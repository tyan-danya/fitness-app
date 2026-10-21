package com.dtyan.fitdiary.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dtyan.fitdiary.BuildConfig
import com.dtyan.fitdiary.appContainer
import com.dtyan.fitdiary.data.db.Athlete
import com.dtyan.fitdiary.data.update.UpdatePhase
import com.dtyan.fitdiary.export.ExportManager
import com.dtyan.fitdiary.ui.backup.BackupDialog
import com.dtyan.fitdiary.ui.common.BlockTitle
import com.dtyan.fitdiary.ui.common.FitButton
import com.dtyan.fitdiary.ui.common.FitButtonKind
import com.dtyan.fitdiary.ui.common.MenuAction
import com.dtyan.fitdiary.ui.common.NavRow
import com.dtyan.fitdiary.ui.common.OverflowMenu
import com.dtyan.fitdiary.ui.nutrition.AiSettingsDialog
import com.dtyan.fitdiary.ui.theme.fitAccents
import kotlinx.coroutines.launch

/** Действия «оболочки» приложения, доступные любому экрану: меню профиля и обновления. */
@Immutable
class AppChrome(val openAccount: () -> Unit = {}, val openUpdates: () -> Unit = {})

val LocalAppChrome = staticCompositionLocalOf { AppChrome() }

/** Выбранный профиль, прочитанный один раз в корне: шапки вкладок не мигают «Я» при переключении. */
@Immutable
data class ActiveProfile(val id: Long, val name: String)

val LocalActiveProfile = compositionLocalOf<ActiveProfile?> { null }

/** Кружок с первой буквой имени; цвет закреплён за профилем, чтобы участников было легко различать. */
@Composable
fun ProfileAvatar(name: String, id: Long, size: Dp = 32.dp) {
    val accents = fitAccents
    val (container, content) = when ((id - 1).mod(4L)) {
        0L -> accents.workoutContainer to accents.onWorkoutContainer
        1L -> accents.nutritionContainer to accents.onNutritionContainer
        2L -> accents.measureContainer to accents.onMeasureContainer
        else -> accents.statsContainer to accents.onStatsContainer
    }
    Box(Modifier.size(size).clip(CircleShape).background(container), contentAlignment = Alignment.Center) {
        Text(name.trim().take(1).uppercase(), color = content,
            style = if (size >= 40.dp) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge)
    }
}

