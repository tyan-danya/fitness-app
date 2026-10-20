package com.dtyan.fitdiary.ui.activity

import com.dtyan.fitdiary.data.db.ActivityDefinition
import com.dtyan.fitdiary.data.db.ActivityEntry
import com.dtyan.fitdiary.domain.ActivityKind
import com.dtyan.fitdiary.domain.ActivityMetric
import java.util.Locale

data class ActivityEntryDraft(
    val activityId: Long,
    val epochDay: Long,
    val clientToken: String,
    val entryId: Long? = null,
    val reps: String = "",
    val seconds: String = "",
    val distance: String = "",
    val steps: String = "",
    val minutes: String = "",
    val speed: String = "",
    val incline: String = "",
    val note: String = "",
    val isClean: Boolean = true,
)

data class ActivityDaySummary(
    val distanceKm: Double,
    val steps: Long,
    val walkingSeconds: Long,
    val exerciseSeconds: Long,
    val sets: Int,
    val reps: Int,
)

fun activityDaySummary(definitions: List<ActivityDefinition>, entries: List<ActivityEntry>): ActivityDaySummary {
    val kinds = definitions.associate { it.id to it.kind }
    val walks = entries.filter { kinds[it.activityId] == ActivityKind.WALK }
    val exercises = entries.filter { kinds[it.activityId] != ActivityKind.WALK }
    return ActivityDaySummary(walks.sumOf { it.distanceKm ?: 0.0 }, walks.sumOf { it.steps ?: 0L },
        walks.sumOf { it.durationSeconds ?: 0L }, exercises.sumOf { it.durationSeconds ?: 0L },
        exercises.size, exercises.sumOf { it.reps ?: 0 })
}

internal fun activityNumber(value: Double): String =
    String.format(Locale.forLanguageTag("ru"), "%.2f", value).trimEnd('0').trimEnd(',')

internal fun walkingMinutesToSeconds(text: String): String =
    text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
        ?.let { kotlin.math.round(it * 60).toLong().toString() } ?: text

internal fun activityMetricLabel(metric: ActivityMetric): String = when (metric) {
    ActivityMetric.REPS -> "повторов"
    ActivityMetric.DURATION_SECONDS -> "секунд"
    ActivityMetric.DISTANCE_KM -> "км"
    ActivityMetric.STEPS -> "шагов"
}

internal fun activityValue(value: Double, metric: ActivityMetric, kind: ActivityKind? = null): String =
    if (metric == ActivityMetric.DURATION_SECONDS && kind == ActivityKind.WALK) "${activityNumber(value / 60.0)} мин"
    else "${activityNumber(value)} ${activityMetricLabel(metric)}"

internal fun entryDescription(entry: ActivityEntry, kind: ActivityKind): String = when (kind) {
    ActivityKind.REPS -> "${entry.reps ?: 0} повторов"
    ActivityKind.DURATION -> "${entry.durationSeconds ?: 0} сек"
    ActivityKind.WALK -> listOfNotNull(
        entry.distanceKm?.let { "${activityNumber(it)} км" },
        entry.steps?.let { "$it шагов" },
        entry.durationSeconds?.let { "${activityNumber(it / 60.0)} мин" },
    ).joinToString(" · ")
}
