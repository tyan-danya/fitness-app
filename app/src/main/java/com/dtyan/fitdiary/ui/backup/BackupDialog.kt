package com.dtyan.fitdiary.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dtyan.fitdiary.appContainer
import com.dtyan.fitdiary.export.BackupManager
import com.dtyan.fitdiary.export.BackupRestoreDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun BackupDialog(onDismiss: () -> Unit, onRestored: () -> Unit) {
    val context = LocalContext.current
    val container = context.appContainer
    val manager = container.backupManager
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<BackupManager.BackupPreview?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true; error = null
            try { preview = manager.inspectBackup(uri) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Не удалось прочитать копию" }
            finally { busy = false }
        }
    }

    val selected = preview
    if (selected != null) {
        BackupRestoreDialog(selected, busy, onConfirm = {
            scope.launch {
                busy = true; error = null
                try {
                    manager.restoreBackup(selected)
                    container.profiles.refreshSelection()
                    preview = null
                    onRestored()
                    onDismiss()
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) {
                    manager.discard(selected)
                    preview = null
                    error = "Восстановление не выполнено: ${e.message ?: "ошибка"}. Предыдущая копия доступна для отката."
                } finally { busy = false }
            }
        }, onDismiss = { manager.discard(selected); preview = null })
    } else {
        AlertDialog(
            onDismissRequest = { if (!busy) onDismiss() },
            title = { Text("Резервные копии") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Копия содержит все профили, тренировки, программы, питание, замеры, каталог и фото. API-ключ и настройки подключения ИИ в неё не входят.")
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = {
                        scope.launch {
                            busy = true; error = null
                            try { context.startActivity(manager.createBackup()) }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { error = e.message ?: "Не удалось сохранить копию" }
                            finally { busy = false }
                        }
                    }) { Text("Сохранить копию") }
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = {
                        error = null; launcher.launch(arrayOf("application/zip", "application/octet-stream"))
                    }) { Text("Восстановить из файла") }
                    if (manager.hasRecoveryCopy()) OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = {
                        scope.launch {
                            busy = true; error = null
                            try { preview = manager.recoveryPreview() }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { error = e.message ?: "Не удалось открыть предыдущую копию" }
                            finally { busy = false }
                        }
                    }) { Text("Вернуть предыдущий дневник") }
                    if (busy) CircularProgressIndicator()
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Закрыть") } },
        )
    }
}
