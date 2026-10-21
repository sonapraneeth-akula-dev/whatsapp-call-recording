package com.soakula.wacr.security

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.biometrics.BiometricManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.soakula.wacr.data.AppPreferences
import com.soakula.wacr.playback.PlaybackReleaseException
import com.soakula.wacr.util.AppLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.IOException

sealed interface AppLockState {
    data object Loading : AppLockState
    data object Failed : AppLockState
    data object Disabled : AppLockState
    data class Locked(val revision: Long) : AppLockState
    data class Unlocked(val revision: Long) : AppLockState
}

@OptIn(ExperimentalCoroutinesApi::class)
class AppLock internal constructor(
    private val context: Context,
    preferences: AppPreferences,
    private val log: AppLog,
    private val stopPlayback: () -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val visible = mutableSetOf<Activity>()
    private val refresh = MutableStateFlow(0L)
    private val mutableState = MutableStateFlow<AppLockState>(AppLockState.Loading)
    val state = mutableState.asStateFlow()
    @Volatile private var enabled: Boolean? = null
    @Volatile private var revision = 0L
    @Volatile private var deadline: Long? = null
    private var releaseFailed = false
    private val expiry = Runnable { canAccess() }
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF && enabled == true) lock()
        }
    }

    init {
        context.registerReceiver(screen, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
        scope.launch {
            refresh.flatMapLatest {
                preferences.values.flowOn(Dispatchers.IO).onStart {
                    revision++
                    mutableState.value = if (releaseFailed) AppLockState.Failed else AppLockState.Loading
                    closePlayback()
                }.catch { error ->
                    if (error is CancellationException) throw error
                    if (error !is IOException && error !is IllegalArgumentException &&
                        error !is IllegalStateException && error !is SecurityException) throw error
                    revision++
                    mutableState.value = AppLockState.Failed
                    log.log("app_lock", "preferences_failed", mapOf("error_type" to error.javaClass.simpleName))
                    closePlayback()
                }
            }.collect { preferences ->
                if (releaseFailed) return@collect
                if (enabled == preferences.appLock &&
                    mutableState.value !in setOf(AppLockState.Loading, AppLockState.Failed)) return@collect
                enabled = preferences.appLock
                if (preferences.appLock) lock() else {
                    revision++
                    clearDeadline()
                    mutableState.value = AppLockState.Disabled
                }
            }
        }
    }

    internal fun foreground(activity: Activity) {
        visible.add(activity)
        canAccess()
        clearDeadline()
    }

    internal fun background(activity: Activity) {
        visible.remove(activity)
        if (visible.isEmpty() && mutableState.value is AppLockState.Unlocked) {
            deadline = SystemClock.elapsedRealtime() + BACKGROUND_TIMEOUT_MS
            handler.removeCallbacks(expiry)
            handler.postDelayed(expiry, BACKGROUND_TIMEOUT_MS)
        }
    }

    fun canAccess(): Boolean {
        if (enabled == true && mutableState.value is AppLockState.Unlocked &&
            (deviceLocked() || deadline?.let { SystemClock.elapsedRealtime() >= it } == true)) lock()
        return mutableState.value == AppLockState.Disabled || mutableState.value is AppLockState.Unlocked
    }

    internal fun authenticated(expectedRevision: Long): Boolean {
        if (mutableState.value != AppLockState.Locked(expectedRevision) ||
            visible.isEmpty() || deviceLocked()) {
            log.log("app_lock", "authentication_expired")
            return false
        }
        clearDeadline()
        mutableState.value = AppLockState.Unlocked(expectedRevision)
        log.log("app_lock", "unlocked")
        return true
    }

    internal fun settingAuthorization(): Long? = if (canAccess()) revision else null

    internal fun canChangeSetting(expectedRevision: Long, enable: Boolean): Boolean {
        val current = mutableState.value
        val allowed = current is AppLockState.Unlocked || enable && current == AppLockState.Disabled
        return allowed && !deviceLocked() &&
            deadline?.let { SystemClock.elapsedRealtime() < it } != false &&
            revision == expectedRevision && mutableState.value == current
    }

    fun retry() { refresh.update { it + 1 } }

    private fun deviceLocked() = context.getSystemService(KeyguardManager::class.java).isDeviceLocked ||
        !context.getSystemService(PowerManager::class.java).isInteractive

    private fun lock() {
        revision++
        clearDeadline()
        mutableState.value = AppLockState.Locked(revision)
        log.log("app_lock", "locked")
        closePlayback()
    }

    private fun closePlayback() {
        try {
            stopPlayback()
        } catch (_: PlaybackReleaseException) {
            releaseFailed = true
            mutableState.value = AppLockState.Failed
            log.log("app_lock", "playback_release_failed_restart_required")
        }
    }

    private fun clearDeadline() {
        deadline = null
        handler.removeCallbacks(expiry)
    }

    internal fun close() {
        scope.cancel()
        handler.removeCallbacks(expiry)
        context.unregisterReceiver(screen)
        visible.clear()
    }

    companion object {
        const val BACKGROUND_TIMEOUT_MS = 60_000L
        const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }
}
