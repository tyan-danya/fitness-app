package com.dtyan.fitdiary.data.repo

import com.dtyan.fitdiary.data.db.ExerciseSetPoint
import com.dtyan.fitdiary.data.db.WeightDao
import com.dtyan.fitdiary.data.db.WeightEntry
import com.dtyan.fitdiary.data.db.Workout
import com.dtyan.fitdiary.data.db.WorkoutDao
import com.dtyan.fitdiary.data.db.WorkoutSetDao
import com.dtyan.fitdiary.data.db.WorkoutVolumePoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest

@OptIn(ExperimentalCoroutinesApi::class)
class StatsRepository(
    private val workoutDao: WorkoutDao,
    private val setDao: WorkoutSetDao,
    private val weightDao: WeightDao,
    private val activeAthleteId: StateFlow<Long> = MutableStateFlow(1L),
) {
    val currentAthleteId: Long get() = activeAthleteId.value
    suspend fun workoutStartTimesBetween(fromMillis: Long, toMillis: Long, athleteId: Long = activeAthleteId.value): List<Long> =
        workoutDao.startTimesBetween(fromMillis, toMillis, athleteId)
    fun observeWeightHistory(): Flow<List<WeightEntry>> = activeAthleteId.flatMapLatest { weightDao.observeAll(it) }
    fun observeLatestWeight(): Flow<WeightEntry?> = activeAthleteId.flatMapLatest { weightDao.observeLatest(it) }
    fun observeLatestWeight(athleteId: Long): Flow<WeightEntry?> = weightDao.observeLatest(athleteId)
    suspend fun addWeightEntry(weightKg: Double, now: Long = System.currentTimeMillis(), athleteId: Long = activeAthleteId.value): Long {
        require(weightKg.isFinite() && weightKg > 0) { "Вес должен быть больше нуля" }
        return weightDao.insert(WeightEntry(timestamp = now, weightKg = weightKg, athleteId = athleteId))
    }
    suspend fun deleteWeightEntry(entry: WeightEntry) = weightDao.delete(entry)
    suspend fun exerciseHistory(exerciseId: Long, athleteId: Long = activeAthleteId.value): List<ExerciseSetPoint> =
        setDao.historyForExercise(exerciseId, athleteId)
    suspend fun volumePoints(athleteId: Long = activeAthleteId.value): List<WorkoutVolumePoint> = workoutDao.volumePoints(athleteId)
    suspend fun getAllFinishedWorkoutsOnce(athleteId: Long = activeAthleteId.value): List<Workout> =
        workoutDao.getAllFinishedOnce(athleteId)
    suspend fun getWeightHistoryOnce(athleteId: Long = activeAthleteId.value): List<WeightEntry> = weightDao.getAllOnce(athleteId)
}
