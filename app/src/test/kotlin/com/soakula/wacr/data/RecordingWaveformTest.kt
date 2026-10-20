package com.soakula.wacr.data

import android.app.Application
import androidx.room.Room
import com.soakula.wacr.recording.CommittedSegment
import com.soakula.wacr.recording.FinalizedAudio
import com.soakula.wacr.recording.WaveformPeaks
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, manifest = Config.NONE)
class RecordingWaveformTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: RecordingRepository
    @Before fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).build()
        repository = RecordingRepository(database)
    }
    @After fun closeDatabase() { database.close() }

    @Test fun durableCheckpointSurvivesRecoveryButCannotOutliveAuthenticatedAudioOrRecording() = runBlocking {
        val id = UUID.randomUUID()
        repository.start(RecordingEntity(id, 1000, 1, "MIC"))
        val checkpoint = WaveformPeaks(1600, 2048, byteArrayOf(100, 200.toByte()))
        repository.commitSegment(id, CommittedSegment(1, 64_000, 20), 2000, checkpoint)
        assertEquals(2048L, database.recordings().waveform(id)?.decode()?.frameCount)
        repository.finalize(id, FinalizedAudio(64_000, 20, 80), recovered = true)
        val saved = checkNotNull(database.recordings().waveform(id)).decode()
        assertEquals(1024L, saved.frameCount)
        assertArrayEquals(byteArrayOf(100), saved.encodedPeaks())
        repository.completeCleanup(id)
        assertNotNull(database.recordings().waveform(id))
        repository.finalize(id, FinalizedAudio(64_000, 20, 80))
        assertEquals(1024L, database.recordings().waveform(id)?.decode()?.frameCount)
        database.recordings().deleteRecording(id)
        assertNull(database.recordings().waveform(id))
    }

    @Test fun normalFinalizationPublishesPeaksWithMetadataWhileLegacyFilesRemainHonestlyMissing() = runBlocking {
        val id = UUID.randomUUID()
        repository.start(RecordingEntity(id, 1000, 1, "MIC"))
        repository.finalize(id, FinalizedAudio(64_000, 20, 80),
            waveform = WaveformPeaks(1600, 1000, byteArrayOf(255.toByte())))
        assertEquals(RecordingStatus.FINALIZED, database.recordings().recording(id)?.status)
        assertEquals(1000L, database.recordings().waveform(id)?.decode()?.frameCount)
        val old = UUID.randomUUID()
        repository.start(RecordingEntity(old, 2000, 1, "MIC"))
        repository.finalize(old, FinalizedAudio(64_000, 20, 80))
        assertNull(database.recordings().waveform(old))
    }
}
