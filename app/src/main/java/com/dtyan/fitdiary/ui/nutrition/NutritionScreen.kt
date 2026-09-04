package com.dtyan.fitdiary.ui.nutrition

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dtyan.fitdiary.appContainer
import com.dtyan.fitdiary.data.SettingsStore
import com.dtyan.fitdiary.data.db.Meal
import com.dtyan.fitdiary.domain.MealType
import com.dtyan.fitdiary.ui.common.Format
import com.dtyan.fitdiary.ui.common.ProgressRing
import com.dtyan.fitdiary.ui.common.pressScale
import com.dtyan.fitdiary.ui.theme.fitAccents
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private const val KEY_MEAL = "meal-"
private const val KEY_HEADER = "header-"
private const val KEY_EMPTY = "empty-"

/** Карточка, которую тащат между секциями: что тащим, где схватили и где сейчас палец (px, координаты списка). */
private data class DragState(
    val meal: Meal,
    val grabOffsetY: Float,
    val fingerY: Float,
)

/** Экран «Питание»: дневник КБЖУ по дням — итог дня, приёмы по группам (завтрак/обед/ужин/перекус), добавление и цели. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NutritionScreen() {
    val context = LocalContext.current
    val container = context.appContainer
    val vm: NutritionViewModel = viewModel {
        NutritionViewModel(
            repo = container.nutritionRepository,
            settingsStore = container.settings,
            estimator = container.nutritionEstimator,
            exchange = container.estimateExchange,
        )
    }

    val selectedDay by vm.selectedDay.collectAsStateWithLifecycle()
    val meals by vm.meals.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val editor by vm.editor.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val pending by vm.pendingEstimates.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()

    var goalsDialogOpen by rememberSaveable { mutableStateOf(false) }
    var aiDialogOpen by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    // id приёма, для которого открыт диалог удаления; -1 — диалог закрыт
    var deleteMealId by rememberSaveable { mutableStateOf(-1L) }

    val snackbar = remember { SnackbarHostState() }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importEstimates(uri)
    }
    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is NutritionEvent.Share -> context.startActivity(event.intent)
                is NutritionEvent.Message -> snackbar.showSnackbar(event.text)
            }
        }
    }

    Scaffold(
        // Отступы системных панелей уже применяет внешний Scaffold в AppRoot — иначе они удвоятся
        contentWindowInsets = WindowInsets(0.dp),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Питание") },
                windowInsets = WindowInsets(0.dp),
                actions = {
                    IconButton(onClick = { goalsDialogOpen = true }) {
                        Icon(Icons.Filled.Tune, contentDescription = "Цели питания")
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Ещё")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Расчёт через ИИ…") },
                            leadingIcon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null) },
                            onClick = { menuOpen = false; aiDialogOpen = true },
                        )
                        DropdownMenuItem(
                            text = { Text(if (pending > 0) "Выгрузить для расчёта ($pending)" else "Выгрузить для расчёта") },
                            leadingIcon = { Icon(Icons.Filled.IosShare, contentDescription = null) },
                            enabled = !busy,
                            onClick = { menuOpen = false; vm.exportPendingEstimates() },
                        )
                        DropdownMenuItem(
                            text = { Text("Загрузить расчёт из файла") },
                            leadingIcon = { Icon(Icons.Filled.FileDownload, contentDescription = null) },
                            enabled = !busy,
                            onClick = { menuOpen = false; importLauncher.launch(arrayOf("*/*")) },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            // onSecondary — гарантированный контраст к оранжевому акценту в обеих темах
            ExtendedFloatingActionButton(
                onClick = { vm.openAddEditor() },
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
            MealList(
                meals = meals,
                settings = settings,
                onAdd = { type -> vm.openAddEditor(type) },
                onEdit = { vm.openEditEditor(it) },
                onDelete = { deleteMealId = it.id },
                onMove = { meal, type -> vm.moveMeal(meal, type) },
            )
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

    if (aiDialogOpen) {
        AiSettingsDialog(
            current = settings,
            onDismiss = { aiDialogOpen = false },
            onSave = { baseUrl, model, apiKey ->
                vm.updateAiSettings(baseUrl, model, apiKey)
                aiDialogOpen = false
            },
        )
    }

    editor?.let { state ->
        MealEditorSheet(
            state = state,
            recent = recent,
            aiConfigured = settings.aiConfigured,
            actions = MealEditorActions(
                onNameChange = vm::onEditorNameChange,
                onMealTypeChange = vm::onEditorMealTypeChange,
                onServingChange = vm::onEditorServingChange,
                onBasisChange = vm::onEditorBasisChange,
                onCaloriesChange = vm::onEditorCaloriesChange,
                onProteinChange = vm::onEditorProteinChange,
                onFatChange = vm::onEditorFatChange,
                onCarbsChange = vm::onEditorCarbsChange,
                onEstimateLaterChange = vm::onEditorEstimateLaterChange,
                onEstimateAi = vm::estimateWithAi,
                onOpenAiSettings = { aiDialogOpen = true },
                onApplyRecent = vm::applyRecent,
                onSave = vm::saveEditor,
                onDismiss = vm::closeEditor,
            ),
        )
    }
}

