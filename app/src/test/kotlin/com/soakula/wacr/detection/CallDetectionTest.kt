package com.soakula.wacr.detection

import android.Manifest
import android.app.Notification
import android.content.ComponentName
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.room.Room
import com.soakula.wacr.WacrApp
import com.soakula.wacr.data.AppDatabase
import com.soakula.wacr.data.CallType
import com.soakula.wacr.data.RecordingDecision
import com.soakula.wacr.recording.RecorderState
import com.soakula.wacr.health.exportDiagnostics
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.experimental.LazyApplication
import org.robolectric.util.ReflectionHelpers
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = WacrApp::class)
@LazyApplication(LazyApplication.LazyLoad.ON)
class CallDetectionTest {
    private val app: WacrApp get() = checkNotNull(RuntimeEnvironment.getApplication() as? WacrApp)
    private lateinit var database: AppDatabase
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Before fun prepare() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler ?: Thread.UncaughtExceptionHandler { _, error ->
            throw AssertionError("Unexpected uncaught test exception", error)
        })
        database = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        ReflectionHelpers.setField(app, "openDatabase", database)
        checkNotNull(app.getSystemService(AudioManager::class.java)).mode = AudioManager.MODE_IN_COMMUNICATION
    }

    @After fun close() = runBlocking {
        try {
            app.detection.shutdown()
            database.close()
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }

    @Suppress("DEPRECATION")
    private fun notification(malformed: Boolean = false) = StatusBarNotification(
        "com.whatsapp", "com.whatsapp", 1, null, 1000, 0, 0,
        Notification().apply {
            category = Notification.CATEGORY_CALL
            flags = Notification.FLAG_ONGOING_EVENT
            `when` = 1000
            extras = Bundle().apply {
                putString(Notification.EXTRA_TEMPLATE, Notification.CallStyle::class.java.name)
                putInt(Notification.EXTRA_CALL_TYPE, 2)
                if (malformed) putString(Notification.EXTRA_SHOW_CHRONOMETER, "true")
                else putBoolean(Notification.EXTRA_SHOW_CHRONOMETER, true)
                putString(Notification.EXTRA_TITLE, "Synthetic caller")
            }
        }, Process.myUserHandle(), 1000
    )

    private suspend fun await(predicate: (CallDetectionState) -> Boolean) =
        withTimeout(10_000) { app.detection.state.first(predicate) }

    @Test fun bothBoundRolesAndFreshAudioSnapshotAreRequiredBeforeOneEncryptedSessionEffect() = runBlocking {
        val accessibility = Any()
        val listener = Any()
        app.detection.listenerConnected(listener) { arrayOf(notification()) }
        assertFalse(app.detection.state.value.ready)
        assertNull(app.detection.state.value.sessionId)
        app.detection.accessibilityConnected(accessibility)
        val connected = await { it.ready && it.sessionId != null }
        val id = checkNotNull(connected.sessionId)
        val row = checkNotNull(database.recordings().session(id))
        assertEquals(RecordingDecision.IGNORED, row.decision)
        assertEquals(CallType.UNKNOWN, row.callType)
        assertFalse(row.isGroupKnown)
        assertNull(row.matchKey)
        assertNull(row.waDisplayName)
        assertEquals("2026-10-20-a73-v1", connected.rulesVersion)
        val output = ByteArrayOutputStream()
        exportDiagnostics(app, app.log, output)
        var metadata: JSONObject? = null
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "device.json") metadata = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
            }
        }
        assertEquals(connected.rulesVersion, checkNotNull(metadata).getString("detection_rules_version"))
        assertTrue(checkNotNull(metadata).getBoolean("detection_ready"))
        assertFalse(checkNotNull(metadata).getBoolean("detection_failed"))
        assertEquals(RecorderState.Idle, app.recorder.state.value)
        assertNull(shadowOf(app).nextStartedService)
        app.detection.listenerDisconnected(listener)
        await { !it.ready && it.sessionId == null }
        assertNotNull(database.recordings().session(id)?.endedAt)
        assertFalse(app.detection.state.value.failed)
        Unit
    }

    @Test fun staleSourceCallbacksCannotRemoveNewlyBoundEvidence() = runBlocking {
        val old = Any()
        val listener = Any()
        app.detection.accessibilityConnected(old)
        app.detection.listenerConnected(listener) { emptyArray() }
        await { it.ready }
        val replacement = Any()
        app.detection.accessibilityConnected(replacement)
        app.detection.accessibilityDisconnected(old)
        app.detection.posted(listener, notification())
        val connected = await { it.ready && it.sessionId != null }
        assertNotNull(connected.call)
        assertEquals(RecorderState.Idle, app.recorder.state.value)
        Unit
    }

    @Test fun malformedNativeExtrasFailClosedAndCloseAlreadyCommittedSession() = runBlocking {
        val listener = Any()
        app.detection.accessibilityConnected(Any())
        app.detection.listenerConnected(listener) { arrayOf(notification()) }
        val id = checkNotNull(await { it.ready && it.sessionId != null }.sessionId)
        app.detection.posted(listener, notification(malformed = true))
        await { it.failed }
        app.detection.shutdown()
        assertNotNull(database.recordings().session(id)?.endedAt)
        assertFalse(app.detection.state.value.ready)
        assertEquals(RecorderState.Idle, app.recorder.state.value)
        assertNull(shadowOf(app).nextStartedService)
        Unit
    }

    @Test fun vanishedBindingSnapshotStaysUnreadyAndAFreshBindingCanRecover() = runBlocking {
        app.detection.accessibilityConnected(Any())
        app.detection.listenerConnected(Any()) { null }
        app.detection.listenerConnected(Any()) { arrayOf(notification()) }
        await { it.ready && it.sessionId != null }
        assertFalse(app.detection.state.value.failed)
        assertEquals(RecorderState.Idle, app.recorder.state.value)
        Unit
    }

    @Test fun manifestUsesSignatureProtectedRolesAndOnlyPackageSpecificVisibility() {
        val pm = app.packageManager
        val accessibility = pm.getServiceInfo(ComponentName(app, CallAccessibilityService::class.java),
            PackageManager.ComponentInfoFlags.of(0))
        val listener = pm.getServiceInfo(ComponentName(app, CallNotificationListener::class.java),
            PackageManager.ComponentInfoFlags.of(0))
        assertEquals(Manifest.permission.BIND_ACCESSIBILITY_SERVICE, accessibility.permission)
        assertEquals(Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE, listener.permission)
        assertTrue(accessibility.exported)
        assertFalse(listener.exported)
        val requested = pm.getPackageInfo(app.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())).requestedPermissions.orEmpty()
        assertFalse(Manifest.permission.INTERNET in requested)
        assertFalse(Manifest.permission.READ_CONTACTS in requested)
        assertFalse(Manifest.permission.QUERY_ALL_PACKAGES in requested)
    }
}
