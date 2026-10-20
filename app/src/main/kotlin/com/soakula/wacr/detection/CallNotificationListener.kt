package com.soakula.wacr.detection

import android.app.NotificationManager
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.soakula.wacr.WacrApp

class CallNotificationListener : NotificationListenerService() {
    private val detector get() = (application as WacrApp).detection
    @Volatile private var binding: Any? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        val owner = Any()
        binding = owner
        detector.listenerConnected(owner) {
            if (binding !== owner) null else activeNotifications.also {
                if (it == null) {
                    (application as WacrApp).log.log("detection", "listener_snapshot_unavailable")
                    rebindIfGranted()
                }
            }
        }
    }

    override fun onNotificationPosted(notification: StatusBarNotification?) {
        if (notification != null) binding?.let { detector.posted(it, notification) }
    }

    override fun onNotificationRemoved(notification: StatusBarNotification?) {
        if (notification != null) binding?.let { detector.removed(it, notification) }
    }

    override fun onListenerDisconnected() {
        detach()
        rebindIfGranted()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        detach()
        super.onDestroy()
    }

    private fun detach() {
        val owner = binding
        binding = null
        if (owner != null) detector.listenerDisconnected(owner)
    }

    private fun rebindIfGranted() {
        val component = ComponentName(this, CallNotificationListener::class.java)
        try {
            if (checkNotNull(getSystemService(NotificationManager::class.java)).isNotificationListenerAccessGranted(component)) {
                requestRebind(component)
            }
        } catch (error: SecurityException) {
            (application as WacrApp).log.log("detection", "listener_rebind_failed", mapOf("error_type" to error.javaClass.simpleName))
        } catch (error: IllegalStateException) {
            (application as WacrApp).log.log("detection", "listener_rebind_failed", mapOf("error_type" to error.javaClass.simpleName))
        }
    }
}
