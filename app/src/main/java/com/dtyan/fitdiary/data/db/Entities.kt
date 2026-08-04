package com.dtyan.fitdiary.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Упражнение (тренажёр). Предзаполняется при создании БД, можно добавлять свои.
 * equipment — вид: «Тренажёр», «Штанга», «Гантели», «Блок», «Своё тело», «Другое».
 * photoPath — путь фото тренажёра относительно filesDir (см. PhotoStore), null — без фото.
 */
@Entity(tableName = "exercises")
data class Exercise(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val muscleGroup: String,
    @ColumnInfo(defaultValue = "Другое")
    val equipment: String = "Другое",
    val photoPath: String? = null,
    val isCustom: Boolean = false,
    val isArchived: Boolean = false,
)

/** Тренировка. endedAt == null — активная (идёт прямо сейчас). Времена — epoch millis. */
@Entity(tableName = "workouts")
data class Workout(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long? = null,
    /** Контрольное взвешивание при завершении, кг. */
    val bodyWeightKg: Double? = null,
    val note: String? = null,
)

/** Один подход: вес × повторы. setIndex — номер подхода этого упражнения в этой тренировке (с 1). */
@Entity(
    tableName = "workout_sets",
    foreignKeys = [
        ForeignKey(
            entity = Workout::class,
            parentColumns = ["id"],
            childColumns = ["workoutId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Exercise::class,
            parentColumns = ["id"],
            childColumns = ["exerciseId"],
        ),
    ],
    indices = [Index("workoutId"), Index("exerciseId")],
)
data class WorkoutSet(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutId: Long,
    val exerciseId: Long,
    val setIndex: Int,
    val weightKg: Double,
    val reps: Int,
    val completedAt: Long,
)

/** Приём пищи. epochDay — LocalDate.toEpochDay() дня, к которому относится приём. */
@Entity(tableName = "meals", indices = [Index("epochDay")])
data class Meal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochDay: Long,
    val timestamp: Long,
    val name: String,
    val calories: Int,
    val proteinG: Double,
    val fatG: Double,
    val carbsG: Double,
)

/** Замер веса тела. fromWorkout — контрольное взвешивание при завершении тренировки. */
@Entity(tableName = "weight_entries")
data class WeightEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val weightKg: Double,
    val fromWorkout: Boolean = false,
)
