package com.dtyan.fitdiary

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Подменяет Dispatchers.Main тестовым диспетчером на время теста.
 *
 * viewModelScope живёт на Main.immediate, поэтому без подмены ViewModel-тесты
 * падают («Main dispatcher is not initialized»). runTest автоматически
 * подхватывает планировщик Main-диспетчера, так что виртуальное время теста,
 * viewModelScope и всё, что диспетчеризуется через [dispatcher], разделяют
 * один TestCoroutineScheduler — advanceUntilIdle() детерминированно дожимает
 * всю очередь.
 *
 * Тот же [dispatcher] удобно отдавать Room через asExecutor(): тогда
 * suspend-DAO и эмиссии Flow тоже исполняются под контролем планировщика,
 * а не на реальном пуле arch_disk_io (иначе advanceUntilIdle вернулся бы
 * раньше, чем БД закончила работу, — гонка).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
