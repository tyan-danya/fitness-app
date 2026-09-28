package com.dtyan.fitdiary.data

import android.content.Context
import com.dtyan.fitdiary.domain.MeasurementType
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Настройки приложения поверх SharedPreferences, наружу — StateFlow. */
class SettingsStore(context: Context) {

    data class Settings(
        val calorieGoal: Int = 2200,
        val proteinGoalG: Int = 150,
        val fatGoalG: Int = 70,
        val carbGoalG: Int = 250,
        val restTimerSeconds: Int = 90,
        val restTimerEnabled: Boolean = true,
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

    init {
        // Точка отсчёта тишины для напоминания фиксируется при первом запуске, иначе
        // «сегодня» сдвигалось бы при каждом старте и напоминание не сработало бы никогда.
        if (!prefs.contains("measurementReminderSinceDay")) {
            prefs.edit().putLong("measurementReminderSinceDay", LocalDate.now().toEpochDay()).apply()
        }
    }

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private fun load(): Settings {
        val d = Settings()
        return Settings(
            calorieGoal = prefs.getInt("calorieGoal", d.calorieGoal),
            proteinGoalG = prefs.getInt("proteinGoalG", d.proteinGoalG),
            fatGoalG = prefs.getInt("fatGoalG", d.fatGoalG),
            carbGoalG = prefs.getInt("carbGoalG", d.carbGoalG),
            restTimerSeconds = prefs.getInt("restTimerSeconds", d.restTimerSeconds),
            restTimerEnabled = prefs.getBoolean("restTimerEnabled", d.restTimerEnabled),
            aiBaseUrl = prefs.getString("aiBaseUrl", d.aiBaseUrl) ?: d.aiBaseUrl,
            aiModel = prefs.getString("aiModel", d.aiModel) ?: d.aiModel,
            aiApiKey = prefs.getString("aiApiKey", d.aiApiKey) ?: d.aiApiKey,
            trackedMeasurements = prefs.getString("trackedMeasurements", null)
                ?.let { MeasurementType.parseSet(it) } ?: d.trackedMeasurements,
            measurementReminderEnabled = prefs.getBoolean("measurementReminderEnabled", d.measurementReminderEnabled),
            measurementReminderDays = prefs.getInt("measurementReminderDays", d.measurementReminderDays),
            measurementReminderHour = prefs.getInt("measurementReminderHour", d.measurementReminderHour),
            measurementReminderSinceDay = prefs.getLong("measurementReminderSinceDay", d.measurementReminderSinceDay),
        )
    }

    fun update(transform: (Settings) -> Settings) {
        val next = transform(_settings.value)
        prefs.edit()
            .putInt("calorieGoal", next.calorieGoal)
            .putInt("proteinGoalG", next.proteinGoalG)
            .putInt("fatGoalG", next.fatGoalG)
            .putInt("carbGoalG", next.carbGoalG)
            .putInt("restTimerSeconds", next.restTimerSeconds)
            .putBoolean("restTimerEnabled", next.restTimerEnabled)
            .putString("aiBaseUrl", next.aiBaseUrl)
            .putString("aiModel", next.aiModel)
            .putString("aiApiKey", next.aiApiKey)
            .putString("trackedMeasurements", MeasurementType.encodeSet(next.trackedMeasurements))
            .putBoolean("measurementReminderEnabled", next.measurementReminderEnabled)
            .putInt("measurementReminderDays", next.measurementReminderDays)
            .putInt("measurementReminderHour", next.measurementReminderHour)
            .putLong("measurementReminderSinceDay", next.measurementReminderSinceDay)
            .apply()
        _settings.value = next
    }
}
