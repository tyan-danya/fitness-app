package com.dtyan.fitdiary.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dtyan.fitdiary.appContainer
import com.dtyan.fitdiary.data.db.Meal
import com.dtyan.fitdiary.ui.common.EmptyState
import com.dtyan.fitdiary.ui.common.Format
import com.dtyan.fitdiary.ui.common.IllustrationDumbbell
import com.dtyan.fitdiary.ui.common.IllustrationSalad
import com.dtyan.fitdiary.ui.common.SectionHeader
import com.dtyan.fitdiary.ui.theme.fitAccents
import java.time.LocalDate
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayDetailsScreen(
    epochDay: Long,
    onOpenWorkout: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val container = LocalContext.current.appContainer
    val vm: DayDetailsViewModel = viewModel {
        DayDetailsViewModel(
            epochDay = epochDay,
            workoutRepository = container.workoutRepository,
            nutritionRepository = container.nutritionRepository,
        )
    }
    val workouts by vm.workouts.collectAsStateWithLifecycle()
    val meals by vm.meals.collectAsStateWithLifecycle()

    Scaffold(
        // Отступы системных панелей уже применяет внешний Scaffold в AppRoot — иначе они удвоятся
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(Format.dateWithDayOfWeek(LocalDate.ofEpochDay(epochDay))) },
                windowInsets = WindowInsets(0.dp),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
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
            item(key = "workouts_header") {
                SectionHeader(
                    icon = Icons.Filled.FitnessCenter,
                    title = "Тренировки",
                    iconTint = fitAccents.workout,
                    iconContainer = fitAccents.workoutContainer,
                )
            }
            if (workouts.isEmpty()) {
                item(key = "workouts_empty") {
                    SectionSurface {
                        EmptyState(
                            title = "Тренировок не было",
                            subtitle = null,
                            illustration = { IllustrationDumbbell(size = 96.dp) },
                        )
                    }
                }
            } else {
                items(workouts, key = { it.id }) { summary ->
                    WorkoutSummaryCard(summary = summary, onClick = { onOpenWorkout(summary.id) })
                }
            }

            item(key = "meals_header") {
                SectionHeader(
                    icon = Icons.Filled.Restaurant,
                    title = "Питание",
                    iconTint = fitAccents.nutrition,
                    iconContainer = fitAccents.nutritionContainer,
                )
            }
            if (meals.isEmpty()) {
                item(key = "meals_empty") {
                    SectionSurface {
                        EmptyState(
                            title = "Записей о питании нет",
                            subtitle = null,
                            illustration = { IllustrationSalad(size = 96.dp) },
                        )
                    }
                }
            } else {
                item(key = "meals") { MealsCard(meals = meals) }
            }
        }
    }
}

/** Карточка секции: крупное скругление, спокойная поверхность, без elevation. */
@Composable
private fun SectionSurface(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        content()
    }
}

/** Приёмы пищи дня: бейдж времени, КБЖУ и итоговая строка-резюме. */
@Composable
private fun MealsCard(meals: List<Meal>) {
    val accents = fitAccents
    SectionSurface {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            meals.forEach { meal ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = accents.nutritionContainer,
                        contentColor = accents.onNutritionContainer,
                    ) {
                        Text(
                            text = Format.time(meal.timestamp),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(meal.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "${meal.calories} ккал · Б ${Format.weight(meal.proteinG)} г · " +
                                "Ж ${Format.weight(meal.fatG)} г · У ${Format.weight(meal.carbsG)} г",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            // Итог дня по КБЖУ
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Итого: ${formatKcal(meals.sumOf { it.calories })} ккал",
                    style = MaterialTheme.typography.titleMedium,
                    color = accents.nutrition,
                )
                Text(
                    text = "Б ${Format.weight(meals.sumOf { it.proteinG })} г · " +
                        "Ж ${Format.weight(meals.sumOf { it.fatG })} г · " +
                        "У ${Format.weight(meals.sumOf { it.carbsG })} г",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val RU = Locale("ru")

/** «1 850» — разряды через пробел, как принято в русской типографике. */
private fun formatKcal(value: Int): String = String.format(RU, "%,d", value)
