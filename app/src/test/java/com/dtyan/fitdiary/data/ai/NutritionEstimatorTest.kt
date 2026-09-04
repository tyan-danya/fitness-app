package com.dtyan.fitdiary.data.ai

import com.dtyan.fitdiary.data.SettingsStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertThrows
import org.junit.Test

/** Сборка запроса, разбор ответа и поведение при ошибках — на фальшивом транспорте, без сети. */
class NutritionEstimatorTest {

    private val configured = SettingsStore.Settings(
        aiBaseUrl = "https://llm.example/v1/",
        aiModel = "test-model",
        aiApiKey = "sk-test",
    )

    private class FakeTransport(private val responses: List<HttpResult>) : HttpTransport {
        val calls = mutableListOf<Triple<String, Map<String, String>, String>>()
        override fun post(url: String, headers: Map<String, String>, body: String): HttpResult {
            calls += Triple(url, headers, body)
            return responses[minOf(calls.size - 1, responses.lastIndex)]
        }
    }

    private fun okResponse(content: String): HttpResult = HttpResult(
        200,
        Json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            kotlinx.serialization.json.buildJsonObject {
                put(
                    "choices",
                    kotlinx.serialization.json.buildJsonArray {
                        add(
                            kotlinx.serialization.json.buildJsonObject {
                                put(
                                    "message",
                                    kotlinx.serialization.json.buildJsonObject {
                                        put("role", kotlinx.serialization.json.JsonPrimitive("assistant"))
                                        put("content", kotlinx.serialization.json.JsonPrimitive(content))
                                    },
                                )
                            },
                        )
                    },
                )
            },
        ),
    )

    // ---------- Сборка запроса ----------

    @Test
    fun buildRequestBody_strict_hasModelMessagesAndJsonSchema() {
        val body = Json.parseToJsonElement(
            NutritionEstimator.buildRequestBody("m1", " Плов ", 300.0, strictSchema = true),
        ).jsonObject

        assertThat(body["model"]!!.jsonPrimitive.content).isEqualTo("m1")
        val messages = body["messages"]!!.jsonArray
        assertThat(messages).hasSize(2)
        assertThat(messages[0].jsonObject["role"]!!.jsonPrimitive.content).isEqualTo("system")
        val user = messages[1].jsonObject["content"]!!.jsonPrimitive.content
        assertThat(user).contains("«Плов»")
        assertThat(user).contains("300 г")
        val rf = body["response_format"]!!.jsonObject
        assertThat(rf["type"]!!.jsonPrimitive.content).isEqualTo("json_schema")
        val schema = rf["json_schema"]!!.jsonObject["schema"]!!.jsonObject
        assertThat(schema["properties"]!!.jsonObject.keys)
            .containsExactly("kcal_per_100g", "protein_per_100g", "fat_per_100g", "carbs_per_100g", "serving_g", "note")
        // Температуру не задаём: новые модели принимают только значение по умолчанию
        assertThat(body.containsKey("temperature")).isFalse()
    }

    @Test
    fun buildRequestBody_noServing_asksForTypicalServing_jsonObjectMode() {
        val body = Json.parseToJsonElement(
            NutritionEstimator.buildRequestBody("m1", "Борщ", null, strictSchema = false),
        ).jsonObject
        val user = body["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonPrimitive.content
        assertThat(user).contains("неизвестен")
        assertThat(body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content).isEqualTo("json_object")
    }

    // ---------- Разбор ответа ----------

    @Test
    fun parseResponse_stringContent_withFences_andSnakeCaseKeys() {
        val content = "```json\n{\"kcal_per_100g\": 112.4, \"protein_per_100g\": 7.5, \"fat_per_100g\": 5.1, " +
            "\"carbs_per_100g\": 9.8, \"serving_g\": 350, \"note\": \"по Скурихину\"}\n```"

        val est = NutritionEstimator.parseResponse(okResponse(content).body)

        assertThat(est.per100.kcal).isEqualTo(112.4)
        assertThat(est.per100.proteinG).isEqualTo(7.5)
        assertThat(est.per100.fatG).isEqualTo(5.1)
        assertThat(est.per100.carbsG).isEqualTo(9.8)
        assertThat(est.servingG).isEqualTo(350.0)
        assertThat(est.note).isEqualTo("по Скурихину")
    }

    @Test
    fun parseResponse_arrayContentParts_nullServing() {
        val body = """{"choices":[{"message":{"role":"assistant","content":[
            {"type":"text","text":"{\"kcal_per_100g\":90,"},
            {"type":"text","text":"\"protein_per_100g\":3,\"fat_per_100g\":1,\"carbs_per_100g\":18,\"serving_g\":null,\"note\":\"\"}"}
        ]}}]}"""

        val est = NutritionEstimator.parseResponse(body)

        assertThat(est.per100.kcal).isEqualTo(90.0)
        assertThat(est.servingG).isNull()
        assertThat(est.note).isNull()
    }

    @Test
    fun parseResponse_missingCalories_orNotJson_throws() {
        assertThrows(EstimateException::class.java) {
            NutritionEstimator.parseResponse(okResponse("""{"protein_per_100g": 3}""").body)
        }
        assertThrows(EstimateException::class.java) { NutritionEstimator.parseResponse("<html>") }
        assertThrows(EstimateException::class.java) { NutritionEstimator.parseResponse("""{"choices":[]}""") }
    }

    // ---------- Сквозной вызов через транспорт ----------

    @Test
    fun estimate_sendsBearerToChatCompletions_parsesResult() = runTest {
        val transport = FakeTransport(listOf(okResponse("""{"kcal_per_100g":200,"protein_per_100g":10,"fat_per_100g":5,"carbs_per_100g":25,"serving_g":150,"note":"ok"}""")))
        val estimator = NutritionEstimator({ configured }, transport)

        val est = estimator.estimate("Творог", null)

        assertThat(transport.calls).hasSize(1)
        val (url, headers, body) = transport.calls.single()
        assertThat(url).isEqualTo("https://llm.example/v1/chat/completions") // хвостовой «/» не удваивается
        assertThat(headers["Authorization"]).isEqualTo("Bearer sk-test")
        assertThat(body).contains("\"model\":\"test-model\"")
        assertThat(est.per100.kcal).isEqualTo(200.0)
        assertThat(est.servingG).isEqualTo(150.0)
    }

    @Test
    fun estimate_on400_retriesInJsonObjectMode() = runTest {
        val transport = FakeTransport(
            listOf(
                HttpResult(400, """{"error":{"message":"response_format json_schema is not supported"}}"""),
                okResponse("""{"kcal_per_100g":50,"protein_per_100g":1,"fat_per_100g":0,"carbs_per_100g":12,"serving_g":200,"note":""}"""),
            ),
        )
        val estimator = NutritionEstimator({ configured }, transport)

        val est = estimator.estimate("Яблоко", 200.0)

        assertThat(transport.calls).hasSize(2)
        assertThat(transport.calls[0].third).contains("json_schema")
        assertThat(transport.calls[1].third).contains("\"type\":\"json_object\"")
        assertThat(transport.calls[1].third).doesNotContain("json_schema")
        assertThat(est.per100.kcal).isEqualTo(50.0)
    }

    @Test
    fun estimate_401_reportsBadKeyWithServerMessage() = runTest {
        val transport = FakeTransport(listOf(HttpResult(401, """{"error":{"message":"Incorrect API key provided"}}""")))
        val estimator = NutritionEstimator({ configured }, transport)

        val e = assertThrows(EstimateException::class.java) {
            kotlinx.coroutines.runBlocking { estimator.estimate("Плов", 300.0) }
        }
        assertThat(e.message).contains("Неверный API-ключ")
        assertThat(e.message).contains("Incorrect API key")
    }

    @Test
    fun estimate_notConfigured_failsBeforeNetwork() = runTest {
        val transport = FakeTransport(emptyList())
        val estimator = NutritionEstimator({ SettingsStore.Settings() }, transport)

        val e = assertThrows(EstimateException::class.java) {
            kotlinx.coroutines.runBlocking { estimator.estimate("Плов", 300.0) }
        }
        assertThat(e.message).contains("API-ключ")
        assertThat(transport.calls).isEmpty()
    }
}
