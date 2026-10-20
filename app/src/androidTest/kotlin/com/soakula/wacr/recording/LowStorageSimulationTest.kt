package com.soakula.wacr.recording

import android.Manifest
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.soakula.wacr.MainActivity
import com.soakula.wacr.WacrApp
import com.soakula.wacr.crypto.CryptoStore
import com.soakula.wacr.data.AppDatabase
import com.soakula.wacr.data.RecordingFiles
import com.soakula.wacr.data.RecordingStatus
import com.soakula.wacr.util.AppLog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LowStorageSimulationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @get:Rule val temporary = TemporaryFolder(context.cacheDir)

    @Test(timeout = 30_000)
    @android.annotation.SuppressLint("MissingPermission")
    fun simulatedDiskPressureStopsRealCaptureAndLeavesDecodableAuthenticatedAac() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            activity.onActivity {
                val app = it.application as WacrApp
                val state = app.recorder.state.value
                check(state !is RecorderState.Preparing && state !is RecorderState.Recording && state !is RecorderState.Saving) {
                    "Stop the current recording before running this isolated simulation"
                }
                app.stopPlaybackForCapture()
            }
            StreamingAeadConfig.register()
            val files = RecordingFiles(temporary.newFolder())
            val store = CryptoStore(files, KeysetHandle.generateNew(PredefinedStreamingAeadParameters.AES256_GCM_HKDF_4KB)
                .getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java))
            val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            try {
                val id = UUID.randomUUID()
                var samplingStartedAt: Long? = null
                var checks = 0
                var saving = 0
                val warnings = mutableListOf<Boolean>()
                val guard = StorageGuard({
                    when (++checks) {
                        1, 2 -> StorageGuard.WARN_BYTES
                        3 -> StorageGuard.WARN_BYTES - 1
                        else -> StorageGuard.STOP_BYTES - 1
                    }
                }, {
                    samplingStartedAt?.let {
                        (SystemClock.elapsedRealtime() - it) / 1000 * StorageGuard.INTERVAL_MS
                    } ?: 0L
                })
                val log = AppLog(temporary.newFolder(), true, {}, { throw it })
                val result = checkNotNull(RecordingJob(database, files, store, log, guard).run(id, null, { false },
                    { _, warning ->
                        if (samplingStartedAt == null) samplingStartedAt = SystemClock.elapsedRealtime()
                        warnings += warning
                    }, { saving++ }))
                assertEquals(RecordingStopReason.LOW_STORAGE, result.reason)
                assertEquals(listOf(false, true), warnings)
                assertEquals(4, checks)
                assertEquals(1, saving)
                val row = checkNotNull(runBlocking { database.recordings().recording(id) })
                assertEquals(RecordingStatus.FINALIZED, row.status)
                assertEquals(result.audio.durationUs / 1000, row.durationMs)
                assertEquals(result.audio.encryptedBytes, row.sizeBytes)
                assertTrue(result.audio.durationUs > 0)
                assertNull(row.remoteCaptured)
                assertTrue(files.segmentIndices(id).isEmpty())
                assertTrue(runBlocking { database.recordings().segments(id) }.isEmpty())
                assertEquals(listOf("audio.aac.enc"), files.file(id, "audio.aac.enc").parentFile!!.listFiles()!!.map { it.name })
                ChannelSource(store.openReader(id, "audio.aac.enc")).use { decode(it) }
            } finally { database.close() }
        }
    }

    private fun decode(source: MediaDataSource) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source)
            assertEquals(1, extractor.trackCount)
            extractor.selectTrack(0)
            val format = extractor.getTrackFormat(0)
            assertEquals("audio/mp4a-latm", format.getString(MediaFormat.KEY_MIME))
            assertEquals(16_000, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
            assertEquals(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            val codec = MediaCodec.createDecoderByType("audio/mp4a-latm")
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inputEnded = false
                var outputEnded = false
                var pcmBytes = 0L
                val deadline = SystemClock.elapsedRealtime() + 10_000
                while (!outputEnded && SystemClock.elapsedRealtime() < deadline) {
                    if (!inputEnded) {
                        val input = codec.dequeueInputBuffer(10_000)
                        if (input >= 0) {
                            val buffer = checkNotNull(codec.getInputBuffer(input)).apply { clear() }
                            val size = extractor.readSampleData(buffer, 0)
                            inputEnded = size < 0
                            codec.queueInputBuffer(input, 0, size.coerceAtLeast(0),
                                if (inputEnded) 0 else extractor.sampleTime,
                                if (inputEnded) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                            if (!inputEnded) extractor.advance()
                        }
                    }
                    val output = codec.dequeueOutputBuffer(info, 10_000)
                    if (output >= 0) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) pcmBytes += info.size
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(output, false)
                    }
                }
                assertTrue("Native AAC decoder did not reach EOS", outputEnded)
                assertTrue("Finalized recording did not decode to PCM", pcmBytes > 0)
            } finally { codec.release() }
        } finally { extractor.release() }
    }

    private class ChannelSource(private val channel: SeekableByteChannel) : MediaDataSource() {
        @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            require(position >= 0 && offset >= 0 && size >= 0 && offset <= buffer.size - size)
            if (size == 0) return 0
            if (position >= channel.size()) return -1
            channel.position(position)
            return channel.read(ByteBuffer.wrap(buffer, offset, size))
        }
        @Synchronized override fun getSize(): Long = channel.size()
        @Synchronized override fun close() = channel.close()
    }
}
