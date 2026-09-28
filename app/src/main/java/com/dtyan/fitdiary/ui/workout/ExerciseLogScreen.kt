@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.dtyan.fitdiary.ui.workout

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dtyan.fitdiary.appContainer
import com.dtyan.fitdiary.data.db.WorkoutSet
import com.dtyan.fitdiary.data.db.Workout
import com.dtyan.fitdiary.ui.common.ConfettiBurst
import com.dtyan.fitdiary.ui.common.EmptyState
import com.dtyan.fitdiary.ui.common.ExercisePhotoThumb
import com.dtyan.fitdiary.ui.common.Format
import com.dtyan.fitdiary.ui.common.IllustrationDumbbell
import com.dtyan.fitdiary.ui.common.PhotoViewerDialog
import com.dtyan.fitdiary.ui.common.SectionHeader
import com.dtyan.fitdiary.ui.common.pressScale
import com.dtyan.fitdiary.ui.common.rememberPhotoPicker
import com.dtyan.fitdiary.ui.theme.fitAccents
import kotlinx.coroutines.launch

/** Сравнение сегодняшнего подхода с одноимённым (по setIndex) из прошлой тренировки. */
private data class SetTrend(val text: String)

private fun trendFor(today: WorkoutSet, previousSets: List<WorkoutSet>): SetTrend? {
    val previous = previousSets.firstOrNull { it.setIndex == today.setIndex } ?: return null
    val weightDelta = today.weightKg - previous.weightKg
    val repsDelta = today.reps - previous.reps
    if (weightDelta == 0.0 && repsDelta == 0) return SetTrend("Как в прошлый раз")
    val changes = buildList {
        if (weightDelta != 0.0) add("${if (weightDelta > 0) "+" else ""}${Format.weight(weightDelta)} кг")
        if (repsDelta != 0) add("${if (repsDelta > 0) "+" else ""}$repsDelta повт.")
    }
    return SetTrend(changes.joinToString(" · ") + " к прошлому разу")
}

@Composable
fun ExerciseLogScreen(
    workoutId: Long,
    exerciseId: Long,
    onBack: () -> Unit,
    onOpenExercise: ((Long, Long) -> Unit)? = null,
    onPlanComplete: ((Long) -> Unit)? = null,
) {
    val container = LocalContext.current.appContainer
    val groupFlow = remember(workoutId) { container.workoutRepository.observeGroupWorkouts(workoutId) }
    val group by groupFlow.collectAsStateWithLifecycle(emptyList())
    val athletes by container.profiles.profiles.collectAsStateWithLifecycle(emptyList())
    var selectedId by rememberSaveable(workoutId) { mutableStateOf(workoutId) }
    val available = group.filter { it.endedAt == null }
    val selected = available.firstOrNull { it.id == selectedId } ?: available.firstOrNull()
    if (selected == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (group.isEmpty()) "Загружаем тренировку…" else "Все участники завершили тренировку")
            TextButton(onClick = onBack) { Text("Назад") }
        }
        return
    }
    val names = athletes.associate { it.id to it.name }
    AthleteExerciseLogScreen(
        workoutId = selected.id, exerciseId = exerciseId, onBack = onBack,
        athleteName = names[selected.athleteId] ?: "Участник",
        participants = available, participantNames = names,
        onSelect = { selectedId = it },
        onNext = if (available.size > 1) ({
            val index = available.indexOfFirst { it.id == selected.id }
            selectedId = available[(index + 1) % available.size].id
        }) else null,
        onNextExercise = { nextExerciseId ->
            if (nextExerciseId != null && onOpenExercise != null) onOpenExercise(selected.id, nextExerciseId)
            else if (onPlanComplete != null) onPlanComplete(selected.id) else onBack()
        },
    )
}

