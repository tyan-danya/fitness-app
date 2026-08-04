@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.dtyan.fitdiary.ui.workout

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dtyan.fitdiary.appContainer
import com.dtyan.fitdiary.data.db.WorkoutSet
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
private enum class SetTrend { BETTER, WORSE, SAME }

private fun trendFor(today: WorkoutSet, previousSets: List<WorkoutSet>): SetTrend? {
    val previous = previousSets.firstOrNull { it.setIndex == today.setIndex } ?: return null
    return when {
        today.weightKg > previous.weightKg -> SetTrend.BETTER
        today.weightKg < previous.weightKg -> SetTrend.WORSE
        today.reps > previous.reps -> SetTrend.BETTER
        today.reps < previous.reps -> SetTrend.WORSE
        else -> SetTrend.SAME
    }
}

@Composable
fun ExerciseLogScreen(
    workoutId: Long,
    exerciseId: Long,
    onBack: () -> Unit,
) {
    val container = LocalContext.current.appContainer
    val vm: ExerciseLogViewModel = viewModel(key = "exercise_log_${workoutId}_$exerciseId") {
        ExerciseLogViewModel(
            workoutId,
            exerciseId,
            container.workoutRepository,
            container.exerciseRepository,
            container.settings,
            container.restTimer,
        )
    }

    val ui by vm.uiState.collectAsStateWithLifecycle()
    val todaySets by vm.todaySets.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val weightText by vm.weightText.collectAsStateWithLifecycle()
    val repsText by vm.repsText.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Замена/добавление фото: сохранить новое, привязать к упражнению, удалить старый файл.
    val photoPicker = rememberPhotoPicker { uri ->
        scope.launch {
            runCatching { container.photoStore.saveFromUri(uri) }.onSuccess { newPath ->
                val old = vm.uiState.value.photoPath
                vm.updatePhoto(newPath)
                container.photoStore.delete(old)
            }
        }
    }
    var showPhotoViewer by remember { mutableStateOf(false) }

    // Счётчик PR-залпов: каждый инкремент запускает конфетти поверх экрана.
    var prCount by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        vm.prEvents.collect {
            prCount += 1
            snackbarHostState.showSnackbar("🏆 Новый личный рекорд!")
        }
    }

    var editingSet by remember { mutableStateOf<WorkoutSet?>(null) }
    var deletingSet by remember { mutableStateOf<WorkoutSet?>(null) }
    var showRestSettings by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbarHostState) },
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
                    actions = {
                        IconButton(onClick = { showRestSettings = true }) {
                            Icon(Icons.Filled.Timer, contentDescription = "Настройки таймера отдыха")
                        }
                    },
                )
            },
            bottomBar = { RestTimerBar(container.restTimer) },
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "photo") {
                    ExercisePhotoBlock(
                        photoPath = ui.photoPath,
                        equipment = ui.equipment,
                        onOpenPhoto = { showPhotoViewer = true },
                        onCamera = photoPicker.launchCamera,
                        onGallery = photoPicker.launchGallery,
                        onDelete = {
                            val old = ui.photoPath
                            vm.updatePhoto(null)
                            scope.launch { container.photoStore.delete(old) }
                        },
                    )
                }

                item(key = "input") {
                    SetInputCard(
                        weightText = weightText,
                        repsText = repsText,
                        onWeightChange = vm::onWeightTextChange,
                        onRepsChange = vm::onRepsTextChange,
                        onBumpWeight = vm::bumpWeight,
                        onBumpReps = vm::bumpReps,
                        onAdd = vm::addSet,
                    )
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

    if (showRestSettings) {
        AlertDialog(
            onDismissRequest = { showRestSettings = false },
            title = { Text("Таймер отдыха") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Таймер отдыха",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = settings.restTimerEnabled,
                            onCheckedChange = vm::setRestTimerEnabled,
                        )
                    }
                    Text(
                        text = "Длительность",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(30, 60, 90, 120, 150, 180).forEach { seconds ->
                            FilterChip(
                                selected = settings.restTimerSeconds == seconds,
                                onClick = { vm.setRestTimerSeconds(seconds) },
                                enabled = settings.restTimerEnabled,
                                label = { Text("$seconds с") },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRestSettings = false }) {
                    Text("Готово")
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
) {
    val weightValue = weightText.replace(',', '.').toDoubleOrNull()
    val repsValue = repsText.toIntOrNull()
    val canAdd = weightValue != null && weightValue >= 0 && repsValue != null && repsValue >= 1

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StepperColumn(
                    label = "ВЕС, КГ",
                    value = weightText,
                    hint = "шаг 2,5",
                    keyboardType = KeyboardType.Decimal,
                    onValueChange = onWeightChange,
                    onMinus = { onBumpWeight(-2.5) },
                    onPlus = { onBumpWeight(2.5) },
                    modifier = Modifier.weight(1f),
                )
                StepperColumn(
                    label = "ПОВТОРЫ",
                    value = repsText,
                    hint = null,
                    keyboardType = KeyboardType.Number,
                    onValueChange = onRepsChange,
                    onMinus = { onBumpReps(-1) },
                    onPlus = { onBumpReps(1) },
                    modifier = Modifier.weight(1f),
                )
            }
            GradientActionButton(
                text = "Записать подход",
                onClick = onAdd,
                enabled = canAdd,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            )
        }
    }
}

