@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.dtyan.fitdiary.ui.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dtyan.fitdiary.appContainer
import com.dtyan.fitdiary.data.db.WorkoutSet
import com.dtyan.fitdiary.data.db.Workout
import com.dtyan.fitdiary.domain.ExerciseGuides
import com.dtyan.fitdiary.ui.common.ConfettiBurst
import com.dtyan.fitdiary.ui.common.DetailTopBar
import com.dtyan.fitdiary.ui.common.EmptyState
import com.dtyan.fitdiary.ui.common.ExerciseGuideButton
import com.dtyan.fitdiary.ui.common.ExerciseGuideThumb
import com.dtyan.fitdiary.ui.common.ExercisePhotoThumb
import com.dtyan.fitdiary.ui.common.FavoriteToggle
import com.dtyan.fitdiary.ui.common.FitButton
import com.dtyan.fitdiary.ui.common.FitButtonKind
import com.dtyan.fitdiary.ui.common.FitCard
import com.dtyan.fitdiary.ui.common.FitDimens
import com.dtyan.fitdiary.ui.common.FitSection
import com.dtyan.fitdiary.ui.common.FitSectionScope
import com.dtyan.fitdiary.ui.common.Format
import com.dtyan.fitdiary.ui.common.IllustrationDumbbell
import com.dtyan.fitdiary.ui.common.PhotoViewerDialog
import com.dtyan.fitdiary.ui.common.SectionHeader
import com.dtyan.fitdiary.ui.common.StatusPill
import com.dtyan.fitdiary.ui.common.rememberPhotoPicker
import com.dtyan.fitdiary.ui.common.sectionColors
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
) = FitSectionScope(FitSection.WORKOUT) {
    val container = LocalContext.current.appContainer
    val groupFlow = remember(workoutId) { container.workoutRepository.observeGroupWorkouts(workoutId) }
    val group by groupFlow.collectAsStateWithLifecycle(emptyList())
    val athletes by container.profiles.profiles.collectAsStateWithLifecycle(emptyList())
    var selectedId by rememberSaveable(workoutId) { mutableStateOf(workoutId) }
    val available = group.filter { it.endedAt == null }
    val selected = available.firstOrNull { it.id == selectedId } ?: available.firstOrNull()
    if (selected == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (group.isEmpty()) "Загружаем тренировку…" else "Все участники завершили тренировку",
                style = MaterialTheme.typography.titleMedium)
            FitButton("Назад", onBack, kind = FitButtonKind.Outlined)
        }
        return@FitSectionScope
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
    val favorite by vm.favorite.collectAsStateWithLifecycle()
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
            snackbarHostState.showSnackbar("$athleteName: новый личный рекорд!")
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
                DetailTopBar(
                    title = ui.exerciseName.ifEmpty { "Упражнение" },
                    subtitle = listOfNotNull(ui.muscleGroup.takeIf { it.isNotBlank() }, ui.equipment.takeIf { it.isNotBlank() })
                        .joinToString(" · ").ifEmpty { null },
                    onBack = onBack,
                    actions = {
                        if (ui.exerciseName.isNotEmpty()) FavoriteToggle(favorite, vm::toggleFavorite, ui.exerciseName)
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
                contentPadding = PaddingValues(start = FitDimens.ScreenPadding, end = FitDimens.ScreenPadding, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(FitDimens.ItemGap),
            ) {
                if (participants.size > 1) stickyHeader(key = "participants") {
                    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
                        ParticipantChips(participants, participantNames, workoutId, !ui.saving, onSelect)
                    }
                }
                item(key = "photo") {
                    ExerciseHeaderBlock(
                        exerciseName = ui.exerciseName,
                        photoPath = ui.photoPath,
                        equipment = ui.equipment,
                        recordWeightKg = ui.recordWeightKg,
                        onOpenPhoto = { showPhotoViewer = true },
                        onCamera = photoPicker.launchCamera,
                        onGallery = photoPicker.launchGallery,
                        onDelete = {
                            val old = ui.photoPath
                            vm.updatePhoto(null) { scope.launch { container.photoStore.delete(old) } }
                        },
                    )
                }

                plannedExercise?.let { planned ->
                    item(key = "plan") {
                        val colors = sectionColors()
                        FitCard(color = colors.container, contentColor = colors.onContainer, spacing = 4.dp) {
                            Text("План: упражнение ${plan.indexOf(planned) + 1} из ${plan.size}", style = MaterialTheme.typography.titleSmall)
                            planTarget(planned.targetSets, planned.targetReps)?.let {
                                Text(it, style = MaterialTheme.typography.bodyLarge)
                                Text("Это ориентир. Запишите свой фактический вес и повторы.", style = MaterialTheme.typography.bodySmall)
                            }
                            planned.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }

                item(key = "input") {
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
                        previous = ui.previousSets.lastOrNull(),
                        onRepeatPrevious = vm::repeatPrevious,
                    )
                    ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                }

                if (plannedExercise != null) item(key = "complete_exercise") {
                    if (plannedExercise.completedAt != null) {
                        val colors = sectionColors()
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusPill("Упражнение выполнено", icon = Icons.Rounded.CheckCircle, container = colors.container,
                                contentColor = colors.onContainer)
                            FitButton("Вернуть в план", vm::reopenExercise, kind = FitButtonKind.Plain, compact = true, enabled = !ui.saving)
                        }
                    } else {
                        FitButton("Упражнение выполнено, дальше", {
                            focusManager.clearFocus()
                            vm.completeExercise(onNextExercise)
                        }, Modifier.fillMaxWidth(), kind = FitButtonKind.Outlined, icon = Icons.Rounded.TaskAlt,
                            enabled = !ui.saving && todaySets.isNotEmpty())
                        Text(if (todaySets.isEmpty()) "Сначала запишите хотя бы один подход."
                            else "Отмечает упражнение только для: $athleteName", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, start = 4.dp))
                    }
                }

                item(key = "header_today") {
                    SectionHeader(icon = Icons.Rounded.FormatListNumbered, title = "Сегодня")
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
            text = { Text("Подход ${set.setIndex}: ${Format.weight(set.weightKg)} × ${set.reps}") },
            confirmButton = {
                TextButton(onClick = { vm.deleteSet(set); deletingSet = null }) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deletingSet = null }) { Text("Отмена") } },
        )
    }
}

