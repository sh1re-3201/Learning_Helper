package com.example.learninghelper

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.learninghelper.data.FocusRepository
import com.example.learninghelper.focus.AppCatalog
import com.example.learninghelper.focus.FocusPolicy
import com.example.learninghelper.focus.ScheduleCalculator
import com.example.learninghelper.focus.SessionPhase
import kotlinx.coroutines.delay

class BlockActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })
        render(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render(intent)
    }

    private fun render(intent: Intent) {
        val blockedPackage = intent.getStringExtra(EXTRA_PACKAGE)
        val blockedLabel = blockedPackage?.let { AppCatalog.labelFor(this, it) } ?: "This app"
        setContent {
            BlockScreen(
                blockedLabel = blockedLabel,
                onBackToStudy = {
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    finish()
                },
                onGoHome = { goHome() },
                onOpenDeskClock = {
                    val clockIntent = Intent(this, MainActivity::class.java).apply {
                        putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_DESK_CLOCK)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    startActivity(clockIntent)
                    finish()
                },
                onOpenEmergencyExit = {
                    val exitIntent = Intent(this, MainActivity::class.java).apply {
                        putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_EMERGENCY_EXIT)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    startActivity(exitIntent)
                    finish()
                },
                onSessionOver = { finish() },
            )
        }
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    companion object {
        private const val EXTRA_PACKAGE = "blocked_package"

        fun createIntent(context: Context, blockedPackage: String): Intent =
            Intent(context, BlockActivity::class.java)
                .putExtra(EXTRA_PACKAGE, blockedPackage)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
    }
}

private val motivationalLines = listOf(
    "Your thesis won't write itself. Future you is counting on this hour.",
    "One focused session beats a whole day of half-work.",
    "Discomfort now, a finished thesis later.",
    "You decided this when you were thinking clearly. Trust that version of you.",
    "Small daily progress compounds into a finished chapter.",
)

@Composable
private fun BlockScreen(
    blockedLabel: String,
    onBackToStudy: () -> Unit,
    onGoHome: () -> Unit,
    onOpenDeskClock: () -> Unit,
    onOpenEmergencyExit: () -> Unit,
    onSessionOver: () -> Unit,
) {
    val session by FocusRepository.session.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var isStarted by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_START) { isStarted = true }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { isStarted = false }

    LaunchedEffect(isStarted) {
        while (isStarted) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    val effNow = FocusRepository.effectiveNow(now)
    val active = session?.takeIf { it.isActive(effNow) }
    val inPreSession = ScheduleCalculator.isInPreSessionLockWindow(
        items = FocusRepository.schedules.value,
        skippedOccurrenceIds = FocusRepository.skippedOccurrences.value,
        nowMillis = now,
    )
    val shouldStayBlocked = active != null || inPreSession

    LaunchedEffect(shouldStayBlocked) {
        if (!shouldStayBlocked) onSessionOver()
    }

    val isBreakPhase = active?.currentPhase(effNow) == SessionPhase.BREAK
    val line = remember(blockedLabel) { motivationalLines.random() }
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0F172A), Color(0xFF1E1B4B))))
            .verticalScroll(scrollState)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("🔒", fontSize = 48.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            "$blockedLabel is locked",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                isBreakPhase -> "Break mode is active, but system management tools remain protected."
                active != null -> "You're in focus mode."
                else -> "Pre-session lock window active."
            },
            color = Color(0xFFCBD5E1),
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        if (active != null) {
            CircularProgressIndicator(
                progress = { 1f - active.progress(effNow) },
                modifier = Modifier.size(130.dp),
                color = if (isBreakPhase) Color(0xFF10B981) else Color(0xFF818CF8),
                trackColor = Color(0xFF334155),
                strokeWidth = 8.dp,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                FocusPolicy.formatDuration(active.remainingMillis(effNow)),
                color = Color.White,
                fontSize = 36.sp,
                fontWeight = FontWeight.Light,
            )
            Text(
                if (isBreakPhase) "remaining in break session" else "remaining in session",
                color = Color(0xFF94A3B8),
                fontSize = 13.sp,
            )
        } else if (inPreSession) {
            Text(
                "Focus begins in < 5 minutes.\nSystem Settings & Device Care are locked.",
                color = Color(0xFFF59E0B),
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            line,
            color = Color(0xFFE2E8F0),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onBackToStudy,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
        ) {
            Text("Back to studying", modifier = Modifier.padding(vertical = 2.dp), fontSize = 15.sp)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onGoHome,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFCBD5E1)),
        ) {
            Text("Go to Home Screen")
        }
        if (active != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onOpenDeskClock,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF38BDF8)),
            ) {
                Text("🕒 Desk Clock (AOD)")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onOpenEmergencyExit,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
            ) {
                Text("Emergency Exit (100-word essay)")
            }
        }
    }
}
