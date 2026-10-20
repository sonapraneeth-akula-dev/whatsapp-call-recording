package com.soakula.wacr.recording

import com.soakula.wacr.crypto.CryptoStore
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel
import java.util.UUID

data class CommittedSegment(val index: Int, val durationUs: Long, val plainBytes: Long)

class SegmentWriter(
    private val recordingId: UUID,
    private val crypto: CryptoStore,
    private val format: AacFormat,
    private val onCommitted: (CommittedSegment) -> Unit
) : Closeable {
    private var index = 1
    private var output: WritableByteChannel? = null
    private var frames = 0
    private var plainBytes = 0L
    private var closed = false
    private var failed = false

    fun write(frame: ByteArray) {
        check(!closed && !failed) { "Segment writer is closed or failed" }
        format.validate(frame)
        failed = true
        if ((frames + 1) * AacFormat.FRAME_DURATION_US > MAX_DURATION_US) commitCurrent()
        val destination = output ?: crypto.openWriter(recordingId, name(index)).also { output = it }
        val bytes = ByteBuffer.wrap(frame)
        while (bytes.hasRemaining()) {
            if (destination.write(bytes) == 0) throw IOException("Encrypted segment writer made no progress")
        }
        plainBytes += frame.size
        frames++
        failed = false
    }

    private fun commitCurrent() {
        val destination = output ?: return
        destination.close()
        output = null
        onCommitted(CommittedSegment(index, frames * AacFormat.FRAME_DURATION_US, plainBytes))
        index++
        frames = 0
        plainBytes = 0
    }

    override fun close() {
        if (closed) return
        closed = true
        if (!failed) {
            commitCurrent()
        } else {
            val partial = output
            output = null
            try {
                partial?.close()
            } finally {
                if (partial != null) crypto.delete(recordingId, name(index))
            }
        }
    }

    companion object {
        const val MAX_DURATION_US = 5_000_000L
        fun name(index: Int): String {
            require(index > 0) { "Segment index must be positive" }
            return "seg_${index.toString().padStart(5, '0')}.enc"
        }
    }
}
