package com.dtyan.fitdiary.ui.activity

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dtyan.fitdiary.ui.common.Format
import com.dtyan.fitdiary.ui.common.sectionColors
import com.dtyan.fitdiary.ui.theme.fitAccents
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/** Секунд в полном круге: минута, а для длинной цели — с запасом до ближайших 30 секунд. */
internal fun dialSpanSeconds(goalSeconds: Double?): Double {
    val goal = goalSeconds ?: return 60.0
    return if (goal <= 50.0) 60.0 else ceil(goal * 1.2 / 30.0) * 30.0
}

/**
 * Циферблат секундомера: по кругу бежит полоса прошедшего времени, цель отмечена флажком на шкале.
 * После цели полоса становится золотой, второй круг рисуется внутренней дорожкой.
 */
@Composable
fun StopwatchDial(
    elapsedSeconds: Long?,
    goalSeconds: Double?,
    running: Boolean,
    modifier: Modifier = Modifier,
    nextGoalSeconds: Double? = null,
    status: String,
) {
    val colors = sectionColors()
    val gold = fitAccents.gold
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    val tick = MaterialTheme.colorScheme.outline
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val span = dialSpanSeconds(goalSeconds)
    val elapsed = (elapsedSeconds ?: 0L).toFloat()
    // Секунды приходят раз в секунду; линейная анимация превращает шаги в плавный бег полосы.
    val animated by animateFloatAsState(
        targetValue = elapsed,
        animationSpec = if (running) tween(durationMillis = 1000, easing = LinearEasing) else tween(durationMillis = 300),
        label = "stopwatchSweep",
    )
    val reached = goalSeconds != null && elapsed >= goalSeconds
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(reached) { if (reached && running) haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    val description = buildString {
        append("Прошло ${Format.durationClock((elapsedSeconds ?: 0L) * 1000)}")
        goalSeconds?.let { append(", цель ${Format.durationClock((it * 1000).toLong())}") }
    }

    Box(modifier.widthIn(max = 300.dp).fillMaxWidth().aspectRatio(1f).semantics { contentDescription = description },
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val ring = size.minDimension * 0.065f
            val radius = size.minDimension / 2f - ring * 1.7f
            val center = Offset(size.width / 2f, size.height / 2f)
            fun point(fraction: Float, r: Float) = (fraction * 2 * PI - PI / 2).let { a ->
                Offset(center.x + (cos(a) * r).toFloat(), center.y + (sin(a) * r).toFloat())
            }
            // Шкала как у часов: короткие риски каждые 5 секунд, длинные — по четвертям круга.
            val tickEvery = if (span <= 60) 5.0 else 10.0
            var s = 0.0
            while (s < span - 0.001) {
                val major = (s % (span / 4)) < 0.001
                val f = (s / span).toFloat()
                drawLine(tick.copy(alpha = if (major) 0.8f else 0.35f), point(f, radius - ring * (if (major) 1.9f else 1.35f)),
                    point(f, radius - ring * 0.95f), strokeWidth = if (major) 4f else 2f, cap = StrokeCap.Round)
                s += tickEvery
            }
            // Дорожка и пройденное время.
            val arcTopLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2, radius * 2)
            drawArc(track, -90f, 360f, false, arcTopLeft, arcSize, style = Stroke(ring, cap = StrokeCap.Round))
            val firstLap = (animated / span.toFloat()).coerceIn(0f, 1f)
            if (firstLap > 0f) {
                val brush = Brush.sweepGradient(listOf(colors.accent.copy(alpha = 0.55f), if (reached) gold else colors.accent), center)
                rotate(-90f, center) {
                    drawArc(brush, 0f, 360f * firstLap, false, arcTopLeft, arcSize, style = Stroke(ring, cap = StrokeCap.Round))
                }
            }
            val secondLap = ((animated - span.toFloat()) / span.toFloat()).coerceIn(0f, 1f)
            if (secondLap > 0f) {
                val inner = radius - ring * 1.25f
                drawArc(gold.copy(alpha = 0.7f), -90f, 360f * secondLap, false, Offset(center.x - inner, center.y - inner),
                    Size(inner * 2, inner * 2), style = Stroke(ring * 0.4f, cap = StrokeCap.Round))
            }
            // Бегунок на конце полосы.
            if (animated > 0f) drawCircle(Color.White, ring * 0.32f, point(firstLap.takeIf { secondLap == 0f } ?: secondLap,
                if (secondLap > 0f) radius - ring * 1.25f else radius))
            nextGoalSeconds?.takeIf { it <= span && it != goalSeconds }?.let { drawGoalFlag(point((it / span).toFloat(), radius + ring * 1.15f),
                (it / span).toFloat(), tick.copy(alpha = 0.6f), ring * 0.55f) }
            goalSeconds?.takeIf { it <= span }?.let { goal ->
                val f = (goal / span).toFloat()
                drawGoalFlag(point(f, radius + ring * 1.15f), f, if (reached) gold else colors.accent, ring * 0.8f)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(Format.durationClock((elapsedSeconds ?: 0L) * 1000), style = MaterialTheme.typography.displayMedium,
                color = if (reached) gold else MaterialTheme.colorScheme.onSurface)
            goalSeconds?.let { goal ->
                Text("цель ${Format.durationClock((goal * 1000).toLong())}", style = MaterialTheme.typography.titleSmall,
                    color = if (reached) gold else colors.accent)
            }
            Text(status, style = MaterialTheme.typography.labelLarge, color = labelColor, textAlign = TextAlign.Center)
        }
    }
}

/** Треугольный флажок цели снаружи кольца, остриём к шкале. */
private fun DrawScope.drawGoalFlag(at: Offset, fraction: Float, color: Color, size: Float) {
    rotate(fraction * 360f, at) {
        val path = Path().apply {
            moveTo(at.x, at.y + size * 0.9f)
            lineTo(at.x - size * 0.8f, at.y - size * 0.6f)
            lineTo(at.x + size * 0.8f, at.y - size * 0.6f)
            close()
        }
        drawPath(path, color)
    }
}
