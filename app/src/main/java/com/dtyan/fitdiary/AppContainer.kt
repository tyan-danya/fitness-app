package com.dtyan.fitdiary

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.dtyan.fitdiary.data.PhotoStore
import com.dtyan.fitdiary.data.RestTimerController
import com.dtyan.fitdiary.data.SettingsStore
import com.dtyan.fitdiary.data.db.AppDatabase
import com.dtyan.fitdiary.data.repo.ExerciseRepository
import com.dtyan.fitdiary.data.repo.NutritionRepository
import com.dtyan.fitdiary.data.repo.StatsRepository
import com.dtyan.fitdiary.data.repo.WorkoutRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Ручной DI-контейнер приложения. */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val database: AppDatabase = AppDatabase.build(appContext)
    val settings: SettingsStore = SettingsStore(appContext)
    val photoStore: PhotoStore = PhotoStore(appContext)

    val workoutRepository = WorkoutRepository(
        workoutDao = database.workoutDao(),
        setDao = database.workoutSetDao(),
        weightDao = database.weightDao(),
    )
    val exerciseRepository = ExerciseRepository(
        exerciseDao = database.exerciseDao(),
        setDao = database.workoutSetDao(),
    )
    val nutritionRepository = NutritionRepository(mealDao = database.mealDao())
    val statsRepository = StatsRepository(
        workoutDao = database.workoutDao(),
        setDao = database.workoutSetDao(),
        weightDao = database.weightDao(),
    )

    val restTimer = RestTimerController(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
        onFinished = { vibrate() },
    )

    private fun vibrate() {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        vibrator?.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1)
        )
    }
}

/** Доступ к контейнеру из Compose/классов по Context. */
val Context.appContainer: AppContainer
    get() = (applicationContext as FitDiaryApp).container
