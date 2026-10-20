package com.soakula.wacr.detection

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class CallDetectorFixtureTest {
    @Test fun capturedTwoCallRunStartsOnlyAtConnectedTimerAndNeverMergesReusedNotificationKey() {
        val fixture = requireNotNull(javaClass.getResourceAsStream("/fixtures/a73-2.26.40.76-outgoing-voice.json"))
            .bufferedReader().use { JSONObject(it.readText()) }
        val rules = RuntimeEnvironment.getApplication().assets.open("detection_rules.json").use(DetectionRules::load)
        val metadata = fixture.getJSONObject("provenance")
        val pkg = metadata.getString("packageName")
        val detector = CallDetector(rules.packages, rules.connection, rules.endDebounceMs)
        val events = mutableListOf<CallEvent>()
        val captured = fixture.getJSONArray("events")
        for (index in 0 until captured.length()) {
            val entry = captured.getJSONObject(index)
            val atMs = entry.getLong("atMs")
            while (detector.nextDeadlineMs?.let { it <= atMs } == true) {
                events += detector.accept(CallSignal.Tick, requireNotNull(detector.nextDeadlineMs))
            }
            val signal = when (entry.getString("kind")) {
                "audio" -> CallSignal.AudioMode(entry.getBoolean("communication"))
                "removed" -> CallSignal.Removed(pkg, "anonymous-call-key")
                "posted" -> CallSignal.Posted(CallNotification(
                    packageName = pkg,
                    key = "anonymous-call-key",
                    category = "call",
                    ongoing = entry.getBoolean("ongoing"),
                    template = entry.getString("template"),
                    styleCallType = entry.getInt("styleCallType"),
                    chronometer = entry.getBoolean("chronometer"),
                    instance = entry.getLong("instance")
                ))
                else -> error("Unknown captured fixture event")
            }
            events += detector.accept(signal, atMs)
        }
        events += detector.accept(CallSignal.Tick, requireNotNull(detector.nextDeadlineMs))
        val starts = events.filterIsInstance<CallEvent.Started>().map { it.call }
        val ends = events.filterIsInstance<CallEvent.Ended>()
        assertEquals(listOf(36_871L, 184_577L), starts.map { it.connectedAtMs })
        assertEquals(listOf(18_294L, 171_287L), starts.map { it.ringingAtMs })
        assertEquals(listOf(79_831L, 229_698L), ends.map { it.endedAtMs })
        assertEquals(listOf(1L, 2L), starts.map { it.sequence })
        assertEquals(starts, ends.map { it.call })
        starts.forEach {
            assertNull(it.caller.matchKey)
            assertNull(it.callType)
            assertNull(it.isGroup)
        }
        assertEquals(CallState.ENDED, detector.state)
        assertTrue(metadata.getBoolean("identityFieldsRemoved"))
        assertFalse(metadata.getBoolean("videoExtraPresenceKnown"))
        assertFalse(metadata.getBoolean("groupClassificationKnown"))
    }
}
