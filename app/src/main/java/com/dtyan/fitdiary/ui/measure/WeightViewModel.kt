package com.dtyan.fitdiary.ui.measure

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dtyan.fitdiary.data.db.WeightEntry
import com.dtyan.fitdiary.data.repo.StatsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Карточка веса: текущий замер, дельта за 30 дней, точки для графика за 90 дней. */
data class WeightUiState(
    val current: WeightEntry? = null,
    val delta30d: Double? = null,
    val chartPoints: List<Pair<Long, Double>> = emptyList(),
)

/** Вес тела на вкладке «Тело»: история взвешиваний и ручная запись. */
class WeightViewModel(
    private val statsRepository: StatsRepository,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    val weight: StateFlow<WeightUiState> = statsRepository.observeWeightHistory()
        .map { entries -> buildWeightState(entries, now()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WeightUiState())

    /** Владелец и время фиксируются в момент нажатия, даже если профиль сменится до записи. */
    fun addWeight(weightKg: Double) {
        val owner = statsRepository.currentAthleteId
        val timestamp = now()
        viewModelScope.launch { statsRepository.addWeightEntry(weightKg, now = timestamp, athleteId = owner) }
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
}
