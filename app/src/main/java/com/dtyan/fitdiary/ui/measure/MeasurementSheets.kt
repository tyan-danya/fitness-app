package com.dtyan.fitdiary.ui.measure

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dtyan.fitdiary.data.SettingsStore
import com.dtyan.fitdiary.data.db.BodyMeasurement
import com.dtyan.fitdiary.domain.MeasurementType
import com.dtyan.fitdiary.ui.common.pressScale
import com.dtyan.fitdiary.ui.theme.fitAccents
import kotlinx.coroutines.launch

/** Шторка «Записать замеры»: поле на каждую отслеживаемую зону, пустые пропускаются. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionEditorSheet(
    state: SessionEditorState,
    onValueChange: (MeasurementType, String) -> Unit,
    onNoteChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val types = state.values.keys.toList()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Замеры сегодня", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "Лента вплотную к телу, но не давит. Заполните только то, что измерили.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            types.forEachIndexed { index, type ->
                OutlinedTextField(
                    value = state.values[type].orEmpty(),
                    onValueChange = { onValueChange(type, it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("${type.emoji} ${type.title}, см") },
                    supportingText = { Text(type.hint) },
                    singleLine = true,
                    isError = state.isInvalid(type),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = if (index == types.lastIndex) ImeAction.Done else ImeAction.Next,
                    ),
                )
            }
            OutlinedTextField(
                value = state.note,
                onValueChange = onNoteChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Заметка (необязательно)") },
                singleLine = true,
            )
            Button(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    scope.launch { sheetState.hide() }.invokeOnCompletion { onSave() }
                },
                enabled = state.canSave,
                modifier = Modifier.fillMaxWidth().height(52.dp).pressScale(),
                colors = ButtonDefaults.buttonColors(containerColor = fitAccents.measure, contentColor = Color.White),
            ) {
                Text("Сохранить", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** Шторка истории одной зоны. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeasurementHistorySheet(
    type: MeasurementType,
    history: List<BodyMeasurement>,
    onDelete: (BodyMeasurement) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("${type.emoji} ${type.title}", style = MaterialTheme.typography.titleLarge)
            Text(type.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MeasurementHistoryList(history = history, onDelete = onDelete)
        }
    }
}

/** Диалог настроек: какие зоны отслеживать и напоминание о замерах. */
@Composable
fun MeasurementSettingsDialog(
    current: SettingsStore.Settings,
    onDismiss: () -> Unit,
    onSave: (tracked: Set<MeasurementType>, reminderEnabled: Boolean, days: Int, hour: Int) -> Unit,
) {
    var tracked by rememberSaveable { mutableStateOf(MeasurementType.encodeSet(current.trackedMeasurements)) }
    var reminderEnabled by rememberSaveable { mutableStateOf(current.measurementReminderEnabled) }
    var daysText by rememberSaveable { mutableStateOf(current.measurementReminderDays.toString()) }
    var hourText by rememberSaveable { mutableStateOf(current.measurementReminderHour.toString()) }

    val trackedSet = MeasurementType.parseSet(tracked)
    val days = daysText.trim().toIntOrNull()
    val hour = hourText.trim().toIntOrNull()
    val valid = trackedSet.isNotEmpty() && days != null && days in 1..60 && hour != null && hour in 0..23

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Замеры") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    "Что измеряем",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MeasurementType.entries.forEach { type ->
                    val checked = type in trackedSet
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                tracked = MeasurementType.encodeSet(if (checked) trackedSet - type else trackedSet + type)
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { on ->
                                tracked = MeasurementType.encodeSet(if (on) trackedSet + type else trackedSet - type)
                            },
                        )
                        Text("${type.emoji} ${type.title}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (trackedSet.isEmpty()) {
                    Text("Выберите хотя бы одну зону", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Text(
                    "Напоминание",
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Напоминать, если замеров давно не было",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(checked = reminderEnabled, onCheckedChange = { reminderEnabled = it })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = daysText,
                        onValueChange = { daysText = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Через, дней") },
                        singleLine = true,
                        enabled = reminderEnabled,
                        isError = reminderEnabled && (days == null || days !in 1..60),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        value = hourText,
                        onValueChange = { hourText = it },
                        modifier = Modifier.width(120.dp),
                        label = { Text("Час, 0–23") },
                        singleLine = true,
                        enabled = reminderEnabled,
                        isError = reminderEnabled && (hour == null || hour !in 0..23),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                Text(
                    "Уведомление со звуком придёт в указанный час, если с последнего замера прошло не меньше заданного числа дней.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onSave(trackedSet, reminderEnabled, days!!, hour!!) }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
