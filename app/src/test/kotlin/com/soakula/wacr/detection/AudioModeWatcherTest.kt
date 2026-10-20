package com.soakula.wacr.detection

import android.app.Application
import android.media.AudioManager
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AudioModeWatcherTest {
    @Test fun observesInitialAndChangedModesAndUnregistersWithoutOwningAudioFocusOrMode() {
        val app = RuntimeEnvironment.getApplication()
        val manager = checkNotNull(app.getSystemService(AudioManager::class.java))
        val changes = mutableListOf<Int>()
        val watcher = AudioModeWatcher(app) { changes += it }
        assertEquals(AudioManager.MODE_NORMAL, watcher.start(app))
        assertThrows(IllegalStateException::class.java) { watcher.start(app) }
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(AudioManager.MODE_IN_COMMUNICATION), changes)
        watcher.close()
        watcher.close()
        manager.mode = AudioManager.MODE_NORMAL
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(AudioManager.MODE_IN_COMMUNICATION), changes)
    }
}
