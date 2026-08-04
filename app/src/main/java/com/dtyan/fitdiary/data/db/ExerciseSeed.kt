package com.dtyan.fitdiary.data.db

import androidx.sqlite.db.SupportSQLiteDatabase

/** Стартовый каталог упражнений: (название, группа мышц, вид оборудования). */
object ExerciseSeed {

    data class SeedExercise(val name: String, val muscleGroup: String, val equipment: String)

    val EXERCISES: List<SeedExercise> = listOf(
        SeedExercise("Жим штанги лёжа", "Грудь", "Штанга"),
        SeedExercise("Жим гантелей лёжа", "Грудь", "Гантели"),
        SeedExercise("Жим в тренажёре от груди", "Грудь", "Тренажёр"),
        SeedExercise("Сведение рук (бабочка)", "Грудь", "Тренажёр"),
        SeedExercise("Отжимания на брусьях", "Грудь", "Своё тело"),
        SeedExercise("Подтягивания", "Спина", "Своё тело"),
        SeedExercise("Тяга верхнего блока", "Спина", "Блок"),
        SeedExercise("Тяга горизонтального блока", "Спина", "Блок"),
        SeedExercise("Тяга штанги в наклоне", "Спина", "Штанга"),
        SeedExercise("Гиперэкстензия", "Спина", "Своё тело"),
        SeedExercise("Приседания со штангой", "Ноги", "Штанга"),
        SeedExercise("Жим ногами", "Ноги", "Тренажёр"),
        SeedExercise("Разгибание ног", "Ноги", "Тренажёр"),
        SeedExercise("Сгибание ног лёжа", "Ноги", "Тренажёр"),
        SeedExercise("Подъём на носки", "Ноги", "Тренажёр"),
        SeedExercise("Жим гантелей сидя", "Плечи", "Гантели"),
        SeedExercise("Жим штанги стоя", "Плечи", "Штанга"),
        SeedExercise("Махи гантелями в стороны", "Плечи", "Гантели"),
        SeedExercise("Обратная бабочка", "Плечи", "Тренажёр"),
        SeedExercise("Сгибание рук со штангой", "Бицепс", "Штанга"),
        SeedExercise("Сгибание рук с гантелями", "Бицепс", "Гантели"),
        SeedExercise("Молотки", "Бицепс", "Гантели"),
        SeedExercise("Французский жим", "Трицепс", "Штанга"),
        SeedExercise("Разгибание рук на блоке", "Трицепс", "Блок"),
        SeedExercise("Скручивания", "Пресс", "Своё тело"),
        SeedExercise("Подъём ног в висе", "Пресс", "Своё тело"),
    )

    /** Порядок групп для интерфейса выбора. */
    val MUSCLE_GROUPS: List<String> =
        listOf("Грудь", "Спина", "Ноги", "Плечи", "Бицепс", "Трицепс", "Пресс", "Другое")

    /** Виды оборудования для фильтра и формы создания. */
    val EQUIPMENT_TYPES: List<String> =
        listOf("Тренажёр", "Штанга", "Гантели", "Блок", "Своё тело", "Другое")

    fun seed(db: SupportSQLiteDatabase) {
        EXERCISES.forEach { e ->
            db.execSQL(
                "INSERT INTO exercises (name, muscleGroup, equipment, isCustom, isArchived) VALUES (?, ?, ?, 0, 0)",
                arrayOf(e.name, e.muscleGroup, e.equipment),
            )
        }
    }

    /** Backfill вида оборудования для предзаполненных упражнений при миграции 1→2. */
    fun backfillEquipment(db: SupportSQLiteDatabase) {
        EXERCISES.forEach { e ->
            db.execSQL(
                "UPDATE exercises SET equipment = ? WHERE name = ? AND isCustom = 0",
                arrayOf(e.equipment, e.name),
            )
        }
    }
}
