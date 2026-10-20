package com.soakula.wacr.recording

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import androidx.room.Room
import com.soakula.wacr.data.AppDatabase
import com.soakula.wacr.data.CallDirection
import com.soakula.wacr.data.CallSessionEntity
import com.soakula.wacr.data.CallType
import com.soakula.wacr.data.ContactRuleEntity
import com.soakula.wacr.data.RecordingDecision
import com.soakula.wacr.detection.CallDetectionState
import com.soakula.wacr.detection.CallSuspension
import com.soakula.wacr.detection.DetectedCall
import com.soakula.wacr.detection.NameExtractor
import com.soakula.wacr.util.AppLog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class RecordingCoordinatorTest {
    @get:Rule val temporary = TemporaryFolder()
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var database: AppDatabase
    private lateinit var recorder: RecorderController
    private lateinit var coordinator: RecordingCoordinator
    private val launches = mutableListOf<Intent>()
    private var denyLaunch = false

    @Before fun prepare() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        database = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val log = AppLog(temporary.newFolder(), false, {}, { throw it })
        recorder = RecorderController(app, log, {
            if (denyLaunch) throw IllegalStateException("Synthetic foreground denial")
            launches += it
        }, {})
        coordinator = RecordingCoordinator(database, recorder, AskNotification(app, log), log)
    }

    @After fun close() {
        coordinator.close()
        database.close()
    }

    private suspend fun connected(group: Boolean?, sequence: Long = 1): CallDetectionState {
        val id = database.recordings().insertSession(CallSessionEntity(
            waDisplayName = "Synthetic contact", matchKey = "synthetic contact",
            isGroup = group == true, isGroupKnown = group != null, connectedAt = 1000,
            decision = RecordingDecision.IGNORED
        ))
        return CallDetectionState(ready = true, sessionId = id, call = DetectedCall(
            sequence, "com.whatsapp", "synthetic-$sequence", sequence,
            NameExtractor.extract("Synthetic contact"), CallDirection.INCOMING, CallType.VOICE, group, null, 10
        ))
    }

    private suspend fun allowContact() {
        database.contactRules().insert(ContactRuleEntity("Synthetic contact", "synthetic contact", 1000))
    }

    private fun prompt() = shadowOf(app.getSystemService(NotificationManager::class.java))
        .getNotification(AskNotification.ID)

    @Test fun unknownAndPositiveGroupCallsAskEvenWithMatchingContactAndEndCancelsPrompt() = runBlocking {
        allowContact()
        for ((index, group) in listOf(null, true).withIndex()) {
            val state = connected(group, index + 1L)
            coordinator.sync(state, setOf(checkNotNull(state.sessionId)))
            assertNotNull(prompt())
            assertEquals(RecordingDecision.ASKED, database.recordings().session(state.sessionId)?.decision)
            assertEquals(RecorderState.Idle, recorder.state.value)
            coordinator.sync(CallDetectionState(ready = true), emptySet())
            assertNull(prompt())
        }
        assertTrue(launches.isEmpty())
    }

    @Test fun knownAllowlistedCallStartsExactlyOnceAndSignalLossStopsOnlyItsOwnedRequest() = runBlocking {
        allowContact()
        val state = connected(false)
        val id = checkNotNull(state.sessionId)
        repeat(3) { coordinator.sync(state, setOf(id)) }
        val first = checkNotNull(RecorderService.parseId(launches.single()))
        val request = checkNotNull(recorder.request(first))
        assertEquals(id, request.sessionId)
        assertEquals(RecordingDecision.AUTO, database.recordings().session(id)?.decision)
        assertNull(prompt())
        coordinator.sync(state.copy(suspension = CallSuspension.RECONNECTING), setOf(id))
        assertTrue(request.stopRequested.get())
        recorder.finished(request, null)
        coordinator.sync(state.copy(suspension = CallSuspension.RECONNECTING), setOf(id))
        assertEquals(1, launches.size)
        coordinator.sync(state, setOf(id))
        assertEquals(2, launches.size)
        val next = checkNotNull(recorder.request(checkNotNull(RecorderService.parseId(launches.last()))))
        assertEquals(id, next.sessionId)
        coordinator.sync(CallDetectionState(), emptySet())
        assertTrue(next.stopRequested.get())
        assertNull(prompt())
    }

    @Test fun explicitStopPreventsAutomaticRestartIncludingWhenSignalLossRacesStop() = runBlocking {
        allowContact()
        val state = connected(false)
        val id = checkNotNull(state.sessionId)
        coordinator.sync(state, setOf(id))
        val request = checkNotNull(recorder.request(checkNotNull(RecorderService.parseId(launches.single()))))
        recorder.stop()
        coordinator.sync(state.copy(suspension = CallSuspension.HOLD), setOf(id))
        recorder.finished(request, null)
        repeat(3) { coordinator.sync(state, setOf(id)) }
        assertEquals(1, launches.size)
        assertEquals(RecorderState.Idle, recorder.state.value)
        assertNull(prompt())
    }

    @Test fun backgroundDenialFallsBackToAskWithoutRepeatedAutoAttemptsAndExplicitConsentCanRetry() = runBlocking {
        allowContact()
        val state = connected(false)
        val id = checkNotNull(state.sessionId)
        denyLaunch = true
        coordinator.sync(state, setOf(id))
        assertNotNull(prompt())
        assertTrue(recorder.state.value is RecorderState.Failed)
        denyLaunch = false
        repeat(3) { coordinator.sync(state, setOf(id)) }
        assertTrue(launches.isEmpty())
        assertEquals(CallRecordingResult.NotVisible, coordinator.confirm(id, RecordingDecision.ASKED) { false })
        val result = coordinator.confirm(id, RecordingDecision.ASKED) { true }
        assertTrue(result is CallRecordingResult.Started)
        assertEquals(1, launches.size)
        assertNull(prompt())
        assertEquals(RecordingDecision.ASKED, database.recordings().session(id)?.decision)
    }

    @Test fun confirmationRejectsStaleHeldBusyAndHiddenEntriesAndPreservesManualDecisionOnResume() = runBlocking {
        val state = connected(null)
        val id = checkNotNull(state.sessionId)
        coordinator.sync(state, setOf(id))
        assertEquals(CallRecordingResult.Expired, coordinator.confirm(id + 1, RecordingDecision.ASKED) { true })
        coordinator.sync(state.copy(suspension = CallSuspension.HOLD), setOf(id))
        assertEquals(CallRecordingResult.Expired, coordinator.confirm(id, RecordingDecision.ASKED) { true })
        coordinator.sync(state, setOf(id))
        assertEquals(CallRecordingResult.NotVisible, coordinator.confirm(id, RecordingDecision.MANUAL) { false })
        val first = coordinator.confirm(id, RecordingDecision.MANUAL) { true } as CallRecordingResult.Started
        assertEquals(CallRecordingResult.Busy, coordinator.confirm(id, RecordingDecision.ASKED) { true })
        val request = checkNotNull(recorder.request(first.id))
        coordinator.sync(state.copy(suspension = CallSuspension.RECONNECTING), setOf(id))
        recorder.finished(request, null)
        coordinator.sync(state, setOf(id))
        assertEquals(2, launches.size)
        assertEquals(RecordingDecision.MANUAL, database.recordings().session(id)?.decision)
    }

    @Test fun heldCallAndSecondCallNeverCaptureConcurrentlyOrStopAnUnrelatedManualRequest() = runBlocking {
        allowContact()
        val first = connected(false)
        val second = connected(false, 2)
        val retained = setOf(checkNotNull(first.sessionId), checkNotNull(second.sessionId))
        coordinator.sync(first, retained)
        val previous = checkNotNull(recorder.request(checkNotNull(RecorderService.parseId(launches.single()))))
        coordinator.sync(second, retained)
        assertTrue(previous.stopRequested.get())
        assertEquals(1, launches.size)
        recorder.saving(previous)
        coordinator.sync(second, retained)
        assertEquals(1, launches.size)
        recorder.finished(previous, null)
        coordinator.sync(second, retained)
        assertEquals(2, launches.size)
        coordinator.close()
        val owned = checkNotNull(recorder.request(checkNotNull(RecorderService.parseId(launches.last()))))
        recorder.finished(owned, null)
        val unrelated = checkNotNull(recorder.request(checkNotNull(recorder.start())))
        coordinator.sync(CallDetectionState(), emptySet())
        assertFalse(unrelated.stopRequested.get())
    }
}
