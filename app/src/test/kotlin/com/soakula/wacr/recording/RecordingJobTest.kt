package com.soakula.wacr.recording

import android.app.Application
import android.annotation.SuppressLint
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.media.AudioRecord
import androidx.room.Room
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.soakula.wacr.crypto.CryptoStore
import com.soakula.wacr.data.*
import com.soakula.wacr.util.AppLog
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioRecord
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowLooper
import java.io.IOException
import java.nio.channels.Channels
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [33], application = Application::class, manifest = Config.NONE,
    shadows = [CaptureEngineTest.RecordingMonitorShadow::class]
)
class RecordingJobTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var database: AppDatabase
    private lateinit var files: RecordingFiles
    private lateinit var crypto: CryptoStore
    private lateinit var log: AppLog
    private var reads = 0
    private var elapsed = 0L
    private var bytes = 1_000_000_000L
    private val inputs = mutableListOf<AudioRecord>()

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
                    reads++
                    data.fill(100, offset, offset + size)
                    return size
                }
            }
        }
    }

    @After fun closeStores() { database.close() }

    private fun job() = RecordingJob(
        database, files, crypto, log, StorageGuard({ bytes }, { elapsed }), now = { 1000L }
    )

    @Test fun stopFlushesEncryptsFinalizesAndCommitsMetadataBeforeRemovingSegments() = runBlocking {
        val id = UUID.randomUUID()
        val progress = mutableListOf<Pair<Long, Boolean>>()
        var saving = false
        val result = checkNotNull(job().run(id, null, { reads >= 90 }, { at, low -> progress += at to low }, { saving = true }))
        assertEquals(RecordingStopReason.REQUESTED, result.reason)
        assertEquals(listOf(1000L to false), progress)
        assertTrue(saving)
        assertTrue(inputs.all { it.state == AudioRecord.STATE_UNINITIALIZED })
        val row = checkNotNull(database.recordings().recording(id))
        assertEquals(RecordingStatus.FINALIZED, row.status)
        assertEquals("VOICE_RECOGNITION", row.captureSource)
        assertNull(row.remoteCaptured)
        assertEquals(result.audio.durationUs / 1000, row.durationMs)
        assertEquals(result.audio.encryptedBytes, row.sizeBytes)
        assertTrue(files.segmentIndices(id).isEmpty())
        assertTrue(database.recordings().segments(id).isEmpty())
        val inspected = Channels.newInputStream(crypto.openReader(id, "audio.aac.enc")).use { AacFormat(1).inspect(it) }
        assertEquals(result.audio.durationUs, inspected.durationUs)
        assertEquals(result.audio.plainBytes, inspected.bytes)
        // Robolectric's codec copies bytes; this checks orchestration, not native AAC encoding.
        assertTrue(inspected.durationUs > SegmentWriter.MAX_DURATION_US)
    }

    @Test fun cancellationBeforeStartAndLowSpaceNeverOpenTheMicrophoneOrCreateMetadata() = runBlocking<Unit> {
        val cancelled = UUID.randomUUID()
        assertNull(job().run(cancelled, null, { true }, { _, _ -> fail() }, { fail() }))
        assertNull(database.recordings().recording(cancelled))
        val refused = UUID.randomUUID()
        bytes = StorageGuard.STOP_BYTES - 1
        assertThrows(LowStorageException::class.java) {
            job().run(refused, null, { false }, { _, _ -> fail() }, { fail() })
        }
        assertEquals(0, reads)
        assertNull(database.recordings().recording(refused))
        assertTrue(files.segmentIndices(refused).isEmpty())
    }

    @Test fun storageWarningAndForcedStopStillFinalizeAuthenticatedAudio() = runBlocking {
        val id = UUID.randomUUID()
        val warnings = mutableListOf<Boolean>()
        val result = checkNotNull(job().run(id, null, {
            elapsed += StorageGuard.INTERVAL_MS
            bytes = when {
                reads >= 3 -> StorageGuard.STOP_BYTES - 1
                reads >= 1 -> StorageGuard.WARN_BYTES - 1
                else -> 1_000_000_000
            }
            false
        }, { _, warning -> warnings += warning }, {}))
        assertEquals(listOf(false, true), warnings)
        assertEquals(RecordingStopReason.LOW_STORAGE, result.reason)
        assertEquals(3, reads)
        assertEquals(RecordingStatus.FINALIZED, database.recordings().recording(id)?.status)
    }

    @Test fun failureRetainsEncryptedAudioForRecoveryInsteadOfClaimingSuccess() = runBlocking {
        val id = UUID.randomUUID()
        val failure = assertThrows(IOException::class.java) {
            job().run(id, null, { reads >= 90 }, { _, _ -> }, { throw IOException("synthetic saving failure") })
        }
        assertEquals("synthetic saving failure", failure.message)
        assertTrue(inputs.all { it.state == AudioRecord.STATE_UNINITIALIZED })
        assertEquals(RecordingStatus.RECORDING, database.recordings().recording(id)?.status)
        assertTrue(files.segmentIndices(id).isNotEmpty())
        assertFalse(files.file(id, "audio.aac.enc").exists())
        assertEquals(RecoveryReport(1, 0, 0), Recovery(database, files, crypto, log).run())
        assertEquals(RecordingStatus.RECOVERED, database.recordings().recording(id)?.status)
    }

    @Test fun immediateStopAfterSetupDoesNotInventAnAudioFile() = runBlocking<Unit> {
        val id = UUID.randomUUID()
        var stop = false
        assertThrows(EmptyRecordingException::class.java) {
            job().run(id, null, { stop }, { _, _ -> stop = true }, {})
        }
        assertEquals(0, reads)
        assertEquals(RecordingStatus.FAILED, database.recordings().recording(id)?.status)
        assertFalse(files.file(id, "audio.aac.enc").exists())
    }

    @Test fun routeChangesReopenCapturePersistRelativeTimesAndKeepOneRecording() = runBlocking {
        val context: Application = RuntimeEnvironment.getApplication()
        selectRoute(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
        val id = UUID.randomUUID()
        RouteWatcher(context, log, { elapsed }).use { watcher ->
            val worker = RecordingJob(
                database, files, crypto, log, StorageGuard({ bytes }, { elapsed }),
                routes = watcher, now = { 1000L }, elapsedMs = { elapsed }
            )
            var switched = false
            val result = checkNotNull(worker.run(id, null, {
                if (reads == 1 && !switched) {
                    switched = true
                    elapsed = 64
                    selectRoute(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
                }
                reads >= 3
            }, { _, _ -> }, {}))
            assertEquals(RecordingStopReason.REQUESTED, result.reason)
        }
        val routes = database.recordings().routes(id)
        assertEquals(listOf(AudioRoute.EARPIECE, AudioRoute.BLUETOOTH), routes.map { it.route })
        assertEquals(listOf(0L, 64L), routes.map { it.atMs })
        assertEquals(2, inputs.distinct().size)
        assertTrue(inputs.all { it.state == AudioRecord.STATE_UNINITIALIZED })
        assertEquals(RecordingStatus.FINALIZED, database.recordings().recording(id)?.status)
    }

    @SuppressLint("MissingPermission")
    private fun selectRoute(type: Int) {
        val context: Application = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(AudioManager::class.java)
        val device = AudioDeviceInfoBuilder.newBuilder().setType(type).build()
        shadowOf(manager).addAvailableCommunicationDevice(device, false)
        assertTrue(manager.setCommunicationDevice(device))
        shadowOf(manager).callOnCommunicationDeviceChangedListeners(device)
        ShadowLooper.idleMainLooper()
    }
}
