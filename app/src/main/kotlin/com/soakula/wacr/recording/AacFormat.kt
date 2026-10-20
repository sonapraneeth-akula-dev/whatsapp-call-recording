package com.soakula.wacr.recording

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

internal data class AacContent(val durationUs: Long, val bytes: Long, val hash: ByteArray)

class AacFormat(val channels: Int) {
    init { require(channels in 1..2) { "AAC capture supports mono or stereo" } }

    val bitRate = if (channels == 1) 32_000 else 48_000

    fun packet(payload: ByteArray): ByteArray = header(payload.size) + payload

    fun validate(packet: ByteArray) {
        require(packet.size > 7 && header(packet.size - 7).contentEquals(packet.copyOfRange(0, 7))) {
            "Expected one complete 16 kHz AAC-LC ADTS frame"
        }
    }

    fun readFrame(input: InputStream): ByteArray? {
        val header = input.readNBytes(7)
        if (header.isEmpty()) return null
        if (header.size != 7) throw EOFException("Truncated ADTS header")
        val size = ((header[3].toInt() and 3) shl 11) or
            ((header[4].toInt() and 0xFF) shl 3) or ((header[5].toInt() and 0xE0) shr 5)
        if (size !in 8..8191) throw IOException("Invalid ADTS frame size")
        val payload = input.readNBytes(size - 7)
        if (payload.size != size - 7) throw EOFException("Truncated AAC payload")
        val frame = header + payload
        try {
            validate(frame)
        } catch (error: IllegalArgumentException) {
            throw IOException("Invalid AAC-LC ADTS frame", error)
        }
        return frame
    }

    internal fun inspect(input: InputStream, consume: (ByteArray) -> Unit = {}): AacContent {
        val hash = MessageDigest.getInstance("SHA-256")
        var frames = 0L
        var bytes = 0L
        while (true) {
            val frame = readFrame(input) ?: break
            frames++
            bytes += frame.size
            hash.update(frame)
            consume(frame)
        }
        if (frames == 0L) throw IOException("Recording contains no AAC frames")
        return AacContent(frames * FRAME_DURATION_US, bytes, hash.digest())
    }

    private fun header(payloadBytes: Int): ByteArray {
        require(payloadBytes in 1..8184) { "AAC payload does not fit one ADTS frame" }
        val size = payloadBytes + 7
        return byteArrayOf(
            0xFF.toByte(), 0xF1.toByte(), 0x60,
            ((channels shl 6) or (size shr 11)).toByte(),
            (size shr 3).toByte(), (((size and 7) shl 5) or 0x1F).toByte(), 0xFC.toByte()
        )
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_DURATION_US = 64_000L // AAC-LC: 1024 samples at 16 kHz.
    }
}
