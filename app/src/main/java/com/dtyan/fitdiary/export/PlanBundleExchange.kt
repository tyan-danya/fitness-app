package com.dtyan.fitdiary.export

import kotlinx.serialization.json.*
import java.net.URI

data class PlanDay(val dayOfWeek: Int, val morning: String, val strength: String, val home: String)
data class PlanSource(val title: String, val url: String)
data class PlanActivity(
    val key: String, val name: String, val kind: String, val goalMetric: String,
    val goals: List<Double>, val instructions: String, val weeklyScheduleNote: String?,
)
data class PlanBundlePreview(
    val key: String, val name: String, val overview: String,
    val week: List<PlanDay>, val templates: List<TemplateImportPreview>,
    val activities: List<PlanActivity>, val sources: List<PlanSource>,
    val documentJson: String,
)

/** Portable plans contain instructions and targets only. Import cannot create performed activity. */
object PlanBundleExchange {
    const val FORMAT = "fitdiary-plan/1"
    fun isBundle(text: String): Boolean = runCatching {
        Json.parseToJsonElement(text.removePrefix("\uFEFF")).jsonObject["format"]?.jsonPrimitive?.content == FORMAT
    }.getOrDefault(false)

    fun parse(text: String): PlanBundlePreview {
        require(text.toByteArray(Charsets.UTF_8).size <= TemplateExchange.MAX_BYTES) { "Файл плана больше 512 КиБ" }
        val root = runCatching { Json.parseToJsonElement(text.removePrefix("\uFEFF")).jsonObject }
            .getOrElse { throw IllegalArgumentException("Некорректный JSON плана") }
        root.fields(setOf("format", "key", "name", "overview", "week", "templates", "activities", "sources"))
        require(root.text("format") == FORMAT) { "Неизвестный формат плана" }
        val key = root.text("key").also { require(Regex("[a-z0-9][a-z0-9_-]{0,79}").matches(it)) { "Некорректный ключ плана" } }
        val name = root.text("name", 120)
        val overview = root.text("overview", 8000)
        val week = root.array("week", 7..7).map { element ->
            val day = element.objectValue()
            day.fields(setOf("dayOfWeek", "morning", "strength", "home"))
            PlanDay(day.integer("dayOfWeek"), day.text("morning", 1500),
                day.text("strength", 1500), day.text("home", 1500))
        }.sortedBy { it.dayOfWeek }
        require(week.map { it.dayOfWeek } == (1..7).toList()) { "Нужны семь разных дней недели: от 1 до 7" }
        val templates = root.array("templates", 0..20).map { element ->
            val template = element.objectValue()
            template.fields(setOf("name", "exercises"))
            TemplateExchange.parse(JsonObject(template + ("format" to JsonPrimitive(TemplateExchange.FORMAT))).toString())
        }
        require(templates.map { it.name.lowercase() }.distinct().size == templates.size) { "Повторяются названия программ" }
        val activities = root.array("activities", 0..50).map { element ->
            val activity = element.objectValue()
            activity.fields(setOf("key", "name", "kind", "goalMetric", "goals", "instructions", "weeklyScheduleNote"))
            val activityKey = activity.text("key")
            require(Regex("[a-z0-9][a-z0-9_-]{0,79}").matches(activityKey)) { "Некорректный ключ активности" }
            val kind = activity.text("kind")
            val metric = activity.text("goalMetric")
            require(when (kind) {
                "REPS" -> metric == "REPS"
                "DURATION" -> metric == "DURATION_SECONDS"
                "WALK" -> metric in setOf("DURATION_SECONDS", "DISTANCE_KM", "STEPS")
                else -> false
            }) { "Цель не соответствует виду активности" }
            val goals = activity.array("goals", 0..30).map {
                val p = it as? JsonPrimitive
                require(p != null && !p.isString && p.doubleOrNull?.isFinite() == true) { "Цели должны быть числами" }
                p.double
            }
            val max = when (metric) { "REPS" -> 999.0; "DURATION_SECONDS" -> 86400.0; "STEPS" -> 200000.0; else -> 200.0 }
            require(goals.all { it > 0 && it <= max && (metric == "DISTANCE_KM" || it % 1.0 == 0.0) }
                && goals.zipWithNext().all { (a, b) -> a < b }) { "Цели должны возрастать и соответствовать единицам" }
            PlanActivity(activityKey, activity.text("name", 120), kind, metric, goals,
                activity.text("instructions", 2000), activity.optionalText("weeklyScheduleNote", 2000))
        }
        require(activities.map { it.key }.distinct().size == activities.size) { "Повторяются ключи активности" }
        require(templates.isNotEmpty() || activities.isNotEmpty()) { "В плане нет программ или активности" }
        val sources = if ("sources" !in root) emptyList() else root.array("sources", 0..10).map {
            val source = it.objectValue()
            source.fields(setOf("title", "url"))
            val url = source.text("url", 2048)
            val uri = runCatching { URI(url) }.getOrNull()
            require(uri?.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null) { "Источник должен быть HTTPS-ссылкой" }
            PlanSource(source.text("title", 200), url)
        }
        return PlanBundlePreview(key, name, overview, week, templates, activities, sources, root.toString())
    }

    private fun JsonElement.objectValue() = this as? JsonObject ?: error("Ожидается объект")
    private fun JsonObject.fields(allowed: Set<String>) {
        require(keys.all { it in allowed }) { "Неизвестное поле в плане" }
    }
    private fun JsonObject.text(key: String, max: Int = 80): String {
        val value = this[key] as? JsonPrimitive
        require(value?.isString == true && value.content.trim().length in 1..max) { "Поле $key: нужен текст до $max символов" }
        return value.content.trim()
    }
    private fun JsonObject.optionalText(key: String, max: Int): String? =
        if (this[key] == null || this[key] == JsonNull) null else text(key, max)
    private fun JsonObject.array(key: String, count: IntRange): JsonArray {
        val value = this[key] as? JsonArray
        require(value != null && value.size in count) { "Некорректный список $key" }
        return value
    }
    private fun JsonObject.integer(key: String): Int {
        val value = this[key] as? JsonPrimitive
        require(value != null && !value.isString && value.intOrNull != null) { "Поле $key должно быть целым числом" }
        return value.int
    }
}
