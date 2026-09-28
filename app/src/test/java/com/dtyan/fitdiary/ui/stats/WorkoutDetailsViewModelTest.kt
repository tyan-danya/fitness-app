package com.dtyan.fitdiary.ui.stats

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dtyan.fitdiary.MainDispatcherRule
import com.dtyan.fitdiary.data.db.AppDatabase
import com.dtyan.fitdiary.data.db.Athlete
import com.dtyan.fitdiary.data.db.Exercise
import com.dtyan.fitdiary.data.repo.WorkoutRepository
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

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WorkoutDetailsViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()
    private lateinit var db: AppDatabase
    private lateinit var repo: WorkoutRepository
    @Before fun setup() {
        val executor = mainRule.dispatcher.asExecutor()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor(executor).setTransactionExecutor(executor).build()
        repo = WorkoutRepository(db.workoutDao(), db.workoutSetDao(), db.weightDao())
    }
    @After fun close() { db.close() }

    @Test fun deleteCompleted_removesOwnSetsAndWeight_preservesFriend() = runTest {
        db.athleteDao().insert(Athlete(id = 1, name = "Я"))
        db.athleteDao().insert(Athlete(id = 2, name = "Друг"))
        val exerciseId = db.exerciseDao().insert(Exercise(name = "Жим", muscleGroup = "Грудь"))
        val members = repo.startGroupWorkout(listOf(1, 2))
        members.forEach { repo.addSet(it.id, exerciseId, 50.0, 10) }
        repo.finishGroupWorkout(members.first().id, mapOf(1L to 80.0, 2L to 70.0))
        val mine = members.first { it.athleteId == 1L }
        val friend = members.first { it.athleteId == 2L }
        val vm = WorkoutDetailsViewModel(mine.id, repo)
        advanceUntilIdle()
        var deleted = 0
        vm.deleteWorkout { deleted++ }
        vm.deleteWorkout { deleted++ }
        advanceUntilIdle()
        assertThat(deleted).isEqualTo(1)
        assertThat(db.workoutDao().getById(mine.id)).isNull()
        assertThat(db.workoutSetDao().getForWorkoutOnce(mine.id)).isEmpty()
        assertThat(db.weightDao().getAllOnce(1)).isEmpty()
        assertThat(db.workoutDao().getById(friend.id)).isNotNull()
        assertThat(db.workoutSetDao().getForWorkoutOnce(friend.id)).hasSize(1)
        assertThat(db.weightDao().getAllOnce(2)).hasSize(1)
    }

    @Test fun accidentalEmptyCompletedWorkout_canBeDeleted() = runTest {
        val id = repo.startWorkout()
        repo.finishWorkout(id)
        val vm = WorkoutDetailsViewModel(id, repo)
        advanceUntilIdle()
        assertThat(vm.state.value.workout).isNotNull()
        assertThat(vm.state.value.groups).isEmpty()
        var deleted = false
        vm.deleteWorkout { deleted = true }
        advanceUntilIdle()
        assertThat(deleted).isTrue()
        assertThat(db.workoutDao().getById(id)).isNull()
    }
}
