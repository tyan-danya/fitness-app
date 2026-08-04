package com.dtyan.fitdiary.data.db

/** Подход вместе с названием упражнения — для списков и экспорта. */
data class SetWithExercise(
    val id: Long,
    val workoutId: Long,
    val exerciseId: Long,
    val setIndex: Int,
    val weightKg: Double,
    val reps: Int,
    val completedAt: Long,
    val exerciseName: String,
    val muscleGroup: String,
    val photoPath: String? = null,
)

/** Сводка завершённой тренировки для списков истории. */
data class WorkoutSummary(
    val id: Long,
    val startedAt: Long,
    val endedAt: Long?,
    val bodyWeightKg: Double?,
    val setCount: Int,
    val exerciseCount: Int,
    val totalVolume: Double,
)

/** Точка истории упражнения: один подход в завершённой тренировке. */
data class ExerciseSetPoint(
    val startedAt: Long,
    val weightKg: Double,
    val reps: Int,
)

/** Суммарное КБЖУ за день. */
data class DayNutritionTotal(
    val epochDay: Long,
    val calories: Int,
    val proteinG: Double,
    val fatG: Double,
    val carbsG: Double,
)

/** Тоннаж одной завершённой тренировки (для графика по неделям). */
data class WorkoutVolumePoint(
    val startedAt: Long,
    val volume: Double,
)
