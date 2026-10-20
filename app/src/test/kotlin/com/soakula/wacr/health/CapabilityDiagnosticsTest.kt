package com.soakula.wacr.health

import androidx.room.Room
import androidx.room.withTransaction
import com.soakula.wacr.WacrApp
import com.soakula.wacr.capability.CapabilityDevice
import com.soakula.wacr.capability.CapabilityMeter
import com.soakula.wacr.crypto.KeyManager
import com.soakula.wacr.data.AppDatabase
import com.soakula.wacr.data.AudioRoute
import com.soakula.wacr.data.CapabilityResult
import com.soakula.wacr.data.CapabilityStatus
import com.soakula.wacr.data.RecordingEntity
import com.soakula.wacr.data.RecordingStatus
import com.soakula.wacr.recording.CaptureSource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.experimental.LazyApplication
import org.robolectric.util.ReflectionHelpers
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.GeneralSecurityException
import java.util.UUID
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = WacrApp::class)
@LazyApplication(LazyApplication.LazyLoad.ON)
class CapabilityDiagnosticsTest {
    private lateinit var app: WacrApp
    private lateinit var database: AppDatabase
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private val device = CapabilityDevice("TechnicalModel", "TechnicalBuild", "com.whatsapp", "1.2", 12)

    @Before fun prepare() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler ?: Thread.UncaughtExceptionHandler { _, error ->
            throw AssertionError("Unexpected uncaught test exception", error)
        })
        app = checkNotNull(RuntimeEnvironment.getApplication() as? WacrApp)
        database = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        ReflectionHelpers.setField(app, "openDatabase", database)
    }

    @After fun cleanup() {
        database.close()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    private fun result(at: Long) = CapabilityResult(
        UUID.randomUUID(), device, AudioRoute.EARPIECE, CaptureSource.MIC, CapabilityStatus.COMPLETE, at,
        sampleFrames = CapabilityMeter.TOTAL_FRAMES, ownRmsDb = -20.0, remoteRmsDb = -40.0, zeroPct = 1.25
    )

    private suspend fun archive(): Map<String, String> {
        val output = ByteArrayOutputStream()
        exportDiagnostics(app, app.log, output)
        return ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            buildMap {
                while (true) {
                    val entry = zip.nextEntry ?: break
                    put(entry.name, zip.readBytes().toString(Charsets.UTF_8))
                }
            }
        }
    }

    @Test fun realZipIncludesOnlyBoundedTechnicalFieldsAndExplicitUnknownOrReviewedVerdicts() = runBlocking {
        val clip = UUID.randomUUID()
        database.recordings().insertRecording(RecordingEntity(
            clip, 1, 1, "MIC", title = "SyntheticPrivateTitle",
            status = RecordingStatus.FINALIZED, durationMs = 30_000, audioPath = "$clip/audio.aac.enc"
        ))
        val probe = result(3).copy(recordingId = clip)
        database.capabilities().insert(probe)
        database.capabilities().insert(result(2).copy(ownOk = true, remoteOk = false, clientSilenced = false))
        database.capabilities().insert(result(1).copy(
            device = device.copy(osBuild = "HistoricalBuild"), status = CapabilityStatus.TIMED_OUT,
            sampleFrames = 100, ownRmsDb = null, remoteRmsDb = null, zeroPct = null
        ))
        File(app.filesDir, "SyntheticPrivate.keyset").writeText("SyntheticPrivateKeyBytes")
        app.log.log("test", "private_event", mapOf("caller" to "SyntheticPrivateCaller"))
        val zip = archive()
        assertEquals(setOf("device.json", "app.jsonl"), zip.keys)
        val metadata = JSONObject(checkNotNull(zip["device.json"]))
        assertEquals("available", metadata.getString("capability_status"))
        assertFalse(metadata.getBoolean("capability_results_truncated"))
        assertEquals(100, metadata.getInt("capability_results_limit"))
        val rows = metadata.getJSONArray("capability_results")
        assertEquals(3, rows.length())
        val unknown = rows.getJSONObject(0)
        assertEquals("TechnicalModel", unknown.getString("device_model"))
        assertEquals("TechnicalBuild", unknown.getString("os_build"))
        assertEquals("com.whatsapp", unknown.getString("wa_package"))
        assertEquals("1.2", unknown.getString("wa_version"))
        assertEquals(12, unknown.getLong("wa_version_code"))
        assertEquals("EARPIECE", unknown.getString("route"))
        assertEquals("MIC", unknown.getString("source"))
        assertEquals("COMPLETE", unknown.getString("status"))
        assertEquals(CapabilityMeter.TOTAL_FRAMES, unknown.getLong("sample_frames"))
        assertEquals(-20.0, unknown.getDouble("own_rms_db"), 0.0)
        assertEquals(-40.0, unknown.getDouble("remote_rms_db"), 0.0)
        assertEquals(1.25, unknown.getDouble("zero_pct"), 0.0)
        assertTrue(unknown.isNull("own_ok") && unknown.isNull("remote_ok") && unknown.isNull("client_silenced"))
        assertTrue(rows.getJSONObject(1).getBoolean("own_ok"))
        assertFalse(rows.getJSONObject(1).getBoolean("remote_ok"))
        assertEquals("TIMED_OUT", rows.getJSONObject(2).getString("status"))
        assertTrue(rows.getJSONObject(2).isNull("remote_ok"))
        assertFalse(zip.values.any { it.contains("SyntheticPrivate") || it.contains(clip.toString()) || it.contains(probe.runId.toString()) })
        assertFalse(unknown.has("recording_id") || unknown.has("run_id") || unknown.has("audio_path"))
        assertEquals(probe.copy(id = 1), database.capabilities().run(probe.runId).single())
        assertEquals("SyntheticPrivateTitle", database.recordings().recording(clip)?.title)
    }

    @Test fun emptyIsNotAnErrorAndOverflowExportsTheLatestHundredWithAnExplicitTruncationFlag() = runBlocking {
        val empty = JSONObject(checkNotNull(archive()["device.json"]))
        assertEquals("no_results", empty.getString("capability_status"))
        assertEquals(0, empty.getJSONArray("capability_results").length())
        database.withTransaction {
            repeat(103) { database.capabilities().insert(result(it.toLong())) }
        }
        val metadata = JSONObject(checkNotNull(archive()["device.json"]))
        val rows = metadata.getJSONArray("capability_results")
        assertEquals(100, rows.length())
        assertTrue(metadata.getBoolean("capability_results_truncated"))
        assertEquals(102, rows.getJSONObject(0).getLong("tested_at"))
        assertEquals(3, rows.getJSONObject(99).getLong("tested_at"))
    }

    @Test fun unreadableDatabaseIsExplicitWithoutLosingTheRestOfTheDiagnosticArchive() = runBlocking {
        ReflectionHelpers.setField(app, "openDatabase", null)
        ReflectionHelpers.setField(app, "keys\$delegate", lazy<KeyManager> {
            throw GeneralSecurityException("SyntheticPrivateKeyFailure")
        })
        val zip = archive()
        val metadata = JSONObject(checkNotNull(zip["device.json"]))
        assertEquals("unavailable", metadata.getString("capability_status"))
        assertTrue(metadata.isNull("capability_results"))
        assertEquals("GeneralSecurityException", metadata.getString("capability_error_type"))
        assertTrue(checkNotNull(zip["app.jsonl"]).contains("capability_read_failed"))
        assertFalse(zip.values.any { it.contains("SyntheticPrivate") })
        assertEquals(setOf("device.json", "app.jsonl"), zip.keys)
    }

    @Test fun cancellationPropagatesAndClosesTheDestinationWithoutProducingAnArchive() {
        ReflectionHelpers.setField(app, "openDatabase", null)
        ReflectionHelpers.setField(app, "keys\$delegate", lazy<KeyManager> {
            throw CancellationException("Synthetic cancelled export")
        })
        val output = object : ByteArrayOutputStream() {
            var closed = false
            override fun close() { closed = true; super.close() }
        }
        assertThrows(CancellationException::class.java) { runBlocking { exportDiagnostics(app, app.log, output) } }
        assertTrue(output.closed)
        assertEquals(0, output.size())
    }
}
