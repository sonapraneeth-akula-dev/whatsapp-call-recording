package com.soakula.wacr.recording

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
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
class AskNotificationTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private fun subject() = AskNotification(app, AppLog(File(app.filesDir, "ask-logs"), true, {}, { throw it }))

    @Test fun promptIsPrivateHighImportanceAndCarriesOnlyAnImmutableSessionSpecificEntry() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val asks = subject()
        assertTrue(asks.show(7))
        val notification = checkNotNull(shadowOf(manager).getNotification(AskNotification.ID))
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(AskNotification.CHANNEL).importance)
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertEquals("Record this call?", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertTrue(notification.flags and Notification.FLAG_LOCAL_ONLY != 0)
        assertNull(notification.fullScreenIntent)
        assertFalse(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertTrue(notification.actions.single().actionIntent.isImmutable)
        val intent = shadowOf(notification.contentIntent).savedIntent
        assertNull(intent.extras)
        assertEquals(7L, RecordingEntry.consumeCall(app, intent))
        assertNull(RecordingEntry.consumeCall(app, intent))
        assertNull(notification.publicVersion.actions)
        assertNull(notification.publicVersion.contentIntent)
        assertTrue(asks.show(7))
        asks.cancel()
        assertNull(shadowOf(manager).getNotification(AskNotification.ID))
    }

    @Test fun oldCallTokensCannotBeRetargetedToNewCallsAndMalformedEntriesAreRejected() {
        val old = RecordingEntry.callPendingIntent(app, 1)
        val next = RecordingEntry.callPendingIntent(app, 2)
        assertNotEquals(old, next)
        assertEquals(1L, RecordingEntry.consumeCall(app, shadowOf(old).savedIntent))
        assertEquals(2L, RecordingEntry.consumeCall(app, shadowOf(next).savedIntent))
        val forged = RecordingEntry.callIntent(app, 1).setClass(app, MainActivity::class.java)
        assertNull(RecordingEntry.consumeCall(app, forged))
        for (uri in listOf("wacr://call/0/record", "wacr://call/-1/record", "wacr://call/1/record?other=2",
            "wacr://call/1/other", "wacr://call/9223372036854775808/record", "other://call/1/record")) {
            assertNull(RecordingEntry.consumeCall(app, RecordingEntry.callIntent(app, 1).setData(Uri.parse(uri))))
        }
        assertNull(RecordingEntry.consumeCall(app, Intent()))
        assertThrows(IllegalArgumentException::class.java) { RecordingEntry.callIntent(app, 0) }
    }

    @Test fun missingPermissionAndBlockedChannelNeverPretendAPromptWasPosted() {
        val asks = subject()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(asks.show(1))
        assertNull(shadowOf(manager).getNotification(AskNotification.ID))
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(asks.show(1))
        manager.getNotificationChannel(AskNotification.CHANNEL).importance = NotificationManager.IMPORTANCE_NONE
        assertFalse(asks.show(2))
        assertNull(shadowOf(manager).getNotification(AskNotification.ID))
    }
}
