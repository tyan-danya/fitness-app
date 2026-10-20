package com.dtyan.fitdiary.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.dtyan.fitdiary.data.db.ActivityEntry
import com.dtyan.fitdiary.data.db.ActivityPlan
import com.dtyan.fitdiary.data.db.AppDatabase
import com.dtyan.fitdiary.data.db.Athlete
import com.dtyan.fitdiary.data.repo.ActivityClock
import com.dtyan.fitdiary.data.repo.ActivityDefinitionInput
import com.dtyan.fitdiary.data.repo.ActivityEntryInput
import com.dtyan.fitdiary.data.repo.ActivityRepository
import com.dtyan.fitdiary.data.repo.AndroidActivityClock
import com.dtyan.fitdiary.domain.ActivityKind
import com.dtyan.fitdiary.domain.ActivityMetric
import com.dtyan.fitdiary.domain.ActivityProgression
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Synthetic records only. These tests never use a person's health information or imported diary. */
@RunWith(RobolectricTestRunner::class)
class ActivityRepositoryTest {
    private class FakeClock : ActivityClock {
        var elapsed = 100_000L
        var wall = 1_700_000_000_000L
        var boot: Int? = 5
        var identity: String = "test-clock"
        override fun elapsedRealtime() = elapsed
        override fun wallTimeMillis() = wall
        override fun bootCount() = boot
        override fun clockIdentity() = identity
        fun advance(millis: Long) { elapsed += millis; wall += millis }
    }
    private lateinit var db: AppDatabase
    private lateinit var repo: ActivityRepository
    private val active = MutableStateFlow(1L)
    private val clock = FakeClock()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = ActivityRepository(db, active, clock)
    }
    @After fun tearDown() = db.close()

    private suspend fun repetitions(owner: Long = 1L) = repo.upsertDefinition(
        ActivityDefinitionInput("synthetic_reps", "Повторы", ActivityKind.REPS, goals = listOf(4.0, 6.0, 10.0)), owner)
    private suspend fun timed(owner: Long = 1L) = repo.upsertDefinition(
        ActivityDefinitionInput("synthetic_time", "Удержание", ActivityKind.DURATION, goals = listOf(7.0, 14.0, 21.0)), owner)
    private suspend fun friend(): Long {
        db.athleteDao().insert(Athlete(1, "Первый"))
        return db.athleteDao().insert(Athlete(name = "Второй"))
    }
    private fun reps(token: String, count: Int, clean: Boolean = true, day: Long = 20_000L) =
        ActivityEntryInput(day, reps = count, isClean = clean, clientToken = token)

    @Test fun defaultsArePersonalIdempotentAndNeverOverwriteEditedOrArchivedGoals() = runTest {
        val other = friend()
        repo.ensureDefaults(1)
        val original = repo.getAllDefinitions(1)
        val walking = original.single { it.key == "walking" }
        repo.updateGoals(walking.id, listOf(300.0, 900.0), 1)
        val hidden = original.single { it.key == "pushup_wide" }
        repo.archiveDefinition(hidden.id, 1)
        repo.ensureDefaults(1)
        repo.ensureDefaults(other)
        assertThat(repo.getAllDefinitions(1).map { it.id }).isEqualTo(original.map { it.id })
        assertThat(repo.getDefinition(walking.id, 1)!!.goals).containsExactly(300.0, 900.0).inOrder()
        assertThat(repo.getDefinition(hidden.id, 1)!!.isArchived).isTrue()
        assertThat(repo.getAllDefinitions(other).map { it.id }.intersect(original.map { it.id }.toSet())).isEmpty()
        assertThat(repo.observeDefinitions(1).first()).hasSize(original.size - 1)
        assertThat(repo.observeDefinitions(1, includeArchived = true).first()).hasSize(original.size)
        assertThat(repo.getAllEntries(1)).isEmpty()
        assertThat(db.workoutDao().getActiveOnce(1)).isNull()
        assertThat(db.workoutDao().getAllFinishedOnce(1)).isEmpty()
    }

    @Test fun upsertPreservesHistoryAndIdentityAndRejectsChangingUnitUnderRecordedValues() = runTest {
        val id = repetitions()
        val entryId = repo.saveEntry(id, reps("preserve", 5))
        val same = repo.upsertDefinition(ActivityDefinitionInput("synthetic_reps", "Новое имя", ActivityKind.REPS,
            goals = listOf(8.0, 12.0), instructions = "Уточнение"))
        assertThat(same).isEqualTo(id)
        assertThat(repo.getAllEntries().single().id).isEqualTo(entryId)
        assertThat(repo.getDefinition(id)!!.instructions).isEqualTo("Уточнение")
        assertThat(runCatching { repo.upsertDefinition(ActivityDefinitionInput(
            "synthetic_reps", "Время", ActivityKind.DURATION)) }.isFailure).isTrue()
        assertThat(repo.getDefinition(id)!!.kind).isEqualTo(ActivityKind.REPS)
        assertThat(repo.getAllEntries().single().reps).isEqualTo(5)
    }

    @Test fun multipleUpsertsParticipateInCallersBundleTransactionAndRollbackTogether() = runTest {
        assertThat(runCatching { db.withTransaction {
            repo.upsertDefinition(ActivityDefinitionInput("valid", "Первая", ActivityKind.REPS))
            repo.upsertDefinition(ActivityDefinitionInput("invalid", "Вторая", ActivityKind.REPS, goals = listOf(10.0, 5.0)))
        } }.isFailure).isTrue()
        assertThat(repo.getAllDefinitions()).isEmpty()
    }

    @Test fun ownerSnapshotSurvivesSwitchAndForeignOwnerCannotReadMutateOrUseToken() = runTest {
        val other = friend()
        val own = repetitions(1)
        val theirs = repetitions(other)
        active.value = other
        val originalId = repo.saveEntry(own, reps("owned", 5), athleteId = 1)
        assertThat(repo.observeEntries().first()).isEmpty()
        assertThat(repo.getDefinition(own)).isNull()
        assertThat(runCatching { repo.deleteEntry(originalId) }.isFailure).isTrue()
        assertThat(runCatching { repo.updateEntry(originalId, reps("ignored", 9)) }.isFailure).isTrue()
        assertThat(runCatching { repo.saveEntry(theirs, reps("owned", 5)) }.isFailure).isTrue()
        repo.updateEntry(originalId, reps("must-not-replace-token", 6, day = 20_001), athleteId = 1)
        val changed = repo.getAllEntries(1).single()
        assertThat(changed.reps).isEqualTo(6)
        assertThat(changed.epochDay).isEqualTo(20_001L)
        assertThat(changed.clientToken).isEqualTo("owned")
        assertThat(changed.athleteId).isEqualTo(1L)
        assertThat(repo.getAllEntries(other)).isEmpty()
    }

    @Test fun databaseRejectsEntryWhoseAthleteDoesNotOwnDefinition() = runTest {
        val other = friend()
        val id = repetitions(1)
        assertThat(runCatching { db.activityDao().insertEntry(ActivityEntry(
            athleteId = other, activityId = id, epochDay = 20_000, timestamp = 1000, reps = 5, clientToken = "foreign-key",
        )) }.isFailure).isTrue()
        assertThat(repo.getAllEntries(1)).isEmpty()
        assertThat(repo.getAllEntries(other)).isEmpty()
    }

    @Test fun progressionUsesBestCleanSetNotSumAndRecomputesAfterEditAndDelete() = runTest {
        val id = repetitions()
        repo.saveEntry(id, reps("small", 3))
        val best = repo.saveEntry(id, reps("best", 5))
        repo.saveEntry(id, reps("unclean", 50, clean = false))
        val def = repo.getDefinition(id)!!
        fun progress(entries: List<ActivityEntry>) = ActivityProgression.calculate(def, entries)
        val before = progress(repo.getAllEntries())
        assertThat(before.bestValue).isEqualTo(5.0)
        assertThat(before.nextGoal).isEqualTo(6.0)
        assertThat(before.completedGoals).isEqualTo(1)
        val reached = repo.saveEntry(id, reps("reached", 6))
        assertThat(progress(repo.getAllEntries()).nextGoal).isEqualTo(10.0)
        repo.deleteEntry(reached)
        assertThat(progress(repo.getAllEntries()).nextGoal).isEqualTo(6.0)
        repo.updateEntry(best, reps("ignored", 2))
        assertThat(progress(repo.getAllEntries()).bestValue).isEqualTo(3.0)
        assertThat(progress(repo.getAllEntries()).nextGoal).isEqualTo(4.0)
    }

    @Test fun walkingKeepsOptionalMeasuresSeparateAndDoesNotInventMissingTime() = runTest {
        val id = repo.upsertDefinition(ActivityDefinitionInput("walk_test", "Маршрут", ActivityKind.WALK,
            goals = listOf(300.0, 600.0)))
        repo.saveEntry(id, ActivityEntryInput(20_000, steps = 1000, clientToken = "steps-only"))
        val def = repo.getDefinition(id)!!
        assertThat(ActivityProgression.calculate(def, repo.getAllEntries()).bestValue).isNull()
        repo.saveEntry(id, ActivityEntryInput(20_001, durationSeconds = 400, distanceKm = 0.5, speedKmh = 4.0,
            inclinePercent = 1.0, clientToken = "walk-duration"))
        assertThat(repo.observeEntriesForDay(20_000).first().single().durationSeconds).isNull()
        assertThat(repo.observeEntriesForDay(20_001).first().single().distanceKm).isEqualTo(0.5)
        assertThat(ActivityProgression.calculate(def, repo.getAllEntries()).nextGoal).isEqualTo(600.0)
        assertThat(runCatching { repo.saveEntry(id, ActivityEntryInput(20_000, distanceKm = Double.NaN, clientToken = "nan")) }.isFailure).isTrue()
        assertThat(runCatching { repo.saveEntry(id, ActivityEntryInput(20_000, speedKmh = 4.0, clientToken = "speed-only")) }.isFailure).isTrue()
        assertThat(runCatching { repo.saveEntry(id, reps("wrong-kind", 4)) }.isFailure).isTrue()
        assertThat(repo.getAllEntries()).hasSize(2)
    }

    @Test fun timerSurvivesRepositoryRecreationUsesMonotonicTimeAndRequiresExplicitExactlyOnceSave() = runTest {
        val id = timed()
        val started = repo.startTimer(id)
        assertThat(repo.startTimer(id)).isEqualTo(started)
        clock.advance(11_800)
        clock.wall += 3_600_000 // Clock correction must not add an hour when the boot is known.
        val recreated = ActivityRepository(db, active, clock)
        assertThat(recreated.elapsedSeconds(recreated.observeTimer(id).first()!!)).isEqualTo(11L)
        val pending = recreated.stopTimer(id)
        assertThat(pending.durationSeconds).isEqualTo(11L)
        assertThat(repo.getAllEntries()).isEmpty()
        clock.advance(100_000)
        clock.boot = 6
        assertThat(repo.elapsedSeconds(repo.observeTimer(id).first()!!)).isEqualTo(11L)
        val input = ActivityEntryInput(20_000, durationSeconds = 9, clientToken = pending.clientToken)
        val ids = coroutineScope { listOf(repo, recreated).map { writer ->
            async { writer.saveEntry(id, input) }
        }.awaitAll() }
        assertThat(ids.distinct()).hasSize(1)
        assertThat(repo.getAllEntries().single().durationSeconds).isEqualTo(9L)
        assertThat(repo.observeTimer(id).first()).isNull()
        assertThat(runCatching { repo.saveEntry(id, input.copy(durationSeconds = 99)) }.isFailure).isTrue()
    }

    @Test fun rebootCannotTurnOldTimerIntoFictionalTimeButAllowsExplicitManualCorrection() = runTest {
        val other = friend()
        val id = timed(1)
        val timer = repo.startTimer(id, 1)
        clock.boot = 6
        clock.advance(300_000) // The new uptime may already exceed the old start; comparing uptime alone is unsafe.
        assertThat(repo.elapsedSeconds(timer)).isNull()
        assertThat(runCatching { repo.stopTimer(id, 1) }.isFailure).isTrue()
        assertThat(repo.getAllEntries(1)).isEmpty()
        active.value = other
        assertThat(repo.observeTimer(id).first()).isNull()
        assertThat(runCatching { repo.stopTimer(id) }.isFailure).isTrue()
        assertThat(runCatching { repo.discardTimer(id) }.isFailure).isTrue()
        repo.saveEntry(id, ActivityEntryInput(20_000, durationSeconds = 8, clientToken = timer.clientToken), athleteId = 1)
        assertThat(repo.getAllEntries(1).single().durationSeconds).isEqualTo(8L)
        assertThat(repo.getAllEntries(other)).isEmpty()
        assertThat(repo.observeTimer(id, 1).first()).isNull()
    }

    @Test fun unavailableBootCounterRejectsChangedTimeOriginInsteadOfGuessing() = runTest {
        clock.boot = null
        val id = timed()
        val timer = repo.startTimer(id)
        clock.advance(7000)
        assertThat(repo.elapsedSeconds(timer)).isEqualTo(7L)
        clock.wall += 60_000
        assertThat(repo.elapsedSeconds(timer)).isNull()
        assertThat(runCatching { repo.stopTimer(id) }.isFailure).isTrue()
        repo.discardTimer(id)
        assertThat(repo.getAllEntries()).isEmpty()
    }

    @Test fun transferredRunningTimerIsRejectedEvenWhenBootCountAndUptimeAppearValid() = runTest {
        val id = timed()
        val timer = repo.startTimer(id)
        clock.advance(18_000)
        assertThat(repo.elapsedSeconds(timer)).isEqualTo(18L)
        // Simulates Android transferring fitdiary.db to an installation with coincidentally identical BOOT_COUNT.
        clock.identity = "another-installation"
        assertThat(repo.elapsedSeconds(timer)).isNull()
        assertThat(runCatching { repo.stopTimer(id) }.isFailure).isTrue()
        assertThat(repo.getAllEntries()).isEmpty()
        repo.saveEntry(id, ActivityEntryInput(20_000, durationSeconds = 7, clientToken = timer.clientToken))
        assertThat(repo.getAllEntries().single().durationSeconds).isEqualTo(7L)
    }

    @Test fun measuredStoppedTimerRemainsKnownAfterTransferButShortTimerCannotSaveZero() = runTest {
        val id = timed()
        repo.startTimer(id)
        clock.advance(800)
        val pending = repo.stopTimer(id)
        assertThat(pending.durationSeconds).isEqualTo(0L)
        clock.identity = "another-installation"
        assertThat(repo.elapsedSeconds(pending)).isEqualTo(0L)
        assertThat(runCatching { repo.saveEntry(id, ActivityEntryInput(20_000,
            durationSeconds = 0, clientToken = pending.clientToken)) }.isFailure).isTrue()
        assertThat(repo.getAllEntries()).isEmpty()
        assertThat(repo.observeTimer(id).first()).isNotNull()
    }

    @Test fun androidClockIdentityPersistsOnlyInNoBackupDirectoryAcrossClockInstances() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = AndroidActivityClock(context).clockIdentity()
        val second = AndroidActivityClock(context).clockIdentity()
        assertThat(first).isEqualTo(second)
        assertThat(java.util.UUID.fromString(first).toString()).isEqualTo(first)
        val storage = java.io.File(context.noBackupFilesDir, "activity-clock-identity")
        assertThat(storage.isFile).isTrue()
        assertThat(storage.readText(Charsets.UTF_8)).isEqualTo(first)
    }

    @Test fun failedEntryWriteKeepsPendingTimerAndDoesNotPartiallySave() = runTest {
        val id = timed()
        val timer = repo.startTimer(id)
        clock.advance(9000)
        repo.stopTimer(id)
        db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER fail_activity BEFORE INSERT ON activity_entries BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
        val input = ActivityEntryInput(20_000, durationSeconds = 9, clientToken = timer.clientToken)
        assertThat(runCatching { repo.saveEntry(id, input) }.isFailure).isTrue()
        assertThat(repo.observeTimer(id).first()!!.durationSeconds).isEqualTo(9L)
        assertThat(repo.getAllEntries()).isEmpty()
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_activity")
        repo.saveEntry(id, input)
        assertThat(repo.observeTimer(id).first()).isNull()
        assertThat(repo.getAllEntries()).hasSize(1)
    }

    @Test fun archivingOrDeletingPlanNeverRemovesHistoryAndTimerMustBeHandledExplicitly() = runTest {
        val id = timed()
        repo.startTimer(id)
        assertThat(runCatching { repo.archiveDefinition(id) }.isFailure).isTrue()
        repo.discardTimer(id)
        val entryId = repo.saveEntry(id, ActivityEntryInput(20_000, durationSeconds = 7, clientToken = "history"))
        val planId = db.activityDao().insertPlan(ActivityPlan(athleteId = 1, planKey = "test", name = "Документ", documentJson = "{}"))
        db.activityDao().deletePlan(planId, 1)
        repo.archiveDefinition(id)
        assertThat(repo.observeDefinitions().first()).isEmpty()
        assertThat(repo.getAllDefinitions().single().isArchived).isTrue()
        assertThat(repo.observeHistory(id).first().single().id).isEqualTo(entryId)
        assertThat(runCatching { repo.saveEntry(id, ActivityEntryInput(20_000, durationSeconds = 7, clientToken = "new")) }.isFailure).isTrue()
    }
}
