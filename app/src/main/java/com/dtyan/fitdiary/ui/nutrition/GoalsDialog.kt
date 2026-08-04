package com.dtyan.fitdiary.ui.nutrition

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.dp
import com.dtyan.fitdiary.data.SettingsStore

/** Диалог настройки дневных целей: калории и БЖУ в граммах, с мини-подсказками под полями. */
@Composable
fun GoalsDialog(
    current: SettingsStore.Settings,
    onDismiss: () -> Unit,
    onSave: (calorieGoal: Int, proteinGoalG: Int, fatGoalG: Int, carbGoalG: Int) -> Unit,
) {
    var caloriesText by rememberSaveable { mutableStateOf(current.calorieGoal.toString()) }
    var proteinText by rememberSaveable { mutableStateOf(current.proteinGoalG.toString()) }
    var fatText by rememberSaveable { mutableStateOf(current.fatGoalG.toString()) }
    var carbsText by rememberSaveable { mutableStateOf(current.carbGoalG.toString()) }

    val calories = caloriesText.trim().toIntOrNull()
    val protein = proteinText.trim().toIntOrNull()
    val fat = fatText.trim().toIntOrNull()
    val carbs = carbsText.trim().toIntOrNull()
    val allValid = listOf(calories, protein, fat, carbs).all { it != null && it > 0 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Цели на день") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                GoalField(caloriesText, { caloriesText = it }, "Цель, ккал", "Сколько энергии планируете в день")
                GoalField(proteinText, { proteinText = it }, "Белки, г", "Для мышц и восстановления")
                GoalField(fatText, { fatText = it }, "Жиры, г", "Для гормонов и энергии")
                GoalField(carbsText, { carbsText = it }, "Углеводы, г", "Топливо для тренировок")
            }
        },
        confirmButton = {
            TextButton(
                enabled = allValid,
                onClick = { onSave(calories!!, protein!!, fat!!, carbs!!) },
            ) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

/** Поле цели с мини-подсказкой под ним — зачем нужна эта цифра. */
@Composable
private fun GoalField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    hint: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        supportingText = {
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}
