package com.soakula.wacr.spike

import com.soakula.wacr.spike.analysis.FrameMeter
import com.soakula.wacr.spike.analysis.Rms
import com.soakula.wacr.spike.log.Json
import com.soakula.wacr.spike.log.Redactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class RmsTest {
    private fun sine(amp: Double, seconds: Double = 1.0, rate: Int = 16000) =
        ShortArray((rate * seconds).toInt()) { (amp * 32767 * sin(2 * PI * 1000 * it / rate)).toInt().toShort() }

    @Test fun halfScaleSineIsAboutMinus9dBFS() {
        val frames = Rms.frames(sine(0.5), 16000)[0]
        assertEquals(10, frames.size)
        frames.forEach { assertEquals(-9.03, it, 0.1) }
    }

    @Test fun silenceIsFloorAndAllZeros() {
        val m = FrameMeter(16000).apply { add(ShortArray(16000), 16000) }
        assertEquals(10, m.frames.size)
        m.frames.forEach { assertEquals(Rms.FLOOR, it, 0.0) }
        assertEquals(100.0, m.zeroPct, 0.0)
    }

    @Test fun meterMatchesBatchAcrossChunks() {
        val pcm = sine(0.1)
        val m = FrameMeter(16000)
        pcm.toList().chunked(1234).forEach { c -> m.add(c.toShortArray(), c.size) }
        val batch = Rms.frames(pcm, 16000)[0]
        assertEquals(batch.size, m.frames.size)
        batch.indices.forEach { assertEquals(batch[it], m.frames[it], 1e-9) }
    }

    @Test fun stereoSplitsChannels() {
        val loud = sine(0.5)
        val pcm = ShortArray(loud.size * 2) { if (it % 2 == 0) loud[it / 2] else 0 }
        val (l, r) = Rms.frames(pcm, 16000, channels = 2)
        assertTrue(l.all { it > -10 })
        assertTrue(r.all { it == Rms.FLOOR })
    }

    @Test fun percentile() {
        val v = (1..100).map { it.toDouble() }
        assertEquals(95.0, Rms.percentile(v, 95.0), 0.0)
        assertEquals(1.0, Rms.percentile(v, 0.0), 0.0)
        assertEquals(Rms.FLOOR, Rms.percentile(emptyList(), 95.0), 0.0)
    }
}

class JsonTest {
    @Test fun encodesNestedAndEscapes() {
        val s = Json.encode(linkedMapOf("a" to 1, "b" to listOf(true, null, 2.5), "c" to "q\"\\\n\u0001", "d" to Double.NaN, "e" to "నమస్తే"))
        assertEquals("{\"a\":1,\"b\":[true,null,2.5],\"c\":\"q\\\"\\\\\\n\\u0001\",\"d\":null,\"e\":\"నమస్తే\"}", s)
    }
}

class RedactorTest {
    private val r = Redactor(Redactor.expand(listOf("Ravi Kumar", "Amma", "राम")))

    @Test fun replacesFullAndFirstNamesConsistently() {
        val out = r.apply("{\"title\":\"Ravi Kumar\",\"cd\":\"Ravi, muted\",\"t\":\"ravi kumar\"}")
        assertFalse(out.contains("Ravi", ignoreCase = true))
        val full = Redactor.tag("name", "ravi kumar")
        assertEquals(2, Regex(Regex.escape(full)).findAll(out).count())
        assertTrue(out.contains(Redactor.tag("name", "ravi")))
    }

    @Test fun keepsUiWordsKeysAndTimestamps() {
        val line = "{\"ts\":1760860000123,\"text\":\"Ringing\",\"timer\":\"01:23\"}"
        assertEquals(line, r.apply(line))
    }

    @Test fun hidesPlusPhoneNumbersAndUnicodeNames() {
        val out = r.apply("Calling +91 98765 43210 and राम")
        assertFalse(out.contains("98765"))
        assertFalse(out.contains("राम"))
        assertTrue(out.startsWith("Calling phone#"))
    }
}
