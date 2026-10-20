package com.soakula.wacr.recording

import android.app.Application
import androidx.room.Room
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.soakula.wacr.crypto.CryptoStore
import com.soakula.wacr.data.*
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
import org.robolectric.annotation.Config
import java.io.IOException
import java.nio.channels.Channels
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, manifest = Config.NONE)
class RecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var database: AppDatabase
    private lateinit var repository: RecordingRepository
    private lateinit var files: RecordingFiles
    private lateinit var crypto: CryptoStore
    private lateinit var recovery: Recovery
    private val format = AacFormat(1)

    @Before fun createStores() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).build()
        repository = RecordingRepository(database)
        files = RecordingFiles(temporary.newFolder())
        StreamingAeadConfig.register()
        crypto = CryptoStore(files, KeysetHandle.generateNew(PredefinedStreamingAeadParameters.AES256_GCM_HKDF_4KB)
            .getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java))
        val log = AppLog(temporary.newFolder(), false, {}, { throw it })
        recovery = Recovery(database, files, crypto, log)
    }

    @After fun closeStores() { database.close() }

    private suspend fun writeRecording(frames: Int, failMetadataOn: Int? = null): UUID {
        val id = UUID.randomUUID()
        repository.start(RecordingEntity(id, 1000, 1, "VOICE_RECOGNITION"))
        val writer = SegmentWriter(id, crypto, format) {
            if (it.index == failMetadataOn) throw IOException("synthetic metadata failure")
            runBlocking { repository.commitSegment(id, it, 2000) }
        }
        if (failMetadataOn == null) {
            writer.use { repeat(frames) { frame -> it.write(format.packet(byteArrayOf(frame.toByte()))) } }
        } else {
            assertThrows(IOException::class.java) {
                writer.use { repeat(frames) { frame -> it.write(format.packet(byteArrayOf(frame.toByte()))) } }
            }
        }
        return id
    }

    @Test fun dropsOnlyUnauthenticatedTailAndRecoversEarlierCompleteFrames() = runBlocking {
        val id = writeRecording(90)
        val tail = files.file(id, SegmentWriter.name(2))
        tail.writeBytes(tail.readBytes().dropLast(1).toByteArray())
        assertEquals(RecoveryReport(1, 0, 0), recovery.run())
        val row = checkNotNull(database.recordings().recording(id))
        assertEquals(RecordingStatus.RECOVERED, row.status)
        assertEquals(4992L, row.durationMs)
        assertTrue(files.segmentIndices(id).isEmpty())
        assertTrue(database.recordings().segments(id).isEmpty())
        Channels.newInputStream(crypto.openReader(id, "audio.aac.enc")).use { input ->
            repeat(78) { assertArrayEquals(format.packet(byteArrayOf(it.toByte())), format.readFrame(input)) }
            assertNull(format.readFrame(input))
        }
        assertEquals(RecoveryReport(0, 0, 0), recovery.run())
    }

    @Test fun adoptsClosedSegmentWhoseMetadataCommitWasInterrupted() = runBlocking {
        val id = writeRecording(90, failMetadataOn = 2)
        assertEquals(1, database.recordings().segments(id).size)
        assertEquals(RecoveryReport(1, 0, 0), recovery.run())
        assertEquals(5760L, database.recordings().recording(id)?.durationMs)
        assertTrue(files.segmentIndices(id).isEmpty())
    }

    @Test fun resumesCleanupAfterMetadataCommitAndPartialFileDeletion() = runBlocking {
        val id = writeRecording(90)
        val segments = database.recordings().segments(id).map { CommittedSegment(it.index, it.durationUs, it.plainBytes) }
        assertThrows(IOException::class.java) {
            Finalizer(files, crypto, format) { _, audio ->
                runBlocking { repository.finalize(id, audio) }
                throw IOException("synthetic crash after metadata commit")
            }.finish(id, segments)
        }
        val before = files.file(id, "audio.aac.enc").readBytes()
        crypto.delete(id, SegmentWriter.name(1))
        assertEquals(RecoveryReport(1, 0, 0), recovery.run())
        assertArrayEquals(before, files.file(id, "audio.aac.enc").readBytes())
        assertEquals(RecordingStatus.FINALIZED, database.recordings().recording(id)?.status)
        assertTrue(database.recordings().needsRecovery().isEmpty())
    }

    @Test fun nonTailCorruptionAndMissingFilesNeverDiscardOtherAudio() = runBlocking {
        val corrupt = writeRecording(160)
        val middle = files.file(corrupt, SegmentWriter.name(2))
        middle.writeBytes(middle.readBytes().dropLast(1).toByteArray())
        val missing = writeRecording(90)
        crypto.delete(missing, SegmentWriter.name(2))
        val intact = writeRecording(10)
        assertEquals(RecoveryReport(1, 0, 2), recovery.run())
        assertEquals(listOf(1, 2, 3), files.segmentIndices(corrupt))
        assertTrue(files.file(missing, SegmentWriter.name(1)).exists())
        assertFalse(files.file(corrupt, "audio.aac.enc").exists())
        assertEquals(RecordingStatus.RECORDING, database.recordings().recording(corrupt)?.status)
        assertEquals(RecordingStatus.RECOVERED, database.recordings().recording(intact)?.status)
    }

    @Test fun emptyInterruptedRecordingBecomesFailedWithoutFabricatingAudio() = runBlocking {
        val id = UUID.randomUUID()
        repository.start(RecordingEntity(id, 1000, 1, "MIC"))
        assertEquals(RecoveryReport(0, 1, 0), recovery.run())
        assertEquals(RecordingStatus.FAILED, database.recordings().recording(id)?.status)
        assertEquals("NO_AUTHENTICATED_AUDIO", database.recordings().recording(id)?.fallbackReason)
        assertFalse(files.file(id, "audio.aac.enc").exists())
    }

    @Test fun authenticatedButOversizedTailIsPreservedInsteadOfClassifiedAsCorrupt() = runBlocking {
        val id = writeRecording(78)
        Channels.newOutputStream(crypto.openWriter(id, SegmentWriter.name(2))).use { output ->
            repeat(79) { output.write(format.packet(byteArrayOf(1))) }
        }
        val before = files.file(id, SegmentWriter.name(2)).readBytes()
        assertEquals(RecoveryReport(0, 0, 1), recovery.run())
        assertArrayEquals(before, files.file(id, SegmentWriter.name(2)).readBytes())
        assertEquals(listOf(1, 2), files.segmentIndices(id))
        assertFalse(files.file(id, "audio.aac.enc").exists())
    }
}