/**
 * Список дня: итог + четыре секции по типу приёма. Долгое нажатие на карточку начинает
 * перетаскивание; отпустили над другой секцией — приём переносится в неё.
 * Жест ловится на самом списке (не на карточке), поэтому карточка может уезжать за экран
 * при автопрокрутке, а перетаскиваемая копия рисуется поверх списка под пальцем.
 */
@Composable
private fun MealList(
    meals: List<Meal>,
    settings: SettingsStore.Settings,
    onAdd: (MealType) -> Unit,
    onEdit: (Meal) -> Unit,
    onDelete: (Meal) -> Unit,
    onMove: (Meal, MealType) -> Unit,
) {
    val sections = remember(meals) { groupMeals(meals) }
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    val currentMeals by rememberUpdatedState(meals)
    val currentOnMove by rememberUpdatedState(onMove)

    var drag by remember { mutableStateOf<DragState?>(null) }
    var containerHeight by remember { mutableIntStateOf(0) }
    val edgePx = with(LocalDensity.current) { 72.dp.toPx() }

    // Тип секции по ключу элемента списка (заголовок, заглушка или карточка приёма)
    fun sectionOf(key: Any?, mealsNow: List<Meal>): MealType? {
        val k = key as? String ?: return null
        return when {
            k.startsWith(KEY_HEADER) -> MealType.valueOf(k.removePrefix(KEY_HEADER))
            k.startsWith(KEY_EMPTY) -> MealType.valueOf(k.removePrefix(KEY_EMPTY))
            k.startsWith(KEY_MEAL) -> {
                val id = k.removePrefix(KEY_MEAL).toLongOrNull()
                mealsNow.firstOrNull { it.id == id }?.mealType
            }
            else -> null
        }
    }

    // Секция под пальцем: попали в элемент — его секция, иначе ближайший элемент секций
    fun targetFor(fingerY: Float, mealsNow: List<Meal>): MealType? {
        val info = listState.layoutInfo
        val y = fingerY + info.viewportStartOffset
        val sectionItems = info.visibleItemsInfo.filter { sectionOf(it.key, mealsNow) != null }
        val hit: LazyListItemInfo? = sectionItems.firstOrNull { y >= it.offset && y < it.offset + it.size }
            ?: sectionItems.minByOrNull { abs(it.offset + it.size / 2f - y) }
        return sectionOf(hit?.key, mealsNow)
    }

    fun itemAt(y: Float): LazyListItemInfo? {
        val info = listState.layoutInfo
        val listY = y + info.viewportStartOffset
        return info.visibleItemsInfo.firstOrNull { listY >= it.offset && listY < it.offset + it.size }
    }

    val target = drag?.let { targetFor(it.fingerY, meals) }

    // Автопрокрутка, пока палец у верхнего/нижнего края списка
    val scrollDir = drag?.let { d ->
        when {
            d.fingerY < edgePx -> -1
            containerHeight > 0 && d.fingerY > containerHeight - edgePx -> 1
            else -> 0
        }
    } ?: 0
    LaunchedEffect(scrollDir) {
        while (scrollDir != 0) {
            listState.scrollBy(scrollDir * 14f)
            delay(16)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerHeight = it.height },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            val item = itemAt(offset.y)
                            val key = item?.key as? String
                            val id = key?.takeIf { it.startsWith(KEY_MEAL) }?.removePrefix(KEY_MEAL)?.toLongOrNull()
                            val meal = currentMeals.firstOrNull { it.id == id }
                            if (item != null && meal != null) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                val itemTop = item.offset - listState.layoutInfo.viewportStartOffset
                                drag = DragState(meal = meal, grabOffsetY = offset.y - itemTop, fingerY = offset.y)
                            }
                        },
                        onDrag = { change, _ ->
                            drag?.let { d ->
                                change.consume()
                                drag = d.copy(fingerY = change.position.y)
                            }
                        },
                        onDragEnd = {
                            drag?.let { d ->
                                val to = targetFor(d.fingerY, currentMeals)
                                if (to != null && to != d.meal.mealType) {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    currentOnMove(d.meal, to)
                                }
                            }
                            drag = null
                        },
                        onDragCancel = { drag = null },
                    )
                },
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "summary") {
                DaySummaryCard(meals = meals, settings = settings)
            }
            sections.forEach { section ->
                item(key = KEY_HEADER + section.type.name) {
                    MealSectionHeader(
                        section = section,
                        highlighted = drag != null && target == section.type,
                        onAdd = { onAdd(section.type) },
                    )
                }
                if (section.meals.isEmpty()) {
                    item(key = KEY_EMPTY + section.type.name) {
                        EmptySectionHint(
                            highlighted = drag != null && target == section.type,
                            dragging = drag != null,
                            onClick = { onAdd(section.type) },
                        )
                    }
                } else {
                    items(section.meals, key = { KEY_MEAL + it.id }) { meal ->
                        val isDragged = drag?.meal?.id == meal.id
                        MealCard(
                            meal = meal,
                            modifier = Modifier.alpha(if (isDragged) 0.35f else 1f),
                            onClick = { onEdit(meal) },
                            onDeleteClick = { onDelete(meal) },
                        )
                    }
                }
            }
        }

        // Перетаскиваемая копия — поверх списка, под пальцем
        drag?.let { d ->
            val top = (d.fingerY - d.grabOffsetY).roundToInt()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .offset { IntOffset(0, top) }
                    .zIndex(1f),
            ) {
                MealCard(meal = d.meal, elevated = true, onClick = {}, onDeleteClick = {})
            }
        }
    }

    // Если перетаскиваемый приём исчез из списка (сменился день, удалён) — жест сбрасываем
    LaunchedEffect(meals) {
        val d = drag
        if (d != null && meals.none { it.id == d.meal.id }) drag = null
    }
}

