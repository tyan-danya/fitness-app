@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.dtyan.fitdiary.ui.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dtyan.fitdiary.appContainer
import com.dtyan.fitdiary.data.db.Exercise
import com.dtyan.fitdiary.data.db.ExerciseSeed
import com.dtyan.fitdiary.ui.common.EmptyState
import com.dtyan.fitdiary.ui.common.ExercisePhotoThumb
import com.dtyan.fitdiary.ui.common.IllustrationSearch
import com.dtyan.fitdiary.ui.common.SectionHeader
import com.dtyan.fitdiary.ui.common.muscleGroupEmoji
import com.dtyan.fitdiary.ui.common.rememberPhotoPicker
import com.dtyan.fitdiary.ui.theme.fitAccents
import kotlinx.coroutines.launch

/** Значение фильтра «показывать все виды оборудования». */
private const val EQUIPMENT_ALL = "Все"

@Composable
fun ExercisePickerScreen(
    workoutId: Long,
    onExerciseChosen: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val container = LocalContext.current.appContainer
    val vm: ExercisePickerViewModel = viewModel(key = "exercise_picker_$workoutId") {
        ExercisePickerViewModel(container.exerciseRepository)
    }

    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var selectedEquipment by rememberSaveable { mutableStateOf(EQUIPMENT_ALL) }
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }

    // Прокидываем фильтр оборудования в VM (в т.ч. после восстановления state).
    LaunchedEffect(selectedEquipment) {
        vm.setEquipment(selectedEquipment.takeUnless { it == EQUIPMENT_ALL })
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text("Выбор упражнения") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreateDialog = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Создать упражнение") },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Поиск-пилюля.
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    vm.setQuery(it)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = MaterialTheme.shapes.extraLarge,
                placeholder = { Text("Найти упражнение…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = {
                            query = ""
                            vm.setQuery("")
                        }) {
                            Icon(Icons.Filled.Close, contentDescription = "Очистить")
                        }
                    }
                },
                singleLine = true,
            )

            // Фильтр по виду оборудования: «Все» + типы из каталога.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                (listOf(EQUIPMENT_ALL) + ExerciseSeed.EQUIPMENT_TYPES).forEach { type ->
                    FilterChip(
                        selected = selectedEquipment == type,
                        onClick = { selectedEquipment = type },
                        label = { Text(type) },
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                // Отступ снизу — чтобы FAB не перекрывал последние элементы.
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                if (uiState.recent.isNotEmpty()) {
                    item(key = "header_recent") {
                        SectionHeader(
                            icon = Icons.Filled.Bolt,
                            title = "Недавние",
                            modifier = Modifier.padding(horizontal = 16.dp),
                            iconTint = fitAccents.workout,
                            iconContainer = fitAccents.workoutContainer,
                        )
                    }
                    items(uiState.recent, key = { "recent_${it.id}" }) { exercise ->
                        ExerciseRow(
                            exercise = exercise,
                            showGroup = true,
                            onClick = { onExerciseChosen(exercise.id) },
                        )
                    }
                }

                uiState.groups.forEach { (group, exercises) ->
                    item(key = "header_$group") {
                        Text(
                            text = group,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(
                                start = 16.dp,
                                end = 16.dp,
                                top = 12.dp,
                                bottom = 4.dp,
                            ),
                        )
                    }
                    items(exercises, key = { it.id }) { exercise ->
                        ExerciseRow(
                            exercise = exercise,
                            showGroup = false,
                            onClick = { onExerciseChosen(exercise.id) },
                        )
                    }
                }

                val filterActive = query.isNotBlank() || selectedEquipment != EQUIPMENT_ALL
                if (filterActive && uiState.recent.isEmpty() && uiState.groups.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(
                            title = "Ничего не найдено",
                            subtitle = "Попробуйте другое название — или создайте своё",
                            illustration = { IllustrationSearch() },
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateExerciseDialog(
            onDismiss = { orphanPhotoPath ->
                showCreateDialog = false
                // Фото без упражнения не оставляем — файл-сирота удаляется.
                if (orphanPhotoPath != null) {
                    scope.launch { container.photoStore.delete(orphanPhotoPath) }
                }
            },
            onCreate = { name, group, equipment, photoPath ->
                showCreateDialog = false
                vm.createExercise(name, group, equipment, photoPath, onExerciseChosen)
            },
        )
    }
}

@Composable
private fun ExerciseRow(
    exercise: Exercise,
    showGroup: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Фото тренажёра; без фото — эмодзи группы мышц на подложке.
        ExercisePhotoThumb(
            photoPath = exercise.photoPath,
            size = 44.dp,
            fallback = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        muscleGroupEmoji(exercise.muscleGroup),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            },
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = exercise.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (showGroup) "${exercise.muscleGroup} · ${exercise.equipment}" else exercise.equipment,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (exercise.isCustom) {
            Surface(
                shape = CircleShape,
                color = fitAccents.workoutContainer,
                contentColor = fitAccents.onWorkoutContainer,
            ) {
                Text(
                    text = "своё",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

/**
 * Диалог создания своего упражнения: имя, группа мышц, вид оборудования
 * и необязательное фото тренажёра (камера или галерея).
 * [onDismiss] получает несохранённый pending-файл фото — вызывающий его удаляет.
 */
@Composable
private fun CreateExerciseDialog(
    onDismiss: (orphanPhotoPath: String?) -> Unit,
    onCreate: (name: String, muscleGroup: String, equipment: String, photoPath: String?) -> Unit,
) {
    val photoStore = LocalContext.current.appContainer.photoStore
    val scope = rememberCoroutineScope()

    var name by rememberSaveable { mutableStateOf("") }
    var selectedGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedEquipment by rememberSaveable { mutableStateOf(ExerciseSeed.EQUIPMENT_TYPES.first()) }
    var pendingPhotoPath by rememberSaveable { mutableStateOf<String?>(null) }

    // Выбранное фото сразу сохраняется в PhotoStore; прежний pending-файл удаляется.
    val photoPicker = rememberPhotoPicker { uri ->
        scope.launch {
            val previous = pendingPhotoPath
            runCatching { photoStore.saveFromUri(uri) }.onSuccess { path ->
                pendingPhotoPath = path
                photoStore.delete(previous)
            }
        }
    }

    AlertDialog(
        onDismissRequest = { onDismiss(pendingPhotoPath) },
        title = { Text("Новое упражнение") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Группа мышц",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ExerciseSeed.MUSCLE_GROUPS.forEach { group ->
                        FilterChip(
                            selected = selectedGroup == group,
                            onClick = { selectedGroup = group },
                            label = { Text(group) },
                        )
                    }
                }
                Text(
                    text = "Вид оборудования",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ExerciseSeed.EQUIPMENT_TYPES.forEach { type ->
                        FilterChip(
                            selected = selectedEquipment == type,
                            onClick = { selectedEquipment = type },
                            label = { Text(type) },
                        )
                    }
                }
                Text(
                    text = "Фото тренажёра (необязательно)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val photo = pendingPhotoPath
                if (photo == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalIconButton(onClick = { photoPicker.launchCamera() }) {
                            Icon(Icons.Filled.PhotoCamera, contentDescription = "Камера")
                        }
                        FilledTonalIconButton(onClick = { photoPicker.launchGallery() }) {
                            Icon(Icons.Filled.Image, contentDescription = "Галерея")
                        }
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ExercisePhotoThumb(
                            photoPath = photo,
                            size = 72.dp,
                            fallback = {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                )
                            },
                        )
                        IconButton(
                            onClick = {
                                pendingPhotoPath = null
                                scope.launch { photoStore.delete(photo) }
                            },
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Убрать фото")
                        }
                    }
                }
            }
        },
        confirmButton = {
            val group = selectedGroup
            Button(
                onClick = { group?.let { onCreate(name.trim(), it, selectedEquipment, pendingPhotoPath) } },
                enabled = name.isNotBlank() && group != null,
            ) {
                Text("Создать")
            }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss(pendingPhotoPath) }) {
                Text("Отмена")
            }
        },
    )
}
