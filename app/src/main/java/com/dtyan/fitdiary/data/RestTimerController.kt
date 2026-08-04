package com.dtyan.fitdiary.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Таймер отдыха между подходами. Живёт в AppContainer, поэтому переживает
 * навигацию между экранами. По окончании дёргает [onFinished] (вибрация).
 */
class RestTimerController(
    private val scope: CoroutineScope,
    private val onFinished: () -> Unit = {},
) {
    data class State(
        val totalSeconds: Int,
        val remainingSeconds: Int,
    )

    private val _state = MutableStateFlow<State?>(null)
    /** null — таймер не идёт. */
    val state: StateFlow<State?> = _state.asStateFlow()

    private var job: Job? = null

    fun start(seconds: Int) {
        if (seconds <= 0) return
        job?.cancel()
        _state.value = State(totalSeconds = seconds, remainingSeconds = seconds)
        job = scope.launch {
            while (true) {
                delay(1000)
                val cur = _state.value ?: break
                val next = cur.remainingSeconds - 1
                if (next <= 0) {
                    _state.value = null
                    onFinished()
                    break
                }
                _state.value = cur.copy(remainingSeconds = next)
            }
        }
    }

    /** Добавить секунд к идущему таймеру (кнопка «+15 c»). */
    fun addSeconds(seconds: Int) {
        val cur = _state.value ?: return
        _state.value = cur.copy(
            totalSeconds = cur.totalSeconds + seconds,
            remainingSeconds = cur.remainingSeconds + seconds,
        )
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.value = null
    }
}
