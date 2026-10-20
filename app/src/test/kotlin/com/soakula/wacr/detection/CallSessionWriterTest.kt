package com.soakula.wacr.detection

import android.app.Application
import androidx.room.Room
import com.soakula.wacr.data.AppDatabase
import com.soakula.wacr.data.CallDirection
import com.soakula.wacr.data.CallType
import com.soakula.wacr.data.RecordingDecision
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, manifest = Config.NONE)
class CallSessionWriterTest {
    private lateinit var database: AppDatabase
    private lateinit var writer: CallSessionWriter
    private lateinit var detector: CallDetector

    @Before fun prepare() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).build()
        writer = CallSessionWriter(database, 100, 10_000)
        detector = CallDetector(setOf("com.whatsapp"), ConnectionRules("call", "CallStyle", 2), 2000, 30_000)
    }

    @After fun close() { database.close() }

    private fun notification(instance: Long = 10, hold: Boolean? = null) = CallSignal.Posted(CallNotification(
        "com.whatsapp", "synthetic-key", "call", true, "CallStyle", 2, true, instance, onHold = hold
    ))

    private suspend fun apply(signal: CallSignal, time: Long): Map<Long, Long> =
        writer.apply(detector.accept(signal, time), detector.retainedSequences)

    private suspend fun count(): Int = database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM call_session").use {
        it.moveToFirst()
        it.getInt(0)
    }

    @Test fun persistsOnlyConnectedCallsAndKeepsUnknownConfidenceAndOriginalLossTime() = runBlocking {
        apply(CallSignal.AudioMode(true), 101)
        apply(CallSignal.Posted(notification().notification.copy(chronometer = false)), 102)
        assertEquals(0, count())
        val id = apply(notification(), 103).values.single()
        val row = checkNotNull(database.recordings().session(id))
        assertEquals(10_002L, row.ringingAt)
        assertEquals(10_003L, row.connectedAt)
        assertNull(row.waDisplayName)
        assertNull(row.matchKey)
        assertFalse(row.isGroupKnown)
        assertEquals(CallType.UNKNOWN, row.callType)
        assertEquals(CallDirection.UNKNOWN, row.direction)
        assertEquals(RecordingDecision.IGNORED, row.decision)
        apply(notification(), 104)
        apply(CallSignal.Removed("com.whatsapp", "synthetic-key"), 200)
        assertNull(database.recordings().session(id)?.endedAt)
        apply(CallSignal.Tick, 2200)
        assertEquals(10_100L, database.recordings().session(id)?.endedAt)
        assertEquals(1, count())
    }

    @Test fun provisionalEndReconnectAndLateHoldReopenTheSameRowWithoutChangingItsOrigin() = runBlocking {
        apply(CallSignal.AudioMode(true), 100)
        val id = apply(notification(), 101).values.single()
        val original = checkNotNull(database.recordings().session(id))
        apply(CallSignal.Removed("com.whatsapp", "synthetic-key"), 200)
        apply(CallSignal.Tick, 2200)
        assertNotNull(database.recordings().session(id)?.endedAt)
        assertEquals(id, apply(notification(hold = true), 2300).values.single())
        assertEquals(original, database.recordings().session(id))
        assertEquals(id, apply(notification(hold = false), 40_000).values.single())
        assertEquals(original, database.recordings().session(id))
        apply(CallSignal.Removed("com.whatsapp", "synthetic-key"), 40_100)
        apply(CallSignal.Tick, 42_100)
        assertEquals(id, apply(notification(hold = false), 42_200).values.single())
        assertEquals(original, database.recordings().session(id))
        assertEquals(1, count())
    }

    @Test fun secondCallAndSourceLossCloseAllRowsAndRetireIdentityMappings() = runBlocking {
        apply(CallSignal.AudioMode(true), 100)
        val first = apply(notification(hold = true), 101)
        assertTrue(first.isEmpty())
        val id = apply(notification(hold = false), 102).values.single()
        apply(notification(hold = true), 103)
        val ids = apply(notification(instance = 20), 104)
        assertEquals(2, ids.size)
        assertEquals(2, count())
        assertTrue(apply(CallSignal.Unavailable, 105).isEmpty())
        ids.values.forEach { assertEquals(10_005L, database.recordings().session(it)?.endedAt) }
        assertTrue(apply(CallSignal.Tick, 40_000).isEmpty())
        assertEquals(2, count())
        assertNotNull(database.recordings().session(id))
    }

    @Test fun expiryRetiresMappingsAndAnExactBoundaryCallGetsANewRow() = runBlocking {
        apply(CallSignal.AudioMode(true), 100)
        val id = apply(notification(), 101).values.single()
        apply(CallSignal.Removed("com.whatsapp", "synthetic-key"), 200)
        apply(CallSignal.Tick, 2200)
        assertTrue(apply(CallSignal.Tick, 30_200).isEmpty())
        val second = apply(notification(), 30_200).values.single()
        assertNotEquals(id, second)
        assertEquals(2, count())
    }

    @Test fun failedBatchRollsBackInsertedRowsAndDoesNotAdvanceIdentityMap() = runBlocking {
        detector.accept(CallSignal.AudioMode(true), 100)
        val events = detector.accept(notification(), 101)
        val call = events.filterIsInstance<CallEvent.Started>().single().call
        assertThrows(IllegalStateException::class.java) {
            runBlocking { writer.apply(events + CallEvent.Ended(call.copy(sequence = 99), 102), setOf(1)) }
        }
        assertEquals(0, count())
        val id = writer.apply(events, setOf(1)).values.single()
        assertEquals(1, count())
        assertEquals(id, writer.apply(events, setOf(1)).values.single())
        assertEquals(1, count())
    }

    @Test fun typeSwitchesPersistExactTimelineAndFinalTypeWithoutCreatingAnotherSession() = runBlocking {
        apply(CallSignal.AudioMode(true), 100)
        val voice = notification().copy(notification = notification().notification.copy(callType = CallType.VOICE))
        val id = apply(voice, 101).values.single()
        val video = voice.copy(notification = voice.notification.copy(callType = CallType.VIDEO))
        assertEquals(id, apply(video, 200).values.single())
        apply(video, 201)
        apply(video.copy(notification = video.notification.copy(callType = null)), 202)
        apply(voice, 300)
        val events = database.recordings().sessionTypeEvents(id)
        assertEquals(listOf(CallType.VOICE, CallType.VIDEO), events.map { it.from })
        assertEquals(listOf(CallType.VIDEO, CallType.VOICE), events.map { it.to })
        assertEquals(listOf(10_100L, 10_200L), events.map { it.atMs })
        assertEquals(CallType.VOICE, database.recordings().session(id)?.callType)
        assertEquals(10_001L, database.recordings().session(id)?.connectedAt)
        assertEquals(1, count())
        apply(CallSignal.Unavailable, 400)
        writer.closeAll(500)
        assertEquals(10_300L, database.recordings().session(id)?.endedAt)
        assertEquals(events, database.recordings().sessionTypeEvents(id))
    }

    @Test fun failedTypeBatchRollsBackBothFinalTypeAndHistory() = runBlocking {
        apply(CallSignal.AudioMode(true), 100)
        val voice = notification().copy(notification = notification().notification.copy(callType = CallType.VOICE))
        val id = apply(voice, 101).values.single()
        val changed = detector.accept(voice.copy(notification = voice.notification.copy(callType = CallType.VIDEO)), 200)
        val call = changed.filterIsInstance<CallEvent.TypeChanged>().single().call
        assertThrows(IllegalStateException::class.java) {
            runBlocking { writer.apply(changed + CallEvent.Ended(call.copy(sequence = 99), 201), setOf(call.sequence)) }
        }
        assertEquals(CallType.VOICE, database.recordings().session(id)?.callType)
        assertTrue(database.recordings().sessionTypeEvents(id).isEmpty())
        writer.apply(changed, detector.retainedSequences)
        assertEquals(CallType.VIDEO, database.recordings().session(id)?.callType)
        assertEquals(1, database.recordings().sessionTypeEvents(id).size)
    }
}
