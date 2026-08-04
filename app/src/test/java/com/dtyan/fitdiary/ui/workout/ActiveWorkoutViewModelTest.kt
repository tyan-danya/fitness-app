package com.dtyan.fitdiary.ui.workout

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dtyan.fitdiary.MainDispatcherRule
import com.dtyan.fitdiary.data.RestTimerController
import com.dtyan.fitdiary.data.db.AppDatabase
import com.dtyan.fitdiary.data.db.Exercise
import com.dtyan.fitdiary.data.repo.StatsRepository
import com.dtyan.fitdiary.data.repo.WorkoutRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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

/**
 * Тесты ActiveWorkoutViewModel поверх реальной in-memory Room-БД.
 * Сводку диалога завершения (тоннаж/подходы/упражнения) VM отдельно не отдаёт —
 * её считает экран из vm.sets (ActiveWorkoutScreen), поэтому здесь проверяются
 * данные, из которых она строится.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ActiveWorkoutViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private lateinit var db: AppDatabase
    private lateinit var workoutRepo: WorkoutRepository
    private lateinit var statsRepo: StatsRepository
    private lateinit var timerScope: CoroutineScope
    private lateinit var restTimer: RestTimerController

    /** Фиксированное время: 2026-08-01T10:00 Europe/Moscow. */
    private val t0: Long = ZonedDateTime
        .of(2026, 8, 1, 10, 0, 0, 0, ZoneId.of("Europe/Moscow"))
        .toInstant().toEpochMilli()

    private val min = 60_000L

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
        statsRepo = StatsRepository(db.workoutDao(), db.workoutSetDao(), db.weightDao())
        timerScope = CoroutineScope(SupervisorJob() + mainRule.dispatcher)
        restTimer = RestTimerController(scope = timerScope, onFinished = {})
    }

    @After
    fun tearDown() {
        timerScope.cancel()
        db.close()
    }

    private suspend fun exercise(name: String, group: String): Long =
        db.exerciseDao().insert(Exercise(name = name, muscleGroup = group, isCustom = true))

    private fun createVm(workoutId: Long) = ActiveWorkoutViewModel(
        workoutId = workoutId,
        workoutRepository = workoutRepo,
        statsRepository = statsRepo,
        restTimer = restTimer,
    )

    private fun countRows(table: String): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    // ---------- Группировка подходов ----------

    @Test
    fun exerciseGroups_groupedByExercise_orderedByFirstSet() = runTest {
        val eBench = exercise("Жим лёжа", "Грудь")
        val eSquat = exercise("Присед", "Ноги")
        val w = workoutRepo.startWorkout(now = t0)
        // Чередуем упражнения: порядок групп — по первому подходу.
        workoutRepo.addSet(w, eBench, 100.0, 5, now = t0 + 1 * min)
        workoutRepo.addSet(w, eSquat, 80.0, 8, now = t0 + 2 * min)
        workoutRepo.addSet(w, eBench, 105.0, 3, now = t0 + 3 * min)

        val vm = createVm(w)
        backgroundScope.launch { vm.workout.collect { } }
        backgroundScope.launch { vm.exerciseGroups.collect { } }
        advanceUntilIdle()

        assertThat(vm.workout.value?.id).isEqualTo(w)

        val groups = vm.exerciseGroups.value
        assertThat(groups).hasSize(2)

        val bench = groups[0]
        assertThat(bench.exerciseId).isEqualTo(eBench)
        assertThat(bench.name).isEqualTo("Жим лёжа")
        assertThat(bench.muscleGroup).isEqualTo("Грудь")
        // Внутри группы — все подходы упражнения в порядке выполнения.
        assertThat(bench.sets.map { it.weightKg }).containsExactly(100.0, 105.0).inOrder()
        assertThat(bench.sets.map { it.setIndex }).containsExactly(1, 2).inOrder()

        val squat = groups[1]
        assertThat(squat.exerciseId).isEqualTo(eSquat)
        assertThat(squat.name).isEqualTo("Присед")
        assertThat(squat.muscleGroup).isEqualTo("Ноги")
        assertThat(squat.sets.map { it.weightKg }).containsExactly(80.0)
    }

    // ---------- Завершение ----------

    @Test
    fun finishWorkout_withWeight_endsWorkout_createsWeightEntry_stopsTimer_closesOnce() = runTest {
        val e = exercise("Жим лёжа", "Грудь")
        val w = workoutRepo.startWorkout(now = t0)
        workoutRepo.addSet(w, e, 100.0, 5, now = t0 + min)
        val vm = createVm(w)
        restTimer.start(90)
        assertThat(restTimer.state.value).isNotNull()

        var closedCount = 0
        vm.finishWorkout(bodyWeightKg = 82.5) { closedCount++ }
        // Повторный вызов до завершения первого игнорируется (защёлка closing).
        vm.finishWorkout(bodyWeightKg = 99.0) { closedCount++ }
        advanceUntilIdle()

        assertThat(closedCount).isEqualTo(1)

        val workout = db.workoutDao().getById(w)!!
        assertThat(workout.endedAt).isNotNull()
        assertThat(workout.bodyWeightKg).isEqualTo(82.5)

        // Контрольное взвешивание попало в историю замеров.
        val entries = db.weightDao().getAllOnce()
        assertThat(entries).hasSize(1)
        assertThat(entries.single().weightKg).isEqualTo(82.5)
        assertThat(entries.single().fromWorkout).isTrue()

        // Таймер отдыха остановлен самим VM.
        assertThat(restTimer.state.value).isNull()
    }

    @Test
    fun finishWorkout_withoutWeight_noWeightEntry() = runTest {
        val w = workoutRepo.startWorkout(now = t0)
        val vm = createVm(w)

        var closed = false
        vm.finishWorkout(bodyWeightKg = null) { closed = true }
        advanceUntilIdle()

        assertThat(closed).isTrue()
        val workout = db.workoutDao().getById(w)!!
        assertThat(workout.endedAt).isNotNull()
        assertThat(workout.bodyWeightKg).isNull()
        assertThat(db.weightDao().getAllOnce()).isEmpty()
    }

    // ---------- Отмена ----------

    @Test
    fun cancelWorkout_deletesWorkoutWithSets_stopsTimer() = runTest {
        val e = exercise("Жим лёжа", "Грудь")
        val w = workoutRepo.startWorkout(now = t0)
        workoutRepo.addSet(w, e, 100.0, 5, now = t0 + min)
        workoutRepo.addSet(w, e, 100.0, 5, now = t0 + 2 * min)
        assertThat(countRows("workout_sets")).isEqualTo(2)
        val vm = createVm(w)
        restTimer.start(60)

        var closed = false
        vm.cancelWorkout { closed = true }
        advanceUntilIdle()

        assertThat(closed).isTrue()
        assertThat(db.workoutDao().getById(w)).isNull()
        assertThat(countRows("workouts")).isEqualTo(0)
        assertThat(countRows("workout_sets")).isEqualTo(0) // CASCADE
        assertThat(restTimer.state.value).isNull()
    }

    // ---------- Данные для сводки диалога завершения ----------

    @Test
    fun setsFlow_carriesDataForFinishDialogSummary() = runTest {
        val eBench = exercise("Жим лёжа", "Грудь")
        val eSquat = exercise("Присед", "Ноги")
        val w = workoutRepo.startWorkout(now = t0)
        workoutRepo.addSet(w, eBench, 100.0, 5, now = t0 + 1 * min) // 500
        workoutRepo.addSet(w, eBench, 105.0, 3, now = t0 + 2 * min) // 315
        workoutRepo.addSet(w, eSquat, 80.0, 10, now = t0 + 3 * min) // 800

        val vm = createVm(w)
        backgroundScope.launch { vm.sets.collect { } }
        advanceUntilIdle()

        // Экран считает сводку так: sets.sumOf { weightKg * reps } и т.д.
        val sets = vm.sets.value
        assertThat(sets).hasSize(3)
        assertThat(sets.sumOf { it.weightKg * it.reps }).isWithin(1e-9).of(1615.0)
        assertThat(sets.map { it.exerciseId }.distinct()).hasSize(2)
    }
}
