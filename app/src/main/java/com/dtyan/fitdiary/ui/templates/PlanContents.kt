package com.dtyan.fitdiary.ui.templates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Today
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dtyan.fitdiary.export.PlanBundlePreview
import com.dtyan.fitdiary.export.PlanDay
import com.dtyan.fitdiary.domain.ExerciseGuides
import com.dtyan.fitdiary.ui.common.ExerciseGuideThumb
import com.dtyan.fitdiary.ui.common.FitButton
import com.dtyan.fitdiary.ui.common.FitButtonKind
import com.dtyan.fitdiary.ui.common.FitButtonRow
import com.dtyan.fitdiary.ui.common.FitCard
import com.dtyan.fitdiary.ui.common.IconBadge
import com.dtyan.fitdiary.ui.common.MenuAction
import com.dtyan.fitdiary.ui.common.OverflowMenu
import com.dtyan.fitdiary.ui.common.StatusPill
import com.dtyan.fitdiary.ui.common.sectionColors
import java.time.LocalDate

private val WEEKDAYS = listOf("Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота", "Воскресенье")

/**
 * A weekly plan. Outside the import preview, [onSwapDays] (weekdays 1..7) and [onShiftWeek] (±1 day)
 * let the person move days around; the preview only shows what the file contains.
 */
