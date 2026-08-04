@file:OptIn(ExperimentalMaterial3Api::class)

package com.dtyan.fitdiary.ui.workout

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dtyan.fitdiary.appContainer
import com.dtyan.fitdiary.ui.common.EmptyState
import com.dtyan.fitdiary.ui.common.ExercisePhotoThumb
import com.dtyan.fitdiary.ui.common.Format
import com.dtyan.fitdiary.ui.common.IllustrationDumbbell
import com.dtyan.fitdiary.ui.common.muscleGroupEmoji
import com.dtyan.fitdiary.ui.common.pressScale
import com.dtyan.fitdiary.ui.theme.fitAccents
import kotlinx.coroutines.delay

@Composable
fun ActiveWorkoutScreen(
    workoutId: Long,
    onAddExercise: () -> Unit,
    onOpenExercise: (Long) -> Unit,
    onWorkoutClosed: () -> Unit,
) {
    val container = LocalContext.current.appContainer
    val vm: ActiveWorkoutViewModel = viewModel(key = "active_workout_$workoutId") {
        ActiveWorkoutViewModel(
            workoutId,
            container.workoutRepository,
            container.statsRepository,
            container.restTimer,
        )
    }

    val workout by vm.workout.collectAsStateWithLifecycle()
    val sets by vm.sets.collectAsStateWithLifecycle()
    val groups by vm.exerciseGroups.collectAsStateWithLifecycle()
    val latestWeight by vm.latestWeight.collectAsStateWithLifecycle()

    var menuOpen by remember { mutableStateOf(false) }
    var showCancelDialog by remember { mutableStateOf(false) }
    var showFinishDialog by remember { mutableStateOf(false) }
    var finishWeightText by remember { mutableStateOf("") }

    // Живое «сейчас» для таймера в шапке.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(workout?.id) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text("Тренировка") },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Меню")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Отменить тренировку",
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    showCancelDialog = true
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column {
                RestTimerBar(container.restTimer)
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FilledTonalButton(
                        onClick = onAddExercise,
                        shape = CircleShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Добавить упражнение", style = MaterialTheme.typography.titleMedium)
                    }
                    GradientActionButton(
                        text = "Завершить",
                        onClick = {
                            finishWeightText = latestWeight?.let { Format.weight(it.weightKg) } ?: ""
                            showFinishDialog = true
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Таймер-чип под шапкой: пилюля с живым временем тренировки.
            val currentWorkout = workout
            AnimatedVisibility(
                visible = currentWorkout != null,
                enter = fadeIn() + slideInVertically(),
            ) {
                Surface(
                    shape = CircleShape,
                    color = fitAccents.workoutContainer,
                    contentColor = fitAccents.onWorkoutContainer,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Timer,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = Format.durationClock(now - (currentWorkout?.startedAt ?: now)),
                            style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
                        )
                    }
                }
            }

            if (groups.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        title = "Тишина в зале",
                        subtitle = "Добавьте первое упражнение — и погнали",
                        illustration = { IllustrationDumbbell() },
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(groups, key = { it.exerciseId }) { group ->
                        ExerciseGroupCard(group = group, onClick = { onOpenExercise(group.exerciseId) })
                    }
                }
            }
        }
    }

    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = { Text("Отменить тренировку?") },
            text = { Text("Тренировка и все её подходы будут удалены. Это действие нельзя отменить.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCancelDialog = false
                        vm.cancelWorkout(onWorkoutClosed)
                    },
                ) {
                    Text("Да, отменить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) {
                    Text("Нет")
                }
            },
        )
    }

    if (showFinishDialog) {
        val startedAt = workout?.startedAt ?: now
        val totalVolume = sets.sumOf { it.weightKg * it.reps }
        AlertDialog(
            onDismissRequest = { showFinishDialog = false },
            title = { Text("Завершить тренировку?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Сводка: три мини-колонки со «числами-героями».
                    Row(modifier = Modifier.fillMaxWidth()) {
                        SummaryCell(
                            value = Format.durationHuman(now - startedAt),
                            label = "длительность",
                            modifier = Modifier.weight(1f),
                        )
                        SummaryCell(
                            value = "${sets.size}",
                            label = ruPlural(sets.size, "подход", "подхода", "подходов"),
                            modifier = Modifier.weight(1f),
                        )
                        SummaryCell(
                            value = Format.volume(totalVolume),
                            label = "тоннаж",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (sets.isEmpty()) {
                        Text(
                            text = "В тренировке нет ни одного подхода",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    OutlinedTextField(
                        value = finishWeightText,
                        onValueChange = { finishWeightText = it },
                        label = { Text("Контрольное взвешивание, кг") },
                        supportingText = { Text("Необязательно") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showFinishDialog = false
                        val weight = finishWeightText.replace(',', '.').toDoubleOrNull()
                        vm.finishWorkout(weight, onWorkoutClosed)
                    },
                ) {
                    Text("Завершить тренировку")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFinishDialog = false }) {
                    Text("Отмена")
                }
            },
        )
    }
}

/** Мини-колонка сводки завершения: значение крупно + подпись. */
@Composable
private fun SummaryCell(value: String, label: String, modifier: Modifier = Modifier) {
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
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Большая кнопка с градиентом раздела «Тренировки» + пружинка + хаптика. */
@Composable
private fun GradientActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = modifier
            .pressScale()
            .clip(MaterialTheme.shapes.large)
            .background(Brush.horizontalGradient(fitAccents.workoutGradient))
            .clickable {
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
private fun ExerciseGroupCard(group: ExerciseGroupUi, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .animateContentSize()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Фото тренажёра; без фото — эмодзи группы мышц на подложке.
            ExercisePhotoThumb(
                photoPath = group.sets.first().photoPath,
                size = 44.dp,
                fallback = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(fitAccents.workoutContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            muscleGroupEmoji(group.muscleGroup),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                },
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = group.sets.joinToString(" · ") { "${Format.weight(it.weightKg)}×${it.reps}" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Text(
                    text = "${group.sets.size} ${ruPlural(group.sets.size, "подход", "подхода", "подходов")}",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** Русские формы множественного числа: 1 подход, 2 подхода, 5 подходов. */
private fun ruPlural(n: Int, one: String, few: String, many: String): String {
    val mod10 = n % 10
    val mod100 = n % 100
    return when {
        mod10 == 1 && mod100 != 11 -> one
        mod10 in 2..4 && mod100 !in 12..14 -> few
        else -> many
    }
}
