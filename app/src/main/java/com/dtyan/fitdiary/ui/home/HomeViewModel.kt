package com.dtyan.fitdiary.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dtyan.fitdiary.data.db.WeightEntry
import com.dtyan.fitdiary.data.db.Workout
import com.dtyan.fitdiary.data.db.WorkoutSummary
import com.dtyan.fitdiary.data.repo.StatsRepository
import com.dtyan.fitdiary.data.repo.WorkoutRepository
import com.dtyan.fitdiary.domain.Calculations
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Сводные показатели для строки карточек-чипов на главном экране. */
data class HomeStats(
    val workoutsLast7Days: Int = 0,
    val weeklyStreak: Int = 0,
)

class HomeViewModel(
    private val workoutRepository: WorkoutRepository,
    statsRepository: StatsRepository,
) : ViewModel() {

    val activeWorkout: StateFlow<Workout?> = workoutRepository.observeActiveWorkout()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val summaries: StateFlow<List<WorkoutSummary>> = workoutRepository.observeFinishedSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val stats: StateFlow<HomeStats> = summaries
        .map { list ->
            val startTimes = list.map { it.startedAt }
            HomeStats(
                workoutsLast7Days = Calculations.workoutsInLastDays(
                    startTimes = startTimes,
                    days = 7,
                    now = System.currentTimeMillis(),
                ),
                weeklyStreak = Calculations.weeklyStreak(startTimes),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeStats())

    val latestWeight: StateFlow<WeightEntry?> = statsRepository.observeLatestWeight()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _starting = MutableStateFlow(false)
    val starting = _starting.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    /** Старт тренировки (или продолжение уже активной — репозиторий идемпотентен). */
    fun startWorkout(onStarted: (Long) -> Unit) {
        val owner = workoutRepository.currentAthleteId
        start(onStarted) { workoutRepository.startWorkout(athleteId = owner) }
    }

    fun startTogether(athleteIds: List<Long>, onStarted: (Long) -> Unit) {
        val ids = athleteIds.toList()
        start(onStarted) { workoutRepository.startGroupWorkout(ids).first().id }
    }

    fun repeatWorkout(sourceWorkoutId: Long, athleteIds: List<Long>, onStarted: (Long) -> Unit) {
        val ids = athleteIds.toList()
        start(onStarted) { workoutRepository.repeatWorkout(sourceWorkoutId, ids).first().id }
    }

    private fun start(onStarted: (Long) -> Unit, action: suspend () -> Long) {
        if (_starting.value) return
        _starting.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                onStarted(action())
            } catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) {
                _error.value = e.message ?: "Не удалось начать тренировку. Проверьте участников."
            } catch (_: Exception) {
                _error.value = "Не удалось начать тренировку. Попробуйте ещё раз."
            } finally {
                _starting.value = false
            }
        }
    }
}
