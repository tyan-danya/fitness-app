package com.dtyan.fitdiary.data.repo

import com.dtyan.fitdiary.data.db.Exercise
import com.dtyan.fitdiary.data.db.SetWithExercise
import com.dtyan.fitdiary.data.db.WeightDao
import com.dtyan.fitdiary.data.db.Workout
import com.dtyan.fitdiary.data.db.WorkoutDao
import com.dtyan.fitdiary.data.db.WorkoutSet
import com.dtyan.fitdiary.data.db.WorkoutSetDao
import com.dtyan.fitdiary.data.db.WorkoutSummary
import com.dtyan.fitdiary.domain.Calculations
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import java.util.UUID

data class AddSetResult(val set: WorkoutSet, val isWeightPr: Boolean, val isE1RmPr: Boolean)

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutRepository(
    private val workoutDao: WorkoutDao,
    private val setDao: WorkoutSetDao,
    @Suppress("unused") private val weightDao: WeightDao,
    private val activeAthleteId: StateFlow<Long> = MutableStateFlow(1L),
) {
    /** Snapshot synchronously in a UI event before launching asynchronous work. */
    val currentAthleteId: Long get() = activeAthleteId.value
    fun observeActiveWorkout(): Flow<Workout?> = activeAthleteId.flatMapLatest { workoutDao.observeActive(it) }
    fun observeWorkout(id: Long): Flow<Workout?> = workoutDao.observeById(id)
    fun observeSetsForWorkout(workoutId: Long): Flow<List<SetWithExercise>> = setDao.observeForWorkout(workoutId)
    fun observeSetsForExercise(workoutId: Long, exerciseId: Long): Flow<List<WorkoutSet>> =
        setDao.observeForWorkoutExercise(workoutId, exerciseId)
    fun observeFinishedSummaries(): Flow<List<WorkoutSummary>> =
        activeAthleteId.flatMapLatest { workoutDao.observeFinishedSummaries(it) }
    fun observeGroupWorkouts(workoutId: Long): Flow<List<Workout>> = workoutDao.observeGroup(workoutId)
    fun observePlannedExercises(workoutId: Long): Flow<List<Exercise>> = workoutDao.observePlannedExercises(workoutId)

    suspend fun getWorkoutOnce(id: Long): Workout? = workoutDao.getById(id)
    suspend fun getSetsForWorkoutOnce(workoutId: Long): List<SetWithExercise> = setDao.getForWorkoutOnce(workoutId)

    suspend fun startWorkout(now: Long = System.currentTimeMillis(), athleteId: Long = activeAthleteId.value): Long =
        workoutDao.startForAthlete(athleteId, now).id

    suspend fun startGroupWorkout(athleteIds: List<Long>, now: Long = System.currentTimeMillis()): List<Workout> {
        val ids = athleteIds.distinct()
        require(ids.isNotEmpty() && ids.all { it > 0 }) { "Выберите участников" }
        return workoutDao.startGroup(ids, now, UUID.randomUUID().toString())
    }

    suspend fun planExercise(workoutId: Long, exerciseId: Long) = workoutDao.planExercise(workoutId, exerciseId)

    suspend fun repeatWorkout(
        sourceWorkoutId: Long,
        athleteIds: List<Long> = listOf(activeAthleteId.value),
        now: Long = System.currentTimeMillis(),
    ): List<Workout> {
        require(athleteIds.isNotEmpty() && athleteIds.all { it > 0 }) { "Выберите участников" }
        requireNotNull(workoutDao.getById(sourceWorkoutId)) { "Тренировка не найдена" }
        return workoutDao.repeatPlan(sourceWorkoutId, athleteIds.distinct(), now, UUID.randomUUID().toString())
    }

    suspend fun finishWorkout(workoutId: Long, bodyWeightKg: Double? = null, now: Long = System.currentTimeMillis()) {
        require(bodyWeightKg == null || (bodyWeightKg.isFinite() && bodyWeightKg > 0)) { "Вес должен быть больше нуля" }
        workoutDao.finishWithWeight(workoutId, bodyWeightKg, now)
    }

    suspend fun finishGroupWorkout(
        workoutId: Long,
        bodyWeights: Map<Long, Double> = emptyMap(),
        now: Long = System.currentTimeMillis(),
    ) {
        require(bodyWeights.values.all { it.isFinite() && it > 0 }) { "Вес должен быть больше нуля" }
        workoutDao.finishGroup(workoutId, bodyWeights, now)
    }

    suspend fun cancelWorkout(workoutId: Long) = deleteWorkout(workoutId)
    suspend fun deleteWorkout(workoutId: Long) = workoutDao.deleteWithWeight(workoutId)

    suspend fun addSet(
        workoutId: Long, exerciseId: Long, weightKg: Double, reps: Int,
        now: Long = System.currentTimeMillis(),
    ): AddSetResult {
        require(weightKg.isFinite() && weightKg >= 0 && reps > 0) { "Проверьте вес и повторы" }
        val athleteId = ownerOf(workoutId)
        val (set, previous) = setDao.recordSet(
            WorkoutSet(workoutId = workoutId, exerciseId = exerciseId, setIndex = 0,
                weightKg = weightKg, reps = reps, completedAt = now),
            athleteId,
        )
        val e1Rm = Calculations.epley1Rm(weightKg, reps)
        return AddSetResult(
            set = set,
            isWeightPr = previous.first?.let { weightKg > it } ?: false,
            isE1RmPr = previous.second?.let { e1Rm > it } ?: false,
        )
    }

    suspend fun updateSet(set: WorkoutSet) {
        require(set.weightKg.isFinite() && set.weightKg >= 0 && set.reps > 0) { "Проверьте вес и повторы" }
        setDao.update(set)
    }
    suspend fun deleteSet(set: WorkoutSet) = setDao.delete(set)

    suspend fun previousWorkoutSets(exerciseId: Long, excludeWorkoutId: Long): List<WorkoutSet> =
        setDao.previousWorkoutSets(exerciseId, excludeWorkoutId, ownerOf(excludeWorkoutId))
    suspend fun lastSetForPrefill(workoutId: Long, exerciseId: Long): WorkoutSet? =
        setDao.lastSetInWorkout(workoutId, exerciseId) ?: previousWorkoutSets(exerciseId, workoutId).lastOrNull()
    suspend fun maxWeight(exerciseId: Long, workoutId: Long? = null): Double? =
        setDao.maxWeight(exerciseId, workoutId?.let { ownerOf(it) } ?: activeAthleteId.value)
    suspend fun maxE1Rm(exerciseId: Long, workoutId: Long? = null): Double? =
        setDao.maxE1Rm(exerciseId, workoutId?.let { ownerOf(it) } ?: activeAthleteId.value)

    private suspend fun ownerOf(workoutId: Long): Long =
        requireNotNull(workoutDao.getById(workoutId)) { "Тренировка не найдена" }.athleteId
}