@Composable
private fun AthleteExerciseLogScreen(
    workoutId: Long,
    exerciseId: Long,
    athleteName: String,
    participants: List<Workout>,
    participantNames: Map<Long, String>,
    onSelect: (Long) -> Unit,
    onNext: (() -> Unit)?,
    onBack: () -> Unit,
    onNextExercise: (Long?) -> Unit,
) {
    val container = LocalContext.current.appContainer
    val vm: ExerciseLogViewModel = viewModel(key = "exercise_log_${workoutId}_$exerciseId") {
        ExerciseLogViewModel(
            workoutId,
            exerciseId,
            container.workoutRepository,
            container.exerciseRepository,
            createSavedStateHandle(),
        )
    }

    val ui by vm.uiState.collectAsStateWithLifecycle()
    val todaySets by vm.todaySets.collectAsStateWithLifecycle()
    val weightText by vm.weightText.collectAsStateWithLifecycle()
    val repsText by vm.repsText.collectAsStateWithLifecycle()
    val plan by vm.plan.collectAsStateWithLifecycle()
    val plannedExercise = plan.firstOrNull { it.exercise.id == exerciseId }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    // Замена/добавление фото: сохранить новое, привязать к упражнению, удалить старый файл.
    val photoPicker = rememberPhotoPicker { uri ->
        scope.launch {
            runCatching { container.photoStore.saveFromUri(uri) }.onSuccess { newPath ->
                val old = vm.uiState.value.photoPath
                vm.updatePhoto(newPath) { scope.launch { container.photoStore.delete(old) } }
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                snackbarHostState.showSnackbar("Не удалось сохранить фото. Попробуйте ещё раз.")
            }
        }
    }
    var showPhotoViewer by remember { mutableStateOf(false) }

    // Счётчик PR-залпов: каждый инкремент запускает конфетти поверх экрана.
    var prCount by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(vm) {
        vm.prEvents.collect {
            prCount += 1
            snackbarHostState.showSnackbar("🏆 $athleteName: новый личный рекорд!")
        }
    }

    var editingSet by remember(workoutId) { mutableStateOf<WorkoutSet?>(null) }
    var deletingSet by remember(workoutId) { mutableStateOf<WorkoutSet?>(null) }
    fun save(next: Boolean) {
        focusManager.clearFocus()
        val savedBy = vm
        val name = athleteName
        vm.addSet { saved ->
            scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                val result = snackbarHostState.showSnackbar(
                    "$name: ${Format.weight(saved.weightKg)} × ${saved.reps} записано",
                    actionLabel = "Отменить", withDismissAction = true,
                    duration = SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) savedBy.deleteSet(saved)
            }
            if (next) onNext?.invoke()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            // Reserve space for the receipt/Undo bar so it cannot cover the exercise action
            // on short screens or with a large system font.
            bottomBar = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    title = {
                        Column {
                            Text(
                                text = ui.exerciseName.ifEmpty { "Упражнение" },
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            ui.recordWeightKg?.let {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.EmojiEvents,
                                        contentDescription = null,
                                        tint = fitAccents.gold,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Text(
                                        text = "Рекорд: ${Format.weight(it)} кг",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = fitAccents.gold,
                                    )
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                        }
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .testTag("exercise-log-list")
                    .fillMaxSize()
                    .imePadding()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                stickyHeader(key = "participants") {
                    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
                        ParticipantChips(participants, participantNames, workoutId, !ui.saving, onSelect)
                    }
                }
                item(key = "photo") {
                    ExercisePhotoBlock(
                        photoPath = ui.photoPath,
                        equipment = ui.equipment,
                        onOpenPhoto = { showPhotoViewer = true },
                        onCamera = photoPicker.launchCamera,
                        onGallery = photoPicker.launchGallery,
                        onDelete = {
                            val old = ui.photoPath
                            vm.updatePhoto(null) { scope.launch { container.photoStore.delete(old) } }
                        },
                    )
                }

                item(key = "input") {
                    plannedExercise?.let { planned ->
                        Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("План: упражнение ${plan.indexOf(planned) + 1} из ${plan.size}", style = MaterialTheme.typography.titleSmall)
                            if (planned.targetSets != null || planned.targetReps != null) {
                                Text(listOfNotNull(planned.targetSets?.let { "Подходы: $it" },
                                    planned.targetReps?.let { "Повторы: $it" }).joinToString(" · "))
                                Text("Это ориентир. Запишите свой фактический вес и повторы.", style = MaterialTheme.typography.bodySmall)
                            }
                            planned.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                    ui.previousSets.lastOrNull()?.let { previous ->
                        TextButton(onClick = vm::repeatPrevious, enabled = !ui.saving) {
                            Text("Повторить прошлый: ${Format.weight(previous.weightKg)} кг × ${previous.reps}")
                        }
                    }
                    SetInputCard(
                        weightText = weightText,
                        repsText = repsText,
                        onWeightChange = vm::onWeightTextChange,
                        onRepsChange = vm::onRepsTextChange,
                        onBumpWeight = vm::bumpWeight,
                        onBumpReps = vm::bumpReps,
                        onAdd = { save(false) },
                        onNext = onNext?.let { { save(true) } },
                        athleteName = athleteName,
                        busy = ui.saving,
                        weightStep = ui.weightStepKg,
                        onStepChange = vm::setWeightStep,
                    )
                    ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                }

                if (plannedExercise != null) item(key = "complete_exercise") {
                    if (plannedExercise.completedAt != null) {
                        Text("✓ Это упражнение выполнено", style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = vm::reopenExercise, enabled = !ui.saving) { Text("Вернуть упражнение в план") }
                    } else {
                        OutlinedButton(onClick = {
                            focusManager.clearFocus()
                            vm.completeExercise(onNextExercise)
                        }, enabled = !ui.saving && todaySets.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text("Упражнение выполнено → следующее", textAlign = TextAlign.Center)
                        }
                        Text(if (todaySets.isEmpty()) "Сначала запишите хотя бы один подход."
                            else "Завершает упражнение только для: $athleteName", style = MaterialTheme.typography.bodySmall)
                    }
                }

                item(key = "header_today") {
                    SectionHeader(
                        icon = Icons.Filled.FormatListNumbered,
                        title = "Сегодня",
                        iconTint = fitAccents.workout,
                        iconContainer = fitAccents.workoutContainer,
                    )
                }

                if (todaySets.isEmpty()) {
                    item(key = "today_empty") {
                        Text(
                            text = "Подходов пока нет — запишите первый",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(todaySets, key = { it.id }) { set ->
                        TodaySetRow(
                            set = set,
                            trend = if (ui.previousLoaded) trendFor(set, ui.previousSets) else null,
                            onEdit = { editingSet = set },
                            onDelete = { deletingSet = set },
                        )
                    }
                }

                item(key = "previous") {
                    PreviousWorkoutBlock(ui)
                }
            }
        }

        // Залп конфетти при новом личном рекорде — поверх всего экрана.
        ConfettiBurst(trigger = prCount)
    }

    if (showPhotoViewer) {
        ui.photoPath?.let { path ->
            PhotoViewerDialog(photoPath = path, onDismiss = { showPhotoViewer = false })
        }
    }

    editingSet?.let { set ->
        EditSetDialog(
            set = set,
            onDismiss = { editingSet = null },
            onSave = { weight, reps ->
                vm.updateSet(set, weight, reps)
                editingSet = null
            },
        )
    }

    deletingSet?.let { set ->
        AlertDialog(
            onDismissRequest = { deletingSet = null },
            title = { Text("Удалить подход?") },
            text = {
                Text("Подход ${set.setIndex}: ${Format.weight(set.weightKg)}×${set.reps}")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteSet(set)
                        deletingSet = null
                    },
                ) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingSet = null }) {
                    Text("Отмена")
                }
            },
        )
    }

}

