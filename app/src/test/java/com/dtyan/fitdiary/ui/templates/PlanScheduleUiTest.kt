package com.dtyan.fitdiary.ui.templates

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
import com.dtyan.fitdiary.data.distinctWeekPlan
import com.dtyan.fitdiary.data.repo.PlanBundleRepository
import com.dtyan.fitdiary.export.PlanBundleExchange
import com.dtyan.fitdiary.ui.theme.FitDiaryTheme
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.time.LocalDate
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

@RunWith(RobolectricTestRunner::class)
@Config(application = FitDiaryApp::class, qualifiers = "w320dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlanScheduleUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var container: AppContainer
    private val weekdays = listOf("Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота", "Воскресенье")
    private val today = LocalDate.now().dayOfWeek.value
    private fun after(day: Int, steps: Int = 1) = Math.floorMod(day - 1 + steps, 7) + 1

    @Before fun setup() {
        container = ApplicationProvider.getApplicationContext<FitDiaryApp>().container
        runBlocking(Dispatchers.IO) {
            container.database.clearAllTables()
            container.profiles.refreshSelection()
            container.profiles.select(1)
            PlanBundleRepository(container.database, container.templateRepository, container.activityRepository)
                .importPlan(PlanBundleExchange.parse(distinctWeekPlan().toString()), 1)
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
    private fun strengthByDay(): Map<Int, String> = runBlocking(Dispatchers.IO) {
        val plan = container.database.activityDao().observePlans(1).first().single()
        PlanBundleExchange.parse(plan.documentJson).week.associate { it.dayOfWeek to it.strength }
    }
    private fun waitForPlan(condition: (Map<Int, String>) -> Boolean) {
        compose.waitUntil(10_000) { condition(strengthByDay()) }
        compose.waitForIdle()
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
    private fun strength(day: Int) = if (day == 4) "Отдых от силовой A/B/C" else "Силовая $day"

    @Test fun dayMenu_makesAnotherDayToday_andWeekShiftsBothWays() {
        render { TemplatesScreen(onBack = {}, onOpenWorkout = {}) }
        waitForText("Пример недели")
        compose.onNodeWithText("Сегодня · ${weekdays[today - 1]}").assertExists()
        compose.onNodeWithText("Другой день на сегодня").performScrollTo().assertIsDisplayed()
        capture("plan-schedule-today-320dp-font130")
        compose.onNodeWithText("Вся неделя и рекомендации").performScrollTo().performClick()
        waitForText("Свернуть план")
        weekdays.forEach { compose.onNodeWithText(it, substring = false).performScrollTo().assertIsDisplayed() }
        compose.onNodeWithText("Сдвигает весь план, порядок дней сохраняется").performScrollTo().assertIsDisplayed()
        capture("plan-schedule-week-320dp-font130")

        // Today has no "make today" item, only "swap with…", which opens the picker and can be cancelled.
        compose.onNodeWithContentDescription("Перенести день: ${weekdays[today - 1]}").performScrollTo().performClick()
        waitForText("Поменять с…")
        compose.onNodeWithText("Сделать сегодняшним").assertDoesNotExist()
        compose.onNodeWithText("Поменять с…").performClick()
        waitForText("${weekdays[today - 1]}: поменять с…")
        compose.onNodeWithText("Отмена").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Отмена").fetchSemanticsNodes().isEmpty() }
        assertThat(strengthByDay().getValue(today)).isEqualTo(strength(today))

        val tomorrow = after(today)
        compose.onNodeWithContentDescription("Перенести день: ${weekdays[tomorrow - 1]}").performScrollTo().performClick()
        waitForText("Сделать сегодняшним")
        compose.onNodeWithText("Сделать сегодняшним").performClick()
        waitForPlan { it.getValue(today) == strength(tomorrow) && it.getValue(tomorrow) == strength(today) }
        waitForText("План недели обновлён")
        compose.onNodeWithText("План недели обновлён").performScrollTo().assertIsDisplayed()
        capture("plan-schedule-updated-320dp-font130")
        assertThat(compose.onAllNodesWithText("Силовая: ${strength(tomorrow)}").fetchSemanticsNodes()).hasSize(2)

        val before = strengthByDay()
        compose.onNodeWithText("На день позже").performScrollTo().performClick()
        waitForPlan { week -> (1..7).all { week.getValue(after(it)) == before.getValue(it) } }
        compose.onNodeWithText("На день раньше").performScrollTo().performClick()
        waitForPlan { it == before }
    }

    @Test fun todayCard_picksAnotherDayInDialogShowingStrengthLines() {
        render { TemplatesScreen(onBack = {}, onOpenWorkout = {}) }
        waitForText("Пример недели")
        compose.onNodeWithText("Другой день на сегодня").performScrollTo().performClick()
        waitForText("Какой день сделать сегодняшним?")
        val target = after(today, 3)
        // Every other weekday is offered with its strength line; today itself is not.
        (1..7).filter { it != today }.forEach { day ->
            compose.onNode(hasText(weekdays[day - 1]) and hasClickAction()).assertExists()
            compose.onNode(hasText("Силовая: ${strength(day)}") and hasClickAction()).assertExists()
        }
        compose.onNode(hasText(weekdays[today - 1]) and hasClickAction()).assertDoesNotExist()
        capture("plan-schedule-swap-dialog-320dp-font130")
        compose.onNode(hasText(weekdays[target - 1]) and hasClickAction()).performScrollTo().performClick()
        waitForPlan { it.getValue(today) == strength(target) && it.getValue(target) == strength(today) }
        compose.onNodeWithText("Какой день сделать сегодняшним?").assertDoesNotExist()
        waitForText("Силовая: ${strength(target)}")
    }

    @Test fun importPreview_hasNoScheduleEditing() {
        val plan = PlanBundleExchange.parse(distinctWeekPlan().toString())
        var calls = 0
        render { PlanContents(plan, preview = true, onSwapDays = { _, _ -> calls++ }, onShiftWeek = { calls++ }) }
        waitForText("Свернуть план")
        weekdays.forEach { compose.onNodeWithText(it, substring = false).assertExists() }
        compose.onAllNodesWithContentDescription("Перенести день", substring = true).assertCountEquals(0)
        listOf("На день раньше", "На день позже", "Другой день на сегодня").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        assertThat(calls).isEqualTo(0)
    }
}
