package com.example.learninghelper.ui

import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.learninghelper.data.FocusRepository
import com.example.learninghelper.focus.AlarmScheduler
import com.example.learninghelper.focus.FocusPolicy
import com.example.learninghelper.focus.ScheduleType
import com.example.learninghelper.focus.ScheduledItem
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val schedules by FocusRepository.schedules.collectAsState()
    val isLocked = FocusRepository.isModificationLocked()
    var showAddDialog by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text("Schedules & Routines", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Button(
                onClick = { showAddDialog = true },
                enabled = !isLocked,
            ) {
                Text("+ Add")
            }
        }

        if (isLocked) {
            Text(
                "Schedules cannot be edited during focus or within 5m of a scheduled session.",
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        Spacer(Modifier.height(8.dp))

        if (schedules.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No routine scheduled yet. Tap + Add to set up study blocks.", color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(schedules, key = { it.id }) { item ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.label.ifBlank { "Focus Routine" }, fontWeight = FontWeight.SemiBold)
                                val timeStr = "%02d:%02d – %02d:%02d (%d min)".format(
                                    item.startHour, item.startMinute, item.endHour, item.endMinute, item.durationMinutes
                                )
                                val patternStr = if (item.breakMinutes > 0) "• ${item.workMinutes}/${item.breakMinutes}" else "• Solid"
                                val longBreakStr = if (item.hasLongBreak && item.durationMinutes > FocusPolicy.LONG_BREAK_THRESHOLD_MINUTES) " (+30m Long Break)" else ""
                                val daysStr = if (item.type == ScheduleType.WEEKLY) {
                                    item.daysOfWeek.sortedBy { it.value }.joinToString(", ") { it.name.take(3) }
                                } else {
                                    "%04d-%02d-%02d".format(item.year, item.month, item.dayOfMonth)
                                }
                                Text("$timeStr $patternStr$longBreakStr\n$daysStr", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Switch(
                                    checked = item.enabled,
                                    onCheckedChange = { isChecked ->
                                        FocusRepository.upsertSchedule(item.copy(enabled = isChecked))
                                        AlarmScheduler.scheduleNext(context)
                                    },
                                    enabled = !isLocked,
                                )
                                if (!isLocked) {
                                    TextButton(
                                        onClick = {
                                            FocusRepository.deleteSchedule(item.id)
                                            AlarmScheduler.scheduleNext(context)
                                        },
                                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                    ) {
                                        Text("✕")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddScheduleDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { newItem ->
                FocusRepository.upsertSchedule(newItem)
                AlarmScheduler.scheduleNext(context)
                showAddDialog = false
            }
        )
    }
}

@Composable
fun AddScheduleDialog(
    onDismiss: () -> Unit,
    onConfirm: (ScheduledItem) -> Unit,
) {
    val context = LocalContext.current
    var label by remember { mutableStateOf("Thesis Sprint") }
    var scheduleType by remember { mutableStateOf(ScheduleType.WEEKLY) }
    var selectedDays by remember {
        mutableStateOf(setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY))
    }
    val today = remember { LocalDate.now() }
    var calYear by remember { mutableIntStateOf(today.year) }
    var calMonth by remember { mutableIntStateOf(today.monthValue) }
    var calDay by remember { mutableIntStateOf(today.dayOfMonth) }

    var startHour by remember { mutableIntStateOf(9) }
    var startMinute by remember { mutableIntStateOf(0) }
    var endHour by remember { mutableIntStateOf(9) }
    var endMinute by remember { mutableIntStateOf(50) }

    var workText by remember { mutableStateOf("50") }
    var breakText by remember { mutableStateOf("0") }
    var hasLongBreak by remember { mutableStateOf(false) }

    val allDays = DayOfWeek.values()

    val durationMin = FocusPolicy.computeDurationMinutes(startHour, startMinute, endHour, endMinute)
    val parsedWork = workText.toIntOrNull()
    val parsedBreak = breakText.toIntOrNull()
    val isIntervalValid = parsedWork != null && parsedWork >= FocusPolicy.MIN_DURATION_MINUTES &&
        parsedBreak != null && parsedBreak in 0..120
    val isDurationValid = durationMin >= FocusPolicy.MIN_DURATION_MINUTES

    val isPastDateTime = remember(scheduleType, calYear, calMonth, calDay, startHour, startMinute) {
        if (scheduleType == ScheduleType.CALENDAR_DATE) {
            try {
                LocalDateTime.of(calYear, calMonth, calDay, startHour, startMinute).isBefore(LocalDateTime.now())
            } catch (e: Exception) {
                false
            }
        } else false
    }

    val isValid = (scheduleType != ScheduleType.WEEKLY || selectedDays.isNotEmpty()) &&
        !isPastDateTime && isDurationValid && isIntervalValid

    val openDatePicker = {
        android.app.DatePickerDialog(
            context,
            { _, y, m, d ->
                calYear = y
                calMonth = m + 1
                calDay = d
            },
            calYear,
            calMonth - 1,
            calDay
        ).apply {
            datePicker.minDate = System.currentTimeMillis() - 1000L
        }.show()
    }

    val openStartPicker = {
        TimePickerDialog(
            context,
            { _, h, m ->
                startHour = h
                startMinute = m
            },
            startHour,
            startMinute,
            true,
        ).show()
    }

    val openEndPicker = {
        TimePickerDialog(
            context,
            { _, h, m ->
                endHour = h
                endMinute = m
            },
            endHour,
            endMinute,
            true,
        ).show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Focus Routine") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("Type", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = scheduleType == ScheduleType.WEEKLY,
                        onClick = { scheduleType = ScheduleType.WEEKLY },
                        label = { Text("Weekly") },
                    )
                    FilterChip(
                        selected = scheduleType == ScheduleType.CALENDAR_DATE,
                        onClick = { scheduleType = ScheduleType.CALENDAR_DATE },
                        label = { Text("Specific Date") },
                    )
                }

                if (scheduleType == ScheduleType.WEEKLY) {
                    Text("Days of Week", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        allDays.forEach { day ->
                            val selected = day in selectedDays
                            val abbr = day.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    selectedDays = if (selected) {
                                        if (selectedDays.size > 1) selectedDays - day else selectedDays
                                    } else {
                                        selectedDays + day
                                    }
                                },
                                label = { Text(abbr) },
                            )
                        }
                    }
                } else {
                    Text("Date (YYYY-MM-DD)", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = "%04d-%02d-%02d".format(calYear, calMonth, calDay),
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier.weight(1f).clickable { openDatePicker() },
                            label = { Text("Target Date") },
                        )
                        Button(onClick = openDatePicker) {
                            Text("Pick")
                        }
                    }
                }

                Text("Session Time Window", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = openStartPicker,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Start: %02d:%02d".format(startHour, startMinute))
                    }
                    OutlinedButton(
                        onClick = openEndPicker,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("End: %02d:%02d".format(endHour, endMinute))
                    }
                }

                val h = durationMin / 60
                val m = durationMin % 60
                val durFormatted = if (h > 0) "${h}h ${m}m ($durationMin min)" else "$durationMin min"
                Text(
                    "Total Duration: $durFormatted",
                    fontSize = 13.sp,
                    color = if (isDurationValid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium,
                )

                Text("Custom Interval Pattern (Minutes)", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = workText,
                        onValueChange = { input ->
                            if (input.length <= 4 && input.all { it.isDigit() }) workText = input
                        },
                        label = { Text("Work (min)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = breakText,
                        onValueChange = { input ->
                            if (input.length <= 3 && input.all { it.isDigit() }) breakText = input
                        },
                        label = { Text("Break (min)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FocusPolicy.PRESET_INTERVALS.forEach { preset ->
                        val (w, b) = preset
                        val isSelected = parsedWork == w && parsedBreak == b
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                workText = w.toString()
                                breakText = b.toString()
                            },
                            label = { Text(if (b > 0) "$w/$b" else "Solid") },
                        )
                    }
                }

                if (durationMin > FocusPolicy.LONG_BREAK_THRESHOLD_MINUTES) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("30-min Long Break", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text("Adds a 30m break near midpoint (>2h session).", fontSize = 11.sp)
                            }
                            Switch(
                                checked = hasLongBreak,
                                onCheckedChange = { hasLongBreak = it },
                            )
                        }
                    }
                }

                if (!isDurationValid) {
                    Text(
                        "End time must be at least ${FocusPolicy.MIN_DURATION_MINUTES}m after start time.",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                    )
                } else if (!isIntervalValid) {
                    Text(
                        "Enter Work >= ${FocusPolicy.MIN_DURATION_MINUTES}m and Break 0–120m.",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                    )
                } else if (isPastDateTime) {
                    Text("Cannot schedule a session in the past.", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                } else if (scheduleType == ScheduleType.WEEKLY && selectedDays.isEmpty()) {
                    Text("Select at least one day.", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val breakMin = parsedBreak ?: 0
                    val workMin = if (breakMin > 0) (parsedWork ?: durationMin) else durationMin
                    val longBreakEnabled = hasLongBreak && durationMin > FocusPolicy.LONG_BREAK_THRESHOLD_MINUTES
                    onConfirm(
                        ScheduledItem(
                            id = UUID.randomUUID().toString(),
                            label = label.ifBlank { "Focus Routine" },
                            type = scheduleType,
                            daysOfWeek = if (scheduleType == ScheduleType.WEEKLY) selectedDays else emptySet(),
                            year = if (scheduleType == ScheduleType.CALENDAR_DATE) calYear else 0,
                            month = if (scheduleType == ScheduleType.CALENDAR_DATE) calMonth else 0,
                            dayOfMonth = if (scheduleType == ScheduleType.CALENDAR_DATE) calDay else 0,
                            startHour = startHour,
                            startMinute = startMinute,
                            durationMinutes = durationMin,
                            workMinutes = workMin,
                            breakMinutes = breakMin,
                            enabled = true,
                            endHour = endHour,
                            endMinute = endMinute,
                            hasLongBreak = longBreakEnabled,
                        )
                    )
                },
                enabled = isValid,
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
