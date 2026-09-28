package com.dtyan.fitdiary.export

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
fun BackupRestoreDialog(
    preview: BackupManager.BackupPreview,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Заменить дневник резервной копией?") },
        text = { Text("Профилей: ${preview.profiles}, тренировок: ${preview.workouts}, приёмов пищи: ${preview.meals}, " +
            "замеров: ${preview.measurements}, программ: ${preview.templates}, фото: ${preview.photos}.\n\n" +
            "Текущий дневник всех профилей будет заменён, включая активные тренировки. " +
            "Перед заменой сохраним локальную копию для отката. Настройки подключения ИИ и ключ не импортируются.") },
        confirmButton = { TextButton(onClick = onConfirm, enabled = !busy) { Text(if (busy) "Восстанавливаем…" else "Заменить дневник") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Отмена") } },
    )
}
