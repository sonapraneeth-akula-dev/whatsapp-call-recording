package com.soakula.wacr.spike.capture

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import com.soakula.wacr.spike.SpikeActivity

/** One status notification (id 1), shared with the capture FGS so cues update the same entry. */
object Notifier {
    const val ID = 1
    private const val CHANNEL = "spike"

    fun init(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Spike status", NotificationManager.IMPORTANCE_LOW))
    }

    fun build(ctx: Context, text: String, ongoing: Boolean = true): Notification =
        NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("WACR Spike")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(ctx, 0, Intent(ctx, SpikeActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            )
            .build()

    fun status(ctx: Context, text: String, ongoing: Boolean = true) {
        if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ctx.getSystemService(NotificationManager::class.java).notify(ID, build(ctx, text, ongoing || CaptureService.instance != null))
    }
}
