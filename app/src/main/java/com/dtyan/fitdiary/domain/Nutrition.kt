package com.dtyan.fitdiary.domain

import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Тип приёма пищи. Определяется автоматически по времени добавления,
 * но пользователь может перенести приём в другую группу вручную.
 * Хранится в БД по имени константы (Room конвертирует enum в TEXT).
 */
enum class MealType(val title: String, val emoji: String) {
    BREAKFAST("Завтрак", "🌅"),
    LUNCH("Обед", "☀️"),
    DINNER("Ужин", "🌙"),
    SNACK("Перекус", "🍎");

    companion object {
        /**
         * Автоопределение по часу суток:
         * 04:00–10:59 — завтрак, 11:00–15:59 — обед, 16:00–21:59 — ужин,
         * 22:00–03:59 — перекус (поздний).
         */
        fun forHour(hour: Int): MealType = when (hour) {
            in 4..10 -> BREAKFAST
            in 11..15 -> LUNCH
            in 16..21 -> DINNER
            else -> SNACK
        }

        fun forTimestamp(millis: Long, zone: ZoneId = ZoneId.systemDefault()): MealType =
            forHour(Instant.ofEpochMilli(millis).atZone(zone).hour)

        /** Порядок секций на экране: завтрак → обед → ужин → перекусы. */
        val ORDERED: List<MealType> = listOf(BREAKFAST, LUNCH, DINNER, SNACK)
    }
}

/** Пищевая ценность на 100 г продукта — так её печатают на упаковках. */
data class Per100(
    val kcal: Double,
    val proteinG: Double,
    val fatG: Double,
    val carbsG: Double,
)

/** Итоговое КБЖУ порции. */
data class MacroTotals(
    val calories: Int,
    val proteinG: Double,
    val fatG: Double,
    val carbsG: Double,
)

/** Пересчёт «на 100 г» → «на порцию весом [servingG] г». Калории округляются до целого. */
fun totalsFor(per100: Per100, servingG: Double): MacroTotals {
    val k = servingG / 100.0
    return MacroTotals(
        calories = (per100.kcal * k).roundToInt(),
        proteinG = round1(per100.proteinG * k),
        fatG = round1(per100.fatG * k),
        carbsG = round1(per100.carbsG * k),
    )
}

/** Обратный пересчёт: из итогов порции — значения на 100 г (для отображения/правки). */
fun per100For(totals: MacroTotals, servingG: Double): Per100? {
    if (servingG <= 0.0) return null
    val k = 100.0 / servingG
    return Per100(
        kcal = round1(totals.calories * k),
        proteinG = round1(totals.proteinG * k),
        fatG = round1(totals.fatG * k),
        carbsG = round1(totals.carbsG * k),
    )
}

private fun round1(value: Double): Double = (value * 10.0).roundToInt() / 10.0
