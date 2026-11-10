package com.example.learninghelper.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.learninghelper.data.FocusRepository
import com.example.learninghelper.focus.EssayConfig
import com.example.learninghelper.focus.EssayResult
import com.example.learninghelper.focus.EssayValidator
import com.example.learninghelper.focus.FocusPolicy
import com.example.learninghelper.ui.theme.MonoSuccess
import kotlinx.coroutines.delay

@Composable
fun EmergencyExitScreen(
    onExitSuccess: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onCancel)
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val minWritingMinutes by FocusRepository.minWritingMinutes.collectAsState()
    val targetSeconds = minWritingMinutes * 60

    var draft by remember { mutableStateOf(FocusRepository.emergencyDraftText.value) }
    var elapsedSeconds by remember { mutableIntStateOf(FocusRepository.emergencyElapsedSeconds.value) }
    var isStarted by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }
    var pasteWarning by remember { mutableStateOf(false) }
    var lastActiveTypingElapsed by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var isTypingActive by remember { mutableStateOf(true) }

    LifecycleEventEffect(Lifecycle.Event.ON_START) { isStarted = true }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        isStarted = false
        FocusRepository.saveEmergencyDraft(draft, elapsedSeconds)
    }

    LaunchedEffect(isStarted) {
        while (isStarted) {
            delay(1_000L)
            val nowElapsed = SystemClock.elapsedRealtime()
            val activelyTyping = (nowElapsed - lastActiveTypingElapsed) <= 20_000L
            isTypingActive = activelyTyping
            if (activelyTyping && elapsedSeconds < targetSeconds) {
                elapsedSeconds++
                if (elapsedSeconds % 5 == 0) {
                    FocusRepository.saveEmergencyDraft(draft, elapsedSeconds)
                }
            }
        }
    }

    val wordsCount = EssayValidator.countRomanWords(draft)
    val timeRemainingSec = (targetSeconds - elapsedSeconds).coerceAtLeast(0)
    val canSubmit = wordsCount >= 100 && timeRemainingSec == 0
    val scrollState = rememberScrollState()

    LaunchedEffect(draft.length) {
        if (draft.length > 50) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
            .verticalScroll(scrollState)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text("← Return") }
            Text("Emergency Exit", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("High Friction Exit Requirements", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                Text(
                    "1. Write a 100-word essay explaining why you need to quit now.\n" +
                    "2. Active typing timer: minimum $minWritingMinutes minutes (pauses after 20s idle).",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Words: $wordsCount / 100",
                color = if (wordsCount >= 100) MonoSuccess else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
            val timerText = when {
                timeRemainingSec == 0 -> "Timer: Ready ✓"
                !isTypingActive -> "Timer: ${FocusPolicy.formatDuration(timeRemainingSec * 1000L)} (Paused)"
                else -> "Timer: ${FocusPolicy.formatDuration(timeRemainingSec * 1000L)}"
            }
            Text(
                timerText,
                color = if (timeRemainingSec == 0) MonoSuccess else if (!isTypingActive) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
        }

        if (pasteWarning) {
            Text("Paste detected & blocked. Please type directly.", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }

        OutlinedTextField(
            value = draft,
            onValueChange = { newText ->
                if (EssayValidator.isPasteAttempt(draft, newText)) {
                    pasteWarning = true
                } else {
                    pasteWarning = false
                    val isMeaningfulTyping = newText.length > draft.length ||
                        (newText != draft && newText.trim() != draft.trim())
                    if (isMeaningfulTyping) {
                        lastActiveTypingElapsed = SystemClock.elapsedRealtime()
                        isTypingActive = true
                    }
                    draft = newText
                    validationError = null
                }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 240.dp),
            placeholder = { Text("Write your honest explanation here...", color = MaterialTheme.colorScheme.outline) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            ),
        )

        validationError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }

        Button(
            onClick = {
                val pastEssays = FocusRepository.history.value.map { it.text }.filter { it.isNotBlank() }
                val result = EssayValidator.validate(draft, previousEssays = pastEssays, config = EssayConfig.EMERGENCY_EXIT)
                if (result is EssayResult.Accepted) {
                    FocusRepository.emergencyExit(draft)
                    onExitSuccess()
                } else if (result is EssayResult.Rejected) {
                    validationError = result.detail
                }
            },
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        ) {
            Text("Unlock & Exit Focus", modifier = Modifier.padding(vertical = 4.dp))
        }
    }
}
