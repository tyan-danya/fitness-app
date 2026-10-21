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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Избранное на узком экране с крупным шрифтом: звёздочка в каталоге и на экране подходов. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FitDiaryApp::class, qualifiers = "w320dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FavoriteExercisesUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var container: AppContainer
    private var friendId = 0L
    private var benchId = 0L
    private var rowId = 0L

    @Before fun prepare() {
        container = ApplicationProvider.getApplicationContext<FitDiaryApp>().container
        runBlocking(Dispatchers.IO) {
            container.database.clearAllTables()
            container.profiles.refreshSelection()
            container.profiles.rename(1, "Даня")
            container.profiles.select(1)
            friendId = container.profiles.create("Миша")
            benchId = container.database.exerciseDao().insert(Exercise(name = "Жим лёжа", muscleGroup = "Грудь", equipment = "Штанга"))
            rowId = container.database.exerciseDao().insert(Exercise(name = "Тяга блока", muscleGroup = "Спина", equipment = "Блок"))
        }
    }

    private fun render(content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.3f)) {
                FitDiaryTheme(darkTheme = false) { Box(Modifier.fillMaxSize()) { content() } }
            }
        }
    }

    private fun waitFor(matcher: SemanticsMatcher) {
        compose.waitUntil(10_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
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

    @Test fun pickerStarMovesExerciseToFavorites_andFilterShowsOnlyFavorites_withoutAddingIt() {
        val workoutId = runBlocking(Dispatchers.IO) { container.workoutRepository.startWorkout(athleteId = 1) }
        var chosen: Long? = null
        render { ExercisePickerScreen(workoutId, onExerciseChosen = { chosen = it }, onBack = {}) }
        waitFor(hasContentDescription("В избранное: Тяга блока"))
        compose.onAllNodesWithText("Избранное", substring = false).assertCountEquals(1) // только фильтр, секции ещё нет

        compose.onNodeWithContentDescription("В избранное: Тяга блока").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Избранное", substring = false).fetchSemanticsNodes().size == 2 }
        compose.onAllNodesWithContentDescription("Убрать из избранного: Тяга блока").assertCountEquals(2)
        capture("favorites-picker-320dp-font130")
        runBlocking(Dispatchers.IO) {
            assertThat(container.exerciseRepository.observeFavoriteIds(1).first()).containsExactly(rowId)
            assertThat(container.exerciseRepository.observeFavoriteIds(friendId).first()).isEmpty()
            // Звёздочка не добавляет упражнение в тренировку.
            assertThat(container.workoutRepository.observePlan(workoutId).first()).isEmpty()
        }
        assertThat(chosen).isNull()

        compose.onNode(hasText("Избранное", substring = false) and hasClickAction()).performClick()
        waitFor(hasContentDescription("Убрать из избранного: Тяга блока"))
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Жим лёжа").fetchSemanticsNodes().isEmpty() }

        compose.onAllNodesWithContentDescription("Убрать из избранного: Тяга блока").onFirst().performClick()
        waitFor(hasText("Избранного пока нет"))
        runBlocking(Dispatchers.IO) { assertThat(container.exerciseRepository.observeFavoriteIds(1).first()).isEmpty() }
    }

    @Test fun exerciseLogStarMarksFavoriteForSelectedParticipant() {
        val members = runBlocking(Dispatchers.IO) { container.workoutRepository.startGroupWorkout(listOf(1, friendId)) }
        val friendsWorkout = members.first { it.athleteId == friendId }.id
        render { ExerciseLogScreen(friendsWorkout, benchId, onBack = {}) }
        waitFor(hasText("Подход для: Миша"))
        waitFor(hasContentDescription("В избранное: Жим лёжа"))
        compose.onNodeWithContentDescription("В избранное: Жим лёжа").performClick()
        waitFor(hasContentDescription("Убрать из избранного: Жим лёжа"))
        runBlocking(Dispatchers.IO) {
            assertThat(container.exerciseRepository.observeFavoriteIds(friendId).first()).containsExactly(benchId)
            assertThat(container.exerciseRepository.observeFavoriteIds(1).first()).isEmpty()
        }
    }
}
