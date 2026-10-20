package com.soakula.wacr.audio

import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

object Rms {
    const val FLOOR = -120.0
    /** A window counts as "heard" when its 95th-percentile 100 ms level is above this. */
    const val SILENT_P95 = -50.0

    fun dbfs(sumSquares: Double, n: Int): Double {
        if (n == 0) return FLOOR
        val rms = sqrt(sumSquares / n) / 32768.0
        return if (rms <= 0.0) FLOOR else max(FLOOR, 20 * log10(rms))
    }

    /** dBFS per [frameMs] frame for each channel of interleaved PCM16. Partial trailing frame dropped. */
    fun frames(pcm: ShortArray, sampleRate: Int, channels: Int = 1, frameMs: Int = 100): List<DoubleArray> {
        val frameLen = sampleRate * frameMs / 1000
        val count = pcm.size / channels / frameLen
        return List(channels) { ch ->
            DoubleArray(count) { f ->
                var sum = 0.0
                for (i in 0 until frameLen) {
                    val s = pcm[(f * frameLen + i) * channels + ch].toDouble()
                    sum += s * s
                }
                dbfs(sum, frameLen)
            }
        }
    }

    fun percentile(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return FLOOR
        val sorted = values.sorted()
        val idx = (ceil(p / 100.0 * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }
}

/** Streaming mono meter: dBFS per 100 ms frame plus digital-zero ratio (silenced clients get zeros). */
class FrameMeter(sampleRate: Int, frameMs: Int = 100) {
    private val frameLen = sampleRate * frameMs / 1000
    private var sum = 0.0
    private var n = 0
    var zeros = 0L; private set
    var total = 0L; private set
    val frames = ArrayList<Double>()

    fun add(buf: ShortArray, len: Int) {
        for (i in 0 until len) {
            val s = buf[i].toInt()
            if (s == 0) zeros++
            sum += (s * s).toDouble()
            n++
            if (n == frameLen) { frames += Rms.dbfs(sum, n); sum = 0.0; n = 0 }
        }
        total += len
    }

    val zeroPct: Double get() = if (total == 0L) 0.0 else zeros * 100.0 / total
}
