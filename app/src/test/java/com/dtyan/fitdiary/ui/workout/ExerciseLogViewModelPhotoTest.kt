package com.dtyan.fitdiary.ui.workout

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dtyan.fitdiary.MainDispatcherRule
import com.dtyan.fitdiary.data.RestTimerController
import com.dtyan.fitdiary.data.SettingsStore
import com.dtyan.fitdiary.data.db.AppDatabase
import com.dtyan.fitdiary.data.db.Exercise
import com.dtyan.fitdiary.data.repo.ExerciseRepository
import com.dtyan.fitdiary.data.repo.WorkoutRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Тесты фото/оборудования в ExerciseLogViewModel: паттерн ExerciseLogViewModelTest —
 * реальная in-memory Room-БД с executor-ами на TestCoroutineScheduler,
 * подменённый Main (MainDispatcherRule), реальные репозитории.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ExerciseLogViewModelPhotoTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private lateinit var db: AppDatabase
    private lateinit var workoutRepo: WorkoutRepository
    private lateinit var exerciseRepo: ExerciseRepository
    private lateinit var settingsStore: SettingsStore
    private lateinit var timerScope: CoroutineScope
    private lateinit var restTimer: RestTimerController

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
        settingsStore = SettingsStore(context)
        timerScope = CoroutineScope(SupervisorJob() + mainRule.dispatcher)
        restTimer = RestTimerController(scope = timerScope, onFinished = {})
    }

    @After
    fun tearDown() {
        timerScope.cancel()
        db.close()
    }

    private suspend fun exercise(photoPath: String? = null): Long = db.exerciseDao().insert(
        Exercise(
            name = "Жим ногами",
            muscleGroup = "Ноги",
            equipment = "Тренажёр",
            photoPath = photoPath,
            isCustom = true,
        )
    )

    private fun createVm(workoutId: Long, exerciseId: Long) = ExerciseLogViewModel(
        workoutId = workoutId,
        exerciseId = exerciseId,
        workoutRepository = workoutRepo,
        exerciseRepository = exerciseRepo,
        settingsStore = settingsStore,
        restTimer = restTimer,
    )

    @Test
    fun init_loadsEquipmentAndPhotoPathFromExercise() = runTest {
        val e = exercise(photoPath = "exercise_photos/leg-press.jpg")
        val w = workoutRepo.startWorkout(now = 1_000)

        val vm = createVm(w, e)
        advanceUntilIdle()

        val ui = vm.uiState.value
        assertThat(ui.exerciseName).isEqualTo("Жим ногами")
        assertThat(ui.equipment).isEqualTo("Тренажёр")
        assertThat(ui.photoPath).isEqualTo("exercise_photos/leg-press.jpg")
    }

    @Test
    fun init_withoutPhoto_photoPathIsNull() = runTest {
        val e = exercise(photoPath = null)
        val w = workoutRepo.startWorkout(now = 1_000)

        val vm = createVm(w, e)
        advanceUntilIdle()

        assertThat(vm.uiState.value.photoPath).isNull()
        assertThat(vm.uiState.value.equipment).isEqualTo("Тренажёр")
    }

    @Test
    fun updatePhoto_setAndReplace_updatesUiStateAndDb() = runTest {
        val e = exercise(photoPath = null)
        val w = workoutRepo.startWorkout(now = 1_000)
        val vm = createVm(w, e)
        advanceUntilIdle()

        // Установка фото.
        vm.updatePhoto("exercise_photos/new.jpg")
        advanceUntilIdle()
        assertThat(vm.uiState.value.photoPath).isEqualTo("exercise_photos/new.jpg")
        assertThat(db.exerciseDao().getById(e)!!.photoPath).isEqualTo("exercise_photos/new.jpg")

        // Замена фото.
        vm.updatePhoto("exercise_photos/replaced.jpg")
        advanceUntilIdle()
        assertThat(vm.uiState.value.photoPath).isEqualTo("exercise_photos/replaced.jpg")
        assertThat(db.exerciseDao().getById(e)!!.photoPath).isEqualTo("exercise_photos/replaced.jpg")

        // Остальные поля упражнения не задеты.
        assertThat(db.exerciseDao().getById(e)!!.equipment).isEqualTo("Тренажёр")
    }

    @Test
    fun updatePhoto_null_clearsUiStateAndDb() = runTest {
        val e = exercise(photoPath = "exercise_photos/old.jpg")
        val w = workoutRepo.startWorkout(now = 1_000)
        val vm = createVm(w, e)
        advanceUntilIdle()
        assertThat(vm.uiState.value.photoPath).isEqualTo("exercise_photos/old.jpg")

        vm.updatePhoto(null)
        advanceUntilIdle()

        assertThat(vm.uiState.value.photoPath).isNull()
        assertThat(db.exerciseDao().getById(e)!!.photoPath).isNull()
    }
}
