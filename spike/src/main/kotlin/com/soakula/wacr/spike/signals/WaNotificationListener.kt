package com.soakula.wacr.spike.signals

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.soakula.wacr.spike.Prefs
import com.soakula.wacr.spike.log.SpikeLog

/** Logs WhatsApp notification lifecycle. Message notifications: metadata only, never content. */
class WaNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        SpikeLog.log("notif", "listener_connected")
        runCatching { activeNotifications }.getOrNull()?.filter(::isWa)?.forEach { report("active", it, null) }
    }

    override fun onListenerDisconnected() = SpikeLog.log("notif", "listener_disconnected")

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (isWa(sbn)) report("posted", sbn, null)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        if (isWa(sbn)) report("removed", sbn, reason)
    }

    private fun isWa(sbn: StatusBarNotification) = sbn.packageName in WA

    private fun report(what: String, sbn: StatusBarNotification, reason: Int?) {
        val n = sbn.notification
        val x = n.extras
        val channel = n.channelId ?: ""
        val isCall = n.category == Notification.CATEGORY_CALL || CALL_CHANNEL.containsMatchIn(channel)
        val title = x.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        Prefs.addName(title)
        Prefs.addName(x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString())
        val data = linkedMapOf<String, Any?>(
            "pkg" to sbn.packageName, "key" to sbn.key, "id" to sbn.id, "postTime" to sbn.postTime,
            "category" to n.category, "channel" to channel, "isCall" to isCall,
            "ongoing" to (n.flags and Notification.FLAG_ONGOING_EVENT != 0),
            "fgs" to (n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0),
            "flags" to n.flags, "when" to n.`when`,
            "chronometer" to x.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER),
            "template" to x.getString(Notification.EXTRA_TEMPLATE)?.substringAfterLast('$'),
            "callType" to x.getInt(Notification.EXTRA_CALL_TYPE, -1),
            "callIsVideo" to x.getBoolean(Notification.EXTRA_CALL_IS_VIDEO, false),
            "fullScreenIntent" to (n.fullScreenIntent != null),
            "actions" to n.actions?.map { it.title?.toString() },
            "removeReason" to reason,
        )
        if (isCall) {
            data["title"] = title
            data["text"] = x.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            data["subText"] = x.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
            data["bigText"] = x.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        }
        SpikeLog.log("notif", what, data)
        CallGate.notification(sbn.key, isCall, removed = what == "removed")
    }

    companion object {
        val WA = setOf("com.whatsapp", "com.whatsapp.w4b")
        private val CALL_CHANNEL = Regex("(?i)call|voip")
    }
}