/** Кнопка профиля в шапке каждой вкладки: кто сейчас ведёт дневник и вход в настройки. */
@Composable
fun ProfileButton(modifier: Modifier = Modifier) {
    val profile = LocalActiveProfile.current ?: run {
        // Экран показан сам по себе (например, в тесте) — читаем профиль напрямую.
        val container = LocalContext.current.appContainer
        val people by container.profiles.profiles.collectAsStateWithLifecycle(null)
        val activeId by container.profiles.activeId.collectAsStateWithLifecycle()
        ActiveProfile(activeId, people?.firstOrNull { it.id == activeId }?.name.orEmpty())
    }
    val name = profile.name
    val active = profile.id
    val chrome = LocalAppChrome.current
    Surface(
        onClick = chrome.openAccount,
        modifier = modifier.semantics { contentDescription = "Профиль и настройки" },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(Modifier.heightIn(min = 40.dp).padding(start = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProfileAvatar(name, active)
            if (name.isNotEmpty()) Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 104.dp))
            Icon(Icons.Rounded.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

private data class NameEdit(val id: Long?, val initial: String)

/**
 * Меню профиля: участники (переключение, добавление, переименование, архив), резервная копия,
 * экспорт, подключение ИИ и обновления — всё с подписями, а не безымянными значками.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSheet(onDismiss: () -> Unit, onDataRestored: () -> Unit, onOpenUpdates: () -> Unit) {
    val context = LocalContext.current
    val container = context.appContainer
    val people by container.profiles.allProfiles.collectAsStateWithLifecycle(emptyList())
    val active by container.profiles.activeId.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val update by container.updater.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var nameEdit by remember { mutableStateOf<NameEdit?>(null) }
    var archiveTarget by remember { mutableStateOf<Athlete?>(null) }
    var backupOpen by remember { mutableStateOf(false) }
    var exportOpen by remember { mutableStateOf(false) }
    var aiOpen by remember { mutableStateOf(false) }
    val activeName = people.firstOrNull { it.id == active }?.name ?: "Я"

    fun run(failure: String, action: suspend () -> Unit, onSuccess: () -> Unit = {}) {
        busy = true; error = null
        scope.launch {
            runCatching { action() }.onSuccess { onSuccess() }.onFailure { error = it.message ?: failure }
            busy = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Профиль и настройки", style = MaterialTheme.typography.titleLarge)
            Text("У каждого участника свой дневник: тренировки, питание, вес и замеры.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            BlockTitle("Участники")
            people.filter { !it.isArchived }.forEach { person ->
                ProfileRow(person, selected = person.id == active, enabled = !busy,
                    onSelect = { run("Не удалось выбрать профиль", { container.profiles.select(person.id) }, onDismiss) },
                    actions = buildList {
                        add(MenuAction("Переименовать", Icons.Rounded.Edit) { nameEdit = NameEdit(person.id, person.name) })
                        if (person.id != 1L) add(MenuAction("Скрыть в архив", Icons.Rounded.Archive) { archiveTarget = person })
                    })
            }
            NavRow("Добавить участника", Icons.Rounded.PersonAdd, onClick = { nameEdit = NameEdit(null, "") },
                subtitle = "Для совместных тренировок на одном телефоне", enabled = !busy)
            val archived = people.filter { it.isArchived }
            if (archived.isNotEmpty()) {
                FitButton(if (showArchived) "Скрыть архив" else "Архив · ${archived.size}", { showArchived = !showArchived },
                    kind = FitButtonKind.Plain, compact = true, enabled = !busy)
                if (showArchived) archived.forEach { person ->
                    ProfileRow(person, selected = false, enabled = false, onSelect = {},
                        actions = listOf(MenuAction("Вернуть из архива", Icons.Rounded.Unarchive) {
                            run("Не удалось вернуть профиль", { container.profiles.restore(person.id) })
                        }))
                }
            }
            if (busy) CircularProgressIndicator(Modifier.padding(8.dp).size(24.dp), strokeWidth = 2.dp)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            BlockTitle("Данные")
            NavRow("Резервная копия", Icons.Rounded.Backup, onClick = { backupOpen = true },
                subtitle = "Сохранить или восстановить весь дневник, все профили и фото", enabled = !busy)
            NavRow("Экспорт для Excel", Icons.Rounded.IosShare, onClick = { exportOpen = true },
                subtitle = "JSON или CSV · $activeName", enabled = !busy)

            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            BlockTitle("Приложение")
            NavRow("Расчёт КБЖУ через ИИ", Icons.Rounded.AutoAwesome, onClick = { aiOpen = true },
                subtitle = if (settings.aiConfigured) "Подключено · ${settings.aiModel}" else "Не подключено", enabled = !busy)
            NavRow("Обновления", Icons.Rounded.SystemUpdate, onClick = onOpenUpdates, enabled = !busy,
                subtitle = "Версия ${BuildConfig.VERSION_NAME}" + when {
                    update.phase == UpdatePhase.DISABLED -> ""
                    update.canInstall -> " · обновление готово"
                    update.release != null -> " · доступна ${update.release?.version}"
                    else -> ""
                })
        }
    }

    nameEdit?.let { edit ->
        ProfileNameDialog(edit.initial, isNew = edit.id == null, busy = busy, error = error,
            onDismiss = { nameEdit = null; error = null },
            onSave = { value ->
                run("Не удалось сохранить", {
                    if (edit.id == null) container.profiles.create(value) else container.profiles.rename(edit.id, value)
                }) { nameEdit = null }
            })
    }
    archiveTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!busy) archiveTarget = null },
            title = { Text("Скрыть профиль ${target.name}?") },
            text = { Text("Вся история сохранится. Профиль можно вернуть из архива. Сначала завершите его текущую тренировку, если она идёт.") },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                run("Не удалось скрыть профиль", { container.profiles.archive(target.id) }) { archiveTarget = null }
            }) { Text("Скрыть") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { archiveTarget = null }) { Text("Отмена") } },
        )
    }
    if (backupOpen) BackupDialog(onDismiss = { backupOpen = false }, onRestored = { onDataRestored(); onDismiss() })
    if (exportOpen) ExportDialog(onDismiss = { exportOpen = false })
    if (aiOpen) AiSettingsDialog(current = settings, onDismiss = { aiOpen = false }, onSave = { baseUrl, model, key ->
        container.settings.updateAiConnection(baseUrl, model, key)
        aiOpen = false
    })
}

@Composable
private fun ProfileRow(person: Athlete, selected: Boolean, enabled: Boolean, onSelect: () -> Unit, actions: List<MenuAction>) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(MaterialTheme.shapes.small)
            .clickable(enabled = enabled && !selected, role = Role.Button, onClickLabel = "Выбрать профиль", onClick = onSelect)
            .padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ProfileAvatar(person.name, person.id, size = 36.dp)
        Column(Modifier.weight(1f)) {
            Text(person.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (selected || person.isArchived) Text(if (selected) "Сейчас выбран" else "В архиве",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) Icon(Icons.Rounded.Check, contentDescription = "Выбран", tint = fitAccents.workout)
        OverflowMenu(actions, contentDescription = "Действия: ${person.name}")
    }
}

@Composable
private fun ProfileNameDialog(initial: String, isNew: Boolean, busy: Boolean, error: String?,
    onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (isNew) "Новый участник" else "Переименовать") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isNew) Text("У участника будут свои тренировки, питание, вес и замеры.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("Имя") }, singleLine = true,
                    enabled = !busy, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(enabled = !busy && name.isNotBlank(), onClick = { onSave(name.trim()) }) {
            Text(if (isNew) "Добавить" else "Сохранить")
        } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Отмена") } },
    )
}

/** Выбор формата экспорта; файл уходит в системное «Поделиться». */
@Composable
private fun ExportDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val container = context.appContainer
    val vm: DataExportViewModel = viewModel {
        DataExportViewModel(
            ExportManager(context.applicationContext, container.workoutRepository, container.nutritionRepository,
                container.statsRepository, container.measurementRepository, container.activityRepository),
            ownerId = { container.profiles.activeId.value },
        )
    }
    val exporting by vm.exporting.collectAsStateWithLifecycle()
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(vm) {
        vm.intents.collect { intent ->
            try { context.startActivity(intent); onDismiss() }
            catch (_: android.content.ActivityNotFoundException) { error = "Нет приложения для отправки файла" }
        }
    }
    LaunchedEffect(vm) { vm.errors.collect { error = it } }
    AlertDialog(
        onDismissRequest = { if (!exporting) onDismiss() },
        title = { Text("Экспорт данных") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Дневник выбранного участника для анализа или Excel. Для переноса всего приложения используйте резервную копию.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                NavRow("JSON", Icons.Rounded.Description, onClick = { error = null; vm.export(ExportFormat.JSON) },
                    subtitle = "Полный экспорт для анализа", enabled = !exporting)
                NavRow("CSV", Icons.Rounded.TableChart, onClick = { error = null; vm.export(ExportFormat.CSV) },
                    subtitle = "Таблицы для Excel", enabled = !exporting)
                if (exporting) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Готовим файлы…")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss, enabled = !exporting) { Text("Закрыть") } },
    )
}
