package com.soakula.wacr.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.soakula.wacr.R
import java.text.NumberFormat
import java.util.Locale

internal val PLAYBACK_SPEEDS = listOf(.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

internal fun skipPosition(position: Long, duration: Long, forward: Boolean): Long {
    val end = duration.coerceAtLeast(0)
    val current = position.coerceIn(0, end)
    return if (forward) current + minOf(10_000, end - current) else current - minOf(10_000, current)
}

@Composable
internal fun TransportControls(
    state: PlaybackUiState, enabled: Boolean, onSeek: (Long) -> Unit, onSpeed: (Float) -> Unit
) {
    var choosingSpeed by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) choosingSpeed = false }
    val formatter = NumberFormat.getNumberInstance(Locale.getDefault()).apply { maximumFractionDigits = 2 }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onSeek(skipPosition(state.positionMs, state.durationMs, false)) },
            enabled = enabled && state.durationMs > 0, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.player_skip_back))
        }
        OutlinedButton(onClick = { onSeek(skipPosition(state.positionMs, state.durationMs, true)) },
            enabled = enabled && state.durationMs > 0, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.player_skip_forward))
        }
    }
    Box {
        TextButton(onClick = { choosingSpeed = true }, enabled = enabled) {
            Text(stringResource(R.string.player_speed, formatter.format(state.speed)))
        }
        DropdownMenu(expanded = choosingSpeed && enabled, onDismissRequest = { choosingSpeed = false }) {
            PLAYBACK_SPEEDS.forEach { speed ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.player_speed_value, formatter.format(speed))) },
                    onClick = { onSpeed(speed); choosingSpeed = false },
                    modifier = Modifier.semantics { selected = state.speed == speed }
                )
            }
        }
    }
}