/** Колонка степпера: подпись, круглые −/+ по бокам и число-герой по центру. */
@Composable
private fun StepperColumn(
    label: String,
    value: String,
    hint: String?,
    keyboardType: KeyboardType,
    onValueChange: (String) -> Unit,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            RoundStepButton(
                icon = Icons.Filled.Remove,
                contentDescription = "Уменьшить",
                onClick = onMinus,
            )
            // Число ужимается по мере роста разрядности, чтобы не вылезать из колонки.
            val numberSize = when {
                value.length <= 2 -> 36.sp
                value.length <= 4 -> 26.sp
                else -> 20.sp
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.displaySmall.copy(
                    fontSize = numberSize,
                    lineHeight = 44.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                ),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            )
            RoundStepButton(
                icon = Icons.Filled.Add,
                contentDescription = "Увеличить",
                onClick = onPlus,
            )
        }
        Text(
            text = hint ?: "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Круглая кнопка шага −/+. */
@Composable
private fun RoundStepButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .size(44.dp)
            .pressScale(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(20.dp))
        }
    }
}

/** Большая кнопка с градиентом раздела «Тренировки» + пружинка + хаптика. */
@Composable
private fun GradientActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = modifier
            .pressScale()
            .alpha(if (enabled) 1f else 0.45f)
            .clip(MaterialTheme.shapes.large)
            .background(Brush.horizontalGradient(fitAccents.workoutGradient))
            .clickable(enabled = enabled) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        // Белый на градиенте — осознанное исключение из правила «цвета только из темы».
        Text(text, style = MaterialTheme.typography.titleMedium, color = Color.White)
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
        }
        if (trend != null) {
            TrendBadge(trend)
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "Удалить подход",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Пилюля-сравнение с прошлым подходом того же номера: ↑ / ↓ / =. */
@Composable
private fun TrendBadge(trend: SetTrend) {
    val (text, container, content) = when (trend) {
        SetTrend.BETTER -> Triple("↑", fitAccents.workoutContainer, fitAccents.workout)
        SetTrend.WORSE -> Triple(
            "↓",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.error,
        )
        SetTrend.SAME -> Triple(
            "=",
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Surface(shape = CircleShape, color = container, contentColor = content) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
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
    var weightText by remember(set.id) { mutableStateOf(Format.weight(set.weightKg)) }
    var repsText by remember(set.id) { mutableStateOf(set.reps.toString()) }

    val weightValue = weightText.replace(',', '.').toDoubleOrNull()
    val repsValue = repsText.toIntOrNull()
    val canSave = weightValue != null && weightValue >= 0 && repsValue != null && repsValue >= 1

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Подход ${set.setIndex}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
