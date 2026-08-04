package com.dtyan.fitdiary.ui.common

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Форматирование чисел/дат для UI. Всё — в русской локали. */
object Format {

    private val RU = Locale("ru")
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", RU)
    private val DATE_SHORT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM", RU)
    private val DATE_FULL: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", RU)
    private val DATE_WITH_DOW: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM", RU)

    fun local(millis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone)

    fun epochDayOf(millis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        local(millis, zone).toLocalDate().toEpochDay()

    /** «62,5» / «100» — без лишних нулей, запятая как разделитель. */
    fun weight(kg: Double): String =
        if (kg % 1.0 == 0.0) kg.toLong().toString()
        else String.format(RU, "%.1f", kg).trimEnd('0').trimEnd(',')

    /** Тоннаж: «850 кг» / «12,4 т». */
    fun volume(kg: Double): String =
        if (kg >= 1000) String.format(RU, "%.1f т", kg / 1000.0).replace(",0 т", " т")
        else "${kg.toLong()} кг"

    /** «1:23:45» / «23:45» — живой таймер тренировки. */
    fun durationClock(millis: Long): String {
        val totalSec = (millis / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(RU, "%d:%02d:%02d", h, m, s)
        else String.format(RU, "%d:%02d", m, s)
    }

    /** «1 ч 23 мин» / «45 мин» — длительность в сводках. */
    fun durationHuman(millis: Long): String {
        val totalMin = (millis / 60000).coerceAtLeast(0)
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h > 0 && m > 0 -> "$h ч $m мин"
            h > 0 -> "$h ч"
            else -> "$m мин"
        }
    }

    fun time(millis: Long): String = local(millis).format(TIME)
    fun dateShort(date: LocalDate): String = date.format(DATE_SHORT)
    fun dateFull(date: LocalDate): String = date.format(DATE_FULL)
    fun dateWithDayOfWeek(date: LocalDate): String =
        date.format(DATE_WITH_DOW).replaceFirstChar { it.titlecase(RU) }
}
