package com.soakula.wacr.recording

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class RecorderNotificationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun stop(flags: Int = PendingIntent.FLAG_IMMUTABLE): PendingIntent =
        PendingIntent.getService(context, flags, Intent().setClassName(
            context.packageName, "com.soakula.wacr.recording.RecorderService"
        ).setAction("synthetic_stop"), flags)

    @Test fun recordingIsImmediateOngoingPrivateAndHasAnImmutableStopAndChronometer() {
        val notifications = RecorderNotification(context)
        val stop = stop()
        val value = notifications.recording(1000, stop)
        assertEquals(RecorderNotification.CHANNEL, value.channelId)
        assertEquals(Notification.VISIBILITY_PRIVATE, value.visibility)
        assertTrue(value.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(value.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertTrue(value.flags and Notification.FLAG_LOCAL_ONLY != 0)
        assertEquals(Notification.FOREGROUND_SERVICE_IMMEDIATE, ReflectionHelpers.getField<Int>(value, "mFgsDeferBehavior"))
        assertEquals(1000L, value.`when`)
        assertTrue(value.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertTrue(value.extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
        assertEquals("Stop", value.actions.single().title.toString())
        assertEquals(stop, value.actions.single().actionIntent)
        assertTrue(value.actions.single().actionIntent.isImmutable)
        assertTrue(value.contentIntent.isImmutable)
        val public = checkNotNull(value.publicVersion)
        assertEquals("Recording", public.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertNull(public.actions)
        assertNull(public.contentIntent)
        assertTrue(public.flags and Notification.FLAG_LOCAL_ONLY != 0)
        assertFalse(public.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertNull(value.fullScreenIntent)
    }

    @Test fun setupAndSaveHaveNoRunningTimerAndWarningKeepsTheStopControl() {
        val notifications = RecorderNotification(context)
        val preparing = notifications.preparing(stop())
        val saving = notifications.saving()
        assertEquals("Preparing recording", preparing.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(1, preparing.actions.size)
        assertFalse(preparing.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertFalse(saving.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertNull(saving.actions)
        val warning = notifications.recording(1000, stop(), lowStorage = true)
        assertTrue(warning.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("500 MB"))
        assertEquals(1, warning.actions.size)
        assertThrows(IllegalArgumentException::class.java) { notifications.recording(-1, stop()) }
        assertThrows(IllegalArgumentException::class.java) { notifications.preparing(stop(PendingIntent.FLAG_MUTABLE)) }
    }

    @Test fun lowImportanceChannelHasNoSoundVibrationOrBadge() {
        RecorderNotification(context)
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(RecorderNotification.CHANNEL)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertEquals(Notification.VISIBILITY_PRIVATE, channel.lockscreenVisibility)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
        assertFalse(channel.canShowBadge())
    }
}
