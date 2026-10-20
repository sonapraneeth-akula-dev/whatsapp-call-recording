package com.soakula.wacr.detection

import android.app.Notification
import android.os.Bundle
import android.os.Process
import android.service.notification.StatusBarNotification
import com.soakula.wacr.data.CallDirection

class CallNotificationReader(private val rules: DetectionRules) {
    fun read(
        notification: StatusBarNotification,
        installedVersion: String?,
        deviceModel: String,
        sdkInt: Int
    ): CallSignal? {
        val pkg = notification.packageName
        if (pkg !in rules.packages || notification.user != Process.myUserHandle()) return null
        val key = notification.key
        require(key.isNotBlank() && key.length <= 512) { "Invalid notification identity" }
        val value = notification.notification
        if (value.category != rules.connection.category || value.flags and Notification.FLAG_GROUP_SUMMARY != 0) {
            return CallSignal.Removed(pkg, key)
        }
        val extras = value.extras
        val rawTemplate = extras.optional<String>(Notification.EXTRA_TEMPLATE)
        val template = when (rawTemplate) {
            Notification.CallStyle::class.java.name -> "CallStyle"
            Notification.BigTextStyle::class.java.name -> "BigTextStyle"
            else -> null
        }
        val styleCallType = extras.optional<Int>(Notification.EXTRA_CALL_TYPE)
        val timer = extras.optional<Boolean>(Notification.EXTRA_SHOW_CHRONOMETER) ?: false
        val video = extras.optional<Boolean>(Notification.EXTRA_CALL_IS_VIDEO)
        val profile = installedVersion?.let { rules.profile(pkg, it, deviceModel, sdkInt) }
        val title = if (profile != null) extras.optional<CharSequence>(Notification.EXTRA_TITLE)?.take(400)?.toString() else null
        return CallSignal.Posted(CallNotification(
            packageName = pkg,
            key = key,
            category = value.category,
            ongoing = value.flags and Notification.FLAG_ONGOING_EVENT != 0,
            template = template,
            styleCallType = styleCallType,
            chronometer = timer,
            instance = value.`when`.takeIf { it > 0 },
            caller = NameExtractor.extract(title),
            // The public type constant requires API 34; its extra value is also readable on API 33.
            direction = if (template == rules.connection.template && styleCallType == 1) {
                CallDirection.INCOMING
            } else CallDirection.UNKNOWN,
            callType = CallTypeClassifier.classify(video)
        ))
    }
}

@Suppress("DEPRECATION")
private inline fun <reified T> Bundle.optional(key: String): T? {
    val value = get(key) ?: return null
    require(value is T) { "Invalid call notification extra" }
    return value
}
