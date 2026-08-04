package com.dtyan.fitdiary.data.repo

import com.dtyan.fitdiary.data.db.ExerciseSetPoint
import com.dtyan.fitdiary.data.db.WeightDao
import com.dtyan.fitdiary.data.db.WeightEntry
import com.dtyan.fitdiary.data.db.Workout
import com.dtyan.fitdiary.data.db.WorkoutDao
import com.dtyan.fitdiary.data.db.WorkoutSetDao
import com.dtyan.fitdiary.data.db.WorkoutVolumePoint
import kotlinx.coroutines.flow.Flow

class StatsRepository(
    private val workoutDao: WorkoutDao,
    private val setDao: WorkoutSetDao,
    private val weightDao: WeightDao,
) {
    /** Времена начала завершённых тренировок в интервале (для календаря/стрика). */
    suspend fun workoutStartTimesBetween(fromMillis: Long, toMillis: Long): List<Long> =
        workoutDao.startTimesBetween(fromMillis, toMillis)

    fun observeWeightHistory(): Flow<List<WeightEntry>> = weightDao.observeAll()
    fun observeLatestWeight(): Flow<WeightEntry?> = weightDao.observeLatest()

    suspend fun addWeightEntry(weightKg: Double, now: Long = System.currentTimeMillis()): Long =
        weightDao.insert(WeightEntry(timestamp = now, weightKg = weightKg, fromWorkout = false))

    suspend fun deleteWeightEntry(entry: WeightEntry) = weightDao.delete(entry)

    /** История подходов упражнения по завершённым тренировкам (для графика прогресса). */
    suspend fun exerciseHistory(exerciseId: Long): List<ExerciseSetPoint> =
        setDao.historyForExercise(exerciseId)

    /** Тоннаж каждой завершённой тренировки (для агрегации по неделям). */
    suspend fun volumePoints(): List<WorkoutVolumePoint> = workoutDao.volumePoints()

    suspend fun getAllFinishedWorkoutsOnce(): List<Workout> = workoutDao.getAllFinishedOnce()
}
