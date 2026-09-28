package com.dtyan.fitdiary.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.dtyan.fitdiary.domain.MealType
import com.dtyan.fitdiary.domain.MeasurementType
import kotlinx.coroutines.flow.Flow

@Dao
interface AthleteDao {
    @Query("SELECT * FROM athletes ORDER BY id")
    fun observeAll(): Flow<List<Athlete>>
    @Query("SELECT * FROM athletes WHERE isArchived = 0 ORDER BY id")
    fun observeActive(): Flow<List<Athlete>>
    @Query("SELECT * FROM athletes WHERE isArchived = 0 ORDER BY id")
    suspend fun getActiveOnce(): List<Athlete>
    @Query("SELECT * FROM athletes WHERE id = :id")
    suspend fun getById(id: Long): Athlete?
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(athlete: Athlete): Long
    @Update
    suspend fun update(athlete: Athlete)
    @Query("SELECT COUNT(*) FROM workouts WHERE athleteId = :athleteId AND endedAt IS NULL")
    suspend fun activeWorkoutCount(athleteId: Long): Int

    @Transaction
    suspend fun archiveKeepingOne(id: Long) {
        require(id != 1L) { "Основной профиль нельзя скрыть" }
        val athlete = getById(id) ?: return
        if (athlete.isArchived) return
        require(getActiveOnce().size > 1) { "Нельзя скрыть последний профиль" }
        require(activeWorkoutCount(id) == 0) { "Сначала завершите тренировку этого участника" }
        update(athlete.copy(isArchived = true))
    }
}

@Dao
interface ExerciseDao {
    @Query("SELECT * FROM exercises WHERE isArchived = 0 ORDER BY muscleGroup, name")
    fun observeActive(): Flow<List<Exercise>>
    @Query("SELECT * FROM exercises WHERE id = :id")
    suspend fun getById(id: Long): Exercise?
    @Query("SELECT * FROM exercises WHERE isArchived = 0 ORDER BY muscleGroup, name")
    suspend fun getActiveOnce(): List<Exercise>
    @Query("SELECT * FROM exercises ORDER BY isArchived, id")
    suspend fun getAllOnce(): List<Exercise>
    @Insert
    suspend fun insert(exercise: Exercise): Long
    @Update
    suspend fun update(exercise: Exercise)
    @Query("UPDATE exercises SET weightStepKg = :stepKg WHERE id = :id")
    suspend fun setWeightStep(id: Long, stepKg: Double)
}

