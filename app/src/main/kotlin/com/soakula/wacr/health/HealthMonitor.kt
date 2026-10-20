package com.soakula.wacr.health

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.soakula.wacr.recording.RecorderNotification
import com.soakula.wacr.recording.AskNotification

enum class HealthProblem {
    MICROPHONE_PERMISSION, NOTIFICATIONS_DISABLED, RECORDING_CHANNEL_DISABLED, REQUEST_CHANNEL_DISABLED,
    DETECTION_UNAVAILABLE, ACCESSIBILITY_DISABLED, NOTIFICATION_ACCESS_DISABLED,
    BATTERY_OPTIMIZED, BACKGROUND_RESTRICTED
}

data class HealthIssue(val problem: HealthProblem, val fixIntent: Intent?)

class HealthMonitor(context: Context, private val detectionReady: () -> Boolean = { true }) {
    private val application = context.applicationContext

    fun check(): List<HealthIssue> = buildList {
        val packageName = application.packageName
        fun settings(action: String) = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        fun appDetails() = settings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$packageName"))

        if (application.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            add(HealthIssue(HealthProblem.MICROPHONE_PERMISSION, appDetails()))
        }
        val notifications = checkNotNull(application.getSystemService(NotificationManager::class.java))
        if (application.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ||
            !notifications.areNotificationsEnabled()) {
            add(HealthIssue(HealthProblem.NOTIFICATIONS_DISABLED,
                settings(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)))
        }
        if (notifications.getNotificationChannel(RecorderNotification.CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) {
            add(HealthIssue(HealthProblem.RECORDING_CHANNEL_DISABLED,
                settings(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, RecorderNotification.CHANNEL)))
        }
        if (notifications.getNotificationChannel(AskNotification.CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) {
            add(HealthIssue(HealthProblem.REQUEST_CHANNEL_DISABLED,
                settings(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, AskNotification.CHANNEL)))
        }

        val services = application.packageManager.getPackageInfo(
            packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SERVICES.toLong())
        ).services.orEmpty().filter { it.enabled && it.packageName == packageName }
        val accessibility = services.filter { it.permission == Manifest.permission.BIND_ACCESSIBILITY_SERVICE }
        val listeners = services.filter { it.permission == Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE }
        if (accessibility.isEmpty() || listeners.isEmpty()) {
            add(HealthIssue(HealthProblem.DETECTION_UNAVAILABLE, null))
        }
        val manager = checkNotNull(application.getSystemService(AccessibilityManager::class.java))
        val enabled = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .map { ComponentName(it.resolveInfo.serviceInfo.packageName, it.resolveInfo.serviceInfo.name) }.toSet()
        val accessibilityEnabled = manager.isEnabled && accessibility.any { ComponentName(it.packageName, it.name) in enabled }
        if (accessibility.isNotEmpty() && !accessibilityEnabled) {
            add(HealthIssue(HealthProblem.ACCESSIBILITY_DISABLED, settings(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
        }
        val listenerEnabled = listeners.any { notifications.isNotificationListenerAccessGranted(ComponentName(it.packageName, it.name)) }
        if (listeners.isNotEmpty() && !listenerEnabled) {
            add(HealthIssue(HealthProblem.NOTIFICATION_ACCESS_DISABLED, settings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)))
        }
        if (accessibilityEnabled && listenerEnabled && !detectionReady()) {
            add(HealthIssue(HealthProblem.DETECTION_UNAVAILABLE, null))
        }

        if (!checkNotNull(application.getSystemService(PowerManager::class.java)).isIgnoringBatteryOptimizations(packageName)) {
            add(HealthIssue(HealthProblem.BATTERY_OPTIMIZED, settings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)))
        }
        if (checkNotNull(application.getSystemService(ActivityManager::class.java)).isBackgroundRestricted) {
            add(HealthIssue(HealthProblem.BACKGROUND_RESTRICTED, appDetails()))
        }
    }
}
