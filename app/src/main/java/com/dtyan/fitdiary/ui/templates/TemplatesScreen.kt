@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.dtyan.fitdiary.ui.templates

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dtyan.fitdiary.appContainer
import com.dtyan.fitdiary.data.db.Exercise
import com.dtyan.fitdiary.data.db.WorkoutTemplate
import kotlinx.coroutines.CancellationException

@Composable
fun TemplatesScreen(
    onBack: () -> Unit,
    onOpenWorkout: (Long) -> Unit,
    sourceWorkoutId: Long? = null,
) {
    val container = LocalContext.current.appContainer
    var ownerId by remember(sourceWorkoutId) {
        mutableStateOf<Long?>(if (sourceWorkoutId == null) container.profiles.activeId.value else null)
    }
    var loadError by remember(sourceWorkoutId) { mutableStateOf<String?>(null) }
    LaunchedEffect(sourceWorkoutId) {
        if (sourceWorkoutId != null) try {
            ownerId = requireNotNull(container.workoutRepository.getWorkoutOnce(sourceWorkoutId)) {
                "Тренировка не найдена"
            }.athleteId
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { loadError = e.message ?: "Не удалось открыть тренировку" }
    }
    val resolvedOwner = ownerId
    if (resolvedOwner == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(loadError ?: "Загружаем программу…")
            TextButton(onClick = onBack) { Text("Назад") }
        }
    } else TemplateScreenContent(resolvedOwner, onBack, onOpenWorkout, sourceWorkoutId)
}

