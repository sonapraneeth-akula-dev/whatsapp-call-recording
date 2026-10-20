package com.soakula.wacr.recording

import com.soakula.wacr.crypto.CryptoStore
import com.soakula.wacr.data.AppDatabase
import com.soakula.wacr.data.RecordingEntity
import com.soakula.wacr.data.RecordingFiles
import com.soakula.wacr.data.RecordingRepository
import com.soakula.wacr.data.RecordingStatus
import com.soakula.wacr.util.AppLog
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.security.GeneralSecurityException

data class RecoveryReport(val completed: Int, val failed: Int, val errors: Int)

/** Run before any capture starts; never inspect or repair a live recording. */
class Recovery(
    private val database: AppDatabase,
    private val files: RecordingFiles,
    private val crypto: CryptoStore,
    private val log: AppLog
) {
    private val repository = RecordingRepository(database)

    suspend fun run(): RecoveryReport {
        var completed = 0
        var failed = 0
        var errors = 0
        for (recording in database.recordings().needsRecovery()) {
            currentCoroutineContext().ensureActive()
            try {
                if (recover(recording)) completed++ else failed++
            } catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                reportError(recording, error)
                errors++
            } catch (error: GeneralSecurityException) {
                reportError(recording, error)
                errors++
            } catch (error: IllegalArgumentException) {
                reportError(recording, error)
                errors++
            }
        }
        return RecoveryReport(completed, failed, errors).also {
            log.log("recovery", "finished", mapOf("completed" to completed, "failed" to failed, "errors" to errors))
        }
    }

    private suspend fun recover(recording: RecordingEntity): Boolean {
        val id = recording.id
        val format = AacFormat(recording.channels)
        val stored = database.recordings().segments(id)
        if (stored.withIndex().any { (offset, segment) -> segment.index != offset + 1 }) {
            throw IOException("Stored segment indices are not contiguous")
        }
        val metadataByIndex = stored.associateBy { it.index }
        val segments = stored.map { CommittedSegment(it.index, it.durationUs, it.plainBytes) }.toMutableList()
        if (!files.file(id, "audio.aac.enc").exists()) {
            if (recording.status != RecordingStatus.RECORDING) throw IOException("Final audio missing after metadata commit")
            val indices = (files.segmentIndices(id) + stored.map { it.index }).distinct().sorted()
            if (indices.withIndex().any { (offset, index) -> index != offset + 1 } ||
                (indices.lastOrNull() ?: 0) > (stored.lastOrNull()?.index ?: 0) + 1) {
                throw IOException("Interrupted recording has missing segment metadata or files")
            }
            segments.clear()
            var badTail: Int? = null
            for (index in indices) {
                currentCoroutineContext().ensureActive()
                // Snapshot opening errors are disk failures, not evidence that a tail may be discarded.
                val snapshot = runInterruptible { crypto.openSegmentSnapshot(id, SegmentWriter.name(index)) }
                val content = try {
                    snapshot.use { format.inspect(it) }
                } catch (error: IOException) {
                    if (index != indices.last()) throw IOException("Invalid non-tail segment; files retained", error)
                    badTail = index
                    break
                }
                if (content.durationUs > SegmentWriter.MAX_DURATION_US) throw IOException("Segment exceeds five seconds")
                val segment = CommittedSegment(index, content.durationUs, content.bytes)
                val metadata = metadataByIndex[index]
                if (metadata != null && (metadata.durationUs != content.durationUs || metadata.plainBytes != content.bytes)) {
                    throw IOException("Authenticated segment differs from stored metadata")
                }
                segments += segment
            }
            if (segments.isEmpty()) {
                repository.failEmptyRecording(id)
                log.log("recovery", "no_authenticated_audio", mapOf("recording_id" to id))
                return false
            }
            for (segment in segments) {
                if (segment.index !in metadataByIndex) {
                    repository.commitSegment(id, segment, files.file(id, SegmentWriter.name(segment.index)).lastModified())
                }
            }
            if (badTail != null) {
                if (badTail in metadataByIndex) repository.discardUnauthenticatedTail(id, badTail)
                crypto.delete(id, SegmentWriter.name(badTail))
                log.log("recovery", "discarded_unauthenticated_tail", mapOf("recording_id" to id, "index" to badTail))
            }
        }
        if (segments.isEmpty()) throw IOException("Final audio exists without durable segment metadata")
        runInterruptible {
            Finalizer(files, crypto, format) { _, audio ->
                runBlocking { repository.finalize(id, audio, recovered = true) }
            }.finish(id, segments)
        }
        repository.completeCleanup(id)
        return true
    }

    private fun reportError(recording: RecordingEntity, error: Exception) {
        log.log("recovery", "recording_error", mapOf(
            "recording_id" to recording.id, "error_type" to error.javaClass.simpleName
        ))
    }
}
