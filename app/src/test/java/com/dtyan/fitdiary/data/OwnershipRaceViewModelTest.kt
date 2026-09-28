package com.dtyan.fitdiary.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dtyan.fitdiary.MainDispatcherRule
import com.dtyan.fitdiary.data.db.AppDatabase
import com.dtyan.fitdiary.data.db.Athlete
import com.dtyan.fitdiary.data.repo.ExerciseRepository
import com.dtyan.fitdiary.data.repo.MeasurementRepository
import com.dtyan.fitdiary.data.repo.NutritionRepository
import com.dtyan.fitdiary.data.repo.StatsRepository
import com.dtyan.fitdiary.data.repo.WorkoutRepository
import com.dtyan.fitdiary.domain.MeasurementType
import com.dtyan.fitdiary.ui.home.HomeViewModel
import com.dtyan.fitdiary.ui.measure.MeasurementsViewModel
import com.dtyan.fitdiary.ui.stats.StatsViewModel
import com.dtyan.fitdiary.ui.workout.ExercisePickerViewModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.MutableStateFlow
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

/** Events are queued on purpose; switch profile before their coroutine gets a turn. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OwnershipRaceViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private lateinit var db: AppDatabase
    private lateinit var context: Context
    private lateinit var workouts: WorkoutRepository
    private lateinit var stats: StatsRepository
    private lateinit var measurements: MeasurementRepository
    private lateinit var nutrition: NutritionRepository
    private lateinit var exercises: ExerciseRepository
    private val active = MutableStateFlow(1L)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("fitdiary_settings", Context.MODE_PRIVATE).edit().clear().commit()
        active.value = 1L
        val executor = main.dispatcher.asExecutor()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor(executor).setTransactionExecutor(executor).build()
        workouts = WorkoutRepository(db.workoutDao(), db.workoutSetDao(), db.weightDao(), active)
        stats = StatsRepository(db.workoutDao(), db.workoutSetDao(), db.weightDao(), active)
        measurements = MeasurementRepository(db.measurementDao(), active)
        nutrition = NutritionRepository(db.mealDao(), active, db)
        exercises = ExerciseRepository(db.exerciseDao(), db.workoutSetDao(), active)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun profiles() {
        db.athleteDao().insert(Athlete(id = 1L, name = "Я"))
        db.athleteDao().insert(Athlete(id = 2L, name = "Друг"))
    }

    @Test
    fun queuedWeightWriteKeepsProfileSelectedAtClick() = runTest {
        profiles()
        val vm = StatsViewModel(context, workouts, nutrition, stats, exercises, measurements)
        vm.addWeight(80.0)
        active.value = 2L
        advanceUntilIdle()
        assertThat(stats.getWeightHistoryOnce(1L).single().weightKg).isEqualTo(80.0)
        assertThat(stats.getWeightHistoryOnce(2L)).isEmpty()
    }

    @Test
    fun queuedMeasurementSaveKeepsClickOwnerAndTimestamp() = runTest {
        profiles()
        val settings = SettingsStore(context, active)
        var clock = 1_000L
        val vm = MeasurementsViewModel(measurements, settings, now = { clock })
        runCurrent()
        vm.openEditor()
        vm.onValueChange(MeasurementType.WAIST, "82")
        vm.onNoteChange("Мой замер")
        vm.saveEditor()
        active.value = 2L
        clock = 99_000L
        advanceUntilIdle()
        val saved = measurements.getAllOnce(1L).single()
        assertThat(saved.valueCm).isEqualTo(82.0)
        assertThat(saved.note).isEqualTo("Мой замер")
        assertThat(saved.timestamp).isEqualTo(1_000L)
        assertThat(measurements.getAllOnce(2L)).isEmpty()
    }

    @Test
    fun queuedSoloWorkoutStartKeepsClickOwner() = runTest {
        profiles()
        val vm = HomeViewModel(workouts, stats)
        var opened: Long? = null
        vm.startWorkout { opened = it }
        active.value = 2L
        advanceUntilIdle()
        val saved = db.workoutDao().getActiveOnce(1L)
        assertThat(saved).isNotNull()
        assertThat(saved!!.id).isEqualTo(opened)
        assertThat(db.workoutDao().getActiveOnce(2L)).isNull()
    }

    @Test
    fun delayedMeasurementSheetCallbackKeepsEditorsOriginalOwner() = runTest {
        profiles()
        val vm = MeasurementsViewModel(measurements, SettingsStore(context, active))
        runCurrent()
        vm.openEditor()
        vm.onValueChange(MeasurementType.WAIST, "82")
        // Simulate the profile changing while the sheet is hiding, before onSave fires.
        active.value = 2L
        vm.saveEditor()
        advanceUntilIdle()
        assertThat(measurements.getAllOnce(1L).single().valueCm).isEqualTo(82.0)
        assertThat(measurements.getAllOnce(2L)).isEmpty()
    }

    @Test
    fun queuedDeleteStillTargetsExactMeasurementAfterSwitch() = runTest {
        profiles()
        measurements.addSession(mapOf(MeasurementType.WAIST to 82.0), now = 1L, athleteId = 1L)
        measurements.addSession(mapOf(MeasurementType.WAIST to 95.0), now = 2L, athleteId = 2L)
        val target = measurements.getAllOnce(1L).single()
        val vm = MeasurementsViewModel(measurements, SettingsStore(context, active))
        vm.delete(target)
        active.value = 2L
        advanceUntilIdle()
        assertThat(measurements.getAllOnce(1L)).isEmpty()
        assertThat(measurements.getAllOnce(2L).single().valueCm).isEqualTo(95.0)
    }

    @Test
    fun exercisePickerRecentListUsesRoutedWorkoutOwnerInsteadOfGlobalProfile() = runTest {
        profiles()
        val mineExercise = exercises.addCustom("Мой жим", "Грудь")
        val friendExercise = exercises.addCustom("Тяга друга", "Спина")
        val mine = workouts.startWorkout(now = 1L, athleteId = 1L)
        val theirs = workouts.startWorkout(now = 2L, athleteId = 2L)
        workouts.addSet(mine, mineExercise, 50.0, 10, now = 3L)
        workouts.addSet(theirs, friendExercise, 90.0, 10, now = 4L)
        active.value = 2L
        val vm = ExercisePickerViewModel(exercises, mine, workouts)
        backgroundScope.launch { vm.uiState.collect { } }
        advanceUntilIdle()
        assertThat(vm.uiState.value.recent.map { it.id }).containsExactly(mineExercise)
    }
}
