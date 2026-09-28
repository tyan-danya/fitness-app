package com.dtyan.fitdiary.ui.profile

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dtyan.fitdiary.appContainer
import com.dtyan.fitdiary.data.db.Athlete
import com.dtyan.fitdiary.ui.backup.BackupDialog
import kotlinx.coroutines.launch

@Composable
fun ProfileToolbar(onDataRestored: () -> Unit, onUpdates: () -> Unit = {}) {
    val container = LocalContext.current.appContainer
    val people by container.profiles.profiles.collectAsStateWithLifecycle(emptyList())
    val active by container.profiles.activeId.collectAsStateWithLifecycle()
    var managing by remember { mutableStateOf(false) }
    var backup by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { managing = true }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Person, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(people.firstOrNull { it.id == active }?.name ?: "Я")
                Text(" ▾")
            }
            IconButton(onClick = { backup = true }) {
                Icon(Icons.Default.Backup, contentDescription = "Резервная копия и восстановление")
            }
            IconButton(onClick = onUpdates) {
                Icon(Icons.Default.SystemUpdate, contentDescription = "Обновления приложения")
            }
        }
    }
    if (managing) ProfilesDialog(onDismiss = { managing = false })
    if (backup) BackupDialog(onDismiss = { backup = false }, onRestored = onDataRestored)
}

@Composable
private fun ProfilesDialog(onDismiss: () -> Unit) {
    val container = LocalContext.current.appContainer
    val people by container.profiles.allProfiles.collectAsStateWithLifecycle(emptyList())
    val active by container.profiles.activeId.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showArchived by remember { mutableStateOf(false) }
    var archiveTarget by remember { mutableStateOf<Athlete?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Участники") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("У каждого — свой дневник, питание, вес и замеры.", style = MaterialTheme.typography.bodyMedium)
                LazyColumn(Modifier.heightIn(max = 260.dp)) {
                    items(people.filter { showArchived || !it.isArchived }, key = { it.id }) { person ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(enabled = !busy && !person.isArchived, onClick = {
                                busy = true
                                scope.launch {
                                    runCatching { container.profiles.select(person.id) }
                                        .onSuccess { onDismiss() }.onFailure { error = it.message }
                                    busy = false
                                }
                            }, modifier = Modifier.weight(1f)) {
                                if (person.id == active) {
                                    Icon(Icons.Default.Check, contentDescription = "Выбран")
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(if (person.isArchived) "${person.name} · архив" else person.name)
                            }
                            IconButton(enabled = !busy, onClick = { editing = person.id; name = person.name; error = null }) {
                                Icon(Icons.Default.Edit, contentDescription = "Переименовать: ${person.name}")
                            }
                            if (person.id != 1L) IconButton(enabled = !busy, onClick = {
                                if (person.isArchived) {
                                    busy = true
                                    scope.launch {
                                        runCatching { container.profiles.restore(person.id) }
                                            .onFailure { error = it.message ?: "Не удалось восстановить профиль" }
                                        busy = false
                                    }
                                } else archiveTarget = person
                            }) {
                                Icon(if (person.isArchived) Icons.Default.Unarchive else Icons.Default.Archive,
                                    contentDescription = "${if (person.isArchived) "Вернуть" else "Скрыть"}: ${person.name}")
                            }
                        }
                    }
                }
                if (people.any { it.isArchived }) TextButton(onClick = { showArchived = !showArchived }, enabled = !busy) {
                    Text(if (showArchived) "Скрыть архив" else "Показать архив")
                }
                OutlinedTextField(value = name, onValueChange = { name = it.take(40); error = null },
                    label = { Text(if (editing == null) "Имя нового участника" else "Новое имя") },
                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(enabled = !busy && name.isNotBlank(), onClick = {
                    busy = true
                    val currentId = editing
                    val value = name.trim()
                    scope.launch {
                        runCatching {
                            if (currentId == null) container.profiles.create(value) else container.profiles.rename(currentId, value)
                        }.onSuccess { name = ""; editing = null }.onFailure { error = it.message ?: "Не удалось сохранить" }
                        busy = false
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text(if (editing == null) "Добавить участника" else "Сохранить имя") }
                if (editing != null) TextButton(onClick = { editing = null; name = "" }, enabled = !busy) { Text("Отменить переименование") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Готово") } },
    )
    archiveTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!busy) archiveTarget = null },
            title = { Text("Скрыть профиль ${target.name}?") },
            text = { Text("Вся история сохранится. Профиль можно вернуть из архива. Сначала завершите его текущую тренировку, если она идёт.") },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        runCatching { container.profiles.archive(target.id) }
                            .onSuccess { if (editing == target.id) { editing = null; name = "" } }
                            .onFailure { error = it.message ?: "Не удалось скрыть профиль" }
                        archiveTarget = null
                        busy = false
                    }
                }) { Text("Скрыть") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { archiveTarget = null }) { Text("Отмена") } },
        )
    }
}
