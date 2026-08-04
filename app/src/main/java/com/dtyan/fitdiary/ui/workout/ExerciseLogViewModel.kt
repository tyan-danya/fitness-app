package com.dtyan.fitdiary.ui.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dtyan.fitdiary.data.RestTimerController
import com.dtyan.fitdiary.data.SettingsStore
import com.dtyan.fitdiary.data.db.WorkoutSet
import com.dtyan.fitdiary.data.repo.ExerciseRepository
import com.dtyan.fitdiary.data.repo.WorkoutRepository
import com.dtyan.fitdiary.ui.common.Format
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ExerciseLogUiState(
    val exerciseName: String = "",
    val muscleGroup: String = "",
    /** Исторический рекорд веса по упражнению; null — подходов ещё не было. */
    val recordWeightKg: Double? = null,
    /** Подходы прошлой тренировки с этим упражнением. */
    val previousSets: List<WorkoutSet> = emptyList(),
    /** Дата прошлой тренировки, уже отформатированная («5 июля»). */
    val previousDateText: String? = null,
    /** Прошлые данные загружены (чтобы не мигать надписью «впервые»). */
    val previousLoaded: Boolean = false,
    /** Фото тренажёра: путь относительно filesDir; null — фото нет. */
    val photoPath: String? = null,
    /** Вид оборудования упражнения («Тренажёр», «Штанга»…). */
    val equipment: String = "",
)

class ExerciseLogViewModel(
    private val workoutId: Long,
    private val exerciseId: Long,
    private val workoutRepository: WorkoutRepository,
    private val exerciseRepository: ExerciseRepository,
    private val settingsStore: SettingsStore,
    private val restTimer: RestTimerController,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExerciseLogUiState())
    val uiState: StateFlow<ExerciseLogUiState> = _uiState.asStateFlow()

    /** Подходы текущей тренировки по этому упражнению. */
    val todaySets: StateFlow<List<WorkoutSet>> =
        workoutRepository.observeSetsForExercise(workoutId, exerciseId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<SettingsStore.Settings> = settingsStore.settings

    private val _weightText = MutableStateFlow("")
    val weightText: StateFlow<String> = _weightText.asStateFlow()

    private val _repsText = MutableStateFlow("")
    val repsText: StateFlow<String> = _repsText.asStateFlow()

    /** Одноразовые события «новый личный рекорд» для снекбара. */
    private val _prEvents = Channel<Unit>(Channel.BUFFERED)
    val prEvents: Flow<Unit> = _prEvents.receiveAsFlow()

    /** Пользователь уже трогал поля ввода — префилл их не перетирает. */
    private var inputTouched = false

    init {
        viewModelScope.launch {
            val exercise = exerciseRepository.getById(exerciseId)
            val record = workoutRepository.maxWeight(exerciseId)
            val previous = workoutRepository.previousWorkoutSets(exerciseId, workoutId)
            val previousDate = previous.firstOrNull()
                ?.let { workoutRepository.getWorkoutOnce(it.workoutId)?.startedAt }
                ?.let { Format.dateShort(Format.local(it).toLocalDate()) }
            _uiState.update {
                it.copy(
                    exerciseName = exercise?.name.orEmpty(),
                    muscleGroup = exercise?.muscleGroup.orEmpty(),
                    photoPath = exercise?.photoPath,
                    equipment = exercise?.equipment.orEmpty(),
                    recordWeightKg = record,
                    previousSets = previous,
                    previousDateText = previousDate,
                    previousLoaded = true,
                )
            }
            if (!inputTouched) {
                val prefill = workoutRepository.lastSetForPrefill(workoutId, exerciseId)
                _weightText.value = Format.weight(prefill?.weightKg ?: 20.0)
                _repsText.value = (prefill?.reps ?: 10).toString()
            }
        }
    }

    fun onWeightTextChange(text: String) {
        inputTouched = true
        _weightText.value = text
    }

    fun onRepsTextChange(text: String) {
        inputTouched = true
        _repsText.value = text
    }

    /** Шаг веса ±2,5 кг от текущего значения поля. */
    fun bumpWeight(delta: Double) {
        inputTouched = true
        val current = _weightText.value.replace(',', '.').toDoubleOrNull() ?: 0.0
        _weightText.value = Format.weight((current + delta).coerceAtLeast(0.0))
    }

    /** Шаг повторов ±1, не меньше одного. */
    fun bumpReps(delta: Int) {
        inputTouched = true
        val current = _repsText.value.toIntOrNull() ?: 0
        _repsText.value = (current + delta).coerceAtLeast(1).toString()
    }

    fun addSet() {
        val weight = _weightText.value.replace(',', '.').toDoubleOrNull() ?: return
        val reps = _repsText.value.toIntOrNull() ?: return
        if (weight < 0 || reps < 1) return
        viewModelScope.launch {
            val result = workoutRepository.addSet(workoutId, exerciseId, weight, reps)
            refreshRecord()
            val current = settingsStore.settings.value
            if (current.restTimerEnabled) {
                restTimer.start(current.restTimerSeconds)
            }
            if (result.isWeightPr || result.isE1RmPr) {
                _prEvents.send(Unit)
            }
        }
    }

    fun updateSet(set: WorkoutSet, weightKg: Double, reps: Int) {
        if (weightKg < 0 || reps < 1) return
        viewModelScope.launch {
            workoutRepository.updateSet(set.copy(weightKg = weightKg, reps = reps))
            refreshRecord()
        }
    }

    fun deleteSet(set: WorkoutSet) {
        viewModelScope.launch {
            workoutRepository.deleteSet(set)
            refreshRecord()
        }
    }

    /**
     * Привязать/заменить/убрать (null) фото тренажёра.
     * Файлы на диске чистит вызывающий через PhotoStore.
     */
    fun updatePhoto(path: String?) {
        viewModelScope.launch {
            val exercise = exerciseRepository.getById(exerciseId) ?: return@launch
            exerciseRepository.updatePhoto(exercise, path)
            _uiState.update { it.copy(photoPath = path) }
        }
    }

    fun setRestTimerEnabled(enabled: Boolean) {
        settingsStore.update { it.copy(restTimerEnabled = enabled) }
    }

    fun setRestTimerSeconds(seconds: Int) {
        settingsStore.update { it.copy(restTimerSeconds = seconds) }
    }

    private suspend fun refreshRecord() {
        _uiState.update { it.copy(recordWeightKg = workoutRepository.maxWeight(exerciseId)) }
    }
}
