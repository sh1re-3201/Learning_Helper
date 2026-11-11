package com.example.learninghelper.focus

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.app.Notification
import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.example.learninghelper.data.FocusRepository
import java.lang.ref.WeakReference

/**
 * Service that suppresses all distraction notifications during WORK phase,
 * while leaving calls (telephony/VoIP) and LearningHelper's own notifications intact.
 */
class FocusNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = WeakReference(this)
        sweepIfWork()
    }

    override fun onListenerDisconnected() {
        if (instance?.get() == this) {
            instance = null
        }
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn ?: return
        val now = System.currentTimeMillis()
        val session = FocusRepository.activeSession(now)
        val inDeskClock = FocusRepository.isDeskClockActive.value
        val isWork = session != null && session.currentPhase(FocusRepository.effectiveNow(now)) == SessionPhase.WORK

        if (!isWork && !inDeskClock) return

        if (isNotificationAllowed(sbn, inDeskClock)) return

        if (!sbn.isClearable) return

        try {
            cancelNotification(sbn.key)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel notification: ${sbn.packageName}", e)
        }
    }

    fun sweepDistractions() {
        val now = System.currentTimeMillis()
        val session = FocusRepository.activeSession(now)
        val inDeskClock = FocusRepository.isDeskClockActive.value
        val isWork = session != null && session.currentPhase(FocusRepository.effectiveNow(now)) == SessionPhase.WORK
        if (!isWork && !inDeskClock) return

        try {
            val active = activeNotifications ?: return
            for (sbn in active) {
                if (!isNotificationAllowed(sbn, inDeskClock) && sbn.isClearable) {
                    try {
                        cancelNotification(sbn.key)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to sweep notification: ${sbn.packageName}", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to inspect active notifications", e)
        }
    }

    private fun sweepIfWork() {
        val now = System.currentTimeMillis()
        val session = FocusRepository.activeSession(now)
        val inDeskClock = FocusRepository.isDeskClockActive.value
        if (inDeskClock || (session != null && session.currentPhase(FocusRepository.effectiveNow(now)) == SessionPhase.WORK)) {
            sweepDistractions()
        }
    }

    private fun isNotificationAllowed(sbn: StatusBarNotification, inDeskClock: Boolean = FocusRepository.isDeskClockActive.value): Boolean {
        val pkg = sbn.packageName ?: return true
        if (pkg == packageName) return true

        val notif = sbn.notification ?: return true

        // Telephony and in-call packages or any VoIP call notification (WhatsApp, Zoom, Teams, etc.)
        if (pkg in CALL_PACKAGES) return true
        if (notif.category == Notification.CATEGORY_CALL) return true
        if (pkg == FocusRepository.grandfatheredCallPackage.value) return true

        // Alarm category for clock alarms
        if (notif.category == Notification.CATEGORY_ALARM && (pkg == "com.sec.android.app.clockpackage" || pkg == "com.google.android.deskclock")) {
            return true
        }

        // All distraction notifications during WORK phase or Desk Clock are dismissed; no allowlist bypass
        return false
    }

    companion object {
        private const val TAG = "FocusNotificationListener"

        @Volatile
        private var instance: WeakReference<FocusNotificationListenerService>? = null

        fun sweepDistractions() {
            instance?.get()?.sweepDistractions()
        }

        fun findActiveCallPackage(): String? {
            val service = instance?.get() ?: return null
            try {
                val active = service.activeNotifications ?: return null
                for (sbn in active) {
                    val notif = sbn.notification ?: continue
                    if (notif.category == Notification.CATEGORY_CALL) {
                        return sbn.packageName
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to inspect active call notifications", e)
            }
            return null
        }

        fun setInterruptionFilter(filter: Int) {
            val service = instance?.get() ?: return
            try {
                service.requestInterruptionFilter(filter)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set interruption filter", e)
            }
        }

        fun resetInterruptionFilterFailsafe(context: Context) {
            if (!FocusRepository.isDeskClockActive.value) {
                setInterruptionFilter(NotificationListenerService.INTERRUPTION_FILTER_ALL)
                val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                runCatching { am?.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0) }
            }
        }

        private val CALL_PACKAGES = setOf(
            "com.android.server.telecom",
            "com.android.phone",
            "com.android.dialer",
            "com.google.android.dialer",
            "com.android.incallui",
            "com.samsung.android.dialer",
            "com.samsung.android.incallui",
            "com.samsung.android.app.telephonyui",
        )
    }
}
