package com.dtyan.fitdiary.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dtyan.fitdiary.data.db.AppDatabase
import com.dtyan.fitdiary.data.db.Athlete
import com.dtyan.fitdiary.data.db.Exercise
import com.dtyan.fitdiary.data.db.WorkoutExercise
import com.dtyan.fitdiary.data.repo.ImportedTemplateExercise
import com.dtyan.fitdiary.data.repo.TemplatePlanItem
import com.dtyan.fitdiary.data.repo.TemplateRepository
import com.dtyan.fitdiary.data.repo.WorkoutRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TemplateRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var templates: TemplateRepository
    private lateinit var workouts: WorkoutRepository
    private val active = MutableStateFlow(1L)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        templates = TemplateRepository(db, active)
        workouts = WorkoutRepository(db.workoutDao(), db.workoutSetDao(), db.weightDao(), active)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun exercise(name: String = "Жим"): Long =
        db.exerciseDao().insert(Exercise(name = name, muscleGroup = "Грудь", equipment = "Штанга"))

    private suspend fun friend(name: String = "Друг"): Long {
        db.athleteDao().insert(Athlete(id = 1, name = "Я"))
        return db.athleteDao().insert(Athlete(name = name))
    }

    @Test
    fun personalTemplatesKeepOwnerWhenGlobalProfileChanges() = runTest {
        val other = friend()
        val e = exercise()
        val mine = templates.create("Мой план", listOf(TemplatePlanItem(e)))
        active.value = other
        assertThat(templates.observeTemplates().first()).isEmpty()
        assertThat(templates.getTemplate(mine)).isNull()
        assertThat(templates.observeTemplate(mine).first()).isNull()
        assertThat(runCatching { templates.delete(mine) }.isFailure).isTrue()
        assertThat(runCatching { templates.update(mine, "Чужое изменение", listOf(TemplatePlanItem(e))) }.isFailure).isTrue()
        // An already accepted UI action uses its captured owner, even if its coroutine ran later.
        templates.update(mine, "Переименован", listOf(TemplatePlanItem(e, 4, 8)), athleteId = 1)
        val theirs = templates.create("Другой план", listOf(TemplatePlanItem(e, 2)))
        assertThat(templates.observeTemplates(1).first().single().name).isEqualTo("Переименован")
        assertThat(templates.observeTemplates(other).first().single().id).isEqualTo(theirs)
    }

    @Test
    fun groupStartsIndependentOrderedPlansAndCompletionNeverInventsSets() = runTest {
        val other = friend()
        val third = friend("Ещё друг")
        val e1 = exercise("Жим" )
        val e2 = exercise("Отжимания")
        val id = templates.create("Верх", listOf(TemplatePlanItem(e2, 2, 12, "Без спешки"), TemplatePlanItem(e1, 4, 8)))
        val group = templates.startTemplate(id, listOf(1, other, third), now = 10)
        assertThat(group.map { it.groupSessionId }.distinct()).hasSize(1)
        assertThat(group.first().groupSessionId).isNotNull()
        for (w in group) {
            val plan = workouts.observePlan(w.id).first()
            assertThat(plan.map { it.exerciseId }).containsExactly(e2, e1).inOrder()
            assertThat(plan.map { it.targetSets }).containsExactly(2, 4).inOrder()
            assertThat(plan.map { it.completedAt }).containsExactly(null, null)
            assertThat(workouts.getSetsForWorkoutOnce(w.id)).isEmpty()
        }
        val mine = group.first().id
        val friendWorkout = group[1].id
        assertThat(runCatching { workouts.markExerciseComplete(mine, e2, true, now = 11) }.exceptionOrNull()?.message)
            .isEqualTo("Сначала запишите хотя бы один подход")
        val recorded = workouts.addSet(mine, e2, 0.0, 12, now = 12).set
        active.value = other
        workouts.markExerciseComplete(mine, e2, true, now = 13)
        assertThat(workouts.observePlan(mine).first().first().completedAt).isEqualTo(13L)
        assertThat(workouts.observePlan(mine).first().first().setCount).isEqualTo(1)
        assertThat(workouts.observePlan(friendWorkout).first().first().completedAt).isNull()
        workouts.markExerciseComplete(mine, e2, false)
        assertThat(workouts.observePlan(mine).first().first().completedAt).isNull()
        workouts.markExerciseComplete(mine, e2, true, now = 14)
        workouts.deleteSet(recorded)
        assertThat(workouts.observePlan(mine).first().first().completedAt).isNull()
        assertThat(workouts.observePlan(mine).first().first().setCount).isEqualTo(0)
    }

    @Test
    fun editingAndDeletingTemplateLeaveStartedWorkoutSnapshotUntouched() = runTest {
        val e1 = exercise()
        val e2 = exercise("Тяга")
        val id = templates.create("День 1", listOf(TemplatePlanItem(e1, 4, 6, "Пауза"), TemplatePlanItem(e2, 3)))
        val started = templates.startTemplate(id, listOf(1), now = 10).single()
        assertThat(started.groupSessionId).isNull()
        templates.update(id, "Новый план", listOf(TemplatePlanItem(e2, 2, 15)))
        templates.delete(id)
        val snapshot = workouts.observePlan(started.id).first()
        assertThat(snapshot.map { it.exerciseId }).containsExactly(e1, e2).inOrder()
        assertThat(snapshot.first().targetSets).isEqualTo(4)
        assertThat(snapshot.first().targetReps).isEqualTo(6)
        assertThat(snapshot.first().note).isEqualTo("Пауза")
        assertThat(workouts.getSetsForWorkoutOnce(started.id)).isEmpty()
    }

    @Test
    fun startRefusesAnyActiveParticipantWithoutCreatingOrChangingOtherWorkouts() = runTest {
        val other = friend()
        val e = exercise()
        val id = templates.create("План", listOf(TemplatePlanItem(e, 4)))
        val existing = workouts.startWorkout(now = 1, athleteId = other)
        workouts.planExercise(existing, e)
        workouts.addSet(existing, e, 20.0, 5)
        val originalPlan = db.workoutDao().getPlanOnce(existing)
        val error = runCatching { templates.startTemplate(id, listOf(1, other), now = 2) }.exceptionOrNull()
        assertThat(error?.message).isEqualTo("Сначала завершите текущую тренировку")
        assertThat(db.workoutDao().getActiveOnce(1)).isNull()
        assertThat(db.workoutDao().getActiveOnce(other)!!.id).isEqualTo(existing)
        assertThat(db.workoutDao().getPlanOnce(existing)).isEqualTo(originalPlan)
        assertThat(workouts.getSetsForWorkoutOnce(existing)).hasSize(1)
        assertThat(workouts.getWorkoutOnce(existing)!!.groupSessionId).isNull()
    }

    @Test
    fun concurrentTemplateStartsCannotLeaveDuplicateWorkoutsOrPartialPlans() = runTest {
        val other = friend()
        val e = exercise()
        val id = templates.create("План", listOf(TemplatePlanItem(e)))
        val secondRepo = TemplateRepository(db, active)
        val outcomes = coroutineScope {
            listOf(templates, secondRepo).map { repository -> async {
                runCatching { repository.startTemplate(id, listOf(1, other), now = 1) }
            } }.awaitAll()
        }
        assertThat(outcomes.count { it.isSuccess }).isEqualTo(1)
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM workouts").use {
            it.moveToFirst(); assertThat(it.getInt(0)).isEqualTo(2)
        }
        for (athlete in listOf(1L, other)) {
            val w = db.workoutDao().getActiveOnce(athlete)!!
            assertThat(db.workoutDao().getPlanOnce(w.id).single().exerciseId).isEqualTo(e)
        }
    }

    @Test
    fun importMatchesNormalizedIdentityPreservesPhotoAndKeepsDifferentEquipmentSeparate() = runTest {
        val old = db.exerciseDao().insert(Exercise(name = "Жим лёжа", muscleGroup = "Грудь", equipment = "Штанга",
            photoPath = "exercise-existing.jpg", weightStepKg = 1.25, isArchived = true))
        val id = templates.importTemplate("Импорт", listOf(
            ImportedTemplateExercise("  ЖИМ   ЛЁЖА ", " грудь ", "штанга", 4, 8),
            ImportedTemplateExercise("Жим лёжа", "Грудь", "Гантели", 3, 12),
        ))
        val details = templates.getTemplate(id)!!
        assertThat(details.items.first().exercise.id).isEqualTo(old)
        assertThat(details.items.first().exercise.photoPath).isEqualTo("exercise-existing.jpg")
        assertThat(details.items.first().exercise.weightStepKg).isEqualTo(1.25)
        assertThat(details.items.first().exercise.isArchived).isFalse()
        assertThat(details.items.last().exercise.id).isNotEqualTo(old)
        assertThat(details.items.last().exercise.isCustom).isTrue()
        assertThat(db.exerciseDao().getAllOnce()).hasSize(2)
    }

    @Test
    fun importRollsBackNewExercisesAndUnarchiveIfSavingTemplateFails() = runTest {
        val old = db.exerciseDao().insert(Exercise(name = "Жим", muscleGroup = "Грудь", equipment = "Штанга", isArchived = true))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_template BEFORE INSERT ON workout_templates BEGIN SELECT RAISE(ABORT, 'test disk failure'); END")
        val failed = runCatching { templates.importTemplate("План", listOf(
            ImportedTemplateExercise("Жим", "Грудь", "Штанга"),
            ImportedTemplateExercise("Новое упражнение", "Спина"),
        )) }
        assertThat(failed.isFailure).isTrue()
        assertThat(db.exerciseDao().getAllOnce()).hasSize(1)
        assertThat(db.exerciseDao().getById(old)!!.isArchived).isTrue()
        assertThat(templates.observeTemplates().first()).isEmpty()
    }

    @Test
    fun invalidImportAndUnknownParticipantHaveNoSideEffects() = runTest {
        val duplicate = listOf(ImportedTemplateExercise("Жим", "Грудь"), ImportedTemplateExercise(" жим ", "грудь"))
        assertThat(runCatching { templates.importTemplate("План", duplicate) }.isFailure).isTrue()
        assertThat(db.exerciseDao().getAllOnce()).isEmpty()
        val e = exercise()
        val id = templates.create("План", listOf(TemplatePlanItem(e)))
        assertThat(runCatching { templates.startTemplate(id, listOf(1, 999)) }.isFailure).isTrue()
        assertThat(db.workoutDao().getActiveOnce(1)).isNull()
        assertThat(runCatching { templates.update(id, "Плохая правка", listOf(TemplatePlanItem(999))) }.isFailure).isTrue()
        assertThat(templates.getTemplate(id)!!.items.single().exercise.id).isEqualTo(e)
        assertThat(templates.getTemplate(id)!!.template.name).isEqualTo("План")
    }

    @Test
    fun saveWorkoutPreservesPlanOrderGoalsAndUnperformedExercisesAndChecksOwner() = runTest {
        val other = friend()
        val e1 = exercise("Первое")
        val e2 = exercise("Незаписанное")
        val e3 = exercise("Вне плана")
        val w = workouts.startWorkout(now = 1)
        db.workoutDao().insertPlan(WorkoutExercise(w, e2, 1, 5, 10, "С резинкой"))
        db.workoutDao().insertPlan(WorkoutExercise(w, e1, 2))
        workouts.addSet(w, e1, 20.0, 6)
        workouts.addSet(w, e3, 15.0, 10)
        workouts.addSet(w, e3, 15.0, 10)
        active.value = other
        assertThat(runCatching { templates.saveFromWorkout(w, "Чужой") }.isFailure).isTrue()
        val id = templates.saveFromWorkout(w, "Из занятия", athleteId = 1)
        val plan = templates.getTemplate(id, 1)!!.items
        assertThat(plan.map { it.exercise.id }).containsExactly(e2, e1, e3).inOrder()
        assertThat(plan.map { it.targetSets }).containsExactly(5, 1, 2).inOrder()
        assertThat(plan.first().targetReps).isEqualTo(10)
        assertThat(plan.first().note).isEqualTo("С резинкой")
    }

    @Test
    fun newGroupParticipantGetsPlanSnapshotButExistingActiveFriendsKeepTheirPlan() = runTest {
        val existingFriend = friend()
        val newFriend = friend("Новый")
        val e1 = exercise()
        val e2 = exercise("Свой маршрут")
        val template = templates.create("План", listOf(TemplatePlanItem(e1, 4, 8, "Пауза")))
        val mine = templates.startTemplate(template, listOf(1)).single().id
        workouts.addSet(mine, e1, 20.0, 8)
        workouts.markExerciseComplete(mine, e1, true)
        val existing = workouts.startWorkout(athleteId = existingFriend)
        workouts.planExercise(existing, e2)
        val group = workouts.startGroupWorkout(listOf(1, existingFriend, newFriend))
        val newcomer = group.single { it.athleteId == newFriend }
        val copied = db.workoutDao().getPlanOnce(newcomer.id).single()
        assertThat(copied.exerciseId).isEqualTo(e1)
        assertThat(copied.targetSets).isEqualTo(4)
        assertThat(copied.targetReps).isEqualTo(8)
        assertThat(copied.note).isEqualTo("Пауза")
        assertThat(copied.completedAt).isNull()
        assertThat(workouts.getSetsForWorkoutOnce(newcomer.id)).isEmpty()
        assertThat(db.workoutDao().getPlanOnce(existing).single().exerciseId).isEqualTo(e2)
    }

    @Test
    fun repeatKeepsGoalSnapshotAndClearsCompletionWithoutCopyingResults() = runTest {
        val e = exercise()
        val id = templates.create("План", listOf(TemplatePlanItem(e, 4, 8, "Подсказка")))
        val source = templates.startTemplate(id, listOf(1)).single().id
        workouts.addSet(source, e, 20.0, 8)
        workouts.markExerciseComplete(source, e, true)
        workouts.finishWorkout(source)
        val repeated = workouts.repeatWorkout(source).single()
        val plan = workouts.observePlan(repeated.id).first().single()
        assertThat(plan.targetSets).isEqualTo(4)
        assertThat(plan.targetReps).isEqualTo(8)
        assertThat(plan.note).isEqualTo("Подсказка")
        assertThat(plan.completedAt).isNull()
        assertThat(plan.setCount).isEqualTo(0)
    }
}
