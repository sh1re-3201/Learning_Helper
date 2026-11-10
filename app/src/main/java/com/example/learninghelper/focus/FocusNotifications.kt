package com.example.learninghelper.focus

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.learninghelper.MainActivity
import com.example.learninghelper.R
import com.example.learninghelper.data.FocusRepository

object FocusNotifications {
    const val CHANNEL_SESSION = "focus_session"
    const val CHANNEL_EVENTS = "focus_events"
    const val CHANNEL_ALARMS = "focus_alarms"
    const val ID_ONGOING = 1001
    const val ID_COMPLETE = 1002
    const val ID_PRE_SESSION = 1003

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SESSION, "Active Focus Session", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows the ongoing focus session and countdown"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_EVENTS, "Focus Events", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Session completion and status updates"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALARMS, "Pre-Session Warnings", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Audible alarms and countdown warnings before a scheduled session starts"
                enableVibration(true)
            }
        )
    }

    fun ongoing(context: Context, session: FocusSession, effNow: Long = FocusRepository.effectiveNow()): Notification {
        val phase = session.currentPhase(effNow)
        val phaseEnd = session.phaseEndAt(effNow)

        val deskClockIntent = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_DESK_CLOCK)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val silenceIntent = PendingIntent.getBroadcast(
            context,
            106,
            Intent(context, AlarmReceiver::class.java).apply {
                action = AlarmReceiver.ACTION_SILENCE_ALARM
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_SESSION)
            .setSmallIcon(R.drawable.ic_stat_focus)
            .setWhen(phaseEnd)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent(context))

        if (phase == SessionPhase.BREAK) {
            builder.setContentTitle("Break Time")
            builder.setContentText("Apps unlocked. Relax until next work block.")
        } else {
            builder.setContentTitle("Focus Mode Active")
            builder.setContentText("Locked in. Only allowed apps and calls work.")
        }

        if (AlarmPlayer.isPlaying()) {
            builder.addAction(R.drawable.ic_stat_focus, "Silence Alarm", silenceIntent)
        }
        builder.addAction(R.drawable.ic_stat_focus, "Desk Clock (AOD)", deskClockIntent)

        return builder.build()
    }

    fun showComplete(context: Context, session: FocusSession) {
        if (!canPost(context)) return
        val minutes = session.durationMillis / 60_000L
        val notification = NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_stat_focus)
            .setContentTitle("Session Complete")
            .setContentText("Great job! You stayed focused for $minutes minutes.")
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        NotificationManagerCompat.from(context).notify(ID_COMPLETE, notification)
    }

    fun canPost(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
