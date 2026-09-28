package com.dtyan.fitdiary.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dtyan.fitdiary.data.db.AppDatabase
import com.dtyan.fitdiary.data.db.Athlete
import com.dtyan.fitdiary.data.db.BodyMeasurement
import com.dtyan.fitdiary.data.db.Exercise
import com.dtyan.fitdiary.data.db.Meal
import com.dtyan.fitdiary.data.db.WeightEntry
import com.dtyan.fitdiary.data.db.Workout
import com.dtyan.fitdiary.data.repo.MeasurementRepository
import com.dtyan.fitdiary.data.repo.StatsRepository
import com.dtyan.fitdiary.data.repo.WorkoutRepository
import com.dtyan.fitdiary.domain.MeasurementType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Real Room integration tests: ownership, migration-era regressions and atomic writes. */
@RunWith(RobolectricTestRunner::class)
class ProfileWorkoutRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: WorkoutRepository
    private lateinit var stats: StatsRepository
    private val active = MutableStateFlow(1L)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = WorkoutRepository(db.workoutDao(), db.workoutSetDao(), db.weightDao(), active)
        stats = StatsRepository(db.workoutDao(), db.workoutSetDao(), db.weightDao(), active)
    }
    @After
    fun tearDown() = db.close()

    private suspend fun exercise(): Long = db.exerciseDao().insert(Exercise(name = "Жим", muscleGroup = "Грудь"))
    private suspend fun friend(): Long {
        db.athleteDao().insert(Athlete(id = 1, name = "Я"))
        return db.athleteDao().insert(Athlete(name = "Друг"))
    }

    @Test
    fun deletingMiddleThenAddingKeepsUniqueNumbers() = runTest {
        val e = exercise()
        val w = repo.startWorkout(now = 1)
        repo.addSet(w, e, 60.0, 10, now = 2)
        val second = repo.addSet(w, e, 60.0, 10, now = 3).set
        repo.addSet(w, e, 60.0, 10, now = 4)
        repo.deleteSet(second)
        repo.addSet(w, e, 60.0, 10, now = 5)
        assertThat(repo.getSetsForWorkoutOnce(w).map { it.setIndex }).containsExactly(1, 3, 4).inOrder()
    }

    @Test
    fun simultaneousRepositoryInstancesCannotDuplicateActiveWorkoutOrSetIndex() = runTest {
        val e = exercise()
        val secondRepo = WorkoutRepository(db.workoutDao(), db.workoutSetDao(), db.weightDao(), active)
        val ids = coroutineScope {
            listOf(async { repo.startWorkout(now = 1) }, async { secondRepo.startWorkout(now = 1) }).awaitAll()
        }
        assertThat(ids.distinct()).hasSize(1)
        coroutineScope {
            (1..10).map { n -> async {
                val writer = if (n % 2 == 0) repo else secondRepo
                writer.addSet(ids.first(), e, 60.0, n, now = n + 1L)
            } }.awaitAll()
        }
        assertThat(repo.getSetsForWorkoutOnce(ids.first()).map { it.setIndex }.sorted())
            .containsExactlyElementsIn(1..10).inOrder()
    }

    @Test
    fun previousSetsAndRecordsUseWorkoutOwnerEvenAfterGlobalProfileSwitch() = runTest {
        val otherId = friend()
        val e = exercise()
        val mine = repo.startWorkout(now = 1)
        repo.addSet(mine, e, 50.0, 10, now = 2)
        repo.finishWorkout(mine, now = 3)
        active.value = otherId
        val theirs = repo.startWorkout(now = 4)
        repo.addSet(theirs, e, 100.0, 10, now = 5)
        repo.finishWorkout(theirs, now = 6)

        active.value = 1L
        val myNext = repo.startWorkout(now = 7)
        active.value = otherId
        assertThat(repo.lastSetForPrefill(myNext, e)!!.weightKg).isEqualTo(50.0)
        assertThat(repo.maxWeight(e, myNext)).isEqualTo(50.0)
        val result = repo.addSet(myNext, e, 55.0, 10, now = 8)
        assertThat(result.isWeightPr).isTrue()
        assertThat(repo.maxWeight(e)).isEqualTo(100.0)
        assertThat(stats.getAllFinishedWorkoutsOnce().map { it.id }).containsExactly(theirs)
        assertThat(stats.getAllFinishedWorkoutsOnce(1L).map { it.id }).containsExactly(mine)
    }

    @Test
    fun groupHasSeparateWorkoutsAndAtomicIndividualWeights() = runTest {
        val otherId = friend()
        val group = repo.startGroupWorkout(listOf(1L, otherId), now = 1)
        assertThat(group.map { it.id }.distinct()).hasSize(2)
        assertThat(group.map { it.groupSessionId }.distinct()).hasSize(1)
        assertThat(group.first().groupSessionId).isNotNull()
        val repeated = repo.startGroupWorkout(listOf(1L, otherId), now = 2)
        assertThat(repeated.map { it.id }).containsExactlyElementsIn(group.map { it.id }).inOrder()
        assertThat(repo.observeGroupWorkouts(group.first().id).first()).hasSize(2)

        repo.finishGroupWorkout(group.first().id, mapOf(1L to 80.0, otherId to 95.0), now = 10)
        assertThat(db.weightDao().getAllOnce(1L).single().weightKg).isEqualTo(80.0)
        assertThat(db.weightDao().getAllOnce(otherId).single().weightKg).isEqualTo(95.0)
        assertThat(db.workoutDao().getActiveOnce(1L)).isNull()
        assertThat(db.workoutDao().getActiveOnce(otherId)).isNull()
    }

    @Test
    fun repeatCopiesExercisePlanWithoutCreatingCompletedSets() = runTest {
        val otherId = friend()
        val e = exercise()
        val original = repo.startWorkout(now = 1)
        repo.addSet(original, e, 100.0, 5, now = 2)
        repo.finishWorkout(original, now = 3)
        val repeated = repo.repeatWorkout(original, listOf(1L, otherId), now = 4)
        for (workout in repeated) {
            assertThat(repo.getSetsForWorkoutOnce(workout.id)).isEmpty()
            assertThat(repo.observePlannedExercises(workout.id).first().map { it.id }).containsExactly(e)
        }
        assertThat(repo.getSetsForWorkoutOnce(original)).hasSize(1)
    }

    @Test
    fun separateSoloStartsCanJoinWithoutReplacingTheirRecords() = runTest {
        val otherId = friend()
        val e = exercise()
        val mine = repo.startGroupWorkout(listOf(1L), now = 1).single()
        val theirs = repo.startGroupWorkout(listOf(otherId), now = 2).single()
        assertThat(mine.groupSessionId).isNull()
        assertThat(theirs.groupSessionId).isNull()
        repo.addSet(mine.id, e, 50.0, 10, now = 3)
        repo.addSet(theirs.id, e, 90.0, 8, now = 4)
        val together = repo.startGroupWorkout(listOf(1L, otherId), now = 5)
        assertThat(together.map { it.id }).containsExactly(mine.id, theirs.id).inOrder()
        assertThat(together.map { it.startedAt }).containsExactly(1L, 2L).inOrder()
        assertThat(together.map { it.groupSessionId }.distinct()).hasSize(1)
        assertThat(together.first().groupSessionId).isNotNull()
        assertThat(repo.getSetsForWorkoutOnce(mine.id).single().weightKg).isEqualTo(50.0)
        assertThat(repo.getSetsForWorkoutOnce(theirs.id).single().weightKg).isEqualTo(90.0)
    }

    @Test
    fun legacySingletonGroupIdsCanBeMergedWithoutLosingTheirPlans() = runTest {
        val otherId = friend()
        val e = exercise()
        val mine = db.workoutDao().insert(Workout(startedAt = 1, athleteId = 1, groupSessionId = "legacy-solo-one"))
        val theirs = db.workoutDao().insert(Workout(startedAt = 2, athleteId = otherId, groupSessionId = "legacy-solo-two"))
        repo.planExercise(mine, e)
        repo.planExercise(theirs, e)
        val together = repo.startGroupWorkout(listOf(1L, otherId), now = 3)
        assertThat(together.map { it.id }).containsExactly(mine, theirs).inOrder()
        assertThat(together.map { it.groupSessionId }.distinct()).hasSize(1)
        assertThat(db.workoutDao().plannedExerciseIds(mine)).containsExactly(e)
        assertThat(db.workoutDao().plannedExerciseIds(theirs)).containsExactly(e)
    }

    @Test
    fun repeatRejectsExistingSessionWithoutAddingPlanOrCreatingOtherParticipants() = runTest {
        val otherId = friend()
        val sourceExercise = exercise()
        val currentExercise = db.exerciseDao().insert(Exercise(name = "Тяга", muscleGroup = "Спина"))
        val source = repo.startWorkout(now = 1)
        repo.addSet(source, sourceExercise, 50.0, 10, now = 2)
        repo.finishWorkout(source, now = 3)
        val current = repo.startWorkout(now = 4)
        repo.planExercise(current, currentExercise)
        repo.addSet(current, currentExercise, 70.0, 8, now = 5)

        // The idle friend is first: refusal must happen before creating their workout.
        val error = runCatching { repo.repeatWorkout(source, listOf(otherId, 1L), now = 6) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error!!.message).isEqualTo("Сначала завершите текущую тренировку")
        assertThat(db.workoutDao().getActiveOnce(otherId)).isNull()
        assertThat(db.workoutDao().getActiveOnce(1L)!!.id).isEqualTo(current)
        assertThat(db.workoutDao().plannedExerciseIds(current)).containsExactly(currentExercise)
        assertThat(repo.getSetsForWorkoutOnce(current).single().weightKg).isEqualTo(70.0)
        assertThat(repo.getWorkoutOnce(current)!!.groupSessionId).isNull()
    }

    @Test
    fun endedInitiatorCannotSilentlyRestartWhileGroupContinues() = runTest {
        val otherId = friend()
        val thirdId = db.athleteDao().insert(Athlete(name = "Ещё друг"))
        val group = repo.startGroupWorkout(listOf(1L, otherId), now = 1)
        val mine = group.first()
        repo.finishWorkout(mine.id, now = 2)
        val error = runCatching { repo.startGroupWorkout(listOf(1L, otherId, thirdId), now = 3) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error!!.message).contains("уже завершили")
        assertThat(repo.getWorkoutOnce(mine.id)!!.endedAt).isEqualTo(2L)
        assertThat(db.workoutDao().getActiveOnce(1L)).isNull()
        assertThat(db.workoutDao().getActiveOnce(thirdId)).isNull()
        assertThat(repo.observeGroupWorkouts(group.last().id).first()).hasSize(2)
    }

    @Test
    fun activeInitiatorCanAddFriendWhilePreservingFinishedMember() = runTest {
        val otherId = friend()
        val thirdId = db.athleteDao().insert(Athlete(name = "Ещё друг"))
        val group = repo.startGroupWorkout(listOf(1L, otherId), now = 1)
        val finished = group.last()
        repo.finishWorkout(finished.id, 95.0, now = 2)
        val joined = repo.startGroupWorkout(listOf(1L, otherId, thirdId), now = 3)
        assertThat(joined.map { it.athleteId }).containsExactly(1L, otherId, thirdId).inOrder()
        assertThat(joined[1].id).isEqualTo(finished.id)
        assertThat(joined[1].endedAt).isEqualTo(2L)
        assertThat(db.workoutDao().getActiveOnce(otherId)).isNull()
        assertThat(db.weightDao().getAllOnce(otherId).single().weightKg).isEqualTo(95.0)
        assertThat(repo.observeGroupWorkouts(group.first().id).first()).hasSize(3)
    }

    @Test
    fun deletingFinishedWorkoutRemovesOnlyItsLinkedWeighIn() = runTest {
        val w = repo.startWorkout(now = 1)
        repo.finishWorkout(w, 80.0, now = 2)
        val independent = db.weightDao().insert(WeightEntry(timestamp = 2, weightKg = 80.0))
        repo.deleteWorkout(w)
        assertThat(repo.getWorkoutOnce(w)).isNull()
        assertThat(db.weightDao().getAllOnce().map { it.id }).containsExactly(independent)
    }

    @Test
    fun finishedEmptyWorkoutCanBeDeletedWithoutAffectingAnotherAthlete() = runTest {
        val otherId = friend()
        val empty = repo.startWorkout(now = 1)
        repo.finishWorkout(empty, now = 2)
        val theirs = repo.startWorkout(now = 3, athleteId = otherId)
        assertThat(stats.getAllFinishedWorkoutsOnce().map { it.id }).containsExactly(empty)
        repo.deleteWorkout(empty)
        assertThat(repo.getWorkoutOnce(empty)).isNull()
        assertThat(stats.getAllFinishedWorkoutsOnce()).isEmpty()
        assertThat(repo.getWorkoutOnce(theirs)!!.athleteId).isEqualTo(otherId)
        assertThat(db.weightDao().getAllOnce()).isEmpty()
    }

    @Test
    fun singleRepRecordUsesSameE1RmAsDomainHelper() = runTest {
        val w = repo.startWorkout(now = 1)
        val e = exercise()
        repo.addSet(w, e, 100.0, 1, now = 2)
        assertThat(repo.maxE1Rm(e, w)).isEqualTo(100.0)
        val result = repo.addSet(w, e, 96.0, 2, now = 3)
        assertThat(result.isWeightPr).isFalse()
        assertThat(result.isE1RmPr).isTrue()
    }

    @Test
    fun completedWorkoutRejectsLateSetAndInvalidWeightDoesNotFinish() = runTest {
        val w = repo.startWorkout(now = 1)
        val e = exercise()
        assertThat(runCatching { repo.finishWorkout(w, -10.0, now = 2) }.isFailure).isTrue()
        assertThat(repo.getWorkoutOnce(w)!!.endedAt).isNull()
        repo.finishWorkout(w, now = 3)
        assertThat(runCatching { repo.addSet(w, e, 10.0, 2, now = 4) }.isFailure).isTrue()
        assertThat(repo.getSetsForWorkoutOnce(w)).isEmpty()
    }

    @Test
    fun nutritionWeightAndMeasurementsRemainIsolatedAcrossProfiles() = runTest {
        val otherId = friend()
        val measurements = MeasurementRepository(db.measurementDao(), active)
        db.mealDao().insert(Meal(epochDay = 1, timestamp = 1, name = "Моё", calories = 100,
            proteinG = 1.0, fatG = 1.0, carbsG = 1.0))
        db.mealDao().insert(Meal(epochDay = 1, timestamp = 2, name = "Друга", calories = 500,
            proteinG = 1.0, fatG = 1.0, carbsG = 1.0, athleteId = otherId))
        stats.addWeightEntry(80.0, now = 1)
        measurements.addSession(mapOf(MeasurementType.WAIST to 80.0), now = 1)
        active.value = otherId
        assertThat(stats.getWeightHistoryOnce()).isEmpty()
        assertThat(measurements.getAllOnce()).isEmpty()
        stats.addWeightEntry(95.0, now = 2)
        measurements.addSession(mapOf(MeasurementType.WAIST to 95.0), now = 2)
        assertThat(db.mealDao().getForDayOnce(1, otherId).single().name).isEqualTo("Друга")
        assertThat(stats.observeLatestWeight().first()!!.weightKg).isEqualTo(95.0)
        active.value = 1L
        assertThat(stats.observeLatestWeight().first()!!.weightKg).isEqualTo(80.0)
        assertThat(measurements.getAllOnce().single().valueCm).isEqualTo(80.0)
    }
}
