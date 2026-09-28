package com.dtyan.fitdiary.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dtyan.fitdiary.appContainer
import com.dtyan.fitdiary.data.db.SetWithExercise
import com.dtyan.fitdiary.ui.common.EmptyState
import com.dtyan.fitdiary.ui.common.Format
import com.dtyan.fitdiary.ui.common.IllustrationSearch
import com.dtyan.fitdiary.ui.common.muscleGroupEmoji
import com.dtyan.fitdiary.ui.theme.fitAccents
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutDetailsScreen(
    workoutId: Long,
    onBack: () -> Unit,
    onRepeatWorkout: ((Long) -> Unit)? = null,
    onSaveTemplate: ((Long) -> Unit)? = null,
) {
    val container = LocalContext.current.appContainer
    val vm: WorkoutDetailsViewModel = viewModel(key = "workout_details_$workoutId") {
        WorkoutDetailsViewModel(
            workoutId = workoutId,
            workoutRepository = container.workoutRepository,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    var showDelete by rememberSaveable(workoutId) { mutableStateOf(false) }
    val athletes by container.profiles.allProfiles.collectAsStateWithLifecycle(emptyList())

    val workout = state.workout
    val title = if (workout != null) {
        val date = Instant.ofEpochMilli(workout.startedAt)
            .atZone(ZoneId.systemDefault()).toLocalDate()
        "Тренировка · ${Format.dateShort(date)}"
    } else {
        "Тренировка"
    }

    Scaffold(
        // Отступы системных панелей уже применяет внешний Scaffold в AppRoot — иначе они удвоятся
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                windowInsets = WindowInsets(0.dp),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    if (workout != null) IconButton(onClick = { showDelete = true }, enabled = !state.busy) {
                        Icon(Icons.Filled.Delete, "Удалить тренировку")
                    }
                },
            )
        },
    ) { innerPadding ->
        when {
            state.loading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = fitAccents.stats) }
            }
            workout == null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        title = state.error ?: "Тренировка не найдена",
                        subtitle = null,
                        illustration = { IllustrationSearch(size = 96.dp) },
                    )
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "owner") {
                        Text(athletes.firstOrNull { it.id == workout.athleteId }?.name ?: "Участник",
                            style = MaterialTheme.typography.titleMedium)
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                    item(key = "summary") {
                        WorkoutHeaderCard(
                            startedAt = workout.startedAt,
                            endedAt = workout.endedAt,
                            setCount = state.groups.sumOf { it.sets.size },
                            totalVolumeKg = state.totalVolumeKg,
                            bodyWeightKg = workout.bodyWeightKg,
                        )
                    }
                    items(state.groups, key = { it.exerciseId }) { group ->
                        ExerciseGroupCard(group)
                    }
                    if (onSaveTemplate != null && state.groups.isNotEmpty()) item(key = "save_template") {
                        OutlinedButton(onClick = { onSaveTemplate(workoutId) }, enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth()) { Text("Сохранить как программу") }
                    }
                    if (onRepeatWorkout != null && state.groups.isNotEmpty()) item(key = "repeat") {
                        OutlinedButton(onClick = { vm.repeatWorkout(onRepeatWorkout) },
                            enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                            Text(if (state.busy) "Готовим…" else "Повторить список упражнений")
                        }
                        Text("Новая тренировка с теми же упражнениями. Подходы и веса не копируются.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    if (showDelete) AlertDialog(
        onDismissRequest = { if (!state.busy) showDelete = false },
        title = { Text("Удалить эту тренировку?") },
        text = { Column {
            Text("Тренировка, её подходы и связанное взвешивание будут удалены. Тренировки друзей сохранятся.")
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = !state.busy, onClick = {
            vm.deleteWorkout { showDelete = false; onBack() }
        }) { Text(if (state.busy) "Удаляем…" else "Удалить", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(enabled = !state.busy, onClick = { showDelete = false }) { Text("Отмена") } },
    )
}

/** Шапка-сводка на statsContainer: длительность, подходы, тоннаж и контрольный вес. */
@Composable
private fun WorkoutHeaderCard(
    startedAt: Long,
    endedAt: Long?,
    setCount: Int,
    totalVolumeKg: Double,
    bodyWeightKg: Double?,
) {
    val accents = fitAccents
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = accents.statsContainer,
        contentColor = accents.onStatsContainer,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                HeaderStat(
                    value = endedAt?.let { Format.durationHuman(it - startedAt) } ?: "—",
                    label = "длительность",
                    modifier = Modifier.weight(1.3f),
                )
                HeaderStat(
                    value = setCount.toString(),
                    label = plural(setCount, "подход", "подхода", "подходов"),
                    modifier = Modifier.weight(0.85f),
                )
                HeaderStat(
                    value = Format.volume(totalVolumeKg),
                    label = "тоннаж",
                    modifier = Modifier.weight(1.05f),
                )
            }
            if (bodyWeightKg != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.MonitorWeight,
                        contentDescription = null,
                        tint = accents.onStatsContainer,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        "Вес после: ${Format.weight(bodyWeightKg)} кг",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
        }
    }
}

/** Колонка сводки: крупное значение и подпись под ним. */
@Composable
private fun HeaderStat(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = fitAccents.onStatsContainer.copy(alpha = 0.8f),
        )
    }
}

/** Секция упражнения: аватар группы мышц, название и подходы построчно. */
@Composable
private fun ExerciseGroupCard(group: ExerciseGroup) {
    val accents = fitAccents
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(shape = CircleShape, color = accents.statsContainer) {
                    Box(
                        modifier = Modifier.size(44.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = muscleGroupEmoji(group.muscleGroup),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(group.exerciseName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = group.muscleGroup,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Лучший подход упражнения — максимальный вес (при равенстве — больше повторов)
            val best = group.sets.maxWithOrNull(compareBy({ it.weightKg }, { it.reps }))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                group.sets.forEach { set ->
                    SetRow(set = set, isBest = best != null && set.id == best.id)
                }
            }
        }
    }
}

/** Строка подхода: «1 · 60 × 12», лучший — золотом и с иконкой кубка. */
@Composable
private fun SetRow(set: SetWithExercise, isBest: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "${set.setIndex} ·",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "${Format.weight(set.weightKg)} × ${set.reps}",
            style = MaterialTheme.typography.bodyLarge,
            color = if (isBest) fitAccents.gold else MaterialTheme.colorScheme.onSurface,
        )
        if (isBest) {
            Icon(
                imageVector = Icons.Filled.EmojiEvents,
                contentDescription = null,
                tint = fitAccents.gold,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
