package com.dtyan.fitdiary.ui.home

import com.dtyan.fitdiary.data.db.ActivityPlan
import com.dtyan.fitdiary.data.db.WorkoutTemplate
import com.dtyan.fitdiary.export.examplePlan
import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/** День плана на главной: метка силовой ведёт к программе с той же буквой, отдых — ни к какой. */
class TodayPlanTest {
    private val templates = listOf(
        WorkoutTemplate(1, 1, "A — жим и вертикальная тяга"),
        WorkoutTemplate(2, 1, "B — тяга с опорой и лёгкие ноги"),
        WorkoutTemplate(3, 1, "C — контролируемый жим и тяга"),
        WorkoutTemplate(4, 1, "Верх тела"),
    )

    @Test fun labelsFromTheOwnersPlan() {
        assertThat(strengthLabel("A вечером или в другое удобное время: жим")).isEqualTo("A")
        assertThat(strengthLabel("B: тяга с опорой грудью")).isEqualTo("B")
        assertThat(strengthLabel("С: кириллическая буква")).isEqualTo("C")
        assertThat(strengthLabel("Отдых от силовой A/B/C.")).isNull()
        assertThat(strengthLabel("Без силовой тренировки")).isNull()
        assertThat(strengthLabel("Верх тела")).isNull()
    }

    @Test fun matchesExactlyOneTemplate() {
        assertThat(matchTemplate("C: контролируемый жим", templates)?.id).isEqualTo(3L)
        assertThat(matchTemplate("Отдых от силовой", templates)).isNull()
        assertThat(matchTemplate("A: жим", templates + WorkoutTemplate(5, 1, "A — запасная"))).isNull()
    }

    @Test fun picksTodaysDayOfTheNewestPlan() {
        val week = (1..7).map { day ->
            JsonObject(mapOf("dayOfWeek" to JsonPrimitive(day), "morning" to JsonPrimitive("Утро $day"),
                "strength" to JsonPrimitive(if (day == 5) "C: жим и тяга" else "Отдых от силовой"), "home" to JsonPrimitive("Дом $day")))
        }
        val document = JsonObject(examplePlan().toMutableMap().apply { put("week", JsonArray(week)) }).toString()
        val older = ActivityPlan(1, 1, "old", "Старый", examplePlan().toString())
        val newer = ActivityPlan(2, 1, "example-week", "Пример недели", document)
        val friday = LocalDate.of(2026, 10, 23)
        val plan = todayPlan(listOf(older, newer), templates, friday)!!
        assertThat(plan.dayOfWeek).isEqualTo(5)
        assertThat(plan.morning).isEqualTo("Утро 5")
        assertThat(plan.template?.name).isEqualTo("C — контролируемый жим и тяга")
        assertThat(todayPlan(listOf(newer), templates, friday.minusDays(1))!!.template).isNull()
        assertThat(todayPlan(emptyList(), templates, friday)).isNull()
        assertThat(todayPlan(listOf(ActivityPlan(3, 1, "bad", "Битый", "{}")), templates, friday)).isNull()
        assertThat(examplePlan()["week"]!!.jsonArray.first().jsonObject.keys).contains("strength")
    }
}
