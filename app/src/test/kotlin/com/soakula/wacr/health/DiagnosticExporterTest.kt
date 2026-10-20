package com.soakula.wacr.health

import android.app.Application
import com.soakula.wacr.logging.Redactor
import com.soakula.wacr.util.AppLog
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class DiagnosticExporterTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var directory: File
    private lateinit var log: AppLog

    @Before fun prepare() {
        directory = temporary.newFolder()
        log = AppLog(directory, redact = false, mirror = {}, reportFailure = { throw it })
    }

    @Test fun streamsOnlyRedactedLogsAndTechnicalStatusWithoutModifyingOriginalDebugLogs() = runBlocking {
        log.log("recorder", "test_event", mapOf(
            "caller" to "SyntheticContact", "text" to "SyntheticPrivateNotification",
            "count" to 7, "ready" to true,
            "nested" to mapOf("SyntheticPrivateKey" to listOf("SyntheticPrivateValue", null, 3))
        ))
        File(directory, "crash-1-2.txt").writeText("SyntheticPrivateCrash")
        File(directory, "secret.keyset").writeText("SyntheticPrivateKeyset")
        val original = File(directory, "app.log").readBytes()
        val output = Destination()
        exportDiagnostics(RuntimeEnvironment.getApplication(), log, output)
        assertTrue(output.closed)
        val entries = entries(output.toByteArray())
        assertEquals(setOf("device.json", "app.jsonl"), entries.keys)
        val metadata = JSONObject(checkNotNull(entries["device.json"]))
        assertEquals(1, metadata.getInt("schema_version"))
        assertEquals("not_available_in_this_build", metadata.getString("capability_status"))
        assertTrue(metadata.isNull("capability_results"))
        assertTrue(metadata.getJSONArray("health_issues").toString().contains("ACCESSIBILITY_DISABLED"))
        assertTrue(metadata.getJSONArray("health_issues").toString().contains("NOTIFICATION_ACCESS_DISABLED"))
        val text = checkNotNull(entries["app.jsonl"])
        assertFalse(entries.values.any { it.contains("Synthetic") })
        val event = JSONObject(text.trim())
        assertEquals("recorder", event.getString("tag"))
        assertEquals("test_event", event.getString("event"))
        assertEquals(7, event.getJSONObject("data").getInt(Redactor.tag("field", "count")))
        assertTrue(event.getJSONObject("data").getBoolean(Redactor.tag("field", "ready")))
        assertArrayEquals(original, File(directory, "app.log").readBytes())
        assertEquals(text, log.redactedSnapshot().toString(Charsets.UTF_8))
    }

    @Test fun malformedTailIsReportedWithoutExportingItsPrivateRawText() = runBlocking {
        log.log("app", "created")
        File(directory, "app.log").appendText("{\"sensitive\":\"SyntheticPrivateTail\"")
        val output = Destination()
        exportDiagnostics(RuntimeEnvironment.getApplication(), log, output)
        val lines = checkNotNull(entries(output.toByteArray())["app.jsonl"]).lineSequence()
            .filter(String::isNotBlank).map(::JSONObject).toList()
        assertEquals(2, lines.size)
        assertEquals("invalid_log_record", lines.last().getString("event"))
        assertEquals(2, lines.last().getInt("line"))
        assertFalse(lines.toString().contains("SyntheticPrivateTail"))
    }

    @Test fun absentLogIsExplicitlyEmptyAndOversizedLogFailsBeforeArchiveWrites() = runBlocking {
        assertTrue(log.redactedSnapshot().isEmpty())
        File(directory, "app.log").writeBytes(ByteArray(AppLog.MAX_BYTES + 1))
        val output = Destination()
        assertThrows(IOException::class.java) {
            runBlocking { exportDiagnostics(RuntimeEnvironment.getApplication(), log, output) }
        }
        assertTrue(output.closed)
        assertEquals(0, output.size())
    }

    @Test fun destinationFailurePropagatesAndClosesInsteadOfReportingACompletedExport() = runBlocking {
        var closed = false
        val output = object : OutputStream() {
            override fun write(value: Int) { throw IOException("Synthetic destination failure") }
            override fun close() { closed = true }
        }
        assertThrows(IOException::class.java) {
            runBlocking { exportDiagnostics(RuntimeEnvironment.getApplication(), log, output) }
        }
        assertTrue(closed)
    }

    private fun entries(bytes: ByteArray): Map<String, String> = ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        buildMap {
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, zip.readBytes().toString(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    private class Destination : ByteArrayOutputStream() {
        var closed = false
        override fun close() { closed = true; super.close() }
    }
}
