package com.dtyan.fitdiary.ui.activity

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dtyan.fitdiary.ui.common.sectionColors
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DAY_LETTERS = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
private val SHORT_DATE = DateTimeFormatter.ofPattern("d MMM", Locale("ru"))

/** Понедельник недели, в которую попадает день. */
internal fun mondayOf(epochDay: Long): Long = LocalDate.ofEpochDay(epochDay).with(DayOfWeek.MONDAY).toEpochDay()

/**
 * Неделя серии: семь кружков Пн–Вс. Закрытый день — залитый кружок с галочкой, начатый — обводка с числом,
 * пустой — бледный, будущий — без отметки. Стрелки листают недели назад и обратно до текущей.
 */
@Composable
fun WeekStreakStrip(
    totals: Map<Long, Long>,
    target: Int?,
    todayEpochDay: Long,
    anchorDay: Long,
    modifier: Modifier = Modifier,
) {
    val colors = sectionColors()
    var monday by rememberSaveable(anchorDay) { mutableLongStateOf(mondayOf(anchorDay)) }
    val currentMonday = mondayOf(todayEpochDay)
    Column(modifier.fillMaxWidth().testTag("pushup-week"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { monday -= 7 }) { Icon(Icons.Rounded.ChevronLeft, contentDescription = "Предыдущая неделя") }
            Text(
                if (monday == currentMonday) "Эта неделя" else
                    "${LocalDate.ofEpochDay(monday).format(SHORT_DATE)} — ${LocalDate.ofEpochDay(monday + 6).format(SHORT_DATE)}",
                style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { monday += 7 }, enabled = monday < currentMonday) {
                Icon(Icons.Rounded.ChevronRight, contentDescription = "Следующая неделя")
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (offset in 0..6) {
                val day = monday + offset
                val total = totals[day] ?: 0L
                val future = day > todayEpochDay
                val reached = target != null && total >= target
                val status = when {
                    future -> "впереди"
                    reached -> "цель закрыта, $total повторов"
                    total > 0 -> "$total повторов"
                    else -> "без отжиманий"
                }
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) {
                    contentDescription = "${DAY_LETTERS[offset]} ${LocalDate.ofEpochDay(day).dayOfMonth}: $status"
                }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(DAY_LETTERS[offset], style = MaterialTheme.typography.labelSmall,
                        color = if (day == todayEpochDay) colors.accent else MaterialTheme.colorScheme.onSurfaceVariant)
                    Surface(
                        modifier = Modifier.widthIn(max = 40.dp).fillMaxWidth().aspectRatio(1f),
                        shape = CircleShape,
                        color = when {
                            reached -> colors.accent
                            total > 0 && !future -> colors.container
                            else -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (future) 0.4f else 1f)
                        },
                        contentColor = if (reached) colors.onAccent else colors.onContainer,
                        border = when {
                            day == todayEpochDay -> BorderStroke(2.dp, colors.accent)
                            total > 0 && !reached -> BorderStroke(1.dp, colors.accent.copy(alpha = 0.6f))
                            else -> null
                        },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            when {
                                reached -> Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                total > 0 && !future -> Text("$total", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                else -> Text("${LocalDate.ofEpochDay(day).dayOfMonth}", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (future) 0.5f else 0.8f))
                            }
                        }
                    }
                }
            }
        }
    }
}
