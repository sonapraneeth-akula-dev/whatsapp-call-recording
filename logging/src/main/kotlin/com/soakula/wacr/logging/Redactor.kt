package com.soakula.wacr.logging

import java.security.MessageDigest

/**
 * Replaces known contact/caller names (1–5 word spans, case-insensitive) and "+<digits>" phone numbers
 * with stable hashes like name#3fa2c1, so the same person maps to the same token across files.
 */
class Redactor(names: Collection<String>) {
    private val names: Set<String> = names.map(::norm).filter { it.length >= 3 && it !in COMMON }.toHashSet()

    fun apply(text: String): String {
        val s = PHONE.replace(text) { tag("phone", it.value.filter(Char::isDigit)) }
        if (names.isEmpty()) return s
        val words = WORD.findAll(s).toList()
        val out = StringBuilder(s.length)
        var pos = 0
        var i = 0
        while (i < words.size) {
            var matched = 0
            for (n in minOf(MAX_WORDS, words.size - i) downTo 1) {
                val cand = s.substring(words[i].range.first, words[i + n - 1].range.last + 1)
                if (norm(cand) in names) { matched = n; break }
            }
            if (matched == 0) { i++; continue }
            val start = words[i].range.first
            val end = words[i + matched - 1].range.last + 1
            out.append(s, pos, start).append(tag("name", norm(s.substring(start, end))))
            pos = end
            i += matched
        }
        return out.append(s, pos, s.length).toString()
    }

    companion object {
        private const val MAX_WORDS = 5
        private val PHONE = Regex("\\+\\d[\\d ()-]{7,}\\d")
        // '_' is part of a word so identifiers like VOICE_CALL / status_bar_* are never partially replaced.
        private val WORD = Regex("[\\p{L}\\p{M}\\p{N}_][\\p{L}\\p{M}\\p{N}_.'-]*")
        private val SPACES = Regex("\\s+")
        /** Contact first names that are also WhatsApp/Android UI words (e.g. a contact saved as "Voice mail"). */
        private val COMMON = setOf(
            "voice", "video", "call", "calls", "phone", "mobile", "home", "work", "office", "mail", "speaker", "audio",
            "camera", "microphone", "mute", "end", "more", "share", "add", "people", "ringing", "calling", "missed",
            "incoming", "outgoing", "whatsapp", "status", "notification", "message", "messages", "group", "android",
            "samsung", "settings", "system", "the", "and", "you", "turn", "select", "device", "screen", "chat", "chats",
        )

        fun norm(s: String) = s.trim().lowercase().replace(SPACES, " ")

        fun tag(kind: String, v: String): String =
            "$kind#" + MessageDigest.getInstance("SHA-256").digest(v.toByteArray())
                .take(3).joinToString("") { "%02x".format(it) }

        /** Full names plus their first word (WhatsApp group tiles often show first names only). */
        fun expand(fullNames: Collection<String>): Set<String> =
            fullNames.flatMap { listOf(it, it.trim().substringBefore(' ')) }.toSet()
    }
}
