package com.dtyan.fitdiary.ui.profile

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
import com.dtyan.fitdiary.ui.navigation.AppRoot
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

/** Меню профиля: все служебные функции подписаны и собраны в одном месте. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FitDiaryApp::class, qualifiers = "w320dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccountSheetUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var container: AppContainer
    private var friendId = 0L

    @Before fun prepare() {
        container = ApplicationProvider.getApplicationContext<FitDiaryApp>().container
        runBlocking(Dispatchers.IO) {
            container.database.clearAllTables()
            container.profiles.refreshSelection()
            container.profiles.rename(1, "Даня")
            container.profiles.select(1)
            friendId = container.profiles.create("Миша")
        }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.3f)) {
                FitDiaryTheme(darkTheme = false) { Box(Modifier.fillMaxSize()) { AppRoot() } }
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

    @Test fun profileButtonOpensLabelledSettings_addsAndSwitchesParticipant() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Профиль и настройки").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Профиль и настройки").performClick()
        waitForText("Профиль и настройки")
        for (label in listOf("Добавить участника", "Резервная копия", "Экспорт для Excel", "Расчёт КБЖУ через ИИ", "Обновления")) {
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        }
        capture("account-sheet-320dp-font130")

        compose.onNodeWithText("Добавить участника").performScrollTo().performClick()
        waitForText("Новый участник")
        compose.onNode(hasSetTextAction() and hasText("Имя")).performTextReplacement("Саша")
        compose.onNodeWithText("Добавить", substring = false).performClick()
        waitForText("Саша")
        runBlocking(Dispatchers.IO) {
            assertThat(container.profiles.profiles.first().map { it.name }).containsExactly("Даня", "Миша", "Саша")
        }

        compose.onNodeWithText("Миша").performScrollTo().performClick()
        compose.waitUntil(10_000) { container.profiles.activeId.value == friendId }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Профиль и настройки").fetchSemanticsNodes().isEmpty() }
        waitForText("Миша")
    }
}
