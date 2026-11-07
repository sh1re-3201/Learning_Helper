package com.example.learninghelper.focus

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class ScheduleType {
    WEEKLY,
    CALENDAR_DATE,
}

data class ScheduledItem(
    val id: String,
    val label: String,
    val type: ScheduleType,
    val daysOfWeek: Set<DayOfWeek> = emptySet(),
    val year: Int = 0,
    val month: Int = 0,
    val dayOfMonth: Int = 0,
    val startHour: Int,
    val startMinute: Int,
    val durationMinutes: Int = 50,
    val workMinutes: Int = 50,
    val breakMinutes: Int = 0,
    val enabled: Boolean = true,
    val endHour: Int = (startHour + (startMinute + durationMinutes) / 60) % 24,
    val endMinute: Int = (startMinute + durationMinutes) % 60,
    val hasLongBreak: Boolean = false,
) {
    init {
        require(startHour in 0..23) { "startHour must be in 0..23" }
        require(startMinute in 0..59) { "startMinute must be in 0..59" }
        require(endHour in 0..23) { "endHour must be in 0..23" }
        require(endMinute in 0..59) { "endMinute must be in 0..59" }
        require(durationMinutes in FocusPolicy.MIN_DURATION_MINUTES..FocusPolicy.MAX_DURATION_MINUTES) {
            "durationMinutes out of range"
        }
    }
}

enum class ScheduleMilestone {
    PRE_5M,
    PRE_2M,
    START,
}

data class UpcomingOccurrence(
    val occurrenceId: String,
    val item: ScheduledItem,
    val startAtMillis: Long,
) {
    val pre5mAtMillis: Long get() = startAtMillis - ScheduleCalculator.PRE_5M_OFFSET_MILLIS
    val pre2mAtMillis: Long get() = startAtMillis - ScheduleCalculator.PRE_2M_OFFSET_MILLIS
    val endAtMillis: Long get() = startAtMillis + item.durationMinutes * 60_000L
}

data class NextAlarmTrigger(
    val occurrence: UpcomingOccurrence,
    val milestone: ScheduleMilestone,
    val triggerAtMillis: Long,
) {
    val milestoneKey: String get() = "${occurrence.occurrenceId}:${milestone.name}"
}

/**
 * Pure Kotlin calculator for weekly recurring schedules and calendar-date schedules.
 * Uses JDK `java.time.*` with injectable [ZoneId] so all edge cases (midnight, week wrap,
 * calendar dates, skipped occurrences, and mid-window rescheduling) are unit-testable on the JVM.
 */
object ScheduleCalculator {

    const val PRE_5M_OFFSET_MILLIS = 5 * 60_000L
    const val PRE_2M_OFFSET_MILLIS = 2 * 60_000L
    private const val LATE_FIRE_TOLERANCE_MILLIS = 20_000L

    private val OCCURRENCE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm", Locale.US)

    fun occurrenceIdFor(date: LocalDate, time: LocalTime): String =
        LocalDateTime.of(date, time).format(OCCURRENCE_FORMATTER)

    /**
     * Finds the earliest enabled, non-skipped occurrence whose `startAtMillis > nowMillis`.
     * If a `CALENDAR_DATE` and `WEEKLY` item fall on the exact same start timestamp, the one
     * with the longer duration wins.
     */
    fun findNextOccurrence(
        items: List<ScheduledItem>,
        skippedOccurrenceIds: Set<String>,
        nowMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): UpcomingOccurrence? {
        val enabledItems = items.filter { it.enabled }
        if (enabledItems.isEmpty()) return null

        val nowZoned = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
        val today = nowZoned.toLocalDate()
        val candidates = mutableListOf<UpcomingOccurrence>()

        for (item in enabledItems) {
            val time = LocalTime.of(item.startHour, item.startMinute)
            when (item.type) {
                ScheduleType.WEEKLY -> {
                    if (item.daysOfWeek.isEmpty()) continue
                    for (dayOffset in 0L..8L) {
                        val candidateDate = today.plusDays(dayOffset)
                        if (candidateDate.dayOfWeek !in item.daysOfWeek) continue
                        val startMillis = candidateDate.atTime(time).atZone(zoneId).toInstant().toEpochMilli()
                        if (startMillis <= nowMillis) continue
                        val occId = occurrenceIdFor(candidateDate, time)
                        if (occId in skippedOccurrenceIds) continue
                        candidates += UpcomingOccurrence(occId, item, startMillis)
                        break
                    }
                }
                ScheduleType.CALENDAR_DATE -> {
                    val date = runCatching { LocalDate.of(item.year, item.month, item.dayOfMonth) }.getOrNull()
                        ?: continue
                    val startMillis = date.atTime(time).atZone(zoneId).toInstant().toEpochMilli()
                    if (startMillis <= nowMillis) continue
                    val occId = occurrenceIdFor(date, time)
                    if (occId in skippedOccurrenceIds) continue
                    candidates += UpcomingOccurrence(occId, item, startMillis)
                }
            }
        }

        return candidates.minWithOrNull(
            compareBy<UpcomingOccurrence> { it.startAtMillis }
                .thenByDescending { it.item.durationMinutes }
        )
    }

