package com.dtyan.fitdiary.ui.nutrition

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dtyan.fitdiary.MainDispatcherRule
import com.dtyan.fitdiary.data.SettingsStore
import com.dtyan.fitdiary.data.ai.HttpResult
import com.dtyan.fitdiary.data.ai.HttpTransport
import com.dtyan.fitdiary.data.ai.NutritionEstimator
import com.dtyan.fitdiary.data.db.AppDatabase
import com.dtyan.fitdiary.data.repo.NutritionRepository
import com.dtyan.fitdiary.domain.MealType
import com.dtyan.fitdiary.domain.Per100
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
import java.time.LocalTime
import java.time.ZoneId

/**
 * Тесты NutritionViewModel поверх реальной in-memory Room-БД и реального
 * SettingsStore (SharedPreferences в Robolectric). Экзекьюторы Room привязаны
 * к тестовому планировщику — advanceUntilIdle() дожимает и viewModelScope,
 * и запросы БД, и переэмиссию Flow. Расчёт через ИИ — на фальшивом транспорте.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NutritionViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private lateinit var db: AppDatabase
    private lateinit var repo: NutritionRepository
    private lateinit var settingsStore: SettingsStore
    private lateinit var transport: FakeTransport
    private lateinit var vm: NutritionViewModel

    private class FakeTransport : HttpTransport {
        var response: HttpResult = HttpResult(500, "")
        val bodies = mutableListOf<String>()
        override fun post(url: String, headers: Map<String, String>, body: String): HttpResult {
            bodies += body
            return response
        }
    }

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
        // Чистые настройки: тесты в одном процессе делят SharedPreferences
        settingsStore.update { SettingsStore.Settings() }
        transport = FakeTransport()
        val estimator = NutritionEstimator(
            settings = { settingsStore.settings.value },
            transport = transport,
            ioDispatcher = mainRule.dispatcher,
        )
        vm = NutritionViewModel(repo, settingsStore, estimator, exchange = null)
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** meals — stateIn(WhileSubscribed): без подписчика значение не обновляется. */
    private fun TestScope.subscribeMeals() {
        backgroundScope.launch { vm.meals.collect { } }
        backgroundScope.launch { vm.pendingEstimates.collect { } }
        advanceUntilIdle()
    }

    /** Заполняет форму нового приёма в режиме «вся порция» (итоговые значения). */
    private fun fillAddEditor(
        name: String,
        calories: String,
        protein: String = "",
        fat: String = "",
        carbs: String = "",
    ) {
        vm.openAddEditor()
        vm.onEditorBasisChange(EntryBasis.TOTAL)
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
        // Для сегодняшнего дня timestamp ≈ сейчас, тип — по текущему часу.
        assertThat(meal.timestamp).isAtLeast(before)
        assertThat(meal.timestamp).isAtMost(after)
        assertThat(meal.mealType).isEqualTo(MealType.forTimestamp(meal.timestamp))
        // В режиме «вся порция» значения на 100 г не сохраняются.
        assertThat(meal.per100).isNull()
        assertThat(meal.servingG).isNull()
        assertThat(meal.needsEstimate).isFalse()

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
        // Полдень другого дня → предвыбран обед
        assertThat(vm.editor.value!!.mealType).isEqualTo(MealType.LUNCH)
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
        assertThat(meal.mealType).isEqualTo(MealType.LUNCH)
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

    @Test
    fun openAddEditor_typePresetByTimeOrExplicit() = runTest {
        vm.openAddEditor()
        assertThat(vm.editor.value!!.mealType).isEqualTo(MealType.forHour(LocalTime.now().hour))
        assertThat(vm.editor.value!!.basis).isEqualTo(EntryBasis.PER_100G)

        vm.openAddEditor(MealType.DINNER)
        assertThat(vm.editor.value!!.mealType).isEqualTo(MealType.DINNER)

        vm.onEditorMealTypeChange(MealType.SNACK)
        assertThat(vm.editor.value!!.mealType).isEqualTo(MealType.SNACK)
    }

    // ---------- Режим «на 100 г» ----------

    @Test
    fun saveEditor_per100WithServing_storesTotalsAndPer100() = runTest {
        subscribeMeals()
        vm.openAddEditor(MealType.BREAKFAST)
        vm.onEditorNameChange("Овсянка")
        vm.onEditorCaloriesChange("350")
        vm.onEditorProteinChange("12,5")
        vm.onEditorFatChange("7")
        vm.onEditorCarbsChange("55")
        // Без веса порции сохранить нельзя — итог не посчитать
        assertThat(vm.editor.value!!.canSave).isFalse()
        assertThat(vm.editor.value!!.totals).isNull()

        vm.onEditorServingChange("130")
        val state = vm.editor.value!!
        assertThat(state.canSave).isTrue()
        assertThat(state.totals!!.calories).isEqualTo(455)
        vm.saveEditor()
        advanceUntilIdle()

        val meal = vm.meals.value.single()
        assertThat(meal.mealType).isEqualTo(MealType.BREAKFAST)
        assertThat(meal.servingG).isEqualTo(130.0)
        assertThat(meal.calories).isEqualTo(455)
        assertThat(meal.proteinG).isEqualTo(16.3)
        assertThat(meal.fatG).isEqualTo(9.1)
        assertThat(meal.carbsG).isEqualTo(71.5)
        assertThat(meal.per100).isEqualTo(Per100(350.0, 12.5, 7.0, 55.0))

        // Редактирование открывает форму в том же режиме с исходными числами
        vm.openEditEditor(meal)
        val editor = vm.editor.value!!
        assertThat(editor.basis).isEqualTo(EntryBasis.PER_100G)
        assertThat(editor.servingText).isEqualTo("130")
        assertThat(editor.caloriesText).isEqualTo("350")
        assertThat(editor.proteinText).isEqualTo("12,5")
        assertThat(editor.carbsText).isEqualTo("55")

        // Поменяли вес — итог пересчитался, per100 остались
        vm.onEditorServingChange("200")
        vm.saveEditor()
        advanceUntilIdle()
        val updated = vm.meals.value.single()
        assertThat(updated.id).isEqualTo(meal.id)
        assertThat(updated.calories).isEqualTo(700)
        assertThat(updated.proteinG).isEqualTo(25.0)
        assertThat(updated.carbsG).isEqualTo(110.0)
        assertThat(updated.per100).isEqualTo(Per100(350.0, 12.5, 7.0, 55.0))
    }

    @Test
    fun editorBasisChange_convertsValuesBothWays() = runTest {
        vm.openAddEditor()
        vm.onEditorServingChange("130")
        vm.onEditorCaloriesChange("350")
        vm.onEditorProteinChange("12,5")

        vm.onEditorBasisChange(EntryBasis.TOTAL)
        var s = vm.editor.value!!
        assertThat(s.basis).isEqualTo(EntryBasis.TOTAL)
        assertThat(s.caloriesText).isEqualTo("455")
        assertThat(s.proteinText).isEqualTo("16,3")
        assertThat(s.totals!!.calories).isEqualTo(455)

        vm.onEditorBasisChange(EntryBasis.PER_100G)
        s = vm.editor.value!!
        assertThat(s.caloriesText).isEqualTo("350")
        assertThat(s.proteinText).isEqualTo("12,5")

        // Без веса переключение просто меняет режим, числа остаются как есть
        vm.onEditorServingChange("")
        vm.onEditorBasisChange(EntryBasis.TOTAL)
        s = vm.editor.value!!
        assertThat(s.basis).isEqualTo(EntryBasis.TOTAL)
        assertThat(s.caloriesText).isEqualTo("350")
        assertThat(s.canSave).isFalse() // имя пустое
    }

    @Test
    fun applyRecent_per100Meal_restoresPer100ModeAndServing() = runTest {
        subscribeMeals()
        repo.addMeal(
            epochDay = LocalDate.now().toEpochDay(), name = "Творог 5%",
            calories = 242, proteinG = 34.2, fatG = 10.0, carbsG = 3.6,
            timestamp = 1L, servingG = 200.0, per100 = Per100(121.0, 17.1, 5.0, 1.8),
        )
        advanceUntilIdle()

        vm.openAddEditor()
        vm.onEditorBasisChange(EntryBasis.TOTAL)
        advanceUntilIdle()
        assertThat(vm.recent.value.map { it.name }).containsExactly("Творог 5%")

        vm.applyRecent(vm.recent.value.single())
        val s = vm.editor.value!!
        assertThat(s.name).isEqualTo("Творог 5%")
        assertThat(s.basis).isEqualTo(EntryBasis.PER_100G)
        assertThat(s.servingText).isEqualTo("200")
        assertThat(s.caloriesText).isEqualTo("121")
        assertThat(s.proteinText).isEqualTo("17,1")
        assertThat(s.totals!!.calories).isEqualTo(242)
    }

    // ---------- Группы и перенос ----------

    @Test
    fun moveMeal_changesTypeAndRegroups() = runTest {
        subscribeMeals()
        val day = LocalDate.now().toEpochDay()
        repo.addMeal(day, "Овсянка", 350, 12.5, 7.0, 55.0, timestamp = 1L, mealType = MealType.BREAKFAST)
        repo.addMeal(day, "Яблоко", 80, 0.4, 0.4, 20.0, timestamp = 2L, mealType = MealType.BREAKFAST)
        advanceUntilIdle()

        var sections = groupMeals(vm.meals.value)
        assertThat(sections.map { it.type }).containsExactly(*MealType.ORDERED.toTypedArray()).inOrder()
        assertThat(sections[0].meals.map { it.name }).containsExactly("Овсянка", "Яблоко")
        assertThat(sections[0].calories).isEqualTo(430)
        assertThat(sections[3].meals).isEmpty()

        val apple = vm.meals.value.first { it.name == "Яблоко" }
        vm.moveMeal(apple, MealType.SNACK)
        advanceUntilIdle()

        sections = groupMeals(vm.meals.value)
        assertThat(sections[0].meals.map { it.name }).containsExactly("Овсянка")
        assertThat(sections[3].meals.map { it.name }).containsExactly("Яблоко")
        assertThat(sections[3].calories).isEqualTo(80)
        assertThat(db.mealDao().getByIds(listOf(apple.id)).single().mealType).isEqualTo(MealType.SNACK)
        // Остальные поля не тронуты
        assertThat(db.mealDao().getByIds(listOf(apple.id)).single().calories).isEqualTo(80)
    }

    // ---------- «Рассчитать позже» и файл-обмен ----------

    @Test
    fun saveEditor_estimateLater_thenApplyEstimateText_fillsValues() = runTest {
        subscribeMeals()
        vm.openAddEditor(MealType.LUNCH)
        vm.onEditorNameChange("Плов с курицей")
        vm.onEditorServingChange("300")
        assertThat(vm.editor.value!!.canSave).isFalse()
        vm.onEditorEstimateLaterChange(true)
        assertThat(vm.editor.value!!.canSave).isTrue()
        vm.saveEditor()
        advanceUntilIdle()

        val pending = vm.meals.value.single()
        assertThat(pending.needsEstimate).isTrue()
        assertThat(pending.calories).isEqualTo(0)
        assertThat(pending.servingG).isEqualTo(300.0)
        assertThat(vm.pendingEstimates.value).isEqualTo(1)
        assertThat(repo.getPendingEstimatesOnce().map { it.id }).containsExactly(pending.id)

        // Редактирование ждущего приёма открывается с галочкой «рассчитать позже»
        vm.openEditEditor(pending)
        assertThat(vm.editor.value!!.estimateLater).isTrue()
        vm.closeEditor()

        val message = vm.applyEstimateText(
            """{"items":[{"id":${pending.id},"name":"Плов с курицей","servingG":300,
                "kcalPer100":190,"proteinPer100":8,"fatPer100":7,"carbsPer100":24}]}""",
        )
        advanceUntilIdle()

        assertThat(message).isEqualTo("Рассчитано: 1 приём")
        val done = vm.meals.value.single()
        assertThat(done.needsEstimate).isFalse()
        assertThat(done.calories).isEqualTo(570)
        assertThat(done.proteinG).isEqualTo(24.0)
        assertThat(done.per100).isEqualTo(Per100(190.0, 8.0, 7.0, 24.0))
        assertThat(vm.pendingEstimates.value).isEqualTo(0)

        // Чужие id и мусор — понятные сообщения, без исключений
        assertThat(vm.applyEstimateText("""[{"id":9999,"kcalPer100":1}]"""))
            .isEqualTo("Приёмы из файла не найдены в дневнике")
        assertThat(vm.applyEstimateText("не json")).startsWith("Файл не распознан")
        assertThat(vm.applyEstimateText("""{"items":[{"id":1}]}""")).isEqualTo("В файле нет заполненных значений")
    }

    // ---------- Расчёт через ИИ ----------

    @Test
    fun estimateWithAi_fillsPer100AndServing_usesConfiguredModel() = runTest {
        settingsStore.update { it.copy(aiApiKey = "sk-1", aiModel = "m-test") }
        transport.response = HttpResult(
            200,
            """{"choices":[{"message":{"role":"assistant","content":
               "{\"kcal_per_100g\":190,\"protein_per_100g\":8,\"fat_per_100g\":7,\"carbs_per_100g\":24,\"serving_g\":300,\"note\":\"типичная порция\"}"}}]}""",
        )
        vm.openAddEditor()
        vm.onEditorNameChange("Плов")
        vm.onEditorEstimateLaterChange(true)

        vm.estimateWithAi()
        assertThat(vm.editor.value!!.estimating).isTrue()
        advanceUntilIdle()

        val s = vm.editor.value!!
        assertThat(transport.bodies.single()).contains("\"model\":\"m-test\"")
        assertThat(s.estimating).isFalse()
        assertThat(s.estimateError).isNull()
        assertThat(s.estimateNote).isEqualTo("типичная порция")
        assertThat(s.basis).isEqualTo(EntryBasis.PER_100G)
        assertThat(s.servingText).isEqualTo("300")
        assertThat(s.caloriesText).isEqualTo("190")
        assertThat(s.proteinText).isEqualTo("8")
        assertThat(s.estimateLater).isFalse() // расчёт получен — «позже» больше не нужно
        assertThat(s.totals!!.calories).isEqualTo(570)
        assertThat(s.canSave).isTrue()
    }

    @Test
    fun estimateWithAi_keepsUserServing_andReportsErrors() = runTest {
        settingsStore.update { it.copy(aiApiKey = "sk-1") }
        transport.response = HttpResult(401, """{"error":{"message":"bad key"}}""")
        vm.openAddEditor()
        vm.onEditorNameChange("Борщ")
        vm.onEditorServingChange("250")

        vm.estimateWithAi()
        advanceUntilIdle()

        var s = vm.editor.value!!
        assertThat(s.estimating).isFalse()
        assertThat(s.estimateError).contains("Неверный API-ключ")
        assertThat(s.servingText).isEqualTo("250")
        assertThat(s.caloriesText).isEmpty()

        // Успешный ответ не перетирает введённый пользователем вес
        transport.response = HttpResult(
            200,
            """{"choices":[{"message":{"content":"{\"kcal_per_100g\":50,\"protein_per_100g\":2,\"fat_per_100g\":2,\"carbs_per_100g\":6,\"serving_g\":400,\"note\":\"\"}"}}]}""",
        )
        vm.estimateWithAi()
        advanceUntilIdle()
        s = vm.editor.value!!
        assertThat(s.estimateError).isNull()
        assertThat(s.servingText).isEqualTo("250")
        assertThat(s.caloriesText).isEqualTo("50")
        assertThat(s.totals!!.calories).isEqualTo(125)
    }

    @Test
    fun estimateWithAi_notConfigured_showsHint_noNetwork() = runTest {
        vm.openAddEditor()
        vm.onEditorNameChange("Борщ")

        vm.estimateWithAi()
        advanceUntilIdle()

        assertThat(transport.bodies).isEmpty()
        assertThat(vm.editor.value!!.estimateError).contains("API-ключ")
    }

    @Test
    fun updateAiSettings_persists_andBlankFallsBackToDefaults() = runTest {
        vm.updateAiSettings(baseUrl = "  ", model = "", apiKey = " sk-abc ")

        val s = vm.settings.value
        assertThat(s.aiBaseUrl).isEqualTo(SettingsStore.DEFAULT_AI_BASE_URL)
        assertThat(s.aiModel).isEqualTo(SettingsStore.DEFAULT_AI_MODEL)
        assertThat(s.aiApiKey).isEqualTo("sk-abc")
        assertThat(s.aiConfigured).isTrue()

        val fresh = SettingsStore(ApplicationProvider.getApplicationContext())
        assertThat(fresh.settings.value.aiApiKey).isEqualTo("sk-abc")
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
            mealType = MealType.SNACK,
        )
        advanceUntilIdle()
        val original = vm.meals.value.single()

        vm.openEditEditor(original)
        val editor = vm.editor.value!!
        assertThat(editor.isNew).isFalse()
        assertThat(editor.basis).isEqualTo(EntryBasis.TOTAL) // вводилось итогом — так и открываем
        assertThat(editor.name).isEqualTo("Творог")
        assertThat(editor.caloriesText).isEqualTo("200")
        assertThat(editor.proteinText).isEqualTo("30")
        assertThat(editor.carbsText).isEmpty() // нулевые БЖУ показываются пустым полем
        assertThat(editor.mealType).isEqualTo(MealType.SNACK)

        vm.onEditorNameChange("Творог 5%")
        vm.onEditorCaloriesChange("250")
        vm.onEditorProteinChange("32,5")
        vm.onEditorMealTypeChange(MealType.DINNER)
        vm.saveEditor()
        advanceUntilIdle()

        val updated = vm.meals.value.single()
        assertThat(updated.id).isEqualTo(original.id)
        assertThat(updated.name).isEqualTo("Творог 5%")
        assertThat(updated.calories).isEqualTo(250)
        assertThat(updated.proteinG).isEqualTo(32.5)
        assertThat(updated.mealType).isEqualTo(MealType.DINNER)
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
