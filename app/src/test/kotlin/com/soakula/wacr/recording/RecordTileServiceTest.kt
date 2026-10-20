package com.soakula.wacr.recording

import android.Manifest
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.soakula.wacr.MainActivity
import com.soakula.wacr.R
import com.soakula.wacr.WacrApp
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.annotation.experimental.LazyApplication
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowService
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 34], application = WacrApp::class, shadows = [RecordTileServiceTest.TileShadow::class])
@LazyApplication(LazyApplication.LazyLoad.ON)
class RecordTileServiceTest {
    private val app get() = checkNotNull(RuntimeEnvironment.getApplication() as? WacrApp)
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Before fun prepare() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler ?: Thread.UncaughtExceptionHandler { _, error -> throw AssertionError(error) })
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    }

    @After fun restore() { Thread.setDefaultUncaughtExceptionHandler(previousHandler) }

    @Test fun tileOpensPrivateVisibleEntryWithoutStartingMicrophoneFromService() {
        val service = Robolectric.buildService(RecordTileService::class.java).create()
        try {
            service.get().onStartListening()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(Tile.STATE_INACTIVE, service.get().qsTile.state)
            assertEquals(app.getString(R.string.tile_record), service.get().qsTile.label)
            val tileShadow = Shadow.extract<TileShadow>(service.get())
            tileShadow.locked = true
            service.get().onClick()
            val entry = checkNotNull(shadowOf(app).nextStartedActivity)
            assertEquals(ComponentName(app, RecordingEntry::class.java), entry.component)
            assertEquals(RecordingEntry.ACTION_START, entry.action)
            assertNull(shadowOf(app).nextStartedService)
            assertEquals(RecorderState.Idle, app.recorder.state.value)
            assertFalse(service.get().isLocked)
        } finally {
            service.get().onStopListening()
            service.destroy()
        }
    }

    @Test fun stateSubscriptionStopsCurrentRecordingAndDoesNotLaunchWhileSaving() {
        val service = Robolectric.buildService(RecordTileService::class.java).create()
        try {
            val id = checkNotNull(app.recorder.start())
            val request = checkNotNull(app.recorder.request(id))
            service.get().onStartListening()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(Tile.STATE_ACTIVE, service.get().qsTile.state)
            service.get().onClick()
            assertTrue(request.stopRequested.get())
            assertNull(shadowOf(app).nextStartedActivity)
            app.recorder.saving(request)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(Tile.STATE_UNAVAILABLE, service.get().qsTile.state)
            service.get().onClick()
            assertNull(shadowOf(app).nextStartedActivity)
            service.get().onStopListening()
            app.recorder.finished(request, null)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(Tile.STATE_UNAVAILABLE, service.get().qsTile.state)
            service.get().onStartListening()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(Tile.STATE_INACTIVE, service.get().qsTile.state)
        } finally {
            service.get().onStopListening()
            service.destroy()
        }
    }

    @Test fun privateEntryStartsOnceOnlyAfterResumeAndExportedActivityCannotForgeIt() {
        val entry = RecordingEntry.intent(app)
        val activity = Robolectric.buildActivity(MainActivity::class.java, entry).create().start()
        try {
            assertEquals(RecorderState.Idle, app.recorder.state.value)
            activity.resume().visible()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(app.recorder.state.value is RecorderState.Preparing)
            assertNotNull(shadowOf(app).nextStartedService)
            val consumed = activity.get().intent
            assertNull(consumed.action)
            ReflectionHelpers.callInstanceMethod<Unit>(activity.get(), "consumeRecordingEntry",
                ReflectionHelpers.ClassParameter.from(Intent::class.java, consumed))
            assertNull(shadowOf(app).nextStartedService)
        } finally {
            activity.pause().stop().destroy()
        }
        val forged = Intent(app, MainActivity::class.java).setAction(RecordingEntry.ACTION_START)
        assertFalse(RecordingEntry.consume(app, forged))
        assertTrue(RecordingEntry.pendingIntent(app).isImmutable)
        val pm = app.packageManager
        @Suppress("DEPRECATION")
        val alias = pm.getActivityInfo(ComponentName(app, RecordingEntry::class.java), 0)
        assertFalse(alias.exported)
        assertEquals(MainActivity::class.java.name, alias.targetActivity)
        @Suppress("DEPRECATION")
        val tile = pm.getServiceInfo(ComponentName(app, RecordTileService::class.java), 0)
        assertEquals(Manifest.permission.BIND_QUICK_SETTINGS_TILE, tile.permission)
    }

    @Test fun forgedExportedEntryNeverStartsOrRequestsRecording() {
        val activity = Robolectric.buildActivity(MainActivity::class.java,
            Intent(app, MainActivity::class.java).setAction(RecordingEntry.ACTION_START)).setup()
        try {
            assertEquals(RecorderState.Idle, app.recorder.state.value)
            assertNull(shadowOf(app).nextStartedService)
            assertFalse(ReflectionHelpers.getField(activity.get(), "recordAfterPermission"))
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test fun trustedEntryStillRequiresPermissionsAndCannotRearmAfterBackgrounding() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val activity = Robolectric.buildActivity(MainActivity::class.java, RecordingEntry.intent(app)).setup()
        try {
            assertEquals(RecorderState.Idle, app.recorder.state.value)
            assertNull(shadowOf(app).nextStartedService)
            assertArrayEquals(arrayOf(Manifest.permission.RECORD_AUDIO),
                shadowOf(activity.get()).lastRequestedPermission.requestedPermissions)
            activity.pause().stop()
            shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
            activity.restart().start().resume()
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(shadowOf(app).nextStartedService)
            assertEquals(RecorderState.Idle, app.recorder.state.value)
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test fun repeatedTrustedIntentCannotStartADuplicateAndUnresumedEntryIsCancelledOnStop() {
        val activity = Robolectric.buildActivity(MainActivity::class.java, RecordingEntry.intent(app)).create().start()
        try {
            activity.stop().restart().start().resume()
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(shadowOf(app).nextStartedService)
            assertEquals(RecorderState.Idle, app.recorder.state.value)
            activity.newIntent(RecordingEntry.intent(app))
            shadowOf(Looper.getMainLooper()).idle()
            assertNotNull(shadowOf(app).nextStartedService)
            val first = app.recorder.state.value
            activity.newIntent(RecordingEntry.intent(app))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(first, app.recorder.state.value)
            assertNull(shadowOf(app).nextStartedService)
        } finally {
            activity.pause().stop().destroy()
        }
    }

    // Robolectric's TileService shadow lacks the API 34 overload and a ShadowService parent.
    @Implements(TileService::class)
    class TileShadow : ShadowService() {
        @RealObject private lateinit var service: TileService
        private val tile = Shadow.newInstanceOf(Tile::class.java)
        var locked = false

        @Implementation protected fun getQsTile(): Tile = tile
        @Implementation protected fun isLocked(): Boolean = locked
        @Implementation protected fun unlockAndRun(action: Runnable) {
            locked = false
            action.run()
        }
        @Implementation(maxSdk = 33)
        protected fun startActivityAndCollapse(intent: Intent) { service.startActivity(intent) }
        @Implementation(minSdk = 34)
        protected fun startActivityAndCollapse(intent: PendingIntent) {
            assertTrue(intent.isActivity)
            assertTrue(intent.isImmutable)
            intent.send()
        }
    }
}
