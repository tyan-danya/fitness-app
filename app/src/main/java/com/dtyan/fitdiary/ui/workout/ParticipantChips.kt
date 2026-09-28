package com.dtyan.fitdiary.ui.workout

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dtyan.fitdiary.data.db.Workout

/** The current athlete remains visible beside the recording form, with a non-colour selected state. */
@Composable
fun ParticipantChips(
    participants: List<Workout>,
    names: Map<Long, String>,
    selectedId: Long,
    enabled: Boolean = true,
    onSelect: (Long) -> Unit,
) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        participants.forEach { workout ->
            FilterChip(
                selected = workout.id == selectedId,
                enabled = enabled && workout.endedAt == null,
                onClick = { onSelect(workout.id) },
                label = { Text((names[workout.athleteId] ?: "Участник") + if (workout.endedAt != null) " · завершил" else "") },
                leadingIcon = if (workout.id == selectedId) ({ Icon(Icons.Filled.Check, contentDescription = null) }) else null,
            )
        }
    }
}
