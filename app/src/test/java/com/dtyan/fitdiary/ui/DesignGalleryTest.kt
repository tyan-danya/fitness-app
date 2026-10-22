package com.dtyan.fitdiary.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inspector.WindowInspector
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.dtyan.fitdiary.AppContainer
import com.dtyan.fitdiary.FitDiaryApp
import com.dtyan.fitdiary.data.db.BodyMeasurement
import com.dtyan.fitdiary.data.db.ExerciseSeed
import com.dtyan.fitdiary.data.db.Meal
import com.dtyan.fitdiary.data.db.WeightEntry
import com.dtyan.fitdiary.data.db.Workout
import com.dtyan.fitdiary.data.db.WorkoutExercise
import com.dtyan.fitdiary.data.db.WorkoutSet
import com.dtyan.fitdiary.data.repo.ActivityEntryInput
import com.dtyan.fitdiary.data.repo.TemplatePlanItem
import com.dtyan.fitdiary.domain.MealType
import com.dtyan.fitdiary.domain.MeasurementType
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dtyan.fitdiary.data.db.ActivityTimer
import com.dtyan.fitdiary.data.repo.PlanBundleRepository
import com.dtyan.fitdiary.domain.ActivityProgression
import com.dtyan.fitdiary.export.PlanBundleExchange
import com.dtyan.fitdiary.export.examplePlan
import com.dtyan.fitdiary.ui.activity.ActivityScreen
import com.dtyan.fitdiary.ui.activity.ActivityStopwatchPanel
import com.dtyan.fitdiary.ui.activity.ActivityViewModel
import com.dtyan.fitdiary.ui.common.FitSection
import com.dtyan.fitdiary.ui.common.FitSectionScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.dtyan.fitdiary.ui.navigation.AppRoot
import com.dtyan.fitdiary.ui.stats.WorkoutDetailsScreen
import com.dtyan.fitdiary.ui.templates.TemplatesScreen
import com.dtyan.fitdiary.ui.theme.FitDiaryTheme
import com.dtyan.fitdiary.ui.workout.ActiveWorkoutScreen
import com.dtyan.fitdiary.ui.workout.ExerciseLogScreen
import com.dtyan.fitdiary.ui.workout.ExercisePickerScreen
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Галерея экранов на типичном телефоне (360 dp, обычный шрифт) с правдоподобным синтетическим дневником.
 * Ничего не проверяет, кроме того, что экраны собираются; картинки — в app/build/design-renders.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FitDiaryApp::class, qualifiers = "w360dp-h1100dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DesignGalleryTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var container: AppContainer
    private var activeWorkoutId = 0L
    private var finishedWorkoutId = 0L
    private var benchId = 0L
    private var screen by mutableStateOf("home")
    private var plankId = 0L

    @Before fun seedDiary() {
        container = ApplicationProvider.getApplicationContext<FitDiaryApp>().container
        runBlocking(Dispatchers.IO) {
            val db = container.database
            db.clearAllTables()
            container.profiles.refreshSelection()
            container.profiles.rename(1, "Даня")
            container.profiles.select(1)
            container.profiles.create("Миша")
            ExerciseSeed.seed(db.openHelper.writableDatabase)
            val catalog = db.exerciseDao().getActiveOnce().associateBy { it.name }
            benchId = catalog.getValue("Жим штанги лёжа").id
            val rowId = catalog.getValue("Тяга верхнего блока").id
            val squatId = catalog.getValue("Приседания со штангой").id
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now()
            fun at(daysAgo: Long, hour: Int) = today.minusDays(daysAgo).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

            for ((index, daysAgo) in listOf(9L, 6L, 3L).withIndex()) {
                val start = at(daysAgo, 18)
                val id = db.workoutDao().insert(Workout(startedAt = start, endedAt = start + 62 * 60_000, athleteId = 1))
                finishedWorkoutId = id
                listOf(benchId, rowId, squatId).forEachIndexed { position, exerciseId ->
                    db.workoutDao().insertPlan(WorkoutExercise(id, exerciseId, position))
                    repeat(3) { set ->
                        db.workoutSetDao().insert(WorkoutSet(workoutId = id, exerciseId = exerciseId, setIndex = set + 1,
                            weightKg = 50.0 + index * 2.5 + position * 10, reps = 10 - set, completedAt = start + (position * 3 + set) * 240_000))
                    }
                }
                db.weightDao().insert(WeightEntry(timestamp = start + 63 * 60_000, weightKg = 82.4 - index * 0.4, athleteId = 1))
            }
            val activeStart = at(0, 9)
            activeWorkoutId = db.workoutDao().insert(Workout(startedAt = activeStart, athleteId = 1))
            listOf(benchId, rowId).forEachIndexed { position, exerciseId ->
                db.workoutDao().insertPlan(WorkoutExercise(activeWorkoutId, exerciseId, position, targetSets = 3, targetReps = 10))
            }
            db.workoutSetDao().insert(WorkoutSet(workoutId = activeWorkoutId, exerciseId = benchId, setIndex = 1,
                weightKg = 57.5, reps = 10, completedAt = activeStart + 600_000))

            container.templateRepository.create("Верх тела", listOf(TemplatePlanItem(benchId, 4, 8), TemplatePlanItem(rowId, 3, 12)), 1)

            val day = today.toEpochDay()
            db.mealDao().insert(Meal(epochDay = day, timestamp = at(0, 8), name = "Овсянка с бананом", calories = 420,
                proteinG = 14.0, fatG = 9.0, carbsG = 70.0, mealType = MealType.BREAKFAST, servingG = 300.0, athleteId = 1))
            db.mealDao().insert(Meal(epochDay = day, timestamp = at(0, 13), name = "Курица с рисом", calories = 610,
                proteinG = 48.0, fatG = 14.0, carbsG = 72.0, mealType = MealType.LUNCH, athleteId = 1))
            db.mealDao().insert(Meal(epochDay = day, timestamp = at(0, 16), name = "Протеиновый батончик", calories = 0,
                proteinG = 0.0, fatG = 0.0, carbsG = 0.0, mealType = MealType.SNACK, needsEstimate = true, athleteId = 1))

            for ((index, daysAgo) in listOf(28L, 14L, 0L).withIndex()) {
                db.measurementDao().insertAll(listOf(
                    BodyMeasurement(epochDay = today.minusDays(daysAgo).toEpochDay(), timestamp = at(daysAgo, 8),
                        type = MeasurementType.WAIST, valueCm = 88.0 - index * 0.8, athleteId = 1),
                    BodyMeasurement(epochDay = today.minusDays(daysAgo).toEpochDay(), timestamp = at(daysAgo, 8),
                        type = MeasurementType.CHEST, valueCm = 102.0 + index * 0.3, athleteId = 1),
                ))
            }

            container.activityRepository.ensureDefaults(1)
            val definitions = container.activityRepository.getAllDefinitions(1).associateBy { it.key }
            definitions["walking"]?.let {
                container.activityRepository.saveEntry(it.id, ActivityEntryInput(epochDay = day, distanceKm = 3.2, steps = 4300,
                    durationSeconds = 38 * 60, clientToken = UUID.randomUUID().toString()), 1)
            }
            suspend fun record(key: String, epochDay: Long = day, reps: Int? = null, seconds: Long? = null) {
                definitions[key]?.let {
                    container.activityRepository.saveEntry(it.id, ActivityEntryInput(epochDay = epochDay, reps = reps,
                        durationSeconds = seconds, clientToken = UUID.randomUUID().toString()), 1)
                }
            }
            listOf(20, 18, 15).forEach { record("pushup_standard", reps = it) }
            listOf(10, 10).forEach { record("pushup_narrow", reps = it) }
            listOf(60, 45).forEach { record("pushup_standard", day - 1, reps = it) }
            listOf(50, 50).forEach { record("pushup_wide", day - 2, reps = it) }
            record("pushup_standard", day - 3, reps = 40)
            record("plank", seconds = 35); record("plank", seconds = 34)
            record("dip_assisted", reps = 10); record("dip_assisted", reps = 8)
            container.settings.setPushUpDailyGoal(100, 1)

            // План недели как у владельца: сегодня силовая «C», программы A/B/C созданы импортом.
            val todayDow = today.dayOfWeek.value
            val program = { letter: String, title: String -> buildJsonObject {
                put("name", "$letter — $title")
                put("exercises", buildJsonArray {
                    add(buildJsonObject { put("name", "Жим штанги лёжа"); put("muscleGroup", "Грудь"); put("equipment", "Штанга")
                        put("targetSets", 3); put("targetReps", 8) })
                    add(buildJsonObject { put("name", "Тяга верхнего блока"); put("muscleGroup", "Спина"); put("equipment", "Блок")
                        put("targetSets", 3); put("targetReps", 10) })
                })
            } }
            val plan = JsonObject(examplePlan().toMutableMap().apply {
                put("key", JsonPrimitive("strength-abc")); put("name", JsonPrimitive("Сохранить силу: A/B/C, ходьба и лёгкая активность"))
                put("week", buildJsonArray { (1..7).forEach { dow -> add(buildJsonObject {
                    put("dayOfWeek", dow)
                    put("morning", "20–30 минут спокойной ходьбы, без утомления перед силовой.")
                    put("strength", if (dow == todayDow) "C: контролируемый жим, тяга с опорой, лёгкие ноги и трицепс." else "Отдых от силовой A/B/C.")
                    put("home", "По желанию планка 2×15–20 секунд.")
                }) } })
                put("templates", buildJsonArray { add(program("A", "жим и вертикальная тяга")); add(program("C", "контролируемый жим и тяга")) })
            })
            PlanBundleRepository(db, container.templateRepository, container.activityRepository)
                .importPlan(PlanBundleExchange.parse(plan.toString()), 1)
            plankId = definitions.getValue("plank").id
            db.activityDao().insertTimer(ActivityTimer(plankId, 1, 0, System.currentTimeMillis() - 28_000, null, 28,
                UUID.randomUUID().toString(), "test-clock"))
            container.exerciseRepository.setFavorite(benchId, true, 1)
            container.exerciseRepository.setFavorite(squatId, true, 1)
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val output = File("build/design-renders").apply { mkdirs() }
        compose.runOnIdle {
            val view = WindowInspector.getGlobalWindowViews().last { it.width > 0 && it.height > 0 && it.isShown }
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun waitForText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun show(name: String, anchor: String) {
        compose.runOnIdle { screen = name }
        try {
            waitForText(anchor)
        } catch (failure: Throwable) {
            File("build/design-renders/$name-semantics.txt").writeText(compose.onRoot().printToString())
            throw failure
        }
        compose.mainClock.advanceTimeBy(1_000)
    }

    private fun gallery(dark: Boolean) {
        val suffix = if (dark) "dark" else "light"
        compose.setContent {
            FitDiaryTheme(darkTheme = dark) {
                Box(Modifier.fillMaxSize()) {
                    when (screen) {
                        "home" -> AppRoot()
                        "workout" -> ActiveWorkoutScreen(activeWorkoutId, {}, { _, _ -> }, {})
                        "picker" -> ExercisePickerScreen(activeWorkoutId, {}, {})
                        "log" -> ExerciseLogScreen(activeWorkoutId, benchId, {})
                        "activity" -> ActivityScreen(onBack = {})
                        "templates" -> TemplatesScreen(onBack = {}, onOpenWorkout = {})
                        "details" -> WorkoutDetailsScreen(finishedWorkoutId, {})
                        "stopwatch" -> FitSectionScope(FitSection.WORKOUT) { Surface(color = MaterialTheme.colorScheme.background) {
                            val vm: ActivityViewModel = viewModel { ActivityViewModel(container.activityRepository, 1L, container.settings) }
                            val state by vm.state.collectAsState()
                            state.definitions.firstOrNull { it.id == plankId }?.let { plank ->
                                ActivityStopwatchPanel(plank, state.timers.firstOrNull { it.activityId == plankId },
                                    ActivityProgression.calculate(plank, state.allEntries), "Даня", false, null, vm)
                            }
                        } }
                    }
                }
            }
        }
        waitForText("Даня")
        capture("01-home-$suffix")
        for ((index, tab) in TABS.withIndex()) {
            compose.onNodeWithTag("tab-$tab").performClick()
            compose.mainClock.advanceTimeBy(1_000)
            Thread.sleep(500) // Room отдаёт данные с IO-потока; даём вкладке их получить.
            compose.mainClock.advanceTimeBy(1_000)
            capture("0${index + 2}-${TAB_FILES[index]}-$suffix")
        }
        show("workout", "Жим штанги лёжа"); capture("10-workout-$suffix")
        show("picker", "Тяга верхнего блока"); capture("11-picker-$suffix")
        show("log", "Жим штанги лёжа"); capture("12-exercise-log-$suffix")
        show("activity", "Отжимания"); capture("13-activity-$suffix")
        compose.onNodeWithTag("activity-day-list").performScrollToNode(hasTestTag("pushup-week"))
        capture("13b-activity-pushups-$suffix")
        compose.onNodeWithTag("activity-day-list").performScrollToNode(hasText("История отжиманий"))
        compose.onNodeWithText("История отжиманий").performClick()
        waitForText("Все варианты отжиманий вместе"); capture("13c-pushup-history-$suffix")
        show("stopwatch", "Результат для"); capture("16-stopwatch-$suffix")
        show("templates", "Сохранить силу"); capture("14-templates-$suffix")
        show("details", "Жим штанги лёжа"); capture("15-workout-details-$suffix")
    }

    @Test fun lightGallery() = gallery(dark = false)

    @Test fun darkGallery() = gallery(dark = true)

    private companion object {
        val TABS = listOf("nutrition", "measurements", "stats")
        val TAB_FILES = listOf("nutrition", "body", "stats")
    }
}
