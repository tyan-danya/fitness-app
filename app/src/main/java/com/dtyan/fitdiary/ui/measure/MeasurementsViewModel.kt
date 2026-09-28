package com.dtyan.fitdiary.ui.measure

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dtyan.fitdiary.data.SettingsStore
import com.dtyan.fitdiary.data.db.BodyMeasurement
import com.dtyan.fitdiary.data.repo.MeasurementRepository
import com.dtyan.fitdiary.domain.MeasurementType
import com.dtyan.fitdiary.domain.isMeasurementValueValid
import com.dtyan.fitdiary.domain.measurementReminderDue
import com.dtyan.fitdiary.ui.common.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Карточка одной зоны: текущее значение, изменение с предыдущего замера и за 30 дней, точки графика. */
data class MetricUiState(
    val type: MeasurementType,
    val current: BodyMeasurement?,
    val deltaPrev: Double?,
    val delta30d: Double?,
    val chartPoints: List<Pair<Long, Double>>,
    val history: List<BodyMeasurement>,
)

/** Состояние экрана: отслеживаемые зоны, дни тишины, «пора ли мерить». */
data class MeasurementsUiState(
    val metrics: List<MetricUiState> = emptyList(),
    val lastDay: LocalDate? = null,
    val daysSinceLast: Long? = null,
    val reminderDue: Boolean = false,
    val hasAny: Boolean = false,
)

/** Форма «записать замеры»: одно поле на каждую отслеживаемую зону; пустое — не записывать. */
data class SessionEditorState(
    val values: Map<MeasurementType, String> = emptyMap(),
    val note: String = "",
) {
    /** Разобранные корректные значения. */
    val parsed: Map<MeasurementType, Double>
        get() = values.mapNotNull { (type, text) ->
            parse(text)?.takeIf(::isMeasurementValueValid)?.let { type to it }
        }.toMap()

    fun isInvalid(type: MeasurementType): Boolean {
        val text = values[type].orEmpty()
        if (text.isBlank()) return false
        val v = parse(text) ?: return true
        return !isMeasurementValueValid(v)
    }

    val canSave: Boolean get() = parsed.isNotEmpty() && values.keys.none { isInvalid(it) }

    companion object {
        fun parse(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()
    }
}

class MeasurementsViewModel(
    private val repo: MeasurementRepository,
    private val settingsStore: SettingsStore,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    val settings: StateFlow<SettingsStore.Settings> = settingsStore.settings

    val state: StateFlow<MeasurementsUiState> = combine(repo.observeAll(), settingsStore.settings) { all, s ->
        buildState(all, s, now())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MeasurementsUiState())

    private val _editor = MutableStateFlow<SessionEditorState?>(null)
    val editor: StateFlow<SessionEditorState?> = _editor.asStateFlow()

    /** Зона, для которой открыта история (полный список замеров с удалением). */
    private val _historyType = MutableStateFlow<MeasurementType?>(null)
    val historyType: StateFlow<MeasurementType?> = _historyType.asStateFlow()

    internal fun buildState(all: List<BodyMeasurement>, s: SettingsStore.Settings, nowMillis: Long): MeasurementsUiState {
        val byType = all.groupBy { it.type }
        val monthAgo = nowMillis - 30L * 24 * 60 * 60 * 1000
        val from180 = nowMillis - 180L * 24 * 60 * 60 * 1000
        val metrics = MeasurementType.entries.filter { it in s.trackedMeasurements }.map { type ->
            val list = byType[type].orEmpty() // DAO отдаёт по возрастанию timestamp
            val current = list.lastOrNull()
            val prev = list.getOrNull(list.size - 2)
            val baseline = list.lastOrNull { it.timestamp <= monthAgo } ?: list.firstOrNull()
            MetricUiState(
                type = type,
                current = current,
                deltaPrev = if (current != null && prev != null) current.valueCm - prev.valueCm else null,
                delta30d = if (current != null && baseline != null && baseline.id != current.id) current.valueCm - baseline.valueCm else null,
                chartPoints = list.filter { it.timestamp >= from180 }.map { it.timestamp to it.valueCm },
                history = list.asReversed(),
            )
        }
        val today = Format.epochDayOf(nowMillis)
        val lastDay = all.maxOfOrNull { it.epochDay }
        return MeasurementsUiState(
            metrics = metrics,
            lastDay = lastDay?.let(LocalDate::ofEpochDay),
            daysSinceLast = lastDay?.let { today - it },
            reminderDue = s.measurementReminderEnabled && measurementReminderDue(
                lastEpochDay = lastDay,
                sinceEpochDay = s.measurementReminderSinceDay,
                todayEpochDay = today,
                thresholdDays = s.measurementReminderDays,
            ),
            hasAny = all.isNotEmpty(),
        )
    }

    // --- Запись сессии замеров ---

    fun openEditor() {
        val tracked = settings.value.trackedMeasurements
        _editor.value = SessionEditorState(values = MeasurementType.entries.filter { it in tracked }.associateWith { "" })
    }

    fun closeEditor() {
        _editor.value = null
    }

    fun onValueChange(type: MeasurementType, text: String) = _editor.update { it?.copy(values = it.values + (type to text)) }
    fun onNoteChange(text: String) = _editor.update { it?.copy(note = text) }

    fun saveEditor() {
        val state = _editor.value ?: return
        if (!state.canSave) return
        _editor.value = null
        viewModelScope.launch { repo.addSession(state.parsed, now = now(), note = state.note) }
    }

    fun delete(item: BodyMeasurement) {
        viewModelScope.launch { repo.delete(item) }
    }

    fun openHistory(type: MeasurementType) {
        _historyType.value = type
    }

    fun closeHistory() {
        _historyType.value = null
    }

    // --- Настройки ---

    fun updateTracked(tracked: Set<MeasurementType>) {
        if (tracked.isEmpty()) return
        settingsStore.update { it.copy(trackedMeasurements = tracked) }
    }

    fun updateReminder(enabled: Boolean, days: Int, hour: Int) {
        settingsStore.update {
            it.copy(
                measurementReminderEnabled = enabled,
                measurementReminderDays = days.coerceIn(1, 60),
                measurementReminderHour = hour.coerceIn(0, 23),
                // Включили заново — тишину считаем с сегодняшнего дня, а не с первого запуска
                measurementReminderSinceDay = if (enabled && !it.measurementReminderEnabled) {
                    Format.epochDayOf(now())
                } else it.measurementReminderSinceDay,
            )
        }
    }
}
