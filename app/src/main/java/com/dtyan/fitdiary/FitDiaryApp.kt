package com.dtyan.fitdiary

import android.app.Application
import com.dtyan.fitdiary.reminder.MeasurementReminder

class FitDiaryApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Будильник напоминания о замерах переставляем при каждом старте процесса — дёшево и надёжно
        val s = container.settings.settings.value
        MeasurementReminder.ensureChannel(this)
        MeasurementReminder.schedule(this, s.measurementReminderEnabled, s.measurementReminderHour)
    }
}
