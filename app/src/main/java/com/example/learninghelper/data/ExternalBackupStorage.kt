package com.example.learninghelper.data

import android.os.Environment
import com.example.learninghelper.focus.ScheduleType
import com.example.learninghelper.focus.ScheduledItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek

data class BackupSnapshot(
    val schemaVersion: Int = 1,
    val updatedAt: Long = System.currentTimeMillis(),
    val schedules: List<ScheduledItem> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    val skippedOccurrences: Set<String> = emptySet(),
    val allowlist: Set<String> = emptySet(),
    val minWritingMinutes: Int = 3,
)

object ExternalBackupStorage {

    private const val DIRECTORY_NAME = "LearningHelper"
    private const val BACKUP_FILE_NAME = "learning_helper_backup.json"
    private const val TMP_FILE_NAME = "learning_helper_backup.json.tmp"

    fun getBackupFile(): File {
        val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val appDir = File(docsDir, DIRECTORY_NAME)
        if (!appDir.exists()) {
            appDir.mkdirs()
        }
        return File(appDir, BACKUP_FILE_NAME)
    }

    private fun getTempFile(): File {
        val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val appDir = File(docsDir, DIRECTORY_NAME)
        if (!appDir.exists()) {
            appDir.mkdirs()
        }
        return File(appDir, TMP_FILE_NAME)
    }

    /**
     * Atomically exports the backup snapshot to the external document directory.
     * Writes to a temporary file first and renames to prevent partial write corruption.
     */
    @Synchronized
    fun exportBackup(snapshot: BackupSnapshot): Boolean = runCatching {
        val jsonStr = serializeSnapshot(snapshot)
        val tmp = getTempFile()
        val target = getBackupFile()

        tmp.writeText(jsonStr, Charsets.UTF_8)
        val renamed = tmp.renameTo(target)
        if (!renamed) {
            // Fallback byte copy if direct rename is rejected by filesystem/storage driver
            tmp.inputStream().use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            tmp.delete()
        }
        true
    }.getOrDefault(false)

    /**
     * Reads and parses the external backup snapshot.
     * Employs fault-tolerant per-item parsing so corrupted or future items don't fail the rest.
     */
    @Synchronized
    fun readBackup(): BackupSnapshot? = runCatching {
        val target = getBackupFile()
        val tmp = getTempFile()
        val fileToRead = when {
            target.exists() && target.length() > 0L -> target
            tmp.exists() && tmp.length() > 0L -> tmp
            else -> return null
        }
        val jsonStr = fileToRead.readText(Charsets.UTF_8)
        parseSnapshot(jsonStr)
    }.getOrNull()

    fun serializeSnapshot(snapshot: BackupSnapshot): String {
        val root = JSONObject()
        root.put("schemaVersion", snapshot.schemaVersion)
        root.put("updatedAt", snapshot.updatedAt)
        root.put("minWritingMinutes", snapshot.minWritingMinutes)

        val schedArray = JSONArray()
        for (item in snapshot.schedules) {
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
            schedArray.put(obj)
        }
        root.put("schedules", schedArray)

        val histArray = JSONArray()
        for (item in snapshot.history) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("type", item.type.name)
                put("timestamp", item.timestamp)
                put("durationMinutes", item.durationMinutes)
                put("minutesRemaining", item.minutesRemaining)
                put("title", item.title)
                put("text", item.text)
            }
            histArray.put(obj)
        }
        root.put("history", histArray)

        val skipArray = JSONArray()
        snapshot.skippedOccurrences.forEach { skipArray.put(it) }
        root.put("skippedOccurrences", skipArray)

        val allowArray = JSONArray()
        snapshot.allowlist.forEach { allowArray.put(it) }
        root.put("allowlist", allowArray)

        return root.toString(2)
    }

    fun parseSnapshot(jsonStr: String): BackupSnapshot = runCatching {
        val root = JSONObject(jsonStr)
        val schemaVer = root.optInt("schemaVersion", 1)
        val updatedAt = root.optLong("updatedAt", System.currentTimeMillis())
        val minWriting = root.optInt("minWritingMinutes", 3).coerceIn(1, 15)

        val schedulesList = mutableListOf<ScheduledItem>()
        val schedArray = root.optJSONArray("schedules")
        if (schedArray != null) {
            for (i in 0 until schedArray.length()) {
                runCatching {
                    val obj = schedArray.getJSONObject(i)
                    val typeName = obj.optString("type", ScheduleType.WEEKLY.name)
                    val scheduleType = ScheduleType.valueOf(typeName)
                    val sH = obj.getInt("startHour")
                    val sM = obj.getInt("startMinute")
                    val dur = obj.optInt("durationMinutes", 50)
                    val eH = obj.optInt("endHour", (sH + (sM + dur) / 60) % 24)
                    val eM = obj.optInt("endMinute", (sM + dur) % 60)

                    val days = mutableSetOf<DayOfWeek>()
                    val daysArr = obj.optJSONArray("daysOfWeek")
                    if (daysArr != null) {
                        for (d in 0 until daysArr.length()) {
                            runCatching { days.add(DayOfWeek.valueOf(daysArr.getString(d))) }
                        }
                    }

                    schedulesList.add(
                        ScheduledItem(
                            id = obj.getString("id"),
                            label = obj.optString("label", ""),
                            type = scheduleType,
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
            }
        }

        val historyList = mutableListOf<HistoryEntry>()
        val histArray = root.optJSONArray("history")
        if (histArray != null) {
            for (i in 0 until histArray.length()) {
                runCatching {
                    val obj = histArray.getJSONObject(i)
                    val typeName = obj.getString("type")
                    val histType = HistoryType.valueOf(typeName)
                    historyList.add(
                        HistoryEntry(
                            id = obj.getString("id"),
                            type = histType,
                            timestamp = obj.getLong("timestamp"),
                            durationMinutes = obj.optInt("durationMinutes", 0),
                            minutesRemaining = obj.optInt("minutesRemaining", 0),
                            title = obj.optString("title", ""),
                            text = obj.optString("text", ""),
                        )
                    )
                }
            }
        }

        val skips = mutableSetOf<String>()
        val skipArray = root.optJSONArray("skippedOccurrences")
        if (skipArray != null) {
            for (i in 0 until skipArray.length()) {
                val str = skipArray.optString(i, "")
                if (str.isNotBlank()) skips.add(str)
            }
        }

        val allows = mutableSetOf<String>()
        val allowArray = root.optJSONArray("allowlist")
        if (allowArray != null) {
            for (i in 0 until allowArray.length()) {
                val str = allowArray.optString(i, "")
                if (str.isNotBlank()) allows.add(str)
            }
        }

        BackupSnapshot(
            schemaVersion = schemaVer,
            updatedAt = updatedAt,
            schedules = schedulesList,
            history = historyList,
            skippedOccurrences = skips,
            allowlist = allows,
            minWritingMinutes = minWriting,
        )
    }.getOrDefault(BackupSnapshot())
}
