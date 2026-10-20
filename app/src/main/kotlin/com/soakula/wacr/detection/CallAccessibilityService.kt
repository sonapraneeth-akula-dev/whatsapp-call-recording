package com.soakula.wacr.detection

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.soakula.wacr.WacrApp

class CallAccessibilityService : AccessibilityService() {
    private val detector get() = (application as WacrApp).detection

    override fun onServiceConnected() {
        super.onServiceConnected()
        detector.accessibilityConnected(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in CallDetection.PACKAGES) return
        detector.window(this, pkg, event.className?.take(256)?.toString())
    }

    override fun onInterrupt() {
        (application as WacrApp).log.log("detection", "accessibility_feedback_interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        detector.accessibilityDisconnected(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        detector.accessibilityDisconnected(this)
        super.onDestroy()
    }
}
