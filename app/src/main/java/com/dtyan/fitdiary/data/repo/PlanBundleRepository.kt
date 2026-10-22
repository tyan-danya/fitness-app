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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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

    /** Exchanges the whole contents (morning, strength, home) of two weekdays 1..7; the same day twice changes nothing. */
    suspend fun swapDays(planId: Long, athleteId: Long, first: Int, second: Int) {
        require(first in 1..7 && second in 1..7) { "День недели должен быть от 1 (понедельник) до 7 (воскресенье)" }
        rewriteWeek(planId, athleteId) { day -> when (day) { first -> second; second -> first; else -> day } }
    }

    /**
     * Rotates the whole week: +1 moves every day's contents to the next weekday (Sunday's to Monday),
     * −1 to the previous one. The order of days is kept; 0 changes nothing.
     */
    suspend fun shiftWeek(planId: Long, athleteId: Long, days: Int) {
        require(days in -6..6) { "Сдвиг плана: от 6 дней раньше до 6 дней позже" }
        // The content shown on day d comes from day d − days (wrapping around the week).
        rewriteWeek(planId, athleteId) { day -> Math.floorMod(day - 1 - days, 7) + 1 }
    }

    /**
     * Rewrites only the `week` array of an owned plan: day d receives the contents of day [sourceOf] (d).
     * Every other field of the document stays as stored, and the result must still be a valid plan
     * with the same key and name, so backups keep validating it.
     */
    private suspend fun rewriteWeek(planId: Long, athleteId: Long, sourceOf: (Int) -> Int) {
        database.withTransaction {
            val dao = database.activityDao()
            val row = requireNotNull(dao.getPlan(planId, athleteId)) { "Расписание не найдено в этом профиле" }
            if ((1..7).all { sourceOf(it) == it }) return@withTransaction
            val current = runCatching { PlanBundleExchange.parse(row.documentJson) }
                .getOrElse { throw IllegalArgumentException("Расписание повреждено. Загрузите план заново.") }
            val root = Json.parseToJsonElement(current.documentJson).jsonObject
            val byDay = root.getValue("week").jsonArray.associateBy { it.jsonObject.getValue("dayOfWeek").jsonPrimitive.int }
            val week = JsonArray((1..7).map { day ->
                if (sourceOf(day) == day) byDay.getValue(day)
                else JsonObject(byDay.getValue(sourceOf(day)).jsonObject + ("dayOfWeek" to JsonPrimitive(day)))
            })
            // Same field order as stored: only the value of `week` is replaced.
            val updated = JsonObject(root + ("week" to week)).toString()
            if (updated == row.documentJson) return@withTransaction
            val checked = PlanBundleExchange.parse(updated)
            require(checked.key == row.planKey && checked.name == row.name) { "План изменился бы при перестановке дней" }
            dao.updatePlan(row.copy(documentJson = checked.documentJson))
        }
    }
}
