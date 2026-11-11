package com.example.learninghelper.ui

import android.Manifest
import android.app.TimePickerDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.learninghelper.data.FocusRepository
import com.example.learninghelper.focus.*
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalTime

@Composable
fun HomeScreen(
    resumeTick: Int,
    onOpenAllowlist: () -> Unit,
    onOpenSchedule: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDeskClock: () -> Unit,
    onOpenEmergencyExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val session by FocusRepository.session.collectAsState()
    val allowlist by FocusRepository.allowlist.collectAsState()
    val schedules by FocusRepository.schedules.collectAsState()
    val skippedOccurrences by FocusRepository.skippedOccurrences.collectAsState()

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var isStarted by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_START) { isStarted = true }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { isStarted = false }

    val hasSession = session != null
    LaunchedEffect(isStarted, hasSession) {
        if (!hasSession) {
            now = System.currentTimeMillis()
            return@LaunchedEffect
        }
        while (isStarted && session != null) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    val effNow = FocusRepository.effectiveNow(now)
    val active = session?.takeIf { it.isActive(effNow) }

    val usageStatsOn = remember(resumeTick) { Permissions.hasUsageStatsPermission(context) }
    val overlayOn = remember(resumeTick) { Permissions.canDrawOverlays(context) }
    val notifListenerOn = remember(resumeTick) { Permissions.isNotificationListenerEnabled(context) }
    val allFilesOn = remember(resumeTick) { Permissions.hasAllFilesAccess() }
    var notificationsOk by remember(resumeTick) { mutableStateOf(FocusNotifications.canPost(context)) }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> notificationsOk = granted }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Thesis Lock-In", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        if (active != null) {
            ActiveSessionCard(
                session = active,
                effNow = effNow,
                allowlistSize = allowlist.size,
                onOpenDeskClock = onOpenDeskClock,
                onOpenEmergencyExit = onOpenEmergencyExit,
                onUseRestToken = {
                    FocusRepository.useRestToken(now)
                    FocusService.triggerBreakAlert(context)
                    AlarmScheduler.scheduleNext(context)
                },
                onEndBreakEarly = {
                    AlarmPlayer.stop()
                    FocusRepository.endBreakEarly(now)
                    FocusService.start(context)
                    AlarmScheduler.scheduleNext(context)
                },
            )
        } else {
            if (!usageStatsOn) {
                SetupCard(
                    title = "Usage Access Permission (Required)",
                    body = "Needed to detect and block distracting apps during your focus session.",
                    action = "Grant Usage Access",
                    onAction = { context.startActivity(Permissions.usageAccessSettingsIntent(context)) },
                )
            }
            if (!overlayOn) {
                SetupCard(
                    title = "Appear on Top / Overlay (Required)",
                    body = "Used for the T-2m countdown overlay and floating break timer.",
                    action = "Grant Permission",
                    onAction = { context.startActivity(Permissions.overlaySettingsIntent(context)) },
                )
            }
            if (!notifListenerOn) {
                SetupCard(
                    title = "Notification Blocker (Recommended)",
                    body = "Blocks notifications except phone calls during focus.",
                    action = "Enable Listener",
                    onAction = { context.startActivity(Permissions.notificationListenerSettingsIntent()) },
                )
            }
            if (!allFilesOn) {
                SetupCard(
                    title = "Uninstall-Proof Backup (Recommended)",
                    body = "Allows restoring your focus history and schedules even if the app is uninstalled.",
                    action = "Grant Storage Access",
                    onAction = { context.startActivity(Permissions.allFilesAccessIntent(context)) },
                )
            }
            if (!notificationsOk) {
                SetupCard(
                    title = "Allow Notifications",
                    body = "Shows the ongoing focus countdown and completion notice.",
                    action = "Allow",
                    onAction = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                )
            }

            val upcoming = remember(schedules, skippedOccurrences, now) {
                ScheduleCalculator.findNextOccurrence(schedules, skippedOccurrences, now)
            }
            if (upcoming != null) {
                UpcomingSessionCard(
                    upcoming = upcoming,
                    isLocked = FocusRepository.isModificationLocked(now),
                    onCancel = {
                        FocusRepository.deleteSchedule(upcoming.item.id)
                        AlarmScheduler.scheduleNext(context)
                    },
                )
            }

            StartSessionCard(
                enabled = usageStatsOn && overlayOn,
                onStartNow = { duration, work, breakMin, hasLongBreak ->
                    FocusRepository.startSession(duration, work, breakMin, now, hasLongBreak)
                    FocusService.start(context)
                },
                onScheduleFuture = { sH, sM, eH, eM, dur, w, b, longBreak ->
                    val today = LocalDate.now()
                    val curTime = LocalTime.now()
                    val isLaterToday = sH > curTime.hour || (sH == curTime.hour && sM > curTime.minute)
                    val targetDate = if (isLaterToday) today else today.plusDays(1)
                    val item = ScheduledItem(
                        id = "home_sched_${System.currentTimeMillis()}",
                        label = "Focus Session",
                        type = ScheduleType.CALENDAR_DATE,
                        year = targetDate.year,
                        month = targetDate.monthValue,
                        dayOfMonth = targetDate.dayOfMonth,
                        startHour = sH,
                        startMinute = sM,
                        endHour = eH,
                        endMinute = eM,
                        durationMinutes = dur,
                        workMinutes = w,
                        breakMinutes = b,
                        hasLongBreak = longBreak,
                    )
                    FocusRepository.upsertSchedule(item)
                    AlarmScheduler.scheduleNext(context)
                },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onOpenSchedule, modifier = Modifier.weight(1f)) {
                    Text("Schedule")
                }
                OutlinedButton(onClick = onOpenAllowlist, modifier = Modifier.weight(1f)) {
                    Text("Apps (${allowlist.size})")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onOpenHistory, modifier = Modifier.weight(1f)) {
                    Text("History")
                }
                OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) {
                    Text("Settings")
                }
            }
        }
    }
}

