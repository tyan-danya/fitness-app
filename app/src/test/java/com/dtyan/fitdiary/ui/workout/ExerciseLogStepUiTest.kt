package com.dtyan.fitdiary.ui.workout

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inspector.WindowInspector
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.dtyan.fitdiary.AppContainer
import com.dtyan.fitdiary.FitDiaryApp
import com.dtyan.fitdiary.data.db.Exercise
import com.dtyan.fitdiary.ui.theme.FitDiaryTheme
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Шаг по записям на экране подходов: чип, пояснение, меню и свой шаг (узкий экран, крупный шрифт). */
@RunWith(RobolectricTestRunner::class)
@Config(application = FitDiaryApp::class, qualifiers = "w320dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExerciseLogStepUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var container: AppContainer
    private var machineId = 0L
    private var workoutId = 0L

    private val butterfly = mapOf(29.0 to 2, 34.0 to 5, 39.0 to 10, 43.0 to 1, 44.0 to 6, 46.0 to 1,
        49.0 to 11, 54.0 to 9, 59.0 to 4, 64.0 to 2, 69.0 to 2, 79.0 to 1)

    @Before fun prepare() {
        container = ApplicationProvider.getApplicationContext<FitDiaryApp>().container
        runBlocking(Dispatchers.IO) {
            container.database.clearAllTables()
            container.profiles.refreshSelection()
            container.profiles.rename(1, "Даня")
            container.profiles.select(1)
            machineId = container.database.exerciseDao().insert(
                Exercise(name = "Сведение рук (бабочка)", muscleGroup = "Грудь", equipment = "Тренажёр"))
            val repo = container.workoutRepository
            val past = repo.startWorkout(now = 1_000_000L, athleteId = 1)
            var t = 1_000_000L
            butterfly.forEach { (weight, sets) -> repeat(sets) { repo.addSet(past, machineId, weight, 10, now = ++t) } }
            repo.finishWorkout(past, now = t + 1)
            workoutId = repo.startWorkout(athleteId = 1)
        }
    }

    private fun render(content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.3f)) {
                FitDiaryTheme(darkTheme = false) { Box(Modifier.fillMaxSize()) { content() } }
            }
        }
    }

    private fun waitForText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun capture(name: String) {
        val output = File("build/ui-renders").apply { mkdirs() }
        compose.runOnIdle {
            val view = WindowInspector.getGlobalWindowViews().last { it.width > 0 && it.height > 0 && it.isShown }
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun storedStep() = runBlocking(Dispatchers.IO) {
        container.database.exerciseDao().getById(machineId)!!.weightStepKg
    }

    @Test fun stepFromRecords_isExplained_andCanBeReplacedByCustomStep() {
        render { ExerciseLogScreen(workoutId, machineId, onBack = {}) }
        waitForText("Шаг: 5 кг · по записям")
        compose.onNodeWithText("Шаг 5 кг определён по вашим записям (шкала от 4 кг). Его можно сменить.")
            .performScrollTo().assertIsDisplayed()
        assertThat(storedStep()).isEqualTo(5.0)
        capture("exercise-step-from-records-320dp-font130")

        compose.onNodeWithText("Шаг: 5 кг · по записям").performScrollTo().performClick()
        waitForText("Свой шаг…")
        listOf("1 кг", "2 кг", "2,5 кг", "4 кг", "5 кг", "10 кг").forEach {
            compose.onNodeWithText(it, substring = false).assertExists()
        }
        // Текущий шаг уже по записям — отдельный пункт не нужен.
        compose.onAllNodesWithText("По записям", substring = true).assertCountEquals(0)

        compose.onNodeWithText("Свой шаг…").performClick()
        waitForText("Шаг, кг")
        val field = compose.onNode(hasSetTextAction() and hasText("Шаг, кг"))
        field.performTextReplacement("60")
        compose.onNodeWithText("Сохранить").assertIsNotEnabled()
        field.performTextReplacement("3,5")
        capture("exercise-custom-step-dialog-320dp-font130")
        compose.onNodeWithText("Сохранить").assertIsEnabled().performClick()

        waitForText("Шаг: 3,5 кг")
        compose.waitUntil(10_000) { storedStep() == 3.5 }
        compose.onAllNodesWithText("определён по вашим записям", substring = true).assertCountEquals(0)

        compose.onNodeWithText("Шаг: 3,5 кг").performScrollTo().performClick()
        waitForText("По записям: 5 кг")
        compose.onNodeWithText("По записям: 5 кг").performClick()
        waitForText("Шаг: 5 кг · по записям")
        compose.waitUntil(10_000) { storedStep() == 5.0 }
    }
}
