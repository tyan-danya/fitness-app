package com.dtyan.fitdiary.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.dtyan.fitdiary.data.db.Meal
import com.dtyan.fitdiary.data.repo.NutritionRepository.EstimateUpdate
import com.dtyan.fitdiary.domain.Per100
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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Файл-обмен для расчёта КБЖУ «вручную через ассистента»:
 * приложение выгружает JSON с приёмами, ждущими расчёта, пользователь отдаёт его
 * ассистенту, тот заполняет значения на 100 г и вес порции, и файл загружается обратно.
 * Формат ответа — тот же, что и запроса, поэтому ассистенту достаточно «заполнить null-ы».
 */
class EstimateExchange(private val context: Context) {

    /** Шаринг файла-запроса через системный chooser. */
    suspend fun shareRequest(meals: List<Meal>): Intent = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val stamp = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val file = File(dir, "fitdiary-estimate-request-$stamp.json")
        file.writeText(buildRequestJson(meals), Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, SHARE_TEXT)
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        Intent.createChooser(send, "Файл для расчёта КБЖУ").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Чтение выбранного пользователем файла ответа. */
    suspend fun readText(uri: Uri): String = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: throw IllegalStateException("Не удалось открыть файл")
    }

    companion object {
        const val FORMAT = "fitdiary-estimate/1"

        private const val INSTRUCTIONS =
            "Для каждого элемента items оцени пищевую ценность блюда name НА 100 ГРАММ и заполни " +
                "kcalPer100, proteinPer100, fatPer100, carbsPer100 числами. Если servingG равен null — " +
                "оцени типичный вес порции в граммах и запиши в servingG; если задан — не меняй. " +
                "Поля id, date, meal, name не менять. Верни JSON ровно этой же структуры (можно только items)."

        private const val SHARE_TEXT =
            "Заполни в этом файле значения КБЖУ на 100 г для каждого блюда и верни файл того же формата."

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun buildRequestJson(meals: List<Meal>): String {
            val root = buildJsonObject {
                put("format", FORMAT)
                put("instructions", INSTRUCTIONS)
                put(
                    "items",
                    buildJsonArray {
                        meals.forEach { meal ->
                            add(
                                buildJsonObject {
                                    put("id", meal.id)
                                    put("date", LocalDate.ofEpochDay(meal.epochDay).format(DateTimeFormatter.ISO_LOCAL_DATE))
                                    put("meal", meal.mealType.title)
                                    put("name", meal.name)
                                    put("servingG", meal.servingG)
                                    put("kcalPer100", meal.caloriesPer100)
                                    put("proteinPer100", meal.proteinPer100)
                                    put("fatPer100", meal.fatPer100)
                                    put("carbsPer100", meal.carbsPer100)
                                }
                            )
                        }
                    },
                )
            }
            return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), root)
        }

        /**
         * Разбор ответа: корень — объект с items или сразу массив. Элементы без id
         * или без kcalPer100 пропускаются. Ключи терпимы к snake_case-синонимам.
         */
        fun parseResponse(text: String): List<EstimateUpdate> {
            val root = try {
                json.parseToJsonElement(text.trim().removePrefix("﻿"))
            } catch (e: Exception) {
                throw IllegalArgumentException("Файл не является JSON", e)
            }
            val items: JsonArray = when (root) {
                is JsonArray -> root
                is JsonObject -> root["items"] as? JsonArray ?: throw IllegalArgumentException("В файле нет списка items")
                else -> throw IllegalArgumentException("Неожиданная структура файла")
            }
            return items.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val id = obj.num("id")?.toLong() ?: obj["id"]?.let { (it as? JsonPrimitive)?.longOrNull }
                    ?: return@mapNotNull null
                val kcal = obj.num("kcalPer100", "kcal_per_100g", "caloriesPer100", "calories_per_100g")
                    ?: return@mapNotNull null
                EstimateUpdate(
                    mealId = id,
                    per100 = Per100(
                        kcal = kcal.coerceAtLeast(0.0),
                        proteinG = (obj.num("proteinPer100", "protein_per_100g") ?: 0.0).coerceAtLeast(0.0),
                        fatG = (obj.num("fatPer100", "fat_per_100g") ?: 0.0).coerceAtLeast(0.0),
                        carbsG = (obj.num("carbsPer100", "carbs_per_100g") ?: 0.0).coerceAtLeast(0.0),
                    ),
                    servingG = obj.num("servingG", "serving_g")?.takeIf { it > 0 },
                )
            }
        }

        private fun JsonObject.num(vararg keys: String): Double? = keys.firstNotNullOfOrNull { k ->
            val v: JsonElement = this[k] ?: return@firstNotNullOfOrNull null
            if (v is JsonNull) null else (v as? JsonPrimitive)?.let { p ->
                p.doubleOrNull ?: p.content.replace(',', '.').toDoubleOrNull()
            }
        }
    }
}
