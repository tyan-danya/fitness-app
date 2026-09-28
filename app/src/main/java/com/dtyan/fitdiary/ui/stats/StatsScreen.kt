package com.dtyan.fitdiary.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dtyan.fitdiary.appContainer
import com.dtyan.fitdiary.data.db.Exercise
import com.dtyan.fitdiary.data.db.WorkoutSummary
import com.dtyan.fitdiary.ui.common.EmptyState
import com.dtyan.fitdiary.ui.common.Format
import com.dtyan.fitdiary.ui.common.IllustrationChart
import com.dtyan.fitdiary.ui.common.IllustrationDumbbell
import com.dtyan.fitdiary.ui.common.SectionHeader
import com.dtyan.fitdiary.ui.common.pressScale
import com.dtyan.fitdiary.ui.theme.fitAccents
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private val RU = Locale("ru")
private val DAY_MONTH = DateTimeFormatter.ofPattern("dd.MM")
private val MONTH_SHORT = DateTimeFormatter.ofPattern("LLL", RU)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onOpenDay: (Long) -> Unit,
    onOpenWorkout: (Long) -> Unit,
) {
    val context = LocalContext.current
    val container = context.appContainer
    val appContext = context.applicationContext
    val vm: StatsViewModel = viewModel {
        StatsViewModel(
            appContext = appContext,
            workoutRepository = container.workoutRepository,
            nutritionRepository = container.nutritionRepository,
            statsRepository = container.statsRepository,
            exerciseRepository = container.exerciseRepository,
            measurementRepository = container.measurementRepository,
        )
    }

    val month by vm.month.collectAsStateWithLifecycle()
    val calendar by vm.calendar.collectAsStateWithLifecycle()
    val weight by vm.weight.collectAsStateWithLifecycle()
    val weeklyVolume by vm.weeklyVolume.collectAsStateWithLifecycle()
    val exercises by vm.exercises.collectAsStateWithLifecycle()
    val selectedExercise by vm.selectedExercise.collectAsStateWithLifecycle()
    val exerciseProgress by vm.exerciseProgress.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val exporting by vm.exporting.collectAsStateWithLifecycle()

    var showExportDialog by remember { mutableStateOf(false) }
    var showWeightDialog by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.exportIntents.collect { intent ->
            showExportDialog = false
            try {
                context.startActivity(intent)
            } catch (_: android.content.ActivityNotFoundException) {
                snackbar.showSnackbar("Нет приложения для отправки файла")
            }
        }
    }
    LaunchedEffect(vm) { vm.errors.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        // Отступы системных панелей уже применяет внешний Scaffold в AppRoot — иначе они удвоятся
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text("Статистика") },
                windowInsets = WindowInsets(0.dp),
                actions = {
                    IconButton(onClick = { showExportDialog = true }) {
                        Icon(Icons.Filled.IosShare, contentDescription = "Экспорт данных")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "calendar") {
                Column {
                    SectionHeader(
                        icon = Icons.Filled.CalendarMonth,
                        title = "Календарь",
                        iconTint = fitAccents.stats,
                        iconContainer = fitAccents.statsContainer,
                    )
                    SectionCard(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp)) {
                        MonthCalendar(
                            month = month,
                            workoutDays = calendar.workoutDays,
                            mealDays = calendar.mealDays,
                            onPreviousMonth = vm::previousMonth,
                            onNextMonth = vm::nextMonth,
                            onDayClick = { date -> onOpenDay(date.toEpochDay()) },
                        )
                    }
                }
            }
            item(key = "weight") {
                Column {
                    SectionHeader(
                        icon = Icons.Filled.MonitorWeight,
                        title = "Вес",
                        iconTint = fitAccents.stats,
                        iconContainer = fitAccents.statsContainer,
                    )
                    SectionCard {
                        WeightSectionContent(
                            state = weight,
                            onAddClick = { showWeightDialog = true },
                        )
                    }
                }
            }
            item(key = "volume") {
                Column {
                    SectionHeader(
                        icon = Icons.Filled.FitnessCenter,
                        title = "Тоннаж по неделям",
                        iconTint = fitAccents.workout,
                        iconContainer = fitAccents.workoutContainer,
                    )
                    SectionCard {
                        BarChart(
                            bars = weeklyVolume.map { it.monday.format(DAY_MONTH) to it.volumeKg },
                            modifier = Modifier.fillMaxWidth().height(240.dp),
                            valueFormatter = { Format.volume(it) },
                        )
                    }
                }
            }
            item(key = "progress") {
                Column {
                    SectionHeader(
                        icon = Icons.AutoMirrored.Filled.TrendingUp,
                        title = "Прогресс упражнения",
                        iconTint = fitAccents.stats,
                        iconContainer = fitAccents.statsContainer,
                    )
                    SectionCard {
                        ExerciseProgressContent(
                            exercises = exercises,
                            selected = selectedExercise,
                            progress = exerciseProgress,
                            onSelect = vm::selectExercise,
                        )
                    }
                }
            }
            item(key = "history_header") {
                SectionHeader(
                    icon = Icons.Filled.History,
                    title = "История тренировок",
                    iconTint = fitAccents.stats,
                    iconContainer = fitAccents.statsContainer,
                )
            }
            if (history.isEmpty()) {
                item(key = "history_empty") {
                    SectionCard {
                        EmptyState(
                            title = "Пока нет завершённых тренировок",
                            subtitle = "Завершите первую тренировку — она появится здесь",
                            illustration = { IllustrationDumbbell() },
                        )
                    }
                }
            } else {
                items(history, key = { it.id }) { summary ->
                    WorkoutSummaryCard(summary = summary, onClick = { onOpenWorkout(summary.id) })
                }
            }
        }
    }

    if (showExportDialog) {
        ExportDialog(
            exporting = exporting,
            onExport = vm::export,
            onDismiss = { if (!exporting) showExportDialog = false },
        )
    }
    if (showWeightDialog) {
        AddWeightDialog(
            onConfirm = { kg ->
                vm.addWeight(kg)
                showWeightDialog = false
            },
            onDismiss = { showWeightDialog = false },
        )
    }
}

