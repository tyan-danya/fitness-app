@file:OptIn(ExperimentalLayoutApi::class)

package com.dtyan.fitdiary.ui.workout

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbDownOffAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dtyan.fitdiary.appContainer
import com.dtyan.fitdiary.data.db.Workout
import com.dtyan.fitdiary.domain.ExerciseGuides
import com.dtyan.fitdiary.ui.common.DetailTopBar
import com.dtyan.fitdiary.ui.common.DislikedPill
import com.dtyan.fitdiary.ui.common.ExerciseGuideThumb
import com.dtyan.fitdiary.ui.common.FitButton
import com.dtyan.fitdiary.ui.common.FitButtonKind
import com.dtyan.fitdiary.ui.common.FitButtonRow
import com.dtyan.fitdiary.ui.common.FitCard
import com.dtyan.fitdiary.ui.common.FitDimens
import com.dtyan.fitdiary.ui.common.FitSection
import com.dtyan.fitdiary.ui.common.FitSectionScope
import com.dtyan.fitdiary.ui.common.Format
import com.dtyan.fitdiary.ui.common.MenuAction
import com.dtyan.fitdiary.ui.common.OverflowMenu
import com.dtyan.fitdiary.ui.common.StatusPill
import com.dtyan.fitdiary.ui.common.sectionColors

