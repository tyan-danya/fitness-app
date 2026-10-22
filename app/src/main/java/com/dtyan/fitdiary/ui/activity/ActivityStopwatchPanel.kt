package com.dtyan.fitdiary.ui.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.dtyan.fitdiary.data.db.ActivityDefinition
import com.dtyan.fitdiary.data.db.ActivityTimer
import com.dtyan.fitdiary.domain.ActivityProgress
import com.dtyan.fitdiary.domain.ExerciseGuides
import com.dtyan.fitdiary.ui.common.ExerciseGuideThumb
import com.dtyan.fitdiary.ui.common.FitButton
import com.dtyan.fitdiary.ui.common.FitButtonKind
import com.dtyan.fitdiary.ui.common.Format
import com.dtyan.fitdiary.ui.common.StatusPill
import com.dtyan.fitdiary.ui.common.sectionColors
import com.dtyan.fitdiary.ui.theme.fitAccents
import java.time.LocalDate
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ActivityStopwatchPanel(definition: ActivityDefinition, timer: ActivityTimer?, progress: ActivityProgress, ownerName: String,
    busy: Boolean, error: String?, vm: ActivityViewModel, modifier: Modifier = Modifier) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var elapsed by remember(timer) { mutableStateOf(timer?.let(vm::elapsedSeconds)) }
    var seconds by rememberSaveable(timer?.clientToken) { mutableStateOf(timer?.durationSeconds?.toString().orEmpty()) }
    var note by rememberSaveable(timer?.clientToken) { mutableStateOf("") }
    var clean by rememberSaveable(timer?.clientToken) { mutableStateOf(true) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var showInstructions by rememberSaveable(definition.id) { mutableStateOf(false) }
    LaunchedEffect(timer?.clientToken, timer?.durationSeconds, lifecycleOwner) {
        if (timer == null) return@LaunchedEffect
        if (timer.durationSeconds != null && seconds.isBlank()) seconds = timer.durationSeconds.toString()
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            do {
                elapsed = vm.elapsedSeconds(timer)
                if (timer.durationSeconds != null || elapsed == null) break
                delay(250)
            } while (true)
        }
    }
    val needsCorrection = timer != null && timer.durationSeconds == null && elapsed == null
    val view = LocalView.current
    val running = timer != null && timer.durationSeconds == null && !needsCorrection
    DisposableEffect(view, running) {
        val previouslyKeptOn = view.keepScreenOn
        if (running) view.keepScreenOn = true
        onDispose { view.keepScreenOn = previouslyKeptOn }
    }
    val day = timer?.let { Format.epochDayOf(it.startWallTime) } ?: LocalDate.now().toEpochDay()
    val target = progress.nextGoal ?: definition.goals.lastOrNull()
    val shownSeconds = if (timer?.durationSeconds != null) seconds.toLongOrNull() ?: elapsed else elapsed
    LazyColumn(modifier.fillMaxSize().testTag("activity-stopwatch"), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item("title") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ExerciseGuideThumb(ExerciseGuides.forActivity(definition), size = 48.dp, displayName = definition.name)
                Column(Modifier.weight(1f)) {
                    Text(definition.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("Результат для: $ownerName", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item("clock") {
            val running = timer != null && timer.durationSeconds == null && !needsCorrection
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StopwatchDial(
                    elapsedSeconds = shownSeconds,
                    goalSeconds = target,
                    running = running,
                    nextGoalSeconds = target?.let { goal -> definition.goals.firstOrNull { it > goal } },
                    status = when {
                        timer == null -> "готов к старту"
                        needsCorrection -> "укажите время вручную"
                        target != null && (shownSeconds ?: 0L) >= target -> "цель достигнута"
                        running -> "держим"
                        else -> "остановлен"
                    },
                )
                if (target != null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val colors = sectionColors()
                        StatusPill("Цель: ${activityValue(target, definition.goalMetric)}", icon = Icons.Rounded.Flag,
                            container = colors.container, contentColor = colors.onContainer)
                        if (shownSeconds != null && shownSeconds >= target) {
                            StatusPill("Цель в этом отсчёте достигнута", icon = Icons.Rounded.EmojiEvents,
                                container = fitAccents.gold.copy(alpha = 0.18f), contentColor = MaterialTheme.colorScheme.onSurface)
                        } else {
                            StatusPill("Осталось: ${activityValue((target - (shownSeconds ?: 0L)).coerceAtLeast(0.0), definition.goalMetric)}")
                        }
                        definition.goals.firstOrNull { it > target }?.let { next ->
                            StatusPill("Следующая ступень: ${activityValue(next, definition.goalMetric)}")
                        }
                    }
                } else Text("Цель не задана. Можно настроить её в карточке активности.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(when {
                    timer == null -> "Нажмите «Старт», когда готовы."
                    needsCorrection -> "После перезапуска или переноса приложения укажите фактическое время вручную."
                    timer.durationSeconds == null -> "Секундомер идёт. Можно свернуть приложение — время сохранится."
                    else -> "Остановлен. Проверьте время и сохраните подход."
                }, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                Text("Прогресс изменится только после сохранения подхода · ${Format.dateFull(LocalDate.ofEpochDay(day))}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
        if (timer == null) item("start") {
            FitButton("Старт", { vm.startTimer(definition.id) }, Modifier.fillMaxWidth(), icon = Icons.Rounded.PlayArrow, enabled = !busy)
        } else if (timer.durationSeconds == null && !needsCorrection) item("stop") {
            FitButton("Стоп", { vm.stopTimer(definition.id) }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Stop, enabled = !busy)
        } else {
            item("actual") {
                ActivityNumberField("Фактические секунды", seconds, { seconds = it; vm.clearError() }, busy)
                Text("Можно исправить время до сохранения.", style = MaterialTheme.typography.bodySmall)
            }
            item("clean") { CleanResultCheckbox(clean, { clean = it; vm.clearError() }, !busy) }
            item("note") {
                OutlinedTextField(note, { note = it.take(1000); vm.clearError() }, label = { Text("Заметка · необязательно") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4, enabled = !busy)
            }
            item("save") {
                FitButton("Сохранить подход · $ownerName", { vm.saveTimer(timer, seconds, note, clean) }, Modifier.fillMaxWidth(),
                    enabled = !busy)
            }
        }
        if (timer != null) item("discard") {
            FitButton("Сбросить без записи", { confirmDiscard = true }, Modifier.fillMaxWidth(), kind = FitButtonKind.Danger, enabled = !busy)
        }
        if (definition.instructions.isNotBlank()) item("instructions") {
            FitButton(if (showInstructions) "Скрыть технику" else "Техника выполнения", { showInstructions = !showInstructions },
                kind = FitButtonKind.Plain, compact = true)
            if (showInstructions) Text(definition.instructions, style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (confirmDiscard) AlertDialog(onDismissRequest = { if (!busy) confirmDiscard = false }, title = { Text("Сбросить секундомер?") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Этот отсчёт удалится. Записанный ранее прогресс сохранится.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(onClick = { confirmDiscard = false; vm.discardTimer(definition.id) }, enabled = !busy) {
            Text("Сбросить", color = MaterialTheme.colorScheme.error)
        } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }, enabled = !busy) { Text("Отмена") } })
}
