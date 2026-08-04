package com.dtyan.fitdiary.domain

import com.dtyan.fitdiary.data.db.WorkoutSet
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Чистые JUnit-тесты доменных расчётов — без Robolectric. */
class CalculationsTest {

    // ---------- epley1Rm ----------

    @Test
    fun epley1Rm_oneRep_returnsWeightItself() {
        assertThat(Calculations.epley1Rm(100.0, 1)).isEqualTo(100.0)
    }

    @Test
    fun epley1Rm_tenReps_appliesEpleyFormula() {
        assertThat(Calculations.epley1Rm(90.0, 10)).isWithin(1e-9).of(90.0 * (1 + 10 / 30.0))
    }

    @Test
    fun epley1Rm_zeroReps_returnsZero() {
        assertThat(Calculations.epley1Rm(100.0, 0)).isEqualTo(0.0)
    }

    @Test
    fun epley1Rm_negativeReps_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            Calculations.epley1Rm(100.0, -1)
        }
    }

    // ---------- totalVolume ----------

    private fun set(weight: Double, reps: Int) = WorkoutSet(
        workoutId = 1,
        exerciseId = 1,
        setIndex = 1,
        weightKg = weight,
        reps = reps,
        completedAt = 0,
    )

    @Test
    fun totalVolume_emptyList_isZero() {
        assertThat(Calculations.totalVolume(emptyList())).isEqualTo(0.0)
    }

    @Test
    fun totalVolume_sumsWeightTimesReps() {
        val sets = listOf(set(100.0, 5), set(60.0, 10), set(62.5, 8))
        assertThat(Calculations.totalVolume(sets))
            .isWithin(1e-9)
            .of(100.0 * 5 + 60.0 * 10 + 62.5 * 8)
    }

    // ---------- workoutsInLastDays ----------

    @Test
    fun workoutsInLastDays_intervalBoundariesInclusive() {
        val now = 1_800_000_000_000L
        val from = now - 7L * 24 * 60 * 60 * 1000
        val times = listOf(
            from - 1, // за границей — не считается
            from, // ровно на нижней границе — считается
            now - 1000, // внутри интервала
            now, // ровно сейчас — считается
            now + 1, // будущее — не считается
        )
        assertThat(Calculations.workoutsInLastDays(times, 7, now)).isEqualTo(3)
    }

    @Test
    fun workoutsInLastDays_empty_isZero() {
        assertThat(Calculations.workoutsInLastDays(emptyList(), 7, 1_800_000_000_000L)).isEqualTo(0)
    }

    // ---------- weeklyStreak ----------

    private val zone = ZoneId.of("Europe/Moscow")

    /** Суббота; её ISO-неделя — 27.07–02.08.2026. */
    private val today: LocalDate = LocalDate.of(2026, 8, 1)

    private fun ts(date: LocalDate): Long =
        date.atTime(LocalTime.of(10, 0)).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun weeklyStreak_empty_isZero() {
        assertThat(Calculations.weeklyStreak(emptyList(), zone, today)).isEqualTo(0)
    }

    @Test
    fun weeklyStreak_workoutInCurrentWeek_isOne() {
        val times = listOf(ts(LocalDate.of(2026, 7, 28))) // вторник текущей недели
        assertThat(Calculations.weeklyStreak(times, zone, today)).isAtLeast(1)
        assertThat(Calculations.weeklyStreak(times, zone, today)).isEqualTo(1)
    }

    @Test
    fun weeklyStreak_consecutiveWeeksCounted() {
        val times = listOf(
            ts(LocalDate.of(2026, 7, 28)), // текущая неделя (27.07–02.08)
            ts(LocalDate.of(2026, 7, 21)), // прошлая (20–26.07)
            ts(LocalDate.of(2026, 7, 15)), // позапрошлая (13–19.07)
        )
        assertThat(Calculations.weeklyStreak(times, zone, today)).isEqualTo(3)
    }

    @Test
    fun weeklyStreak_weekGapBreaksStreak() {
        val times = listOf(
            ts(LocalDate.of(2026, 7, 28)), // текущая неделя
            ts(LocalDate.of(2026, 7, 14)), // две недели назад; прошлая (20–26.07) пропущена
        )
        assertThat(Calculations.weeklyStreak(times, zone, today)).isEqualTo(1)
    }

    @Test
    fun weeklyStreak_currentWeekWithoutWorkout_previousWithWorkout_notReset() {
        val times = listOf(
            ts(LocalDate.of(2026, 7, 22)), // прошлая неделя (20–26.07)
            ts(LocalDate.of(2026, 7, 16)), // позапрошлая (13–19.07)
        )
        // текущая неделя ещё не закончилась — стрик не обнуляется
        assertThat(Calculations.weeklyStreak(times, zone, today)).isEqualTo(2)
    }

    @Test
    fun weeklyStreak_currentAndPreviousWeeksEmpty_isZero() {
        val times = listOf(ts(LocalDate.of(2026, 7, 14))) // только две недели назад
        assertThat(Calculations.weeklyStreak(times, zone, today)).isEqualTo(0)
    }
}
