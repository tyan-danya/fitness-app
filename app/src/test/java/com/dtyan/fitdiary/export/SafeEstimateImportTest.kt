package com.dtyan.fitdiary.export

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dtyan.fitdiary.data.SettingsStore
import com.dtyan.fitdiary.MainDispatcherRule
import com.dtyan.fitdiary.data.db.AppDatabase
import com.dtyan.fitdiary.data.db.Meal
import com.dtyan.fitdiary.data.repo.NutritionRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class SafeEstimateImportTest {
    @get:Rule val mainRule = MainDispatcherRule()
    private lateinit var db: AppDatabase
    private lateinit var repo: NutritionRepository
    private lateinit var exchange: EstimateExchange
    private lateinit var context: Context
    private val athlete = MutableStateFlow(1L)

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = NutritionRepository(db.mealDao(), athlete, db)
        val settings = SettingsStore(context)
        exchange = EstimateExchange(context, { settings.diaryId }, { athlete.value })
    }
    @After fun tearDown() { db.close() }

    private suspend fun pending(name: String): Meal {
        val id = repo.addMeal(1L, name, 0, 0.0, 0.0, 0.0, servingG = 300.0, needsEstimate = true)
        return db.mealDao().getByIds(listOf(id)).single()
    }
    private fun response(meals: List<Meal>) = exchange.createRequestJson(meals)
        .replace("\"kcalPer100\": null", "\"kcalPer100\": 190")
        .replace("\"proteinPer100\": null", "\"proteinPer100\": 8")
        .replace("\"fatPer100\": null", "\"fatPer100\": 7")
        .replace("\"carbsPer100\": null", "\"carbsPer100\": 24")

    @Test fun editedRecordConflictsAndPreviewDoesNotWrite() = runTest {
        val original = pending("Original")
        val batch = exchange.readResponse(response(listOf(original)))
        repo.updateMeal(original.copy(name = "Corrected by user"))
        val preview = repo.previewEstimates(batch.updates, batch.athleteId)
        assertThat(preview.accepted).isEmpty()
        assertThat(preview.conflicts).containsExactly("Corrected by user")
        assertThat(db.mealDao().getByIds(listOf(original.id)).single().calories).isEqualTo(0)
    }

    @Test fun anotherDiaryOrProfileAndChangedFingerprintAreRejected() = runTest {
        val meal = pending("Meal")
        val response = response(listOf(meal))
        val foreign = EstimateExchange(context, { UUID.randomUUID().toString() })
        assertThat(runCatching { foreign.readResponse(response) }.isFailure).isTrue()
        athlete.value = 2
        assertThat(runCatching { exchange.readResponse(response) }.isFailure).isTrue()
        athlete.value = 1
        val altered = response.replace(NutritionRepository.fingerprint(meal), "forged")
        assertThat(runCatching { exchange.readResponse(altered) }.isFailure).isTrue()
        assertThat(db.mealDao().getByIds(listOf(meal.id)).single().needsEstimate).isTrue()
    }

    @Test fun partialSqlFailureRollsBackAllAppliedMeals() = runTest {
        val first = pending("First")
        val second = pending("Second")
        val batch = exchange.readResponse(response(listOf(first, second)))
        db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER reject_second BEFORE UPDATE ON meals WHEN NEW.id = ${second.id} BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        assertThat(runCatching { repo.applyEstimates(batch.updates) }.isFailure).isTrue()
        val meals = db.mealDao().getByIds(listOf(first.id, second.id))
        assertThat(meals.all { it.needsEstimate && it.calories == 0 }).isTrue()
    }

    @Test fun changesAfterPreviewAreCheckedAgainAndCannotOverwrite() = runTest {
        val first = pending("First")
        val batch = exchange.readResponse(response(listOf(first)))
        val preview = repo.previewEstimates(batch.updates, batch.athleteId)
        repo.updateMeal(first.copy(servingG = 250.0))
        assertThat(runCatching { repo.applyEstimates(preview.accepted) }.isFailure).isTrue()
        assertThat(db.mealDao().getByIds(listOf(first.id)).single().servingG).isEqualTo(250.0)
    }
}
