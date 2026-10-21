package com.dtyan.fitdiary.ui.measure

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dtyan.fitdiary.MainDispatcherRule
import com.dtyan.fitdiary.data.db.AppDatabase
import com.dtyan.fitdiary.data.repo.StatsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Вес на вкладке «Тело»: дельта за 30 дней и окно графика в 90 дней поверх реальной in-memory Room. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WeightViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private lateinit var db: AppDatabase
    private lateinit var statsRepo: StatsRepository

    private val hour = 3_600_000L
    private val day = 24 * hour

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val executor = mainRule.dispatcher.asExecutor()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(executor)
            .setTransactionExecutor(executor)
            .build()
        statsRepo = StatsRepository(db.workoutDao(), db.workoutSetDao(), db.weightDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun weight_delta30d_gain_baselineOlderThan30Days() = runTest {
        val now = System.currentTimeMillis()
        statsRepo.addWeightEntry(70.0, now = now - 100 * day) // старше 90 дней — вне графика
        statsRepo.addWeightEntry(80.0, now = now - 40 * day) // база для дельты
        statsRepo.addWeightEntry(82.5, now = now - 1 * day) // текущий

        val vm = WeightViewModel(statsRepo, now = { now })
        backgroundScope.launch { vm.weight.collect { } }
        advanceUntilIdle()

        val weight = vm.weight.value
        assertThat(weight.current?.weightKg).isEqualTo(82.5)
        assertThat(weight.delta30d).isNotNull()
        assertThat(weight.delta30d!!).isWithin(1e-9).of(2.5) // 82,5 − 80,0
        // На графике только последние 90 дней.
        assertThat(weight.chartPoints.map { it.second }).containsExactly(80.0, 82.5).inOrder()
    }

    @Test
    fun weight_delta30d_loss_negativeValue() = runTest {
        val now = System.currentTimeMillis()
        statsRepo.addWeightEntry(90.0, now = now - 35 * day)
        statsRepo.addWeightEntry(85.5, now = now - 2 * hour)

        val vm = WeightViewModel(statsRepo, now = { now })
        backgroundScope.launch { vm.weight.collect { } }
        advanceUntilIdle()

        val weight = vm.weight.value
        assertThat(weight.current?.weightKg).isEqualTo(85.5)
        assertThat(weight.delta30d!!).isWithin(1e-9).of(-4.5) // 85,5 − 90,0
    }

    @Test
    fun addWeight_recordsSingleEntryAtClickTime() = runTest {
        val vm = WeightViewModel(statsRepo, now = { 5_000L })
        vm.addWeight(77.7)
        advanceUntilIdle()

        val saved = statsRepo.getWeightHistoryOnce(1L).single()
        assertThat(saved.weightKg).isEqualTo(77.7)
        assertThat(saved.timestamp).isEqualTo(5_000L)
    }
}
