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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dtyan.fitdiary.appContainer
import com.dtyan.fitdiary.data.db.Workout
import com.dtyan.fitdiary.data.db.WorkoutTemplate
import com.dtyan.fitdiary.data.repo.PlanBundleRepository
import com.dtyan.fitdiary.ui.common.ActionTile
import com.dtyan.fitdiary.ui.common.EmptyState
import com.dtyan.fitdiary.ui.common.FitButton
import com.dtyan.fitdiary.ui.common.FitButtonKind
import com.dtyan.fitdiary.ui.common.FitButtonRow
import com.dtyan.fitdiary.ui.common.FitCard
import com.dtyan.fitdiary.ui.common.FitDimens
import com.dtyan.fitdiary.ui.common.IconBadge
import com.dtyan.fitdiary.ui.common.sectionColors
import com.dtyan.fitdiary.ui.common.FitSection
import com.dtyan.fitdiary.ui.common.FitSectionScope
import com.dtyan.fitdiary.ui.common.Format
import com.dtyan.fitdiary.ui.common.HeroBarbellDecor
import com.dtyan.fitdiary.ui.common.IllustrationDumbbell
import com.dtyan.fitdiary.ui.common.SectionHeader
import com.dtyan.fitdiary.ui.common.StatChip
import com.dtyan.fitdiary.ui.common.TabHeader
import com.dtyan.fitdiary.ui.common.TileGrid
import com.dtyan.fitdiary.ui.common.WorkoutSummaryCard
import com.dtyan.fitdiary.ui.common.pressScale
import com.dtyan.fitdiary.ui.profile.LocalAppChrome
import com.dtyan.fitdiary.ui.profile.ProfileAvatar
import com.dtyan.fitdiary.ui.theme.fitAccents
import com.dtyan.fitdiary.ui.update.UpdateBanner
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.flow.flowOf

