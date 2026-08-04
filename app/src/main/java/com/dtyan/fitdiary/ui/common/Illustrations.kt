package com.dtyan.fitdiary.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dtyan.fitdiary.ui.theme.fitAccents

/*
 * Плоские рисованные иллюстрации для пустых состояний и декора.
 * Рисуются Canvas-ом в цветах текущей темы — без ассетов и внешних библиотек.
 */

private fun DrawScope.roundRect(color: Color, rect: Rect, radius: Float) {
    drawRoundRect(
        color = color,
        topLeft = rect.topLeft,
        size = rect.size,
        cornerRadius = CornerRadius(radius, radius),
    )
}

/** Гантель под наклоном с тенью — пустые состояния тренировок. */
@Composable
fun IllustrationDumbbell(modifier: Modifier = Modifier, size: Dp = 120.dp) {
    val accent = fitAccents.workout
    val container = fitAccents.workoutContainer
    val onContainer = fitAccents.onWorkoutContainer
    Box(modifier.size(size)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = this.size.width
            val h = this.size.height
            // Тень
            drawOval(
                color = onContainer.copy(alpha = 0.10f),
                topLeft = Offset(w * 0.14f, h * 0.78f),
                size = Size(w * 0.72f, h * 0.12f),
            )
            rotate(degrees = -22f, pivot = Offset(w / 2, h / 2)) {
                val cy = h / 2
                val barH = h * 0.075f
                // Гриф
                roundRect(
                    color = onContainer.copy(alpha = 0.85f),
                    rect = Rect(Offset(w * 0.16f, cy - barH / 2), Size(w * 0.68f, barH)),
                    radius = barH,
                )
                // Внутренние блины
                val bigH = h * 0.42f
                val bigW = w * 0.115f
                roundRect(accent, Rect(Offset(w * 0.235f, cy - bigH / 2), Size(bigW, bigH)), bigW * 0.45f)
                roundRect(accent, Rect(Offset(w * 0.65f, cy - bigH / 2), Size(bigW, bigH)), bigW * 0.45f)
                // Внешние блины поменьше
                val smallH = h * 0.28f
                val smallW = w * 0.085f
                roundRect(container, Rect(Offset(w * 0.145f, cy - smallH / 2), Size(smallW, smallH)), smallW * 0.45f)
                roundRect(container, Rect(Offset(w * 0.77f, cy - smallH / 2), Size(smallW, smallH)), smallW * 0.45f)
            }
            // Искры
            drawCircle(accent.copy(alpha = 0.9f), radius = w * 0.02f, center = Offset(w * 0.85f, h * 0.2f))
            drawCircle(container, radius = w * 0.028f, center = Offset(w * 0.12f, h * 0.16f))
        }
    }
}

/** Миска салата — пустые состояния питания. */
@Composable
fun IllustrationSalad(modifier: Modifier = Modifier, size: Dp = 120.dp) {
    val accent = fitAccents.nutrition
    val container = fitAccents.nutritionContainer
    val leaf = fitAccents.workout
    val leafSoft = fitAccents.workoutContainer
    Box(modifier.size(size)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = this.size.width
            val h = this.size.height
            // Тень
            drawOval(
                color = accent.copy(alpha = 0.12f),
                topLeft = Offset(w * 0.16f, h * 0.82f),
                size = Size(w * 0.68f, h * 0.10f),
            )
            // Листья над миской
            drawCircle(leafSoft, radius = w * 0.13f, center = Offset(w * 0.36f, h * 0.34f))
            drawCircle(leaf, radius = w * 0.10f, center = Offset(w * 0.54f, h * 0.28f))
            drawCircle(leafSoft, radius = w * 0.085f, center = Offset(w * 0.68f, h * 0.38f))
            // Помидор
            drawCircle(accent, radius = w * 0.065f, center = Offset(w * 0.45f, h * 0.42f))
            // Миска — полукруг
            val bowl = Path().apply {
                moveTo(w * 0.18f, h * 0.48f)
                lineTo(w * 0.82f, h * 0.48f)
                arcTo(
                    rect = Rect(Offset(w * 0.18f, h * 0.16f), Size(w * 0.64f, h * 0.64f)),
                    startAngleDegrees = 0f,
                    sweepAngleDegrees = 180f,
                    forceMoveTo = false,
                )
                close()
            }
            drawPath(bowl, color = container)
            // Ободок миски
            roundRect(accent, Rect(Offset(w * 0.15f, h * 0.455f), Size(w * 0.70f, h * 0.05f)), h * 0.025f)
            // Ножка
            roundRect(container, Rect(Offset(w * 0.42f, h * 0.78f), Size(w * 0.16f, h * 0.05f)), h * 0.025f)
        }
    }
}

