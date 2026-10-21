package com.dtyan.fitdiary.ui.measure

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.dtyan.fitdiary.data.db.BodyMeasurement
import com.dtyan.fitdiary.reminder.MeasurementReminder
import com.dtyan.fitdiary.ui.common.BodyZoneIcon
import com.dtyan.fitdiary.ui.common.EmptyState
import com.dtyan.fitdiary.ui.common.FitButton
import com.dtyan.fitdiary.ui.common.FitButtonKind
import com.dtyan.fitdiary.ui.common.FitCard
import com.dtyan.fitdiary.ui.common.FitDimens
import com.dtyan.fitdiary.ui.common.FitFab
import com.dtyan.fitdiary.ui.common.FitSection
import com.dtyan.fitdiary.ui.common.FitSectionScope
import com.dtyan.fitdiary.ui.common.Format
import com.dtyan.fitdiary.ui.common.IllustrationChart
import com.dtyan.fitdiary.ui.common.SectionHeader
import com.dtyan.fitdiary.ui.common.StatusPill
import com.dtyan.fitdiary.ui.common.TabHeader
import com.dtyan.fitdiary.ui.common.sectionColors
import com.dtyan.fitdiary.ui.stats.LineChart
import com.dtyan.fitdiary.ui.theme.fitAccents
import java.time.LocalDate
import kotlin.math.abs

/**
 * Вкладка «Тело»: вес (взвешивания и график) и замеры лентой — статус, карточки
 * отслеживаемых зон с динамикой и графиком, запись новой сессии, выбор зон и напоминание.
 */
@Composable
fun MeasurementsScreen() = FitSectionScope(FitSection.BODY) {
    val context = LocalContext.current
    val container = context.appContainer
    val vm: MeasurementsViewModel = viewModel {
        MeasurementsViewModel(container.measurementRepository, container.settings)
    }
    val weightVm: WeightViewModel = viewModel { WeightViewModel(container.statsRepository) }

    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val editor by vm.editor.collectAsStateWithLifecycle()
    val historyType by vm.historyType.collectAsStateWithLifecycle()
    val weight by weightVm.weight.collectAsStateWithLifecycle()

    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var weightOpen by rememberSaveable { mutableStateOf(false) }

    // Разрешение на уведомления (Android 13+): просим при включении напоминания
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !MeasurementReminder.hasNotificationPermission(context)) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TabHeader(title = "Тело", subtitle = "Вес и замеры")
        },
        floatingActionButton = {
            FitFab("Записать замеры", Icons.Rounded.Add, onClick = vm::openEditor)
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            contentPadding = PaddingValues(start = FitDimens.ScreenPadding, end = FitDimens.ScreenPadding, top = 4.dp,
                bottom = FitDimens.FabClearance),
            verticalArrangement = Arrangement.spacedBy(FitDimens.ItemGap),
        ) {
            item(key = "weight_header") { SectionHeader(icon = Icons.Rounded.MonitorWeight, title = "Вес") }
            item(key = "weight") { WeightCard(weight, onAdd = { weightOpen = true }) }

            item(key = "header") { SectionHeader(icon = Icons.Rounded.Straighten, title = "Замеры") }
            item(key = "status") {
                StatusCard(state = state, reminderEnabled = settings.measurementReminderEnabled,
                    reminderDays = settings.measurementReminderDays, reminderHour = settings.measurementReminderHour,
                    onSettings = { settingsOpen = true })
            }
            if (!state.hasAny) {
                item(key = "empty") {
                    EmptyState(
                        title = "Замеров ещё нет",
                        subtitle = "Снимите мерки лентой утром до еды — и записывайте раз в несколько дней",
                        illustration = { IllustrationChart() },
                    )
                }
            }
            items(state.metrics, key = { it.type.name }) { metric ->
                MetricCard(metric = metric, onClick = { vm.openHistory(metric.type) })
            }
        }
    }

    editor?.let { st ->
        SessionEditorSheet(
            state = st,
            onValueChange = vm::onValueChange,
            onNoteChange = vm::onNoteChange,
            onSave = vm::saveEditor,
            onDismiss = vm::closeEditor,
        )
    }

    historyType?.let { type ->
        val metric = state.metrics.firstOrNull { it.type == type }
        MeasurementHistorySheet(
            type = type,
            history = metric?.history.orEmpty(),
            onDelete = vm::delete,
            onDismiss = vm::closeHistory,
        )
    }

    if (settingsOpen) {
        MeasurementSettingsDialog(
            current = settings,
            onDismiss = { settingsOpen = false },
            onSave = { tracked, reminderEnabled, days, hour ->
                vm.updateTracked(tracked)
                vm.updateReminder(reminderEnabled, days, hour)
                MeasurementReminder.schedule(context, reminderEnabled, hour)
                if (reminderEnabled) ensureNotificationPermission()
                settingsOpen = false
            },
        )
    }

    if (weightOpen) {
        AddWeightDialog(
            onConfirm = { kg ->
                weightVm.addWeight(kg)
                weightOpen = false
            },
            onDismiss = { weightOpen = false },
        )
    }
}

