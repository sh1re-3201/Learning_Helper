package com.example.learninghelper

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.learninghelper.data.FocusRepository
import com.example.learninghelper.focus.AlarmPlayer
import com.example.learninghelper.focus.AlarmScheduler
import com.example.learninghelper.focus.EssayConfig
import com.example.learninghelper.focus.EssayResult
import com.example.learninghelper.focus.EssayValidator
import com.example.learninghelper.focus.FocusPolicy
import com.example.learninghelper.focus.FocusService
import kotlinx.coroutines.delay

class PreSessionActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        enableEdgeToEdge()

        val occId = intent.getStringExtra(EXTRA_OCCURRENCE_ID) ?: ""
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Scheduled Session"
        val startMillis = intent.getLongExtra(EXTRA_START_MILLIS, 0L)
        val durationMin = intent.getIntExtra(EXTRA_DURATION_MIN, 50)
        val workMin = intent.getIntExtra(EXTRA_WORK_MIN, 50)
        val breakMin = intent.getIntExtra(EXTRA_BREAK_MIN, 0)
        val hasLongBreak = intent.getBooleanExtra(EXTRA_HAS_LONG_BREAK, false)
        val initialSkip = intent.getBooleanExtra(EXTRA_INITIAL_SKIP, false)

        setContent {
            PreSessionScreen(
                title = title,
                startMillis = startMillis,
                initialShowSkip = initialSkip,
                onProceed = {
                    AlarmPlayer.stop()
                    finish()
                },
                onSilenceAlarm = {
                    AlarmPlayer.stop()
                },
                onSkipConfirmed = { reason ->
                    AlarmPlayer.stop()
                    FocusRepository.skipOccurrence(
                        occurrenceId = occId,
                        title = title,
                        reasonText = reason,
                        scheduledStartMillis = startMillis,
                    )
                    AlarmScheduler.scheduleNext(this)
                    finish()
                },
                onTimeReached = {
                    AlarmPlayer.stop()
                    FocusRepository.startSession(durationMin, workMin, breakMin, hasLongBreak = hasLongBreak)
                    FocusService.start(this)
                    finish()
                }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onDestroy() {
        AlarmPlayer.stop()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            AlarmPlayer.stop()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        private const val EXTRA_OCCURRENCE_ID = "occurrence_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_START_MILLIS = "start_millis"
        private const val EXTRA_DURATION_MIN = "duration_min"
        private const val EXTRA_WORK_MIN = "work_min"
        private const val EXTRA_BREAK_MIN = "break_min"
        private const val EXTRA_HAS_LONG_BREAK = "has_long_break"
        private const val EXTRA_INITIAL_SKIP = "initial_skip"

        fun createIntent(
            context: Context,
            occId: String,
            title: String,
            startMillis: Long,
            durationMin: Int = 50,
            workMin: Int = 50,
            breakMin: Int = 0,
            initialShowSkip: Boolean = false,
            hasLongBreak: Boolean = false,
        ): Intent =
            Intent(context, PreSessionActivity::class.java).apply {
                putExtra(EXTRA_OCCURRENCE_ID, occId)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_START_MILLIS, startMillis)
                putExtra(EXTRA_DURATION_MIN, durationMin)
                putExtra(EXTRA_WORK_MIN, workMin)
                putExtra(EXTRA_BREAK_MIN, breakMin)
                putExtra(EXTRA_HAS_LONG_BREAK, hasLongBreak)
                putExtra(EXTRA_INITIAL_SKIP, initialShowSkip)
            }
    }
}

