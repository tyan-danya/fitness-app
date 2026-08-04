package com.dtyan.fitdiary.ui.nutrition

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dtyan.fitdiary.data.db.Meal
import com.dtyan.fitdiary.ui.common.pressScale
import com.dtyan.fitdiary.ui.theme.fitAccents
import kotlinx.coroutines.launch

/**
 * Нижняя шторка добавления/редактирования приёма пищи — одна форма на оба режима.
 * Чипы «Недавнее» показываются только при добавлении нового приёма.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealEditorSheet(
    state: MealEditorState,
    recent: List<Meal>,
    onNameChange: (String) -> Unit,
    onCaloriesChange: (String) -> Unit,
    onProteinChange: (String) -> Unit,
    onFatChange: (String) -> Unit,
    onCarbsChange: (String) -> Unit,
    onApplyRecent: (Meal) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (state.isNew) "Приём пищи" else "Редактировать",
                style = MaterialTheme.typography.titleLarge,
            )

            if (state.isNew && recent.isNotEmpty()) {
                // Мини-заголовок секции: иконка в плашке цвета раздела + подпись
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = RoundedCornerShape(7.dp),
                        color = fitAccents.nutritionContainer,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Bolt,
                            contentDescription = null,
                            tint = fitAccents.nutrition,
                            modifier = Modifier
                                .padding(3.dp)
                                .size(14.dp),
                        )
                    }
                    Text(
                        text = "Недавнее",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(recent, key = { it.id }) { meal ->
                        RecentMealChip(meal = meal, onClick = { onApplyRecent(meal) })
                    }
                }
            }

            OutlinedTextField(
                value = state.name,
                onValueChange = onNameChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Название") },
                singleLine = true,
            )
            // Калории — главное число формы, поэтому вводится крупно
            OutlinedTextField(
                value = state.caloriesText,
                onValueChange = onCaloriesChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.headlineSmall,
                label = { Text("Калории") },
                singleLine = true,
                isError = state.caloriesText.isNotBlank() && !state.caloriesValid,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next,
                ),
            )
            MacroField(
                value = state.proteinText,
                onValueChange = onProteinChange,
                label = "Белки, г",
                imeAction = ImeAction.Next,
            )
            MacroField(
                value = state.fatText,
                onValueChange = onFatChange,
                label = "Жиры, г",
                imeAction = ImeAction.Next,
            )
            MacroField(
                value = state.carbsText,
                onValueChange = onCarbsChange,
                label = "Углеводы, г",
                imeAction = ImeAction.Done,
            )

            Button(
                onClick = {
                    // Лёгкий «щелчок» — приём записан; затем анимация закрытия и сохранение
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    scope.launch { sheetState.hide() }.invokeOnCompletion { onSave() }
                },
                enabled = state.canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .pressScale(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = fitAccents.nutrition,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ),
            ) {
                Text(
                    text = if (state.isNew) "Добавить" else "Сохранить",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

/** Чип недавнего блюда: пилюля в цвете раздела — название и калории, тап заполняет форму. */
@Composable
private fun RecentMealChip(meal: Meal, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .widthIn(max = 200.dp)
            .pressScale(),
        shape = CircleShape,
        color = fitAccents.nutritionContainer,
        contentColor = fitAccents.onNutritionContainer,
    ) {
        Column(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = meal.name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${meal.calories} ккал",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** Поле одного макронутриента: Double, необязательное (пусто = 0), десятичная клавиатура. */
@Composable
private fun MacroField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    imeAction: ImeAction,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = imeAction,
        ),
    )
}
