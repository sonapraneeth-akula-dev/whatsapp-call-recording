package com.soakula.wacr.spike

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.soakula.wacr.spike.log.SpikeLog
import com.soakula.wacr.spike.signals.CallGate
import com.soakula.wacr.spike.signals.CallScreenSampler

/** Logs WhatsApp window/content events (text only on call screens) and drives the call-screen sampler. */
class SpikeAccessibilityService : AccessibilityService() {
    private lateinit var sampler: CallScreenSampler
    private val lastLogged = HashMap<String, Long>()

    override fun onServiceConnected() {
        instance = this
        sampler = CallScreenSampler(this)
        SpikeLog.log("a11y", "connected")
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        val cls = e.className?.toString() ?: ""
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val isActivity = cls.startsWith("com.whatsapp")
                if (isActivity) {
                    CallGate.window(cls)
                    sampler.update(CallGate.isCallClass(cls))
                }
                val call = CallGate.isCallClass(cls)
                SpikeLog.log("a11y", "window_state", buildMap {
                    put("pkg", pkg); put("cls", cls); put("isActivity", isActivity); put("callWindow", call)
                    if (call) {
                        put("text", e.text.joinToString(" | ").take(200))
                        put("cd", e.contentDescription?.toString()?.take(200))
                    }
                })
                if (call && Prefs.treeDump) sampler.dumpTree("window_state")
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val src = e.source
                val id = src?.viewIdResourceName?.substringAfter(":id/") ?: cls.substringAfterLast('.')
                if (!throttle(id)) return
                SpikeLog.log("a11y", "content", buildMap {
                    put("id", id); put("cls", cls.substringAfterLast('.')); put("changes", e.contentChangeTypes)
                    if (CallGate.callWindow) {
                        put("text", src?.text?.toString()?.take(200))
                        put("cd", src?.contentDescription?.toString()?.take(200))
                    }
                })
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED ->
                if (throttle("windows")) SpikeLog.log("a11y", "windows_changed", mapOf("changes" to e.windowChanges))
            AccessibilityEvent.TYPE_VIEW_CLICKED -> if (CallGate.callWindow) {
                SpikeLog.log("a11y", "click", mapOf(
                    "id" to e.source?.viewIdResourceName?.substringAfter(":id/"),
                    "text" to e.text.joinToString(" | ").take(200), "cd" to e.contentDescription?.toString()?.take(200)
                ))
            }
        }
    }

    /** At most one log per key per second (content changes fire many times a second during calls). */
    private fun throttle(key: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - (lastLogged[key] ?: 0L) < 1000) return false
        lastLogged[key] = now
        return true
    }

    override fun onInterrupt() = SpikeLog.log("a11y", "interrupt")

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        sampler.update(false)
        SpikeLog.log("a11y", "unbound")
        return super.onUnbind(intent)
    }

    companion object {
        @Volatile var instance: SpikeAccessibilityService? = null
    }
}
