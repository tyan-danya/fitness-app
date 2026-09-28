package com.dtyan.fitdiary.ui.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dtyan.fitdiary.data.db.Exercise
import com.dtyan.fitdiary.data.db.ExerciseSeed
import com.dtyan.fitdiary.data.repo.ExerciseRepository
import com.dtyan.fitdiary.data.repo.WorkoutRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PickerUiState(
    /** Недавно использованные упражнения (только при пустом поиске). */
    val recent: List<Exercise> = emptyList(),
    /** Секции каталога: группа мышц → упражнения, в порядке ExerciseSeed.MUSCLE_GROUPS. */
    val groups: List<Pair<String, List<Exercise>>> = emptyList(),
)

class ExercisePickerViewModel(
    private val exerciseRepository: ExerciseRepository,
    private val workoutId: Long? = null,
    private val workoutRepository: WorkoutRepository? = null,
) : ViewModel() {

    private val query = MutableStateFlow("")

    /** Фильтр по виду оборудования; null — показывать все. */
    private val equipment = MutableStateFlow<String?>(null)

    private val usedIds = MutableStateFlow<List<Long>>(emptyList())

    init {
        val fallbackOwner = exerciseRepository.currentAthleteId
        viewModelScope.launch {
            val owner = workoutId?.let { workoutRepository?.getWorkoutOnce(it)?.athleteId } ?: fallbackOwner
            usedIds.value = exerciseRepository.usedExerciseIds(owner)
        }
    }

    val uiState: StateFlow<PickerUiState> = combine(
        exerciseRepository.observeExercises(),
        query,
        equipment,
        usedIds,
    ) { exercises, q, eq, used ->
        val trimmed = q.trim()
        val filtered = exercises
            .filter { trimmed.isEmpty() || it.name.contains(trimmed, ignoreCase = true) }
            .filter { eq == null || it.equipment == eq }

        // «Недавние» — пересечение использованных id с активным каталогом
        // (фильтр по виду оборудования действует и здесь).
        val byId = exercises.associateBy { it.id }
        val recent =
            if (trimmed.isEmpty()) {
                used.reversed().mapNotNull { byId[it] }
                    .filter { eq == null || it.equipment == eq }
                    .take(6)
            } else emptyList()

        val order = ExerciseSeed.MUSCLE_GROUPS.withIndex().associate { (index, group) -> group to index }
        val groups = filtered
            .groupBy { it.muscleGroup }
            .toList()
            .sortedBy { (group, _) -> order[group] ?: Int.MAX_VALUE }

        PickerUiState(recent = recent, groups = groups)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PickerUiState())

    fun setQuery(value: String) {
        query.value = value
    }

    /** Установить фильтр по виду оборудования; null — «Все». */
    fun setEquipment(value: String?) {
        equipment.value = value
    }

    private var creating = false

    fun createExercise(
        name: String,
        muscleGroup: String,
        equipment: String = "Тренажёр",
        photoPath: String? = null,
        onCreated: (Long) -> Unit,
    ) {
        if (creating || name.isBlank()) return
        creating = true
        viewModelScope.launch {
            try {
                onCreated(exerciseRepository.addCustom(name, muscleGroup, equipment, photoPath))
            } finally {
                creating = false
            }
        }
    }
}