/** Вес: текущее значение, изменение за 30 дней, график и кнопка нового взвешивания. */
@Composable
private fun WeightCard(state: WeightUiState, onAdd: () -> Unit) {
    val colors = sectionColors()
    FitCard(spacing = 12.dp) {
        val current = state.current
        if (current == null) {
            Text("Взвешиваний пока нет", style = MaterialTheme.typography.titleMedium)
            Text("Записывайте вес утром натощак — или укажите его при завершении тренировки.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("${Format.weight(current.weightKg)} кг", style = MaterialTheme.typography.displaySmall, color = colors.accent)
                    Text("Последнее: " + Format.dateShort(Format.local(current.timestamp).toLocalDate()),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.delta30d?.let { delta ->
                    // Изменение массы нейтрально: рост не считается неудачей.
                    StatusPill(
                        when {
                            delta < -0.049 -> "−${Format.weight(abs(delta))} кг за 30 дней"
                            delta > 0.049 -> "+${Format.weight(delta)} кг за 30 дней"
                            else -> "без изменений"
                        },
                    )
                }
            }
            if (state.chartPoints.size >= 2) {
                LineChart(
                    points = state.chartPoints,
                    color = colors.accent,
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    minSpan = 2.0, // ±1 кг вокруг данных: динамика в 1–2 кг видна, шум в 100 г не растянут
                    valueFormatter = { Format.weight(it) },
                )
            }
        }
        FitButton("Записать вес", onAdd, Modifier.fillMaxWidth(), kind = FitButtonKind.Tonal, icon = Icons.Rounded.MonitorWeight)
    }
}

@Composable
private fun AddWeightDialog(onConfirm: (Double) -> Unit, onDismiss: () -> Unit) {
    var input by remember { mutableStateOf("") }
    val parsed = input.replace(',', '.').toDoubleOrNull()
    val valid = parsed != null && parsed > 0.0 && parsed < 500.0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новое взвешивание") },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Вес, кг") },
                singleLine = true,
                isError = input.isNotBlank() && !valid,
                supportingText = { Text("Больше 0 и меньше 500 кг") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        },
        confirmButton = { TextButton(onClick = { parsed?.let(onConfirm) }, enabled = valid) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** Статус замеров: когда мерили последний раз и как настроено напоминание. */
@Composable
private fun StatusCard(state: MeasurementsUiState, reminderEnabled: Boolean, reminderDays: Int, reminderHour: Int,
    onSettings: () -> Unit) {
    val colors = sectionColors()
    val due = state.reminderDue
    FitCard(
        color = if (due) MaterialTheme.colorScheme.errorContainer else colors.container,
        contentColor = if (due) MaterialTheme.colorScheme.onErrorContainer else colors.onContainer,
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 8.dp),
        spacing = 4.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(if (due) Icons.Rounded.NotificationsActive else Icons.Rounded.Straighten, contentDescription = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val last = state.lastDay
                val days = state.daysSinceLast
                Text(
                    text = when {
                        last == null -> "Замеров пока не было"
                        days == 0L -> "Замеры сегодня — отлично"
                        days == 1L -> "Последние замеры вчера"
                        else -> "Последние замеры $days ${MeasurementReminder.pluralDays(days ?: 0)} назад"
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = when {
                        due -> "Пора снять мерки"
                        !reminderEnabled -> "Напоминание выключено"
                        else -> "Напомним через $reminderDays ${MeasurementReminder.pluralDays(reminderDays.toLong())} без замеров, в $reminderHour:00"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        FitButton("Зоны и напоминание", onSettings, kind = FitButtonKind.Plain, compact = true, icon = Icons.Rounded.Tune)
    }
}

/** Карточка зоны: силуэт с подсвеченной зоной, значение в см, дельты и компактный график. Тап — история. */
@Composable
private fun MetricCard(metric: MetricUiState, onClick: () -> Unit) {
    val colors = sectionColors()
    FitCard(onClick = onClick, onClickLabel = "История замеров: ${metric.type.title}") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BodyZoneIcon(metric.type)
            Column(Modifier.weight(1f)) {
                Text(metric.type.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(metric.current?.let { Format.dateShort(LocalDate.ofEpochDay(it.epochDay)) } ?: metric.type.hint,
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val current = metric.current
        if (current == null) {
            Text("Ещё не измерялось", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${Format.weight(current.valueCm)} см", style = MaterialTheme.typography.displaySmall, color = colors.accent)
                Column(modifier = Modifier.padding(bottom = 6.dp)) {
                    metric.deltaPrev?.let { DeltaLine("с прошлого", it) }
                    metric.delta30d?.let { DeltaLine("за 30 дней", it) }
                }
            }
            if (metric.chartPoints.size >= 2) {
                LineChart(
                    points = metric.chartPoints,
                    color = colors.accent,
                    modifier = Modifier.fillMaxWidth().height(170.dp),
                    minSpan = 2.0,
                    valueFormatter = { Format.weight(it) },
                )
            } else {
                Text("График появится после второго замера", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** «−1,2 см с прошлого» — уменьшение зелёным, рост нейтральным (что «хорошо», зависит от цели). */
@Composable
private fun DeltaLine(label: String, delta: Double) {
    val sign = when {
        delta > 0.05 -> "+"
        delta < -0.05 -> "−"
        else -> "±"
    }
    Text(
        text = "$sign${Format.weight(abs(delta))} см $label",
        style = MaterialTheme.typography.labelMedium,
        color = if (delta < -0.05) fitAccents.workout else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Список замеров одной зоны с удалением. */
@Composable
internal fun MeasurementHistoryList(history: List<BodyMeasurement>, onDelete: (BodyMeasurement) -> Unit) {
    if (history.isEmpty()) {
        Text("Записей пока нет", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column {
        history.forEachIndexed { index, item ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(Format.dateWithDayOfWeek(LocalDate.ofEpochDay(item.epochDay)), style = MaterialTheme.typography.bodyMedium)
                    item.note?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text("${Format.weight(item.valueCm)} см", style = MaterialTheme.typography.titleMedium, color = sectionColors().accent)
                IconButton(onClick = { onDelete(item) }) {
                    Icon(Icons.Rounded.Delete, contentDescription = "Удалить замер", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (index != history.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
    Spacer(Modifier.height(8.dp))
}
