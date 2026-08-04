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

    private var starting = false

    /** Старт тренировки (или продолжение уже активной — репозиторий идемпотентен). */
    fun startWorkout(onStarted: (Long) -> Unit) {
        if (starting) return
        starting = true
        viewModelScope.launch {
            try {
                onStarted(workoutRepository.startWorkout())
            } finally {
                starting = false
            }
        }
    }
}
