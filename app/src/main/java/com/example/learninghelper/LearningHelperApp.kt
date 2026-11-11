package com.example.learninghelper

import android.app.Application
import com.example.learninghelper.data.FocusRepository
import com.example.learninghelper.focus.FocusNotificationListenerService
import com.example.learninghelper.focus.FocusNotifications

class LearningHelperApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FocusRepository.init(this)
        FocusNotifications.ensureChannels(this)
        FocusNotificationListenerService.resetInterruptionFilterFailsafe(this)
    }
}
