package com.soakula.wacr.recording

import com.soakula.wacr.crypto.CryptoStore
import com.soakula.wacr.data.RecordingFiles
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SeekableByteChannel
import java.nio.channels.WritableByteChannel
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

data class FinalizedAudio(val durationUs: Long, val plainBytes: Long, val encryptedBytes: Long)

class Finalizer(
    private val files: RecordingFiles,
    private val crypto: CryptoStore,
    private val format: AacFormat,
    private val onFinalized: (UUID, FinalizedAudio) -> Unit
) {
    /** onFinalized must durably commit metadata before returning; failure retains the segments. */
    fun finish(recordingId: UUID, segments: List<CommittedSegment>): FinalizedAudio = synchronized(lock) {
        require(segments.isNotEmpty()) { "No committed audio segments" }
        segments.forEachIndexed { index, segment ->
            require(segment.index == index + 1) { "Segments must be contiguous and ordered" }
            require(segment.durationUs in 1..SegmentWriter.MAX_DURATION_US &&
                segment.durationUs % AacFormat.FRAME_DURATION_US == 0L) { "Invalid segment duration" }
            val frames = segment.durationUs / AacFormat.FRAME_DURATION_US
            require(segment.plainBytes in frames * 8..frames * 8191) { "Invalid segment size" }
        }
        val expectedBytes = segments.sumOf { it.plainBytes }
        val expectedDuration = segments.sumOf { it.durationUs }
        val destination = files.file(recordingId, "audio.aac.enc")
        if (!destination.exists()) {
            val digest = MessageDigest.getInstance("SHA-256")
            for (segment in segments) {
                val content = inspect(crypto.openReader(recordingId, SegmentWriter.name(segment.index))) { digest.update(it) }
                if (content.bytes != segment.plainBytes || content.durationUs != segment.durationUs) {
                    throw IOException("Segment metadata does not match authenticated audio")
                }
            }
            val expectedHash = digest.digest()
            // Sources are fully authenticated before replacing a stale encrypted pending file.
            crypto.delete(recordingId, "audio.pending.enc")
            crypto.openPendingAudioWriter(recordingId).use { output ->
                segments.forEach { segment ->
                    inspect(crypto.openReader(recordingId, SegmentWriter.name(segment.index))) { writeFrame(output, it) }
                }
            }
            val pending = inspect(crypto.openPendingAudioReader(recordingId))
            if (pending.bytes != expectedBytes || pending.durationUs != expectedDuration ||
                !MessageDigest.isEqual(expectedHash, pending.hash)) {
                throw IOException("Final audio verification failed; segments retained")
            }
            crypto.publishAudio(recordingId)
        }
        val finalized = inspect(crypto.openReader(recordingId, "audio.aac.enc"))
        if (finalized.bytes != expectedBytes || finalized.durationUs != expectedDuration) {
            throw IOException("Existing final audio does not match segment metadata")
        }
        verifyRemainingSources(recordingId, segments)
        val result = FinalizedAudio(expectedDuration, expectedBytes, Files.size(destination.toPath()))
        onFinalized(recordingId, result)
        segments.forEach { crypto.delete(recordingId, SegmentWriter.name(it.index)) }
        crypto.delete(recordingId, "audio.pending.enc")
        result
    }

    private fun verifyRemainingSources(recordingId: UUID, segments: List<CommittedSegment>) {
        crypto.openReader(recordingId, "audio.aac.enc").use { audio ->
            var offset = 0L
            for (segment in segments) {
                if (files.file(recordingId, SegmentWriter.name(segment.index)).exists()) {
                    audio.position(offset)
                    val content = inspect(crypto.openReader(recordingId, SegmentWriter.name(segment.index))) { frame ->
                        val actual = ByteBuffer.allocate(frame.size)
                        while (actual.hasRemaining()) {
                            if (audio.read(actual) <= 0) throw IOException("Cannot verify final audio against source")
                        }
                        if (!frame.contentEquals(actual.array())) throw IOException("Final audio differs from source")
                    }
                    if (content.bytes != segment.plainBytes || content.durationUs != segment.durationUs) {
                        throw IOException("Segment metadata does not match authenticated audio")
                    }
                }
                offset += segment.plainBytes
            }
        }
    }

    private fun inspect(channel: SeekableByteChannel, consume: (ByteArray) -> Unit = {}): AacContent =
        Channels.newInputStream(channel).use { format.inspect(it, consume) }

    private fun writeFrame(output: WritableByteChannel, frame: ByteArray) {
        val buffer = ByteBuffer.wrap(frame)
        while (buffer.hasRemaining()) {
            if (output.write(buffer) == 0) throw IOException("Final audio writer made no progress")
        }
    }

    companion object {
        // ponytail: one finalization lock; per-recording locks if parallel finalization matters.
        private val lock = Any()
    }
}
