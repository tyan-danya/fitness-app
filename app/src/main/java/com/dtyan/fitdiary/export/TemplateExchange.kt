package com.dtyan.fitdiary.export

import com.dtyan.fitdiary.data.repo.ImportedTemplateExercise
import com.dtyan.fitdiary.data.db.TemplateDetails
import com.dtyan.fitdiary.data.repo.TemplateRepository
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.*

/** A portable program contains exercise descriptions, never personal results or local row IDs. */
data class TemplateImportPreview(val name: String, val exercises: List<ImportedTemplateExercise>)

object TemplateExchange {
    const val FORMAT = "fitdiary-template/1"
    const val MAX_BYTES = 512 * 1024
    private val json = Json { prettyPrint = true }
    private val fields = setOf("name", "muscleGroup", "equipment", "targetSets", "targetReps", "note")

    fun parse(input: InputStream): TemplateImportPreview {
        val bytes = input.readBytesBounded(MAX_BYTES)
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            throw IllegalArgumentException("Сохраните файл программы в кодировке UTF-8")
        }
        return parse(text)
    }

    fun parse(text: String): TemplateImportPreview {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Файл программы больше 512 КиБ" }
        val root = try { json.parseToJsonElement(text.removePrefix("\uFEFF")) as? JsonObject }
        catch (_: Exception) { null } ?: throw IllegalArgumentException("Некорректный JSON программы")
        require(root.keys == setOf("format", "name", "exercises")) { "Ожидается файл программы с полями format, name и exercises" }
        require(root.string("format") == FORMAT) { "Неизвестный формат программы. Нужен $FORMAT" }
        val name = root.string("name").trim()
        val rows = root["exercises"] as? JsonArray ?: throw IllegalArgumentException("Нет списка упражнений")
        require(rows.size in 1..100) { "В программе должно быть от 1 до 100 упражнений" }
        val items = rows.mapIndexed { index, element ->
            val row = element as? JsonObject ?: throw IllegalArgumentException("Упражнение ${index + 1}: ожидается объект")
            require(row.keys.all { it in fields }) { "Упражнение ${index + 1}: неизвестное поле" }
            ImportedTemplateExercise(
                name = row.string("name").trim(),
                muscleGroup = row.string("muscleGroup").trim(),
                equipment = if ("equipment" in row) row.string("equipment").trim() else "Другое",
                targetSets = if ("targetSets" in row) row.integer("targetSets") else 3,
                targetReps = row["targetReps"]?.takeUnless { it == JsonNull }?.let { row.integer("targetReps") },
                note = row["note"]?.takeUnless { it == JsonNull }?.let { row.string("note").trim().takeIf(String::isNotEmpty) },
            )
        }
        TemplateRepository.validateImport(name, items)
        return TemplateImportPreview(name, items)
    }

    fun encode(details: TemplateDetails): String {
        val items = details.items.map {
            ImportedTemplateExercise(it.exercise.name, it.exercise.muscleGroup, it.exercise.equipment,
                it.targetSets, it.targetReps, it.note)
        }
        TemplateRepository.validateImport(details.template.name, items)
        val root = buildJsonObject {
            put("format", FORMAT)
            put("name", details.template.name)
            put("exercises", buildJsonArray {
                items.forEach { item -> add(buildJsonObject {
                    put("name", item.name)
                    put("muscleGroup", item.muscleGroup)
                    put("equipment", item.equipment)
                    put("targetSets", item.targetSets)
                    put("targetReps", item.targetReps)
                    put("note", item.note)
                }) }
            })
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    private fun JsonObject.string(key: String): String {
        val value = this[key] as? JsonPrimitive
        require(value?.isString == true) { "Поле $key должно быть строкой" }
        return value.content
    }

    private fun JsonObject.integer(key: String): Int {
        val value = this[key] as? JsonPrimitive
        require(value != null && !value.isString && value.intOrNull != null) { "Поле $key должно быть целым числом" }
        return value.int
    }
}
