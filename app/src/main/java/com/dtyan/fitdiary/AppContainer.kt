package com.dtyan.fitdiary

import android.content.Context
import com.dtyan.fitdiary.data.PhotoStore
import com.dtyan.fitdiary.data.SettingsStore
import com.dtyan.fitdiary.data.ai.NutritionEstimator
import com.dtyan.fitdiary.data.db.AppDatabase
import com.dtyan.fitdiary.data.repo.ExerciseRepository
import com.dtyan.fitdiary.data.repo.ProfileRepository
import com.dtyan.fitdiary.data.repo.MeasurementRepository
import com.dtyan.fitdiary.data.repo.NutritionRepository
import com.dtyan.fitdiary.data.repo.StatsRepository
import com.dtyan.fitdiary.data.repo.WorkoutRepository
import com.dtyan.fitdiary.export.EstimateExchange
import com.dtyan.fitdiary.export.BackupManager

/** Ручной DI-контейнер приложения. */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val database: AppDatabase = AppDatabase.build(appContext)
    val profiles = ProfileRepository(appContext, database.athleteDao())
    val settings: SettingsStore = SettingsStore(appContext, profiles.activeId)
    val photoStore: PhotoStore = PhotoStore(appContext)

    val workoutRepository = WorkoutRepository(
        workoutDao = database.workoutDao(),
        setDao = database.workoutSetDao(),
        weightDao = database.weightDao(),
        activeAthleteId = profiles.activeId,
    )
    val exerciseRepository = ExerciseRepository(
        exerciseDao = database.exerciseDao(),
        setDao = database.workoutSetDao(),
        activeAthleteId = profiles.activeId,
    )
    val nutritionRepository = NutritionRepository(mealDao = database.mealDao(), activeAthleteId = profiles.activeId, database = database)
    val measurementRepository = MeasurementRepository(dao = database.measurementDao(), activeAthleteId = profiles.activeId)
    val statsRepository = StatsRepository(
        workoutDao = database.workoutDao(),
        setDao = database.workoutSetDao(),
        weightDao = database.weightDao(),
        activeAthleteId = profiles.activeId,
    )

    /** Расчёт КБЖУ через ИИ (настройки читаются при каждом запросе) и файл-обмен для ручного расчёта. */
    val nutritionEstimator = NutritionEstimator(settings = { settings.settings.value })
    val estimateExchange = EstimateExchange(appContext, { settings.diaryId }, { profiles.activeId.value })
    val backupManager = BackupManager(appContext, database, settings)
}

/** Доступ к контейнеру из Compose/классов по Context. */
val Context.appContainer: AppContainer
    get() = (applicationContext as FitDiaryApp).container