/**
 * Компактный блок «фото тренажёра + вид оборудования» между шапкой и вводом.
 * С фото: миниатюра (тап — просмотр), пилюля оборудования и кнопка замены.
 * Без фото: кнопка «Добавить фото тренажёра» и пилюля оборудования.
 */
@Composable
private fun ExercisePhotoBlock(
    photoPath: String?,
    equipment: String,
    onOpenPhoto: () -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (photoPath != null) {
            ExercisePhotoThumb(
                photoPath = photoPath,
                size = 64.dp,
                modifier = Modifier.clickable(onClick = onOpenPhoto),
                fallback = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    )
                },
            )
            if (equipment.isNotBlank()) {
                EquipmentPill(equipment)
            }
            Spacer(Modifier.weight(1f))
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.PhotoCamera, contentDescription = "Заменить фото")
                }
                PhotoSourceMenu(
                    expanded = menuOpen,
                    hasPhoto = true,
                    onDismiss = { menuOpen = false },
                    onCamera = onCamera,
                    onGallery = onGallery,
                    onDelete = onDelete,
                )
            }
        } else {
            Box {
                OutlinedButton(onClick = { menuOpen = true }) {
                    Icon(
                        imageVector = Icons.Filled.PhotoCamera,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Добавить фото тренажёра")
                }
                PhotoSourceMenu(
                    expanded = menuOpen,
                    hasPhoto = false,
                    onDismiss = { menuOpen = false },
                    onCamera = onCamera,
                    onGallery = onGallery,
                    onDelete = onDelete,
                )
            }
            if (equipment.isNotBlank()) {
                EquipmentPill(equipment)
            }
        }
    }
}

