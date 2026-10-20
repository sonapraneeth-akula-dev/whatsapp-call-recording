package com.soakula.wacr.recording

import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.soakula.wacr.crypto.CryptoStore
import com.soakula.wacr.data.RecordingFiles
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.channels.Channels
import java.util.UUID

class FinalizerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val format = AacFormat(1)

    private fun crypto(files: RecordingFiles): CryptoStore {
        StreamingAeadConfig.register()
        val primitive = KeysetHandle.generateNew(PredefinedStreamingAeadParameters.AES256_GCM_HKDF_4KB)
            .getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
        return CryptoStore(files, primitive)
    }

    private fun segments(id: UUID, crypto: CryptoStore): List<CommittedSegment> {
        val committed = mutableListOf<CommittedSegment>()
        SegmentWriter(id, crypto, format, committed::add).use { writer ->
            repeat(90) { writer.write(format.packet(byteArrayOf(it.toByte()))) }
        }
        return committed
    }

    @Test fun mergeAuthenticatesAndPreservesFrameOrderWithoutPlaintextFiles() {
        val root = temporary.newFolder()
        val files = RecordingFiles(root)
        val crypto = crypto(files)
        val id = UUID.randomUUID()
        val segments = segments(id, crypto)
        var metadataWritten = false
        val finalizer = Finalizer(files, crypto, format) { actualId, result ->
            assertEquals(id, actualId)
            assertEquals(90 * AacFormat.FRAME_DURATION_US, result.durationUs)
            if (!metadataWritten) assertTrue(files.file(id, SegmentWriter.name(1)).exists())
            metadataWritten = true
        }
        val result = finalizer.finish(id, segments)
        assertTrue(metadataWritten)
        assertEquals(720L, result.plainBytes)
        Channels.newInputStream(crypto.openReader(id, "audio.aac.enc")).use { input ->
            repeat(90) { assertArrayEquals(format.packet(byteArrayOf(it.toByte())), format.readFrame(input)) }
            assertNull(format.readFrame(input))
        }
        assertEquals(listOf(files.file(id, "audio.aac.enc")), root.walkTopDown().filter { it.isFile }.toList())
        finalizer.finish(id, segments)
    }

    @Test fun metadataFailureCanBeRetriedWithoutLosingSegmentsOrOverwritingFinalAudio() {
        val files = RecordingFiles(temporary.newFolder())
        val crypto = crypto(files)
        val id = UUID.randomUUID()
        val segments = segments(id, crypto)
        assertThrows(IOException::class.java) {
            Finalizer(files, crypto, format) { _, _ -> throw IOException("database full") }.finish(id, segments)
        }
        val before = files.file(id, "audio.aac.enc").readBytes()
        assertTrue(files.file(id, SegmentWriter.name(1)).isFile)
        crypto.delete(id, SegmentWriter.name(1))
        Finalizer(files, crypto, format) { _, _ -> }.finish(id, segments)
        assertArrayEquals(before, files.file(id, "audio.aac.enc").readBytes())
        assertFalse(files.file(id, SegmentWriter.name(1)).exists())
    }

    @Test fun existingFinalWithMatchingSizeButDifferentAudioNeverDeletesSources() {
        val files = RecordingFiles(temporary.newFolder())
        val crypto = crypto(files)
        val id = UUID.randomUUID()
        val segments = segments(id, crypto)
        Channels.newOutputStream(crypto.openWriter(id, "audio.aac.enc")).use { output ->
            repeat(90) { output.write(format.packet(byteArrayOf(99))) }
        }
        val before = files.file(id, "audio.aac.enc").readBytes()
        assertThrows(IOException::class.java) {
            Finalizer(files, crypto, format) { _, _ -> fail("Must retain sources") }.finish(id, segments)
        }
        assertArrayEquals(before, files.file(id, "audio.aac.enc").readBytes())
        assertTrue(files.file(id, SegmentWriter.name(1)).isFile)
        assertTrue(files.file(id, SegmentWriter.name(2)).isFile)
    }

    @Test fun corruptOrMismatchedSourcesPreserveAllOriginalFiles() {
        val files = RecordingFiles(temporary.newFolder())
        val crypto = crypto(files)
        val id = UUID.randomUUID()
        val segments = segments(id, crypto)
        val finalizer = Finalizer(files, crypto, format) { _, _ -> fail("No final result expected") }
        assertThrows(IOException::class.java) { finalizer.finish(id, segments.map { it.copy(plainBytes = it.plainBytes + 1) }) }
        val source = files.file(id, SegmentWriter.name(2))
        source.writeBytes(source.readBytes().dropLast(1).toByteArray())
        assertThrows(IOException::class.java) { finalizer.finish(id, segments) }
        assertTrue(files.file(id, SegmentWriter.name(1)).isFile)
        assertTrue(source.isFile)
        assertFalse(files.file(id, "audio.aac.enc").exists())
        assertThrows(IllegalArgumentException::class.java) { finalizer.finish(id, segments.reversed()) }
    }
}
