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
import com.dtyan.fitdiary.data.repo.TemplatePlanItem
import com.dtyan.fitdiary.ui.navigation.AppRoot
import com.dtyan.fitdiary.ui.templates.TemplatesScreen
import com.dtyan.fitdiary.ui.theme.FitDiaryTheme
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
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

    @Test fun updateNotificationOpensDialog_onceAcrossGlobalProfileChange() {
        render { AppRoot(updateRequestNonce = 1) }
        waitForText("Обновления ФитДневника")
        compose.onNodeWithText("Обновления ФитДневника").assertIsDisplayed()
        capture("update-dialog-320dp-font130")
        compose.onNodeWithText("Закрыть", substring = false).performClick()
        compose.onNodeWithText("Обновления ФитДневника").assertDoesNotExist()
        runBlocking(Dispatchers.IO) { container.profiles.select(friendId) }
        waitForText("Миша")
        compose.waitForIdle()
        compose.onNodeWithText("Обновления ФитДневника").assertDoesNotExist()
        compose.onNodeWithText("Мои программы").assertExists()
    }

    @Test fun templates_editorAddsCatalogExercise_andSavesAtNarrowWidth() {
        render { AppRoot() }
        waitForText("Мои программы")
        compose.onNodeWithText("Мои программы").performScrollTo().performClick()
        waitForText("Создать программу")
        compose.onNodeWithText("Создать программу").performClick()
        waitForText("Название программы")
        compose.onNode(hasSetTextAction() and hasText("Название программы")).performTextReplacement("Моя первая программа")
        compose.onNodeWithText("Добавить из каталога").performScrollTo().performClick()
        waitForText("Жим лёжа")
        compose.onNodeWithText("Добавить", substring = false).performClick()
        compose.onNodeWithText("Готово · выбрано: 1").performClick()
        waitForText("1. Жим лёжа")
        compose.onNode(hasSetTextAction() and hasText("Повторы · необязательно")).performScrollTo().performTextReplacement("8")
        capture("template-editor-320dp-font130")
        compose.onNodeWithText("Сохранить программу").performClick()
        waitForText("Программа сохранена")
        compose.onNodeWithText("Моя первая программа").assertExists()
        runBlocking(Dispatchers.IO) {
            val template = container.templateRepository.observeTemplates(1).first().single()
            val row = container.templateRepository.getTemplate(template.id)!!.items.single()
            assertThat(row.exercise.id).isEqualTo(exerciseId)
            assertThat(row.targetSets).isEqualTo(3)
            assertThat(row.targetReps).isEqualTo(8)
            assertThat(container.database.workoutDao().getActiveOnce(1)).isNull()
        }
    }

    @Test fun guidedProgram_recordsActualResult_andAdvancesOnlyCurrentParticipant() {
        val secondId = runBlocking(Dispatchers.IO) {
            val second = container.database.exerciseDao().insert(Exercise(name = "Тяга блока", muscleGroup = "Спина"))
            container.templateRepository.create("День А", listOf(
                TemplatePlanItem(exerciseId, 3, 8, "Контролируйте движение"),
                TemplatePlanItem(second, 2, 10),
            ), athleteId = 1)
            second
        }
        render { AppRoot() }
        waitForText("Мои программы")
        compose.onNodeWithText("Мои программы").performScrollTo().performClick()
        waitForText("День А")
        compose.onNodeWithText("Начать по программе").performScrollTo().performClick()
        waitForText("Кто тренируется?")
        compose.onNodeWithText("Миша").performClick()
        compose.onAllNodesWithText("Начать по программе").onLast().performClick()
        waitForText("Открыть следующее: Жим лёжа")
        compose.onNodeWithText("План: 0 из 2 упражнений выполнено").assertExists()
        capture("guided-workout-320dp-font130")
        compose.onNodeWithText("Открыть следующее: Жим лёжа").performScrollTo().performClick()
        waitForText("Подход для: Даня")
        compose.onNodeWithTag("exercise-log-list").performScrollToNode(hasText("Упражнение выполнено → следующее"))
        compose.onNodeWithText("Упражнение выполнено → следующее").assertIsNotEnabled()
        compose.onNode(hasSetTextAction() and hasText("Вес, кг")).performScrollTo().performTextReplacement("41")
        compose.onNode(hasSetTextAction() and hasText("Повторы")).performTextReplacement("6")
        compose.onNodeWithText("Записать · Даня").performScrollTo().assertIsEnabled().assertIsDisplayed()
        capture("guided-before-save-320dp-font130")
        compose.onNodeWithText("Записать · Даня").performClick()
        try {
            // A semantics read drains pending Main/Room continuations in Robolectric. Polling
            // only the database leaves the click's main-thread coroutine queued indefinitely.
            waitForText("Даня: 41 × 6 записано")
        } catch (failure: Throwable) {
            capture("guided-save-failure-320dp-font130")
            File("build/ui-renders/guided-save-semantics.txt").writeText(compose.onRoot().printToString())
            throw failure
        }
        runBlocking(Dispatchers.IO) {
            val id = container.database.workoutDao().getActiveOnce(1)!!.id
            assertThat(container.database.workoutSetDao().getForWorkoutOnce(id)).hasSize(1)
        }
        File("build/ui-renders/guided-before-completion-semantics.txt").writeText(compose.onRoot().printToString())
        try {
            // Scroll the lazy container to discover virtualized rows; performScrollTo on a
            // text node only works while that row is already composed in the viewport.
            compose.onNodeWithTag("exercise-log-list").performScrollToNode(hasText("Упражнение выполнено → следующее"))
            compose.onNodeWithText("Упражнение выполнено → следующее").assertIsEnabled().assertIsDisplayed()
            File("build/ui-renders/guided-completion-semantics.txt").writeText(compose.onRoot().printToString())
            capture("guided-exercise-320dp-font130")
            compose.onNodeWithText("Упражнение выполнено → следующее").performClick()
            waitForText("Тяга блока")
        } catch (failure: Throwable) {
            capture("guided-completion-failure-320dp-font130")
            File("build/ui-renders/guided-completion-failure-semantics.txt").writeText(compose.onRoot().printToString())
            throw failure
        }
        waitForText("План: упражнение 2 из 2")
        runBlocking(Dispatchers.IO) {
            val mine = container.database.workoutDao().getActiveOnce(1)!!.id
            val friend = container.database.workoutDao().getActiveOnce(friendId)!!.id
            val myPlan = container.workoutRepository.observePlan(mine).first()
            assertThat(myPlan.first().completedAt).isNotNull()
            assertThat(myPlan.last().exerciseId).isEqualTo(secondId)
            assertThat(myPlan.last().completedAt).isNull()
            assertThat(container.workoutRepository.observePlan(friend).first().all { it.completedAt == null }).isTrue()
            val set = container.database.workoutSetDao().getForWorkoutOnce(mine).single()
            assertThat(set.weightKg).isEqualTo(41.0)
            assertThat(set.reps).isEqualTo(6)
            assertThat(container.database.workoutSetDao().getForWorkoutOnce(friend)).isEmpty()
        }
    }

    @Test fun saveFriendsWorkoutAsProgram_usesWorkoutOwnerEvenWithDifferentGlobalProfile() {
        val friendWorkoutId = runBlocking(Dispatchers.IO) {
            val id = container.workoutRepository.startWorkout(athleteId = friendId)
            container.workoutRepository.planExercise(id, exerciseId)
            id
        }
        render { TemplatesScreen(onBack = {}, onOpenWorkout = {}, sourceWorkoutId = friendWorkoutId) }
        waitForText("Профиль: Миша")
        compose.onNodeWithText("Сохранить", substring = false).performClick()
        waitForText("Программа сохранена. Можно уточнить ориентиры.")
        runBlocking(Dispatchers.IO) {
            assertThat(container.templateRepository.observeTemplates(friendId).first()).hasSize(1)
            assertThat(container.templateRepository.observeTemplates(1).first()).isEmpty()
            assertThat(container.profiles.activeId.value).isEqualTo(1)
        }
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
