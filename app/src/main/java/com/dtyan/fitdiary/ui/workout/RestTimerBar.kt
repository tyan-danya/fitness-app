package com.dtyan.fitdiary.ui.workout

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dtyan.fitdiary.data.RestTimerController
import com.dtyan.fitdiary.ui.common.Format
import com.dtyan.fitdiary.ui.theme.fitAccents

/**
 * Плавающая карточка таймера отдыха внизу экрана. Если таймер не идёт —
 * не занимает места, поэтому её можно безусловно ставить в bottomBar.
 * Появляется и уезжает с анимацией (slide + fade).
 */
@Composable
fun RestTimerBar(restTimer: RestTimerController, modifier: Modifier = Modifier) {
    val state by restTimer.state.collectAsStateWithLifecycle()

    // Держим последнее ненулевое состояние, чтобы карточка не «пустела» на exit-анимации.
    var lastState by remember { mutableStateOf<RestTimerController.State?>(null) }
    LaunchedEffect(state) {
        if (state != null) lastState = state
    }

    AnimatedVisibility(
        visible = state != null,
        modifier = modifier,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
    ) {
        val current = state ?: lastState ?: return@AnimatedVisibility

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                // Воздух по краям и снизу — карточка «парит», а не прилипает к низу.
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 12.dp),
            shape = MaterialTheme.shapes.large,
            color = fitAccents.workoutContainer,
            contentColor = fitAccents.onWorkoutContainer,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Timer,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Отдых",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = Format.durationClock(current.remainingSeconds * 1000L),
                        style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"),
                    )
                }

                val progress by animateFloatAsState(
                    targetValue = current.remainingSeconds.toFloat() /
                        current.totalSeconds.coerceAtLeast(1),
                    animationSpec = tween(durationMillis = 500),
                    label = "restProgress",
                )
                LinearProgressIndicator(
                    progress = { progress },
                    color = fitAccents.workout,
                    trackColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalButton(
                        onClick = { restTimer.addSeconds(15) },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = fitAccents.workout,
                        ),
                    ) {
                        Text("+15 с")
                    }
                    TextButton(
                        onClick = { restTimer.stop() },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = fitAccents.onWorkoutContainer,
                        ),
                    ) {
                        Text("Пропустить")
                    }
                }
            }
        }
    }
}