@Composable
private fun TemplateScreenContent(
    ownerId: Long,
    onBack: () -> Unit,
    onOpenWorkout: (Long) -> Unit,
    sourceWorkoutId: Long?,
) {
    val context = LocalContext.current
    val container = context.appContainer
    val vm: TemplatesViewModel = viewModel(key = "templates_$ownerId") {
        TemplatesViewModel(container.templateRepository, container.exerciseRepository, ownerId, createSavedStateHandle())
    }
    val templates by vm.templates.collectAsStateWithLifecycle()
    val exercises by vm.exercises.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val profiles by container.profiles.profiles.collectAsStateWithLifecycle(emptyList())
    val allProfiles by container.profiles.allProfiles.collectAsStateWithLifecycle(emptyList())
    val ownerName = allProfiles.firstOrNull { it.id == ownerId }?.name ?: "Участник"
    val focus = LocalFocusManager.current
    var choosingExercises by rememberSaveable { mutableStateOf(false) }
    var catalogQuery by rememberSaveable { mutableStateOf("") }
    var discard by rememberSaveable { mutableStateOf(false) }
    var deleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    var startId by rememberSaveable { mutableStateOf<Long?>(null) }
    var participants by rememberSaveable { mutableStateOf<List<Long>>(listOf(ownerId)) }
    var sourceVisible by rememberSaveable(sourceWorkoutId) { mutableStateOf(sourceWorkoutId != null && !vm.sourceHandled) }
    var sourceName by rememberSaveable(sourceWorkoutId) { mutableStateOf("Моя программа") }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.readImport(context.contentResolver, it) }
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        vm.writeExport(context.contentResolver, uri)
    }
    fun back() {
        if (busy) return
        when {
            choosingExercises -> choosingExercises = false
            draft != null -> discard = true
            else -> onBack()
        }
    }
    BackHandler { back() }

    Scaffold(
        modifier = Modifier.imePadding(),
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(title = { Text(when {
                choosingExercises -> "Выбрать упражнения"
                draft != null -> if (draft?.id == null) "Новая программа" else "Редактор программы"
                else -> "Мои программы"
            }) }, windowInsets = WindowInsets(0.dp), navigationIcon = {
                IconButton(onClick = ::back, enabled = !busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            })
        },
        bottomBar = {
            if (draft != null) Surface(shadowElevation = 4.dp) {
                Button(onClick = {
                    focus.clearFocus()
                    if (choosingExercises) choosingExercises = false else vm.save()
                }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 48.dp)) {
                    Text(if (busy) "Сохраняем…" else if (choosingExercises) "Готово · выбрано: ${draft?.rows?.size ?: 0}" else "Сохранить программу")
                }
            }
        },
    ) { padding ->
        val currentDraft = draft
        when {
            choosingExercises && currentDraft != null -> TemplateCatalog(
                exercises, currentDraft.rows.map { it.exerciseId }.toSet(), catalogQuery,
                { catalogQuery = it }, { vm.addExercise(it) }, Modifier.padding(padding),
            )
            currentDraft != null -> TemplateEditor(
                currentDraft, busy, error, message, ownerName, vm,
                onAdd = { catalogQuery = ""; choosingExercises = true }, modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item("intro") {
                    Text("Профиль: $ownerName", style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(bottom = 8.dp))
                    Text("Сохраните порядок упражнений и ориентиры. На тренировке записывайте свои фактические результаты.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item("actions") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::newTemplate, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Создать программу") }
                        OutlinedButton(onClick = { importPicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                            enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Импортировать JSON") }
                        Text("Формат FitDiary. Сначала покажем содержимое файла, затем вы подтвердите импорт.",
                            style = MaterialTheme.typography.bodySmall)
                        StatusText(busy, error, message)
                    }
                }
                if (templates.isEmpty()) item("empty") { Text("Программ пока нет. Соберите свою из каталога или загрузите JSON-файл.") }
                items(templates, key = { it.id }) { template ->
                    TemplateCard(template, busy,
                        onStart = { participants = listOf(ownerId); startId = template.id },
                        onEdit = { vm.edit(template.id) },
                        onExport = { vm.prepareExport(template.id) { exportPicker.launch(it) } },
                        onDelete = { deleteId = template.id })
                }
            }
        }
    }

    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Закрыть редактор?") },
        text = { Text("Несохранённые изменения программы будут потеряны.") },
        confirmButton = { TextButton(onClick = { discard = false; vm.discardDraft() }) { Text("Не сохранять") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("Продолжить редактирование") } })

    deleteId?.let { id -> AlertDialog(onDismissRequest = { if (!busy) deleteId = null },
        title = { Text("Удалить программу?") }, text = { Column {
            Text(templates.firstOrNull { it.id == id }?.name.orEmpty())
            Text("Прошлые и текущие тренировки сохранятся.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = !busy, onClick = { vm.delete(id) { deleteId = null } }) { Text("Удалить") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { deleteId = null }) { Text("Отмена") } }) }

    startId?.let { id -> AlertDialog(onDismissRequest = { if (!busy) startId = null },
        title = { Text("Кто тренируется?") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(templates.firstOrNull { it.id == id }?.name.orEmpty(), style = MaterialTheme.typography.titleMedium)
                Text("У всех будет одинаковый план. Подходы и прогресс у каждого свои.")
                profiles.forEach { athlete ->
                    val checked = athlete.id in participants
                    Row(Modifier.fillMaxWidth().clickable(enabled = !busy && athlete.id != ownerId) {
                        participants = if (checked) participants - athlete.id else participants + athlete.id
                    }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = checked, onCheckedChange = { value ->
                            participants = if (value) participants + athlete.id else participants - athlete.id
                        }, enabled = !busy && athlete.id != ownerId)
                        Text(athlete.name + if (athlete.id == ownerId) " · владелец программы" else "")
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(enabled = !busy, onClick = { vm.start(id, participants) { startId = null; onOpenWorkout(it) } }) {
            Text(if (busy) "Готовим…" else "Начать по программе")
        } }, dismissButton = { TextButton(enabled = !busy, onClick = { startId = null }) { Text("Отмена") } }) }

    preview?.let { imported -> AlertDialog(onDismissRequest = vm::dismissPreview,
        title = { Text("Предпросмотр импорта") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(imported.name, style = MaterialTheme.typography.titleMedium)
                Text("${imported.exercises.size} упражнений. Будет создана новая программа для: $ownerName.")
                imported.exercises.forEachIndexed { index, item -> Column {
                    Text("${index + 1}. ${item.name}", style = MaterialTheme.typography.titleSmall)
                    Text("${item.muscleGroup} · ${item.equipment}", style = MaterialTheme.typography.bodySmall)
                    Text(targets(item.targetSets, item.targetReps), style = MaterialTheme.typography.bodySmall)
                    item.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                } }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(onClick = vm::importPreview, enabled = !busy) { Text(if (busy) "Импортируем…" else "Импортировать") } },
        dismissButton = { TextButton(onClick = vm::dismissPreview, enabled = !busy) { Text("Отмена") } }) }

    if (sourceVisible && sourceWorkoutId != null && !vm.sourceHandled) AlertDialog(
        onDismissRequest = { if (!busy) { sourceVisible = false; vm.dismissSource() } },
        title = { Text("Сохранить как программу") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Профиль: $ownerName", style = MaterialTheme.typography.titleSmall)
            Text("Сохраним упражнения по порядку и ориентиры. Подходы, веса и отметки выполнения не копируются.")
            OutlinedTextField(sourceName, { sourceName = it.take(80) }, label = { Text("Название программы") },
                modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(enabled = !busy && sourceName.isNotBlank(), onClick = { vm.saveSource(sourceWorkoutId, sourceName) }) { Text("Сохранить") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { sourceVisible = false; vm.dismissSource() }) { Text("Отмена") } },
    )
}

@Composable
private fun StatusText(busy: Boolean, error: String?, message: String?) {
    if (busy) Text("Подождите…", style = MaterialTheme.typography.bodySmall)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
}

@Composable
private fun TemplateCard(template: WorkoutTemplate, busy: Boolean, onStart: () -> Unit, onEdit: () -> Unit,
    onExport: () -> Unit, onDelete: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(template.name, style = MaterialTheme.typography.titleLarge)
            Button(onClick = onStart, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Начать по программе") }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onEdit, enabled = !busy) { Text("Изменить") }
                TextButton(onClick = onExport, enabled = !busy) { Text("Экспорт") }
                TextButton(onClick = onDelete, enabled = !busy) { Text("Удалить") }
            }
        }
    }
}

@Composable
private fun TemplateEditor(draft: TemplateDraft, busy: Boolean, error: String?, message: String?, ownerName: String, vm: TemplatesViewModel,
    onAdd: () -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("name") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Профиль: $ownerName", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(draft.name, vm::changeName, label = { Text("Название программы") }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
                Text("Упражнения идут сверху вниз. Подходы и повторы — ориентиры, фактический результат вы запишете на тренировке.",
                    style = MaterialTheme.typography.bodySmall)
                StatusText(busy, error, message)
            }
        }
        itemsIndexed(draft.rows, key = { _, row -> row.exerciseId }) { index, row ->
            Surface(shape = MaterialTheme.shapes.large, tonalElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${index + 1}. ${row.name}", style = MaterialTheme.typography.titleMedium)
                    Text(row.muscleGroup, style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = { vm.moveExercise(row.exerciseId, -1) }, enabled = !busy && index > 0) {
                            Icon(Icons.Filled.ArrowUpward, "Выше: ${row.name}")
                        }
                        IconButton(onClick = { vm.moveExercise(row.exerciseId, 1) }, enabled = !busy && index < draft.rows.lastIndex) {
                            Icon(Icons.Filled.ArrowDownward, "Ниже: ${row.name}")
                        }
                        IconButton(onClick = { vm.removeExercise(row.exerciseId) }, enabled = !busy) {
                            Icon(Icons.Filled.Delete, "Убрать: ${row.name}")
                        }
                    }
                    OutlinedTextField(row.sets, { value -> vm.updateRow(row.exerciseId) { it.copy(sets = value.filter(Char::isDigit).take(3)) } },
                        label = { Text("Подходы") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next))
                    OutlinedTextField(row.reps, { value -> vm.updateRow(row.exerciseId) { it.copy(reps = value.filter(Char::isDigit).take(3)) } },
                        label = { Text("Повторы · необязательно") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next))
                    OutlinedTextField(row.note, { value -> vm.updateRow(row.exerciseId) { it.copy(note = value.take(500)) } },
                        label = { Text("Заметка · необязательно") }, modifier = Modifier.fillMaxWidth(), enabled = !busy, maxLines = 4)
                }
            }
        }
        item("add") {
            OutlinedButton(onClick = onAdd, enabled = !busy && draft.rows.size < 100,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Добавить из каталога") }
            if (draft.rows.isEmpty()) Text("Добавьте хотя бы одно упражнение.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TemplateCatalog(exercises: List<Exercise>, selected: Set<Long>, query: String, onQuery: (String) -> Unit,
    onAdd: (Exercise) -> Unit, modifier: Modifier = Modifier) {
    val filtered = exercises.filter { query.isBlank() || "${it.name} ${it.muscleGroup} ${it.equipment}".contains(query.trim(), ignoreCase = true) }
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(query, onQuery, label = { Text("Найти упражнение") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Добавлено: ${selected.size} из 100", modifier = Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (filtered.isEmpty()) item { Text("Ничего не найдено. Попробуйте другое название.") }
            items(filtered, key = { it.id }) { exercise ->
                val included = exercise.id in selected
                Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(exercise.name, style = MaterialTheme.typography.titleMedium)
                        Text("${exercise.muscleGroup} · ${exercise.equipment}", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { onAdd(exercise) }, enabled = !included && selected.size < 100) {
                            Text(if (included) "✓ Добавлено" else "Добавить")
                        }
                    }
                }
            }
        }
    }
}

private fun targets(sets: Int, reps: Int?): String = "Подходы: $sets" + (reps?.let { " · Повторы: $it" } ?: "")