/** Заголовок секции: эмодзи + название, ккал секции, кнопка «+» для добавления сразу в неё. */
@Composable
private fun MealSectionHeader(
    section: MealSection,
    highlighted: Boolean,
    onAdd: () -> Unit,
) {
    val accents = fitAccents
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (highlighted) accents.nutritionContainer else Color.Transparent,
        border = if (highlighted) BorderStroke(2.dp, accents.nutrition) else null,
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 0.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(section.type.emoji, style = MaterialTheme.typography.titleMedium)
            Text(
                text = section.type.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = if (section.meals.isEmpty()) "—" else "${formatKcal(section.calories)} ккал",
                style = MaterialTheme.typography.labelLarge,
                color = if (section.meals.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else accents.nutrition,
            )
            IconButton(onClick = onAdd) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "Добавить в «${section.type.title}»",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Заглушка пустой секции: подсказка и цель для перетаскивания. */
@Composable
private fun EmptySectionHint(
    highlighted: Boolean,
    dragging: Boolean,
    onClick: () -> Unit,
) {
    val accents = fitAccents
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = if (highlighted) accents.nutritionContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            if (highlighted) accents.nutrition else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Text(
            text = if (dragging) "Отпустите, чтобы перенести сюда" else "Пусто — нажмите, чтобы добавить",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (highlighted) accents.onNutritionContainer else MaterialTheme.colorScheme.onSurfaceVariant,
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
    val pending = meals.count { it.needsEstimate }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
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
            if (pending > 0) {
                Text(
                    text = "⏳ $pending ${pluralPending(pending)} без КБЖУ — итог дня неполный",
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 14.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun pluralPending(n: Int): String = when {
    n % 10 == 1 && n % 100 != 11 -> "приём"
    n % 10 in 2..4 && n % 100 !in 12..14 -> "приёма"
    else -> "приёмов"
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

/**
 * Карточка приёма пищи: бейдж времени, название (и вес порции), калории цветом раздела,
 * корзина удаления. elevated — «поднятая» копия во время перетаскивания.
 */
@Composable
private fun MealCard(
    meal: Meal,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier,
    elevated: Boolean = false,
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .then(if (elevated) Modifier else Modifier.pressScale()),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (elevated) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (elevated) 10.dp else 0.dp),
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
                    text = meal.servingG?.let { "${meal.name} · ${Format.weight(it)} г" } ?: meal.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (meal.needsEstimate) {
                    Text(
                        text = "⏳ ждёт расчёта КБЖУ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
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
