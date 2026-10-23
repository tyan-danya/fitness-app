package com.dtyan.fitdiary.ui.workout

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dtyan.fitdiary.data.db.Exercise
import com.dtyan.fitdiary.data.db.WorkoutSet
import com.dtyan.fitdiary.data.repo.ExerciseRepository
import com.dtyan.fitdiary.data.repo.TemplateRepository
import com.dtyan.fitdiary.data.repo.WorkoutRepository
import com.dtyan.fitdiary.domain.ManualStepRegistry
import com.dtyan.fitdiary.domain.WeightSteps
import com.dtyan.fitdiary.ui.common.Format
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.math.abs

data class ExerciseLogUiState(
    val exerciseName: String = "",
    val muscleGroup: String = "",
    val recordWeightKg: Double? = null,
    val previousSets: List<WorkoutSet> = emptyList(),
    val previousDateText: String? = null,
    val previousLoaded: Boolean = false,
    val photoPath: String? = null,
    val equipment: String = "",
    val weightStepKg: Double = ExerciseLogViewModel.DEFAULT_STEP,
    /** Шкала по собственным записям; null — записей мало или шкала неочевидна. */
    val stepGuess: WeightSteps.StepGuess? = null,
    /** Начало шкалы текущего шага: кнопки ± ходят по делениям stepBase + k·weightStepKg. */
    val stepBase: Double = 0.0,
    /** Шаг подобран по записям автоматически в этом сеансе — экран поясняет, откуда он. */
    val stepFromHistory: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
) {
    /** Текущий шаг совпадает с найденным по записям. */
    val stepMatchesGuess: Boolean
        get() = stepGuess != null && ExerciseLogViewModel.sameStep(stepGuess.step, weightStepKg)
}