/**
 * Главная вкладки «Тренировка»: старт или продолжение тренировки, четыре одинаковые плитки
 * частых действий, короткие итоги и последние тренировки.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun HomeScreen(
    onOpenWorkout: (Long) -> Unit,
    onOpenWorkoutDetails: (Long) -> Unit,
    onOpenTemplates: (() -> Unit)? = null,
    onOpenActivity: (() -> Unit)? = null,
    onOpenStats: (() -> Unit)? = null,
    onOpenBody: (() -> Unit)? = null,
) = FitSectionScope(FitSection.WORKOUT) {
    val container = LocalContext.current.appContainer
    val chrome = LocalAppChrome.current
    val vm: HomeViewModel = viewModel {
        HomeViewModel(
            container.workoutRepository, container.statsRepository,
            PlanBundleRepository(container.database, container.templateRepository, container.activityRepository),
            container.templateRepository, container.activityRepository, container.settings,
        )
    }
    val todayPlan by vm.todayPlan.collectAsStateWithLifecycle()
    val pushUpsToday by vm.pushUpsToday.collectAsStateWithLifecycle()

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
    val lastWorkout = summaries.firstOrNull()

    fun chooseParticipants() {
        if (profiles.size < 2) { chrome.openAccount(); return }
        requiredIds = if (activeWorkout == null) emptyList() else
            listOf(activeId) + currentGroup.map { it.athleteId }.filter { it != activeId }
        chosenIds = if (requiredIds.isNotEmpty()) requiredIds.toList() else
            listOf(activeId) + profiles.map { it.id }.filter { it != activeId }
        repeatSource = null
        choosingParticipants = true
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TabHeader(title = greeting(), subtitle = Format.dateWithDayOfWeek(LocalDate.now())) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = FitDimens.ScreenPadding, end = FitDimens.ScreenPadding, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(FitDimens.ItemGap),
        ) {
            item(key = "update") { UpdateBanner(container.updater, onOpen = chrome.openUpdates) }

            item(key = "hero") {
                val current = activeWorkout
                if (current == null) {
                    StartWorkoutHeroCard(starting = starting, onStart = { if (!starting) vm.startWorkout(onOpenWorkout) })
                } else {
                    ActiveWorkoutHeroCard(workout = current, participants = currentGroup.count { it.endedAt == null },
                        onContinue = { onOpenWorkout(current.id) })
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            }

            todayPlan?.let { plan ->
                item(key = "today_plan") {
                    TodayPlanCard(plan, canStart = activeWorkout == null && !starting,
                        onStart = { template -> vm.startTemplate(template.id, onOpenWorkout) },
                        onOpenPlan = onOpenTemplates)
                }
            }

            item(key = "actions") {
                val tiles = buildList<@Composable (Modifier) -> Unit> {
                    if (onOpenTemplates != null) add { m ->
                        ActionTile("Мои программы", Icons.AutoMirrored.Rounded.ListAlt, onOpenTemplates, m,
                            subtitle = "Шаблоны и план недели")
                    }
                    if (onOpenActivity != null) add { m ->
                        ActionTile("Активность за день", Icons.AutoMirrored.Rounded.DirectionsWalk, onOpenActivity, m,
                            subtitle = pushUpsToday?.let { "Отжимания: ${it.cleanReps} из ${it.target}" } ?: "Ходьба, планка, отжимания")
                    }
                    add { m ->
                        ActionTile(
                            if (activeWorkout == null) "Тренироваться вместе" else "Добавить участников",
                            if (profiles.size < 2) Icons.Rounded.GroupAdd else Icons.Rounded.Groups,
                            ::chooseParticipants, m, enabled = !starting,
                            subtitle = if (profiles.size < 2) "Сначала добавьте участника" else "У каждого свои подходы",
                        )
                    }
                    if (activeWorkout == null && lastWorkout != null) add { m ->
                        ActionTile("Повторить прошлую", Icons.Rounded.Replay, {
                            chosenIds = listOf(activeId)
                            requiredIds = emptyList()
                            repeatSource = lastWorkout.id
                            choosingParticipants = true
                        }, m, enabled = !starting,
                            subtitle = "Упражнения от ${Format.dateShort(Format.local(lastWorkout.startedAt).toLocalDate())}")
                    }
                }
                TileGrid(tiles)
            }

            item(key = "stats") {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    maxItemsInEachRow = if (compactStats) 2 else 3,
                ) {
                    StatChip(Icons.Rounded.LocalFireDepartment, fitAccents.nutrition, "${stats.weeklyStreak}",
                        Format.plural(stats.weeklyStreak, "неделя подряд", "недели подряд", "недель подряд"), Modifier.weight(1f),
                        onClick = onOpenStats, onClickLabel = "Открыть статистику")
                    StatChip(Icons.Rounded.FitnessCenter, fitAccents.workout, "${stats.workoutsLast7Days}",
                        "за 7 дней", Modifier.weight(1f), onClick = onOpenStats, onClickLabel = "Открыть историю тренировок")
                    StatChip(Icons.Rounded.MonitorWeight, fitAccents.measure,
                        latestWeight?.let { Format.weight(it.weightKg) } ?: "—", "вес, кг", Modifier.weight(1f),
                        onClick = onOpenBody, onClickLabel = "Открыть вес")
                }
            }

            item(key = "header_recent") {
                SectionHeader(icon = Icons.Rounded.History, title = "Последние тренировки")
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
                    WorkoutSummaryCard(summary = summary, onClick = { onOpenWorkoutDetails(summary.id) })
                }
            }
        }
    }

    if (choosingParticipants) AlertDialog(
        onDismissRequest = { if (!starting) choosingParticipants = false },
        title = { Text(if (repeatSource != null) "Повторить упражнения" else "Кто тренируется?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(when {
                    repeatSource != null -> "Скопируется только список упражнений. Веса и подходы записываются заново."
                    requiredIds.isNotEmpty() -> "Выберите, кто присоединится. Имеющиеся участники и подходы сохранятся."
                    else -> "У каждого будут свои подходы, история и рекорды."
                }, style = MaterialTheme.typography.bodyMedium)
                profiles.forEach { athlete ->
                    val alreadyFinished = currentGroup.any { it.athleteId == athlete.id && it.endedAt != null }
                    val locked = starting || athlete.id in requiredIds
                    val checked = athlete.id in chosenIds
                    fun toggle(selected: Boolean) {
                        chosenIds = if (selected) (chosenIds + athlete.id).distinct() else chosenIds.filter { it != athlete.id }
                    }
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.small)
                            .clickable(enabled = !locked, role = Role.Checkbox) { toggle(!checked) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Checkbox(checked = checked, enabled = !locked, onCheckedChange = ::toggle)
                        ProfileAvatar(athlete.name, athlete.id)
                        Text(athlete.name + when {
                            athlete.id !in requiredIds -> ""
                            alreadyFinished -> " · завершил"
                            else -> " · уже в тренировке"
                        }, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(enabled = chosenIds.isNotEmpty() && !starting, onClick = {
            val done: (Long) -> Unit = { choosingParticipants = false; onOpenWorkout(it) }
            val source = repeatSource
            val ids = chosenIds.sortedBy { if (it == activeId) 0 else 1 }
            if (source == null) vm.startTogether(ids, done)
            else vm.repeatWorkout(source, ids, done)
        }) { Text(if (starting) "Готовим…" else "Начать") } },
        dismissButton = { TextButton(enabled = !starting, onClick = { choosingParticipants = false }) { Text("Отмена") } },
    )
}

private val WEEKDAYS = listOf("Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота", "Воскресенье")

/**
 * «Сегодня по плану»: день недели из загруженного плана — утро, силовая и дом. Если силовая названа
 * буквой программы («C: …»), её можно начать отсюда; день можно перенести в «Моих программах».
 */
