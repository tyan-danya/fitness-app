package com.dtyan.fitdiary.ui.nutrition

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dtyan.fitdiary.MainDispatcherRule
import com.dtyan.fitdiary.data.SettingsStore
import com.dtyan.fitdiary.data.ai.HttpResult
import com.dtyan.fitdiary.data.ai.HttpTransport
import com.dtyan.fitdiary.data.ai.NutritionEstimator
import com.dtyan.fitdiary.data.db.AppDatabase
import com.dtyan.fitdiary.data.repo.NutritionRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NutritionSafetyTest {
    @get:Rule val mainRule = MainDispatcherRule()
    private lateinit var db: AppDatabase
    private lateinit var vm: NutritionViewModel
    private val athlete = MutableStateFlow(1L)
    private val deferred = DeferredDispatcher()

    private class DeferredDispatcher : CoroutineDispatcher() {
        val pending = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
        fun release() { while (pending.isNotEmpty()) pending.removeFirst().run() }
    }

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor(mainRule.dispatcher.asExecutor())
            .setTransactionExecutor(mainRule.dispatcher.asExecutor()).build()
        val settings = SettingsStore(context)
        settings.update { it.copy(aiApiKey = "unused-test-placeholder") }
        val estimator = NutritionEstimator({ settings.settings.value }, HttpTransport { _, _, _ ->
            HttpResult(200, """{"choices":[{"message":{"content":"{\"kcal_per_100g\":190,\"protein_per_100g\":8,\"fat_per_100g\":7,\"carbs_per_100g\":24,\"serving_g\":300,\"note\":\"dish-A\"}"}}]}""")
        }, deferred)
        vm = NutritionViewModel(NutritionRepository(db.mealDao(), athlete, db), settings, estimator)
    }

    @After fun tearDown() { db.close() }

    @Test fun lateEstimateCannotFillReplacementEditor() = runTest {
        vm.openAddEditor(); vm.onEditorNameChange("Dish A"); vm.estimateWithAi()
        advanceUntilIdle()
        assertThat(deferred.pending).hasSize(1)
        vm.closeEditor(); vm.openAddEditor(); vm.onEditorNameChange("Dish B")
        deferred.release(); advanceUntilIdle()
        assertThat(vm.editor.value!!.name).isEqualTo("Dish B")
        assertThat(vm.editor.value!!.caloriesText).isEmpty()
        assertThat(vm.editor.value!!.estimateNote).isNull()
        assertThat(vm.editor.value!!.estimating).isFalse()
    }

    @Test fun editingMacrosCancelsEstimateAndKeepsManualInput() = runTest {
        vm.openAddEditor(); vm.onEditorNameChange("Dish A"); vm.estimateWithAi()
        advanceUntilIdle()
        vm.onEditorCaloriesChange("123")
        deferred.release(); advanceUntilIdle()
        assertThat(vm.editor.value!!.caloriesText).isEqualTo("123")
        assertThat(vm.editor.value!!.estimating).isFalse()
    }

    @Test fun switchingAthleteClosesEditorAndDropsEstimate() = runTest {
        vm.openAddEditor(); vm.onEditorNameChange("Dish A"); vm.estimateWithAi()
        advanceUntilIdle()
        athlete.value = 2L
        advanceUntilIdle(); deferred.release(); advanceUntilIdle()
        assertThat(vm.editor.value).isNull()
    }
}
