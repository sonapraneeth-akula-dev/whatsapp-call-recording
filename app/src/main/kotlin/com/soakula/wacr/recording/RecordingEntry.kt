package com.soakula.wacr.recording

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent

internal object RecordingEntry {
    const val ACTION_START = "com.soakula.wacr.action.START_MANUAL_RECORDING"

    fun intent(context: Context): Intent = Intent(ACTION_START)
        .setComponent(ComponentName(context, RecordingEntry::class.java))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun pendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 41, intent(context), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun consume(context: Context, intent: Intent): Boolean {
        if (intent.action != ACTION_START || intent.component != ComponentName(context, RecordingEntry::class.java)) return false
        intent.action = null
        return true
    }
}
