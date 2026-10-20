package com.soakula.wacr.recording

import kotlin.math.abs

class WaveformPeaks(val framesPerPeak: Long, val frameCount: Long, values: ByteArray) {
    init {
        require(framesPerPeak > 0 && frameCount > 0) { "Invalid waveform timeline" }
        require(values.size in 1..WaveformAccumulator.MAX_PEAKS &&
            (frameCount - 1) / framesPerPeak + 1 == values.size.toLong()) { "Invalid waveform peaks" }
    }
    private val amplitudes = values.copyOf()

    fun encodedPeaks(): ByteArray = amplitudes.copyOf()

    fun limitedTo(frames: Long): WaveformPeaks {
        require(frames > 0) { "Invalid waveform limit" }
        if (frames >= frameCount) return this
        val size = ((frames - 1) / framesPerPeak + 1).toInt()
        return WaveformPeaks(framesPerPeak, frames, amplitudes.copyOf(size))
    }

    fun bars(columns: Int): FloatArray {
        require(columns > 0) { "Invalid waveform width" }
        val count = minOf(columns, amplitudes.size)
        return FloatArray(count) { column ->
            var peak = 0
            for (index in column * amplitudes.size / count until (column + 1) * amplitudes.size / count)
                peak = maxOf(peak, amplitudes[index].toInt() and 255)
            peak / 255f
        }
    }
}

/** Single-worker PCM envelope; pairwise compaction keeps long calls bounded. */
class WaveformAccumulator(private val channels: Int) {
    init { require(channels in 1..2) { "Invalid waveform channel count" } }
    private val peaks = ByteArray(MAX_PEAKS)
    private var size = 0
    private var framesPerPeak = AacFormat.SAMPLE_RATE / 10L
    private var framesInPeak = 0L
    private var totalFrames = 0L
    private var maximum = 0

    fun append(pcm: ShortArray, count: Int = pcm.size) {
        require(count in 0..pcm.size && count % channels == 0) { "Incomplete waveform PCM frames" }
        check(count / channels <= Long.MAX_VALUE - totalFrames) { "Waveform timeline overflow" }
        var offset = 0
        while (offset < count) {
            if (size == MAX_PEAKS) compact()
            repeat(channels) { maximum = maxOf(maximum, abs(pcm[offset++].toInt())) }
            totalFrames++
            if (++framesInPeak == framesPerPeak) {
                peaks[size++] = quantize(maximum)
                maximum = 0
                framesInPeak = 0
            }
        }
    }

    fun snapshot(): WaveformPeaks? {
        if (totalFrames == 0L) return null
        val values = peaks.copyOf(size + if (framesInPeak > 0) 1 else 0)
        if (framesInPeak > 0) values[size] = quantize(maximum)
        return WaveformPeaks(framesPerPeak, totalFrames, values)
    }

    private fun compact() {
        check(framesInPeak == 0L)
        for (index in 0 until MAX_PEAKS / 2)
            peaks[index] = maxOf(peaks[index * 2].toInt() and 255, peaks[index * 2 + 1].toInt() and 255).toByte()
        size /= 2
        framesPerPeak = Math.multiplyExact(framesPerPeak, 2)
    }

    private fun quantize(value: Int): Byte = ((value * 255 + 16_384) / 32_768).toByte()

    companion object { const val MAX_PEAKS = 2048 }
}
