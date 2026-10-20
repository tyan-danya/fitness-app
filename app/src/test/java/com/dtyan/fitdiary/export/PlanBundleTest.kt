package com.dtyan.fitdiary.export

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dtyan.fitdiary.data.db.AppDatabase
import com.dtyan.fitdiary.data.db.Athlete
import com.dtyan.fitdiary.data.repo.*
import com.dtyan.fitdiary.domain.ActivityKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

internal fun examplePlan(): JsonObject = buildJsonObject {
    put("format", "fitdiary-plan/1"); put("key", "example-week"); put("name", "Пример недели")
    put("overview", "Индивидуальные ориентиры можно изменить.")
    put("week", buildJsonArray { (1..7).forEach { day -> add(buildJsonObject {
        put("dayOfWeek", day); put("morning", "Лёгкая прогулка"); put("strength", "По готовности"); put("home", "Отдых")
    }) } })
    put("templates", buildJsonArray { add(buildJsonObject {
        put("name", "Пример силовой"); put("exercises", buildJsonArray { add(buildJsonObject {
            put("name", "Жим"); put("muscleGroup", "Грудь"); put("equipment", "Штанга")
            put("targetSets", 2); put("targetReps", 8)
        }) })
    }) })
    put("activities", buildJsonArray {
        add(buildJsonObject {
            put("key", "walking"); put("name", "Ходьба"); put("kind", "WALK"); put("goalMetric", "DURATION_SECONDS")
            put("goals", JsonArray(listOf(600, 900, 1200).map(::JsonPrimitive))); put("instructions", "Запишите фактическое время.")
        })
        add(buildJsonObject {
            put("key", "plank"); put("name", "Планка"); put("kind", "DURATION"); put("goalMetric", "DURATION_SECONDS")
            put("goals", JsonArray(listOf(10, 15, 20).map(::JsonPrimitive))); put("instructions", "Один качественный подход.")
        })
    })
}

@RunWith(RobolectricTestRunner::class)
class PlanBundleTest {
    private lateinit var db: AppDatabase
    private lateinit var activities: ActivityRepository
    private lateinit var programs: TemplateRepository
    private lateinit var plans: PlanBundleRepository
    private val clock = object : ActivityClock {
        override fun elapsedRealtime() = 1000L
        override fun wallTimeMillis() = 1792480000000L
        override fun bootCount() = 1
    }
    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        activities = ActivityRepository(db, clock = clock)
        programs = TemplateRepository(db)
        plans = PlanBundleRepository(db, programs, activities)
    }
    @After fun tearDown() { db.close() }

    @Test fun importsOneWholePlanWithoutInventingResultsAndRepeatedImportKeepsEdits() = runTest {
        val preview = PlanBundleExchange.parse(examplePlan().toString())
        assertThat(plans.importPlan(preview, 1)).isTrue()
        val plank = activities.getAllDefinitions(1).first { it.key == "plank" }
        activities.updateGoals(plank.id, listOf(12.0, 18.0), 1)
        assertThat(plans.importPlan(preview, 1)).isFalse()
        assertThat(programs.observeTemplates(1).first()).hasSize(1)
        assertThat(plans.observePlans(1).first()).hasSize(1)
        assertThat(activities.getDefinition(plank.id, 1)!!.goals).containsExactly(12.0, 18.0).inOrder()
        assertThat(activities.getAllEntries(1)).isEmpty()
        assertThat(db.workoutDao().getActiveOnce(1)).isNull()
    }

    @Test fun changedPlanWithSameKeyCannotSilentlyReplaceGoals() = runTest {
        plans.importPlan(PlanBundleExchange.parse(examplePlan().toString()), 1)
        val changed = JsonObject(examplePlan() + ("overview" to JsonPrimitive("Изменено")))
        assertThat(runCatching { plans.importPlan(PlanBundleExchange.parse(changed.toString()), 1) }.isFailure).isTrue()
        assertThat(plans.observePlans(1).first().single().documentJson).isEqualTo(examplePlan().toString())
    }

    @Test fun duplicateKeyIsScopedToOwnerAndHistoryIsNotReplaced() = runTest {
        db.athleteDao().insert(Athlete(1, "A")); db.athleteDao().insert(Athlete(2, "B"))
        val preview = PlanBundleExchange.parse(examplePlan().toString())
        plans.importPlan(preview, 1); plans.importPlan(preview, 2)
        val a = activities.getAllDefinitions(1).first { it.key == "plank" }
        val b = activities.getAllDefinitions(2).first { it.key == "plank" }
        activities.saveEntry(a.id, ActivityEntryInput(20000, durationSeconds = 17, clientToken = "one"), 1)
        assertThat(a.id).isNotEqualTo(b.id)
        assertThat(activities.getAllEntries(2)).isEmpty()
        plans.deleteSchedule(plans.observePlans(1).first().single().id, 1)
        assertThat(activities.getAllEntries(1)).hasSize(1)
        assertThat(programs.observeTemplates(1).first()).hasSize(1)
    }

    @Test fun conflictAfterFirstDefinitionRollsBackWholeBundle() = runTest {
        val id = activities.upsertDefinition(ActivityDefinitionInput("plank", "Other type", ActivityKind.REPS), 1)
        activities.saveEntry(id, ActivityEntryInput(20000, reps = 5, clientToken = "old"), 1)
        assertThat(runCatching { plans.importPlan(PlanBundleExchange.parse(examplePlan().toString()), 1) }.isFailure).isTrue()
        assertThat(activities.getAllDefinitions(1).map { it.key }).containsExactly("plank")
        assertThat(programs.observeTemplates(1).first()).isEmpty()
        assertThat(plans.observePlans(1).first()).isEmpty()
        assertThat(activities.getAllEntries(1).single().reps).isEqualTo(5)
    }

    @Test fun rejectsDuplicateDaysForeignResultsAndInvalidLadders() {
        val valid = examplePlan()
        val activities = valid.getValue("activities").jsonArray
        val malformed = listOf(
            JsonObject(valid + ("week" to JsonArray(List(7) { valid.getValue("week").jsonArray.first() }))),
            JsonObject(valid + ("actualResults" to JsonArray(emptyList()))),
            JsonObject(valid + ("activities" to JsonArray(listOf(JsonObject(activities.first().jsonObject +
                ("goals" to JsonArray(listOf(JsonPrimitive(30), JsonPrimitive(20))))))))),
            JsonObject(valid + ("activities" to JsonArray(listOf(JsonObject(activities.first().jsonObject +
                ("goalMetric" to JsonPrimitive("REPS"))))))),
        )
        malformed.forEach { assertThat(runCatching { PlanBundleExchange.parse(it.toString()) }.isFailure).isTrue() }
    }

    /** Optional local delivery check; the private file is never copied into sources or build artifacts. */
    @Test fun validatesLocallyProvidedDeliveryPlanWhenConfigured() = runTest {
        val path = System.getenv("FITDIARY_PLAN_CHECK_FILE") ?: return@runTest
        val plan = PlanBundleExchange.parse(File(path).readText(Charsets.UTF_8))
        assertThat(plan.templates).hasSize(3)
        assertThat(plan.week).hasSize(7)
        assertThat(plans.importPlan(plan, 1)).isTrue()
        assertThat(programs.observeTemplates(1).first()).hasSize(3)
        assertThat(activities.getAllEntries(1)).isEmpty()
    }
}
