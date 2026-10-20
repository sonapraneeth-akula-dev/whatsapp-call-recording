package com.soakula.wacr.detection

import org.junit.Assert.*
import org.junit.Test

class NameExtractorTest {
    @Test fun firstReadableCandidateWinsAndUnknownNeverAutoMatches() {
        assertEquals(CallerName("Unknown", null), NameExtractor.extract(null, " \n "))
        assertEquals(CallerName("Ravi", "ravi"), NameExtractor.extract(null, "\u0000", " Ravi ", "Other"))
        assertEquals("unknown", NameExtractor.extract("Unknown").matchKey)
    }

    @Test fun unicodeCaseEmojiAndWhitespaceNormalizeWithoutLosingIndicMarks() {
        assertEquals("ravi kumar", NameExtractor.matchKey("\uFF32\uFF21\uFF36\uFF29\u00A0 Kumar \u2764\uFE0F"))
        assertEquals("\u0c30\u0c3e\u0c35\u0c3f", NameExtractor.matchKey("\u0c30\u0c3e\u0c35\u0c3f \uD83D\uDE00"))
        assertEquals("+91 98765 43210", NameExtractor.extract("+91 98765 43210").matchKey)
        assertNull(NameExtractor.matchKey("\uD83D\uDE00"))
    }

    @Test fun controlsAndBidiOverridesAreRemovedAndCodePointLimitPreservesSurrogates() {
        assertEquals("Ravi Kumar", NameExtractor.extract("Ravi\t \u202EKumar\u0000").displayName)
        val name = NameExtractor.extract("\uD83D\uDE00".repeat(210)).displayName
        assertEquals(200, name.codePointCount(0, name.length))
        assertEquals(400, name.length)
        assertFalse(name.contains('\uFFFD'))
    }
}
