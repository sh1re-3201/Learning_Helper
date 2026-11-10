package com.example.learninghelper.focus

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import com.example.learninghelper.BlockActivity
import com.example.learninghelper.data.FocusRepository

/**
 * Battery-optimized foreground app monitor replacing the legacy AccessibilityService.
 * Uses official Android UsageStatsManager (PACKAGE_USAGE_STATS) to detect running applications.
 *
 * Runs on a dedicated background HandlerThread to prevent UI thread jank.
 * Screen-off CPU usage is strictly 0%.
 */
object ForegroundAppMonitor {

    private const val TAG = "ForegroundAppMonitor"
    private const val CHECK_INTERVAL_MS = 400L
    private const val DEBOUNCE_MS = 400L
    private const val LOOKBACK_SWEEP_MS = 15 * 60_000L

    private val monitorThread = HandlerThread("ForegroundAppMonitorThread").apply { start() }
    private val backgroundHandler = Handler(monitorThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var isRunning = false
    @Volatile
    private var isScreenOn = true
    private var cachedContext: Context? = null

    private var alwaysAllowed: Set<String> = emptySet()
    private var lastAlwaysAllowedRefresh = 0L
    private var lastAllowedPackage: String? = null
    private var lastKnownForegroundPackage: String? = null
    private var lastBlockAtElapsed = 0L
    private var lastObservedPhase: SessionPhase? = null
    private var workPhaseGrandfatherChecked = false
    private var lastCallNormalElapsed = 0L
    private var postCallRunnableScheduled = false

    private val postCallLockoutRunnable = Runnable {
        postCallRunnableScheduled = false
        val ctx = cachedContext ?: return@Runnable
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val stillInCall = am?.mode == AudioManager.MODE_IN_CALL || am?.mode == AudioManager.MODE_IN_COMMUNICATION
        if (!stillInCall) {
            FocusRepository.setGrandfatheredCallPackage(null)
            lastCallNormalElapsed = 0L
            lastAllowedPackage = null
            lastKnownForegroundPackage = null
            enforceCurrentWindows(ctx)
        }
    }

    private val checkRunnable = object : Runnable {
        override fun run() {
            val ctx = cachedContext
            if (isRunning && isScreenOn && ctx != null) {
                checkForeground(ctx, isImmediateSweep = false)
                if (isRunning && isScreenOn) {
                    backgroundHandler.postDelayed(this, CHECK_INTERVAL_MS)
                }
            }
        }
    }

    @Synchronized
    fun start(context: Context) {
        cachedContext = context.applicationContext
        refreshAlwaysAllowed(context)
        if (!isRunning) {
            isRunning = true
            backgroundHandler.removeCallbacks(checkRunnable)
            if (isScreenOn) {
                backgroundHandler.post {
                    checkForeground(context, isImmediateSweep = true)
                    if (isRunning && isScreenOn) {
                        backgroundHandler.postDelayed(checkRunnable, CHECK_INTERVAL_MS)
                    }
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        isRunning = false
        backgroundHandler.removeCallbacks(checkRunnable)
        backgroundHandler.removeCallbacks(postCallLockoutRunnable)
        postCallRunnableScheduled = false
        lastAllowedPackage = null
        lastKnownForegroundPackage = null
        lastObservedPhase = null
        workPhaseGrandfatherChecked = false
        lastCallNormalElapsed = 0L
    }

    @Synchronized
    fun onScreenOff() {
        isScreenOn = false
        backgroundHandler.removeCallbacks(checkRunnable)
    }

    @Synchronized
    fun onScreenOn(context: Context) {
        isScreenOn = true
        cachedContext = context.applicationContext
        if (isRunning) {
            backgroundHandler.removeCallbacks(checkRunnable)
            backgroundHandler.post {
                checkForeground(context, isImmediateSweep = false)
                if (isRunning && isScreenOn) {
                    backgroundHandler.postDelayed(checkRunnable, CHECK_INTERVAL_MS)
                }
            }
        }
    }

    fun enforceCurrentWindows(context: Context? = null) {
        val ctx = context?.applicationContext ?: cachedContext ?: return
        lastKnownForegroundPackage = null
        backgroundHandler.post {
            checkForeground(ctx, isImmediateSweep = true)
        }
    }

    private fun checkForeground(context: Context, isImmediateSweep: Boolean) {
        if (!Permissions.hasUsageStatsPermission(context)) {
            return
        }

        val wallNow = System.currentTimeMillis()
        val effNow = FocusRepository.effectiveNow(wallNow)
        val session = FocusRepository.activeSession(wallNow)
        val inPreSession = ScheduleCalculator.isInPreSessionLockWindow(
            items = FocusRepository.schedules.value,
            skippedOccurrenceIds = FocusRepository.skippedOccurrences.value,
            nowMillis = wallNow,
        )

        if (session == null && !inPreSession) {
            stop()
            return
        }

        val elapsed = SystemClock.elapsedRealtime()
        if (elapsed - lastAlwaysAllowedRefresh > 60_000L) {
            refreshAlwaysAllowed(context)
        }

        val candidatePackages = detectForegroundPackages(context, isImmediateSweep, wallNow)

        if (session != null && session.isActive(effNow)) {
            val curPhase = session.currentPhase(effNow)
            if (curPhase != lastObservedPhase) {
                lastObservedPhase = curPhase
                if (curPhase == SessionPhase.WORK) {
                    workPhaseGrandfatherChecked = false
                    lastCallNormalElapsed = 0L
                } else {
                    FocusRepository.setGrandfatheredCallPackage(null)
                }
            }

            if (curPhase == SessionPhase.WORK) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                val inCall = audioManager?.mode == AudioManager.MODE_IN_CALL || audioManager?.mode == AudioManager.MODE_IN_COMMUNICATION

                if (!workPhaseGrandfatherChecked) {
                    workPhaseGrandfatherChecked = true
                    if (inCall) {
                        val activePkg = FocusNotificationListenerService.findActiveCallPackage()
                            ?: candidatePackages.firstOrNull {
                                it !in alwaysAllowed &&
                                    it !in AppCatalog.ALWAYS_BLOCKED_DURING_SESSION &&
                                    it !in FocusRepository.allowlist.value
                            }
                        if (!activePkg.isNullOrBlank()) {
                            FocusRepository.setGrandfatheredCallPackage(activePkg)
                        }
                    } else {
                        FocusRepository.setGrandfatheredCallPackage(null)
                    }
                }

                val currentGrandfathered = FocusRepository.grandfatheredCallPackage.value
                if (!currentGrandfathered.isNullOrBlank()) {
                    if (!inCall) {
                        if (lastCallNormalElapsed == 0L) {
                            lastCallNormalElapsed = elapsed
                            if (!postCallRunnableScheduled) {
                                postCallRunnableScheduled = true
                                backgroundHandler.postDelayed(postCallLockoutRunnable, 3_000L)
                            }
                        } else if (elapsed - lastCallNormalElapsed >= 3_000L) {
                            FocusRepository.setGrandfatheredCallPackage(null)
                            lastCallNormalElapsed = 0L
                            lastAllowedPackage = null
                            lastKnownForegroundPackage = null
                        }
                    } else {
                        if (postCallRunnableScheduled) {
                            postCallRunnableScheduled = false
                            backgroundHandler.removeCallbacks(postCallLockoutRunnable)
                        }
                        lastCallNormalElapsed = 0L
                    }
                }
            }
        } else {
            lastObservedPhase = null
            workPhaseGrandfatherChecked = false
            lastCallNormalElapsed = 0L
            lastAllowedPackage = null
            FocusRepository.setGrandfatheredCallPackage(null)
        }

        val grandfatheredCallPkg = FocusRepository.grandfatheredCallPackage.value

        if (candidatePackages.size == 1 && candidatePackages.first() == lastAllowedPackage && !inPreSession) {
            return
        }

        for (pkg in candidatePackages) {
            val block = FocusPolicy.shouldBlock(
                packageName = pkg,
                session = session,
                now = effNow,
                allowlist = FocusRepository.allowlist.value,
                alwaysAllowed = alwaysAllowed,
                alwaysBlockedDuringSession = AppCatalog.ALWAYS_BLOCKED_DURING_SESSION,
                inPreSessionLockWindow = inPreSession,
                grandfatheredCallPackage = grandfatheredCallPkg,
            )

            if (block) {
                lastAllowedPackage = null
                lastKnownForegroundPackage = null
                if (elapsed - lastBlockAtElapsed > DEBOUNCE_MS) {
                    lastBlockAtElapsed = elapsed
                    mainHandler.post {
                        val intent = BlockActivity.createIntent(context, pkg).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                        context.startActivity(intent)
                    }
                }
                break
            } else {
                lastAllowedPackage = pkg
            }
        }
    }

    private fun detectForegroundPackages(
        context: Context,
        isImmediateSweep: Boolean,
        nowWall: Long,
    ): Set<String> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return emptySet()
        val lookbackMs = if (isImmediateSweep) LOOKBACK_SWEEP_MS else 2_500L
        val startTime = (nowWall - lookbackMs).coerceAtLeast(0L)

        try {
            val events = usm.queryEvents(startTime, nowWall)
            val event = UsageEvents.Event()
            var latestPkg: String? = null
            var latestTime = 0L

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    if (event.timeStamp >= latestTime) {
                        latestTime = event.timeStamp
                        latestPkg = event.packageName
                    }
                }
            }

            if (latestPkg != null) {
                lastKnownForegroundPackage = latestPkg
                return setOf(latestPkg)
            }
        } catch (ignored: Exception) {}

        // Steady-State Optimization: If no new ACTIVITY_RESUMED event occurred in the 2.5s window,
        // the user is stably inside the currently foreground application. Avoid calling queryUsageStats!
        val cached = lastKnownForegroundPackage
        if (cached != null) {
            return setOf(cached)
        }

        // Cold-startup fallback: query usage stats only when lastKnownForegroundPackage is null
        try {
            val stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                nowWall - 15_000L,
                nowWall,
            )
            stats?.maxByOrNull { it.lastTimeUsed }?.packageName?.let {
                lastKnownForegroundPackage = it
                return setOf(it)
            }
        } catch (ignored: Exception) {}

        return emptySet()
    }

    private fun refreshAlwaysAllowed(context: Context) {
        alwaysAllowed = AppCatalog.alwaysAllowedPackages(context)
        lastAlwaysAllowedRefresh = SystemClock.elapsedRealtime()
    }
}
