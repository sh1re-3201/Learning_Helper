package com.example.learninghelper.data

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import com.example.learninghelper.focus.FocusPolicy
import com.example.learninghelper.focus.FocusSession
import com.example.learninghelper.focus.ScheduleCalculator
import com.example.learninghelper.focus.ScheduleType
import com.example.learninghelper.focus.ScheduledItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek

enum class HistoryType {
    COMPLETED,
    SKIPPED,
    EMERGENCY_EXIT,
}

data class HistoryEntry(
    val id: String,
    val type: HistoryType,
    val timestamp: Long,
    val durationMinutes: Int = 0,
    val minutesRemaining: Int = 0,
    val title: String = "",
    val text: String = "",
)

object FocusRepository {
    const val SEVEN_DAYS_MILLIS = 7 * 24 * 60 * 60_000L
    const val MAX_ALLOWED_APPS = 5
    const val MAX_OPTIONAL_ALLOWED_APPS = 5

    private const val PREFS_NAME = "focus_prefs"
    private const val KEY_START = "session_start"
    private const val KEY_END = "session_end"
    private const val KEY_WORK_MIN = "session_work_min"
    private const val KEY_BREAK_MIN = "session_break_min"
    private const val KEY_HAS_LONG_BREAK = "session_has_long_break"
    private const val KEY_REST_START = "session_rest_start"
    private const val KEY_REST_END = "session_rest_end"
    private const val KEY_ALLOWLIST = "allowlist"

    private const val KEY_ACCUMULATED_ELAPSED = "session_accumulated_elapsed"
    private const val KEY_ANCHOR_ELAPSED = "session_anchor_elapsed"
    private const val KEY_ANCHOR_WALL = "session_anchor_wall"
    private const val KEY_ANCHOR_BOOT_COUNT = "session_anchor_boot_count"

    private const val KEY_SCHEDULES_JSON = "schedules_json"
    private const val KEY_SKIPPED_OCCURRENCES_JSON = "skipped_occurrences_json"
    private const val KEY_FIRED_MILESTONES_JSON = "fired_milestones_json"
    private const val KEY_HISTORY_JSON = "history_json"

    private const val KEY_MIN_WRITING_MINUTES = "min_writing_minutes"
    private const val KEY_ALARM_SOUND_URI = "alarm_sound_uri"
    private const val KEY_EMERGENCY_DRAFT = "emergency_draft"
    private const val KEY_EMERGENCY_ELAPSED_SEC = "emergency_elapsed_sec"
    private const val KEY_EMERGENCY_SESSION_START = "emergency_session_start"
    private const val KEY_EARLY_ENDED_BREAKS = "session_early_ended_breaks"

    val DEFAULT_ALLOWLIST = setOf("com.whatsapp", "com.whatsapp.w4b", "com.android.settings")

    private lateinit var prefs: SharedPreferences
    private lateinit var appContext: Context

    private val _session = MutableStateFlow<FocusSession?>(null)
    val session: StateFlow<FocusSession?> = _session.asStateFlow()

    private val _allowlist = MutableStateFlow<Set<String>>(emptySet())
    val allowlist: StateFlow<Set<String>> = _allowlist.asStateFlow()

    private val _schedules = MutableStateFlow<List<ScheduledItem>>(emptyList())
    val schedules: StateFlow<List<ScheduledItem>> = _schedules.asStateFlow()

    private val _skippedOccurrences = MutableStateFlow<Set<String>>(emptySet())
    val skippedOccurrences: StateFlow<Set<String>> = _skippedOccurrences.asStateFlow()

    private val _firedMilestones = MutableStateFlow<Set<String>>(emptySet())
    val firedMilestones: StateFlow<Set<String>> = _firedMilestones.asStateFlow()

    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    private val _minWritingMinutes = MutableStateFlow(3)
    val minWritingMinutes: StateFlow<Int> = _minWritingMinutes.asStateFlow()

    private val _alarmSoundUri = MutableStateFlow<String?>(null)
    val alarmSoundUri: StateFlow<String?> = _alarmSoundUri.asStateFlow()

