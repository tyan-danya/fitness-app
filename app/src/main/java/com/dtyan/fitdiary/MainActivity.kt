package com.dtyan.fitdiary

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.dtyan.fitdiary.reminder.MeasurementReminder
import com.dtyan.fitdiary.data.update.AppUpdater
import com.dtyan.fitdiary.ui.navigation.AppRoot
import com.dtyan.fitdiary.ui.navigation.Routes
import com.dtyan.fitdiary.ui.theme.FitDiaryTheme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Вкладка, запрошенная интентом (тап по уведомлению); null — обычный запуск. */
    private var requestedTab by mutableStateOf<String?>(null)
    private var requestNonce by mutableIntStateOf(0)
    private var updateRequestNonce by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestedTab = tabFrom(intent)
        if (intent?.getBooleanExtra(AppUpdater.EXTRA_OPEN_UPDATES, false) == true) updateRequestNonce++
        selectRequestedProfile(intent)
        setContent {
            FitDiaryTheme {
                AppRoot(requestedTab = requestedTab, requestNonce = requestNonce, updateRequestNonce = updateRequestNonce)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        tabFrom(intent)?.let { requestedTab = it; requestNonce++ }
        if (intent.getBooleanExtra(AppUpdater.EXTRA_OPEN_UPDATES, false)) updateRequestNonce++
        selectRequestedProfile(intent)
    }

    override fun onResume() {
        super.onResume()
        appContainer.updater.onResume()
    }

    private fun selectRequestedProfile(intent: Intent?) {
        val id = intent?.getLongExtra(MeasurementReminder.EXTRA_ATHLETE_ID, -1) ?: -1
        if (id > 0) lifecycleScope.launch { runCatching { appContainer.profiles.select(id) } }
    }

    private fun tabFrom(intent: Intent?): String? =
        when (intent?.getStringExtra(MeasurementReminder.EXTRA_OPEN_TAB)) {
            MeasurementReminder.TAB_MEASUREMENTS -> Routes.MEASUREMENTS
            else -> null
        }
}
