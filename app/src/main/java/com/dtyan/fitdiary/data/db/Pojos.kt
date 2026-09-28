package com.dtyan.fitdiary.data.db

import androidx.room.Embedded
import androidx.room.Ignore

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

data class TemplateExerciseInfo(
    @Embedded val exercise: Exercise,
    val position: Int,
    val targetSets: Int,
    val targetReps: Int?,
    val note: String?,
)

data class TemplateDetails(val template: WorkoutTemplate, val items: List<TemplateExerciseInfo>)

/** Goals and explicit completion belong to this participant's workout, not the template. */
data class WorkoutPlanEntry(
    @Embedded val exercise: Exercise,
    val workoutId: Long,
    val position: Int,
    val targetSets: Int?,
    val targetReps: Int?,
    val note: String?,
    val completedAt: Long?,
    val setCount: Int,
) {
    @get:Ignore
    val exerciseId: Long get() = exercise.id
}
