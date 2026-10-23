package com.dtyan.fitdiary.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbDownOffAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dtyan.fitdiary.ui.theme.fitAccents

/** Единые размеры интерфейса: один набор отступов и высот на все экраны. */
object FitDimens {
    val ScreenPadding = 16.dp
    val CardPadding = 16.dp
    val ItemGap = 12.dp
    val ButtonHeight = 52.dp
    val CompactButtonHeight = 40.dp
    val TileHeight = 92.dp

    /** Нижний отступ списка, чтобы плавающая кнопка не закрывала последние карточки. */
    val FabClearance = 96.dp
}

/** Раздел приложения: задаёт акцент кнопок, плашек и индикаторов на всех его экранах. */
enum class FitSection { WORKOUT, NUTRITION, BODY, STATS }

@Immutable
data class SectionColors(val accent: Color, val onAccent: Color, val container: Color, val onContainer: Color)

val LocalFitSection = staticCompositionLocalOf { FitSection.WORKOUT }

@Composable
@ReadOnlyComposable
fun sectionColors(section: FitSection = LocalFitSection.current): SectionColors {
    val a = fitAccents
    return when (section) {
        FitSection.WORKOUT -> SectionColors(a.workout, a.onWorkout, a.workoutContainer, a.onWorkoutContainer)
        FitSection.NUTRITION -> SectionColors(a.nutrition, a.onNutrition, a.nutritionContainer, a.onNutritionContainer)
        FitSection.BODY -> SectionColors(a.measure, a.onMeasure, a.measureContainer, a.onMeasureContainer)
        FitSection.STATS -> SectionColors(a.stats, a.onStats, a.statsContainer, a.onStatsContainer)
    }
}

/** Всё внутри получает акцент раздела: кнопки, плашки и FAB перекрашиваются без параметров. */
@Composable
fun FitSectionScope(section: FitSection, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalFitSection provides section, content = content)
}

/**
 * Вес кнопки. Filled — главное действие экрана (одно), Tonal — частое второстепенное,
 * Outlined — альтернатива рядом с главным, Plain — тихое действие в тексте или карточке,
 * Danger — необратимое.
 */
enum class FitButtonKind { Filled, Tonal, Outlined, Plain, Danger }

/** Кнопка с единой высотой, формой и типографикой для всего приложения. */
@Composable
fun FitButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: FitButtonKind = FitButtonKind.Filled,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    compact: Boolean = false,
    section: FitSection = LocalFitSection.current,
) {
    val colors = sectionColors(section)
    val sized = modifier.heightIn(min = if (compact) FitDimens.CompactButtonHeight else FitDimens.ButtonHeight)
    val padding = PaddingValues(horizontal = if (compact) 14.dp else 20.dp, vertical = 8.dp)
    val active = enabled && !loading
    val content: @Composable RowScope.() -> Unit = {
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
            Spacer(Modifier.width(8.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    when (kind) {
        FitButtonKind.Filled -> Button(onClick, sized, active, contentPadding = padding,
            colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
            content = content)
        FitButtonKind.Tonal -> FilledTonalButton(onClick, sized, active, contentPadding = padding,
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = colors.container, contentColor = colors.onContainer),
            content = content)
        FitButtonKind.Outlined -> OutlinedButton(onClick, sized, active, contentPadding = padding,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.accent),
            border = BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant),
            content = content)
        FitButtonKind.Plain -> TextButton(onClick, sized, active, contentPadding = padding,
            colors = ButtonDefaults.textButtonColors(contentColor = colors.accent), content = content)
        FitButtonKind.Danger -> OutlinedButton(onClick, sized, active, contentPadding = padding,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = if (active) 0.6f else 0.3f)),
            content = content)
    }
}

/** Две равные кнопки рядом; на узком экране или при крупном шрифте — друг под другом. */
@Composable
fun FitButtonRow(
    modifier: Modifier = Modifier,
    minWidthForRow: Dp = 300.dp,
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val stacked = maxWidth < minWidthForRow || LocalDensity.current.fontScale > 1.15f
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                first(Modifier.fillMaxWidth())
                second(Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                first(Modifier.weight(1f))
                second(Modifier.weight(1f))
            }
        }
    }
}

/** Плавающая кнопка раздела — одна форма и цвет для «Добавить», «Записать», «Создать». */
@Composable
fun FitFab(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = sectionColors()
    ExtendedFloatingActionButton(
        onClick = onClick,
        modifier = modifier.pressScale(),
        containerColor = colors.accent,
        contentColor = colors.onAccent,
        icon = { Icon(icon, contentDescription = null) },
        text = { Text(text, style = MaterialTheme.typography.labelLarge) },
    )
}

/** Карточка: одна поверхность, скругление и внутренний отступ на всех экранах. */
@Composable
fun FitCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    shape: Shape = MaterialTheme.shapes.large,
    contentPadding: PaddingValues = PaddingValues(FitDimens.CardPadding),
    spacing: Dp = 8.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val clickable = if (onClick != null) {
        Modifier.pressScale().clip(shape).clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
    } else Modifier
    Surface(modifier = modifier.fillMaxWidth().then(clickable), shape = shape, color = color, contentColor = contentColor) {
        Column(Modifier.padding(contentPadding), verticalArrangement = Arrangement.spacedBy(spacing), content = content)
    }
}

