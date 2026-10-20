package com.soakula.wacr.ui.player

import org.junit.Assert.*
import org.junit.Test

class TransportControlsTest {
    @Test fun skipClampsAtBothEndsWithoutOverflowingLongPositions() {
        assertEquals(0L, skipPosition(3000, 60_000, false))
        assertEquals(13_000L, skipPosition(3000, 60_000, true))
        assertEquals(60_000L, skipPosition(59_000, 60_000, true))
        assertEquals(49_000L, skipPosition(59_000, 60_000, false))
        assertEquals(0L, skipPosition(-1, -1, false))
        assertEquals(Long.MAX_VALUE, skipPosition(Long.MAX_VALUE - 1, Long.MAX_VALUE, true))
        assertEquals(Long.MAX_VALUE - 10_000, skipPosition(Long.MAX_VALUE, Long.MAX_VALUE, false))
    }
}
