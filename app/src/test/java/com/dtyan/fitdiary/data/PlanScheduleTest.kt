package com.dtyan.fitdiary.data

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dtyan.fitdiary.MainDispatcherRule
import com.dtyan.fitdiary.data.db.ActivityPlan
import com.dtyan.fitdiary.data.db.AppDatabase
import com.dtyan.fitdiary.data.db.Athlete
import com.dtyan.fitdiary.data.repo.ActivityClock
import com.dtyan.fitdiary.data.repo.ActivityRepository
import com.dtyan.fitdiary.data.repo.ExerciseRepository
import com.dtyan.fitdiary.data.repo.PlanBundleRepository
import com.dtyan.fitdiary.data.repo.TemplateRepository
import com.dtyan.fitdiary.export.BackupManager
import com.dtyan.fitdiary.export.PlanBundleExchange
import com.dtyan.fitdiary.export.examplePlan
import com.dtyan.fitdiary.ui.templates.TemplatesViewModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID

/** The example plan with seven different days and a source, stored with the week in the given order. */
internal fun distinctWeekPlan(order: List<Int> = (1..7).toList()): JsonObject = JsonObject(examplePlan() + mapOf(
    "week" to JsonArray(order.map { day -> buildJsonObject {
        put("dayOfWeek", day); put("morning", "Утро $day")
        put("strength", if (day == 4) "Отдых от силовой A/B/C" else "Силовая $day"); put("home", "Дома $day")
    } }),
    "sources" to buildJsonArray { add(buildJsonObject { put("title", "Рекомендации"); put("url", "https://example.org/plan") }) },
))

internal val testClock = object : ActivityClock {
    override fun elapsedRealtime() = 1000L
    override fun wallTimeMillis() = 1792480000000L
    override fun bootCount() = 1
}

private fun strengthByDay(documentJson: String): Map<Int, String> =
    PlanBundleExchange.parse(documentJson).week.associate { it.dayOfWeek to it.strength }

private fun storedDays(documentJson: String): List<Int> =
    Json.parseToJsonElement(documentJson).jsonObject.getValue("week").jsonArray.map { it.jsonObject.getValue("dayOfWeek").jsonPrimitive.int }

private fun withoutWeek(documentJson: String): JsonObject =
    JsonObject(Json.parseToJsonElement(documentJson).jsonObject - "week")

