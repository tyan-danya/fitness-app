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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.dtyan.fitdiary.ui.common.EmptyState
import com.dtyan.fitdiary.ui.common.IllustrationChart
import com.dtyan.fitdiary.ui.theme.fitAccents
import kotlin.math.max

/**
 * Графики на Canvas без внешних библиотек: фирменные цвета раздела,
 * градиентные заливки и мягкая анимация появления при смене данных.
 */

private const val GRID_LINES = 3

/**
 * Линейный график. [color] — акцент линии: для веса — `fitAccents.stats`,
 * для прогресса упражнения — `fitAccents.workout`.
 */
@Composable
fun LineChart(
    points: List<Pair<Long, Double>>,
    color: Color,
    modifier: Modifier = Modifier,
    valueFormatter: (Double) -> String = { "%.1f".format(it) },
) {
    if (points.size < 2) {
        EmptyChartStub(modifier)
        return
    }
    val surfaceColor = MaterialTheme.colorScheme.surface
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val textMeasurer = rememberTextMeasurer()

    // Появление: линия «прорисовывается» слева направо при каждой смене данных.
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(points) {
        reveal.snapTo(0f)
        reveal.animateTo(1f, tween(durationMillis = 700, easing = FastOutSlowInEasing))
    }

    Canvas(modifier = modifier) {
        val sorted = points.sortedBy { it.first }
        val minV = sorted.minOf { it.second }
        val maxV = sorted.maxOf { it.second }
        // Защита от вырожденного диапазона (все значения равны)
        val range = if (maxV - minV < 1e-9) 1.0 else maxV - minV
        val loV = if (maxV - minV < 1e-9) minV - range / 2 else minV
        val hiV = if (maxV - minV < 1e-9) maxV + range / 2 else maxV

        val maxLabel = textMeasurer.measure(valueFormatter(maxV), labelStyle)
        val minLabel = textMeasurer.measure(valueFormatter(minV), labelStyle)
        val gutter = max(maxLabel.size.width, minLabel.size.width) + 8.dp.toPx()

        val dotRadius = 4.dp.toPx()
        val dotOuter = dotRadius + 2.dp.toPx() // радиус вместе с обводкой

        val chartLeft = gutter
        val chartRight = size.width - dotOuter
        val chartTop = max(maxLabel.size.height / 2f, dotOuter)
        val chartBottom = size.height - max(minLabel.size.height / 2f, dotOuter)
        val chartWidth = chartRight - chartLeft
        val chartHeight = chartBottom - chartTop

        fun yOf(v: Double): Float =
            chartBottom - ((v - loV) / (hiV - loV)).toFloat() * chartHeight

        // Сетка: 3 тонкие пунктирные горизонтали (верх, середина, низ)
        val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
        repeat(GRID_LINES) { i ->
            val y = chartTop + chartHeight * i / (GRID_LINES - 1)
            drawLine(
                color = gridColor,
                start = Offset(chartLeft, y),
                end = Offset(chartRight, y),
                strokeWidth = 1.dp.toPx(),
                pathEffect = dash,
            )
        }

        // Подписи min/max слева
        drawText(maxLabel, topLeft = Offset(0f, yOf(maxV) - maxLabel.size.height / 2f))
        drawText(minLabel, topLeft = Offset(0f, yOf(minV) - minLabel.size.height / 2f))

        val minX = sorted.first().first
        val maxX = sorted.last().first
        val spanX = (maxX - minX).coerceAtLeast(1L).toFloat()
        fun xOf(t: Long): Float = chartLeft + (t - minX) / spanX * chartWidth

        val linePath = Path()
        sorted.forEachIndexed { i, (t, v) ->
            val x = xOf(t)
            val y = yOf(v)
            if (i == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
        }
        // Замкнутый контур под линией — для градиентной заливки
        val fillPath = Path().apply {
            addPath(linePath)
            lineTo(xOf(maxX), chartBottom)
            lineTo(xOf(minX), chartBottom)
            close()
        }

        // Свип отрисовки: содержимое появляется в расширяющемся окне слева направо
        clipRect(left = 0f, top = 0f, right = size.width * reveal.value, bottom = size.height) {
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(color.copy(alpha = 0.25f), Color.Transparent),
                    startY = chartTop,
                    endY = chartBottom,
                ),
            )
            drawPath(
                path = linePath,
                color = color,
                style = Stroke(
                    width = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
            // Точки: залитый кружок с обводкой цвета поверхности
            sorted.forEach { (t, v) ->
                val dot = Offset(xOf(t), yOf(v))
                drawCircle(color = surfaceColor, radius = dotOuter, center = dot)
                drawCircle(color = color, radius = dotRadius, center = dot)
            }
        }
    }
}

/** Столбчатый график тоннажа: градиент statsGradient, скруглённый верх, анимация роста. */
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
    val gradientColors = fitAccents.statsGradient
    val zeroBarColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val valueStyle = MaterialTheme.typography.labelSmall.copy(
        color = MaterialTheme.colorScheme.onSurface,
    )
    val axisStyle = MaterialTheme.typography.labelSmall.copy(
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val textMeasurer = rememberTextMeasurer()

    // Столбики вырастают от базовой линии при каждой смене данных
    val grow = remember { Animatable(0f) }
    LaunchedEffect(bars) {
        grow.snapTo(0f)
        grow.animateTo(1f, tween(durationMillis = 600, easing = FastOutSlowInEasing))
    }

    Canvas(modifier = modifier) {
        val maxV = max(bars.maxOf { it.second }, 1.0) // чтобы нулевые данные не делили на ноль

        val maxLabel = textMeasurer.measure(valueFormatter(maxV), axisStyle)
        val minLabel = textMeasurer.measure(valueFormatter(0.0), axisStyle)
        val gutter = max(maxLabel.size.width, minLabel.size.width) + 8.dp.toPx()
        val axisLabelHeight = textMeasurer.measure("28.07", axisStyle).size.height

        val chartLeft = gutter
        val chartRight = size.width - 4.dp.toPx()
        val chartTop = maxLabel.size.height + 4.dp.toPx() // место под значения над столбиками
        val chartBottom = size.height - axisLabelHeight - 4.dp.toPx()
        val chartWidth = chartRight - chartLeft
        val chartHeight = chartBottom - chartTop

        val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
        repeat(GRID_LINES) { i ->
            val y = chartTop + chartHeight * i / (GRID_LINES - 1)
            drawLine(
                color = gridColor,
                start = Offset(chartLeft, y),
                end = Offset(chartRight, y),
                strokeWidth = 1.dp.toPx(),
                pathEffect = dash,
            )
        }
        drawText(maxLabel, topLeft = Offset(0f, chartTop - maxLabel.size.height / 2f))
        drawText(minLabel, topLeft = Offset(0f, chartBottom - minLabel.size.height / 2f))

        val slot = chartWidth / bars.size
        val barWidth = slot * 0.6f
        bars.forEachIndexed { i, (label, value) ->
            val centerX = chartLeft + slot * i + slot / 2f
            val left = centerX - barWidth / 2f
            if (value > 0) {
                val fullHeight = ((value / maxV) * chartHeight).toFloat()
                val height = fullHeight * grow.value
                val top = chartBottom - height
                // Скруглён только верх; радиус не больше текущей высоты столбика
                val radius = minOf(barWidth / 2f, 10.dp.toPx(), height)
                val barPath = Path().apply {
                    addRoundRect(
                        RoundRect(
                            left = left,
                            top = top,
                            right = left + barWidth,
                            bottom = chartBottom,
                            topLeftCornerRadius = CornerRadius(radius, radius),
                            topRightCornerRadius = CornerRadius(radius, radius),
                            bottomRightCornerRadius = CornerRadius.Zero,
                            bottomLeftCornerRadius = CornerRadius.Zero,
                        ),
                    )
                }
                drawPath(
                    path = barPath,
                    brush = Brush.verticalGradient(
                        colors = gradientColors,
                        startY = top,
                        endY = chartBottom,
                    ),
                )
                // Значение над столбиком — едет вверх вместе с ростом
                val valueLayout = textMeasurer.measure(valueFormatter(value), valueStyle)
                drawText(
                    valueLayout,
                    topLeft = Offset(
                        (centerX - valueLayout.size.width / 2f)
                            .coerceIn(0f, size.width - valueLayout.size.width),
                        (top - valueLayout.size.height - 2.dp.toPx()).coerceAtLeast(0f),
                    ),
                )
            } else {
                // Нулевая неделя: тонкая полоска-подложка у базовой линии
                drawRoundRect(
                    color = zeroBarColor,
                    topLeft = Offset(left, chartBottom - 3.dp.toPx()),
                    size = Size(barWidth, 3.dp.toPx()),
                    cornerRadius = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx()),
                )
            }
            // Подпись недели под столбиком
            val axisLayout = textMeasurer.measure(label, axisStyle)
            drawText(
                axisLayout,
                topLeft = Offset(
                    (centerX - axisLayout.size.width / 2f)
                        .coerceIn(0f, size.width - axisLayout.size.width),
                    chartBottom + 4.dp.toPx(),
                ),
            )
        }
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
