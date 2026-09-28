package com.dtyan.fitdiary.ui.workout

import android.content.Context
import androidx.room.Room
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.dtyan.fitdiary.MainDispatcherRule
import com.dtyan.fitdiary.data.db.AppDatabase
import com.dtyan.fitdiary.data.db.Exercise
import com.dtyan.fitdiary.data.db.Athlete
import com.dtyan.fitdiary.data.repo.ExerciseRepository
import com.dtyan.fitdiary.data.repo.WorkoutRepository
import com.dtyan.fitdiary.ui.common.Format
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneId
import java.time.ZonedDateTime

/** Реальная Room-БД: префилл, изоляция участников, черновики и точная отмена записи. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ExerciseLogViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private lateinit var db: AppDatabase
    private lateinit var workoutRepo: WorkoutRepository
    private lateinit var exerciseRepo: ExerciseRepository

    /** Фиксированное время: 2026-08-01T10:00 Europe/Moscow. */
    private val t0: Long = ZonedDateTime
        .of(2026, 8, 1, 10, 0, 0, 0, ZoneId.of("Europe/Moscow"))
        .toInstant().toEpochMilli()

    private val min = 60_000L
    private val hour = 3_600_000L

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

    private suspend fun exercise(name: String = "Жим лёжа", group: String = "Грудь"): Long =
        db.exerciseDao().insert(Exercise(name = name, muscleGroup = group, isCustom = true))

    private fun createVm(workoutId: Long, exerciseId: Long) = ExerciseLogViewModel(
        workoutId = workoutId,
        exerciseId = exerciseId,
        workoutRepository = workoutRepo,
        exerciseRepository = exerciseRepo,
    )

    /** Прошлая завершённая тренировка: 100×5, затем 112,5×3. Возвращает её id. */
    private suspend fun previousWorkoutWithHistory(exerciseId: Long): Long {
        val w = workoutRepo.startWorkout(now = t0)
        workoutRepo.addSet(w, exerciseId, 100.0, 5, now = t0 + min)
        workoutRepo.addSet(w, exerciseId, 112.5, 3, now = t0 + 2 * min)
        workoutRepo.finishWorkout(w, bodyWeightKg = null, now = t0 + 50 * min)
        return w
    }

    // ---------- Префилл полей ввода ----------

    @Test
    fun prefill_noHistory_defaults20kgAnd10Reps() = runTest {
        val e = exercise()
        val w = workoutRepo.startWorkout(now = t0)

        val vm = createVm(w, e)
        advanceUntilIdle()

        assertThat(vm.weightText.value).isEqualTo("20")
        assertThat(vm.repsText.value).isEqualTo("10")
        val ui = vm.uiState.value
        assertThat(ui.exerciseName).isEqualTo("Жим лёжа")
        assertThat(ui.muscleGroup).isEqualTo("Грудь")
        assertThat(ui.recordWeightKg).isNull()
        assertThat(ui.previousSets).isEmpty()
        assertThat(ui.previousDateText).isNull()
        assertThat(ui.previousLoaded).isTrue()
    }

    @Test
    fun prefill_withPreviousWorkout_takesItsLastSet() = runTest {
        val e = exercise()
        previousWorkoutWithHistory(e)
        val w2 = workoutRepo.startWorkout(now = t0 + 24 * hour)

        val vm = createVm(w2, e)
        advanceUntilIdle()

        // Последний подход прошлой тренировки: 112,5×3.
        assertThat(vm.weightText.value).isEqualTo("112,5")
        assertThat(vm.repsText.value).isEqualTo("3")

        val ui = vm.uiState.value
        assertThat(ui.recordWeightKg).isEqualTo(112.5)
        assertThat(ui.previousSets.map { it.weightKg }).containsExactly(100.0, 112.5).inOrder()
        assertThat(ui.previousSets.map { it.reps }).containsExactly(5, 3).inOrder()
        assertThat(ui.previousDateText)
            .isEqualTo(Format.dateShort(Format.local(t0).toLocalDate()))
        assertThat(ui.previousLoaded).isTrue()
    }

    @Test
    fun prefill_setInCurrentWorkout_beatsPreviousWorkout() = runTest {
        val e = exercise()
        previousWorkoutWithHistory(e)
        val w2 = workoutRepo.startWorkout(now = t0 + 24 * hour)
        workoutRepo.addSet(w2, e, 60.0, 8, now = t0 + 24 * hour + min)

        val vm = createVm(w2, e)
        advanceUntilIdle()

        assertThat(vm.weightText.value).isEqualTo("60")
        assertThat(vm.repsText.value).isEqualTo("8")
    }

    // ---------- addSet ----------

    @Test
    fun addSet_doubleTap_recordsOneSetWithSnapshotValues() = runTest {
        val e = exercise()
        val w = workoutRepo.startWorkout(now = t0)
        val vm = createVm(w, e)
        advanceUntilIdle()
        vm.onWeightTextChange("62,5")
        vm.onRepsTextChange("5")
        var callbacks = 0
        vm.addSet { callbacks++ }
        vm.onWeightTextChange("100")
        vm.addSet { callbacks++ }
        advanceUntilIdle()
        val rows = db.workoutSetDao().getForWorkoutOnce(w)
        assertThat(rows).hasSize(1)
        assertThat(rows.single().weightKg).isEqualTo(62.5)
        assertThat(callbacks).isEqualTo(1)
        assertThat(vm.uiState.value.saving).isFalse()
    }
    @Test
    fun addSet_veryFirstSetInHistory_noPrEvent() = runTest {
        val e = exercise()
        val w = workoutRepo.startWorkout(now = t0)
        val vm = createVm(w, e)
        val prEvents = mutableListOf<Unit>()
        backgroundScope.launch { vm.prEvents.collect { prEvents += it } }
        advanceUntilIdle()

        vm.onWeightTextChange("100")
        vm.onRepsTextChange("5")
        vm.addSet()
        advanceUntilIdle()

        assertThat(prEvents).isEmpty()
        assertThat(db.workoutSetDao().getForWorkoutOnce(w)).hasSize(1)
    }

    @Test
    fun addSet_newWeightRecord_emitsPrEvent_andRefreshesRecordInUiState() = runTest {
        val e = exercise()
        previousWorkoutWithHistory(e) // рекорд в истории: 112,5
        val w2 = workoutRepo.startWorkout(now = t0 + 24 * hour)
        val vm = createVm(w2, e)
        val prEvents = mutableListOf<Unit>()
        backgroundScope.launch { vm.prEvents.collect { prEvents += it } }
        advanceUntilIdle()
        assertThat(vm.uiState.value.recordWeightKg).isEqualTo(112.5)

        vm.onWeightTextChange("120")
        vm.onRepsTextChange("2")
        vm.addSet()
        advanceUntilIdle()
        runCurrent() // Deliver the buffered PR event to the background collector.

        assertThat(prEvents).hasSize(1)
        // recordWeightKg обновился после более тяжёлого подхода.
        assertThat(vm.uiState.value.recordWeightKg).isEqualTo(120.0)

        // Более лёгкий подход нового события не даёт и рекорд не сбивает.
        vm.onWeightTextChange("80")
        vm.onRepsTextChange("5")
        vm.addSet()
        advanceUntilIdle()
        runCurrent()
        assertThat(prEvents).hasSize(1)
        assertThat(vm.uiState.value.recordWeightKg).isEqualTo(120.0)
    }

    // ---------- bumpWeight / bumpReps ----------

    @Test
    fun bumpWeight_steps2point5_floorsAtZero_parsesComma() = runTest {
        val e = exercise()
        val w = workoutRepo.startWorkout(now = t0)
        val vm = createVm(w, e)
        advanceUntilIdle() // префилл «20»

        vm.bumpWeight(2.5)
        assertThat(vm.weightText.value).isEqualTo("22,5")
        vm.bumpWeight(-2.5)
        assertThat(vm.weightText.value).isEqualTo("20")

        // Нижняя граница: вес не уходит в минус.
        vm.onWeightTextChange("1")
        vm.bumpWeight(-2.5)
        assertThat(vm.weightText.value).isEqualTo("0")

        // Значение с запятой парсится как дробное.
        vm.onWeightTextChange("62,5")
        vm.bumpWeight(2.5)
        assertThat(vm.weightText.value).isEqualTo("65")
    }

    @Test
    fun bumpReps_steps1_floorsAtOne() = runTest {
        val e = exercise()
        val w = workoutRepo.startWorkout(now = t0)
        val vm = createVm(w, e)
        advanceUntilIdle() // префилл «10»

        vm.bumpReps(1)
        assertThat(vm.repsText.value).isEqualTo("11")
        vm.bumpReps(-1)
        assertThat(vm.repsText.value).isEqualTo("10")

        // Нижняя граница: повторов не меньше одного.
        vm.onRepsTextChange("1")
        vm.bumpReps(-1)
        assertThat(vm.repsText.value).isEqualTo("1")

        // Мусор в поле трактуется как 0 → после инкремента снова минимум 1.
        vm.onRepsTextChange("")
        vm.bumpReps(-1)
        assertThat(vm.repsText.value).isEqualTo("1")
    }

    @Test
    fun participants_keepOwnPrefillDraftAndUndoTarget() = runTest {
        db.athleteDao().insert(Athlete(id = 1, name = "Я"))
        db.athleteDao().insert(Athlete(id = 2, name = "Друг"))
        val e = exercise()
        val firstHistory = workoutRepo.startWorkout(now = t0, athleteId = 1)
        workoutRepo.addSet(firstHistory, e, 100.0, 5)
        workoutRepo.finishWorkout(firstHistory)
        val friendHistory = workoutRepo.startWorkout(now = t0, athleteId = 2)
        workoutRepo.addSet(friendHistory, e, 40.0, 12)
        workoutRepo.finishWorkout(friendHistory)
        val members = workoutRepo.startGroupWorkout(listOf(1, 2))
        val mine = createVm(members.first { it.athleteId == 1L }.id, e)
        val friend = createVm(members.first { it.athleteId == 2L }.id, e)
        advanceUntilIdle()
        assertThat(mine.weightText.value).isEqualTo("100")
        assertThat(friend.weightText.value).isEqualTo("40")
        assertThat(friend.uiState.value.recordWeightKg).isEqualTo(40.0)
        mine.onWeightTextChange("105")
        friend.onWeightTextChange("42,5")
        var saved: com.dtyan.fitdiary.data.db.WorkoutSet? = null
        mine.addSet { saved = it }
        friend.addSet()
        advanceUntilIdle()
        mine.deleteSet(saved!!)
        advanceUntilIdle()
        assertThat(db.workoutSetDao().getForWorkoutOnce(members.first { it.athleteId == 1L }.id)).isEmpty()
        val friendsSets = db.workoutSetDao().getForWorkoutOnce(members.first { it.athleteId == 2L }.id)
        assertThat(friendsSets).hasSize(1)
        assertThat(friendsSets.single().weightKg).isEqualTo(42.5)
        assertThat(mine.weightText.value).isEqualTo("105")
    }

    @Test
    fun restoredDraft_isNotOverwrittenByHistoryPrefill() = runTest {
        val e = exercise()
        val w = workoutRepo.startWorkout(now = t0)
        val handle = SavedStateHandle(mapOf("weight" to "37,5", "reps" to "7", "touched" to true))
        val vm = ExerciseLogViewModel(w, e, workoutRepo, exerciseRepo, handle)
        advanceUntilIdle()
        assertThat(vm.weightText.value).isEqualTo("37,5")
        assertThat(vm.repsText.value).isEqualTo("7")
    }

    @Test
    fun nonFiniteWeight_doesNotRecordOrCallSuccess() = runTest {
        val e = exercise()
        val w = workoutRepo.startWorkout(now = t0)
        val vm = createVm(w, e)
        advanceUntilIdle()
        vm.onWeightTextChange("Infinity")
        var called = false
        vm.addSet { called = true }
        advanceUntilIdle()
        assertThat(called).isFalse()
        assertThat(db.workoutSetDao().getForWorkoutOnce(w)).isEmpty()
    }
}
