package com.soakula.wacr.playback

import android.app.PendingIntent
import android.content.Intent
import android.database.SQLException
import android.os.Process
import androidx.media3.common.MediaItem
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.soakula.wacr.MainActivity
import com.soakula.wacr.R
import com.soakula.wacr.WacrApp
import com.soakula.wacr.data.RecordingStatus
import com.soakula.wacr.recording.RecorderState
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.catch

@UnstableApi
class PlaybackService : MediaSessionService() {
    private val app get() = application as WacrApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var engine: ExoPlayer? = null
    private var session: MediaSession? = null
    private val selected = MutableStateFlow<UUID?>(null)
    private var selectionRevision = 0L

    override fun onCreate() {
        super.onCreate()
        if (!app.appLock.canAccess() || app.playbackReleaseFailed) {
            app.log.log("playback", "locked_service_rejected")
            stopSelf()
            return
        }
        val player = createRecordingPlayer(this, { app.crypto }, app.log)
        engine = player
        setMediaNotificationProvider(PrivatePlaybackNotification(this))
        setListener(object : Listener {
            override fun onForegroundServiceStartNotAllowedException() {
                app.log.log("playback", "foreground_denied")
                closePlayback()
            }
        })
        val guarded = object : ForwardingPlayer(player) {
            override fun getAvailableCommands(): Player.Commands =
                if (app.appLock.canAccess()) super.getAvailableCommands() else Player.Commands.EMPTY

            override fun isCommandAvailable(command: Int): Boolean =
                app.appLock.canAccess() && super.isCommandAvailable(command)

            override fun play() = setPlayWhenReady(true)
            override fun setPlayWhenReady(playWhenReady: Boolean) {
                if (playWhenReady && (captureBusy() || app.playbackReleaseFailed || !app.appLock.canAccess())) {
                    app.log.log("playback", "play_rejected")
                    session?.setPlaybackException(PlaybackException(
                        "Private playback unavailable", null, PlaybackException.ERROR_CODE_UNSPECIFIED))
                } else player.playWhenReady = playWhenReady
            }
        }
        session = MediaSession.Builder(this, guarded).setCallback(Callbacks())
            .setSessionActivity(PendingIntent.getActivity(this, 43,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE))
            .setMediaButtonPreferences(ImmutableList.of(
                CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10).setPlayerCommand(Player.COMMAND_SEEK_BACK)
                    .setDisplayName(getString(R.string.player_skip_back)).build(),
                CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_10).setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                    .setDisplayName(getString(R.string.player_skip_forward)).build()
            )).build()
        app.playbackService = this
        scope.launch {
            selected.collectLatest { id ->
                if (id != null) {
                    try {
                        val database = withContext(Dispatchers.IO) { app.database() }
                        database.recordings().observePlayable(id).catch { error ->
                            when (error) {
                                is SQLException, is IOException, is IllegalStateException -> {
                                    app.log.log("playback", "eligibility_failed", mapOf("error_type" to error.javaClass.simpleName))
                                    closePlayback()
                                }
                                else -> throw error
                            }
                        }.collect { valid ->
                            if (!valid) closePlayback()
                        }
                    } catch (error: SQLException) {
                        app.log.log("playback", "database_unavailable")
                        closePlayback()
                    } catch (error: GeneralSecurityException) {
                        app.log.log("playback", "key_unavailable")
                        closePlayback()
                    }
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        if (!allowed(controllerInfo) || captureBusy() || app.playbackReleaseFailed || !app.appLock.canAccess()) {
            app.log.log("playback", "connection_rejected")
            return null
        }
        return session
    }

    internal fun closePlayback() {
        scope.cancel()
        session?.release()
        session = null
        val player = engine
        engine = null
        try {
            if (player != null) releasePlayback(player)
        } catch (error: PlaybackReleaseException) {
            app.playbackReleaseFailed = true
            app.log.log("playback", "release_failed_restart_required")
            throw error
        } finally {
            if (app.playbackService === this) app.playbackService = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    internal fun closeRecording(id: UUID) { if (selected.value == id) closePlayback() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (selected.value == null || captureBusy() || app.playbackReleaseFailed || !app.appLock.canAccess()) {
            app.log.log("playback", "stale_command_rejected")
            closePlayback()
            return START_NOT_STICKY
        }
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) { closePlayback() }

    override fun onDestroy() {
        closePlayback()
        super.onDestroy()
    }

    private fun captureBusy(): Boolean = when (app.recorder.state.value) {
        is RecorderState.Preparing, is RecorderState.Recording, is RecorderState.Saving -> true
        else -> false
    }

    private fun allowed(controller: MediaSession.ControllerInfo) =
        playbackControllerAllowed(controller.uid, controller.packageName, controller.isTrusted, Process.myUid())

    private inner class Callbacks : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if (!allowed(controller) || captureBusy() || app.playbackReleaseFailed || !app.appLock.canAccess()) {
                app.log.log("playback", "controller_rejected")
                return MediaSession.ConnectionResult.reject()
            }
            val commands = if (controller.uid == Process.myUid()) MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
            else Player.Commands.Builder().addAll(
                Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA
            ).build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder().setAvailablePlayerCommands(commands).build()
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            if (controller.uid != Process.myUid() || captureBusy() || !app.appLock.canAccess()) {
                app.log.log("playback", "selection_rejected")
                return Futures.immediateFailedFuture(IllegalStateException("Playback selection unavailable"))
            }
            engine?.pause()
            engine?.clearMediaItems()
            selected.value = null
            val revision = ++selectionRevision
            session?.setPlaybackException(controller, null)
            val id = try {
                require(mediaItems.size == 1) { "One private recording required" }
                playbackId(mediaItems.single().mediaId)
            }
            catch (_: IllegalArgumentException) {
                app.log.log("playback", "identity_rejected")
                session?.setPlaybackException(controller, PlaybackException(
                    "Invalid private recording", null, PlaybackException.ERROR_CODE_UNSPECIFIED))
                return Futures.immediateFailedFuture(IllegalArgumentException("Invalid private playback identity"))
            }
            val future = SettableFuture.create<MutableList<MediaItem>>()
            fun failed(error: Throwable) {
                app.log.log("playback", "selection_failed", mapOf("error_type" to error.javaClass.simpleName))
                if (selectionRevision == revision) session?.setPlaybackException(controller,
                    PlaybackException("Private recording unavailable", null, PlaybackException.ERROR_CODE_UNSPECIFIED))
                future.setException(error)
            }
            val job = scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        app.ensureRecovered()
                        val dao = app.database().recordings()
                        val row = checkNotNull(dao.recording(id)) { "Recording unavailable" }
                        check(row.status in setOf(RecordingStatus.FINALIZED, RecordingStatus.RECOVERED) &&
                            row.audioPath == "$id/audio.aac.enc" && (row.durationMs ?: 0) > 0 && dao.deletion(id) == null) {
                            "Recording unavailable"
                        }
                    }
                    ensureActive()
                    check(!captureBusy() && app.appLock.canAccess() && session === mediaSession && selectionRevision == revision) {
                        "Playback authorization expired"
                    }
                    selected.value = id
                    mediaSession.setSessionActivity(PlaybackEntry.pendingIntent(this@PlaybackService, id))
                    future.set(mutableListOf(MediaItem.Builder().setMediaId(id.toString())
                        .setUri(DecryptingDataSource.uri(id))
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(getString(R.string.playback_private_title)).build())
                        .build()))
                } catch (error: CancellationException) { future.cancel(false); throw error
                } catch (error: IOException) { failed(error)
                } catch (error: SQLException) { failed(error)
                } catch (error: GeneralSecurityException) { failed(error)
                } catch (error: ProviderException) { failed(error)
                } catch (error: SecurityException) { failed(error)
                } catch (error: IllegalArgumentException) { failed(error)
                } catch (error: IllegalStateException) { failed(error)
                } catch (error: UnsatisfiedLinkError) { failed(error) }
            }

            future.addListener({ if (future.isCancelled) job.cancel() }, mainExecutor)
            return future
        }
    }
}