/** One immutable workout/athlete + exercise identity; drafts cannot leak between participants. */
class ExerciseLogViewModel(
    private val workoutId: Long,
    private val exerciseId: Long,
    private val workoutRepository: WorkoutRepository,
    private val exerciseRepository: ExerciseRepository,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
    private val manualSteps: ManualStepRegistry? = null,
    /** Программы участника: после «Не нравится» в них тоже предлагается замена. */
    private val templates: TemplateRepository? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ExerciseLogUiState())
    val uiState: StateFlow<ExerciseLogUiState> = _uiState.asStateFlow()
    val todaySets = workoutRepository.observeSetsForExercise(workoutId, exerciseId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val plan = workoutRepository.observePlan(workoutId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val weightText = savedStateHandle.getStateFlow("weight", "")
    val repsText = savedStateHandle.getStateFlow("reps", "")
    private val _prEvents = Channel<Unit>(Channel.BUFFERED)
    val prEvents = _prEvents.receiveAsFlow()
    /** Вес → число подходов по всем тренировкам: из этого берутся шкала и её начало. */
    private var usage: Map<Double, Int> = emptyMap()

    private val owner = flow { workoutRepository.getWorkoutOnce(workoutId)?.athleteId?.let { emit(it) } }

    /** Избранное принадлежит участнику этой тренировки, а не профилю, выбранному в шапке. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val favorite: StateFlow<Boolean> = owner
        .flatMapLatest { owner -> exerciseRepository.observeFavoriteIds(owner).map { exerciseId in it } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** «Не нравится» — тоже отметка участника этой тренировки. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val disliked: StateFlow<Boolean> = owner
        .flatMapLatest { owner -> exerciseRepository.observeDislikedIds(owner).map { exerciseId in it } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        viewModelScope.launch {
            try {
                val exercise = exerciseRepository.getById(exerciseId)
                val previous = workoutRepository.previousWorkoutSets(exerciseId, workoutId)
                val date = previous.firstOrNull()?.let { workoutRepository.getWorkoutOnce(it.workoutId) }
                    ?.startedAt?.let { Format.dateShort(Format.local(it).toLocalDate()) }
                val record = workoutRepository.maxWeight(exerciseId, workoutId)
                val step = detectStep(exercise)
                _uiState.update { it.copy(
                    exerciseName = exercise?.name.orEmpty(), muscleGroup = exercise?.muscleGroup.orEmpty(),
                    photoPath = exercise?.photoPath, equipment = exercise?.equipment.orEmpty(),
                    weightStepKg = step.step, stepGuess = step.guess, stepBase = baseFor(step.step, step.guess),
                    stepFromHistory = step.autoApplied,
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
                            equipment = current.equipment, weightStepKg = current.weightStepKg,
                            stepBase = baseFor(current.weightStepKg, it.stepGuess)) }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _uiState.update { it.copy(error = "Не удалось загрузить упражнение. Откройте его ещё раз.") } }
        }
    }

    fun onWeightTextChange(text: String) { savedStateHandle["touched"] = true; savedStateHandle["weight"] = text }
    fun onRepsTextChange(text: String) { savedStateHandle["touched"] = true; savedStateHandle["reps"] = text }
    /**
     * Знак [delta] — направление, величина — шаг (экран передаёт текущий). Вес ходит по шкале
     * тренажёра: с деления — на соседнее, с веса вне шкалы — к ближайшему делению в ту же сторону.
     */
    fun bumpWeight(delta: Double) {
        if (!delta.isFinite() || delta == 0.0) return
        val state = _uiState.value
        val step = abs(delta)
        val base = if (sameStep(step, state.weightStepKg)) state.stepBase else WeightSteps.base(usage, step)
        val current = weightText.value.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0
        onWeightTextChange(Format.weight(WeightSteps.bump(current, step, base, up = delta > 0)))
    }
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
    fun toggleFavorite() {
        val next = !favorite.value
        viewModelScope.launch {
            try {
                val owner = requireNotNull(workoutRepository.getWorkoutOnce(workoutId)?.athleteId)
                exerciseRepository.setFavorite(exerciseId, next, owner)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _uiState.update { it.copy(error = "Не удалось изменить избранное. Попробуйте ещё раз.") } }
        }
    }
    /**
     * Ставит или снимает «Не нравится». После новой отметки [onMarked] узнаёт, есть ли где заменить
     * упражнение: в невыполненном плане этой тренировки или в программах участника.
     */
    fun toggleDislike(onMarked: (canReplace: Boolean) -> Unit = {}) {
        val next = !disliked.value
        viewModelScope.launch {
            try {
                val owner = requireNotNull(workoutRepository.getWorkoutOnce(workoutId)?.athleteId)
                exerciseRepository.setDisliked(exerciseId, next, owner)
                if (next) {
                    val inPlan = workoutRepository.observePlan(workoutId).first()
                        .any { it.exercise.id == exerciseId && it.completedAt == null }
                    onMarked(inPlan || templates?.templatesWithExercise(exerciseId, owner)?.isNotEmpty() == true)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _uiState.update { it.copy(error = "Не удалось изменить отметку. Попробуйте ещё раз.") } }
        }
    }
    /** Шаг выбран человеком: сохраняется в упражнении и больше не подбирается автоматически. */
    fun setWeightStep(step: Double) {
        if (!step.isFinite() || step !in STEP_RANGE) return
        mutate {
            exerciseRepository.updateWeightStep(exerciseId, step)
            manualSteps?.markWeightStepManual(exerciseId)
            _uiState.update { it.copy(weightStepKg = step, stepBase = baseFor(step, it.stepGuess), stepFromHistory = false) }
        }
    }
    fun completeExercise(onNext: (Long?) -> Unit) = mutate {
        require(workoutRepository.observeSetsForExercise(workoutId, exerciseId).first().isNotEmpty()) {
            "Сначала запишите хотя бы один подход"
        }
        workoutRepository.markExerciseComplete(workoutId, exerciseId, true)
        onNext(nextInPlan())
    }
    fun reopenExercise() = mutate {
        workoutRepository.markExerciseComplete(workoutId, exerciseId, false)
    }
    /** «Пропустить в этой тренировке» — и сразу к следующему невыполненному упражнению. */
    fun skipExercise(onNext: (Long?) -> Unit) = mutate {
        workoutRepository.skipExercise(workoutId, exerciseId, true)
        onNext(nextInPlan())
    }
    fun unskipExercise() = mutate {
        workoutRepository.skipExercise(workoutId, exerciseId, false)
    }
    /** Первое по плану упражнение, которое не выполнено и не пропущено (кроме текущего). */
    private suspend fun nextInPlan(): Long? = workoutRepository.observePlan(workoutId).first()
        .firstOrNull { it.completedAt == null && it.skippedAt == null && it.exercise.id != exerciseId }?.exercise?.id
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

    private class StepChoice(val step: Double, val guess: WeightSteps.StepGuess?, val autoApplied: Boolean)

    /**
     * Шкала по записям. Шаг тренажёра/блока, который ещё не меняли (2,5 по умолчанию), один раз
     * заменяется найденным; выбранный человеком шаг не трогаем. Штанге и гантелям — только подсказка.
     * Сбой подбора не мешает открыть упражнение: остаётся сохранённый шаг.
     */
    private suspend fun detectStep(exercise: Exercise?): StepChoice {
        val stored = exercise?.weightStepKg ?: DEFAULT_STEP
        if (exercise == null) return StepChoice(stored, null, false)
        return try {
            usage = exerciseRepository.weightUsage(exerciseId)
            val guess = WeightSteps.guess(usage)
            val applied = guess?.takeIf {
                exercise.equipment in STACK_EQUIPMENT && sameStep(stored, DEFAULT_STEP) && !sameStep(it.step, stored) &&
                    manualSteps?.isWeightStepManual(exerciseId) != true
            }
            if (applied != null) exerciseRepository.updateWeightStep(exerciseId, applied.step)
            StepChoice(applied?.step ?: stored, guess, applied != null)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { StepChoice(stored, null, false) }
    }

    private fun baseFor(step: Double, guess: WeightSteps.StepGuess?): Double =
        if (guess != null && sameStep(guess.step, step)) guess.base else WeightSteps.base(usage, step)

    companion object {
        const val DEFAULT_STEP = 2.5
        /** Допустимый шаг, в том числе свой: от 0,1 до 50 кг. */
        val STEP_RANGE = 0.1..50.0
        /** Наборные стеки: шкала у каждого своя, поэтому шаг подбирается автоматически. */
        val STACK_EQUIPMENT = setOf("Тренажёр", "Блок")

        fun sameStep(a: Double, b: Double): Boolean = abs(a - b) < 1e-6
    }
}
