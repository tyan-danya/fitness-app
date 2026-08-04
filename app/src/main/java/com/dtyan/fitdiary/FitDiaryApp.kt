package com.dtyan.fitdiary

import android.app.Application

class FitDiaryApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
