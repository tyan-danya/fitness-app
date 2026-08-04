package com.dtyan.fitdiary.ui.nutrition

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dtyan.fitdiary.appContainer
import com.dtyan.fitdiary.data.SettingsStore
import com.dtyan.fitdiary.data.db.Meal
import com.dtyan.fitdiary.ui.common.EmptyState
import com.dtyan.fitdiary.ui.common.Format
import com.dtyan.fitdiary.ui.common.IllustrationSalad
import com.dtyan.fitdiary.ui.common.ProgressRing
import com.dtyan.fitdiary.ui.common.SectionHeader
import com.dtyan.fitdiary.ui.common.pressScale
import com.dtyan.fitdiary.ui.theme.fitAccents
import java.time.LocalDate
import java.util.Locale

/** Экран «Питание»: дневник КБЖУ по дням — итог дня, список приёмов, добавление и цели. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NutritionScreen() {
    val container = LocalContext.current.appContainer
    val vm: NutritionViewModel = viewModel {
        NutritionViewModel(container.nutritionRepository, container.settings)
    }

    val selectedDay by vm.selectedDay.collectAsStateWithLifecycle()
    val meals by vm.meals.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val editor by vm.editor.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()

    var goalsDialogOpen by rememberSaveable { mutableStateOf(false) }
    // id приёма, для которого открыт диалог удаления; -1 — диалог закрыт
    var deleteMealId by rememberSaveable { mutableStateOf(-1L) }

    Scaffold(
        // Отступы системных панелей уже применяет внешний Scaffold в AppRoot — иначе они удвоятся
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text("Питание") },
                windowInsets = WindowInsets(0.dp),
                actions = {
                    IconButton(onClick = { goalsDialogOpen = true }) {
                        Icon(Icons.Filled.Tune, contentDescription = "Цели питания")
                    }
                },
            )
        },
        floatingActionButton = {
            // onSecondary — гарантированный контраст к оранжевому акценту в обеих темах
            ExtendedFloatingActionButton(
                onClick = vm::openAddEditor,
                modifier = Modifier.pressScale(),
                containerColor = fitAccents.nutrition,
                contentColor = MaterialTheme.colorScheme.onSecondary,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Добавить") },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            DaySwitcher(
                day = selectedDay,
                onPrevious = { vm.shiftDay(-1) },
                onNext = { vm.shiftDay(1) },
                onBackToToday = vm::resetToToday,
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "summary") {
                    DaySummaryCard(meals = meals, settings = settings)
                }
                item(key = "meals-header") {
                    SectionHeader(
                        icon = Icons.Filled.Restaurant,
                        title = "Приёмы пищи",
                        iconTint = fitAccents.nutrition,
                        iconContainer = fitAccents.nutritionContainer,
                    )
                }
                if (meals.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(
                            title = "Ещё ничего не съедено",
                            subtitle = "Добавьте первый приём — это займёт секунд десять",
                            illustration = { IllustrationSalad() },
                        )
                    }
                } else {
                    items(meals, key = { it.id }) { meal ->
                        MealCard(
                            meal = meal,
                            onClick = { vm.openEditEditor(meal) },
                            onDeleteClick = { deleteMealId = meal.id },
                        )
                    }
                }
            }
        }
    }

    // Подтверждение удаления; если приём исчез из списка (сменился день) — диалог закроется сам
    val mealToDelete = meals.find { it.id == deleteMealId }
    if (mealToDelete != null) {
        AlertDialog(
            onDismissRequest = { deleteMealId = -1L },
            title = { Text("Удалить приём пищи?") },
            text = { Text("«${mealToDelete.name}» будет удалён.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteMeal(mealToDelete)
                        deleteMealId = -1L
                    },
                ) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteMealId = -1L }) { Text("Отмена") }
            },
        )
    }

    if (goalsDialogOpen) {
        GoalsDialog(
            current = settings,
            onDismiss = { goalsDialogOpen = false },
            onSave = { calories, protein, fat, carbs ->
                vm.updateGoals(calories, protein, fat, carbs)
                goalsDialogOpen = false
            },
        )
    }

    editor?.let { state ->
        MealEditorSheet(
            state = state,
            recent = recent,
            onNameChange = vm::onEditorNameChange,
            onCaloriesChange = vm::onEditorCaloriesChange,
            onProteinChange = vm::onEditorProteinChange,
            onFatChange = vm::onEditorFatChange,
            onCarbsChange = vm::onEditorCarbsChange,
            onApplyRecent = vm::applyRecent,
            onSave = vm::saveEditor,
            onDismiss = vm::closeEditor,
        )
    }
}

/**
 * Переключатель дня: круглые стрелки по бокам, по центру дата-«пилюля»
 * в цвете раздела. Тап по дате — возврат к сегодняшнему дню.
 */
