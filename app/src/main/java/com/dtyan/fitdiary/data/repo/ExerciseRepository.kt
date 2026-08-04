package com.dtyan.fitdiary.data.repo

import com.dtyan.fitdiary.data.db.Exercise
import com.dtyan.fitdiary.data.db.ExerciseDao
import com.dtyan.fitdiary.data.db.WorkoutSetDao
import kotlinx.coroutines.flow.Flow

class ExerciseRepository(
    private val exerciseDao: ExerciseDao,
    private val setDao: WorkoutSetDao,
) {
    fun observeExercises(): Flow<List<Exercise>> = exerciseDao.observeActive()

    suspend fun getById(id: Long): Exercise? = exerciseDao.getById(id)

    suspend fun addCustom(
        name: String,
        muscleGroup: String,
        equipment: String = "Тренажёр",
        photoPath: String? = null,
    ): Long = exerciseDao.insert(
        Exercise(
            name = name.trim(),
            muscleGroup = muscleGroup,
            equipment = equipment,
            photoPath = photoPath,
            isCustom = true,
        )
    )

    suspend fun rename(exercise: Exercise, newName: String) =
        exerciseDao.update(exercise.copy(name = newName.trim()))

    /** Привязать/заменить/убрать (null) фото тренажёра. Старый файл удаляет вызывающий. */
    suspend fun updatePhoto(exercise: Exercise, photoPath: String?) =
        exerciseDao.update(exercise.copy(photoPath = photoPath))

    /** Скрыть из каталога (история подходов сохраняется). */
    suspend fun archive(exercise: Exercise) =
        exerciseDao.update(exercise.copy(isArchived = true))

    /** id упражнений, которыми пользователь уже пользовался — для блока «недавние». */
    suspend fun usedExerciseIds(): List<Long> = setDao.usedExerciseIds()
}
