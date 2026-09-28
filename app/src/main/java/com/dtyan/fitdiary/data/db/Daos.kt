package com.dtyan.fitdiary.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.dtyan.fitdiary.domain.MealType
import com.dtyan.fitdiary.domain.MeasurementType
import kotlinx.coroutines.flow.Flow

@Dao
interface ExerciseDao {
    @Query("SELECT * FROM exercises WHERE isArchived = 0 ORDER BY muscleGroup, name")
    fun observeActive(): Flow<List<Exercise>>

    @Query("SELECT * FROM exercises WHERE id = :id")
    suspend fun getById(id: Long): Exercise?

    @Query("SELECT * FROM exercises WHERE isArchived = 0 ORDER BY muscleGroup, name")
    suspend fun getActiveOnce(): List<Exercise>

    @Insert
    suspend fun insert(exercise: Exercise): Long

    @Update
    suspend fun update(exercise: Exercise)
}

@Dao
interface WorkoutDao {
    @Query("SELECT * FROM workouts WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    fun observeActive(): Flow<Workout?>

    @Query("SELECT * FROM workouts WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    suspend fun getActiveOnce(): Workout?

    @Query("SELECT * FROM workouts WHERE id = :id")
    fun observeById(id: Long): Flow<Workout?>

    @Query("SELECT * FROM workouts WHERE id = :id")
    suspend fun getById(id: Long): Workout?

    @Query(
        """SELECT w.id, w.startedAt, w.endedAt, w.bodyWeightKg,
                  COUNT(s.id) AS setCount,
                  COUNT(DISTINCT s.exerciseId) AS exerciseCount,
                  IFNULL(SUM(s.weightKg * s.reps), 0) AS totalVolume
           FROM workouts w
           LEFT JOIN workout_sets s ON s.workoutId = w.id
           WHERE w.endedAt IS NOT NULL
           GROUP BY w.id
           ORDER BY w.startedAt DESC"""
    )
    fun observeFinishedSummaries(): Flow<List<WorkoutSummary>>

    @Query("SELECT startedAt FROM workouts WHERE endedAt IS NOT NULL AND startedAt BETWEEN :fromMillis AND :toMillis")
    suspend fun startTimesBetween(fromMillis: Long, toMillis: Long): List<Long>

    @Query("SELECT * FROM workouts WHERE endedAt IS NOT NULL ORDER BY startedAt")
    suspend fun getAllFinishedOnce(): List<Workout>

    @Query(
        """SELECT w.startedAt, IFNULL(SUM(s.weightKg * s.reps), 0) AS volume
           FROM workouts w
           LEFT JOIN workout_sets s ON s.workoutId = w.id
           WHERE w.endedAt IS NOT NULL
           GROUP BY w.id
           ORDER BY w.startedAt"""
    )
    suspend fun volumePoints(): List<WorkoutVolumePoint>

    @Insert
    suspend fun insert(workout: Workout): Long

    @Update
    suspend fun update(workout: Workout)

    @Delete
    suspend fun delete(workout: Workout)
}

@Dao
interface WorkoutSetDao {
    @Query(
        """SELECT s.id, s.workoutId, s.exerciseId, s.setIndex, s.weightKg, s.reps, s.completedAt,
                  e.name AS exerciseName, e.muscleGroup, e.photoPath
           FROM workout_sets s JOIN exercises e ON e.id = s.exerciseId
           WHERE s.workoutId = :workoutId
           ORDER BY s.completedAt, s.id"""
    )
    fun observeForWorkout(workoutId: Long): Flow<List<SetWithExercise>>

    @Query(
        """SELECT s.id, s.workoutId, s.exerciseId, s.setIndex, s.weightKg, s.reps, s.completedAt,
                  e.name AS exerciseName, e.muscleGroup, e.photoPath
           FROM workout_sets s JOIN exercises e ON e.id = s.exerciseId
           WHERE s.workoutId = :workoutId
           ORDER BY s.completedAt, s.id"""
    )
    suspend fun getForWorkoutOnce(workoutId: Long): List<SetWithExercise>

    @Query(
        """SELECT * FROM workout_sets
           WHERE workoutId = :workoutId AND exerciseId = :exerciseId
           ORDER BY setIndex"""
    )
    fun observeForWorkoutExercise(workoutId: Long, exerciseId: Long): Flow<List<WorkoutSet>>

    @Query(
        """SELECT * FROM workout_sets
           WHERE workoutId = :workoutId AND exerciseId = :exerciseId
           ORDER BY setIndex DESC LIMIT 1"""
    )
    suspend fun lastSetInWorkout(workoutId: Long, exerciseId: Long): WorkoutSet?

    @Query("SELECT COUNT(*) FROM workout_sets WHERE workoutId = :workoutId AND exerciseId = :exerciseId")
    suspend fun setCount(workoutId: Long, exerciseId: Long): Int

    /** Подходы этого упражнения из последней завершённой тренировки, где оно встречалось (кроме текущей). */
    @Query(
        """SELECT * FROM workout_sets
           WHERE exerciseId = :exerciseId AND workoutId = (
               SELECT s2.workoutId FROM workout_sets s2
               JOIN workouts w2 ON w2.id = s2.workoutId
               WHERE s2.exerciseId = :exerciseId
                 AND s2.workoutId != :excludeWorkoutId
                 AND w2.endedAt IS NOT NULL
               ORDER BY w2.startedAt DESC LIMIT 1
           )
           ORDER BY setIndex"""
    )
    suspend fun previousWorkoutSets(exerciseId: Long, excludeWorkoutId: Long): List<WorkoutSet>

    @Query("SELECT MAX(weightKg) FROM workout_sets WHERE exerciseId = :exerciseId")
    suspend fun maxWeight(exerciseId: Long): Double?

    /** Максимальный расчётный 1ПМ (формула Эпли) по всем подходам упражнения. */
    @Query("SELECT MAX(weightKg * (1 + reps / 30.0)) FROM workout_sets WHERE exerciseId = :exerciseId")
    suspend fun maxE1Rm(exerciseId: Long): Double?

    @Query(
        """SELECT w.startedAt, s.weightKg, s.reps
           FROM workout_sets s JOIN workouts w ON w.id = s.workoutId
           WHERE s.exerciseId = :exerciseId AND w.endedAt IS NOT NULL
           ORDER BY w.startedAt, s.setIndex"""
    )
    suspend fun historyForExercise(exerciseId: Long): List<ExerciseSetPoint>

    /** id упражнений, по которым уже есть подходы (для сортировки/фильтров каталога). */
    @Query("SELECT DISTINCT exerciseId FROM workout_sets")
    suspend fun usedExerciseIds(): List<Long>

    @Insert
    suspend fun insert(set: WorkoutSet): Long

    @Update
    suspend fun update(set: WorkoutSet)

    @Delete
    suspend fun delete(set: WorkoutSet)
}

@Dao
interface MealDao {
    @Query("SELECT * FROM meals WHERE epochDay = :epochDay ORDER BY timestamp, id")
    fun observeForDay(epochDay: Long): Flow<List<Meal>>

    @Query("SELECT * FROM meals WHERE epochDay = :epochDay ORDER BY timestamp, id")
    suspend fun getForDayOnce(epochDay: Long): List<Meal>

    /** Недавние блюда без повторов по названию — для быстрого добавления. */
    @Query(
        """SELECT * FROM meals WHERE id IN (SELECT MAX(id) FROM meals GROUP BY name)
           ORDER BY id DESC LIMIT :limit"""
    )
    suspend fun recentDistinct(limit: Int): List<Meal>

    @Query("SELECT DISTINCT epochDay FROM meals WHERE epochDay BETWEEN :fromDay AND :toDay")
    suspend fun daysWithMeals(fromDay: Long, toDay: Long): List<Long>

    @Query(
        """SELECT epochDay, SUM(calories) AS calories, SUM(proteinG) AS proteinG,
                  SUM(fatG) AS fatG, SUM(carbsG) AS carbsG
           FROM meals WHERE epochDay BETWEEN :fromDay AND :toDay
           GROUP BY epochDay ORDER BY epochDay"""
    )
    suspend fun dailyTotals(fromDay: Long, toDay: Long): List<DayNutritionTotal>

    @Query("SELECT * FROM meals ORDER BY timestamp, id")
    suspend fun getAllOnce(): List<Meal>

    /** Приёмы, ждущие расчёта КБЖУ (для выгрузки в файл-обмен). */
    @Query("SELECT * FROM meals WHERE needsEstimate = 1 ORDER BY epochDay, timestamp, id")
    suspend fun getPendingEstimatesOnce(): List<Meal>

    @Query("SELECT COUNT(*) FROM meals WHERE needsEstimate = 1")
    fun observePendingEstimateCount(): Flow<Int>

    @Query("SELECT * FROM meals WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<Meal>

    @Query("UPDATE meals SET mealType = :type WHERE id = :id")
    suspend fun setMealType(id: Long, type: MealType)

    @Insert
    suspend fun insert(meal: Meal): Long

    @Update
    suspend fun update(meal: Meal)

    @Delete
    suspend fun delete(meal: Meal)
}

@Dao
interface WeightDao {
    @Query("SELECT * FROM weight_entries ORDER BY timestamp, id")
    fun observeAll(): Flow<List<WeightEntry>>

    @Query("SELECT * FROM weight_entries ORDER BY timestamp DESC, id DESC LIMIT 1")
    fun observeLatest(): Flow<WeightEntry?>

    @Query("SELECT * FROM weight_entries ORDER BY timestamp, id")
    suspend fun getAllOnce(): List<WeightEntry>

    @Insert
    suspend fun insert(entry: WeightEntry): Long

    @Delete
    suspend fun delete(entry: WeightEntry)
}

@Dao
interface MeasurementDao {
    @Query("SELECT * FROM body_measurements ORDER BY timestamp, id")
    fun observeAll(): Flow<List<BodyMeasurement>>

    @Query("SELECT * FROM body_measurements ORDER BY timestamp, id")
    suspend fun getAllOnce(): List<BodyMeasurement>

    @Query("SELECT * FROM body_measurements WHERE type = :type ORDER BY timestamp, id")
    suspend fun getForType(type: MeasurementType): List<BodyMeasurement>

    @Query("SELECT * FROM body_measurements WHERE epochDay = :epochDay ORDER BY timestamp, id")
    suspend fun getForDayOnce(epochDay: Long): List<BodyMeasurement>

    /** День последнего замера любой зоны (для напоминания); null — замеров ещё не было. */
    @Query("SELECT MAX(epochDay) FROM body_measurements")
    suspend fun lastEpochDay(): Long?

    @Query("SELECT MAX(epochDay) FROM body_measurements")
    fun observeLastEpochDay(): Flow<Long?>

    @Insert
    suspend fun insertAll(items: List<BodyMeasurement>): List<Long>

    @Update
    suspend fun update(item: BodyMeasurement)

    @Delete
    suspend fun delete(item: BodyMeasurement)
}
