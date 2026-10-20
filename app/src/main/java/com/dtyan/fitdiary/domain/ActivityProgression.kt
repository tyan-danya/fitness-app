package com.dtyan.fitdiary.domain

import com.dtyan.fitdiary.data.db.ActivityDefinition
import com.dtyan.fitdiary.data.db.ActivityEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

enum class ActivityKind { REPS, DURATION, WALK }

enum class ActivityMetric { REPS, DURATION_SECONDS, DISTANCE_KM, STEPS }

val ActivityKind.defaultMetric: ActivityMetric
    get() = when (this) {
        ActivityKind.REPS -> ActivityMetric.REPS
        ActivityKind.DURATION, ActivityKind.WALK -> ActivityMetric.DURATION_SECONDS
    }

data class ActivityProgress(
    val bestValue: Double?,
    val nextGoal: Double?,
    val completedGoals: Int,
    val totalGoals: Int,
    val allGoalsReached: Boolean,
)

/** A ladder uses a single actual clean entry. Several practice sets are never added into a record. */
object ActivityProgression {
    fun encodeGoals(goals: List<Double>): String = JsonArray(goals.map(::JsonPrimitive)).toString()

    fun decodeGoals(json: String): List<Double> {
        val array = Json.parseToJsonElement(json) as? JsonArray
            ?: throw IllegalArgumentException("Некорректный список целей")
        return array.map {
            val primitive = it as? JsonPrimitive
            require(primitive != null && !primitive.isString) { "Цель должна быть числом" }
            requireNotNull(primitive.doubleOrNull) { "Цель должна быть числом" }
        }
    }

    fun value(entry: ActivityEntry, metric: ActivityMetric): Double? = when (metric) {
        ActivityMetric.REPS -> entry.reps?.toDouble()
        ActivityMetric.DURATION_SECONDS -> entry.durationSeconds?.toDouble()
        ActivityMetric.DISTANCE_KM -> entry.distanceKm
        ActivityMetric.STEPS -> entry.steps?.toDouble()
    }

    fun calculate(definition: ActivityDefinition, entries: List<ActivityEntry>): ActivityProgress {
        val goals = definition.goals
        val best = entries.asSequence()
            .filter { it.activityId == definition.id && it.athleteId == definition.athleteId && it.isClean }
            .mapNotNull { value(it, definition.goalMetric) }
            .filter { it.isFinite() && it > 0 }
            .maxOrNull()
        val completed = goals.count { best != null && best >= it }
        return ActivityProgress(best, goals.firstOrNull { best == null || it > best },
            completed, goals.size, goals.isNotEmpty() && completed == goals.size)
    }

    fun validateGoals(kind: ActivityKind, metric: ActivityMetric, goals: List<Double>) {
        val allowed = when (kind) {
            ActivityKind.REPS -> setOf(ActivityMetric.REPS)
            ActivityKind.DURATION -> setOf(ActivityMetric.DURATION_SECONDS)
            ActivityKind.WALK -> setOf(ActivityMetric.DURATION_SECONDS, ActivityMetric.DISTANCE_KM, ActivityMetric.STEPS)
        }
        require(metric in allowed) { "Единица цели не соответствует активности" }
        require(goals.size <= 100) { "Не больше 100 ступеней цели" }
        require(goals.all { it.isFinite() && it > 0 }) { "Цели должны быть положительными числами" }
        require(goals.zipWithNext().all { (a, b) -> b > a }) { "Цели должны идти по возрастанию без повторов" }
        val maximum = when (metric) {
            ActivityMetric.REPS -> 100_000.0
            ActivityMetric.DURATION_SECONDS -> 86_400.0
            ActivityMetric.DISTANCE_KM -> 1_000.0
            ActivityMetric.STEPS -> 1_000_000.0
        }
        require(goals.all { it <= maximum }) { "Слишком большое значение цели" }
        if (metric != ActivityMetric.DISTANCE_KM) {
            require(goals.all { it == kotlin.math.floor(it) }) { "Для этой единицы нужны целые числа" }
        }
    }
}
