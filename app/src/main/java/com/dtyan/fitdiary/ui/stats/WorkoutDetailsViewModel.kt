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
import kotlinx.coroutines.CancellationException

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
    val busy: Boolean = false,
    val error: String? = null,
)

class WorkoutDetailsViewModel(
    private val workoutId: Long,
    private val workoutRepository: WorkoutRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(WorkoutDetailsUiState())
    val state: StateFlow<WorkoutDetailsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            try {
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
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.value = WorkoutDetailsUiState(loading = false, error = "Не удалось загрузить тренировку") }
        }
    }

    fun deleteWorkout(onDeleted: () -> Unit) = mutate {
        workoutRepository.deleteWorkout(workoutId)
        onDeleted()
    }

    fun repeatWorkout(onStarted: (Long) -> Unit) = mutate {
        val owner = _state.value.workout?.athleteId ?: return@mutate
        onStarted(workoutRepository.repeatWorkout(workoutId, listOf(owner)).first().id)
    }

    private fun mutate(action: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { _state.value = _state.value.copy(error = e.message ?: "Не удалось сохранить изменение") }
            catch (_: Exception) { _state.value = _state.value.copy(error = "Не удалось сохранить изменение. Попробуйте ещё раз.") }
            finally { _state.value = _state.value.copy(busy = false) }
        }
    }
}
