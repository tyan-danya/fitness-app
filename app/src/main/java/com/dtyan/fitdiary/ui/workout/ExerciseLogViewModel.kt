package com.dtyan.fitdiary.ui.workout

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dtyan.fitdiary.data.db.WorkoutSet
import com.dtyan.fitdiary.data.repo.ExerciseRepository
import com.dtyan.fitdiary.data.repo.WorkoutRepository
import com.dtyan.fitdiary.ui.common.Format
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ExerciseLogUiState(
    val exerciseName: String = "",
    val muscleGroup: String = "",
    val recordWeightKg: Double? = null,
    val previousSets: List<WorkoutSet> = emptyList(),
    val previousDateText: String? = null,
    val previousLoaded: Boolean = false,
    val photoPath: String? = null,
    val equipment: String = "",
    val weightStepKg: Double = 2.5,
    val saving: Boolean = false,
    val error: String? = null,
)

/** One immutable workout/athlete + exercise identity; drafts cannot leak between participants. */
class ExerciseLogViewModel(
    private val workoutId: Long,
    private val exerciseId: Long,
    private val workoutRepository: WorkoutRepository,
    private val exerciseRepository: ExerciseRepository,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(ExerciseLogUiState())
    val uiState: StateFlow<ExerciseLogUiState> = _uiState.asStateFlow()
    val todaySets = workoutRepository.observeSetsForExercise(workoutId, exerciseId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val weightText = savedStateHandle.getStateFlow("weight", "")
    val repsText = savedStateHandle.getStateFlow("reps", "")
    private val _prEvents = Channel<Unit>(Channel.BUFFERED)
    val prEvents = _prEvents.receiveAsFlow()

    init {
        viewModelScope.launch {
            try {
                val exercise = exerciseRepository.getById(exerciseId)
                val previous = workoutRepository.previousWorkoutSets(exerciseId, workoutId)
                val date = previous.firstOrNull()?.let { workoutRepository.getWorkoutOnce(it.workoutId) }
                    ?.startedAt?.let { Format.dateShort(Format.local(it).toLocalDate()) }
                val record = workoutRepository.maxWeight(exerciseId, workoutId)
                _uiState.update { it.copy(
                    exerciseName = exercise?.name.orEmpty(), muscleGroup = exercise?.muscleGroup.orEmpty(),
                    photoPath = exercise?.photoPath, equipment = exercise?.equipment.orEmpty(),
                    weightStepKg = exercise?.weightStepKg ?: 2.5,
                    recordWeightKg = record,
                    previousSets = previous, previousDateText = date, previousLoaded = true,
                ) }
                val prefill = workoutRepository.lastSetForPrefill(workoutId, exerciseId)
                if (savedStateHandle.get<Boolean>("touched") != true) {
                    savedStateHandle["weight"] = Format.weight(prefill?.weightKg ?: 20.0)
                    savedStateHandle["reps"] = (prefill?.reps ?: 10).toString()
                }
                exerciseRepository.observeExercises().collect { exercises ->
                    exercises.firstOrNull { it.id == exerciseId }?.let { current ->
                        _uiState.update { it.copy(exerciseName = current.name, photoPath = current.photoPath,
                            equipment = current.equipment, weightStepKg = current.weightStepKg) }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _uiState.update { it.copy(error = "Не удалось загрузить упражнение. Откройте его ещё раз.") } }
        }
    }

    fun onWeightTextChange(text: String) { savedStateHandle["touched"] = true; savedStateHandle["weight"] = text }
    fun onRepsTextChange(text: String) { savedStateHandle["touched"] = true; savedStateHandle["reps"] = text }
    fun bumpWeight(delta: Double) = onWeightTextChange(Format.weight(
        ((weightText.value.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0) + delta).coerceAtLeast(0.0)))
    fun bumpReps(delta: Int) = onRepsTextChange(((repsText.value.toIntOrNull() ?: 0).toLong() + delta).coerceIn(1, 999).toString())
    fun repeatPrevious() { _uiState.value.previousSets.lastOrNull()?.let {
        onWeightTextChange(Format.weight(it.weightKg)); onRepsTextChange(it.reps.toString())
    } }

    /** Lock before launch; callback receives the exact persisted set for Undo. */
    fun addSet(onSaved: (WorkoutSet) -> Unit = {}) {
        val weight = weightText.value.replace(',', '.').toDoubleOrNull() ?: return
        val reps = repsText.value.toIntOrNull() ?: return
        if (!weight.isFinite() || weight !in 0.0..2000.0 || reps !in 1..999 || _uiState.value.saving) return
        _uiState.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val result = workoutRepository.addSet(workoutId, exerciseId, weight, reps)
                refreshRecord()
                if (result.isWeightPr || result.isE1RmPr) _prEvents.send(Unit)
                onSaved(result.set)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _uiState.update { it.copy(error = "Подход не записан. Попробуйте ещё раз.") } }
            finally { _uiState.update { it.copy(saving = false) } }
        }
    }

    fun updateSet(set: WorkoutSet, weightKg: Double, reps: Int) {
        if (set.workoutId != workoutId || set.exerciseId != exerciseId || !weightKg.isFinite() || weightKg !in 0.0..2000.0 || reps !in 1..999) return
        mutate { workoutRepository.updateSet(set.copy(weightKg = weightKg, reps = reps)); refreshRecord() }
    }
    fun deleteSet(set: WorkoutSet) {
        if (set.workoutId != workoutId || set.exerciseId != exerciseId) return
        // Undo targets an immutable row ID and remains valid while another set is saving.
        viewModelScope.launch {
            try { workoutRepository.deleteSet(set); refreshRecord() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { _uiState.update { it.copy(error = "Не удалось удалить подход. Попробуйте ещё раз.") } }
        }
    }
    fun updatePhoto(path: String?, onSaved: () -> Unit = {}) = mutate {
        val exercise = exerciseRepository.getById(exerciseId) ?: return@mutate
        exerciseRepository.updatePhoto(exercise, path)
        _uiState.update { it.copy(photoPath = path) }
        onSaved()
    }
    fun setWeightStep(step: Double) = mutate {
        exerciseRepository.updateWeightStep(exerciseId, step)
        _uiState.update { it.copy(weightStepKg = step) }
    }
    private fun mutate(action: suspend () -> Unit) {
        if (_uiState.value.saving) return
        _uiState.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { _uiState.update { it.copy(error = "Не удалось сохранить изменение. Попробуйте ещё раз.") } }
            finally { _uiState.update { it.copy(saving = false) } }
        }
    }
    private suspend fun refreshRecord() {
        val record = workoutRepository.maxWeight(exerciseId, workoutId)
        _uiState.update { it.copy(recordWeightKg = record) }
    }
}