@Composable
internal fun PlanContents(
    plan: PlanBundlePreview,
    preview: Boolean = false,
    enabled: Boolean = true,
    onSwapDays: ((first: Int, second: Int) -> Unit)? = null,
    onShiftWeek: ((days: Int) -> Unit)? = null,
) {
    var expanded by rememberSaveable(plan.key) { mutableStateOf(preview) }
    // The weekday whose partner is being chosen in the swap dialog.
    var swapAnchor by rememberSaveable(plan.key) { mutableStateOf<Int?>(null) }
    // True when opened from the «Сегодня» block: the question is which day to do today.
    var swapForToday by rememberSaveable(plan.key) { mutableStateOf(false) }
    val todayNumber = LocalDate.now().dayOfWeek.value
    val today = plan.week.first { it.dayOfWeek == todayNumber }
    val swap = onSwapDays.takeUnless { preview }
    val shift = onShiftWeek.takeUnless { preview }
    val colors = sectionColors()
    val links = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(Icons.Rounded.CalendarMonth)
            Column(Modifier.weight(1f)) {
                Text(plan.name, style = MaterialTheme.typography.titleMedium)
                Text("${plan.templates.size} силовых программ · ${plan.activities.size} видов активности",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!preview) {
            FitCard(shape = MaterialTheme.shapes.medium, color = colors.container, contentColor = colors.onContainer,
                contentPadding = PaddingValues(12.dp), spacing = 2.dp) {
                Text("Сегодня · ${WEEKDAYS[today.dayOfWeek - 1]}", style = MaterialTheme.typography.titleSmall)
                DayLines(today)
                if (swap != null) FitButton("Другой день на сегодня", { swapForToday = true; swapAnchor = todayNumber },
                    kind = FitButtonKind.Plain, compact = true, icon = Icons.Rounded.SwapVert, enabled = enabled)
            }
        }
        FitButton(if (expanded) "Свернуть план" else "Вся неделя и рекомендации", { expanded = !expanded },
            kind = FitButtonKind.Plain, compact = true, icon = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore)
        if (expanded) {
            Text(plan.overview)
            plan.week.forEach { day ->
                val isToday = day.dayOfWeek == todayNumber
                val name = WEEKDAYS[day.dayOfWeek - 1]
                FitCard(
                    shape = MaterialTheme.shapes.medium, contentPadding = PaddingValues(12.dp), spacing = 4.dp,
                    color = if (isToday) colors.container else MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = if (isToday) colors.onContainer else MaterialTheme.colorScheme.onSurface,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (isToday) StatusPill("Сегодня", icon = Icons.Rounded.Today,
                            container = colors.accent, contentColor = colors.onAccent)
                        if (swap != null) OverflowMenu(buildList {
                            if (!isToday) add(MenuAction("Сделать сегодняшним", Icons.Rounded.Today) { swap(day.dayOfWeek, todayNumber) })
                            add(MenuAction("Поменять с…", Icons.Rounded.SwapVert) { swapForToday = false; swapAnchor = day.dayOfWeek })
                        }, contentDescription = "Перенести день: $name", enabled = enabled)
                    }
                    DayLines(day)
                }
            }
            if (shift != null) {
                FitButtonRow(
                    first = { m -> FitButton("На день раньше", { shift(-1) }, m, kind = FitButtonKind.Outlined,
                        icon = Icons.Rounded.ArrowUpward, enabled = enabled, compact = true) },
                    second = { m -> FitButton("На день позже", { shift(1) }, m, kind = FitButtonKind.Outlined,
                        icon = Icons.Rounded.ArrowDownward, enabled = enabled, compact = true) },
                )
                Text("Сдвигает весь план, порядок дней сохраняется",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (preview) {
                plan.templates.forEach { template ->
                    Text(template.name, style = MaterialTheme.typography.titleMedium)
                    template.exercises.forEach { exercise ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            ExerciseGuideThumb(ExerciseGuides.resolve(exercise.name, exercise.equipment), displayName = exercise.name)
                            Text("${exercise.name}: ${exercise.targetSets} подхода" +
                                (exercise.targetReps?.let { " · $it повторов" } ?: ""), modifier = Modifier.weight(1f))
                        }
                        exercise.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                plan.activities.forEach { activity ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        ExerciseGuideThumb(ExerciseGuides.forActivity(activity.key, activity.name), displayName = activity.name)
                        Text(activity.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    }
                    val units = when (activity.goalMetric) {
                        "DURATION_SECONDS" -> "сек"; "DISTANCE_KM" -> "км"; "STEPS" -> "шагов"; else -> "повторов"
                    }
                    Text(if (activity.goals.isEmpty()) "Без заданных целей" else
                        "Рубежи: ${activity.goals.joinToString(" → ") { if (it % 1 == 0.0) it.toLong().toString() else it.toString() }} $units")
                    Text(activity.instructions, style = MaterialTheme.typography.bodySmall)
                    activity.weeklyScheduleNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            plan.sources.forEach { source ->
                FitButton(source.title, { runCatching { links.openUri(source.url) } }, kind = FitButtonKind.Plain, compact = true)
            }
        }
    }
    if (swap != null) swapAnchor?.let { anchor ->
        SwapDayDialog(plan, anchor, todayNumber, forToday = swapForToday,
            onPick = { other -> swapAnchor = null; swap(anchor, other) },
            onDismiss = { swapAnchor = null })
    }
}

@Composable
private fun DayLines(day: PlanDay) {
    Text("Утро: ${day.morning}")
    Text("Силовая: ${day.strength}")
    Text("Дома: ${day.home}")
}

/** Picks the weekday to exchange with [anchor]; each choice shows its strength line so the swap is visible. */
@Composable
private fun SwapDayDialog(plan: PlanBundlePreview, anchor: Int, today: Int, forToday: Boolean,
    onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val colors = sectionColors()
    val anchorDay = plan.week.first { it.dayOfWeek == anchor }
    val anchorName = WEEKDAYS[anchor - 1]
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (forToday) "Какой день сделать сегодняшним?" else "$anchorName: поменять с…") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (forToday) "Выбранный день станет сегодняшним, а сегодняшний встанет на его место."
                    else "Дни поменяются местами целиком: утро, силовая и домашние упражнения.",
                    style = MaterialTheme.typography.bodyMedium)
                Text((if (anchor == today) "Сегодня · " else "") + "$anchorName · Силовая: ${anchorDay.strength}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                plan.week.filter { it.dayOfWeek != anchor }.forEach { day ->
                    val name = WEEKDAYS[day.dayOfWeek - 1]
                    FitCard(onClick = { onPick(day.dayOfWeek) }, onClickLabel = "Поменять с днём: $name",
                        shape = MaterialTheme.shapes.medium, contentPadding = PaddingValues(12.dp), spacing = 2.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            if (day.dayOfWeek == today) StatusPill("Сегодня", container = colors.container, contentColor = colors.onContainer)
                        }
                        Text("Силовая: ${day.strength}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = { FitButton("Отмена", onDismiss, kind = FitButtonKind.Plain, compact = true) },
    )
}
