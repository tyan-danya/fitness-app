package com.dtyan.fitdiary.ui.activity

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dtyan.fitdiary.data.SettingsStore
import com.dtyan.fitdiary.data.db.ActivityDefinition
import com.dtyan.fitdiary.data.db.ActivityEntry
import com.dtyan.fitdiary.data.db.ActivityTimer
import com.dtyan.fitdiary.data.repo.ActivityDefinitionInput
import com.dtyan.fitdiary.data.repo.ActivityEntryInput
import com.dtyan.fitdiary.data.repo.ActivityRepository
import com.dtyan.fitdiary.domain.ActivityKind
import com.dtyan.fitdiary.domain.ActivityMetric
import com.dtyan.fitdiary.domain.DailyPushUps
import com.dtyan.fitdiary.ui.common.Format
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject

data class ActivityUiState(
    val definitions: List<ActivityDefinition> = emptyList(),
    val allEntries: List<ActivityEntry> = emptyList(),
    val timers: List<ActivityTimer> = emptyList(),
    val epochDay: Long = LocalDate.now().toEpochDay(),
    val loading: Boolean = true,
    val dailyPushUpTarget: Int? = null,
    val todayEpochDay: Long = LocalDate.now().toEpochDay(),
    val ownerId: Long = 1L,
) {
    val dayEntries get() = allEntries.filter { it.epochDay == epochDay }.sortedByDescending { it.timestamp }
    val summary get() = activityDaySummary(definitions, dayEntries)
    val pushUpProgress get() = DailyPushUps.calculate(definitions, allEntries, ownerId, epochDay,
        dailyPushUpTarget.takeIf { epochDay == todayEpochDay })
    val pushUpStreak get() = dailyPushUpTarget?.takeIf { epochDay == todayEpochDay }?.let { target ->
        DailyPushUps.streak(definitions, allEntries, ownerId, todayEpochDay, target)
    }
}

