package com.example.learninghelper.focus

import java.util.Locale

enum class SessionPhase {
    WORK,
    BREAK,
}

/**
 * A single focus session. Once created it cannot be shortened – it only ends when
 * [endAt] is reached or via a validated 100-word Emergency Exit.
 *
 * Each session starts with 1 "Rest Token" ([restTokenUsed] == false) that can be spent once
 * during [SessionPhase.WORK] for an unscheduled 30-minute break without shifting [endAt].
 */
data class FocusSession(
    val startAt: Long,
    val endAt: Long,
    val workMinutes: Int = 50,
    val breakMinutes: Int = 0,
    val restBreakStartAt: Long = 0L,
    val restBreakEndAt: Long = 0L,
    val hasLongBreak: Boolean = false,
    val earlyEndedBreakStarts: Set<Long> = emptySet(),
) {
    init {
        require(endAt > startAt) { "endAt must be after startAt" }
        require(workMinutes >= FocusPolicy.MIN_DURATION_MINUTES) { "workMinutes must be >= ${FocusPolicy.MIN_DURATION_MINUTES}" }
        require(breakMinutes >= 0) { "breakMinutes must be >= 0" }
    }

    val durationMillis: Long get() = endAt - startAt

    val restTokenUsed: Boolean get() = restBreakStartAt > 0L && restBreakEndAt >= restBreakStartAt

    val breakWindows: List<Pair<Long, Long>> by lazy { computeBreakWindows() }

    /**
     * A started session remains active until [endAt]. Using `now < endAt` (with monotonic clamping
     * in the repository) ensures backward system-clock shifts cannot deactivate an active session.
     */
    fun isActive(now: Long): Boolean = now < endAt && now >= startAt

    fun remainingMillis(now: Long): Long = (endAt - now.coerceAtLeast(startAt)).coerceIn(0L, durationMillis)

    /** Progress from 0f (just started) to 1f (finished). */
    fun progress(now: Long): Float =
        ((now - startAt).toFloat() / durationMillis).coerceIn(0f, 1f)

    fun isRestTokenBreak(now: Long): Boolean =
        isActive(now) && restTokenUsed && now in restBreakStartAt until minOf(restBreakEndAt, endAt)

    fun canUseRestToken(now: Long): Boolean =
        isActive(now) && !restTokenUsed && currentPhase(now) == SessionPhase.WORK

    fun withRestTokenUsed(
        now: Long,
        restMinutes: Int = FocusPolicy.REST_TOKEN_DURATION_MINUTES,
    ): FocusSession {
        if (!canUseRestToken(now)) return this
        val clampedNow = now.coerceAtLeast(startAt)
        val breakEnd = minOf(endAt, clampedNow + restMinutes * 60_000L)
        return copy(restBreakStartAt = clampedNow, restBreakEndAt = breakEnd)
    }

    fun canEndBreakEarly(now: Long): Boolean =
        isActive(now) && currentPhase(now) == SessionPhase.BREAK

    fun withBreakEndedEarly(now: Long): FocusSession {
        if (!canEndBreakEarly(now)) return this
        val currentSched = breakWindows.firstOrNull { (bStart, bEnd) ->
            bStart !in earlyEndedBreakStarts && now in bStart until bEnd
        }
        val newEarlyBreaks = if (currentSched != null) {
            earlyEndedBreakStarts + currentSched.first
        } else {
            earlyEndedBreakStarts
        }
        val newRestEnd = if (isRestTokenBreak(now)) {
            now.coerceAtLeast(restBreakStartAt + 1L)
        } else {
            restBreakEndAt
        }
        return copy(
            restBreakEndAt = newRestEnd,
            earlyEndedBreakStarts = newEarlyBreaks,
        )
    }

    fun withRestTokenEndedEarly(now: Long): FocusSession = withBreakEndedEarly(now)

    fun currentPhase(now: Long): SessionPhase {
        if (!isActive(now)) return SessionPhase.WORK
        if (isRestTokenBreak(now)) return SessionPhase.BREAK
        val schedBreak = scheduledBreakWindowAt(now)
        return if (schedBreak != null) SessionPhase.BREAK else SessionPhase.WORK
    }

    /** Timestamp when the current [SessionPhase] transitions (or [endAt] if no more transitions). */
    fun phaseEndAt(now: Long): Long {
        if (!isActive(now)) return endAt
        if (isRestTokenBreak(now)) return minOf(restBreakEndAt, endAt)
        scheduledBreakWindowAt(now)?.let { (_, breakEnd) ->
            return minOf(breakEnd, endAt)
        }
        // Currently in WORK: find when the next valid scheduled break starts, if any.
        val nextBreak = breakWindows.firstOrNull { (bStart, _) -> bStart > now && bStart !in earlyEndedBreakStarts }
        return nextBreak?.first ?: endAt
    }

    fun phaseRemainingMillis(now: Long): Long =
        (phaseEndAt(now) - now.coerceAtLeast(startAt)).coerceAtLeast(0L)

    private fun scheduledBreakWindowAt(now: Long): Pair<Long, Long>? =
        breakWindows.firstOrNull { (bStart, bEnd) ->
            bStart !in earlyEndedBreakStarts && now in bStart until bEnd
        }

    private fun computeBreakWindows(): List<Pair<Long, Long>> {
        val longBreakActive = hasLongBreak && durationMillis > FocusPolicy.LONG_BREAK_THRESHOLD_MILLIS
        val longBreakMs = FocusPolicy.LONG_BREAK_DURATION_MINUTES * 60_000L
        val workMs = workMinutes * 60_000L
        val breakMs = breakMinutes * 60_000L

        if (breakMinutes <= 0) {
            if (!longBreakActive) return emptyList()
            val midStart = startAt + (durationMillis - longBreakMs) / 2L
            val midEnd = midStart + longBreakMs
            return if (midStart >= startAt + FocusPolicy.MIN_WORK_TAIL_MILLIS && endAt - midEnd >= FocusPolicy.MIN_WORK_TAIL_MILLIS) {
                listOf(midStart to midEnd)
            } else {
                emptyList()
            }
        }

        val standardBreaks = mutableListOf<Pair<Long, Long>>()
        var cursor = startAt
        while (true) {
            val bStart = cursor + workMs
            val bEnd = bStart + breakMs
            if (endAt - bEnd >= FocusPolicy.MIN_WORK_TAIL_MILLIS) {
                standardBreaks.add(bStart to bEnd)
                cursor = bEnd
            } else {
                break
            }
        }

        if (!longBreakActive) return standardBreaks

        val midpoint = startAt + durationMillis / 2L
        if (standardBreaks.isEmpty()) {
            val bStart = startAt + workMs
            val bEnd = bStart + longBreakMs
            return if (endAt - bEnd >= FocusPolicy.MIN_WORK_TAIL_MILLIS) {
                listOf(bStart to bEnd)
            } else {
                emptyList()
            }
        }

        val sortedCandidateIndices = standardBreaks.indices.sortedBy { idx ->
            kotlin.math.abs(standardBreaks[idx].first - midpoint)
        }

        for (longIdx in sortedCandidateIndices) {
            val result = mutableListOf<Pair<Long, Long>>()
            var t = startAt
            var i = 0
            var longBreakPlaced = false
            while (true) {
                val bStart = t + workMs
                val thisBreakMs = if (i == longIdx) longBreakMs else breakMs
                val bEnd = bStart + thisBreakMs
                if (endAt - bEnd >= FocusPolicy.MIN_WORK_TAIL_MILLIS) {
                    result.add(bStart to bEnd)
                    if (i == longIdx) longBreakPlaced = true
                    t = bEnd
                    i++
                } else {
                    break
                }
            }
            if (longBreakPlaced) {
                return result
            }
        }

        return standardBreaks
    }
}

