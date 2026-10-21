package com.dtyan.fitdiary.ui.templates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.dtyan.fitdiary.export.PlanBundlePreview
import com.dtyan.fitdiary.domain.ExerciseGuides
import com.dtyan.fitdiary.ui.common.ExerciseGuideThumb
import com.dtyan.fitdiary.ui.common.FitButton
import com.dtyan.fitdiary.ui.common.FitButtonKind
import com.dtyan.fitdiary.ui.common.IconBadge
import com.dtyan.fitdiary.ui.common.sectionColors
import java.time.LocalDate

@Composable
internal fun PlanContents(plan: PlanBundlePreview, preview: Boolean = false) {
    var expanded by rememberSaveable(plan.key) { mutableStateOf(preview) }
    val days = listOf("Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота", "Воскресенье")
    val today = plan.week.first { it.dayOfWeek == LocalDate.now().dayOfWeek.value }
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
            val colors = sectionColors()
            Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = colors.container, contentColor = colors.onContainer) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Сегодня · ${days[today.dayOfWeek - 1]}", style = MaterialTheme.typography.titleSmall)
                    Text("Утро: ${today.morning}")
                    Text("Силовая: ${today.strength}")
                    Text("Дома: ${today.home}")
                }
            }
        }
        FitButton(if (expanded) "Свернуть план" else "Вся неделя и рекомендации", { expanded = !expanded },
            kind = FitButtonKind.Plain, compact = true, icon = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore)
        if (expanded) {
            Text(plan.overview)
            plan.week.forEach { day ->
                Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(days[day.dayOfWeek - 1], style = MaterialTheme.typography.titleMedium)
                        Text("Утро: ${day.morning}")
                        Text("Силовая: ${day.strength}")
                        Text("Дома: ${day.home}")
                    }
                }
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
}
