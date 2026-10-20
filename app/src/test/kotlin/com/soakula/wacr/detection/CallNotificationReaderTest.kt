package com.soakula.wacr.detection

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.service.notification.StatusBarNotification
import com.soakula.wacr.data.CallDirection
import com.soakula.wacr.data.CallType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class CallNotificationReaderTest {
    private fun reader() = CallNotificationReader(RuntimeEnvironment.getApplication().assets
        .open("detection_rules.json").use(DetectionRules::load))

    private fun notification() = Notification().apply {
        category = Notification.CATEGORY_CALL
        flags = Notification.FLAG_ONGOING_EVENT
        `when` = 1000
        extras = Bundle().apply {
            putString(Notification.EXTRA_TEMPLATE, Notification.CallStyle::class.java.name)
            putInt(Notification.EXTRA_CALL_TYPE, 2)
            putBoolean(Notification.EXTRA_SHOW_CHRONOMETER, true)
            putCharSequence(Notification.EXTRA_TITLE, "Synthetic caller")
        }
    }

    @Suppress("DEPRECATION")
    private fun status(value: Notification = notification(), pkg: String = "com.whatsapp") =
        StatusBarNotification(pkg, pkg, 7, null, 1000, 0, 0, value, Process.myUserHandle(), 1000)

    private fun read(
        value: Notification = notification(),
        version: String? = "2.26.40.76",
        model: String = "SM-A736B",
        sdk: Int = 36
    ) = (reader().read(status(value), version, model, sdk) as CallSignal.Posted).notification

    @Test fun knownProfileReadsOnlyBoundedCallerCandidateWithoutInventingUncapturedMetadata() {
        val value = notification()
        value.extras.putCharSequence(Notification.EXTRA_TITLE, "\uFF32\uFF21\uFF36\uFF29\u0000 " + "x".repeat(1000))
        value.extras.putString(Notification.EXTRA_TEXT, "Synthetic private unrelated text")
        val result = read(value)
        assertEquals(199, result.caller.displayName.codePointCount(0, result.caller.displayName.length))
        assertTrue(checkNotNull(result.caller.matchKey).startsWith("ravi "))
        assertEquals(CallDirection.UNKNOWN, result.direction)
        assertNull(result.callType)
        assertNull(result.isGroup)
        assertNull(result.onHold)
        assertFalse(result.toString().contains("Synthetic private unrelated text"))
    }

    @Test fun unsupportedBuildDeviceOsAndBusinessDoNotReadOrRetainCallerField() {
        val value = notification()
        value.extras.putInt(Notification.EXTRA_TITLE, 7)
        for (result in listOf(
            read(value, version = null), read(value, version = "2.26.40.77"),
            read(value, model = "SM-E426B", sdk = 33), read(value, sdk = 33),
            (reader().read(status(value, "com.whatsapp.w4b"), "2.26.40.76", "SM-A736B", 36) as CallSignal.Posted).notification
        )) assertEquals(CallerName("Unknown", null), result.caller)
    }

    @Test fun otherAppsChatNotificationsAndSummariesAreExcludedBeforeExtrasAreRead() {
        val value = notification()
        value.extras.putInt(Notification.EXTRA_TEMPLATE, 99)
        value.extras.putInt(Notification.EXTRA_TITLE, 99)
        assertNull(reader().read(status(value, "other.app"), "2.26.40.76", "SM-A736B", 36))
        value.category = Notification.CATEGORY_MESSAGE
        assertTrue(reader().read(status(value), "2.26.40.76", "SM-A736B", 36) is CallSignal.Removed)
        value.category = Notification.CATEGORY_CALL
        value.flags = value.flags or Notification.FLAG_GROUP_SUMMARY
        assertTrue(reader().read(status(value), "2.26.40.76", "SM-A736B", 36) is CallSignal.Removed)
    }

    @Test fun explicitVideoPresenceDiffersFromMissingAndWrongTypedExtrasThrow() {
        val value = notification()
        assertNull(read(value).callType)
        value.extras.putBoolean(Notification.EXTRA_CALL_IS_VIDEO, false)
        assertEquals(CallType.VOICE, read(value).callType)
        value.extras.putBoolean(Notification.EXTRA_CALL_IS_VIDEO, true)
        assertEquals(CallType.VIDEO, read(value).callType)
        val mutations: List<(Bundle) -> Unit> = listOf(
            { it.putString(Notification.EXTRA_SHOW_CHRONOMETER, "true") },
            { it.putString(Notification.EXTRA_CALL_TYPE, "2") },
            { it.putString(Notification.EXTRA_CALL_IS_VIDEO, "false") },
            { it.putInt(Notification.EXTRA_TEMPLATE, 7) },
            { it.putInt(Notification.EXTRA_TITLE, 7) }
        )
        mutations.forEach { mutate ->
            val malformed = notification().also { mutate(it.extras) }
            assertThrows(IllegalArgumentException::class.java) { read(malformed) }
        }
    }

    @Test fun missingTimerInvalidInstanceAndLookalikeTemplateCannotProveConnection() {
        for (value in listOf(
            notification().apply { extras.remove(Notification.EXTRA_SHOW_CHRONOMETER) },
            notification().apply { `when` = 0 },
            notification().apply { extras.putString(Notification.EXTRA_TEMPLATE, "other.CallStyle") }
        )) {
            val rules = RuntimeEnvironment.getApplication().assets.open("detection_rules.json").use(DetectionRules::load)
            val detector = CallDetector(rules.packages, rules.connection, rules.endDebounceMs, rules.reconnectWindowMs)
            detector.accept(CallSignal.AudioMode(true), 0)
            val events = detector.accept(CallSignal.Posted(read(value)), 1)
            assertTrue(events.none { it is CallEvent.Started })
        }
    }

    @Test fun nativeIncomingCallStyleCanRingWithoutOngoingFlagAndCarriesDirection() {
        val application = RuntimeEnvironment.getApplication()
        val action = PendingIntent.getActivity(application, 0, Intent("synthetic.call.action"), PendingIntent.FLAG_IMMUTABLE)
        val value = Notification.Builder(application, "synthetic-calls")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setCategory(Notification.CATEGORY_CALL)
            .setStyle(Notification.CallStyle.forIncomingCall(
                Person.Builder().setName("Synthetic caller").build(), action, action
            )).build()
        value.flags = value.flags and Notification.FLAG_ONGOING_EVENT.inv()
        val incoming = read(value)
        assertFalse(incoming.ongoing)
        assertEquals(CallDirection.INCOMING, incoming.direction)
        val rules = RuntimeEnvironment.getApplication().assets.open("detection_rules.json").use(DetectionRules::load)
        val detector = CallDetector(rules.packages, rules.connection, rules.endDebounceMs, rules.reconnectWindowMs)
        assertTrue(detector.accept(CallSignal.Posted(incoming), 1).none { it is CallEvent.Started })
        assertEquals(CallState.RINGING, detector.state)
    }
}
