package com.example.learninghelper.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.learninghelper.ui.theme.MonoBlack
import com.example.learninghelper.ui.theme.MonoOnSurface
import com.example.learninghelper.ui.theme.MonoOnSurfaceVariant
import com.example.learninghelper.ui.theme.MonoOutline
import com.example.learninghelper.ui.theme.MonoOutlineVariant
import com.example.learninghelper.ui.theme.MonoPrimary
import com.example.learninghelper.ui.theme.MonoSuccess
import com.example.learninghelper.ui.theme.MonoSurfaceVariant
import kotlin.math.cos
import kotlin.math.sin

/**
 * A bespoke monochrome Bauhaus/chronograph-style analog stopwatch dial for the active focus session.
 * Features 3 hands:
 * - Second hand (longest, slender with counterweight tail): completes 360° every 60 seconds.
 * - Minute hand (intermediate length, medium thickness): completes 360° every 60 minutes.
 * - Hour hand (shortest, boldest): completes 360° every 12 hours.
 *
 * @param progress Fraction of the session/phase that has elapsed, in 0f..1f (0f = just started, 1f = finished).
 * @param timeFormatted Formatted countdown text (e.g. "24:50").
 * @param phaseLabel Phase badge text (e.g. "FOCUS" or "BREAK").
 * @param isBreak Whether the active session is currently in a break phase.
 * @param modifier Modifier for the outer Box.
 * @param dialSize Outer dimension of the dial (default 220dp).
 * @param hours Elapsed session hours (0..11).
 * @param minutes Elapsed session minutes (0..59).
 * @param seconds Elapsed session seconds (0..59).
 */
@Composable
fun AnalogStopwatchDial(
    progress: Float,
    timeFormatted: String,
    phaseLabel: String,
    isBreak: Boolean,
    modifier: Modifier = Modifier,
    dialSize: Dp = 220.dp,
    hours: Int = 0,
    minutes: Int = 0,
    seconds: Int = 0,
) {
    val clampedProgress = progress.coerceIn(0f, 1f)
    val accentColor = if (isBreak) MonoSuccess else MonoPrimary

    Box(
        modifier = modifier.size(dialSize),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val radius = minOf(cx, cy)

            // 1. Outer Bezel Ring
            val bezelRadius = radius - 2.dp.toPx()
            drawCircle(
                color = MonoOutlineVariant,
                radius = bezelRadius,
                style = Stroke(width = 1.dp.toPx()),
            )

            // 2. 60 Chronograph Radial Ticks
            val outerTickRadius = radius - 4.dp.toPx()
            for (i in 0 until 60) {
                val angleRad = Math.toRadians((i * 6.0) - 90.0)
                val cosA = cos(angleRad).toFloat()
                val sinA = sin(angleRad).toFloat()

                val (tickLength, strokeWidth, tickColor) = when {
                    // Cardinal markers (12, 3, 6, 9 o'clock)
                    i % 15 == 0 -> Triple(12.dp.toPx(), 2.dp.toPx(), MonoOnSurface)
                    // Major 5-minute ticks
                    i % 5 == 0 -> Triple(8.dp.toPx(), 1.5.dp.toPx(), MonoOutline)
                    // Minor second/minute ticks
                    else -> Triple(4.5.dp.toPx(), 0.8.dp.toPx(), MonoOutlineVariant)
                }

                val innerTickRadius = outerTickRadius - tickLength
                val start = Offset(cx + innerTickRadius * cosA, cy + innerTickRadius * sinA)
                val end = Offset(cx + outerTickRadius * cosA, cy + outerTickRadius * sinA)

                drawLine(
                    color = tickColor,
                    start = start,
                    end = end,
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
            }

            // 3. Progress Arc Track
            val trackRadius = radius - 24.dp.toPx()
            val trackStroke = 3.5.dp.toPx()

            // Faint background track
            drawCircle(
                color = MonoSurfaceVariant,
                radius = trackRadius,
                style = Stroke(width = trackStroke),
            )

            // Active elapsed arc (sweeps clockwise from 12 o'clock following progress)
            if (clampedProgress > 0.001f) {
                drawArc(
                    color = accentColor,
                    startAngle = -90f,
                    sweepAngle = clampedProgress * 360f,
                    useCenter = false,
                    topLeft = Offset(cx - trackRadius, cy - trackRadius),
                    size = Size(trackRadius * 2f, trackRadius * 2f),
                    style = Stroke(width = trackStroke, cap = StrokeCap.Round),
                )
            }

            // 4. Three Analog Timer Watch Hands (Order: Hour -> Minute -> Second)

            // [A] Shortest, boldest Hour Hand (1 revolution per 12 hours)
            val safeHours = hours % 12
            val safeMinutes = minutes % 60
            val safeSeconds = seconds % 60

            val hrDegrees = (safeHours + safeMinutes / 60.0 + safeSeconds / 3600.0) * 30.0
            val hrAngleRad = Math.toRadians(hrDegrees - 90.0)
            val hrCos = cos(hrAngleRad).toFloat()
            val hrSin = sin(hrAngleRad).toFloat()
            val hrLength = radius * 0.48f

            drawLine(
                color = MonoOnSurface,
                start = Offset(cx, cy),
                end = Offset(cx + hrLength * hrCos, cy + hrLength * hrSin),
                strokeWidth = 4.dp.toPx(),
                cap = StrokeCap.Round,
            )

            // [B] Intermediate Minute Hand (1 revolution per 60 minutes)
            val minDegrees = (safeMinutes + safeSeconds / 60.0) * 6.0
            val minAngleRad = Math.toRadians(minDegrees - 90.0)
            val minCos = cos(minAngleRad).toFloat()
            val minSin = sin(minAngleRad).toFloat()
            val minLength = radius * 0.72f

            drawLine(
                color = MonoOnSurface,
                start = Offset(cx, cy),
                end = Offset(cx + minLength * minCos, cy + minLength * minSin),
                strokeWidth = 2.5.dp.toPx(),
                cap = StrokeCap.Round,
            )

            // [C] Longest Second Hand with Counterweight Tail (1 revolution per 60 seconds)
            val secDegrees = safeSeconds * 6.0
            val secAngleRad = Math.toRadians(secDegrees - 90.0)
            val secCos = cos(secAngleRad).toFloat()
            val secSin = sin(secAngleRad).toFloat()
            val secLength = radius * 0.88f
            val tailLength = 14.dp.toPx()

            val secEnd = Offset(cx + secLength * secCos, cy + secLength * secSin)
            val secTail = Offset(cx - tailLength * secCos, cy - tailLength * secSin)

            drawLine(
                color = accentColor,
                start = secTail,
                end = secEnd,
                strokeWidth = 1.5.dp.toPx(),
                cap = StrokeCap.Round,
            )

            // 5. Center Pivot Hub
            drawCircle(
                color = accentColor,
                radius = 5.dp.toPx(),
                center = Offset(cx, cy),
            )
            drawCircle(
                color = MonoBlack,
                radius = 2.dp.toPx(),
                center = Offset(cx, cy),
            )
        }

        // 6. Sub-dial Digital Readout & Phase Badge (Positioned at 6 o'clock sub-dial)
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = dialSize * 0.22f)
                .background(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
                    shape = CircleShape,
                )
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = timeFormatted,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                letterSpacing = 1.sp,
            )
            Text(
                text = phaseLabel,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                color = if (isBreak) MonoSuccess else MonoOnSurfaceVariant,
            )
        }
    }
}
