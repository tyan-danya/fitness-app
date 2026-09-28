package com.dtyan.fitdiary.data

import android.content.Context
import com.dtyan.fitdiary.domain.MeasurementType
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Настройки приложения поверх SharedPreferences, наружу — StateFlow. */
class SettingsStore(context: Context, private val activeAthleteId: StateFlow<Long> = MutableStateFlow(1L)) {

    data class Settings(
        val calorieGoal: Int = 2200,
        val proteinGoalG: Int = 150,
        val fatGoalG: Int = 70,
        val carbGoalG: Int = 250,
        /** Расчёт КБЖУ через ИИ: OpenAI-совместимый эндпоинт (chat/completions). */
        val aiBaseUrl: String = DEFAULT_AI_BASE_URL,
        val aiModel: String = DEFAULT_AI_MODEL,
        val aiApiKey: String = "",
        /** Какие зоны замеряем. */
        val trackedMeasurements: Set<MeasurementType> = MeasurementType.DEFAULT_TRACKED,
        /** Напоминание «давно не было замеров»: включено, через сколько дней тишины, в какой час. */
        val measurementReminderEnabled: Boolean = true,
        val measurementReminderDays: Int = 3,
        val measurementReminderHour: Int = 20,
        /** День (epochDay), с которого считаем тишину, если замеров ещё не было. */
        val measurementReminderSinceDay: Long = LocalDate.now().toEpochDay(),
    ) {
        /** Ключ задан — кнопка «Рассчитать» в форме приёма активна. */
        val aiConfigured: Boolean get() = aiApiKey.isNotBlank() && aiBaseUrl.isNotBlank() && aiModel.isNotBlank()
    }

    companion object {
        const val DEFAULT_AI_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_AI_MODEL = "gpt-4o-mini"
    }