    private val _emergencyDraftText = MutableStateFlow("")
    val emergencyDraftText: StateFlow<String> = _emergencyDraftText.asStateFlow()

    private val _emergencyElapsedSeconds = MutableStateFlow(0)
    val emergencyElapsedSeconds: StateFlow<Int> = _emergencyElapsedSeconds.asStateFlow()

    private val _grandfatheredCallPackage = MutableStateFlow<String?>(null)
    val grandfatheredCallPackage: StateFlow<String?> = _grandfatheredCallPackage.asStateFlow()

    private val _isDeskClockActive = MutableStateFlow(false)
    val isDeskClockActive: StateFlow<Boolean> = _isDeskClockActive.asStateFlow()

    fun setGrandfatheredCallPackage(pkg: String?) {
        _grandfatheredCallPackage.value = pkg
    }

    fun setDeskClockActive(active: Boolean) {
        _isDeskClockActive.value = active
    }

    @Synchronized
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        if (!prefs.contains(KEY_ALLOWLIST)) {
            prefs.edit().putStringSet(KEY_ALLOWLIST, HashSet(DEFAULT_ALLOWLIST)).commit()
        }
        val currentAllowlist = prefs.getStringSet(KEY_ALLOWLIST, emptySet())?.toMutableSet() ?: mutableSetOf()
        if ("com.android.settings" !in currentAllowlist) {
            currentAllowlist.add("com.android.settings")
            prefs.edit().putStringSet(KEY_ALLOWLIST, currentAllowlist).commit()
        }
        _allowlist.value = currentAllowlist
        _minWritingMinutes.value = prefs.getInt(KEY_MIN_WRITING_MINUTES, 3).coerceIn(1, 15)
        _alarmSoundUri.value = prefs.getString(KEY_ALARM_SOUND_URI, null)

        _schedules.value = parseSchedulesJson(prefs.getString(KEY_SCHEDULES_JSON, "[]") ?: "[]")
        _skippedOccurrences.value = parseStringSetJson(prefs.getString(KEY_SKIPPED_OCCURRENCES_JSON, "[]") ?: "[]")
        _firedMilestones.value = parseStringSetJson(prefs.getString(KEY_FIRED_MILESTONES_JSON, "[]") ?: "[]")
        val rawHistory = parseHistoryJson(prefs.getString(KEY_HISTORY_JSON, "[]") ?: "[]")
        val now = System.currentTimeMillis()
        val pruned = rawHistory.filterNot {
            it.type == HistoryType.SKIPPED && (now - it.timestamp) > SEVEN_DAYS_MILLIS
        }
        if (pruned.size != rawHistory.size) {
            prefs.edit().putString(KEY_HISTORY_JSON, serializeHistoryJson(pruned)).apply()
        }
        _history.value = pruned

        restoreSession()
        reconcileReboot()

