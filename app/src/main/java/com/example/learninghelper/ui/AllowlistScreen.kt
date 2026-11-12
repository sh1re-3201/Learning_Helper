package com.example.learninghelper.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.example.learninghelper.data.FocusRepository
import com.example.learninghelper.focus.AppCatalog
import com.example.learninghelper.focus.InstalledApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AllowlistScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val allowlist by FocusRepository.allowlist.collectAsState()
    val isLocked = FocusRepository.isModificationLocked()

    val apps by produceState<List<InstalledApp>?>(null) {
        value = withContext(Dispatchers.IO) { AppCatalog.loadSelectableApps(context) }
    }

    val selfIcon = remember {
        runCatching {
            context.packageManager.getApplicationIcon(context.packageName)
                .toBitmap(width = 96, height = 96)
                .asImageBitmap()
        }.getOrNull()
    }

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var filterThesisEssentials by remember { mutableStateOf(false) }

    val optionalCount = allowlist.count { it !in FocusRepository.DEFAULT_ALLOWLIST }
    val isQuotaFull = optionalCount >= FocusRepository.MAX_OPTIONAL_ALLOWED_APPS

    Column(modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text(
                "Allowed Apps ($optionalCount/${FocusRepository.MAX_OPTIONAL_ALLOWED_APPS} Selected)",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            if (isLocked) "Locked: Allowlist cannot be changed during focus or pre-session." else "Select up to 5 optional apps. Default and system apps consume 0 quota.",
            style = MaterialTheme.typography.bodyMedium,
            color = if (isLocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Spacer(Modifier.height(8.dp))

        // Permanent Always Unblocked Card for LearningHelper & System
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selfIcon != null) {
                    Image(selfIcon, contentDescription = null, modifier = Modifier.size(40.dp))
                } else {
                    Spacer(Modifier.size(40.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "LearningHelper & System Core",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Always unblocked • Core system & Settings • 0 of 5 quota used",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                SuggestionChip(
                    onClick = {},
                    label = { Text("0 Quota", fontSize = 11.sp) },
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Multi-tab selector
        val optionalSelectedApps = (apps ?: emptyList()).filter {
            it.packageName in allowlist && it.packageName !in FocusRepository.DEFAULT_ALLOWLIST
        }
        val defaultAllowedApps = (apps ?: emptyList()).filter {
            it.packageName in allowlist && it.packageName in FocusRepository.DEFAULT_ALLOWLIST
        }

        PrimaryTabRow(selectedTabIndex = selectedTabIndex) {
            Tab(
                selected = selectedTabIndex == 0,
                onClick = { selectedTabIndex = 0 },
                text = { Text("Selected ($optionalCount/${FocusRepository.MAX_OPTIONAL_ALLOWED_APPS})") },
            )
            Tab(
                selected = selectedTabIndex == 1,
                onClick = { selectedTabIndex = 1 },
                text = { Text("Available") },
            )
        }

        val list = apps
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (selectedTabIndex == 0) {
            // Tab 0: Selected Apps
            LazyColumn(Modifier.fillMaxSize()) {
                if (optionalSelectedApps.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "No optional apps selected yet (0/${FocusRepository.MAX_OPTIONAL_ALLOWED_APPS} used).",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Switch to the 'Available' tab to choose up to 5 optional apps to allow during focus sessions.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(16.dp))
                                Button(onClick = { selectedTabIndex = 1 }) {
                                    Text("Browse Available Apps")
                                }
                            }
                        }
                    }
                } else {
                    item {
                        Text(
                            "Optional Chosen Apps ($optionalCount/${FocusRepository.MAX_OPTIONAL_ALLOWED_APPS})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    items(optionalSelectedApps, key = { it.packageName }) { app ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            app.icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(40.dp)) }
                                ?: Spacer(Modifier.size(40.dp))
                            Spacer(Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(app.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                Text("Optional allowed app • 1 of 5 quota used", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                            OutlinedButton(
                                onClick = { FocusRepository.setAllowed(app.packageName, false) },
                                enabled = !isLocked,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            ) {
                                Text("Remove")
                            }
                        }
                    }
                }

                if (defaultAllowedApps.isNotEmpty()) {
                    item {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Text(
                            "Default Whitelisted Apps (0 Quota Used)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    items(defaultAllowedApps, key = { it.packageName }) { app ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            app.icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(40.dp)) }
                                ?: Spacer(Modifier.size(40.dp))
                            Spacer(Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(app.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                Text("Default unblocked • 0 quota used", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                            }
                            if (app.packageName == "com.android.settings") {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(8.dp),
                                ) {
                                    Text(
                                        "System Core",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    )
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { FocusRepository.setAllowed(app.packageName, false) },
                                    enabled = !isLocked,
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                ) {
                                    Text("Disable")
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // Tab 1: Available Apps
            Column(Modifier.fillMaxSize()) {
                if (isQuotaFull) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) {
                        Text(
                            "Maximum of 5 optional apps reached. To allow a different app, remove one from the 'Selected' tab first.",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }

                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    FilterChip(
                        selected = filterThesisEssentials,
                        onClick = { filterThesisEssentials = !filterThesisEssentials },
                        label = { Text("Thesis Essentials") }
                    )
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search installed apps") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )

                val availableFiltered = list.filter { app ->
                    val matchesQuery = query.isBlank() || app.label.contains(query, ignoreCase = true)
                    val matchesThesis = !filterThesisEssentials || app.packageName in AppCatalog.THESIS_ESSENTIALS
                    matchesQuery && matchesThesis
                }.sortedWith(
                    compareByDescending<InstalledApp> { it.packageName in allowlist }
                        .thenByDescending { it.packageName in FocusRepository.DEFAULT_ALLOWLIST }
                        .thenBy { it.label.lowercase() }
                )

                LazyColumn(Modifier.fillMaxSize()) {
                    items(availableFiltered, key = { it.packageName }) { app ->
                        val checked = app.packageName in allowlist
                        val isDefault = app.packageName in FocusRepository.DEFAULT_ALLOWLIST
                        val canToggle = !isLocked && (isDefault || checked || !isQuotaFull)

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = canToggle) {
                                    FocusRepository.setAllowed(app.packageName, !checked)
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            app.icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(40.dp)) }
                                ?: Spacer(Modifier.size(40.dp))
                            Spacer(Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    app.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (canToggle || checked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                )
                                if (isDefault) {
                                    Text(
                                        "Default Whitelisted • 0 quota",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                    )
                                }
                            }
                            Checkbox(
                                checked = checked,
                                enabled = canToggle,
                                onCheckedChange = { FocusRepository.setAllowed(app.packageName, it) },
                            )
                        }
                    }
                }
            }
        }
    }
}
