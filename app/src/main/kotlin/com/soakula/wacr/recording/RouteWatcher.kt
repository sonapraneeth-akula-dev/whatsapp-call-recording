package com.soakula.wacr.recording

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.soakula.wacr.data.AudioRoute
import com.soakula.wacr.util.AppLog
import java.io.Closeable
import java.util.concurrent.ConcurrentLinkedQueue

data class RouteChange(val route: AudioRoute?, val elapsedMs: Long)

/** Observes the active communication device, never selects a device or requests audio focus. */
class RouteWatcher(
    context: Context,
    private val log: AppLog,
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime
) : Closeable {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val lock = Any()
    private val events = ConcurrentLinkedQueue<RouteChange>()
    @Volatile private var closed = false
    private var route: AudioRoute? = null
    private var initialized = false
    private val communication = AudioManager.OnCommunicationDeviceChangedListener { refresh() }
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    init {
        manager.addOnCommunicationDeviceChangedListener(context.mainExecutor, communication)
        var ready = false
        try {
            manager.registerAudioDeviceCallback(devices, Handler(Looper.getMainLooper()))
            refresh()
            ready = true
        } finally {
            if (!ready) close()
        }
    }

    fun takeInitialRoute(): AudioRoute? = synchronized(lock) {
        check(!closed) { "Route watcher is closed" }
        events.clear()
        route
    }

    fun changes(): List<RouteChange> {
        check(!closed) { "Route watcher is closed" }
        return generateSequence { events.poll() }.toList()
    }

    private fun refresh() {
        if (closed) return
        synchronized(lock) {
            val next = classify(manager.communicationDevice?.type)
            if (closed || (initialized && next == route)) return
            route = next
            initialized = true
            val at = elapsedMs()
            check(at >= 0) { "Route clock must be nonnegative" }
            events.add(RouteChange(next, at))
            log.log("route", "changed", mapOf("route" to next?.name))
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            events.clear()
        }
        try {
            manager.unregisterAudioDeviceCallback(devices)
        } finally {
            manager.removeOnCommunicationDeviceChangedListener(communication)
        }
    }

    companion object {
        internal fun classify(type: Int?): AudioRoute? = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioRoute.EARPIECE
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> AudioRoute.SPEAKER
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> AudioRoute.WIRED
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> AudioRoute.USB
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST, AudioDeviceInfo.TYPE_HEARING_AID -> AudioRoute.BLUETOOTH
            else -> null
        }
    }
}
