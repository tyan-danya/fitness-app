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

    @Test fun sevenCompletedDaysStayCurrentUntilTodayEndsAndExtendWhenTodaysSumReachesTarget() {
        val definitions = listOf(definition(), definition(2, "pushup_narrow"))
        val history = (1..7).flatMap { daysAgo ->
            listOf(entry(daysAgo * 10L, reps = 40, day = today - daysAgo),
                entry(daysAgo * 10L + 1, activityId = 2, reps = 60, day = today - daysAgo))
        }
        val partialToday = history + entry(100, reps = 15)
        assertThat(DailyPushUps.streak(definitions, history, 1, today, 100))
            .isEqualTo(DailyPushUpStreak(100, currentDays = 7, bestDays = 7, todayReached = false))
        assertThat(DailyPushUps.streak(definitions, partialToday, 1, today, 100))
            .isEqualTo(DailyPushUpStreak(100, currentDays = 7, bestDays = 7, todayReached = false))
        val completedToday = partialToday + entry(101, activityId = 2, reps = 85)
        assertThat(DailyPushUps.streak(definitions, completedToday.reversed(), 1, today, 100))
            .isEqualTo(DailyPushUpStreak(100, currentDays = 8, bestDays = 8, todayReached = true))
    }

    @Test fun missedYesterdayEndsCurrentRunButPreservesBestAndFutureDaysCannotExtendIt() {
        val definitions = listOf(definition())
        val oldRun = (2..5).map { daysAgo -> entry(daysAgo.toLong(), reps = 100, day = today - daysAgo) }
        val future = (1..10).map { daysAhead -> entry(daysAhead + 100L, reps = 100, day = today + daysAhead) }
        val missedDay = oldRun + entry(20, reps = 99, day = today - 1) + future
        assertThat(DailyPushUps.streak(definitions, missedDay, 1, today, 100))
            .isEqualTo(DailyPushUpStreak(100, currentDays = 0, bestDays = 4, todayReached = false))
        assertThat(DailyPushUps.streak(definitions, missedDay + entry(21, reps = 100), 1, today, 100))
            .isEqualTo(DailyPushUpStreak(100, currentDays = 1, bestDays = 4, todayReached = true))
    }

    @Test fun streakUsesExactVariantsArchivedHistoryCleanPositiveSetsAndBothOwners() {
        val variants = listOf("pushup_standard", "pushup_narrow", "pushup_wide", "pushup_knees",
            "pushup_incline", "pushup_low", "pushup_high")
            .mapIndexed { index, key -> definition(index + 1L, key).copy(isArchived = index == 2) }
        val definitions = variants + listOf(
            definition(20, "pushup_standard", owner = 2),
            definition(21, "dip_assisted"),
            definition(22, "bench_dip_reverse"),
            definition(23, "pushup_custom"),
            definition(24, "pushup_wide").copy(kind = ActivityKind.DURATION),
            definition(25, "walking").copy(kind = ActivityKind.WALK),
        )
        val validToday = variants.map { entry(it.id, it.id, reps = 10) }
        val excludedYesterday = listOf(
            entry(30, reps = 100, day = today - 1, clean = false),
            entry(31, activityId = 20, reps = 100, day = today - 1),
            entry(32, reps = 100, owner = 2, day = today - 1),
            entry(33, activityId = 999, reps = 100, day = today - 1),
            entry(34, reps = 0, day = today - 1),
            entry(35, reps = -100, day = today - 1),
            entry(36, reps = null, day = today - 1),
        ) + (21L..25L).map { entry(it + 30, it, reps = 100, day = today - 1) }
        val friendHistory = listOf(entry(70, activityId = 20, reps = 100, owner = 2, day = today - 1),
            entry(71, activityId = 20, reps = 100, owner = 2, day = today - 2))
        val entries = validToday + excludedYesterday + friendHistory
        assertThat(DailyPushUps.streak(definitions, entries, 1, today, 70))
            .isEqualTo(DailyPushUpStreak(70, currentDays = 1, bestDays = 1, todayReached = true))
        assertThat(DailyPushUps.streak(definitions, entries, 1, today, 100))
            .isEqualTo(DailyPushUpStreak(100, currentDays = 0, bestDays = 0, todayReached = false))
        assertThat(DailyPushUps.streak(definitions, entries, 2, today, 100))
            .isEqualTo(DailyPushUpStreak(100, currentDays = 2, bestDays = 2, todayReached = false))
    }

    @Test fun streakRecalculatesAfterEntryEditDeletionAndDifferentTarget() {
        val definitions = listOf(definition())
        val original = listOf(entry(1, reps = 100, day = today - 2),
            entry(2, reps = 100, day = today - 1), entry(3, reps = 100))
        assertThat(DailyPushUps.streak(definitions, original, 1, today, 100))
            .isEqualTo(DailyPushUpStreak(100, currentDays = 3, bestDays = 3, todayReached = true))
        val edited = original.map { if (it.id == 2L) it.copy(reps = 80) else it }
        assertThat(DailyPushUps.streak(definitions, edited, 1, today, 100))
            .isEqualTo(DailyPushUpStreak(100, currentDays = 1, bestDays = 1, todayReached = true))
        assertThat(DailyPushUps.streak(definitions, edited, 1, today, 80))
            .isEqualTo(DailyPushUpStreak(80, currentDays = 3, bestDays = 3, todayReached = true))
        val deletedToday = edited.filterNot { it.id == 3L }
        assertThat(DailyPushUps.streak(definitions, deletedToday, 1, today, 100))
            .isEqualTo(DailyPushUpStreak(100, currentDays = 0, bestDays = 1, todayReached = false))
        assertThat(DailyPushUps.streak(definitions, deletedToday, 1, today, 80))
            .isEqualTo(DailyPushUpStreak(80, currentDays = 2, bestDays = 2, todayReached = false))
        assertThat(DailyPushUps.streak(definitions, original, 1, today, 101))
            .isEqualTo(DailyPushUpStreak(101, currentDays = 0, bestDays = 0, todayReached = false))
    }

    @Test fun streakUsesLongDailySumsBeyondIntRange() {
        val entries = List(30_000) { entry(it + 1L, reps = 100_000) } + entry(30_001, reps = 100_000, day = today - 1)
        assertThat(DailyPushUps.streak(listOf(definition()), entries, 1, today, 100_000))
            .isEqualTo(DailyPushUpStreak(100_000, currentDays = 2, bestDays = 2, todayReached = true))
    }

    @Test fun streakHandlesEmptyHistoryInvalidInputsAndCalendarBoundaryWithoutWrapping() {
        assertThat(DailyPushUps.streak(emptyList(), emptyList(), 1, today, 1))
            .isEqualTo(DailyPushUpStreak(1, currentDays = 0, bestDays = 0, todayReached = false))
        for (target in listOf(-1, 0, 100_001, Int.MAX_VALUE)) {
            assertThat(runCatching { DailyPushUps.streak(emptyList(), emptyList(), 1, today, target) }.isFailure).isTrue()
        }
        assertThat(runCatching { DailyPushUps.streak(emptyList(), emptyList(), 0, today, 10) }.isFailure).isTrue()
        val extremeDates = listOf(entry(1, day = Long.MIN_VALUE), entry(2, day = Long.MAX_VALUE))
        assertThat(DailyPushUps.streak(listOf(definition()), extremeDates, 1, Long.MAX_VALUE, 10))
            .isEqualTo(DailyPushUpStreak(10, currentDays = 1, bestDays = 1, todayReached = true))
        assertThat(DailyPushUps.streak(listOf(definition()), extremeDates, 1, Long.MIN_VALUE, 100))
            .isEqualTo(DailyPushUpStreak(100, currentDays = 0, bestDays = 0, todayReached = false))
    }
}