/**
 * Шапка упражнения: своё фото или встроенная схема, оборудование и рекорд,
 * а под ними две подписанные кнопки — «Техника» и «Фото тренажёра».
 */
@Composable
private fun ExerciseHeaderBlock(
    exerciseName: String,
    photoPath: String?,
    equipment: String,
    recordWeightKg: Double?,
    onOpenPhoto: () -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val guide = ExerciseGuides.resolve(exerciseName, equipment)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (photoPath != null) {
            ExercisePhotoThumb(
                photoPath = photoPath,
                size = 72.dp,
                modifier = Modifier.clickable(onClickLabel = "Открыть фото тренажёра", onClick = onOpenPhoto),
                fallback = { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh)) },
            )
        } else {
            ExerciseGuideThumb(guide, size = 72.dp, displayName = exerciseName)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            recordWeightKg?.let {
                StatusPill("Рекорд: ${Format.weight(it)} кг", icon = Icons.Rounded.EmojiEvents,
                    container = fitAccents.gold.copy(alpha = 0.16f), contentColor = MaterialTheme.colorScheme.onSurface)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ExerciseGuideButton(guide, photoPath, displayName = exerciseName)
                Box {
                    FitButton(if (photoPath == null) "Фото тренажёра" else "Сменить фото", { menuOpen = true },
                        kind = FitButtonKind.Plain, compact = true, icon = Icons.Rounded.PhotoCamera)
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Сфотографировать") },
                            leadingIcon = { Icon(Icons.Rounded.PhotoCamera, null) },
                            onClick = { menuOpen = false; onCamera() })
                        DropdownMenuItem(text = { Text("Из галереи") },
                            leadingIcon = { Icon(Icons.Rounded.Image, null) },
                            onClick = { menuOpen = false; onGallery() })
                        if (photoPath != null) DropdownMenuItem(
                            text = { Text("Удалить фото", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; onDelete() })
                    }
                }
            }
        }
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
    previous: WorkoutSet?,
    onRepeatPrevious: () -> Unit,
) {
    val weight = weightText.replace(',', '.').toDoubleOrNull()
    val reps = repsText.toIntOrNull()
    val weightValid = weight != null && weight.isFinite() && weight in 0.0..2000.0
    val repsValid = reps != null && reps in 1..999
    var stepMenu by remember { mutableStateOf(false) }
    val largeText = LocalDensity.current.fontScale > 1.2f
    val repsFocus = remember { FocusRequester() }
    FitCard(spacing = 12.dp) {
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
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                FitButton("Шаг: ${Format.weight(weightStep)} кг", { stepMenu = true }, kind = FitButtonKind.Tonal,
                    compact = true, icon = Icons.Rounded.Straighten, enabled = !busy)
                DropdownMenu(expanded = stepMenu, onDismissRequest = { stepMenu = false }) {
                    listOf(0.5, 1.0, 1.25, 2.5, 5.0, 10.0).forEach { step ->
                        DropdownMenuItem(text = { Text("${Format.weight(step)} кг") },
                            onClick = { stepMenu = false; onStepChange(step) })
                    }
                }
            }
            if (previous != null) FitButton("Как в прошлый раз: ${Format.weight(previous.weightKg)} × ${previous.reps}",
                onRepeatPrevious, kind = FitButtonKind.Tonal, compact = true, icon = Icons.Rounded.Replay, enabled = !busy)
        }
        if ((!weightValid && weightText.isNotBlank()) || (!repsValid && repsText.isNotBlank())) {
            Text("Вес: от 0 до 2000 кг. Повторы: от 1 до 999.", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
        FitButton("Записать · $athleteName", onAdd, Modifier.fillMaxWidth(), loading = busy,
            enabled = weightValid && repsValid)
        if (onNext != null) {
            FitButton("Записать и следующий", onNext, Modifier.fillMaxWidth(), kind = FitButtonKind.Outlined,
                enabled = weightValid && repsValid && !busy)
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
    val colors = sectionColors()
    val stepperColors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = colors.container, contentColor = colors.onContainer)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalIconButton(onClick = onMinus, enabled = enabled, modifier = Modifier.size(48.dp), colors = stepperColors) {
            Icon(Icons.Rounded.Remove, contentDescription = "Уменьшить: $label")
        }
        OutlinedTextField(
            value = value, onValueChange = onValueChange, label = { Text(label) },
            singleLine = true, enabled = enabled, isError = isError,
            shape = MaterialTheme.shapes.medium,
            textStyle = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType,
                imeAction = if (onNext != null) ImeAction.Next else ImeAction.Done),
            keyboardActions = KeyboardActions(onNext = { onNext?.invoke() }, onDone = { focusManager.clearFocus() }),
            modifier = Modifier.weight(1f).then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        )
        FilledTonalIconButton(onClick = onPlus, enabled = enabled, modifier = Modifier.size(48.dp), colors = stepperColors) {
            Icon(Icons.Rounded.Add, contentDescription = "Увеличить: $label")
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
    val colors = sectionColors()
    FitCard(onClick = onEdit, onClickLabel = "Изменить подход ${set.setIndex}", shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(start = 12.dp, top = 8.dp, end = 4.dp, bottom = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(colors.container), contentAlignment = Alignment.Center) {
                Text("${set.setIndex}", style = MaterialTheme.typography.titleSmall, color = colors.onContainer)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("${Format.weight(set.weightKg)} × ${set.reps}", style = MaterialTheme.typography.titleMedium)
                Text(listOfNotNull(Format.time(set.completedAt), trend?.text).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Rounded.Edit, contentDescription = "Изменить подход ${set.setIndex}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.Delete, contentDescription = "Удалить подход ${set.setIndex}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PreviousWorkoutBlock(ui: ExerciseLogUiState) {
    if (!ui.previousLoaded) return

    if (ui.previousSets.isEmpty()) {
        EmptyState(
            title = "Первый раз!",
            subtitle = "Сделайте подход — будет с чем сравнивать в следующий раз",
            illustration = { IllustrationDumbbell(size = 96.dp) },
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader(
                icon = Icons.Rounded.History,
                title = ui.previousDateText?.let { "Прошлая тренировка · $it" } ?: "Прошлая тренировка",
            )
            FitCard {
                ui.previousSets.forEachIndexed { index, set ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("${set.setIndex}", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${Format.weight(set.weightKg)} × ${set.reps}", style = MaterialTheme.typography.bodyLarge)
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
            TextButton(onClick = { onSave(weightValue ?: return@TextButton, repsValue ?: return@TextButton) }, enabled = canSave) {
                Text("Сохранить")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
