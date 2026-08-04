package com.dtyan.fitdiary.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dtyan.fitdiary.data.db.Meal
import com.dtyan.fitdiary.data.db.WorkoutSummary
import com.dtyan.fitdiary.data.repo.NutritionRepository
import com.dtyan.fitdiary.data.repo.WorkoutRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class DayDetailsViewModel(
    epochDay: Long,
    workoutRepository: WorkoutRepository,
    nutritionRepository: NutritionRepository,
) : ViewModel() {

    private val date: LocalDate = LocalDate.ofEpochDay(epochDay)
    private val zone: ZoneId = ZoneId.systemDefault()
    private val started = SharingStarted.WhileSubscribed(5_000)

    /** Завершённые тренировки, начавшиеся в этот день (по локальной дате). */
    val workouts: StateFlow<List<WorkoutSummary>> = workoutRepository.observeFinishedSummaries()
        .map { summaries ->
            summaries.filter {
                Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() == date
            }
        }
        .stateIn(viewModelScope, started, emptyList())

    val meals: StateFlow<List<Meal>> = nutritionRepository.observeMealsForDay(epochDay)
        .stateIn(viewModelScope, started, emptyList())
}
