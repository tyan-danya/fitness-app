package com.dtyan.fitdiary.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dtyan.fitdiary.data.db.WorkoutSummary
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val MONTH_SHORT: DateTimeFormatter = DateTimeFormatter.ofPattern("LLL", Locale("ru"))

/** Строка завершённой тренировки — одинаковая на главной, в статистике и в карточке дня. */
@Composable
fun WorkoutSummaryCard(summary: WorkoutSummary, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FitCard(
        modifier = modifier,
        onClick = onClick,
        onClickLabel = "Открыть тренировку",
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            DateBadge(Format.local(summary.startedAt).toLocalDate())
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(summary.endedAt?.let { Format.durationHuman(it - summary.startedAt) } ?: "Тренировка",
                    style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    "${summary.exerciseCount} упр. · ${summary.setCount} " + Format.plural(summary.setCount, "подход", "подхода", "подходов"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (summary.totalVolume > 0) Text(Format.volume(summary.totalVolume), style = MaterialTheme.typography.titleSmall,
                color = sectionColors().accent, maxLines = 1)
        }
    }
}

/** Квадратный бейдж даты: крупный день и короткий месяц в цвете раздела. */
@Composable
fun DateBadge(date: LocalDate, modifier: Modifier = Modifier) {
    val colors = sectionColors()
    Surface(modifier, shape = MaterialTheme.shapes.small, color = colors.container, contentColor = colors.onContainer) {
        Column(Modifier.width(52.dp).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.titleLarge)
            Text(date.format(MONTH_SHORT).replace(".", ""), style = MaterialTheme.typography.labelSmall)
        }
    }
}
