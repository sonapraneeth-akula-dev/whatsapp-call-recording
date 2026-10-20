package com.soakula.wacr.capability

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.soakula.wacr.data.*
import com.soakula.wacr.recording.CaptureSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
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
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class CapabilityProfilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var database: AppDatabase
    private lateinit var preferences: AppPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val device = CapabilityDevice("Synthetic phone", "Synthetic OS", "com.whatsapp", "synthetic", 1)
    private val profiles get() = CapabilityProfiles(database, preferences)

    @Before fun createStores() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).build()
        val file = temporary.newFile("settings.preferences_pb")
        preferences = AppPreferences(PreferenceDataStoreFactory.create(scope = scope) { file })
    }

    @After fun closeStores() = runBlocking {
        database.close()
        scope.coroutineContext[Job]!!.cancelAndJoin()
    }

    private suspend fun result(
        source: CaptureSource = CaptureSource.MIC, stamp: CapabilityDevice = device,
        route: AudioRoute = AudioRoute.EARPIECE, status: CapabilityStatus = CapabilityStatus.COMPLETE
    ): CapabilityResult {
        val clip = UUID.randomUUID()
        database.recordings().insertRecording(RecordingEntity(
            clip, 1, 1, source.name, status = RecordingStatus.FINALIZED, durationMs = 30_000,
            sizeBytes = 100, audioPath = "$clip/audio.aac.enc"
        ))
        val complete = status == CapabilityStatus.COMPLETE
        val row = CapabilityResult(
            UUID.randomUUID(), stamp, route, source, status, 1000, clip,
            sampleFrames = if (complete) CapabilityMeter.TOTAL_FRAMES else 0,
            ownRmsDb = if (complete) -25.0 else null, remoteRmsDb = if (complete) -30.0 else null,
            zeroPct = if (complete) 0.0 else null
        )
        return row.copy(id = database.capabilities().insert(row))
    }

    @Test fun energyAloneNeverChangesTheProvisionalProfileAndExplicitReviewIsRouteScoped() = runBlocking {
        val row = result()
        assertEquals(listOf(CaptureSource.VOICE_RECOGNITION), profiles.load(device).sourcesFor(AudioRoute.EARPIECE))
        profiles.review(row.id, device, true, true)
        val profile = profiles.load(device)
        assertEquals(listOf(CaptureSource.MIC), profile.sourcesFor(AudioRoute.EARPIECE))
        assertEquals(listOf(CaptureSource.VOICE_RECOGNITION), profile.sourcesFor(AudioRoute.BLUETOOTH))
        assertEquals(listOf(CaptureSource.VOICE_RECOGNITION), profile.sourcesFor(null))
        val cache = JSONObject(checkNotNull(preferences.captureProfileJson.first()))
        assertEquals("com.whatsapp", cache.getString("wa_package"))
        assertEquals("MIC", cache.getJSONObject("routes").getJSONArray("EARPIECE").getString(0))
    }

    @Test fun newerUnreviewedOrFailedProbeInvalidatesAnOlderApprovedSource() = runBlocking {
        val first = result()
        profiles.review(first.id, device, true, true)
        assertEquals(listOf(CaptureSource.MIC), profiles.load(device).sourcesFor(AudioRoute.EARPIECE))
        result()
        assertEquals(listOf(CaptureSource.VOICE_RECOGNITION), profiles.load(device).sourcesFor(AudioRoute.EARPIECE))
        val third = result()
        profiles.review(third.id, device, true, true)
        result(status = CapabilityStatus.UNAVAILABLE)
        assertEquals(listOf(CaptureSource.VOICE_RECOGNITION), profiles.load(device).sourcesFor(AudioRoute.EARPIECE))
    }

    @Test fun eitherNegativeVerdictRemovesTheSourceWithoutInventingRemoteCapture() = runBlocking {
        val row = result()
        profiles.review(row.id, device, true, false)
        assertEquals(listOf(CaptureSource.VOICE_RECOGNITION), profiles.load(device).sourcesFor(AudioRoute.EARPIECE))
        assertNull(database.recordings().recording(checkNotNull(row.recordingId))?.remoteCaptured)
        profiles.review(row.id, device, false, true)
        assertTrue(profiles.load(device).routeSources.isEmpty())
    }

    @Test fun scopesProfilesToExactDeviceBuildPackageAndBothVersionIdentifiers() = runBlocking {
        val row = result()
        profiles.review(row.id, device, true, true)
        for (different in listOf(
            device.copy(model = "Other phone"), device.copy(osBuild = "Other OS"),
            device.copy(waPackage = "com.whatsapp.w4b"), device.copy(waVersion = "other"),
            device.copy(waVersionCode = 2)
        )) {
            assertTrue(profiles.load(different).routeSources.isEmpty())
            assertThrows(IllegalStateException::class.java) {
                runBlocking { profiles.review(row.id, different, true, true) }
            }
        }
    }

    @Test fun partialMissingDeletedOrPendingDeletionClipsCannotAcquireNewVerdicts() = runBlocking {
        val partial = result(status = CapabilityStatus.CANCELLED)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { profiles.review(partial.id, device, true, true) }
        }
        val deleted = result()
        database.recordings().deleteRecording(checkNotNull(deleted.recordingId))
        assertThrows(IllegalStateException::class.java) {
            runBlocking { profiles.review(deleted.id, device, true, true) }
        }
        val pending = result()
        database.recordings().insertDeletion(PendingRecordingDeletion(
            checkNotNull(pending.recordingId), UUID.randomUUID(), 1000, 1
        ))
        assertThrows(IllegalStateException::class.java) {
            runBlocking { profiles.review(pending.id, device, true, true) }
        }
        assertTrue(profiles.load(device).routeSources.isEmpty())
    }

    @Test fun deletingAPreviouslyReviewedClipKeepsItsHistoricalListeningVerdict() = runBlocking {
        val row = result()
        profiles.review(row.id, device, true, true)
        database.recordings().deleteRecording(checkNotNull(row.recordingId))
        assertEquals(listOf(CaptureSource.MIC), profiles.load(device).sourcesFor(AudioRoute.EARPIECE))
    }
}
