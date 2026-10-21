package com.dtyan.fitdiary.ui.activity

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inspector.WindowInspector
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.dtyan.fitdiary.AppContainer
import com.dtyan.fitdiary.FitDiaryApp
import com.dtyan.fitdiary.data.repo.ActivityEntryInput
import com.dtyan.fitdiary.data.repo.PlanBundleRepository
import com.dtyan.fitdiary.export.PlanBundleExchange
import com.dtyan.fitdiary.export.examplePlan
import com.dtyan.fitdiary.ui.templates.TemplatesScreen
import com.dtyan.fitdiary.ui.theme.FitDiaryTheme
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.time.LocalDate
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

@RunWith(RobolectricTestRunner::class)
@Config(application = FitDiaryApp::class, qualifiers = "w320dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActivityUiSmokeTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var container: AppContainer
    @Before fun setup() {
        container = ApplicationProvider.getApplicationContext<FitDiaryApp>().container
        runBlocking(Dispatchers.IO) {
            container.database.clearAllTables()
            container.profiles.refreshSelection()
            container.profiles.rename(1, "Даня")
            container.profiles.select(1)
        }
    }
    private fun render(content: @Composable () -> Unit = { ActivityScreen(onBack = {}) }) {
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

    @Test fun walking_validatesAndRecordsSegmentMetricsAt320dpWithoutWorkout() {
        render()
        waitForText("Отжимания за день")
        capture("activity-home-320dp-font130")
        compose.onNodeWithTag("activity-day-list").performScrollToNode(hasText("Записать ходьбу"))
        compose.onNodeWithText("Записать ходьбу").performScrollTo().performClick()
        waitForText("Расстояние, км")
        compose.onNodeWithText("Сохранить · Даня").performClick()
        waitForText("Укажите километры, шаги или время этой прогулки")
        compose.onNodeWithText("Укажите километры, шаги или время этой прогулки").assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("Расстояние, км")).performScrollTo().performTextReplacement("2,75")
        compose.onNodeWithText("Укажите километры, шаги или время этой прогулки").assertDoesNotExist()
        compose.onNodeWithTag("activity-entry-editor").performScrollToNode(hasText("Шаги за эту прогулку"))
        compose.onNode(hasSetTextAction() and hasText("Шаги за эту прогулку")).performTextReplacement("4200")
        compose.onNodeWithTag("activity-entry-editor").performScrollToNode(hasText("Время ходьбы, мин"))
        compose.onNode(hasSetTextAction() and hasText("Время ходьбы, мин")).performTextReplacement("35")
        capture("activity-walk-320dp-font130")
        compose.onNodeWithText("Сохранить · Даня").performClick()
        waitForText("Ходьба: запись сохранена")
        runBlocking(Dispatchers.IO) {
            val row = container.activityRepository.getAllEntries(1).single()
            assertThat(row.distanceKm).isEqualTo(2.75)
            assertThat(row.steps).isEqualTo(4200)
            assertThat(row.durationSeconds).isEqualTo(2100)
            assertThat(container.database.workoutDao().getActiveOnce(1)).isNull()
        }
        compose.onNodeWithText("Ходьба: 2,75 км · 4200 шагов").assertExists()
    }

    @Test fun importedScheduleExpandsAllWeekdays_andKeepsProgramStartReachable() {
        runBlocking(Dispatchers.IO) {
            PlanBundleRepository(container.database, container.templateRepository, container.activityRepository)
                .importPlan(PlanBundleExchange.parse(examplePlan().toString()), 1)
        }
        render { TemplatesScreen(onBack = {}, onOpenWorkout = {}) }
        waitForText("Пример недели")
        compose.onNodeWithText("Вся неделя и рекомендации").performScrollTo().performClick()
        waitForText("Свернуть план")
        listOf("Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота", "Воскресенье").forEach { day ->
            compose.onNodeWithText(day, substring = false).performScrollTo().assertIsDisplayed()
        }
        capture("activity-schedule-week-320dp-font130")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Пример силовой"))
        compose.onNodeWithText("Начать по программе").performScrollTo().assertIsDisplayed().assertIsEnabled()
        capture("activity-schedule-program-320dp-font130")
    }

    @Test fun plank_stopDoesNotSave_manualCorrectionDoes_andGoalVisibleBesideClock() {
        runBlocking(Dispatchers.IO) {
            container.activityRepository.ensureDefaults(1)
            val plank = container.activityRepository.getAllDefinitions(1).first { it.key == "plank" }
            container.activityRepository.updateGoals(plank.id, listOf(15.0, 30.0), 1)
            container.activityRepository.upsertDefinition(com.dtyan.fitdiary.data.repo.ActivityDefinitionInput(
                plank.key, plank.name, plank.kind, plank.goalMetric, listOf(15.0, 30.0),
                instructions = "Подробное описание техники. ".repeat(50), sortOrder = plank.sortOrder), 1)
        }
        render()
        waitForText("Отжимания за день")
        compose.onNodeWithTag("activity-day-list").performScrollToNode(hasText("Планка · секундомер"))
        compose.onNodeWithText("Планка · секундомер").performScrollTo().performClick()
        waitForText("Старт")
        compose.onNodeWithText("Цель: 15 секунд").assertIsDisplayed()
        compose.onNodeWithText("Осталось: 15 секунд").assertIsDisplayed()
        compose.onNodeWithText("Следующая ступень: 30 секунд").assertIsDisplayed()
        compose.onNodeWithText("Старт").assertIsDisplayed().performClick()
        waitForText("Стоп")
        compose.onNodeWithText("Стоп").assertIsDisplayed()
        capture("activity-plank-running-320dp-font130")
        compose.onNodeWithText("Стоп").performClick()
        waitForText("Фактические секунды")
        runBlocking(Dispatchers.IO) { assertThat(container.activityRepository.getAllEntries(1)).isEmpty() }
        compose.onNodeWithTag("activity-stopwatch").performScrollToNode(hasText("Фактические секунды"))
        compose.onNode(hasSetTextAction() and hasText("Фактические секунды")).performTextReplacement("17")
        compose.onNodeWithTag("activity-stopwatch").performScrollToNode(hasText("Сохранить подход · Даня"))
        capture("activity-plank-save-320dp-font130")
        compose.onNodeWithText("Сохранить подход · Даня").assertIsDisplayed().performClick()
        waitForText("Секундомер: подход сохранён")
        runBlocking(Dispatchers.IO) {
            val row = container.activityRepository.getAllEntries(1).single()
            assertThat(row.durationSeconds).isEqualTo(17)
            assertThat(container.activityRepository.observeTimers(1).first()).isEmpty()
        }
    }

    @Test fun cleanRepsHaveOwnHistory_andCanBeEditedThenDeleted() {
        runBlocking(Dispatchers.IO) {
            container.activityRepository.ensureDefaults(1)
            val pushup = container.activityRepository.getAllDefinitions(1).first { it.key == "pushup_knees" }
            container.activityRepository.saveEntry(pushup.id, ActivityEntryInput(LocalDate.now().toEpochDay(), reps = 8,
                clientToken = "smoke-knee-set", note = "Первый подход"), 1)
        }
        render()
        waitForText("Записать подход")
        compose.onNodeWithTag("activity-day-list").performScrollToNode(hasContentDescription("Изменить запись: Отжимания — с колен"))
        compose.onNodeWithContentDescription("Изменить запись: Отжимания — с колен").performClick()
        waitForText("Повторы за один подход")
        compose.onNode(hasSetTextAction() and hasText("Повторы за один подход")).performTextReplacement("9")
        compose.onNodeWithText("Сохранить · Даня").performClick()
        waitForText("Отжимания — с колен: запись сохранена")
        compose.onNodeWithTag("activity-day-list").performScrollToNode(hasContentDescription("Удалить запись: Отжимания — с колен"))
        compose.onNodeWithContentDescription("Удалить запись: Отжимания — с колен").performClick()
        waitForText("Удалить запись?")
        compose.onNodeWithText("Удалить", substring = false).performClick()
        waitForText("Запись удалена")
        compose.onNodeWithText("Запись удалена").assertIsDisplayed()
        capture("activity-deleted-feedback-320dp-font130")
        runBlocking(Dispatchers.IO) { assertThat(container.activityRepository.getAllEntries(1)).isEmpty() }
    }
}
