package com.dtyan.fitdiary.domain

import com.dtyan.fitdiary.data.db.WorkoutSet
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields

/** Чистые доменные расчёты — без Android-зависимостей, легко тестируются. */
object Calculations {

    /** Расчётный одноповторный максимум по формуле Эпли. */
    fun epley1Rm(weightKg: Double, reps: Int): Double {
        require(reps >= 0) { "reps must be >= 0" }
        if (reps == 0) return 0.0
        if (reps == 1) return weightKg
        return weightKg * (1 + reps / 30.0)
    }

    /** Тоннаж: суммарный поднятый вес (кг). */
    fun totalVolume(sets: List<WorkoutSet>): Double = sets.sumOf { it.weightKg * it.reps }

    /** Количество тренировок за последние [days] суток включительно. */
    fun workoutsInLastDays(startTimes: List<Long>, days: Int, now: Long): Int {
        val from = now - days.toLong() * 24 * 60 * 60 * 1000
        return startTimes.count { it in from..now }
    }

    /**
     * Недельный стрик: сколько ISO-недель подряд была хотя бы одна тренировка.
     * Текущая неделя без тренировки стрик не обнуляет (она ещё не закончилась).
     */
    fun weeklyStreak(
        startTimes: List<Long>,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
    ): Int {
        if (startTimes.isEmpty()) return 0
        val weekFields = WeekFields.ISO
        fun weekKey(date: LocalDate): Long =
            date.get(weekFields.weekBasedYear()) * 100L + date.get(weekFields.weekOfWeekBasedYear())

        val weeksWithWorkout = startTimes
            .map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            .map { weekKey(it) }
            .toSet()

        var cursor = today
        var streak = 0
        if (weekKey(cursor) !in weeksWithWorkout) {
            cursor = cursor.minusWeeks(1)
            if (weekKey(cursor) !in weeksWithWorkout) return 0
        }
        while (weekKey(cursor) in weeksWithWorkout) {
            streak++
            cursor = cursor.minusWeeks(1)
        }
        return streak
    }
}
