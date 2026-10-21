package com.dtyan.fitdiary.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dtyan.fitdiary.domain.ExerciseGuide

/** Personal photographs stay first; the bundled drawing also works with a missing photo file. */
@Composable
fun ExerciseGuideThumb(
    guide: ExerciseGuide,
    photoPath: String? = null,
    size: Dp = 56.dp,
    modifier: Modifier = Modifier,
    displayName: String = guide.title,
) {
    var open by rememberSaveable(guide.key, displayName) { mutableStateOf(false) }
    val art = ExerciseArtwork.find(guide.key)
    ExercisePhotoThumb(photoPath, size, modifier
        .semantics { contentDescription = "Схема и техника: $displayName" }
        .clickable(role = Role.Button, onClickLabel = "Открыть схему и технику") { open = true }) {
        Surface(Modifier.fillMaxSize(), color = art?.let { if (it.darkBackground) Color(0xFF26303C) else Color.White }
            ?: MaterialTheme.colorScheme.surfaceContainerHigh) {
            val frame = art?.frames?.firstOrNull()
            if (frame != null) Image(painterResource(frame), contentDescription = null,
                modifier = Modifier.fillMaxSize().padding(2.dp), contentScale = ContentScale.Fit)
            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.FitnessCenter, contentDescription = null,
                    modifier = Modifier.size(size * 0.5f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (open) ExerciseGuideDialog(guide, photoPath, displayName) { open = false }
}

@Composable
fun ExerciseGuideButton(guide: ExerciseGuide, photoPath: String? = null, displayName: String = guide.title) {
    var open by rememberSaveable(guide.key, displayName) { mutableStateOf(false) }
    FitButton("Схема и техника", { open = true }, Modifier.semantics { contentDescription = "Схема и техника: $displayName" },
        kind = FitButtonKind.Plain, compact = true, icon = Icons.AutoMirrored.Rounded.MenuBook)
    if (open) ExerciseGuideDialog(guide, photoPath, displayName) { open = false }
}

@Composable
fun ExerciseGuideDialog(
    guide: ExerciseGuide,
    photoPath: String? = null,
    displayName: String = guide.title,
    onDismiss: () -> Unit,
) {
    val art = ExerciseArtwork.find(guide.key)
    val links = LocalUriHandler.current
    var showPhoto by rememberSaveable(photoPath) { mutableStateOf(false) }
    var linkError by remember { mutableStateOf(false) }
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.92f
    fun openLink(url: String) {
        linkError = runCatching { links.openUri(url) }.isFailure
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(12.dp).heightIn(max = maxHeight),
            shape = MaterialTheme.shapes.large) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(displayName, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Закрыть справку") }
                }
                LazyColumn(Modifier.weight(1f, fill = false).testTag("exercise-guide-content"),
                    contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (guide.isGeneric) item("generic") {
                        Text("Общая справка об оборудовании. Для точной техники уточните название упражнения.",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    if (art != null && art.frames.isNotEmpty()) {
                        art.frames.forEachIndexed { index, frame ->
                            item("frame_$index") {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (art.frames.size > 1) Text("Положение ${index + 1}", style = MaterialTheme.typography.labelLarge)
                                    Surface(shape = MaterialTheme.shapes.medium,
                                        color = if (art.darkBackground) Color(0xFF26303C) else Color.White) {
                                        Image(painterResource(frame), contentDescription = if (art.frames.size > 1)
                                            "${guide.title}: положение ${index + 1}" else "${guide.title}: схема упражнения",
                                            modifier = Modifier.fillMaxWidth().aspectRatio(1.25f).padding(8.dp), contentScale = ContentScale.Fit)
                                    }
                                }
                            }
                        }
                        if (art.caption.isNotBlank()) item("caption") { Text(art.caption, style = MaterialTheme.typography.bodySmall) }
                    } else item("no_drawing") {
                        Text("Для этого упражнения пока нет встроенной схемы.", style = MaterialTheme.typography.bodySmall)
                    }
                    if (photoPath != null) item("personal_photo") {
                        TextButton(onClick = { showPhoto = true }) { Text("Открыть моё фото тренажёра") }
                    }
                    item("recognition") {
                        GuideSection("Как узнать тренажёр", listOf(guide.equipmentTitle, guide.equipmentDescription).filter { it.isNotBlank() })
                    }
                    if (guide.setup.isNotEmpty()) item("setup") { GuideSection("Как настроить и занять положение", guide.setup, numbered = true) }
                    if (guide.movement.isNotEmpty()) item("movement") { GuideSection("Как выполнить", guide.movement, numbered = true) }
                    if (guide.mistakes.isNotEmpty()) item("mistakes") { GuideSection("Частые ошибки", guide.mistakes) }
                    if (art != null) item("art_credit") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Иллюстрация: ${art.credit}", style = MaterialTheme.typography.bodySmall)
                            if (art.sourceUrl.isNotBlank()) TextButton(onClick = { openLink(art.sourceUrl) }) { Text("Источник иллюстрации") }
                            if (art.licenseUrl.isNotBlank()) TextButton(onClick = { openLink(art.licenseUrl) }) { Text("Лицензия иллюстрации") }
                        }
                    }
                    if (guide.sources.isNotEmpty()) item("sources") {
                        Column {
                            Text("Источники описания", style = MaterialTheme.typography.titleSmall)
                            guide.sources.forEach { source ->
                                TextButton(onClick = { openLink(source.url) }) { Text(source.title) }
                            }
                        }
                    }
                    if (linkError) item("link_error") { Text("Не удалось открыть ссылку.", color = MaterialTheme.colorScheme.error) }
                }
                FitButton("Закрыть", onDismiss, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    kind = FitButtonKind.Tonal)
            }
        }
    }
    if (showPhoto && photoPath != null) PhotoViewerDialog(photoPath) { showPhoto = false }
}

@Composable
private fun GuideSection(title: String, lines: List<String>, numbered: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        lines.forEachIndexed { index, text -> Text(if (numbered) "${index + 1}. $text" else text) }
    }
}
