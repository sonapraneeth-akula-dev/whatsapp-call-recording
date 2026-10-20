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
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.WritableByteChannel
import java.util.UUID

class SegmentWriterTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun primitive(): StreamingAead {
        StreamingAeadConfig.register()
        return KeysetHandle.generateNew(PredefinedStreamingAeadParameters.AES256_GCM_HKDF_4KB)
            .getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
    }

    @Test fun completeFramesRotateBeforeFiveSecondsAndEachSegmentAuthenticates() {
        val files = RecordingFiles(temporary.newFolder())
        val crypto = CryptoStore(files, primitive())
        val id = UUID.randomUUID()
        val format = AacFormat(1)
        val packet = format.packet(ByteArray(50) { it.toByte() })
        val committed = mutableListOf<CommittedSegment>()
        SegmentWriter(id, crypto, format, committed::add).use { writer -> repeat(160) { writer.write(packet) } }
        assertEquals(listOf(4_992_000L, 4_992_000L, 256_000L), committed.map { it.durationUs })
        assertTrue(committed.all { it.durationUs <= 5_000_000L })
        committed.forEach { segment ->
            val cleartext = Channels.newInputStream(crypto.openReader(id, SegmentWriter.name(segment.index))).use { it.readBytes() }
            assertEquals(segment.plainBytes, cleartext.size.toLong())
            assertEquals(segment.durationUs / AacFormat.FRAME_DURATION_US * packet.size, cleartext.size.toLong())
            for (offset in cleartext.indices step packet.size) {
                assertArrayEquals(packet, cleartext.copyOfRange(offset, offset + packet.size))
            }
        }
    }

    @Test fun idleCloseCreatesNothingAndInvalidFramesDoNotTouchStorage() {
        val root = temporary.newFolder()
        val crypto = CryptoStore(RecordingFiles(root), primitive())
        val writer = SegmentWriter(UUID.randomUUID(), crypto, AacFormat(1)) { fail("No segment expected") }
        assertThrows(IllegalArgumentException::class.java) { writer.write(byteArrayOf()) }
        writer.close()
        writer.close()
        assertFalse(root.walkTopDown().any { it.isFile })
        assertThrows(IllegalStateException::class.java) { writer.write(AacFormat(1).packet(byteArrayOf(1))) }
    }

    @Test fun metadataCallbackFailurePreservesTheDurableSegment() {
        val files = RecordingFiles(temporary.newFolder())
        val crypto = CryptoStore(files, primitive())
        val id = UUID.randomUUID()
        val packet = AacFormat(1).packet(byteArrayOf(1))
        val writer = SegmentWriter(id, crypto, AacFormat(1)) { throw IOException("metadata unavailable") }
        assertThrows(IOException::class.java) { repeat(79) { writer.write(packet) } }
        writer.close()
        assertTrue(files.file(id, SegmentWriter.name(1)).isFile)
        assertFalse(files.file(id, SegmentWriter.name(2)).exists())
        Channels.newInputStream(crypto.openReader(id, SegmentWriter.name(1))).use {
            assertEquals(78 * packet.size, it.readBytes().size)
        }
    }

    @Test fun writeFailureDropsOnlyTheCurrentIncompleteSegment() {
        val delegate = primitive()
        var opened = 0
        val failing = object : StreamingAead by delegate {
            override fun newEncryptingChannel(channel: WritableByteChannel, associatedData: ByteArray): WritableByteChannel {
                val encrypted = delegate.newEncryptingChannel(channel, associatedData)
                opened++
                return if (opened == 1) encrypted else object : WritableByteChannel by encrypted {
                    override fun write(source: ByteBuffer): Int = throw IOException("disk full")
                }
            }
        }
        val files = RecordingFiles(temporary.newFolder())
        val crypto = CryptoStore(files, failing)
        val id = UUID.randomUUID()
        val committed = mutableListOf<CommittedSegment>()
        val writer = SegmentWriter(id, crypto, AacFormat(1), committed::add)
        val packet = AacFormat(1).packet(byteArrayOf(1))
        assertThrows(IOException::class.java) { repeat(79) { writer.write(packet) } }
        writer.close()
        assertEquals(1, committed.size)
        assertTrue(files.file(id, SegmentWriter.name(1)).isFile)
        assertFalse(files.file(id, SegmentWriter.name(2)).exists())
    }
}
