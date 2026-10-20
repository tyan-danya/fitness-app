package com.dtyan.fitdiary.data.repo

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.AtomicFile
import androidx.room.withTransaction
import com.dtyan.fitdiary.data.db.ActivityDefinition
import com.dtyan.fitdiary.data.db.ActivityEntry
import com.dtyan.fitdiary.data.db.ActivityTimer
import com.dtyan.fitdiary.data.db.AppDatabase
import com.dtyan.fitdiary.domain.ActivityKind
import com.dtyan.fitdiary.domain.ActivityMetric
import com.dtyan.fitdiary.domain.ActivityProgression
import com.dtyan.fitdiary.domain.defaultMetric
import java.time.LocalDate
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ActivityDefinitionInput(
    val key: String,
    val name: String,
    val kind: ActivityKind,
    val goalMetric: ActivityMetric = kind.defaultMetric,
    val goals: List<Double> = emptyList(),
    val instructions: String = "",
    val weeklyScheduleNote: String? = null,
    val sortOrder: Int = 0,
)

data class ActivityEntryInput(
    val epochDay: Long,
    val reps: Int? = null,
    val durationSeconds: Long? = null,
    val distanceKm: Double? = null,
    val steps: Long? = null,
    val speedKmh: Double? = null,
    val inclinePercent: Double? = null,
    val note: String? = null,
    val isClean: Boolean = true,
    val clientToken: String,
)

interface ActivityClock {
    fun elapsedRealtime(): Long
    fun wallTimeMillis(): Long
    fun bootCount(): Int?
    /** Default is for injected test clocks; production uses an identity excluded from Android backup. */
    fun clockIdentity(): String = "test-clock"
}

class AndroidActivityClock(private val context: Context) : ActivityClock {
    private val identity: String by lazy { loadInstallationIdentity() }
    override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()
    override fun wallTimeMillis(): Long = System.currentTimeMillis()
    override fun bootCount(): Int? = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
    }.getOrNull()?.takeIf { it >= 0 }
    override fun clockIdentity(): String = identity

    private fun loadInstallationIdentity(): String = synchronized(identityLock) {
        val directory = context.noBackupFilesDir
        check(directory.isDirectory || directory.mkdirs()) { "Не удалось подготовить секундомер" }
        val file = AtomicFile(File(directory, "activity-clock-identity"))
        val stored = runCatching { file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText().trim() } }.getOrNull()
        if (stored != null && runCatching { UUID.fromString(stored).toString() == stored }.getOrDefault(false)) {
            return@synchronized stored
        }
        val created = UUID.randomUUID().toString()
        val output = file.startWrite()
        try {
            output.write(created.toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (e: Throwable) {
            file.failWrite(output)
            throw e
        }
        created
    }

    private companion object {
        val identityLock = Any()
    }
}