@Composable
fun ActiveWorkoutScreen(
    workoutId: Long,
    onAddExercise: (Long) -> Unit,
    onOpenExercise: (Long, Long) -> Unit,
    onWorkoutClosed: () -> Unit,
    onSaveTemplate: ((Long) -> Unit)? = null,
    /** Подобрать замену: (тренировка участника, упражнение). */
    onReplaceExercise: ((Long, Long) -> Unit)? = null,
) = FitSectionScope(FitSection.WORKOUT) {
    val container = LocalContext.current.appContainer
    var selectedId by rememberSaveable(workoutId) { mutableStateOf(workoutId) }
    val groupFlow = remember(selectedId) { container.workoutRepository.observeGroupWorkouts(selectedId) }
    val group by groupFlow.collectAsStateWithLifecycle(emptyList())
    val athletes by container.profiles.profiles.collectAsStateWithLifecycle(emptyList())
    val selected = group.firstOrNull { it.id == selectedId && it.endedAt == null }
        ?: group.firstOrNull { it.endedAt == null }
    if (selected == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (group.isEmpty()) "Загружаем тренировку…" else "Тренировка завершена", style = MaterialTheme.typography.titleMedium)
            FitButton("На главную", onWorkoutClosed, kind = FitButtonKind.Outlined)
        }
        return@FitSectionScope
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
        onReplace = onReplaceExercise?.let { replace -> { exerciseId: Long -> replace(selected.id, exerciseId) } },
    )
}

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
    onReplace: ((Long) -> Unit)?,
) {
    val container = LocalContext.current.appContainer
    val vm: ActiveWorkoutViewModel = viewModel(key = "active_workout_${selected.id}") {
        ActiveWorkoutViewModel(selected.id, container.workoutRepository, container.exerciseRepository)
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
    val done = planned.count { it.completed }
    val skipped = planned.count { it.skipped }
    val next = planned.firstOrNull { !it.completed && !it.skipped }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            DetailTopBar(
                title = "Тренировка",
                subtitle = "$name · начало в ${Format.time(selected.startedAt)}",
                onBack = onBack,
                backLabel = "На главную",
                actions = {
                    OverflowMenu(buildList {
                        if (onSaveTemplate != null && groups.isNotEmpty()) add(MenuAction("Сохранить как программу", Icons.Rounded.Bookmarks, onClick = onSaveTemplate))
                        add(MenuAction("Удалить тренировку: $name", Icons.Rounded.Delete, destructive = true) { showDelete = true })
                    }, enabled = !busy)
                },
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                FitButtonRow(Modifier.padding(FitDimens.ScreenPadding), minWidthForRow = 340.dp,
                    first = { m -> FitButton("Добавить упражнение", onAddExercise, m, kind = FitButtonKind.Tonal, icon = Icons.Rounded.Add, enabled = !busy) },
                    second = { m ->
                        FitButton(if (busy) "Сохраняем…" else "Завершить тренировку", {
                            weights = emptyMap()
                            finishAll = active.size > 1
                            showFinish = true
                        }, m, enabled = !busy)
                    })
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = FitDimens.ScreenPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(FitDimens.ItemGap)) {
            if (participants.size > 1) item("participants") { ParticipantChips(participants, names, selected.id, !busy, onSelect) }
            item("summary") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill("${sets.size} " + Format.plural(sets.size, "подход", "подхода", "подходов"))
                    StatusPill("Тоннаж ${Format.volume(sets.sumOf { it.weightKg * it.reps })}")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            }
            if (planned.isNotEmpty()) item("plan_progress") {
                val colors = sectionColors()
                FitCard(color = colors.container, contentColor = colors.onContainer) {
                    Text("План: $done из ${planned.size}" + if (skipped > 0) " · пропущено: $skipped" else "",
                        style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(
                        progress = { (done + skipped).toFloat() / planned.size },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(MaterialTheme.shapes.small),
                        color = colors.accent, trackColor = MaterialTheme.colorScheme.surface, gapSize = 0.dp, drawStopIndicator = {},
                    )
                    if (next != null) {
                        FitButton("Следующее: ${next.name}", { onOpenExercise(next.exerciseId) }, Modifier.fillMaxWidth(),
                            icon = Icons.AutoMirrored.Rounded.ArrowForward, enabled = !busy)
                        // Не делаете его сегодня — пропуск убирает его из «Следующего» только в этой тренировке.
                        FitButton("Пропустить в этой тренировке", { vm.skip(next.exerciseId, true) }, kind = FitButtonKind.Plain,
                            compact = true, icon = Icons.Rounded.SkipNext, enabled = !busy)
                    }
                    else Text("Все упражнения отмечены. Можно завершать тренировку.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (groups.isEmpty()) item("empty") {
                Text("Добавьте первое упражнение. Каждый участник записывает свои веса и повторы.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp))
            }
            items(groups, key = { it.exerciseId }) { group ->
                ExerciseGroupCard(group, !busy,
                    onToggleComplete = { vm.markComplete(group.exerciseId, !group.completed) },
                    onClick = { if (!busy) onOpenExercise(group.exerciseId) },
                    onSkip = { vm.skip(group.exerciseId, !group.skipped) },
                    onToggleDislike = { vm.setDisliked(group.exerciseId, !group.disliked) },
                    onReplace = onReplace?.let { replace -> { replace(group.exerciseId) } })
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
                        Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                            .clickable(enabled = !busy, role = Role.Checkbox) { finishAll = !finishAll },
                            verticalAlignment = Alignment.CenterVertically) {
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
            confirmButton = { TextButton(enabled = valid && !busy, onClick = {
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

/** «Цель: 3 подхода по 10» — ориентир программы человеческими словами. */
internal fun planTarget(sets: Int?, reps: Int?): String? = when {
    sets != null && reps != null -> "Цель: $sets ${Format.plural(sets, "подход", "подхода", "подходов")} по $reps"
    sets != null -> "Цель: $sets ${Format.plural(sets, "подход", "подхода", "подходов")}"
    reps != null -> "Цель: $reps ${Format.plural(reps, "повтор", "повтора", "повторов")}"
    else -> null
}

@Composable
private fun ExerciseGroupCard(
    group: ExerciseGroupUi,
    enabled: Boolean,
    onToggleComplete: () -> Unit,
    onClick: () -> Unit,
    onSkip: () -> Unit,
    onToggleDislike: () -> Unit,
    onReplace: (() -> Unit)?,
) {
    val colors = sectionColors()
    FitCard(onClick = onClick, onClickLabel = "Открыть упражнение", shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 4.dp, bottom = 12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ExerciseGuideThumb(ExerciseGuides.resolve(group.name), group.photoPath, size = 56.dp, displayName = group.name)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(group.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (group.disliked) DislikedPill()
                planTarget(group.targetSets, group.targetReps)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                group.note?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(if (group.sets.isEmpty()) "Подходов пока нет" else
                    group.sets.joinToString(" · ") { "${Format.weight(it.weightKg)} × ${it.reps}" },
                    style = MaterialTheme.typography.bodyMedium)
            }
            OverflowMenu(buildList {
                if (onReplace != null && group.planned && !group.completed) add(MenuAction("Заменить упражнение", Icons.Rounded.SwapHoriz, onClick = onReplace))
                if (group.planned && !group.completed) add(MenuAction(if (group.skipped) "Вернуть в план" else "Пропустить в этой тренировке",
                    Icons.Rounded.SkipNext, onClick = onSkip))
                add(MenuAction(if (group.disliked) "Убрать «Не нравится»" else "Не нравится",
                    if (group.disliked) Icons.Rounded.ThumbDownOffAlt else Icons.Rounded.ThumbDown, onClick = onToggleDislike))
            }, contentDescription = "Действия: ${group.name}", enabled = enabled)
        }
        if (group.planned && group.skipped) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StatusPill("Пропущено", Modifier.align(Alignment.CenterVertically), icon = Icons.Rounded.SkipNext,
                container = colors.container, contentColor = colors.onContainer)
            FitButton("Вернуть", onSkip, kind = FitButtonKind.Plain, compact = true, enabled = enabled)
        }
        // Одна отметка вместо статуса и кнопки: нажатие ставит или снимает «Выполнено».
        else if (group.planned) FilterChip(
            selected = group.completed,
            onClick = onToggleComplete,
            enabled = enabled && (group.completed || group.sets.isNotEmpty()),
            label = { Text(if (group.completed) "Выполнено" else if (group.sets.isEmpty()) "По плану · запишите подход" else "Отметить выполненным") },
            leadingIcon = {
                Icon(if (group.completed) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = null, modifier = Modifier.size(18.dp))
            },
            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = colors.container,
                selectedLabelColor = colors.onContainer, selectedLeadingIconColor = colors.accent),
        )
    }
}
