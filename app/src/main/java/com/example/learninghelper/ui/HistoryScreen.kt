package com.example.learninghelper.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.learninghelper.data.FocusRepository
import com.example.learninghelper.data.HistoryEntry
import com.example.learninghelper.data.HistoryType
import com.example.learninghelper.ui.theme.MonoSuccess
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)

    val history by FocusRepository.history.collectAsState()
    val sdf = remember { SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()) }
    var selectedEntry by remember { mutableStateOf<HistoryEntry?>(null) }

    val now = System.currentTimeMillis()
    val today = remember(now) { LocalDate.now() }
    val zoneId = remember { ZoneId.systemDefault() }

    val todayMinutes = remember(history, today) {
        history.filter { entry ->
            entry.type == HistoryType.COMPLETED &&
                Instant.ofEpochMilli(entry.timestamp).atZone(zoneId).toLocalDate() == today
        }.sumOf { it.durationMinutes }
    }

    val sevenDaysAgoMillis = now - FocusRepository.SEVEN_DAYS_MILLIS
    val sevenDayMinutes = remember(history, sevenDaysAgoMillis) {
        history.filter { entry ->
            entry.type == HistoryType.COMPLETED && entry.timestamp >= sevenDaysAgoMillis
        }.sumOf { it.durationMinutes }
    }

    val currentStreak = remember(history, today) {
        val completedDates = history
            .filter { it.type == HistoryType.COMPLETED }
            .map { Instant.ofEpochMilli(it.timestamp).atZone(zoneId).toLocalDate() }
            .toSet()

        var streak = 0
        var checkDate = if (completedDates.contains(today)) today else today.minusDays(1)
        while (completedDates.contains(checkDate)) {
            streak++
            checkDate = checkDate.minusDays(1)
        }
        streak
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text("Focus History", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(12.dp))

        // Summary metrics
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MetricCard(
                title = "Today",
                value = "${todayMinutes}m",
                modifier = Modifier.weight(1f),
            )
            MetricCard(
                title = "7 Days",
                value = if (sevenDayMinutes >= 60) "${sevenDayMinutes / 60}h ${sevenDayMinutes % 60}m" else "${sevenDayMinutes}m",
                modifier = Modifier.weight(1f),
            )
            MetricCard(
                title = "Streak",
                value = if (currentStreak > 0) "$currentStreak d" else "0 d",
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(16.dp))

        if (history.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No session history recorded yet.", color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(history, key = { it.id }) { entry ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (entry.text.isNotBlank()) Modifier.clickable { selectedEntry = entry }
                                else Modifier
                            ),
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                val tagColor = when (entry.type) {
                                    HistoryType.COMPLETED -> MonoSuccess
                                    HistoryType.SKIPPED -> MaterialTheme.colorScheme.onSurfaceVariant
                                    HistoryType.EMERGENCY_EXIT -> MaterialTheme.colorScheme.error
                                }
                                Text(entry.type.name, color = tagColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text(sdf.format(Date(entry.timestamp)), color = MaterialTheme.colorScheme.outline, fontSize = 12.sp)
                            }

                            Text(entry.title.ifBlank { "Focus Session" }, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)

                            if (entry.durationMinutes > 0) {
                                Text(
                                    "${entry.durationMinutes} minutes" + if (entry.minutesRemaining > 0) " (${entry.minutesRemaining}m remaining when exited)" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }

                            if (entry.text.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    entry.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "Tap to view full text",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    selectedEntry?.let { entry ->
        val scrollState = rememberScrollState()
        AlertDialog(
            onDismissRequest = { selectedEntry = null },
            title = {
                Text(
                    text = when (entry.type) {
                        HistoryType.EMERGENCY_EXIT -> "Emergency Exit Essay"
                        HistoryType.SKIPPED -> "Pre-Session Skip Reason"
                        HistoryType.COMPLETED -> "Session Details"
                    },
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .verticalScroll(scrollState)
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "${entry.title} • ${sdf.format(Date(entry.timestamp))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    HorizontalDivider()
                    Text(
                        text = entry.text,
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 20.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedEntry = null }) {
                    Text("Close")
                }
            },
        )
    }
}

@Composable
private fun MetricCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}
