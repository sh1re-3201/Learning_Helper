package com.example.learninghelper

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.example.learninghelper.data.FocusRepository
import com.example.learninghelper.focus.AlarmScheduler
import com.example.learninghelper.focus.FocusService
import com.example.learninghelper.ui.*
import com.example.learninghelper.ui.theme.LearningHelperTheme

class MainActivity : ComponentActivity() {

    private var resumeTick by mutableIntStateOf(0)
    private var initialScreen by mutableIntStateOf(SCREEN_HOME)
    private var navTrigger by mutableLongStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)

        setContent {
            LearningHelperTheme {
                var screen by rememberSaveable { mutableIntStateOf(initialScreen) }

                LaunchedEffect(navTrigger) {
                    if (navTrigger > 0L) {
                        screen = initialScreen
                    }
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val contentModifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)

                    when (screen) {
                        SCREEN_ALLOWLIST -> AllowlistScreen(
                            onBack = { screen = SCREEN_HOME },
                            modifier = contentModifier,
                        )
                        SCREEN_SCHEDULE -> ScheduleScreen(
                            onBack = { screen = SCREEN_HOME },
                            modifier = contentModifier,
                        )
                        SCREEN_HISTORY -> HistoryScreen(
                            onBack = { screen = SCREEN_HOME },
                            modifier = contentModifier,
                        )
                        SCREEN_SETTINGS -> SettingsScreen(
                            onBack = { screen = SCREEN_HOME },
                            modifier = contentModifier,
                        )
                        SCREEN_DESK_CLOCK -> DeskClockScreen(
                            onExit = { screen = SCREEN_HOME },
                            modifier = contentModifier,
                        )
                        SCREEN_EMERGENCY_EXIT -> EmergencyExitScreen(
                            onExitSuccess = {
                                FocusService.stop(this@MainActivity)
                                screen = SCREEN_HOME
                            },
                            onCancel = { screen = SCREEN_HOME },
                            modifier = contentModifier,
                        )
                        else -> HomeScreen(
                            resumeTick = resumeTick,
                            onOpenAllowlist = { screen = SCREEN_ALLOWLIST },
                            onOpenSchedule = { screen = SCREEN_SCHEDULE },
                            onOpenHistory = { screen = SCREEN_HISTORY },
                            onOpenSettings = { screen = SCREEN_SETTINGS },
                            onOpenDeskClock = { screen = SCREEN_DESK_CLOCK },
                            onOpenEmergencyExit = { screen = SCREEN_EMERGENCY_EXIT },
                            modifier = contentModifier,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val targetScreen = intent?.getIntExtra(EXTRA_SCREEN, -1) ?: -1
        if (targetScreen in SCREEN_HOME..SCREEN_EMERGENCY_EXIT) {
            initialScreen = targetScreen
            navTrigger = System.currentTimeMillis()
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
        if (FocusRepository.isFocusActive()) {
            FocusService.start(this)
        } else {
            FocusRepository.clearExpiredSession()
        }
        AlarmScheduler.scheduleNext(this)
    }

    companion object {
        const val EXTRA_SCREEN = "extra_screen"
        const val SCREEN_HOME = 0
        const val SCREEN_ALLOWLIST = 1
        const val SCREEN_SCHEDULE = 2
        const val SCREEN_HISTORY = 3
        const val SCREEN_SETTINGS = 4
        const val SCREEN_DESK_CLOCK = 5
        const val SCREEN_EMERGENCY_EXIT = 6
    }
}
