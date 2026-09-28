package com.dtyan.fitdiary.data.ai

import com.dtyan.fitdiary.data.SettingsStore
import com.dtyan.fitdiary.domain.Per100
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Результат оценки: значения на 100 г и, если вес порции не был задан, — типичный вес порции. */
data class NutritionEstimate(
    val per100: Per100,
    val servingG: Double?,
    val note: String?,
)

/** Понятная пользователю ошибка расчёта (текст показывается в форме). */
class EstimateException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Транспорт HTTP POST — вынесен, чтобы тестировать сборку запроса и разбор ответа без сети. */
fun interface HttpTransport {
    fun post(url: String, headers: Map<String, String>, body: String): HttpResult
}

data class HttpResult(val code: Int, val body: String)

/** HttpURLConnection без сторонних зависимостей: одного POST в минуту хватает. */
class UrlConnectionTransport : HttpTransport {
    override fun post(url: String, headers: Map<String, String>, body: String): HttpResult {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 90_000
            conn.doOutput = true
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return HttpResult(code, text)
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * Оценка КБЖУ блюда через OpenAI-совместимый chat/completions.
 * Сначала просит строгий JSON по схеме (response_format = json_schema); если сервер
 * такой формат не принимает (HTTP 400) — повторяет в режиме json_object.
 */
class NutritionEstimator(
    private val settings: () -> SettingsStore.Settings,
    private val transport: HttpTransport = UrlConnectionTransport(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun estimate(name: String, servingG: Double?): NutritionEstimate = withContext(ioDispatcher) {
        val s = settings()
        if (!s.aiConfigured) throw EstimateException("Укажите API-ключ в настройках расчёта через ИИ")
        val url = s.aiBaseUrl.trimEnd('/') + "/chat/completions"
        val headers = mapOf(
            "Authorization" to "Bearer ${s.aiApiKey.trim()}",
            "Content-Type" to "application/json; charset=utf-8",
        )
        val first = post(url, headers, buildRequestBody(s.aiModel, name, servingG, strictSchema = true))
        val result = if (first.code == 400) {
            post(url, headers, buildRequestBody(s.aiModel, name, servingG, strictSchema = false))
        } else first
        if (result.code !in 200..299) throw EstimateException(describeHttpError(result))
        parseResponse(result.body)
    }

    private fun post(url: String, headers: Map<String, String>, body: String): HttpResult =
        try {
            transport.post(url, headers, body)
        } catch (e: IOException) {
            throw EstimateException("Нет соединения с сервером ИИ", e)
        }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        private const val SYSTEM_PROMPT =
            "Ты — справочник по пищевой ценности продуктов и блюд. По названию блюда оцени его " +
                "энергетическую ценность и макронутриенты НА 100 ГРАММ готового продукта, опираясь на " +
                "таблицы химического состава (Скурихин, USDA) и типичные рецепты. Если вес порции не " +
                "указан, оцени типичный вес одной порции в граммах. Отвечай только JSON без пояснений."

        /** Тело запроса chat/completions. strictSchema — просить json_schema, иначе json_object. */
        fun buildRequestBody(model: String, name: String, servingG: Double?, strictSchema: Boolean): String {
            val servingLine = if (servingG != null && servingG > 0) {
                "Вес порции: ${formatG(servingG)} г (в serving_g верни это же число)."
            } else {
                "Вес порции неизвестен — оцени типичный вес порции в граммах в поле serving_g."
            }
            val userPrompt = "Блюдо: «${name.trim()}». $servingLine Поля ответа: kcal_per_100g, " +
                "protein_per_100g, fat_per_100g, carbs_per_100g (граммы на 100 г), serving_g, " +
                "note (одна короткая фраза: на что опирался)."
            val body = buildJsonObject {
                put("model", model)
                putJsonArray("messages") {
                    add(buildJsonObject { put("role", "system"); put("content", SYSTEM_PROMPT) })
                    add(buildJsonObject { put("role", "user"); put("content", userPrompt) })
                }
                putJsonObject("response_format") {
                    if (strictSchema) {
                        put("type", "json_schema")
                        putJsonObject("json_schema") {
                            put("name", "nutrition_estimate")
                            put("strict", true)
                            put("schema", schema())
                        }
                    } else {
                        put("type", "json_object")
                    }
                }
            }
            return body.toString()
        }

        private fun schema(): JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                for (field in listOf("kcal_per_100g", "protein_per_100g", "fat_per_100g", "carbs_per_100g")) {
                    putJsonObject(field) { put("type", "number") }
                }
                putJsonObject("serving_g") {
                    put("type", buildJsonArray { add("number"); add("null") })
                }
                putJsonObject("note") { put("type", "string") }
            }
            put(
                "required",
                buildJsonArray {
                    listOf("kcal_per_100g", "protein_per_100g", "fat_per_100g", "carbs_per_100g", "serving_g", "note")
                        .forEach { add(it) }
                },
            )
            put("additionalProperties", false)
        }

        /** Разбор ответа chat/completions: choices[0].message.content → JSON оценки (возможно в ```-ограде). */
        fun parseResponse(body: String): NutritionEstimate {
            val root = try {
                json.parseToJsonElement(body).jsonObject
            } catch (e: Exception) {
                throw EstimateException("Сервер ИИ вернул не JSON", e)
            }
            val content = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject?.get("content")
                ?.let { contentText(it) }
                ?: throw EstimateException("В ответе ИИ нет содержимого")
            return parseEstimateJson(content)
        }

        /** content бывает строкой или массивом частей ({type:text, text}). */
        private fun contentText(element: JsonElement): String? = when (element) {
            is JsonPrimitive -> element.content
            is JsonArray -> element.mapNotNull { part ->
                (part as? JsonObject)?.get("text")?.jsonPrimitive?.content
            }.joinToString("")
            else -> null
        }

        /** Разбор самого JSON оценки; терпим к синонимам ключей и ```json-ограде. */
        fun parseEstimateJson(raw: String): NutritionEstimate {
            val cleaned = raw.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            val obj = try {
                json.parseToJsonElement(cleaned).jsonObject
            } catch (e: Exception) {
                throw EstimateException("Не удалось разобрать оценку ИИ", e)
            }
            fun num(vararg keys: String): Double? = keys.firstNotNullOfOrNull { k ->
                val v = obj[k] ?: return@firstNotNullOfOrNull null
                if (v is JsonNull) null else v.jsonPrimitive.doubleOrNull
            }
            val kcal = num("kcal_per_100g", "calories_per_100g", "kcalPer100", "caloriesPer100")
                ?: throw EstimateException("В оценке ИИ нет калорий на 100 г")
            val per100 = Per100(
                kcal = kcal.coerceAtLeast(0.0),
                proteinG = (num("protein_per_100g", "proteinPer100") ?: 0.0).coerceAtLeast(0.0),
                fatG = (num("fat_per_100g", "fatPer100") ?: 0.0).coerceAtLeast(0.0),
                carbsG = (num("carbs_per_100g", "carbsPer100") ?: 0.0).coerceAtLeast(0.0),
            )
            val serving = num("serving_g", "servingG")?.takeIf { it > 0 }
            if (!per100.kcal.isFinite() || per100.kcal !in 0.0..1000.0 ||
                listOf(per100.proteinG, per100.fatG, per100.carbsG).any { !it.isFinite() || it !in 0.0..100.0 } ||
                (serving != null && (!serving.isFinite() || serving > 100_000.0))) {
                throw EstimateException("ИИ вернул некорректные значения КБЖУ. Введите их вручную или повторите расчёт.")
            }
            val note = (obj["note"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            return NutritionEstimate(per100, serving, note)
        }

        private fun describeHttpError(result: HttpResult): String {
            val serverMessage = try {
                json.parseToJsonElement(result.body).jsonObject["error"]?.let { err ->
                    (err as? JsonObject)?.get("message")?.jsonPrimitive?.content ?: err.jsonPrimitive.content
                }
            } catch (_: Exception) {
                null
            }
            val base = when (result.code) {
                401 -> "Неверный API-ключ"
                403 -> "Доступ запрещён"
                404 -> "Эндпоинт не найден — проверьте базовый URL"
                429 -> "Превышен лимит запросов, попробуйте позже"
                in 500..599 -> "Сервер ИИ недоступен"
                else -> "Ошибка сервера ИИ (HTTP ${result.code})"
            }
            return if (serverMessage.isNullOrBlank()) base else "$base: ${serverMessage.take(160)}"
        }

        private fun formatG(value: Double): String =
            if (value % 1.0 == 0.0) value.toLong().toString() else String.format(Locale.ROOT, "%.1f", value)
    }
}