        if (com.example.learninghelper.focus.Permissions.hasAllFilesAccess()) {
            backupExecutor.execute {
                syncWithExternalBackup(appContext)
            }
        }
    }

    private fun restoreSession() {
        runCatching {
            val start = prefs.getLong(KEY_START, 0L)
            val end = prefs.getLong(KEY_END, 0L)
            if (start > 0L && end > start) {
                val rawWork = prefs.getInt(KEY_WORK_MIN, 50)
                val workMin = rawWork.coerceIn(com.example.learninghelper.focus.FocusPolicy.MIN_DURATION_MINUTES, com.example.learninghelper.focus.FocusPolicy.MAX_DURATION_MINUTES)
                val breakMin = prefs.getInt(KEY_BREAK_MIN, 0).coerceAtLeast(0)
                val hasLongBreak = prefs.getBoolean(KEY_HAS_LONG_BREAK, false)
                val restStart = prefs.getLong(KEY_REST_START, 0L)
                val restEnd = prefs.getLong(KEY_REST_END, 0L)
                val earlyBreakStr = prefs.getString(KEY_EARLY_ENDED_BREAKS, "") ?: ""
                val earlyBreaks = if (earlyBreakStr.isNotBlank()) {
                    earlyBreakStr.split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()
                } else {
                    emptySet()
                }
                _session.value = FocusSession(
                    startAt = start,
                    endAt = end,
                    workMinutes = workMin,
                    breakMinutes = breakMin,
                    restBreakStartAt = restStart,
                    restBreakEndAt = restEnd,
                    hasLongBreak = hasLongBreak,
                    earlyEndedBreakStarts = earlyBreaks,
                )
                val draftStart = prefs.getLong(KEY_EMERGENCY_SESSION_START, 0L)
                if (draftStart == start) {
                    _emergencyDraftText.value = prefs.getString(KEY_EMERGENCY_DRAFT, "") ?: ""
                    _emergencyElapsedSeconds.value = prefs.getInt(KEY_EMERGENCY_ELAPSED_SEC, 0)
                } else {
                    _emergencyDraftText.value = ""
                    _emergencyElapsedSeconds.value = 0
                }
            } else {
                _session.value = null
            }
        }.onFailure {
            clearActiveSessionInternal()
            _session.value = null
        }
    }

    const val MAX_REBOOT_SLACK_MS = 5 * 60_000L
    private const val CHECKPOINT_INTERVAL_MS = 30_000L
    private var lastCheckpointElapsed = 0L
    private var cachedBootCount: Int? = null

    /**
     * Periodically flushes monotonic elapsed progress to persistent storage (throttled to >= 30s).
     */
    @Synchronized
    fun checkpointMonotonicProgress(force: Boolean = false) {
        val session = _session.value ?: return
        val curElapsed = SystemClock.elapsedRealtime()
        if (!force && curElapsed - lastCheckpointElapsed < CHECKPOINT_INTERVAL_MS) {
            return
        }
        val curBoot = getBootCount()
        val anchorBoot = prefs.getInt(KEY_ANCHOR_BOOT_COUNT, -1)
        val anchorElapsed = prefs.getLong(KEY_ANCHOR_ELAPSED, 0L)

        if (anchorBoot == curBoot && anchorElapsed > 0L && curElapsed >= anchorElapsed) {
            val delta = curElapsed - anchorElapsed
            val newAccumulated = prefs.getLong(KEY_ACCUMULATED_ELAPSED, 0L) + delta
            val curWall = System.currentTimeMillis()
            lastCheckpointElapsed = curElapsed
            prefs.edit()
                .putLong(KEY_ACCUMULATED_ELAPSED, newAccumulated)
                .putLong(KEY_ANCHOR_ELAPSED, curElapsed)
                .putLong(KEY_ANCHOR_WALL, curWall)
                .apply()
        }
    }

    /**
     * Reconciles monotonic clock anchor across reboots.
     */
    @Synchronized
    fun reconcileReboot() {
        val session = _session.value ?: return
        val anchorBoot = prefs.getInt(KEY_ANCHOR_BOOT_COUNT, -1)
        val curBoot = getBootCount()

        if (anchorBoot != curBoot && curBoot > 0) {
            val lastSavedWall = prefs.getLong(KEY_ANCHOR_WALL, session.startAt)
            val curWall = System.currentTimeMillis()
            val rawDelta = (curWall - lastSavedWall).coerceAtLeast(0L)
            // Cap reboot delta to MAX_REBOOT_SLACK_MS (5m) to prevent forward clock tampering bypass
            val wallDelta = minOf(rawDelta, MAX_REBOOT_SLACK_MS)

            val accumulated = prefs.getLong(KEY_ACCUMULATED_ELAPSED, 0L) + wallDelta

            prefs.edit()
                .putLong(KEY_ACCUMULATED_ELAPSED, accumulated)
                .putLong(KEY_ANCHOR_ELAPSED, SystemClock.elapsedRealtime())
                .putLong(KEY_ANCHOR_WALL, curWall)
                .putInt(KEY_ANCHOR_BOOT_COUNT, curBoot)
                .commit()
        }
    }

    fun effectiveNow(wallNow: Long = System.currentTimeMillis()): Long {
        val session = _session.value ?: return wallNow
        val anchorBoot = prefs.getInt(KEY_ANCHOR_BOOT_COUNT, -1)
        val curBoot = getBootCount()
        val anchorElapsed = prefs.getLong(KEY_ANCHOR_ELAPSED, 0L)
        val accumulated = prefs.getLong(KEY_ACCUMULATED_ELAPSED, 0L)
        val curElapsed = SystemClock.elapsedRealtime()

        return if (anchorBoot == curBoot && anchorElapsed > 0L && curElapsed >= anchorElapsed) {
            session.startAt + accumulated + (curElapsed - anchorElapsed)
        } else {
            wallNow.coerceAtLeast(session.startAt)
        }
    }

    private fun getBootCount(): Int {
        cachedBootCount?.let { return it }
        val count = runCatching {
            Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT)
        }.getOrDefault(0)
        if (count > 0) cachedBootCount = count
        return count
    }

    fun activeSession(wallNow: Long = System.currentTimeMillis()): FocusSession? {
        val session = _session.value ?: return null
        val effNow = effectiveNow(wallNow)
        return if (session.isActive(effNow)) session else null
    }

    fun isFocusActive(wallNow: Long = System.currentTimeMillis()): Boolean =
        activeSession(wallNow) != null

    fun isModificationLocked(wallNow: Long = System.currentTimeMillis()): Boolean {
        if (isFocusActive(wallNow)) return true
        return ScheduleCalculator.isInPreSessionLockWindow(
            items = _schedules.value,
            skippedOccurrenceIds = _skippedOccurrences.value,
            nowMillis = wallNow,
        )
    }

    @Synchronized
    fun startSession(
        durationMinutes: Int,
        workMinutes: Int = durationMinutes,
        breakMinutes: Int = 0,
        now: Long = System.currentTimeMillis(),
        hasLongBreak: Boolean = false,
    ): FocusSession {
        val current = activeSession(now)
        if (current != null) {
            val extendedEnd = maxOf(current.endAt, now + durationMinutes * 60_000L)
            val updated = current.copy(endAt = extendedEnd)
            prefs.edit().putLong(KEY_END, extendedEnd).commit()
            _session.value = updated
            return updated
        }

        val session = FocusPolicy.createSession(now, durationMinutes, workMinutes, breakMinutes, hasLongBreak)
        prefs.edit()
            .putLong(KEY_START, session.startAt)
            .putLong(KEY_END, session.endAt)
            .putInt(KEY_WORK_MIN, session.workMinutes)
            .putInt(KEY_BREAK_MIN, session.breakMinutes)
            .putBoolean(KEY_HAS_LONG_BREAK, session.hasLongBreak)
            .putLong(KEY_REST_START, 0L)
            .putLong(KEY_REST_END, 0L)
            .putLong(KEY_ACCUMULATED_ELAPSED, 0L)
            .putLong(KEY_ANCHOR_ELAPSED, SystemClock.elapsedRealtime())
            .putLong(KEY_ANCHOR_WALL, now)
            .putInt(KEY_ANCHOR_BOOT_COUNT, getBootCount())
            .remove(KEY_EMERGENCY_DRAFT)
            .remove(KEY_EMERGENCY_ELAPSED_SEC)
            .putLong(KEY_EMERGENCY_SESSION_START, session.startAt)
            .remove(KEY_EARLY_ENDED_BREAKS)
            .commit()

        _session.value = session
        _emergencyDraftText.value = ""
        _emergencyElapsedSeconds.value = 0
        return session
    }

    @Synchronized
    fun useRestToken(now: Long = System.currentTimeMillis()): Boolean {
        val current = activeSession(now) ?: return false
        val effNow = effectiveNow(now)
        if (!current.canUseRestToken(effNow)) return false
        val updated = current.withRestTokenUsed(effNow)
        prefs.edit()
            .putLong(KEY_REST_START, updated.restBreakStartAt)
            .putLong(KEY_REST_END, updated.restBreakEndAt)
            .commit()
        _session.value = updated
        return true
    }

    @Synchronized
    fun endBreakEarly(now: Long = System.currentTimeMillis()): Boolean {
        val current = activeSession(now) ?: return false
        val effNow = effectiveNow(now)
        if (!current.canEndBreakEarly(effNow)) return false
        val updated = current.withBreakEndedEarly(effNow)
        prefs.edit()
            .putLong(KEY_REST_END, updated.restBreakEndAt)
            .putString(KEY_EARLY_ENDED_BREAKS, updated.earlyEndedBreakStarts.joinToString(","))
            .commit()
        _session.value = updated
        return true
    }

    @Synchronized
    fun saveEmergencyDraft(draftText: String, elapsedSeconds: Int) {
        val current = _session.value ?: return
        prefs.edit()
            .putString(KEY_EMERGENCY_DRAFT, draftText)
            .putInt(KEY_EMERGENCY_ELAPSED_SEC, elapsedSeconds)
            .putLong(KEY_EMERGENCY_SESSION_START, current.startAt)
            .apply()
        _emergencyDraftText.value = draftText
        _emergencyElapsedSeconds.value = elapsedSeconds
    }

    @Synchronized
    fun emergencyExit(essayText: String, now: Long = System.currentTimeMillis()): Boolean {
        val current = _session.value ?: return false
        val effNow = effectiveNow(now)
        val remainingMin = ((current.endAt - effNow) / 60_000L).coerceAtLeast(0L).toInt()

        val entry = HistoryEntry(
            id = "exit_${current.startAt}",
            type = HistoryType.EMERGENCY_EXIT,
            timestamp = effNow,
            durationMinutes = (current.durationMillis / 60_000L).toInt(),
            minutesRemaining = remainingMin,
            title = "Emergency Exit (${remainingMin}m remaining)",
            text = essayText,
        )
        addHistoryEntry(entry)
        clearActiveSessionInternal()
        return true
    }

    @Synchronized
    fun skipOccurrence(
        occurrenceId: String,
        title: String,
        reasonText: String,
        scheduledStartMillis: Long,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        // Allows skipping prior to start or within the one-time +60s grace period extension
        if (now > scheduledStartMillis + 60_000L) return false

        // If a session auto-started at T-0 while the user was actively typing a skip reason,
        // clear that session and stop FocusService immediately.
        if (isFocusActive(now)) {
            val active = activeSession(now)
            if (active != null && kotlin.math.abs(active.startAt - scheduledStartMillis) <= 90_000L) {
                clearActiveSessionInternal()
                if (::appContext.isInitialized) {
                    com.example.learninghelper.focus.FocusService.stop(appContext)
                }
            } else {
                return false
            }
        }

        val updatedSkips = _skippedOccurrences.value.toMutableSet().apply { add(occurrenceId) }
        saveSkippedOccurrences(updatedSkips)

        val entry = HistoryEntry(
            id = "skip_$occurrenceId",
            type = HistoryType.SKIPPED,
            timestamp = now,
            title = title.ifBlank { "Scheduled Session Skipped" },
            text = reasonText,
        )
        addHistoryEntry(entry)
        return true
    }

    @Synchronized
    fun clearExpiredSession(now: Long = System.currentTimeMillis()): FocusSession? {
        val current = _session.value ?: return null
        val effNow = effectiveNow(now)
        if (current.isActive(effNow)) return null

        val entry = HistoryEntry(
            id = "completed_${current.startAt}",
            type = HistoryType.COMPLETED,
            timestamp = current.endAt,
            durationMinutes = (current.durationMillis / 60_000L).toInt(),
            title = "Completed Focus Session",
        )
        addHistoryEntry(entry)
        clearActiveSessionInternal()
        return current
    }

    private fun clearActiveSessionInternal() {
        prefs.edit()
            .remove(KEY_START)
            .remove(KEY_END)
            .remove(KEY_WORK_MIN)
            .remove(KEY_BREAK_MIN)
            .remove(KEY_HAS_LONG_BREAK)
            .remove(KEY_REST_START)
            .remove(KEY_REST_END)
            .remove(KEY_ACCUMULATED_ELAPSED)
            .remove(KEY_ANCHOR_ELAPSED)
            .remove(KEY_ANCHOR_WALL)
            .remove(KEY_ANCHOR_BOOT_COUNT)
            .remove(KEY_EMERGENCY_DRAFT)
            .remove(KEY_EMERGENCY_ELAPSED_SEC)
            .remove(KEY_EMERGENCY_SESSION_START)
            .remove(KEY_EARLY_ENDED_BREAKS)
            .commit()
        _session.value = null
        _emergencyDraftText.value = ""
        _emergencyElapsedSeconds.value = 0
        _grandfatheredCallPackage.value = null
    }

    fun optionalAllowedCount(current: Set<String> = _allowlist.value): Int =
        current.count { it !in DEFAULT_ALLOWLIST }

    @Synchronized
    fun setAllowed(packageName: String, allowed: Boolean): Boolean {
        if (isModificationLocked()) return false
        val current = _allowlist.value
        val isDefault = packageName in DEFAULT_ALLOWLIST
        if (allowed && packageName !in current && !isDefault) {
            val optionalCount = current.count { it !in DEFAULT_ALLOWLIST }
            if (optionalCount >= MAX_OPTIONAL_ALLOWED_APPS) {
                return false
            }
        }
        val updated = current.toMutableSet().apply {
            if (allowed) add(packageName) else remove(packageName)
        }
        prefs.edit().putStringSet(KEY_ALLOWLIST, HashSet(updated)).apply()
        _allowlist.value = updated
        triggerExternalBackup()
        return true
    }

    @Synchronized
    fun upsertSchedule(item: ScheduledItem): Boolean {
        if (isModificationLocked()) return false
        val current = _schedules.value.toMutableList()
        val index = current.indexOfFirst { it.id == item.id }
        if (index >= 0) current[index] = item else current.add(item)
        saveSchedules(current)
        return true
    }

    @Synchronized
    fun deleteSchedule(id: String): Boolean {
        if (isModificationLocked()) return false
        val current = _schedules.value.filterNot { it.id == id }
        saveSchedules(current)
        return true
    }

    @Synchronized
    fun setMinWritingMinutes(minutes: Int): Boolean {
        if (isModificationLocked()) return false
        val clean = minutes.coerceIn(1, 15)
        prefs.edit().putInt(KEY_MIN_WRITING_MINUTES, clean).apply()
        _minWritingMinutes.value = clean
        triggerExternalBackup()
        return true
    }

    @Synchronized
    fun setAlarmSoundUri(uriString: String?): Boolean {
        if (isModificationLocked()) return false
        prefs.edit().putString(KEY_ALARM_SOUND_URI, uriString).apply()
        _alarmSoundUri.value = uriString
        return true
    }

    @Synchronized
    fun recordMilestoneFired(milestoneKey: String) {
        val updated = _firedMilestones.value.toMutableSet().apply { add(milestoneKey) }
        prefs.edit().putString(KEY_FIRED_MILESTONES_JSON, serializeStringSet(updated)).apply()
        _firedMilestones.value = updated
    }

    fun recentSkipReasons(now: Long = System.currentTimeMillis()): List<String> {
        return _history.value
            .filter { it.type == HistoryType.SKIPPED && (now - it.timestamp) <= SEVEN_DAYS_MILLIS }
            .map { it.text }
            .filter { it.isNotBlank() }
    }

    private fun addHistoryEntry(entry: HistoryEntry) {
        val now = System.currentTimeMillis()
        val cleaned = _history.value.filterNot {
            it.type == HistoryType.SKIPPED && (now - it.timestamp) > SEVEN_DAYS_MILLIS
        }
        val updated = (listOf(entry) + cleaned).take(200)
        prefs.edit().putString(KEY_HISTORY_JSON, serializeHistoryJson(updated)).apply()
        _history.value = updated
        triggerExternalBackup()
    }

    private fun saveSchedules(items: List<ScheduledItem>) {
        prefs.edit().putString(KEY_SCHEDULES_JSON, serializeSchedulesJson(items)).apply()
        _schedules.value = items
        triggerExternalBackup()
    }

    private fun saveSkippedOccurrences(items: Set<String>) {
        prefs.edit().putString(KEY_SKIPPED_OCCURRENCES_JSON, serializeStringSet(items)).apply()
        _skippedOccurrences.value = items
        triggerExternalBackup()
    }

    private val backupExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
    private var pendingBackupTask: java.util.concurrent.ScheduledFuture<*>? = null

    fun triggerExternalBackup(debounceMs: Long = 500L) {
        if (!::appContext.isInitialized) return
        if (!com.example.learninghelper.focus.Permissions.hasAllFilesAccess()) return
        synchronized(this) {
            pendingBackupTask?.cancel(false)
            pendingBackupTask = backupExecutor.schedule({
                val snapshot = BackupSnapshot(
                    schemaVersion = 1,
                    updatedAt = System.currentTimeMillis(),
                    schedules = _schedules.value,
                    history = _history.value,
                    skippedOccurrences = _skippedOccurrences.value,
                    allowlist = _allowlist.value,
                    minWritingMinutes = _minWritingMinutes.value,
                )
                ExternalBackupStorage.exportBackup(snapshot)
            }, debounceMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }

    @Synchronized
    fun syncWithExternalBackup(context: Context) {
        init(context)
        if (!com.example.learninghelper.focus.Permissions.hasAllFilesAccess()) return
        val backup = ExternalBackupStorage.readBackup() ?: run {
            triggerExternalBackup(0L)
            return
        }

        // Restore custom minWritingMinutes if not yet customized in local prefs
        if (!prefs.contains(KEY_MIN_WRITING_MINUTES) && backup.minWritingMinutes != _minWritingMinutes.value) {
            _minWritingMinutes.value = backup.minWritingMinutes
            prefs.edit().putInt(KEY_MIN_WRITING_MINUTES, backup.minWritingMinutes).apply()
        }

        // Merge schedules: keep local by ID, append missing backup schedules
        val localSchedules = _schedules.value.toMutableList()
        val localSchedIds = localSchedules.map { it.id }.toSet()
        var schedChanged = false
        for (sched in backup.schedules) {
            if (sched.id !in localSchedIds) {
                localSchedules.add(sched)
                schedChanged = true
            }
        }
        if (schedChanged) {
            prefs.edit().putString(KEY_SCHEDULES_JSON, serializeSchedulesJson(localSchedules)).apply()
            _schedules.value = localSchedules
        }

        // Merge history: deduplicate by ID, sort descending by timestamp, cap at 200
        val localHist = _history.value.toMutableList()
        val localHistIds = localHist.map { it.id }.toSet()
        var histChanged = false
        for (entry in backup.history) {
            if (entry.id !in localHistIds) {
                localHist.add(entry)
                histChanged = true
            }
        }
        if (histChanged) {
            val mergedHist = localHist.sortedByDescending { it.timestamp }.take(200)
            prefs.edit().putString(KEY_HISTORY_JSON, serializeHistoryJson(mergedHist)).apply()
            _history.value = mergedHist
        }

        // Merge skipped occurrences
        val mergedSkips = (_skippedOccurrences.value + backup.skippedOccurrences)
        if (mergedSkips.size != _skippedOccurrences.value.size) {
            prefs.edit().putString(KEY_SKIPPED_OCCURRENCES_JSON, serializeStringSet(mergedSkips)).apply()
            _skippedOccurrences.value = mergedSkips
        }

        // Merge allowlist ensuring DEFAULT_ALLOWLIST remains intact
        val mergedAllowlist = (_allowlist.value + backup.allowlist + DEFAULT_ALLOWLIST).toMutableSet()
        if (mergedAllowlist.size != _allowlist.value.size) {
            prefs.edit().putStringSet(KEY_ALLOWLIST, HashSet(mergedAllowlist)).apply()
            _allowlist.value = mergedAllowlist
        }

        triggerExternalBackup(0L)
    }

    private fun serializeSchedulesJson(items: List<ScheduledItem>): String {
        val array = JSONArray()
        for (item in items) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("label", item.label)
                put("type", item.type.name)
                put("startHour", item.startHour)
                put("startMinute", item.startMinute)
                put("endHour", item.endHour)
                put("endMinute", item.endMinute)
                put("durationMinutes", item.durationMinutes)
                put("workMinutes", item.workMinutes)
                put("breakMinutes", item.breakMinutes)
                put("hasLongBreak", item.hasLongBreak)
                put("enabled", item.enabled)
                put("year", item.year)
                put("month", item.month)
                put("dayOfMonth", item.dayOfMonth)
                val daysArr = JSONArray()
                item.daysOfWeek.forEach { daysArr.put(it.name) }
                put("daysOfWeek", daysArr)
            }
            array.put(obj)
        }
        return array.toString()
    }

    private fun parseSchedulesJson(jsonStr: String): List<ScheduledItem> = runCatching {
        val array = JSONArray(jsonStr)
        val list = mutableListOf<ScheduledItem>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val days = mutableSetOf<DayOfWeek>()
            val daysArr = obj.optJSONArray("daysOfWeek")
            if (daysArr != null) {
                for (d in 0 until daysArr.length()) {
                    runCatching { days.add(DayOfWeek.valueOf(daysArr.getString(d))) }
                }
            }
            val sH = obj.getInt("startHour")
            val sM = obj.getInt("startMinute")
            val dur = obj.optInt("durationMinutes", 50)
            val eH = obj.optInt("endHour", (sH + (sM + dur) / 60) % 24)
            val eM = obj.optInt("endMinute", (sM + dur) % 60)
            list.add(
                ScheduledItem(
                    id = obj.getString("id"),
                    label = obj.optString("label", ""),
                    type = ScheduleType.valueOf(obj.getString("type")),
                    daysOfWeek = days,
                    year = obj.optInt("year", 0),
                    month = obj.optInt("month", 0),
                    dayOfMonth = obj.optInt("dayOfMonth", 0),
                    startHour = sH,
                    startMinute = sM,
                    durationMinutes = dur,
                    workMinutes = obj.optInt("workMinutes", 50),
                    breakMinutes = obj.optInt("breakMinutes", 0),
                    enabled = obj.optBoolean("enabled", true),
                    endHour = eH,
                    endMinute = eM,
                    hasLongBreak = obj.optBoolean("hasLongBreak", false),
                )
            )
        }
        list
    }.getOrDefault(emptyList())

    private fun serializeStringSet(items: Set<String>): String {
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        return arr.toString()
    }

    private fun parseStringSetJson(jsonStr: String): Set<String> = runCatching {
        val arr = JSONArray(jsonStr)
        val set = mutableSetOf<String>()
        for (i in 0 until arr.length()) set.add(arr.getString(i))
        set
    }.getOrDefault(emptySet())

    private fun serializeHistoryJson(items: List<HistoryEntry>): String {
        val arr = JSONArray()
        for (item in items) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("type", item.type.name)
                put("timestamp", item.timestamp)
                put("durationMinutes", item.durationMinutes)
                put("minutesRemaining", item.minutesRemaining)
                put("title", item.title)
                put("text", item.text)
            }
            arr.put(obj)
        }
        return arr.toString()
    }

    private fun parseHistoryJson(jsonStr: String): List<HistoryEntry> = runCatching {
        val arr = JSONArray(jsonStr)
        val list = mutableListOf<HistoryEntry>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(
                HistoryEntry(
                    id = obj.getString("id"),
                    type = HistoryType.valueOf(obj.getString("type")),
                    timestamp = obj.getLong("timestamp"),
                    durationMinutes = obj.optInt("durationMinutes", 0),
                    minutesRemaining = obj.optInt("minutesRemaining", 0),
                    title = obj.optString("title", ""),
                    text = obj.optString("text", ""),
                )
            )
        }
        list
    }.getOrDefault(emptyList())
}
