package com.dtyan.fitdiary.export

import com.dtyan.fitdiary.data.db.Meal
import com.dtyan.fitdiary.domain.MealType
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertThrows
import org.junit.Test

/** Чистые функции файл-обмена: сборка запроса и разбор ответа (без Android). */
class EstimateExchangeTest {

    private fun meal(id: Long, name: String, servingG: Double? = null, type: MealType = MealType.LUNCH) = Meal(
        id = id, epochDay = 20700, timestamp = 0L, name = name,
        calories = 0, proteinG = 0.0, fatG = 0.0, carbsG = 0.0,
        mealType = type, servingG = servingG, needsEstimate = true,
    )

    @Test
    fun buildRequestJson_hasFormatInstructionsAndNullPlaceholders() {
        val json = EstimateExchange.buildRequestJson(
            listOf(meal(7, "Плов с курицей", servingG = 300.0), meal(8, "Салат «Цезарь»", type = MealType.DINNER)),
        )

        val root = Json.parseToJsonElement(json).jsonObject
        assertThat(root["format"]!!.jsonPrimitive.content).isEqualTo(EstimateExchange.FORMAT)
        assertThat(root["instructions"]!!.jsonPrimitive.content).contains("100")
        val items = root["items"]!!.jsonArray
        assertThat(items).hasSize(2)
        val first = items[0].jsonObject
        assertThat(first["id"]!!.jsonPrimitive.content).isEqualTo("7")
        assertThat(first["date"]!!.jsonPrimitive.content).isEqualTo("2026-09-04")
        assertThat(first["meal"]!!.jsonPrimitive.content).isEqualTo("Обед")
        assertThat(first["name"]!!.jsonPrimitive.content).isEqualTo("Плов с курицей")
        assertThat(first["servingG"]!!.jsonPrimitive.content).isEqualTo("300.0")
        assertThat(first["kcalPer100"].toString()).isEqualTo("null")
        val second = items[1].jsonObject
        assertThat(second["servingG"].toString()).isEqualTo("null")
        assertThat(second["meal"]!!.jsonPrimitive.content).isEqualTo("Ужин")
    }

    @Test
    fun parseResponse_objectWithItems_readsValuesAndServing() {
        val text = """
            {"format":"fitdiary-estimate/1","items":[
              {"id":7,"name":"Плов","servingG":300,"kcalPer100":190.5,"proteinPer100":8,"fatPer100":7.2,"carbsPer100":24},
              {"id":8,"name":"Цезарь","servingG":null,"kcalPer100":null}
            ]}
        """.trimIndent()

        val updates = EstimateExchange.parseResponse(text)

        // Второй элемент без kcalPer100 пропущен
        assertThat(updates).hasSize(1)
        val u = updates.single()
        assertThat(u.mealId).isEqualTo(7L)
        assertThat(u.servingG).isEqualTo(300.0)
        assertThat(u.per100.kcal).isEqualTo(190.5)
        assertThat(u.per100.proteinG).isEqualTo(8.0)
        assertThat(u.per100.fatG).isEqualTo(7.2)
        assertThat(u.per100.carbsG).isEqualTo(24.0)
    }

    @Test
    fun parseResponse_bareArray_snakeCase_stringNumbers_bom() {
        val text = "﻿" + """
            [{"id":"12","kcal_per_100g":"120,5","protein_per_100g":3,"fat_per_100g":1,"carbs_per_100g":22,"serving_g":250}]
        """.trimIndent()

        val updates = EstimateExchange.parseResponse(text)

        assertThat(updates).hasSize(1)
        assertThat(updates[0].mealId).isEqualTo(12L)
        assertThat(updates[0].per100.kcal).isEqualTo(120.5)
        assertThat(updates[0].servingG).isEqualTo(250.0)
    }

    @Test
    fun parseResponse_negativeValuesClamped_zeroServingIgnored() {
        val updates = EstimateExchange.parseResponse(
            """[{"id":1,"kcalPer100":100,"proteinPer100":-5,"servingG":0}]""",
        )
        assertThat(updates.single().per100.proteinG).isEqualTo(0.0)
        assertThat(updates.single().servingG).isNull()
    }

    @Test
    fun parseResponse_invalidInput_throws() {
        assertThrows(IllegalArgumentException::class.java) { EstimateExchange.parseResponse("это не json") }
        assertThrows(IllegalArgumentException::class.java) { EstimateExchange.parseResponse("""{"foo":1}""") }
        assertThrows(IllegalArgumentException::class.java) { EstimateExchange.parseResponse("42") }
    }
}
