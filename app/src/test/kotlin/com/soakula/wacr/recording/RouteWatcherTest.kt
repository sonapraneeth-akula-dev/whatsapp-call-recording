package com.soakula.wacr.recording

import android.annotation.SuppressLint
import android.app.Application
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.soakula.wacr.data.AudioRoute
import com.soakula.wacr.util.AppLog
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, manifest = Config.NONE)
class RouteWatcherTest {
    @get:Rule val temporary = TemporaryFolder()
    private val application: Application get() = RuntimeEnvironment.getApplication()
    private val manager get() = application.getSystemService(AudioManager::class.java)
    private lateinit var log: AppLog
    private var elapsed = 100L

    @Before fun createLog() { log = AppLog(temporary.newFolder(), false, {}, { throw it }) }

    @Test fun mapsOnlyKnownActiveDeviceTypesAndDistinguishesUsbFromAnalogWired() {
        assertEquals(AudioRoute.EARPIECE, RouteWatcher.classify(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))
        assertEquals(AudioRoute.SPEAKER, RouteWatcher.classify(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE))
        assertEquals(AudioRoute.WIRED, RouteWatcher.classify(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        listOf(AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY)
            .forEach { assertEquals(AudioRoute.USB, RouteWatcher.classify(it)) }
        listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER)
            .forEach { assertEquals(AudioRoute.BLUETOOTH, RouteWatcher.classify(it)) }
        assertNull(RouteWatcher.classify(null))
        assertNull(RouteWatcher.classify(AudioDeviceInfo.TYPE_HDMI))
    }

    @Test fun connectionInventoryIsNotMistakenForTheSelectedDeviceAndNotificationsAreDeduplicated() {
        val earpiece = select(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
        RouteWatcher(application, log, { elapsed }).use { watcher ->
            assertEquals(AudioRoute.EARPIECE, watcher.takeInitialRoute())
            assertTrue(watcher.changes().isEmpty())
            val bluetooth = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO).build()
            shadowOf(manager).addAvailableCommunicationDevice(bluetooth, true)
            ShadowLooper.idleMainLooper()
            assertEquals(earpiece, manager.communicationDevice)
            assertTrue(watcher.changes().isEmpty())
            elapsed = 200
            choose(bluetooth)
            assertEquals(listOf(RouteChange(AudioRoute.BLUETOOTH, 200)), watcher.changes())
            shadowOf(manager).callOnCommunicationDeviceChangedListeners(bluetooth)
            ShadowLooper.idleMainLooper()
            assertTrue(watcher.changes().isEmpty())
            assertEquals(bluetooth, manager.communicationDevice)
            assertNull(shadowOf(manager).lastAudioFocusRequest)
            assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        }
    }

    @Test fun closeUnregistersBothCallbacksAndNeverChangesTheChosenDevice() {
        val watcher = RouteWatcher(application, log, { elapsed })
        watcher.takeInitialRoute()
        select(AudioDeviceInfo.TYPE_USB_HEADSET)
        assertEquals(AudioRoute.USB, watcher.changes().single().route)
        watcher.close()
        watcher.close()
        val wired = select(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
        shadowOf(manager).addOutputDevice(wired, true)
        ShadowLooper.idleMainLooper()
        assertEquals(wired, manager.communicationDevice)
        assertThrows(IllegalStateException::class.java) { watcher.changes() }
        assertThrows(IllegalStateException::class.java) { watcher.takeInitialRoute() }
    }

    @Test fun initialSnapshotDiscardsPreCaptureTransitionsAndUnknownDevicesAreNotGuessed() {
        RouteWatcher(application, log, { elapsed }).use { watcher ->
            select(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
            select(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            assertEquals(AudioRoute.BLUETOOTH, watcher.takeInitialRoute())
            assertTrue(watcher.changes().isEmpty())
            select(AudioDeviceInfo.TYPE_HDMI)
            assertNull(watcher.changes().single().route)
        }
    }

    private fun select(type: Int): AudioDeviceInfo {
        val device = AudioDeviceInfoBuilder.newBuilder().setType(type).build()
        shadowOf(manager).addAvailableCommunicationDevice(device, false)
        choose(device)
        return device
    }

    @SuppressLint("MissingPermission")
    private fun choose(device: AudioDeviceInfo) {
        // Simulates WhatsApp choosing a device; production only observes.
        assertTrue(manager.setCommunicationDevice(device))
        shadowOf(manager).callOnCommunicationDeviceChangedListeners(device)
        ShadowLooper.idleMainLooper()
    }
}
