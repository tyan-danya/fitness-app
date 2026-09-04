package com.dtyan.fitdiary.ui.nutrition

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.dtyan.fitdiary.data.SettingsStore

/**
 * Настройки расчёта КБЖУ через ИИ: OpenAI-совместимый эндпоинт, модель и ключ.
 * Ключ хранится только на устройстве и уходит лишь на указанный сервер.
 */
@Composable
fun AiSettingsDialog(
    current: SettingsStore.Settings,
    onDismiss: () -> Unit,
    onSave: (baseUrl: String, model: String, apiKey: String) -> Unit,
) {
    var baseUrl by rememberSaveable { mutableStateOf(current.aiBaseUrl) }
    var model by rememberSaveable { mutableStateOf(current.aiModel) }
    var apiKey by rememberSaveable { mutableStateOf(current.aiApiKey) }
    var showKey by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Расчёт через ИИ") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Кнопка «Рассчитать через ИИ» в форме приёма отправит название блюда и вес " +
                        "порции на этот сервер и заполнит КБЖУ на 100 г. Подходит OpenAI или любой " +
                        "совместимый сервис (chat/completions).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API-ключ") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                imageVector = if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showKey) "Скрыть ключ" else "Показать ключ",
                            )
                        }
                    },
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Модель") },
                    singleLine = true,
                    supportingText = { Text("Например: ${SettingsStore.DEFAULT_AI_MODEL}") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Базовый URL") },
                    singleLine = true,
                    supportingText = { Text("По умолчанию ${SettingsStore.DEFAULT_AI_BASE_URL}") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(baseUrl, model, apiKey) }) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}
