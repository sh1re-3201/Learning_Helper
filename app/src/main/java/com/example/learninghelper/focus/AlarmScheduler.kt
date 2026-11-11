package com.example.learninghelper.focus

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.learninghelper.PreSessionActivity
import com.example.learninghelper.R
import com.example.learninghelper.data.FocusRepository

object AlarmPlayer {
    private var mediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoStopRunnable = Runnable { stop() }
    private var audioFocusRequest: AudioFocusRequest? = null
    private var appContext: Context? = null

    @Synchronized
    fun play(context: Context, soundUriString: String?, timeoutMs: Long = 45_000L) {
        stop()

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager?.mode == AudioManager.MODE_IN_CALL ||
            audioManager?.mode == AudioManager.MODE_IN_COMMUNICATION ||
            audioManager?.mode == AudioManager.MODE_RINGTONE
        ) {
            Log.i("AlarmPlayer", "Call active or ringing; suppressing audible alarm.")
            return
        }
        appContext = context.applicationContext

        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LearningHelper:AlarmWakeLock")?.apply {
                acquire(timeoutMs)
            }

            handler.postDelayed(autoStopRunnable, timeoutMs)

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(audioAttributes)
                    .build()
                audioFocusRequest = focusRequest
                audioManager?.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager?.requestAudioFocus(null, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            }

            val soundUri = if (!soundUriString.isNullOrBlank()) {
                Uri.parse(soundUriString)
            } else {
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            }

            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, soundUri)
                setAudioAttributes(audioAttributes)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.w("AlarmPlayer", "Failed to start alarm sound playback", e)
            stop()
        }
    }

    fun isPlaying(): Boolean = mediaPlayer?.isPlaying == true

    @Synchronized
    fun stop() {
        handler.removeCallbacks(autoStopRunnable)
        try {
            val am = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am?.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am?.abandonAudioFocus(null)
            }
        } catch (ignored: Exception) {}
        audioFocusRequest = null

        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (ignored: Exception) {}
        mediaPlayer = null

        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (ignored: Exception) {}
        wakeLock = null
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        FocusRepository.init(context)
        val action = intent.action ?: return
        val occurrenceId = intent.getStringExtra(EXTRA_OCCURRENCE_ID) ?: ""
        val startMillis = intent.getLongExtra(EXTRA_START_MILLIS, 0L)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Scheduled Focus"
        val durationMin = intent.getIntExtra(EXTRA_DURATION_MIN, 50)
        val workMin = intent.getIntExtra(EXTRA_WORK_MIN, 50)
        val breakMin = intent.getIntExtra(EXTRA_BREAK_MIN, 0)
        val hasLongBreak = intent.getBooleanExtra(EXTRA_HAS_LONG_BREAK, false)

        when (action) {
            ACTION_SILENCE_ALARM -> {
                AlarmPlayer.stop()
                val nm = context.getSystemService(NotificationManager::class.java)
                nm.cancel(FocusNotifications.ID_PRE_SESSION)
                FocusService.updateOngoingNotification(context)
            }
            ACTION_PHASE_BOUNDARY -> {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                val wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LearningHelper:PhaseBoundaryWakeLock")
                wakeLock?.acquire(10_000L)
                try {
                    val activeSession = FocusRepository.activeSession()
                    if (activeSession != null) {
                        FocusService.evaluateCurrentPhase(context)
                    } else {
                        val completed = FocusRepository.clearExpiredSession()
                        if (completed != null) {
                            FocusNotifications.showComplete(context, completed)
                        }
                        FocusService.stop(context)
                    }
                    AlarmScheduler.scheduleNext(context)
                } catch (e: Exception) {
                    Log.w("AlarmReceiver", "Could not evaluate phase boundary", e)
                } finally {
                    try {
                        if (wakeLock?.isHeld == true) wakeLock.release()
                    } catch (e: Exception) {
                        Log.w("AlarmReceiver", "Error releasing wakelock", e)
                    }
                }
            }
            ACTION_PRE_5M -> {
                if (FocusRepository.isFocusActive()) {
                    FocusRepository.recordMilestoneFired("$occurrenceId:PRE_5M")
                    AlarmScheduler.scheduleNext(context)
                    return
                }
                FocusRepository.recordMilestoneFired("$occurrenceId:PRE_5M")
                AlarmPlayer.play(context, FocusRepository.alarmSoundUri.value)

                // Post T-5m warning notification with Silence and Skip options
                FocusNotifications.ensureChannels(context)
                val preIntent = PreSessionActivity.createIntent(
                    context, occurrenceId, title, startMillis, durationMin, workMin, breakMin, initialShowSkip = true, hasLongBreak = hasLongBreak
                )
                val pendingPre = PendingIntent.getActivity(
                    context, 101, preIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val silenceIntent = Intent(context, AlarmReceiver::class.java).apply {
                    this.action = ACTION_SILENCE_ALARM
                }
                val pendingSilence = PendingIntent.getBroadcast(
                    context, 102, silenceIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val notif = NotificationCompat.Builder(context, FocusNotifications.CHANNEL_ALARMS)
                    .setSmallIcon(R.drawable.ic_stat_focus)
                    .setContentTitle("Focus Starts in 5 Minutes")
                    .setContentText("$title ($durationMin min) begins soon.")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(pendingPre)
                    .addAction(R.drawable.ic_stat_focus, "Silence", pendingSilence)
                    .addAction(R.drawable.ic_stat_focus, "Skip (20w reason)", pendingPre)
                    .build()

                if (FocusNotifications.canPost(context)) {
                    try {
                        val nm = context.getSystemService(NotificationManager::class.java)
                        nm.notify(FocusNotifications.ID_PRE_SESSION, notif)
                    } catch (e: SecurityException) {
                        Log.w("AlarmReceiver", "Notification permission missing", e)
                    }
                }

                ForegroundAppMonitor.start(context)
                AlarmScheduler.scheduleNext(context)
            }
            ACTION_PRE_2M -> {
                if (FocusRepository.isFocusActive()) {
                    FocusRepository.recordMilestoneFired("$occurrenceId:PRE_2M")
                    AlarmScheduler.scheduleNext(context)
                    return
                }
                FocusRepository.recordMilestoneFired("$occurrenceId:PRE_2M")
                AlarmPlayer.play(context, FocusRepository.alarmSoundUri.value)

                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                val inCall = audioManager?.mode == AudioManager.MODE_IN_CALL ||
                    audioManager?.mode == AudioManager.MODE_IN_COMMUNICATION ||
                    audioManager?.mode == AudioManager.MODE_RINGTONE
                if (!inCall) {
                    val preIntent = PreSessionActivity.createIntent(
                        context, occurrenceId, title, startMillis, durationMin, workMin, breakMin, hasLongBreak = hasLongBreak
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    val pendingPre = PendingIntent.getActivity(
                        context,
                        101,
                        preIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    // Full-screen intent notification compliant with Android 14 BAL over Keyguard
                    if (FocusNotifications.canPost(context)) {
                        val notif = NotificationCompat.Builder(context, FocusNotifications.CHANNEL_ALARMS)
                            .setSmallIcon(R.drawable.ic_stat_focus)
                            .setContentTitle("Focus Session Starting in 2 Minutes")
                            .setContentText("$title begins at scheduled time.")
                            .setPriority(NotificationCompat.PRIORITY_MAX)
                            .setCategory(NotificationCompat.CATEGORY_ALARM)
                            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                            .setFullScreenIntent(pendingPre, true)
                            .setAutoCancel(true)
                            .setContentIntent(pendingPre)
                            .build()
                        runCatching {
                            context.getSystemService(NotificationManager::class.java).notify(FocusNotifications.ID_PRE_SESSION, notif)
                        }
                    }
                    try {
                        context.startActivity(preIntent)
                    } catch (e: Exception) {
                        Log.w("AlarmReceiver", "Direct startActivity deferred to full-screen intent", e)
                    }
                } else {
                    // Call in progress or ringing: update the pre-session notification in notification tray
                    if (FocusNotifications.canPost(context)) {
                        val pendingPre = PendingIntent.getActivity(
                            context,
                            101,
                            PreSessionActivity.createIntent(context, occurrenceId, title, startMillis, durationMin, workMin, breakMin, hasLongBreak = hasLongBreak),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        )
                        val notif = NotificationCompat.Builder(context, FocusNotifications.CHANNEL_ALARMS)
                            .setSmallIcon(R.drawable.ic_stat_focus)
                            .setContentTitle("Focus session starts in 2 minutes")
                            .setContentText("Focus lock begins at scheduled time. Tap to open preparation screen.")
                            .setPriority(NotificationCompat.PRIORITY_HIGH)
                            .setAutoCancel(true)
                            .setContentIntent(pendingPre)
                            .build()
                        runCatching {
                            context.getSystemService(NotificationManager::class.java).notify(FocusNotifications.ID_PRE_SESSION, notif)
                        }
                    }
                }
                ForegroundAppMonitor.start(context)
                AlarmScheduler.scheduleNext(context)
            }
            ACTION_START -> {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                val wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LearningHelper:SessionStartWakeLock")
                wakeLock?.acquire(10_000L)
                try {
                    AlarmPlayer.stop()
                    val nm = context.getSystemService(NotificationManager::class.java)
                    nm.cancel(FocusNotifications.ID_PRE_SESSION)

                    FocusRepository.recordMilestoneFired("$occurrenceId:START")
                    if (occurrenceId !in FocusRepository.skippedOccurrences.value) {
                        FocusRepository.startSession(durationMin, workMin, breakMin, hasLongBreak = hasLongBreak)
                        FocusService.start(context)
                        ForegroundAppMonitor.enforceCurrentWindows(context)
                    }
                    AlarmScheduler.scheduleNext(context)
                } finally {
                    try {
                        if (wakeLock?.isHeld == true) {
                            wakeLock.release()
                        }
                    } catch (e: Exception) {
                        Log.w("AlarmReceiver", "Error releasing wakelock", e)
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_PRE_5M = "com.example.learninghelper.PRE_5M"
        const val ACTION_PRE_2M = "com.example.learninghelper.PRE_2M"
        const val ACTION_START = "com.example.learninghelper.START"
        const val ACTION_SILENCE_ALARM = "com.example.learninghelper.SILENCE_ALARM"
        const val ACTION_PHASE_BOUNDARY = "com.example.learninghelper.PHASE_BOUNDARY"

        const val EXTRA_OCCURRENCE_ID = "occurrence_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_START_MILLIS = "start_millis"
        const val EXTRA_DURATION_MIN = "duration_min"
        const val EXTRA_WORK_MIN = "work_min"
        const val EXTRA_BREAK_MIN = "break_min"
        const val EXTRA_HAS_LONG_BREAK = "has_long_break"
    }
}