/** Столбики с трендом — пустые состояния статистики/графиков. */
@Composable
fun IllustrationChart(modifier: Modifier = Modifier, size: Dp = 120.dp) {
    val accent = fitAccents.stats
    val container = fitAccents.statsContainer
    val line = fitAccents.workout
    Box(modifier.size(size)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = this.size.width
            val h = this.size.height
            val base = h * 0.82f
            val barW = w * 0.14f
            fun bar(x: Float, barHeight: Float, color: Color) {
                roundRect(color, Rect(Offset(x, base - barHeight), Size(barW, barHeight)), barW * 0.35f)
            }
            bar(w * 0.16f, h * 0.28f, container)
            bar(w * 0.42f, h * 0.44f, accent)
            bar(w * 0.68f, h * 0.60f, container)
            // Линия тренда с точками
            val p1 = Offset(w * 0.20f, h * 0.42f)
            val p2 = Offset(w * 0.50f, h * 0.30f)
            val p3 = Offset(w * 0.80f, h * 0.14f)
            val trend = Path().apply {
                moveTo(p1.x, p1.y); lineTo(p2.x, p2.y); lineTo(p3.x, p3.y)
            }
            drawPath(trend, color = line, style = Stroke(width = w * 0.03f, cap = StrokeCap.Round))
            listOf(p1, p2, p3).forEach {
                drawCircle(Color.White, radius = w * 0.045f, center = it)
                drawCircle(line, radius = w * 0.03f, center = it)
            }
        }
    }
}

/** Лупа — пустой поиск. */
@Composable
fun IllustrationSearch(modifier: Modifier = Modifier, size: Dp = 120.dp) {
    val accent = fitAccents.workout
    val container = fitAccents.workoutContainer
    Box(modifier.size(size)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = this.size.width
            val h = this.size.height
            val c = Offset(w * 0.44f, h * 0.42f)
            val r = w * 0.26f
            drawCircle(container, radius = r, center = c)
            drawCircle(accent, radius = r, center = c, style = Stroke(width = w * 0.055f))
            // Блик
            drawArc(
                color = Color.White.copy(alpha = 0.7f),
                startAngle = 200f,
                sweepAngle = 60f,
                useCenter = false,
                topLeft = Offset(c.x - r * 0.62f, c.y - r * 0.62f),
                size = Size(r * 1.24f, r * 1.24f),
                style = Stroke(width = w * 0.03f, cap = StrokeCap.Round),
            )
            // Ручка
            val handle = Path().apply {
                moveTo(c.x + r * 0.72f, c.y + r * 0.72f)
                lineTo(w * 0.82f, h * 0.80f)
            }
            drawPath(handle, color = accent, style = Stroke(width = w * 0.075f, cap = StrokeCap.Round))
        }
    }
}

/**
 * Декор hero-карточки: силуэт штанги и полупрозрачные «блины»-круги.
 * Рисовать поверх градиента (в Box позади текста), цвета — белые с малой альфой.
 */
@Composable
fun HeroBarbellDecor(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        // Концентрические «блины» справа
        val c = Offset(w * 0.88f, h * 0.30f)
        drawCircle(Color.White.copy(alpha = 0.10f), radius = h * 0.62f, center = c)
        drawCircle(Color.White.copy(alpha = 0.10f), radius = h * 0.42f, center = c)
        drawCircle(Color.White.copy(alpha = 0.12f), radius = h * 0.22f, center = c)
        // Наклонный гриф в левом нижнем углу
        rotate(degrees = -24f, pivot = Offset(w * 0.18f, h * 0.85f)) {
            val barH = h * 0.055f
            drawRoundRect(
                color = Color.White.copy(alpha = 0.14f),
                topLeft = Offset(w * 0.0f, h * 0.85f - barH / 2),
                size = Size(w * 0.38f, barH),
                cornerRadius = CornerRadius(barH, barH),
            )
            val plateH = h * 0.30f
            val plateW = w * 0.035f
            drawRoundRect(
                color = Color.White.copy(alpha = 0.18f),
                topLeft = Offset(w * 0.10f, h * 0.85f - plateH / 2),
                size = Size(plateW, plateH),
                cornerRadius = CornerRadius(plateW * 0.5f, plateW * 0.5f),
            )
            drawRoundRect(
                color = Color.White.copy(alpha = 0.18f),
                topLeft = Offset(w * 0.26f, h * 0.85f - plateH / 2),
                size = Size(plateW, plateH),
                cornerRadius = CornerRadius(plateW * 0.5f, plateW * 0.5f),
            )
        }
    }
}