/** Меню выбора источника фото: камера, галерея и (если фото уже есть) удаление. */
@Composable
private fun PhotoSourceMenu(
    expanded: Boolean,
    hasPhoto: Boolean,
    onDismiss: () -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Сфотографировать") },
            onClick = {
                onDismiss()
                onCamera()
            },
        )
        DropdownMenuItem(
            text = { Text("Из галереи") },
            onClick = {
                onDismiss()
                onGallery()
            },
        )
        if (hasPhoto) {
            DropdownMenuItem(
                text = { Text("Удалить фото", color = MaterialTheme.colorScheme.error) },
                onClick = {
                    onDismiss()
                    onDelete()
                },
            )
        }
    }
}

/** Пилюля с видом оборудования («Тренажёр», «Штанга»…). */
@Composable
private fun EquipmentPill(text: String) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}


@Composable
private fun SetInputCard(
    weightText: String,
    repsText: String,
    onWeightChange: (String) -> Unit,
    onRepsChange: (String) -> Unit,
    onBumpWeight: (Double) -> Unit,
    onBumpReps: (Int) -> Unit,
    onAdd: () -> Unit,
    onNext: (() -> Unit)?,
    athleteName: String,
    busy: Boolean,
    weightStep: Double,
    onStepChange: (Double) -> Unit,
) {
    val weight = weightText.replace(',', '.').toDoubleOrNull()
    val reps = repsText.toIntOrNull()
    val weightValid = weight != null && weight.isFinite() && weight in 0.0..2000.0
    val repsValid = reps != null && reps in 1..999
    var stepMenu by remember { mutableStateOf(false) }
    val largeText = LocalDensity.current.fontScale > 1.2f
    val repsFocus = remember { FocusRequester() }
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Подход для: $athleteName", style = MaterialTheme.typography.titleMedium)
            BoxWithConstraints {
                val weightField: @Composable (Modifier) -> Unit = { modifier ->
                    StepperColumn("Вес, кг", weightText, onWeightChange, KeyboardType.Decimal,
                        { onBumpWeight(-weightStep) }, { onBumpWeight(weightStep) }, !busy,
                        weightText.isNotBlank() && !weightValid, modifier, onNext = { repsFocus.requestFocus() })
                }
                val repsField: @Composable (Modifier) -> Unit = { modifier ->
                    StepperColumn("Повторы", repsText, onRepsChange, KeyboardType.Number,
                        { onBumpReps(-1) }, { onBumpReps(1) }, !busy,
                        repsText.isNotBlank() && !repsValid, modifier, focusRequester = repsFocus)
                }
                if (maxWidth < 420.dp || largeText) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        weightField(Modifier.fillMaxWidth())
                        repsField(Modifier.fillMaxWidth())
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        weightField(Modifier.weight(1f))
                        repsField(Modifier.weight(1f))
                    }
                }
            }
            Box {
                TextButton(onClick = { stepMenu = true }, enabled = !busy) {
                    Text("Шаг веса: ${Format.weight(weightStep)} кг")
                }
                DropdownMenu(expanded = stepMenu, onDismissRequest = { stepMenu = false }) {
                    listOf(0.5, 1.0, 1.25, 2.5, 5.0, 10.0).forEach { step ->
                        DropdownMenuItem(text = { Text("${Format.weight(step)} кг") },
                            onClick = { stepMenu = false; onStepChange(step) })
                    }
                }
            }
            if ((!weightValid && weightText.isNotBlank()) || (!repsValid && repsText.isNotBlank())) {
                Text("Вес: от 0 до 2000 кг. Повторы: от 1 до 999.", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onAdd, enabled = weightValid && repsValid && !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text("Записать · $athleteName", textAlign = TextAlign.Center)
            }
            if (onNext != null) {
                OutlinedButton(onClick = onNext, enabled = weightValid && repsValid && !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Записать и следующий", textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun StepperColumn(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    enabled: Boolean,
    isError: Boolean,
    modifier: Modifier = Modifier,
    onNext: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
) {
    val focusManager = LocalFocusManager.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconButton(onClick = onMinus, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Remove, contentDescription = "Уменьшить: $label")
        }
        OutlinedTextField(
            value = value, onValueChange = onValueChange, label = { Text(label) },
            singleLine = true, enabled = enabled, isError = isError,
            textStyle = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType,
                imeAction = if (onNext != null) ImeAction.Next else ImeAction.Done),
            keyboardActions = KeyboardActions(onNext = { onNext?.invoke() }, onDone = { focusManager.clearFocus() }),
            modifier = Modifier.weight(1f).then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        )
        IconButton(onClick = onPlus, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Add, contentDescription = "Увеличить: $label")
        }
    }
}

