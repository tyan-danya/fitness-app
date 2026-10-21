package com.dtyan.fitdiary.domain

import com.dtyan.fitdiary.data.db.ActivityDefinition
import com.dtyan.fitdiary.data.db.ActivityEntry
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DailyPushUpsTest {
    private val today = 20_000L

    private fun definition(id: Long = 1L, key: String = "pushup_standard", owner: Long = 1L) =
        ActivityDefinition(id, owner, key, "Synthetic activity", ActivityKind.REPS, ActivityMetric.REPS, "[]")

    private fun entry(id: Long = 1L, activityId: Long = 1L, reps: Int? = 10,
        owner: Long = 1L, day: Long = today, clean: Boolean = true) =
        ActivityEntry(id, owner, activityId, day, timestamp = 1L, reps = reps, isClean = clean, clientToken = "entry-$id")

    @Test fun exactlyKnownRepetitionVariantsCountIncludingArchivedDefinitions() {
        val keys = listOf("pushup_standard", "pushup_narrow", "pushup_wide", "pushup_knees",
            "pushup_incline", "pushup_low", "pushup_high")
        val definitions = keys.mapIndexed { index, key -> definition(index + 1L, key).copy(isArchived = index == 1) }
        val entries = definitions.map { entry(it.id, it.id) }
        definitions.forEach { assertThat(DailyPushUps.isPushUp(it)).isTrue() }
        val progress = DailyPushUps.calculate(definitions, entries, 1, today, 100)
        assertThat(progress.cleanReps).isEqualTo(70L)
        assertThat(progress.totalReps).isEqualTo(70L)
        assertThat(progress.setCount).isEqualTo(7)
        assertThat(progress.target).isEqualTo(100)
        assertThat(progress.remaining).isEqualTo(30L)
        assertThat(progress.reached).isFalse()
        assertThat(progress.fraction).isWithin(0.0001f).of(0.7f)
    }

    @Test fun dipsWalkingUnknownPrefixesAndWrongKindNeverCount() {
        val excluded = listOf("dips", "dip_assisted", "bench_dip_reverse", "walking", "pushup_custom",
            "pushup_standard_extra", "PUSHUP_STANDARD", "pushup_standard ")
            .mapIndexed { index, key -> definition(index + 1L, key) } +
            definition(90, "pushup_standard").copy(kind = ActivityKind.DURATION) +
            definition(91, "pushup_wide").copy(kind = ActivityKind.WALK)
        excluded.forEach { assertThat(DailyPushUps.isPushUp(it)).isFalse() }
        val progress = DailyPushUps.calculate(excluded, excluded.map { entry(it.id, it.id) }, 1, today, 10)
        assertThat(progress.cleanReps).isEqualTo(0L)
        assertThat(progress.totalReps).isEqualTo(0L)
        assertThat(progress.setCount).isEqualTo(0)
        assertThat(progress.remaining).isEqualTo(10L)
        assertThat(progress.fraction).isEqualTo(0f)
    }

    @Test fun onlySelectedDayAndBothDefinitionAndEntryOwnerCount() {
        val definitions = listOf(definition(), definition(2, owner = 2))
        val entries = listOf(
            entry(1, reps = 12),
            entry(2, reps = 50, day = today - 1),
            entry(3, reps = 50, day = today + 1),
            entry(4, reps = 50, owner = 2), // Cross-owner association must never leak a friend's repetitions.
            entry(5, activityId = 2, reps = 50),
            entry(6, activityId = 2, reps = 17, owner = 2),
            entry(7, activityId = 999, reps = 50),
        )
        val first = DailyPushUps.calculate(definitions, entries, 1, today, 100)
        val friend = DailyPushUps.calculate(definitions, entries, 2, today, 100)
        assertThat(first.totalReps).isEqualTo(12L)
        assertThat(first.setCount).isEqualTo(1)
        assertThat(friend.totalReps).isEqualTo(17L)
        assertThat(friend.setCount).isEqualTo(1)
    }

    @Test fun dirtySetsStayInVolumeButDoNotCompleteGoalAndInvalidRepetitionsAreIgnored() {
        val entries = listOf(entry(1, reps = 8), entry(2, reps = 12, clean = false),
            entry(3, reps = 0), entry(4, reps = -4), entry(5, reps = null))
        val progress = DailyPushUps.calculate(listOf(definition()), entries, 1, today, 10)
        assertThat(progress.cleanReps).isEqualTo(8L)
        assertThat(progress.totalReps).isEqualTo(20L)
        assertThat(progress.setCount).isEqualTo(2)
        assertThat(progress.remaining).isEqualTo(2L)
        assertThat(progress.reached).isFalse()
        assertThat(progress.fraction).isWithin(0.0001f).of(0.8f)
    }

    @Test fun editedAndDeletedEntriesRecomputeProgressWithoutAccumulatingOldValues() {
        val definitions = listOf(definition())
        val original = listOf(entry(1, reps = 10), entry(2, reps = 15))
        val completed = DailyPushUps.calculate(definitions, original, 1, today, 20)
        assertThat(completed.cleanReps).isEqualTo(25L)
        assertThat(completed.reached).isTrue()
        assertThat(completed.fraction).isEqualTo(1f)
        assertThat(completed.remaining).isEqualTo(0L)
        val edited = listOf(original[0], original[1].copy(reps = 4, isClean = false))
        assertThat(DailyPushUps.calculate(definitions, edited, 1, today, 20).cleanReps).isEqualTo(10L)
        assertThat(DailyPushUps.calculate(definitions, edited, 1, today, 20).totalReps).isEqualTo(14L)
        val deleted = DailyPushUps.calculate(definitions, edited.dropLast(1), 1, today, 20)
        assertThat(deleted.totalReps).isEqualTo(10L)
        assertThat(deleted.setCount).isEqualTo(1)
        assertThat(deleted.reached).isFalse()
        assertThat(deleted.remaining).isEqualTo(10L)
    }

    @Test fun noGoalStillShowsVolumeAndChangingGoalNeverChangesRecordedTotals() {
        val definitions = listOf(definition())
        val entries = listOf(entry(reps = 12))
        val withoutGoal = DailyPushUps.calculate(definitions, entries, 1, today, null)
        assertThat(withoutGoal.cleanReps).isEqualTo(12L)
        assertThat(withoutGoal.target).isNull()
        assertThat(withoutGoal.remaining).isNull()
        assertThat(withoutGoal.reached).isFalse()
        assertThat(withoutGoal.fraction).isEqualTo(0f)
        val lowered = DailyPushUps.calculate(definitions, entries, 1, today, 10)
        val raised = DailyPushUps.calculate(definitions, entries, 1, today, 30)
        assertThat(lowered.totalReps).isEqualTo(raised.totalReps)
        assertThat(lowered.reached).isTrue()
        assertThat(raised.reached).isFalse()
        assertThat(raised.remaining).isEqualTo(18L)
    }

    @Test fun manyValidSetsSumBeyondIntRangeWithoutOverflow() {
        val entries = List(30_000) { entry(it + 1L, reps = 100_000, clean = it < 25_000) }
        val progress = DailyPushUps.calculate(listOf(definition()), entries, 1, today, 100_000)
        assertThat(progress.totalReps).isEqualTo(3_000_000_000L)
        assertThat(progress.cleanReps).isEqualTo(2_500_000_000L)
        assertThat(progress.setCount).isEqualTo(30_000)
        assertThat(progress.reached).isTrue()
        assertThat(progress.remaining).isEqualTo(0L)
        assertThat(progress.fraction).isEqualTo(1f)
    }

    @Test fun invalidTargetOrOwnerIsRejectedAndSupportedBoundsWork() {
        for (target in listOf(-1, 0, 100_001, Int.MAX_VALUE)) {
            assertThat(runCatching { DailyPushUps.calculate(emptyList(), emptyList(), 1, today, target) }.isFailure).isTrue()
        }
        assertThat(runCatching { DailyPushUps.calculate(emptyList(), emptyList(), 0, today, 10) }.isFailure).isTrue()
        for (target in listOf(1, 100_000)) {
            val progress = DailyPushUps.calculate(emptyList(), emptyList(), 1, today, target)
            assertThat(progress.remaining).isEqualTo(target.toLong())
            assertThat(progress.reached).isFalse()
        }
    }
}
