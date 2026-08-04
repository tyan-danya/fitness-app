package com.dtyan.fitdiary.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dtyan.fitdiary.ui.theme.fitAccents
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Эмодзи группы мышц — единый словарь для всех экранов. */
fun muscleGroupEmoji(group: String): String = when (group) {
    "Грудь" -> "🏋️"
    "Спина" -> "🧗"
    "Ноги" -> "🦵"
    "Плечи" -> "🤸"
    "Бицепс" -> "💪"
    "Трицепс" -> "🥊"
    "Пресс" -> "🔥"
    else -> "⭐"
}

/** Заголовок секции: эмодзи + жирный текст. */
@Composable
fun SectionHeader(emoji: String, title: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(top = 8.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(emoji, style = MaterialTheme.typography.titleLarge)
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

/** Заголовок секции: иконка в цветной плашке + жирный текст (вместо эмодзи). */
@Composable
fun SectionHeader(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    iconContainer: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
) {
    Row(
        modifier = modifier.padding(top = 8.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(9.dp),
            color = iconContainer,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier
                    .padding(5.dp)
                    .size(18.dp),
            )
        }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

/** Компактная карточка-метрика: эмодзи, крупное значение, подпись. */
@Composable
fun StatChip(
    emoji: String,
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = container,
        contentColor = contentColor,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(emoji, style = MaterialTheme.typography.titleMedium)
            Text(value, style = MaterialTheme.typography.headlineSmall, maxLines = 1)
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Компактная карточка-метрика: тонированная иконка, крупное значение, подпись. */
@Composable
fun StatChip(
    icon: ImageVector,
    iconTint: Color,
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = container,
        contentColor = contentColor,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.height(2.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall, maxLines = 1)
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Кольцевой прогресс с содержимым по центру (калории, вес и т.п.). */
@Composable
fun ProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    ringSize: Dp = 150.dp,
    strokeWidth: Dp = 14.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "ringProgress",
    )
    Box(modifier.size(ringSize), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
            val inset = strokeWidth.toPx() / 2
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = stroke,
            )
            if (animated > 0f) {
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = 360f * animated,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = stroke,
                )
            }
        }
        content()
    }
}

/** Дружелюбное пустое состояние: крупный эмодзи + заголовок + подпись. */
@Composable
fun EmptyState(
    emoji: String,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(emoji, fontSize = 44.sp)
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Пустое состояние с рисованной иллюстрацией (см. Illustrations.kt) вместо эмодзи. */
@Composable
fun EmptyState(
    title: String,
    subtitle: String?,
    illustration: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        illustration()
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * «Пружинящее» нажатие: элемент слегка сжимается, пока палец на нём.
 * События не потребляет — clickable под ним работает как обычно.
 */
fun Modifier.pressScale(scaleDown: Float = 0.96f): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) scaleDown else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "pressScale",
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                pressed = true
                waitForUpOrCancellation()
                pressed = false
            }
        }
}

private data class ConfettiParticle(
    val angleRad: Float,
    val speed: Float,
    val radius: Float,
    val colorIndex: Int,
    val spin: Float,
    val isRect: Boolean,
)

/**
 * Залп конфетти по центру контейнера. Срабатывает при каждом увеличении [trigger]
 * (0 — ничего не рисуется). Накладывай поверх контента через Box.
 */
@Composable
fun ConfettiBurst(trigger: Int, modifier: Modifier = Modifier) {
    val accents = fitAccents
    val palette = listOf(
        accents.workout, accents.nutrition, accents.stats,
        accents.gold, accents.protein, accents.carbs,
    )
    val progress = remember(trigger) { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger > 0) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(durationMillis = 950, easing = LinearEasing))
        }
    }
    val particles = remember(trigger) {
        val rnd = Random(trigger)
        List(48) {
            ConfettiParticle(
                angleRad = (rnd.nextFloat() * 360f * Math.PI / 180f).toFloat(),
                speed = 0.25f + rnd.nextFloat() * 0.45f,
                radius = 6f + rnd.nextFloat() * 8f,
                colorIndex = rnd.nextInt(palette.size),
                spin = rnd.nextFloat() * 720f - 360f,
                isRect = rnd.nextBoolean(),
            )
        }
    }
    if (trigger <= 0) return
    Canvas(modifier.fillMaxSize()) {
        val t = progress.value
        if (t >= 1f) return@Canvas
        val alpha = (1f - t).coerceIn(0f, 1f)
        val gravity = 0.55f * t * t
        particles.forEach { p ->
            val dist = p.speed * t
            val x = center.x + cos(p.angleRad) * dist * size.width
            val y = center.y + sin(p.angleRad) * dist * size.height * 0.6f + gravity * size.height
            if (y < size.height) {
                val color = palette[p.colorIndex].copy(alpha = alpha)
                if (p.isRect) {
                    rotate(degrees = p.spin * t, pivot = Offset(x, y)) {
                        drawRect(
                            color = color,
                            topLeft = Offset(x - p.radius, y - p.radius / 2),
                            size = Size(p.radius * 2, p.radius),
                        )
                    }
                } else {
                    drawCircle(color = color, radius = p.radius, center = Offset(x, y))
                }
            }
        }
    }
}
