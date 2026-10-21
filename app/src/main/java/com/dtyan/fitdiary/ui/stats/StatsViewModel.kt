package com.dtyan.fitdiary.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dtyan.fitdiary.data.db.Exercise
import com.dtyan.fitdiary.data.db.ExerciseSetPoint
import com.dtyan.fitdiary.data.db.WorkoutSummary
import com.dtyan.fitdiary.data.repo.ExerciseRepository
import com.dtyan.fitdiary.data.repo.NutritionRepository
import com.dtyan.fitdiary.data.repo.StatsRepository
import com.dtyan.fitdiary.data.repo.WorkoutRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.WeekFields

/** Данные календаря месяца: дни с тренировками и дни с записями питания. */
data class CalendarUiState(
    val workoutDays: Set<LocalDate> = emptySet(),
    val mealDays: Set<LocalDate> = emptySet(),
)

/** Столбик тоннажа одной ISO-недели (по дате понедельника). */
data class WeekVolumeBar(
    val monday: LocalDate,
    val volumeKg: Double,
)

/** Прогресс выбранного упражнения: макс вес за тренировку + рекорд. */
data class ExerciseProgressUiState(
    val points: List<Pair<Long, Double>> = emptyList(),
    val recordWeightKg: Double? = null,
    val recordReps: Int? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel(
    private val workoutRepository: WorkoutRepository,
    private val nutritionRepository: NutritionRepository,
    private val statsRepository: StatsRepository,
    exerciseRepository: ExerciseRepository,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val started = SharingStarted.WhileSubscribed(5_000)

    // ---------- Календарь ----------

    private val _month = MutableStateFlow(YearMonth.now())
    val month: StateFlow<YearMonth> = _month.asStateFlow()

    // Смена месяца или завершение тренировки перезагружают данные календаря
    val calendar: StateFlow<CalendarUiState> =
        combine(_month, workoutRepository.observeFinishedSummaries(), nutritionRepository.observeAllMeals()) { m, _, _ -> m }
            .transformLatest { m -> emit(loadCalendar(m)) }
            .stateIn(viewModelScope, started, CalendarUiState())

    fun previousMonth() { _month.value = _month.value.minusMonths(1) }
    fun nextMonth() { _month.value = _month.value.plusMonths(1) }

    private suspend fun loadCalendar(month: YearMonth): CalendarUiState {
        val owner = statsRepository.currentAthleteId
        val fromMillis = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val toMillis = month.atEndOfMonth().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val workoutDays = statsRepository.workoutStartTimesBetween(fromMillis, toMillis, owner)
            .map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            .toSet()
        val mealDays = nutritionRepository
            .daysWithMeals(month.atDay(1).toEpochDay(), month.atEndOfMonth().toEpochDay(), owner)
            .map { LocalDate.ofEpochDay(it) }
            .toSet()
        return CalendarUiState(workoutDays = workoutDays, mealDays = mealDays)
    }

    // ---------- Тоннаж по неделям ----------

    val weeklyVolume: StateFlow<List<WeekVolumeBar>> =
        workoutRepository.observeFinishedSummaries()
            .transformLatest { emit(loadWeeklyVolume()) }
            .stateIn(viewModelScope, started, emptyList())

    private suspend fun loadWeeklyVolume(): List<WeekVolumeBar> {
        val dayOfWeek = WeekFields.ISO.dayOfWeek()
        val byMonday = statsRepository.volumePoints()
            .groupBy { point ->
                Instant.ofEpochMilli(point.startedAt).atZone(zone).toLocalDate().with(dayOfWeek, 1)
            }
            .mapValues { (_, points) -> points.sumOf { it.volume } }
        val currentMonday = LocalDate.now(zone).with(dayOfWeek, 1)
        return (7 downTo 0).map { weeksAgo ->
            val monday = currentMonday.minusWeeks(weeksAgo.toLong())
            WeekVolumeBar(monday = monday, volumeKg = byMonday[monday] ?: 0.0)
        }
    }

    // ---------- Прогресс упражнения ----------

    /** Каталог для выбора упражнения; избранные участника — первыми. */
    val exercises: StateFlow<List<Exercise>> =
        combine(exerciseRepository.observeExercises(), exerciseRepository.observeFavoriteIds()) { all, favorites ->
            all.sortedBy { if (it.id in favorites) 0 else 1 }
        }.stateIn(viewModelScope, started, emptyList())

    val favoriteIds: StateFlow<Set<Long>> = exerciseRepository.observeFavoriteIds()
        .stateIn(viewModelScope, started, emptySet())

    private val _selectedExercise = MutableStateFlow<Exercise?>(null)
    val selectedExercise: StateFlow<Exercise?> = _selectedExercise.asStateFlow()

    val exerciseProgress: StateFlow<ExerciseProgressUiState?> =
        combine(_selectedExercise, workoutRepository.observeFinishedSummaries()) { exercise, _ -> exercise }
        .transformLatest { exercise ->
            if (exercise == null) {
                emit(null)
            } else {
                emit(buildProgress(statsRepository.exerciseHistory(exercise.id)))
            }
        }
        .stateIn(viewModelScope, started, null)

    fun selectExercise(exercise: Exercise) { _selectedExercise.value = exercise }

    private fun buildProgress(history: List<ExerciseSetPoint>): ExerciseProgressUiState {
        if (history.isEmpty()) return ExerciseProgressUiState()
        // Точка графика — максимальный вес подхода в каждой тренировке
        val points = history.groupBy { it.startedAt }
            .map { (startedAt, sets) -> startedAt to sets.maxOf { it.weightKg } }
            .sortedBy { it.first }
        val recordWeight = history.maxOf { it.weightKg }
        val recordReps = history.filter { it.weightKg == recordWeight }.maxOf { it.reps }
        return ExerciseProgressUiState(
            points = points,
            recordWeightKg = recordWeight,
            recordReps = recordReps,
        )
    }

    // ---------- История тренировок ----------

    val history: StateFlow<List<WorkoutSummary>> = workoutRepository.observeFinishedSummaries()
        .stateIn(viewModelScope, started, emptyList())
}