    /**
     * Computes the single next alarm trigger (`T-5m`, `T-2m`, or `T-0`) to register with `AlarmManager`.
     *
     * Handles mid-window rescheduling cleanly: if `nowMillis` is already between `T-5m` and `T-2m`
     * (e.g. right after `PRE_5M` fired or after a schedule was created 4 minutes before start),
     * it advances directly to `PRE_2M` rather than re-firing `PRE_5M` in a loop.
     */
    fun findNextAlarmTrigger(
        items: List<ScheduledItem>,
        skippedOccurrenceIds: Set<String>,
        firedMilestoneKeys: Set<String>,
        nowMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): NextAlarmTrigger? {
        val occ = findNextOccurrence(items, skippedOccurrenceIds, nowMillis, zoneId) ?: return null

        val pre5Key = "${occ.occurrenceId}:${ScheduleMilestone.PRE_5M.name}"
        val pre2Key = "${occ.occurrenceId}:${ScheduleMilestone.PRE_2M.name}"
        val startKey = "${occ.occurrenceId}:${ScheduleMilestone.START.name}"

        if (pre5Key !in firedMilestoneKeys && nowMillis <= occ.pre5mAtMillis + LATE_FIRE_TOLERANCE_MILLIS) {
            return NextAlarmTrigger(
                occurrence = occ,
                milestone = ScheduleMilestone.PRE_5M,
                triggerAtMillis = occ.pre5mAtMillis.coerceAtLeast(nowMillis),
            )
        }

        if (pre2Key !in firedMilestoneKeys && nowMillis <= occ.pre2mAtMillis + LATE_FIRE_TOLERANCE_MILLIS) {
            return NextAlarmTrigger(
                occurrence = occ,
                milestone = ScheduleMilestone.PRE_2M,
                triggerAtMillis = occ.pre2mAtMillis.coerceAtLeast(nowMillis),
            )
        }

        if (startKey !in firedMilestoneKeys) {
            return NextAlarmTrigger(
                occurrence = occ,
                milestone = ScheduleMilestone.START,
                triggerAtMillis = occ.startAtMillis.coerceAtLeast(nowMillis),
            )
        }

        // If all milestones for `occ` are marked fired, look right after `occ.startAtMillis`.
        return findNextAlarmTrigger(
            items = items,
            skippedOccurrenceIds = skippedOccurrenceIds,
            firedMilestoneKeys = firedMilestoneKeys,
            nowMillis = occ.startAtMillis + 1L,
            zoneId = zoneId,
        )
    }

    /**
     * Returns true when `nowMillis` is inside the `[T-5m, T-0)` pre-session warning window of an
     * upcoming non-skipped occurrence. During this window, allowlist, schedule, and settings
     * modifications are locked unless the user submits a valid >= 20-word skip reason.
     */
    fun isInPreSessionLockWindow(
        items: List<ScheduledItem>,
        skippedOccurrenceIds: Set<String>,
        nowMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        // Look back up to 60s so mid-sentence typing grace period at T-0 remains locked
        val occ = findNextOccurrence(items, skippedOccurrenceIds, nowMillis - 60_000L, zoneId) ?: return false
        return nowMillis in occ.pre5mAtMillis until (occ.startAtMillis + 60_000L)
    }
}
