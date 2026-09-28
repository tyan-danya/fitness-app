package com.dtyan.fitdiary.ui.update

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dtyan.fitdiary.BuildConfig
import com.dtyan.fitdiary.data.update.AppUpdater
import com.dtyan.fitdiary.data.update.UpdatePhase
import java.util.Locale

@Composable
fun UpdateDialog(updater: AppUpdater, onDismiss: () -> Unit) {
    val state by updater.state.collectAsStateWithLifecycle()
    val activity = LocalContext.current.findActivity()
    LaunchedEffect(updater) { updater.onResume() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Обновления ФитДневника") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Установлена версия ${BuildConfig.VERSION_NAME}")
                if (state.phase == UpdatePhase.DISABLED) {
                    Text(state.message.orEmpty())
                } else {
                    Text("Проверяем при запуске и примерно раз в сутки. Новые APK скачиваются по Wi-Fi. Android всегда спрашивает подтверждение установки.", style = MaterialTheme.typography.bodySmall)
                    state.release?.let { release ->
                        Text("Доступна ${release.version} · ${String.format(Locale.forLanguageTag("ru"), "%.1f", release.sizeBytes / 1048576.0)} МБ", style = MaterialTheme.typography.titleMedium)
                        if (release.notes.isNotBlank()) Text(release.notes, style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.phase == UpdatePhase.DOWNLOADING) {
                        LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())
                        Text("Скачано ${state.progress}%")
                    } else if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    state.message?.let { Text(it, color = if (state.phase == UpdatePhase.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (state.canInstall) {
                        Button(onClick = { activity?.let(updater::install) }, enabled = activity != null, modifier = Modifier.fillMaxWidth()) {
                            Text(if (state.phase == UpdatePhase.NEEDS_PERMISSION) "Разрешить установку в Android" else "Установить обновление")
                        }
                    } else if (state.release != null && !state.busy) {
                        Button(onClick = { updater.downloadNow(allowMetered = true) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Скачать сейчас · любая сеть")
                        }
                    }
                    if (state.phase == UpdatePhase.DOWNLOADING || state.phase == UpdatePhase.WAITING_FOR_WIFI) {
                        TextButton(onClick = updater::cancelDownload) { Text("Отменить загрузку") }
                    }
                    OutlinedButton(onClick = updater::checkNow, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Проверить обновления") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

@Composable
fun UpdateBanner(updater: AppUpdater, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val state by updater.state.collectAsStateWithLifecycle()
    if (state.release == null || state.phase in setOf(UpdatePhase.DISABLED, UpdatePhase.UP_TO_DATE)) return
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                when (state.phase) {
                    UpdatePhase.READY, UpdatePhase.NEEDS_PERMISSION -> "Версия ${state.release!!.version} готова к установке"
                    UpdatePhase.DOWNLOADING -> "Обновление: ${state.progress}%"
                    UpdatePhase.VERIFYING -> "Проверяем обновление…"
                    else -> "Доступна версия ${state.release!!.version}"
                },
                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onOpen) { Text("Открыть") }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