    private val prefs = context.getSharedPreferences("fitdiary_settings", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val diaryId: String get() = checkNotNull(prefs.getString("diaryId", null))

    init {
        if (!prefs.contains("diaryId")) {
            check(prefs.edit().putString("diaryId", UUID.randomUUID().toString()).commit())
        }
        // Точка отсчёта тишины для напоминания фиксируется при первом запуске, иначе
        // «сегодня» сдвигалось бы при каждом старте и напоминание не сработало бы никогда.
        if (!prefs.contains("measurementReminderSinceDay")) {
            prefs.edit().putLong("measurementReminderSinceDay", LocalDate.now().toEpochDay()).apply()
        }
    }

    private val _settings = MutableStateFlow(settingsFor(activeAthleteId.value))
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    init { scope.launch { activeAthleteId.collect { _settings.value = settingsFor(it) } } }

    private fun key(id: Long, name: String): String = "athlete.$id.$name"
    private fun readKey(id: Long, name: String): String =
        if (id == 1L && !prefs.contains(key(id, name))) name else key(id, name)

    fun settingsFor(athleteId: Long): Settings {
        require(athleteId > 0)
        val sinceKey = readKey(athleteId, "measurementReminderSinceDay")
        if (!prefs.contains(sinceKey)) {
            check(prefs.edit().putLong(sinceKey, LocalDate.now().toEpochDay()).commit())
        }
        fun k(name: String) = readKey(athleteId, name)
        val d = Settings()
        return Settings(
            calorieGoal = prefs.getInt(k("calorieGoal"), d.calorieGoal),
            proteinGoalG = prefs.getInt(k("proteinGoalG"), d.proteinGoalG),
            fatGoalG = prefs.getInt(k("fatGoalG"), d.fatGoalG),
            carbGoalG = prefs.getInt(k("carbGoalG"), d.carbGoalG),
            aiBaseUrl = prefs.getString("aiBaseUrl", d.aiBaseUrl) ?: d.aiBaseUrl,
            aiModel = prefs.getString("aiModel", d.aiModel) ?: d.aiModel,
            aiApiKey = prefs.getString("aiApiKey", d.aiApiKey) ?: d.aiApiKey,
            trackedMeasurements = prefs.getString(k("trackedMeasurements"), null)
                ?.let { MeasurementType.parseSet(it) } ?: d.trackedMeasurements,
            measurementReminderEnabled = prefs.getBoolean(k("measurementReminderEnabled"), d.measurementReminderEnabled),
            measurementReminderDays = prefs.getInt(k("measurementReminderDays"), d.measurementReminderDays),
            measurementReminderHour = prefs.getInt(k("measurementReminderHour"), d.measurementReminderHour),
            measurementReminderSinceDay = prefs.getLong(k("measurementReminderSinceDay"), d.measurementReminderSinceDay),
        )
    }

    fun update(transform: (Settings) -> Settings) {
        val owner = activeAthleteId.value
        val next = transform(settingsFor(owner))
        require(next.calorieGoal in 0..100_000 && next.proteinGoalG in 0..10_000 &&
            next.fatGoalG in 0..10_000 && next.carbGoalG in 0..10_000) { "Цели питания вне допустимого диапазона" }
        require(next.measurementReminderDays in 1..365 && next.measurementReminderHour in 0..23 &&
            next.measurementReminderSinceDay in -365_000L..365_000L && next.trackedMeasurements.isNotEmpty()) { "Проверьте настройки замеров" }
        fun k(name: String) = key(owner, name)
        check(prefs.edit()
            .putInt(k("calorieGoal"), next.calorieGoal)
            .putInt(k("proteinGoalG"), next.proteinGoalG)
            .putInt(k("fatGoalG"), next.fatGoalG)
            .putInt(k("carbGoalG"), next.carbGoalG)
            .putString("aiBaseUrl", next.aiBaseUrl)
            .putString("aiModel", next.aiModel)
            .putString("aiApiKey", next.aiApiKey)
            .putString(k("trackedMeasurements"), MeasurementType.encodeSet(next.trackedMeasurements))
            .putBoolean(k("measurementReminderEnabled"), next.measurementReminderEnabled)
            .putInt(k("measurementReminderDays"), next.measurementReminderDays)
            .putInt(k("measurementReminderHour"), next.measurementReminderHour)
            .putLong(k("measurementReminderSinceDay"), next.measurementReminderSinceDay)
            .commit()) { "Не удалось сохранить настройки" }
        _settings.value = settingsFor(activeAthleteId.value)
    }

    private val personalKeys = setOf("calorieGoal", "proteinGoalG", "fatGoalG", "carbGoalG",
        "trackedMeasurements", "measurementReminderEnabled", "measurementReminderDays",
        "measurementReminderHour", "measurementReminderSinceDay")

    private fun safeKey(name: String): Boolean = name == "diaryId" || name in personalKeys ||
        (Regex("athlete\\.[1-9][0-9]*\\.[A-Za-z]+").matches(name) && name.substringAfterLast('.') in personalKeys)

    /** Neither the AI key nor its endpoint travels to another device in a backup. */
    fun exportSafeSettings(): String = buildJsonObject {
        prefs.all.filterKeys(::safeKey).toSortedMap().forEach { (name, value) ->
            when (value) {
                is String -> put(name, value)
                is Boolean -> put(name, value)
                is Number -> put(name, value)
            }
        }
    }.toString()

    fun validateSafeSettings(json: String) { parseSafeSettings(json) }

    private fun parseSafeSettings(json: String): JsonObject {
        require(json.length <= 1_000_000) { "Слишком большой файл настроек" }
        val data = Json.parseToJsonElement(json) as? JsonObject ?: error("Неверный формат настроек")
        require(data.size <= 10_000) { "Слишком много настроек" }
        require(data["diaryId"]?.jsonPrimitive?.contentOrNull?.let {
            runCatching { UUID.fromString(it) }.isSuccess
        } == true) { "Нет идентификатора дневника" }
        data.forEach { (name, element) ->
            require(safeKey(name)) { "Неизвестная настройка: $name" }
            val value = element as? JsonPrimitive ?: error("Неверная настройка: $name")
            val field = name.substringAfterLast('.')
            when (field) {
                "diaryId" -> Unit
                "trackedMeasurements" -> require(value.isString && value.content.length <= 1000 && MeasurementType.parseSet(value.content).isNotEmpty())
                "measurementReminderEnabled" -> require(!value.isString && value.booleanOrNull != null)
                "measurementReminderSinceDay" -> require(!value.isString && value.longOrNull?.let { it in -365_000L..365_000L } == true)
                else -> {
                    val number = value.intOrNull
                    val allowed = when(field) {
                        "measurementReminderDays" -> 1..365
                        "measurementReminderHour" -> 0..23
                        "calorieGoal" -> 0..100_000
                        else -> 0..10_000
                    }
                    require(!value.isString && number != null && number in allowed) { "Неверное значение: $name" }
                }
            }
        }
        return data
    }

    /** Validate before writing; synchronous commit allows the restore coordinator to roll back. */
    fun restoreSafeSettings(json: String) {
        val data = parseSafeSettings(json)
        val edit = prefs.edit()
        prefs.all.keys.filter(::safeKey).forEach { edit.remove(it) }
        data.forEach { (name, element) ->
            val value = element.jsonPrimitive
            when {
                value.isString -> edit.putString(name, value.content)
                value.booleanOrNull != null -> edit.putBoolean(name, value.boolean)
                name.endsWith("measurementReminderSinceDay") -> edit.putLong(name, value.long)
                else -> edit.putInt(name, value.int)
            }
        }
        check(edit.commit()) { "Не удалось восстановить настройки" }
        _settings.value = settingsFor(activeAthleteId.value)
    }
}
