package com.soakula.wacr.spike.capture

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.soakula.wacr.spike.Host
import com.soakula.wacr.spike.Prefs
import com.soakula.wacr.spike.log.SpikeLog
import com.soakula.wacr.spike.signals.AudioStateLogger
import com.soakula.wacr.spike.signals.CallGate
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Starts a sweep when a call is detected (and again whenever the route changes to one not yet swept in this call),
 * using the host chosen in Prefs. Every start path and failure is logged — that is the R-2 probe.
 */
object SweepController {
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val current = AtomicReference<SourceSweep?>()
    private val sweptRoutes = mutableSetOf<String>()
    val running = MutableStateFlow(false)

    fun init(ctx: Context) {
        app = ctx.applicationContext
        CallGate.listener = ::onCall
    }

    private fun onCall(start: Boolean) {
        if (!start) {
            current.get()?.let { it.cancelled = true; SpikeLog.log("sweep", "cancel", mapOf("reason" to "call_end")) }
            if (Prefs.host == Host.ON_DETECT) CaptureService.stop(app)
            return
        }
        sweptRoutes.clear()
        if (!Prefs.autoSweep) { SpikeLog.log("sweep", "auto_off"); return }
        main.postDelayed({ if (CallGate.inCall) begin("call_detected") }, 3000) // let the call settle
    }

    fun onRouteChanged() {
        if (CallGate.inCall && Prefs.autoSweep && current.get() == null) main.postDelayed({ maybeResweep() }, 2000)
    }

    private fun maybeResweep() {
        val route = AudioStateLogger.route(app.getSystemService(AudioManager::class.java))
        if (CallGate.inCall && current.get() == null && route !in sweptRoutes) begin("route_changed:$route")
    }

    fun begin(reason: String, host: String = Prefs.host) {
        if (current.get() != null) { SpikeLog.log("sweep", "already_running", mapOf("reason" to reason)); return }
        SpikeLog.log("sweep", "begin", mapOf("host" to host, "reason" to reason, "signals" to CallGate.signals(), "process" to SourceSweep.processState()))
        when (host) {
            Host.DIRECT -> runSweep(Host.DIRECT, reason)
            Host.READY ->
                if (CaptureService.ready.value) runSweep(Host.READY, reason)
                else { SpikeLog.log("sweep", "ready_fgs_not_running"); startFgs(reason) }
            else -> startFgs(reason)
        }
    }

    private fun startFgs(reason: String) {
        try {
            app.startForegroundService(
                Intent(app, CaptureService::class.java).setAction(CaptureService.ACTION_SWEEP)
                    .putExtra(CaptureService.EXTRA_REASON, reason).putExtra(CaptureService.EXTRA_HOST, Host.ON_DETECT)
            )
            SpikeLog.log("fgs", "start_requested", mapOf("reason" to reason))
        } catch (e: Exception) {
            SpikeLog.error("fgs", "start_failed", e, mapOf("process" to SourceSweep.processState()))
            runSweep(Host.DIRECT + "(fallback)", reason)
        }
    }

    fun runSweep(host: String, reason: String) {
        val s = SourceSweep(app, host, reason)
        if (!current.compareAndSet(null, s)) return
        running.value = true
        sweptRoutes += AudioStateLogger.route(app.getSystemService(AudioManager::class.java))
        thread(name = "sweep") {
            try {
                s.run()
            } catch (e: Throwable) {
                SpikeLog.error("sweep", "crashed", e)
            } finally {
                current.set(null)
                running.value = false
                main.post {
                    if (host == Host.ON_DETECT && !CaptureService.ready.value) CaptureService.stop(app)
                    else if (CaptureService.ready.value) Notifier.status(app, "Ready — a test sweep starts when a WhatsApp call connects")
                    maybeResweep()
                }
            }
        }
    }

    fun stop(reason: String) {
        current.get()?.let { it.cancelled = true; SpikeLog.log("sweep", "cancel", mapOf("reason" to reason)) }
    }
}
