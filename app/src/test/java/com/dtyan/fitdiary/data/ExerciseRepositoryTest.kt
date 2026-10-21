package com.dtyan.fitdiary.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dtyan.fitdiary.data.db.AppDatabase
import com.dtyan.fitdiary.data.db.Athlete
import com.dtyan.fitdiary.data.db.FavoriteExercise
import com.dtyan.fitdiary.data.db.Workout
import com.dtyan.fitdiary.data.db.WorkoutSet
import com.dtyan.fitdiary.data.repo.ExerciseRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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

    // ---------- избранное ----------

    /** In-memory БД без сид-колбэка: профили для внешних ключей избранного заводим сами. */
    private suspend fun addProfiles() {
        db.athleteDao().insert(Athlete(1, "Я"))
        db.athleteDao().insert(Athlete(2, "Друг"))
    }

    private fun favoriteCreatedAt(athleteId: Long, exerciseId: Long): Long? =
        db.openHelper.readableDatabase.query(
            "SELECT createdAt FROM favorite_exercises WHERE athleteId = ? AND exerciseId = ?",
            arrayOf<Any>(athleteId, exerciseId),
        ).use { if (it.moveToFirst()) it.getLong(0) else null }

    @Test
    fun favorites_arePerProfile_whileCatalogIsShared() = runTest {
        addProfiles()
        val e1 = repo.addCustom("Жим лёжа", "Грудь")
        val e2 = repo.addCustom("Присед", "Ноги")
        val friend = ExerciseRepository(db.exerciseDao(), db.workoutSetDao(), MutableStateFlow(2L))

        repo.setFavorite(e1, true) // активный профиль 1 по умолчанию
        friend.setFavorite(e2, true) // активный профиль 2

        assertThat(repo.observeFavoriteIds().first()).containsExactly(e1)
        assertThat(friend.observeFavoriteIds().first()).containsExactly(e2)
        assertThat(repo.observeFavoriteIds(athleteId = 2).first()).containsExactly(e2)
        // Каталог общий: оба видят оба упражнения.
        assertThat(friend.observeExercises().first().map { it.id }).containsExactly(e1, e2)

        // Снятие отметки у одного профиля не трогает другой.
        friend.setFavorite(e1, true)
        repo.setFavorite(e1, false)
        assertThat(repo.observeFavoriteIds().first()).isEmpty()
        assertThat(friend.observeFavoriteIds().first()).containsExactly(e1, e2)
    }

    @Test
    fun setFavorite_isIdempotent_andKeepsOriginalTime() = runTest {
        addProfiles()
        val e1 = repo.addCustom("Жим лёжа", "Грудь")
        db.exerciseDao().insertFavorite(FavoriteExercise(athleteId = 1, exerciseId = e1, createdAt = 1L))

        repo.setFavorite(e1, true)
        repo.setFavorite(e1, true)
        assertThat(db.exerciseDao().getFavoriteIds(1)).containsExactly(e1) // одна строка
        assertThat(favoriteCreatedAt(1, e1)).isEqualTo(1L) // время первой отметки не сдвинулось

        repo.setFavorite(e1, false)
        repo.setFavorite(e1, false) // повторное снятие — без ошибки
        repo.setFavorite(e1, false, athleteId = 2) // и снятие никогда не отмеченного
        assertThat(db.exerciseDao().getFavoriteIds(1)).isEmpty()
        assertThat(favoriteCreatedAt(1, e1)).isNull()

        val before = System.currentTimeMillis()
        repo.setFavorite(e1, true)
        assertThat(favoriteCreatedAt(1, e1)!!).isAtLeast(before)
    }

    @Test
    fun setFavorite_rejectsMissingProfileOrExercise() = runTest {
        addProfiles()
        val e1 = repo.addCustom("Жим лёжа", "Грудь")
        val noProfile = ExerciseRepository(db.exerciseDao(), db.workoutSetDao(), MutableStateFlow(0L))

        assertThat(runCatching { noProfile.setFavorite(e1, true) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(runCatching { repo.setFavorite(e1, true, athleteId = -1) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(runCatching { repo.setFavorite(0, true) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        // Внешние ключи: несуществующие профиль и упражнение не попадают в избранное.
        assertThat(runCatching { repo.setFavorite(e1, true, athleteId = 99) }.isFailure).isTrue()
        assertThat(runCatching { repo.setFavorite(999, true) }.isFailure).isTrue()
        assertThat(db.exerciseDao().getFavoriteIds(1)).isEmpty()
        assertThat(db.exerciseDao().getFavoriteIds(99)).isEmpty()
    }

    @Test
    fun observeFavoriteIds_emitsEachChangeOfThisProfileOnly() = runTest {
        addProfiles()
        val e1 = repo.addCustom("Жим лёжа", "Грудь")
        val e2 = repo.addCustom("Присед", "Ноги")
        val emissions = Channel<Set<Long>>(Channel.UNLIMITED)
        // Room выдаёт значения со своих потоков, поэтому ждём в реальном времени.
        backgroundScope.launch(Dispatchers.Default) { repo.observeFavoriteIds(1).collect { emissions.send(it) } }
        suspend fun next(): Set<Long> = withContext(Dispatchers.Default) { withTimeout(5_000) { emissions.receive() } }

        assertThat(next()).isEmpty()
        repo.setFavorite(e1, true)
        assertThat(next()).containsExactly(e1)
        // Изменение чужого избранного не порождает повторной выдачи того же набора.
        repo.setFavorite(e2, true, athleteId = 2)
        repo.setFavorite(e2, true)
        assertThat(next()).containsExactly(e1, e2)
        repo.setFavorite(e1, false)
        assertThat(next()).containsExactly(e2)
        repo.setFavorite(e2, false)
        assertThat(next()).isEmpty()
    }
}