/** Иконка в скруглённой плашке цвета раздела — общий «аватар» карточек, плиток и строк. */
@Composable
fun IconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    tint: Color = sectionColors().accent,
    container: Color = sectionColors().container,
) {
    Surface(modifier.size(size), shape = RoundedCornerShape(size * 0.3f), color = container) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.55f))
        }
    }
}

/**
 * Плитка быстрого действия: иконка, название и короткая подпись.
 * Плитки в одном ряду всегда одного размера — вместо разнокалиберных кнопок.
 */
@Composable
fun ActionTile(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    section: FitSection = LocalFitSection.current,
) {
    val colors = sectionColors(section)
    val shape = MaterialTheme.shapes.medium
    Surface(
        modifier = modifier
            .heightIn(min = FitDimens.TileHeight)
            .pressScale()
            .clip(shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            IconBadge(icon, size = 36.dp, tint = colors.accent, container = colors.container)
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Плитки по [columns] в ряд одинаковой высоты; неполный последний ряд растягивается на всю ширину. */
@Composable
fun TileGrid(tiles: List<@Composable (Modifier) -> Unit>, modifier: Modifier = Modifier, columns: Int = 2) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { tile -> tile(Modifier.weight(1f).fillMaxHeight()) }
            }
        }
    }
}

/** Строка-переход: иконка, заголовок, подпись и стрелка. Для настроек, меню профиля и списков. */
@Composable
fun NavRow(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconBadge(icon, size = 36.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing != null) trailing()
        else Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Плашка статуса: «Выполнено», «По плану», «своё», «ждёт расчёта». */
@Composable
fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Surface(modifier, shape = CircleShape, color = container, contentColor = contentColor) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

/** Звёздочка «избранное»: золотая, когда отмечено. Подпись говорит, что произойдёт по нажатию. */
@Composable
fun FavoriteToggle(favorite: Boolean, onToggle: () -> Unit, name: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    IconToggleButton(checked = favorite, onCheckedChange = { onToggle() }, enabled = enabled, modifier = modifier) {
        Icon(
            imageVector = if (favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
            contentDescription = if (favorite) "Убрать из избранного: $name" else "В избранное: $name",
            tint = if (favorite) fitAccents.gold else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** «Не нравится»: палец вниз, закрашенный, когда отмечено. Пара к звёздочке избранного. */
@Composable
fun DislikeToggle(disliked: Boolean, onToggle: () -> Unit, name: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    IconToggleButton(checked = disliked, onCheckedChange = { onToggle() }, enabled = enabled, modifier = modifier) {
        Icon(
            imageVector = if (disliked) Icons.Rounded.ThumbDown else Icons.Rounded.ThumbDownOffAlt,
            contentDescription = if (disliked) "Убрать «Не нравится»: $name" else "Не нравится: $name",
            tint = if (disliked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Метка «Не нравится» в списках: в каталоге, программах и плане тренировки. */
@Composable
fun DislikedPill(modifier: Modifier = Modifier) {
    StatusPill("Не нравится", modifier, icon = Icons.Rounded.ThumbDown,
        container = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
}

/** Пункт меню «Ещё». */
data class MenuAction(val label: String, val icon: ImageVector? = null, val destructive: Boolean = false,
    val enabled: Boolean = true, val onClick: () -> Unit)

/** Кнопка «⋮» с меню второстепенных действий — вместо ряда мелких текстовых кнопок. */
@Composable
fun OverflowMenu(actions: List<MenuAction>, contentDescription: String = "Ещё", enabled: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled) {
            Icon(Icons.Rounded.MoreVert, contentDescription = contentDescription)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.forEach { action ->
                val color = if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                DropdownMenuItem(
                    text = { Text(action.label, color = color) },
                    leadingIcon = action.icon?.let { { Icon(it, contentDescription = null, tint = color) } },
                    enabled = action.enabled,
                    onClick = { open = false; action.onClick() },
                )
            }
        }
    }
}

/** Шапка вложенного экрана: «назад», заголовок с подписью и действия. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailTopBar(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    backEnabled: Boolean = true,
    backLabel: String = "Назад",
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        windowInsets = WindowInsets(0, 0, 0, 0),
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        title = {
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack, enabled = backEnabled) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, backLabel) }
        },
        actions = actions,
    )
}

/** Заголовок блока внутри экрана со ссылкой-действием справа. */
@Composable
fun BlockTitle(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
        if (action != null) action()
    }
}

/** Переключатель дня: стрелки по бокам и дата-пилюля; нажатие на дату — возврат к сегодня. */
@Composable
fun DaySwitcher(
    label: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onLabelClick: () -> Unit,
    modifier: Modifier = Modifier,
    nextEnabled: Boolean = true,
    enabled: Boolean = true,
    labelClickDescription: String = "Вернуться к сегодняшнему дню",
) {
    val colors = sectionColors()
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            IconButton(onClick = onPrevious, enabled = enabled) { Icon(Icons.Rounded.ChevronLeft, "Предыдущий день") }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Surface(modifier = Modifier.pressScale(), shape = CircleShape, color = colors.container, contentColor = colors.onContainer) {
                Text(
                    text = label,
                    modifier = Modifier
                        .clickable(enabled = enabled, onClickLabel = labelClickDescription, onClick = onLabelClick)
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            IconButton(onClick = onNext, enabled = enabled && nextEnabled) { Icon(Icons.Rounded.ChevronRight, "Следующий день") }
        }
    }
}
