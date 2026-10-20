@file:OptIn(ExperimentalLayoutApi::class)

package com.dtyan.fitdiary.ui.activity

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dtyan.fitdiary.data.db.ActivityDefinition
import com.dtyan.fitdiary.domain.ActivityKind
import com.dtyan.fitdiary.domain.ActivityMetric
import com.dtyan.fitdiary.ui.common.Format
import java.time.LocalDate

@Composable
internal fun ActivityEntryEditor(draft: ActivityEntryDraft, definition: ActivityDefinition, ownerName: String,
    busy: Boolean, onChange: ((ActivityEntryDraft) -> ActivityEntryDraft) -> Unit,
    modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize().testTag("activity-entry-editor"), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("title") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(definition.name, style = MaterialTheme.typography.headlineSmall)
                Text("Профиль: $ownerName")
                Text(Format.dateFull(LocalDate.ofEpochDay(draft.epochDay)), style = MaterialTheme.typography.titleSmall)
                if (definition.instructions.isNotBlank()) Text(definition.instructions, style = MaterialTheme.typography.bodySmall)
            }
        }
        when (definition.kind) {
            ActivityKind.REPS -> item("reps") {
                ActivityNumberField("Повторы за один подход", draft.reps, { text -> onChange { it.copy(reps = text) } }, busy)
                Text("Не складывайте несколько подходов в одну запись.", style = MaterialTheme.typography.bodySmall)
            }
            ActivityKind.DURATION -> item("seconds") {
                ActivityNumberField("Фактические секунды", draft.seconds, { text -> onChange { it.copy(seconds = text) } }, busy)
                Text("Длительность одного удержания. Секундомер можно открыть из карточки активности.", style = MaterialTheme.typography.bodySmall)
            }
            ActivityKind.WALK -> {
                item("walk_help") { Text("Запишите хотя бы один показатель этой прогулки. Шаги — только за этот выход, а не дневной итог шагомера.") }
                item("distance") { ActivityNumberField("Расстояние, км", draft.distance, { value -> onChange { it.copy(distance = value) } }, busy, decimal = true) }
                item("steps") { ActivityNumberField("Шаги за эту прогулку", draft.steps, { value -> onChange { it.copy(steps = value) } }, busy) }
                item("minutes") { ActivityNumberField("Время ходьбы, мин", draft.minutes, { value -> onChange { it.copy(minutes = value) } }, busy, decimal = true) }
                item("speed") { ActivityNumberField("Скорость, км/ч · необязательно", draft.speed, { value -> onChange { it.copy(speed = value) } }, busy, decimal = true) }
                item("incline") { ActivityNumberField("Наклон, % · необязательно", draft.incline, { value -> onChange { it.copy(incline = value) } }, busy, decimal = true) }
            }
        }
        if (definition.kind != ActivityKind.WALK) item("clean") {
            CleanResultCheckbox(draft.isClean, { value -> onChange { it.copy(isClean = value) } }, !busy)
        }
        item("note") {
            OutlinedTextField(draft.note, { text -> onChange { it.copy(note = text.take(1000)) } },
                label = { Text("Заметка · необязательно") }, supportingText = { Text("Например, техника или условия выполнения.") },
                modifier = Modifier.fillMaxWidth(), enabled = !busy, minLines = 2, maxLines = 5)
        }
    }
}

@Composable
internal fun ActivityNumberField(label: String, value: String, onChange: (String) -> Unit, busy: Boolean, decimal: Boolean = false) {
    OutlinedTextField(value, { onChange(it.take(18)) }, label = { Text(label) }, modifier = Modifier.fillMaxWidth(),
        singleLine = true, enabled = !busy,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number, imeAction = ImeAction.Next))
}

