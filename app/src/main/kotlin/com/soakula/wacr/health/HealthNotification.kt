package com.soakula.wacr.health

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.soakula.wacr.MainActivity
import com.soakula.wacr.R
import com.soakula.wacr.ui.home.healthTitle
import com.soakula.wacr.util.AppLog

internal class HealthNotification(private val context: Context, private val log: AppLog) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.health_alert_channel), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { setShowBadge(false); lockscreenVisibility = Notification.VISIBILITY_PRIVATE }
        )
    }

    fun publish(issues: List<HealthIssue>): Boolean {
        if (issues.isEmpty()) {
            manager.cancel(ID)
            return true
        }
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ||
            !manager.areNotificationsEnabled() ||
            manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) {
            manager.cancel(ID)
            log.log("health", "alert_unavailable")
            return false
        }
        val public = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(context.getString(R.string.health_alert_title))
            .setContentText(context.getString(R.string.health_alert_public))
            .setLocalOnly(true).build()
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(context.getString(R.string.health_alert_title))
            .setContentText(context.getString(R.string.health_alert_detail,
                context.getString(healthTitle(issues.first().problem))))
            .setContentIntent(PendingIntent.getActivity(context, 42,
                Intent(context, MainActivity::class.java)
                    .setAction(ACTION_SETUP)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setLocalOnly(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setShowWhen(false).build()
        return try {
            manager.notify(ID, notification)
            true
        } catch (error: SecurityException) {
            log.log("health", "alert_permission_changed")
            false
        }
    }

    companion object {
        const val CHANNEL = "setup_health"
        const val ID = 2004
        const val ACTION_SETUP = "com.soakula.wacr.action.REVIEW_SETUP"
    }
}
