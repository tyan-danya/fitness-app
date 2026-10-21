package com.dtyan.fitdiary.ui.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dtyan.fitdiary.data.db.Exercise
import com.dtyan.fitdiary.data.db.ExerciseSeed
import com.dtyan.fitdiary.data.repo.ExerciseRepository
import com.dtyan.fitdiary.data.repo.WorkoutRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PickerUiState(
    /** Избранные упражнения участника (только при пустом поиске и без фильтра «только избранное»). */
    val favorites: List<Exercise> = emptyList(),
    /** Недавно использованные упражнения, которых нет в избранном (только при пустом поиске). */
    val recent: List<Exercise> = emptyList(),
    /** Секции каталога: группа мышц → упражнения, в порядке ExerciseSeed.MUSCLE_GROUPS. */
    val groups: List<Pair<String, List<Exercise>>> = emptyList(),
    val favoriteIds: Set<Long> = emptySet(),
    val onlyFavorites: Boolean = false,
)

private data class PickerFilters(val query: String, val equipment: String?, val onlyFavorites: Boolean)

@OptIn(ExperimentalCoroutinesApi::class)
class ExercisePickerViewModel(
    private val exerciseRepository: ExerciseRepository,
    private val workoutId: Long? = null,
    private val workoutRepository: WorkoutRepository? = null,
) : ViewModel() {

    private val query = MutableStateFlow("")

    /** Фильтр по виду оборудования; null — показывать все. */
    private val equipment = MutableStateFlow<String?>(null)

    private val onlyFavorites = MutableStateFlow(false)

    private val usedIds = MutableStateFlow<List<Long>>(emptyList())

    /** Чьё избранное показываем: владельца тренировки, иначе выбранного профиля. */
    private val owner = MutableStateFlow<Long?>(null)

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        val fallbackOwner = exerciseRepository.currentAthleteId
        viewModelScope.launch {
            val resolved = workoutId?.let { workoutRepository?.getWorkoutOnce(it)?.athleteId } ?: fallbackOwner
            owner.value = resolved
            usedIds.value = exerciseRepository.usedExerciseIds(resolved)
        }
    }

    private val favoriteIds = owner.filterNotNull().flatMapLatest { exerciseRepository.observeFavoriteIds(it) }

    private val filters = combine(query, equipment, onlyFavorites) { q, eq, only -> PickerFilters(q.trim(), eq, only) }

    val uiState: StateFlow<PickerUiState> = combine(
        exerciseRepository.observeExercises(),
        filters,
        usedIds,
        favoriteIds,
    ) { exercises, f, used, favorites ->
        val byId = exercises.associateBy { it.id }
        fun matchesEquipment(e: Exercise) = f.equipment == null || e.equipment == f.equipment
        val filtered = exercises
            .filter { f.query.isEmpty() || it.name.contains(f.query, ignoreCase = true) }
            .filter(::matchesEquipment)
            .filter { !f.onlyFavorites || it.id in favorites }

        val browsing = f.query.isEmpty() && !f.onlyFavorites
        // Избранное — в порядке добавления; фильтр по виду оборудования действует и здесь.
        val favoriteList = if (browsing) favorites.mapNotNull { byId[it] }.filter(::matchesEquipment) else emptyList()
        // «Недавние» — пересечение использованных id с активным каталогом, без повторов избранного.
        val recent = if (browsing) {
            used.reversed().mapNotNull { byId[it] }
                .filter { matchesEquipment(it) && it.id !in favorites }
                .take(6)
        } else emptyList()

        val order = ExerciseSeed.MUSCLE_GROUPS.withIndex().associate { (index, group) -> group to index }
        val groups = filtered
            .groupBy { it.muscleGroup }
            .toList()
            .sortedBy { (group, _) -> order[group] ?: Int.MAX_VALUE }

        PickerUiState(favorites = favoriteList, recent = recent, groups = groups, favoriteIds = favorites, onlyFavorites = f.onlyFavorites)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PickerUiState())

    fun setQuery(value: String) {
        query.value = value
    }

    /** Установить фильтр по виду оборудования; null — «Все». */
    fun setEquipment(value: String?) {
        equipment.value = value
    }

    fun setOnlyFavorites(value: Boolean) {
        onlyFavorites.value = value
    }

    fun toggleFavorite(exerciseId: Long) {
        val athleteId = owner.value ?: return
        val favorite = exerciseId !in uiState.value.favoriteIds
        viewModelScope.launch {
            try {
                _error.value = null
                exerciseRepository.setFavorite(exerciseId, favorite, athleteId)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _error.value = "Не удалось изменить избранное. Попробуйте ещё раз." }
        }
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
