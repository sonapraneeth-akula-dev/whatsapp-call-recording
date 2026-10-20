package com.soakula.wacr.util

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class AppLogTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun releaseRedactsNestedValuesBeforeDiskAndLogcat() {
        val lines = mutableListOf<String>()
        val directory = temporary.newFolder()
        val log = AppLog(directory, true, lines::add) { throw it }
        log.log("detection", "connected", mapOf(
            "contact" to "Ravi Kumar", "phone" to "9876543210",
            "participants" to listOf("Ravi", mapOf("name" to "\u0c30\u0c3e\u0c35\u0c3f")),
            "duration_ms" to 42, "silenced" to false
        ))
        val text = File(directory, "app.log").readText()
        assertEquals(lines.single() + "\n", text)
        listOf("Ravi", "9876543210", "\u0c30\u0c3e\u0c35\u0c3f").forEach { assertFalse(text.contains(it)) }
        assertTrue(text.contains("value#"))
        assertTrue(text.contains("\"duration_ms\":42"))
        assertTrue(text.contains("\"silenced\":false"))
    }

    @Test fun ringRetainsCompleteUtf8LinesAndNewestEventAcrossRestart() {
        val directory = temporary.newFolder()
        val log = AppLog(directory, false, {}) { throw it }
        repeat(700) { log.log("capture", "level", mapOf("sample" to "\u0c30".repeat(1500), "index" to it)) }
        AppLog(directory, false, {}) { throw it }.log("app", "restarted")
        val file = File(directory, "app.log")
        assertTrue(file.length() <= AppLog.MAX_BYTES)
        val text = file.readText()
        assertFalse(text.contains('\uFFFD'))
        assertTrue(text.lineSequence().filter(String::isNotEmpty).all { it.startsWith("{") && it.endsWith("}") })
        assertTrue(text.contains("\"event\":\"restarted\""))
        assertFalse(text.contains("\"index\":0}"))
    }

    @Test fun releaseCrashOmitsMessagesAndAlwaysChainsSystemHandler() {
        val directory = temporary.newFolder()
        val log = AppLog(directory, true, {}) { throw it }
        var forwarded = false
        val failure = IllegalStateException("Ravi 9876543210", IOException("private cause"))
        log.crashHandler { thread, error ->
            assertEquals("Ravi", thread.name)
            assertSame(failure, error)
            forwarded = true
        }.uncaughtException(Thread("Ravi"), failure)
        val crash = directory.listFiles()!!.single().readText()
        assertTrue(forwarded)
        assertTrue(crash.contains("java.lang.IllegalStateException"))
        assertTrue(crash.contains("java.io.IOException"))
        assertFalse(crash.contains("Ravi"))
        assertFalse(crash.contains("9876543210"))
        assertFalse(crash.contains("private cause"))
    }

    @Test fun diskFailuresAreReportedAndDoNotPreventCrashForwarding() {
        val file = temporary.newFile()
        var failures = 0
        var forwarded = false
        val log = AppLog(file, true, {}) { failures++ }
        log.log("app", "created")
        log.crashHandler { _, _ -> forwarded = true }.uncaughtException(Thread.currentThread(), RuntimeException())
        assertEquals(2, failures)
        assertTrue(forwarded)
    }

    @Test fun oversizedEntriesAreExplicitlyTruncated() {
        val directory = temporary.newFolder()
        val log = AppLog(directory, false, {}) { throw it }
        log.log("capture", "snapshot", mapOf("nodes" to List(500) { "text".repeat(500) }))
        val text = File(directory, "app.log").readText()
        assertTrue(text.contains("\"data_truncated\":true"))
        assertTrue(text.toByteArray().size < 4096)
    }
}
