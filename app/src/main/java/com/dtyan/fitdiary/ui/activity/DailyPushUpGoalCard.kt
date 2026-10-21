package com.dtyan.fitdiary.ui.activity

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dtyan.fitdiary.domain.DailyPushUpProgress
import com.dtyan.fitdiary.domain.DailyPushUpStreak
import com.dtyan.fitdiary.domain.DailyPushUps
import com.dtyan.fitdiary.ui.common.FitButton
import com.dtyan.fitdiary.ui.common.FitButtonKind
import com.dtyan.fitdiary.ui.common.FitButtonRow
import com.dtyan.fitdiary.ui.common.FitCard
import com.dtyan.fitdiary.ui.common.IconBadge
import com.dtyan.fitdiary.ui.common.StatusPill
import com.dtyan.fitdiary.ui.common.sectionColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DailyPushUpGoalCard(
    progress: DailyPushUpProgress,
    isToday: Boolean,
    busy: Boolean,
    canRecord: Boolean,
    onRecord: () -> Unit,
    onEditGoal: () -> Unit,
    streak: DailyPushUpStreak? = null,
) {
    val target = progress.target.takeIf { isToday }
    val uncounted = (progress.totalReps - progress.cleanReps).coerceAtLeast(0)
    val colors = sectionColors()
    FitCard(Modifier.testTag("daily-pushup-card"), spacing = 6.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(Icons.Rounded.LocalFireDepartment, size = 36.dp)
                Text("Отжимания за день", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            Text(if (target == null) "${progress.cleanReps} повторов засчитано" else "${progress.cleanReps} / $target повторов",
                style = MaterialTheme.typography.headlineSmall, color = colors.accent,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Text("Подходов: ${progress.setCount}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (uncounted > 0) Text("Ещё $uncounted повторов с поправкой на технику — в истории, без зачёта в цель.",
                style = MaterialTheme.typography.bodySmall)
            if (!isToday) {
                Text("Цель настраивается для сегодняшнего дня", style = MaterialTheme.typography.bodySmall)
            } else {
                if (target != null) {
                    LinearProgressIndicator(progress = { progress.fraction },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).testTag("daily-pushup-progress"),
                        color = colors.accent, trackColor = MaterialTheme.colorScheme.surfaceContainerHigh, gapSize = 0.dp,
                        drawStopIndicator = {})
                    Text(if (progress.reached) "Цель достигнута" else "Осталось: ${progress.remaining ?: 0} повторов",
                        style = MaterialTheme.typography.bodyMedium)
                    streak?.takeIf { it.target == target }?.let { series ->
                        Column(Modifier.testTag("daily-pushup-streak"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Дни по ${series.target}+ повторов", style = MaterialTheme.typography.titleSmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                StatusPill("Текущая серия: ${series.currentDays} дн.", icon = Icons.Rounded.LocalFireDepartment,
                                    container = colors.container, contentColor = colors.onContainer)
                                StatusPill("Лучшая серия: ${series.bestDays} дн.", icon = Icons.Rounded.EmojiEvents)
                            }
                            if (!series.todayReached && series.currentDays > 0) Text(
                                "Вчерашняя серия сохраняется до конца дня. Сегодня пока не засчитано.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else Text("Цель не задана", style = MaterialTheme.typography.bodySmall)
                Text("Не нужно добирать через боль или усталость.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FitButtonRow(
                minWidthForRow = 320.dp,
                first = { m ->
                    FitButton("Записать подход", onRecord, m.testTag("daily-pushup-record"), kind = FitButtonKind.Tonal,
                        compact = true, icon = Icons.Rounded.Add, enabled = !busy && canRecord)
                },
                second = { m ->
                    if (isToday) FitButton(if (target == null) "Цель на день" else "Изменить цель", onEditGoal, m,
                        kind = FitButtonKind.Plain, compact = true, icon = Icons.Rounded.Flag, enabled = !busy)
                },
            )
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
                OutlinedTextField(text, { text = it.take(12); attempted = false; onEdit() },
                    label = { Text("Повторов за день") }, enabled = !busy, singleLine = true,
                    isError = validation != null, modifier = Modifier.fillMaxWidth().testTag("daily-pushup-target-input"),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done))
                validation?.let { Text(it, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                Text("Необязательный ориентир на каждый день, пока вы его не измените или не уберёте. Автоматически не повышается.")
                Text("Смена цели пересчитает текущую и лучшую серии по новому порогу для всей истории.",
                    style = MaterialTheme.typography.bodyMedium)
                Text("Складываются стандартные варианты отжиманий с сохранением техники из раздела «Активность». Брусья, обратные отжимания и записи тренировок в зале сюда не входят.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Лестница отдельного упражнения сравнивает один подход; эта цель — сумму за день.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Не нужно добирать через боль или усталость.", style = MaterialTheme.typography.bodySmall)
                if (target != null) FitButton("Убрать цель", onClear, kind = FitButtonKind.Danger, compact = true, enabled = !busy)
            }
        },
        confirmButton = { TextButton(onClick = {
            attempted = true
            if (valid) onSave(text)
        }, enabled = !busy) { Text(if (busy) "Сохраняем…" else "Сохранить цель") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Отмена") } },
    )
}
