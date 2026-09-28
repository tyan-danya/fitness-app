package com.dtyan.fitdiary.ui.stats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.dtyan.fitdiary.ui.common.EmptyState
import com.dtyan.fitdiary.ui.common.IllustrationChart
import com.dtyan.fitdiary.ui.theme.fitAccents

/**
 * Composable-обёртки графиков: берут цвета из темы, анимируют появление
 * и делегируют рисование чистым функциям из ChartDrawing.kt.
 */

@Composable
private fun chartColors(accent: Color, gradient: List<Color>) = ChartColors(
    accent = accent,
    gradient = gradient,
    grid = MaterialTheme.colorScheme.outlineVariant,
    axis = MaterialTheme.colorScheme.outline,
    surface = MaterialTheme.colorScheme.surfaceContainerLow,
    muted = MaterialTheme.colorScheme.surfaceContainerHigh,
)

@Composable
private fun chartTextStyles() = ChartTextStyles(
    axis = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
    value = MaterialTheme.typography.labelMedium.copy(
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.SemiBold,
    ),
)

/**
 * Линейный график. [color] — акцент линии: для веса — `fitAccents.stats`,
 * для прогресса упражнения — `fitAccents.workout`. [minSpan] — минимальный охват оси Y
 * (для веса 2 кг: колебания в сотни граммов не растягиваются на весь экран, но 1–2 кг видны отчётливо).
 */
@Composable
fun LineChart(
    points: List<Pair<Long, Double>>,
    color: Color,
    modifier: Modifier = Modifier,
    minSpan: Double = 0.0,
    valueFormatter: (Double) -> String = { "%.1f".format(it) },
) {
    if (points.size < 2) {
        EmptyChartStub(modifier)
        return
    }
    val colors = chartColors(color, listOf(color, color))
    val styles = chartTextStyles()
    val textMeasurer = rememberTextMeasurer()

    // Появление: линия «прорисовывается» слева направо при каждой смене данных.
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(points) {
        reveal.snapTo(0f)
        reveal.animateTo(1f, tween(durationMillis = 700, easing = FastOutSlowInEasing))
    }

    Canvas(modifier = modifier) {
        drawLineChart(
            points = points,
            colors = colors,
            styles = styles,
            textMeasurer = textMeasurer,
            valueFormatter = valueFormatter,
            reveal = reveal.value,
            minSpan = minSpan,
        )
    }
}

/** Столбчатый график тоннажа: градиент statsGradient, ноль внизу, анимация роста от базовой линии. */
@Composable
fun BarChart(
    bars: List<Pair<String, Double>>,
    modifier: Modifier = Modifier,
    valueFormatter: (Double) -> String = { "%.0f".format(it) },
) {
    if (bars.isEmpty()) {
        EmptyChartStub(modifier)
        return
    }
    val colors = chartColors(fitAccents.stats, fitAccents.statsGradient)
    val styles = chartTextStyles()
    val textMeasurer = rememberTextMeasurer()

    val grow = remember { Animatable(0f) }
    LaunchedEffect(bars) {
        grow.snapTo(0f)
        grow.animateTo(1f, tween(durationMillis = 600, easing = FastOutSlowInEasing))
    }

    Canvas(modifier = modifier) {
        drawBarChart(
            bars = bars,
            colors = colors,
            styles = styles,
            textMeasurer = textMeasurer,
            valueFormatter = valueFormatter,
            grow = grow.value,
        )
    }
}

@Composable
private fun EmptyChartStub(modifier: Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        EmptyState(
            title = "Мало данных",
            subtitle = "Нужно хотя бы две точки — продолжайте записывать",
            illustration = { IllustrationChart(size = 96.dp) },
        )
    }
}
