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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
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
import kotlinx.coroutines.flow.flowOf

@Composable
@OptIn(ExperimentalLayoutApi::class)
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
    val profiles by container.profiles.profiles.collectAsStateWithLifecycle(emptyList())
    val activeId by container.profiles.activeId.collectAsStateWithLifecycle()
    val starting by vm.starting.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var choosingParticipants by rememberSaveable { mutableStateOf(false) }
    var repeatSource by rememberSaveable { mutableStateOf<Long?>(null) }
    var chosenIds by rememberSaveable { mutableStateOf<List<Long>>(emptyList()) }
    var requiredIds by rememberSaveable { mutableStateOf<List<Long>>(emptyList()) }
    val groupFlow = remember(activeWorkout?.id) {
        activeWorkout?.id?.let { container.workoutRepository.observeGroupWorkouts(it) } ?: flowOf(emptyList())
    }
    val currentGroup by groupFlow.collectAsStateWithLifecycle(emptyList())
    val compactStats = LocalDensity.current.fontScale > 1.2f || LocalConfiguration.current.screenWidthDp < 360

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
                StartWorkoutHeroCard(onStart = { if (!starting) vm.startWorkout(onOpenWorkout) })
            } else {
                ActiveWorkoutHeroCard(workout = current, onContinue = { onOpenWorkout(current.id) })
            }
        }

        item(key = "actions") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                        requiredIds = if (activeWorkout == null) emptyList() else
                            listOf(activeId) + currentGroup.map { it.athleteId }.filter { it != activeId }
                        chosenIds = if (requiredIds.isNotEmpty()) requiredIds.toList() else
                            listOf(activeId) + profiles.map { it.id }.filter { it != activeId }
                        repeatSource = null
                        choosingParticipants = true
                    }, enabled = !starting && profiles.size > 1, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(if (activeWorkout == null) "Тренироваться вместе" else "Добавить участников")
                    }
                    if (profiles.size < 2) Text("Добавьте друзей через выбор профиля сверху.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (activeWorkout == null) {
                    summaries.firstOrNull()?.let { last ->
                        TextButton(onClick = {
                            chosenIds = listOf(activeId)
                            requiredIds = emptyList()
                            repeatSource = last.id
                            choosingParticipants = true
                        }, enabled = !starting, modifier = Modifier.fillMaxWidth()) { Text("Повторить упражнения прошлой тренировки") }
                    }
                }
                if (starting) Text("Готовим тренировку…")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }

        item(key = "stats") {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = if (compactStats) 2 else 3,
            ) {
                StatChip(
                    icon = Icons.Filled.LocalFireDepartment,
                    iconTint = fitAccents.nutrition,
                    value = "${stats.weeklyStreak} нед",
                    label = "стрик",
                    modifier = Modifier
                        .weight(1f),
                )
                StatChip(
                    icon = Icons.Filled.FitnessCenter,
                    iconTint = fitAccents.workout,
                    value = "${stats.workoutsLast7Days}",
                    label = "за 7 дней",
                    modifier = Modifier
                        .weight(1f),
                )
                StatChip(
                    icon = Icons.Filled.MonitorWeight,
                    iconTint = fitAccents.stats,
                    value = latestWeight?.let { Format.weight(it.weightKg) } ?: "—",
                    label = "вес, кг",
                    modifier = Modifier
                        .weight(1f),
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
    if (choosingParticipants) AlertDialog(
        onDismissRequest = { if (!starting) choosingParticipants = false },
        title = { Text(if (repeatSource != null) "Повторить упражнения" else "Кто тренируется?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(when {
                    repeatSource != null -> "Скопируется только список упражнений. Веса и подходы записываются заново."
                    requiredIds.isNotEmpty() -> "Выберите, кто присоединится. Имеющиеся участники и подходы сохранятся."
                    else -> "У каждого будут свои подходы, история и рекорды."
                })
                profiles.forEach { athlete ->
                    val alreadyFinished = currentGroup.any { it.athleteId == athlete.id && it.endedAt != null }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = athlete.id in chosenIds, enabled = !starting && athlete.id !in requiredIds, onCheckedChange = { selected ->
                            chosenIds = if (selected) (chosenIds + athlete.id).distinct() else chosenIds.filter { it != athlete.id }
                        })
                        Text(athlete.name + when {
                            athlete.id !in requiredIds -> ""
                            alreadyFinished -> " · завершил"
                            else -> " · уже в тренировке"
                        })
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { Button(enabled = chosenIds.isNotEmpty() && !starting, onClick = {
            val done: (Long) -> Unit = { choosingParticipants = false; onOpenWorkout(it) }
            val source = repeatSource
            val ids = chosenIds.sortedBy { if (it == activeId) 0 else 1 }
            if (source == null) vm.startTogether(ids, done)
            else vm.repeatWorkout(source, ids, done)
        }) { Text(if (starting) "Готовим…" else "Начать") } },
        dismissButton = { TextButton(enabled = !starting, onClick = { choosingParticipants = false }) { Text("Отмена") } },
    )
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

/** Продолжение тренировки без постоянно обновляющегося отсчёта. */
@Composable
private fun ActiveWorkoutHeroCard(workout: Workout, onContinue: () -> Unit) {
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
                        text = "Продолжить тренировку",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                }
                Text(
                    text = "Начало: ${Format.dateShort(Format.local(workout.startedAt).toLocalDate())}, ${Format.time(workout.startedAt)}",
                    style = MaterialTheme.typography.bodyMedium,
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
