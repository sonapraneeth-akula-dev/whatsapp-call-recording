package com.soakula.wacr.recording

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.nio.ByteOrder

class AacEncoder(private val format: AacFormat, private val onFrame: (ByteArray) -> Unit) : Closeable {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val pending = ByteArrayOutputStream()
    private var queuedSamples = 0L
    private var closed = false
    private var failed = false

    init {
        var started = false
        try {
            val mediaFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, AacFormat.SAMPLE_RATE, format.channels
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, format.bitRate)
            }
            codec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            started = true
        } finally {
            if (!started) codec.release()
        }
    }

    fun encode(pcm: ShortArray, count: Int = pcm.size) {
        check(!closed && !failed) { "Encoder is closed or failed" }
        require(count in 0..pcm.size && count % format.channels == 0) { "PCM must contain complete interleaved sample frames" }
        failed = true
        var offset = 0
        while (offset < count) {
            val index = inputIndex()
            val input = checkNotNull(codec.getInputBuffer(index)).apply { clear() }
            val samples = minOf(input.remaining() / 2, count - offset) / format.channels * format.channels
            if (samples == 0) throw IOException("AAC encoder input buffer is too small")
            input.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, offset, samples)
            codec.queueInputBuffer(index, 0, samples * 2, timestampUs(), 0)
            queuedSamples += samples / format.channels
            offset += samples
            drain(false)
        }
        failed = false
    }

    private fun timestampUs() = queuedSamples * 1_000_000 / AacFormat.SAMPLE_RATE

    private fun inputIndex(): Int {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) return index
            if (index != MediaCodec.INFO_TRY_AGAIN_LATER) throw IOException("Unexpected AAC input status: $index")
            drain(false)
        }
        throw IOException("AAC encoder input timed out")
    }

    private fun drain(ending: Boolean) {
        val info = MediaCodec.BufferInfo()
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            val index = codec.dequeueOutputBuffer(info, 10_000)
            when {
                index >= 0 -> {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val output = checkNotNull(codec.getOutputBuffer(index))
                            if (pending.size() + info.size > 8184) throw IOException("AAC access unit exceeds ADTS limit")
                            val bytes = ByteArray(info.size)
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            output.get(bytes)
                            pending.write(bytes)
                            if (info.flags and MediaCodec.BUFFER_FLAG_PARTIAL_FRAME == 0) {
                                val packet = format.packet(pending.toByteArray())
                                pending.reset()
                                onFrame(packet)
                            }
                        }
                    } finally {
                        codec.releaseOutputBuffer(index, false)
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        if (!ending || pending.size() != 0) throw IOException("AAC stream ended with incomplete output")
                        return
                    }
                }
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!ending) return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> throw IOException("Unexpected AAC output status: $index")
            }
        }
        throw IOException("AAC encoder output timed out")
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            if (!failed) {
                codec.queueInputBuffer(inputIndex(), 0, 0, timestampUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                drain(true)
            }
        } finally {
            try { codec.stop() } finally { codec.release() }
        }
    }
}
