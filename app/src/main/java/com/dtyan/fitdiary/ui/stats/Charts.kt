package com.dtyan.fitdiary.ui.stats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.dtyan.fitdiary.ui.common.EmptyState
import com.dtyan.fitdiary.ui.common.IllustrationChart
import com.dtyan.fitdiary.ui.theme.fitAccents
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

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
    if (points.isEmpty()) {
        EmptyChartStub(modifier)
        return
    }
    val sorted = remember(points) { points.sortedBy { it.first } }
    val rows = sorted.map { dateLabel(it.first) to valueFormatter(it.second) }
    var selected by remember(points) { mutableStateOf<Int?>(null) }
    if (selected != null) ChartDataDialog(rows, selected!!, onDismiss = { selected = null })
    if (points.size == 1) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(rows[0].second, style = MaterialTheme.typography.headlineMedium)
                Text(rows[0].first, style = MaterialTheme.typography.bodyMedium)
            }
        }
        return
    }
    val colors = chartColors(color, listOf(color, color))
    val styles = chartTextStyles()
    val textMeasurer = rememberTextMeasurer()
    val axis = niceAxis(sorted.minOf { it.second }, sorted.maxOf { it.second }, minSpan = minSpan)
    val density = LocalDensity.current
    val left = axis.ticks.maxOf { textMeasurer.measure(valueFormatter(it), styles.axis).size.width } + with(density) { 10.dp.toPx() }
    val rightPadding = with(density) { 8.dp.toPx() }

    // Появление: линия «прорисовывается» слева направо при каждой смене данных.
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(points) {
        reveal.snapTo(0f)
        reveal.animateTo(1f, tween(durationMillis = 700, easing = FastOutSlowInEasing))
    }

    Canvas(modifier = modifier
        .semantics {
            contentDescription = "График: ${rows.size} значений. Последнее: ${rows.last().first}, ${rows.last().second}"
            onClick(label = "Показать даты и значения") { selected = rows.lastIndex; true }
        }
        .pointerInput(points, left, rightPadding) {
            detectTapGestures { tap ->
                val ratio = ((tap.x - left) / (size.width - rightPadding - left).coerceAtLeast(1f)).coerceIn(0f, 1f)
                val time = sorted.first().first + (sorted.last().first - sorted.first().first) * ratio.toDouble()
                selected = sorted.indices.minByOrNull { abs(sorted[it].first - time) }
            }
        }) {
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
    var showDetails by remember(bars) { mutableStateOf(false) }
    if (showDetails) ChartDataDialog(bars.map { it.first to valueFormatter(it.second) }, bars.lastIndex) { showDetails = false }

    val grow = remember { Animatable(0f) }
    LaunchedEffect(bars) {
        grow.snapTo(0f)
        grow.animateTo(1f, tween(durationMillis = 600, easing = FastOutSlowInEasing))
    }

    Canvas(modifier = modifier
        .semantics {
            contentDescription = "Тоннаж по неделям: ${bars.size} значений"
            onClick(label = "Показать значения") { showDetails = true; true }
        }
        .pointerInput(bars) { detectTapGestures { showDetails = true } }) {
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

private fun dateLabel(timestamp: Long): String = Instant.ofEpochMilli(timestamp)
    .atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))

@Composable
private fun ChartDataDialog(rows: List<Pair<String, String>>, selected: Int, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Значения графика") }, text = {
        LazyColumn(state = rememberLazyListState(initialFirstVisibleItemIndex = (selected - 2).coerceAtLeast(0)),
            modifier = Modifier.heightIn(max = 400.dp)) {
            itemsIndexed(rows) { index, row ->
                Surface(color = if (index == selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(row.first, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(row.second, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } })
}

@Composable
private fun EmptyChartStub(modifier: Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        EmptyState(
            title = "Данных пока нет",
            subtitle = "Добавьте первую запись",
            illustration = { IllustrationChart(size = 96.dp) },
        )
    }
}
