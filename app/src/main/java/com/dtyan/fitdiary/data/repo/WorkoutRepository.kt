package com.dtyan.fitdiary.data.repo

import com.dtyan.fitdiary.data.db.SetWithExercise
import com.dtyan.fitdiary.data.db.WeightDao
import com.dtyan.fitdiary.data.db.WeightEntry
import com.dtyan.fitdiary.data.db.Workout
import com.dtyan.fitdiary.data.db.WorkoutDao
import com.dtyan.fitdiary.data.db.WorkoutSet
import com.dtyan.fitdiary.data.db.WorkoutSetDao
import com.dtyan.fitdiary.data.db.WorkoutSummary
import kotlinx.coroutines.flow.Flow

/** Результат записи подхода: сам подход + признаки новых личных рекордов. */
data class AddSetResult(
    val set: WorkoutSet,
    val isWeightPr: Boolean,
    val isE1RmPr: Boolean,
)

class WorkoutRepository(
    private val workoutDao: WorkoutDao,
    private val setDao: WorkoutSetDao,
    private val weightDao: WeightDao,
) {
    fun observeActiveWorkout(): Flow<Workout?> = workoutDao.observeActive()
    fun observeWorkout(id: Long): Flow<Workout?> = workoutDao.observeById(id)
    fun observeSetsForWorkout(workoutId: Long): Flow<List<SetWithExercise>> =
        setDao.observeForWorkout(workoutId)
    fun observeSetsForExercise(workoutId: Long, exerciseId: Long): Flow<List<WorkoutSet>> =
        setDao.observeForWorkoutExercise(workoutId, exerciseId)
    fun observeFinishedSummaries(): Flow<List<WorkoutSummary>> = workoutDao.observeFinishedSummaries()

    suspend fun getWorkoutOnce(id: Long): Workout? = workoutDao.getById(id)
    suspend fun getSetsForWorkoutOnce(workoutId: Long): List<SetWithExercise> =
        setDao.getForWorkoutOnce(workoutId)

    /** Старт тренировки. Если активная уже есть — возвращает её id (двойной старт невозможен). */
    suspend fun startWorkout(now: Long = System.currentTimeMillis()): Long {
        workoutDao.getActiveOnce()?.let { return it.id }
        return workoutDao.insert(Workout(startedAt = now))
    }

    /**
     * Завершение тренировки с опциональным контрольным взвешиванием.
     * Вес также попадает в историю замеров (weight_entries).
     */
    suspend fun finishWorkout(
        workoutId: Long,
        bodyWeightKg: Double? = null,
        now: Long = System.currentTimeMillis(),
    ) {
        val workout = workoutDao.getById(workoutId) ?: return
        if (workout.endedAt != null) return
        workoutDao.update(workout.copy(endedAt = now, bodyWeightKg = bodyWeightKg))
        if (bodyWeightKg != null) {
            weightDao.insert(WeightEntry(timestamp = now, weightKg = bodyWeightKg, fromWorkout = true))
        }
    }

    /** Отмена тренировки: удаляется сама тренировка и все её подходы (CASCADE). */
    suspend fun cancelWorkout(workoutId: Long) {
        workoutDao.getById(workoutId)?.let { workoutDao.delete(it) }
    }

    /** Записать подход. setIndex проставляется автоматически, рекорды считаются до вставки. */
    suspend fun addSet(
        workoutId: Long,
        exerciseId: Long,
        weightKg: Double,
        reps: Int,
        now: Long = System.currentTimeMillis(),
    ): AddSetResult {
        val prevMaxWeight = setDao.maxWeight(exerciseId)
        val prevMaxE1Rm = setDao.maxE1Rm(exerciseId)
        val nextIndex = setDao.setCount(workoutId, exerciseId) + 1
        val set = WorkoutSet(
            workoutId = workoutId,
            exerciseId = exerciseId,
            setIndex = nextIndex,
            weightKg = weightKg,
            reps = reps,
            completedAt = now,
        )
        val id = setDao.insert(set)
        val e1Rm = weightKg * (1 + reps / 30.0)
        return AddSetResult(
            set = set.copy(id = id),
            isWeightPr = prevMaxWeight != null && weightKg > prevMaxWeight,
            isE1RmPr = prevMaxE1Rm != null && e1Rm > prevMaxE1Rm,
        )
    }

    suspend fun updateSet(set: WorkoutSet) = setDao.update(set)
    suspend fun deleteSet(set: WorkoutSet) = setDao.delete(set)

    /** Подходы прошлой тренировки по упражнению — «сколько я делал в прошлый раз». */
    suspend fun previousWorkoutSets(exerciseId: Long, excludeWorkoutId: Long): List<WorkoutSet> =
        setDao.previousWorkoutSets(exerciseId, excludeWorkoutId)

    /** Что подставить в поля ввода: последний подход текущей тренировки, иначе прошлой. */
    suspend fun lastSetForPrefill(workoutId: Long, exerciseId: Long): WorkoutSet? =
        setDao.lastSetInWorkout(workoutId, exerciseId)
            ?: previousWorkoutSets(exerciseId, workoutId).lastOrNull()

    suspend fun maxWeight(exerciseId: Long): Double? = setDao.maxWeight(exerciseId)
    suspend fun maxE1Rm(exerciseId: Long): Double? = setDao.maxE1Rm(exerciseId)
}
