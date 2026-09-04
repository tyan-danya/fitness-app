package com.dtyan.fitdiary.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.dtyan.fitdiary.data.repo.NutritionRepository
import com.dtyan.fitdiary.data.repo.StatsRepository
import com.dtyan.fitdiary.data.repo.WorkoutRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---------- DTO для JSON-экспорта ----------

@Serializable
private data class ExportRoot(
    val exportedAt: String,
    val bodyWeights: List<ExportBodyWeight>,
    val workouts: List<ExportWorkout>,
    val meals: List<ExportMeal>,
)

@Serializable
private data class ExportBodyWeight(
    val dateTime: String,
    val weightKg: Double,
    val fromWorkout: Boolean,
)

@Serializable
private data class ExportWorkout(
    val date: String,
    val startedAt: String,
    val endedAt: String,
    val durationMinutes: Long,
    val bodyWeightKg: Double? = null,
    val totalVolumeKg: Double,
    val sets: List<ExportSet>,
)

@Serializable
private data class ExportSet(
    val exercise: String,
    val muscleGroup: String,
    val setIndex: Int,
    val weightKg: Double,
    val reps: Int,
    val completedAt: String,
)

@Serializable
private data class ExportMeal(
    val date: String,
    val time: String,
    val name: String,
    val calories: Int,
    val proteinG: Double,
    val fatG: Double,
    val carbsG: Double,
    val mealType: String,
    val servingG: Double? = null,
    val caloriesPer100: Double? = null,
    val proteinPer100: Double? = null,
    val fatPer100: Double? = null,
    val carbsPer100: Double? = null,
    val needsEstimate: Boolean = false,
)

/**
 * Сборка экспортов (JSON/CSV) и share-интентов через FileProvider.
 * Держит только applicationContext-совместимый Context — передавать applicationContext.
 */