/** Pure decision logic for the lock – kept free of Android types so it is unit-testable. */
object FocusPolicy {
    const val MIN_DURATION_MINUTES = 5
    const val MAX_DURATION_MINUTES = 1440
    const val MIN_WORK_TAIL_MINUTES = 5
    const val MIN_WORK_TAIL_MILLIS = MIN_WORK_TAIL_MINUTES * 60_000L
    const val REST_TOKEN_DURATION_MINUTES = 30
    const val LONG_BREAK_DURATION_MINUTES = 30
    const val LONG_BREAK_THRESHOLD_MINUTES = 120
    const val LONG_BREAK_THRESHOLD_MILLIS = LONG_BREAK_THRESHOLD_MINUTES * 60_000L
    val PRESET_INTERVALS: List<Pair<Int, Int>> = listOf(
        50 to 0,   // Continuous (no scheduled breaks)
        25 to 5,   // Pomodoro 25 / 5
        50 to 10,  // Deep work 50 / 10
        90 to 15,  // Thesis block 90 / 15
    )

    fun computeDurationMinutes(startHour: Int, startMinute: Int, endHour: Int, endMinute: Int): Int {
        val startTotal = startHour * 60 + startMinute
        val endTotal = endHour * 60 + endMinute
        val diff = endTotal - startTotal
        return if (diff > 0) diff else diff + 24 * 60
    }