/** Карточка секции: крупное скругление, спокойная поверхность, без elevation. */
@Composable
private fun SectionCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

// ---------- Экспорт ----------

@Composable
private fun ExportDialog(
    exporting: Boolean,
    onExport: (ExportFormat) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Экспорт данных") },
        text = {
            if (exporting) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Text("Готовим файлы…")
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ExportOptionCard(
                        icon = Icons.Filled.Description,
                        iconTint = fitAccents.stats,
                        iconContainer = fitAccents.statsContainer,
                        title = "JSON",
                        subtitle = "полный экспорт для анализа",
                        onClick = { onExport(ExportFormat.JSON) },
                    )
                    ExportOptionCard(
                        icon = Icons.Filled.TableChart,
                        iconTint = fitAccents.workout,
                        iconContainer = fitAccents.workoutContainer,
                        title = "CSV",
                        subtitle = "таблицы для Excel",
                        onClick = { onExport(ExportFormat.CSV) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !exporting) { Text("Отмена") }
        },
    )
}

/** Большая кликабельная карточка-вариант формата экспорта: иконка в тонированной плашке. */
@Composable
private fun ExportOptionCard(
    icon: ImageVector,
    iconTint: Color,
    iconContainer: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().pressScale(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = iconContainer,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier
                        .padding(8.dp)
                        .size(22.dp),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------- Вес ----------

@Composable
private fun WeightSectionContent(
    state: WeightUiState,
    onAddClick: () -> Unit,
) {
    val current = state.current
    if (current == null) {
        EmptyState(
            title = "Пока нет замеров",
            subtitle = "Добавьте первое взвешивание",
            illustration = { IllustrationChart() },
        )
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "${Format.weight(current.weightKg)} кг",
                style = MaterialTheme.typography.displaySmall,
            )
            val delta = state.delta30d
            if (delta != null) WeightDeltaChip(delta)
        }
        if (state.delta30d != null) {
            Text(
                "изменение за 30 дней",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(16.dp))
        LineChart(
            points = state.chartPoints,
            color = fitAccents.stats,
            modifier = Modifier.fillMaxWidth().height(230.dp),
            minSpan = 2.0, // ±1 кг вокруг данных: динамика в 1–2 кг видна, шум в 100 г не растянут
            valueFormatter = { Format.weight(it) },
        )
    }
    Spacer(Modifier.height(16.dp))
    FilledTonalButton(
        onClick = onAddClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = fitAccents.statsContainer,
            contentColor = fitAccents.onStatsContainer,
        ),
    ) {
        Text("Добавить взвешивание")
    }
}