@Composable
private fun TodayPlanCard(plan: TodayPlan, canStart: Boolean, onStart: (WorkoutTemplate) -> Unit, onOpenPlan: (() -> Unit)?) {
    FitCard(onClick = onOpenPlan, onClickLabel = "Открыть план недели", spacing = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(Icons.Rounded.CalendarMonth, size = 36.dp)
            Column(Modifier.weight(1f)) {
                Text("Сегодня по плану · ${WEEKDAYS[plan.dayOfWeek - 1]}", style = MaterialTheme.typography.titleSmall)
                Text(plan.planName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        PlanLine(Icons.Rounded.WbTwilight, "Утро", plan.morning)
        PlanLine(Icons.Rounded.FitnessCenter, "Силовая", plan.strength)
        PlanLine(Icons.Rounded.Home, "Дома", plan.home)
        val template = plan.template
        FitButtonRow(
            minWidthForRow = 320.dp,
            first = { m ->
                if (template != null) FitButton("Начать: ${template.name}", { onStart(template) }, m, icon = Icons.Rounded.PlayArrow,
                    enabled = canStart)
                else FitButton("Весь план", { onOpenPlan?.invoke() }, m, kind = FitButtonKind.Tonal, compact = true)
            },
            second = { m ->
                FitButton("Перенести день", { onOpenPlan?.invoke() }, m, kind = FitButtonKind.Plain, compact = true,
                    icon = Icons.Rounded.SwapHoriz)
            },
        )
    }
}

@Composable
private fun PlanLine(icon: ImageVector, label: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = sectionColors().accent, modifier = Modifier.padding(top = 2.dp).size(18.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Приветствие по времени суток. */
private fun greeting(): String {
    val hour = LocalTime.now().hour
    return when {
        hour in 5..11 -> "Доброе утро"
        hour in 12..17 -> "Добрый день"
        else -> "Добрый вечер"
    }
}

/** Hero-карточка старта: градиент раздела + рисованный декор штанги, целиком кликабельна. */
@Composable
private fun StartWorkoutHeroCard(starting: Boolean, onStart: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale()
            .clip(MaterialTheme.shapes.large)
            .background(Brush.linearGradient(fitAccents.workoutGradient))
            .clickable(enabled = !starting, role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onStart()
            },
    ) {
        HeroBarbellDecor(Modifier.matchParentSize())
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // На градиенте — единственное место, где белый задаём явно.
            Text(if (starting) "Готовим тренировку…" else "Начать тренировку",
                style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Text("Записывайте подходы и следите за прогрессом",
                style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
        }
    }
}

/** Продолжение тренировки без постоянно обновляющегося отсчёта. */
@Composable
private fun ActiveWorkoutHeroCard(workout: Workout, participants: Int, onContinue: () -> Unit) {
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
            .clickable(role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onContinue()
            },
    ) {
        HeroBarbellDecor(Modifier.matchParentSize())
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color.White.copy(alpha = pulse)))
                    Text("Продолжить тренировку", style = MaterialTheme.typography.titleMedium, color = Color.White)
                }
                Text(
                    text = "Начало: ${Format.dateShort(Format.local(workout.startedAt).toLocalDate())}, ${Format.time(workout.startedAt)}" +
                        if (participants > 1) " · участников: $participants" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                )
            }
            Box(
                modifier = Modifier.size(48.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "Продолжить", tint = Color.White)
            }
        }
    }
}
