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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dtyan.fitdiary.appContainer
import com.dtyan.fitdiary.data.db.BodyMeasurement
import com.dtyan.fitdiary.reminder.MeasurementReminder
import com.dtyan.fitdiary.ui.common.EmptyState
import com.dtyan.fitdiary.ui.common.Format
import com.dtyan.fitdiary.ui.common.IllustrationChart
import com.dtyan.fitdiary.ui.common.SectionHeader
import com.dtyan.fitdiary.ui.common.pressScale
import com.dtyan.fitdiary.ui.stats.LineChart
import com.dtyan.fitdiary.ui.theme.fitAccents
import java.time.LocalDate

/**
 * Экран «Замеры»: статус (когда мерили последний раз), карточки отслеживаемых зон
 * с текущим значением, динамикой и графиком, запись новой сессии замеров,
 * выбор зон и настройка напоминания.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeasurementsScreen() {
    val context = LocalContext.current
    val container = context.appContainer
    val vm: MeasurementsViewModel = viewModel {
        MeasurementsViewModel(container.measurementRepository, container.settings)
    }

    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val editor by vm.editor.collectAsStateWithLifecycle()
    val historyType by vm.historyType.collectAsStateWithLifecycle()

    var settingsOpen by rememberSaveable { mutableStateOf(false) }

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
            TopAppBar(
                title = { Text("Замеры") },
                windowInsets = WindowInsets(0.dp),
                actions = {
                    IconButton(onClick = { settingsOpen = true }) {
                        Icon(Icons.Filled.Tune, contentDescription = "Зоны и напоминание")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = vm::openEditor,
                modifier = Modifier.pressScale(),
                containerColor = fitAccents.measure,
                contentColor = Color.White,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Записать замеры") },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "status") {
                StatusCard(state = state, reminderDays = settings.measurementReminderDays, onMeasure = vm::openEditor)
            }
            item(key = "header") {
                SectionHeader(
                    icon = Icons.Filled.Straighten,
                    title = "Отслеживаемые зоны",
                    iconTint = fitAccents.measure,
                    iconContainer = fitAccents.measureContainer,
                )
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
}

/** Карточка статуса: когда мерили последний раз; при «тишине» дольше порога — предупреждение. */
@Composable
private fun StatusCard(state: MeasurementsUiState, reminderDays: Int, onMeasure: () -> Unit) {
    val accents = fitAccents
    val due = state.reminderDue
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (due) MaterialTheme.colorScheme.errorContainer else accents.measureContainer,
        contentColor = if (due) MaterialTheme.colorScheme.onErrorContainer else accents.onMeasureContainer,
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (due) Icons.Filled.NotificationsActive else Icons.Filled.Straighten,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val last = state.lastDay
                val days = state.daysSinceLast
                Text(
                    text = when {
                        last == null -> "Замеров пока не было"
                        days == 0L -> "Замеры сегодня — отлично"
                        days == 1L -> "Последние замеры вчера"
                        else -> "Последние замеры $days ${MeasurementReminder.pluralDays(days ?: 0)} назад"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = when {
                        due -> "Пора снять мерки: напоминание срабатывает после $reminderDays ${MeasurementReminder.pluralDays(reminderDays.toLong())} без замеров"
                        last != null -> Format.dateWithDayOfWeek(last)
                        else -> "Нажмите «Записать замеры», чтобы начать"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Карточка зоны: значение-герой в см, дельты, компактный график. Тап — история. */
@Composable
private fun MetricCard(metric: MetricUiState, onClick: () -> Unit) {
    val accents = fitAccents
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().pressScale(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = RoundedCornerShape(9.dp), color = accents.measureContainer) {
                    Text(metric.type.emoji, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
                }
                Text(
                    text = metric.type.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                metric.current?.let {
                    Text(
                        text = Format.dateShort(LocalDate.ofEpochDay(it.epochDay)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val current = metric.current
            if (current == null) {
                Text(
                    text = "Ещё не измерялось · ${metric.type.hint}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "${Format.weight(current.valueCm)} см",
                        style = MaterialTheme.typography.displaySmall,
                        color = accents.measure,
                    )
                    Column(modifier = Modifier.padding(bottom = 6.dp)) {
                        metric.deltaPrev?.let { DeltaLine("с прошлого", it) }
                        metric.delta30d?.let { DeltaLine("за 30 дней", it) }
                    }
                }
                if (metric.chartPoints.size >= 2) {
                    LineChart(
                        points = metric.chartPoints,
                        color = accents.measure,
                        modifier = Modifier.fillMaxWidth().height(170.dp),
                        minSpan = 2.0,
                        valueFormatter = { Format.weight(it) },
                    )
                } else {
                    Text(
                        text = "График появится после второго замера",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
    val magnitude = Format.weight(kotlin.math.abs(delta))
    Text(
        text = "$sign$magnitude см $label",
        style = MaterialTheme.typography.labelMedium,
        color = when {
            delta < -0.05 -> fitAccents.workout
            delta > 0.05 -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

/** Список замеров одной зоны с удалением. */
@Composable
internal fun MeasurementHistoryList(history: List<BodyMeasurement>, onDelete: (BodyMeasurement) -> Unit) {
    if (history.isEmpty()) {
        Text(
            "Записей пока нет",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
                    Text(
                        text = Format.dateWithDayOfWeek(LocalDate.ofEpochDay(item.epochDay)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    item.note?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    text = "${Format.weight(item.valueCm)} см",
                    style = MaterialTheme.typography.titleMedium,
                    color = fitAccents.measure,
                )
                IconButton(onClick = { onDelete(item) }) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Удалить замер", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (index != history.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
    Spacer(Modifier.height(8.dp))
}