@Composable
private fun TodaySetRow(
    set: WorkoutSet,
    trend: SetTrend?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onEdit)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Номер подхода в круглом бейдже.
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(fitAccents.workoutContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "${set.setIndex}",
                style = MaterialTheme.typography.titleSmall,
                color = fitAccents.onWorkoutContainer,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${Format.weight(set.weightKg)} × ${set.reps}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = Format.time(set.completedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (trend != null) Text(trend.text, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Filled.Edit, contentDescription = "Изменить подход ${set.setIndex}")
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "Удалить подход ${set.setIndex}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PreviousWorkoutBlock(ui: ExerciseLogUiState) {
    if (!ui.previousLoaded) return

    if (ui.previousSets.isEmpty()) {
        EmptyState(
            title = "Первый раз!",
            subtitle = "Сделай подход — будет с чем сравнивать в следующий раз",
            illustration = { IllustrationDumbbell(size = 96.dp) },
        )
    } else {
        Column {
            SectionHeader(
                icon = Icons.Filled.History,
                title = ui.previousDateText
                    ?.let { "Прошлая тренировка ($it)" }
                    ?: "Прошлая тренировка",
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .animateContentSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ui.previousSets.forEach { set ->
                        Text(
                            text = "${set.setIndex} · ${Format.weight(set.weightKg)}×${set.reps}",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EditSetDialog(
    set: WorkoutSet,
    onDismiss: () -> Unit,
    onSave: (weightKg: Double, reps: Int) -> Unit,
) {
    var weightText by rememberSaveable(set.id) { mutableStateOf(Format.weight(set.weightKg)) }
    var repsText by rememberSaveable(set.id) { mutableStateOf(set.reps.toString()) }

    val weightValue = weightText.replace(',', '.').toDoubleOrNull()
    val repsValue = repsText.toIntOrNull()
    val canSave = weightValue != null && weightValue.isFinite() && weightValue in 0.0..2000.0 && repsValue != null && repsValue in 1..999

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Подход ${set.setIndex}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = weightText,
                    onValueChange = { weightText = it },
                    label = { Text("Вес, кг") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = repsText,
                    onValueChange = { repsText = it },
                    label = { Text("Повторы") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(weightValue ?: return@Button, repsValue ?: return@Button) },
                enabled = canSave,
            ) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        },
    )
}
