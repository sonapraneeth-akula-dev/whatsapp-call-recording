package com.soakula.wacr.recording

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import com.soakula.wacr.R
import com.soakula.wacr.util.AppLog

internal class AskNotification(private val context: Context, private val log: AppLog) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private var shown: Long? = null

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.ask_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
    }

    fun show(sessionId: Long): Boolean {
        require(sessionId > 0) { "Session ID must be positive" }
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ||
            !manager.areNotificationsEnabled() ||
            manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) {
            cancel()
            log.log("ask", "notifications_unavailable")
            return false
        }
        if (shown == sessionId) return true
        val public = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(context.getString(R.string.ask_public_title))
            .setContentText(context.getString(R.string.ask_public_detail))
            .setLocalOnly(true).build()
        val entry = RecordingEntry.callPendingIntent(context, sessionId)
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(context.getString(R.string.ask_title))
            .setContentText(context.getString(R.string.ask_detail))
            .setContentIntent(entry)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.tile_record), entry).build())
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setLocalOnly(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setShowWhen(false)
            .build()
        return try {
            manager.notify(ID, notification)
            shown = sessionId
            true
        } catch (error: SecurityException) {
            shown = null
            log.log("ask", "notification_permission_changed")
            false
        }
    }

    fun cancel() {
        manager.cancel(ID)
        shown = null
    }

    companion object {
        const val CHANNEL = "recording_requests"
        const val ID = 2003
    }
}
