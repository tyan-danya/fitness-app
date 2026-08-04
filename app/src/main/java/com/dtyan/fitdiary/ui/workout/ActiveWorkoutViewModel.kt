package com.dtyan.fitdiary.ui.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dtyan.fitdiary.data.RestTimerController
import com.dtyan.fitdiary.data.db.SetWithExercise
import com.dtyan.fitdiary.data.db.WeightEntry
import com.dtyan.fitdiary.data.db.Workout
import com.dtyan.fitdiary.data.repo.StatsRepository
import com.dtyan.fitdiary.data.repo.WorkoutRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Упражнение внутри активной тренировки: группа подходов одной карточкой. */
data class ExerciseGroupUi(
    val exerciseId: Long,
    val name: String,
    val muscleGroup: String,
    val sets: List<SetWithExercise>,
)

class ActiveWorkoutViewModel(
    private val workoutId: Long,
    private val workoutRepository: WorkoutRepository,
    statsRepository: StatsRepository,
    private val restTimer: RestTimerController,
) : ViewModel() {

    val workout: StateFlow<Workout?> = workoutRepository.observeWorkout(workoutId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val sets: StateFlow<List<SetWithExercise>> = workoutRepository.observeSetsForWorkout(workoutId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Группировка по упражнению. Список из DAO отсортирован по completedAt,
     * поэтому groupBy (LinkedHashMap) даёт группы в порядке первого подхода.
     */
    val exerciseGroups: StateFlow<List<ExerciseGroupUi>> = sets
        .map { list ->
            list.groupBy { it.exerciseId }.map { (exerciseId, exerciseSets) ->
                ExerciseGroupUi(
                    exerciseId = exerciseId,
                    name = exerciseSets.first().exerciseName,
                    muscleGroup = exerciseSets.first().muscleGroup,
                    sets = exerciseSets,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Последний замер веса — префилл поля контрольного взвешивания. */
    val latestWeight: StateFlow<WeightEntry?> = statsRepository.observeLatestWeight()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private var closing = false

    fun cancelWorkout(onClosed: () -> Unit) {
        if (closing) return
        closing = true
        viewModelScope.launch {
            workoutRepository.cancelWorkout(workoutId)
            restTimer.stop()
            onClosed()
        }
    }

    fun finishWorkout(bodyWeightKg: Double?, onClosed: () -> Unit) {
        if (closing) return
        closing = true
        viewModelScope.launch {
            workoutRepository.finishWorkout(workoutId, bodyWeightKg)
            restTimer.stop()
            onClosed()
        }
    }
}