@Composable
fun PreSessionScreen(
    title: String,
    startMillis: Long,
    initialShowSkip: Boolean,
    onProceed: () -> Unit,
    onSilenceAlarm: () -> Unit,
    onSkipConfirmed: (String) -> Unit,
    onTimeReached: () -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var isStarted by remember { mutableStateOf(false) }

    var showSkipInput by rememberSaveable { mutableStateOf(initialShowSkip) }
    var skipReasonText by rememberSaveable { mutableStateOf("") }
    var validationError by remember { mutableStateOf<String?>(null) }
    var pasteWarning by remember { mutableStateOf(false) }
    var gracePeriodActive by rememberSaveable { mutableStateOf(false) }
    var graceSecondsRemaining by rememberSaveable { mutableIntStateOf(60) }

    LifecycleEventEffect(Lifecycle.Event.ON_START) { isStarted = true }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { isStarted = false }

    LaunchedEffect(isStarted, gracePeriodActive) {
        while (isStarted) {
            now = System.currentTimeMillis()
            if (gracePeriodActive) {
                delay(1_000L)
                graceSecondsRemaining--
                if (graceSecondsRemaining <= 0) {
                    onTimeReached()
                    break
                }
            } else {
                if (now >= startMillis) {
                    val typedWords = EssayValidator.countRomanWords(skipReasonText)
                    if (showSkipInput && typedWords >= 5) {
                        // Grant one-time +60s grace extension if user is actively mid-sentence
                        gracePeriodActive = true
                    } else {
                        onTimeReached()
                        break
                    }
                }
                delay(1_000L)
            }
        }
    }

    val remainingMs = (startMillis - now).coerceAtLeast(0L)
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
            .verticalScroll(scrollState)
            .imePadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Upcoming Session", color = Color(0xFF94A3B8), fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        Text(title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)

        Spacer(Modifier.height(24.dp))
        if (gracePeriodActive) {
            Text(
                "00:%02d".format(graceSecondsRemaining),
                color = Color(0xFFEF4444),
                fontSize = 48.sp,
                fontWeight = FontWeight.Light,
            )
            Text("Grace period: finish typing your reason", color = Color(0xFFEF4444), fontSize = 14.sp)
        } else {
            Text(FocusPolicy.formatDuration(remainingMs), color = Color(0xFFF59E0B), fontSize = 48.sp, fontWeight = FontWeight.Light)
            Text("starts automatically when timer hits 0:00", color = Color(0xFF64748B), fontSize = 14.sp)
        }

        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onSilenceAlarm) {
            Text("Silence Alarm (or Vol-Key)")
        }

        Spacer(Modifier.height(16.dp))

        if (!showSkipInput) {
            Button(
                onClick = onProceed,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5)),
            ) {
                Text("Proceed (Lock in at 0:00)", modifier = Modifier.padding(vertical = 4.dp))
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    onSilenceAlarm()
                    showSkipInput = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Skip Session (Requires 20-word reason)")
            }
        } else {
            val wordsCount = EssayValidator.countRomanWords(skipReasonText)
            Text(
                "Skip Reason (Min 20 Roman words): $wordsCount / 20",
                color = if (wordsCount >= 20) Color(0xFF10B981) else Color(0xFFF59E0B),
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = skipReasonText,
                onValueChange = { newText ->
                    if (EssayValidator.isPasteAttempt(skipReasonText, newText)) {
                        pasteWarning = true
                    } else {
                        pasteWarning = false
                        skipReasonText = newText
                        validationError = null
                    }
                },
                modifier = Modifier.fillMaxWidth().height(120.dp),
                placeholder = { Text("Write your reason for skipping this scheduled session...", color = Color.Gray) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF6366F1),
                    unfocusedBorderColor = Color(0xFF475569),
                ),
            )

            if (pasteWarning) {
                Spacer(Modifier.height(4.dp))
                Text("Paste detected & blocked. Please type directly.", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }

            validationError?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { showSkipInput = false }) {
                    Text("Cancel", color = Color.Gray)
                }
                Button(
                    onClick = {
                        val pastSkipReasons = FocusRepository.recentSkipReasons()
                        val result = EssayValidator.validate(skipReasonText, previousEssays = pastSkipReasons, config = EssayConfig.SKIP_REASON)
                        if (result is EssayResult.Accepted) {
                            onSkipConfirmed(skipReasonText)
                        } else if (result is EssayResult.Rejected) {
                            validationError = result.detail
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                ) {
                    Text("Confirm Skip")
                }
            }
        }
    }
}
