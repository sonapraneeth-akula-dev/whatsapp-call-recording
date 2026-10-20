package com.soakula.wacr.ui.player

import android.text.format.DateUtils
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.soakula.wacr.R
import com.soakula.wacr.data.RecordingEntity
import com.soakula.wacr.data.RecordingStatus
import com.soakula.wacr.data.RecordingWithSession
import com.soakula.wacr.ui.library.RecordingRow

data class PlaybackUiState(
    val ready: Boolean = false,
    val playRequested: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val failed: Boolean = false,
    val speed: Float = 1f
)

internal fun RecordingEntity.hasPlayableAudio() =
    status in setOf(RecordingStatus.FINALIZED, RecordingStatus.RECOVERED) &&
        audioPath == "$id/audio.aac.enc" && (durationMs ?: 0) > 0

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    entry: RecordingWithSession,
    state: PlaybackUiState,
    blocked: Boolean,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeed: (Float) -> Unit = {},
    waveform: @Composable () -> Unit = {},
    actions: @Composable () -> Unit = {}
) {
    val playable = entry.recording.hasPlayableAudio()
    val duration = state.durationMs.coerceAtLeast(0)
    var scrub by remember(entry.recording.id) { mutableStateOf<Float?>(null) }
    val seekLabel = stringResource(R.string.player_seek)
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.player_title)) },
            navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.player_back)) } })
    }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when {
                !playable -> Text(stringResource(R.string.player_unavailable))
                blocked -> Text(stringResource(R.string.player_capture_active))
                state.failed -> Text(stringResource(R.string.player_error), color = MaterialTheme.colorScheme.error)
                !state.ready || state.buffering -> LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            waveform()
            Slider(
                value = (scrub ?: state.positionMs.toFloat()).coerceIn(0f, duration.toFloat()),
                onValueChange = { scrub = it },
                onValueChangeFinished = {
                    scrub?.let { onSeek(it.toLong().coerceIn(0, duration)) }
                    scrub = null
                },
                valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                enabled = playable && state.ready && !state.failed && !blocked && duration > 0,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = seekLabel }
            )
            Text(stringResource(R.string.player_times,
                DateUtils.formatElapsedTime(state.positionMs.coerceAtLeast(0) / 1000),
                DateUtils.formatElapsedTime(duration / 1000)))
            Button(
                onClick = onPlayPause,
                enabled = playable && state.ready && !state.failed && !blocked,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(if (state.playRequested) R.string.player_pause else R.string.player_play)) }
            TransportControls(state, playable && state.ready && !state.failed && !blocked, onSeek, onSpeed)
            RecordingRow(entry)
            actions()
        }
    }
}
