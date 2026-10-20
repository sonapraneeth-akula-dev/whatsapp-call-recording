package com.soakula.wacr.recording

import org.junit.Assert.*
import org.junit.Test

class WaveformPeaksTest {
    @Test fun emptySilenceSignedExtremesStereoAndPartialBucketsHaveHonestPeaks() {
        val accumulator = WaveformAccumulator(2)
        assertNull(accumulator.snapshot())
        accumulator.append(ShortArray(3200))
        accumulator.append(shortArrayOf(0, Short.MIN_VALUE))
        val result = checkNotNull(accumulator.snapshot())
        assertEquals(1600L, result.framesPerPeak)
        assertEquals(1601L, result.frameCount)
        assertArrayEquals(byteArrayOf(0, 255.toByte()), result.encodedPeaks())
        assertArrayEquals(floatArrayOf(0f, 1f), result.bars(2), 0f)
        assertArrayEquals(floatArrayOf(1f), result.bars(1), 0f)
    }

    @Test fun appendingAndExternalByteMutationCannotChangeEarlierSnapshots() {
        val accumulator = WaveformAccumulator(1)
        accumulator.append(shortArrayOf(0))
        val first = checkNotNull(accumulator.snapshot())
        first.encodedPeaks()[0] = 255.toByte()
        accumulator.append(shortArrayOf(Short.MAX_VALUE))
        assertArrayEquals(byteArrayOf(0), first.encodedPeaks())
        assertArrayEquals(byteArrayOf(255.toByte()), checkNotNull(accumulator.snapshot()).encodedPeaks())
        val bytes = byteArrayOf(100)
        val copied = WaveformPeaks(1600, 1, bytes)
        bytes[0] = 0
        assertArrayEquals(byteArrayOf(100), copied.encodedPeaks())
    }

    @Test fun compactionAndChunkBoundariesKeepPeaksAlignedAndNeverDiscardAnImpulse() {
        val samples = ShortArray(1600 * WaveformAccumulator.MAX_PEAKS + 17)
        samples[1600 * 2001] = Short.MIN_VALUE
        samples[samples.lastIndex] = Short.MAX_VALUE
        val whole = WaveformAccumulator(1).apply { append(samples) }
        val chunks = WaveformAccumulator(1)
        for (start in samples.indices step 1024) chunks.append(samples.copyOfRange(start, minOf(start + 1024, samples.size)))
        val result = checkNotNull(whole.snapshot())
        assertEquals(3200L, result.framesPerPeak)
        assertEquals(samples.size.toLong(), result.frameCount)
        assertArrayEquals(result.encodedPeaks(), checkNotNull(chunks.snapshot()).encodedPeaks())
        assertEquals(255.toByte(), result.encodedPeaks()[1000])
        assertEquals(255.toByte(), result.encodedPeaks().last())
        assertEquals(1f, result.bars(1).single(), 0f)
    }

    @Test fun hourOfPcmStaysBoundedAndDownsamplesToScreenWidth() {
        val accumulator = WaveformAccumulator(1)
        val second = ShortArray(AacFormat.SAMPLE_RATE).apply { this[0] = Short.MIN_VALUE }
        repeat(3600) { accumulator.append(second) }
        val result = checkNotNull(accumulator.snapshot())
        assertEquals(57_600_000L, result.frameCount)
        assertTrue(result.encodedPeaks().size <= WaveformAccumulator.MAX_PEAKS)
        assertEquals(360, result.bars(360).size)
        assertTrue(result.bars(360).all { it == 1f })
        val truncated = result.limitedTo(1600)
        assertEquals(1600L, truncated.frameCount)
        assertEquals(1, truncated.encodedPeaks().size)
    }

    @Test fun malformedInputsAndTimelinesAreRejectedWithoutModifyingValidCapture() {
        val accumulator = WaveformAccumulator(2)
        assertThrows(IllegalArgumentException::class.java) { accumulator.append(shortArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { accumulator.append(shortArrayOf(1, 2), 3) }
        assertNull(accumulator.snapshot())
        assertThrows(IllegalArgumentException::class.java) { WaveformAccumulator(0) }
        assertThrows(IllegalArgumentException::class.java) { WaveformPeaks(1, 0, byteArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { WaveformPeaks(1, 2, byteArrayOf(1)) }
        val peaks = WaveformPeaks(1, 1, byteArrayOf(1))
        assertThrows(IllegalArgumentException::class.java) { peaks.bars(0) }
        assertThrows(IllegalArgumentException::class.java) { peaks.limitedTo(0) }
    }
}