/** Local activity is independent of workouts, and every asynchronous action carries its original owner. */
class ActivityRepository(
    private val database: AppDatabase,
    private val activeAthleteId: StateFlow<Long> = MutableStateFlow(1L),
    private val clock: ActivityClock,
) {
    private val dao = database.activityDao()
    val currentAthleteId: Long get() = activeAthleteId.value

    fun observeDefinitions(athleteId: Long = currentAthleteId, includeArchived: Boolean = false): Flow<List<ActivityDefinition>> =
        dao.observeDefinitions(athleteId, includeArchived)
    fun observeEntries(athleteId: Long = currentAthleteId): Flow<List<ActivityEntry>> = dao.observeEntries(athleteId)
    fun observeEntriesForDay(epochDay: Long, athleteId: Long = currentAthleteId): Flow<List<ActivityEntry>> =
        dao.observeEntriesForDay(epochDay, athleteId)
    fun observeHistory(activityId: Long, athleteId: Long = currentAthleteId): Flow<List<ActivityEntry>> =
        dao.observeHistory(activityId, athleteId)
    fun observeTimer(activityId: Long, athleteId: Long = currentAthleteId): Flow<ActivityTimer?> =
        dao.observeTimer(activityId, athleteId)
    fun observeTimers(athleteId: Long = currentAthleteId): Flow<List<ActivityTimer>> = dao.observeTimers(athleteId)
    suspend fun getDefinition(id: Long, athleteId: Long = currentAthleteId): ActivityDefinition? = dao.getDefinition(id, athleteId)
    suspend fun getAllDefinitions(athleteId: Long = currentAthleteId): List<ActivityDefinition> = dao.getAllDefinitions(athleteId)
    suspend fun getAllEntries(athleteId: Long = currentAthleteId): List<ActivityEntry> = dao.getAllEntries(athleteId)

    /** Idempotent bundle import: never REPLACE the row, as that would break its history and timers. */
    suspend fun upsertDefinition(input: ActivityDefinitionInput, athleteId: Long = currentAthleteId): Long {
        validateDefinition(input)
        return database.withTransaction {
            requireAvailableAthlete(athleteId)
            val old = dao.getDefinitionByKey(input.key, athleteId)
            if (old != null && (old.kind != input.kind || old.goalMetric != input.goalMetric)) {
                require(dao.entryCount(old.id) == 0 && dao.getTimer(old.id, athleteId) == null) {
                    "Нельзя менять тип или единицу активности с записями. Создайте отдельную активность."
                }
            }
            val definition = ActivityDefinition(old?.id ?: 0, athleteId, input.key, input.name.trim(), input.kind,
                input.goalMetric, ActivityProgression.encodeGoals(input.goals), input.instructions.trim(),
                input.weeklyScheduleNote?.trim()?.takeIf { it.isNotEmpty() }, input.sortOrder, false)
            if (old == null) dao.insertDefinition(definition) else {
                dao.updateDefinition(definition)
                old.id
            }
        }
    }

    suspend fun updateGoals(id: Long, goals: List<Double>, athleteId: Long = currentAthleteId) = database.withTransaction {
        val definition = requireDefinition(id, athleteId)
        ActivityProgression.validateGoals(definition.kind, definition.goalMetric, goals)
        dao.updateDefinition(definition.copy(goalsJson = ActivityProgression.encodeGoals(goals)))
    }

    suspend fun archiveDefinition(id: Long, athleteId: Long = currentAthleteId) = database.withTransaction {
        val definition = requireDefinition(id, athleteId)
        require(dao.getTimer(id, athleteId) == null) { "Сначала сохраните или удалите таймер этой активности" }
        dao.updateDefinition(definition.copy(isArchived = true))
    }

    suspend fun saveEntry(activityId: Long, input: ActivityEntryInput, athleteId: Long = currentAthleteId): Long =
        database.withTransaction {
            val definition = requireDefinition(activityId, athleteId)
            validateEntry(definition.kind, input)
            val existing = dao.getEntryByToken(input.clientToken)
            if (existing != null) {
                require(existing.athleteId == athleteId && existing.activityId == activityId) {
                    "Эта запись уже принадлежит другой активности или профилю"
                }
                require(existing == input.toEntry(activityId, athleteId, existing.timestamp, existing.id)) {
                    "Запись уже сохранена с другими значениями. Откройте её для редактирования."
                }
                return@withTransaction existing.id
            }
            require(!definition.isArchived) { "Активность находится в архиве" }
            requireAvailableAthlete(athleteId)
            val timer = dao.getTimerByToken(input.clientToken)
            if (timer != null) {
                require(timer.athleteId == athleteId && timer.activityId == activityId) { "Таймер принадлежит другой активности или профилю" }
                // Explicit manual correction is also allowed after reboot; the repository never invents elapsed time.
                require(input.durationSeconds != null && input.durationSeconds > 0) { "Укажите фактическое время" }
            }
            val id = dao.insertEntry(input.toEntry(activityId, athleteId, clock.wallTimeMillis()))
            if (timer != null) dao.deleteTimer(activityId, athleteId)
            id
        }

    suspend fun updateEntry(id: Long, input: ActivityEntryInput, athleteId: Long = currentAthleteId) = database.withTransaction {
        val entry = requireNotNull(dao.getEntry(id, athleteId)) { "Запись не найдена в этом профиле" }
        val definition = requireDefinition(entry.activityId, athleteId)
        val captured = input.copy(clientToken = entry.clientToken)
        validateEntry(definition.kind, captured)
        dao.updateEntry(captured.toEntry(entry.activityId, athleteId, entry.timestamp, entry.id))
    }

    suspend fun deleteEntry(id: Long, athleteId: Long = currentAthleteId) = database.withTransaction {
        requireNotNull(dao.getEntry(id, athleteId)) { "Запись не найдена в этом профиле" }
        dao.deleteEntry(id, athleteId)
    }

    suspend fun startTimer(activityId: Long, athleteId: Long = currentAthleteId): ActivityTimer = database.withTransaction {
        requireAvailableAthlete(athleteId)
        val definition = requireDefinition(activityId, athleteId)
        require(!definition.isArchived && definition.kind != ActivityKind.REPS) { "Таймер доступен для времени и ходьбы" }
        dao.getTimer(activityId, athleteId)?.let {
            require(it.durationSeconds == null) { "Сначала сохраните или удалите остановленный таймер" }
            return@withTransaction it
        }
        val timer = ActivityTimer(activityId, athleteId, clock.elapsedRealtime(), clock.wallTimeMillis(),
            clock.bootCount(), clientToken = UUID.randomUUID().toString(), clockIdentity = clock.clockIdentity())
        dao.insertTimer(timer)
        timer
    }

    suspend fun stopTimer(activityId: Long, athleteId: Long = currentAthleteId): ActivityTimer = database.withTransaction {
        requireDefinition(activityId, athleteId)
        val timer = requireNotNull(dao.getTimer(activityId, athleteId)) { "Таймер не найден" }
        if (timer.durationSeconds != null) return@withTransaction timer
        val elapsed = requireNotNull(elapsedSeconds(timer)) {
            "После перезагрузки или переноса приложения время неизвестно. Введите фактическое время вручную или удалите таймер."
        }
        timer.copy(durationSeconds = elapsed).also { dao.updateTimer(it) }
    }

    suspend fun discardTimer(activityId: Long, athleteId: Long = currentAthleteId) = database.withTransaction {
        requireDefinition(activityId, athleteId)
        dao.deleteTimer(activityId, athleteId)
    }

    /** Monotonic elapsed time survives process death; a changed boot must never become fictional activity. */
    fun elapsedSeconds(timer: ActivityTimer): Long? {
        timer.durationSeconds?.let { return it.takeIf { duration -> duration >= 0 } }
        if (timer.clockIdentity != clock.clockIdentity()) return null
        val elapsed = runCatching { Math.subtractExact(clock.elapsedRealtime(), timer.startElapsedRealtime) }.getOrNull() ?: return null
        if (elapsed < 0 || timer.startElapsedRealtime < 0) return null
        val boot = clock.bootCount()
        if (timer.bootCount != null && boot != null) {
            if (timer.bootCount != boot) return null
        } else {
            // Some devices do not expose BOOT_COUNT. Reject a changed clock origin instead of guessing.
            val wall = runCatching { Math.subtractExact(clock.wallTimeMillis(), timer.startWallTime) }.getOrNull() ?: return null
            val drift = runCatching { Math.subtractExact(wall, elapsed) }.getOrNull() ?: return null
            if (drift == Long.MIN_VALUE || abs(drift) > 10_000L) return null
        }
        return elapsed / 1_000
    }

    /** Neutral editable examples. Never overwrite a person's goals or resurrect an archived definition. */
    suspend fun ensureDefaults(athleteId: Long = currentAthleteId) = database.withTransaction {
        requireAvailableAthlete(athleteId)
        defaults.forEach { input ->
            if (dao.getDefinitionByKey(input.key, athleteId) == null) upsertDefinition(input, athleteId)
        }
    }

    private suspend fun requireDefinition(id: Long, athleteId: Long): ActivityDefinition =
        requireNotNull(dao.getDefinition(id, athleteId)) { "Активность не найдена в этом профиле" }

    private suspend fun requireAvailableAthlete(athleteId: Long) {
        database.workoutDao().ensureDefaultAthlete()
        require(database.workoutDao().availableAthlete(athleteId) == 1) { "Профиль не найден или скрыт" }
    }

    private fun ActivityEntryInput.toEntry(activityId: Long, athleteId: Long, timestamp: Long, id: Long = 0) =
        ActivityEntry(id, athleteId, activityId, epochDay, timestamp, reps, durationSeconds, distanceKm, steps,
            speedKmh, inclinePercent, note?.trim()?.takeIf { it.isNotEmpty() }, isClean, clientToken)

    companion object {
        private val KEY_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,79}")

        fun validateDefinition(input: ActivityDefinitionInput) {
            require(KEY_PATTERN.matches(input.key)) { "Некорректный ключ активности" }
            require(input.name.trim().length in 1..120) { "Название активности: от 1 до 120 символов" }
            require(input.instructions.length <= 2000) { "Инструкция: не больше 2000 символов" }
            require(input.weeklyScheduleNote == null || input.weeklyScheduleNote.length <= 2000) { "Расписание: не больше 2000 символов" }
            require(input.sortOrder >= 0) { "Некорректный порядок активности" }
            ActivityProgression.validateGoals(input.kind, input.goalMetric, input.goals)
        }

        fun validateEntry(kind: ActivityKind, input: ActivityEntryInput) {
            require(runCatching { LocalDate.ofEpochDay(input.epochDay) }.isSuccess) { "Некорректная дата" }
            require(input.clientToken.length in 1..128 && input.clientToken.isNotBlank()) { "Некорректный идентификатор записи" }
            require(input.note == null || input.note.length <= 2000) { "Заметка: не больше 2000 символов" }
            require(input.reps == null || input.reps in 1..100_000) { "Укажите положительное количество повторов" }
            require(input.durationSeconds == null || input.durationSeconds in 1L..86_400L) { "Время должно быть от 1 секунды до 24 часов" }
            require(input.distanceKm == null || (input.distanceKm.isFinite() && input.distanceKm > 0 && input.distanceKm <= 1000)) { "Проверьте расстояние" }
            require(input.steps == null || input.steps in 1L..1_000_000L) { "Проверьте количество шагов" }
            require(input.speedKmh == null || (input.speedKmh.isFinite() && input.speedKmh > 0 && input.speedKmh <= 100)) { "Проверьте скорость" }
            require(input.inclinePercent == null || (input.inclinePercent.isFinite() && input.inclinePercent in -100.0..100.0)) { "Проверьте наклон" }
            when (kind) {
                ActivityKind.REPS -> require(input.reps != null && input.durationSeconds == null && input.distanceKm == null &&
                    input.steps == null && input.speedKmh == null && input.inclinePercent == null) { "Для этой активности запишите один подход в повторах" }
                ActivityKind.DURATION -> require(input.durationSeconds != null && input.reps == null && input.distanceKm == null &&
                    input.steps == null && input.speedKmh == null && input.inclinePercent == null) { "Для этой активности укажите фактическое время" }
                ActivityKind.WALK -> require(input.reps == null && (input.durationSeconds != null || input.distanceKm != null || input.steps != null)) {
                    "Для ходьбы укажите хотя бы время, расстояние или шаги"
                }
            }
        }

        private val defaults = listOf(
            ActivityDefinitionInput("walking", "Ходьба", ActivityKind.WALK, goals = listOf(1200.0),
                instructions = "Запишите фактические время, расстояние или шаги. Поля, которые вы не измеряли, оставьте пустыми.", sortOrder = 0),
            ActivityDefinitionInput("plank", "Планка", ActivityKind.DURATION, goals = listOf(15.0),
                instructions = "Одна запись — одно фактическое удержание. Таймер сохраняет результат только после подтверждения.", sortOrder = 1),
            ActivityDefinitionInput("pushup_standard", "Отжимания — обычная постановка", ActivityKind.REPS, goals = listOf(10.0),
                instructions = "Одна запись — один подход. Разные варианты записывайте отдельно.", sortOrder = 2),
            ActivityDefinitionInput("pushup_wide", "Отжимания — широкая постановка", ActivityKind.REPS,
                instructions = "Отдельный вариант постановки рук. Записывайте только фактически выполненный подход.", sortOrder = 3),
            ActivityDefinitionInput("pushup_narrow", "Отжимания — узкая постановка", ActivityKind.REPS,
                instructions = "Отдельный вариант постановки рук. Записывайте только фактически выполненный подход.", sortOrder = 4),
            ActivityDefinitionInput("pushup_knees", "Отжимания — с колен", ActivityKind.REPS,
                instructions = "Отдельный вариант опоры. Его результат не смешивается с другими отжиманиями.", sortOrder = 5),
            ActivityDefinitionInput("pushup_incline", "Отжимания — руки на возвышении", ActivityKind.REPS,
                instructions = "Укажите высоту или вид опоры в заметке, если это нужно для сравнения.", sortOrder = 6),
        )
    }
}
