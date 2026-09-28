package com.dtyan.fitdiary.domain

/**
 * Зоны замеров тела (сантиметровой лентой). Пользователь выбирает, что отслеживать:
 * при похудении часто хватает талии, при наборе — руки, грудь, бёдра.
 * Хранится в БД по имени константы.
 */
enum class MeasurementType(val title: String, val hint: String, val emoji: String) {
    NECK("Шея", "под кадыком", "🧣"),
    SHOULDERS("Плечи", "по самым широким точкам", "🤷"),
    CHEST("Грудь", "по линии сосков, на выдохе", "🫁"),
    WAIST("Талия", "самое узкое место, на выдохе", "📏"),
    BELLY("Живот", "по пупку", "🫃"),
    HIPS("Бёдра (ягодицы)", "по самой широкой части", "🍑"),
    BICEPS_L("Бицепс левый", "напряжённый, в самой широкой части", "💪"),
    BICEPS_R("Бицепс правый", "напряжённый, в самой широкой части", "💪"),
    FOREARM_L("Предплечье левое", "самое широкое место", "🦾"),
    FOREARM_R("Предплечье правое", "самое широкое место", "🦾"),
    THIGH_L("Бедро левое", "верхняя треть, стоя", "🦵"),
    THIGH_R("Бедро правое", "верхняя треть, стоя", "🦵"),
    CALF_L("Голень левая", "самое широкое место икры", "🦶"),
    CALF_R("Голень правая", "самое широкое место икры", "🦶");

    companion object {
        /** Набор по умолчанию для нового пользователя. */
        val DEFAULT_TRACKED: Set<MeasurementType> = setOf(WAIST, CHEST, HIPS, BICEPS_R, THIGH_R)

        /** Разбор строки настроек «WAIST,CHEST»; неизвестные имена пропускаются. */
        fun parseSet(raw: String?): Set<MeasurementType> =
            raw.orEmpty().split(',').mapNotNull { name -> entries.firstOrNull { it.name == name.trim() } }.toSet()

        fun encodeSet(set: Set<MeasurementType>): String = entries.filter { it in set }.joinToString(",") { it.name }
    }
}

/** Границы разумных значений, см — защита от опечаток вроде «850». */
const val MEASUREMENT_MIN_CM = 10.0
const val MEASUREMENT_MAX_CM = 250.0

fun isMeasurementValueValid(cm: Double): Boolean = cm in MEASUREMENT_MIN_CM..MEASUREMENT_MAX_CM

/**
 * Пора ли напомнить о замерах: с последнего замера (или с момента включения напоминания,
 * если замеров ещё не было) прошло не меньше [thresholdDays] полных дней.
 * lastEpochDay == null и sinceEpochDay == null — напоминать нечего.
 */
fun measurementReminderDue(
    lastEpochDay: Long?,
    sinceEpochDay: Long?,
    todayEpochDay: Long,
    thresholdDays: Int,
): Boolean {
    val anchor = lastEpochDay ?: sinceEpochDay ?: return false
    return todayEpochDay - anchor >= thresholdDays
}
