package com.soakula.wacr.capability

import com.soakula.wacr.audio.Rms
import org.junit.Assert.*
import org.junit.Test

class CapabilityMeterTest {
    @Test fun keepsTwoCuedWindowsSeparateAndSkipsTheirReactionTime() {
        val meter = CapabilityMeter()
        repeat(20) { meter.add(ShortArray(1600) { 32767 }, 1600, false) }
        repeat(130) { meter.add(ShortArray(1600) { 1000 }, 1600, false) }
        assertEquals(CapabilityPhase.OTHER, meter.phase)
        repeat(20) { meter.add(ShortArray(1600) { 32767 }, 1600, false) }
        repeat(130) { meter.add(ShortArray(1600) { 500 }, 1600, false) }
        assertTrue(meter.complete)
        assertEquals(480_000L, meter.sampleFrames)
        assertEquals(Rms.dbfs(1_000_000.0, 1), meter.ownRmsDb!!, 0.00001)
        assertEquals(Rms.dbfs(250_000.0, 1), meter.remoteRmsDb!!, 0.00001)
        assertEquals(0.0, meter.zeroPct!!, 0.0)
        assertEquals(false, meter.clientSilenced)
    }

    @Test fun emptyAndShortWindowsNeverInventEvidenceForEitherSide() {
        val meter = CapabilityMeter()
        assertNull(meter.ownRmsDb)
        assertNull(meter.remoteRmsDb)
        assertNull(meter.zeroPct)
        assertNull(meter.clientSilenced)
        repeat(20) { meter.add(ShortArray(1600), 1600, null) }
        assertNull(meter.ownRmsDb)
        assertNull(meter.remoteRmsDb)
        assertFalse(meter.complete)
        meter.add(ShortArray(1600), 1600, null)
        assertEquals(Rms.FLOOR, meter.ownRmsDb!!, 0.0)
        assertNull(meter.remoteRmsDb)
        assertEquals(100.0, meter.zeroPct!!, 0.0)
    }

    @Test fun arbitraryChunkBoundariesAndOvershootDoNotCrossTheThirtySecondLimit() {
        val meter = CapabilityMeter()
        repeat(469) { meter.add(ShortArray(1024) { 1000 }, 1024, null) }
        assertTrue(meter.complete)
        assertEquals(480_000L, meter.sampleFrames)
        val own = meter.ownRmsDb
        meter.add(ShortArray(1024), 1024, true)
        assertEquals(480_000L, meter.sampleFrames)
        assertEquals(own, meter.ownRmsDb)
        assertEquals(0.0, meter.zeroPct!!, 0.0)
    }

    @Test fun frameworkSilencingStaysKnownEvenAfterAnotherObservation() {
        val meter = CapabilityMeter()
        meter.add(shortArrayOf(0, 1), 2, true)
        meter.add(shortArrayOf(1, 1), 2, false)
        assertEquals(true, meter.clientSilenced)
        assertEquals(25.0, meter.zeroPct!!, 0.0)
    }

    @Test fun rejectsInvalidPcmLengths() {
        val meter = CapabilityMeter()
        assertThrows(IllegalArgumentException::class.java) { meter.add(shortArrayOf(1), -1, null) }
        assertThrows(IllegalArgumentException::class.java) { meter.add(shortArrayOf(1), 2, null) }
        assertEquals(0L, meter.sampleFrames)
    }
}
