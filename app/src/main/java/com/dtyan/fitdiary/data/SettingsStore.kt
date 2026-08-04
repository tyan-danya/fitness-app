package com.dtyan.fitdiary.data

import android.content.Context
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
    )

    private val prefs = context.getSharedPreferences("fitdiary_settings", Context.MODE_PRIVATE)

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
            .apply()
        _settings.value = next
    }
}