@Composable
internal fun CleanResultCheckbox(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean) {
    Column {
        Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked, onCheckedChange = onChange, enabled = enabled)
            Text("Выполнено с нужной техникой", modifier = Modifier.weight(1f))
        }
        Text("Если техника изменилась, снимите отметку. Результат сохранится в истории, но не повысит ступень цели.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun ActivityGoalsDialog(definition: ActivityDefinition, busy: Boolean, error: String?, onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit) {
    val inMinutes = definition.kind == ActivityKind.WALK && definition.goalMetric == ActivityMetric.DURATION_SECONDS
    var rows by rememberSaveable(definition.id) { mutableStateOf<List<String>>(definition.goals.map {
        activityNumber(if (inMinutes) it / 60.0 else it)
    }.ifEmpty { listOf("") }) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Лестница целей") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(definition.name, style = MaterialTheme.typography.titleMedium)
            Text("Единица: ${if (inMinutes) "минуты" else activityMetricLabel(definition.goalMetric)}. Заполняйте ступени по возрастанию. Цель сравнивается с одним чистым результатом.")
            rows.forEachIndexed { index, value ->
                Column {
                    ActivityNumberField("Ступень ${index + 1}", value, { text -> rows = rows.mapIndexed { i, old -> if (i == index) text else old } }, busy,
                        decimal = inMinutes || definition.goalMetric == ActivityMetric.DISTANCE_KM)
                    TextButton(onClick = { rows = rows.filterIndexed { i, _ -> i != index } }, enabled = !busy) { Text("Убрать ступень ${index + 1}") }
                }
            }
            OutlinedButton(onClick = { rows = rows + "" }, enabled = !busy && rows.size < 30, modifier = Modifier.fillMaxWidth()) { Text("Добавить ступень") }
            Text("Пустая лестница отключает цель, история остаётся.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = { onSave(if (inMinutes) rows.map(::walkingMinutesToSeconds) else rows) }, enabled = !busy) { Text("Сохранить цели") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Отмена") } })
}

@Composable
internal fun ActivityDefinitionDialog(existing: ActivityDefinition?, busy: Boolean, error: String?, onDismiss: () -> Unit,
    onSave: (ActivityDefinition?, String, ActivityKind, ActivityMetric, String) -> Unit) {
    var name by rememberSaveable(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var kindName by rememberSaveable(existing?.id) { mutableStateOf((existing?.kind ?: ActivityKind.REPS).name) }
    var metricName by rememberSaveable(existing?.id) { mutableStateOf((existing?.goalMetric ?: ActivityMetric.REPS).name) }
    var instructions by rememberSaveable(existing?.id) { mutableStateOf(existing?.instructions.orEmpty()) }
    val kind = ActivityKind.valueOf(kindName)
    val metric = ActivityMetric.valueOf(metricName)
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (existing == null) "Новая активность" else "Настроить активность") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it.take(120) }, label = { Text("Название и вариант") },
                    supportingText = { Text("Например: «Отжимания с колен»") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                if (existing == null) {
                    Text("Как записывать результат")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActivityKind.entries.forEach { choice -> FilterChip(selected = kind == choice, onClick = {
                            kindName = choice.name
                            metricName = when (choice) {
                                ActivityKind.REPS -> ActivityMetric.REPS
                                ActivityKind.DURATION -> ActivityMetric.DURATION_SECONDS
                                ActivityKind.WALK -> ActivityMetric.DISTANCE_KM
                            }.name
                        }, enabled = !busy, label = { Text(when (choice) {
                            ActivityKind.REPS -> "Повторы"
                            ActivityKind.DURATION -> "Секунды"
                            ActivityKind.WALK -> "Ходьба"
                        }) }) }
                    }
                    if (kind == ActivityKind.WALK) {
                        Text("Цель для одной прогулки")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(ActivityMetric.DISTANCE_KM, ActivityMetric.STEPS, ActivityMetric.DURATION_SECONDS).forEach { choice ->
                                FilterChip(selected = metric == choice, onClick = { metricName = choice.name }, enabled = !busy,
                                    label = { Text(activityMetricLabel(choice)) })
                            }
                        }
                    }
                } else Text("Единица цели: ${activityMetricLabel(existing.goalMetric)}. Для другого варианта создайте отдельную активность.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(instructions, { instructions = it.take(2000) }, label = { Text("Описание техники · необязательно") },
                    modifier = Modifier.fillMaxWidth(), enabled = !busy, minLines = 2, maxLines = 5)
                if (existing == null) Text("Цель можно задать после создания через «Лестница целей».", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(onClick = { onSave(existing, name, kind, metric, instructions) }, enabled = !busy && name.isNotBlank()) { Text("Сохранить активность") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Отмена") } })
}
