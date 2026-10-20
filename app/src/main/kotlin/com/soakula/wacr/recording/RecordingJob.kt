package com.soakula.wacr.recording

import android.Manifest
import android.os.SystemClock
import androidx.annotation.RequiresPermission
import com.soakula.wacr.crypto.CryptoStore
import com.soakula.wacr.data.AppDatabase
import com.soakula.wacr.data.RecordingEntity
import com.soakula.wacr.data.RecordingFiles
import com.soakula.wacr.data.RecordingRepository
import com.soakula.wacr.util.AppLog
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.UUID

enum class RecordingStopReason { REQUESTED, LOW_STORAGE }
data class RecordingResult(val audio: FinalizedAudio, val reason: RecordingStopReason)
class LowStorageException : IOException("Less than 100 MB available")
class EmptyRecordingException : IOException("Recording ended without authenticated audio")

/** Blocking, single-worker pipeline. Caller must finish recovery before constructing it. */
class RecordingJob(
    database: AppDatabase,
    private val files: RecordingFiles,
    private val crypto: CryptoStore,
    private val log: AppLog,
    private val storage: StorageGuard,
    private val profile: CaptureProfile = CaptureProfile(),
    private val routes: RouteWatcher? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime
) {
    private val repository = RecordingRepository(database)

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun run(
        id: UUID,
        sessionId: Long?,
        shouldStop: () -> Boolean,
        onRecording: (startedAt: Long, lowStorage: Boolean) -> Unit,
        onSaving: () -> Unit
    ): RecordingResult? {
        if (shouldStop()) return null
        val initialStorage = storage.beforeStart()
        if (initialStorage.level == StorageLevel.STOP) throw LowStorageException()
        if (shouldStop()) return null
        val segments = mutableListOf<CommittedSegment>()
        var reason = RecordingStopReason.REQUESTED
        val initialRoute = routes?.takeInitialRoute()
        CaptureEngine(profile, initialRoute, log).use { capture ->
            if (shouldStop()) return null
            val startedAt = now()
            val startedElapsed = elapsedMs()
            runBlocking { repository.start(RecordingEntity(id, startedAt, 1, capture.source.name, sessionId = sessionId)) }
            if (initialRoute != null) runBlocking { repository.recordRoute(id, initialRoute, 0) }
            SegmentWriter(id, crypto, capture.format) { segment ->
                runBlocking { repository.commitSegment(id, segment, now()) }
                segments += segment
            }.use { writer ->
                AacEncoder(capture.format, writer::write).use { encoder ->
                    var warning = initialStorage.level == StorageLevel.WARN
                    onRecording(startedAt, warning)
                    val pcm = ShortArray(1024)
                    while (!shouldStop()) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException("Recording worker interrupted")
                        val level = storage.poll().level
                        if (level == StorageLevel.STOP) {
                            reason = RecordingStopReason.LOW_STORAGE
                            break
                        }
                        if ((level == StorageLevel.WARN) != warning) {
                            warning = level == StorageLevel.WARN
                            onRecording(startedAt, warning)
                        }
                        for (change in routes?.changes().orEmpty()) {
                            if (change.route != null) runBlocking {
                                repository.recordRoute(id, change.route, maxOf(0, change.elapsedMs - startedElapsed))
                            }
                            capture.changeRoute(change.route)
                        }
                        val count = capture.read(pcm)
                        if (count > 0) encoder.encode(pcm, count) else Thread.sleep(10)
                    }
                }
            }
        }
        onSaving()
        if (segments.isEmpty()) {
            runBlocking { repository.failEmptyRecording(id) }
            throw EmptyRecordingException()
        }
        val audio = Finalizer(files, crypto, AacFormat(1)) { _, result ->
            runBlocking { repository.finalize(id, result) }
        }.finish(id, segments)
        runBlocking { repository.completeCleanup(id) }
        log.log("recorder", "finalized", mapOf(
            "recording_id" to id, "duration_us" to audio.durationUs, "stop_reason" to reason.name
        ))
        return RecordingResult(audio, reason)
    }
}
