package com.dtyan.fitdiary.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dtyan.fitdiary.data.db.AppDatabase
import com.dtyan.fitdiary.data.db.Exercise
import com.dtyan.fitdiary.data.repo.WorkoutRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneId
import java.time.ZonedDateTime

/** Тесты WorkoutRepository поверх реальной in-memory Room-БД (Robolectric). */
@RunWith(RobolectricTestRunner::class)
class WorkoutRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: WorkoutRepository

    /** Фиксированное время: 2026-08-01T10:00 Europe/Moscow. */
    private val t0: Long = ZonedDateTime
        .of(2026, 8, 1, 10, 0, 0, 0, ZoneId.of("Europe/Moscow"))
        .toInstant().toEpochMilli()

    private val min = 60_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = WorkoutRepository(
            workoutDao = db.workoutDao(),
            setDao = db.workoutSetDao(),
            weightDao = db.weightDao(),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun exercise(name: String, group: String = "Грудь"): Long =
        db.exerciseDao().insert(Exercise(name = name, muscleGroup = group, isCustom = true))

    private fun countRows(table: String): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    // ---------- startWorkout ----------

    @Test
    fun startWorkout_createsActive_secondCallReturnsSameId() = runTest {
        val id1 = repo.startWorkout(now = t0)

        val active = db.workoutDao().getActiveOnce()
        assertThat(active).isNotNull()
        assertThat(active!!.id).isEqualTo(id1)
        assertThat(active.startedAt).isEqualTo(t0)
        assertThat(active.endedAt).isNull()

        // Повторный старт при активной тренировке возвращает её же id, второй не создаёт.
        val id2 = repo.startWorkout(now = t0 + 5 * min)
        assertThat(id2).isEqualTo(id1)
        assertThat(countRows("workouts")).isEqualTo(1)
    }

    // ---------- finishWorkout ----------

    @Test
    fun finishWorkout_setsEndAndWeight_createsWeightEntry_repeatIsNoOp() = runTest {
        val id = repo.startWorkout(now = t0)

        repo.finishWorkout(id, bodyWeightKg = 82.5, now = t0 + 45 * min)

        val workout = db.workoutDao().getById(id)!!
        assertThat(workout.endedAt).isEqualTo(t0 + 45 * min)
        assertThat(workout.bodyWeightKg).isEqualTo(82.5)

        val entries = db.weightDao().getAllOnce()
        assertThat(entries).hasSize(1)
        assertThat(entries[0].weightKg).isEqualTo(82.5)
        assertThat(entries[0].fromWorkout).isTrue()
        assertThat(entries[0].timestamp).isEqualTo(t0 + 45 * min)

        // Повторное завершение — no-op: ничего не меняется, второй замер не появляется.
        repo.finishWorkout(id, bodyWeightKg = 90.0, now = t0 + 60 * min)
        val after = db.workoutDao().getById(id)!!
        assertThat(after.endedAt).isEqualTo(t0 + 45 * min)
        assertThat(after.bodyWeightKg).isEqualTo(82.5)
        assertThat(db.weightDao().getAllOnce()).hasSize(1)
    }

    @Test
    fun finishWorkout_withoutWeight_doesNotCreateWeightEntry() = runTest {
        val id = repo.startWorkout(now = t0)

        repo.finishWorkout(id, bodyWeightKg = null, now = t0 + 30 * min)

        val workout = db.workoutDao().getById(id)!!
        assertThat(workout.endedAt).isEqualTo(t0 + 30 * min)
        assertThat(workout.bodyWeightKg).isNull()
        assertThat(db.weightDao().getAllOnce()).isEmpty()
    }

    // ---------- cancelWorkout ----------

    @Test
    fun cancelWorkout_deletesWorkoutAndItsSets() = runTest {
        val e = exercise("Жим лёжа")
        val id = repo.startWorkout(now = t0)
        repo.addSet(id, e, 100.0, 5, now = t0 + min)
        repo.addSet(id, e, 100.0, 5, now = t0 + 2 * min)
        assertThat(countRows("workout_sets")).isEqualTo(2)

        repo.cancelWorkout(id)

        assertThat(db.workoutDao().getById(id)).isNull()
        assertThat(countRows("workouts")).isEqualTo(0)
        assertThat(countRows("workout_sets")).isEqualTo(0)
    }

    // ---------- addSet ----------

    @Test
    fun addSet_assignsSetIndexPerExerciseWithinWorkout() = runTest {
        val eA = exercise("Жим лёжа")
        val eB = exercise("Присед", "Ноги")
        val w1 = repo.startWorkout(now = t0)

        assertThat(repo.addSet(w1, eA, 100.0, 5, now = t0 + min).set.setIndex).isEqualTo(1)
        assertThat(repo.addSet(w1, eA, 100.0, 5, now = t0 + 2 * min).set.setIndex).isEqualTo(2)
        assertThat(repo.addSet(w1, eA, 100.0, 5, now = t0 + 3 * min).set.setIndex).isEqualTo(3)

        // Другое упражнение в той же тренировке — своя нумерация с 1.
        assertThat(repo.addSet(w1, eB, 80.0, 8, now = t0 + 4 * min).set.setIndex).isEqualTo(1)

        // Другая тренировка — нумерация того же упражнения начинается заново.
        repo.finishWorkout(w1, bodyWeightKg = null, now = t0 + 50 * min)
        val w2 = repo.startWorkout(now = t0 + 100 * min)
        assertThat(repo.addSet(w2, eA, 100.0, 5, now = t0 + 101 * min).set.setIndex).isEqualTo(1)
    }

    @Test
    fun addSet_prFlags_firstEverBothFalse_thenWeightPr_thenE1RmPrOnly() = runTest {
        val e = exercise("Жим лёжа")
        val w = repo.startWorkout(now = t0)

        // Самый первый подход в истории упражнения — рекорды не отмечаются.
        val first = repo.addSet(w, e, 100.0, 5, now = t0 + min)
        assertThat(first.isWeightPr).isFalse()
        assertThat(first.isE1RmPr).isFalse()

        // Больший вес → рекорд по весу (расчётный 1ПМ тоже вырос).
        val heavier = repo.addSet(w, e, 105.0, 5, now = t0 + 2 * min)
        assertThat(heavier.isWeightPr).isTrue()
        assertThat(heavier.isE1RmPr).isTrue()

        // Тот же вес, больше повторов → только рекорд по 1ПМ.
        val moreReps = repo.addSet(w, e, 105.0, 6, now = t0 + 3 * min)
        assertThat(moreReps.isWeightPr).isFalse()
        assertThat(moreReps.isE1RmPr).isTrue()
    }

    // ---------- lastSetForPrefill ----------

    @Test
    fun lastSetForPrefill_currentWorkoutFirst_thenPreviousWorkout_thenNull() = runTest {
        val e = exercise("Жим лёжа")
        val eNoHistory = exercise("Присед", "Ноги")

        // Совсем без истории → null.
        val w1 = repo.startWorkout(now = t0)
        assertThat(repo.lastSetForPrefill(w1, e)).isNull()

        repo.addSet(w1, e, 100.0, 5, now = t0 + min)
        repo.addSet(w1, e, 105.0, 3, now = t0 + 2 * min)
        repo.finishWorkout(w1, bodyWeightKg = null, now = t0 + 50 * min)

        // Текущая тренировка пуста по упражнению → последний подход прошлой.
        val w2 = repo.startWorkout(now = t0 + 100 * min)
        val fromPrev = repo.lastSetForPrefill(w2, e)
        assertThat(fromPrev).isNotNull()
        assertThat(fromPrev!!.workoutId).isEqualTo(w1)
        assertThat(fromPrev.setIndex).isEqualTo(2)
        assertThat(fromPrev.weightKg).isEqualTo(105.0)
        assertThat(fromPrev.reps).isEqualTo(3)

        // Подход текущей тренировки приоритетнее прошлой.
        repo.addSet(w2, e, 110.0, 2, now = t0 + 101 * min)
        val fromCurrent = repo.lastSetForPrefill(w2, e)
        assertThat(fromCurrent).isNotNull()
        assertThat(fromCurrent!!.workoutId).isEqualTo(w2)
        assertThat(fromCurrent.weightKg).isEqualTo(110.0)
        assertThat(fromCurrent.reps).isEqualTo(2)

        // Упражнение без какой-либо истории → null.
        assertThat(repo.lastSetForPrefill(w2, eNoHistory)).isNull()
    }
}
