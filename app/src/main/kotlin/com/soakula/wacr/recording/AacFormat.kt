package com.soakula.wacr.recording

class AacFormat(val channels: Int) {
    init { require(channels in 1..2) { "AAC capture supports mono or stereo" } }

    val bitRate = if (channels == 1) 32_000 else 48_000

    fun packet(payload: ByteArray): ByteArray = header(payload.size) + payload

    fun validate(packet: ByteArray) {
        require(packet.size > 7 && header(packet.size - 7).contentEquals(packet.copyOfRange(0, 7))) {
            "Expected one complete 16 kHz AAC-LC ADTS frame"
        }
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
