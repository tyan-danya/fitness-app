package com.dtyan.fitdiary.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MeasurementsTest {

    @Test
    fun parseAndEncodeSet_roundTrip_ignoresUnknown() {
        val encoded = MeasurementType.encodeSet(setOf(MeasurementType.WAIST, MeasurementType.BICEPS_R, MeasurementType.NECK))
        // Порядок — как в enum, независимо от порядка во множестве
        assertThat(encoded).isEqualTo("NECK,WAIST,BICEPS_R")
        assertThat(MeasurementType.parseSet(encoded)).containsExactly(MeasurementType.NECK, MeasurementType.WAIST, MeasurementType.BICEPS_R)
        assertThat(MeasurementType.parseSet("WAIST, FOO ,")).containsExactly(MeasurementType.WAIST)
        assertThat(MeasurementType.parseSet(null)).isEmpty()
        assertThat(MeasurementType.parseSet("")).isEmpty()
    }

    @Test
    fun valueValidation_rejectsTypos() {
        assertThat(isMeasurementValueValid(92.5)).isTrue()
        assertThat(isMeasurementValueValid(9.0)).isFalse()
        assertThat(isMeasurementValueValid(850.0)).isFalse()
    }

    @Test
    fun reminderDue_countsFromLastMeasurement_orFromEnableDay() {
        val today = 20_000L
        // Замер 3 дня назад при пороге 3 — пора
        assertThat(measurementReminderDue(lastEpochDay = today - 3, sinceEpochDay = today - 30, todayEpochDay = today, thresholdDays = 3)).isTrue()
        // 2 дня назад — рано
        assertThat(measurementReminderDue(lastEpochDay = today - 2, sinceEpochDay = today - 30, todayEpochDay = today, thresholdDays = 3)).isFalse()
        // Замеров не было: считаем от дня включения напоминания
        assertThat(measurementReminderDue(lastEpochDay = null, sinceEpochDay = today - 3, todayEpochDay = today, thresholdDays = 3)).isTrue()
        assertThat(measurementReminderDue(lastEpochDay = null, sinceEpochDay = today - 1, todayEpochDay = today, thresholdDays = 3)).isFalse()
        // Нет ни замеров, ни точки отсчёта — напоминать нечего
        assertThat(measurementReminderDue(lastEpochDay = null, sinceEpochDay = null, todayEpochDay = today, thresholdDays = 3)).isFalse()
    }
}
