package com.dtyan.fitdiary.ui.nutrition

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dtyan.fitdiary.data.SettingsStore
import com.dtyan.fitdiary.data.ai.EstimateException
import com.dtyan.fitdiary.data.ai.NutritionEstimator
import com.dtyan.fitdiary.data.db.Meal
import com.dtyan.fitdiary.data.repo.NutritionRepository
import com.dtyan.fitdiary.domain.MacroTotals
import com.dtyan.fitdiary.domain.MealType
import com.dtyan.fitdiary.domain.Per100
import com.dtyan.fitdiary.domain.per100For
import com.dtyan.fitdiary.domain.totalsFor
import com.dtyan.fitdiary.export.EstimateExchange
import com.dtyan.fitdiary.ui.common.Format
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** Как пользователь вводит КБЖУ: на 100 г продукта (как на упаковке) или сразу итог порции. */
enum class EntryBasis { PER_100G, TOTAL }

/** Состояние формы добавления/редактирования приёма пищи. editing == null — новый приём. */
data class MealEditorState(
    val editing: Meal? = null,
    val name: String = "",
    val mealType: MealType = MealType.SNACK,
    val servingText: String = "",
    val basis: EntryBasis = EntryBasis.PER_100G,
    val caloriesText: String = "",
    val proteinText: String = "",
    val fatText: String = "",
    val carbsText: String = "",
    /** «Рассчитать позже»: сохранить без КБЖУ и выгрузить в файл для ассистента. */
    val estimateLater: Boolean = false,
    val estimating: Boolean = false,
    val estimateError: String? = null,
    /** Комментарий ИИ к оценке — показывается под полями. */
    val estimateNote: String? = null,
) {
    val isNew: Boolean get() = editing == null

    /** Вес порции, г (> 0), либо null. */
    val servingG: Double? get() = parseNumber(servingText)?.takeIf { it > 0 }
    val servingValid: Boolean get() = servingG != null

    val caloriesValid: Boolean get() = parseNumber(caloriesText)?.let { it >= 0 } == true

    /** Значения на 100 г — только в режиме PER_100G. */
    val per100: Per100?
        get() {
            if (basis != EntryBasis.PER_100G) return null
            val kcal = parseNumber(caloriesText)?.takeIf { it >= 0 } ?: return null
            return Per100(
                kcal = kcal,
                proteinG = parseNumber(proteinText) ?: 0.0,
                fatG = parseNumber(fatText) ?: 0.0,
                carbsG = parseNumber(carbsText) ?: 0.0,
            )
        }

    /** Итог порции по введённым данным; null — данных для расчёта не хватает. */
    val totals: MacroTotals?
        get() = when (basis) {
            EntryBasis.PER_100G -> {
                val p = per100 ?: return null
                val g = servingG ?: return null
                totalsFor(p, g)
            }
            EntryBasis.TOTAL -> {
                val kcal = parseNumber(caloriesText)?.takeIf { it >= 0 } ?: return null
                MacroTotals(
                    calories = kcal.roundToInt(),
                    proteinG = parseNumber(proteinText) ?: 0.0,
                    fatG = parseNumber(fatText) ?: 0.0,
                    carbsG = parseNumber(carbsText) ?: 0.0,
                )
            }
        }

    /** Без КБЖУ сохранить можно только с пометкой «рассчитать позже». */
    val canSave: Boolean get() = name.isNotBlank() && (estimateLater || totals != null)

    /** Кнопка ИИ активна, когда есть название и запрос ещё не идёт. */
    val canEstimate: Boolean get() = name.isNotBlank() && !estimating

    companion object {
        /** Терпимый парсинг: запятая приравнивается к точке, пустое поле = null. */
        fun parseNumber(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()
    }
}

/** Группа приёмов одного типа с итогом калорий — секция списка на экране. */
data class MealSection(val type: MealType, val meals: List<Meal>) {
    val calories: Int get() = meals.sumOf { it.calories }
}

/** Все четыре секции в фиксированном порядке (пустые тоже — в них можно перетащить приём). */
fun groupMeals(meals: List<Meal>): List<MealSection> {
    val byType = meals.groupBy { it.mealType }
    return MealType.ORDERED.map { MealSection(it, byType[it].orEmpty()) }
}

/** Разовые события экрана: открыть системный chooser либо показать сообщение. */
sealed interface NutritionEvent {
    data class Share(val intent: Intent) : NutritionEvent
    data class Message(val text: String) : NutritionEvent
}

class NutritionViewModel(
    private val repo: NutritionRepository,
    private val settingsStore: SettingsStore,
    private val estimator: NutritionEstimator? = null,
    private val exchange: EstimateExchange? = null,
) : ViewModel() {

    val settings: StateFlow<SettingsStore.Settings> = settingsStore.settings

    private val _selectedDay = MutableStateFlow(LocalDate.now())
    val selectedDay: StateFlow<LocalDate> = _selectedDay.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val meals: StateFlow<List<Meal>> = _selectedDay
        .flatMapLatest { day -> repo.observeMealsForDay(day.toEpochDay()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Сколько приёмов (за все дни) ждут расчёта — бейдж на пункте меню «Выгрузить». */
    val pendingEstimates: StateFlow<Int> = repo.observePendingEstimateCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _recent = MutableStateFlow<List<Meal>>(emptyList())
    val recent: StateFlow<List<Meal>> = _recent.asStateFlow()

    private val _editor = MutableStateFlow<MealEditorState?>(null)
    val editor: StateFlow<MealEditorState?> = _editor.asStateFlow()

    private val _events = Channel<NutritionEvent>(Channel.BUFFERED)
    val events: Flow<NutritionEvent> = _events.receiveAsFlow()

    private val _busy = MutableStateFlow(false)
    /** Идёт выгрузка/загрузка файла расчёта. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    // --- Выбранный день ---

    fun shiftDay(deltaDays: Long) = _selectedDay.update { it.plusDays(deltaDays) }

    fun resetToToday() {
        _selectedDay.value = LocalDate.now()
    }

    // --- Форма приёма пищи ---

    /** Новый приём. type == null — определить по времени (для не-сегодня — полдень → обед). */
    fun openAddEditor(type: MealType? = null) {
        val day = _selectedDay.value
        _editor.value = MealEditorState(
            mealType = type ?: MealType.forTimestamp(newMealTimestamp(day)),
        )
        viewModelScope.launch { _recent.value = repo.recentMeals(12) }
    }

    fun openEditEditor(meal: Meal) {
        _editor.value = MealEditorState(editing = meal, mealType = meal.mealType).withValuesOf(meal)
    }

    fun closeEditor() {
        _editor.value = null
    }

    fun onEditorNameChange(value: String) = _editor.update { it?.copy(name = value, estimateError = null) }
    fun onEditorMealTypeChange(type: MealType) = _editor.update { it?.copy(mealType = type) }
    fun onEditorServingChange(value: String) = _editor.update { it?.copy(servingText = value) }
    fun onEditorCaloriesChange(value: String) = _editor.update { it?.copy(caloriesText = value) }
    fun onEditorProteinChange(value: String) = _editor.update { it?.copy(proteinText = value) }
    fun onEditorFatChange(value: String) = _editor.update { it?.copy(fatText = value) }
    fun onEditorCarbsChange(value: String) = _editor.update { it?.copy(carbsText = value) }
    fun onEditorEstimateLaterChange(value: Boolean) = _editor.update { it?.copy(estimateLater = value) }

    /**
     * Переключение режима ввода с пересчётом уже введённых чисел:
     * «на 100 г» ⇄ «итог порции» при известном весе — значения не теряются.
     */
    fun onEditorBasisChange(basis: EntryBasis) = _editor.update { state ->
        state ?: return@update null
        if (state.basis == basis) return@update state
        val g = state.servingG
        val converted = when (basis) {
            EntryBasis.TOTAL -> state.totals
                ?.let { t -> state.copy(basis = basis).withTotalsText(t) }
            EntryBasis.PER_100G -> {
                val t = state.totals
                if (t != null && g != null) per100For(t, g)?.let { p -> state.copy(basis = basis).withPer100Text(p) } else null
            }
        }
        converted ?: state.copy(basis = basis)
    }

    /** Чип «Недавнее»: заполняет форму значениями выбранного блюда (на 100 г, если так вводилось). */
    fun applyRecent(meal: Meal) = _editor.update { it?.withValuesOf(meal)?.copy(estimateError = null, estimateNote = null) }

    private fun MealEditorState.withValuesOf(meal: Meal): MealEditorState {
        val serving = meal.servingG?.let(::numberText) ?: ""
        val base = copy(name = meal.name, servingText = serving, estimateLater = meal.needsEstimate)
        val p = meal.per100
        return if (p != null) {
            base.copy(basis = EntryBasis.PER_100G).withPer100Text(p)
        } else if (meal.needsEstimate && meal.calories == 0) {
            base.copy(basis = EntryBasis.PER_100G, caloriesText = "", proteinText = "", fatText = "", carbsText = "")
        } else {
            base.copy(basis = EntryBasis.TOTAL)
                .withTotalsText(MacroTotals(meal.calories, meal.proteinG, meal.fatG, meal.carbsG))
        }
    }

    private fun MealEditorState.withPer100Text(p: Per100) = copy(
        caloriesText = numberText(p.kcal),
        proteinText = macroText(p.proteinG),
        fatText = macroText(p.fatG),
        carbsText = macroText(p.carbsG),
    )

    private fun MealEditorState.withTotalsText(t: MacroTotals) = copy(
        caloriesText = t.calories.toString(),
        proteinText = macroText(t.proteinG),
        fatText = macroText(t.fatG),
        carbsText = macroText(t.carbsG),
    )

    /** Сохраняет форму: новый приём — вставка, существующий — обновление полей. */
    fun saveEditor() {
        val state = _editor.value ?: return
        if (!state.canSave) return
        val name = state.name.trim()
        val totals = state.totals ?: MacroTotals(0, 0.0, 0.0, 0.0)
        // Значения «на 100 г» сохраняем даже без веса порции — при дозаполнении веса они не потеряются
        val per100 = state.per100
        val serving = state.servingG
        val needsEstimate = state.estimateLater
        val existing = state.editing
        val day = _selectedDay.value
        _editor.value = null
        viewModelScope.launch {
            if (existing == null) {
                repo.addMeal(
                    epochDay = day.toEpochDay(),
                    name = name,
                    calories = totals.calories,
                    proteinG = totals.proteinG,
                    fatG = totals.fatG,
                    carbsG = totals.carbsG,
                    timestamp = newMealTimestamp(day),
                    mealType = state.mealType,
                    servingG = serving,
                    per100 = per100,
                    needsEstimate = needsEstimate,
                )
            } else {
                // День и время приёма при редактировании не меняем
                repo.updateMeal(
                    existing.copy(
                        name = name,
                        calories = totals.calories,
                        proteinG = totals.proteinG,
                        fatG = totals.fatG,
                        carbsG = totals.carbsG,
                        mealType = state.mealType,
                        servingG = serving,
                        caloriesPer100 = per100?.kcal,
                        proteinPer100 = per100?.proteinG,
                        fatPer100 = per100?.fatG,
                        carbsPer100 = per100?.carbsG,
                        needsEstimate = needsEstimate,
                    )
                )
            }
        }
    }

    fun deleteMeal(meal: Meal) {
        viewModelScope.launch { repo.deleteMeal(meal) }
    }

    /** Перетаскивание карточки в другую секцию. */
    fun moveMeal(meal: Meal, type: MealType) {
        if (meal.mealType == type) return
        viewModelScope.launch { repo.moveMeal(meal.id, type) }
    }

    // --- Расчёт через ИИ ---

    fun estimateWithAi() {
        val state = _editor.value ?: return
        val est = estimator ?: return
        if (!state.canEstimate) return
        _editor.update { it?.copy(estimating = true, estimateError = null, estimateNote = null) }
        val name = state.name.trim()
        val serving = state.servingG
        viewModelScope.launch {
            try {
                val result = est.estimate(name, serving)
                _editor.update { cur ->
                    cur ?: return@update null
                    val servingText = cur.servingText.ifBlank { result.servingG?.let(::numberText) ?: "" }
                    cur.copy(
                        basis = EntryBasis.PER_100G,
                        servingText = servingText,
                        estimating = false,
                        estimateLater = false,
                        estimateNote = result.note ?: "Оценка ИИ — при необходимости поправьте",
                    ).withPer100Text(result.per100)
                }
            } catch (e: EstimateException) {
                _editor.update { it?.copy(estimating = false, estimateError = e.message) }
            } catch (e: Exception) {
                _editor.update { it?.copy(estimating = false, estimateError = "Не удалось рассчитать: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    fun updateAiSettings(baseUrl: String, model: String, apiKey: String) {
        settingsStore.update {
            it.copy(
                aiBaseUrl = baseUrl.trim().ifBlank { SettingsStore.DEFAULT_AI_BASE_URL },
                aiModel = model.trim().ifBlank { SettingsStore.DEFAULT_AI_MODEL },
                aiApiKey = apiKey.trim(),
            )
        }
    }

    // --- Файл-обмен для расчёта «вручную через ассистента» ---

    fun exportPendingEstimates() {
        val ex = exchange ?: return
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                val pending = repo.getPendingEstimatesOnce()
                if (pending.isEmpty()) {
                    _events.send(NutritionEvent.Message("Нет приёмов, ожидающих расчёта"))
                } else {
                    _events.send(NutritionEvent.Share(ex.shareRequest(pending)))
                }
            } catch (e: Exception) {
                _events.send(NutritionEvent.Message("Не удалось подготовить файл: ${e.message}"))
            } finally {
                _busy.value = false
            }
        }
    }

    fun importEstimates(uri: Uri) {
        val ex = exchange ?: return
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                val text = ex.readText(uri)
                val message = applyEstimateText(text)
                _events.send(NutritionEvent.Message(message))
            } catch (e: Exception) {
                _events.send(NutritionEvent.Message("Не удалось загрузить файл: ${e.message}"))
            } finally {
                _busy.value = false
            }
        }
    }

    /** Разбор и применение содержимого файла ответа; возвращает текст для пользователя. */
    suspend fun applyEstimateText(text: String): String {
        val updates = try {
            EstimateExchange.parseResponse(text)
        } catch (e: IllegalArgumentException) {
            return "Файл не распознан: ${e.message}"
        }
        if (updates.isEmpty()) return "В файле нет заполненных значений"
        val applied = repo.applyEstimates(updates)
        return when {
            applied == 0 -> "Приёмы из файла не найдены в дневнике"
            applied == updates.size -> "Рассчитано: $applied ${pluralMeals(applied)}"
            else -> "Рассчитано: $applied из ${updates.size} — остальные не найдены"
        }
    }

    // --- Цели КБЖУ ---

    fun updateGoals(calorieGoal: Int, proteinGoalG: Int, fatGoalG: Int, carbGoalG: Int) {
        settingsStore.update {
            it.copy(
                calorieGoal = calorieGoal,
                proteinGoalG = proteinGoalG,
                fatGoalG = fatGoalG,
                carbGoalG = carbGoalG,
            )
        }
    }

    /** Нулевые БЖУ показываем пустым полем — «не заполнено». */
    private fun macroText(value: Double): String =
        if (value == 0.0) "" else Format.weight(value)

    /** Число без хвостовых нулей, с запятой: 130 → «130», 12.5 → «12,5». */
    private fun numberText(value: Double): String = Format.weight(value)

    private fun pluralMeals(n: Int): String {
        val mod10 = n % 10
        val mod100 = n % 100
        return when {
            mod10 == 1 && mod100 != 11 -> "приём"
            mod10 in 2..4 && mod100 !in 12..14 -> "приёма"
            else -> "приёмов"
        }
    }

    /** Для сегодняшнего дня — текущий момент, для другого — полдень того дня в системной зоне. */
    private fun newMealTimestamp(day: LocalDate): Long =
        if (day == LocalDate.now()) {
            System.currentTimeMillis()
        } else {
            day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
}
