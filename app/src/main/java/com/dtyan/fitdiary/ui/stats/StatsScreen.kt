package com.dtyan.fitdiary.ui.stats

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dtyan.fitdiary.appContainer
import com.dtyan.fitdiary.data.db.Exercise
import com.dtyan.fitdiary.ui.common.EmptyState
import com.dtyan.fitdiary.ui.common.FitCard
import com.dtyan.fitdiary.ui.common.FitDimens
import com.dtyan.fitdiary.ui.common.FitSection
import com.dtyan.fitdiary.ui.common.FitSectionScope
import com.dtyan.fitdiary.ui.common.Format
import com.dtyan.fitdiary.ui.common.IllustrationChart
import com.dtyan.fitdiary.ui.common.IllustrationDumbbell
import com.dtyan.fitdiary.ui.common.SectionHeader
import com.dtyan.fitdiary.ui.common.TabHeader
import com.dtyan.fitdiary.ui.common.WorkoutSummaryCard
import com.dtyan.fitdiary.ui.theme.fitAccents
import java.time.format.DateTimeFormatter

private val DAY_MONTH = DateTimeFormatter.ofPattern("dd.MM")

/** Вкладка «Статистика»: календарь, тоннаж по неделям, прогресс упражнения и история тренировок. */
@Composable
fun StatsScreen(
    onOpenDay: (Long) -> Unit,
    onOpenWorkout: (Long) -> Unit,
) = FitSectionScope(FitSection.STATS) {
    val container = LocalContext.current.appContainer
    val vm: StatsViewModel = viewModel {
        StatsViewModel(
            workoutRepository = container.workoutRepository,
            nutritionRepository = container.nutritionRepository,
            statsRepository = container.statsRepository,
            exerciseRepository = container.exerciseRepository,
        )
    }

    val month by vm.month.collectAsStateWithLifecycle()
    val calendar by vm.calendar.collectAsStateWithLifecycle()
    val weeklyVolume by vm.weeklyVolume.collectAsStateWithLifecycle()
    val exercises by vm.exercises.collectAsStateWithLifecycle()
    val favoriteIds by vm.favoriteIds.collectAsStateWithLifecycle()
    val selectedExercise by vm.selectedExercise.collectAsStateWithLifecycle()
    val exerciseProgress by vm.exerciseProgress.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()

    Scaffold(
        // Отступы системных панелей уже применяет внешний Scaffold в AppRoot — иначе они удвоятся
        contentWindowInsets = WindowInsets(0.dp),
        topBar = { TabHeader(title = "Статистика") },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(start = FitDimens.ScreenPadding, end = FitDimens.ScreenPadding, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(FitDimens.ItemGap),
        ) {
            item(key = "calendar") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader(icon = Icons.Rounded.CalendarMonth, title = "Календарь")
                    FitCard(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp)) {
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
            item(key = "volume") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader(icon = Icons.Rounded.FitnessCenter, title = "Тоннаж по неделям",
                        iconTint = fitAccents.workout, iconContainer = fitAccents.workoutContainer)
                    FitCard(contentPadding = PaddingValues(20.dp)) {
                        BarChart(
                            bars = weeklyVolume.map { it.monday.format(DAY_MONTH) to it.volumeKg },
                            modifier = Modifier.fillMaxWidth().height(240.dp),
                            valueFormatter = { Format.volume(it) },
                        )
                    }
                }
            }
            item(key = "progress") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader(icon = Icons.AutoMirrored.Rounded.TrendingUp, title = "Прогресс упражнения")
                    FitCard(contentPadding = PaddingValues(20.dp)) {
                        ExerciseProgressContent(
                            exercises = exercises,
                            favoriteIds = favoriteIds,
                            selected = selectedExercise,
                            progress = exerciseProgress,
                            onSelect = vm::selectExercise,
                        )
                    }
                }
            }
            item(key = "history_header") {
                SectionHeader(icon = Icons.Rounded.History, title = "История тренировок")
            }
            if (history.isEmpty()) {
                item(key = "history_empty") {
                    FitCard {
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
}

// ---------- Прогресс упражнения ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExerciseProgressContent(
    exercises: List<Exercise>,
    favoriteIds: Set<Long>,
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
                    leadingIcon = if (exercise.id in favoriteIds) ({
                        Icon(Icons.Rounded.Star, contentDescription = "Избранное", tint = fitAccents.gold)
                    }) else null,
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
                            imageVector = Icons.Rounded.EmojiEvents,
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
