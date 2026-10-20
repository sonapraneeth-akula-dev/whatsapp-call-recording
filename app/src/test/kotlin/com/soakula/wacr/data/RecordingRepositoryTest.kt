package com.soakula.wacr.data

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import com.soakula.wacr.recording.CommittedSegment
import com.soakula.wacr.recording.FinalizedAudio
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, manifest = Config.NONE)
class RecordingRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: RecordingRepository

    @Before fun createDatabase() {
        // Only test databases are plaintext, in memory, with synthetic contacts.
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).build()
        repository = RecordingRepository(database)
    }

    @After fun closeDatabase() { database.close() }

    @Test fun libraryObservesSessionFinalizationAndDeletionWithoutLosingManualRecordings() = runBlocking {
        val updates = Channel<List<RecordingWithSession>>(Channel.UNLIMITED)
        val observer = launch { repository.observeAll().collect { updates.send(it) } }
        suspend fun next() = withTimeout(10_000) { updates.receive() }
        val id = UUID.randomUUID()
        try {
            assertTrue(next().isEmpty())
            repository.start(
                RecordingEntity(id, 1000, 1, "MIC"),
                CallSessionEntity(waDisplayName = "Synthetic", direction = CallDirection.INCOMING)
            )
            val active = next().single()
            assertEquals(id, active.recording.id)
            assertEquals(RecordingStatus.RECORDING, active.recording.status)
            assertEquals("Synthetic", active.session?.waDisplayName)
            assertEquals(CallDirection.INCOMING, active.session?.direction)
            repository.finalize(id, FinalizedAudio(64_000, 20, 80))
            val finalized = next().single()
            assertEquals(RecordingStatus.FINALIZED, finalized.recording.status)
            assertEquals(64L, finalized.recording.durationMs)
            assertNull(finalized.recording.remoteCaptured)
            database.recordings().deleteSession(checkNotNull(active.session?.id))
            val manual = next().single()
            assertEquals(id, manual.recording.id)
            assertNull(manual.recording.sessionId)
            assertNull(manual.session)
            database.recordings().deleteRecording(id)
            assertTrue(next().isEmpty())
        } finally {
            observer.cancelAndJoin()
            updates.close()
        }
    }

    @Test fun libraryIsNewestFirstWithStableTiesAndRetainsRecoveredAndFailedRows() = runBlocking {
        val times = listOf(1000L, 8000L, 4000L, 8000L, 6000L, 2000L, 3000L)
        val ids = times.indices.map { UUID(0, it.toLong() + 1) }
        times.forEachIndexed { index, time ->
            repository.start(RecordingEntity(ids[index], time, 1, "MIC"))
        }
        repository.finalize(ids[1], FinalizedAudio(64_000, 20, 80), recovered = true)
        repository.failEmptyRecording(ids[3])
        val rows = repository.observeAll().first()
        assertEquals(listOf(1, 3, 4, 2, 6, 5, 0).map(ids::get), rows.map { it.recording.id })
        assertEquals(RecordingStatus.RECOVERED, rows[0].recording.status)
        assertEquals(RecordingStatus.FAILED, rows[1].recording.status)
        assertNull(rows[1].recording.audioPath)
        assertTrue(rows.all { it.session == null })
        assertEquals(rows.take(5), repository.observeRecent().first())
    }

    @Test fun recentRecordingsObserveNewEntriesAndNeverExceedFive() = runBlocking {
        val ids = (1..6).map { UUID(0, it.toLong()) }
        ids.take(5).forEachIndexed { index, id ->
            repository.start(RecordingEntity(id, index.toLong(), 1, "MIC"))
        }
        val updates = Channel<List<RecordingWithSession>>(Channel.UNLIMITED)
        val observer = launch { repository.observeRecent().collect { updates.send(it) } }
        try {
            assertEquals(ids.take(5).reversed(), withTimeout(10_000) {
                updates.receive().map { it.recording.id }
            })
            repository.start(RecordingEntity(ids.last(), 6, 1, "MIC"))
            assertEquals(ids.takeLast(5).reversed(), withTimeout(10_000) {
                updates.receive().map { it.recording.id }
            })
            assertEquals(6, repository.observeAll().first().size)
        } finally {
            observer.cancelAndJoin()
            updates.close()
        }
    }

    @Test fun storesMetadataAndKeepsCleanupRecoverableAcrossFinalization() = runBlocking {
        val id = UUID.randomUUID()
        val dao = database.recordings()
        repository.start(
            RecordingEntity(id, 1000, 1, "VOICE_RECOGNITION"),
            CallSessionEntity(waDisplayName = "Synthetic", direction = CallDirection.OUTGOING, connectedAt = 1000)
        )
        val recording = checkNotNull(dao.recording(id))
        assertEquals("Synthetic", dao.session(checkNotNull(recording.sessionId))?.waDisplayName)
        repository.commitSegment(id, CommittedSegment(1, 64_000, 20), 1064)
        dao.insertRoute(RouteEventEntity(id, 0, AudioRoute.SPEAKER))
        assertEquals(AudioRoute.SPEAKER, dao.routes(id).single().route)
        assertEquals(64_000L, dao.segments(id).single().durationUs)
        val result = FinalizedAudio(64_000, 20, 80)
        repository.finalize(id, result, recovered = true)
        repository.finalize(id, result)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.finalize(id, result.copy(encryptedBytes = 81)) }
        }
        assertEquals(RecordingStatus.RECOVERED, dao.recording(id)?.status)
        assertEquals("$id/audio.aac.enc", dao.recording(id)?.audioPath)
        assertEquals(id, dao.needsRecovery().single().id)
        repository.completeCleanup(id)
        assertTrue(dao.needsRecovery().isEmpty())
        assertTrue(dao.segments(id).isEmpty())
    }

    @Test fun rejectsGapsAndRollsBackSessionWhenRecordingInsertFails() = runBlocking {
        val id = UUID.randomUUID()
        val recording = RecordingEntity(id, 1000, 1, "MIC")
        repository.start(recording)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.commitSegment(id, CommittedSegment(2, 64_000, 20), 1064) }
        }
        assertTrue(database.recordings().segments(id).isEmpty())
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { repository.start(recording, CallSessionEntity(waDisplayName = "Rollback")) }
        }
        assertNull(database.recordings().session(1))
    }

    @Test fun foreignKeysRetainRecordingOnSessionDeletionAndCascadeRecordingChildren() = runBlocking {
        val id = UUID.randomUUID()
        val dao = database.recordings()
        repository.start(RecordingEntity(id, 1000, 1, "MIC"), CallSessionEntity())
        repository.commitSegment(id, CommittedSegment(1, 64_000, 20), 1064)
        dao.insertRoute(RouteEventEntity(id, 0, AudioRoute.EARPIECE))
        dao.deleteSession(checkNotNull(dao.recording(id)?.sessionId))
        assertNotNull(dao.recording(id))
        assertNull(dao.recording(id)?.sessionId)
        dao.deleteRecording(id)
        assertTrue(dao.segments(id).isEmpty())
        assertTrue(dao.routes(id).isEmpty())
    }

    @Test fun routeTimelineIsOrderedDeduplicatedAndWritableOnlyWhileRecording() = runBlocking {
        val id = UUID.randomUUID()
        repository.start(RecordingEntity(id, 1000, 1, "VOICE_RECOGNITION"))
        repository.recordRoute(id, AudioRoute.EARPIECE, 0)
        repository.recordRoute(id, AudioRoute.EARPIECE, 10)
        repository.recordRoute(id, AudioRoute.BLUETOOTH, 20)
        assertEquals(listOf(0L, 20L), database.recordings().routes(id).map { it.atMs })
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.recordRoute(id, AudioRoute.USB, -1) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.recordRoute(id, AudioRoute.USB, 19) } }
        repository.finalize(id, FinalizedAudio(64_000, 20, 80))
        assertThrows(IllegalStateException::class.java) { runBlocking { repository.recordRoute(id, AudioRoute.USB, 30) } }
        assertEquals(2, database.recordings().routes(id).size)
    }
}
