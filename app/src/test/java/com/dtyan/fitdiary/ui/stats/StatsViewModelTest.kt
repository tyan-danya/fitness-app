package com.dtyan.fitdiary.ui.stats

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dtyan.fitdiary.MainDispatcherRule
import com.dtyan.fitdiary.data.db.AppDatabase
import com.dtyan.fitdiary.data.db.Exercise
import com.dtyan.fitdiary.data.repo.ExerciseRepository
import com.dtyan.fitdiary.data.repo.NutritionRepository
import com.dtyan.fitdiary.data.repo.StatsRepository
import com.dtyan.fitdiary.data.repo.WorkoutRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.WeekFields

/**
 * Тесты StatsViewModel поверх реальной in-memory Room-БД.
 * Календарь/тоннаж/вес привязаны к «сейчас» внутри VM (YearMonth.now,
 * LocalDate.now, System.currentTimeMillis), поэтому данные создаются
 * относительно текущего момента.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StatsViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private lateinit var appContext: Context
    private lateinit var db: AppDatabase
    private lateinit var workoutRepo: WorkoutRepository
    private lateinit var nutritionRepo: NutritionRepository
    private lateinit var statsRepo: StatsRepository
    private lateinit var exerciseRepo: ExerciseRepository

    private val zone: ZoneId = ZoneId.systemDefault()
    private val min = 60_000L
    private val hour = 3_600_000L
    private val day = 24 * hour

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        val executor = mainRule.dispatcher.asExecutor()
        db = Room.inMemoryDatabaseBuilder(appContext, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(executor)
            .setTransactionExecutor(executor)
            .build()
        workoutRepo = WorkoutRepository(db.workoutDao(), db.workoutSetDao(), db.weightDao())
        nutritionRepo = NutritionRepository(db.mealDao())
        statsRepo = StatsRepository(db.workoutDao(), db.workoutSetDao(), db.weightDao())
        exerciseRepo = ExerciseRepository(db.exerciseDao(), db.workoutSetDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun createVm() = StatsViewModel(
        appContext = appContext,
        workoutRepository = workoutRepo,
        nutritionRepository = nutritionRepo,
        statsRepository = statsRepo,
        exerciseRepository = exerciseRepo,
    )

    private suspend fun exercise(name: String = "Жим лёжа", group: String = "Грудь"): Long =
        db.exerciseDao().insert(Exercise(name = name, muscleGroup = group, isCustom = true))

    private fun millisOf(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    /** Завершённая тренировка со стартом [startedAt] и одним подходом weight×reps. */
    private suspend fun finishedWorkout(
        startedAt: Long,
        exerciseId: Long? = null,
        weight: Double = 0.0,
        reps: Int = 0,
    ): Long {
        val w = workoutRepo.startWorkout(now = startedAt)
        if (exerciseId != null) {
            workoutRepo.addSet(w, exerciseId, weight, reps, now = startedAt + min)
        }
        workoutRepo.finishWorkout(w, bodyWeightKg = null, now = startedAt + hour)
        return w
    }

    // ---------- Календарь месяца ----------

    @Test
    fun calendar_marksWorkoutDaysAndMealDays_ofCurrentMonth() = runTest {
        val month = YearMonth.now()
        val workoutDay = month.atDay(10)
        val mealDay = month.atDay(12)
        val activeDay = month.atDay(15)

        finishedWorkout(startedAt = millisOf(workoutDay, 9))
        // Активная (незавершённая) тренировка в календарь не попадает.
        workoutRepo.startWorkout(now = millisOf(activeDay, 9))
        nutritionRepo.addMeal(
            epochDay = mealDay.toEpochDay(),
            name = "Обед",
            calories = 500,
            proteinG = 30.0,
            fatG = 15.0,
            carbsG = 40.0,
            timestamp = millisOf(mealDay, 13),
        )

        val vm = createVm()
        backgroundScope.launch { vm.calendar.collect { } }
        advanceUntilIdle()

        assertThat(vm.month.value).isEqualTo(month)
        val calendar = vm.calendar.value
        assertThat(calendar.workoutDays).contains(workoutDay)
        assertThat(calendar.workoutDays).doesNotContain(activeDay)
        assertThat(calendar.workoutDays).doesNotContain(mealDay)
        assertThat(calendar.mealDays).contains(mealDay)
        assertThat(calendar.mealDays).doesNotContain(workoutDay)
    }

    // ---------- Тоннаж по неделям ----------

    @Test
    fun weeklyVolume_twoWorkoutsInSameIsoWeek_summed() = runTest {
        val monday = LocalDate.now(zone).with(WeekFields.ISO.dayOfWeek(), 1)
        val e = exercise()
        finishedWorkout(millisOf(monday, 8), e, weight = 100.0, reps = 5) // 500
        finishedWorkout(millisOf(monday, 12), e, weight = 80.0, reps = 10) // 800

        val vm = createVm()
        backgroundScope.launch { vm.weeklyVolume.collect { } }
        advanceUntilIdle()

        val bars = vm.weeklyVolume.value
        // 8 столбиков: от «7 недель назад» до текущей.
        assertThat(bars).hasSize(8)
        assertThat(bars.first().monday).isEqualTo(monday.minusWeeks(7))
        assertThat(bars.last().monday).isEqualTo(monday)
        // Обе тренировки одной ISO-недели суммируются.
        assertThat(bars.last().volumeKg).isWithin(1e-9).of(1300.0)
        assertThat(bars.dropLast(1).sumOf { it.volumeKg }).isEqualTo(0.0)
    }

    // ---------- Вес: дельта за 30 дней ----------

    @Test
    fun weight_delta30d_gain_baselineOlderThan30Days() = runTest {
        val now = System.currentTimeMillis()
        statsRepo.addWeightEntry(70.0, now = now - 100 * day) // старше 90 дней — вне графика
        statsRepo.addWeightEntry(80.0, now = now - 40 * day) // база для дельты
        statsRepo.addWeightEntry(82.5, now = now - 1 * day) // текущий

        val vm = createVm()
        backgroundScope.launch { vm.weight.collect { } }
        advanceUntilIdle()

        val weight = vm.weight.value
        assertThat(weight.current?.weightKg).isEqualTo(82.5)
        assertThat(weight.delta30d).isNotNull()
        assertThat(weight.delta30d!!).isWithin(1e-9).of(2.5) // 82,5 − 80,0
        // На графике только последние 90 дней.
        assertThat(weight.chartPoints.map { it.second }).containsExactly(80.0, 82.5).inOrder()
    }

    @Test
    fun weight_delta30d_loss_negativeValue() = runTest {
        val now = System.currentTimeMillis()
        statsRepo.addWeightEntry(90.0, now = now - 35 * day)
        statsRepo.addWeightEntry(85.5, now = now - 2 * hour)

        val vm = createVm()
        backgroundScope.launch { vm.weight.collect { } }
        advanceUntilIdle()

        val weight = vm.weight.value
        assertThat(weight.current?.weightKg).isEqualTo(85.5)
        assertThat(weight.delta30d!!).isWithin(1e-9).of(-4.5) // 85,5 − 90,0
    }

    // ---------- Прогресс упражнения ----------

    @Test
    fun exerciseProgress_pointIsMaxWeightPerWorkout_recordsFromHistory() = runTest {
        val now = System.currentTimeMillis()
        val e = exercise()
        val w1Start = now - 10 * day
        val w2Start = now - 5 * day
        // Тренировка 1: 100×5 и 105×3 → точка 105.
        val w1 = workoutRepo.startWorkout(now = w1Start)
        workoutRepo.addSet(w1, e, 100.0, 5, now = w1Start + min)
        workoutRepo.addSet(w1, e, 105.0, 3, now = w1Start + 2 * min)
        workoutRepo.finishWorkout(w1, bodyWeightKg = null, now = w1Start + hour)
        // Тренировка 2: 110×2 → точка 110.
        val w2 = workoutRepo.startWorkout(now = w2Start)
        workoutRepo.addSet(w2, e, 110.0, 2, now = w2Start + min)
        workoutRepo.finishWorkout(w2, bodyWeightKg = null, now = w2Start + hour)
        // Активная тренировка в историю прогресса не входит.
        val wActive = workoutRepo.startWorkout(now = now - hour)
        workoutRepo.addSet(wActive, e, 999.0, 1, now = now - hour + min)

        val vm = createVm()
        backgroundScope.launch { vm.exerciseProgress.collect { } }
        advanceUntilIdle()
        // До выбора упражнения прогресса нет.
        assertThat(vm.exerciseProgress.value).isNull()

        val exerciseEntity = db.exerciseDao().getById(e)!!
        vm.selectExercise(exerciseEntity)
        advanceUntilIdle()

        assertThat(vm.selectedExercise.value).isEqualTo(exerciseEntity)
        val progress = vm.exerciseProgress.value
        assertThat(progress).isNotNull()
        // Точка — максимальный вес подхода в каждой завершённой тренировке.
        assertThat(progress!!.points)
            .containsExactly(w1Start to 105.0, w2Start to 110.0)
            .inOrder()
        assertThat(progress.recordWeightKg).isEqualTo(110.0)
        assertThat(progress.recordReps).isEqualTo(2)
    }
}
