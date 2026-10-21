package com.dtyan.fitdiary.ui.activity

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dtyan.fitdiary.MainDispatcherRule
import com.dtyan.fitdiary.data.SettingsStore
import com.dtyan.fitdiary.data.db.AppDatabase
import com.dtyan.fitdiary.data.db.Athlete
import com.dtyan.fitdiary.data.repo.ActivityClock
import com.dtyan.fitdiary.data.repo.ActivityEntryInput
import com.dtyan.fitdiary.data.repo.ActivityRepository
import com.dtyan.fitdiary.domain.ActivityKind
import com.dtyan.fitdiary.domain.ActivityProgression
import com.dtyan.fitdiary.ui.common.Format
import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ActivityViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()
    private lateinit var db: AppDatabase
    private lateinit var repo: ActivityRepository
    private lateinit var settings: SettingsStore
    private val activeId = MutableStateFlow(1L)
    private val date = LocalDate.of(2026, 10, 20)
    private val epochDay = date.toEpochDay()
    private val clock = FakeClock()
    private inner class FakeClock : ActivityClock {
        var elapsed = 500_000L
        var wall = date.atTime(23, 59, 50).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        var boot = 10
        override fun elapsedRealtime() = elapsed
        override fun wallTimeMillis() = wall
        override fun bootCount() = boot
    }
    @Before fun setup() {
        val executor = mainRule.dispatcher.asExecutor()
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("fitdiary_settings", Context.MODE_PRIVATE).edit().clear().commit()
        settings = SettingsStore(context, activeId)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor(executor).setTransactionExecutor(executor).build()
        repo = ActivityRepository(db, activeId, clock)
    }
    @After fun close() { db.close() }
    private fun vm(owner: Long = 1L, state: SavedStateHandle = SavedStateHandle()) = ActivityViewModel(repo, owner, settings, state) { epochDay }

    @Test fun dailyGoalValidatesInput_andKeepsScreenOwnerWhenActiveProfileChanges() = runTest {
        db.athleteDao().insert(Athlete(id = 2, name = "Друг"))
        val screen = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { screen.state.collect {} }
        advanceUntilIdle()
        var saved = 0
        for (invalid in listOf("", "1.5", "0", "-1", "100001", "2147483648")) {
            screen.saveDailyPushUpGoal(invalid) { saved++ }
            advanceUntilIdle()
            assertThat(screen.error.value).isNotNull()
            assertThat(saved).isEqualTo(0)
            assertThat(settings.settingsFor(1).pushUpDailyGoal).isNull()
        }
        screen.saveDailyPushUpGoal(" 10 ") { saved++ }
        activeId.value = 2L
        advanceUntilIdle()
        assertThat(settings.settingsFor(1).pushUpDailyGoal).isEqualTo(10)
        assertThat(settings.settingsFor(2).pushUpDailyGoal).isNull()
        assertThat(screen.state.value.dailyPushUpTarget).isEqualTo(10)
        assertThat(screen.state.value.ownerId).isEqualTo(1)
        assertThat(saved).isEqualTo(1)
        screen.clearDailyPushUpGoal { saved++ }
        advanceUntilIdle()
        assertThat(settings.settingsFor(1).pushUpDailyGoal).isNull()
        assertThat(screen.state.value.dailyPushUpTarget).isNull()
        assertThat(saved).isEqualTo(2)
    }

    @Test fun dailyProgressRecalculatesAfterEditDelete_andDoesNotApplyCurrentTargetToHistory() = runTest {
        val screen = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { screen.state.collect {} }
        advanceUntilIdle()
        val definitions = repo.getAllDefinitions(1)
        val normal = definitions.first { it.key == "pushup_standard" }
        val narrow = definitions.first { it.key == "pushup_narrow" }
        repo.saveEntry(normal.id, ActivityEntryInput(epochDay, reps = 5, clientToken = "normal-five"), 1)
        repo.saveEntry(narrow.id, ActivityEntryInput(epochDay, reps = 7, clientToken = "narrow-seven"), 1)
        repo.saveEntry(normal.id, ActivityEntryInput(epochDay - 1, reps = 30, clientToken = "yesterday"), 1)
        screen.saveDailyPushUpGoal("10") {}
        advanceUntilIdle()
        assertThat(screen.state.value.pushUpProgress.cleanReps).isEqualTo(12)
        assertThat(screen.state.value.pushUpProgress.reached).isTrue()
        assertThat(screen.state.value.pushUpStreak!!.currentDays).isEqualTo(2)
        val entry = repo.getAllEntries(1).first { it.clientToken == "narrow-seven" }
        screen.editEntry(entry)
        screen.updateDraft { it.copy(reps = "2") }
        screen.saveEntry()
        advanceUntilIdle()
        assertThat(screen.state.value.pushUpProgress.cleanReps).isEqualTo(7)
        assertThat(screen.state.value.pushUpProgress.remaining).isEqualTo(3)
        assertThat(screen.state.value.pushUpStreak!!.currentDays).isEqualTo(1)
        screen.deleteEntry(repo.getAllEntries(1).first { it.clientToken == "normal-five" }) {}
        advanceUntilIdle()
        assertThat(screen.state.value.pushUpProgress.cleanReps).isEqualTo(2)
        screen.selectDay(epochDay - 1)
        advanceUntilIdle()
        assertThat(screen.state.value.pushUpProgress.cleanReps).isEqualTo(30)
        assertThat(screen.state.value.pushUpProgress.target).isNull()
        assertThat(screen.state.value.pushUpProgress.reached).isFalse()
        assertThat(screen.state.value.pushUpStreak).isNull()
        screen.saveDailyPushUpGoal("20") {}
        advanceUntilIdle()
        assertThat(screen.error.value).isNotNull()
        assertThat(settings.settingsFor(1).pushUpDailyGoal).isEqualTo(10)
    }

    @Test fun midnightFollowsToday_butPreservesOpenDraftAndExplicitHistorySelection() = runTest {
        var calendarDay = epochDay
        val state = SavedStateHandle()
        val screen = ActivityViewModel(repo, 1, settings, state) { calendarDay }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { screen.state.collect {} }
        advanceUntilIdle()
        val normal = repo.getAllDefinitions(1).first { it.key == "pushup_standard" }
        repo.saveEntry(normal.id, ActivityEntryInput(epochDay, reps = 10, clientToken = "before-midnight"), 1)
        settings.setPushUpDailyGoal(10, 1)
        screen.newEntry(normal.id)
        screen.updateDraft { it.copy(reps = "5") }
        calendarDay++
        screen.refreshDay()
        advanceUntilIdle()
        assertThat(screen.selectedDay.value).isEqualTo(calendarDay)
        assertThat(screen.state.value.todayEpochDay).isEqualTo(calendarDay)
        assertThat(screen.state.value.pushUpProgress.cleanReps).isEqualTo(0)
        assertThat(screen.state.value.pushUpStreak!!.currentDays).isEqualTo(1)
        assertThat(screen.state.value.pushUpStreak!!.todayReached).isFalse()
        assertThat(screen.editor.value!!.epochDay).isEqualTo(epochDay)
        screen.saveEntry()
        advanceUntilIdle()
        assertThat(repo.getAllEntries(1).all { it.epochDay == epochDay }).isTrue()
        screen.selectDay(epochDay)
        calendarDay++
        screen.refreshDay()
        advanceUntilIdle()
        assertThat(screen.selectedDay.value).isEqualTo(epochDay)
        assertThat(screen.state.value.pushUpProgress.cleanReps).isEqualTo(15)
    }

    @Test fun recreationAfterMidnightMovesPreviousToday_butKeepsHistoricalDate() = runTest {
        val todayState = SavedStateHandle(mapOf("activityDay" to epochDay, "activityToday" to epochDay))
        val screen = ActivityViewModel(repo, 1, settings, todayState) { epochDay + 1 }
        assertThat(screen.selectedDay.value).isEqualTo(epochDay + 1)
        val historical = SavedStateHandle(mapOf("activityDay" to epochDay - 5, "activityToday" to epochDay))
        val history = ActivityViewModel(repo, 1, settings, historical) { epochDay + 1 }
        assertThat(history.selectedDay.value).isEqualTo(epochDay - 5)
        advanceUntilIdle()
    }

    @Test fun walkingGoalMinutesRoundTripPreservesImportedSeconds() {
        for (seconds in 1..1000) {
            assertThat(walkingMinutesToSeconds(activityNumber(seconds / 60.0))).isEqualTo(seconds.toString())
        }
        assertThat(walkingMinutesToSeconds("1,67")).isEqualTo("100")
    }

    @Test fun walk_requiresAnActualMetric_andRejectsNonFiniteOrInvalidOptionalValue() {
        val draft = ActivityEntryDraft(1, epochDay, "walk-token")
        assertThrows(IllegalArgumentException::class.java) { ActivityViewModel.parseEntry(draft, ActivityKind.WALK) }
        assertThrows(IllegalArgumentException::class.java) { ActivityViewModel.parseEntry(draft.copy(speed = "5"), ActivityKind.WALK) }
        assertThrows(IllegalArgumentException::class.java) { ActivityViewModel.parseEntry(draft.copy(distance = "NaN"), ActivityKind.WALK) }
        assertThrows(IllegalArgumentException::class.java) { ActivityViewModel.parseEntry(draft.copy(steps = "123", speed = "?"), ActivityKind.WALK) }
        val actual = ActivityViewModel.parseEntry(draft.copy(minutes = "12,5", incline = "-2"), ActivityKind.WALK)
        assertThat(actual.durationSeconds).isEqualTo(750)
        assertThat(actual.inclinePercent).isEqualTo(-2.0)
        assertThat(actual.steps).isNull()
    }

    @Test fun walkingSave_snapshotsOwnerAndDate_andDoubleTapDoesNotDuplicateOrCreateWorkout() = runTest {
        db.athleteDao().insert(Athlete(id = 2, name = "Миша"))
        val vm = vm()
        advanceUntilIdle()
        val walking = repo.getAllDefinitions(1).first { it.key == "walking" }
        vm.selectDay(epochDay - 1)
        vm.newEntry(walking.id)
        vm.saveEntry()
        advanceUntilIdle()
        assertThat(vm.error.value).isEqualTo("Укажите километры, шаги или время этой прогулки")
        vm.updateDraft { it.copy(distance = "2,75", steps = "4200", minutes = "35") }
        assertThat(vm.error.value).isNull()
        vm.selectDay(epochDay)
        vm.saveEntry(); vm.saveEntry()
        activeId.value = 2
        advanceUntilIdle()
        val entry = repo.getAllEntries(1).single()
        assertThat(entry.epochDay).isEqualTo(epochDay - 1)
        assertThat(entry.distanceKm).isEqualTo(2.75)
        assertThat(entry.steps).isEqualTo(4200)
        assertThat(entry.durationSeconds).isEqualTo(2100)
        assertThat(repo.getAllEntries(2)).isEmpty()
        assertThat(db.workoutDao().getActiveOnce(1)).isNull()
        val summary = activityDaySummary(repo.getAllDefinitions(1), listOf(entry))
        assertThat(summary.steps).isEqualTo(4200)
        assertThat(summary.sets).isEqualTo(0)
        assertThat(summary.walkingSeconds).isEqualTo(2100)
    }

    @Test fun savedDraft_survivesViewModelRecreation_andEditingPreservesPrecisionAndIdentity() = runTest {
        val state = SavedStateHandle()
        val first = vm(state = state)
        advanceUntilIdle()
        val walking = repo.getAllDefinitions(1).first { it.key == "walking" }
        first.newEntry(walking.id)
        first.updateDraft { it.copy(distance = "1,234567", note = "Утро") }
        val recovered = vm(state = SavedStateHandle(mapOf("activityDraft" to state.get<String>("activityDraft"))))
        advanceUntilIdle()
        assertThat(recovered.editor.value?.distance).isEqualTo("1,234567")
        recovered.saveEntry()
        advanceUntilIdle()
        val saved = repo.getAllEntries(1).single()
        recovered.editEntry(saved)
        recovered.updateDraft { it.copy(note = "Исправленная заметка") }
        recovered.saveEntry()
        advanceUntilIdle()
        val edited = repo.getAllEntries(1).single()
        assertThat(edited.id).isEqualTo(saved.id)
        assertThat(edited.clientToken).isEqualTo(saved.clientToken)
        assertThat(edited.timestamp).isEqualTo(saved.timestamp)
        assertThat(edited.distanceKm).isEqualTo(1.234567)
    }

    @Test fun stopwatch_recoversAfterRecreation_andSavesOnlyExplicitCorrectedResultOnceToStartDay() = runTest {
        val first = vm()
        advanceUntilIdle()
        val plank = repo.getAllDefinitions(1).first { it.key == "plank" }
        first.startTimer(plank.id)
        advanceUntilIdle()
        clock.elapsed += 65_000; clock.wall += 65_000
        val recovered = vm()
        advanceUntilIdle()
        recovered.selectDay(epochDay - 4)
        val running = repo.observeTimer(plank.id, 1).first()!!
        assertThat(recovered.elapsedSeconds(running)).isEqualTo(65)
        assertThat(repo.getAllEntries(1)).isEmpty()
        recovered.stopTimer(plank.id)
        advanceUntilIdle()
        val stopped = repo.observeTimer(plank.id, 1).first()!!
        assertThat(stopped.durationSeconds).isEqualTo(65)
        assertThat(repo.getAllEntries(1)).isEmpty()
        recovered.saveTimer(stopped, "60", "Уточнил время", true)
        recovered.saveTimer(stopped, "60", "Уточнил время", true)
        advanceUntilIdle()
        val entry = repo.getAllEntries(1).single()
        assertThat(entry.durationSeconds).isEqualTo(60)
        assertThat(entry.epochDay).isEqualTo(Format.epochDayOf(running.startWallTime))
        assertThat(entry.epochDay).isEqualTo(epochDay)
        assertThat(recovered.selectedDay.value).isEqualTo(epochDay)
        assertThat(repo.observeTimer(plank.id, 1).first()).isNull()
    }

    @Test fun timerCannotCrossOwner_andRebootRequiresManualActualSeconds() = runTest {
        db.athleteDao().insert(Athlete(id = 2, name = "Миша"))
        val mine = vm()
        advanceUntilIdle()
        val plank = repo.getAllDefinitions(1).first { it.key == "plank" }
        mine.startTimer(plank.id)
        advanceUntilIdle()
        val timer = repo.observeTimer(plank.id, 1).first()!!
        clock.boot++; clock.elapsed = 1000
        assertThat(mine.elapsedSeconds(timer)).isNull()
        val friend = vm(owner = 2)
        advanceUntilIdle()
        friend.saveTimer(timer, "20", "", true)
        advanceUntilIdle()
        assertThat(friend.error.value).isNotNull()
        assertThat(repo.getAllEntries(2)).isEmpty()
        assertThat(repo.observeTimer(plank.id, 1).first()).isNotNull()
        mine.saveTimer(timer, "18", "После перезагрузки", true)
        advanceUntilIdle()
        assertThat(repo.getAllEntries(1).single().durationSeconds).isEqualTo(18)
    }

    @Test fun goalsRequireIncreasingSteps_andUseBestCleanSet_notDailyRepSum() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val definition = repo.getAllDefinitions(1).first { it.key == "pushup_standard" }
        var done = 0
        vm.saveGoals(definition.id, listOf("10", "5")) { done++ }
        advanceUntilIdle()
        assertThat(done).isEqualTo(0)
        assertThat(vm.error.value).isNotNull()
        vm.saveGoals(definition.id, listOf("10", "20", "30")) { done++ }
        advanceUntilIdle()
        listOf(12 to true, 15 to true, 50 to false).forEachIndexed { index, (reps, clean) ->
            repo.saveEntry(definition.id, ActivityEntryInput(epochDay, reps = reps, isClean = clean, clientToken = "reps-$index"), 1)
        }
        val current = repo.getDefinition(definition.id, 1)!!
        val entries = repo.getAllEntries(1)
        val progress = ActivityProgression.calculate(current, entries)
        assertThat(progress.bestValue).isEqualTo(15.0)
        assertThat(progress.nextGoal).isEqualTo(20.0)
        assertThat(activityDaySummary(listOf(current), entries).reps).isEqualTo(77)
        assertThat(done).isEqualTo(1)
    }
}
