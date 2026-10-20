package com.soakula.wacr.recording

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class StorageGuardTest {
    @Test fun exactDecimalMegabyteThresholdsWarnAndStopOnlyBelowTheirBoundaries() {
        for ((bytes, level) in listOf(
            500_000_000L to StorageLevel.READY,
            499_999_999L to StorageLevel.WARN,
            100_000_000L to StorageLevel.WARN,
            99_999_999L to StorageLevel.STOP,
            0L to StorageLevel.STOP
        )) {
            assertEquals(StorageCheck(bytes, level), StorageGuard({ bytes }, { 0 }).beforeStart())
        }
    }

    @Test fun pollsEveryThirtySecondsButEveryStartForcesFreshStorageReading() {
        var now = 0L
        var bytes = 600_000_000L
        var reads = 0
        val guard = StorageGuard({ reads++; bytes }, { now })
        assertEquals(StorageLevel.READY, guard.beforeStart().level)
        bytes = 200_000_000
        now = 29_999
        assertEquals(StorageLevel.READY, guard.poll().level)
        assertEquals(1, reads)
        now = 30_000
        assertEquals(StorageLevel.WARN, guard.poll().level)
        assertEquals(2, reads)
        bytes = 10
        assertEquals(StorageLevel.STOP, guard.beforeStart().level)
        assertEquals(3, reads)
        now = 0
        bytes = 600_000_000
        assertEquals(StorageLevel.READY, guard.poll().level)
        assertEquals(4, reads)
    }

    @Test fun failedChecksSurfaceErrorsAndNeverCacheSuccessOrDelayRetry() {
        var bytes = -1L
        val guard = StorageGuard({ bytes }, { 0 })
        assertThrows(IOException::class.java) { guard.beforeStart() }
        bytes = 100
        assertEquals(StorageLevel.STOP, guard.poll().level)
        val failed = StorageGuard({ throw IOException("synthetic disk failure") }, { 0 })
        assertThrows(IOException::class.java) { failed.poll() }
    }
}
