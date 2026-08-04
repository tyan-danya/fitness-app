package com.dtyan.fitdiary.ui.home

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dtyan.fitdiary.MainDispatcherRule
import com.dtyan.fitdiary.data.db.AppDatabase
import com.dtyan.fitdiary.data.repo.StatsRepository
import com.dtyan.fitdiary.data.repo.WorkoutRepository
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

/**
 * Тесты HomeViewModel поверх реальной in-memory Room-БД.
 * HomeStats зависит от настоящего «сейчас» (VM берёт System.currentTimeMillis()
 * внутри), поэтому тестовые тренировки создаются относительно текущего времени
 * («сейчас минус часы») — это детерминировано для окна в 7 дней и ISO-недель.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HomeViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private lateinit var db: AppDatabase
    private lateinit var workoutRepo: WorkoutRepository
    private lateinit var statsRepo: StatsRepository

    private val hour = 3_600_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val executor = mainRule.dispatcher.asExecutor()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(executor)
            .setTransactionExecutor(executor)
            .build()
        workoutRepo = WorkoutRepository(db.workoutDao(), db.workoutSetDao(), db.weightDao())
        statsRepo = StatsRepository(db.workoutDao(), db.workoutSetDao(), db.weightDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun createVm() = HomeViewModel(workoutRepo, statsRepo)

    /** Завершённая тренировка: старт [startedAt], финиш через полчаса. */
    private suspend fun finishedWorkout(startedAt: Long): Long {
        val id = workoutRepo.startWorkout(now = startedAt)
        workoutRepo.finishWorkout(id, bodyWeightKg = null, now = startedAt + hour / 2)
        return id
    }

    // ---------- Активная тренировка ----------

    @Test
    fun noActiveWorkout_startWorkout_createsAndCallsBackWithId() = runTest {
        val vm = createVm()
        backgroundScope.launch { vm.activeWorkout.collect { } }
        advanceUntilIdle()
        assertThat(vm.activeWorkout.value).isNull()

        var startedId: Long? = null
        vm.startWorkout { startedId = it }
        advanceUntilIdle()

        assertThat(startedId).isNotNull()
        val active = vm.activeWorkout.value
        assertThat(active).isNotNull()
        assertThat(active!!.id).isEqualTo(startedId)
        assertThat(active.endedAt).isNull()
        assertThat(db.workoutDao().getById(startedId!!)).isNotNull()
    }

    @Test
    fun activeWorkoutExists_visibleInState_startReturnsSameId() = runTest {
        val existing = workoutRepo.startWorkout(now = System.currentTimeMillis() - hour)

        val vm = createVm()
        backgroundScope.launch { vm.activeWorkout.collect { } }
        advanceUntilIdle()

        assertThat(vm.activeWorkout.value?.id).isEqualTo(existing)

        // Старт при уже активной тренировке возвращает её же id (идемпотентность репозитория).
        var startedId: Long? = null
        vm.startWorkout { startedId = it }
        advanceUntilIdle()
        assertThat(startedId).isEqualTo(existing)
    }

    // ---------- Недельная статистика ----------

    @Test
    fun stats_twoFinishedThisWeek_countIs2_streakAtLeast1() = runTest {
        val now = System.currentTimeMillis()
        finishedWorkout(startedAt = now - 3 * hour)
        finishedWorkout(startedAt = now - 1 * hour)

        val vm = createVm()
        backgroundScope.launch { vm.stats.collect { } }
        advanceUntilIdle()

        val stats = vm.stats.value
        assertThat(stats.workoutsLast7Days).isEqualTo(2)
        // Обе тренировки не старше 3 часов — текущая или прошлая ISO-неделя,
        // в любом случае стрик не меньше 1.
        assertThat(stats.weeklyStreak).isAtLeast(1)
    }

    @Test
    fun stats_noWorkouts_zeroes() = runTest {
        val vm = createVm()
        backgroundScope.launch { vm.stats.collect { } }
        advanceUntilIdle()

        assertThat(vm.stats.value.workoutsLast7Days).isEqualTo(0)
        assertThat(vm.stats.value.weeklyStreak).isEqualTo(0)
    }

    // ---------- Последние тренировки ----------

    @Test
    fun summaries_sortedByStartDesc_activeExcluded() = runTest {
        val now = System.currentTimeMillis()
        val wOld = finishedWorkout(startedAt = now - 50 * hour)
        val wMid = finishedWorkout(startedAt = now - 30 * hour)
        val wNew = finishedWorkout(startedAt = now - 5 * hour)
        // Активная тренировка в список завершённых не попадает.
        val wActive = workoutRepo.startWorkout(now = now - hour)

        val vm = createVm()
        backgroundScope.launch { vm.summaries.collect { } }
        advanceUntilIdle()

        // VM отдаёт полный список DESC; ограничение «максимум 5» накладывает
        // экран (HomeScreen: summaries.take(5)).
        val ids = vm.summaries.value.map { it.id }
        assertThat(ids).containsExactly(wNew, wMid, wOld).inOrder()
        assertThat(ids).doesNotContain(wActive)
    }
}
