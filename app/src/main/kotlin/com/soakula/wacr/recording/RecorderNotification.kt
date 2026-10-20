package com.soakula.wacr.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import com.soakula.wacr.MainActivity
import com.soakula.wacr.R

class RecorderNotification(private val context: Context) {
    init {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.recording_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.recording_channel_description)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
    }

    fun preparing(stop: PendingIntent): Notification =
        build(R.string.recording_preparing, R.string.recording_preparing_detail, stop)

    fun recording(startedAt: Long, stop: PendingIntent, lowStorage: Boolean = false): Notification {
        require(startedAt >= 0)
        return build(
            R.string.recording_active,
            if (lowStorage) R.string.recording_storage_warning else R.string.recording_active_detail,
            stop, startedAt
        )
    }

    fun saving(): Notification = build(R.string.recording_saving, R.string.recording_saving_detail)

    fun notice(@StringRes detail: Int): Notification = build(R.string.recording_notice_title, detail, ongoing = false)

    private fun build(
        @StringRes title: Int, @StringRes detail: Int, stop: PendingIntent? = null,
        startedAt: Long? = null, ongoing: Boolean = true
    ): Notification {
        if (stop != null) {
            require(stop.isImmutable && stop.creatorPackage == context.packageName) { "Stop action must be immutable and app-owned" }
        }
        val public = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(context.getString(if (ongoing) R.string.recording_public_title else R.string.recording_notice_title))
            .setContentText(context.getString(R.string.recording_public_detail))
            .setLocalOnly(true)
            .setOngoing(ongoing).build()
        val content = context.getString(detail)
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(context.getString(title))
            .setContentText(content)
            .setStyle(Notification.BigTextStyle().bigText(content))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setOngoing(ongoing)
            .setLocalOnly(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setShowWhen(startedAt != null)
            .setUsesChronometer(startedAt != null)
            .setWhen(startedAt ?: 0)
            .setContentIntent(PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            ))
            .apply {
                if (stop != null) addAction(Notification.Action.Builder(
                    null, context.getString(R.string.recording_stop), stop
                ).build())
            }.build()
    }

    companion object {
        const val ID = 2001
        const val NOTICE_ID = 2002
        const val CHANNEL = "recording"
    }
}
