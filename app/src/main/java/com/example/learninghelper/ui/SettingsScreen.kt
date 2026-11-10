package com.example.learninghelper.ui

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.learninghelper.data.FocusRepository
import com.example.learninghelper.focus.Permissions

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val minWritingMinutes by FocusRepository.minWritingMinutes.collectAsState()
    val alarmSoundUri by FocusRepository.alarmSoundUri.collectAsState()
    val isLocked = FocusRepository.isModificationLocked()

    var checkTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        checkTick++
    }

    val usageStatsOn = remember(checkTick) { Permissions.hasUsageStatsPermission(context) }
    val overlayOn = remember(checkTick) { Permissions.canDrawOverlays(context) }
    val notifListenerOn = remember(checkTick) { Permissions.isNotificationListenerEnabled(context) }
    val allFilesOn = remember(checkTick) { Permissions.hasAllFilesAccess() }
    val batteryOptOn = remember(checkTick) { Permissions.isIgnoringBatteryOptimizations(context) }

    val ringtonePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            FocusRepository.setAlarmSoundUri(uri?.toString())
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        if (isLocked) {
            Text(
                "🔒 Settings cannot be modified during active focus or right before a scheduled session.",
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
            )
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Emergency Exit Writing Time", fontWeight = FontWeight.SemiBold)
                Text(
                    "Minimum active writing time required before the emergency exit essay can be submitted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                var sliderValue by remember(minWritingMinutes) { mutableFloatStateOf(minWritingMinutes.toFloat()) }
                Text("${sliderValue.toInt()} minutes", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = { FocusRepository.setMinWritingMinutes(sliderValue.toInt()) },
                    valueRange = 1f..15f,
                    steps = 13,
                    enabled = !isLocked,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Pre-Session Alarm Sound", fontWeight = FontWeight.SemiBold)
                Text(
                    "Played at T-5m and T-2m before scheduled focus blocks.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Button(
                    onClick = {
                        val currentUri = alarmSoundUri?.let { Uri.parse(it) }
                            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, currentUri)
                        }
                        ringtonePickerLauncher.launch(intent)
                    },
                    enabled = !isLocked,
                ) {
                    Text("Select Sound")
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Required Permissions", fontWeight = FontWeight.SemiBold)
                Text(
                    "LearningHelper requires these system permissions to enforce lock-in, display HUD countdowns, and prevent unwanted interruptions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )

                PermissionRow(
                    name = "Usage Access",
                    description = "Monitors foreground apps and blocks distractions",
                    isGranted = usageStatsOn,
                    onClick = { context.startActivity(Permissions.usageAccessSettingsIntent(context)) },
                )
                PermissionRow(
                    name = "Overlay ('Appear on top')",
                    description = "Displays the break timer floating countdown",
                    isGranted = overlayOn,
                    onClick = { context.startActivity(Permissions.overlaySettingsIntent(context)) },
                )
                PermissionRow(
                    name = "Notification Listener",
                    description = "Silences non-call notifications during study",
                    isGranted = notifListenerOn,
                    onClick = { context.startActivity(Permissions.notificationListenerSettingsIntent()) },
                )
                PermissionRow(
                    name = "All Files Access (Backup)",
                    description = "Restores focus history and schedules after reinstallation",
                    isGranted = allFilesOn,
                    onClick = { context.startActivity(Permissions.allFilesAccessIntent(context)) },
                )
                PermissionRow(
                    name = "Battery Optimization Exemption",
                    description = "Prevents Android from killing active focus timers",
                    isGranted = batteryOptOn,
                    onClick = { context.startActivity(Permissions.batteryOptimizationIntent(context)) },
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(
    name: String,
    description: String,
    isGranted: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(name, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        if (isGranted) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = Color(0xFF10B981).copy(alpha = 0.15f),
                contentColor = Color(0xFF10B981),
                modifier = Modifier.clickable(onClick = onClick),
            ) {
                Text(
                    "✓ Granted",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        } else {
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text("Grant", fontSize = 12.sp)
            }
        }
    }
}
