package com.dtyan.fitdiary.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.dtyan.fitdiary.data.db.Meal
import com.dtyan.fitdiary.data.repo.NutritionRepository.EstimateUpdate
import com.dtyan.fitdiary.data.repo.NutritionRepository
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Файл-обмен для расчёта КБЖУ «вручную через ассистента»:
 * приложение выгружает JSON с приёмами, ждущими расчёта, пользователь отдаёт его
 * ассистенту, тот заполняет значения на 100 г и вес порции, и файл загружается обратно.
 * Формат ответа — тот же, что и запроса, поэтому ассистенту достаточно «заполнить null-ы».
 */
class EstimateExchange(
    private val context: Context,
    private val diaryId: () -> String = { defaultDiaryId(context) },
    private val activeAthleteId: () -> Long = { 1L },
) {
    private val requests = context.getSharedPreferences("fitdiary_estimate_requests", Context.MODE_PRIVATE)
    data class ImportBatch(val requestId: String, val diaryId: String, val athleteId: Long, val updates: List<EstimateUpdate>)

    /** Keep the original fingerprints locally; an edited response cannot redefine them. */
    fun createRequestJson(meals: List<Meal>): String {
        require(meals.isNotEmpty() && meals.size <= 5000) { "Некорректное число приёмов" }
        val athlete = meals.first().athleteId
        require(athlete == activeAthleteId() && meals.all { it.athleteId == athlete }) { "Смешаны профили" }
        val requestId = UUID.randomUUID().toString()
        val result = buildRequestJson(meals, diaryId(), requestId, athlete)
        val editor = requests.edit()
        // Keep the 20 latest requests; no meal data goes to a server here.
        requests.all.entries.sortedBy { entry -> runCatching {
            json.parseToJsonElement(entry.value as String).jsonObject["createdAt"]?.jsonPrimitive?.longOrNull ?: 0L
        }.getOrDefault(0L) }.dropLast(19)
            .forEach { editor.remove(it.key) }
        check(editor.putString(requestId, result).commit()) { "Не удалось сохранить запрос" }
        return result
    }

    fun readResponse(text: String): ImportBatch {
        require(text.length <= 4_000_000) { "Файл расчёта слишком большой" }
        val root = json.parseToJsonElement(text.trim().removePrefix("\uFEFF")).jsonObject
        require(root["format"]?.jsonPrimitive?.content == FORMAT) { "Нужен ответ на новую выгрузку приложения (формат $FORMAT)" }
        val id = root["requestId"]?.jsonPrimitive?.content ?: error("Нет номера запроса")
        require(runCatching { UUID.fromString(id) }.isSuccess) { "Некорректный номер запроса" }
        val original = requests.getString(id, null)?.let { json.parseToJsonElement(it).jsonObject }
            ?: throw IllegalArgumentException("Запрос не найден на этом устройстве. Выгрузите приёмы заново.")
        val diary = root["diaryId"]?.jsonPrimitive?.content
        val athlete = root["athleteId"]?.jsonPrimitive?.longOrNull
        require(diary == diaryId() && diary == original["diaryId"]?.jsonPrimitive?.content) { "Файл относится к другому дневнику" }
        require(athlete == activeAthleteId() && athlete == original["athleteId"]?.jsonPrimitive?.longOrNull) { "Выберите профиль, для которого выгружен файл" }
        val items = (original["items"] as JsonArray).associate { el ->
            val item = el.jsonObject
            item["id"]!!.jsonPrimitive.longOrNull!! to item["fingerprint"]!!.jsonPrimitive.content
        }
        val responseItems = root["items"] as? JsonArray ?: throw IllegalArgumentException("В файле нет списка items")
        require(responseItems.size <= 5000) { "Слишком много приёмов в файле" }
        responseItems.forEach { element ->
            val item = element as? JsonObject ?: throw IllegalArgumentException("Некорректный приём в файле")
            val kcal = item.num("kcalPer100", "kcal_per_100g", "caloriesPer100", "calories_per_100g")
            if (kcal != null) {
                require(kcal.isFinite() && kcal in 0.0..1000.0) { "Некорректные калории на 100 г" }
                val macros = listOf(item.num("proteinPer100", "protein_per_100g"), item.num("fatPer100", "fat_per_100g"), item.num("carbsPer100", "carbs_per_100g"))
                require(macros.all { it != null && it.isFinite() && it in 0.0..100.0 }) { "Заполните все значения БЖУ на 100 г" }
            }
        }
        val updates = parseResponse(text).map { u ->
            require(items[u.mealId] != null && u.fingerprint == items[u.mealId]) { "Состав запроса изменён. Выгрузите приёмы заново." }
            u.copy(athleteId = requireNotNull(athlete)).also(NutritionRepository::validateEstimate)
        }
        require(updates.size <= 5000 && updates.map { it.mealId }.distinct().size == updates.size) { "Повторяющиеся приёмы в файле" }
        return ImportBatch(id, requireNotNull(diary), requireNotNull(athlete), updates)
    }

    fun isCurrent(batch: ImportBatch): Boolean = batch.diaryId == diaryId() && batch.athleteId == activeAthleteId()

    /** Шаринг файла-запроса через системный chooser. */
    suspend fun shareRequest(meals: List<Meal>): Intent = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val stamp = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val file = File(dir, "fitdiary-estimate-request-$stamp-${UUID.randomUUID()}.json")
        file.writeText(createRequestJson(meals), Charsets.UTF_8)
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
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val bytes = stream.readBytesBounded(4_000_000)
            bytes.toString(Charsets.UTF_8)
        }
            ?: throw IllegalStateException("Не удалось открыть файл")
    }

    companion object {
        const val FORMAT = "fitdiary-estimate/2"

        private fun defaultDiaryId(context: Context): String {
            val prefs = context.getSharedPreferences("fitdiary_settings", Context.MODE_PRIVATE)
            return prefs.getString("diaryId", null) ?: UUID.randomUUID().toString().also {
                check(prefs.edit().putString("diaryId", it).commit())
            }
        }

        private const val INSTRUCTIONS =
            "Для каждого элемента items оцени пищевую ценность блюда name НА 100 ГРАММ и заполни " +
                "kcalPer100, proteinPer100, fatPer100, carbsPer100 числами. Если servingG равен null — " +
                "оцени типичный вес порции в граммах и запиши в servingG; если задан — не меняй. " +
                "Поля format, diaryId, requestId, athleteId и все id, fingerprint, date, meal, name не менять. " +
                "Верни JSON целиком ровно той же структуры."

        private const val SHARE_TEXT =
            "Заполни в этом файле значения КБЖУ на 100 г для каждого блюда и верни файл того же формата."

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun buildRequestJson(meals: List<Meal>, diaryId: String = "", requestId: String = "", athleteId: Long = 1L): String {
            val root = buildJsonObject {
                put("format", FORMAT)
                put("diaryId", diaryId)
                put("requestId", requestId)
                put("athleteId", athleteId)
                put("createdAt", System.currentTimeMillis())
                put("instructions", INSTRUCTIONS)
                put(
                    "items",
                    buildJsonArray {
                        meals.forEach { meal ->
                            add(
                                buildJsonObject {
                                    put("id", meal.id)
                                    put("fingerprint", NutritionRepository.fingerprint(meal))
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
                json.parseToJsonElement(text.trim().removePrefix("\uFEFF"))
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
                val id = obj["id"]?.let { (it as? JsonPrimitive)?.longOrNull }
                    ?: return@mapNotNull null
                val kcal = obj.num("kcalPer100", "kcal_per_100g", "caloriesPer100", "calories_per_100g")
                    ?: return@mapNotNull null
                EstimateUpdate(
                    mealId = id,
                    fingerprint = (obj["fingerprint"] as? JsonPrimitive)?.content,
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
