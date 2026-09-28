package com.dtyan.fitdiary.ui.workout

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inspector.WindowInspector
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import com.dtyan.fitdiary.ui.navigation.AppRoot
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

/** Real Compose rendering with synthetic local data; no network or physical device required. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FitDiaryApp::class, qualifiers = "w320dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkoutUiSmokeTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var container: AppContainer
    private var friendId = 0L
    private var otherId = 0L
    private var exerciseId = 0L

    @Before fun prepareSyntheticDiary() {
        container = ApplicationProvider.getApplicationContext<FitDiaryApp>().container
        runBlocking(Dispatchers.IO) {
            container.database.clearAllTables()
            container.profiles.refreshSelection()
            container.profiles.rename(1, "Даня")
            container.profiles.select(1)
            friendId = container.profiles.create("Миша")
            otherId = container.profiles.create("Саша")
            exerciseId = container.database.exerciseDao().insert(Exercise(name = "Жим лёжа", muscleGroup = "Грудь"))
        }
    }

    private fun render(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.3f)) {
                FitDiaryTheme(darkTheme = false) { Box(Modifier.fillMaxSize()) { content() } }
            }
        }
    }

    private fun waitForText(text: String) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun capture(name: String) {
        val output = File("build/ui-renders").apply { mkdirs() }
        // PixelCopy/captureToImage requires a hardware redraw signal absent in Robolectric.
        // Draw the actual visible native View tree, including a dialog when it is topmost.
        compose.runOnIdle {
            val view = WindowInspector.getGlobalWindowViews().last { it.width > 0 && it.height > 0 && it.isShown }
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun home_startsGroupFromParticipantChooser() {
        render { AppRoot() }
        waitForText("Тренироваться вместе")
        capture("home-320dp-font130")
        compose.onNodeWithText("Тренироваться вместе").performScrollTo().performClick()
        waitForText("Кто тренируется?")
        compose.onNodeWithText("Миша").assertExists()
        compose.onNodeWithText("Саша").assertExists()
        capture("group-chooser-320dp-font130")
        compose.onNodeWithText("Начать", substring = false).performClick()
        waitForText("Добавить упражнение")
        compose.onNodeWithText("Миша", substring = false).assertExists()
        compose.onNodeWithText("Саша", substring = false).assertExists()
        runBlocking(Dispatchers.IO) {
            assertThat(container.database.workoutDao().getActiveOnce(1)).isNotNull()
            assertThat(container.database.workoutDao().getActiveOnce(friendId)).isNotNull()
            assertThat(container.database.workoutDao().getActiveOnce(otherId)).isNotNull()
        }
        capture("group-workout-320dp-font130")
    }

    @Test fun exercise_preservesDrafts_switchesAfterSave_andUndoTargetsOriginalAthlete() {
        val members = runBlocking(Dispatchers.IO) {
            container.workoutRepository.startGroupWorkout(listOf(1, friendId, otherId))
        }
        val myWorkoutId = members.first { it.athleteId == 1L }.id
        val friendsWorkoutId = members.first { it.athleteId == friendId }.id
        render { ExerciseLogScreen(myWorkoutId, exerciseId, onBack = {}) }
        waitForText("Подход для: Даня")
        compose.onNode(hasSetTextAction() and hasText("Вес, кг")).performScrollTo().performTextReplacement("37,5")
        compose.onNode(hasSetTextAction() and hasText("Повторы")).performTextReplacement("7")
        compose.onNodeWithText("Миша", substring = false).performScrollTo().performClick()
        waitForText("Подход для: Миша")
        compose.onNode(hasSetTextAction() and hasText("Вес, кг")).performScrollTo().performTextReplacement("22,5")
        compose.onNodeWithText("Даня", substring = false).performScrollTo().performClick()
        waitForText("Подход для: Даня")
        compose.onNode(hasSetTextAction() and hasText("37,5")).assertExists()
        compose.onNode(hasSetTextAction() and hasText("7")).assertExists()
        compose.onNodeWithText("Записать · Даня").performScrollTo().assertIsDisplayed()
        capture("exercise-320dp-font130")
        compose.onNodeWithText("Записать и следующий").performScrollTo().performClick()
        waitForText("Подход для: Миша")
        compose.onNode(hasSetTextAction() and hasText("22,5")).assertExists()
        waitForText("Отменить")
        compose.onNodeWithText("Отменить", substring = false).performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            runBlocking(Dispatchers.IO) { container.database.workoutSetDao().getForWorkoutOnce(myWorkoutId).isEmpty() }
        }
        runBlocking(Dispatchers.IO) {
            assertThat(container.database.workoutSetDao().getForWorkoutOnce(friendsWorkoutId)).isEmpty()
        }
    }
}
