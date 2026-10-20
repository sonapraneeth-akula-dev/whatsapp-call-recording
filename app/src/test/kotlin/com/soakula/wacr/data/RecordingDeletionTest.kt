package com.soakula.wacr.data

import android.app.Application
import android.app.job.JobScheduler
import android.provider.Settings
import androidx.room.Room
import com.soakula.wacr.recording.CommittedSegment
import com.soakula.wacr.recording.DeletionJobService
import com.soakula.wacr.recording.FinalizedAudio
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, manifest = Config.NONE)
class RecordingDeletionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val application: Application get() = RuntimeEnvironment.getApplication()
    private val scheduler: JobScheduler get() = checkNotNull(application.getSystemService(JobScheduler::class.java))
    private lateinit var database: AppDatabase
    private lateinit var repository: RecordingRepository
    private lateinit var files: RecordingFiles
    private lateinit var deletion: RecordingDeletion
    private var time = 1000L

    @Before fun prepare() {
        // Synthetic local test database only; production still opens through SQLCipher.
        application.deleteDatabase(NAME)
        Settings.Global.putInt(application.contentResolver, Settings.Global.BOOT_COUNT, 1)
        scheduler.cancelAll()
        files = RecordingFiles(temporary.newFolder())
        openDatabase()
    }

    private fun openDatabase() {
        database = Room.databaseBuilder(application, AppDatabase::class.java, NAME).build()
        repository = RecordingRepository(database)
        deletion = RecordingDeletion(application, database, files) { time }
    }

    @After fun close() {
        database.close()
        application.deleteDatabase(NAME)
    }

    private suspend fun recording(id: UUID = UUID.randomUUID()): UUID {
        repository.start(RecordingEntity(id, 1000, 1, "MIC"), CallSessionEntity(waDisplayName = "Synthetic"))
        repository.commitSegment(id, CommittedSegment(1, 64_000, 20), 1064)
        repository.recordRoute(id, AudioRoute.EARPIECE, 0)
        repository.finalize(id, FinalizedAudio(64_000, 20, 80))
        for (name in listOf("audio.aac.enc", "audio.pending.enc", "seg_00001.enc", "video.mp4.enc")) {
            val artifact = files.file(id, name)
            check(artifact.parentFile!!.isDirectory || artifact.parentFile!!.mkdirs())
            artifact.writeBytes(ByteArray(80) { 19 })
        }
        return id
    }

    @Test fun hidesImmediatelyAndUndoAt4999MillisecondsRestoresUnchangedFilesAndMetadata() = runBlocking {
        val id = recording()
        val before = checkNotNull(database.recordings().recording(id))
        val updates = Channel<List<RecordingWithSession>>(Channel.UNLIMITED)
        val observer = launch { repository.observeAll().collect { updates.send(it) } }
        suspend fun next() = withTimeout(10_000) { updates.receive() }
        try {
            assertEquals(id, next().single().recording.id)
            val ticket = deletion.request(id)
            assertEquals(6000L, ticket.undoUntilElapsedMs)
            assertTrue(next().isEmpty())
            assertTrue(repository.observeRecent().first().isEmpty())
            assertTrue(database.recordings().needsRecovery().isEmpty())
            assertEquals(before, database.recordings().recording(id))
            assertThrows(IllegalStateException::class.java) { runBlocking { repository.rename(id, "Hidden") } }
            val job = checkNotNull(scheduler.getPendingJob(DeletionJobService.JOB_ID))
            assertEquals(5000L, job.minLatencyMillis)
            assertTrue(job.isPersisted)
            assertNull(job.requiredNetwork)
            time = 5999
            assertEquals(1L, deletion.purgeDue())
            deletion.undo(ticket)
            assertEquals(before, next().single().recording)
            assertEquals(id, database.recordings().needsRecovery().single().id)
            assertArrayEquals(ByteArray(80) { 19 }, files.file(id, "audio.aac.enc").readBytes())
            assertNull(database.recordings().deletion(id))
        } finally {
            observer.cancelAndJoin()
            updates.close()
        }
    }

    @Test fun exactFiveSecondBoundaryRejectsUndoAndDeletesOnlyTheRequestedRecordingAndChildren() = runBlocking {
        val id = recording()
        val other = recording()
        val ticket = deletion.request(id)
        time = 6000
        assertNull(deletion.pendingUndo())
        assertThrows(IllegalStateException::class.java) { runBlocking { deletion.undo(ticket) } }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { deletion.undo(ticket.copy(undoUntilElapsedMs = Long.MAX_VALUE)) }
        }

        assertNull(deletion.purgeDue())
        assertNull(database.recordings().recording(id))
        assertTrue(database.recordings().segments(id).isEmpty())
        assertTrue(database.recordings().routes(id).isEmpty())
        assertNull(database.recordings().deletion(id))
        assertFalse(files.file(id, "audio.aac.enc").parentFile!!.exists())
        assertNotNull(database.recordings().recording(other))
        assertTrue(files.file(other, "audio.aac.enc").isFile)
        assertEquals(other, repository.observeAll().first().single().recording.id)
    }

    @Test fun restoresOnlyLiveSameBootUncommittedUndoTickets() = runBlocking {
        assertNull(deletion.pendingUndo())
        val first = deletion.request(recording())
        time++
        val second = deletion.request(recording())
        assertEquals(second, deletion.pendingUndo())
        deletion.undo(second)
        assertEquals(first, deletion.pendingUndo())
        Settings.Global.putInt(application.contentResolver, Settings.Global.BOOT_COUNT, 2)
        assertNull(deletion.pendingUndo())
        Settings.Global.putInt(application.contentResolver, Settings.Global.BOOT_COUNT, 1)
        database.recordings().commitDeletion(first.recordingId, first.requestId)
        assertNull(deletion.pendingUndo())
    }

    @Test fun durableRequestSurvivesDatabaseReopenAndCleanupIsIdempotentAcrossConcurrentWorkers() = runBlocking {
        val id = recording()
        val ticket = deletion.request(id)
        database.close()
        openDatabase()
        assertEquals(ticket, database.recordings().deletion(id))
        assertTrue(repository.observeAll().first().isEmpty())
        time = 6000
        val second = RecordingDeletion(application, database, files) { time }
        assertEquals(listOf(null, null), awaitAll(async { deletion.purgeDue() }, async { second.purgeDue() }))
        assertNull(deletion.purgeDue())
        assertFalse(files.file(id, "audio.aac.enc").parentFile!!.exists())
    }

    @Test fun unsafeArtifactRetainsDurableClaimAndCanNeverBeUndoneAfterCleanupStarts() = runBlocking {
        val id = recording()
        val ticket = deletion.request(id)
        val unexpected = File(files.file(id, "audio.aac.enc").parentFile, "unexpected.txt")
        unexpected.writeText("Synthetic unexpected artifact")
        time = 6000
        assertThrows(IllegalArgumentException::class.java) { runBlocking { deletion.purgeDue() } }
        assertTrue(checkNotNull(database.recordings().deletion(id)).committed)
        assertNotNull(database.recordings().recording(id))
        assertArrayEquals(ByteArray(80) { 19 }, files.file(id, "audio.aac.enc").readBytes())
        database.close()
        openDatabase()
        time = 0
        assertThrows(IllegalStateException::class.java) { runBlocking { deletion.undo(ticket) } }
        check(unexpected.delete())
        assertNull(deletion.purgeDue())
        assertNull(database.recordings().recording(id))
    }

    @Test fun interruptedPartialAndCompletedFileCleanupResumeFromClaimsWithoutRestoringLostAudio() = runBlocking {
        val partial = recording()
        val removed = recording()
        val tickets = listOf(deletion.request(partial), deletion.request(removed))
        for (ticket in tickets) {
            assertEquals(1, database.recordings().commitDeletion(ticket.recordingId, ticket.requestId))
        }
        check(files.file(partial, "seg_00001.enc").delete())
        files.deleteRecording(removed)
        database.close()
        openDatabase()
        time = 0
        for (ticket in tickets) {
            assertThrows(IllegalStateException::class.java) { runBlocking { deletion.undo(ticket) } }
        }
        assertNull(deletion.purgeDue())
        assertTrue(database.recordings().pendingDeletions().isEmpty())
        assertNull(database.recordings().recording(partial))
        assertNull(database.recordings().recording(removed))
        assertFalse(files.file(partial, "audio.aac.enc").parentFile!!.exists())
    }

    @Test fun rebootExpiresTheOldBootWindowWithoutWaitingForAnUnrelatedUptimeOrWallClock() = runBlocking {
        val id = recording()
        val ticket = deletion.request(id)
        Settings.Global.putInt(application.contentResolver, Settings.Global.BOOT_COUNT, 2)
        time = 0
        assertThrows(IllegalStateException::class.java) { runBlocking { deletion.undo(ticket) } }
        assertNull(deletion.purgeDue())
        assertNull(database.recordings().recording(id))
    }

    @Test fun staleUndoCannotRestoreASecondDeletionAndDuplicateRequestsDoNotExtendTheWindow() = runBlocking {
        val id = recording()
        val first = deletion.request(id)
        deletion.undo(first)
        val second = deletion.request(id)
        assertNotEquals(first.requestId, second.requestId)
        assertThrows(IllegalStateException::class.java) { runBlocking { deletion.undo(first) } }
        assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
            runBlocking { deletion.request(id) }
        }
        assertEquals(second, database.recordings().deletion(id))
        deletion.undo(second)
    }

    @Test fun schedulingFailureRollsBackTheIntentInsteadOfHidingAnUnscheduledDeletion() = runBlocking {
        val id = recording()
        shadowOf(scheduler).failOnJob(DeletionJobService.JOB_ID)
        assertThrows(IOException::class.java) { runBlocking { deletion.request(id) } }
        assertNull(database.recordings().deletion(id))
        assertEquals(id, repository.observeAll().first().single().recording.id)
        assertTrue(files.file(id, "audio.aac.enc").isFile)
        Unit
    }

    @Test fun activeMissingOrInvalidClockRequestsNeverCreateADestructiveIntent() = runBlocking {
        val id = UUID.randomUUID()
        repository.start(RecordingEntity(id, 1000, 1, "MIC"))
        assertThrows(IllegalStateException::class.java) { runBlocking { deletion.request(id) } }
        assertThrows(IllegalStateException::class.java) { runBlocking { deletion.request(UUID.randomUUID()) } }
        val finished = recording()
        for (invalid in listOf(-1L, Long.MAX_VALUE)) {
            time = invalid
            assertThrows(IllegalArgumentException::class.java) { runBlocking { deletion.request(finished) } }
        }
        time = 1000
        Settings.Global.putString(application.contentResolver, Settings.Global.BOOT_COUNT, null)
        assertThrows(IllegalStateException::class.java) { runBlocking { deletion.request(finished) } }
        assertTrue(database.recordings().pendingDeletions().isEmpty())
        assertTrue(scheduler.allPendingJobs.isEmpty())
    }

    private companion object { const val NAME = "synthetic-recording-deletion.db" }
}
