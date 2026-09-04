package com.dtyan.fitdiary.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class NutritionTest {

    @Test
    fun mealType_forHour_boundaries() {
        assertThat(MealType.forHour(4)).isEqualTo(MealType.BREAKFAST)
        assertThat(MealType.forHour(10)).isEqualTo(MealType.BREAKFAST)
        assertThat(MealType.forHour(11)).isEqualTo(MealType.LUNCH)
        assertThat(MealType.forHour(15)).isEqualTo(MealType.LUNCH)
        assertThat(MealType.forHour(16)).isEqualTo(MealType.DINNER)
        assertThat(MealType.forHour(21)).isEqualTo(MealType.DINNER)
        assertThat(MealType.forHour(22)).isEqualTo(MealType.SNACK)
        assertThat(MealType.forHour(0)).isEqualTo(MealType.SNACK)
        assertThat(MealType.forHour(3)).isEqualTo(MealType.SNACK)
    }

    @Test
    fun mealType_forTimestamp_usesGivenZone() {
        val zone = ZoneId.of("Europe/Moscow")
        val breakfast = LocalDate.of(2026, 9, 4).atTime(8, 30).atZone(zone).toInstant().toEpochMilli()
        val dinner = LocalDate.of(2026, 9, 4).atTime(19, 0).atZone(zone).toInstant().toEpochMilli()
        assertThat(MealType.forTimestamp(breakfast, zone)).isEqualTo(MealType.BREAKFAST)
        assertThat(MealType.forTimestamp(dinner, zone)).isEqualTo(MealType.DINNER)
        // Та же секунда в другой зоне — другой час, другой тип
        assertThat(MealType.forTimestamp(dinner, ZoneId.of("Asia/Tokyo"))).isEqualTo(MealType.SNACK) // 01:00
    }

    @Test
    fun totalsFor_scalesPer100ByServing_roundsCaloriesAndTenths() {
        val per100 = Per100(kcal = 366.0, proteinG = 12.3, fatG = 6.1, carbsG = 61.8)

        val totals = totalsFor(per100, 130.0)

        assertThat(totals.calories).isEqualTo(476) // 475.8 → 476
        assertThat(totals.proteinG).isEqualTo(16.0) // 15.99 → 16.0
        assertThat(totals.fatG).isEqualTo(7.9) // 7.93 → 7.9
        assertThat(totals.carbsG).isEqualTo(80.3) // 80.34 → 80.3
    }

    @Test
    fun totalsFor_hundredGrams_isIdentity() {
        val per100 = Per100(250.0, 10.0, 5.0, 30.0)
        assertThat(totalsFor(per100, 100.0)).isEqualTo(MacroTotals(250, 10.0, 5.0, 30.0))
    }

    @Test
    fun per100For_invertsTotals_andRejectsZeroServing() {
        val totals = MacroTotals(calories = 455, proteinG = 16.3, fatG = 9.1, carbsG = 72.2)

        val per100 = per100For(totals, 130.0)!!

        assertThat(per100.kcal).isEqualTo(350.0)
        assertThat(per100.proteinG).isEqualTo(12.5)
        assertThat(per100.fatG).isEqualTo(7.0)
        assertThat(per100.carbsG).isEqualTo(55.5)
        assertThat(per100For(totals, 0.0)).isNull()
    }
}
