package com.dtyan.fitdiary.ui.templates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.dtyan.fitdiary.export.PlanBundlePreview
import java.time.LocalDate

@Composable
internal fun PlanContents(plan: PlanBundlePreview, preview: Boolean = false) {
    var expanded by rememberSaveable(plan.key) { mutableStateOf(preview) }
    val days = listOf("Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота", "Воскресенье")
    val today = plan.week.first { it.dayOfWeek == LocalDate.now().dayOfWeek.value }
    val links = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(plan.name, style = MaterialTheme.typography.titleLarge)
        Text("${plan.templates.size} силовых программ · ${plan.activities.size} видов активности",
            style = MaterialTheme.typography.bodySmall)
        if (!preview) {
            Text("Сегодня · ${days[today.dayOfWeek - 1]}", style = MaterialTheme.typography.titleMedium)
            Text("Утро: ${today.morning}")
            Text("Силовая: ${today.strength}")
            Text("Дома: ${today.home}")
        }
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Свернуть план" else "Вся неделя и рекомендации") }
        if (expanded) {
            Text(plan.overview)
            plan.week.forEach { day ->
                Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
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
                        Text("${exercise.name}: ${exercise.targetSets} подхода" +
                            (exercise.targetReps?.let { " · $it повторов" } ?: ""))
                        exercise.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                plan.activities.forEach { activity ->
                    Text(activity.name, style = MaterialTheme.typography.titleMedium)
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
                TextButton(onClick = { runCatching { links.openUri(source.url) } }) { Text(source.title) }
            }
        }
    }
}
