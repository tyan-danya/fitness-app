package com.dtyan.fitdiary.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Реальные выгрузки владельца: вес → число подходов по упражнению (каталог общий для профилей). */
class WeightStepsTest {

    private val butterfly = mapOf(29.0 to 2, 34.0 to 5, 39.0 to 10, 43.0 to 1, 44.0 to 6, 46.0 to 1,
        49.0 to 11, 54.0 to 9, 59.0 to 4, 64.0 to 2, 69.0 to 2, 79.0 to 1)
    private val latPulldown = mapOf(20.0 to 1, 23.0 to 1, 24.0 to 2, 27.0 to 1, 28.0 to 3, 32.0 to 5,
        36.0 to 1, 39.0 to 3, 40.0 to 3, 44.0 to 2, 45.0 to 4, 48.0 to 1, 52.0 to 8, 56.0 to 2,
        59.0 to 4, 60.0 to 1, 64.0 to 1, 68.0 to 1)
    private val tricepsPushdown = mapOf(14.0 to 2, 18.0 to 14, 23.0 to 18, 27.0 to 6)
    private val lowerTriceps = mapOf(12.0 to 3, 14.0 to 2, 16.0 to 3, 18.0 to 2, 20.0 to 3, 22.0 to 2)
    private val chestPress = mapOf(25.0 to 3, 30.0 to 1, 35.0 to 3, 40.0 to 1, 45.0 to 1, 55.0 to 1)
    private val benchPress = mapOf(40.0 to 13, 50.0 to 4, 55.0 to 1, 60.0 to 12, 70.0 to 12, 75.0 to 1,
        80.0 to 8, 85.0 to 1, 87.5 to 1, 90.0 to 1)
    private val doubleFly = mapOf(12.0 to 2, 16.0 to 1, 20.0 to 1, 24.0 to 1, 28.0 to 1)

    @Test fun butterfly_step5_onGridFrom4() {
        val guess = WeightSteps.guess(butterfly)!!
        assertThat(guess.step).isEqualTo(5.0)
        assertThat(guess.base).isEqualTo(4.0)
        assertThat(guess.coverage).isWithin(1e-9).of(52.0 / 54.0)
    }

    @Test fun latPulldown_mixedStacks_noConfidentStep() {
        assertThat(WeightSteps.guess(latPulldown)).isNull()
    }

    @Test fun roundedPoundStack_noStep() {
        assertThat(WeightSteps.guess(tricepsPushdown)).isNull()
    }

    @Test fun lowerTriceps_step2_base0() {
        val guess = WeightSteps.guess(lowerTriceps)!!
        assertThat(guess.step).isEqualTo(2.0)
        assertThat(guess.base).isEqualTo(0.0)
        assertThat(guess.coverage).isEqualTo(1.0)
    }

    @Test fun chestPress_step5_not10() {
        val guess = WeightSteps.guess(chestPress)!!
        assertThat(guess.step).isEqualTo(5.0)
        assertThat(guess.base).isEqualTo(0.0)
    }

    @Test fun benchPress_step5_despiteSingleHalfPlate() {
        val guess = WeightSteps.guess(benchPress)!!
        assertThat(guess.step).isEqualTo(5.0)
        assertThat(guess.base).isEqualTo(0.0)
        assertThat(guess.coverage).isWithin(1e-9).of(53.0 / 54.0)
    }

    @Test fun doubleFly_step4() {
        val guess = WeightSteps.guess(doubleFly)!!
        assertThat(guess.step).isEqualTo(4.0)
        assertThat(guess.base).isEqualTo(0.0)
    }

    @Test fun tooLittleData_null() {
        assertThat(WeightSteps.guess(emptyMap())).isNull()
        assertThat(WeightSteps.guess(mapOf(7.0 to 6))).isNull()
        assertThat(WeightSteps.guess(mapOf(10.0 to 5, 15.0 to 5))).isNull()
        assertThat(WeightSteps.guess(mapOf(10.0 to 1, 15.0 to 2, 20.0 to 2))).isNull() // 5 подходов
        // Нулевой и отрицательный вес не считаются данными.
        assertThat(WeightSteps.guess(mapOf(0.0 to 10, 10.0 to 3, 15.0 to 3))).isNull()
    }

    @Test fun base_isMostCommonResidue_forAnyStep() {
        assertThat(WeightSteps.base(butterfly, 5.0)).isEqualTo(4.0)
        assertThat(WeightSteps.base(butterfly, 2.5)).isEqualTo(1.5)
        assertThat(WeightSteps.base(benchPress, 2.5)).isEqualTo(0.0)
        assertThat(WeightSteps.base(emptyMap(), 5.0)).isEqualTo(0.0)
        assertThat(WeightSteps.base(mapOf(7.0 to 6), 5.0)).isEqualTo(2.0)
    }

    @Test fun bump_onGrid_movesByStep() {
        assertThat(WeightSteps.bump(29.0, 5.0, 4.0, up = true)).isEqualTo(34.0)
        assertThat(WeightSteps.bump(29.0, 5.0, 4.0, up = false)).isEqualTo(24.0)
        assertThat(WeightSteps.bump(62.5, 2.5, 0.0, up = true)).isEqualTo(65.0)
        assertThat(WeightSteps.bump(20.0, 2.5, 0.0, up = false)).isEqualTo(17.5)
    }

    @Test fun bump_offGrid_snapsToNeighbour() {
        assertThat(WeightSteps.bump(43.0, 5.0, 4.0, up = true)).isEqualTo(44.0)
        assertThat(WeightSteps.bump(43.0, 5.0, 4.0, up = false)).isEqualTo(39.0)
        assertThat(WeightSteps.bump(0.0, 5.0, 4.0, up = true)).isEqualTo(4.0)
        assertThat(WeightSteps.bump(20.0, 5.0, 4.0, up = true)).isEqualTo(24.0)
    }

    @Test fun bump_neverBelowZero_andRoundsTo3Decimals() {
        assertThat(WeightSteps.bump(0.0, 5.0, 4.0, up = false)).isEqualTo(0.0)
        assertThat(WeightSteps.bump(0.0, 2.5, 0.0, up = false)).isEqualTo(0.0)
        assertThat(WeightSteps.bump(1.0, 2.5, 0.0, up = false)).isEqualTo(0.0)
        assertThat(WeightSteps.bump(4.0, 5.0, 4.0, up = false)).isEqualTo(0.0)
        assertThat(WeightSteps.bump(0.1 + 0.2, 0.1, 0.0, up = true)).isEqualTo(0.4)
        assertThat(WeightSteps.bump(20.0, 1.25, 0.0, up = true)).isEqualTo(21.25)
    }
}
