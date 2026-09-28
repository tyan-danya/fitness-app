package com.dtyan.fitdiary.ui.templates

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dtyan.fitdiary.MainDispatcherRule
import com.dtyan.fitdiary.data.db.AppDatabase
import com.dtyan.fitdiary.data.db.Athlete
import com.dtyan.fitdiary.data.db.Exercise
import com.dtyan.fitdiary.data.repo.ExerciseRepository
import com.dtyan.fitdiary.data.repo.TemplateRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TemplatesViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()
    private lateinit var db: AppDatabase
    private lateinit var repo: TemplateRepository
    private lateinit var exerciseRepo: ExerciseRepository
    private val activeId = MutableStateFlow(1L)

    @Before fun setUp() {
        val executor = mainRule.dispatcher.asExecutor()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor(executor).setTransactionExecutor(executor).build()
        repo = TemplateRepository(db, activeId)
        exerciseRepo = ExerciseRepository(db.exerciseDao(), db.workoutSetDao(), activeId)
    }
    @After fun tearDown() { db.close() }
    private fun vm(state: SavedStateHandle = SavedStateHandle()) = TemplatesViewModel(repo, exerciseRepo, 1L, state)
    private suspend fun athletes() {
        db.athleteDao().insert(Athlete(id = 1, name = "Даня"))
        db.athleteDao().insert(Athlete(id = 2, name = "Миша"))
    }
    private suspend fun exercise(name: String): Exercise {
        val value = Exercise(name = name, muscleGroup = "Спина")
        return value.copy(id = db.exerciseDao().insert(value))
    }

    @Test fun editor_restoresOrderTargetsAndTextDraft_afterRecreation() = runTest {
        val first = exercise("Тяга")
        val second = exercise("Присед")
        val state = SavedStateHandle()
        val original = vm(state)
        original.newTemplate()
        original.changeName("День А")
        original.addExercise(first)
        original.addExercise(second)
        original.addExercise(first)
        original.moveExercise(second.id, -1)
        original.updateRow(second.id) { it.copy(sets = "4", reps = "8", note = "Спокойный темп") }

        val recreatedState = SavedStateHandle(mapOf("templateDraft" to state.get<String>("templateDraft")))
        val restored = vm(recreatedState)
        assertThat(restored.draft.value?.name).isEqualTo("День А")
        assertThat(restored.draft.value?.rows?.map { it.exerciseId }).containsExactly(second.id, first.id).inOrder()
        assertThat(restored.draft.value?.rows?.first()?.sets).isEqualTo("4")
        assertThat(restored.draft.value?.rows?.first()?.reps).isEqualTo("8")
        assertThat(restored.draft.value?.rows?.first()?.note).isEqualTo("Спокойный темп")
    }

    @Test fun save_validates_andDoubleTapCreatesOneOwnedTemplate_whenGlobalOwnerChanges() = runTest {
        athletes()
        val exercise = exercise("Жим")
        val vm = vm()
        vm.newTemplate()
        vm.save()
        advanceUntilIdle()
        assertThat(vm.error.value).isEqualTo("Введите название программы")
        assertThat(repo.observeTemplates(1).first()).isEmpty()

        vm.changeName("День А")
        vm.addExercise(exercise)
        vm.updateRow(exercise.id) { it.copy(reps = "0") }
        vm.save()
        advanceUntilIdle()
        assertThat(vm.draft.value).isNotNull()
        assertThat(repo.observeTemplates(1).first()).isEmpty()

        vm.updateRow(exercise.id) { it.copy(reps = "8") }
        vm.save()
        vm.save()
        activeId.value = 2
        advanceUntilIdle()
        assertThat(repo.observeTemplates(1).first()).hasSize(1)
        assertThat(repo.observeTemplates(2).first()).isEmpty()
        assertThat(vm.draft.value).isNull()
    }

    @Test fun start_keepsCurrentOwnerFirst_andOnlyCopiesPlanForEachParticipant() = runTest {
        athletes()
        val exercise = exercise("Тяга")
        val vm = vm()
        vm.newTemplate(); vm.changeName("День Б"); vm.addExercise(exercise); vm.save()
        advanceUntilIdle()
        val templateId = repo.observeTemplates(1).first().single().id
        var openedId: Long? = null
        vm.start(templateId, listOf(2, 1, 2)) { openedId = it }
        activeId.value = 2
        advanceUntilIdle()
        val mine = db.workoutDao().getActiveOnce(1)!!
        val friend = db.workoutDao().getActiveOnce(2)!!
        assertThat(openedId).isEqualTo(mine.id)
        assertThat(mine.groupSessionId).isEqualTo(friend.groupSessionId)
        assertThat(db.workoutDao().observePlan(mine.id).first().single().exerciseId).isEqualTo(exercise.id)
        assertThat(db.workoutDao().observePlan(friend.id).first().single().completedAt).isNull()
        assertThat(db.workoutSetDao().getForWorkoutOnce(mine.id)).isEmpty()
        assertThat(db.workoutSetDao().getForWorkoutOnce(friend.id)).isEmpty()
    }
}
