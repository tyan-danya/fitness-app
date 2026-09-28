package com.dtyan.fitdiary.data.repo

import com.dtyan.fitdiary.data.db.Exercise
import com.dtyan.fitdiary.data.db.ExerciseDao
import com.dtyan.fitdiary.data.db.WorkoutSetDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ExerciseRepository(
    private val exerciseDao: ExerciseDao,
    private val setDao: WorkoutSetDao,
    private val activeAthleteId: StateFlow<Long> = MutableStateFlow(1L),
) {
    val currentAthleteId: Long get() = activeAthleteId.value
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
    suspend fun usedExerciseIds(athleteId: Long = activeAthleteId.value): List<Long> = setDao.usedExerciseIds(athleteId)

    suspend fun updateWeightStep(exerciseId: Long, step: Double) {
        require(step.isFinite() && step > 0) { "Шаг веса должен быть больше нуля" }
        exerciseDao.setWeightStep(exerciseId, step)
    }

    suspend fun setWeightStep(exerciseId: Long, stepKg: Double) = updateWeightStep(exerciseId, stepKg)
}
