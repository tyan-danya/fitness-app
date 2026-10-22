package com.dtyan.fitdiary.ui.activity

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inspector.WindowInspector
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.dtyan.fitdiary.AppContainer
import com.dtyan.fitdiary.FitDiaryApp
import com.dtyan.fitdiary.data.repo.ActivityDefinitionInput
import com.dtyan.fitdiary.data.repo.ActivityEntryInput
import com.dtyan.fitdiary.domain.ActivityKind
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
class DailyPushUpGoalUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var container: AppContainer

    @Before fun setup() {
        container = ApplicationProvider.getApplicationContext<FitDiaryApp>().container
        runBlocking(Dispatchers.IO) {
            container.database.clearAllTables()
            container.profiles.refreshSelection()
            container.profiles.rename(1, "Даня")
            container.profiles.select(1)
            container.settings.setPushUpDailyGoal(null, 1)
        }
    }
    private fun render() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.3f)) {
                FitDiaryTheme(darkTheme = false) {
                    Box(Modifier.fillMaxSize()) { ActivityScreen(onBack = {}) }
                }
            }
        }
    }
    private fun waitForText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun showCard() {
        compose.onNodeWithTag("activity-day-list").performScrollToNode(hasText("Отжимания за день"))
    }
    /** Карточка выше экрана 320 dp при шрифте 130 %: докручиваем до конкретной строки. */
    private fun showLine(text: String) {
        compose.onNodeWithTag("activity-day-list").performScrollToNode(hasText(text))
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

    @Test fun goalStartsBlank_validatesThenSavesAndClears_withoutCreatingResults() {
        render()
        waitForText("Отжимания за день")
        showCard()
        compose.onNodeWithText("Цель на день").performScrollTo().performClick()
        waitForText("Цель отжиманий на день")
        assertThat(compose.onNodeWithTag("daily-pushup-target-input").fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text).isEmpty()
        compose.onNodeWithText("Сохранить цель").assertIsDisplayed().performClick()
        waitForText("Введите целое число от 1 до 100000")
        compose.onNodeWithText("Введите целое число от 1 до 100000").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("daily-pushup-target-input").performScrollTo().performTextReplacement("0")
        compose.onNodeWithText("Сохранить цель").performClick()
        waitForText("Введите целое число от 1 до 100000")
        runBlocking(Dispatchers.IO) { assertThat(container.settings.observePushUpDailyGoal(1).first()).isNull() }
        compose.onNodeWithTag("daily-pushup-target-input").performScrollTo().performTextReplacement("30")
        compose.onNodeWithText("Введите целое число от 1 до 100000").assertDoesNotExist()
        capture("daily-pushup-goal-dialog-320dp-font130")
        compose.onNodeWithText("Сохранить цель").assertIsDisplayed().performClick()
        waitForText("0 / 30 повторов")
        showCard()
        compose.onNodeWithText("Осталось: 30 повторов").assertIsDisplayed()
        compose.onNodeWithText("Изменить цель").performScrollTo().performClick()
        waitForText("Цель отжиманий на день")
        compose.onNodeWithText("Убрать цель").performScrollTo().performClick()
        waitForText("Цель не задана")
        compose.onNodeWithTag("daily-pushup-streak").assertDoesNotExist()
        runBlocking(Dispatchers.IO) {
            assertThat(container.settings.observePushUpDailyGoal(1).first()).isNull()
            assertThat(container.activityRepository.getAllEntries(1)).isEmpty()
            assertThat(container.database.workoutDao().getActiveOnce(1)).isNull()
        }
    }

    @Test fun recordsKnownVariants_countsCleanAndArchivedHistory_andHidesCurrentTargetOnPastDay() {
        val day = LocalDate.now().toEpochDay()
        runBlocking(Dispatchers.IO) {
            val repo = container.activityRepository
            repo.ensureDefaults(1)
            val definitions = repo.getAllDefinitions(1).associateBy { it.key }
            repo.saveEntry(definitions.getValue("pushup_standard").id, ActivityEntryInput(day, reps = 8, clientToken = "standard"), 1)
            repo.saveEntry(definitions.getValue("pushup_knees").id, ActivityEntryInput(day, reps = 5, clientToken = "knees"), 1)
            repo.saveEntry(definitions.getValue("pushup_wide").id, ActivityEntryInput(day, reps = 3, isClean = false, clientToken = "wide-dirty"), 1)
            repo.saveEntry(definitions.getValue("pushup_standard").id, ActivityEntryInput(day - 1, reps = 4, clientToken = "past"), 1)
            repo.archiveDefinition(definitions.getValue("pushup_knees").id, 1)
            val dips = repo.upsertDefinition(ActivityDefinitionInput("dip_assisted", "Брусья с противовесом", ActivityKind.REPS), 1)
            repo.saveEntry(dips, ActivityEntryInput(day, reps = 12, clientToken = "dips"), 1)
            val friend = container.profiles.create("Миша")
            repo.ensureDefaults(friend)
            val friendsPushup = repo.getAllDefinitions(friend).first { it.key == "pushup_standard" }
            repo.saveEntry(friendsPushup.id, ActivityEntryInput(day, reps = 50, clientToken = "friend"), friend)
            container.settings.setPushUpDailyGoal(20, 1)
        }
        render()
        waitForText("13 / 20 повторов")
        showCard()
        compose.onNodeWithText("Осталось: 7 повторов").assertIsDisplayed()
        compose.onNodeWithText("Ещё 3 повторов с поправкой на технику — в истории, без зачёта в цель.").assertExists()
        capture("daily-pushup-progress-320dp-font130")
        compose.onNodeWithTag("daily-pushup-record").performScrollTo().performClick()
        waitForText("Какой вариант отжиманий?")
        // Dialog-only semantics prove archived variants and dips cannot be chosen here.
        compose.onNode(hasText("Отжимания — с колен") and hasAnyAncestor(isDialog())).assertDoesNotExist()
        compose.onNode(hasText("Брусья с противовесом") and hasAnyAncestor(isDialog())).assertDoesNotExist()
        compose.onNode(hasText("Отжимания — обычная постановка") and hasAnyAncestor(isDialog())).performClick()
        waitForText("Повторы за один подход")
        compose.onNode(hasSetTextAction() and hasText("Повторы за один подход")).performTextReplacement("7")
        compose.onNodeWithText("Сохранить · Даня").performClick()
        waitForText("20 / 20 повторов")
        showCard()
        showLine("Цель достигнута"); compose.onNodeWithText("Цель достигнута").assertIsDisplayed()
        runBlocking(Dispatchers.IO) { assertThat(container.settings.observePushUpDailyGoal(1).first()).isEqualTo(20) }
        compose.onNodeWithTag("activity-day-list").performScrollToIndex(0)
        compose.onNodeWithContentDescription("Предыдущий день").performClick()
        waitForText("4 повторов засчитано")
        showCard()
        compose.onNodeWithText("Цель настраивается для сегодняшнего дня").assertIsDisplayed()
        compose.onNodeWithTag("daily-pushup-progress").assertDoesNotExist()
        compose.onNodeWithTag("daily-pushup-streak").assertDoesNotExist()
        compose.onNodeWithText("Изменить цель").assertDoesNotExist()
        capture("daily-pushup-history-320dp-font130")
        runBlocking(Dispatchers.IO) {
            assertThat(container.activityRepository.getAllEntries(1)).hasSize(6)
            assertThat(container.database.workoutDao().getActiveOnce(1)).isNull()
        }
    }

    @Test fun sevenDayStreakWaitsForToday_thenExtends_andRecalculatesForChangedThreshold() {
        val day = LocalDate.now().toEpochDay()
        runBlocking(Dispatchers.IO) {
            val repo = container.activityRepository
            repo.ensureDefaults(1)
            val activityId = repo.getAllDefinitions(1).first { it.key == "pushup_standard" }.id
            for (daysAgo in 1..7) {
                repo.saveEntry(activityId, ActivityEntryInput(day - daysAgo, reps = 100, clientToken = "streak-day-$daysAgo"), 1)
            }
            repo.saveEntry(activityId, ActivityEntryInput(day - 8, reps = 80, clientToken = "lower-threshold-day"), 1)
            repeat(9) { index ->
                repo.saveEntry(activityId, ActivityEntryInput(day, reps = 10, clientToken = "today-set-$index"), 1)
            }
            container.settings.setPushUpDailyGoal(100, 1)
        }
        render()
        waitForText("90 / 100 повторов")
        showCard()
        showLine("Дни по 100+ повторов"); compose.onNodeWithText("Дни по 100+ повторов").assertIsDisplayed()
        showLine("Текущая серия: 7 дн."); compose.onNodeWithText("Текущая серия: 7 дн.").assertIsDisplayed()
        showLine("Лучшая серия: 7 дн."); compose.onNodeWithText("Лучшая серия: 7 дн.").assertIsDisplayed()
        showLine("Вчерашняя серия сохраняется до конца дня. Сегодня пока не засчитано."); compose.onNodeWithText("Вчерашняя серия сохраняется до конца дня. Сегодня пока не засчитано.").assertIsDisplayed()
        capture("daily-pushup-streak-320dp-font130")
        compose.onNodeWithTag("daily-pushup-record").performScrollTo().performClick()
        waitForText("Какой вариант отжиманий?")
        compose.onNode(hasText("Отжимания — обычная постановка") and hasAnyAncestor(isDialog())).performClick()
        waitForText("Повторы за один подход")
        compose.onNode(hasSetTextAction() and hasText("Повторы за один подход")).performTextReplacement("10")
        compose.onNodeWithText("Сохранить · Даня").performClick()
        waitForText("100 / 100 повторов")
        showCard()
        showLine("Цель достигнута"); compose.onNodeWithText("Цель достигнута").assertIsDisplayed()
        showLine("Текущая серия: 8 дн."); compose.onNodeWithText("Текущая серия: 8 дн.").assertIsDisplayed()
        showLine("Лучшая серия: 8 дн."); compose.onNodeWithText("Лучшая серия: 8 дн.").assertIsDisplayed()
        compose.onNodeWithText("Вчерашняя серия сохраняется до конца дня. Сегодня пока не засчитано.").assertDoesNotExist()
        compose.onNodeWithText("Изменить цель").performScrollTo().performClick()
        waitForText("Цель отжиманий на день")
        compose.onNodeWithText("Смена цели пересчитает текущую и лучшую серии по новому порогу для всей истории.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("daily-pushup-target-input").performScrollTo().performTextReplacement("50")
        compose.onNodeWithText("Сохранить цель").assertIsDisplayed().performClick()
        waitForText("100 / 50 повторов")
        showCard()
        compose.onNodeWithText("Дни по 50+ повторов").assertIsDisplayed()
        compose.onNodeWithText("Текущая серия: 9 дн.").assertIsDisplayed()
        compose.onNodeWithText("Лучшая серия: 9 дн.").assertIsDisplayed()
        compose.onNodeWithText("Дни по 100+ повторов").assertDoesNotExist()
        capture("daily-pushup-streak-recalculated-320dp-font130")
        runBlocking(Dispatchers.IO) {
            assertThat(container.settings.observePushUpDailyGoal(1).first()).isEqualTo(50)
            assertThat(container.activityRepository.getAllEntries(1)).hasSize(18)
        }
    }
}
