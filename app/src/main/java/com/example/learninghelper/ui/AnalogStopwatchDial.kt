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
 *
 * @param progress Fraction of the session/phase that has elapsed, in 0f..1f (0f = just started, 1f = finished).
 * @param timeFormatted Formatted countdown text (e.g. "24:50").
 * @param phaseLabel Phase badge text (e.g. "FOCUS" or "BREAK").
 * @param isBreak Whether the active session is currently in a break phase.
 * @param dialSize Outer dimension of the dial (default 220dp).
 */
@Composable
fun AnalogStopwatchDial(
    progress: Float,
    timeFormatted: String,
    phaseLabel: String,
    isBreak: Boolean,
    modifier: Modifier = Modifier,
    dialSize: Dp = 220.dp,
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

            // 4. Chronograph Stopwatch Needle
            val needleAngleRad = Math.toRadians((clampedProgress * 360.0) - 90.0)
            val nCos = cos(needleAngleRad).toFloat()
            val nSin = sin(needleAngleRad).toFloat()

            // Slender needle pointing to current progress
            val needleLength = trackRadius - 4.dp.toPx()
            val needleEnd = Offset(cx + needleLength * nCos, cy + needleLength * nSin)

            // Counterweight tail extending opposite direction
            val tailLength = 12.dp.toPx()
            val tailEnd = Offset(cx - tailLength * nCos, cy - tailLength * nSin)

            drawLine(
                color = accentColor,
                start = tailEnd,
                end = needleEnd,
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )

            // 5. Center Pivot Hub
            drawCircle(
                color = accentColor,
                radius = 4.dp.toPx(),
                center = Offset(cx, cy),
            )
            drawCircle(
                color = MonoBlack,
                radius = 1.5.dp.toPx(),
                center = Offset(cx, cy),
            )
        }

        // 6. Centered Digital Readout with Subtle Plate Protection
        Column(
            modifier = Modifier
                .background(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                    shape = CircleShape,
                )
                .padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = timeFormatted,
                fontSize = 34.sp,
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.onSurface,
                letterSpacing = 1.sp,
            )
            Text(
                text = phaseLabel,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                color = if (isBreak) MonoSuccess else MonoOnSurfaceVariant,
            )
        }
    }
}
