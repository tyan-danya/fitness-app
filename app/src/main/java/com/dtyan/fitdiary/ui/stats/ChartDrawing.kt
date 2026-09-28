package com.dtyan.fitdiary.ui.stats

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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Отрисовка графиков как чистые функции DrawScope — без Compose-состояния,
 * поэтому их можно рисовать в bitmap в юнит-тестах и проверять глазами.
 * Composable-обёртки (Charts.kt) добавляют цвета темы и анимацию появления.
 */

/** Цвета графика из темы. */
data class ChartColors(
    val accent: Color,
    val gradient: List<Color>,
    val grid: Color,
    val axis: Color,
    val surface: Color,
    val muted: Color,
)

/** Стили подписей. */
data class ChartTextStyles(
    val axis: TextStyle,
    val value: TextStyle,
)

/** Шкала оси Y: «круглые» деления, ноль всегда в шкале для столбиков, не обязательно — для линий. */
data class Axis(val min: Double, val max: Double, val step: Double) {
    val ticks: List<Double>
        get() {
            val out = mutableListOf<Double>()
            var v = min
            var guard = 0
            while (v <= max + step * 1e-6 && guard++ < 50) {
                out += v
                v += step
            }
            return out
        }
}

/**
 * Подбирает шкалу с «красивым» шагом (1, 2, 2.5, 5 × 10ⁿ) на ≈[targetTicks] делений.
 * [minSpan] — минимальный охват (для веса ±1 кг вокруг данных, чтобы не растягивать шум на весь экран,
 * но и не терять динамику). [includeZero] — начинать от нуля (столбики).
 */
fun niceAxis(minValue: Double, maxValue: Double, targetTicks: Int = 4, minSpan: Double = 0.0, includeZero: Boolean = false): Axis {
    var lo = if (includeZero) minOf(0.0, minValue) else minValue
    var hi = if (includeZero) maxOf(0.0, maxValue) else maxValue
    if (hi - lo < minSpan) {
        val mid = (lo + hi) / 2
        lo = mid - minSpan / 2
        hi = mid + minSpan / 2
        if (includeZero && lo < 0 && minValue >= 0) { lo = 0.0; hi = max(hi, minSpan) }
    }
    if (hi - lo < 1e-9) { lo -= 1.0; hi += 1.0 }
    val rawStep = (hi - lo) / targetTicks
    val magnitude = 10.0.pow(floor(log10(rawStep)))
    val norm = rawStep / magnitude
    val niceNorm = when {
        norm <= 1.0 -> 1.0
        norm <= 2.0 -> 2.0
        norm <= 2.5 -> 2.5
        norm <= 5.0 -> 5.0
        else -> 10.0
    }
    val step = niceNorm * magnitude
    val niceLo = floor(lo / step + 1e-9) * step
    val niceHi = ceil(hi / step - 1e-9) * step
    return Axis(min = niceLo, max = if (niceHi > niceLo) niceHi else niceLo + step, step = step)
}

private val DAY_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM")

/** Подписи оси X по датам: первая, последняя и равномерные промежуточные без наложения. */
fun dateAxisLabels(minMillis: Long, maxMillis: Long, maxLabels: Int, zone: ZoneId = ZoneId.systemDefault()): List<Pair<Long, String>> {
    if (maxLabels <= 0) return emptyList()
    val first = Instant.ofEpochMilli(minMillis).atZone(zone).toLocalDate()
    val last = Instant.ofEpochMilli(maxMillis).atZone(zone).toLocalDate()
    val days = (last.toEpochDay() - first.toEpochDay()).coerceAtLeast(0)
    if (days == 0L || maxLabels == 1) return listOf(minMillis to first.format(DAY_MONTH))
    val n = minOf(maxLabels, (days + 1).toInt())
    return (0 until n).map { i ->
        val d: LocalDate = first.plusDays((days * i / (n - 1)))
        val t = if (i == 0) minMillis else if (i == n - 1) maxMillis else d.atStartOfDay(zone).toInstant().toEpochMilli()
        t to d.format(DAY_MONTH)
    }
}

/**
 * Линейный график: сетка по «красивым» делениям с подписями слева, даты снизу,
 * градиентная заливка под линией, точки, подпись последнего значения.
 * [reveal] 0..1 — доля ширины, уже «прорисованная» анимацией.
 */
