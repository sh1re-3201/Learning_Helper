package com.example.learninghelper.ui

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.widget.Chronometer
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.learninghelper.data.FocusRepository
import com.example.learninghelper.focus.AlarmPlayer
import com.example.learninghelper.focus.AlarmScheduler
import com.example.learninghelper.focus.FocusNotificationListenerService
import com.example.learninghelper.focus.FocusService
import com.example.learninghelper.focus.SessionPhase
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun DeskClockScreen(
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = {
        AlarmPlayer.stop()
        onExit()
    })
    val context = LocalContext.current
    val view = LocalView.current
    val activity = context as? Activity

    DisposableEffect(Unit) {
        val rawOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val originalOrientation = if (
            rawOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE ||
            rawOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE ||
            rawOrientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE ||
            rawOrientation == ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        ) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            rawOrientation
        }
        val window = activity?.window
        val originalBrightness = window?.attributes?.screenBrightness ?: -1f

        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window?.let {
            val lp = it.attributes
            lp.screenBrightness = 0.02f
            it.attributes = lp
            it.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val controller = WindowCompat.getInsetsController(it, view)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val wasMuted = runCatching { audioManager?.isStreamMute(AudioManager.STREAM_NOTIFICATION) }.getOrNull() ?: false
        if (!wasMuted) {
            runCatching { audioManager?.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0) }
        }
        FocusRepository.setDeskClockActive(true)
        FocusNotificationListenerService.setInterruptionFilter(NotificationListenerService.INTERRUPTION_FILTER_PRIORITY)
        FocusNotificationListenerService.sweepDistractions()

        onDispose {
            AlarmPlayer.stop()
            FocusRepository.setDeskClockActive(false)
            FocusNotificationListenerService.setInterruptionFilter(NotificationListenerService.INTERRUPTION_FILTER_ALL)
            if (!wasMuted) {
                runCatching { audioManager?.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0) }
            }

            activity?.requestedOrientation = originalOrientation
            window?.let {
                val lp = it.attributes
                lp.screenBrightness = originalBrightness
                it.attributes = lp
                it.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val controller = WindowCompat.getInsetsController(it, view)
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    var currentTimeString by remember { mutableStateOf(getFormattedTime()) }
    var pixelShiftX by remember { mutableIntStateOf(0) }
    var pixelShiftY by remember { mutableIntStateOf(0) }
    var showControls by remember { mutableStateOf(false) }
    var showConfirmEndBreak by remember { mutableStateOf(false) }

    var isStarted by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { isStarted = true }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { isStarted = false }

    LaunchedEffect(isStarted) {
        while (isStarted) {
            currentTimeString = getFormattedTime()
            val nowMs = System.currentTimeMillis()
            val minuteOfDay = (nowMs / 60_000L).toInt()
            pixelShiftX = (minuteOfDay % 7 - 3) * 3
            pixelShiftY = (minuteOfDay % 5 - 2) * 2

            val delayToNextMinute = 60_000L - (nowMs % 60_000L)
            delay(delayToNextMinute.coerceAtLeast(1_000L))
        }
    }

    LaunchedEffect(showControls) {
        if (showControls) {
            delay(4_000L)
            showControls = false
        }
    }

    var phaseTick by remember { mutableLongStateOf(0L) }
    val session by FocusRepository.session.collectAsState()
    val wallNow = remember(currentTimeString, phaseTick) { System.currentTimeMillis() }
    val effNow = FocusRepository.effectiveNow(wallNow)
    val active = session?.takeIf { it.isActive(effNow) }
    val isBreak = active != null && active.currentPhase(effNow) == SessionPhase.BREAK

    LaunchedEffect(isBreak) {
        if (!isBreak) showConfirmEndBreak = false
    }

    LaunchedEffect(active?.phaseEndAt(effNow)) {
        val endAt = active?.phaseEndAt(effNow) ?: return@LaunchedEffect
        val delayMs = endAt - effNow
        if (delayMs > 0) {
            delay(delayMs + 50L)
            phaseTick = System.currentTimeMillis()
        }
    }

    val targetBackgroundColor = if (isBreak) Color(0xFF273C86) else Color.Black
    val animatedBgColor by animateColorAsState(
        targetValue = targetBackgroundColor,
        animationSpec = tween(durationMillis = 3000, easing = FastOutSlowInEasing),
        label = "DeskClockBgAnimation",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(animatedBgColor)
            .clickable {
                AlarmPlayer.stop()
                FocusService.updateOngoingNotification(context)
                showControls = !showControls
            },
    ) {
        // Center Clock
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = pixelShiftX.dp, y = pixelShiftY.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = currentTimeString,
                color = if (isBreak) Color.White else Color(0xFF8A94A6),
                fontSize = 110.sp,
                fontWeight = FontWeight.ExtraLight,
                letterSpacing = 2.sp,
            )
            if (isBreak) {
                Text(
                    text = "BREAK TIME",
                    color = Color(0xFFBFDBFE),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 3.sp,
                )
            }
        }

        // Bottom Right Countdown
        if (active != null) {
            val phase = active.currentPhase(effNow)
            val label = when {
                phase == SessionPhase.BREAK -> "Break Remaining"
                active.breakMinutes > 0 -> "Until Break"
                else -> "Until End"
            }
            val remainingMs = active.phaseRemainingMillis(effNow)

            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = (pixelShiftX - 24).dp, y = (pixelShiftY - 16).dp),
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    text = label.uppercase(Locale.ROOT),
                    color = if (isBreak) Color(0xFFE0E7FF) else Color(0xFF64748B),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                AndroidView(
                    factory = { ctx ->
                        Chronometer(ctx).apply {
                            setTextColor(android.graphics.Color.parseColor(if (isBreak) "#FFFFFF" else "#5EEAD4"))
                            textSize = 28f
                            isCountDown = true
                        }
                    },
                    update = { chrono ->
                        chrono.base = SystemClock.elapsedRealtime() + remainingMs
                        chrono.setTextColor(android.graphics.Color.parseColor(if (isBreak) "#FFFFFF" else "#5EEAD4"))
                        if (isStarted) chrono.start() else chrono.stop()
                    },
                    onRelease = { chrono ->
                        chrono.stop()
                    }
                )
            }
        }

        if (showConfirmEndBreak) {
            AlertDialog(
                onDismissRequest = { showConfirmEndBreak = false },
                title = { Text("End Break Early?") },
                text = { Text("Are you sure you want to end your break now and lock back into Focus Mode immediately?") },
                confirmButton = {
                    Button(onClick = {
                        showConfirmEndBreak = false
                        showControls = false
                        AlarmPlayer.stop()
                        FocusRepository.endBreakEarly(System.currentTimeMillis())
                        FocusService.start(context)
                        AlarmScheduler.scheduleNext(context)
                        phaseTick = System.currentTimeMillis()
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

        if (showControls) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = {
                        AlarmPlayer.stop()
                        onExit()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0x66334155)),
                ) {
                    Text("Exit Desk Clock", color = Color.White)
                }
                if (isBreak) {
                    Button(
                        onClick = { showConfirmEndBreak = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0x99B45309)),
                    ) {
                        Text("End Break Early", color = Color.White)
                    }
                }
            }
        }
    }
}

private fun getFormattedTime(): String =
    LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm", Locale.US))