@Composable
private fun DaySwitcher(
    day: LocalDate,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onBackToToday: () -> Unit,
) {
    val today = LocalDate.now()
    val label = when (day) {
        today -> "Сегодня"
        today.minusDays(1) -> "Вчера"
        else -> Format.dateWithDayOfWeek(day)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            IconButton(onClick = onPrevious) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = "Предыдущий день")
            }
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.pressScale(),
                shape = CircleShape,
                color = fitAccents.nutritionContainer,
                contentColor = fitAccents.onNutritionContainer,
            ) {
                Text(
                    text = label,
                    modifier = Modifier
                        .clickable(onClickLabel = "Вернуться к сегодняшнему дню", onClick = onBackToToday)
                        .padding(horizontal = 24.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
            }
        }
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            IconButton(onClick = onNext) {
                Icon(Icons.Filled.ChevronRight, contentDescription = "Следующий день")
            }
        }
    }
}

/**
 * Герой экрана — «Итог дня»: слева кольцо калорий с числом-героем,
 * справа три полоски макронутриентов (Б/Ж/У) в фирменных цветах.
 */
@Composable
private fun DaySummaryCard(meals: List<Meal>, settings: SettingsStore.Settings) {
    val calories = meals.sumOf { it.calories }
    val protein = meals.sumOf { it.proteinG }
    val fat = meals.sumOf { it.fatG }
    val carbs = meals.sumOf { it.carbsG }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CalorieRing(calories = calories, goal = settings.calorieGoal)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                MacroBar("Белки", protein, settings.proteinGoalG, fitAccents.protein)
                MacroBar("Жиры", fat, settings.fatGoalG, fitAccents.fat)
                MacroBar("Углеводы", carbs, settings.carbGoalG, fitAccents.carbs)
            }
        }
    }
}

/** Кольцо калорий: съеденное — число-герой, под ним цель. При переборе кольцо краснеет. */
@Composable
private fun CalorieRing(calories: Int, goal: Int) {
    val overGoal = calories > goal
    // Число «догоняет» новое значение — живой счётчик при добавлении приёмов
    val animatedCalories by animateIntAsState(targetValue = calories, label = "kcalValue")
    ProgressRing(
        progress = calories.toFloat() / goal.coerceAtLeast(1),
        ringSize = 140.dp,
        color = if (overGoal) MaterialTheme.colorScheme.error else fitAccents.nutrition,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = formatKcal(animatedCalories),
                style = MaterialTheme.typography.displaySmall,
                maxLines = 1,
            )
            Text(
                text = "из ${formatKcal(goal)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Полоска одного макронутриента: цветная точка + «Белки … 120/150 г» + тонкий прогресс своим цветом. */
@Composable
private fun MacroBar(
    label: String,
    valueG: Double,
    goalG: Int,
    barColor: Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Точка-индикатор в цвете макроса — визуально связывает подпись с полоской ниже
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(barColor),
            )
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${Format.weight(valueG)}/$goalG г",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
            )
        }
        val fraction by animateFloatAsState(
            targetValue = (valueG / goalG.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f),
            label = "macroProgress",
        )
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape),
            color = if (valueG > goalG) MaterialTheme.colorScheme.error else barColor,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}

/** Карточка приёма пищи: бейдж времени, название, калории цветом раздела, корзина удаления. */
@Composable
private fun MealCard(
    meal: Meal,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 4.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = fitAccents.nutritionContainer,
                contentColor = fitAccents.onNutritionContainer,
            ) {
                Text(
                    text = Format.time(meal.timestamp),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = meal.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "${formatKcal(meal.calories)} ккал",
                        modifier = Modifier.alignByBaseline(),
                        style = MaterialTheme.typography.titleSmall,
                        color = fitAccents.nutrition,
                    )
                    Text(
                        text = "Б ${Format.weight(meal.proteinG)} · Ж ${Format.weight(meal.fatG)}" +
                            " · У ${Format.weight(meal.carbsG)}",
                        modifier = Modifier.alignByBaseline(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onDeleteClick) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "Удалить приём пищи",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val RU_LOCALE = Locale("ru")

/** «1 850» — разряды через пробел, как принято в русской типографике. */
private fun formatKcal(value: Int): String = String.format(RU_LOCALE, "%,d", value)
