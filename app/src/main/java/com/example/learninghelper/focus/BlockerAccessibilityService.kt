package com.example.learninghelper.focus

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.example.learninghelper.BlockActivity
import com.example.learninghelper.data.FocusRepository

class BlockerAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var alwaysAllowed: Set<String> = emptySet()
    private var lastAlwaysAllowedRefresh = 0L
    private var lastBlockAtElapsed = 0L
    private var lastObservedPhase: SessionPhase? = null
    private var workPhaseGrandfatherChecked = false
    private var lastCallNormalElapsed = 0L
    private var lastAllowedPackage: String? = null
    private var postCallRunnableScheduled = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        FocusRepository.init(this)
        refreshAlwaysAllowed()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (instance == this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return

        val packageName = event.packageName?.toString()
        evaluateAndEnforce(packageName)
    }

    private fun evaluateAndEnforce(activeEventPkg: String?) {
        val wallNow = System.currentTimeMillis()
        val effNow = FocusRepository.effectiveNow(wallNow)
        val session = FocusRepository.activeSession(wallNow)
        val inPreSession = ScheduleCalculator.isInPreSessionLockWindow(
            items = FocusRepository.schedules.value,
            skippedOccurrenceIds = FocusRepository.skippedOccurrences.value,
            nowMillis = wallNow,
        )

        if (session == null && !inPreSession) return

        val elapsed = SystemClock.elapsedRealtime()
        if (elapsed - lastAlwaysAllowedRefresh > 60_000L) {
            refreshAlwaysAllowed()
        }

        // Multi-Window / Split-Screen / Pop-Up View Inspection:
        // Check all interactive application windows on screen, plus activeEventPkg and rootInActiveWindow
        val candidatePackages = mutableSetOf<String>()
        if (!activeEventPkg.isNullOrBlank()) candidatePackages.add(activeEventPkg)
        rootInActiveWindow?.packageName?.toString()?.let { candidatePackages.add(it) }

        try {
            val windowList = windows
            for (window in windowList) {
                if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    val root = window.root
                    val pkg = root?.packageName?.toString()
                    if (!pkg.isNullOrBlank()) {
                        candidatePackages.add(pkg)
                    }
                }
            }
        } catch (ignored: Exception) {}

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
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                val inCall = audioManager?.mode == AudioManager.MODE_IN_CALL || audioManager?.mode == AudioManager.MODE_IN_COMMUNICATION

                if (!workPhaseGrandfatherChecked) {
                    workPhaseGrandfatherChecked = true
                    if (inCall) {
                        val activePkg = FocusNotificationListenerService.findActiveCallPackage()
                            ?: candidatePackages.firstOrNull { it !in alwaysAllowed && it !in AppCatalog.ALWAYS_BLOCKED_DURING_SESSION && it !in FocusRepository.allowlist.value }
                            ?: rootInActiveWindow?.packageName?.toString()
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
                                handler.postDelayed({
                                    postCallRunnableScheduled = false
                                    val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                                    val stillInCall = am?.mode == AudioManager.MODE_IN_CALL || am?.mode == AudioManager.MODE_IN_COMMUNICATION
                                    if (!stillInCall) {
                                        FocusRepository.setGrandfatheredCallPackage(null)
                                        lastCallNormalElapsed = 0L
                                        lastAllowedPackage = null
                                        enforceCurrentWindows()
                                    }
                                }, 3_000L)
                            }
                        } else if (elapsed - lastCallNormalElapsed >= 3_000L) {
                            // Call ended for >= 3 seconds: revoke grandfathered package immediately!
                            FocusRepository.setGrandfatheredCallPackage(null)
                            lastCallNormalElapsed = 0L
                            lastAllowedPackage = null
                        }
                    } else {
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
                performGlobalAction(GLOBAL_ACTION_HOME)
                if (elapsed - lastBlockAtElapsed > DEBOUNCE_MS) {
                    lastBlockAtElapsed = elapsed
                    startActivity(BlockActivity.createIntent(this, pkg))
                }
                break
            } else {
                lastAllowedPackage = pkg
            }
        }
    }

    fun enforceCurrentWindows() {
        val rootPkg = rootInActiveWindow?.packageName?.toString()
        evaluateAndEnforce(rootPkg)
    }

    override fun onInterrupt() = Unit

    private fun refreshAlwaysAllowed() {
        alwaysAllowed = AppCatalog.alwaysAllowedPackages(this)
        lastAlwaysAllowedRefresh = SystemClock.elapsedRealtime()
    }

    companion object {
        private const val DEBOUNCE_MS = 400L

        @Volatile
        var instance: BlockerAccessibilityService? = null
            private set

        fun enforceCurrentWindows() {
            instance?.enforceCurrentWindows()
        }
    }
}
