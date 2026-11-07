package com.example.learninghelper.focus

import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Chronometer
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.learninghelper.data.FocusRepository

class FocusService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var chronometer: Chronometer? = null
    private var statusTextView: TextView? = null
    private var isScreenOn = true
    private var didVibrateWarning = false
    private var lastObservedPhase: SessionPhase? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    chronometer?.stop()
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    updateBreakOverlayChronometer()
                }
            }
        }
    }

    private val checkRunnable = Runnable { checkSession() }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        FocusRepository.init(this)
        val session = FocusRepository.activeSession()
        if (session == null) {
            FocusRepository.clearExpiredSession()
            removeBreakOverlay()
            stopSelf()
            return START_NOT_STICKY
        }

        FocusNotifications.ensureChannels(this)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }

        val effNow = FocusRepository.effectiveNow()
        try {
            ServiceCompat.startForeground(
                this,
                FocusNotifications.ID_ONGOING,
                FocusNotifications.ongoing(this, session, effNow),
                type,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not enter foreground", e)
        }

        val forceBreakAlert = intent?.action == ACTION_TRIGGER_BREAK_ALERT
        evaluatePhase(session, forceBreakAlert = forceBreakAlert)
        return START_STICKY
    }

    private fun evaluatePhase(session: FocusSession, forceBreakAlert: Boolean = false) {
        val now = System.currentTimeMillis()
        val effNow = FocusRepository.effectiveNow(now)
        val phase = session.currentPhase(effNow)

        val isEnteringBreak = forceBreakAlert || (lastObservedPhase == SessionPhase.WORK && phase == SessionPhase.BREAK)

        if (isEnteringBreak) {
            AlarmPlayer.play(this, FocusRepository.alarmSoundUri.value, timeoutMs = 15_000L)
            triggerBreakArrivalVibration()
        } else if (lastObservedPhase == SessionPhase.BREAK && phase == SessionPhase.WORK) {
            AlarmPlayer.stop()
            handler.postDelayed({ FocusNotificationListenerService.sweepDistractions() }, 150L)
        }
        lastObservedPhase = phase

        if (phase == SessionPhase.BREAK) {
            showOrUpdateBreakOverlay(session, effNow)
        } else {
            removeBreakOverlay()
            FocusNotificationListenerService.sweepDistractions()
            BlockerAccessibilityService.enforceCurrentWindows()
        }

        // Keep ongoing notification updated with current phase, chronometer base, and alarm action
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(FocusNotifications.ID_ONGOING, FocusNotifications.ongoing(this, session, effNow))

        // Ensure AlarmManager has armed the exact next wakeup alarm across Doze mode
        AlarmScheduler.scheduleNext(this)

        val remainingPhaseMs = session.phaseRemainingMillis(effNow)
        val nextCheckDelay = if (phase == SessionPhase.BREAK && remainingPhaseMs > 10_000L) {
            remainingPhaseMs - 10_000L
        } else {
            remainingPhaseMs
        }
        scheduleCheck(nextCheckDelay)
    }

    private fun scheduleCheck(delayMs: Long) {
        handler.removeCallbacks(checkRunnable)
        handler.postDelayed(checkRunnable, delayMs.coerceIn(1_000L, 60_000L))
    }

    private fun checkSession() {
        val now = System.currentTimeMillis()
        val session = FocusRepository.activeSession(now)
        if (session != null) {
            evaluatePhase(session)
        } else {
            val completed = FocusRepository.clearExpiredSession(now)
            if (completed != null) {
                FocusNotifications.showComplete(this, completed)
            }
            removeBreakOverlay()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun showOrUpdateBreakOverlay(session: FocusSession, effNow: Long) {
        if (!Permissions.canDrawOverlays(this)) return

        if (overlayView == null) {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val density = resources.displayMetrics.density

            val container = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val padH = (12 * density).toInt()
                val padV = (6 * density).toInt()
                setPadding(padH, padV, padH, padV)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#B30F172A")) // Translucent 70% slate
                    cornerRadius = 20 * density
                    setStroke((1 * density).toInt(), Color.parseColor("#475569"))
                }
            }

            val icon = TextView(this).apply {
                text = "☕ "
                textSize = 14f
                setTextColor(Color.WHITE)
            }
            container.addView(icon)
            statusTextView = icon

            val chrono = Chronometer(this).apply {
                textSize = 14f
                setTextColor(Color.WHITE)
                isCountDown = true
            }
            container.addView(chrono)
            chronometer = chrono

            val layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                x = (16 * density).toInt()
                y = (64 * density).toInt()
            }

            try {
                windowManager?.addView(container, layoutParams)
                overlayView = container
            } catch (e: Exception) {
                Log.w(TAG, "Could not add break overlay", e)
            }
        }

        updateBreakOverlayChronometer()
    }

    private fun updateBreakOverlayChronometer() {
        val session = FocusRepository.activeSession() ?: return
        val now = System.currentTimeMillis()
        val effNow = FocusRepository.effectiveNow(now)
        val remainingMs = session.phaseRemainingMillis(effNow)

        val isTenSecWarning = remainingMs in 1L..10_000L
        if (remainingMs > 10_000L) {
            didVibrateWarning = false
        }
        val density = resources.displayMetrics.density
        (overlayView as? LinearLayout)?.background = GradientDrawable().apply {
            setColor(if (isTenSecWarning) Color.parseColor("#CCB45309") else Color.parseColor("#B30F172A"))
            cornerRadius = 20 * density
            setStroke((1 * density).toInt(), if (isTenSecWarning) Color.parseColor("#F59E0B") else Color.parseColor("#475569"))
        }

        statusTextView?.text = if (isTenSecWarning) "🔒 Locking in " else "☕ "

        if (isTenSecWarning && !didVibrateWarning) {
            didVibrateWarning = true
            triggerVibration()
        }

        chronometer?.apply {
            base = SystemClock.elapsedRealtime() + remainingMs
            if (isScreenOn) start()
        }
    }

    private fun triggerVibration() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    android.os.VibrationEffect.createOneShot(300L, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(android.os.VibrationEffect.createOneShot(300L, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(300L)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not trigger vibration pulse", e)
        }
    }

    private fun triggerBreakArrivalVibration() {
        try {
            val pattern = longArrayOf(0, 400, 200, 400, 200, 400)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                vm?.defaultVibrator?.vibrate(
                    android.os.VibrationEffect.createWaveform(pattern, -1)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(pattern, -1)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not trigger break arrival vibration", e)
        }
    }

    private fun removeBreakOverlay() {
        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (ignored: Exception) {}
        }
        overlayView = null
        chronometer = null
        statusTextView = null
        didVibrateWarning = false
    }

    override fun onDestroy() {
        unregisterReceiver(screenReceiver)
        handler.removeCallbacks(checkRunnable)
        AlarmPlayer.stop()
        removeBreakOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "FocusService"
        const val ACTION_EVALUATE_PHASE = "com.example.learninghelper.EVALUATE_PHASE"
        const val ACTION_TRIGGER_BREAK_ALERT = "com.example.learninghelper.TRIGGER_BREAK_ALERT"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, FocusService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Could not start FocusService", e)
            }
        }

        fun evaluateCurrentPhase(context: Context) {
            try {
                val intent = Intent(context, FocusService::class.java).apply {
                    action = ACTION_EVALUATE_PHASE
                }
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.w(TAG, "Could not start FocusService for evaluateCurrentPhase", e)
            }
        }

        fun triggerBreakAlert(context: Context) {
            try {
                val intent = Intent(context, FocusService::class.java).apply {
                    action = ACTION_TRIGGER_BREAK_ALERT
                }
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.w(TAG, "Could not start FocusService for triggerBreakAlert", e)
            }
        }

        fun updateOngoingNotification(context: Context) {
            FocusRepository.init(context)
            val session = FocusRepository.activeSession() ?: return
            val nm = context.getSystemService(NotificationManager::class.java)
            nm?.notify(FocusNotifications.ID_ONGOING, FocusNotifications.ongoing(context, session))
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, FocusService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Could not stop FocusService", e)
            }
        }
    }
}
