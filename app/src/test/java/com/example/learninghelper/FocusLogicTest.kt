package com.example.learninghelper

import com.example.learninghelper.data.FocusRepository
import com.example.learninghelper.data.HistoryEntry
import com.example.learninghelper.data.HistoryType
import com.example.learninghelper.focus.*
import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class FocusLogicTest {

    @Test
    fun testFocusPolicyPhasesAndTailRule() {
        val now = 1_000_000L
        // 60-min session with 50m work, 10m break
        // Tail rule: 50 + 10 = 60m. There are 0 minutes of work after the 10m break.
        // Therefore, the break is suppressed by the tail rule and remains WORK.
        val session = FocusSession(
            startAt = now,
            endAt = now + 60 * 60_000L,
            workMinutes = 50,
            breakMinutes = 10,
        )

        assertEquals(SessionPhase.WORK, session.currentPhase(now + 25 * 60_000L))
        assertEquals(SessionPhase.WORK, session.currentPhase(now + 55 * 60_000L))

        // 120-min session with 50m work, 10m break -> cycle 1 break is 50..60m, followed by 60m work.
        // Tail rule allows break since 120 - 60 = 60m >= 5m.
        val longSession = FocusSession(
            startAt = now,
            endAt = now + 120 * 60_000L,
            workMinutes = 50,
            breakMinutes = 10,
        )
        assertEquals(SessionPhase.WORK, longSession.currentPhase(now + 30 * 60_000L))
        assertEquals(SessionPhase.BREAK, longSession.currentPhase(now + 55 * 60_000L))
        assertEquals(SessionPhase.WORK, longSession.currentPhase(now + 70 * 60_000L))
    }

    @Test
    fun testRestTokenBehavior() {
        val now = 1_000_000L
        val session = FocusSession(
            startAt = now,
            endAt = now + 120 * 60_000L,
            workMinutes = 120,
            breakMinutes = 0,
        )

        assertTrue(session.canUseRestToken(now + 10 * 60_000L))
        val withRest = session.withRestTokenUsed(now + 10 * 60_000L)
        assertTrue(withRest.restTokenUsed)
        assertTrue(withRest.isRestTokenBreak(now + 20 * 60_000L))
        assertEquals(SessionPhase.BREAK, withRest.currentPhase(now + 20 * 60_000L))

        assertFalse(withRest.canUseRestToken(now + 50 * 60_000L))

        val endedEarly = withRest.withRestTokenEndedEarly(now + 15 * 60_000L)
        assertEquals(SessionPhase.WORK, endedEarly.currentPhase(now + 16 * 60_000L))
    }

    @Test
    fun testWorkToBreakPhaseCalculations() {
        val now = 1_000_000L
        // 120-min session with 50m work, 10m break
        val session = FocusSession(
            startAt = now,
            endAt = now + 120 * 60_000L,
            workMinutes = 50,
            breakMinutes = 10,
        )

        // At t = 20m (WORK): phaseEndAt should be the start of the first break (t = 50m)
        val t20 = now + 20 * 60_000L
        assertEquals(SessionPhase.WORK, session.currentPhase(t20))
        val expectedBreakStart = now + 50 * 60_000L
        assertEquals(expectedBreakStart, session.phaseEndAt(t20))
        assertEquals(30 * 60_000L, session.phaseRemainingMillis(t20))

        // At t = 55m (BREAK): phaseEndAt should be the end of the break (t = 60m)
        val t55 = now + 55 * 60_000L
        assertEquals(SessionPhase.BREAK, session.currentPhase(t55))
        val expectedBreakEnd = now + 60 * 60_000L
        assertEquals(expectedBreakEnd, session.phaseEndAt(t55))
        assertEquals(5 * 60_000L, session.phaseRemainingMillis(t55))

        // At t = 70m (WORK after break): next break or end
        // Next break is suppressed by tail rule or endAt is reached
        val t70 = now + 70 * 60_000L
        assertEquals(SessionPhase.WORK, session.currentPhase(t70))
        assertEquals(session.endAt, session.phaseEndAt(t70))
        assertEquals(50 * 60_000L, session.phaseRemainingMillis(t70))

        // Rest Token phaseEndAt test
        val pureWorkSession = FocusSession(
            startAt = now,
            endAt = now + 90 * 60_000L,
            workMinutes = 90,
            breakMinutes = 0,
        )
        val withRest = pureWorkSession.withRestTokenUsed(now + 10 * 60_000L)
        // Rest break is now + 10m until now + 40m (30 minutes)
        val inRest = now + 25 * 60_000L
        assertEquals(SessionPhase.BREAK, withRest.currentPhase(inRest))
        val expectedRestEnd = now + 40 * 60_000L
        assertEquals(expectedRestEnd, withRest.phaseEndAt(inRest))
        assertEquals(15 * 60_000L, withRest.phaseRemainingMillis(inRest))

        // Phase boundary action & request code constants
        assertEquals("com.example.learninghelper.PHASE_BOUNDARY", AlarmReceiver.ACTION_PHASE_BOUNDARY)
        assertEquals(105, AlarmScheduler.REQUEST_CODE_PHASE_BOUNDARY)
    }

    @Test
    fun testBlockingLogic() {
        val now = 1_000_000L
        val session = FocusSession(
            startAt = now,
            endAt = now + 60 * 60_000L,
            workMinutes = 60,
            breakMinutes = 0,
        )

        val defaultAllowlist = FocusRepository.DEFAULT_ALLOWLIST
        val alwaysAllowed = AppCatalog.STATIC_ALWAYS_ALLOWED
        val alwaysBlocked = AppCatalog.ALWAYS_BLOCKED_DURING_SESSION

        // Always allowed (dialer, package installers) is never blocked
        assertFalse(FocusPolicy.shouldBlock("com.android.dialer", session, now + 1000L, defaultAllowlist, alwaysAllowed, alwaysBlocked))
        assertFalse(FocusPolicy.shouldBlock("com.google.android.packageinstaller", session, now + 1000L, defaultAllowlist, alwaysAllowed, alwaysBlocked))
        assertFalse(FocusPolicy.shouldBlock("com.android.packageinstaller", session, now + 1000L, defaultAllowlist, alwaysAllowed, alwaysBlocked))

        // Settings is in default allowlist, so uninstallation and settings are accessible
        assertFalse(FocusPolicy.shouldBlock("com.android.settings", session, now + 1000L, defaultAllowlist, alwaysAllowed, alwaysBlocked))

        // WhatsApp is allowed
        assertFalse(FocusPolicy.shouldBlock("com.whatsapp", session, now + 1000L, defaultAllowlist, alwaysAllowed, alwaysBlocked))

        // Distracting app is blocked
        assertTrue(FocusPolicy.shouldBlock("com.instagram.android", session, now + 1000L, defaultAllowlist, alwaysAllowed, alwaysBlocked))

        // If settings is removed from allowlist, it is blocked during WORK like any non-allowed app
        val withoutSettings = defaultAllowlist - "com.android.settings"
        assertTrue(FocusPolicy.shouldBlock("com.android.settings", session, now + 1000L, withoutSettings, alwaysAllowed, alwaysBlocked))
    }

    @Test
    fun testBreakAndPreSessionAntiTampering() {
        val now = 1_000_000L
        val longSession = FocusSession(
            startAt = now,
            endAt = now + 120 * 60_000L,
            workMinutes = 50,
            breakMinutes = 10,
        )
        val breakTime = now + 55 * 60_000L
        assertEquals(SessionPhase.BREAK, longSession.currentPhase(breakTime))

        val allowlist = FocusRepository.DEFAULT_ALLOWLIST
        val alwaysAllowed = AppCatalog.STATIC_ALWAYS_ALLOWED
        val alwaysBlocked = AppCatalog.ALWAYS_BLOCKED_DURING_SESSION

        // During BREAK: normal apps and settings are unlocked, package installers remain always allowed (for uninstallability)
        assertFalse(FocusPolicy.shouldBlock("com.instagram.android", longSession, breakTime, allowlist, alwaysAllowed, alwaysBlocked))
        assertFalse(FocusPolicy.shouldBlock("com.android.settings", longSession, breakTime, allowlist, alwaysAllowed, alwaysBlocked))
        assertFalse(FocusPolicy.shouldBlock("com.google.android.packageinstaller", longSession, breakTime, allowlist, alwaysAllowed, alwaysBlocked))

        // But anti-tampering packages (Device Care, App Stores) remain strictly blocked!
        assertTrue(FocusPolicy.shouldBlock("com.samsung.android.lool", longSession, breakTime, allowlist, alwaysAllowed, alwaysBlocked))
        assertTrue(FocusPolicy.shouldBlock("com.android.vending", longSession, breakTime, allowlist, alwaysAllowed, alwaysBlocked))
        assertTrue(FocusPolicy.shouldBlock("com.sec.android.app.samsungapps", longSession, breakTime, allowlist, alwaysAllowed, alwaysBlocked))

        // During Pre-Session Lock Window [T-5m, T-0):
        assertFalse(FocusPolicy.shouldBlock("com.instagram.android", null, now, allowlist, alwaysAllowed, alwaysBlocked, inPreSessionLockWindow = true))
        assertFalse(FocusPolicy.shouldBlock("com.google.android.packageinstaller", null, now, allowlist, alwaysAllowed, alwaysBlocked, inPreSessionLockWindow = true))
        assertTrue(FocusPolicy.shouldBlock("com.samsung.android.lool", null, now, allowlist, alwaysAllowed, alwaysBlocked, inPreSessionLockWindow = true))
        assertTrue(FocusPolicy.shouldBlock("com.android.vending", null, now, allowlist, alwaysAllowed, alwaysBlocked, inPreSessionLockWindow = true))
        assertTrue(FocusPolicy.shouldBlock("com.sec.android.app.samsungapps", null, now, allowlist, alwaysAllowed, alwaysBlocked, inPreSessionLockWindow = true))
    }

    @Test
    fun testPreSessionLockWindowGracePeriod() {
        val zone = ZoneId.of("UTC")
        val targetStartMillis = 10_000_000L

        val calItem = ScheduledItem(
            id = "cal_grace",
            label = "Grace Period Test",
            type = ScheduleType.CALENDAR_DATE,
            year = 2026,
            month = 10,
            dayOfMonth = 25,
            startHour = 14,
            startMinute = 0,
            durationMinutes = 60,
        )

        // Mock findNextOccurrence returning targetStartMillis by testing isInPreSessionLockWindow with calendar item
        val items = listOf(
            ScheduledItem(
                id = "weekly_grace",
                label = "Grace",
                type = ScheduleType.WEEKLY,
                daysOfWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
                startHour = 12,
                startMinute = 0,
                durationMinutes = 60,
            )
        )
        val zdt = ZonedDateTime.of(2026, 10, 25, 12, 0, 0, 0, zone)
        val startMillis = zdt.toInstant().toEpochMilli()

        // T - 3m: inside lock window
        assertTrue(ScheduleCalculator.isInPreSessionLockWindow(items, emptySet(), startMillis - 3 * 60_000L, zone))
        // T + 30s: inside lock window due to +60s typing grace period!
        assertTrue(ScheduleCalculator.isInPreSessionLockWindow(items, emptySet(), startMillis + 30_000L, zone))
        // T + 70s: past grace period, lock window closed
        assertFalse(ScheduleCalculator.isInPreSessionLockWindow(items, emptySet(), startMillis + 70_000L, zone))
    }

    @Test
    fun testEssayValidatorAntiSpamAndLength() {
        // Legitimate 100+ word essay
        val validEssay = """
            I truly need to exit this study session because an urgent university administrative matter requires my immediate attention.
            My advisor sent a critical update regarding my final undergraduate thesis submission deadline which demands prompt revision today.
            I have made substantial progress on chapter three and will resume writing tomorrow morning with fresh focus and dedication.
            Taking care of this academic requirement right now will allow me to continue working without anxiety or distraction later on.
            I acknowledge the importance of maintaining strict focus discipline and promise to compensate for this interrupted time during my evening block.
            Thank you for holding me accountable throughout this rigorous academic endeavor.
        """.trimIndent()

        val validResult = EssayValidator.validate(validEssay, config = EssayConfig.EMERGENCY_EXIT)
        assertTrue("Expected accepted, got $validResult", validResult is EssayResult.Accepted)

        // Gibberish rejection
        val gibberish = "asdfghjkl ".repeat(15)
        val gibberishResult = EssayValidator.validate(gibberish, config = EssayConfig.SKIP_REASON)
        assertTrue("Expected rejection for gibberish", gibberishResult is EssayResult.Rejected)

        // Single word spam rejection
        val spam = "quit ".repeat(25)
        val spamResult = EssayValidator.validate(spam, config = EssayConfig.SKIP_REASON)
        assertTrue("Expected rejection for spam", spamResult is EssayResult.Rejected)

        // Paste detection
        assertTrue(EssayValidator.isPasteAttempt("Hello", "Hello world and this is a pasted sentence right here"))
        assertFalse(EssayValidator.isPasteAttempt("Hello ", "Hello world "))
    }

    @Test
    fun testMultilingualRomanEssays() {
        // Spanish essay with accents (>= 100 words)
        val spanishEssay = """
            Necesito terminar esta sesión de estudio urgente debido a una situación imprevista con mi tesis académica en la universidad nacional.
            Mi profesor guía ha solicitado una reunión extraordinaria esta tarde para revisar las correcciones del último capítulo antes del envío definitivo.
            He completado la mayor parte del marco teórico planificado para hoy y reanudaré mis actividades con total compromiso el día de mañana temprano.
            Cumplir con esta solicitud institucional es absolutamente crucial para garantizar que mi graduación no sufra retrasos administrativos innecesarios ante el decanato.
            Agradezco profundamente la disciplina rigurosa impuesta por este sistema y reafirmo mi compromiso de compensar las horas restantes durante el próximo fin de semana.
            Mantendré la máxima concentración una vez resuelto este inconveniente académico tan relevante para mi titulación profesional universitaria.
        """.trimIndent()
        val spanishResult = EssayValidator.validate(spanishEssay, config = EssayConfig.EMERGENCY_EXIT)
        assertTrue("Expected Spanish accepted, got $spanishResult", spanishResult is EssayResult.Accepted)

        // Indonesian essay (>= 100 words)
        val indonesianEssay = """
            Saya benar-benar harus keluar dari sesi belajar ini karena ada urusan administrasi akademik yang sangat mendesak dari pihak jurusan kampus.
            Dosen pembimbing skripsi saya baru saja meminta berkas revisi bab empat dikirimkan sebelum jam lima sore untuk keperluan verifikasi seminar hasil.
            Saya sudah menyelesaikan analisis data utama dengan sangat baik dan berkomitmen untuk melanjutkan penulisan bab lima besok pagi secara fokus dan terarah.
            Menyelesaikan revisi berkas ini hari ini merupakan hal yang sangat penting agar jadwal yudisium dan wisuda saya tidak terhambat oleh masalah birokrasi fakultas.
            Terima kasih banyak atas ketegasan sistem ini dan saya berjanji dengan sungguh-sungguh akan mengganti waktu belajar ini pada jadwal berikutnya malam nanti.
            Fokus belajar saya akan segera dilanjutkan setelah seluruh berkas penting ini berhasil saya serahkan ke bagian sekretariat akademik.
        """.trimIndent()
        val indonesianResult = EssayValidator.validate(indonesianEssay, config = EssayConfig.EMERGENCY_EXIT)
        assertTrue("Expected Indonesian accepted, got $indonesianResult", indonesianResult is EssayResult.Accepted)
    }

    @Test
    fun testScheduleCalculator() {
        val zone = ZoneId.of("UTC")
        val now = 1700000000000L // arbitrary fixed timestamp

        val item = ScheduledItem(
            id = "sched1",
            label = "Morning Study",
            type = ScheduleType.WEEKLY,
            daysOfWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
            startHour = 10,
            startMinute = 0,
            durationMinutes = 60,
        )

        val occ = ScheduleCalculator.findNextOccurrence(listOf(item), emptySet(), now, zone)
        assertNotNull(occ)
        assertTrue(occ!!.startAtMillis > now)

        val trigger = ScheduleCalculator.findNextAlarmTrigger(listOf(item), emptySet(), emptySet(), now, zone)
        assertNotNull(trigger)
    }

    @Test
    fun testCalendarDateAndMilestoneScheduling() {
        val zone = ZoneId.of("UTC")
        val zdt = ZonedDateTime.of(2026, 10, 25, 14, 0, 0, 0, zone)
        val targetStartMillis = zdt.toInstant().toEpochMilli()

        val calItem = ScheduledItem(
            id = "cal1",
            label = "Sunday Thesis Block",
            type = ScheduleType.CALENDAR_DATE,
            year = 2026,
            month = 10,
            dayOfMonth = 25,
            startHour = 14,
            startMinute = 0,
            durationMinutes = 90,
            workMinutes = 45,
            breakMinutes = 15,
        )

        // Before T-5m (e.g. T-10m)
        val atTMinus10m = targetStartMillis - 10 * 60_000L
        val trigger10m = ScheduleCalculator.findNextAlarmTrigger(listOf(calItem), emptySet(), emptySet(), atTMinus10m, zone)
        assertNotNull(trigger10m)
        assertEquals(ScheduleMilestone.PRE_5M, trigger10m!!.milestone)
        assertEquals(targetStartMillis - 5 * 60_000L, trigger10m.triggerAtMillis)
        assertFalse(ScheduleCalculator.isInPreSessionLockWindow(listOf(calItem), emptySet(), atTMinus10m, zone))

        // Mid-window at T-4m (between T-5m and T-2m)
        val atTMinus4m = targetStartMillis - 4 * 60_000L
        val trigger4m = ScheduleCalculator.findNextAlarmTrigger(listOf(calItem), emptySet(), emptySet(), atTMinus4m, zone)
        assertNotNull(trigger4m)
        assertEquals(ScheduleMilestone.PRE_2M, trigger4m!!.milestone)
        assertEquals(targetStartMillis - 2 * 60_000L, trigger4m.triggerAtMillis)
        assertTrue(ScheduleCalculator.isInPreSessionLockWindow(listOf(calItem), emptySet(), atTMinus4m, zone))

        // Mid-window at T-1m (between T-2m and T-0)
        val atTMinus1m = targetStartMillis - 1 * 60_000L
        val trigger1m = ScheduleCalculator.findNextAlarmTrigger(listOf(calItem), emptySet(), emptySet(), atTMinus1m, zone)
        assertNotNull(trigger1m)
        assertEquals(ScheduleMilestone.START, trigger1m!!.milestone)
        assertEquals(targetStartMillis, trigger1m.triggerAtMillis)
        assertTrue(ScheduleCalculator.isInPreSessionLockWindow(listOf(calItem), emptySet(), atTMinus1m, zone))

        // Past event
        val atPast = targetStartMillis + 100 * 60_000L
        val triggerPast = ScheduleCalculator.findNextAlarmTrigger(listOf(calItem), emptySet(), emptySet(), atPast, zone)
        assertNull(triggerPast)
    }

    @Test
    fun testFifoSkipRetentionLogic() {
        val now = 1_000_000_000L
        val sevenDaysMs = FocusRepository.SEVEN_DAYS_MILLIS

        val recentSkip = HistoryEntry(
            id = "s1",
            type = HistoryType.SKIPPED,
            timestamp = now - (3 * 24 * 60 * 60_000L),
            text = "Recent skip reason within seven days",
        )
        val expiredSkip = HistoryEntry(
            id = "s2",
            type = HistoryType.SKIPPED,
            timestamp = now - (8 * 24 * 60 * 60_000L),
            text = "Old skip reason that should be pruned after seven days",
        )
        val completedSession = HistoryEntry(
            id = "c1",
            type = HistoryType.COMPLETED,
            timestamp = now - (10 * 24 * 60 * 60_000L),
            text = "",
        )
        val historyList = listOf(recentSkip, expiredSkip, completedSession)

        val pruned = historyList.filterNot {
            it.type == HistoryType.SKIPPED && (now - it.timestamp) > sevenDaysMs
        }

        assertEquals(2, pruned.size)
        assertTrue(pruned.contains(recentSkip))
        assertFalse(pruned.contains(expiredSkip))
        assertTrue(pruned.contains(completedSession)) // Completed sessions are preserved
    }

    @Test
    fun testStartEndDurationAndLongBreakAndCustomIntervals() {
        // 1. Start/End duration calculation (same-day and overnight rollover)
        assertEquals(135, FocusPolicy.computeDurationMinutes(9, 0, 11, 15))
        assertEquals(180, FocusPolicy.computeDurationMinutes(22, 30, 1, 30))
        assertEquals(1440, FocusPolicy.computeDurationMinutes(8, 0, 8, 0))

        // 2. Custom hand-typed work/break intervals (e.g., 40m work / 8m break across 110m)
        val now = 1_000_000L
        val customSession = FocusSession(
            startAt = now,
            endAt = now + 110 * 60_000L,
            workMinutes = 40,
            breakMinutes = 8,
            hasLongBreak = false,
        )
        // Cycle 1: 0..40m WORK, 40..48m BREAK
        // Cycle 2: 48..88m WORK, 88..96m BREAK
        // Cycle 3: 96..110m WORK (14m >= 5m tail rule)
        assertEquals(SessionPhase.WORK, customSession.currentPhase(now + 35 * 60_000L))
        assertEquals(SessionPhase.BREAK, customSession.currentPhase(now + 44 * 60_000L))
        assertEquals(SessionPhase.WORK, customSession.currentPhase(now + 60 * 60_000L))
        assertEquals(SessionPhase.BREAK, customSession.currentPhase(now + 92 * 60_000L))
        assertEquals(SessionPhase.WORK, customSession.currentPhase(now + 100 * 60_000L))

        // 3. Long 30-min break in >2h (180m) session with 50m/10m intervals
        // Cycle 1: 0..50m WORK, 50..60m BREAK (standard 10m)
        // Cycle 2: 60..110m WORK, 110..140m BREAK (upgraded to 30m long break near 90m midpoint!)
        // Cycle 3: 140..180m WORK (40m remaining work)
        val longSessionWithLongBreak = FocusSession(
            startAt = now,
            endAt = now + 180 * 60_000L,
            workMinutes = 50,
            breakMinutes = 10,
            hasLongBreak = true,
        )
        assertEquals(SessionPhase.BREAK, longSessionWithLongBreak.currentPhase(now + 55 * 60_000L))
        assertEquals(SessionPhase.WORK, longSessionWithLongBreak.currentPhase(now + 65 * 60_000L))
        assertEquals(SessionPhase.BREAK, longSessionWithLongBreak.currentPhase(now + 115 * 60_000L))
        assertEquals(SessionPhase.BREAK, longSessionWithLongBreak.currentPhase(now + 135 * 60_000L))
        assertEquals(SessionPhase.WORK, longSessionWithLongBreak.currentPhase(now + 145 * 60_000L))
        assertEquals(now + 140 * 60_000L, longSessionWithLongBreak.phaseEndAt(now + 120 * 60_000L))

        // 4. Long 30-min break in >2h (180m) solid session (breakMinutes = 0)
        // Midpoint 30m break placed at [75m, 105m)
        val solidWithLongBreak = FocusSession(
            startAt = now,
            endAt = now + 180 * 60_000L,
            workMinutes = 180,
            breakMinutes = 0,
            hasLongBreak = true,
        )
        assertEquals(SessionPhase.WORK, solidWithLongBreak.currentPhase(now + 70 * 60_000L))
        assertEquals(SessionPhase.BREAK, solidWithLongBreak.currentPhase(now + 80 * 60_000L))
        assertEquals(SessionPhase.BREAK, solidWithLongBreak.currentPhase(now + 100 * 60_000L))
        assertEquals(SessionPhase.WORK, solidWithLongBreak.currentPhase(now + 110 * 60_000L))
    }

    @Test
    fun testAllowlistMaxQuota() {
        // Enforces MAX_ALLOWED_APPS = 5 and MAX_OPTIONAL_ALLOWED_APPS = 5
        assertEquals(5, FocusRepository.MAX_ALLOWED_APPS)
        assertEquals(5, FocusRepository.MAX_OPTIONAL_ALLOWED_APPS)

        val defaultApps = FocusRepository.DEFAULT_ALLOWLIST
        assertEquals(setOf("com.whatsapp", "com.whatsapp.w4b", "com.android.settings"), defaultApps)

        // Starting with default apps, optional count is 0
        var currentAllowlist = defaultApps
        val initialOptionalCount = currentAllowlist.count { it !in defaultApps }
        assertEquals(0, initialOptionalCount)

        // Add 5 optional apps
        val optionalApps = listOf("com.notion.id", "com.spotify.music", "com.slack", "us.zoom.videomeetings", "com.google.android.apps.docs")
        for (app in optionalApps) {
            val optionalCount = currentAllowlist.count { it !in defaultApps }
            assertTrue(optionalCount < FocusRepository.MAX_OPTIONAL_ALLOWED_APPS)
            currentAllowlist = currentAllowlist + app
        }

        // Total allowlist has 8 apps (3 default + 5 optional)
        assertEquals(8, currentAllowlist.size)
        val finalOptionalCount = currentAllowlist.count { it !in defaultApps }
        assertEquals(5, finalOptionalCount)

        // Adding a 6th optional app is rejected
        val sixthApp = "com.instagram.android"
        val canAddSixth = currentAllowlist.count { it !in defaultApps } < FocusRepository.MAX_OPTIONAL_ALLOWED_APPS
        assertFalse(canAddSixth)

        // Toggling/re-enabling a default app is allowed even when 5 optional apps are selected
        val withoutSettings = currentAllowlist - "com.android.settings"
        val isDefaultApp = "com.android.settings" in defaultApps
        val canReAddSettings = isDefaultApp || (withoutSettings.count { it !in defaultApps } < FocusRepository.MAX_OPTIONAL_ALLOWED_APPS)
        assertTrue(canReAddSettings)
    }

    @Test
    fun testFutureDateSchedulingOccurrence() {
        val zone = ZoneId.of("UTC")
        val zdtNow = ZonedDateTime.of(2026, 10, 21, 18, 30, 0, 0, zone)
        val nowMillis = zdtNow.toInstant().toEpochMilli()

        // User schedules for 19:00 today (30 min in future)
        val todayItem = ScheduledItem(
            id = "sched_today",
            label = "Evening Study",
            type = ScheduleType.CALENDAR_DATE,
            year = 2026,
            month = 10,
            dayOfMonth = 21,
            startHour = 19,
            startMinute = 0,
            durationMinutes = 60,
        )

        val nextOcc = ScheduleCalculator.findNextOccurrence(listOf(todayItem), emptySet(), nowMillis, zone)
        assertNotNull(nextOcc)
        assertEquals("sched_today", nextOcc!!.item.id)
        val expectedStart = ZonedDateTime.of(2026, 10, 21, 19, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expectedStart, nextOcc.startAtMillis)

        // Pre-session milestones
        assertEquals(expectedStart - 5 * 60_000L, nextOcc.pre5mAtMillis)
        assertEquals(expectedStart - 2 * 60_000L, nextOcc.pre2mAtMillis)
    }

    @Test
    fun testGrandfatheredActiveCallContinuityAndPostCallBlock() {
        val now = 1_000_000L
        val session = FocusSession(
            startAt = now,
            endAt = now + 60 * 60_000L,
            workMinutes = 60,
            breakMinutes = 0,
        )

        val nonWhitelistedVoipApp = "com.discord"
        val allowlist = setOf("com.whatsapp")
        val alwaysAllowed = setOf("com.android.dialer", "com.samsung.android.dialer", "com.samsung.android.app.contacts")

        // 1. When grandfatheredCallPackage == "com.discord" (call in progress when session starts):
        // Discord call is NOT blocked!
        val blockedDuringCall = FocusPolicy.shouldBlock(
            packageName = nonWhitelistedVoipApp,
            session = session,
            now = now + 1000L,
            allowlist = allowlist,
            alwaysAllowed = alwaysAllowed,
            alwaysBlockedDuringSession = AppCatalog.ALWAYS_BLOCKED_DURING_SESSION,
            grandfatheredCallPackage = nonWhitelistedVoipApp,
        )
        assertFalse("Grandfathered active call must continue uninterrupted", blockedDuringCall)

        // 2. The moment call ends (grandfatheredCallPackage becomes null):
        // Discord is IMMEDIATELY blocked! User cannot make any new outgoing calls from Discord!
        val blockedAfterCall = FocusPolicy.shouldBlock(
            packageName = nonWhitelistedVoipApp,
            session = session,
            now = now + 1000L,
            allowlist = allowlist,
            alwaysAllowed = alwaysAllowed,
            alwaysBlockedDuringSession = AppCatalog.ALWAYS_BLOCKED_DURING_SESSION,
            grandfatheredCallPackage = null,
        )
        assertTrue("Non-whitelisted app must be blocked once the call ends", blockedAfterCall)

        // 3. Contacts / Phone apps are ALWAYS allowed for outgoing calls
        assertFalse(FocusPolicy.shouldBlock("com.samsung.android.dialer", session, now, allowlist, alwaysAllowed))
        assertFalse(FocusPolicy.shouldBlock("com.samsung.android.app.contacts", session, now, allowlist, alwaysAllowed))
    }

    @Test
    fun testEarlyBreakCompletionScheduledBreak() {
        val now = 1_000_000L
        // 120-min session with 50m work, 10m break (Break window is now+50m .. now+60m)
        val session = FocusSession(
            startAt = now,
            endAt = now + 120 * 60_000L,
            workMinutes = 50,
            breakMinutes = 10,
        )

        val t52 = now + 52 * 60_000L
        assertEquals(SessionPhase.BREAK, session.currentPhase(t52))
        assertTrue(session.canEndBreakEarly(t52))

        val resumed = session.withBreakEndedEarly(t52)
        assertTrue(resumed.earlyEndedBreakStarts.contains(now + 50 * 60_000L))

        // Immediately resumes WORK phase for the remainder of this scheduled break interval
        val t53 = now + 53 * 60_000L
        assertEquals(SessionPhase.WORK, resumed.currentPhase(t53))
        assertEquals(resumed.endAt, resumed.phaseEndAt(t53))
        assertEquals(now + 120 * 60_000L, resumed.endAt)

        // Session in WORK phase cannot end break early
        assertFalse(resumed.canEndBreakEarly(t53))
        assertSame(resumed, resumed.withBreakEndedEarly(t53))
    }

    @Test
    fun testEarlyBreakCompletionRestTokenBreak() {
        val now = 1_000_000L
        val session = FocusSession(
            startAt = now,
            endAt = now + 90 * 60_000L,
            workMinutes = 90,
            breakMinutes = 0,
        )
        val withRest = session.withRestTokenUsed(now + 10 * 60_000L)
        val t20 = now + 20 * 60_000L

        assertEquals(SessionPhase.BREAK, withRest.currentPhase(t20))
        assertTrue(withRest.canEndBreakEarly(t20))

        val resumed = withRest.withBreakEndedEarly(t20)
        assertEquals(t20, resumed.restBreakEndAt)

        val t21 = now + 21 * 60_000L
        assertEquals(SessionPhase.WORK, resumed.currentPhase(t21))
        assertEquals(now + 90 * 60_000L, resumed.endAt)
    }

    @Test
    fun testEarlyBreakCompletionOverlappingRestAndScheduledBreak() {
        val now = 1_000_000L
        // 120-min session with 50m work, 10m break (Scheduled break is now+50m .. now+60m)
        val session = FocusSession(
            startAt = now,
            endAt = now + 120 * 60_000L,
            workMinutes = 50,
            breakMinutes = 10,
        )
        // User triggers rest token at t=40m (spans 40m..70m, overlapping with scheduled 50..60m)
        val withRest = session.withRestTokenUsed(now + 40 * 60_000L)
        val t55 = now + 55 * 60_000L
        assertTrue(withRest.isRestTokenBreak(t55))
        assertEquals(SessionPhase.BREAK, withRest.currentPhase(t55))

        // Ending break early at t=55m clears BOTH the rest break and the overlapping scheduled break window
        val resumed = withRest.withBreakEndedEarly(t55)
        assertEquals(t55, resumed.restBreakEndAt)
        assertTrue(resumed.earlyEndedBreakStarts.contains(now + 50 * 60_000L))

        // At t=56m, session must be in WORK phase
        val t56 = now + 56 * 60_000L
        assertEquals(SessionPhase.WORK, resumed.currentPhase(t56))
    }
}
