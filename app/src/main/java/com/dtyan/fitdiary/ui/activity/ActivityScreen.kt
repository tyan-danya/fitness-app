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
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.dtyan.fitdiary.ui.common.ActionTile
import com.dtyan.fitdiary.ui.common.BlockTitle
import com.dtyan.fitdiary.ui.common.DaySwitcher
import com.dtyan.fitdiary.ui.common.DetailTopBar
import com.dtyan.fitdiary.ui.common.ExerciseGuideThumb
import com.dtyan.fitdiary.ui.common.FitButton
import com.dtyan.fitdiary.ui.common.FitButtonKind
import com.dtyan.fitdiary.ui.common.FitButtonRow
import com.dtyan.fitdiary.ui.common.FitCard
import com.dtyan.fitdiary.ui.common.FitSection
import com.dtyan.fitdiary.ui.common.FitSectionScope
import com.dtyan.fitdiary.ui.common.Format
import com.dtyan.fitdiary.ui.common.IconBadge
import com.dtyan.fitdiary.ui.common.MenuAction
import com.dtyan.fitdiary.ui.common.NavRow
import com.dtyan.fitdiary.ui.common.OverflowMenu
import com.dtyan.fitdiary.ui.common.StatusPill
import com.dtyan.fitdiary.ui.common.TileGrid
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun ActivityScreen(onBack: () -> Unit, onOpenPrograms: (() -> Unit)? = null) = FitSectionScope(FitSection.WORKOUT) {
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
    var showPushUpHistory by rememberSaveable { mutableStateOf(false) }
    var pushUpsExpanded by rememberSaveable { mutableStateOf(false) }
    fun back() {
        if (busy) return
        when {
            editor != null -> discardDraft = true
            timerId != null -> vm.openTimer(null)
            historyId != null -> vm.openHistory(null)
            showPushUpHistory -> showPushUpHistory = false
            else -> onBack()
        }
    }
    BackHandler { back() }
    val history = state.definitions.firstOrNull { it.id == historyId }
    val timedActivity = state.definitions.firstOrNull { it.id == timerId }
    val editingActivity = state.definitions.firstOrNull { it.id == editor?.activityId }
    val activePushUps = state.definitions.filter { !it.isArchived && DailyPushUps.isPushUp(it) }
    val otherSets = state.definitions.filter { !it.isArchived && it.kind == ActivityKind.REPS && !DailyPushUps.isPushUp(it) }
    val pushUpTotals = remember(state.definitions, state.allEntries, owner) {
        DailyPushUps.dailyCleanTotals(state.definitions, state.allEntries, owner)
    }
    val title = when {
        editor != null -> if (editor?.entryId == null) "Записать активность" else "Изменить запись"
        timedActivity != null -> "Секундомер"
        history != null -> "История активности"
        showPushUpHistory -> "История отжиманий"
        else -> "Активность за день"
    }
    Scaffold(
        modifier = Modifier.imePadding(), contentWindowInsets = WindowInsets(0.dp),
        topBar = { DetailTopBar(title = title, subtitle = ownerName, onBack = ::back, backEnabled = !busy) },
        bottomBar = {
            if (editor != null || error != null || message != null || busy) Surface(shadowElevation = 4.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val feedback = error ?: message
                    if (feedback != null) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(feedback, color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
                        FitButton("ОК", { vm.clearError(); vm.clearMessage() }, kind = FitButtonKind.Plain, compact = true)
                    }
                    if (editor != null) FitButton(if (busy) "Сохраняем…" else "Сохранить · $ownerName", vm::saveEntry,
                        Modifier.fillMaxWidth(), enabled = !busy)
                    else if (busy) Text("Подождите…", style = MaterialTheme.typography.bodySmall)
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
                    FitCard {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            ExerciseGuideThumb(ExerciseGuides.forActivity(history), displayName = history.name)
                            Text(history.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        }
                        ActivityProgressCard(history, state.allEntries)
                    }
                }
                val groups = state.allEntries.filter { it.activityId == history.id }.groupBy { it.epochDay }.toSortedMap(reverseOrder())
                if (groups.isEmpty()) item("empty") { Text("Записей пока нет. Каждый подход или прогулка появится здесь после сохранения.") }
                groups.forEach { (day, entries) ->
                    item("day_$day") {
                        val best = ActivityProgression.calculate(history, entries).bestValue
                        BlockTitle(Format.dateFull(LocalDate.ofEpochDay(day)), action = best?.let { value ->
                            { StatusPill("Лучшее: ${activityValue(value, history.goalMetric, history.kind)}") }
                        })
                    }
                    items(entries.sortedByDescending { it.timestamp }, key = { "entry_${it.id}" }) { entry ->
                        ActivityLogCard(entry, history, busy, onEdit = { vm.editEntry(entry) }, onDelete = { deleteId = entry.id })
                    }
                }
            }
            showPushUpHistory -> LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("pushup-history"),
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item("week") {
                    FitCard {
                        Text("Все варианты отжиманий вместе", style = MaterialTheme.typography.titleMedium)
                        WeekStreakStrip(pushUpTotals, state.dailyPushUpTarget, state.todayEpochDay, state.todayEpochDay)
                        state.dailyPushUpTarget?.let { target ->
                            Text("Закрытый день — $target+ повторов с сохранением техники.", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                val byDefinition = state.definitions.associateBy { it.id }
                val days = state.allEntries.filter { byDefinition[it.activityId]?.let(DailyPushUps::isPushUp) == true }
                    .groupBy { it.epochDay }.toSortedMap(reverseOrder())
                if (days.isEmpty()) item("empty") {
                    Text("Отжиманий пока нет. Записанные подходы всех вариантов появятся здесь по дням.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                days.forEach { (day, entries) ->
                    item("pushup_day_$day") {
                        BlockTitle(Format.dateFull(LocalDate.ofEpochDay(day)), action = {
                            StatusPill("${entries.sumOf { it.reps ?: 0 }} повт. · ${entries.size} подх.")
                        })
                    }
                    items(entries.sortedByDescending { it.timestamp }, key = { "pushup_entry_${it.id}" }) { entry ->
                        byDefinition[entry.activityId]?.let { definition ->
                            ActivityLogCard(entry, definition, busy, onEdit = { vm.editEntry(entry) }, onDelete = { deleteId = entry.id })
                        }
                    }
                }
            }
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("activity-day-list"),
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item("date") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        DaySwitcher(
                            label = if (state.epochDay == state.todayEpochDay) "Сегодня" else Format.dateFull(LocalDate.ofEpochDay(state.epochDay)),
                            onPrevious = { vm.selectDay(state.epochDay - 1) },
                            onNext = { vm.selectDay(state.epochDay + 1) },
                            onLabelClick = { showDate = true },
                            nextEnabled = state.epochDay < state.todayEpochDay,
                            enabled = !busy,
                            labelClickDescription = "Выбрать дату",
                        )
                        if (state.epochDay != state.todayEpochDay) FitButton("Вернуться к сегодня", { vm.selectDay(state.todayEpochDay) },
                            Modifier.align(Alignment.CenterHorizontally), kind = FitButtonKind.Plain, compact = true)
                    }
                }
                item("summary") {
                    val lines = activityDayBreakdown(state.definitions, state.dayEntries)
                    FitCard(Modifier.testTag("activity-day-summary")) {
                        Text("Итог за день", style = MaterialTheme.typography.titleMedium)
                        if (lines.isEmpty()) Text("Пока ничего не записано.", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        lines.forEach { line ->
                            Row(
                                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClickLabel = "История") {
                                    if (line.key == "pushups") showPushUpHistory = true else vm.openHistory(line.activityIds.single())
                                }.padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconBadge(when {
                                    line.kind == ActivityKind.WALK -> Icons.AutoMirrored.Rounded.DirectionsWalk
                                    line.kind == ActivityKind.DURATION -> Icons.Rounded.Timer
                                    line.key == "pushups" -> Icons.Rounded.LocalFireDepartment
                                    else -> Icons.Rounded.FitnessCenter
                                }, size = 36.dp)
                                Column(Modifier.weight(1f)) {
                                    Text(line.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(line.value, style = MaterialTheme.typography.titleSmall)
                                    line.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                            }
                        }
                        Text("Только записи этого раздела. Тренировки в зале считаются отдельно.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item("daily_pushups") {
                    DailyPushUpGoalCard(state.pushUpProgress, isToday = state.epochDay == state.todayEpochDay,
                        busy = busy, canRecord = activePushUps.isNotEmpty(), onRecord = { choosePushUps = true },
                        onEditGoal = { vm.clearError(); vm.clearMessage(); showDailyGoal = true }, streak = state.pushUpStreak,
                        variants = pushUpVariantLines(state.definitions, state.dayEntries), weekTotals = pushUpTotals,
                        weekTarget = state.dailyPushUpTarget, selectedDay = state.epochDay, todayEpochDay = state.todayEpochDay,
                        onOpenVariant = { vm.openHistory(it) }, onOpenHistory = { showPushUpHistory = true })
                }
                item("quick") {
                    val walking = state.definitions.firstOrNull { !it.isArchived && it.key == "walking" }
                    val plank = state.definitions.firstOrNull { !it.isArchived && it.key == "plank" }
                    val tiles = buildList<@Composable (Modifier) -> Unit> {
                        if (walking != null) add { m ->
                            ActionTile("Записать ходьбу", Icons.AutoMirrored.Rounded.DirectionsWalk, { vm.newEntry(walking.id) }, m,
                                subtitle = "Км, шаги, время", enabled = !busy)
                        }
                        if (plank != null) add { m ->
                            ActionTile("Планка · секундомер", Icons.Rounded.Timer, { vm.openTimer(plank.id) }, m,
                                subtitle = "Время удержания", enabled = !busy)
                        }
                        if (otherSets.isNotEmpty()) add { m ->
                            ActionTile("Другие упражнения", Icons.Rounded.FitnessCenter, { chooseSet = true }, m,
                                subtitle = otherSets.joinToString(", ") { it.name }, enabled = !busy)
                        }
                    }
                    TileGrid(tiles)
                }
                onOpenPrograms?.let { open ->
                    item("programs") {
                        FitCard(contentPadding = PaddingValues(8.dp)) {
                            NavRow("Мои программы и расписание", Icons.AutoMirrored.Rounded.ListAlt, onClick = open,
                                subtitle = "План недели, силовые программы и цели")
                        }
                    }
                }
                if (state.timers.isNotEmpty()) item("pending_timers") {
                    FitCard(contentPadding = PaddingValues(8.dp), spacing = 0.dp) {
                        Text("Незавершённые секундомеры", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(8.dp))
                        state.timers.forEach { timer ->
                            val name = state.definitions.firstOrNull { it.id == timer.activityId }?.name ?: "Упражнение"
                            NavRow(name, Icons.Rounded.Timer, onClick = { vm.openTimer(timer.activityId) }, enabled = !busy,
                                subtitle = if (timer.durationSeconds == null) "Идёт отсчёт" else "Ожидает сохранения")
                        }
                    }
                }
                item("definitions_title") {
                    BlockTitle("Упражнения и цели", action = {
                        FitButton("Добавить", { createDefinition = true }, kind = FitButtonKind.Plain, compact = true,
                            icon = Icons.Rounded.Add, enabled = !busy)
                    })
                    Text("Каждый вариант — отдельная история. Ступени — ваши ориентиры: достигнутые отмечаются автоматически по одному чистому подходу или одной прогулке.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val visible = state.definitions.filter { !it.isArchived || showArchived }.sortedBy { it.sortOrder }
                val groups = listOf(
                    "Ходьба и упражнения на время" to visible.filter { it.kind != ActivityKind.REPS },
                    "Отжимания" to visible.filter { DailyPushUps.isPushUp(it) },
                    "Другие упражнения" to visible.filter { it.kind == ActivityKind.REPS && !DailyPushUps.isPushUp(it) },
                )
                groups.forEach { (groupTitle, members) ->
                    if (members.isEmpty()) return@forEach
                    val collapsible = groupTitle == "Отжимания"
                    item("group_$groupTitle") {
                        if (collapsible) FitCard(onClick = { pushUpsExpanded = !pushUpsExpanded },
                            onClickLabel = if (pushUpsExpanded) "Свернуть" else "Показать варианты", contentPadding = PaddingValues(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                IconBadge(Icons.Rounded.LocalFireDepartment, size = 36.dp)
                                Column(Modifier.weight(1f)) {
                                    Text("Отжимания · ${members.size} ${plural(members.size.toLong(), "вариант", "варианта", "вариантов")}",
                                        style = MaterialTheme.typography.titleSmall)
                                    Text("У каждого варианта своя лестница целей и история", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(if (pushUpsExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
                            }
                        } else Text(groupTitle, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                    if (!collapsible || pushUpsExpanded) items(members, key = { "definition_${it.id}" }) { definition ->
                        ActivityDefinitionCard(definition, state.allEntries, busy, onRecord = { vm.newEntry(definition.id) },
                            onTimer = { vm.openTimer(definition.id) }, onGoals = { goalId = definition.id },
                            onHistory = { vm.openHistory(definition.id) }, onEdit = { definitionId = definition.id },
                            onArchive = { archiveId = definition.id }, onRestore = {
                                vm.saveDefinition(definition, definition.name, definition.kind, definition.goalMetric, definition.instructions) {}
                            })
                    }
                }
                if (state.definitions.any { it.isArchived }) item("archives") {
                    FitButton(if (showArchived) "Скрыть архив" else "Показать архив активностей", { showArchived = !showArchived },
                        kind = FitButtonKind.Plain, compact = true)
                }
                item("day_entries") { BlockTitle("Записи за выбранный день") }
                if (state.dayEntries.isEmpty()) item("empty_day") {
                    Text("За этот день записей пока нет. Начните с прогулки или одного подхода — сохранится только введённый результат.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
                    ActivityChoiceRow(activity) { choosePushUps = false; vm.newEntry(activity.id) }
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
            otherSets.forEach { activity ->
                ActivityChoiceRow(activity) { chooseSet = false; vm.newEntry(activity.id) }
            }
        }
    }, confirmButton = { TextButton(onClick = { chooseSet = false }) { Text("Закрыть") } })
    deleteId?.let { id -> state.allEntries.firstOrNull { it.id == id }?.let { entry ->
        AlertDialog(onDismissRequest = { if (!busy) deleteId = null }, title = { Text("Удалить запись?") },
            text = { Column { Text("Удалится только этот результат за ${Format.dateFull(LocalDate.ofEpochDay(entry.epochDay))}.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) } } },
            confirmButton = { TextButton(enabled = !busy, onClick = { vm.deleteEntry(entry) { deleteId = null } }) {
                Text("Удалить", color = MaterialTheme.colorScheme.error)
            } },
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
    Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
    FitCard {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ExerciseGuideThumb(ExerciseGuides.forActivity(definition), displayName = definition.name)
            Column(Modifier.weight(1f)) {
                Text(definition.name, style = MaterialTheme.typography.titleMedium)
                if (definition.isArchived) Text("В архиве", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OverflowMenu(buildList {
                add(MenuAction("История", Icons.Rounded.History, onClick = onHistory))
                if (definition.isArchived) add(MenuAction("Вернуть из архива", Icons.Rounded.Unarchive, onClick = onRestore))
                else {
                    add(MenuAction("Лестница целей", Icons.Rounded.Flag, onClick = onGoals))
                    add(MenuAction("Настроить", Icons.Rounded.Edit, onClick = onEdit))
                    add(MenuAction("Скрыть в архив", Icons.Rounded.Archive, onClick = onArchive))
                }
            }, contentDescription = "Действия: ${definition.name}", enabled = !busy)
        }
        if (definition.instructions.isNotBlank()) Text(definition.instructions, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        definition.weeklyScheduleNote?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        ActivityProgressCard(definition, entries)
        if (!definition.isArchived) {
            if (definition.kind == ActivityKind.DURATION) FitButtonRow(
                first = { m -> FitButton("Записать результат", onRecord, m, kind = FitButtonKind.Tonal, enabled = !busy) },
                second = { m -> FitButton("Секундомер", onTimer, m, kind = FitButtonKind.Outlined, icon = Icons.Rounded.Timer, enabled = !busy) },
            ) else FitButton("Записать результат", onRecord, Modifier.fillMaxWidth(), kind = FitButtonKind.Tonal, enabled = !busy)
        }
    }
}

/** Строка выбора активности в диалоге: схема упражнения и название. */
@Composable
private fun ActivityChoiceRow(activity: ActivityDefinition, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(MaterialTheme.shapes.small).clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        ExerciseGuideThumb(ExerciseGuides.forActivity(activity), size = 44.dp, displayName = activity.name)
        Text(activity.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ActivityLogCard(entry: ActivityEntry, definition: ActivityDefinition, busy: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    FitCard(shape = MaterialTheme.shapes.medium, contentPadding = PaddingValues(start = 14.dp, top = 12.dp, end = 4.dp, bottom = 8.dp), spacing = 4.dp) {
        Row(verticalAlignment = Alignment.Top) {
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(definition.name, style = MaterialTheme.typography.titleSmall)
            Text(entryDescription(entry, definition.kind), style = MaterialTheme.typography.titleLarge)
            Text("Записано в ${Format.time(entry.timestamp)}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (definition.kind != ActivityKind.WALK && !entry.isClean) Text("С поправкой на технику · не повышает цель", style = MaterialTheme.typography.bodySmall)
            if (definition.kind == ActivityKind.WALK) listOfNotNull(entry.speedKmh?.let { "Скорость: ${activityNumber(it)} км/ч" },
                entry.inclinePercent?.let { "Наклон: ${activityNumber(it)}%" }).takeIf { it.isNotEmpty() }?.let {
                Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
            entry.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
          }
          Row {
            IconButton(onClick = onEdit, enabled = !busy) {
                Icon(Icons.Rounded.Edit, "Изменить запись: ${definition.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDelete, enabled = !busy) {
                Icon(Icons.Rounded.Delete, "Удалить запись: ${definition.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          }
        }
    }
}
