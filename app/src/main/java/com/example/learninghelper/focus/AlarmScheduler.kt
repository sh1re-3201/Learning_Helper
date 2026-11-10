package com.example.learninghelper.focus

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
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

    @Synchronized
    fun play(context: Context, soundUriString: String?, timeoutMs: Long = 45_000L) {
        stop()

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager?.mode == AudioManager.MODE_IN_CALL || audioManager?.mode == AudioManager.MODE_IN_COMMUNICATION) {
            Log.i("AlarmPlayer", "Call active; suppressing audible alarm.")
            return
        }

        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LearningHelper:AlarmWakeLock")?.apply {
                acquire(timeoutMs)
            }

            handler.postDelayed(autoStopRunnable, timeoutMs)

            val soundUri = if (!soundUriString.isNullOrBlank()) {
                Uri.parse(soundUriString)
            } else {
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            }

            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, soundUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
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
                wakeLock?.acquire(15_000L)
                try {
                    FocusService.evaluateCurrentPhase(context)
                } finally {
                    runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
                }
                AlarmScheduler.scheduleNext(context)
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

                val nm = context.getSystemService(NotificationManager::class.java)
                nm.notify(FocusNotifications.ID_PRE_SESSION, notif)

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
                val inCall = audioManager?.mode == AudioManager.MODE_IN_CALL || audioManager?.mode == AudioManager.MODE_IN_COMMUNICATION
                if (!inCall) {
                    val preIntent = PreSessionActivity.createIntent(
                        context, occurrenceId, title, startMillis, durationMin, workMin, breakMin, hasLongBreak = hasLongBreak
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    context.startActivity(preIntent)
                }
                AlarmScheduler.scheduleNext(context)
            }
            ACTION_START -> {
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
