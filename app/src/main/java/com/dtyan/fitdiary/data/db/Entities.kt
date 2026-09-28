package com.dtyan.fitdiary.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.dtyan.fitdiary.domain.MealType
import com.dtyan.fitdiary.domain.MeasurementType
import com.dtyan.fitdiary.domain.Per100

/** Локальный спортсмен. Архивирование сохраняет всю его историю. */
@Entity(tableName = "athletes")
data class Athlete(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(defaultValue = "0") val isArchived: Boolean = false,
)

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
    @ColumnInfo(defaultValue = "2.5") val weightStepKg: Double = 2.5,
)

/** Тренировка. endedAt == null — активная (идёт прямо сейчас). Времена — epoch millis. */
@Entity(tableName = "workouts", indices = [Index("athleteId"), Index("groupSessionId")])
data class Workout(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long? = null,
    /** Контрольное взвешивание при завершении, кг. */
    val bodyWeightKg: Double? = null,
    val note: String? = null,
    @ColumnInfo(defaultValue = "1") val athleteId: Long = 1L,
    val groupSessionId: String? = null,
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
    indices = [Index("workoutId"), Index("exerciseId"), Index(value = ["workoutId", "exerciseId", "setIndex"], unique = true)],
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

/**
 * Приём пищи. epochDay — LocalDate.toEpochDay() дня, к которому относится приём.
 *
 * calories/proteinG/fatG/carbsG — итог порции (по ним считаются все суммы).
 * servingG — вес порции в граммах (null — неизвестен).
 * *Per100 — как пользователь ввёл значения «на 100 г» (null — вводил итог порции целиком);
 * при заданных servingG и *Per100 итог = per100 × servingG / 100.
 * needsEstimate — КБЖУ ещё не известно, приём ждёт расчёта (ИИ или файл-обмен).
 */
@Entity(tableName = "meals", indices = [Index("epochDay"), Index(value = ["athleteId", "epochDay"])])
data class Meal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochDay: Long,
    val timestamp: Long,
    val name: String,
    val calories: Int,
    val proteinG: Double,
    val fatG: Double,
    val carbsG: Double,
    @ColumnInfo(defaultValue = "SNACK")
    val mealType: MealType = MealType.SNACK,
    val servingG: Double? = null,
    val caloriesPer100: Double? = null,
    val proteinPer100: Double? = null,
    val fatPer100: Double? = null,
    val carbsPer100: Double? = null,
    @ColumnInfo(defaultValue = "0")
    val needsEstimate: Boolean = false,
    @ColumnInfo(defaultValue = "1") val athleteId: Long = 1L,
) {
    /** Значения на 100 г, если приём вводился в этом режиме. */
    val per100: Per100?
        get() {
            val k = caloriesPer100 ?: return null
            return Per100(k, proteinPer100 ?: 0.0, fatPer100 ?: 0.0, carbsPer100 ?: 0.0)
        }
}

/** Замер веса тела. fromWorkout — контрольное взвешивание при завершении тренировки. */
@Entity(tableName = "weight_entries", indices = [Index("athleteId"), Index("sourceWorkoutId")])
data class WeightEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val weightKg: Double,
    val fromWorkout: Boolean = false,
    @ColumnInfo(defaultValue = "1") val athleteId: Long = 1L,
    val sourceWorkoutId: Long? = null,
)

/**
 * Замер тела сантиметровой лентой. epochDay — день замера (LocalDate.toEpochDay()),
 * type — зона (талия, грудь…), valueCm — значение в сантиметрах.
 */
@Entity(tableName = "body_measurements", indices = [Index("epochDay"), Index("type"), Index(value = ["athleteId", "epochDay"])])
data class BodyMeasurement(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochDay: Long,
    val timestamp: Long,
    val type: MeasurementType,
    val valueCm: Double,
    val note: String? = null,
    @ColumnInfo(defaultValue = "1") val athleteId: Long = 1L,
)

/** План упражнений хранится отдельно: повтор тренировки не создаёт выполненные подходы. */
@Entity(
    tableName = "workout_exercises",
    primaryKeys = ["workoutId", "exerciseId"],
    foreignKeys = [
        ForeignKey(entity = Workout::class, parentColumns = ["id"], childColumns = ["workoutId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Exercise::class, parentColumns = ["id"], childColumns = ["exerciseId"]),
    ],
    indices = [Index("exerciseId")],
)
data class WorkoutExercise(
    val workoutId: Long,
    val exerciseId: Long,
    val position: Int,
    val targetSets: Int? = null,
    val targetReps: Int? = null,
    val note: String? = null,
    val completedAt: Long? = null,
)

/** Личный сохранённый план. Начатые тренировки хранят независимую копию. */
@Entity(
    tableName = "workout_templates",
    foreignKeys = [ForeignKey(entity = Athlete::class, parentColumns = ["id"], childColumns = ["athleteId"])],
    indices = [Index("athleteId")],
)
data class WorkoutTemplate(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(defaultValue = "1") val athleteId: Long = 1L,
    val name: String,
)

@Entity(
    tableName = "template_exercises",
    primaryKeys = ["templateId", "exerciseId"],
    foreignKeys = [
        ForeignKey(entity = WorkoutTemplate::class, parentColumns = ["id"], childColumns = ["templateId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Exercise::class, parentColumns = ["id"], childColumns = ["exerciseId"]),
    ],
    indices = [Index("exerciseId")],
)
data class TemplateExercise(
    val templateId: Long,
    val exerciseId: Long,
    val position: Int,
    @ColumnInfo(defaultValue = "3") val targetSets: Int = 3,
    val targetReps: Int? = null,
    val note: String? = null,
)
