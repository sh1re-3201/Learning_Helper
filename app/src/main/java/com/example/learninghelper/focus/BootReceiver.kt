package com.example.learninghelper.focus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.learninghelper.data.FocusRepository

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        FocusRepository.init(context)
        FocusNotificationListenerService.resetInterruptionFilterFailsafe(context)
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                FocusRepository.reconcileReboot()
                if (FocusRepository.isFocusActive()) {
                    FocusService.start(context)
                } else {
                    FocusRepository.clearExpiredSession()
                }
                AlarmScheduler.scheduleNext(context)
            }
        }
    }
}
