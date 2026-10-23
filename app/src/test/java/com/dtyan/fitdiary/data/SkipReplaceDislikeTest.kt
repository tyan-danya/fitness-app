package com.dtyan.fitdiary.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dtyan.fitdiary.data.db.AppDatabase
import com.dtyan.fitdiary.data.db.Athlete
import com.dtyan.fitdiary.data.db.Exercise
import com.dtyan.fitdiary.data.db.WorkoutExercise
import com.dtyan.fitdiary.data.repo.ExerciseRepository
import com.dtyan.fitdiary.data.repo.TemplatePlanItem
import com.dtyan.fitdiary.data.repo.TemplateRepository
import com.dtyan.fitdiary.data.repo.WorkoutRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** «Пропустить в этой тренировке», замена упражнения и «Не нравится» поверх настоящей Room. */
@RunWith(RobolectricTestRunner::class)
class SkipReplaceDislikeTest {
    private lateinit var db: AppDatabase
    private lateinit var workouts: WorkoutRepository
    private lateinit var templates: TemplateRepository
    private lateinit var exercises: ExerciseRepository
    private val active = MutableStateFlow(1L)
    private var friend = 0L

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        workouts = WorkoutRepository(db.workoutDao(), db.workoutSetDao(), db.weightDao(), active)
        templates = TemplateRepository(db, active)
        exercises = ExerciseRepository(db.exerciseDao(), db.workoutSetDao(), active)
        db.athleteDao().insert(Athlete(id = 1, name = "Я"))
        friend = db.athleteDao().insert(Athlete(name = "Друг"))
    }

    @After fun tearDown() = db.close()

    private suspend fun exercise(name: String, group: String = "Ноги") =
        db.exerciseDao().insert(Exercise(name = name, muscleGroup = group, equipment = "Своё тело"))

    /** Тренировка по плану из [ids] с ориентиром 1×10 и заметкой у каждого упражнения. */
    private suspend fun workoutWith(vararg ids: Long, athleteId: Long = 1L): Long {
        val workoutId = workouts.startWorkout(now = 1000, athleteId = athleteId)
        ids.forEachIndexed { index, id ->
            db.workoutDao().insertPlan(WorkoutExercise(workoutId, id, index + 1, targetSets = 1, targetReps = 10, note = "Старая заметка"))
        }
        return workoutId
    }

    private suspend fun nextInPlan(workoutId: Long): Long? = workouts.observePlan(workoutId).first()
        .firstOrNull { it.completedAt == null && it.skippedAt == null }?.exercise?.id

    @Test fun skippedExerciseIsNoLongerNext_andComesBackWhenUnskippedOrRecorded() = runTest {
        val bridge = exercise("Ягодичный мост без веса")
        val bench = exercise("Вставание со скамьи")
        val press = exercise("Жим ногами")
        val workoutId = workoutWith(bridge, bench, press)
        assertThat(nextInPlan(workoutId)).isEqualTo(bridge)

        workouts.skipExercise(workoutId, bridge, true, now = 2000)
        workouts.skipExercise(workoutId, bench, true, now = 2100)
        assertThat(nextInPlan(workoutId)).isEqualTo(press)
        // Повторный пропуск не сдвигает время, план и ориентиры не меняются.
        workouts.skipExercise(workoutId, bridge, true, now = 9000)
        val plan = workouts.observePlan(workoutId).first()
        assertThat(plan.map { it.skippedAt }).containsExactly(2000L, 2100L, null).inOrder()
        assertThat(plan.map { it.targetReps }).containsExactly(10, 10, 10)

        workouts.skipExercise(workoutId, bridge, false)
        assertThat(nextInPlan(workoutId)).isEqualTo(bridge)
        // Подход по пропущенному упражнению возвращает его в план.
        workouts.addSet(workoutId, bench, 0.0, 8, now = 3000)
        assertThat(workouts.observePlan(workoutId).first().first { it.exercise.id == bench }.skippedAt).isNull()
        // «Выполнено» снимает пропуск, а выполненное пропустить нельзя.
        workouts.skipExercise(workoutId, bench, true, now = 3100)
        workouts.markExerciseComplete(workoutId, bench, true, now = 3200)
        val done = workouts.observePlan(workoutId).first().first { it.exercise.id == bench }
        assertThat(done.completedAt).isEqualTo(3200L)
        assertThat(done.skippedAt).isNull()
        assertThat(runCatching { workouts.skipExercise(workoutId, bench, true) }.exceptionOrNull()?.message)
            .isEqualTo("Упражнение уже выполнено")
    }

    @Test fun skipIsPerParticipantAndOnlyForActiveWorkouts() = runTest {
        val bridge = exercise("Ягодичный мост без веса")
        val members = workouts.startGroupWorkout(listOf(1L, friend), now = 1000)
        members.forEach { db.workoutDao().insertPlan(WorkoutExercise(it.id, bridge, 1)) }
        val mine = members.first { it.athleteId == 1L }.id
        val theirs = members.first { it.athleteId == friend }.id

        workouts.skipExercise(mine, bridge, true)
        assertThat(workouts.observePlan(mine).first().single().skippedAt).isNotNull()
        assertThat(workouts.observePlan(theirs).first().single().skippedAt).isNull()

        workouts.finishWorkout(mine, now = 5000)
        assertThat(runCatching { workouts.skipExercise(mine, bridge, false) }.exceptionOrNull()?.message)
            .isEqualTo("Тренировка уже завершена")
        assertThat(runCatching { workouts.skipExercise(theirs, 999, true) }.exceptionOrNull()?.message)
            .isEqualTo("Упражнение не найдено в плане")
    }

    @Test fun replacementTakesTheSamePlaceAndTargetsInWorkoutAndPrograms() = runTest {
        val row = exercise("Тяга с опорой", "Спина")
        val bridge = exercise("Ягодичный мост без веса")
        val curl = exercise("Сгибание рук", "Бицепс")
        val legCurl = exercise("Сгибание ног лёжа")
        val programB = templates.create("B — тяга с опорой и лёгкие ноги", listOf(
            TemplatePlanItem(row, 3, 10, "Грудь на опоре"), TemplatePlanItem(bridge, 1, 10, "Лёжа на спине…"), TemplatePlanItem(curl, 2, 10)))
        val programA = templates.create("A — без моста", listOf(TemplatePlanItem(row)))
        val friendsProgram = templates.create("Друг: с мостом", listOf(TemplatePlanItem(bridge)), athleteId = friend)
        val workoutId = workoutWith(row, bridge, curl)

        assertThat(templates.templatesWithExercise(bridge, 1).map { it.id }).containsExactly(programB)
        templates.replaceExercise(bridge, legCurl, workoutId, listOf(programB), athleteId = 1, now = 2000)

        val plan = workouts.observePlan(workoutId).first()
        assertThat(plan.map { it.exercise.id }).containsExactly(row, legCurl, curl).inOrder()
        val replaced = plan[1]
        assertThat(replaced.targetSets to replaced.targetReps).isEqualTo(1 to 10)
        assertThat(replaced.note).isEqualTo("Вместо «Ягодичный мост без веса».")
        assertThat(replaced.completedAt).isNull()
        val items = templates.getTemplate(programB, 1)!!.items
        assertThat(items.map { it.exercise.id }).containsExactly(row, legCurl, curl).inOrder()
        assertThat(items[1].targetSets to items[1].targetReps).isEqualTo(1 to 10)
        assertThat(items[1].note).isEqualTo("Вместо «Ягодичный мост без веса».")
        assertThat(items[0].note).isEqualTo("Грудь на опоре")
        // Чужая программа и программа без моста не тронуты.
        assertThat(templates.getTemplate(friendsProgram, friend)!!.items.single().exercise.id).isEqualTo(bridge)
        assertThat(templates.getTemplate(programA, 1)!!.items.single().exercise.id).isEqualTo(row)
    }

    @Test fun startedExerciseStaysCompletedAndReplacementGoesRightAfterIt() = runTest {
        val bench = exercise("Вставание со скамьи")
        val press = exercise("Жим ногами")
        val curl = exercise("Сгибание ног лёжа")
        val workoutId = workoutWith(bench, curl)
        workouts.addSet(workoutId, bench, 0.0, 6, now = 1500)

        templates.replaceExercise(bench, press, workoutId, emptyList(), athleteId = 1, now = 2000)

        val plan = workouts.observePlan(workoutId).first()
        assertThat(plan.map { it.exercise.id }).containsExactly(bench, press, curl).inOrder()
        assertThat(plan[0].completedAt).isEqualTo(2000L)
        assertThat(plan[0].setCount).isEqualTo(1)
        assertThat(nextInPlan(workoutId)).isEqualTo(press)
    }

    @Test fun replacementIsAllOrNothingAndRejectsDuplicatesOrForeignData() = runTest {
        val row = exercise("Тяга с опорой", "Спина")
        val bridge = exercise("Ягодичный мост без веса")
        val legCurl = exercise("Сгибание ног лёжа")
        val programB = templates.create("B", listOf(TemplatePlanItem(bridge), TemplatePlanItem(legCurl)))
        val workoutId = workoutWith(row, bridge)

        // Замена уже есть в программе — тренировка тоже остаётся прежней.
        assertThat(runCatching { templates.replaceExercise(bridge, legCurl, workoutId, listOf(programB), athleteId = 1) }
            .exceptionOrNull()?.message).isEqualTo("«Сгибание ног лёжа» уже есть в программе «B»")
        assertThat(workouts.observePlan(workoutId).first().map { it.exercise.id }).containsExactly(row, bridge).inOrder()

        assertThat(runCatching { templates.replaceExercise(bridge, row, workoutId, emptyList(), athleteId = 1) }
            .exceptionOrNull()?.message).isEqualTo("Это упражнение уже есть в тренировке")
        assertThat(runCatching { templates.replaceExercise(bridge, legCurl, workoutId, emptyList(), athleteId = friend) }
            .exceptionOrNull()?.message).isEqualTo("Эта тренировка принадлежит другому профилю")
        assertThat(runCatching { templates.replaceExercise(bridge, legCurl, null, listOf(programB), athleteId = friend) }
            .exceptionOrNull()?.message).isEqualTo("Шаблон не найден в этом профиле")
        assertThat(runCatching { templates.replaceExercise(bridge, legCurl, null, emptyList(), athleteId = 1) }
            .exceptionOrNull()?.message).isEqualTo("Выберите, где заменить")
        assertThat(templates.getTemplate(programB, 1)!!.items.map { it.exercise.id }).containsExactly(bridge, legCurl).inOrder()
    }

    @Test fun dislikeAndFavoriteAreExclusivePerProfile() = runTest {
        val bridge = exercise("Ягодичный мост без веса")
        exercises.setFavorite(bridge, true, athleteId = 1)
        exercises.setFavorite(bridge, true, athleteId = friend)

        exercises.setDisliked(bridge, true, athleteId = 1)
        assertThat(exercises.observeDislikedIds(1).first()).containsExactly(bridge)
        assertThat(exercises.observeFavoriteIds(1).first()).isEmpty()
        // У друга своя отметка: его избранное не тронуто.
        assertThat(exercises.observeFavoriteIds(friend).first()).containsExactly(bridge)
        assertThat(exercises.observeDislikedIds(friend).first()).isEmpty()

        exercises.setDisliked(bridge, true, athleteId = 1) // повтор ничего не ломает
        exercises.setFavorite(bridge, true, athleteId = 1)
        assertThat(exercises.observeDislikedIds(1).first()).isEmpty()
        assertThat(exercises.observeFavoriteIds(1).first()).containsExactly(bridge)
        exercises.setDisliked(bridge, true, athleteId = 1)
        exercises.setDisliked(bridge, false, athleteId = 1)
        assertThat(exercises.observeDislikedIds(1).first()).isEmpty()
        assertThat(exercises.observeFavoriteIds(1).first()).isEmpty()
        assertThat(runCatching { exercises.setDisliked(bridge, true, athleteId = 0) }.isFailure).isTrue()
    }

    @Test fun programItemRefsListEveryProgramOfTheProfileInOrder() = runTest {
        val row = exercise("Тяга с опорой", "Спина")
        val bridge = exercise("Ягодичный мост без веса")
        val b = templates.create("B", listOf(TemplatePlanItem(row), TemplatePlanItem(bridge)))
        templates.create("Друг", listOf(TemplatePlanItem(bridge)), athleteId = friend)

        val refs = templates.observeItemRefs(1).first()
        assertThat(refs.map { it.templateId to it.name }).containsExactly(b to "Тяга с опорой", b to "Ягодичный мост без веса").inOrder()
    }
}
