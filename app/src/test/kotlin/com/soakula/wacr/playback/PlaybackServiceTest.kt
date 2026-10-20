package com.soakula.wacr.playback

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.room.Room
import com.soakula.wacr.MainActivity
import com.soakula.wacr.R
import com.soakula.wacr.WacrApp
import com.soakula.wacr.data.*
import com.soakula.wacr.recording.RecoveryReport
import com.soakula.wacr.recording.RecorderState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.experimental.LazyApplication
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.lang.reflect.Proxy

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = WacrApp::class)
@LazyApplication(LazyApplication.LazyLoad.ON)
class PlaybackServiceTest {
    private lateinit var host: ServiceController<PlaybackService>
    private lateinit var service: PlaybackService
    private lateinit var session: MediaSession
    private lateinit var engine: ExoPlayer
    private lateinit var controller: MediaController
    private lateinit var database: AppDatabase
    private val app: WacrApp get() = RuntimeEnvironment.getApplication() as WacrApp
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Before fun prepare() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler ?: Thread.UncaughtExceptionHandler { _, error ->
            throw AssertionError("Unexpected uncaught test exception", error)
        })
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        database = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        ReflectionHelpers.setField(app, "openDatabase", database)
        ReflectionHelpers.setField(app, "recovered", RecoveryReport(0, 0, 0))
        host = Robolectric.buildService(PlaybackService::class.java).create()
        service = host.get()
        session = ReflectionHelpers.getField(service, "session")
        engine = ReflectionHelpers.getField(service, "engine")
        service.addSession(session)
        val future = MediaController.Builder(app, session.token).buildAsync()
        await { future.isDone }
        controller = future.get()
    }

    @After fun close() {
        if (::controller.isInitialized) controller.release()
        if (::host.isInitialized) host.destroy()
        if (::database.isInitialized) database.close()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    private fun select(): UUID {
        val id = UUID.randomUUID()
        runBlocking {
            database.recordings().insertRecording(RecordingEntity(id, 1000, 1, "MIC", title = "Synthetic private contact",
                status = RecordingStatus.FINALIZED, durationMs = 60_000, audioPath = "$id/audio.aac.enc"))
        }
        controller.setMediaItem(MediaItem.Builder().setMediaId(id.toString()).setUri("https://invalid.example/private")
            .setMediaMetadata(MediaMetadata.Builder().setTitle("Synthetic private contact").build()).build())
        await { engine.currentMediaItem?.mediaId == id.toString() }
        return id
    }

    @Test fun validatesAndRebuildsSelectionThenNativeSessionControlsBackgroundPlayback() {
        val id = select()
        assertEquals(DecryptingDataSource.uri(id), engine.currentMediaItem?.localConfiguration?.uri)
        assertEquals(app.getString(R.string.playback_private_title), engine.mediaMetadata.title)
        prepareSyntheticTimeline()
        controller.seekTo(30_000)
        await { engine.currentPosition == 30_000L }
        controller.seekBack()
        await { engine.currentPosition == 20_000L }
        controller.seekForward()
        await { engine.currentPosition == 30_000L }
        controller.play()
        await { engine.playWhenReady }
        controller.release()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(engine.isReleased)
        assertTrue(engine.playWhenReady)
        app.stopPlaybackForCapture()
        assertTrue(engine.isReleased)
        assertNull(app.playbackService)
    }

    @Test fun pendingDeletionStopsAndReleasesSelectedAudio() {
        val id = select()
        runBlocking {
            database.recordings().insertDeletion(PendingRecordingDeletion(
                id, UUID.randomUUID(), 6000, 1))
        }
        await { engine.isReleased }
        assertNull(app.playbackService)
        assertNotNull(runBlocking { database.recordings().recording(id) })
    }

    @Test fun invalidSelectionCannotResumeThePreviousRecordingAndReportsNativePlayerError() {
        select()
        controller.play()
        await { engine.playWhenReady }
        controller.setMediaItem(MediaItem.Builder().setMediaId("1-1-1-1-1").setUri("file:///private").build())
        await { engine.currentMediaItem == null && controller.playerError != null }
        assertFalse(engine.playWhenReady)
    }

    @Test fun preparingCaptureRejectsSessionPlayBeforeRecorderServiceOpensMicrophone() {
        select()
        val state = ReflectionHelpers.getField<MutableStateFlow<RecorderState>>(app.recorder, "mutableState")
        state.value = RecorderState.Preparing(UUID.randomUUID())
        controller.play()
        await { controller.playerError != null }
        assertFalse(engine.playWhenReady)
        app.stopPlaybackForCapture()
        assertTrue(engine.isReleased)
    }

    @Test fun notificationRedactsPublicMetadataAndRoutesThroughAnImmutablePrivateEntry() {
        val id = select()
        prepareSyntheticTimeline()
        service.onUpdateNotification(session, false)
        val manager = app.getSystemService(NotificationManager::class.java)
        await { shadowOf(manager).getNotification(2005) != null }
        val notification = checkNotNull(shadowOf(manager).getNotification(2005))
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertTrue(notification.flags and Notification.FLAG_LOCAL_ONLY != 0)
        val public = checkNotNull(notification.publicVersion)
        assertEquals(app.getString(R.string.playback_private_title), public.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertTrue(public.actions.isNullOrEmpty())
        assertFalse(notification.extras.toString().contains("Synthetic private contact"))
        engine.seekTo(30_000)
        val back = notification.actions.single { it.title == app.getString(R.string.player_skip_back) }
        assertTrue(back.actionIntent.isImmutable)
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(shadowOf(back.actionIntent).savedIntent, 0, 1))
        await { engine.currentPosition == 20_000L }
        val forward = notification.actions.single { it.title == app.getString(R.string.player_skip_forward) }
        service.onStartCommand(shadowOf(forward.actionIntent).savedIntent, 0, 2)
        await { engine.currentPosition == 30_000L }
        val play = notification.actions.single { it.title == app.getString(R.string.player_play) }
        service.onStartCommand(shadowOf(play.actionIntent).savedIntent, 0, 3)
        await { engine.playWhenReady }
        await { shadowOf(manager).getNotification(2005)?.actions?.any { it.title == app.getString(R.string.player_pause) } == true }
        val pause = shadowOf(manager).getNotification(2005).actions.single { it.title == app.getString(R.string.player_pause) }
        service.onStartCommand(shadowOf(pause.actionIntent).savedIntent, 0, 4)
        await { !engine.playWhenReady }
        val pending = checkNotNull(notification.contentIntent)
        assertTrue(pending.isImmutable)
        val intent = shadowOf(pending).savedIntent
        assertEquals(ComponentName(app, PlaybackEntry::class.java), intent.component)
        assertEquals(id, PlaybackEntry.consume(app, Intent(intent)))
        assertFalse(app.packageManager.getActivityInfo(intent.component!!, 0).exported)
    }

    @Test fun playbackEntryRejectsExportedActivityAndNoncanonicalOrReplayedRequests() {
        val id = UUID.randomUUID()
        val intent = PlaybackEntry.intent(app, id)
        assertEquals(id, PlaybackEntry.consume(app, intent))
        assertNull(PlaybackEntry.consume(app, intent))
        assertNull(PlaybackEntry.consume(app, PlaybackEntry.intent(app, id).setComponent(ComponentName(app, MainActivity::class.java))))
        assertNull(PlaybackEntry.consume(app, PlaybackEntry.intent(app, id).setData(android.net.Uri.parse("wacr://playback/$id?x=1"))))
    }

    @Test fun releaseTimeoutCannotBeMistakenForSuccessfulMicrophoneHandoff() {
        var listener: Player.Listener? = null
        val timedOut = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, arguments ->
            when (method.name) {
                "addListener" -> { listener = arguments!!.single() as Player.Listener; null }
                "release" -> {
                    listener!!.onPlayerError(PlaybackException("Synthetic timeout", null, PlaybackException.ERROR_CODE_TIMEOUT))
                    null
                }
                else -> error("Unexpected release operation: ${method.name}")
            }
        } as ExoPlayer
        ReflectionHelpers.setField(service, "engine", timedOut)
        try {
            assertThrows(PlaybackReleaseException::class.java) { app.stopPlaybackForCapture() }
            assertTrue(app.playbackReleaseFailed)
            assertNull(app.playbackService)
            assertThrows(PlaybackReleaseException::class.java) { app.stopPlaybackForCapture() }
            assertThrows(PlaybackReleaseException::class.java) { app.stopPlaybackForDeletion(UUID.randomUUID()) }
        } finally { engine.release() }
    }

    @Test fun staleNotificationCannotStartAnEmptyServiceOrResurrectDeletedAudio() {
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(Intent(Intent.ACTION_MEDIA_BUTTON), 0, 1))
        assertTrue(engine.isReleased)
        assertNull(app.playbackService)
        assertFalse(ReflectionHelpers.getField<Lazy<*>>(app, "keys\$delegate").isInitialized())
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!predicate() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(10)
        }
        assertTrue("Native media session did not reach expected state", predicate())
    }

    private fun prepareSyntheticTimeline() {
        // Native transport/session coverage, not a claim of authenticated AAC decoding on the host.
        engine.setMediaSource(SilenceMediaSource.Factory().setDurationUs(60_000_000).createMediaSource())
        engine.prepare()
        await { controller.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) }
    }
}
