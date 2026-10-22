package com.dtyan.fitdiary.ui.activity

import com.dtyan.fitdiary.data.db.ActivityDefinition
import com.dtyan.fitdiary.data.db.ActivityEntry
import com.dtyan.fitdiary.domain.ActivityKind
import com.dtyan.fitdiary.domain.ActivityMetric
import com.dtyan.fitdiary.domain.DailyPushUps
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Итог дня раздельно по видам — сценарий владельца: ходьба, планка, два варианта отжиманий и брусья. */
class ActivityBreakdownTest {
    private val day = 20_382L

    private fun definition(id: Long, key: String, name: String, kind: ActivityKind, order: Int) = ActivityDefinition(
        id, 1L, key, name, kind,
        when (kind) { ActivityKind.REPS -> ActivityMetric.REPS; else -> ActivityMetric.DURATION_SECONDS }, "[]", sortOrder = order,
    )

    private val walking = definition(1, "walking", "Ходьба", ActivityKind.WALK, 0)
    private val plank = definition(2, "plank", "Планка на предплечьях", ActivityKind.DURATION, 1)
    private val standard = definition(3, "pushup_standard", "Отжимания — обычная постановка", ActivityKind.REPS, 2)
    private val narrow = definition(4, "pushup_narrow", "Отжимания — узкая постановка", ActivityKind.REPS, 3)
    private val dips = definition(10, "dip_assisted", "Брусья с противовесом", ActivityKind.REPS, 9)
    private val definitions = listOf(walking, plank, standard, narrow, dips)

    private var nextId = 1L
    private fun entry(activity: ActivityDefinition, reps: Int? = null, seconds: Long? = null, km: Double? = null,
        steps: Long? = null, at: Long = nextId, clean: Boolean = true) = ActivityEntry(nextId++, 1L, activity.id, day, at,
        reps, seconds, km, steps, null, null, null, clean, "token-$nextId")

    private val entries = listOf(
        entry(plank, seconds = 35), entry(narrow, reps = 10), entry(standard, reps = 10), entry(plank, seconds = 34),
        entry(narrow, reps = 10), entry(walking, seconds = 1200, km = 2.1, steps = 2400), entry(walking, seconds = 300, km = 0.5),
        entry(dips, reps = 10), entry(dips, reps = 8),
    )

    @Test fun walkingPushUpsPlankAndDipsAreSeparateLines() {
        val lines = activityDayBreakdown(definitions, entries)
        assertThat(lines.map { it.title }).containsExactly("Ходьба", "Отжимания", "Планка на предплечьях", "Брусья с противовесом").inOrder()
        val walk = lines[0]
        assertThat(walk.value).isEqualTo("2,6 км · 2400 шагов · 25 мин")
        assertThat(walk.detail).isEqualTo("2 прогулки")
        val pushUps = lines[1]
        assertThat(pushUps.value).isEqualTo("30 повторов · 3 подхода")
        assertThat(pushUps.detail).isEqualTo("обычная постановка 10 · узкая постановка 10 + 10")
        assertThat(pushUps.activityIds).containsExactly(3L, 4L)
        assertThat(lines[2].value).isEqualTo("лучшее 35 сек")
        assertThat(lines[2].detail).isEqualTo("2 подхода · всего 69 сек")
        assertThat(lines[3].value).isEqualTo("18 повторов")
        assertThat(lines[3].detail).isEqualTo("2 подхода: 10 + 8")
    }

    @Test fun emptyDayHasNoLines_andUnknownActivitiesAreIgnored() {
        assertThat(activityDayBreakdown(definitions, emptyList())).isEmpty()
        val foreign = ActivityEntry(99, 1L, 777L, day, 1L, 10, null, null, null, null, null, null, true, "foreign")
        assertThat(activityDayBreakdown(definitions, listOf(foreign))).isEmpty()
    }

    @Test fun pushUpVariantsKeepSetOrderAndCleanCount() {
        val lines = pushUpVariantLines(definitions, listOf(
            entry(narrow, reps = 12, at = 5), entry(narrow, reps = 6, at = 9, clean = false), entry(standard, reps = 8, at = 7),
        ))
        assertThat(lines.map { it.shortName }).containsExactly("обычная постановка", "узкая постановка").inOrder()
        assertThat(lines[1].sets).containsExactly(12, 6).inOrder()
        assertThat(lines[1].clean).isEqualTo(12)
        assertThat(pushUpShortName("Отжимания от высокой опоры")).isEqualTo("от высокой опоры")
        assertThat(pushUpShortName("Мои отжимания")).isEqualTo("мои отжимания")
    }

    @Test fun dailyCleanTotalsCountOnlyCleanPushUpsOfTheProfile() {
        val other = entry(standard, reps = 50).copy(id = 500, athleteId = 2L, clientToken = "other")
        val totals = DailyPushUps.dailyCleanTotals(definitions, entries + other +
            entry(standard, reps = 5, clean = false) + entry(standard, reps = 7).copy(epochDay = day - 1), 1L)
        assertThat(totals).containsExactly(day, 30L, day - 1, 7L)
    }

    @Test fun dialSpanIsAMinuteForShortGoalsAndGrowsForLongOnes() {
        assertThat(dialSpanSeconds(null)).isEqualTo(60.0)
        assertThat(dialSpanSeconds(35.0)).isEqualTo(60.0)
        assertThat(dialSpanSeconds(60.0)).isEqualTo(90.0)
        assertThat(dialSpanSeconds(90.0)).isEqualTo(120.0)
    }
}
