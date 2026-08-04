package com.dtyan.fitdiary.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dtyan.fitdiary.data.db.AppDatabase
import com.dtyan.fitdiary.data.db.Workout
import com.dtyan.fitdiary.data.db.WorkoutSet
import com.dtyan.fitdiary.data.repo.ExerciseRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Тесты ExerciseRepository поверх реальной in-memory Room-БД (Robolectric). */
@RunWith(RobolectricTestRunner::class)
class ExerciseRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ExerciseRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = ExerciseRepository(db.exerciseDao(), db.workoutSetDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun addSet(workoutId: Long, exerciseId: Long, index: Int, at: Long) {
        db.workoutSetDao().insert(
            WorkoutSet(
                workoutId = workoutId,
                exerciseId = exerciseId,
                setIndex = index,
                weightKg = 50.0,
                reps = 8,
                completedAt = at,
            )
        )
    }

    // ---------- addCustom ----------

    @Test
    fun addCustom_defaults_equipmentIsTrenazher_photoIsNull() = runTest {
        val id = repo.addCustom("  Жим в хаммере  ", "Грудь")

        val e = repo.getById(id)!!
        assertThat(e.name).isEqualTo("Жим в хаммере") // имя триммится
        assertThat(e.muscleGroup).isEqualTo("Грудь")
        assertThat(e.equipment).isEqualTo("Тренажёр") // дефолт репозитория
        assertThat(e.photoPath).isNull()
        assertThat(e.isCustom).isTrue()
        assertThat(e.isArchived).isFalse()
    }

    @Test
    fun addCustom_savesEquipmentAndPhotoPath() = runTest {
        val id = repo.addCustom(
            "Тяга нижнего блока",
            "Спина",
            equipment = "Блок",
            photoPath = "exercise_photos/block.jpg",
        )

        val e = repo.getById(id)!!
        assertThat(e.equipment).isEqualTo("Блок")
        assertThat(e.photoPath).isEqualTo("exercise_photos/block.jpg")
    }

    // ---------- updatePhoto ----------

    @Test
    fun updatePhoto_set_replace_clear() = runTest {
        val id = repo.addCustom("Жим ногами", "Ноги")
        assertThat(repo.getById(id)!!.photoPath).isNull()

        // Установка.
        repo.updatePhoto(repo.getById(id)!!, "exercise_photos/one.jpg")
        assertThat(repo.getById(id)!!.photoPath).isEqualTo("exercise_photos/one.jpg")

        // Замена.
        repo.updatePhoto(repo.getById(id)!!, "exercise_photos/two.jpg")
        assertThat(repo.getById(id)!!.photoPath).isEqualTo("exercise_photos/two.jpg")

        // Сброс в null; остальные поля не задеты.
        repo.updatePhoto(repo.getById(id)!!, null)
        val cleared = repo.getById(id)!!
        assertThat(cleared.photoPath).isNull()
        assertThat(cleared.name).isEqualTo("Жим ногами")
        assertThat(cleared.equipment).isEqualTo("Тренажёр")
    }

    // ---------- usedExerciseIds ----------

    @Test
    fun usedExerciseIds_reflectsSets_distinct() = runTest {
        val e1 = repo.addCustom("Жим лёжа", "Грудь")
        val e2 = repo.addCustom("Присед", "Ноги")
        repo.addCustom("Молотки", "Бицепс") // без подходов — не должен попасть

        assertThat(repo.usedExerciseIds()).isEmpty()

        val w = db.workoutDao().insert(Workout(startedAt = 1000))
        addSet(w, e1, 1, at = 1100)
        assertThat(repo.usedExerciseIds()).containsExactly(e1)

        addSet(w, e2, 1, at = 1200)
        addSet(w, e1, 2, at = 1300) // повторный подход не дублирует id
        assertThat(repo.usedExerciseIds()).containsExactly(e1, e2)
    }

    // ---------- observeExercises ----------

    @Test
    fun observeExercises_hidesArchived() = runTest {
        val e1 = repo.addCustom("Жим лёжа", "Грудь")
        val e2 = repo.addCustom("Присед", "Ноги")

        assertThat(repo.observeExercises().first().map { it.id }).containsExactly(e1, e2)

        repo.archive(repo.getById(e2)!!)

        val visible = repo.observeExercises().first()
        assertThat(visible.map { it.id }).containsExactly(e1)
        // Из БД упражнение не удалено — только скрыто.
        assertThat(repo.getById(e2)!!.isArchived).isTrue()
    }
}
