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

data class DailyPushUpStreak(
    val target: Int,
    val currentDays: Int,
    val bestDays: Int,
    val todayReached: Boolean,
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

    private fun activityIds(definitions: List<ActivityDefinition>, athleteId: Long): Set<Long> =
        // Archived definitions remain part of the diary, so their past entries still count.
        definitions.asSequence()
            .filter { it.athleteId == athleteId && isPushUp(it) }
            .map { it.id }.toSet()

    fun calculate(
        definitions: List<ActivityDefinition>,
        entries: List<ActivityEntry>,
        athleteId: Long,
        epochDay: Long,
        target: Int?,
    ): DailyPushUpProgress {
        require(athleteId > 0)
        validateTarget(target)
        val activityIds = activityIds(definitions, athleteId)
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

    /** Re-evaluates every past day against the selected target; unfinished today keeps yesterday's streak. */
    fun streak(
        definitions: List<ActivityDefinition>,
        entries: List<ActivityEntry>,
        athleteId: Long,
        todayEpochDay: Long,
        target: Int,
    ): DailyPushUpStreak {
        require(athleteId > 0)
        validateTarget(target)
        val activityIds = activityIds(definitions, athleteId)
        val cleanTotals = mutableMapOf<Long, Long>()
        entries.forEach { entry ->
            if (entry.athleteId != athleteId || entry.activityId !in activityIds ||
                !entry.isClean || entry.epochDay > todayEpochDay) return@forEach
            val reps = entry.reps?.takeIf { it > 0 } ?: return@forEach
            cleanTotals[entry.epochDay] = cleanTotals.getOrDefault(entry.epochDay, 0L) + reps.toLong()
        }
        val reachedDays = cleanTotals.filterValues { it >= target.toLong() }.keys.sorted()
        var previousDay: Long? = null
        var run = 0
        var bestDays = 0
        reachedDays.forEach { day ->
            val previous = previousDay
            run = if (previous != null && previous != Long.MAX_VALUE && day == previous + 1L) run + 1 else 1
            bestDays = maxOf(bestDays, run)
            previousDay = day
        }
        val lastReached = reachedDays.lastOrNull()
        val todayReached = lastReached == todayEpochDay
        val continuesFromYesterday = todayEpochDay != Long.MIN_VALUE && lastReached == todayEpochDay - 1L
        return DailyPushUpStreak(
            target = target,
            currentDays = if (todayReached || continuesFromYesterday) run else 0,
            bestDays = bestDays,
            todayReached = todayReached,
        )
    }
}
