package com.soakula.wacr.detection

import android.app.Application
import android.app.Notification
import android.os.Bundle
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.room.Room
import com.soakula.wacr.data.AppDatabase
import com.soakula.wacr.data.CallDirection
import com.soakula.wacr.data.CallType
import com.soakula.wacr.data.RecordingDecision
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class GoldenTest {
    @Test fun capturedA73BuildReplaysThroughNativeInterpretationAndTransactionalSessionEffects() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val fixture = checkNotNull(javaClass.getResourceAsStream("/fixtures/a73-2.26.40.76-outgoing-voice.json"))
            .bufferedReader().use { JSONObject(it.readText()) }
        val provenance = fixture.getJSONObject("provenance")
        assertEquals("captured_redacted_spike", provenance.getString("kind"))
        assertEquals("relative_wall_clock_ms", provenance.getString("timeBasis"))
        assertTrue(provenance.getBoolean("identityFieldsRemoved"))
        assertFalse(provenance.getBoolean("videoExtraPresenceKnown"))
        assertFalse(provenance.getBoolean("groupClassificationKnown"))
        val rules = app.assets.open("detection_rules.json").use(DetectionRules::load)
        val pkg = provenance.getString("packageName")
        val version = provenance.getString("versionName")
        val model = provenance.getString("deviceModel")
        val sdk = provenance.getInt("sdkInt")
        assertNotNull(rules.profile(pkg, version, model, sdk))
        assertNull(rules.profile(pkg, "$version.1", model, sdk))
        val detector = CallDetector(rules.packages, rules.connection, rules.endDebounceMs, rules.reconnectWindowMs)
        val reader = CallNotificationReader(rules)
        val database = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        try {
            val writer = CallSessionWriter(database, 0, WALL_ORIGIN)
            val events = mutableListOf<CallEvent>()
            val sessionIds = mutableMapOf<Long, Long>()
            suspend fun accept(signal: CallSignal, atMs: Long) {
                val effects = detector.accept(signal, atMs)
                events += effects
                val identities = writer.apply(effects, detector.retainedSequences)
                effects.filterIsInstance<CallEvent.Started>().forEach {
                    sessionIds[it.call.sequence] = checkNotNull(identities[it.call.sequence])
                }
            }
            val captured = fixture.getJSONArray("events")
            for (index in 0 until captured.length()) {
                val entry = captured.getJSONObject(index)
                val atMs = entry.getLong("atMs")
                while (detector.nextDeadlineMs?.let { it <= atMs } == true) {
                    accept(CallSignal.Tick, checkNotNull(detector.nextDeadlineMs))
                }
                val signal = when (entry.getString("kind")) {
                    "audio" -> CallSignal.AudioMode(entry.getBoolean("communication"))
                    "posted" -> checkNotNull(reader.read(envelope(entry, pkg), version, model, sdk))
                    "removed" -> CallSignal.Removed(pkg, envelope(entry, pkg).key)
                    else -> error("Unknown captured fixture event")
                }
                accept(signal, atMs)
            }
            while (detector.nextDeadlineMs != null) accept(CallSignal.Tick, checkNotNull(detector.nextDeadlineMs))
            assertEquals(2, sessionIds.size)
            assertEquals(listOf(36_871L, 184_577L), events.filterIsInstance<CallEvent.Started>().map { it.call.connectedAtMs })
            assertEquals(listOf(79_831L, 229_698L), events.filterIsInstance<CallEvent.Ended>().map { it.endedAtMs })
            assertFalse(events.any { it is CallEvent.Resumed })
            val rows = sessionIds.toSortedMap().values.map { checkNotNull(database.recordings().session(it)) }
            assertEquals(listOf(18_294L, 171_287L).map { WALL_ORIGIN + it }, rows.map { it.ringingAt })
            assertEquals(listOf(36_871L, 184_577L).map { WALL_ORIGIN + it }, rows.map { it.connectedAt })
            assertEquals(listOf(79_831L, 229_698L).map { WALL_ORIGIN + it }, rows.map { it.endedAt })
            rows.forEach {
                assertNull(it.waDisplayName)
                assertNull(it.matchKey)
                assertEquals(CallDirection.UNKNOWN, it.direction)
                assertEquals(CallType.UNKNOWN, it.callType)
                assertFalse(it.isGroupKnown)
                assertEquals(RecordingDecision.IGNORED, it.decision)
            }
            database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM call_session").use {
                assertTrue(it.moveToFirst())
                assertEquals(2, it.getInt(0))
            }
            database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM recording").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
            assertEquals(CallState.ENDED, detector.state)
            assertTrue(detector.retainedSequences.isEmpty())
        } finally {
            database.close()
        }
    }

    // Reconstructs retained protocol fields, not an original OS parcel or unrecorded extras.
    @Suppress("DEPRECATION")
    private fun envelope(entry: JSONObject, pkg: String): StatusBarNotification {
        val value = Notification().apply {
            category = Notification.CATEGORY_CALL
            flags = if (entry.getBoolean("ongoing")) Notification.FLAG_ONGOING_EVENT else 0
            `when` = WALL_ORIGIN + entry.getLong("instance")
            extras = Bundle().apply {
                putString(Notification.EXTRA_TEMPLATE, when (entry.getString("template")) {
                    "CallStyle" -> Notification.CallStyle::class.java.name
                    "BigTextStyle" -> Notification.BigTextStyle::class.java.name
                    else -> error("Uncaptured notification template")
                })
                putInt(Notification.EXTRA_CALL_TYPE, entry.getInt("styleCallType"))
                putBoolean(Notification.EXTRA_SHOW_CHRONOMETER, entry.getBoolean("chronometer"))
            }
        }
        return StatusBarNotification(pkg, pkg, 7, "anonymous-call", 10000, 0, 0, value, Process.myUserHandle(),
            WALL_ORIGIN + entry.getLong("atMs"))
    }

    companion object { private const val WALL_ORIGIN = 1_700_000_000_000L }
}
