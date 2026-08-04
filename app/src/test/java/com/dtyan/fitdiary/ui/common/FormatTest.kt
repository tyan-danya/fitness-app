package com.dtyan.fitdiary.ui.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Чистые JUnit-тесты форматирования — без Robolectric. */
class FormatTest {

    // ---------- weight ----------

    @Test
    fun weight_fractional_usesCommaSeparator() {
        assertThat(Format.weight(62.5)).isEqualTo("62,5")
    }

    @Test
    fun weight_whole_withoutDecimals() {
        assertThat(Format.weight(100.0)).isEqualTo("100")
    }

    // ---------- volume ----------

    @Test
    fun volume_below1000_inKilograms() {
        assertThat(Format.volume(850.0)).isEqualTo("850 кг")
    }

    @Test
    fun volume_above1000_inTonsWithComma() {
        assertThat(Format.volume(12400.0)).isEqualTo("12,4 т")
    }

    @Test
    fun volume_exactly1000_wholeTonWithoutFraction() {
        assertThat(Format.volume(1000.0)).isEqualTo("1 т")
    }

    // ---------- durationClock ----------

    @Test
    fun durationClock_underHour_minutesAndSeconds() {
        assertThat(Format.durationClock(65_000L)).isEqualTo("1:05")
    }

    @Test
    fun durationClock_overHour_hoursMinutesSeconds() {
        assertThat(Format.durationClock(3_725_000L)).isEqualTo("1:02:05")
    }

    // ---------- durationHuman ----------

    @Test
    fun durationHuman_minutesOnly() {
        assertThat(Format.durationHuman(45 * 60_000L)).isEqualTo("45 мин")
    }

    @Test
    fun durationHuman_wholeHour() {
        assertThat(Format.durationHuman(60 * 60_000L)).isEqualTo("1 ч")
    }

    @Test
    fun durationHuman_hoursAndMinutes() {
        assertThat(Format.durationHuman(83 * 60_000L)).isEqualTo("1 ч 23 мин")
    }
}