@Composable
private fun ActiveSessionCard(
    session: FocusSession,
    effNow: Long,
    allowlistSize: Int,
    onOpenDeskClock: () -> Unit,
    onOpenEmergencyExit: () -> Unit,
    onUseRestToken: () -> Unit,
    onEndBreakEarly: () -> Unit,
) {
    val phase = session.currentPhase(effNow)
    val isBreak = phase == SessionPhase.BREAK
    var showConfirmEndBreak by remember { mutableStateOf(false) }

    LaunchedEffect(isBreak) {
        if (!isBreak) showConfirmEndBreak = false
    }

    if (showConfirmEndBreak) {
        AlertDialog(
            onDismissRequest = { showConfirmEndBreak = false },
            title = { Text("End Break Early?") },
            text = { Text("Are you sure you want to end your break now and lock back into Focus Mode immediately?") },
            confirmButton = {
                Button(onClick = {
                    showConfirmEndBreak = false
                    onEndBreakEarly()
                }) {
                    Text("Yes, Resume Work")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showConfirmEndBreak = false }) {
                    Text("Keep Resting")
                }
            },
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isBreak) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (isBreak) "Break Time" else "Focus Mode Active",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )

            AnalogStopwatchDial(
                progress = session.progress(effNow),
                timeFormatted = FocusPolicy.formatDuration(session.remainingMillis(effNow)),
                phaseLabel = if (isBreak) "BREAK" else "FOCUS",
                isBreak = isBreak,
                dialSize = 210.dp,
            )

            Text(
                if (isBreak) "Apps temporarily unlocked" else "Only calls & $allowlistSize allowed apps work",
                style = MaterialTheme.typography.bodyMedium,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenDeskClock) {
                    Text("Desk Clock (AOD)")
                }
                if (session.canUseRestToken(effNow)) {
                    OutlinedButton(onClick = onUseRestToken) {
                        Text("Spend Rest Token (30m)")
                    }
                } else if (isBreak) {
                    OutlinedButton(onClick = { showConfirmEndBreak = true }) {
                        Text("End Break Early")
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = onOpenEmergencyExit,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text("Emergency Exit (100-word essay)")
            }
        }
    }
}

