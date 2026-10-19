package com.soakula.wacr.spike.capture

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** PCM16 WAV writer; header sizes patched on close. */
class WavWriter(file: File, private val sampleRate: Int, private val channels: Int = 1) : Closeable {
    private val raf = RandomAccessFile(file, "rw").apply { setLength(0); write(ByteArray(44)) }
    private var bytes = 0L

    fun write(buf: ShortArray, len: Int) {
        val b = ByteBuffer.allocate(len * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.asShortBuffer().put(buf, 0, len)
        raf.write(b.array())
        bytes += len * 2
    }

    override fun close() {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt((36 + bytes).toInt()).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(sampleRate)
        h.putInt(sampleRate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        h.put("data".toByteArray()).putInt(bytes.toInt())
        raf.seek(0)
        raf.write(h.array())
        raf.close()
    }
}
