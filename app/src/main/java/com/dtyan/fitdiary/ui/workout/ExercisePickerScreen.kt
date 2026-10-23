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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dtyan.fitdiary.appContainer
import com.dtyan.fitdiary.data.db.Exercise
import com.dtyan.fitdiary.data.db.ExerciseSeed
import com.dtyan.fitdiary.domain.ExerciseGuides
import com.dtyan.fitdiary.ui.common.DetailTopBar
import com.dtyan.fitdiary.ui.common.DislikedPill
import com.dtyan.fitdiary.ui.common.ExerciseGuideThumb
import com.dtyan.fitdiary.ui.common.EmptyState
import com.dtyan.fitdiary.ui.common.ExercisePhotoThumb
import com.dtyan.fitdiary.ui.common.FavoriteToggle
import com.dtyan.fitdiary.ui.common.FitDimens
import com.dtyan.fitdiary.ui.common.FitFab
import com.dtyan.fitdiary.ui.common.FitSection
import com.dtyan.fitdiary.ui.common.FitSectionScope
import com.dtyan.fitdiary.ui.common.IllustrationSearch
import com.dtyan.fitdiary.ui.common.SectionHeader
import com.dtyan.fitdiary.ui.common.StatusPill
import com.dtyan.fitdiary.ui.common.sectionColors
import com.dtyan.fitdiary.ui.common.rememberPhotoPicker
import com.dtyan.fitdiary.ui.theme.fitAccents
import kotlinx.coroutines.launch

/** Значение фильтра «показывать все виды оборудования». */
private const val EQUIPMENT_ALL = "Все"

/**
 * Каталог упражнений. С [replaceExerciseId] он подбирает замену: сверху похожие, а выбор
 * подтверждается — где заменить (эта тренировка, программы участника). [onReplaced] получает
 * новое упражнение, если оно встало в тренировку.
 */
@Composable
fun ExercisePickerScreen(
    workoutId: Long,
    onExerciseChosen: (Long) -> Unit,
    onBack: () -> Unit,
    replaceExerciseId: Long? = null,
    onReplaced: (Long?) -> Unit = {},
) = FitSectionScope(FitSection.WORKOUT) {
    val container = LocalContext.current.appContainer
    val vm: ExercisePickerViewModel = viewModel(key = "exercise_picker_${workoutId}_${replaceExerciseId ?: 0}") {
        ExercisePickerViewModel(container.exerciseRepository, workoutId, container.workoutRepository,
            replaceExerciseId, container.templateRepository)
    }

    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val favoriteError by vm.error.collectAsStateWithLifecycle()
    val targets by vm.replaceTargets.collectAsStateWithLifecycle()
    var replacement by remember { mutableStateOf<Exercise?>(null) }
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var selectedEquipment by rememberSaveable { mutableStateOf(EQUIPMENT_ALL) }
    var onlyFavorites by rememberSaveable { mutableStateOf(false) }
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    var chooseError by remember { mutableStateOf<String?>(null) }
    fun choose(exerciseId: Long) {
        if (replaceExerciseId != null) {
            // Подтверждение покажет, где заменить; сам выбор ничего не меняет.
            scope.launch { replacement = container.exerciseRepository.getById(exerciseId) }
            return
        }
        if (choosing) return
        choosing = true
        chooseError = null
        scope.launch {
            try {
                container.workoutRepository.planExercise(workoutId, exerciseId)
                onExerciseChosen(exerciseId)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { chooseError = "Не удалось добавить упражнение. Попробуйте ещё раз." }
            finally { choosing = false }
        }
    }

    // Прокидываем фильтры в VM (в т.ч. после восстановления state).
    LaunchedEffect(selectedEquipment) {
        vm.setEquipment(selectedEquipment.takeUnless { it == EQUIPMENT_ALL })
    }
    LaunchedEffect(onlyFavorites) { vm.setOnlyFavorites(onlyFavorites) }
    LaunchedEffect(query) { vm.setQuery(query) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (replaceExerciseId == null) DetailTopBar(title = "Выбор упражнения", onBack = onBack)
            else DetailTopBar(title = "Замена упражнения", subtitle = targets.exercise?.let { "Вместо: ${it.name}" }, onBack = onBack)
        },
        floatingActionButton = {
            FitFab("Создать упражнение", Icons.Rounded.Add, onClick = { showCreateDialog = true })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            (chooseError ?: favoriteError)?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            // Поиск-пилюля.
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    vm.setQuery(it)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape = MaterialTheme.shapes.extraLarge,
                placeholder = { Text("Найти упражнение…") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = {
                            query = ""
                            vm.setQuery("")
                        }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Очистить")
                        }
                    }
                },
                singleLine = true,
            )

            // Фильтры: только избранное, затем вид оборудования.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = onlyFavorites,
                    onClick = { onlyFavorites = !onlyFavorites },
                    label = { Text("Избранное") },
                    leadingIcon = {
                        Icon(if (onlyFavorites) Icons.Rounded.Star else Icons.Rounded.StarBorder, contentDescription = null,
                            tint = fitAccents.gold, modifier = Modifier.size(18.dp))
                    },
                )
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
                contentPadding = PaddingValues(bottom = FitDimens.FabClearance),
            ) {
                if (uiState.similar.isNotEmpty()) {
                    item(key = "header_similar") {
                        SectionHeader(
                            icon = Icons.Rounded.SwapHoriz,
                            title = "Похожие · ${targets.exercise?.muscleGroup.orEmpty()}",
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    items(uiState.similar, key = { "similar_${it.id}" }) { exercise ->
                        ExerciseRow(exercise, showGroup = true, favorite = exercise.id in uiState.favoriteIds,
                            onToggleFavorite = { vm.toggleFavorite(exercise.id) }, onClick = { choose(exercise.id) }, replacing = true)
                    }
                }
                if (uiState.favorites.isNotEmpty()) {
                    item(key = "header_favorites") {
                        SectionHeader(
                            icon = Icons.Rounded.Star,
                            title = "Избранное",
                            modifier = Modifier.padding(horizontal = 16.dp),
                            iconTint = fitAccents.gold,
                        )
                    }
                    items(uiState.favorites, key = { "favorite_${it.id}" }) { exercise ->
                        ExerciseRow(exercise, showGroup = true, favorite = true, onToggleFavorite = { vm.toggleFavorite(exercise.id) },
                            onClick = { choose(exercise.id) }, replacing = replaceExerciseId != null)
                    }
                }
                if (uiState.recent.isNotEmpty()) {
                    item(key = "header_recent") {
                        SectionHeader(icon = Icons.Rounded.History, title = "Недавние", modifier = Modifier.padding(horizontal = 16.dp))
                    }
                    items(uiState.recent, key = { "recent_${it.id}" }) { exercise ->
                        ExerciseRow(exercise, showGroup = true, favorite = false, onToggleFavorite = { vm.toggleFavorite(exercise.id) },
                            onClick = { choose(exercise.id) }, replacing = replaceExerciseId != null)
                    }
                }

                uiState.groups.forEach { (group, exercises) ->
                    item(key = "header_$group") {
                        Text(
                            text = group,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                    items(exercises, key = { it.id }) { exercise ->
                        ExerciseRow(exercise, showGroup = false, favorite = exercise.id in uiState.favoriteIds,
                            onToggleFavorite = { vm.toggleFavorite(exercise.id) }, onClick = { choose(exercise.id) },
                            disliked = exercise.id in uiState.dislikedIds, replacing = replaceExerciseId != null)
                    }
                }

                val filterActive = query.isNotBlank() || selectedEquipment != EQUIPMENT_ALL || onlyFavorites
                if (filterActive && uiState.favorites.isEmpty() && uiState.recent.isEmpty() && uiState.groups.isEmpty()) {
                    item(key = "empty") {
                        val noFavorites = onlyFavorites && query.isBlank()
                        EmptyState(
                            title = if (noFavorites) "Избранного пока нет" else "Ничего не найдено",
                            subtitle = if (noFavorites) "Отмечайте любимые упражнения звёздочкой — они будут сверху"
                                else "Попробуйте другое название — или создайте своё",
                            illustration = { IllustrationSearch() },
                        )
                    }
                }
            }
        }
    }

    replacement?.let { chosen ->
        ReplaceConfirmDialog(targets, chosen,
            onDismiss = { replacement = null },
            onConfirm = { inWorkout, templateIds ->
                replacement = null
                vm.replace(chosen.id, inWorkout, templateIds, onReplaced)
            })
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
                vm.createExercise(name, group, equipment, photoPath, ::choose)
            },
        )
    }
}

