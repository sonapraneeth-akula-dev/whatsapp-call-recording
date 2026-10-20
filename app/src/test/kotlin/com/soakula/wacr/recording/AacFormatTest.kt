package com.soakula.wacr.recording

import org.junit.Assert.*
import org.junit.Test

class AacFormatTest {
    @Test fun monoAndStereoHeadersEncodeLc16kHzAndExactFrameLength() {
        val mono = AacFormat(1)
        val packet = mono.packet(ByteArray(100) { it.toByte() })
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x60, 0x40, 0x0D, 0x7F, 0xFC.toByte()), packet.copyOfRange(0, 7))
        mono.validate(packet)
        val stereo = AacFormat(2).packet(ByteArray(100))
        assertEquals(0x80, stereo[3].toInt() and 0xFF)
        assertEquals(32_000, mono.bitRate)
        assertEquals(48_000, AacFormat(2).bitRate)
        assertEquals(64_000L, AacFormat.FRAME_DURATION_US)
    }

    @Test fun framingRejectsWrongProfileRateChannelsLengthsAndMultipleBlocks() {
        val format = AacFormat(1)
        val packet = format.packet(ByteArray(100))
        for (index in listOf(0, 1, 2, 3, 4, 5, 6)) {
            val changed = packet.copyOf()
            changed[index] = (changed[index].toInt() xor 1).toByte()
            assertThrows(IllegalArgumentException::class.java) { format.validate(changed) }
        }
        assertThrows(IllegalArgumentException::class.java) { format.validate(packet.copyOf(packet.size - 1)) }
        assertThrows(IllegalArgumentException::class.java) { AacFormat(2).validate(packet) }
        assertThrows(IllegalArgumentException::class.java) { format.packet(byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { format.packet(ByteArray(8185)) }
        assertThrows(IllegalArgumentException::class.java) { AacFormat(0) }
        assertEquals(8191, format.packet(ByteArray(8184)).size)
    }
}
