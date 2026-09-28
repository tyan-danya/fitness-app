package com.dtyan.fitdiary

import android.app.Application
import com.dtyan.fitdiary.reminder.MeasurementReminder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FitDiaryApp : Application() {

    lateinit var container: AppContainer
        private set
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Будильник напоминания о замерах переставляем при каждом старте процесса — дёшево и надёжно
        MeasurementReminder.ensureChannel(this)
        applicationScope.launch {
            container.settings.settings.collect { s ->
                MeasurementReminder.schedule(this@FitDiaryApp, s.measurementReminderEnabled, s.measurementReminderHour)
            }
        }
    }
}