@Composable
private fun StartSessionCard(
    enabled: Boolean,
    onStartNow: (Int, Int, Int, Boolean) -> Unit,
    onScheduleFuture: (Int, Int, Int, Int, Int, Int, Int, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val initialNow = remember { LocalTime.now() }
    val initialEnd = remember { initialNow.plusMinutes(50) }

    var startHour by rememberSaveable { mutableIntStateOf(initialNow.hour) }
    var startMinute by rememberSaveable { mutableIntStateOf(initialNow.minute) }
    var endHour by rememberSaveable { mutableIntStateOf(initialEnd.hour) }
    var endMinute by rememberSaveable { mutableIntStateOf(initialEnd.minute) }

    var workText by rememberSaveable { mutableStateOf("50") }
    var breakText by rememberSaveable { mutableStateOf("0") }
    var hasLongBreak by rememberSaveable { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }

    val durationMinutes = FocusPolicy.computeDurationMinutes(startHour, startMinute, endHour, endMinute)
    val parsedWork = workText.toIntOrNull()
    val parsedBreak = breakText.toIntOrNull()
    val isIntervalValid = parsedWork != null && parsedWork >= FocusPolicy.MIN_DURATION_MINUTES &&
        parsedBreak != null && parsedBreak in 0..120
    val isDurationValid = durationMinutes >= FocusPolicy.MIN_DURATION_MINUTES

    val curTime = remember(startHour, startMinute) { LocalTime.now() }
    val curMinutes = curTime.hour * 60 + curTime.minute
    val startMinutes = startHour * 60 + startMinute
    val diffMinutes = if (startMinutes >= curMinutes) startMinutes - curMinutes else startMinutes - curMinutes + 24 * 60
    val isStartingNow = diffMinutes <= 1

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

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Start Focus Block", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

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
                TextButton(
                    onClick = {
                        val cur = LocalTime.now()
                        startHour = cur.hour
                        startMinute = cur.minute
                    }
                ) {
                    Text("Now")
                }
            }

            val h = durationMinutes / 60
            val m = durationMinutes % 60
            val durFormatted = if (h > 0) "${h}h ${m}m ($durationMinutes min)" else "$durationMinutes min"
            Text(
                "Session Window: $durFormatted",
                style = MaterialTheme.typography.bodyMedium,
                color = if (isDurationValid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Medium,
            )
            if (isDurationValid) {
                val startLabel = if (isStartingNow) "Starting immediately" else "Starts in ${if (diffMinutes >= 60) "${diffMinutes / 60}h ${diffMinutes % 60}m" else "$diffMinutes min"} (at %02d:%02d)".format(startHour, startMinute)
                Text(
                    startLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isStartingNow) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (!isDurationValid) {
                Text(
                    "End time must be at least ${FocusPolicy.MIN_DURATION_MINUTES} minutes after start time.",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                )
            }

            Text("Custom Interval Pattern (Minutes)", style = MaterialTheme.typography.labelLarge)
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

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

            if (!isIntervalValid) {
                Text(
                    "Enter Work >= ${FocusPolicy.MIN_DURATION_MINUTES} min and Break 0–120 min.",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                )
            }

            if (durationMinutes > FocusPolicy.LONG_BREAK_THRESHOLD_MINUTES) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Include 30-min Long Break", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(
                                "Adds a 30m extended break near the midpoint of this >2h session.",
                                fontSize = 12.sp,
                            )
                        }
                        Switch(
                            checked = hasLongBreak,
                            onCheckedChange = { hasLongBreak = it },
                        )
                    }
                }
            }

            Button(
                onClick = { confirming = true },
                enabled = enabled && isDurationValid && isIntervalValid,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isStartingNow) "Lock Me In Now" else "Schedule Lock-In (Starts at %02d:%02d)".format(startHour, startMinute))
            }
        }
    }

    if (confirming && isDurationValid && isIntervalValid) {
        val workMin = if ((parsedBreak ?: 0) > 0) (parsedWork ?: durationMinutes) else durationMinutes
        val breakMin = parsedBreak ?: 0
        val longBreakEnabled = hasLongBreak && durationMinutes > FocusPolicy.LONG_BREAK_THRESHOLD_MINUTES
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = {
                val titleText = if (isStartingNow) {
                    "Lock in now until %02d:%02d ($durationMinutes min)?".format(endHour, endMinute)
                } else {
                    "Schedule Lock-In from %02d:%02d to %02d:%02d ($durationMinutes min)?".format(startHour, startMinute, endHour, endMinute)
                }
                Text(titleText)
            },
            text = {
                val breakSummary = buildString {
                    if (breakMin > 0) append("Intervals: ${workMin}m work / ${breakMin}m break.")
                    else append("Continuous focus block.")
                    if (longBreakEnabled) append(" Includes a 30-minute mid-session long break.")
                }
                val warningText = if (isStartingNow) {
                    "$breakSummary\n\nDistracting apps will be blocked immediately until the timer finishes. Emergency exit requires a 100-word essay."
                } else {
                    "$breakSummary\n\nThis session will automatically lock in at %02d:%02d.\nAudible alarms will sound at T-5m and T-2m.\n\nApps will NOT be blocked until the scheduled start time.".format(startHour, startMinute)
                }
                Text(warningText)
            },
            confirmButton = {
                Button(onClick = {
                    confirming = false
                    if (isStartingNow) {
                        onStartNow(durationMinutes, workMin, breakMin, longBreakEnabled)
                    } else {
                        onScheduleFuture(startHour, startMinute, endHour, endMinute, durationMinutes, workMin, breakMin, longBreakEnabled)
                    }
                }) {
                    Text(if (isStartingNow) "Confirm & Lock Now" else "Confirm & Schedule")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun UpcomingSessionCard(
    upcoming: UpcomingOccurrence,
    isLocked: Boolean,
    onCancel: () -> Unit,
) {
    val remainingMs = (upcoming.startAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
    val remMin = (remainingMs / 60_000L).toInt()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Upcoming Focus Session",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                if (remMin <= 5) {
                    SuggestionChip(
                        onClick = {},
                        label = { Text("Pre-Session", fontSize = 11.sp) },
                    )
                }
            }

            Text(
                "${upcoming.item.label}: %02d:%02d – %02d:%02d (%d min)".format(
                    upcoming.item.startHour, upcoming.item.startMinute,
                    upcoming.item.endHour, upcoming.item.endMinute,
                    upcoming.item.durationMinutes,
                ),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )

            val startsInText = if (remMin >= 60) "${remMin / 60}h ${remMin % 60}m" else "$remMin min"
            Text(
                "Starts in $startsInText • Warnings sound at T-5m and T-2m",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
            )

            if (!isLocked) {
                OutlinedButton(
                    onClick = onCancel,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("Cancel Schedule")
                }
            }
        }
    }
}

@Composable
private fun SetupCard(title: String, body: String, action: String, onAction: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onAction) { Text(action) }
        }
    }
}
