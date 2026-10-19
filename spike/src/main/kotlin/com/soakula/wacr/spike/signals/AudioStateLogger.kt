package com.soakula.wacr.spike.signals

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioRecordingConfiguration
import android.os.Handler
import android.os.Looper
import com.soakula.wacr.spike.capture.SweepController
import com.soakula.wacr.spike.log.Names
import com.soakula.wacr.spike.log.SpikeLog

/** Logs audio mode, routing, and every app's active recording/playback configs (privacy-reduced by Android). */
object AudioStateLogger {
    private var started = false

    fun start(ctx: Context) {
        if (started) return
        started = true
        val am = ctx.getSystemService(AudioManager::class.java)
        val exec = ctx.mainExecutor
        val h = Handler(Looper.getMainLooper())
        CallGate.mode(am.mode)
        SpikeLog.log(
            "audio", "initial", mapOf(
                "mode" to Names.mode(am.mode), "communicationDevice" to am.communicationDevice?.let(Names::device),
                "playback" to playbacks(am.activePlaybackConfigurations), "recording" to recordings(am.activeRecordingConfigurations)
            )
        )
        am.addOnModeChangedListener(exec) { m ->
            SpikeLog.log("audio", "mode", mapOf("mode" to Names.mode(m)))
            CallGate.mode(m)
        }
        am.addOnCommunicationDeviceChangedListener(exec) { d ->
            SpikeLog.log("audio", "communication_device", mapOf("device" to d?.let(Names::device), "route" to route(am)))
            SweepController.onRouteChanged()
        }
        am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) =
                SpikeLog.log("audio", "devices_added", mapOf("devices" to added.map(Names::device)))
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) =
                SpikeLog.log("audio", "devices_removed", mapOf("devices" to removed.map(Names::device)))
        }, h)
        am.registerAudioRecordingCallback(object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) =
                SpikeLog.log("audio", "recording_configs", mapOf("configs" to recordings(configs)))
        }, h)
        am.registerAudioPlaybackCallback(object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) =
                SpikeLog.log("audio", "playback_configs", mapOf("configs" to playbacks(configs)))
        }, h)
    }

    fun recordings(list: List<AudioRecordingConfiguration>) = list.map {
        linkedMapOf(
            "source" to Names.source(it.clientAudioSource), "session" to it.clientAudioSessionId,
            "silenced" to it.isClientSilenced, "device" to it.audioDevice?.let { d -> Names.deviceType(d.type) },
            "rate" to it.format.sampleRate, "effects" to it.clientEffects.map { e -> e.name }
        )
    }

    private fun playbacks(list: List<AudioPlaybackConfiguration>) = list.map {
        mapOf("usage" to Names.usage(it.audioAttributes.usage), "contentType" to it.audioAttributes.contentType)
    }

    fun route(am: AudioManager): String {
        val d = am.communicationDevice ?: return "unknown"
        return when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "speaker"
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET -> "wired"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> "bluetooth"
            else -> Names.deviceType(d.type).lowercase()
        }
    }
}
