package com.soakula.wacr.data

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import com.soakula.wacr.recording.CommittedSegment
import com.soakula.wacr.recording.FinalizedAudio
import kotlinx.coroutines.runBlocking
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
