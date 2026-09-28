package com.dtyan.fitdiary.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.room.withTransaction
import com.dtyan.fitdiary.data.SettingsStore
import com.dtyan.fitdiary.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Full local backup. The archive never contains AI credentials or connection settings. */
class BackupManager(
    private val context: Context,
    private val database: AppDatabase,
    private val settings: SettingsStore,
) {
    class BackupPreview internal constructor(
        internal val staging: File,
        internal val document: JsonObject,
        val profiles: Int,
        val workouts: Int,
        val meals: Int,
        val photos: Int,
        val measurements: Int,
        val templates: Int = 0,
    )

    private val mutex = Mutex()
    private val json = Json { prettyPrint = true }
    private val stagingRoot get() = File(context.cacheDir, "backup-import").apply { mkdirs() }

    companion object {
        private const val FORMAT = "fitdiary-backup/1"
        private const val MANIFEST = "diary.json"
        private const val MAX_MANIFEST = 16 * 1024 * 1024
        private const val MAX_PHOTO = 10 * 1024 * 1024
        private const val MAX_TOTAL = 256L * 1024 * 1024
        private const val MAX_ENTRIES = 2001
        private const val MAX_ROWS = 200_000
        private val TABLES = listOf("athletes", "exercises", "workout_templates", "template_exercises", "workouts", "workout_sets", "workout_exercises", "meals", "weight_entries", "body_measurements")
        private val V5_TABLES = TABLES.filterNot { it in setOf("workout_templates", "template_exercises") }.toSet()
        private val PHOTO_PATH = Regex("exercise_photos/[A-Za-z0-9_-]+\\.jpg")
    }

    suspend fun createBackup(): Intent = withContext(Dispatchers.IO) {
        mutex.withLock {
            val exports = File(context.cacheDir, "exports").apply { mkdirs() }
            val file = File(exports, "fitdiary-backup-${System.currentTimeMillis()}.zip")
            writeArchive(file)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(null, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            Intent.createChooser(send, "Сохранить резервную копию").apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    /** Also used by round-trip tests; only writes the requested local archive. */
    suspend fun writeArchive(destination: File) = withContext(Dispatchers.IO) {
        val document = snapshot()
        validate(document) // Never export an archive that this version would reject.
        val manifest = json.encodeToString(JsonObject.serializer(), document).toByteArray(Charsets.UTF_8)
        require(manifest.size <= MAX_MANIFEST) { "Дневник слишком большой для этой версии резервной копии" }
        val photos = photoPaths(document)
        require(photos.size < MAX_ENTRIES) { "Слишком много фотографий" }
        val temporary = File(destination.parentFile, "${destination.name}.${UUID.randomUUID()}.tmp")
        destination.parentFile?.mkdirs()
        try {
            var total = manifest.size.toLong()
            ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST))
                zip.write(manifest)
                zip.closeEntry()
                for (relative in photos) {
                    require(PHOTO_PATH.matches(relative)) { "Некорректный путь фотографии" }
                    val photo = File(context.filesDir, relative)
                    require(photo.isFile && photo.length() in 1..MAX_PHOTO.toLong()) { "Фотография отсутствует или слишком большая: $relative" }
                    validatePhoto(photo)
                    total += photo.length()
                    require(total <= MAX_TOTAL) { "Резервная копия слишком большая" }
                    zip.putNextEntry(ZipEntry(relative))
                    photo.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            // Keep the prior recovery snapshot intact until the replacement is ready.
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    private suspend fun snapshot(): JsonObject = database.withTransaction {
        val sql = database.openHelper.writableDatabase
        var totalRows = 0
        val tables = buildJsonObject {
            for (table in TABLES) {
                put(table, buildJsonArray {
                    val order = when (table) {
                        "workout_exercises" -> "workoutId, position"
                        "template_exercises" -> "templateId, position"
                        else -> "id"
                    }
                    sql.query("SELECT * FROM `$table` ORDER BY $order").use { cursor ->
                        while (cursor.moveToNext()) {
                            require(++totalRows <= MAX_ROWS) { "Слишком много записей" }
                            add(buildJsonObject {
                                cursor.columnNames.forEachIndexed { i, name -> put(name, cursor.jsonValue(i)) }
                            })
                        }
                    }
                })
            }
        }
        buildJsonObject {
            put("format", FORMAT)
            put("schemaVersion", sql.version)
            put("createdAt", System.currentTimeMillis())
            put("settings", json.parseToJsonElement(settings.exportSafeSettings()))
            put("tables", tables)
        }
    }

    suspend fun inspectBackup(uri: Uri): BackupPreview = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { inspectArchive(it) }
            ?: throw IllegalArgumentException("Не удалось открыть резервную копию")
    }

    suspend fun inspectArchive(input: InputStream): BackupPreview = withContext(Dispatchers.IO) {
        val stage = File(stagingRoot, UUID.randomUUID().toString()).apply { check(mkdirs()) }
        try {
            val names = mutableSetOf<String>()
            var total = 0L
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    require(!entry.isDirectory && (name == MANIFEST || PHOTO_PATH.matches(name))) { "Недопустимый файл в архиве" }
                    require(names.add(name) && names.size <= MAX_ENTRIES) { "Повторяющиеся файлы или слишком много файлов" }
                    val bytes = zip.readBytesBounded(if (name == MANIFEST) MAX_MANIFEST else MAX_PHOTO)
                    total += bytes.size
                    require(total <= MAX_TOTAL && bytes.isNotEmpty()) { "Архив пустой или слишком большой" }
                    val target = File(stage, name)
                    require(target.canonicalPath.startsWith(stage.canonicalPath + File.separator)) { "Недопустимый путь в архиве" }
                    target.parentFile?.mkdirs()
                    target.writeBytes(bytes)
                    zip.closeEntry()
                }
            }
            require(MANIFEST in names) { "В архиве нет дневника" }
            val document = upgradeDocument(json.parseToJsonElement(File(stage, MANIFEST).readText(Charsets.UTF_8)).jsonObject)
            validate(document)
            val referenced = photoPaths(document)
            require(names == referenced + MANIFEST) { "Фотографии не соответствуют дневнику" }
            referenced.forEach { path -> validatePhoto(File(stage, path)) }
            val tables = document["tables"]!!.jsonObject
            BackupPreview(stage, document, tables["athletes"]!!.jsonArray.size,
                tables["workouts"]!!.jsonArray.size, tables["meals"]!!.jsonArray.size,
                referenced.size, tables["body_measurements"]!!.jsonArray.size, tables["workout_templates"]!!.jsonArray.size)
        } catch (e: Throwable) {
            stage.deleteRecursively()
            throw e
        }
    }

    /** Called only after the replacement warning has been explicitly confirmed. */
    suspend fun restoreBackup(preview: BackupPreview) = withContext(Dispatchers.IO + NonCancellable) {
        mutex.withLock {
            require(preview.staging.isDirectory && preview.staging.parentFile?.canonicalFile == stagingRoot.canonicalFile) { "Предпросмотр устарел" }
            validate(preview.document)
            val previousSettings = settings.exportSafeSettings()
            // Survives app restart; accessible through recoveryPreview() if needed.
            writeArchive(File(context.filesDir, "backups/before-restore.zip"))
            val newPhotos = mutableListOf<File>()
            var settingsAttempted = false
            try {
                val renamed = photoPaths(preview.document).associateWith { old ->
                    val new = "exercise_photos/${UUID.randomUUID()}.jpg"
                    val destination = File(context.filesDir, new)
                    destination.parentFile?.mkdirs()
                    newPhotos += destination
                    File(preview.staging, old).copyTo(destination)
                    new
                }
                database.withTransaction {
                    val sql = database.openHelper.writableDatabase
                    val tables = preview.document["tables"]!!.jsonObject
                    TABLES.asReversed().forEach { sql.execSQL("DELETE FROM `$it`") }
                    for (table in TABLES) {
                        for (element in tables[table]!!.jsonArray) {
                            val row = element.jsonObject
                            val columns = row.keys.toList()
                            val values = columns.map { column ->
                                val original = row[column]!!
                                if (table == "exercises" && column == "photoPath" && original != JsonNull)
                                    renamed.getValue(original.jsonPrimitive.content)
                                else original.sqlValue()
                            }.toTypedArray()
                            sql.execSQL("INSERT INTO `$table` (${columns.joinToString { "`$it`" }}) VALUES (${columns.joinToString { "?" }})", values)
                        }
                    }
                    sql.query("PRAGMA foreign_key_check").use { require(!it.moveToFirst()) { "Нарушены связи дневника" } }
                    settingsAttempted = true
                    settings.restoreSafeSettings(preview.document["settings"]!!.toString())
                }
            } catch (e: Throwable) {
                if (settingsAttempted) runCatching { settings.restoreSafeSettings(previousSettings) }.exceptionOrNull()?.let(e::addSuppressed)
                newPhotos.forEach { it.delete() }
                throw e
            }
            // Old photos remain until a later cleanup; they are the rollback copy's source files.
            database.invalidationTracker.refreshVersionsAsync()
            discard(preview)
        }
    }

    suspend fun recoveryPreview(): BackupPreview = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, "backups/before-restore.zip")
        require(file.isFile) { "Предыдущая копия отсутствует" }
        file.inputStream().use { inspectArchive(it) }
    }

    fun hasRecoveryCopy(): Boolean = File(context.filesDir, "backups/before-restore.zip").isFile

    fun discard(preview: BackupPreview) {
        if (preview.staging.parentFile?.canonicalFile == stagingRoot.canonicalFile) preview.staging.deleteRecursively()
    }

    private data class Column(val name: String, val type: String, val required: Boolean)

    /** Version 1.5 backups remain usable; new program/completion state did not exist in schema 5. */
    private fun upgradeDocument(document: JsonObject): JsonObject {
        if (document["schemaVersion"]?.jsonPrimitive?.intOrNull != 5 || database.openHelper.writableDatabase.version != 6) return document
        require(document["format"]?.jsonPrimitive?.content == FORMAT) { "Неизвестный формат резервной копии" }
        val tables = document["tables"] as? JsonObject ?: error("Отсутствует дневник")
        require(tables.keys == V5_TABLES) { "Неполный набор таблиц старой копии" }
        val oldPlan = tables["workout_exercises"] as? JsonArray ?: error("Отсутствует план тренировки")
        val plan = oldPlan.map { element ->
            val row = element as? JsonObject ?: error("Некорректная запись плана")
            require(row.keys == setOf("workoutId", "exerciseId", "position")) { "Некорректная структура старого плана" }
            JsonObject(row.toMutableMap().apply {
                put("targetSets", JsonNull); put("targetReps", JsonNull)
                put("note", JsonNull); put("completedAt", JsonNull)
            })
        }
        return JsonObject(document.toMutableMap().apply {
            put("schemaVersion", JsonPrimitive(6))
            put("tables", JsonObject(tables.toMutableMap().apply {
                put("workout_exercises", JsonArray(plan))
                put("workout_templates", JsonArray(emptyList()))
                put("template_exercises", JsonArray(emptyList()))
            }))
        })
    }

    private fun validate(document: JsonObject) {
        require(document["format"]?.jsonPrimitive?.content == FORMAT) { "Неизвестный формат резервной копии" }
        val sql = database.openHelper.writableDatabase
        require(document["schemaVersion"]?.jsonPrimitive?.intOrNull == sql.version) { "Версия копии не поддерживается; обновите приложение" }
        settings.validateSafeSettings(document["settings"]?.toString() ?: error("Отсутствуют настройки"))
        val tables = document["tables"] as? JsonObject ?: error("Отсутствует дневник")
        require(tables.keys == TABLES.toSet()) { "Неполный набор таблиц" }
        val ids = mutableMapOf<String, Set<Long>>()
        var totalRows = 0
        for (table in TABLES) {
            val columns = sql.query("PRAGMA table_info(`$table`)").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(Column(cursor.getString(cursor.getColumnIndexOrThrow("name")),
                        cursor.getString(cursor.getColumnIndexOrThrow("type")), cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) != 0))
                }
            }
            val rows = tables[table] as? JsonArray ?: error("Некорректная таблица")
            totalRows += rows.size
            require(totalRows <= MAX_ROWS) { "Слишком много записей" }
            val rowIds = mutableSetOf<Long>()
            val compositeIds = mutableSetOf<Pair<Long, Long>>()
            for (element in rows) {
                val row = element as? JsonObject ?: error("Некорректная запись")
                require(row.keys == columns.map { it.name }.toSet()) { "Структура записи не соответствует версии приложения" }
                for (column in columns) {
                    val value = row[column.name]!!
                    if (value == JsonNull) require(!column.required) { "Обязательное поле пустое" }
                    else {
                        val primitive = value as? JsonPrimitive ?: error("Некорректное поле")
                        when (column.type.uppercase()) {
                            "INTEGER" -> require(!primitive.isString && primitive.longOrNull != null) { "Некорректное целое число" }
                            "REAL" -> require(!primitive.isString && primitive.doubleOrNull?.isFinite() == true) { "Некорректное число" }
                            "TEXT" -> require(primitive.isString && primitive.content.length <= 100_000) { "Некорректный текст" }
                            else -> error("Неподдерживаемый тип поля")
                        }
                    }
                }
                if (table in setOf("workout_exercises", "template_exercises")) {
                    val parentKey = if (table == "workout_exercises") "workoutId" else "templateId"
                    require(compositeIds.add(row[parentKey]!!.jsonPrimitive.long to row["exerciseId"]!!.jsonPrimitive.long)) { "Повторяющиеся упражнения плана" }
                    require(row["position"]!!.jsonPrimitive.int >= 0) { "Некорректный порядок упражнений" }
                    row["targetSets"]?.takeUnless { it == JsonNull }?.let { require(it.jsonPrimitive.int in 1..100) { "Некорректная цель подходов" } }
                    row["targetReps"]?.takeUnless { it == JsonNull }?.let { require(it.jsonPrimitive.int in 1..999) { "Некорректная цель повторов" } }
                    row["note"]?.takeUnless { it == JsonNull }?.let { require(it.jsonPrimitive.content.length <= 500) { "Слишком длинное примечание" } }
                } else {
                    val id = row.getValue("id").jsonPrimitive.long
                    require(id > 0 && rowIds.add(id)) { "Повторяющиеся идентификаторы" }
                }
                row["athleteId"]?.let { require(it.jsonPrimitive.long > 0) { "Некорректный профиль" } }
                for (flag in listOf("isArchived", "isCustom", "needsEstimate", "fromWorkout")) {
                    row[flag]?.let { require(it.jsonPrimitive.int in 0..1) { "Некорректный флаг $flag" } }
                }
                for (field in listOf("weightKg", "valueCm", "calories", "proteinG", "fatG", "carbsG", "servingG", "caloriesPer100", "proteinPer100", "fatPer100", "carbsPer100")) {
                    row[field]?.takeUnless { it == JsonNull }?.let { require(it.jsonPrimitive.double >= 0) { "Отрицательное значение $field" } }
                }
                if (table == "meals") require(row["mealType"]!!.jsonPrimitive.content in setOf("BREAKFAST", "LUNCH", "DINNER", "SNACK")) { "Неизвестный тип приёма" }
                row["epochDay"]?.let { require(runCatching { java.time.LocalDate.ofEpochDay(it.jsonPrimitive.long) }.isSuccess) { "Некорректная дата" } }
                if (table == "athletes") require(row["name"]!!.jsonPrimitive.content.trim().length in 1..40) { "Некорректное имя профиля" }
                if (table == "workout_templates") require(row["name"]!!.jsonPrimitive.content.trim().length in 1..80) { "Некорректное название программы" }
                if (table == "exercises") require(row["weightStepKg"]!!.jsonPrimitive.double > 0) { "Некорректный шаг веса" }
                if (table == "body_measurements") require(runCatching {
                    com.dtyan.fitdiary.domain.MeasurementType.valueOf(row["type"]!!.jsonPrimitive.content)
                }.isSuccess) { "Неизвестный тип замера" }
                if (table == "workout_sets") require(row["reps"]!!.jsonPrimitive.int > 0 && row["setIndex"]!!.jsonPrimitive.int > 0) { "Некорректный подход" }
            }
            ids[table] = rowIds
        }
        require(ids.getValue("athletes").isNotEmpty()) { "В копии нет профилей" }
        require(tables["athletes"]!!.jsonArray.any {
            it.jsonObject["id"]!!.jsonPrimitive.long == 1L && it.jsonObject["isArchived"]!!.jsonPrimitive.int == 0
        }) { "Основной профиль отсутствует или скрыт" }
        val profiles = ids.getValue("athletes")
        for (table in listOf("workouts", "meals", "weight_entries", "body_measurements", "workout_templates")) {
            tables[table]!!.jsonArray.forEach { require(it.jsonObject["athleteId"]!!.jsonPrimitive.long in profiles) { "Запись без профиля" } }
        }
        val workouts = tables["workouts"]!!.jsonArray.map { it.jsonObject }
        workouts.forEach { row -> row["endedAt"]?.takeUnless { it == JsonNull }?.let {
            require(it.jsonPrimitive.long >= row["startedAt"]!!.jsonPrimitive.long) { "Тренировка завершена раньше начала" }
        } }
        val activeProfiles = workouts.filter { it["endedAt"] == JsonNull }.map { it["athleteId"]!!.jsonPrimitive.long }
        require(activeProfiles.distinct().size == activeProfiles.size) { "Несколько активных тренировок одного профиля" }
        val setKeys = tables["workout_sets"]!!.jsonArray.map { it.jsonObject.let { row ->
            Triple(row["workoutId"]!!.jsonPrimitive.long, row["exerciseId"]!!.jsonPrimitive.long, row["setIndex"]!!.jsonPrimitive.int)
        } }
        require(setKeys.distinct().size == setKeys.size) { "Повторяются номера подходов" }
        (tables["workout_sets"]!!.jsonArray + tables["workout_exercises"]!!.jsonArray).forEach { element ->
            val row = element.jsonObject
            require(row["workoutId"]!!.jsonPrimitive.long in ids.getValue("workouts") && row["exerciseId"]!!.jsonPrimitive.long in ids.getValue("exercises")) { "Подход без тренировки или упражнения" }
        }
        val recordedExercises = setKeys.map { it.first to it.second }.toSet()
        tables["workout_exercises"]!!.jsonArray.forEach { element ->
            val row = element.jsonObject
            if (row["completedAt"] != JsonNull) {
                require((row["workoutId"]!!.jsonPrimitive.long to row["exerciseId"]!!.jsonPrimitive.long) in recordedExercises) {
                    "Выполненное упражнение без записанных подходов"
                }
            }
        }
        tables["template_exercises"]!!.jsonArray.forEach { element ->
            val row = element.jsonObject
            require(row["templateId"]!!.jsonPrimitive.long in ids.getValue("workout_templates") && row["exerciseId"]!!.jsonPrimitive.long in ids.getValue("exercises")) { "Программа ссылается на отсутствующее упражнение" }
        }
        val templateItems = tables["template_exercises"]!!.jsonArray.groupBy { it.jsonObject["templateId"]!!.jsonPrimitive.long }
        ids.getValue("workout_templates").forEach { require((templateItems[it]?.size ?: 0) in 1..100) { "В программе должно быть от 1 до 100 упражнений" } }
        tables["weight_entries"]!!.jsonArray.forEach { element ->
            val row = element.jsonObject
            row["sourceWorkoutId"]?.takeUnless { it == JsonNull }?.let { source ->
                val workout = workouts.firstOrNull { it["id"]!!.jsonPrimitive.long == source.jsonPrimitive.long }
                require(workout != null && workout["athleteId"] == row["athleteId"]) { "Замер веса связан с чужой тренировкой" }
            }
        }
        photoPaths(document).forEach { require(PHOTO_PATH.matches(it)) { "Некорректный путь фотографии" } }
    }

    private fun photoPaths(document: JsonObject): Set<String> = document["tables"]!!.jsonObject["exercises"]!!.jsonArray
        .mapNotNull { it.jsonObject["photoPath"]?.takeUnless { value -> value == JsonNull }?.jsonPrimitive?.content }.toSet()

    private fun validatePhoto(file: File) {
        val isJpeg = file.inputStream().use { it.read() == 0xff && it.read() == 0xd8 && it.read() == 0xff }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        require(isJpeg && bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096) { "Некорректная фотография в архиве" }
    }

    private fun Cursor.jsonValue(i: Int): JsonElement = when (getType(i)) {
        Cursor.FIELD_TYPE_NULL -> JsonNull
        Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(getLong(i))
        Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(getDouble(i))
        Cursor.FIELD_TYPE_STRING -> JsonPrimitive(getString(i))
        else -> error("Неподдерживаемый тип данных")
    }

    private fun JsonElement.sqlValue(): Any? = if (this == JsonNull) null else jsonPrimitive.let { value ->
        if (value.isString) value.content else value.longOrNull ?: value.double
    }
}
