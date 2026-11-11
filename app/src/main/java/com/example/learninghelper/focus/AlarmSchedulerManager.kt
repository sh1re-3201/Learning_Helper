package com.example.learninghelper.focus

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.example.learninghelper.data.FocusRepository

object AlarmScheduler {

    const val REQUEST_CODE_SCHEDULED = 100
    const val REQUEST_CODE_PHASE_BOUNDARY = 105

    fun scheduleNext(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val now = System.currentTimeMillis()
        FocusRepository.init(context)

        // 1. Schedule exact wakeup alarm for active session phase transition (WORK <-> BREAK / End)
        val activeSession = FocusRepository.activeSession(now)
        val phaseIntent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_PHASE_BOUNDARY
        }
        val phasePending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_PHASE_BOUNDARY,
            phaseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        if (activeSession != null) {
            val effNow = FocusRepository.effectiveNow(now)
            val phaseEndAt = activeSession.phaseEndAt(effNow)
            val remainingMs = (phaseEndAt - effNow).coerceAtLeast(0L)
            if (remainingMs > 0L) {
                val triggerElapsed = SystemClock.elapsedRealtime() + remainingMs
                try {
                    am.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerElapsed,
                        phasePending,
                    )
                } catch (e: SecurityException) {
                    am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerElapsed, phasePending)
                }
            } else {
                am.cancel(phasePending)
                phasePending.cancel()
            }
        } else {
            am.cancel(phasePending)
            phasePending.cancel()
        }

        // 2. Schedule upcoming pre-session / session start milestone
        val nextTrigger = ScheduleCalculator.findNextAlarmTrigger(
            items = FocusRepository.schedules.value,
            skippedOccurrenceIds = FocusRepository.skippedOccurrences.value,
            firedMilestoneKeys = FocusRepository.firedMilestones.value,
            nowMillis = now,
        )

        if (nextTrigger == null) {
            cancelScheduledMilestoneAlarms(context, am)
            return
        }

        val occ = nextTrigger.occurrence
        val action = when (nextTrigger.milestone) {
            ScheduleMilestone.PRE_5M -> AlarmReceiver.ACTION_PRE_5M
            ScheduleMilestone.PRE_2M -> AlarmReceiver.ACTION_PRE_2M
            ScheduleMilestone.START -> AlarmReceiver.ACTION_START
        }

        // Cancel any pending milestone alarms that don't match the current milestone action
        for (act in ALL_SCHEDULED_ACTIONS) {
            if (act != action) {
                val otherIntent = Intent(context, AlarmReceiver::class.java).apply { this.action = act }
                val otherPi = PendingIntent.getBroadcast(
                    context,
                    REQUEST_CODE_SCHEDULED,
                    otherIntent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
                )
                if (otherPi != null) {
                    am.cancel(otherPi)
                    otherPi.cancel()
                }
            }
        }

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            this.action = action
            putExtra(AlarmReceiver.EXTRA_OCCURRENCE_ID, occ.occurrenceId)
            putExtra(AlarmReceiver.EXTRA_TITLE, occ.item.label)
            putExtra(AlarmReceiver.EXTRA_START_MILLIS, occ.startAtMillis)
            putExtra(AlarmReceiver.EXTRA_DURATION_MIN, occ.item.durationMinutes)
            putExtra(AlarmReceiver.EXTRA_WORK_MIN, occ.item.workMinutes)
            putExtra(AlarmReceiver.EXTRA_BREAK_MIN, occ.item.breakMinutes)
            putExtra(AlarmReceiver.EXTRA_HAS_LONG_BREAK, occ.item.hasLongBreak)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_SCHEDULED,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        try {
            am.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                nextTrigger.triggerAtMillis,
                pendingIntent,
            )
        } catch (e: SecurityException) {
            am.set(AlarmManager.RTC_WAKEUP, nextTrigger.triggerAtMillis, pendingIntent)
        }
    }

    private val ALL_SCHEDULED_ACTIONS = listOf(
        AlarmReceiver.ACTION_PRE_5M,
        AlarmReceiver.ACTION_PRE_2M,
        AlarmReceiver.ACTION_START,
    )

    fun cancelScheduledMilestoneAlarms(context: Context, am: AlarmManager? = null) {
        val alarmManager = am ?: context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        for (act in ALL_SCHEDULED_ACTIONS) {
            val intent = Intent(context, AlarmReceiver::class.java).apply { action = act }
            val pi = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_SCHEDULED,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            if (pi != null) {
                alarmManager.cancel(pi)
                pi.cancel()
            }
        }
    }

    fun cancelPhaseAlarm(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val phaseIntent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_PHASE_BOUNDARY
        }
        val phasePending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_PHASE_BOUNDARY,
            phaseIntent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (phasePending != null) {
            am.cancel(phasePending)
            phasePending.cancel()
        }
    }
}