@Dao
interface WorkoutDao {
    @Query("SELECT * FROM workouts WHERE athleteId = :athleteId AND endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    fun observeActive(athleteId: Long = 1L): Flow<Workout?>
    @Query("SELECT * FROM workouts WHERE athleteId = :athleteId AND endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    suspend fun getActiveOnce(athleteId: Long = 1L): Workout?
    @Query("SELECT * FROM workouts WHERE id = :id")
    fun observeById(id: Long): Flow<Workout?>
    @Query("SELECT * FROM workouts WHERE id = :id")
    suspend fun getById(id: Long): Workout?
    @Query("SELECT * FROM workouts WHERE id = :workoutId OR groupSessionId = (SELECT groupSessionId FROM workouts WHERE id = :workoutId) ORDER BY id")
    fun observeGroup(workoutId: Long): Flow<List<Workout>>
    @Query("SELECT * FROM workouts WHERE id = :workoutId OR groupSessionId = (SELECT groupSessionId FROM workouts WHERE id = :workoutId) ORDER BY id")
    suspend fun getGroupOnce(workoutId: Long): List<Workout>
    @Query("SELECT * FROM workouts WHERE groupSessionId = :groupSessionId ORDER BY id")
    suspend fun getGroupBySession(groupSessionId: String): List<Workout>
    @Query("INSERT OR IGNORE INTO athletes(id, name, isArchived) VALUES(1, 'Я', 0)")
    suspend fun ensureDefaultAthlete()
    @Query("SELECT COUNT(*) FROM athletes WHERE id = :athleteId AND isArchived = 0")
    suspend fun availableAthlete(athleteId: Long): Int

    @Query("""SELECT w.id, w.startedAt, w.endedAt, w.bodyWeightKg,
                  COUNT(s.id) AS setCount, COUNT(DISTINCT s.exerciseId) AS exerciseCount,
                  IFNULL(SUM(s.weightKg * s.reps), 0) AS totalVolume
           FROM workouts w LEFT JOIN workout_sets s ON s.workoutId = w.id
           WHERE w.athleteId = :athleteId AND w.endedAt IS NOT NULL
           GROUP BY w.id ORDER BY w.startedAt DESC""")
    fun observeFinishedSummaries(athleteId: Long = 1L): Flow<List<WorkoutSummary>>
    @Query("SELECT startedAt FROM workouts WHERE athleteId = :athleteId AND endedAt IS NOT NULL AND startedAt BETWEEN :fromMillis AND :toMillis")
    suspend fun startTimesBetween(fromMillis: Long, toMillis: Long, athleteId: Long = 1L): List<Long>
    @Query("SELECT * FROM workouts WHERE athleteId = :athleteId AND endedAt IS NOT NULL ORDER BY startedAt")
    suspend fun getAllFinishedOnce(athleteId: Long = 1L): List<Workout>
    @Query("""SELECT w.startedAt, IFNULL(SUM(s.weightKg * s.reps), 0) AS volume
           FROM workouts w LEFT JOIN workout_sets s ON s.workoutId = w.id
           WHERE w.athleteId = :athleteId AND w.endedAt IS NOT NULL
           GROUP BY w.id ORDER BY w.startedAt""")
    suspend fun volumePoints(athleteId: Long = 1L): List<WorkoutVolumePoint>
    @Insert
    suspend fun insert(workout: Workout): Long
    @Update
    suspend fun update(workout: Workout)
    @Delete
    suspend fun delete(workout: Workout)

    @Query("INSERT INTO weight_entries(timestamp, weightKg, fromWorkout, athleteId, sourceWorkoutId) VALUES(:now, :weight, 1, :athleteId, :workoutId)")
    suspend fun insertWorkoutWeight(workoutId: Long, athleteId: Long, weight: Double, now: Long)
    @Query("DELETE FROM weight_entries WHERE sourceWorkoutId = :workoutId")
    suspend fun deleteLinkedWeight(workoutId: Long)
    @Query("DELETE FROM workouts WHERE id = :workoutId")
    suspend fun deleteById(workoutId: Long)

    /** Room serializes read-check-write even across repository instances. */
    @Transaction
    suspend fun startForAthlete(athleteId: Long, now: Long, groupSessionId: String? = null): Workout {
        ensureDefaultAthlete()
        require(availableAthlete(athleteId) == 1) { "Профиль не найден или скрыт" }
        getActiveOnce(athleteId)?.let { existing ->
            if (groupSessionId != null && existing.groupSessionId != groupSessionId) {
                return existing.copy(groupSessionId = groupSessionId).also { update(it) }
            }
            return existing
        }
        val workout = Workout(startedAt = now, athleteId = athleteId, groupSessionId = groupSessionId)
        return workout.copy(id = insert(workout))
    }

    @Transaction
    suspend fun startGroup(athleteIds: List<Long>, now: Long, newGroupId: String): List<Workout> {
        require(athleteIds.isNotEmpty()) { "Выберите участников" }
        if (athleteIds.size == 1) return listOf(startForAthlete(athleteIds.single(), now))
        // Earlier builds assigned a group UUID even to a solo session. Such a
        // singleton may join another session without losing its ID, sets or plan.
        val candidateGroups = athleteIds.mapNotNull { getActiveOnce(it)?.groupSessionId }.distinct()
        val groups = candidateGroups.filter { getGroupBySession(it).size > 1 }
        require(groups.size <= 1) { "Участники уже тренируются в разных группах" }
        val groupId = groups.firstOrNull() ?: newGroupId
        val existingMembers = getGroupBySession(groupId).associateBy { it.athleteId }
        // The first selected person is the initiator. Returning their old ended
        // workout would silently open a friend's session instead of starting theirs.
        require(existingMembers[athleteIds.first()]?.endedAt == null) {
            "Вы уже завершили эту совместную тренировку. Начните отдельную тренировку или дождитесь остальных участников."
        }
        val existingActiveIds = athleteIds.mapNotNull { getActiveOnce(it)?.id }.toSet()
        val workouts = athleteIds.map { existingMembers[it] ?: startForAthlete(it, now, groupId) }
        val sourcePlan = getPlanOnce(workouts.first().id)
        workouts.drop(1).filter { it.id !in existingActiveIds && it.athleteId !in existingMembers }.forEach { newcomer ->
            sourcePlan.forEach { insertPlan(it.copy(workoutId = newcomer.id, completedAt = null)) }
        }
        return workouts
    }

    @Transaction
    suspend fun finishWithWeight(workoutId: Long, bodyWeightKg: Double?, now: Long) {
        val workout = getById(workoutId) ?: return
        if (workout.endedAt != null) return
        update(workout.copy(endedAt = now, bodyWeightKg = bodyWeightKg))
        if (bodyWeightKg != null) insertWorkoutWeight(workout.id, workout.athleteId, bodyWeightKg, now)
    }

    @Transaction
    suspend fun finishGroup(workoutId: Long, bodyWeights: Map<Long, Double>, now: Long) {
        getGroupOnce(workoutId).forEach { finishWithWeight(it.id, bodyWeights[it.athleteId], now) }
    }

    @Transaction
    suspend fun deleteWithWeight(workoutId: Long) {
        deleteLinkedWeight(workoutId)
        deleteById(workoutId)
    }

    @Query("SELECT e.* FROM workout_exercises p JOIN exercises e ON e.id = p.exerciseId WHERE p.workoutId = :workoutId ORDER BY p.position, e.id")
    fun observePlannedExercises(workoutId: Long): Flow<List<Exercise>>
    @Query("SELECT exerciseId FROM workout_exercises WHERE workoutId = :workoutId ORDER BY position, exerciseId")
    suspend fun plannedExerciseIds(workoutId: Long): List<Long>
    @Query("SELECT * FROM workout_exercises WHERE workoutId = :workoutId ORDER BY position, exerciseId")
    suspend fun getPlanOnce(workoutId: Long): List<WorkoutExercise>
    @Query("""SELECT e.*, p.workoutId, p.position, p.targetSets, p.targetReps, p.note, p.completedAt,
        (SELECT COUNT(*) FROM workout_sets s WHERE s.workoutId = p.workoutId AND s.exerciseId = p.exerciseId) AS setCount
        FROM workout_exercises p JOIN exercises e ON e.id = p.exerciseId
        WHERE p.workoutId = :workoutId ORDER BY p.position, p.exerciseId""")
    fun observePlan(workoutId: Long): Flow<List<WorkoutPlanEntry>>
    @Query("SELECT DISTINCT exerciseId FROM workout_sets WHERE workoutId = :workoutId ORDER BY completedAt, id")
    suspend fun performedExerciseIds(workoutId: Long): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlan(plan: WorkoutExercise)
    @Query("SELECT IFNULL(MAX(position), 0) + 1 FROM workout_exercises WHERE workoutId = :workoutId")
    suspend fun nextPlanPosition(workoutId: Long): Int
    @Query("UPDATE workout_exercises SET completedAt = :completedAt WHERE workoutId = :workoutId AND exerciseId = :exerciseId")
    suspend fun setPlanCompletion(workoutId: Long, exerciseId: Long, completedAt: Long?)
    @Query("SELECT COUNT(*) FROM workout_sets WHERE workoutId = :workoutId AND exerciseId = :exerciseId")
    suspend fun countPlanSets(workoutId: Long, exerciseId: Long): Int

    @Transaction
    suspend fun markExerciseComplete(workoutId: Long, exerciseId: Long, done: Boolean, now: Long) {
        val workout = requireNotNull(getById(workoutId)) { "Тренировка не найдена" }
        require(workout.endedAt == null) { "Тренировка уже завершена" }
        val plan = requireNotNull(getPlanOnce(workoutId).find { it.exerciseId == exerciseId }) { "Упражнение не найдено в плане" }
        require(!done || countPlanSets(workoutId, exerciseId) > 0) { "Сначала запишите хотя бы один подход" }
        setPlanCompletion(workoutId, exerciseId, if (done) plan.completedAt ?: now else null)
    }

    @Transaction
    suspend fun planExercise(workoutId: Long, exerciseId: Long) {
        val workout = getById(workoutId) ?: return
        if (workout.endedAt != null) return
        insertPlan(WorkoutExercise(workoutId, exerciseId, nextPlanPosition(workoutId)))
    }

    @Transaction
    suspend fun repeatPlan(sourceWorkoutId: Long, athleteIds: List<Long>, now: Long, groupId: String): List<Workout> {
        requireNotNull(getById(sourceWorkoutId)) { "Тренировка не найдена" }
        require(athleteIds.none { getActiveOnce(it) != null }) { "Сначала завершите текущую тренировку" }
        val sourcePlan = getPlanOnce(sourceWorkoutId).associateBy { it.exerciseId }
        val exerciseIds = (sourcePlan.keys + performedExerciseIds(sourceWorkoutId)).distinct()
        val workouts = if (athleteIds.size == 1) listOf(startForAthlete(athleteIds.single(), now))
            else startGroup(athleteIds, now, groupId)
        workouts.forEach { workout ->
            exerciseIds.forEachIndexed { index, exerciseId ->
                val source = sourcePlan[exerciseId]
                insertPlan(WorkoutExercise(workout.id, exerciseId, index + 1,
                    source?.targetSets, source?.targetReps, source?.note))
            }
        }
        return workouts
    }
}

@Dao
interface TemplateDao {
    @Query("SELECT * FROM workout_templates WHERE athleteId = :athleteId ORDER BY id DESC")
    fun observeTemplates(athleteId: Long): Flow<List<WorkoutTemplate>>
    @Query("SELECT * FROM workout_templates WHERE id = :id AND athleteId = :athleteId")
    fun observeById(id: Long, athleteId: Long): Flow<WorkoutTemplate?>
    @Query("SELECT * FROM workout_templates WHERE id = :id AND athleteId = :athleteId")
    suspend fun getById(id: Long, athleteId: Long): WorkoutTemplate?
    @Query("""SELECT e.*, p.position, p.targetSets, p.targetReps, p.note
        FROM template_exercises p JOIN exercises e ON e.id = p.exerciseId
        WHERE p.templateId = :templateId ORDER BY p.position, p.exerciseId""")
    fun observeItems(templateId: Long): Flow<List<TemplateExerciseInfo>>
    @Query("""SELECT e.*, p.position, p.targetSets, p.targetReps, p.note
        FROM template_exercises p JOIN exercises e ON e.id = p.exerciseId
        WHERE p.templateId = :templateId ORDER BY p.position, p.exerciseId""")
    suspend fun getItems(templateId: Long): List<TemplateExerciseInfo>
    @Insert
    suspend fun insert(template: WorkoutTemplate): Long
    @Insert
    suspend fun insertItems(items: List<TemplateExercise>)
    @Update
    suspend fun update(template: WorkoutTemplate)
    @Query("DELETE FROM workout_templates WHERE id = :id AND athleteId = :athleteId")
    suspend fun delete(id: Long, athleteId: Long)
    @Query("DELETE FROM template_exercises WHERE templateId = :templateId")
    suspend fun deleteItems(templateId: Long)
}

@Dao
interface WorkoutSetDao {
    @Query("""SELECT s.id, s.workoutId, s.exerciseId, s.setIndex, s.weightKg, s.reps, s.completedAt,
                  e.name AS exerciseName, e.muscleGroup, e.photoPath
           FROM workout_sets s JOIN exercises e ON e.id = s.exerciseId
           WHERE s.workoutId = :workoutId ORDER BY s.completedAt, s.id""")
    fun observeForWorkout(workoutId: Long): Flow<List<SetWithExercise>>
    @Query("""SELECT s.id, s.workoutId, s.exerciseId, s.setIndex, s.weightKg, s.reps, s.completedAt,
                  e.name AS exerciseName, e.muscleGroup, e.photoPath
           FROM workout_sets s JOIN exercises e ON e.id = s.exerciseId
           WHERE s.workoutId = :workoutId ORDER BY s.completedAt, s.id""")
    suspend fun getForWorkoutOnce(workoutId: Long): List<SetWithExercise>
    @Query("SELECT * FROM workout_sets WHERE workoutId = :workoutId AND exerciseId = :exerciseId ORDER BY setIndex, id")
    fun observeForWorkoutExercise(workoutId: Long, exerciseId: Long): Flow<List<WorkoutSet>>
    @Query("SELECT * FROM workout_sets WHERE workoutId = :workoutId AND exerciseId = :exerciseId ORDER BY setIndex DESC, id DESC LIMIT 1")
    suspend fun lastSetInWorkout(workoutId: Long, exerciseId: Long): WorkoutSet?
    @Query("SELECT COUNT(*) FROM workout_sets WHERE workoutId = :workoutId AND exerciseId = :exerciseId")
    suspend fun setCount(workoutId: Long, exerciseId: Long): Int
    @Query("SELECT IFNULL(MAX(setIndex), 0) + 1 FROM workout_sets WHERE workoutId = :workoutId AND exerciseId = :exerciseId")
    suspend fun nextSetIndex(workoutId: Long, exerciseId: Long): Int
    @Query("SELECT endedAt IS NULL FROM workouts WHERE id = :workoutId")
    suspend fun isWorkoutActive(workoutId: Long): Boolean?

    @Query("""SELECT * FROM workout_sets WHERE exerciseId = :exerciseId AND workoutId = (
               SELECT s2.workoutId FROM workout_sets s2 JOIN workouts w2 ON w2.id = s2.workoutId
               WHERE s2.exerciseId = :exerciseId AND s2.workoutId != :excludeWorkoutId
                 AND w2.athleteId = :athleteId AND w2.endedAt IS NOT NULL
               ORDER BY w2.startedAt DESC, w2.id DESC LIMIT 1)
           ORDER BY setIndex, id""")
    suspend fun previousWorkoutSets(exerciseId: Long, excludeWorkoutId: Long, athleteId: Long = 1L): List<WorkoutSet>
    @Query("SELECT MAX(s.weightKg) FROM workout_sets s JOIN workouts w ON w.id = s.workoutId WHERE s.exerciseId = :exerciseId AND w.athleteId = :athleteId")
    suspend fun maxWeight(exerciseId: Long, athleteId: Long = 1L): Double?
    @Query("SELECT MAX(CASE WHEN s.reps = 0 THEN 0.0 WHEN s.reps = 1 THEN s.weightKg ELSE s.weightKg * (1 + s.reps / 30.0) END) FROM workout_sets s JOIN workouts w ON w.id = s.workoutId WHERE s.exerciseId = :exerciseId AND w.athleteId = :athleteId")
    suspend fun maxE1Rm(exerciseId: Long, athleteId: Long = 1L): Double?
    @Query("""SELECT w.startedAt, s.weightKg, s.reps FROM workout_sets s JOIN workouts w ON w.id = s.workoutId
           WHERE s.exerciseId = :exerciseId AND w.athleteId = :athleteId AND w.endedAt IS NOT NULL
           ORDER BY w.startedAt, s.setIndex""")
    suspend fun historyForExercise(exerciseId: Long, athleteId: Long = 1L): List<ExerciseSetPoint>
    @Query("SELECT DISTINCT s.exerciseId FROM workout_sets s JOIN workouts w ON w.id = s.workoutId WHERE w.athleteId = :athleteId")
    suspend fun usedExerciseIds(athleteId: Long = 1L): List<Long>
    @Insert
    suspend fun insert(set: WorkoutSet): Long
    @Update
    suspend fun update(set: WorkoutSet)
    @Delete
    suspend fun delete(set: WorkoutSet)

    @Query("""UPDATE workout_exercises SET completedAt = NULL
        WHERE workoutId = :workoutId AND exerciseId = :exerciseId
        AND NOT EXISTS (SELECT 1 FROM workout_sets WHERE workoutId = :workoutId AND exerciseId = :exerciseId)""")
    suspend fun clearEmptyPlanCompletion(workoutId: Long, exerciseId: Long)

    @Transaction
    suspend fun deleteAndRefreshCompletion(set: WorkoutSet) {
        delete(set)
        clearEmptyPlanCompletion(set.workoutId, set.exerciseId)
    }

    @Transaction
    suspend fun insertWithNextIndex(set: WorkoutSet): WorkoutSet {
        check(isWorkoutActive(set.workoutId) == true) { "Тренировка уже завершена" }
        val indexed = set.copy(setIndex = nextSetIndex(set.workoutId, set.exerciseId))
        return indexed.copy(id = insert(indexed))
    }

    @Transaction
    suspend fun recordSet(set: WorkoutSet, athleteId: Long): Pair<WorkoutSet, Pair<Double?, Double?>> {
        val previous = maxWeight(set.exerciseId, athleteId) to maxE1Rm(set.exerciseId, athleteId)
        return insertWithNextIndex(set) to previous
    }
}

@Dao
interface MealDao {
    @Query("SELECT * FROM meals WHERE athleteId = :athleteId ORDER BY timestamp, id")
    fun observeAll(athleteId: Long = 1L): Flow<List<Meal>>
    @Query("SELECT * FROM meals WHERE athleteId = :athleteId AND epochDay = :epochDay ORDER BY timestamp, id")
    fun observeForDay(epochDay: Long, athleteId: Long = 1L): Flow<List<Meal>>
    @Query("SELECT * FROM meals WHERE athleteId = :athleteId AND epochDay = :epochDay ORDER BY timestamp, id")
    suspend fun getForDayOnce(epochDay: Long, athleteId: Long = 1L): List<Meal>
    @Query("SELECT * FROM meals WHERE athleteId = :athleteId AND id IN (SELECT MAX(id) FROM meals WHERE athleteId = :athleteId GROUP BY name) ORDER BY id DESC LIMIT :limit")
    suspend fun recentDistinct(limit: Int, athleteId: Long = 1L): List<Meal>
    @Query("SELECT DISTINCT epochDay FROM meals WHERE athleteId = :athleteId AND epochDay BETWEEN :fromDay AND :toDay")
    suspend fun daysWithMeals(fromDay: Long, toDay: Long, athleteId: Long = 1L): List<Long>
    @Query("""SELECT epochDay, SUM(calories) AS calories, SUM(proteinG) AS proteinG, SUM(fatG) AS fatG, SUM(carbsG) AS carbsG
           FROM meals WHERE athleteId = :athleteId AND epochDay BETWEEN :fromDay AND :toDay GROUP BY epochDay ORDER BY epochDay""")
    suspend fun dailyTotals(fromDay: Long, toDay: Long, athleteId: Long = 1L): List<DayNutritionTotal>
    @Query("SELECT * FROM meals WHERE athleteId = :athleteId ORDER BY timestamp, id")
    suspend fun getAllOnce(athleteId: Long = 1L): List<Meal>
    @Query("SELECT * FROM meals WHERE athleteId = :athleteId AND needsEstimate = 1 ORDER BY epochDay, timestamp, id")
    suspend fun getPendingEstimatesOnce(athleteId: Long = 1L): List<Meal>
    @Query("SELECT COUNT(*) FROM meals WHERE athleteId = :athleteId AND needsEstimate = 1")
    fun observePendingEstimateCount(athleteId: Long = 1L): Flow<Int>
    @Query("SELECT * FROM meals WHERE athleteId = :athleteId AND id IN (:ids)")
    suspend fun getByIds(ids: List<Long>, athleteId: Long = 1L): List<Meal>
    @Query("UPDATE meals SET mealType = :type WHERE id = :id AND athleteId = :athleteId")
    suspend fun setMealType(id: Long, type: MealType, athleteId: Long = 1L)
    @Insert
    suspend fun insert(meal: Meal): Long
    @Update
    suspend fun update(meal: Meal)
    @Update
    suspend fun updateAll(meals: List<Meal>)
    @Delete
    suspend fun delete(meal: Meal)
}

@Dao
interface WeightDao {
    @Query("SELECT * FROM weight_entries WHERE athleteId = :athleteId ORDER BY timestamp, id")
    fun observeAll(athleteId: Long = 1L): Flow<List<WeightEntry>>
    @Query("SELECT * FROM weight_entries WHERE athleteId = :athleteId ORDER BY timestamp DESC, id DESC LIMIT 1")
    fun observeLatest(athleteId: Long = 1L): Flow<WeightEntry?>
    @Query("SELECT * FROM weight_entries WHERE athleteId = :athleteId ORDER BY timestamp, id")
    suspend fun getAllOnce(athleteId: Long = 1L): List<WeightEntry>
    @Insert
    suspend fun insert(entry: WeightEntry): Long
    @Delete
    suspend fun delete(entry: WeightEntry)
}

@Dao
interface MeasurementDao {
    @Query("SELECT * FROM body_measurements WHERE athleteId = :athleteId ORDER BY timestamp, id")
    fun observeAll(athleteId: Long = 1L): Flow<List<BodyMeasurement>>
    @Query("SELECT * FROM body_measurements WHERE athleteId = :athleteId ORDER BY timestamp, id")
    suspend fun getAllOnce(athleteId: Long = 1L): List<BodyMeasurement>
    @Query("SELECT * FROM body_measurements WHERE athleteId = :athleteId AND type = :type ORDER BY timestamp, id")
    suspend fun getForType(type: MeasurementType, athleteId: Long = 1L): List<BodyMeasurement>
    @Query("SELECT * FROM body_measurements WHERE athleteId = :athleteId AND epochDay = :epochDay ORDER BY timestamp, id")
    suspend fun getForDayOnce(epochDay: Long, athleteId: Long = 1L): List<BodyMeasurement>
    @Query("SELECT MAX(epochDay) FROM body_measurements WHERE athleteId = :athleteId")
    suspend fun lastEpochDay(athleteId: Long = 1L): Long?
    @Query("SELECT MAX(epochDay) FROM body_measurements WHERE athleteId = :athleteId")
    fun observeLastEpochDay(athleteId: Long = 1L): Flow<Long?>
    @Insert
    suspend fun insertAll(items: List<BodyMeasurement>): List<Long>
    @Update
    suspend fun update(item: BodyMeasurement)
    @Delete
    suspend fun delete(item: BodyMeasurement)
}
