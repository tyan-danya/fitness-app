package com.dtyan.fitdiary.ui.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dtyan.fitdiary.data.db.Exercise
import com.dtyan.fitdiary.data.db.ExerciseSeed
import com.dtyan.fitdiary.data.db.WorkoutTemplate
import com.dtyan.fitdiary.data.repo.ExerciseRepository
import com.dtyan.fitdiary.data.repo.TemplateRepository
import com.dtyan.fitdiary.data.repo.WorkoutRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
    /** «Не нравится» участника: в каталоге такие упражнения идут последними в своей группе. */
    val dislikedIds: Set<Long> = emptySet(),
    /** Режим замены: похожие упражнения той же группы мышц, первыми — избранные и знакомые. */
    val similar: List<Exercise> = emptyList(),
)

private data class PickerFilters(val query: String, val equipment: String?, val onlyFavorites: Boolean)

/** Где можно заменить упражнение: в невыполненном плане этой тренировки и в программах участника. */
data class ReplaceTargets(
    val exercise: Exercise? = null,
    val inWorkout: Boolean = false,
    val templates: List<WorkoutTemplate> = emptyList(),
    val loaded: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ExercisePickerViewModel(
    private val exerciseRepository: ExerciseRepository,
    private val workoutId: Long? = null,
    private val workoutRepository: WorkoutRepository? = null,
    /** Не null — каталог подбирает замену этому упражнению, а не добавляет новое. */
    private val replaceExerciseId: Long? = null,
    private val templateRepository: TemplateRepository? = null,
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

    private val _replaceTargets = MutableStateFlow(ReplaceTargets())
    val replaceTargets: StateFlow<ReplaceTargets> = _replaceTargets.asStateFlow()

    init {
        val fallbackOwner = exerciseRepository.currentAthleteId
        viewModelScope.launch {
            val resolved = workoutId?.let { workoutRepository?.getWorkoutOnce(it)?.athleteId } ?: fallbackOwner
            owner.value = resolved
            usedIds.value = exerciseRepository.usedExerciseIds(resolved)
            if (replaceExerciseId != null) try {
                val inWorkout = workoutId != null && workoutRepository?.observePlan(workoutId)?.first()
                    ?.any { it.exercise.id == replaceExerciseId && it.completedAt == null } == true
                _replaceTargets.value = ReplaceTargets(exerciseRepository.getById(replaceExerciseId), inWorkout,
                    templateRepository?.templatesWithExercise(replaceExerciseId, resolved).orEmpty(), loaded = true)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _error.value = "Не удалось найти, где стоит упражнение. Откройте замену ещё раз." }
        }
    }

    private val favoriteIds = owner.filterNotNull().flatMapLatest { exerciseRepository.observeFavoriteIds(it) }
    private val dislikedIds = owner.filterNotNull().flatMapLatest { exerciseRepository.observeDislikedIds(it) }
    /** Уже стоящие в плане тренировки — заменой их не предлагаем. */
    private val plannedIds = workoutId?.takeIf { replaceExerciseId != null }?.let { id ->
        workoutRepository?.observePlan(id)?.map { plan -> plan.map { it.exercise.id }.toSet() }
    } ?: flowOf(emptySet())
    private val marks = combine(favoriteIds, dislikedIds, plannedIds, _replaceTargets) { favorites, disliked, planned, targets ->
        Marks(favorites, disliked, planned, targets.exercise)
    }

    private data class Marks(val favorites: Set<Long>, val disliked: Set<Long>, val planned: Set<Long>, val replacing: Exercise?)

    private val filters = combine(query, equipment, onlyFavorites) { q, eq, only -> PickerFilters(q.trim(), eq, only) }

    val uiState: StateFlow<PickerUiState> = combine(
        exerciseRepository.observeExercises(),
        filters,
        usedIds,
        marks,
    ) { catalog, f, used, m ->
        val favorites = m.favorites
        // Заменяемое и уже стоящие в плане не предлагаем: выбрать их заменой нельзя.
        val exercises = catalog.filter { it.id != replaceExerciseId && it.id !in m.planned }
        val byId = exercises.associateBy { it.id }
        fun matchesEquipment(e: Exercise) = f.equipment == null || e.equipment == f.equipment
        val filtered = exercises
            .filter { f.query.isEmpty() || it.name.contains(f.query, ignoreCase = true) }
            .filter(::matchesEquipment)
            .filter { !f.onlyFavorites || it.id in favorites }

        val browsing = f.query.isEmpty() && !f.onlyFavorites
        // Избранное — в порядке добавления; фильтр по виду оборудования действует и здесь.
        val favoriteList = if (browsing) favorites.mapNotNull { byId[it] }.filter(::matchesEquipment) else emptyList()
        // «Недавние» — пересечение использованных id с активным каталогом, без повторов избранного и нелюбимых.
        val recent = if (browsing) {
            used.reversed().mapNotNull { byId[it] }
                .filter { matchesEquipment(it) && it.id !in favorites && it.id !in m.disliked }
                .take(6)
        } else emptyList()
        val similar = m.replacing?.takeIf { browsing }?.let { old ->
            val recency = used.reversed().withIndex().associate { (index, id) -> id to index }
            exercises.filter { it.muscleGroup == old.muscleGroup && it.id !in m.disliked && matchesEquipment(it) }
                .sortedWith(compareBy<Exercise>({ it.id !in favorites }, { recency[it.id] ?: Int.MAX_VALUE }, { it.name }))
                .take(8)
        }.orEmpty()

        val order = ExerciseSeed.MUSCLE_GROUPS.withIndex().associate { (index, group) -> group to index }
        val groups = filtered
            .groupBy { it.muscleGroup }
            .toList()
            .sortedBy { (group, _) -> order[group] ?: Int.MAX_VALUE }
            .map { (group, items) -> group to items.sortedBy { it.id in m.disliked } }

        PickerUiState(favorites = favoriteList, recent = recent, groups = groups, favoriteIds = favorites, onlyFavorites = f.onlyFavorites,
            dislikedIds = m.disliked, similar = similar)
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

    private var replacing = false

    /**
     * Заменить выбранным упражнением в этой тренировке ([inWorkout]) и в программах [templateIds].
     * [onDone] получает новое упражнение, если оно встало в тренировку, иначе null.
     */
    fun replace(newExerciseId: Long, inWorkout: Boolean, templateIds: List<Long>, onDone: (Long?) -> Unit) {
        val old = replaceExerciseId ?: return
        val athleteId = owner.value ?: return
        val repository = templateRepository ?: return
        if (replacing) return
        replacing = true
        viewModelScope.launch {
            try {
                _error.value = null
                repository.replaceExercise(old, newExerciseId, workoutId?.takeIf { inWorkout }, templateIds, athleteId)
                onDone(newExerciseId.takeIf { inWorkout })
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _error.value = e.message ?: "Не удалось заменить упражнение. Попробуйте ещё раз." }
            finally { replacing = false }
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
