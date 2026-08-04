package com.dtyan.fitdiary.export

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dtyan.fitdiary.data.db.AppDatabase
import com.dtyan.fitdiary.data.db.Exercise
import com.dtyan.fitdiary.data.repo.NutritionRepository
import com.dtyan.fitdiary.data.repo.StatsRepository
import com.dtyan.fitdiary.data.repo.WorkoutRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Тесты build*-методов ExportManager поверх реальных репозиториев и in-memory БД.
 * Всё, что зависит от зоны машины (даты/времена локальных форматтеров), сверяется
 * ожиданием, вычисленным той же логикой через ZoneId.systemDefault().
 */
@RunWith(RobolectricTestRunner::class)
class ExportManagerTest {

    private lateinit var db: AppDatabase
    private lateinit var workoutRepo: WorkoutRepository
    private lateinit var nutritionRepo: NutritionRepository
    private lateinit var statsRepo: StatsRepository
    private lateinit var manager: ExportManager

    /** Фиксированное время: 2026-08-01T10:00 Europe/Moscow. */
    private val t0: Long = ZonedDateTime
        .of(2026, 8, 1, 10, 0, 0, 0, ZoneId.of("Europe/Moscow"))
        .toInstant().toEpochMilli()

    private val min = 60_000L

    private val plainExercise = "Жим штанги лёжа"

    /** Имя с ; и " — проверка CSV-эскейпа. */
    private val trickyExercise = "Тяга \"верхнего\"; блока"

    private val mealDay1: LocalDate = LocalDate.of(2026, 7, 31)
    private val mealDay2: LocalDate = LocalDate.of(2026, 8, 1)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        workoutRepo = WorkoutRepository(db.workoutDao(), db.workoutSetDao(), db.weightDao())
        nutritionRepo = NutritionRepository(db.mealDao())
        statsRepo = StatsRepository(db.workoutDao(), db.workoutSetDao(), db.weightDao())
        manager = ExportManager(context, workoutRepo, nutritionRepo, statsRepo)
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * 1 завершённая тренировка (2 упражнения, 3 подхода, контрольное взвешивание 82,5),
     * 2 приёма пищи в разные дни, 1 ручное взвешивание.
     * Тоннаж: 62,5×8 + 62,5×10 + 45×12 = 1665 кг; длительность — ровно 45 минут.
     */
    private suspend fun seedData() {
        val e1 = db.exerciseDao().insert(Exercise(name = plainExercise, muscleGroup = "Грудь"))
        val e2 = db.exerciseDao().insert(Exercise(name = trickyExercise, muscleGroup = "Спина", isCustom = true))

        // Ручное взвешивание — за сутки до тренировки.
        statsRepo.addWeightEntry(80.0, now = t0 - 24 * 60 * min)

        val w = workoutRepo.startWorkout(now = t0)
        workoutRepo.addSet(w, e1, 62.5, 8, now = t0 + 5 * min)
        workoutRepo.addSet(w, e1, 62.5, 10, now = t0 + 10 * min)
        workoutRepo.addSet(w, e2, 45.0, 12, now = t0 + 15 * min)
        workoutRepo.finishWorkout(w, bodyWeightKg = 82.5, now = t0 + 45 * min)

        nutritionRepo.addMeal(
            epochDay = mealDay1.toEpochDay(),
            name = "Овсянка",
            calories = 350,
            proteinG = 12.5,
            fatG = 7.0,
            carbsG = 55.0,
            timestamp = t0 - 20 * 60 * min,
        )
        nutritionRepo.addMeal(
            epochDay = mealDay2.toEpochDay(),
            name = "Курица с рисом",
            calories = 650,
            proteinG = 45.0,
            fatG = 20.0,
            carbsG = 70.5,
            timestamp = t0 + 120 * min,
        )
    }

    // ---------- JSON ----------

