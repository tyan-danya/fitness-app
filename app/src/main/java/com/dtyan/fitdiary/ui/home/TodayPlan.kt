package com.dtyan.fitdiary.ui.home

import com.dtyan.fitdiary.data.db.ActivityPlan
import com.dtyan.fitdiary.data.db.WorkoutTemplate
import com.dtyan.fitdiary.export.PlanBundleExchange
import java.time.LocalDate

/** День плана недели на сегодня и, если силовая названа буквой программы, сама программа. */
data class TodayPlan(
    val planName: String,
    val dayOfWeek: Int,
    val morning: String,
    val strength: String,
    val home: String,
    val template: WorkoutTemplate?,
)

private val LOOKALIKES = mapOf('А' to 'A', 'В' to 'B', 'С' to 'C', 'Е' to 'E', 'К' to 'K', 'М' to 'M', 'Н' to 'H', 'О' to 'O', 'Р' to 'P', 'Т' to 'T', 'Х' to 'X')

/** «C: контролируемый жим…» и «A вечером…» дают метку C и A; «Отдых от силовой» метки не даёт. */
internal fun strengthLabel(strength: String): String? {
    val token = Regex("^\\s*([A-Za-zА-Яа-я0-9]{1,2})(?=[\\s:.—-])").find(strength)?.groupValues?.get(1) ?: return null
    return token.uppercase().map { LOOKALIKES[it] ?: it }.joinToString("")
}

/** Программа, чьё название начинается с той же метки: «C — контролируемый жим и тяга». Ровно одна, иначе null. */
internal fun matchTemplate(strength: String, templates: List<WorkoutTemplate>): WorkoutTemplate? {
    val label = strengthLabel(strength) ?: return null
    return templates.filter { template -> strengthLabel(template.name) == label }.singleOrNull()
}

/** Самый свежий план профиля, день недели сегодняшней даты; повреждённый документ просто не показывается. */
internal fun todayPlan(plans: List<ActivityPlan>, templates: List<WorkoutTemplate>, date: LocalDate): TodayPlan? {
    val plan = plans.maxByOrNull { it.id } ?: return null
    val parsed = runCatching { PlanBundleExchange.parse(plan.documentJson) }.getOrNull() ?: return null
    val day = parsed.week.firstOrNull { it.dayOfWeek == date.dayOfWeek.value } ?: return null
    return TodayPlan(parsed.name, day.dayOfWeek, day.morning, day.strength, day.home, matchTemplate(day.strength, templates))
}
