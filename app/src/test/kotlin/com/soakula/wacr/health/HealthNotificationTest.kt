package com.soakula.wacr.health

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import com.soakula.wacr.MainActivity
import com.soakula.wacr.util.AppLog
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class HealthNotificationTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private fun subject() = HealthNotification(app, AppLog(File(app.filesDir, "health-logs"), true, {}, { throw it }))
    private val issues = listOf(HealthIssue(HealthProblem.ACCESSIBILITY_DISABLED, null))

    @Test fun actionablePrivateAlertUsesExistingIssueTextAndClearsWhenHealthy() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val alerts = subject()
        assertTrue(alerts.publish(issues))
        val notification = checkNotNull(shadowOf(manager).getNotification(HealthNotification.ID))
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertEquals(HealthNotification.CHANNEL, notification.channelId)
        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertTrue(notification.flags and Notification.FLAG_LOCAL_ONLY != 0)
        assertNull(notification.fullScreenIntent)
        assertTrue(notification.contentIntent.isImmutable)
        val intent = shadowOf(notification.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(HealthNotification.ACTION_SETUP, intent.action)
        assertNull(intent.extras)
        assertNull(notification.publicVersion.contentIntent)
        assertNull(notification.publicVersion.actions)
        assertTrue(alerts.publish(emptyList()))
        assertNull(shadowOf(manager).getNotification(HealthNotification.ID))
    }

    @Test fun deniedNotificationsOrChannelNeverPretendAnAlertWasShown() {
        val alerts = subject()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(alerts.publish(issues))
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(alerts.publish(issues))
        manager.getNotificationChannel(HealthNotification.CHANNEL).importance = NotificationManager.IMPORTANCE_NONE
        assertFalse(alerts.publish(issues))
        assertNull(shadowOf(manager).getNotification(HealthNotification.ID))
    }
}
