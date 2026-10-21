package com.dtyan.fitdiary.ui.update

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dtyan.fitdiary.BuildConfig
import com.dtyan.fitdiary.data.update.AppUpdater
import com.dtyan.fitdiary.data.update.UpdatePhase
import com.dtyan.fitdiary.ui.common.FitButton
import com.dtyan.fitdiary.ui.common.FitButtonKind
import com.dtyan.fitdiary.ui.common.FitCard
import com.dtyan.fitdiary.ui.common.sectionColors
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
                        FitButton(if (state.phase == UpdatePhase.NEEDS_PERMISSION) "Разрешить установку в Android" else "Установить обновление",
                            { activity?.let(updater::install) }, Modifier.fillMaxWidth(), enabled = activity != null)
                    } else if (state.release != null && !state.busy) {
                        FitButton("Скачать сейчас · любая сеть", { updater.downloadNow(allowMetered = true) }, Modifier.fillMaxWidth())
                    }
                    if (state.phase == UpdatePhase.DOWNLOADING || state.phase == UpdatePhase.WAITING_FOR_WIFI) {
                        FitButton("Отменить загрузку", updater::cancelDownload, Modifier.fillMaxWidth(), kind = FitButtonKind.Plain)
                    }
                    FitButton("Проверить обновления", updater::checkNow, Modifier.fillMaxWidth(), kind = FitButtonKind.Outlined, enabled = !state.busy)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

/** Карточка на главной, пока есть что скачать или установить. */
@Composable
fun UpdateBanner(updater: AppUpdater, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val state by updater.state.collectAsStateWithLifecycle()
    val release = state.release
    if (release == null || state.phase in setOf(UpdatePhase.DISABLED, UpdatePhase.UP_TO_DATE)) return
    val colors = sectionColors()
    FitCard(modifier, onClick = onOpen, onClickLabel = "Открыть обновления", color = colors.container, contentColor = colors.onContainer,
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.SystemUpdate, contentDescription = null)
            Text(
                when (state.phase) {
                    UpdatePhase.READY, UpdatePhase.NEEDS_PERMISSION -> "Версия ${release.version} готова к установке"
                    UpdatePhase.DOWNLOADING -> "Скачиваем обновление: ${state.progress}%"
                    UpdatePhase.VERIFYING -> "Проверяем обновление…"
                    else -> "Доступна версия ${release.version}"
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
            )
            FitButton("Открыть", onOpen, kind = FitButtonKind.Plain, compact = true)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