/** Changes in body mass are neutral: an increase is not inherently a failure. */
@Composable
private fun WeightDeltaChip(delta: Double) {
    val accents = fitAccents
    val (text, container, content) = when {
        delta < -0.049 -> Triple(
            "↓ ${Format.weight(abs(delta))} кг",
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        delta > 0.049 -> Triple(
            "↑ ${Format.weight(delta)} кг",
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> Triple(
            "без изменений",
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Surface(shape = CircleShape, color = container, contentColor = content) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun AddWeightDialog(
    onConfirm: (Double) -> Unit,
    onDismiss: () -> Unit,
) {
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
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.let(onConfirm) },
                enabled = valid,
            ) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

// ---------- Прогресс упражнения ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExerciseProgressContent(
    exercises: List<Exercise>,
    selected: Exercise?,
    progress: ExerciseProgressUiState?,
    onSelect: (Exercise) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selected?.name ?: "Выберите упражнение",
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            exercises.forEach { exercise ->
                DropdownMenuItem(
                    text = { Text(exercise.name) },
                    onClick = {
                        onSelect(exercise)
                        expanded = false
                    },
                )
            }
        }
    }

    if (selected != null) {
        Spacer(Modifier.height(16.dp))
        val state = progress
        when {
            state == null -> CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = fitAccents.stats,
            )
            state.points.isEmpty() -> EmptyState(
                title = "Нет данных по этому упражнению",
                subtitle = "Запишите подходы — график появится здесь",
                illustration = { IllustrationChart() },
            )
            else -> {
                LineChart(
                    points = state.points,
                    color = fitAccents.workout,
                    modifier = Modifier.fillMaxWidth().height(230.dp),
                    minSpan = 10.0,
                    valueFormatter = { Format.weight(it) },
                )
                if (state.recordWeightKg != null && state.recordReps != null) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.EmojiEvents,
                            contentDescription = null,
                            tint = fitAccents.gold,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            "Рекорд: ${Format.weight(state.recordWeightKg)} кг × ${state.recordReps}",
                            style = MaterialTheme.typography.titleSmall,
                            color = fitAccents.gold,
                        )
                    }
                }
            }
        }
    }
}

// ---------- История ----------

/** Русские формы множественного числа: plural(3, «подход», «подхода», «подходов»). */
internal fun plural(n: Int, one: String, few: String, many: String): String {
    val mod10 = n % 10
    val mod100 = n % 100
    return when {
        mod100 in 11..14 -> many
        mod10 == 1 -> one
        mod10 in 2..4 -> few
        else -> many
    }
}

/** Мини-строка тренировки: дата-бейдж, состав, длительность и тоннаж. */
@Composable
internal fun WorkoutSummaryCard(
    summary: WorkoutSummary,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().pressScale(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val date = Instant.ofEpochMilli(summary.startedAt)
                .atZone(ZoneId.systemDefault()).toLocalDate()
            DateBadge(date)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "${summary.exerciseCount} " +
                        plural(summary.exerciseCount, "упражнение", "упражнения", "упражнений") +
                        " · ${summary.setCount} " +
                        plural(summary.setCount, "подход", "подхода", "подходов"),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val duration = summary.endedAt?.let { Format.durationHuman(it - summary.startedAt) }
                if (duration != null) {
                    Text(
                        text = duration,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = Format.volume(summary.totalVolume),
                style = MaterialTheme.typography.titleMedium,
                color = fitAccents.stats,
            )
        }
    }
}

/** Квадратный бейдж даты: крупный день + короткий месяц на statsContainer. */
@Composable
private fun DateBadge(date: LocalDate) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = fitAccents.statsContainer,
        contentColor = fitAccents.onStatsContainer,
    ) {
        Column(
            modifier = Modifier.width(56.dp).padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = date.format(MONTH_SHORT).replace(".", ""),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