class ExportManager(
    private val context: Context,
    private val workoutRepository: WorkoutRepository,
    private val nutritionRepository: NutritionRepository,
    private val statsRepository: StatsRepository,
) {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    private val zone: ZoneId get() = ZoneId.systemDefault()

    private fun iso(millis: Long): String =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(millis).atZone(zone))

    private fun localDate(millis: Long): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    private fun isoDate(millis: Long): String =
        localDate(millis).format(DateTimeFormatter.ISO_LOCAL_DATE)

    private fun timeHm(millis: Long): String =
        DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(millis).atZone(zone))

    // ---------- JSON ----------

    /** Чистое построение JSON-строки экспорта (без IO с файлами — удобно тестировать). */
    suspend fun buildJsonString(now: Long = System.currentTimeMillis()): String {
        val weights = statsRepository.observeWeightHistory().first()
        val workouts = statsRepository.getAllFinishedWorkoutsOnce().map { workout ->
            val sets = workoutRepository.getSetsForWorkoutOnce(workout.id)
            val ended = workout.endedAt ?: workout.startedAt
            ExportWorkout(
                date = isoDate(workout.startedAt),
                startedAt = iso(workout.startedAt),
                endedAt = iso(ended),
                durationMinutes = (ended - workout.startedAt) / 60_000,
                bodyWeightKg = workout.bodyWeightKg,
                totalVolumeKg = sets.sumOf { it.weightKg * it.reps },
                sets = sets.map { s ->
                    ExportSet(
                        exercise = s.exerciseName,
                        muscleGroup = s.muscleGroup,
                        setIndex = s.setIndex,
                        weightKg = s.weightKg,
                        reps = s.reps,
                        completedAt = iso(s.completedAt),
                    )
                },
            )
        }
        val meals = nutritionRepository.getAllOnce().map { meal ->
            ExportMeal(
                date = LocalDate.ofEpochDay(meal.epochDay).format(DateTimeFormatter.ISO_LOCAL_DATE),
                time = timeHm(meal.timestamp),
                name = meal.name,
                calories = meal.calories,
                proteinG = meal.proteinG,
                fatG = meal.fatG,
                carbsG = meal.carbsG,
                mealType = meal.mealType.name,
                servingG = meal.servingG,
                caloriesPer100 = meal.caloriesPer100,
                proteinPer100 = meal.proteinPer100,
                fatPer100 = meal.fatPer100,
                carbsPer100 = meal.carbsPer100,
                needsEstimate = meal.needsEstimate,
            )
        }
        val root = ExportRoot(
            exportedAt = iso(now),
            bodyWeights = weights.map {
                ExportBodyWeight(dateTime = iso(it.timestamp), weightKg = it.weightKg, fromWorkout = it.fromWorkout)
            },
            workouts = workouts,
            meals = meals,
        )
        return json.encodeToString(root)
    }

    // ---------- CSV ----------

    /** Число с запятой-разделителем, без хвостовых нулей: 60.0 → «60», 62.5 → «62,5». */
    private fun csvNum(value: Double): String {
        val s = String.format(Locale.ROOT, "%.2f", value)
            .trimEnd('0').trimEnd('.')
        return s.replace('.', ',')
    }

    /** RFC-эскейп: поле с ; " или переводом строки — в кавычки, кавычки удваиваются. */
    private fun csvField(raw: String): String =
        if (raw.contains(';') || raw.contains('"') || raw.contains('\n') || raw.contains('\r')) {
            "\"" + raw.replace("\"", "\"\"") + "\""
        } else raw

    private fun row(vararg fields: String): String = fields.joinToString(";") { csvField(it) }

    suspend fun buildWorkoutSetsCsv(): String {
        val sb = StringBuilder()
        sb.appendLine(row("date", "start", "end", "duration_min", "exercise", "muscle_group", "set_index", "weight_kg", "reps"))
        for (workout in statsRepository.getAllFinishedWorkoutsOnce()) {
            val ended = workout.endedAt ?: workout.startedAt
            val date = isoDate(workout.startedAt)
            val start = timeHm(workout.startedAt)
            val end = timeHm(ended)
            val durationMin = ((ended - workout.startedAt) / 60_000).toString()
            for (s in workoutRepository.getSetsForWorkoutOnce(workout.id)) {
                sb.appendLine(
                    row(
                        date, start, end, durationMin,
                        s.exerciseName, s.muscleGroup,
                        s.setIndex.toString(), csvNum(s.weightKg), s.reps.toString(),
                    )
                )
            }
        }
        return sb.toString()
    }

    suspend fun buildMealsCsv(): String {
        val sb = StringBuilder()
        sb.appendLine(
            row(
                "date", "time", "meal_type", "name", "serving_g", "calories", "protein_g", "fat_g", "carbs_g",
                "kcal_per_100g", "protein_per_100g", "fat_per_100g", "carbs_per_100g",
            )
        )
        for (meal in nutritionRepository.getAllOnce()) {
            sb.appendLine(
                row(
                    LocalDate.ofEpochDay(meal.epochDay).format(DateTimeFormatter.ISO_LOCAL_DATE),
                    timeHm(meal.timestamp),
                    meal.mealType.title,
                    meal.name,
                    meal.servingG?.let(::csvNum) ?: "",
                    meal.calories.toString(),
                    csvNum(meal.proteinG),
                    csvNum(meal.fatG),
                    csvNum(meal.carbsG),
                    meal.caloriesPer100?.let(::csvNum) ?: "",
                    meal.proteinPer100?.let(::csvNum) ?: "",
                    meal.fatPer100?.let(::csvNum) ?: "",
                    meal.carbsPer100?.let(::csvNum) ?: "",
                )
            )
        }
        return sb.toString()
    }

    suspend fun buildWeightsCsv(): String {
        val sb = StringBuilder()
        sb.appendLine(row("date", "time", "weight_kg", "from_workout"))
        for (entry in statsRepository.observeWeightHistory().first()) {
            sb.appendLine(
                row(
                    isoDate(entry.timestamp),
                    timeHm(entry.timestamp),
                    csvNum(entry.weightKg),
                    if (entry.fromWorkout) "1" else "0",
                )
            )
        }
        return sb.toString()
    }

    // ---------- Файлы и share-интенты ----------

    private fun exportsDir(): File = File(context.cacheDir, "exports").apply { mkdirs() }

    private fun uriFor(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    private fun todayStamp(): String = LocalDate.now(zone).format(DateTimeFormatter.ISO_LOCAL_DATE)

    /** Полный JSON-экспорт → chooser «поделиться файлом». */
    suspend fun exportJson(): Intent = withContext(Dispatchers.IO) {
        val content = buildJsonString()
        val file = File(exportsDir(), "fitdiary-export-${todayStamp()}.json")
        file.writeText(content, Charsets.UTF_8)
        val uri = uriFor(file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        Intent.createChooser(send, "Экспорт данных").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Три CSV (подходы, питание, вес) с BOM для Excel → chooser. */
    suspend fun exportCsv(): Intent = withContext(Dispatchers.IO) {
        val stamp = todayStamp()
        val bom = "\uFEFF" // BOM — чтобы русский Excel распознал UTF-8
        val files = listOf(
            "fitdiary-sets-$stamp.csv" to buildWorkoutSetsCsv(),
            "fitdiary-meals-$stamp.csv" to buildMealsCsv(),
            "fitdiary-weights-$stamp.csv" to buildWeightsCsv(),
        ).map { (name, content) ->
            File(exportsDir(), name).apply { writeText(bom + content, Charsets.UTF_8) }
        }
        val uris = ArrayList<Uri>(files.map { uriFor(it) })
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/csv"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = ClipData.newRawUri(null, uris.first()).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        Intent.createChooser(send, "Экспорт данных").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
