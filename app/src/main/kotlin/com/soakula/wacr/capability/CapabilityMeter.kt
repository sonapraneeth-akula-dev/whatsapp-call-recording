package com.soakula.wacr.capability

import com.soakula.wacr.audio.FrameMeter
import com.soakula.wacr.audio.Rms
import com.soakula.wacr.recording.AacFormat

enum class CapabilityPhase { YOU, OTHER, SAVING }

/** Cued mixed-mono windows, not isolated speakers or an audibility verdict. */
class CapabilityMeter {
    private val meter = FrameMeter(AacFormat.SAMPLE_RATE)
    var clientSilenced: Boolean? = null
        private set
    val sampleFrames: Long get() = meter.total
    val complete: Boolean get() = sampleFrames == TOTAL_FRAMES
    val phase: CapabilityPhase
        get() = if (sampleFrames < TOTAL_FRAMES / 2) CapabilityPhase.YOU else CapabilityPhase.OTHER
    val ownRmsDb: Double? get() = percentile(20, 150)
    val remoteRmsDb: Double? get() = percentile(170, 300)
    val zeroPct: Double? get() = meter.zeroPct.takeIf { sampleFrames > 0 }

    fun add(pcm: ShortArray, count: Int, silenced: Boolean?) {
        require(count in 0..pcm.size) { "PCM count is outside the supplied buffer" }
        val accepted = minOf(count.toLong(), TOTAL_FRAMES - sampleFrames).toInt()
        if (accepted == 0) return
        if (silenced != null && clientSilenced != true) clientSilenced = silenced
        meter.add(pcm, accepted)
    }

    private fun percentile(start: Int, end: Int): Double? {
        if (meter.frames.size <= start) return null
        return Rms.percentile(meter.frames.subList(start, minOf(end, meter.frames.size)), 95.0)
    }

    companion object {
        const val TOTAL_FRAMES = AacFormat.SAMPLE_RATE * 30L
    }
}
