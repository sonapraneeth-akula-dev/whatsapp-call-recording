package com.soakula.wacr.spike.signals

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import com.soakula.wacr.spike.Prefs
import com.soakula.wacr.logging.Json
import com.soakula.wacr.spike.log.SpikeLog

/** While the WhatsApp call screen is shown: dump its view tree ~1/s (tree/<ts>.json) and optional screenshots (shots/). */
class CallScreenSampler(private val svc: AccessibilityService) {
    private val h = Handler(Looper.getMainLooper())
    private var running = false
    private var lastShot = 0L
    private var dumps = 0
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            if (Prefs.treeDump) dumpTree("tick")
            if (Prefs.screenshots) shot()
            h.postDelayed(this, 1000)
        }
    }

    fun update(active: Boolean) {
        if (active && !running) {
            running = true; dumps = 0
            SpikeLog.log("a11y", "sampler_start")
            h.post(tick)
        } else if (!active && running) {
            running = false
            h.removeCallbacks(tick)
            SpikeLog.log("a11y", "sampler_stop", mapOf("dumps" to dumps))
        }
    }

    fun dumpTree(reason: String) {
        val roots = runCatching { svc.windows.mapNotNull { it.root }.filter { it.packageName?.toString() in WaNotificationListener.WA } }
            .getOrDefault(emptyList()).ifEmpty { listOfNotNull(svc.rootInActiveWindow) }
        if (roots.isEmpty()) return
        val nodes = ArrayList<Map<String, Any?>>()
        roots.forEach { walk(it, 0, nodes) }
        val ts = System.currentTimeMillis()
        val rel = "tree/$ts.json"
        runCatching { SpikeLog.file(rel).writeText(Json.encode(mapOf("ts" to ts, "reason" to reason, "nodes" to nodes))) }
        dumps++
        if (reason != "tick" || dumps % 10 == 1) SpikeLog.log("a11y", "tree_dump", mapOf("file" to rel, "nodes" to nodes.size, "reason" to reason, "dumps" to dumps))
    }

    private fun walk(n: AccessibilityNodeInfo, depth: Int, out: MutableList<Map<String, Any?>>) {
        if (out.size >= 800) return
        val r = Rect().also(n::getBoundsInScreen)
        val m = linkedMapOf<String, Any?>("d" to depth, "cls" to n.className?.toString()?.substringAfterLast('.'))
        n.viewIdResourceName?.let { m["id"] = it.substringAfter(":id/") }
        n.text?.let { m["text"] = it.toString().take(200) }
        n.contentDescription?.let { m["cd"] = it.toString().take(200) }
        n.stateDescription?.let { m["state"] = it.toString().take(100) }
        m["b"] = "${r.left},${r.top},${r.right},${r.bottom}"
        if (n.isSelected) m["sel"] = true
        if (n.isChecked) m["chk"] = true
        if (n.isFocused) m["foc"] = true
        if (n.isAccessibilityFocused) m["a11yFoc"] = true
        if (!n.isVisibleToUser) m["vis"] = false
        if (!n.isEnabled) m["en"] = false
        m["order"] = n.drawingOrder
        out += m
        for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1, out) }
    }

    private fun shot() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastShot < 1000) return
        lastShot = now
        svc.takeScreenshot(Display.DEFAULT_DISPLAY, svc.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                val hb = r.hardwareBuffer
                try {
                    val bmp = Bitmap.wrapHardwareBuffer(hb, r.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false) ?: return
                    val small = Bitmap.createScaledBitmap(bmp, bmp.width / 3, bmp.height / 3, true)
                    SpikeLog.file("shots/${System.currentTimeMillis()}.jpg").outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 60, it) }
                } catch (e: Exception) {
                    SpikeLog.error("a11y", "screenshot_save_failed", e)
                } finally {
                    hb.close()
                }
            }

            override fun onFailure(errorCode: Int) = SpikeLog.log("a11y", "screenshot_failed", mapOf("code" to errorCode))
        })
    }
}
