package com.dtyan.fitdiary.ui.stats

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dtyan.fitdiary.data.db.Exercise
import com.dtyan.fitdiary.data.db.ExerciseSetPoint
import com.dtyan.fitdiary.data.db.WeightEntry
import com.dtyan.fitdiary.data.db.WorkoutSummary
import com.dtyan.fitdiary.data.repo.ExerciseRepository
import com.dtyan.fitdiary.data.repo.NutritionRepository
import com.dtyan.fitdiary.data.repo.StatsRepository
import com.dtyan.fitdiary.data.repo.WorkoutRepository
import com.dtyan.fitdiary.export.ExportManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.WeekFields

enum class ExportFormat { JSON, CSV }

/** Данные календаря месяца: дни с тренировками и дни с записями питания. */
data class CalendarUiState(
    val workoutDays: Set<LocalDate> = emptySet(),
    val mealDays: Set<LocalDate> = emptySet(),
)

/** Карточка веса: текущий замер, дельта за 30 дней, точки для графика за 90 дней. */
data class WeightUiState(
    val current: WeightEntry? = null,
    val delta30d: Double? = null,
    val chartPoints: List<Pair<Long, Double>> = emptyList(),
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
    appContext: Context,
    private val workoutRepository: WorkoutRepository,
    private val nutritionRepository: NutritionRepository,
    private val statsRepository: StatsRepository,
    exerciseRepository: ExerciseRepository,
) : ViewModel() {

    private val exportManager = ExportManager(
        context = appContext.applicationContext,
        workoutRepository = workoutRepository,
        nutritionRepository = nutritionRepository,
        statsRepository = statsRepository,
    )

    private val zone: ZoneId = ZoneId.systemDefault()
    private val started = SharingStarted.WhileSubscribed(5_000)

    // ---------- Календарь ----------

    private val _month = MutableStateFlow(YearMonth.now())
    val month: StateFlow<YearMonth> = _month.asStateFlow()

    // Смена месяца или завершение тренировки перезагружают данные календаря
    val calendar: StateFlow<CalendarUiState> =
        combine(_month, workoutRepository.observeFinishedSummaries()) { m, _ -> m }
            .transformLatest { m -> emit(loadCalendar(m)) }
            .stateIn(viewModelScope, started, CalendarUiState())

    fun previousMonth() { _month.value = _month.value.minusMonths(1) }
    fun nextMonth() { _month.value = _month.value.plusMonths(1) }

    private suspend fun loadCalendar(month: YearMonth): CalendarUiState {
        val fromMillis = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val toMillis = month.atEndOfMonth().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val workoutDays = statsRepository.workoutStartTimesBetween(fromMillis, toMillis)
            .map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            .toSet()
        val mealDays = nutritionRepository
            .daysWithMeals(month.atDay(1).toEpochDay(), month.atEndOfMonth().toEpochDay())
            .map { LocalDate.ofEpochDay(it) }
            .toSet()
        return CalendarUiState(workoutDays = workoutDays, mealDays = mealDays)
    }

    // ---------- Вес ----------

    val weight: StateFlow<WeightUiState> = statsRepository.observeWeightHistory()
        .map { entries -> buildWeightState(entries, System.currentTimeMillis()) }
        .stateIn(viewModelScope, started, WeightUiState())

    fun addWeight(weightKg: Double) {
        viewModelScope.launch { statsRepository.addWeightEntry(weightKg) }
    }

    private fun buildWeightState(entries: List<WeightEntry>, now: Long): WeightUiState {
        if (entries.isEmpty()) return WeightUiState()
        val current = entries.last() // DAO отдаёт по возрастанию timestamp
        val monthAgo = now - 30L * 24 * 60 * 60 * 1000
        // База для дельты: последний замер не позже 30 дней назад, иначе самый ранний
        val baseline = entries.lastOrNull { it.timestamp <= monthAgo } ?: entries.first()
        val delta = if (entries.size >= 2) current.weightKg - baseline.weightKg else null
        val from90 = now - 90L * 24 * 60 * 60 * 1000
        val points = entries.filter { it.timestamp >= from90 }.map { it.timestamp to it.weightKg }
        return WeightUiState(current = current, delta30d = delta, chartPoints = points)
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

    val exercises: StateFlow<List<Exercise>> = exerciseRepository.observeExercises()
        .stateIn(viewModelScope, started, emptyList())

    private val _selectedExercise = MutableStateFlow<Exercise?>(null)
    val selectedExercise: StateFlow<Exercise?> = _selectedExercise.asStateFlow()

    val exerciseProgress: StateFlow<ExerciseProgressUiState?> = _selectedExercise
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

    // ---------- Экспорт ----------

    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting.asStateFlow()

    private val _exportIntents = Channel<Intent>(Channel.BUFFERED)
    /** Готовые share-интенты: composable запускает их через context.startActivity. */
    val exportIntents: Flow<Intent> = _exportIntents.receiveAsFlow()

    fun export(format: ExportFormat) {
        if (_exporting.value) return
        viewModelScope.launch {
            _exporting.value = true
            try {
                val intent = when (format) {
                    ExportFormat.JSON -> exportManager.exportJson()
                    ExportFormat.CSV -> exportManager.exportCsv()
                }
                _exportIntents.send(intent)
            } finally {
                _exporting.value = false
            }
        }
    }
}