    @Test
    fun buildJsonString_parsesBack_withExpectedStructureAndValues() = runTest {
        seedData()
        val exportNow = t0 + 60 * min

        val root = Json.parseToJsonElement(manager.buildJsonString(now = exportNow)).jsonObject

        // exportedAt присутствует; сверяем ожиданием, построенным той же логикой.
        val expectedExportedAt = DateTimeFormatter.ISO_OFFSET_DATE_TIME
            .format(Instant.ofEpochMilli(exportNow).atZone(ZoneId.systemDefault()))
        assertThat(root["exportedAt"]!!.jsonPrimitive.content).isNotEmpty()
        assertThat(root["exportedAt"]!!.jsonPrimitive.content).isEqualTo(expectedExportedAt)

        // Тренировки.
        val workouts = root["workouts"]!!.jsonArray
        assertThat(workouts).hasSize(1)
        val workout = workouts[0].jsonObject
        assertThat(workout["durationMinutes"]!!.jsonPrimitive.long).isEqualTo(45)
        assertThat(workout["totalVolumeKg"]!!.jsonPrimitive.double).isWithin(1e-9).of(1665.0)
        assertThat(workout["bodyWeightKg"]!!.jsonPrimitive.double).isEqualTo(82.5)
        val expectedWorkoutDate = Instant.ofEpochMilli(t0).atZone(ZoneId.systemDefault())
            .toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
        assertThat(workout["date"]!!.jsonPrimitive.content).isEqualTo(expectedWorkoutDate)

        // Подходы: порядок по completedAt, у каждого exercise/weightKg/reps.
        val sets = workout["sets"]!!.jsonArray
        assertThat(sets).hasSize(3)
        val firstSet = sets[0].jsonObject
        assertThat(firstSet["exercise"]!!.jsonPrimitive.content).isEqualTo(plainExercise)
        assertThat(firstSet["weightKg"]!!.jsonPrimitive.double).isEqualTo(62.5)
        assertThat(firstSet["reps"]!!.jsonPrimitive.int).isEqualTo(8)
        val lastSet = sets[2].jsonObject
        assertThat(lastSet["exercise"]!!.jsonPrimitive.content).isEqualTo(trickyExercise)
        assertThat(lastSet["weightKg"]!!.jsonPrimitive.double).isEqualTo(45.0)
        assertThat(lastSet["reps"]!!.jsonPrimitive.int).isEqualTo(12)

        // Приёмы пищи с КБЖУ; дата — из epochDay, от зоны машины не зависит.
        val meals = root["meals"]!!.jsonArray
        assertThat(meals).hasSize(2)
        val meal = meals[0].jsonObject // getAllOnce: ORDER BY timestamp → «Овсянка» первая
        assertThat(meal["date"]!!.jsonPrimitive.content).isEqualTo("2026-07-31")
        assertThat(meal["name"]!!.jsonPrimitive.content).isEqualTo("Овсянка")
        assertThat(meal["calories"]!!.jsonPrimitive.int).isEqualTo(350)
        assertThat(meal["proteinG"]!!.jsonPrimitive.double).isEqualTo(12.5)
        assertThat(meal["fatG"]!!.jsonPrimitive.double).isEqualTo(7.0)
        assertThat(meal["carbsG"]!!.jsonPrimitive.double).isEqualTo(55.0)

        // Замеры веса: ручной и контрольный из тренировки (по timestamp — ручной первый).
        val bodyWeights = root["bodyWeights"]!!.jsonArray
        assertThat(bodyWeights).hasSize(2)
        val manual = bodyWeights[0].jsonObject
        assertThat(manual["weightKg"]!!.jsonPrimitive.double).isEqualTo(80.0)
        assertThat(manual["fromWorkout"]!!.jsonPrimitive.boolean).isFalse()
        val fromWorkout = bodyWeights[1].jsonObject
        assertThat(fromWorkout["weightKg"]!!.jsonPrimitive.double).isEqualTo(82.5)
        assertThat(fromWorkout["fromWorkout"]!!.jsonPrimitive.boolean).isTrue()
    }

    // ---------- CSV ----------

    @Test
    fun buildWorkoutSetsCsv_headerRowCount_escaping_decimalComma() = runTest {
        seedData()

        val lines = manager.buildWorkoutSetsCsv().trimEnd('\n').split("\n")

        assertThat(lines[0])
            .isEqualTo("date;start;end;duration_min;exercise;muscle_group;set_index;weight_kg;reps")
        assertThat(lines).hasSize(4) // заголовок + 3 подхода

        // Обычная строка (без кавычек) — можно разбирать по «;».
        val first = lines[1].split(";")
        val expectedDate = Instant.ofEpochMilli(t0).atZone(ZoneId.systemDefault())
            .toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
        assertThat(first[0]).isEqualTo(expectedDate)
        assertThat(first[3]).isEqualTo("45") // duration_min
        assertThat(first[4]).isEqualTo(plainExercise)
        assertThat(first[6]).isEqualTo("1") // set_index
        assertThat(first[7]).isEqualTo("62,5") // десятичная запятая
        assertThat(first[8]).isEqualTo("8")

        // Поле с ; и " — обёрнуто в кавычки, внутренние кавычки удвоены.
        assertThat(lines[3]).contains("\"Тяга \"\"верхнего\"\"; блока\"")
        assertThat(lines[3]).endsWith(";Спина;1;45;12") // группа;индекс;вес(без хвостовых нулей);повторы
    }

    @Test
    fun buildMealsCsv_headerRowCount_decimalComma() = runTest {
        seedData()

        val lines = manager.buildMealsCsv().trimEnd('\n').split("\n")

        assertThat(lines[0]).isEqualTo("date;time;name;calories;protein_g;fat_g;carbs_g")
        assertThat(lines).hasSize(3) // заголовок + 2 приёма пищи

        val first = lines[1].split(";") // «Овсянка» — раньше по timestamp
        assertThat(first[0]).isEqualTo("2026-07-31") // дата из epochDay — зоны не касается
        assertThat(first[2]).isEqualTo("Овсянка")
        assertThat(first[3]).isEqualTo("350")
        assertThat(first[4]).isEqualTo("12,5") // десятичная запятая
        assertThat(first[5]).isEqualTo("7") // без хвостовых нулей
        assertThat(first[6]).isEqualTo("55")

        val second = lines[2].split(";")
        assertThat(second[0]).isEqualTo("2026-08-01")
        assertThat(second[2]).isEqualTo("Курица с рисом")
        assertThat(second[6]).isEqualTo("70,5")
    }

    @Test
    fun buildWeightsCsv_headerRowCount_flagsAndDecimals() = runTest {
        seedData()

        val lines = manager.buildWeightsCsv().trimEnd('\n').split("\n")

        assertThat(lines[0]).isEqualTo("date;time;weight_kg;from_workout")
        assertThat(lines).hasSize(3) // заголовок + ручное + контрольное

        val manual = lines[1].split(";") // ручное — раньше по timestamp
        assertThat(manual[2]).isEqualTo("80")
        assertThat(manual[3]).isEqualTo("0")

        val fromWorkout = lines[2].split(";")
        assertThat(fromWorkout[2]).isEqualTo("82,5")
        assertThat(fromWorkout[3]).isEqualTo("1")
    }
}
