package com.dtyan.fitdiary.ui.activity

import com.dtyan.fitdiary.data.db.ActivityDefinition
import com.dtyan.fitdiary.data.db.ActivityEntry
import com.dtyan.fitdiary.domain.ActivityKind
import com.dtyan.fitdiary.domain.ActivityMetric
import com.dtyan.fitdiary.domain.DailyPushUps
import com.dtyan.fitdiary.ui.common.Format
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

/** Одна строка итога дня — свой вид активности, а не общая сумма «подходов, повторов и секунд». */
data class ActivityDayLine(
    val key: String,
    val kind: ActivityKind,
    val title: String,
    val value: String,
    val detail: String?,
    val activityIds: List<Long>,
)

/**
 * Итог дня по видам: каждая ходьба и каждое упражнение на время отдельно, все варианты отжиманий —
 * одной строкой с разбивкой, прочие упражнения на повторы — каждое своей строкой.
 */
fun activityDayBreakdown(definitions: List<ActivityDefinition>, entries: List<ActivityEntry>): List<ActivityDayLine> {
    val byId = definitions.associateBy { it.id }
    val dayEntries = entries.filter { byId.containsKey(it.activityId) }
    val pushUpEntries = dayEntries.filter { DailyPushUps.isPushUp(byId.getValue(it.activityId)) }
    val lines = mutableListOf<ActivityDayLine>()
    val ordered = definitions.sortedWith(compareBy({ it.sortOrder }, { it.id }))
    for (definition in ordered) {
        if (DailyPushUps.isPushUp(definition)) continue
        val own = dayEntries.filter { it.activityId == definition.id }
        if (own.isEmpty()) continue
        lines += when (definition.kind) {
            ActivityKind.WALK -> {
                val km = own.sumOf { it.distanceKm ?: 0.0 }
                val steps = own.sumOf { it.steps ?: 0L }
                val minutes = own.sumOf { it.durationSeconds ?: 0L } / 60.0
                ActivityDayLine("def_${definition.id}", definition.kind, definition.name,
                    listOfNotNull(km.takeIf { it > 0 }?.let { "${activityNumber(it)} км" },
                        steps.takeIf { it > 0 }?.let { "$it ${plural(it, "шаг", "шага", "шагов")}" },
                        minutes.takeIf { it > 0 }?.let { "${activityNumber(it)} мин" }).joinToString(" · "),
                    "${own.size} ${plural(own.size.toLong(), "прогулка", "прогулки", "прогулок")}", listOf(definition.id))
            }
            ActivityKind.DURATION -> {
                val holds = own.mapNotNull { it.durationSeconds }
                ActivityDayLine("def_${definition.id}", definition.kind, definition.name,
                    "лучшее ${holds.maxOrNull() ?: 0} сек",
                    "${own.size} ${plural(own.size.toLong(), "подход", "подхода", "подходов")} · всего ${holds.sum()} сек", listOf(definition.id))
            }
            ActivityKind.REPS -> {
                val reps = own.sumOf { (it.reps ?: 0).toLong() }
                ActivityDayLine("def_${definition.id}", definition.kind, definition.name,
                    "$reps ${plural(reps, "повтор", "повтора", "повторов")}",
                    "${own.size} ${plural(own.size.toLong(), "подход", "подхода", "подходов")}: " +
                        own.sortedBy { it.timestamp }.joinToString(" + ") { "${it.reps ?: 0}" }, listOf(definition.id))
            }
        }
    }
    if (pushUpEntries.isNotEmpty()) {
        val reps = pushUpEntries.sumOf { (it.reps ?: 0).toLong() }
        val variants = pushUpVariantLines(definitions, pushUpEntries).joinToString(" · ") { "${it.shortName} ${it.sets.joinToString(" + ")}" }
        // Отжимания — сразу после ходьбы: это главный домашний показатель дня.
        val index = lines.indexOfFirst { it.kind != ActivityKind.WALK }.takeIf { it >= 0 } ?: lines.size
        lines.add(index, ActivityDayLine("pushups", ActivityKind.REPS, "Отжимания",
            "$reps ${plural(reps, "повтор", "повтора", "повторов")} · ${pushUpEntries.size} ${plural(pushUpEntries.size.toLong(), "подход", "подхода", "подходов")}",
            variants, pushUpEntries.map { it.activityId }.distinct()))
    }
    return lines
}

/** Сделанные варианты отжиманий: короткое название и повторы каждого подхода по порядку. */
data class PushUpVariantLine(val activityId: Long, val shortName: String, val sets: List<Int>, val clean: Int)

fun pushUpVariantLines(definitions: List<ActivityDefinition>, entries: List<ActivityEntry>): List<PushUpVariantLine> {
    val byId = definitions.associateBy { it.id }
    return entries.filter { byId[it.activityId]?.let(DailyPushUps::isPushUp) == true }
        .groupBy { it.activityId }
        .map { (id, own) ->
            val sorted = own.sortedBy { it.timestamp }
            PushUpVariantLine(id, pushUpShortName(byId.getValue(id).name), sorted.map { it.reps ?: 0 },
                sorted.filter { it.isClean }.sumOf { it.reps ?: 0 })
        }
        .sortedBy { byId.getValue(it.activityId).sortOrder }
}

/** «Отжимания — узкая постановка» → «узкая постановка»; своё название остаётся как есть. */
internal fun pushUpShortName(name: String): String =
    name.removePrefix("Отжимания").trimStart(' ', '—', '-').ifBlank { name }.replaceFirstChar { it.lowercase() }

internal fun plural(n: Long, one: String, few: String, many: String): String = Format.plural(n, one, few, many)

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
