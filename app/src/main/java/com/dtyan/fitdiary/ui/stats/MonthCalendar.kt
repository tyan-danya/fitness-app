package com.dtyan.fitdiary.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dtyan.fitdiary.ui.common.pressScale
import com.dtyan.fitdiary.ui.theme.fitAccents
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val RU = Locale("ru")
private val MONTH_TITLE: DateTimeFormatter = DateTimeFormatter.ofPattern("LLLL yyyy", RU)
private val WEEKDAYS = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

/**
 * Календарь месяца: точки-маркеры тренировок (fitAccents.workout) и питания
 * (fitAccents.nutrition), день с тренировкой залит workoutContainer,
 * клик по дню с данными открывает детали дня.
 */
@Composable
fun MonthCalendar(
    month: YearMonth,
    workoutDays: Set<LocalDate>,
    mealDays: Set<LocalDate>,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onDayClick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CalendarArrow(
                onClick = onPreviousMonth,
                icon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Предыдущий месяц") },
            )
            Text(
                text = month.format(MONTH_TITLE).replaceFirstChar { it.titlecase(RU) },
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            CalendarArrow(
                onClick = onNextMonth,
                icon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Следующий месяц") },
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            WEEKDAYS.forEach { day ->
                Text(
                    text = day,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        val today = LocalDate.now()
        val firstDay = month.atDay(1)
        // Смещение первого дня в сетке Пн..Вс (ISO: понедельник = 1)
        val offset = firstDay.dayOfWeek.value - 1
        val totalCells = offset + month.lengthOfMonth()
        val weeks = (totalCells + 6) / 7

        repeat(weeks) { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                repeat(7) { column ->
                    val dayOfMonth = week * 7 + column - offset + 1
                    if (dayOfMonth in 1..month.lengthOfMonth()) {
                        val date = month.atDay(dayOfMonth)
                        DayCell(
                            date = date,
                            isToday = date == today,
                            hasWorkout = date in workoutDays,
                            hasMeals = date in mealDays,
                            onClick = { onDayClick(date) },
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Box(modifier = Modifier.weight(1f).height(48.dp))
                    }
                }
            }
        }
    }
}

/** Круглая тональная стрелка навигации по месяцам. */
@Composable
private fun CalendarArrow(
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    FilledTonalIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        content = icon,
    )
}

@Composable
private fun DayCell(
    date: LocalDate,
    isToday: Boolean,
    hasWorkout: Boolean,
    hasMeals: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accents = fitAccents
    val hasData = hasWorkout || hasMeals
    val isWeekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
    Box(
        modifier = modifier.height(48.dp).padding(2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .size(42.dp)
                .then(if (hasData) Modifier.pressScale(0.9f) else Modifier)
                .clip(CircleShape)
                // День с тренировкой — залитый круг фирменного зелёного контейнера
                .then(if (hasWorkout) Modifier.background(accents.workoutContainer) else Modifier)
                // Сегодняшний день — рамка акцентом раздела «Статистика»
                .then(if (isToday) Modifier.border(2.dp, accents.stats, CircleShape) else Modifier)
                .then(if (hasData) Modifier.clickable(onClick = onClick) else Modifier),
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    hasWorkout -> accents.onWorkoutContainer
                    isToday -> accents.stats
                    isWeekend -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            // Слот под маркеры всегда занят — числа всех ячеек на одной высоте
            Row(
                modifier = Modifier.height(5.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                if (hasWorkout) Dot(accents.workout)
                if (hasMeals) Dot(accents.nutrition)
            }
        }
    }
}

@Composable
private fun Dot(color: Color) {
    Box(
        modifier = Modifier
            .size(5.dp)
            .clip(CircleShape)
            .background(color),
    )
}
