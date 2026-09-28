package com.dtyan.fitdiary.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dtyan.fitdiary.MainDispatcherRule
import com.dtyan.fitdiary.domain.MeasurementType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsStoreTest {
    @get:Rule val main = MainDispatcherRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val active = MutableStateFlow(1L)
    private val prefs get() = context.getSharedPreferences("fitdiary_settings", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
        active.value = 1L
    }

    @Test
    fun legacyPreferencesRemainWithOriginalProfileAndAreNeverInheritedByFriend() = runTest {
        prefs.edit()
            .putInt("calorieGoal", 1875)
            .putInt("proteinGoalG", 125)
            .putString("trackedMeasurements", "WAIST,CHEST")
            .putBoolean("measurementReminderEnabled", false)
            .putInt("measurementReminderDays", 7)
            .putInt("measurementReminderHour", 9)
            .putLong("measurementReminderSinceDay", 20100)
            .commit()
        val store = SettingsStore(context, active)
        runCurrent()
        val legacy = store.settingsFor(1L)
        assertThat(legacy.calorieGoal).isEqualTo(1875)
        assertThat(legacy.proteinGoalG).isEqualTo(125)
        assertThat(legacy.trackedMeasurements).containsExactly(MeasurementType.WAIST, MeasurementType.CHEST)
        assertThat(legacy.measurementReminderEnabled).isFalse()
        assertThat(legacy.measurementReminderDays).isEqualTo(7)
        assertThat(legacy.measurementReminderHour).isEqualTo(9)
        assertThat(legacy.measurementReminderSinceDay).isEqualTo(20100L)

        active.value = 2L
        runCurrent()
        assertThat(store.settings.value.calorieGoal).isEqualTo(SettingsStore.Settings().calorieGoal)
        assertThat(store.settings.value.proteinGoalG).isEqualTo(SettingsStore.Settings().proteinGoalG)
        assertThat(store.settings.value.measurementReminderEnabled).isTrue()
        store.update { it.copy(calorieGoal = 3100) }
        assertThat(store.settingsFor(1L)).isEqualTo(legacy)
        active.value = 1L
        runCurrent()
        store.update { it.copy(calorieGoal = 1950) }
        assertThat(prefs.getInt("athlete.1.calorieGoal", -1)).isEqualTo(1950)
        assertThat(prefs.getInt("calorieGoal", -1)).isEqualTo(1875)
        assertThat(store.settingsFor(2L).calorieGoal).isEqualTo(3100)
    }

    @Test
    fun immediateProfileSwitchUsesNewOwnersSettingsBeforeCollectorRuns() = runTest {
        val store = SettingsStore(context, active)
        runCurrent()
        store.update { it.copy(calorieGoal = 1800, proteinGoalG = 100) }
        active.value = 2L
        // Intentionally do not advance the active-profile flow collector here.
        store.update { it.copy(proteinGoalG = 190) }
        assertThat(store.settingsFor(2L).calorieGoal).isEqualTo(2200)
        assertThat(store.settingsFor(2L).proteinGoalG).isEqualTo(190)
        assertThat(store.settingsFor(1L).calorieGoal).isEqualTo(1800)
        assertThat(store.settingsFor(1L).proteinGoalG).isEqualTo(100)
        runCurrent()
        assertThat(store.settings.value).isEqualTo(store.settingsFor(2L))
        active.value = 1L
        runCurrent()
        assertThat(store.settings.value.calorieGoal).isEqualTo(1800)
    }

    @Test
    fun safeBackupRoundTripsAllProfilesAndDiaryIdentityWithoutAnyAiConnectionData() = runTest {
        val store = SettingsStore(context, active)
        runCurrent()
        store.update { it.copy(calorieGoal = 1800, trackedMeasurements = setOf(MeasurementType.WAIST),
            aiApiKey = "test-source-secret", aiBaseUrl = "https://source.example.test/private", aiModel = "private-source-model",
            measurementReminderSinceDay = 20001L) }
        active.value = 2L
        store.update { it.copy(calorieGoal = 3300, proteinGoalG = 190,
            trackedMeasurements = setOf(MeasurementType.CHEST, MeasurementType.BICEPS_R),
            measurementReminderDays = 5, measurementReminderHour = 8, measurementReminderSinceDay = 20002L) }
        // Historical or unexpected secret names also must never enter the allow-listed export.
        prefs.edit().putString("athlete.2.aiApiKey", "test-profile-secret").putString("customSecret", "test-other-secret").commit()
        val expectedOne = store.settingsFor(1L)
        val expectedTwo = store.settingsFor(2L)
        val originalDiary = store.diaryId
        val backup = store.exportSafeSettings()
        val exported = Json.parseToJsonElement(backup).jsonObject
        assertThat(exported.keys).doesNotContain("aiApiKey")
        assertThat(exported.keys).doesNotContain("aiBaseUrl")
        assertThat(exported.keys).doesNotContain("aiModel")
        assertThat(exported.keys).doesNotContain("athlete.2.aiApiKey")
        assertThat(exported.keys).doesNotContain("customSecret")
        assertThat(backup).doesNotContain("test-source-secret")
        assertThat(backup).doesNotContain("source.example.test")
        assertThat(backup).doesNotContain("private-source-model")

        active.value = 1L
        store.update { it.copy(calorieGoal = 999, aiApiKey = "test-destination-secret",
            aiBaseUrl = "https://destination.example.test/private", aiModel = "destination-model") }
        prefs.edit().putString("diaryId", UUID.randomUUID().toString()).putInt("athlete.3.calorieGoal", 4444).commit()
        store.restoreSafeSettings(backup)
        runCurrent()
        assertThat(store.diaryId).isEqualTo(originalDiary)
        assertThat(store.settingsFor(1L).calorieGoal).isEqualTo(expectedOne.calorieGoal)
        assertThat(store.settingsFor(1L).trackedMeasurements).isEqualTo(expectedOne.trackedMeasurements)
        assertThat(store.settingsFor(1L).measurementReminderSinceDay).isEqualTo(20001L)
        assertThat(store.settingsFor(2L).calorieGoal).isEqualTo(expectedTwo.calorieGoal)
        assertThat(store.settingsFor(2L).proteinGoalG).isEqualTo(190)
        assertThat(store.settingsFor(2L).trackedMeasurements).isEqualTo(expectedTwo.trackedMeasurements)
        assertThat(store.settingsFor(2L).measurementReminderSinceDay).isEqualTo(20002L)
        assertThat(prefs.contains("athlete.3.calorieGoal")).isFalse()
        assertThat(store.settings.value.aiApiKey).isEqualTo("test-destination-secret")
        assertThat(store.settings.value.aiBaseUrl).isEqualTo("https://destination.example.test/private")
        assertThat(store.settings.value.aiModel).isEqualTo("destination-model")
        assertThat(store.exportSafeSettings()).isEqualTo(backup)
    }

    @Test
    fun invalidRestoreNeverPartiallyMutatesPreferencesOrState() = runTest {
        val store = SettingsStore(context, active)
        runCurrent()
        store.update { it.copy(calorieGoal = 2000, aiApiKey = "test-unchanged-secret") }
        val valid = Json.parseToJsonElement(store.exportSafeSettings()).jsonObject
        val beforePrefs = prefs.all.toMap()
        val beforeState = store.settings.value
        val invalidValues: List<Pair<String, JsonElement>> = listOf(
            "athlete.1.calorieGoal" to JsonPrimitive(-1),
            "athlete.1.measurementReminderDays" to JsonPrimitive(0),
            "athlete.1.measurementReminderHour" to JsonPrimitive(24),
            "athlete.1.proteinGoalG" to JsonPrimitive("100"),
            "athlete.1.trackedMeasurements" to JsonPrimitive("UNKNOWN_ZONE"),
            "diaryId" to JsonPrimitive("not-a-uuid"),
            "aiApiKey" to JsonPrimitive("untrusted-secret"),
            "unrecognized" to JsonPrimitive(1),
        )
        for ((key, value) in invalidValues) {
            val invalid = buildJsonObject { valid.forEach { (k, v) -> put(k, v) }; put(key, value) }.toString()
            assertThat(runCatching { store.restoreSafeSettings(invalid) }.isFailure).isTrue()
            assertThat(prefs.all).isEqualTo(beforePrefs)
            assertThat(store.settings.value).isEqualTo(beforeState)
        }
    }

    @Test
    fun invalidUpdateIsRejectedBeforeAnyPreferenceOrStateChange() = runTest {
        val store = SettingsStore(context, active)
        runCurrent()
        store.update { it.copy(calorieGoal = 2000) }
        val beforePrefs = prefs.all.toMap()
        val beforeState = store.settings.value
        val invalid: List<(SettingsStore.Settings) -> SettingsStore.Settings> = listOf(
            { it.copy(calorieGoal = -1) },
            { it.copy(proteinGoalG = -1) },
            { it.copy(measurementReminderDays = 0) },
            { it.copy(measurementReminderHour = 24) },
        )
        for (transform in invalid) {
            assertThat(runCatching { store.update(transform) }.isFailure).isTrue()
            assertThat(prefs.all).isEqualTo(beforePrefs)
            assertThat(store.settings.value).isEqualTo(beforeState)
        }
    }
}
