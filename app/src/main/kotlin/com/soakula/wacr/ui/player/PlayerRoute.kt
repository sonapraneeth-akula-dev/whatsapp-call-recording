package com.soakula.wacr.ui.player

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import com.soakula.wacr.data.RecordingWithSession
import com.soakula.wacr.playback.DecryptingDataSource
import com.soakula.wacr.recording.WaveformPeaks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerRoute(
    entry: RecordingWithSession,
    blocked: Boolean,
    onBack: () -> Unit,
    createPlayer: suspend () -> Player,
    reportFailure: (Throwable) -> Unit,
    loadWaveform: suspend () -> WaveformPeaks? = { null },
    reportWaveformFailure: (Throwable) -> Unit = reportFailure,
    actions: @Composable (() -> Unit) -> Unit = {}
) {
    var engine by remember { mutableStateOf<Player?>(null) }
    var state by remember { mutableStateOf(PlaybackUiState()) }
    var openFailed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    fun failed(error: Throwable) {
        reportFailure(error)
        openFailed = true
        engine?.pause()
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val playable = entry.recording.hasPlayableAudio()
    DisposableEffect(Unit) { onDispose { engine?.release() } }
    LaunchedEffect(entry.recording.id, playable, blocked, attempt) {
        if (!playable || blocked) {
            engine?.pause()
            return@LaunchedEffect
        }
        openFailed = false
        try {
            val created = engine?.takeIf { it.playerError == null } ?: createPlayer().also { engine?.release() }
            engine = created
            if (created.currentMediaItem?.mediaId != entry.recording.id.toString() || created.playerError != null) {
                created.setMediaItem(MediaItem.Builder().setMediaId(entry.recording.id.toString())
                    .setUri(DecryptingDataSource.uri(entry.recording.id)).build())
                created.prepare()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            failed(error)
        } catch (error: GeneralSecurityException) {
            failed(error)
        } catch (error: ProviderException) {
            failed(error)
        } catch (error: IllegalStateException) {
            failed(error)
        } catch (error: SecurityException) {
            failed(error)
        } catch (error: UnsatisfiedLinkError) {
            failed(error)
        }
    }
    val player = engine
    DisposableEffect(player, lifecycle) {
        if (player == null) return@DisposableEffect onDispose {}
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                state = playbackState(player, entry.recording.durationMs)
            }
        }
        player.addListener(listener)
        state = playbackState(player, entry.recording.durationMs)
        onDispose {
            player.removeListener(listener)
        }
    }
    LaunchedEffect(player, blocked) { if (blocked) player?.pause() }
    LaunchedEffect(player) {
        if (player == null) return@LaunchedEffect
        while (true) {
            state = playbackState(player, entry.recording.durationMs)
            delay(250)
        }
    }
    PlayerScreen(entry, state.copy(failed = state.failed || openFailed), blocked, onBack, onPlayPause = {
        if (player != null && !blocked && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            if (player.playWhenReady && player.playbackState != Player.STATE_ENDED) player.pause() else {
                if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                player.play()
            }
        }
    }, onSeek = { position -> if (!blocked) player?.seekTo(position) },
        onSpeed = { speed ->
            require(speed in PLAYBACK_SPEEDS) { "Unsupported playback speed" }
            if (!blocked && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                player?.playbackParameters = PlaybackParameters(speed, 1f)
        },
        onRetry = {
            engine?.release()
            engine = null
            state = PlaybackUiState()
            attempt++
        },
        waveform = {
            if (playable) WaveformPanel(entry.recording.id,
                if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f,
                loadWaveform, reportWaveformFailure)
        },
        actions = { actions { player?.pause() } })
}

private fun playbackState(player: Player, metadataDuration: Long?) = PlaybackUiState(
    ready = player.playbackState != Player.STATE_IDLE && player.playerError == null,
    playRequested = player.playWhenReady && player.playbackState != Player.STATE_ENDED,
    buffering = player.playbackState == Player.STATE_BUFFERING,
    positionMs = player.currentPosition.coerceAtLeast(0),
    durationMs = player.duration.takeIf { it > 0 } ?: metadataDuration ?: 0,
    failed = player.playerError != null,
    speed = player.playbackParameters.speed
)