fun DrawScope.drawLineChart(
    points: List<Pair<Long, Double>>,
    colors: ChartColors,
    styles: ChartTextStyles,
    textMeasurer: TextMeasurer,
    valueFormatter: (Double) -> String,
    reveal: Float = 1f,
    minSpan: Double = 0.0,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    if (points.size < 2) return
    val sorted = points.sortedBy { it.first }
    val axis = niceAxis(sorted.minOf { it.second }, sorted.maxOf { it.second }, targetTicks = 4, minSpan = minSpan)
    val tickLabels = axis.ticks.map { it to textMeasurer.measure(valueFormatter(it), styles.axis) }
    val gutter = tickLabels.maxOf { it.second.size.width } + 10.dp.toPx()
    val axisLabelHeight = textMeasurer.measure("00.00", styles.axis).size.height

    val dotRadius = 4.dp.toPx()
    val dotOuter = dotRadius + 2.dp.toPx()
    val chartLeft = gutter
    val chartRight = size.width - dotOuter - 2.dp.toPx()
    val chartTop = max(tickLabels.first().second.size.height / 2f, dotOuter) + 2.dp.toPx()
    val chartBottom = size.height - axisLabelHeight - 6.dp.toPx() - dotOuter / 2
    val chartWidth = chartRight - chartLeft
    val chartHeight = chartBottom - chartTop
    if (chartWidth <= 0 || chartHeight <= 0) return

    fun yOf(v: Double): Float = chartBottom - ((v - axis.min) / (axis.max - axis.min)).toFloat() * chartHeight

    // Сетка и подписи Y
    val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))
    tickLabels.forEach { (v, layout) ->
        val y = yOf(v)
        drawLine(colors.grid, Offset(chartLeft, y), Offset(chartRight, y), strokeWidth = 1.dp.toPx(), pathEffect = dash)
        drawText(layout, topLeft = Offset(0f, (y - layout.size.height / 2f).coerceIn(0f, size.height - layout.size.height)))
    }

    val minX = sorted.first().first
    val maxX = sorted.last().first
    val spanX = (maxX - minX).coerceAtLeast(1L).toFloat()
    fun xOf(t: Long): Float = chartLeft + (t - minX) / spanX * chartWidth

    // Подписи X (даты)
    val sampleW = textMeasurer.measure("00.00", styles.axis).size.width
    val maxLabels = (chartWidth / (sampleW * 1.8f)).toInt().coerceIn(2, 6)
    dateAxisLabels(minX, maxX, maxLabels, zone).forEach { (t, label) ->
        val layout = textMeasurer.measure(label, styles.axis)
        val x = (xOf(t) - layout.size.width / 2f).coerceIn(chartLeft - 4.dp.toPx(), size.width - layout.size.width)
        drawText(layout, topLeft = Offset(x, size.height - layout.size.height))
    }

    val linePath = Path()
    sorted.forEachIndexed { i, (t, v) ->
        if (i == 0) linePath.moveTo(xOf(t), yOf(v)) else linePath.lineTo(xOf(t), yOf(v))
    }
    val fillPath = Path().apply {
        addPath(linePath)
        lineTo(xOf(maxX), chartBottom)
        lineTo(xOf(minX), chartBottom)
        close()
    }

    clipRect(left = 0f, top = 0f, right = size.width * reveal.coerceIn(0f, 1f), bottom = size.height) {
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(colors.accent.copy(alpha = 0.28f), Color.Transparent),
                startY = chartTop,
                endY = chartBottom,
            ),
        )
        drawPath(linePath, colors.accent, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        sorted.forEach { (t, v) ->
            val c = Offset(xOf(t), yOf(v))
            drawCircle(colors.surface, dotOuter, c)
            drawCircle(colors.accent, dotRadius, c)
        }
        // Подпись последнего значения — над последней точкой (или под ней, если сверху нет места)
        val (lt, lv) = sorted.last()
        val lastLayout = textMeasurer.measure(valueFormatter(lv), styles.value)
        val lx = (xOf(lt) - lastLayout.size.width / 2f).coerceIn(chartLeft, size.width - lastLayout.size.width - 4.dp.toPx())
        val above = yOf(lv) - dotOuter - lastLayout.size.height - 6.dp.toPx()
        val ly = if (above >= 0f) above else yOf(lv) + dotOuter + 6.dp.toPx()
        drawRoundRect(
            colors.surface.copy(alpha = 0.85f),
            Offset(lx - 4.dp.toPx(), ly - 2.dp.toPx()),
            Size(lastLayout.size.width + 8.dp.toPx(), lastLayout.size.height + 4.dp.toPx()),
            CornerRadius(6.dp.toPx(), 6.dp.toPx()),
        )
        drawText(lastLayout, topLeft = Offset(lx, ly))
    }
}

