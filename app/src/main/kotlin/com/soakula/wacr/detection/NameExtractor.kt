package com.soakula.wacr.detection

import java.text.Normalizer
import java.util.Locale

data class CallerName(val displayName: String, val matchKey: String?)

object NameExtractor {
    fun extract(vararg candidates: String?): CallerName {
        val displayName = candidates.asSequence().filterNotNull().map(::clean).firstOrNull(String::isNotEmpty)
            ?: return CallerName("Unknown", null)
        return CallerName(displayName, matchKey(displayName))
    }

    fun matchKey(value: String): String? {
        val normalized = Normalizer.normalize(clean(value), Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val key = buildString {
            val points = normalized.codePoints().iterator()
            while (points.hasNext()) {
                val point = points.nextInt()
                when (Character.getType(point)) {
                    Character.OTHER_SYMBOL.toInt(), Character.MODIFIER_SYMBOL.toInt(), Character.FORMAT.toInt() -> continue
                }
                when (point) {
                    0xFE0E, 0xFE0F, 0x20E3 -> Unit
                    else -> appendCodePoint(point)
                }
            }
        }
        return SPACES.replace(key, " ").trim().takeIf(String::isNotEmpty)
    }

    private fun clean(value: String): String {
        val bounded = buildString {
            val points = value.codePoints().limit(200).iterator()
            while (points.hasNext()) {
                val point = points.nextInt()
                if (Character.isISOControl(point)) {
                    if (Character.isWhitespace(point)) append(' ')
                    continue
                }
                if (Character.getType(point) == Character.FORMAT.toInt() && point != 0x200C && point != 0x200D) continue
                appendCodePoint(point)
            }
        }
        return SPACES.replace(bounded, " ").trim()
    }

    private val SPACES = Regex("[\\s\\p{Z}]+")
}
