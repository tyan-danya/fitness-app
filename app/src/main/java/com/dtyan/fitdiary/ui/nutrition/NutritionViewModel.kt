package com.dtyan.fitdiary.ui.nutrition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dtyan.fitdiary.data.SettingsStore
import com.dtyan.fitdiary.data.db.Meal
import com.dtyan.fitdiary.data.repo.NutritionRepository
import com.dtyan.fitdiary.ui.common.Format
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** Состояние формы добавления/редактирования приёма пищи. editing == null — новый приём. */
data class MealEditorState(
    val editing: Meal? = null,
    val name: String = "",
    val caloriesText: String = "",
    val proteinText: String = "",
    val fatText: String = "",
    val carbsText: String = "",
) {
    val isNew: Boolean get() = editing == null
    val caloriesValid: Boolean get() = caloriesText.trim().toIntOrNull() != null
    val canSave: Boolean get() = name.isNotBlank() && caloriesValid
}

class NutritionViewModel(
    private val repo: NutritionRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    val settings: StateFlow<SettingsStore.Settings> = settingsStore.settings

    private val _selectedDay = MutableStateFlow(LocalDate.now())
    val selectedDay: StateFlow<LocalDate> = _selectedDay.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val meals: StateFlow<List<Meal>> = _selectedDay
        .flatMapLatest { day -> repo.observeMealsForDay(day.toEpochDay()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _recent = MutableStateFlow<List<Meal>>(emptyList())
    val recent: StateFlow<List<Meal>> = _recent.asStateFlow()

    private val _editor = MutableStateFlow<MealEditorState?>(null)
    val editor: StateFlow<MealEditorState?> = _editor.asStateFlow()

    // --- Выбранный день ---

    fun shiftDay(deltaDays: Long) = _selectedDay.update { it.plusDays(deltaDays) }

    fun resetToToday() {
        _selectedDay.value = LocalDate.now()
    }

    // --- Форма приёма пищи ---

    fun openAddEditor() {
        _editor.value = MealEditorState()
        viewModelScope.launch { _recent.value = repo.recentMeals(12) }
    }

    fun openEditEditor(meal: Meal) {
        _editor.value = MealEditorState(
            editing = meal,
            name = meal.name,
            caloriesText = meal.calories.toString(),
            proteinText = macroText(meal.proteinG),
            fatText = macroText(meal.fatG),
            carbsText = macroText(meal.carbsG),
        )
    }

    fun closeEditor() {
        _editor.value = null
    }

    fun onEditorNameChange(value: String) = _editor.update { it?.copy(name = value) }
    fun onEditorCaloriesChange(value: String) = _editor.update { it?.copy(caloriesText = value) }
    fun onEditorProteinChange(value: String) = _editor.update { it?.copy(proteinText = value) }
    fun onEditorFatChange(value: String) = _editor.update { it?.copy(fatText = value) }
    fun onEditorCarbsChange(value: String) = _editor.update { it?.copy(carbsText = value) }

    /** Чип «Недавнее»: заполняет все поля формы значениями выбранного блюда. */
    fun applyRecent(meal: Meal) = _editor.update {
        it?.copy(
            name = meal.name,
            caloriesText = meal.calories.toString(),
            proteinText = macroText(meal.proteinG),
            fatText = macroText(meal.fatG),
            carbsText = macroText(meal.carbsG),
        )
    }

    /** Сохраняет форму: новый приём — вставка, существующий — обновление полей КБЖУ. */
    fun saveEditor() {
        val state = _editor.value ?: return
        if (!state.canSave) return
        val name = state.name.trim()
        val calories = state.caloriesText.trim().toIntOrNull() ?: return
        val protein = parseMacro(state.proteinText)
        val fat = parseMacro(state.fatText)
        val carbs = parseMacro(state.carbsText)
        val existing = state.editing
        val day = _selectedDay.value
        _editor.value = null
        viewModelScope.launch {
            if (existing == null) {
                repo.addMeal(
                    epochDay = day.toEpochDay(),
                    name = name,
                    calories = calories,
                    proteinG = protein,
                    fatG = fat,
                    carbsG = carbs,
                    timestamp = newMealTimestamp(day),
                )
            } else {
                // День и время приёма при редактировании не меняем
                repo.updateMeal(
                    existing.copy(
                        name = name,
                        calories = calories,
                        proteinG = protein,
                        fatG = fat,
                        carbsG = carbs,
                    )
                )
            }
        }
    }

    fun deleteMeal(meal: Meal) {
        viewModelScope.launch { repo.deleteMeal(meal) }
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

    /** Терпимый парсинг: запятая приравнивается к точке, пустое поле = 0. */
    private fun parseMacro(text: String): Double =
        text.trim().replace(',', '.').toDoubleOrNull() ?: 0.0

    /** Для сегодняшнего дня — текущий момент, для другого — полдень того дня в системной зоне. */
    private fun newMealTimestamp(day: LocalDate): Long =
        if (day == LocalDate.now()) {
            System.currentTimeMillis()
        } else {
            day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
}
