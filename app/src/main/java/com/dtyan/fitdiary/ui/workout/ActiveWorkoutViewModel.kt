package com.dtyan.fitdiary.ui.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dtyan.fitdiary.data.db.SetWithExercise
import com.dtyan.fitdiary.data.db.Workout
import com.dtyan.fitdiary.data.repo.ExerciseRepository
import com.dtyan.fitdiary.data.repo.WorkoutRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    /** Пропущено в этой тренировке: «Следующее» его не предлагает. */
    val skipped: Boolean = false,
    /** У участника стоит отметка «Не нравится». */
    val disliked: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ActiveWorkoutViewModel(
    private val workoutId: Long,
    private val workoutRepository: WorkoutRepository,
    private val exerciseRepository: ExerciseRepository? = null,
) : ViewModel() {
    val workout = workoutRepository.observeWorkout(workoutId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val sets = workoutRepository.observeSetsForWorkout(workoutId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val participants = workoutRepository.observeGroupWorkouts(workoutId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    /** Отметки «Не нравится» участника этой тренировки, а не профиля в шапке. */
    private val dislikedIds = workout.filterNotNull().map { it.athleteId }.distinctUntilChanged()
        .flatMapLatest { owner -> exerciseRepository?.observeDislikedIds(owner) ?: flowOf(emptySet()) }
    val exerciseGroups = combine(sets, workoutRepository.observePlan(workoutId), dislikedIds.onStart { emit(emptySet()) }) { list, plan, disliked ->
        val byExercise = list.groupBy { it.exerciseId }
        val planned = plan.map { entry -> ExerciseGroupUi(
            entry.exercise.id, entry.exercise.name, entry.exercise.muscleGroup,
            byExercise[entry.exercise.id].orEmpty(), entry.exercise.photoPath,
            planned = true, targetSets = entry.targetSets, targetReps = entry.targetReps,
            note = entry.note, completed = entry.completedAt != null, skipped = entry.skippedAt != null,
            disliked = entry.exercise.id in disliked,
        ) }
        planned + byExercise.filterKeys { id -> plan.none { it.exercise.id == id } }.map { (id, rows) ->
            ExerciseGroupUi(id, rows.first().exerciseName, rows.first().muscleGroup, rows, rows.first().photoPath,
                disliked = id in disliked)
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

    /** «Пропустить в этой тренировке» или вернуть пропущенное в план. */
    fun skip(exerciseId: Long, skipped: Boolean) = act("Не удалось изменить отметку. Попробуйте ещё раз.") {
        workoutRepository.skipExercise(workoutId, exerciseId, skipped)
    }

    fun setDisliked(exerciseId: Long, disliked: Boolean) = act("Не удалось изменить отметку. Попробуйте ещё раз.") {
        val owner = requireNotNull(workoutRepository.getWorkoutOnce(workoutId)) { "Тренировка не найдена" }.athleteId
        requireNotNull(exerciseRepository).setDisliked(exerciseId, disliked, owner)
    }

    private fun act(failure: String, action: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { _error.value = failure }
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
