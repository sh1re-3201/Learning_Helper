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
import androidx.compose.ui.Modifier
import com.example.learninghelper.data.FocusRepository
import com.example.learninghelper.focus.AlarmScheduler
import com.example.learninghelper.focus.FocusService
import com.example.learninghelper.ui.*
import com.example.learninghelper.ui.theme.LearningHelperTheme

class MainActivity : ComponentActivity() {

    private var resumeTick by mutableIntStateOf(0)
    private var currentScreen by mutableIntStateOf(SCREEN_HOME)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState != null) {
            currentScreen = savedInstanceState.getInt(KEY_SCREEN, SCREEN_HOME)
        } else {
            handleIntent(intent)
        }

        setContent {
            LearningHelperTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val contentModifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)

                    when (currentScreen) {
                        SCREEN_ALLOWLIST -> AllowlistScreen(
                            onBack = { currentScreen = SCREEN_HOME },
                            modifier = contentModifier,
                        )
                        SCREEN_SCHEDULE -> ScheduleScreen(
                            onBack = { currentScreen = SCREEN_HOME },
                            modifier = contentModifier,
                        )
                        SCREEN_HISTORY -> HistoryScreen(
                            onBack = { currentScreen = SCREEN_HOME },
                            modifier = contentModifier,
                        )
                        SCREEN_SETTINGS -> SettingsScreen(
                            onBack = { currentScreen = SCREEN_HOME },
                            modifier = contentModifier,
                        )
                        SCREEN_DESK_CLOCK -> DeskClockScreen(
                            onExit = { currentScreen = SCREEN_HOME },
                            modifier = contentModifier,
                        )
                        SCREEN_EMERGENCY_EXIT -> EmergencyExitScreen(
                            onExitSuccess = {
                                FocusService.stop(this@MainActivity)
                                currentScreen = SCREEN_HOME
                            },
                            onCancel = { currentScreen = SCREEN_HOME },
                            modifier = contentModifier,
                        )
                        else -> HomeScreen(
                            resumeTick = resumeTick,
                            onOpenAllowlist = { currentScreen = SCREEN_ALLOWLIST },
                            onOpenSchedule = { currentScreen = SCREEN_SCHEDULE },
                            onOpenHistory = { currentScreen = SCREEN_HISTORY },
                            onOpenSettings = { currentScreen = SCREEN_SETTINGS },
                            onOpenDeskClock = { currentScreen = SCREEN_DESK_CLOCK },
                            onOpenEmergencyExit = { currentScreen = SCREEN_EMERGENCY_EXIT },
                            modifier = contentModifier,
                        )
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SCREEN, currentScreen)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val targetScreen = intent?.getIntExtra(EXTRA_SCREEN, -1) ?: -1
        if (targetScreen in SCREEN_HOME..SCREEN_EMERGENCY_EXIT) {
            currentScreen = targetScreen
            intent?.removeExtra(EXTRA_SCREEN)
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
        private const val KEY_SCREEN = "key_current_screen"
        const val SCREEN_HOME = 0
        const val SCREEN_ALLOWLIST = 1
        const val SCREEN_SCHEDULE = 2
        const val SCREEN_HISTORY = 3
        const val SCREEN_SETTINGS = 4
        const val SCREEN_DESK_CLOCK = 5
        const val SCREEN_EMERGENCY_EXIT = 6
    }
}
