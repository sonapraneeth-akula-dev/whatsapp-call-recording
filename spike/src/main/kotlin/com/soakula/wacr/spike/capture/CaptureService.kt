package com.soakula.wacr.spike.capture

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.soakula.wacr.spike.Host
import com.soakula.wacr.spike.log.SpikeLog
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Microphone foreground service. ACTION_READY = AD-012 fallback (started from the app, idles with a "Ready"
 * notification); ACTION_SWEEP = AD-012 primary (started by the accessibility service when a call is detected).
 */
class CaptureService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_READY
        val reason = intent?.getStringExtra(EXTRA_REASON) ?: action
        if (action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val text = if (action == ACTION_READY) "Ready — a test sweep starts when a WhatsApp call connects" else "Starting sweep…"
        try {
            startForeground(Notifier.ID, Notifier.build(this, text), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            SpikeLog.error("fgs", "startForeground_failed", e, mapOf("action" to action, "process" to SourceSweep.processState()))
            stopSelf()
            if (action == ACTION_SWEEP) SweepController.runSweep(Host.DIRECT + "(fallback)", reason)
            return START_NOT_STICKY
        }
        instance = this
        SpikeLog.log("fgs", "started", mapOf("action" to action, "reason" to reason, "process" to SourceSweep.processState()))
        if (action == ACTION_READY) ready.value = true
        if (action == ACTION_SWEEP) SweepController.runSweep(intent?.getStringExtra(EXTRA_HOST) ?: Host.ON_DETECT, reason)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        instance = null
        ready.value = false
        SpikeLog.log("fgs", "destroyed")
    }

    companion object {
        const val ACTION_READY = "ready"
        const val ACTION_SWEEP = "sweep"
        const val ACTION_STOP = "stop"
        const val EXTRA_REASON = "reason"
        const val EXTRA_HOST = "host"
        @Volatile var instance: CaptureService? = null
        val ready = MutableStateFlow(false)

        fun stop(@Suppress("UNUSED_PARAMETER") ctx: Context) {
            instance?.let { it.stopForeground(STOP_FOREGROUND_REMOVE); it.stopSelf() }
        }
    }
}
