package com.dtyan.fitdiary.ui.nutrition

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dtyan.fitdiary.MainDispatcherRule
import com.dtyan.fitdiary.data.SettingsStore
import com.dtyan.fitdiary.data.db.AppDatabase
import com.dtyan.fitdiary.data.repo.NutritionRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.ZoneId

/**
 * Тесты NutritionViewModel поверх реальной in-memory Room-БД и реального
 * SettingsStore (SharedPreferences в Robolectric). Экзекьюторы Room привязаны
 * к тестовому планировщику — advanceUntilIdle() дожимает и viewModelScope,
 * и запросы БД, и переэмиссию Flow.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NutritionViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private lateinit var db: AppDatabase
    private lateinit var repo: NutritionRepository
    private lateinit var settingsStore: SettingsStore
    private lateinit var vm: NutritionViewModel

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val executor = mainRule.dispatcher.asExecutor()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(executor)
            .setTransactionExecutor(executor)
            .build()
        repo = NutritionRepository(db.mealDao())
        settingsStore = SettingsStore(context)
        vm = NutritionViewModel(repo, settingsStore)
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** meals — stateIn(WhileSubscribed): без подписчика значение не обновляется. */
    private fun TestScope.subscribeMeals() {
        backgroundScope.launch { vm.meals.collect { } }
        advanceUntilIdle()
    }

    private fun fillAddEditor(
        name: String,
        calories: String,
        protein: String = "",
        fat: String = "",
        carbs: String = "",
    ) {
        vm.openAddEditor()
        vm.onEditorNameChange(name)
        vm.onEditorCaloriesChange(calories)
        vm.onEditorProteinChange(protein)
        vm.onEditorFatChange(fat)
        vm.onEditorCarbsChange(carbs)
    }

    // ---------- Добавление приёма ----------

    @Test
    fun saveEditor_newMealToday_nowTimestampAndTodayEpochDay() = runTest {
        subscribeMeals()
        val today = vm.selectedDay.value
        assertThat(today).isEqualTo(LocalDate.now())

        fillAddEditor("Овсянка", "350", protein = "12,5", fat = "7", carbs = "55,5")
        val before = System.currentTimeMillis()
        vm.saveEditor()
        advanceUntilIdle()
        val after = System.currentTimeMillis()

        // Форма закрылась сразу.
        assertThat(vm.editor.value).isNull()

        val stored = db.mealDao().getForDayOnce(today.toEpochDay())
        assertThat(stored).hasSize(1)
        val meal = stored.single()
        assertThat(meal.name).isEqualTo("Овсянка")
        assertThat(meal.calories).isEqualTo(350)
        assertThat(meal.proteinG).isEqualTo(12.5) // запятая распарсилась
        assertThat(meal.fatG).isEqualTo(7.0)
        assertThat(meal.carbsG).isEqualTo(55.5)
        assertThat(meal.epochDay).isEqualTo(today.toEpochDay())
        // Для сегодняшнего дня timestamp ≈ сейчас.
        assertThat(meal.timestamp).isAtLeast(before)
        assertThat(meal.timestamp).isAtMost(after)

        // И приём виден через наблюдаемый список VM.
        assertThat(vm.meals.value.map { it.id }).containsExactly(meal.id)
    }

    @Test
    fun saveEditor_newMealOnOtherDay_noonTimestampAndThatEpochDay() = runTest {
        subscribeMeals()
        vm.shiftDay(-1)
        advanceUntilIdle()
        val yesterday = vm.selectedDay.value
        assertThat(yesterday).isEqualTo(LocalDate.now().minusDays(1))

        fillAddEditor("Гречка", "400")
        vm.saveEditor()
        advanceUntilIdle()

        val stored = db.mealDao().getForDayOnce(yesterday.toEpochDay())
        assertThat(stored).hasSize(1)
        val meal = stored.single()
        assertThat(meal.epochDay).isEqualTo(yesterday.toEpochDay())
        // Не «сейчас», а полдень выбранного дня в системной зоне.
        val expectedNoon = yesterday.atTime(12, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertThat(meal.timestamp).isEqualTo(expectedNoon)
        // Пустые поля БЖУ = 0.
        assertThat(meal.proteinG).isEqualTo(0.0)
        assertThat(meal.fatG).isEqualTo(0.0)
        assertThat(meal.carbsG).isEqualTo(0.0)

        // Список выбранного (вчерашнего) дня содержит приём…
        assertThat(vm.meals.value.map { it.name }).containsExactly("Гречка")
        // …а после возврата на сегодня список пуст.
        vm.resetToToday()
        advanceUntilIdle()
        assertThat(vm.meals.value).isEmpty()
    }

    // ---------- Итоги дня ----------

    @Test
    fun meals_dayTotals_matchAddedMeals() = runTest {
        subscribeMeals()

        fillAddEditor("Завтрак", "350", protein = "12,5", fat = "7", carbs = "55")
        vm.saveEditor()
        advanceUntilIdle()
        fillAddEditor("Обед", "650", protein = "45", fat = "20", carbs = "10,5")
        vm.saveEditor()
        advanceUntilIdle()

        // Экран суммирует именно meals (NutritionScreen), поэтому проверяем суммы по списку VM.
        val meals = vm.meals.value
        assertThat(meals).hasSize(2)
        assertThat(meals.sumOf { it.calories }).isEqualTo(1000)
        assertThat(meals.sumOf { it.proteinG }).isWithin(1e-9).of(57.5)
        assertThat(meals.sumOf { it.fatG }).isWithin(1e-9).of(27.0)
        assertThat(meals.sumOf { it.carbsG }).isWithin(1e-9).of(65.5)
    }

    // ---------- Цели КБЖУ ----------

    @Test
    fun updateGoals_updatesStateAndPersists() = runTest {
        vm.updateGoals(calorieGoal = 2500, proteinGoalG = 180, fatGoalG = 80, carbGoalG = 300)

        val s = vm.settings.value
        assertThat(s.calorieGoal).isEqualTo(2500)
        assertThat(s.proteinGoalG).isEqualTo(180)
        assertThat(s.fatGoalG).isEqualTo(80)
        assertThat(s.carbGoalG).isEqualTo(300)

        // Новый SettingsStore над теми же prefs видит сохранённое.
        val fresh = SettingsStore(ApplicationProvider.getApplicationContext())
        val persisted = fresh.settings.value
        assertThat(persisted.calorieGoal).isEqualTo(2500)
        assertThat(persisted.proteinGoalG).isEqualTo(180)
        assertThat(persisted.fatGoalG).isEqualTo(80)
        assertThat(persisted.carbGoalG).isEqualTo(300)
        // Прочие настройки не задеты.
        assertThat(persisted.restTimerSeconds).isEqualTo(90)
        assertThat(persisted.restTimerEnabled).isTrue()
    }

    // ---------- Редактирование и удаление ----------

    @Test
    fun saveEditor_editExistingMeal_updatesFieldsKeepsDayAndTimestamp() = runTest {
        subscribeMeals()
        val today = vm.selectedDay.value
        repo.addMeal(
            epochDay = today.toEpochDay(),
            name = "Творог",
            calories = 200,
            proteinG = 30.0,
            fatG = 5.0,
            carbsG = 0.0,
            timestamp = 1_234_567_890L,
        )
        advanceUntilIdle()
        val original = vm.meals.value.single()

        vm.openEditEditor(original)
        val editor = vm.editor.value!!
        assertThat(editor.isNew).isFalse()
        assertThat(editor.name).isEqualTo("Творог")
        assertThat(editor.caloriesText).isEqualTo("200")
        assertThat(editor.proteinText).isEqualTo("30")
        assertThat(editor.carbsText).isEmpty() // нулевые БЖУ показываются пустым полем

        vm.onEditorNameChange("Творог 5%")
        vm.onEditorCaloriesChange("250")
        vm.onEditorProteinChange("32,5")
        vm.saveEditor()
        advanceUntilIdle()

        val updated = vm.meals.value.single()
        assertThat(updated.id).isEqualTo(original.id)
        assertThat(updated.name).isEqualTo("Творог 5%")
        assertThat(updated.calories).isEqualTo(250)
        assertThat(updated.proteinG).isEqualTo(32.5)
        // День и время приёма при редактировании не меняются.
        assertThat(updated.epochDay).isEqualTo(today.toEpochDay())
        assertThat(updated.timestamp).isEqualTo(1_234_567_890L)
    }

    @Test
    fun deleteMeal_removesFromListAndDb() = runTest {
        subscribeMeals()
        val today = vm.selectedDay.value
        repo.addMeal(today.toEpochDay(), "Перекус", 150, 5.0, 3.0, 20.0, timestamp = 111L)
        repo.addMeal(today.toEpochDay(), "Ужин", 500, 40.0, 15.0, 30.0, timestamp = 222L)
        advanceUntilIdle()
        assertThat(vm.meals.value).hasSize(2)
        val snack = vm.meals.value.first { it.name == "Перекус" }

        vm.deleteMeal(snack)
        advanceUntilIdle()

        assertThat(vm.meals.value.map { it.name }).containsExactly("Ужин")
        assertThat(db.mealDao().getAllOnce().map { it.name }).containsExactly("Ужин")
    }
}
