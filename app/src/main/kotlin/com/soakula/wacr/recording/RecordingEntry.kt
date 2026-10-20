package com.soakula.wacr.recording

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri

internal object RecordingEntry {
    const val ACTION_START = "com.soakula.wacr.action.START_MANUAL_RECORDING"
    private const val ACTION_CALL = "com.soakula.wacr.action.RECORD_DETECTED_CALL"

    fun intent(context: Context): Intent = Intent(ACTION_START)
        .setComponent(ComponentName(context, RecordingEntry::class.java))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun pendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 41, intent(context), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun callIntent(context: Context, sessionId: Long): Intent {
        require(sessionId > 0) { "Session ID must be positive" }
        return intent(context).setAction(ACTION_CALL).setData(Uri.parse("wacr://call/$sessionId/record"))
    }

    fun callPendingIntent(context: Context, sessionId: Long): PendingIntent = PendingIntent.getActivity(
        context, 41, callIntent(context, sessionId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun consumeCall(context: Context, intent: Intent): Long? {
        if (intent.action != ACTION_CALL || intent.component != ComponentName(context, RecordingEntry::class.java)) return null
        val uri = intent.data ?: return null
        val id = uri.pathSegments.firstOrNull()?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        if (uri != Uri.parse("wacr://call/$id/record")) return null
        intent.action = null
        intent.data = null
        return id
    }

    fun consume(context: Context, intent: Intent): Boolean {
        if (intent.action != ACTION_START || intent.component != ComponentName(context, RecordingEntry::class.java)) return false
        intent.action = null
        return true
    }
}
