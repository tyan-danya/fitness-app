package com.dtyan.fitdiary.ui.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dtyan.fitdiary.appContainer
import com.dtyan.fitdiary.data.db.Workout
import com.dtyan.fitdiary.data.db.WorkoutSummary
import com.dtyan.fitdiary.ui.common.EmptyState
import com.dtyan.fitdiary.ui.common.Format
import com.dtyan.fitdiary.ui.common.HeroBarbellDecor
import com.dtyan.fitdiary.ui.common.IllustrationDumbbell
import com.dtyan.fitdiary.ui.common.SectionHeader
import com.dtyan.fitdiary.ui.common.StatChip
import com.dtyan.fitdiary.ui.common.pressScale
import com.dtyan.fitdiary.ui.theme.fitAccents
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    onOpenWorkout: (Long) -> Unit,
    onOpenWorkoutDetails: (Long) -> Unit,
) {
    val container = LocalContext.current.appContainer
    val vm: HomeViewModel = viewModel {
        HomeViewModel(container.workoutRepository, container.statsRepository)
    }

    val activeWorkout by vm.activeWorkout.collectAsStateWithLifecycle()
    val summaries by vm.summaries.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val latestWeight by vm.latestWeight.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "greeting") {
            GreetingBlock()
        }

        item(key = "hero") {
            val current = activeWorkout
            if (current == null) {
                StartWorkoutHeroCard(onStart = { vm.startWorkout(onOpenWorkout) })
            } else {
                ActiveWorkoutHeroCard(workout = current, onContinue = { onOpenWorkout(current.id) })
            }
        }

        item(key = "stats") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatChip(
                    icon = Icons.Filled.LocalFireDepartment,
                    iconTint = fitAccents.nutrition,
                    value = "${stats.weeklyStreak} нед",
                    label = "стрик",
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
                StatChip(
                    icon = Icons.Filled.FitnessCenter,
                    iconTint = fitAccents.workout,
                    value = "${stats.workoutsLast7Days}",
                    label = "за 7 дней",
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
                StatChip(
                    icon = Icons.Filled.MonitorWeight,
                    iconTint = fitAccents.stats,
                    value = latestWeight?.let { Format.weight(it.weightKg) } ?: "—",
                    label = "вес, кг",
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
        }

        item(key = "header_recent") {
            SectionHeader(
                icon = Icons.Filled.History,
                title = "Последние тренировки",
                modifier = Modifier.padding(top = 8.dp),
                iconTint = fitAccents.workout,
                iconContainer = fitAccents.workoutContainer,
            )
        }

        if (summaries.isEmpty()) {
            item(key = "empty_history") {
                EmptyState(
                    title = "Пока пусто",
                    subtitle = "Первая тренировка начинается с одной кнопки",
                    illustration = { IllustrationDumbbell() },
                )
            }
        } else {
            items(summaries.take(5), key = { it.id }) { summary ->
                WorkoutSummaryCard(
                    summary = summary,
                    onClick = { onOpenWorkoutDetails(summary.id) },
                )
            }
        }
    }
}

/** Приветствие по времени суток + короткий призыв. */
@Composable
private fun GreetingBlock() {
    val greeting = remember {
        val hour = LocalTime.now().hour
        when {
            hour < 12 -> "Доброе утро 👋"
            hour < 18 -> "Добрый день 👋"
            else -> "Добрый вечер 👋"
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(greeting, style = MaterialTheme.typography.headlineMedium)
        Text(
            text = "Готов к тренировке?",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Hero-карточка старта: градиент раздела + рисованный декор штанги, целиком кликабельна. */
@Composable
private fun StartWorkoutHeroCard(onStart: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale()
            .clip(MaterialTheme.shapes.large)
            .background(Brush.linearGradient(fitAccents.workoutGradient))
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onStart()
            },
    ) {
        HeroBarbellDecor(Modifier.matchParentSize())
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // На градиенте — единственное место, где белый задаём явно.
            Text(
                text = "Начать тренировку",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
            )
            Text(
                text = "Записывай подходы — следи за прогрессом",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}

/** Та же hero-карточка, когда тренировка уже идёт: пульс + живой таймер. */
@Composable
private fun ActiveWorkoutHeroCard(workout: Workout, onContinue: () -> Unit) {
    // Живой таймер: тикаем раз в секунду, пока карточка на экране.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(workout.id) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    val haptics = LocalHapticFeedback.current
    val pulse by rememberInfiniteTransition(label = "livePulse").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 650), RepeatMode.Reverse),
        label = "livePulseAlpha",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale()
            .clip(MaterialTheme.shapes.large)
            .background(Brush.linearGradient(fitAccents.workoutGradient))
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onContinue()
            },
    ) {
        HeroBarbellDecor(Modifier.matchParentSize())
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = pulse)),
                    )
                    Text(
                        text = "Тренировка идёт",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                }
                Text(
                    text = Format.durationClock(now - workout.startedAt),
                    style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"),
                    color = Color.White,
                )
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Продолжить",
                    tint = Color.White,
                )
            }
        }
    }
}

private val MONTH_SHORT: DateTimeFormatter = DateTimeFormatter.ofPattern("LLL", Locale("ru"))

/** Квадратик-бейдж даты: день крупно, месяц мелко. */
@Composable
private fun DateBadge(date: LocalDate) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = fitAccents.workoutContainer,
        contentColor = fitAccents.onWorkoutContainer,
    ) {
        Column(
            modifier = Modifier.size(52.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("${date.dayOfMonth}", style = MaterialTheme.typography.titleLarge)
            Text(
                text = date.format(MONTH_SHORT).replace(".", ""),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun WorkoutSummaryCard(summary: WorkoutSummary, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DateBadge(Format.local(summary.startedAt).toLocalDate())
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val duration = (summary.endedAt ?: summary.startedAt) - summary.startedAt
                Text(
                    text = Format.durationHuman(duration),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append("${summary.setCount} ")
                        append(ruPlural(summary.setCount, "подход", "подхода", "подходов"))
                        if (summary.totalVolume > 0) {
                            append(" · ${Format.volume(summary.totalVolume)}")
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Русские формы множественного числа: 1 подход, 2 подхода, 5 подходов. */
private fun ruPlural(n: Int, one: String, few: String, many: String): String {
    val mod10 = n % 10
    val mod100 = n % 100
    return when {
        mod10 == 1 && mod100 != 11 -> one
        mod10 in 2..4 && mod100 !in 12..14 -> few
        else -> many
    }
}
