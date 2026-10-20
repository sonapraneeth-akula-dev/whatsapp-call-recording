package com.soakula.wacr.playback

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PlaybackAccessTest {
    @Test fun onlyOwnUidPlatformAndTrustedSystemUiCanConnect() {
        assertTrue(playbackControllerAllowed(10123, "own.app", false, 10123))
        assertTrue(playbackControllerAllowed(1000, "android", false, 10123))
        assertTrue(playbackControllerAllowed(10042, "com.android.systemui", true, 10123))
        assertFalse(playbackControllerAllowed(10042, "com.android.systemui", false, 10123))
        assertFalse(playbackControllerAllowed(10042, "other.trusted.app", true, 10123))
        assertFalse(playbackControllerAllowed(10042, "own.app", false, 10123))
    }

    @Test fun identityMustBeCanonicalNotAUriOrAnAbbreviatedUuid() {
        val id = UUID.randomUUID()
        assertEquals(id, playbackId(id.toString()))
        for (invalid in listOf("", "1-1-1-1-1", id.toString().uppercase(), "wacr://playback/$id", "$id ")) {
            assertThrows(IllegalArgumentException::class.java) { playbackId(invalid) }
        }
    }
}
