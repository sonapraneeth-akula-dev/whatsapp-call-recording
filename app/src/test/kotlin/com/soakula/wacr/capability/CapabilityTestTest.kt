package com.soakula.wacr.capability

import android.app.Application
import android.media.AudioRecord
import androidx.room.Room
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.soakula.wacr.crypto.CryptoStore
import com.soakula.wacr.data.AppDatabase
import com.soakula.wacr.data.AudioRoute
import com.soakula.wacr.data.CapabilityResult
import com.soakula.wacr.data.CapabilityStatus
import com.soakula.wacr.data.RecordingEntity
import com.soakula.wacr.data.RecordingFiles
import com.soakula.wacr.data.RecordingStatus
import com.soakula.wacr.recording.AacFormat
import com.soakula.wacr.recording.CaptureEngineTest
import com.soakula.wacr.recording.CaptureSource
import com.soakula.wacr.recording.LowStorageException
import com.soakula.wacr.recording.StorageGuard
import com.soakula.wacr.util.AppLog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioRecord
import java.io.IOException
import java.nio.channels.Channels
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [33], application = Application::class, manifest = Config.NONE,
    shadows = [CaptureEngineTest.RecordingMonitorShadow::class]
)
class CapabilityTestTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var database: AppDatabase
    private lateinit var files: RecordingFiles
    private lateinit var crypto: CryptoStore
    private lateinit var log: AppLog
    private var route: AudioRoute? = AudioRoute.EARPIECE
    private var reads = 0
    private var freeBytes = 1_000_000_000L
    private var elapsed = 0L
    private var cancelled = false
    private var failProgress = false
    private var deadSource: CaptureSource? = null
    private val inputs = mutableSetOf<AudioRecord>()
    private val progress = mutableListOf<CapabilityProgress>()
    private val device = CapabilityDevice("Synthetic phone", "Synthetic build", "com.whatsapp", "synthetic", 1)

    @Before fun createStores() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).build()
        files = RecordingFiles(temporary.newFolder())
        StreamingAeadConfig.register()
        crypto = CryptoStore(files, KeysetHandle.generateNew(PredefinedStreamingAeadParameters.AES256_GCM_HKDF_4KB)
            .getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java))
        log = AppLog(temporary.newFolder(), false, {}, { throw it })
        CaptureEngineTest.RecordingMonitorShadow.silenceNextConfig = false
        ShadowAudioRecord.setSourceProvider { record ->
            inputs += record
            object : ShadowAudioRecord.AudioRecordSource {
                override fun readInShortArray(data: ShortArray, offset: Int, size: Int, blocking: Boolean): Int {
                    if (record.audioSource == deadSource?.androidId) return AudioRecord.ERROR_DEAD_OBJECT
                    reads++
                    data.fill(1000, offset, offset + size)
                    return size
                }
            }
        }
    }

    @After fun closeStores() { database.close() }

    private fun worker() = CapabilityTest(database, files, crypto, log,
        StorageGuard({ freeBytes }, { elapsed }), { route }, { 1000L }, { elapsed })

    private fun run(sources: List<CaptureSource> = listOf(CaptureSource.VOICE_RECOGNITION)) = worker().run(
        device, AudioRoute.EARPIECE, sources, { cancelled }, {
            progress += it
            if (failProgress) throw IOException("synthetic foreground failure")
        }, { _, _ -> }, {}
    )

    @Test fun sweepsEveryUnprivilegedSourceIntoEncryptedClipsAndPrivateUnreviewedRows() = runBlocking {
        val rows = run(CaptureSource.entries)
        assertEquals(CaptureSource.entries, rows.map { it.source })
        assertEquals(1, rows.map { it.runId }.distinct().size)
        assertEquals(rows, database.capabilities().run(rows.first().runId))
        assertEquals(5, database.capabilities().observeRecent().first().size)
        for (row in rows) {
            assertEquals(device, row.device)
            assertEquals(AudioRoute.EARPIECE, row.route)
            assertEquals(CapabilityStatus.COMPLETE, row.status)
            assertEquals(480_000L, row.sampleFrames)
            assertTrue(checkNotNull(row.ownRmsDb) > -50)
            assertTrue(checkNotNull(row.remoteRmsDb) > -50)
            assertEquals(false, row.clientSilenced)
            assertNull(row.ownOk)
            assertNull(row.remoteOk)
            val id = checkNotNull(row.recordingId)
            val recording = checkNotNull(database.recordings().recording(id))
            assertEquals(row.source.name, recording.captureSource)
            assertEquals(RecordingStatus.FINALIZED, recording.status)
            assertNull(recording.remoteCaptured)
            assertNull(recording.sessionId)
            assertTrue(database.recordings().segments(id).isEmpty())
            assertEquals(listOf("audio.aac.enc"), files.file(id, "audio.aac.enc").parentFile!!.listFiles()!!.map { it.name })
            // Host codec copies bytes: authenticated orchestration, not native AAC/audibility evidence.
            val audio = Channels.newInputStream(crypto.openReader(id, "audio.aac.enc")).use { AacFormat(1).inspect(it) }
            assertTrue(audio.durationUs >= 30_000_000L)
            assertEquals(recording.durationMs, audio.durationUs / 1000)
        }
        assertEquals(5, inputs.size)
        assertTrue(inputs.all { it.state == AudioRecord.STATE_UNINITIALIZED })
        assertEquals(CapabilityPhase.YOU, progress.first().phase)
        assertTrue(progress.any { it.phase == CapabilityPhase.OTHER })
        assertEquals(CapabilityPhase.SAVING, progress.last().phase)
        assertTrue(progress.all { it.elapsedMs in 0..30_000 })
    }

    @Test fun unavailableSourceDoesNotFallbackUnderItsNameAndDoesNotPreventTheNextProbe() = runBlocking {
        deadSource = CaptureSource.VOICE_RECOGNITION
        val rows = run(listOf(CaptureSource.VOICE_RECOGNITION, CaptureSource.MIC))
        val failed = rows.first()
        assertEquals(CaptureSource.VOICE_RECOGNITION, failed.source)
        assertEquals(CapabilityStatus.UNAVAILABLE, failed.status)
        assertNull(failed.recordingId)
        assertNull(failed.ownRmsDb)
        assertNull(failed.remoteRmsDb)
        assertNull(failed.ownOk)
        assertNull(failed.remoteOk)
        assertEquals(CaptureSource.MIC, rows.last().source)
        assertEquals(CapabilityStatus.COMPLETE, rows.last().status)
        assertTrue(database.recordings().needsRecovery().isEmpty())
        assertTrue(inputs.all { it.state == AudioRecord.STATE_UNINITIALIZED })
    }

    @Test fun frameworkSilencedProbePersistsTheRefusalWithoutInventingAudibility() = runBlocking {
        CaptureEngineTest.RecordingMonitorShadow.silenceNextConfig = true
        val result = run().single()
        assertEquals(CapabilityStatus.UNAVAILABLE, result.status)
        assertEquals(true, result.clientSilenced)
        assertEquals(0L, result.sampleFrames)
        assertNull(result.ownOk)
        assertNull(result.remoteOk)
        assertNull(result.recordingId)
        assertTrue(database.recordings().needsRecovery().isEmpty())
    }

    @Test fun routeChangeStopsTheSweepAndRetainsOnlyAnExplicitlyIncompleteClip() = runBlocking {
        val rows = worker().run(device, AudioRoute.EARPIECE, CaptureSource.entries, { false }, {
            if (it.elapsedMs > 0) route = AudioRoute.SPEAKER
        }, { _, _ -> }, {})
        val result = rows.single()
        assertEquals(CapabilityStatus.ROUTE_CHANGED, result.status)
        assertTrue(result.sampleFrames in 1 until CapabilityMeter.TOTAL_FRAMES)
        assertNull(result.remoteRmsDb)
        assertNull(result.ownOk)
        assertNull(result.remoteOk)
        assertNotNull(result.recordingId)
        assertEquals(1, inputs.size)
        assertTrue(inputs.all { it.state == AudioRecord.STATE_UNINITIALIZED })
    }

    @Test fun cancellationBeforeStartAndMismatchedOrUnknownRoutesNeverOpenInput() = runBlocking {
        cancelled = true
        assertTrue(run().isEmpty())
        cancelled = false
        route = null
        assertThrows(IllegalStateException::class.java) { run() }
        route = AudioRoute.BLUETOOTH
        assertThrows(IllegalStateException::class.java) { run() }
        assertEquals(0, reads)
        assertTrue(inputs.isEmpty())
        assertTrue(database.capabilities().observeRecent().first().isEmpty())
    }

    @Test fun cancellingAnActiveProbeFinalizesItsClipAndNeverProbesAnotherSource() {
        val result = worker().run(device, AudioRoute.EARPIECE, CaptureSource.entries, { cancelled }, {
            if (it.elapsedMs > 0) cancelled = true
        }, { _, _ -> }, {}).single()
        assertEquals(CapabilityStatus.CANCELLED, result.status)
        assertNotNull(result.recordingId)
        assertNull(result.ownOk)
        assertNull(result.remoteOk)
        assertEquals(1, inputs.size)
        assertTrue(inputs.all { it.state == AudioRecord.STATE_UNINITIALIZED })
    }

    @Test fun diskStopDoesNotContinueTheSweepOrTurnAPartialMeasurementIntoSuccess() {
        val result = worker().run(device, AudioRoute.EARPIECE, CaptureSource.entries, { false }, {
            if (it.elapsedMs > 0) {
                elapsed += StorageGuard.INTERVAL_MS
                freeBytes = StorageGuard.STOP_BYTES - 1
            }
        }, { _, _ -> }, {}).single()
        assertEquals(CapabilityStatus.LOW_STORAGE, result.status)
        assertNotNull(result.recordingId)
        assertTrue(result.sampleFrames < CapabilityMeter.TOTAL_FRAMES)
        assertNull(result.ownOk)
        assertNull(result.remoteOk)
        assertEquals(1, inputs.size)
        freeBytes = StorageGuard.STOP_BYTES - 1
        assertThrows(LowStorageException::class.java) { run() }
    }

    @Test fun realPipelineFailurePropagatesRatherThanClaimingAnUnsupportedSource() = runBlocking {
        failProgress = true
        val error = assertThrows(IOException::class.java) { run() }
        assertEquals("synthetic foreground failure", error.message)
        assertTrue(database.capabilities().observeRecent().first().isEmpty())
        assertTrue(inputs.all { it.state == AudioRecord.STATE_UNINITIALIZED })
        assertFalse(database.recordings().needsRecovery().isEmpty())
    }

    @Test fun slowButNonemptyInputHasAMonotonicDeadlineInsteadOfHoldingTheMicrophoneForever() {
        val result = worker().run(device, AudioRoute.EARPIECE, CaptureSource.entries, { false }, {
            if (it.phase != CapabilityPhase.SAVING && it.elapsedMs > 0) {
                elapsed = if (elapsed == 0L) 34_999 else 35_000
            }
        }, { _, _ -> }, {}).single()
        assertEquals(CapabilityStatus.TIMED_OUT, result.status)
        assertEquals(2048L, result.sampleFrames)
        assertNull(result.ownOk)
        assertNull(result.remoteOk)
        assertNotNull(result.recordingId)
        assertEquals(1, inputs.size)
        assertTrue(inputs.all { it.state == AudioRecord.STATE_UNINITIALIZED })
    }

    @Test fun rejectsDuplicateSourcesInvalidMetadataAndUnrecoveredRecordings() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) { run(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { run(listOf(CaptureSource.MIC, CaptureSource.MIC)) }
        assertThrows(IllegalArgumentException::class.java) { device.copy(waPackage = "synthetic.unrelated") }
        assertThrows(IllegalArgumentException::class.java) { device.copy(waVersion = "") }
        val id = UUID.randomUUID()
        database.recordings().insertRecording(RecordingEntity(id, 1, 1, "MIC"))
        assertThrows(IllegalStateException::class.java) { run() }
        assertEquals(0, reads)
    }

    @Test fun rejectsInvalidMetricsAndPreservesMeasurementWhenItsClipIsDeleted() = runBlocking {
        val result = run().single()
        assertThrows(IllegalArgumentException::class.java) { result.copy(sampleFrames = 1) }
        assertThrows(IllegalArgumentException::class.java) { result.copy(ownRmsDb = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { result.copy(zeroPct = 101.0) }
        assertThrows(IllegalArgumentException::class.java) {
            CapabilityResult(UUID.randomUUID(), device, AudioRoute.EARPIECE, CaptureSource.MIC,
                CapabilityStatus.UNAVAILABLE, 1, ownOk = true)
        }
        database.recordings().deleteRecording(checkNotNull(result.recordingId))
        val retained = database.capabilities().run(result.runId).single()
        assertNull(retained.recordingId)
        assertEquals(result.sampleFrames, retained.sampleFrames)
        assertEquals(result.ownRmsDb, retained.ownRmsDb)
        assertNull(retained.ownOk)
        assertNull(retained.remoteOk)
    }
}
