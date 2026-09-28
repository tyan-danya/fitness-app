package com.dtyan.fitdiary.ui.measure

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dtyan.fitdiary.MainDispatcherRule
import com.dtyan.fitdiary.data.SettingsStore
import com.dtyan.fitdiary.data.db.AppDatabase
import com.dtyan.fitdiary.data.repo.MeasurementRepository
import com.dtyan.fitdiary.domain.MeasurementType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.ZoneId

/** MeasurementsViewModel поверх in-memory Room и реального SettingsStore; «сейчас» подменяется. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MeasurementsViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private lateinit var db: AppDatabase
    private lateinit var repo: MeasurementRepository
    private lateinit var settings: SettingsStore
    private lateinit var vm: MeasurementsViewModel

    private val today: LocalDate = LocalDate.of(2026, 9, 18)
    private var now: Long = millis(today, 9)

    private fun millis(day: LocalDate, hour: Int): Long =
        day.atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val executor = mainRule.dispatcher.asExecutor()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(executor)
            .setTransactionExecutor(executor)
            .build()
        repo = MeasurementRepository(db.measurementDao())
        settings = SettingsStore(context)
        settings.update { SettingsStore.Settings(measurementReminderSinceDay = today.minusDays(10).toEpochDay()) }
        vm = MeasurementsViewModel(repo, settings, now = { now })
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun TestScope.subscribe() {
        backgroundScope.launch { vm.state.collect { } }
        advanceUntilIdle()
    }

    @Test
    fun initialState_defaultTracked_reminderDueFromEnableDay() = runTest {
        subscribe()
        val s = vm.state.value
        assertThat(s.metrics.map { it.type }).containsExactlyElementsIn(MeasurementType.DEFAULT_TRACKED)
        assertThat(s.hasAny).isFalse()
        assertThat(s.lastDay).isNull()
        // Замеров не было 10 дней с момента включения при пороге 3 — пора
        assertThat(s.reminderDue).isTrue()
    }

    @Test
    fun saveEditor_storesOnlyFilledValidFields_andUpdatesMetrics() = runTest {
        subscribe()
        vm.openEditor()
        val editor = vm.editor.value!!
        assertThat(editor.values.keys).containsExactlyElementsIn(MeasurementType.DEFAULT_TRACKED)
        assertThat(editor.canSave).isFalse()

        vm.onValueChange(MeasurementType.WAIST, "91,5")
        vm.onValueChange(MeasurementType.CHEST, "")
        vm.onValueChange(MeasurementType.HIPS, "abc") // некорректно → блокирует сохранение
        assertThat(vm.editor.value!!.isInvalid(MeasurementType.HIPS)).isTrue()
        assertThat(vm.editor.value!!.canSave).isFalse()
        vm.onValueChange(MeasurementType.HIPS, "")
        vm.onValueChange(MeasurementType.BICEPS_R, "850") // опечатка вне диапазона
        assertThat(vm.editor.value!!.canSave).isFalse()
        vm.onValueChange(MeasurementType.BICEPS_R, "36")
        vm.onNoteChange("утром")
        assertThat(vm.editor.value!!.canSave).isTrue()

        vm.saveEditor()
        advanceUntilIdle()

        assertThat(vm.editor.value).isNull()
        val all = repo.getAllOnce()
        assertThat(all.map { it.type }).containsExactly(MeasurementType.WAIST, MeasurementType.BICEPS_R)
        assertThat(all.all { it.epochDay == today.toEpochDay() && it.note == "утром" }).isTrue()

        val s = vm.state.value
        assertThat(s.hasAny).isTrue()
        assertThat(s.lastDay).isEqualTo(today)
        assertThat(s.daysSinceLast).isEqualTo(0L)
        assertThat(s.reminderDue).isFalse()
        val waist = s.metrics.first { it.type == MeasurementType.WAIST }
        assertThat(waist.current!!.valueCm).isEqualTo(91.5)
        assertThat(waist.deltaPrev).isNull()
        assertThat(waist.chartPoints).hasSize(1)
        assertThat(s.metrics.first { it.type == MeasurementType.CHEST }.current).isNull()
    }

    @Test
    fun metrics_deltas_and_reminderAfterSilence() = runTest {
        subscribe()
        repo.addSession(mapOf(MeasurementType.WAIST to 93.0), now = millis(today.minusDays(40), 9))
        repo.addSession(mapOf(MeasurementType.WAIST to 92.0), now = millis(today.minusDays(6), 9))
        repo.addSession(mapOf(MeasurementType.WAIST to 91.2), now = millis(today.minusDays(3), 9))
        advanceUntilIdle()

        val waist = vm.state.value.metrics.first { it.type == MeasurementType.WAIST }
        assertThat(waist.current!!.valueCm).isEqualTo(91.2)
        assertThat(waist.deltaPrev).isWithin(1e-9).of(-0.8)
        assertThat(waist.delta30d).isWithin(1e-9).of(-1.8) // база — замер 40 дней назад
        assertThat(waist.chartPoints).hasSize(3)
        assertThat(waist.history.map { it.valueCm }).containsExactly(91.2, 92.0, 93.0).inOrder() // новые сверху

        // 3 дня тишины при пороге 3 — пора напомнить
        val s = vm.state.value
        assertThat(s.daysSinceLast).isEqualTo(3L)
        assertThat(s.reminderDue).isTrue()

        // Порог 5 дней — ещё рано
        vm.updateReminder(enabled = true, days = 5, hour = 20)
        advanceUntilIdle()
        assertThat(vm.state.value.reminderDue).isFalse()

        // Напоминание выключено — не «пора» даже при большой тишине
        vm.updateReminder(enabled = false, days = 1, hour = 20)
        advanceUntilIdle()
        assertThat(vm.state.value.reminderDue).isFalse()
    }

    @Test
    fun updateTracked_changesMetricsAndEditorFields_ignoresEmpty() = runTest {
        subscribe()
        vm.updateTracked(setOf(MeasurementType.WAIST))
        advanceUntilIdle()
        assertThat(vm.state.value.metrics.map { it.type }).containsExactly(MeasurementType.WAIST)
        vm.openEditor()
        assertThat(vm.editor.value!!.values.keys).containsExactly(MeasurementType.WAIST)
        vm.closeEditor()

        vm.updateTracked(emptySet()) // пустой набор игнорируется
        assertThat(settings.settings.value.trackedMeasurements).containsExactly(MeasurementType.WAIST)

        // Персистентность
        val fresh = SettingsStore(ApplicationProvider.getApplicationContext())
        assertThat(fresh.settings.value.trackedMeasurements).containsExactly(MeasurementType.WAIST)
    }

    @Test
    fun updateReminder_reenabling_resetsSilenceAnchorToToday() = runTest {
        vm.updateReminder(enabled = false, days = 3, hour = 20)
        vm.updateReminder(enabled = true, days = 3, hour = 8)
        val s = settings.settings.value
        assertThat(s.measurementReminderEnabled).isTrue()
        assertThat(s.measurementReminderHour).isEqualTo(8)
        assertThat(s.measurementReminderSinceDay).isEqualTo(today.toEpochDay())
        // Границы: дни и час обрезаются
        vm.updateReminder(enabled = true, days = 999, hour = 30)
        assertThat(settings.settings.value.measurementReminderDays).isEqualTo(60)
        assertThat(settings.settings.value.measurementReminderHour).isEqualTo(23)
    }

    @Test
    fun delete_removesEntry() = runTest {
        subscribe()
        repo.addSession(mapOf(MeasurementType.WAIST to 92.0, MeasurementType.CHEST to 100.0), now = now)
        advanceUntilIdle()
        val chest = repo.getAllOnce().first { it.type == MeasurementType.CHEST }

        vm.delete(chest)
        advanceUntilIdle()

        assertThat(repo.getAllOnce().map { it.type }).containsExactly(MeasurementType.WAIST)
        assertThat(vm.state.value.metrics.first { it.type == MeasurementType.CHEST }.current).isNull()
    }
}
