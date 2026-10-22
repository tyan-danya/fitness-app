package com.dtyan.fitdiary.ui.workout

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dtyan.fitdiary.MainDispatcherRule
import com.dtyan.fitdiary.data.db.AppDatabase
import com.dtyan.fitdiary.data.db.Athlete
import com.dtyan.fitdiary.data.db.Exercise
import com.dtyan.fitdiary.data.repo.ExerciseRepository
import com.dtyan.fitdiary.data.repo.WorkoutRepository
import com.dtyan.fitdiary.domain.ManualStepRegistry
import com.dtyan.fitdiary.domain.WeightSteps
import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneId
import java.time.ZonedDateTime

/** Шаг веса по собственным записям: реальная Room-БД, история подходов и экран упражнения. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ExerciseLogWeightStepTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private lateinit var db: AppDatabase
    private lateinit var workoutRepo: WorkoutRepository
    private lateinit var exerciseRepo: ExerciseRepository

    private val t0: Long = ZonedDateTime
        .of(2026, 8, 1, 10, 0, 0, 0, ZoneId.of("Europe/Moscow"))
        .toInstant().toEpochMilli()
    private val min = 60_000L
    private val day = 24 * 3_600_000L

    /** «Сведение рук (бабочка)»: шкала через 5 кг от 4 (29, 34, 39 …), пара промахов. */
    private val butterfly = mapOf(29.0 to 2, 34.0 to 5, 39.0 to 10, 43.0 to 1, 44.0 to 6, 46.0 to 1,
        49.0 to 11, 54.0 to 9, 59.0 to 4, 64.0 to 2, 69.0 to 2, 79.0 to 1)
    private val benchPress = mapOf(40.0 to 13, 50.0 to 4, 55.0 to 1, 60.0 to 12, 70.0 to 12, 75.0 to 1,
        80.0 to 8, 85.0 to 1, 87.5 to 1, 90.0 to 1)
    private val doubleFly = mapOf(12.0 to 2, 16.0 to 1, 20.0 to 1, 24.0 to 1, 28.0 to 1)
    private val lowerTriceps = mapOf(12.0 to 3, 14.0 to 2, 16.0 to 3, 18.0 to 2, 20.0 to 3, 22.0 to 2)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val executor = mainRule.dispatcher.asExecutor()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(executor)
            .setTransactionExecutor(executor)
            .build()
        workoutRepo = WorkoutRepository(db.workoutDao(), db.workoutSetDao(), db.weightDao())
        exerciseRepo = ExerciseRepository(db.exerciseDao(), db.workoutSetDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun exercise(name: String, equipment: String, step: Double = 2.5): Long =
        db.exerciseDao().insert(Exercise(name = name, muscleGroup = "Грудь", equipment = equipment,
            isCustom = true, weightStepKg = step))

    /** Завершённая тренировка профиля [athleteId] с подходами «вес → сколько раз». */
    private suspend fun history(exerciseId: Long, counts: Map<Double, Int>, athleteId: Long = 1L, start: Long = t0) {
        val w = workoutRepo.startWorkout(now = start, athleteId = athleteId)
        var minute = 1L
        counts.forEach { (weight, sets) ->
            repeat(sets) { workoutRepo.addSet(w, exerciseId, weight, 10, now = start + minute++ * min) }
        }
        workoutRepo.finishWorkout(w, now = start + (minute + 1) * min)
    }

    private fun createVm(workoutId: Long, exerciseId: Long) =
        ExerciseLogViewModel(workoutId, exerciseId, workoutRepo, exerciseRepo)

    private suspend fun storedStep(exerciseId: Long) = db.exerciseDao().getById(exerciseId)!!.weightStepKg

    @Test
    fun machineWithDefaultStep_takesStepFromHistoryOfAllProfiles_once() = runTest {
        db.athleteDao().insert(Athlete(id = 1, name = "Даня"))
        val friend = db.athleteDao().insert(Athlete(name = "Миша"))
        val e = exercise("Сведение рук (бабочка)", "Тренажёр")
        // Каталог общий: шкала тренажёра складывается из записей обоих профилей.
        history(e, butterfly.filterKeys { it < 50 })
        history(e, butterfly.filterKeys { it >= 50 }, athleteId = friend)
        assertThat(exerciseRepo.weightUsage(e)).isEqualTo(butterfly)

        val w = workoutRepo.startWorkout(now = t0 + day)
        val vm = createVm(w, e)
        advanceUntilIdle()

        val ui = vm.uiState.value
        assertThat(ui.weightStepKg).isEqualTo(5.0)
        assertThat(ui.stepGuess?.step).isEqualTo(5.0)
        assertThat(ui.stepGuess?.base).isEqualTo(4.0)
        assertThat(ui.stepBase).isEqualTo(4.0)
        assertThat(ui.stepFromHistory).isTrue()
        assertThat(ui.stepMatchesGuess).isTrue()
        assertThat(storedStep(e)).isEqualTo(5.0)

        // Повторное открытие: шаг уже не по умолчанию, подсказка «определён по записям» не повторяется.
        val reopened = createVm(w, e)
        advanceUntilIdle()
        assertThat(reopened.uiState.value.weightStepKg).isEqualTo(5.0)
        assertThat(reopened.uiState.value.stepFromHistory).isFalse()
        assertThat(reopened.uiState.value.stepMatchesGuess).isTrue()
        assertThat(reopened.uiState.value.stepBase).isEqualTo(4.0)
    }

    @Test
    fun cableWithDefaultStep_isAutoApplied() = runTest {
        val e = exercise("Нижний трицепс", "Блок")
        history(e, lowerTriceps)
        val vm = createVm(workoutRepo.startWorkout(now = t0 + day), e)
        advanceUntilIdle()
        assertThat(vm.uiState.value.weightStepKg).isEqualTo(2.0)
        assertThat(vm.uiState.value.stepBase).isEqualTo(0.0)
        assertThat(vm.uiState.value.stepFromHistory).isTrue()
        assertThat(storedStep(e)).isEqualTo(2.0)
    }

    @Test
    fun barbell_onlyGetsSuggestion_stepUnchanged() = runTest {
        val e = exercise("Жим штанги лёжа", "Штанга")
        history(e, benchPress)
        val vm = createVm(workoutRepo.startWorkout(now = t0 + day), e)
        advanceUntilIdle()

        val ui = vm.uiState.value
        assertThat(ui.stepGuess?.step).isEqualTo(5.0)
        assertThat(ui.weightStepKg).isEqualTo(2.5)
        assertThat(ui.stepFromHistory).isFalse()
        assertThat(ui.stepMatchesGuess).isFalse()
        assertThat(ui.stepBase).isEqualTo(0.0)
        assertThat(storedStep(e)).isEqualTo(2.5)
    }

    @Test
    fun chosenStep_isNeverOverwritten() = runTest {
        // Шаг 4 выбран человеком, хотя записи говорят 5.
        val four = exercise("Сведение рук (бабочка)", "Тренажёр", step = 4.0)
        history(four, butterfly)
        // Шаг 5 выбран человеком, хотя записи говорят 4.
        val five = exercise("Сведение рук двойное", "Тренажёр", step = 5.0)
        history(five, doubleFly, start = t0 + day)
        val w = workoutRepo.startWorkout(now = t0 + 2 * day)

        val vmFour = createVm(w, four)
        val vmFive = createVm(w, five)
        advanceUntilIdle()

        assertThat(vmFour.uiState.value.stepGuess?.step).isEqualTo(5.0)
        assertThat(vmFour.uiState.value.weightStepKg).isEqualTo(4.0)
        assertThat(vmFour.uiState.value.stepFromHistory).isFalse()
        assertThat(vmFour.uiState.value.stepBase).isEqualTo(WeightSteps.base(butterfly, 4.0))
        assertThat(storedStep(four)).isEqualTo(4.0)

        assertThat(vmFive.uiState.value.stepGuess?.step).isEqualTo(4.0)
        assertThat(vmFive.uiState.value.weightStepKg).isEqualTo(5.0)
        assertThat(vmFive.uiState.value.stepFromHistory).isFalse()
        assertThat(storedStep(five)).isEqualTo(5.0)
    }

    @Test
    fun bumpWeight_snapsToBase4Grid() = runTest {
        val e = exercise("Сведение рук (бабочка)", "Тренажёр")
        history(e, butterfly)
        val vm = createVm(workoutRepo.startWorkout(now = t0 + day), e)
        advanceUntilIdle()
        val step = vm.uiState.value.weightStepKg
        assertThat(step).isEqualTo(5.0)

        vm.onWeightTextChange("29")
        vm.bumpWeight(step)
        assertThat(vm.weightText.value).isEqualTo("34")
        vm.bumpWeight(-step)
        assertThat(vm.weightText.value).isEqualTo("29")

        vm.onWeightTextChange("43")
        vm.bumpWeight(step)
        assertThat(vm.weightText.value).isEqualTo("44")
        vm.onWeightTextChange("43")
        vm.bumpWeight(-step)
        assertThat(vm.weightText.value).isEqualTo("39")

        vm.onWeightTextChange("0")
        vm.bumpWeight(-step)
        assertThat(vm.weightText.value).isEqualTo("0")
        vm.bumpWeight(step)
        assertThat(vm.weightText.value).isEqualTo("4")
    }

    @Test
    fun customStep_persists_andRecomputesBase() = runTest {
        val e = exercise("Сведение рук (бабочка)", "Тренажёр")
        history(e, butterfly)
        val w = workoutRepo.startWorkout(now = t0 + day)
        val vm = createVm(w, e)
        advanceUntilIdle()
        assertThat(vm.uiState.value.stepFromHistory).isTrue()

        vm.setWeightStep(2.5)
        advanceUntilIdle()
        assertThat(vm.uiState.value.weightStepKg).isEqualTo(2.5)
        assertThat(vm.uiState.value.stepBase).isEqualTo(1.5) // 29, 34, 39 … = 1,5 + k·2,5
        assertThat(vm.uiState.value.stepFromHistory).isFalse()

        vm.setWeightStep(3.5)
        advanceUntilIdle()
        assertThat(storedStep(e)).isEqualTo(3.5)
        assertThat(vm.uiState.value.weightStepKg).isEqualTo(3.5)
        assertThat(vm.uiState.value.stepMatchesGuess).isFalse()
        assertThat(vm.uiState.value.stepBase).isEqualTo(WeightSteps.base(butterfly, 3.5))

        // Вне 0,1–50 кг шаг не сохраняется.
        vm.setWeightStep(60.0)
        vm.setWeightStep(0.05)
        vm.setWeightStep(Double.NaN)
        advanceUntilIdle()
        assertThat(storedStep(e)).isEqualTo(3.5)

        // Свой шаг переживает повторное открытие: авто-подбор его не трогает.
        val reopened = createVm(w, e)
        advanceUntilIdle()
        assertThat(reopened.uiState.value.weightStepKg).isEqualTo(3.5)
        assertThat(reopened.uiState.value.stepFromHistory).isFalse()
        assertThat(storedStep(e)).isEqualTo(3.5)
    }

    @Test
    fun noHistory_keepsDefaultStepWithoutGuess() = runTest {
        val e = exercise("Новый тренажёр", "Тренажёр")
        val vm = createVm(workoutRepo.startWorkout(now = t0), e)
        advanceUntilIdle()
        val ui = vm.uiState.value
        assertThat(ui.stepGuess).isNull()
        assertThat(ui.weightStepKg).isEqualTo(2.5)
        assertThat(ui.stepBase).isEqualTo(0.0)
        assertThat(ui.stepFromHistory).isFalse()
        vm.bumpWeight(2.5)
        assertThat(vm.weightText.value).isEqualTo("22,5")
    }

    @Test
    fun manuallyChosenDefaultStep_isNotReplacedByHistoryAgain() = runTest {
        val registry = object : ManualStepRegistry {
            val ids = mutableSetOf<Long>()
            override fun isWeightStepManual(exerciseId: Long) = exerciseId in ids
            override fun markWeightStepManual(exerciseId: Long) { ids += exerciseId }
        }
        val e = exercise("Сведение рук (бабочка)", "Тренажёр")
        history(e, butterfly)
        val w = workoutRepo.startWorkout(now = t0 + day)
        val vm = ExerciseLogViewModel(w, e, workoutRepo, exerciseRepo, SavedStateHandle(), registry)
        advanceUntilIdle()
        assertThat(storedStep(e)).isEqualTo(5.0)

        // Человек сознательно вернул 2,5 кг — это его выбор, а не значение по умолчанию.
        vm.setWeightStep(2.5)
        advanceUntilIdle()
        assertThat(registry.ids).containsExactly(e)
        val reopened = ExerciseLogViewModel(w, e, workoutRepo, exerciseRepo, SavedStateHandle(), registry)
        advanceUntilIdle()
        assertThat(reopened.uiState.value.weightStepKg).isEqualTo(2.5)
        assertThat(reopened.uiState.value.stepFromHistory).isFalse()
        assertThat(storedStep(e)).isEqualTo(2.5)
    }
}
