package com.dtyan.fitdiary.ui.profile

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dtyan.fitdiary.export.ExportManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

enum class ExportFormat { JSON, CSV }

/** Экспорт дневника выбранного участника для анализа или Excel (не резервная копия). */
class DataExportViewModel(
    private val exportManager: ExportManager,
    private val ownerId: () -> Long,
) : ViewModel() {
    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting.asStateFlow()

    private val _intents = Channel<Intent>(Channel.BUFFERED)
    /** Готовые share-интенты: composable запускает их через context.startActivity. */
    val intents: Flow<Intent> = _intents.receiveAsFlow()
    private val _errors = Channel<String>(Channel.BUFFERED)
    val errors: Flow<String> = _errors.receiveAsFlow()

    fun export(format: ExportFormat) {
        if (_exporting.value) return
        val owner = ownerId()
        _exporting.value = true
        viewModelScope.launch {
            try {
                val intent = when (format) {
                    ExportFormat.JSON -> exportManager.exportJson(athleteId = owner)
                    ExportFormat.CSV -> exportManager.exportCsv(athleteId = owner)
                }
                _intents.send(intent)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _errors.send(error.message ?: "Не удалось подготовить экспорт")
            } finally {
                _exporting.value = false
            }
        }
    }
}
