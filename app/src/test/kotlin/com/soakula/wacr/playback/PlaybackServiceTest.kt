package com.soakula.wacr.playback

import android.app.Notification
import android.app.Activity
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
import androidx.media3.session.MediaSessionService
import androidx.room.Room
import com.soakula.wacr.MainActivity
import com.soakula.wacr.R
import com.soakula.wacr.WacrApp
import com.soakula.wacr.data.*
import com.soakula.wacr.recording.RecoveryReport
import com.soakula.wacr.recording.RecorderState
import com.soakula.wacr.security.AppLockState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
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
        runBlocking { app.preferences.setAppLock(false) { true } }
        await { app.appLock.state.value == AppLockState.Disabled }
        database = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        ReflectionHelpers.setField(app, "openDatabase", database)
        ReflectionHelpers.setField(app, "recovered", RecoveryReport(0, 0, 0))
        startService()
    }

    @After fun close() {
        if (::controller.isInitialized) controller.release()
        if (::host.isInitialized) host.destroy()
        if (::database.isInitialized) database.close()
        runBlocking { app.preferences.setAppLock(false) { true } }
        app.appLock.close()
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

    @Test fun appRelockReleasesTheRealEngineAndStaleMediaCommandsCannotRestartIt() {
        select()
        prepareSyntheticTimeline()
        controller.play()
        await { engine.playWhenReady }
        val connection = session.connectedControllers.first()
        val capture = RecorderState.Recording(UUID.randomUUID(), 1000, false)
        ReflectionHelpers.getField<MutableStateFlow<RecorderState>>(app.recorder, "mutableState").value = capture
        runBlocking { app.preferences.setAppLock(true) { true } }
        await { engine.isReleased && app.appLock.state.value is AppLockState.Locked }
        assertNull(app.playbackService)
        assertEquals(capture, app.recorder.state.value)
        assertNull(service.onGetSession(connection))
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(Intent(Intent.ACTION_MEDIA_BUTTON), 0, 1))
        assertTrue(engine.isReleased)
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

    @Test fun screenOffAndBackgroundTimeoutReleaseAuthenticatedAudioWithoutStoppingCallCapture() {
        runBlocking { app.preferences.setAppLock(true) { true } }
        await { app.appLock.state.value is AppLockState.Locked }
        val owner = Robolectric.buildActivity(Activity::class.java).get()
        for (screenOff in listOf(true, false)) {
            controller.release()
            host.destroy()
            ReflectionHelpers.getField<MutableStateFlow<RecorderState>>(app.recorder, "mutableState").value = RecorderState.Idle
            app.appLock.foreground(owner)
            val locked = app.appLock.state.value as AppLockState.Locked
            assertTrue(app.appLock.authenticated(locked.revision))
            startService()
            select()
            prepareSyntheticTimeline()
            controller.play()
            await { engine.playWhenReady }
            val capture = RecorderState.Recording(UUID.randomUUID(), 1000, false)
            ReflectionHelpers.getField<MutableStateFlow<RecorderState>>(app.recorder, "mutableState").value = capture
            app.appLock.background(owner)
            if (screenOff) {
                app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
            } else {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))
            }
            await { engine.isReleased }
            assertNull(app.playbackService)
            assertFalse(app.appLock.canAccess())
            assertEquals(capture, app.recorder.state.value)
        }
    }

    @Test fun lockedServiceCannotCreateAPlayerOrAcceptANewConnection() {
        val connection = session.connectedControllers.first()
        runBlocking { app.preferences.setAppLock(true) { true } }
        await { app.appLock.state.value is AppLockState.Locked }
        controller.release()
        host.destroy()
        host = Robolectric.buildService(PlaybackService::class.java).create()
        service = host.get()
        assertNull(ReflectionHelpers.getField<ExoPlayer?>(service, "engine"))
        assertNull(service.onGetSession(connection))
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

    @Test fun productionConnectorBindsPrivateServiceAndSurfacesSessionDisconnection() {
        val id = select()
        configureServiceBinding()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val connection = scope.async { createPlaybackController(app) }
            await { connection.isCompleted }
            val player = runBlocking { connection.await() }
            assertEquals(id.toString(), player.currentMediaItem?.mediaId)
            assertNull(player.playerError)
            app.stopPlaybackForCapture()
            await { player.playerError != null }
            player.release()
            assertTrue(engine.isReleased)
        } finally { scope.cancel() }
    }

    @Test fun cancellingProductionConnectionReleasesItsBindingWithoutClosingSharedAudio() {
        configureServiceBinding()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val connection = scope.async { createPlaybackController(app) }
            connection.cancel()
            await { shadowOf(app).unboundServiceConnections.isNotEmpty() }
            assertTrue(connection.isCancelled)
            assertFalse(engine.isReleased)
        } finally { scope.cancel() }
    }

    @Test fun missingActiveUnsafeAndDeletingMetadataCannotReplaceAuthorizedAudio() {
        val invalid = listOf(
            null,
            RecordingEntity(UUID.randomUUID(), 1, 1, "MIC"),
            RecordingEntity(UUID.randomUUID(), 1, 1, "MIC", status = RecordingStatus.FINALIZED,
                durationMs = 60_000, audioPath = "../audio.aac.enc"),
            RecordingEntity(UUID.randomUUID(), 1, 1, "MIC", status = RecordingStatus.FINALIZED, durationMs = 60_000)
                .let { it.copy(audioPath = "${it.id}/audio.aac.enc") }
        )
        for ((index, row) in invalid.withIndex()) {
            if (controller.playerError != null) {
                controller.release()
                controller = connect()
            }
            select()
            await { controller.playerError == null }
            val id = row?.id ?: UUID.randomUUID()
            runBlocking {
                if (row != null) database.recordings().insertRecording(row)
                if (index == 3) database.recordings().insertDeletion(PendingRecordingDeletion(id, UUID.randomUUID(), 6000, 1))
            }
            controller.setMediaItem(MediaItem.Builder().setMediaId(id.toString()).build())
            await { controller.playerError != null && engine.currentMediaItem == null }
            assertFalse(engine.playWhenReady)
        }
    }

    private fun startService() {
        host = Robolectric.buildService(PlaybackService::class.java).create()
        service = host.get()
        session = ReflectionHelpers.getField(service, "session")
        engine = ReflectionHelpers.getField(service, "engine")
        service.addSession(session)
        controller = connect()
    }

    private fun configureServiceBinding() {
        shadowOf(app).setUnbindServiceCallsOnServiceDisconnected(false)
        shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, PlaybackService::class.java),
            service.onBind(Intent(MediaSessionService.SERVICE_INTERFACE)))
    }

    private fun connect(): MediaController {
        val future = MediaController.Builder(app, session.token).buildAsync()
        await { future.isDone }
        return future.get()
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
