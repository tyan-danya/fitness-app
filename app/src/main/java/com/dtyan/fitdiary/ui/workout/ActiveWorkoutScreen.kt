package com.dtyan.fitdiary.ui.workout

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dtyan.fitdiary.appContainer
import com.dtyan.fitdiary.data.db.Workout
import com.dtyan.fitdiary.ui.common.ExercisePhotoThumb
import com.dtyan.fitdiary.ui.common.Format
import com.dtyan.fitdiary.ui.common.muscleGroupEmoji

@Composable
fun ActiveWorkoutScreen(
    workoutId: Long,
    onAddExercise: (Long) -> Unit,
    onOpenExercise: (Long, Long) -> Unit,
    onWorkoutClosed: () -> Unit,
    onSaveTemplate: ((Long) -> Unit)? = null,
) {
    val container = LocalContext.current.appContainer
    var selectedId by rememberSaveable(workoutId) { mutableStateOf(workoutId) }
    val groupFlow = remember(selectedId) { container.workoutRepository.observeGroupWorkouts(selectedId) }
    val group by groupFlow.collectAsStateWithLifecycle(emptyList())
    val athletes by container.profiles.profiles.collectAsStateWithLifecycle(emptyList())
    val selected = group.firstOrNull { it.id == selectedId && it.endedAt == null }
        ?: group.firstOrNull { it.endedAt == null }
    if (selected == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (group.isEmpty()) "Загружаем тренировку…" else "Тренировка завершена")
            TextButton(onClick = onWorkoutClosed) { Text("На главную") }
        }
        return
    }
    ActiveAthleteWorkout(
        selected, group, athletes.associate { it.id to it.name },
        onSelect = { selectedId = it },
        onAddExercise = { onAddExercise(selected.id) },
        onOpenExercise = { onOpenExercise(selected.id, it) },
        onBack = onWorkoutClosed,
        onIndividualClosed = {
            val next = group.firstOrNull { it.id != selected.id && it.endedAt == null }
            if (next != null) selectedId = next.id else onWorkoutClosed()
        },
        onGroupClosed = onWorkoutClosed,
        onSaveTemplate = onSaveTemplate?.let { { it(selected.id) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActiveAthleteWorkout(
    selected: Workout,
    participants: List<Workout>,
    names: Map<Long, String>,
    onSelect: (Long) -> Unit,
    onAddExercise: () -> Unit,
    onOpenExercise: (Long) -> Unit,
    onBack: () -> Unit,
    onIndividualClosed: () -> Unit,
    onGroupClosed: () -> Unit,
    onSaveTemplate: (() -> Unit)?,
) {
    val container = LocalContext.current.appContainer
    val vm: ActiveWorkoutViewModel = viewModel(key = "active_workout_${selected.id}") {
        ActiveWorkoutViewModel(selected.id, container.workoutRepository)
    }
    val groups by vm.exerciseGroups.collectAsStateWithLifecycle()
    val sets by vm.sets.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val name = names[selected.athleteId] ?: "Участник"
    var showDelete by remember(selected.id) { mutableStateOf(false) }
    var showFinish by rememberSaveable(selected.id) { mutableStateOf(false) }
    var finishAll by rememberSaveable(selected.id) { mutableStateOf(false) }
    var weights by rememberSaveable(selected.id) { mutableStateOf<Map<Long, String>>(emptyMap()) }
    val active = participants.filter { it.endedAt == null }
    val planned = groups.filter { it.planned }
    val next = planned.firstOrNull { !it.completed }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text("Тренировка · $name") },
                navigationIcon = { IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "На главную")
                } },
                actions = { IconButton(onClick = { showDelete = true }, enabled = !busy) {
                    Icon(Icons.Filled.Delete, "Удалить тренировку: $name")
                } },
            )
        },
        bottomBar = {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onAddExercise, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.Add, null); Spacer(Modifier.width(8.dp)); Text("Добавить упражнение")
                }
                Button(onClick = {
                    weights = emptyMap()
                    finishAll = active.size > 1
                    showFinish = true
                }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(if (busy) "Сохраняем…" else "Завершить тренировку")
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("participants") { ParticipantChips(participants, names, selected.id, !busy, onSelect) }
            item("summary") {
                Text("${sets.size} подходов · ${Format.volume(sets.sumOf { it.weightKg * it.reps })} тоннаж",
                    style = MaterialTheme.typography.bodyMedium)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            if (planned.isNotEmpty()) item("plan_progress") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("План: ${planned.count { it.completed }} из ${planned.size} упражнений выполнено",
                        style = MaterialTheme.typography.titleMedium)
                    if (next != null) Button(onClick = { onOpenExercise(next.exerciseId) },
                        enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Открыть следующее: ${next.name}")
                    } else Text("Все упражнения отмечены. Можно завершить тренировку.")
                }
            }
            if (groups.isEmpty()) item("empty") {
                Text("Добавьте первое упражнение. Каждый участник записывает свои веса и повторы.",
                    modifier = Modifier.padding(vertical = 24.dp))
            }
            items(groups, key = { it.exerciseId }) { group ->
                ExerciseGroupCard(group, !busy,
                    onToggleComplete = { vm.markComplete(group.exerciseId, !group.completed) },
                    onClick = { if (!busy) onOpenExercise(group.exerciseId) })
            }
            if (onSaveTemplate != null && groups.isNotEmpty()) item("save_template") {
                OutlinedButton(onClick = onSaveTemplate, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Сохранить как программу")
                }
            }
        }
    }
    if (showDelete) AlertDialog(
        onDismissRequest = { if (!busy) showDelete = false },
        title = { Text("Удалить тренировку: $name?") },
        text = { Text("Эта тренировка, её подходы и связанное взвешивание будут удалены. Тренировки друзей сохранятся.") },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            showDelete = false; vm.cancelWorkout(onIndividualClosed)
        }) { Text("Удалить", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Отмена") } },
    )
    if (showFinish) {
        val finishing = if (finishAll) active else listOf(selected)
        val valid = finishing.all { participant ->
            val text = weights[participant.athleteId].orEmpty()
            val value = text.replace(',', '.').toDoubleOrNull()
            text.isBlank() || (value != null && value.isFinite() && value > 0 && value < 500)
        }
        AlertDialog(
            onDismissRequest = { if (!busy) showFinish = false },
            title = { Text("Завершение тренировки") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (active.size > 1) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = finishAll, onCheckedChange = { finishAll = it }, enabled = !busy)
                            Text("Завершить для всех (${active.size})")
                        }
                        if (!finishAll) Text("Завершаем только: $name")
                    }
                    Text("Вес тела — только если взвесились сегодня. Можно оставить пустым.")
                    finishing.forEach { participant ->
                        val text = weights[participant.athleteId].orEmpty()
                        val parsed = text.replace(',', '.').toDoubleOrNull()
                        OutlinedTextField(
                            value = text,
                            onValueChange = { value -> weights = weights + (participant.athleteId to value) },
                            label = { Text("${names[participant.athleteId] ?: "Участник"} · вес, кг") },
                            isError = text.isNotBlank() && (parsed == null || !parsed.isFinite() || parsed <= 0 || parsed >= 500),
                            supportingText = { Text("Необязательно · больше 0 и меньше 500 кг") },
                            singleLine = true, enabled = !busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (sets.isEmpty()) Text("У $name нет записанных подходов.", style = MaterialTheme.typography.bodySmall)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { Button(enabled = valid && !busy, onClick = {
                val bodyWeights = finishing.mapNotNull { participant ->
                    weights[participant.athleteId]?.replace(',', '.')?.toDoubleOrNull()?.let { participant.athleteId to it }
                }.toMap()
                if (finishAll) vm.finishGroup(bodyWeights) { showFinish = false; onGroupClosed() }
                else vm.finishWorkout(bodyWeights[selected.athleteId]) { showFinish = false; onIndividualClosed() }
            }) { Text(if (busy) "Сохраняем…" else if (finishAll) "Завершить всех" else "Завершить") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { showFinish = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun ExerciseGroupCard(group: ExerciseGroupUi, enabled: Boolean, onToggleComplete: () -> Unit, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Открыть упражнение", onClick = onClick).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ExercisePhotoThumb(photoPath = group.photoPath, size = 44.dp,
                fallback = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(muscleGroupEmoji(group.muscleGroup)) } })
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(group.name, style = MaterialTheme.typography.titleMedium)
                if (group.planned) Text(if (group.completed) "✓ Выполнено" else "○ По плану",
                    style = MaterialTheme.typography.labelLarge)
                if (group.targetSets != null || group.targetReps != null) Text(
                    listOfNotNull(group.targetSets?.let { "Подходы: $it" }, group.targetReps?.let { "Повторы: $it" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall)
                group.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text(if (group.sets.isEmpty()) "По плану · подходов пока нет" else
                    group.sets.joinToString(" · ") { "${Format.weight(it.weightKg)} × ${it.reps}" },
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (group.planned) TextButton(onClick = onToggleComplete, enabled = enabled && (group.completed || group.sets.isNotEmpty())) {
                    Text(if (group.completed) "Вернуть в план" else "Отметить выполненным")
                }
            }
        }
    }
}