/** All reads, drafts, timers and writes belong to the immutable owner of this screen. */
class ActivityViewModel(
    private val repository: ActivityRepository,
    val ownerId: Long,
    private val settings: SettingsStore,
    private val savedState: SavedStateHandle = SavedStateHandle(),
    private val today: () -> Long = { LocalDate.now().toEpochDay() },
) : ViewModel() {
    val selectedDay = savedState.getStateFlow("activityDay", today())
    private val currentDay = MutableStateFlow(savedState.get<Long>("activityToday") ?: today())
    val historyId = savedState.getStateFlow<Long?>("activityHistory", null)
    val timerActivityId = savedState.getStateFlow<Long?>("activityTimer", null)
    private val ready = MutableStateFlow(false)
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _editor = MutableStateFlow(decodeDraft(savedState["activityDraft"]))
    val editor = _editor.asStateFlow()
    val state = combine(repository.observeDefinitions(ownerId, includeArchived = true), repository.observeEntries(ownerId),
        repository.observeTimers(ownerId), selectedDay, ready) { definitions, entries, timers, day, loaded ->
        ActivityUiState(definitions, entries, timers, day, !loaded, ownerId = ownerId)
    }.combine(settings.observePushUpDailyGoal(ownerId)) { state, target ->
        state.copy(dailyPushUpTarget = target)
    }.combine(currentDay) { state, day -> state.copy(todayEpochDay = day) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000),
            ActivityUiState(epochDay = selectedDay.value, todayEpochDay = currentDay.value, ownerId = ownerId))

    init {
        refreshDay()
        operation {
            try { repository.ensureDefaults(athleteId = ownerId) } finally { ready.value = true }
        }
    }

    /** Follow the calendar only when viewing today. Drafts retain their original recording date. */
    fun refreshDay() {
        val actual = today()
        val previous = currentDay.value
        if (actual != previous) {
            if (selectedDay.value == previous || selectedDay.value > actual) savedState["activityDay"] = actual
            currentDay.value = actual
        }
        if (savedState.get<Long>("activityToday") != actual) savedState["activityToday"] = actual
    }

    fun saveDailyPushUpGoal(text: String, onSaved: () -> Unit) = operation {
        refreshDay()
        require(selectedDay.value == currentDay.value) { "Дневную цель можно настроить на сегодня" }
        val target = requireNotNull(text.trim().toIntOrNull()) { "Введите целое число повторов" }
        settings.setPushUpDailyGoal(target, athleteId = ownerId)
        _message.value = "Дневная цель по отжиманиям сохранена"
        onSaved()
    }

    fun clearDailyPushUpGoal(onSaved: () -> Unit) = operation {
        refreshDay()
        require(selectedDay.value == currentDay.value) { "Дневную цель можно настроить на сегодня" }
        settings.setPushUpDailyGoal(null, athleteId = ownerId)
        _message.value = "Дневная цель отключена. Записи сохранены"
        onSaved()
    }

    fun selectDay(day: Long) { if (!_busy.value && day <= today()) savedState["activityDay"] = day }
    fun openHistory(activityId: Long?) { if (!_busy.value) { _error.value = null; _message.value = null; savedState["activityHistory"] = activityId } }
    fun openTimer(activityId: Long?) { if (!_busy.value) { _error.value = null; _message.value = null; savedState["activityTimer"] = activityId } }
    fun clearMessage() { _message.value = null }
    fun clearError() { _error.value = null }
    fun newEntry(activityId: Long) {
        if (_busy.value) return
        refreshDay()
        _error.value = null; _message.value = null
        setDraft(ActivityEntryDraft(activityId, selectedDay.value, UUID.randomUUID().toString()))
    }
    fun editEntry(entry: ActivityEntry) {
        if (_busy.value || entry.athleteId != ownerId) return
        _error.value = null; _message.value = null
        setDraft(ActivityEntryDraft(entry.activityId, entry.epochDay, entry.clientToken, entry.id,
            entry.reps?.toString().orEmpty(), entry.durationSeconds?.toString().orEmpty(),
            entry.distanceKm?.toString()?.replace('.', ',').orEmpty(), entry.steps?.toString().orEmpty(),
            entry.durationSeconds?.let { activityNumber(it / 60.0) }.orEmpty(),
            entry.speedKmh?.toString()?.replace('.', ',').orEmpty(), entry.inclinePercent?.toString()?.replace('.', ',').orEmpty(),
            entry.note.orEmpty(), entry.isClean))
    }
    fun updateDraft(transform: (ActivityEntryDraft) -> ActivityEntryDraft) {
        if (!_busy.value) _editor.value?.let { _error.value = null; setDraft(transform(it)) }
    }
    fun closeEditor() { if (!_busy.value) { setDraft(null); _error.value = null } }
    fun saveEntry() {
        val draft = _editor.value ?: return
        operation {
            val definition = requireNotNull(repository.getDefinition(draft.activityId, athleteId = ownerId)) { "Активность не найдена" }
            val input = parseEntry(draft, definition.kind)
            if (draft.entryId == null) repository.saveEntry(draft.activityId, input, athleteId = ownerId)
            else repository.updateEntry(draft.entryId, input, athleteId = ownerId)
            setDraft(null)
            _message.value = "${definition.name}: запись сохранена"
        }
    }
    fun deleteEntry(entry: ActivityEntry, onDeleted: () -> Unit) = operation {
        repository.deleteEntry(entry.id, athleteId = ownerId)
        _message.value = "Запись удалена"
        onDeleted()
    }
    fun saveGoals(activityId: Long, values: List<String>, onSaved: () -> Unit) = operation {
        val goals = values.filter { it.isNotBlank() }.map {
            requireNotNull(number(it)) { "Введите число для каждой ступени" }.also { value ->
                require(value > 0) { "Каждая ступень должна быть больше нуля" }
            }
        }
        require(goals.zipWithNext().all { (a, b) -> b > a }) { "Каждая следующая ступень должна быть больше предыдущей" }
        repository.updateGoals(activityId, goals, athleteId = ownerId)
        onSaved()
    }
    fun saveDefinition(existing: ActivityDefinition?, name: String, kind: ActivityKind, metric: ActivityMetric,
        instructions: String, onSaved: () -> Unit) = operation {
        repository.upsertDefinition(ActivityDefinitionInput(
            key = existing?.key ?: "custom_${UUID.randomUUID()}", name = name.trim(), kind = existing?.kind ?: kind,
            goalMetric = existing?.goalMetric ?: metric, goals = existing?.goals.orEmpty(), instructions = instructions.trim(),
            weeklyScheduleNote = existing?.weeklyScheduleNote,
            sortOrder = existing?.sortOrder ?: ((state.value.definitions.maxOfOrNull { it.sortOrder } ?: -1) + 1),
        ), athleteId = ownerId)
        onSaved()
    }
    fun archive(definition: ActivityDefinition, onArchived: () -> Unit) = operation {
        repository.archiveDefinition(definition.id, athleteId = ownerId)
        onArchived()
    }

    fun startTimer(activityId: Long) = operation { repository.startTimer(activityId, athleteId = ownerId) }
    fun stopTimer(activityId: Long) = operation { repository.stopTimer(activityId, athleteId = ownerId) }
    fun discardTimer(activityId: Long) = operation { repository.discardTimer(activityId, athleteId = ownerId) }
    fun elapsedSeconds(timer: ActivityTimer): Long? = repository.elapsedSeconds(timer)
    fun saveTimer(timer: ActivityTimer, seconds: String, note: String, clean: Boolean) = operation {
        require(timer.athleteId == ownerId) { "Секундомер принадлежит другому профилю" }
        val actual = requireNotNull(seconds.trim().toLongOrNull()?.takeIf { it > 0 }) { "Укажите фактическую длительность в секундах" }
        repository.saveEntry(timer.activityId, ActivityEntryInput(
            epochDay = Format.epochDayOf(timer.startWallTime), durationSeconds = actual,
            note = note.trim().ifEmpty { null }, isClean = clean, clientToken = timer.clientToken,
        ), athleteId = ownerId)
        _message.value = "Секундомер: подход сохранён"
        savedState["activityDay"] = Format.epochDayOf(timer.startWallTime)
        savedState["activityTimer"] = null
    }

    private fun operation(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true; _error.value = null; _message.value = null
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _error.value = e.message ?: "Не удалось сохранить. Попробуйте ещё раз." }
            finally { _busy.value = false }
        }
    }
    private fun setDraft(value: ActivityEntryDraft?) {
        _editor.value = value
        savedState["activityDraft"] = value?.let { draft -> JSONObject().apply {
            put("activity", draft.activityId); put("day", draft.epochDay); put("token", draft.clientToken)
            put("id", draft.entryId ?: JSONObject.NULL); put("reps", draft.reps); put("seconds", draft.seconds)
            put("distance", draft.distance); put("steps", draft.steps); put("minutes", draft.minutes)
            put("speed", draft.speed); put("incline", draft.incline); put("note", draft.note); put("clean", draft.isClean)
        }.toString() }
    }
    companion object {
        internal fun number(value: String): Double? = value.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
        internal fun parseEntry(draft: ActivityEntryDraft, kind: ActivityKind): ActivityEntryInput {
            fun decimal(text: String, label: String, allowZero: Boolean = false): Double? = text.takeIf { it.isNotBlank() }?.let {
                requireNotNull(number(it)?.takeIf { n -> if (allowZero) n >= 0 else n > 0 }) { "$label: введите корректное число" }
            }
            fun integer(text: String, label: String): Long? = text.takeIf { it.isNotBlank() }?.let {
                requireNotNull(it.trim().toLongOrNull()?.takeIf { n -> n > 0 }) { "$label: введите целое число больше нуля" }
            }
            val reps = if (kind == ActivityKind.REPS) requireNotNull(integer(draft.reps, "Повторы")) { "Укажите количество повторов" } else null
            require(reps == null || reps <= Int.MAX_VALUE) { "Слишком большое количество повторов" }
            val seconds = when (kind) {
                ActivityKind.REPS -> null
                ActivityKind.DURATION -> requireNotNull(integer(draft.seconds, "Секунды")) { "Укажите фактические секунды" }
                ActivityKind.WALK -> decimal(draft.minutes, "Минуты")?.let { minutes ->
                    require(minutes <= 1440 && minutes * 60 >= 1) { "Длительность прогулки: от 1 секунды до 24 часов" }
                    kotlin.math.round(minutes * 60).toLong()
                }
            }
            val distance = if (kind == ActivityKind.WALK) decimal(draft.distance, "Километры") else null
            val steps = if (kind == ActivityKind.WALK) integer(draft.steps, "Шаги") else null
            if (kind == ActivityKind.WALK) require(distance != null || steps != null || seconds != null) {
                "Укажите километры, шаги или время этой прогулки"
            }
            return ActivityEntryInput(draft.epochDay, reps?.toInt(), seconds, distance, steps,
                if (kind == ActivityKind.WALK) decimal(draft.speed, "Скорость") else null,
                if (kind == ActivityKind.WALK && draft.incline.isNotBlank()) requireNotNull(number(draft.incline)) { "Наклон: введите корректное число" } else null,
                draft.note.trim().ifEmpty { null }, draft.isClean, draft.clientToken)
        }
        private fun decodeDraft(value: String?): ActivityEntryDraft? = value?.let { runCatching {
            val json = JSONObject(it)
            ActivityEntryDraft(json.getLong("activity"), json.getLong("day"), json.getString("token"),
                if (json.isNull("id")) null else json.getLong("id"), json.getString("reps"), json.getString("seconds"),
                json.getString("distance"), json.getString("steps"), json.getString("minutes"), json.getString("speed"),
                json.getString("incline"), json.getString("note"), json.getBoolean("clean"))
        }.getOrNull() }
    }
}
