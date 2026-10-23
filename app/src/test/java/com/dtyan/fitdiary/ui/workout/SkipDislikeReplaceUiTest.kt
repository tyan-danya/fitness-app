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
import com.dtyan.fitdiary.data.repo.TemplatePlanItem
import com.dtyan.fitdiary.ui.templates.TemplatesScreen
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

/**
 * «Пропустить в этой тренировке», «Не нравится» и замена на узком экране с крупным шрифтом —
 * на примере силовой B владельца: мост и вставание со скамьи ему не нравятся.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FitDiaryApp::class, qualifiers = "w320dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkipDislikeReplaceUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var container: AppContainer
    private var row = 0L
    private var bench = 0L
    private var bridge = 0L
    private var legCurl = 0L
    private var programB = 0L

    @Before fun prepare() {
        container = ApplicationProvider.getApplicationContext<FitDiaryApp>().container
        runBlocking(Dispatchers.IO) {
            container.database.clearAllTables()
            container.profiles.refreshSelection()
            container.profiles.rename(1, "Даня")
            container.profiles.select(1)
            val dao = container.database.exerciseDao()
            row = dao.insert(Exercise(name = "Тяга с опорой грудью", muscleGroup = "Спина", equipment = "Тренажёр"))
            bench = dao.insert(Exercise(name = "Вставание со скамьи", muscleGroup = "Ноги", equipment = "Своё тело"))
            bridge = dao.insert(Exercise(name = "Ягодичный мост без веса", muscleGroup = "Ноги", equipment = "Своё тело"))
            legCurl = dao.insert(Exercise(name = "Сгибание ног лёжа", muscleGroup = "Ноги", equipment = "Тренажёр"))
            programB = container.templateRepository.create("B — тяга и лёгкие ноги", listOf(
                TemplatePlanItem(row, 3, 10), TemplatePlanItem(bench, 1, 8, "С высокой скамьи"),
                TemplatePlanItem(bridge, 1, 10, "Лёжа на спине"),
            ), athleteId = 1)
        }
    }

    private fun startProgram(): Long = runBlocking(Dispatchers.IO) {
        container.templateRepository.startTemplate(programB, listOf(1L), athleteId = 1).single().id
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

    /** Ожидание с прокруткой главного потока: продолжения корутин ViewModel выполняются только на нём. */
    private fun waitIdleUntil(condition: () -> Boolean) {
        compose.waitUntil(20_000) { compose.waitForIdle(); condition() }
    }

    private fun waitForDb(check: suspend () -> Boolean) = waitIdleUntil { runBlocking(Dispatchers.IO) { check() } }

    /** Длинные списки составляют только видимое: сначала докручиваем до нужного элемента. */
    private fun scrollTo(matcher: SemanticsMatcher) {
        compose.waitForIdle()
        compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(matcher)
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

    private fun plan(workoutId: Long) = runBlocking(Dispatchers.IO) { container.workoutRepository.observePlan(workoutId).first() }

    @Test fun exerciseScreenSkipMovesOnAndTheSkippedOneIsNotOfferedAgain() {
        val workoutId = startProgram()
        var opened: Long? = null
        render { ExerciseLogScreen(workoutId, bench, onBack = {}, onOpenExercise = { _, next -> opened = next },
            onReplaceExercise = { _, _ -> }) }
        waitFor(hasText("Подход для: Даня"))
        waitFor(hasText("План: упражнение 2 из 3"))
        compose.onNodeWithTag("exercise-log-list").performScrollToNode(hasText("Пропустить в этой тренировке"))
        capture("skip-exercise-log-320dp-font130")
        compose.onNodeWithText("Пропустить в этой тренировке").performClick()

        // Следующее после пропуска — первое невыполненное и не пропущенное: тяга, а не снова скамья.
        waitIdleUntil { opened != null }
        assertThat(opened).isEqualTo(row)
        assertThat(plan(workoutId).first { it.exercise.id == bench }.skippedAt).isNotNull()
        waitFor(hasText("Пропущено", substring = false))
        capture("skip-exercise-log-skipped-320dp-font130")
        compose.onNodeWithText("Вернуть в план").performScrollTo().performClick()
        waitForDb { container.workoutRepository.observePlan(workoutId).first().first { it.exercise.id == bench }.skippedAt == null }
    }

    @Test fun workoutScreenSkipsTheSuggestedNextAndCanBringItBack() {
        val workoutId = startProgram()
        render { ActiveWorkoutScreen(workoutId, onAddExercise = {}, onOpenExercise = { _, _ -> }, onWorkoutClosed = {}) }
        waitFor(hasText("Следующее: Тяга с опорой грудью"))
        runBlocking(Dispatchers.IO) { container.workoutRepository.skipExercise(workoutId, row, true) }
        waitFor(hasText("Следующее: Вставание со скамьи"))
        compose.onNodeWithText("Пропустить в этой тренировке").performClick()
        waitFor(hasText("Следующее: Ягодичный мост без веса"))
        compose.onNodeWithText("План: 0 из 3 · пропущено: 2").assertExists()
        capture("skip-active-workout-320dp-font130")

        compose.onAllNodesWithText("Вернуть").onFirst().performScrollTo().performClick()
        waitFor(hasText("Следующее: Тяга с опорой грудью"))
    }

    @Test fun dislikeOnExerciseScreenOffersReplacementAndIsPersonal() {
        val workoutId = startProgram()
        var replacing: Pair<Long, Long>? = null
        render { ExerciseLogScreen(workoutId, bridge, onBack = {}, onReplaceExercise = { w, e -> replacing = w to e }) }
        waitFor(hasContentDescription("Не нравится: Ягодичный мост без веса"))
        compose.onNodeWithContentDescription("Не нравится: Ягодичный мост без веса").performClick()
        waitFor(hasText("Подобрать замену", substring = false) and hasClickAction() and hasAnyAncestor(isDialog()))
        capture("dislike-offer-320dp-font130")
        runBlocking(Dispatchers.IO) {
            assertThat(container.exerciseRepository.observeDislikedIds(1).first()).containsExactly(bridge)
        }
        compose.onNode(hasText("Подобрать замену", substring = false) and hasAnyAncestor(isDialog())).performClick()
        waitIdleUntil { replacing != null }
        assertThat(replacing).isEqualTo(workoutId to bridge)

        // Снять отметку можно той же кнопкой; повторное нажатие замену не предлагает.
        compose.onNodeWithContentDescription("Убрать «Не нравится»: Ягодичный мост без веса").performClick()
        waitFor(hasContentDescription("Не нравится: Ягодичный мост без веса"))
        waitForDb { container.exerciseRepository.observeDislikedIds(1).first().isEmpty() }
        compose.onAllNodes(isDialog()).assertCountEquals(0)
    }

    @Test fun replacementPickerPutsSimilarFirstAndReplacesInWorkoutAndProgram() {
        val workoutId = startProgram()
        runBlocking(Dispatchers.IO) { container.exerciseRepository.setDisliked(bench, true, 1) }
        var result: Long? = -1L
        render {
            ExercisePickerScreen(workoutId, onExerciseChosen = {}, onBack = {}, replaceExerciseId = bridge,
                onReplaced = { result = it })
        }
        waitFor(hasText("Похожие · Ноги"))
        compose.onNodeWithText("Вместо: Ягодичный мост без веса").assertExists()
        capture("replace-picker-320dp-font130")
        compose.onAllNodesWithText("Сгибание ног лёжа").onFirst().performClick()
        waitFor(hasText("Заменить на «Сгибание ног лёжа»?"))
        compose.onNodeWithText("В этой тренировке").assertExists()
        compose.onNodeWithText("В программе «B — тяга и лёгкие ноги»").assertExists()
        capture("replace-confirm-320dp-font130")
        compose.onNodeWithText("Заменить", substring = false).performClick()

        waitIdleUntil { result != -1L }
        assertThat(result).isEqualTo(legCurl)
        val replaced = plan(workoutId)
        assertThat(replaced.map { it.exercise.id }).containsExactly(row, bench, legCurl).inOrder()
        assertThat(replaced.last().targetReps).isEqualTo(10)
        runBlocking(Dispatchers.IO) {
            val items = container.templateRepository.getTemplate(programB, 1)!!.items
            assertThat(items.map { it.exercise.id }).containsExactly(row, bench, legCurl).inOrder()
            assertThat(items.last().note).isEqualTo("Вместо «Ягодичный мост без веса».")
        }
    }

    @Test fun programsMarkDislikedExercisesAndTheEditorReplacesThemInPlace() {
        runBlocking(Dispatchers.IO) { container.exerciseRepository.setDisliked(bridge, true, 1) }
        render { TemplatesScreen(onBack = {}, onOpenWorkout = {}) }
        waitFor(hasText("Не нравится: Ягодичный мост без веса"))
        compose.onNodeWithText("3. Ягодичный мост без веса").assertExists()
        capture("programs-disliked-320dp-font130")

        compose.onNodeWithText("Подобрать замену").performScrollTo().performClick()
        waitFor(hasText("Редактор программы"))
        scrollTo(hasText("Подобрать замену") and hasClickAction())
        compose.onNode(hasText("Подобрать замену") and hasClickAction()).performClick()
        waitFor(hasText("Замена упражнения"))
        capture("program-replace-catalog-320dp-font130")
        val replaceLegCurl = hasText("Заменить", substring = false) and hasClickAction() and hasAnySibling(hasText("Сгибание ног лёжа"))
        scrollTo(replaceLegCurl)
        compose.onNode(replaceLegCurl).performClick()
        waitFor(hasText("Редактор программы"))
        scrollTo(hasText("3. Сгибание ног лёжа"))
        compose.onNodeWithText("Вместо «Ягодичный мост без веса».").assertExists()
        compose.onNodeWithText("Сохранить программу").performClick()
        waitFor(hasText("Программа сохранена"))
        runBlocking(Dispatchers.IO) {
            val items = container.templateRepository.getTemplate(programB, 1)!!.items
            assertThat(items.map { it.exercise.id }).containsExactly(row, bench, legCurl).inOrder()
            assertThat(items.last().targetSets to items.last().targetReps).isEqualTo(1 to 10)
            assertThat(items.last().note).isEqualTo("Вместо «Ягодичный мост без веса».")
        }
        compose.onAllNodesWithText("Не нравится: Ягодичный мост без веса").assertCountEquals(0)
    }
}
