package com.soakula.wacr.health

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.pm.ResolveInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.soakula.wacr.recording.RecorderNotification
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class HealthMonitorTest {
    private val application: Application get() = RuntimeEnvironment.getApplication()
    private val notifications get() = checkNotNull(application.getSystemService(NotificationManager::class.java))
    private val accessibility get() = checkNotNull(application.getSystemService(AccessibilityManager::class.java))
    private lateinit var monitor: HealthMonitor

    @Before fun prepare() {
        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(notifications).setNotificationsEnabled(true)
        shadowOf(checkNotNull(application.getSystemService(PowerManager::class.java)))
            .setIgnoringBatteryOptimizations(application.packageName, true)
        monitor = HealthMonitor(application)
    }

    private fun service(name: String, permission: String) = ServiceInfo().apply {
        packageName = application.packageName
        this.name = "${application.packageName}.$name"
        this.permission = permission
        applicationInfo = application.applicationInfo
        enabled = true
    }

    private fun enableDetector(): Pair<ServiceInfo, ServiceInfo> {
        application.packageManager.getPackageInfo(application.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SERVICES.toLong())).services.orEmpty()
            .filter { it.permission == Manifest.permission.BIND_ACCESSIBILITY_SERVICE ||
                it.permission == Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE }
            .forEach { shadowOf(application.packageManager).addOrUpdateService(ServiceInfo(it).apply { enabled = false }) }
        val a11y = service("SyntheticAccessibility", Manifest.permission.BIND_ACCESSIBILITY_SERVICE)
        val listener = service("SyntheticListener", Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE)
        shadowOf(application.packageManager).addOrUpdateService(a11y)
        shadowOf(application.packageManager).addOrUpdateService(listener)
        val info = AccessibilityServiceInfo()
        ReflectionHelpers.setField(info, "mResolveInfo", ResolveInfo().apply { serviceInfo = a11y })
        shadowOf(accessibility).setEnabled(true)
        shadowOf(accessibility).setEnabledAccessibilityServiceList(listOf(info))
        shadowOf(notifications).setNotificationListenerAccessGranted(ComponentName(listener.packageName, listener.name), true)
        return a11y to listener
    }

    private fun problems() = monitor.check().map { it.problem }.toSet()

    @Test fun productionAdaptersRequireExplicitGrantsWithRealSettingsFixes() {
        val issues = monitor.check()
        assertEquals(setOf(HealthProblem.ACCESSIBILITY_DISABLED, HealthProblem.NOTIFICATION_ACCESS_DISABLED),
            issues.map { it.problem }.toSet())
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, issues.first { it.problem == HealthProblem.ACCESSIBILITY_DISABLED }.fixIntent?.action)
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, issues.first { it.problem == HealthProblem.NOTIFICATION_ACCESS_DISABLED }.fixIntent?.action)
    }

    @Test fun grantedServicesWithoutOperationalDetectorNeverReportReady() {
        enableDetector()
        var ready = false
        monitor = HealthMonitor(application) { ready }
        assertEquals(setOf(HealthProblem.DETECTION_UNAVAILABLE), problems())
        ready = true
        assertTrue(monitor.check().isEmpty())
        ready = false
        assertEquals(setOf(HealthProblem.DETECTION_UNAVAILABLE), problems())
    }

    @Test fun allNativeChecksMustPassAndRecheckingObservesRevocationAndReenablement() {
        val (_, listener) = enableDetector()
        assertTrue(monitor.check().isEmpty())
        shadowOf(accessibility).setEnabled(false)
        assertEquals(setOf(HealthProblem.ACCESSIBILITY_DISABLED), problems())
        shadowOf(accessibility).setEnabled(true)
        shadowOf(notifications).setNotificationListenerAccessGranted(ComponentName(listener.packageName, listener.name), false)
        assertEquals(setOf(HealthProblem.NOTIFICATION_ACCESS_DISABLED), problems())
        shadowOf(notifications).setNotificationListenerAccessGranted(ComponentName(listener.packageName, listener.name), true)
        shadowOf(checkNotNull(application.getSystemService(PowerManager::class.java)))
            .setIgnoringBatteryOptimizations(application.packageName, false)
        val issue = monitor.check().single()
        assertEquals(HealthProblem.BATTERY_OPTIMIZED, issue.problem)
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, issue.fixIntent?.action)
    }

    @Test fun microphoneNotificationsAndBlockedChannelProduceAppSpecificFixes() {
        enableDetector()
        shadowOf(application).denyPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        notifications.createNotificationChannel(NotificationChannel(
            RecorderNotification.CHANNEL, "Synthetic recording channel", NotificationManager.IMPORTANCE_NONE
        ))
        val issues = monitor.check().associateBy { it.problem }
        assertEquals(setOf(
            HealthProblem.MICROPHONE_PERMISSION, HealthProblem.NOTIFICATIONS_DISABLED,
            HealthProblem.RECORDING_CHANNEL_DISABLED
        ), issues.keys)
        assertEquals("package:${application.packageName}", issues[HealthProblem.MICROPHONE_PERMISSION]?.fixIntent?.dataString)
        assertEquals(application.packageName, issues[HealthProblem.NOTIFICATIONS_DISABLED]?.fixIntent
            ?.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals(RecorderNotification.CHANNEL, issues[HealthProblem.RECORDING_CHANNEL_DISABLED]?.fixIntent
            ?.getStringExtra(Settings.EXTRA_CHANNEL_ID))
    }

    @Test fun globalNotificationDisableAndBackgroundRestrictionAreNotHiddenByGrantedPermissions() {
        enableDetector()
        shadowOf(notifications).setNotificationsEnabled(false)
        shadowOf(checkNotNull(application.getSystemService(ActivityManager::class.java))).setBackgroundRestricted(true)
        assertEquals(setOf(HealthProblem.NOTIFICATIONS_DISABLED, HealthProblem.BACKGROUND_RESTRICTED), problems())
    }

    @Test fun unrelatedOrDisabledServicesCannotSatisfyTheOwnPackageDetectorChecks() {
        val (a11y, _) = enableDetector()
        val other = AccessibilityServiceInfo()
        ReflectionHelpers.setField(other, "mResolveInfo", ResolveInfo().apply {
            serviceInfo = ServiceInfo(a11y).apply { packageName = "synthetic.other" }
        })
        shadowOf(accessibility).setEnabledAccessibilityServiceList(listOf(other))
        assertEquals(setOf(HealthProblem.ACCESSIBILITY_DISABLED), problems())
        shadowOf(application.packageManager).addOrUpdateService(a11y.apply { enabled = false })
        assertEquals(setOf(HealthProblem.DETECTION_UNAVAILABLE), problems())
    }
}