/**
 * Где заменить: эта тренировка и программы участника, где стоит упражнение. По умолчанию — везде;
 * подходы и повторы остаются прежними.
 */
@Composable
private fun ReplaceConfirmDialog(
    targets: ReplaceTargets,
    chosen: Exercise,
    onDismiss: () -> Unit,
    onConfirm: (inWorkout: Boolean, templateIds: List<Long>) -> Unit,
) {
    var inWorkout by rememberSaveable(chosen.id, targets.loaded) { mutableStateOf(targets.inWorkout) }
    var templateIds by rememberSaveable(chosen.id, targets.loaded) { mutableStateOf(targets.templates.map { it.id }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Заменить на «${chosen.name}»?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Вместо «${targets.exercise?.name.orEmpty()}». Подходы и повторы останутся прежними.")
                if (!targets.inWorkout && targets.templates.isEmpty()) {
                    Text("Упражнения нет ни в плане этой тренировки, ни в программах.", color = MaterialTheme.colorScheme.error)
                }
                if (targets.inWorkout) CheckRow("В этой тренировке", inWorkout) { inWorkout = it }
                targets.templates.forEach { template ->
                    CheckRow("В программе «${template.name}»", template.id in templateIds) { checked ->
                        templateIds = if (checked) templateIds + template.id else templateIds - template.id
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(inWorkout && targets.inWorkout, templateIds) },
                enabled = (inWorkout && targets.inWorkout) || templateIds.isNotEmpty()) { Text("Заменить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { onChange(!checked) }.heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ExerciseRow(
    exercise: Exercise,
    showGroup: Boolean,
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit,
    disliked: Boolean = false,
    replacing: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = if (replacing) "Выбрать заменой" else "Добавить в тренировку", onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExerciseGuideThumb(ExerciseGuides.forExercise(exercise), exercise.photoPath, size = 56.dp, displayName = exercise.name)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = exercise.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (showGroup) "${exercise.muscleGroup} · ${exercise.equipment}" else exercise.equipment,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (exercise.isCustom) {
                    val colors = sectionColors()
                    StatusPill("своё", container = colors.container, contentColor = colors.onContainer)
                }
            }
            if (disliked) DislikedPill()
        }
        FavoriteToggle(favorite, onToggleFavorite, exercise.name)
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
                            Icon(Icons.Rounded.PhotoCamera, contentDescription = "Камера")
                        }
                        FilledTonalIconButton(onClick = { photoPicker.launchGallery() }) {
                            Icon(Icons.Rounded.Image, contentDescription = "Галерея")
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
                            Icon(Icons.Rounded.Close, contentDescription = "Убрать фото")
                        }
                    }
                }
            }
        },
        confirmButton = {
            val group = selectedGroup
            TextButton(
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
