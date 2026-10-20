package com.dtyan.fitdiary.data.repo

import androidx.room.withTransaction
import com.dtyan.fitdiary.data.db.ActivityPlan
import com.dtyan.fitdiary.data.db.AppDatabase
import com.dtyan.fitdiary.domain.ActivityKind
import com.dtyan.fitdiary.domain.ActivityMetric
import com.dtyan.fitdiary.export.PlanBundleExchange
import com.dtyan.fitdiary.export.PlanBundlePreview
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

/** One transaction installs a plan for one explicitly captured profile; it never imports results. */
class PlanBundleRepository(
    private val database: AppDatabase,
    private val templates: TemplateRepository,
    private val activities: ActivityRepository,
) {
    fun observePlans(athleteId: Long): Flow<List<ActivityPlan>> = database.activityDao().observePlans(athleteId)

    /** False means the exact same plan was already imported and no data was changed. */
    suspend fun importPlan(preview: PlanBundlePreview, athleteId: Long): Boolean {
        // Treat even an in-memory preview as untrusted at the write boundary.
        val plan = PlanBundleExchange.parse(preview.documentJson)
        return database.withTransaction {
            database.workoutDao().ensureDefaultAthlete()
            require(database.workoutDao().availableAthlete(athleteId) == 1) { "Профиль не найден или скрыт" }
            database.activityDao().getPlanByKey(plan.key, athleteId)?.let { previous ->
                require(Json.parseToJsonElement(previous.documentJson) == Json.parseToJsonElement(plan.documentJson)) {
                    "План с таким ключом уже загружен. Для новой редакции нужен другой ключ."
                }
                return@withTransaction false
            }
            val existingNames = database.openHelper.readableDatabase.query(
                "SELECT name FROM workout_templates WHERE athleteId = ?", arrayOf<Any>(athleteId),
            ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0).lowercase()) } }
            require(plan.templates.none { it.name.lowercase() in existingNames }) {
                "Программа с таким названием уже существует. Переименуйте её перед импортом нового плана."
            }
            plan.activities.forEachIndexed { position, activity ->
                activities.upsertDefinition(ActivityDefinitionInput(
                    key = activity.key, name = activity.name, kind = ActivityKind.valueOf(activity.kind),
                    goalMetric = ActivityMetric.valueOf(activity.goalMetric), goals = activity.goals,
                    instructions = activity.instructions, weeklyScheduleNote = activity.weeklyScheduleNote,
                    sortOrder = position,
                ), athleteId)
            }
            plan.templates.asReversed().forEach { templates.importTemplate(it.name, it.exercises, athleteId) }
            database.activityDao().insertPlan(ActivityPlan(
                athleteId = athleteId, planKey = plan.key, name = plan.name, documentJson = plan.documentJson,
            ))
            true
        }
    }

    suspend fun deleteSchedule(id: Long, athleteId: Long) {
        database.activityDao().deletePlan(id, athleteId)
    }
}
