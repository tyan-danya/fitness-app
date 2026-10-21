package com.dtyan.fitdiary.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Cookie
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dtyan.fitdiary.domain.MealType
import com.dtyan.fitdiary.domain.MeasurementType

/** Иконка приёма пищи: рассвет, день, ночь и перекус. */
val MealType.icon: ImageVector
    get() = when (this) {
        MealType.BREAKFAST -> Icons.Rounded.WbTwilight
        MealType.LUNCH -> Icons.Rounded.LightMode
        MealType.DINNER -> Icons.Rounded.Bedtime
        MealType.SNACK -> Icons.Rounded.Cookie
    }

/**
 * Силуэт человека с подсвеченной зоной замера — показывает, где прикладывать ленту.
 * Сторона как в зеркале: «левый» подсвечивается слева.
 */
@Composable
fun BodyZoneIcon(
    type: MeasurementType,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    accent: Color = sectionColors().accent,
    container: Color = sectionColors().container,
) {
    val figure = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.32f)
    Surface(modifier.size(size), shape = RoundedCornerShape(size * 0.3f), color = container) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.padding(size * 0.1f).size(size * 0.8f)) { drawBodyZone(type, figure, accent) }
        }
    }
}

/** Фигура в сетке 24×24: голова, шея, торс, руки и ноги; зона — толстой линией акцента. */
private fun DrawScope.drawBodyZone(type: MeasurementType, figure: Color, accent: Color) {
    val u = size.minDimension / 24f
    fun p(x: Float, y: Float) = Offset(x * u, y * u)
    val limb = 2.1f * u
    val highlight = 2.6f * u

    drawCircle(figure, radius = 2.2f * u, center = p(12f, 3f))
    drawRoundRect(figure, topLeft = p(11f, 4.8f), size = Size(2f * u, 1.8f * u))
    val torso = Path().apply {
        moveTo(7.6f * u, 6.6f * u)
        lineTo(16.4f * u, 6.6f * u)
        lineTo(15.3f * u, 12.4f * u)
        lineTo(15.8f * u, 15.6f * u)
        lineTo(8.2f * u, 15.6f * u)
        lineTo(8.7f * u, 12.4f * u)
        close()
    }
    drawPath(torso, figure)
    fun segment(a: Offset, b: Offset, color: Color = figure, width: Float = limb) =
        drawLine(color, a, b, strokeWidth = width, cap = StrokeCap.Round)
    // Плечо → локоть → кисть; бедро → колено → стопа. Индекс 0 — левая сторона рисунка.
    val shoulder = listOf(p(7.4f, 7.4f), p(16.6f, 7.4f))
    val elbow = listOf(p(6.1f, 11.2f), p(17.9f, 11.2f))
    val wrist = listOf(p(5.3f, 15f), p(18.7f, 15f))
    val hip = listOf(p(10.1f, 15.4f), p(13.9f, 15.4f))
    val knee = listOf(p(9.7f, 19.4f), p(14.3f, 19.4f))
    val ankle = listOf(p(9.6f, 23f), p(14.4f, 23f))
    for (side in 0..1) {
        segment(shoulder[side], elbow[side]); segment(elbow[side], wrist[side])
        segment(hip[side], knee[side]); segment(knee[side], ankle[side])
    }
    fun band(y: Float, left: Float, right: Float) =
        drawRoundRect(accent, topLeft = p(left, y - 0.9f), size = Size((right - left) * u, 1.8f * u),
            cornerRadius = CornerRadius(0.9f * u))
    fun mid(a: Offset, b: Offset) = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
    when (type) {
        MeasurementType.NECK -> band(5.7f, 10.4f, 13.6f)
        MeasurementType.SHOULDERS -> band(7.3f, 6.6f, 17.4f)
        MeasurementType.CHEST -> band(9f, 7.8f, 16.2f)
        MeasurementType.WAIST -> band(12.2f, 8.4f, 15.6f)
        MeasurementType.BELLY -> band(13.6f, 8.3f, 15.7f)
        MeasurementType.HIPS -> band(15.4f, 7.8f, 16.2f)
        MeasurementType.BICEPS_L, MeasurementType.BICEPS_R -> {
            val side = if (type == MeasurementType.BICEPS_L) 0 else 1
            segment(shoulder[side], mid(shoulder[side], elbow[side]).let { mid(it, elbow[side]) }, accent, highlight)
        }
        MeasurementType.FOREARM_L, MeasurementType.FOREARM_R -> {
            val side = if (type == MeasurementType.FOREARM_L) 0 else 1
            segment(elbow[side], mid(elbow[side], wrist[side]), accent, highlight)
        }
        MeasurementType.THIGH_L, MeasurementType.THIGH_R -> {
            val side = if (type == MeasurementType.THIGH_L) 0 else 1
            segment(hip[side], mid(hip[side], knee[side]), accent, highlight)
        }
        MeasurementType.CALF_L, MeasurementType.CALF_R -> {
            val side = if (type == MeasurementType.CALF_L) 0 else 1
            segment(knee[side], mid(knee[side], ankle[side]), accent, highlight)
        }
    }
}
