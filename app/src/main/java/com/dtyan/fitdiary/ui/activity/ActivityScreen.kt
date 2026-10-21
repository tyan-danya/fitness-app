@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.dtyan.fitdiary.ui.activity

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dtyan.fitdiary.appContainer
import com.dtyan.fitdiary.data.db.ActivityDefinition
import com.dtyan.fitdiary.data.db.ActivityEntry
import com.dtyan.fitdiary.domain.ActivityKind
import com.dtyan.fitdiary.domain.ActivityProgression
import com.dtyan.fitdiary.domain.DailyPushUps
import com.dtyan.fitdiary.domain.ExerciseGuides
import com.dtyan.fitdiary.ui.common.ExerciseGuideThumb
import com.dtyan.fitdiary.ui.common.Format
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun ActivityScreen(onBack: () -> Unit, onOpenPrograms: (() -> Unit)? = null) {
    val container = LocalContext.current.appContainer
    val owner = remember { container.profiles.activeId.value }
    val vm: ActivityViewModel = viewModel(key = "daily_activity_$owner") {
        ActivityViewModel(container.activityRepository, owner, container.settings, createSavedStateHandle())
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(vm, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) { vm.refreshDay(); delay(1000L) }
        }
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val editor by vm.editor.collectAsStateWithLifecycle()
    val historyId by vm.historyId.collectAsStateWithLifecycle()
    val timerId by vm.timerActivityId.collectAsStateWithLifecycle()
    val people by container.profiles.allProfiles.collectAsStateWithLifecycle(emptyList())
    val ownerName = people.firstOrNull { it.id == owner }?.name ?: "Участник"
    var showDate by rememberSaveable { mutableStateOf(false) }
    var goalId by rememberSaveable { mutableStateOf<Long?>(null) }
    var definitionId by rememberSaveable { mutableStateOf<Long?>(null) }
    var createDefinition by rememberSaveable { mutableStateOf(false) }
    var deleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    var archiveId by rememberSaveable { mutableStateOf<Long?>(null) }
    var chooseSet by rememberSaveable { mutableStateOf(false) }
    var choosePushUps by rememberSaveable { mutableStateOf(false) }
    var showDailyGoal by rememberSaveable { mutableStateOf(false) }
    var discardDraft by rememberSaveable { mutableStateOf(false) }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    fun back() {
        if (busy) return
        when {
            editor != null -> discardDraft = true
            timerId != null -> vm.openTimer(null)
            historyId != null -> vm.openHistory(null)
            else -> onBack()
        }
    }
    BackHandler { back() }
    val history = state.definitions.firstOrNull { it.id == historyId }
    val timedActivity = state.definitions.firstOrNull { it.id == timerId }
    val editingActivity = state.definitions.firstOrNull { it.id == editor?.activityId }
    val activePushUps = state.definitions.filter { !it.isArchived && DailyPushUps.isPushUp(it) }
    val title = when {
        editor != null -> if (editor?.entryId == null) "Записать активность" else "Изменить запись"
        timedActivity != null -> "Секундомер"
        history != null -> "История активности"
        else -> "Активность за день"
    }
    Scaffold(
        modifier = Modifier.imePadding(), contentWindowInsets = WindowInsets(0.dp),
        topBar = { TopAppBar(title = { Text(title) }, windowInsets = WindowInsets(0.dp), navigationIcon = {
            IconButton(onClick = ::back, enabled = !busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
        }) },
        bottomBar = {
            if (editor != null || error != null || message != null || busy) Surface(shadowElevation = 4.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val feedback = error ?: message
                    if (feedback != null) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(feedback, color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
                        TextButton(onClick = { vm.clearError(); vm.clearMessage() }) { Text("ОК") }
                    }
                    if (editor != null) Button(onClick = vm::saveEntry, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text(if (busy) "Сохраняем…" else "Сохранить · $ownerName")
                    } else if (busy) Text("Подождите…", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
    ) { padding ->
        val draft = editor
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            draft != null && editingActivity != null -> ActivityEntryEditor(draft, editingActivity, ownerName, busy,
                vm::updateDraft, Modifier.padding(padding))
            timedActivity != null -> ActivityStopwatchPanel(timedActivity, state.timers.firstOrNull { it.activityId == timedActivity.id },
                ActivityProgression.calculate(timedActivity, state.allEntries), ownerName, busy, error, vm, Modifier.padding(padding))
            history != null -> LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("activity-history"),
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item("header") {
                    Text(history.name, style = MaterialTheme.typography.headlineSmall)
                    Text("Профиль: $ownerName", style = MaterialTheme.typography.bodySmall)
                    ActivityProgressCard(history, state.allEntries)
                }
                val groups = state.allEntries.filter { it.activityId == history.id }.groupBy { it.epochDay }.toSortedMap(reverseOrder())
                if (groups.isEmpty()) item("empty") { Text("Записей пока нет. Каждый подход или прогулка появится здесь после сохранения.") }
                groups.forEach { (day, entries) ->
                    item("day_$day") {
                        Text(Format.dateFull(LocalDate.ofEpochDay(day)), style = MaterialTheme.typography.titleMedium)
                        val best = ActivityProgression.calculate(history, entries).bestValue
                        best?.let { Text("Лучшее за день: ${activityValue(it, history.goalMetric, history.kind)}", style = MaterialTheme.typography.bodySmall) }
                    }
                    items(entries.sortedByDescending { it.timestamp }, key = { "entry_${it.id}" }) { entry ->
                        ActivityLogCard(entry, history, busy, onEdit = { vm.editEntry(entry) }, onDelete = { deleteId = entry.id })
                    }
                }
            }
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("activity-day-list"),
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item("date") {
                    Text("Профиль: $ownerName", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { vm.selectDay(state.epochDay - 1) }, enabled = !busy) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Предыдущий день")
                        }
                        TextButton(onClick = { showDate = true }, enabled = !busy, modifier = Modifier.weight(1f)) {
                            Text(Format.dateFull(LocalDate.ofEpochDay(state.epochDay)))
                        }
                        IconButton(onClick = { vm.selectDay(state.epochDay + 1) }, enabled = !busy && state.epochDay < state.todayEpochDay) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, "Следующий день")
                        }
                    }
                    if (state.epochDay != state.todayEpochDay) TextButton(onClick = { vm.selectDay(state.todayEpochDay) }) { Text("Сегодня") }
                }
                item("summary") {
                    val summary = state.summary
                    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 2.dp) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Итог за день", style = MaterialTheme.typography.titleMedium)
                            Text("Ходьба: ${activityNumber(summary.distanceKm)} км · ${summary.steps} шагов")
                            Text("Время ходьбы: ${activityNumber(summary.walkingSeconds / 60.0)} мин")
                            Text("Упражнения: ${summary.sets} подходов · ${summary.reps} повторов · ${summary.exerciseSeconds} сек")
                            Text("Только сохранённые здесь записи. Тренировки в зале считаются отдельно.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item("daily_pushups") {
                    DailyPushUpGoalCard(state.pushUpProgress, isToday = state.epochDay == state.todayEpochDay,
                        busy = busy, canRecord = activePushUps.isNotEmpty(), onRecord = { choosePushUps = true },
                        onEditGoal = { vm.clearError(); vm.clearMessage(); showDailyGoal = true }, streak = state.pushUpStreak)
                }
                item("quick") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.definitions.firstOrNull { !it.isArchived && it.key == "walking" }?.let { walking ->
                            Button(onClick = { vm.newEntry(walking.id) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Записать ходьбу") }
                        }
                        state.definitions.firstOrNull { !it.isArchived && it.key == "plank" }?.let { plank ->
                            OutlinedButton(onClick = { vm.openTimer(plank.id) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Планка · секундомер") }
                        }
                        OutlinedButton(onClick = { chooseSet = true }, enabled = !busy && state.definitions.any { !it.isArchived && it.kind == ActivityKind.REPS },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Другие упражнения") }
                        onOpenPrograms?.let { TextButton(onClick = it, modifier = Modifier.fillMaxWidth()) { Text("Мои программы и расписание") } }
                    }
                }
                if (state.timers.isNotEmpty()) item("pending_timers") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Незавершённые секундомеры", style = MaterialTheme.typography.titleMedium)
                        state.timers.forEach { timer ->
                            val name = state.definitions.firstOrNull { it.id == timer.activityId }?.name ?: "Упражнение"
                            OutlinedButton(onClick = { vm.openTimer(timer.activityId) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                Text("$name · ${if (timer.durationSeconds == null) "идёт" else "ожидает сохранения"}")
                            }
                        }
                    }
                }
                item("definitions_title") {
                    Text("Упражнения и цели", style = MaterialTheme.typography.titleLarge)
                    Text("Каждый вариант — отдельная история. Ступени — ваши ориентиры: достигнутые отмечаются автоматически по одному чистому подходу или одной прогулке.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { createDefinition = true }, enabled = !busy) { Text("Добавить свою активность") }
                }
                items(state.definitions.filter { !it.isArchived || showArchived }.sortedBy { it.sortOrder }, key = { "definition_${it.id}" }) { definition ->
                    ActivityDefinitionCard(definition, state.allEntries, busy, onRecord = { vm.newEntry(definition.id) },
                        onTimer = { vm.openTimer(definition.id) }, onGoals = { goalId = definition.id },
                        onHistory = { vm.openHistory(definition.id) }, onEdit = { definitionId = definition.id },
                        onArchive = { archiveId = definition.id }, onRestore = {
                            vm.saveDefinition(definition, definition.name, definition.kind, definition.goalMetric, definition.instructions) {}
                        })
                }
                if (state.definitions.any { it.isArchived }) item("archives") {
                    TextButton(onClick = { showArchived = !showArchived }) { Text(if (showArchived) "Скрыть архив" else "Показать архив активностей") }
                }
                item("day_entries") { Text("Записи за выбранный день", style = MaterialTheme.typography.titleLarge) }
                if (state.dayEntries.isEmpty()) item("empty_day") { Text("За этот день записей пока нет. Начните с прогулки или одного подхода — сохранится только введённый результат.") }
                items(state.dayEntries, key = { "day_entry_${it.id}" }) { entry ->
                    state.definitions.firstOrNull { it.id == entry.activityId }?.let { definition ->
                        ActivityLogCard(entry, definition, busy, onEdit = { vm.editEntry(entry) }, onDelete = { deleteId = entry.id })
                    }
                }
            }
        }
    }
    if (showDate) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = state.epochDay * 86_400_000,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis / 86_400_000 <= state.todayEpochDay
            })
        DatePickerDialog(onDismissRequest = { showDate = false }, confirmButton = {
            TextButton(onClick = {
                picker.selectedDateMillis?.let { vm.selectDay(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()) }
                showDate = false
            }, enabled = picker.selectedDateMillis != null) { Text("Выбрать") }
        }, dismissButton = { TextButton(onClick = { showDate = false }) { Text("Отмена") } }) { DatePicker(picker) }
    }
    if (showDailyGoal) DailyPushUpGoalDialog(state.dailyPushUpTarget, ownerName, busy, error,
        onDismiss = { showDailyGoal = false; vm.clearError() }, onEdit = vm::clearError,
        onSave = { vm.saveDailyPushUpGoal(it) { showDailyGoal = false } },
        onClear = { vm.clearDailyPushUpGoal { showDailyGoal = false } })
    if (choosePushUps) AlertDialog(onDismissRequest = { choosePushUps = false }, title = { Text("Какой вариант отжиманий?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                activePushUps.forEach { activity ->
                    TextButton(onClick = { choosePushUps = false; vm.newEntry(activity.id) }, modifier = Modifier.fillMaxWidth()) { Text(activity.name) }
                }
            }
        }, confirmButton = { TextButton(onClick = { choosePushUps = false }) { Text("Закрыть") } })
    goalId?.let { id -> state.definitions.firstOrNull { it.id == id }?.let { definition ->
        ActivityGoalsDialog(definition, busy, error, onDismiss = { goalId = null }, onSave = { vm.saveGoals(id, it) { goalId = null } })
    } }
    if (createDefinition || definitionId != null) ActivityDefinitionDialog(
        existing = state.definitions.firstOrNull { it.id == definitionId }, busy = busy, error = error,
        onDismiss = { createDefinition = false; definitionId = null }, onSave = { old, name, kind, metric, instructions ->
            vm.saveDefinition(old, name, kind, metric, instructions) { createDefinition = false; definitionId = null }
        })
    if (chooseSet) AlertDialog(onDismissRequest = { chooseSet = false }, title = { Text("Какой подход записать?") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            state.definitions.filter { !it.isArchived && it.kind == ActivityKind.REPS }.forEach { activity ->
                TextButton(onClick = { chooseSet = false; vm.newEntry(activity.id) }, modifier = Modifier.fillMaxWidth()) { Text(activity.name) }
            }
        }
    }, confirmButton = { TextButton(onClick = { chooseSet = false }) { Text("Закрыть") } })
    deleteId?.let { id -> state.allEntries.firstOrNull { it.id == id }?.let { entry ->
        AlertDialog(onDismissRequest = { if (!busy) deleteId = null }, title = { Text("Удалить запись?") },
            text = { Column { Text("Удалится только этот результат за ${Format.dateFull(LocalDate.ofEpochDay(entry.epochDay))}.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) } } },
            confirmButton = { TextButton(enabled = !busy, onClick = { vm.deleteEntry(entry) { deleteId = null } }) { Text("Удалить") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { deleteId = null }) { Text("Отмена") } })
    } }
    archiveId?.let { id -> state.definitions.firstOrNull { it.id == id }?.let { definition ->
        AlertDialog(onDismissRequest = { if (!busy) archiveId = null }, title = { Text("Скрыть активность?") },
            text = { Column { Text("${definition.name}: история сохранится в архиве.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) } } },
            confirmButton = { TextButton(enabled = !busy, onClick = { vm.archive(definition) { archiveId = null } }) { Text("Скрыть") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { archiveId = null }) { Text("Отмена") } })
    } }
    if (discardDraft) AlertDialog(onDismissRequest = { discardDraft = false }, title = { Text("Закрыть запись?") },
        text = { Text("Введённые изменения ещё не сохранены.") },
        confirmButton = { TextButton(onClick = { discardDraft = false; vm.closeEditor() }) { Text("Не сохранять") } },
        dismissButton = { TextButton(onClick = { discardDraft = false }) { Text("Продолжить ввод") } })
}

@Composable
internal fun ActivityProgressCard(definition: ActivityDefinition, entries: List<ActivityEntry>) {
    val progress = ActivityProgression.calculate(definition, entries.filter { it.activityId == definition.id })
    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(progress.bestValue?.let { "Лучший результат: ${activityValue(it, definition.goalMetric, definition.kind)}" } ?: "Пока нет результата для цели")
        if (definition.goals.isEmpty()) Text("Цель пока не задана. Можно настроить свою лестницу.", style = MaterialTheme.typography.bodySmall)
        else if (progress.allGoalsReached) Text("Все ${definition.goals.size} ступени достигнуты", color = MaterialTheme.colorScheme.primary)
        else {
            val current = progress.nextGoal ?: definition.goals.first()
            Text("Текущая цель: ${activityValue(current, definition.goalMetric, definition.kind)}", style = MaterialTheme.typography.titleSmall)
            val next = definition.goals.firstOrNull { it > current }
            Text(next?.let { "Следующая ступень: ${activityValue(it, definition.goalMetric, definition.kind)}" } ?: "Это последняя ступень")
            Text("До цели: ${activityValue((current - (progress.bestValue ?: 0.0)).coerceAtLeast(0.0), definition.goalMetric, definition.kind)}")
        }
    }
}

@Composable
private fun ActivityDefinitionCard(definition: ActivityDefinition, entries: List<ActivityEntry>, busy: Boolean,
    onRecord: () -> Unit, onTimer: () -> Unit, onGoals: () -> Unit, onHistory: () -> Unit, onEdit: () -> Unit, onArchive: () -> Unit,
    onRestore: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ExerciseGuideThumb(ExerciseGuides.forActivity(definition), displayName = definition.name)
                Text(definition.name + if (definition.isArchived) " · архив" else "", style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f))
            }
            if (definition.instructions.isNotBlank()) Text(definition.instructions, style = MaterialTheme.typography.bodySmall)
            definition.weeklyScheduleNote?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            ActivityProgressCard(definition, entries)
            if (!definition.isArchived) Button(onClick = onRecord, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Записать результат") }
            if (definition.kind == ActivityKind.DURATION && !definition.isArchived) OutlinedButton(onClick = onTimer, enabled = !busy,
                modifier = Modifier.fillMaxWidth()) { Text("Открыть секундомер") }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onHistory, enabled = !busy) { Text("История") }
                if (definition.isArchived) TextButton(onClick = onRestore, enabled = !busy) { Text("Вернуть") }
                if (!definition.isArchived) {
                    TextButton(onClick = onGoals, enabled = !busy) { Text("Лестница целей") }
                    TextButton(onClick = onEdit, enabled = !busy) { Text("Настроить") }
                    TextButton(onClick = onArchive, enabled = !busy) { Text("Скрыть") }
                }
            }
        }
    }
}

@Composable
private fun ActivityLogCard(entry: ActivityEntry, definition: ActivityDefinition, busy: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(definition.name, style = MaterialTheme.typography.titleMedium)
            Text(entryDescription(entry, definition.kind), style = MaterialTheme.typography.titleLarge)
            Text("Записано в ${Format.time(entry.timestamp)}", style = MaterialTheme.typography.bodySmall)
            if (definition.kind != ActivityKind.WALK && !entry.isClean) Text("С поправкой на технику · не повышает цель", style = MaterialTheme.typography.bodySmall)
            if (definition.kind == ActivityKind.WALK) listOfNotNull(entry.speedKmh?.let { "Скорость: ${activityNumber(it)} км/ч" },
                entry.inclinePercent?.let { "Наклон: ${activityNumber(it)}%" }).takeIf { it.isNotEmpty() }?.let {
                Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
            entry.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Row {
                IconButton(onClick = onEdit, enabled = !busy) { Icon(Icons.Filled.Edit, "Изменить запись: ${definition.name}") }
                IconButton(onClick = onDelete, enabled = !busy) { Icon(Icons.Filled.Delete, "Удалить запись: ${definition.name}") }
            }
        }
    }
}
