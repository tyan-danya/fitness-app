package com.dtyan.fitdiary.domain

import com.dtyan.fitdiary.data.db.ActivityDefinition
import com.dtyan.fitdiary.data.db.ActivityEntry

data class DailyPushUpProgress(
    val cleanReps: Long,
    val totalReps: Long,
    val setCount: Int,
    val target: Int?,
    val remaining: Long?,
    val reached: Boolean,
    val fraction: Float,
)

/** Daily volume across known push-up variants; this never changes the single-set record ladder. */
object DailyPushUps {
    const val MAX_TARGET = 100_000

    private val keys = setOf(
        "pushup_standard", "pushup_narrow", "pushup_wide", "pushup_knees",
        "pushup_incline", "pushup_low", "pushup_high",
    )

    fun isPushUp(definition: ActivityDefinition): Boolean =
        definition.kind == ActivityKind.REPS && definition.key in keys

    fun validateTarget(target: Int?) {
        require(target == null || target in 1..MAX_TARGET) { "Цель отжиманий должна быть от 1 до $MAX_TARGET" }
    }

    fun calculate(
        definitions: List<ActivityDefinition>,
        entries: List<ActivityEntry>,
        athleteId: Long,
        epochDay: Long,
        target: Int?,
    ): DailyPushUpProgress {
        require(athleteId > 0)
        validateTarget(target)
        // Archived definitions remain part of the diary, so their past entries still count.
        val activityIds = definitions.asSequence()
            .filter { it.athleteId == athleteId && isPushUp(it) }
            .map { it.id }.toSet()
        var cleanReps = 0L
        var totalReps = 0L
        var setCount = 0
        entries.forEach { entry ->
            if (entry.athleteId != athleteId || entry.epochDay != epochDay || entry.activityId !in activityIds) return@forEach
            val reps = entry.reps?.takeIf { it > 0 } ?: return@forEach
            totalReps += reps.toLong()
            if (entry.isClean) cleanReps += reps.toLong()
            setCount++
        }
        return DailyPushUpProgress(
            cleanReps = cleanReps,
            totalReps = totalReps,
            setCount = setCount,
            target = target,
            remaining = target?.let { (it.toLong() - cleanReps).coerceAtLeast(0L) },
            reached = target != null && cleanReps >= target.toLong(),
            fraction = target?.let { (cleanReps.toDouble() / it).coerceIn(0.0, 1.0).toFloat() } ?: 0f,
        )
    }
}