@RunWith(RobolectricTestRunner::class)
class PlanScheduleTest {
    @get:Rule val mainRule = MainDispatcherRule()
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var plans: PlanBundleRepository
    private lateinit var archive: File

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        plans = PlanBundleRepository(db, TemplateRepository(db), ActivityRepository(db, clock = testClock))
        archive = File(context.cacheDir, "plan-schedule-${UUID.randomUUID()}.zip")
    }
    @After fun tearDown() { db.close(); archive.delete() }

    private suspend fun imported(document: JsonObject = distinctWeekPlan(), athleteId: Long = 1): ActivityPlan {
        assertThat(plans.importPlan(PlanBundleExchange.parse(document.toString()), athleteId)).isTrue()
        return plans.observePlans(athleteId).first().single()
    }
    private suspend fun reload(athleteId: Long = 1): ActivityPlan = plans.observePlans(athleteId).first().single()

    @Test fun swapDays_exchangesWholeDaysOrdersWeekAndKeepsOtherFields() = runTest {
        // Stored out of order: the rewrite must still list Monday..Sunday.
        val plan = imported(distinctWeekPlan(listOf(3, 1, 2, 7, 5, 4, 6)))
        val before = PlanBundleExchange.parse(plan.documentJson).week.associateBy { it.dayOfWeek }

        plans.swapDays(plan.id, 1, first = 4, second = 5)

        val after = reload()
        val week = PlanBundleExchange.parse(after.documentJson).week.associateBy { it.dayOfWeek }
        assertThat(week.getValue(4)).isEqualTo(before.getValue(5).copy(dayOfWeek = 4))
        assertThat(week.getValue(5)).isEqualTo(before.getValue(4).copy(dayOfWeek = 5))
        assertThat(week.getValue(4).strength).isEqualTo("Силовая 5")
        assertThat(week.getValue(5).strength).isEqualTo("Отдых от силовой A/B/C")
        (listOf(1, 2, 3, 6, 7)).forEach { assertThat(week.getValue(it)).isEqualTo(before.getValue(it)) }
        assertThat(storedDays(after.documentJson)).containsExactlyElementsIn(1..7).inOrder()
        // key, name, overview, templates, activities and sources stay byte-for-byte, in the same field order.
        assertThat(withoutWeek(after.documentJson)).isEqualTo(withoutWeek(plan.documentJson))
        assertThat(Json.parseToJsonElement(after.documentJson).jsonObject.keys.toList())
            .isEqualTo(Json.parseToJsonElement(plan.documentJson).jsonObject.keys.toList())
        assertThat(after.copy(documentJson = "")).isEqualTo(plan.copy(documentJson = ""))
        val parsed = PlanBundleExchange.parse(after.documentJson)
        assertThat(parsed.key).isEqualTo("example-week")
        assertThat(parsed.name).isEqualTo("Пример недели")

        // Swapping back restores the contents; the same day twice changes nothing.
        plans.swapDays(plan.id, 1, 5, 4)
        assertThat(strengthByDay(reload().documentJson)).isEqualTo(strengthByDay(plan.documentJson))
        val stable = reload().documentJson
        plans.swapDays(plan.id, 1, 3, 3)
        assertThat(reload().documentJson).isEqualTo(stable)
    }

    @Test fun shiftWeek_rotatesThroughSundayAndRoundTripRestoresOriginal() = runTest {
        val plan = imported()
        val original = strengthByDay(plan.documentJson)

        plans.shiftWeek(plan.id, 1, 1)
        val later = strengthByDay(reload().documentJson)
        assertThat(later.getValue(1)).isEqualTo(original.getValue(7)) // Sunday's day becomes Monday's
        (2..7).forEach { assertThat(later.getValue(it)).isEqualTo(original.getValue(it - 1)) }
        assertThat(later.getValue(5)).isEqualTo("Отдых от силовой A/B/C")

        plans.shiftWeek(plan.id, 1, -1)
        assertThat(reload().documentJson).isEqualTo(plan.documentJson)

        plans.shiftWeek(plan.id, 1, -1)
        val earlier = strengthByDay(reload().documentJson)
        assertThat(earlier.getValue(7)).isEqualTo(original.getValue(1)) // Monday's day becomes Sunday's
        (1..6).forEach { assertThat(earlier.getValue(it)).isEqualTo(original.getValue(it + 1)) }
        plans.shiftWeek(plan.id, 1, 1)
        assertThat(reload().documentJson).isEqualTo(plan.documentJson)

        // Six days later is one day earlier; zero changes nothing.
        plans.shiftWeek(plan.id, 1, 6)
        assertThat(strengthByDay(reload().documentJson)).isEqualTo(earlier)
        plans.shiftWeek(plan.id, 1, -6)
        assertThat(reload().documentJson).isEqualTo(plan.documentJson)
        plans.shiftWeek(plan.id, 1, 0)
        assertThat(reload().documentJson).isEqualTo(plan.documentJson)
        plans.shiftWeek(plan.id, 1, 3)
        assertThat(withoutWeek(reload().documentJson)).isEqualTo(withoutWeek(plan.documentJson))
        assertThat(storedDays(reload().documentJson)).containsExactlyElementsIn(1..7).inOrder()
    }

    @Test fun anotherProfilesOrMissingPlanIsRejectedWithoutChanges() = runTest {
        db.athleteDao().insert(Athlete(1, "Даня")); db.athleteDao().insert(Athlete(2, "Миша"))
        val mine = imported(athleteId = 1)
        val theirs = imported(athleteId = 2)
        val attempts = listOf<suspend () -> Unit>(
            { plans.swapDays(theirs.id, 1, 4, 5) },
            { plans.shiftWeek(theirs.id, 1, 1) },
            { plans.swapDays(mine.id, 2, 4, 5) },
            { plans.shiftWeek(mine.id, 2, -1) },
            { plans.swapDays(9999, 1, 4, 5) },
            { plans.shiftWeek(9999, 1, 1) },
        )
        attempts.forEach { attempt ->
            val error = runCatching { attempt() }.exceptionOrNull()
            assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(error!!.message).isEqualTo("Расписание не найдено в этом профиле")
        }
        assertThat(reload(1)).isEqualTo(mine)
        assertThat(reload(2)).isEqualTo(theirs)
    }

    @Test fun invalidWeekdayOrShiftIsRejected() = runTest {
        val plan = imported()
        listOf<suspend () -> Unit>(
            { plans.swapDays(plan.id, 1, 0, 3) },
            { plans.swapDays(plan.id, 1, 1, 8) },
            { plans.swapDays(plan.id, 1, -1, 7) },
            { plans.shiftWeek(plan.id, 1, 7) },
            { plans.shiftWeek(plan.id, 1, -7) },
        ).forEach { attempt ->
            assertThat(runCatching { attempt() }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThat(reload()).isEqualTo(plan)
    }

    @Test fun rewrittenPlanStillPassesBackupWriteInspectAndRestore() = runTest {
        val plan = imported()
        plans.swapDays(plan.id, 1, 4, 5)
        plans.shiftWeek(plan.id, 1, 1)
        val edited = reload()
        assertThat(PlanBundleExchange.parse(edited.documentJson).documentJson).isEqualTo(edited.documentJson)
        val manager = BackupManager(context, db, SettingsStore(context))
        manager.writeArchive(archive)
        plans.shiftWeek(plan.id, 1, 2) // changed after the backup: restore must bring back the backed-up week
        val preview = archive.inputStream().use { manager.inspectArchive(it) }
        assertThat(preview.profiles).isEqualTo(1)
        assertThat(preview.templates).isEqualTo(1)
        manager.restoreBackup(preview)
        val restored = db.activityDao().getPlanByKey("example-week", 1)!!
        assertThat(restored.documentJson).isEqualTo(edited.documentJson)
        assertThat(strengthByDay(restored.documentJson).getValue(6)).isEqualTo("Отдых от силовой A/B/C")
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlanScheduleViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()
    private lateinit var db: AppDatabase
    private lateinit var plans: PlanBundleRepository
    private lateinit var vm: TemplatesViewModel

    @Before fun setUp() {
        val executor = mainRule.dispatcher.asExecutor()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor(executor).setTransactionExecutor(executor).build()
        val activeId = MutableStateFlow(1L)
        val templates = TemplateRepository(db, activeId)
        plans = PlanBundleRepository(db, templates, ActivityRepository(db, activeId, testClock))
        vm = TemplatesViewModel(templates, ExerciseRepository(db.exerciseDao(), db.workoutSetDao(), activeId), 1L,
            SavedStateHandle(), plans)
    }
    @After fun tearDown() { db.close() }

    @Test fun swapAndShift_reportSuccessAndErrorsInRussian() = runTest {
        db.athleteDao().insert(Athlete(1, "Даня")); db.athleteDao().insert(Athlete(2, "Миша"))
        plans.importPlan(PlanBundleExchange.parse(distinctWeekPlan().toString()), 1)
        plans.importPlan(PlanBundleExchange.parse(distinctWeekPlan().toString()), 2)
        val mine = plans.observePlans(1).first().single()
        val theirs = plans.observePlans(2).first().single()

        vm.swapDays(mine.id, 4, 5)
        advanceUntilIdle()
        assertThat(vm.error.value).isNull()
        assertThat(vm.message.value).isEqualTo("План недели обновлён")
        assertThat(strengthByDay(plans.observePlans(1).first().single().documentJson).getValue(4)).isEqualTo("Силовая 5")

        vm.shiftWeek(mine.id, -1)
        advanceUntilIdle()
        assertThat(vm.message.value).isEqualTo("План недели обновлён")
        assertThat(strengthByDay(plans.observePlans(1).first().single().documentJson).getValue(3)).isEqualTo("Силовая 5")

        vm.swapDays(theirs.id, 1, 2)
        advanceUntilIdle()
        assertThat(vm.message.value).isNull()
        assertThat(vm.error.value).isEqualTo("Расписание не найдено в этом профиле")
        assertThat(plans.observePlans(2).first().single()).isEqualTo(theirs)
        assertThat(vm.busy.value).isFalse()
    }
}
