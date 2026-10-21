package com.soakula.wacr.security

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.Intent
import android.os.PowerManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.soakula.wacr.data.AppPreferences
import com.soakula.wacr.data.ThemeChoice
import com.soakula.wacr.playback.PlaybackReleaseException
import com.soakula.wacr.util.AppLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
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
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.io.IOException
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 36], application = Application::class)
class AppLockTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val values = MutableStateFlow<Preferences>(emptyPreferences())
    private var failRead = false
    private var failRelease = false
    private var stops = 0
    private val preferences = AppPreferences(object : DataStore<Preferences> {
        override val data = values.onEach { if (failRead) throw IOException("Synthetic preference failure") }
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(values.value).also { values.value = it }
    })
    private lateinit var lock: AppLock
    private val activity get() = owner
    private lateinit var owner: Activity

    @Before fun prepare() {
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(false)
        owner = Robolectric.buildActivity(Activity::class.java).get()
        lock = newLock()
    }

    @After fun close() { lock.close() }

    private fun newLock() = AppLock(context, preferences, AppLog(File(context.cacheDir, "lock-test"), redact = true,
        mirror = {}, reportFailure = { throw AssertionError("Test log write failed", it) })) {
        stops++
        if (failRelease) throw PlaybackReleaseException()
    }

    @Test fun coldLoadFailsClosedAndDisabledLockPreservesOrdinaryBackgroundPlayback() {
        assertFalse(lock.canAccess())
        await { lock.state.value == AppLockState.Disabled }
        stops = 0
        lock.foreground(activity)
        lock.background(activity)
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        ShadowLooper.idleMainLooper()
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(61))
        assertTrue(lock.canAccess())
        assertEquals(0, stops)
    }

    @Test fun backgroundRelocksAtExactlySixtySecondsAndShortReturnsPreserveAuthentication() {
        unlock()
        lock.background(activity)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(59_999))
        assertTrue(lock.canAccess())
        lock.foreground(activity)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(61))
        assertTrue(lock.canAccess())
        lock.background(activity)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))
        assertFalse(lock.canAccess())
        assertEquals(1, stops)
    }

    @Test fun screenOffImmediatelyRevokesPlaybackAndOldAuthenticationCannotUnlockAgain() {
        unlock()
        val revision = (lock.state.value as AppLockState.Unlocked).revision
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        ShadowLooper.idleMainLooper()
        assertTrue(lock.state.value is AppLockState.Locked)
        assertEquals(1, stops)
        assertFalse(lock.authenticated(revision))
        assertFalse(lock.canAccess())
    }

    @Test fun deviceStateCheckClosesTheMissedBroadcastWindowAndDisallowsLockedAuthentication() {
        unlock()
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(true)
        assertFalse(lock.canAccess())
        val locked = lock.state.value as AppLockState.Locked
        assertFalse(lock.authenticated(locked.revision))
        assertEquals(1, stops)
    }

    @Test fun appearanceChangesDoNotResetAuthenticationAndRecreationDoesNotPersistIt() = runBlocking {
        unlock()
        preferences.setTheme(ThemeChoice.DARK)
        ShadowLooper.idleMainLooper()
        assertTrue(lock.canAccess())
        lock.close()
        lock = newLock()
        await { lock.state.value is AppLockState.Locked }
        assertFalse(lock.canAccess())
    }

    @Test fun multipleVisibleActivitiesDoNotStartBackgroundTimerPrematurely() {
        unlock()
        val second = Robolectric.buildActivity(Activity::class.java).get()
        lock.foreground(second)
        lock.background(activity)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(61))
        assertTrue(lock.canAccess())
        lock.background(second)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))
        assertFalse(lock.canAccess())
    }

    @Test fun readFailureRevokesAccessAndRetryRequiresNewAuthentication() = runBlocking {
        unlock()
        failRead = true
        preferences.setTheme(ThemeChoice.DARK)
        await { lock.state.value == AppLockState.Failed }
        assertFalse(lock.canAccess())
        assertEquals(1, stops)
        failRead = false
        lock.retry()
        await { lock.state.value is AppLockState.Locked }
        assertFalse(lock.canAccess())
    }

    @Test fun releaseFailureIsExplicitAndCannotLookLikeAnUnlockedApp() {
        unlock()
        failRelease = true
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        ShadowLooper.idleMainLooper()
        assertEquals(AppLockState.Failed, lock.state.value)
        assertFalse(lock.canAccess())
    }

    private fun unlock() {
        await { lock.state.value == AppLockState.Disabled }
        runBlocking { preferences.setAppLock(true) }
        await { lock.state.value is AppLockState.Locked }
        val locked = lock.state.value as AppLockState.Locked
        assertFalse(lock.authenticated(locked.revision))
        lock.foreground(activity)
        assertTrue(lock.authenticated(locked.revision))
        stops = 0
    }

    private fun await(predicate: () -> Boolean) {
        val end = System.nanoTime() + 10_000_000_000L
        while (!predicate() && System.nanoTime() < end) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(5)
        }
        assertTrue("App lock did not reach expected state", predicate())
    }
}
