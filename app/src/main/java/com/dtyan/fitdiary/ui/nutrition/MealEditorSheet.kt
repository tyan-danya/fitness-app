package com.dtyan.fitdiary.ui.nutrition

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.dtyan.fitdiary.domain.MealType
import com.dtyan.fitdiary.ui.common.Format
import com.dtyan.fitdiary.ui.common.pressScale
import com.dtyan.fitdiary.ui.theme.fitAccents
import kotlinx.coroutines.launch

/** Колбэки формы приёма пищи — собраны в один объект, чтобы сигнатура шторки не расползалась. */
data class MealEditorActions(
    val onNameChange: (String) -> Unit,
    val onMealTypeChange: (MealType) -> Unit,
    val onServingChange: (String) -> Unit,
    val onBasisChange: (EntryBasis) -> Unit,
    val onCaloriesChange: (String) -> Unit,
    val onProteinChange: (String) -> Unit,
    val onFatChange: (String) -> Unit,
    val onCarbsChange: (String) -> Unit,
    val onEstimateLaterChange: (Boolean) -> Unit,
    val onEstimateAi: () -> Unit,
    val onOpenAiSettings: () -> Unit,
    val onApplyRecent: (Meal) -> Unit,
    val onSave: () -> Unit,
    val onDismiss: () -> Unit,
)

/**
 * Нижняя шторка добавления/редактирования приёма пищи — одна форма на оба режима.
 * Порядок: тип приёма → недавнее → название → вес порции → режим ввода («на 100 г» / «вся порция»)
 * → КБЖУ → живой итог порции → расчёт через ИИ / «рассчитать позже» → сохранить.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealEditorSheet(
    state: MealEditorState,
    recent: List<Meal>,
    aiConfigured: Boolean,
    actions: MealEditorActions,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val per100 = state.basis == EntryBasis.PER_100G

    ModalBottomSheet(
        onDismissRequest = actions.onDismiss,
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

            // Тип приёма: предвыбран по времени, меняется одним тапом
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MealType.ORDERED.forEach { type ->
                    FilterChip(
                        selected = state.mealType == type,
                        onClick = { actions.onMealTypeChange(type) },
                        label = { Text("${type.emoji} ${type.title}") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = fitAccents.nutritionContainer,
                            selectedLabelColor = fitAccents.onNutritionContainer,
                        ),
                    )
                }
            }

            if (state.isNew && recent.isNotEmpty()) {
                MiniSectionLabel(text = "Недавнее")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(recent, key = { it.id }) { meal ->
                        RecentMealChip(meal = meal, onClick = { actions.onApplyRecent(meal) })
                    }
                }
            }

            OutlinedTextField(
                value = state.name,
                onValueChange = actions.onNameChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Название") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )

            OutlinedTextField(
                value = state.servingText,
                onValueChange = actions.onServingChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Вес порции, г") },
                singleLine = true,
                isError = per100 && state.servingText.isNotBlank() && !state.servingValid,
                supportingText = if (per100 && state.servingText.isBlank() && !state.estimateLater) {
                    { Text("Нужен, чтобы посчитать итог из значений на 100 г") }
                } else null,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Next,
                ),
            )

            // Режим ввода: как на упаковке (на 100 г) или сразу итог порции
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = per100,
                    onClick = { actions.onBasisChange(EntryBasis.PER_100G) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    label = { Text("На 100 г") },
                )
                SegmentedButton(
                    selected = !per100,
                    onClick = { actions.onBasisChange(EntryBasis.TOTAL) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    label = { Text("Вся порция") },
                )
            }

            // Калории — главное число формы, поэтому вводится крупно
            OutlinedTextField(
                value = state.caloriesText,
                onValueChange = actions.onCaloriesChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.headlineSmall,
                label = { Text(if (per100) "Ккал на 100 г" else "Калории, всего") },
                singleLine = true,
                isError = state.caloriesText.isNotBlank() && !state.caloriesValid,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Next,
                ),
            )
            val suffix = if (per100) " на 100 г" else ""
            MacroField(state.proteinText, actions.onProteinChange, "Белки, г$suffix", ImeAction.Next)
            MacroField(state.fatText, actions.onFatChange, "Жиры, г$suffix", ImeAction.Next)
            MacroField(state.carbsText, actions.onCarbsChange, "Углеводы, г$suffix", ImeAction.Done)

            // Живой итог порции — то, что попадёт в дневник
            val totals = state.totals
            if (per100 && totals != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = fitAccents.nutritionContainer,
                    contentColor = fitAccents.onNutritionContainer,
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(
                            text = "Итого порция ${Format.weight(state.servingG ?: 0.0)} г",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            text = "${totals.calories} ккал · Б ${Format.weight(totals.proteinG)} · " +
                                "Ж ${Format.weight(totals.fatG)} · У ${Format.weight(totals.carbsG)}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }

            // Расчёт через ИИ: кнопка + статус; без ключа ведёт в настройки
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { if (aiConfigured) actions.onEstimateAi() else actions.onOpenAiSettings() },
                    enabled = state.canEstimate,
                    modifier = Modifier.weight(1f),
                ) {
                    if (state.estimating) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Text(
                        text = when {
                            state.estimating -> "  Считаем…"
                            aiConfigured -> "  Рассчитать через ИИ"
                            else -> "  Настроить расчёт через ИИ"
                        },
                    )
                }
            }
            state.estimateError?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            state.estimateNote?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // «Рассчитать позже»: сохранить без КБЖУ, потом выгрузить файл ассистенту
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { actions.onEstimateLaterChange(!state.estimateLater) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = state.estimateLater, onCheckedChange = actions.onEstimateLaterChange)
                Column {
                    Text("Рассчитать позже", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "Сохранить без КБЖУ и выгрузить в файл для расчёта",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Button(
                onClick = {
                    // Лёгкий «щелчок» — приём записан; затем анимация закрытия и сохранение
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    scope.launch { sheetState.hide() }.invokeOnCompletion { actions.onSave() }
                },
                enabled = state.canSave && !state.estimating,
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
            if (!state.canSave && state.name.isNotBlank()) {
                TextButton(
                    onClick = { actions.onEstimateLaterChange(true) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Не знаю КБЖУ — сохранить и рассчитать позже")
                }
            }
        }
    }
}

/** Мини-заголовок секции внутри шторки: иконка в плашке цвета раздела + подпись. */
@Composable
private fun MiniSectionLabel(text: String) {
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
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Чип недавнего блюда: название и ккал (на 100 г, если так вводилось), тап заполняет форму. */
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
            val p = meal.per100
            Text(
                text = if (p != null) "${Format.weight(p.kcal)} ккал / 100 г" else "${meal.calories} ккал",
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