    fun createSession(
        now: Long,
        durationMinutes: Int,
        workMinutes: Int = durationMinutes.coerceAtLeast(MIN_DURATION_MINUTES),
        breakMinutes: Int = 0,
        hasLongBreak: Boolean = false,
    ): FocusSession {
        require(durationMinutes in MIN_DURATION_MINUTES..MAX_DURATION_MINUTES) {
            "Duration must be between $MIN_DURATION_MINUTES and $MAX_DURATION_MINUTES minutes"
        }
        val cleanWork = workMinutes.coerceIn(MIN_DURATION_MINUTES, MAX_DURATION_MINUTES)
        val cleanBreak = breakMinutes.coerceIn(0, 120)
        return FocusSession(
            startAt = now,
            endAt = now + durationMinutes * 60_000L,
            workMinutes = cleanWork,
            breakMinutes = cleanBreak,
            hasLongBreak = hasLongBreak && durationMinutes > LONG_BREAK_THRESHOLD_MINUTES,
        )
    }

    /**
     * Returns true if [packageName] must be blocked right now.
     *
     * @param allowlist apps the user chose to allow (e.g. WhatsApp)
     * @param alwaysAllowed apps that can never be blocked (this app, dialer/call UI, Clock, launcher, keyboard, system UI)
     * @param alwaysBlockedDuringSession tamper-sensitive system apps (Settings, Package Installer, Device Care)
     *        that remain blocked during WORK, BREAK, and the [T-5m, T-0) pre-session lock window.
     * @param inPreSessionLockWindow true when inside the [T-5m, T-0) pre-session warning window.
     * @param grandfatheredCallPackage package of an active call that began before WORK phase and is permitted until finished.
     */
    fun shouldBlock(
        packageName: String?,
        session: FocusSession?,
        now: Long,
        allowlist: Set<String>,
        alwaysAllowed: Set<String>,
        alwaysBlockedDuringSession: Set<String> = emptySet(),
        inPreSessionLockWindow: Boolean = false,
        grandfatheredCallPackage: String? = null,
    ): Boolean {
        if (packageName.isNullOrBlank()) return false
        if (packageName in alwaysAllowed) return false
        if (!grandfatheredCallPackage.isNullOrBlank() && packageName == grandfatheredCallPackage) return false

        val active = session != null && session.isActive(now)
        if (!active) {
            return inPreSessionLockWindow && packageName in alwaysBlockedDuringSession
        }

        // Tamper-sensitive packages (Settings, Uninstaller, Device Care) stay blocked even during BREAK.
        if (packageName in alwaysBlockedDuringSession) return true

        // Normal apps are unlocked during a scheduled BREAK or Rest Token break.
        if (session.currentPhase(now) == SessionPhase.BREAK) return false

        return packageName !in allowlist
    }

    /** Formats a duration as `m:ss` or `h:mm:ss` using Locale.US for deterministic output. */
    fun formatDuration(millis: Long): String {
        val totalSeconds = (millis.coerceAtLeast(0L) + 999L) / 1000L // round up so 0:00 only shows at the very end
        val h = totalSeconds / 3600L
        val m = (totalSeconds % 3600L) / 60L
        val s = totalSeconds % 60L
        return if (h > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%d:%02d", m, s)
        }
    }
}
