package com.dtyan.fitdiary.ui.activity

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dtyan.fitdiary.domain.DailyPushUpProgress
import com.dtyan.fitdiary.domain.DailyPushUps

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DailyPushUpGoalCard(
    progress: DailyPushUpProgress,
    isToday: Boolean,
    busy: Boolean,
    canRecord: Boolean,
    onRecord: () -> Unit,
    onEditGoal: () -> Unit,
) {
    val target = progress.target.takeIf { isToday }
    val uncounted = (progress.totalReps - progress.cleanReps).coerceAtLeast(0)
    Surface(Modifier.fillMaxWidth().testTag("daily-pushup-card"), shape = MaterialTheme.shapes.large, tonalElevation = 2.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Отжимания за день", style = MaterialTheme.typography.titleMedium)
            Text(if (target == null) "${progress.cleanReps} повторов засчитано" else "${progress.cleanReps} / $target повторов",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Text("Подходов: ${progress.setCount}", style = MaterialTheme.typography.bodySmall)
            if (uncounted > 0) Text("Ещё $uncounted повторов с поправкой на технику — в истории, без зачёта в цель.",
                style = MaterialTheme.typography.bodySmall)
            if (!isToday) {
                Text("Цель настраивается для сегодняшнего дня", style = MaterialTheme.typography.bodySmall)
            } else {
                if (target != null) {
                    LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth().testTag("daily-pushup-progress"))
                    Text(if (progress.reached) "Цель достигнута" else "Осталось: ${progress.remaining ?: 0} повторов",
                        style = MaterialTheme.typography.bodyMedium)
                } else Text("Цель не задана", style = MaterialTheme.typography.bodySmall)
                Text("Не нужно добирать через боль или усталость.", style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onRecord, enabled = !busy && canRecord,
                    modifier = Modifier.testTag("daily-pushup-record")) { Text("Записать подход") }
                if (isToday) TextButton(onClick = onEditGoal, enabled = !busy) {
                    Text(if (target == null) "Цель на день" else "Изменить цель")
                }
            }
        }
    }
}

@Composable
internal fun DailyPushUpGoalDialog(
    target: Int?,
    ownerName: String,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(target?.toString().orEmpty()) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val valid = text.trim().toIntOrNull()?.let { it in 1..DailyPushUps.MAX_TARGET } == true
    val validation = if (attempted && !valid) "Введите целое число от 1 до ${DailyPushUps.MAX_TARGET}" else error
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Цель отжиманий на день") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Профиль: $ownerName", style = MaterialTheme.typography.titleSmall)
                Text("Необязательный ориентир для суммы подходов из раздела «Активность». Складываются стандартные варианты отжиманий с сохранением техники.")
                Text("Брусья, обратные отжимания и записи тренировок в зале сюда не входят. Лестница отдельного упражнения сравнивает один подход; эта цель — сумму за день.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Цель сохраняется и для следующих дней, пока вы её не измените или не уберёте. Автоматически не повышается.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(text, { text = it.take(12); attempted = false; onEdit() },
                    label = { Text("Повторов за день") }, enabled = !busy, singleLine = true,
                    isError = validation != null, modifier = Modifier.fillMaxWidth().testTag("daily-pushup-target-input"),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done))
                validation?.let { Text(it, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                Text("Не нужно добирать через боль или усталость.", style = MaterialTheme.typography.bodySmall)
                if (target != null) TextButton(onClick = onClear, enabled = !busy) { Text("Убрать цель") }
            }
        },
        confirmButton = { TextButton(onClick = {
            attempted = true
            if (valid) onSave(text)
        }, enabled = !busy) { Text(if (busy) "Сохраняем…" else "Сохранить цель") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Отмена") } },
    )
}