/**
 * Столбчатый график от нулевой базовой линии (внизу): «красивые» деления Y с подписями,
 * значения над столбиками, подписи категорий снизу. [grow] 0..1 — доля высоты, до которой выросли столбики.
 */
fun DrawScope.drawBarChart(
    bars: List<Pair<String, Double>>,
    colors: ChartColors,
    styles: ChartTextStyles,
    textMeasurer: TextMeasurer,
    valueFormatter: (Double) -> String,
    grow: Float = 1f,
) {
    if (bars.isEmpty()) return
    val maxV = max(bars.maxOf { it.second }, 0.0)
    val axis = niceAxis(0.0, if (maxV <= 0.0) 1.0 else maxV, targetTicks = 4, includeZero = true)
    val tickLabels = axis.ticks.map { it to textMeasurer.measure(valueFormatter(it), styles.axis) }
    val gutter = tickLabels.maxOf { it.second.size.width } + 10.dp.toPx()
    val axisLabelHeight = textMeasurer.measure("00.00", styles.axis).size.height
    val valueHeight = textMeasurer.measure("0", styles.value).size.height

    val chartLeft = gutter
    val chartRight = size.width - 4.dp.toPx()
    val chartTop = valueHeight + 6.dp.toPx() // место под значение над самым высоким столбиком
    val chartBottom = size.height - axisLabelHeight - 6.dp.toPx()
    val chartWidth = chartRight - chartLeft
    val chartHeight = chartBottom - chartTop
    if (chartWidth <= 0 || chartHeight <= 0) return

    fun yOf(v: Double): Float = chartBottom - ((v - axis.min) / (axis.max - axis.min)).toFloat() * chartHeight

    val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))
    tickLabels.forEach { (v, layout) ->
        val y = yOf(v)
        if (v != 0.0) drawLine(colors.grid, Offset(chartLeft, y), Offset(chartRight, y), strokeWidth = 1.dp.toPx(), pathEffect = dash)
        drawText(layout, topLeft = Offset(0f, (y - layout.size.height / 2f).coerceIn(0f, size.height - layout.size.height)))
    }
    // Базовая линия нуля — сплошная, заметная
    drawLine(colors.axis, Offset(chartLeft, chartBottom), Offset(chartRight, chartBottom), strokeWidth = 1.5.dp.toPx())

    val slot = chartWidth / bars.size
    val barWidth = slot * 0.62f
    bars.forEachIndexed { i, (label, value) ->
        val centerX = chartLeft + slot * i + slot / 2f
        val left = centerX - barWidth / 2f
        if (value > 0) {
            val fullHeight = chartBottom - yOf(value)
            val height = fullHeight * grow.coerceIn(0f, 1f)
            val top = chartBottom - height
            val radius = minOf(barWidth / 2f, 10.dp.toPx(), height)
            val barPath = Path().apply {
                addRoundRect(
                    RoundRect(
                        left = left, top = top, right = left + barWidth, bottom = chartBottom,
                        topLeftCornerRadius = CornerRadius(radius, radius),
                        topRightCornerRadius = CornerRadius(radius, radius),
                        bottomRightCornerRadius = CornerRadius.Zero,
                        bottomLeftCornerRadius = CornerRadius.Zero,
                    ),
                )
            }
            drawPath(barPath, brush = Brush.verticalGradient(colors.gradient, startY = top, endY = chartBottom))
            val valueLayout = textMeasurer.measure(valueFormatter(value), styles.value)
            drawText(
                valueLayout,
                topLeft = Offset(
                    (centerX - valueLayout.size.width / 2f).coerceIn(0f, size.width - valueLayout.size.width),
                    (top - valueLayout.size.height - 3.dp.toPx()).coerceAtLeast(0f),
                ),
            )
        } else {
            // Пустая неделя: маркер у базовой линии
            drawRoundRect(
                colors.muted,
                Offset(left, chartBottom - 3.dp.toPx()),
                Size(barWidth, 3.dp.toPx()),
                CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx()),
            )
        }
        val axisLayout = textMeasurer.measure(label, styles.axis)
        drawText(
            axisLayout,
            topLeft = Offset(
                (centerX - axisLayout.size.width / 2f).coerceIn(0f, size.width - axisLayout.size.width),
                size.height - axisLayout.size.height,
            ),
        )
    }
}

/** Округление к десятым для подписей, где формат не задан. */
fun roundTenth(v: Double): Double = (v * 10).roundToInt() / 10.0
