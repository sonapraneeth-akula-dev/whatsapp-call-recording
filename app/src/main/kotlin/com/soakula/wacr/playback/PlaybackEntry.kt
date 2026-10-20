package com.soakula.wacr.playback

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.UUID

internal object PlaybackEntry {
    const val ACTION = "com.soakula.wacr.action.OPEN_PRIVATE_PLAYBACK"

    fun intent(context: Context, id: UUID): Intent = Intent(ACTION)
        .setComponent(ComponentName(context, PlaybackEntry::class.java))
        .setData(Uri.parse("wacr://playback/$id"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun pendingIntent(context: Context, id: UUID): PendingIntent = PendingIntent.getActivity(
        context, 43, intent(context, id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun consume(context: Context, intent: Intent): UUID? {
        if (intent.action != ACTION || intent.component != ComponentName(context, PlaybackEntry::class.java)) return null
        val id = try { playbackId(intent.data?.lastPathSegment ?: return null) }
        catch (_: IllegalArgumentException) { return null }
        if (intent.data != Uri.parse("wacr://playback/$id")) return null
        intent.action = null
        intent.data = null
        return id
    }
}
