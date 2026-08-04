package com.dtyan.fitdiary.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneId
import java.time.ZonedDateTime

/** Точечные проверки DAO поверх реальной in-memory Room-БД (Robolectric). */
@RunWith(RobolectricTestRunner::class)
class DaoTest {

    private lateinit var db: AppDatabase
    private lateinit var exerciseDao: ExerciseDao
    private lateinit var workoutDao: WorkoutDao
    private lateinit var setDao: WorkoutSetDao
    private lateinit var mealDao: MealDao

    /** Фиксированное время: 2026-08-01T10:00 Europe/Moscow. */
    private val baseTime: Long = ZonedDateTime
        .of(2026, 8, 1, 10, 0, 0, 0, ZoneId.of("Europe/Moscow"))
        .toInstant().toEpochMilli()

    private val hour = 3_600_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        exerciseDao = db.exerciseDao()
        workoutDao = db.workoutDao()
        setDao = db.workoutSetDao()
        mealDao = db.mealDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---------- Хелперы ----------

    private suspend fun newExercise(name: String, group: String = "Грудь"): Long =
        exerciseDao.insert(Exercise(name = name, muscleGroup = group, isCustom = true))

    private suspend fun newWorkout(startedAt: Long, endedAt: Long? = null): Long =
        workoutDao.insert(Workout(startedAt = startedAt, endedAt = endedAt))

    private suspend fun addSet(
        workoutId: Long,
        exerciseId: Long,
        index: Int,
        weight: Double,
        reps: Int,
        at: Long,
    ): Long = setDao.insert(
        WorkoutSet(
            workoutId = workoutId,
            exerciseId = exerciseId,
            setIndex = index,
            weightKg = weight,
            reps = reps,
            completedAt = at,
        )
    )

    private fun meal(
        day: Long,
        ts: Long,
        name: String,
        cal: Int,
        p: Double = 10.0,
        f: Double = 5.0,
        c: Double = 20.0,
    ) = Meal(epochDay = day, timestamp = ts, name = name, calories = cal, proteinG = p, fatG = f, carbsG = c)

    private fun countRows(table: String): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    // ---------- previousWorkoutSets ----------

    @Test
    fun previousWorkoutSets_returnsLastFinishedWorkoutWithExercise_sortedBySetIndex() = runTest {
        val e1 = newExercise("Жим лёжа")
        val e2 = newExercise("Присед", "Ноги")

        // Старая завершённая с e1 — не должна вернуться (есть более свежая).
        val wOld = newWorkout(baseTime, baseTime + hour)
        addSet(wOld, e1, 1, 100.0, 5, baseTime + 10)
        addSet(wOld, e1, 2, 105.0, 5, baseTime + 20)

        // Последняя завершённая с e1 — именно её подходы должны вернуться.
        val wPrev = newWorkout(baseTime + 24 * hour, baseTime + 25 * hour)
        // Вставляем в «неправильном» порядке — проверяем сортировку по setIndex.
        addSet(wPrev, e1, 2, 112.5, 3, baseTime + 24 * hour + 20)
        addSet(wPrev, e1, 1, 110.0, 3, baseTime + 24 * hour + 10)

        // Ещё более поздняя завершённая, но БЕЗ e1 — пропускается.
        val wOther = newWorkout(baseTime + 48 * hour, baseTime + 49 * hour)
        addSet(wOther, e2, 1, 80.0, 8, baseTime + 48 * hour + 10)

        // Текущая активная с e1 — исключается.
        val wActive = newWorkout(baseTime + 72 * hour, endedAt = null)
        addSet(wActive, e1, 1, 999.0, 1, baseTime + 72 * hour + 10)

        val result = setDao.previousWorkoutSets(exerciseId = e1, excludeWorkoutId = wActive)

        assertThat(result.map { it.workoutId }.distinct()).containsExactly(wPrev)
        assertThat(result.map { it.setIndex }).containsExactly(1, 2).inOrder()
        assertThat(result.map { it.weightKg }).containsExactly(110.0, 112.5).inOrder()
    }

    // ---------- maxWeight / maxE1Rm ----------

    @Test
    fun maxWeight_and_maxE1Rm_overAllSets() = runTest {
        val e1 = newExercise("Жим лёжа")
        val w1 = newWorkout(baseTime, baseTime + hour)
        addSet(w1, e1, 1, 100.0, 5, baseTime + 10) // e1Rm ≈ 116,7
        addSet(w1, e1, 2, 110.0, 1, baseTime + 20) // максимум по весу; e1Rm ≈ 113,7
        val w2 = newWorkout(baseTime + 24 * hour, baseTime + 25 * hour)
        addSet(w2, e1, 1, 90.0, 12, baseTime + 24 * hour + 10) // e1Rm = 126 — максимум

        assertThat(setDao.maxWeight(e1)).isEqualTo(110.0)
        assertThat(setDao.maxE1Rm(e1)!!).isWithin(1e-9).of(90.0 * (1 + 12 / 30.0))
    }

    @Test
    fun maxWeight_and_maxE1Rm_noSets_areNull() = runTest {
        val e = newExercise("Присед", "Ноги")
        assertThat(setDao.maxWeight(e)).isNull()
        assertThat(setDao.maxE1Rm(e)).isNull()
    }

