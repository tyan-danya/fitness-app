package com.dtyan.fitdiary.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dtyan.fitdiary.data.db.SetWithExercise
import com.dtyan.fitdiary.data.db.Workout
import com.dtyan.fitdiary.data.repo.WorkoutRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Подходы одного упражнения внутри тренировки. */
data class ExerciseGroup(
    val exerciseId: Long,
    val exerciseName: String,
    val muscleGroup: String,
    val sets: List<SetWithExercise>,
)

data class WorkoutDetailsUiState(
    val loading: Boolean = true,
    val workout: Workout? = null,
    val totalVolumeKg: Double = 0.0,
    val groups: List<ExerciseGroup> = emptyList(),
)

class WorkoutDetailsViewModel(
    workoutId: Long,
    private val workoutRepository: WorkoutRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(WorkoutDetailsUiState())
    val state: StateFlow<WorkoutDetailsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val workout = workoutRepository.getWorkoutOnce(workoutId)
            val sets = workoutRepository.getSetsForWorkoutOnce(workoutId)
            // groupBy сохраняет порядок первого появления упражнения в тренировке
            val groups = sets.groupBy { it.exerciseId }.map { (exerciseId, exerciseSets) ->
                ExerciseGroup(
                    exerciseId = exerciseId,
                    exerciseName = exerciseSets.first().exerciseName,
                    muscleGroup = exerciseSets.first().muscleGroup,
                    sets = exerciseSets.sortedBy { it.setIndex },
                )
            }
            _state.value = WorkoutDetailsUiState(
                loading = false,
                workout = workout,
                totalVolumeKg = sets.sumOf { it.weightKg * it.reps },
                groups = groups,
            )
        }
    }
}
