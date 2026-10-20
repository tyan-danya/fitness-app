package com.dtyan.fitdiary.ui.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
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
import com.dtyan.fitdiary.FitDiaryApp
import com.dtyan.fitdiary.data.db.Exercise
import com.dtyan.fitdiary.domain.ExerciseGuides
import com.dtyan.fitdiary.ui.theme.FitDiaryTheme
import com.dtyan.fitdiary.ui.workout.ExercisePickerScreen
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = FitDiaryApp::class, qualifiers = "w320dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExerciseGuideUiTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<FitDiaryApp>()
    private val photoPath = "guide-tests/personal-photo.png"

    @Before fun prepare() {
        runBlocking(Dispatchers.IO) {
            app.container.database.clearAllTables()
            app.container.profiles.refreshSelection()
            app.container.profiles.select(1)
        }
    }
    @After fun removeFixture() { File(app.filesDir, photoPath).delete() }
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
        compose.runOnIdle {
            val view = WindowInspector.getGlobalWindowViews().last { it.width > 0 && it.height > 0 && it.isShown }
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val output = File("build/ui-renders").apply { mkdirs() }
            File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun everySpecificGuideHasBundledDecodableArtworkAndCredit() {
        val specific = ExerciseGuides.all.filterNot { it.isGeneric }
        assertThat(specific).isNotEmpty()
        specific.forEach { guide ->
            val artwork = requireNotNull(ExerciseArtwork.find(guide.key)) { "Нет иллюстрации: ${guide.key}" }
            assertThat(artwork.frames).isNotEmpty()
            assertThat(artwork.credit).isNotEmpty()
            artwork.frames.forEach { resource ->
                val bitmap = requireNotNull(BitmapFactory.decodeResource(app.resources, resource,
                    BitmapFactory.Options().apply { inSampleSize = 4 })) { "Не читается иллюстрация: ${guide.key}/$resource" }
                try {
                    assertThat(bitmap.width).isGreaterThan(0)
                    assertThat(bitmap.height).isGreaterThan(0)
                } finally { bitmap.recycle() }
            }
        }
    }

    @Test fun pickerThumbnailOpensIllustratedHelp_withoutAddingExerciseUntilRowChosen() {
        val container = app.container
        val (workoutId, exerciseId) = runBlocking(Dispatchers.IO) {
            val exerciseId = container.database.exerciseDao().insert(
                Exercise(name = "Жим ногами", muscleGroup = "Ноги", equipment = "Тренажёр"))
            container.workoutRepository.startWorkout(athleteId = 1) to exerciseId
        }
        var chosen: Long? = null
        render { ExercisePickerScreen(workoutId, onExerciseChosen = { chosen = it }, onBack = {}) }
        waitForText("Жим ногами")
        compose.onNodeWithContentDescription("Схема и техника: Жим ногами").performClick()
        waitForText("Положение 1")
        compose.onNodeWithContentDescription("Жим ногами: положение 1").assertIsDisplayed()
        capture("exercise-guide-machine-320dp-font130")
        listOf("Как узнать тренажёр", "Как настроить и занять положение", "Как выполнить", "Частые ошибки").forEach { section ->
            compose.onNodeWithTag("exercise-guide-content").performScrollToNode(hasText(section))
            compose.onNodeWithText(section).assertIsDisplayed()
        }
        capture("exercise-guide-technique-320dp-font130")
        compose.onNodeWithTag("exercise-guide-content").performScrollToNode(hasText("Лицензия иллюстрации"))
        compose.onNodeWithText("Лицензия иллюстрации").assertIsDisplayed()
        compose.onNodeWithText("Закрыть", substring = false).assertIsDisplayed()
        assertThat(chosen).isNull()
        runBlocking(Dispatchers.IO) {
            assertThat(container.workoutRepository.observePlan(workoutId).first()).isEmpty()
            assertThat(container.database.workoutSetDao().getForWorkoutOnce(workoutId)).isEmpty()
        }
        compose.onNodeWithContentDescription("Закрыть справку").performClick()
        compose.onNodeWithText("Жим ногами", substring = false).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Жим ногами").fetchSemanticsNodes()
            chosen == exerciseId
        }
        runBlocking(Dispatchers.IO) {
            assertThat(container.workoutRepository.observePlan(workoutId).first().single().exerciseId).isEqualTo(exerciseId)
        }
    }

    @Test fun personalPhotoRetainsPriority_andGuideDoesNotModifyIt() {
        val file = File(app.filesDir, photoPath)
        file.parentFile!!.mkdirs()
        Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(25, 150, 95))
            file.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
        val original = file.readBytes()
        val guide = ExerciseGuides.resolve("Жим ногами", "Тренажёр")
        render { ExerciseGuideThumb(guide, photoPath = photoPath) }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Фото тренажёра", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Схема и техника: Жим ногами").performClick()
        waitForText("Положение 1")
        compose.onNodeWithTag("exercise-guide-content").performScrollToNode(hasText("Открыть моё фото тренажёра"))
        compose.onNodeWithText("Открыть моё фото тренажёра").assertIsDisplayed()
        compose.onNodeWithContentDescription("Закрыть справку").performClick()
        assertThat(file.readBytes()).isEqualTo(original)
    }

    @Test fun unknownCustomExerciseClearlyLabelsGenericHelp() {
        val guide = ExerciseGuides.resolve("Мой авторский комплекс", "Другое")
        assertThat(guide.isGeneric).isTrue()
        render { ExerciseGuideThumb(guide, displayName = "Мой авторский комплекс") }
        compose.onNodeWithContentDescription("Схема и техника: Мой авторский комплекс").performClick()
        waitForText("Общая справка об оборудовании. Для точной техники уточните название упражнения.")
        compose.onNodeWithText("Общая справка об оборудовании. Для точной техники уточните название упражнения.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Закрыть справку").assertIsDisplayed()
    }
}