    // ---------- observeFinishedSummaries ----------

    @Test
    fun observeFinishedSummaries_aggregates_andExcludesActive() = runTest {
        val e1 = newExercise("Жим лёжа")
        val e2 = newExercise("Присед", "Ноги")

        val w1 = newWorkout(baseTime, baseTime + hour)
        addSet(w1, e1, 1, 100.0, 5, baseTime + 10)
        addSet(w1, e1, 2, 100.0, 5, baseTime + 20)
        addSet(w1, e2, 1, 50.0, 10, baseTime + 30)

        val w2 = newWorkout(baseTime + 24 * hour, baseTime + 25 * hour) // завершённая без подходов

        val wActive = newWorkout(baseTime + 48 * hour) // активная — не должна попасть
        addSet(wActive, e1, 1, 60.0, 8, baseTime + 48 * hour + 10)

        val summaries = workoutDao.observeFinishedSummaries().first()

        assertThat(summaries.map { it.id }).containsExactly(w2, w1).inOrder() // startedAt DESC
        val s1 = summaries.single { it.id == w1 }
        assertThat(s1.setCount).isEqualTo(3)
        assertThat(s1.exerciseCount).isEqualTo(2)
        assertThat(s1.totalVolume).isWithin(1e-9).of(100.0 * 5 + 100.0 * 5 + 50.0 * 10)
        val s2 = summaries.single { it.id == w2 }
        assertThat(s2.setCount).isEqualTo(0)
        assertThat(s2.exerciseCount).isEqualTo(0)
        assertThat(s2.totalVolume).isEqualTo(0.0)
    }

    // ---------- MealDao ----------

    @Test
    fun mealDao_dailyTotals_sumsPerDay() = runTest {
        val day1 = 20_000L
        val day2 = 20_001L
        mealDao.insert(meal(day1, 1000, "Овсянка", 350, p = 12.5, f = 7.0, c = 55.0))
        mealDao.insert(meal(day1, 2000, "Курица", 650, p = 45.0, f = 20.0, c = 10.0))
        mealDao.insert(meal(day2, 3000, "Творог", 200, p = 30.0, f = 5.0, c = 8.0))

        val totals = mealDao.dailyTotals(day1, day2)

        assertThat(totals).hasSize(2)
        val t1 = totals[0]
        assertThat(t1.epochDay).isEqualTo(day1)
        assertThat(t1.calories).isEqualTo(1000)
        assertThat(t1.proteinG).isWithin(1e-9).of(57.5)
        assertThat(t1.fatG).isWithin(1e-9).of(27.0)
        assertThat(t1.carbsG).isWithin(1e-9).of(65.0)
        val t2 = totals[1]
        assertThat(t2.epochDay).isEqualTo(day2)
        assertThat(t2.calories).isEqualTo(200)
    }

    @Test
    fun mealDao_recentDistinct_uniqueByName_latestVersionFirst() = runTest {
        mealDao.insert(meal(1, 1000, "Овсянка", 300))
        mealDao.insert(meal(1, 2000, "Курица", 650))
        mealDao.insert(meal(2, 3000, "Овсянка", 380)) // новая версия того же блюда

        val recent = mealDao.recentDistinct(10)

        assertThat(recent.map { it.name }).containsExactly("Овсянка", "Курица").inOrder() // id DESC
        assertThat(recent.first { it.name == "Овсянка" }.calories).isEqualTo(380) // последняя версия

        val limited = mealDao.recentDistinct(1)
        assertThat(limited.map { it.name }).containsExactly("Овсянка")
    }

    @Test
    fun mealDao_daysWithMeals_rangeBoundariesInclusive_distinct() = runTest {
        mealDao.insert(meal(100, 1000, "Завтрак", 300))
        mealDao.insert(meal(100, 2000, "Обед", 500)) // тот же день — не дублируется
        mealDao.insert(meal(101, 3000, "Ужин", 400))
        mealDao.insert(meal(105, 4000, "Перекус", 150))

        assertThat(mealDao.daysWithMeals(100, 104)).containsExactly(100L, 101L)
        assertThat(mealDao.daysWithMeals(101, 105)).containsExactly(101L, 105L)
        assertThat(mealDao.daysWithMeals(102, 104)).isEmpty()
    }

    // ---------- Каскадное удаление ----------

    @Test
    fun deletingWorkout_cascadesToItsSets() = runTest {
        val e = newExercise("Жим лёжа")
        val w = newWorkout(baseTime, baseTime + hour)
        addSet(w, e, 1, 100.0, 5, baseTime + 10)
        addSet(w, e, 2, 100.0, 5, baseTime + 20)
        // Подходы другой тренировки не должны пострадать.
        val wKeep = newWorkout(baseTime + 24 * hour, baseTime + 25 * hour)
        addSet(wKeep, e, 1, 80.0, 8, baseTime + 24 * hour + 10)

        assertThat(countRows("workout_sets")).isEqualTo(3)

        workoutDao.delete(workoutDao.getById(w)!!)

        assertThat(workoutDao.getById(w)).isNull()
        assertThat(countRows("workout_sets")).isEqualTo(1)
        assertThat(setDao.getForWorkoutOnce(wKeep)).hasSize(1)
    }
}
