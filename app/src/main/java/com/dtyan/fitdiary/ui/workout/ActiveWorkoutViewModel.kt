package com.dtyan.fitdiary.ui.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dtyan.fitdiary.data.db.SetWithExercise
import com.dtyan.fitdiary.data.db.Workout
import com.dtyan.fitdiary.data.repo.WorkoutRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ExerciseGroupUi(
    val exerciseId: Long,
    val name: String,
    val muscleGroup: String,
    val sets: List<SetWithExercise>,
    val photoPath: String? = null,
    val planned: Boolean = false,
    val targetSets: Int? = null,
    val targetReps: Int? = null,
    val note: String? = null,
    val completed: Boolean = false,
)

class ActiveWorkoutViewModel(
    private val workoutId: Long,
    private val workoutRepository: WorkoutRepository,
) : ViewModel() {
    val workout = workoutRepository.observeWorkout(workoutId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val sets = workoutRepository.observeSetsForWorkout(workoutId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val participants = workoutRepository.observeGroupWorkouts(workoutId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val exerciseGroups = combine(sets, workoutRepository.observePlan(workoutId)) { list, plan ->
        val byExercise = list.groupBy { it.exerciseId }
        val planned = plan.map { entry -> ExerciseGroupUi(
            entry.exercise.id, entry.exercise.name, entry.exercise.muscleGroup,
            byExercise[entry.exercise.id].orEmpty(), entry.exercise.photoPath,
            planned = true, targetSets = entry.targetSets, targetReps = entry.targetReps,
            note = entry.note, completed = entry.completedAt != null,
        ) }
        planned + byExercise.filterKeys { id -> plan.none { it.exercise.id == id } }.map { (id, rows) ->
            ExerciseGroupUi(id, rows.first().exerciseName, rows.first().muscleGroup, rows, rows.first().photoPath)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun markComplete(exerciseId: Long, done: Boolean) {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            try { workoutRepository.markExerciseComplete(workoutId, exerciseId, done) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { _error.value = "Не удалось изменить отметку. Попробуйте ещё раз." }
            finally { _busy.value = false }
        }
    }

    fun cancelWorkout(onClosed: () -> Unit) = close(onClosed) { workoutRepository.deleteWorkout(workoutId) }
    fun finishWorkout(bodyWeightKg: Double?, onClosed: () -> Unit) {
        if (bodyWeightKg != null && (!bodyWeightKg.isFinite() || bodyWeightKg <= 0 || bodyWeightKg >= 500)) return
        close(onClosed) { workoutRepository.finishWorkout(workoutId, bodyWeightKg) }
    }
    fun finishGroup(bodyWeights: Map<Long, Double>, onClosed: () -> Unit) {
        if (bodyWeights.values.any { !it.isFinite() || it <= 0 || it >= 500 }) return
        close(onClosed) { workoutRepository.finishGroupWorkout(workoutId, bodyWeights) }
    }
    private fun close(onClosed: () -> Unit, action: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            try { action(); onClosed() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { _error.value = "Не удалось сохранить. Попробуйте ещё раз."; _busy.value = false }
        }
    }
}
