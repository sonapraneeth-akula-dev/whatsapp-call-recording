package com.soakula.wacr.data

import androidx.room.withTransaction
import com.soakula.wacr.recording.AacFormat
import com.soakula.wacr.recording.CommittedSegment
import com.soakula.wacr.recording.FinalizedAudio
import com.soakula.wacr.recording.SegmentWriter
import java.util.UUID

class RecordingRepository(private val database: AppDatabase) {
    private val dao = database.recordings()

    suspend fun start(recording: RecordingEntity, session: CallSessionEntity? = null) {
        require(recording.status == RecordingStatus.RECORDING && recording.channels in 1..2)
        require(recording.startedAt >= 0 && recording.captureSource.isNotBlank())
        require(recording.audioPath == null && recording.videoPath == null)
        require(session == null || (session.id == 0L && recording.sessionId == null))
        database.withTransaction {
            val sessionId = session?.let { dao.insertSession(it) } ?: recording.sessionId
            dao.insertRecording(recording.copy(sessionId = sessionId))
        }
    }

    suspend fun commitSegment(id: UUID, segment: CommittedSegment, writtenAt: Long) {
        require(segment.durationUs in 1..SegmentWriter.MAX_DURATION_US &&
            segment.durationUs % AacFormat.FRAME_DURATION_US == 0L && writtenAt >= 0)
        val frames = segment.durationUs / AacFormat.FRAME_DURATION_US
        require(segment.plainBytes in frames * 8..frames * 8191)
        database.withTransaction {
            check(dao.recording(id)?.status == RecordingStatus.RECORDING) { "Recording is not active" }
            require(segment.index == (dao.lastSegmentIndex(id) ?: 0) + 1) { "Segment index is not contiguous" }
            dao.insertSegment(SegmentEntity(id, segment.index, segment.durationUs, segment.plainBytes, writtenAt))
        }
    }

    suspend fun recordRoute(id: UUID, route: AudioRoute, atMs: Long) {
        require(atMs >= 0) { "Route time must be nonnegative" }
        database.withTransaction {
            check(dao.recording(id)?.status == RecordingStatus.RECORDING) { "Recording is not active" }
            val previous = dao.lastRoute(id)
            require(previous == null || atMs >= previous.atMs) { "Route time must not go backwards" }
            if (previous?.route != route) dao.insertRoute(RouteEventEntity(id, atMs, route))
        }
    }

    suspend fun finalize(id: UUID, audio: FinalizedAudio, recovered: Boolean = false) {
        require(audio.durationUs > 0 && audio.durationUs % AacFormat.FRAME_DURATION_US == 0L)
        require(audio.plainBytes > 0 && audio.encryptedBytes > audio.plainBytes)
        database.withTransaction {
            val recording = checkNotNull(dao.recording(id)) { "Recording does not exist" }
            check(recording.status != RecordingStatus.FAILED) { "Failed recording cannot be finalized" }
            val path = "$id/audio.aac.enc"
            if (recording.status != RecordingStatus.RECORDING) {
                check(recording.durationMs == audio.durationUs / 1000 &&
                    recording.sizeBytes == audio.encryptedBytes && recording.audioPath == path) {
                    "Finalized metadata does not match retry"
                }
            } else {
                check(dao.updateRecording(recording.copy(
                    status = if (recovered) RecordingStatus.RECOVERED else RecordingStatus.FINALIZED,
                    durationMs = audio.durationUs / 1000, sizeBytes = audio.encryptedBytes, audioPath = path
                )) == 1)
            }
        }
    }

    internal suspend fun discardUnauthenticatedTail(id: UUID, index: Int) {
        database.withTransaction {
            check(dao.recording(id)?.status == RecordingStatus.RECORDING)
            check(dao.lastSegmentIndex(id) == index)
            dao.deleteSegment(id, index)
        }
    }

    internal suspend fun failEmptyRecording(id: UUID) {
        database.withTransaction {
            val recording = checkNotNull(dao.recording(id))
            check(recording.status == RecordingStatus.RECORDING)
            check(dao.updateRecording(recording.copy(
                status = RecordingStatus.FAILED, fallbackReason = "NO_AUTHENTICATED_AUDIO"
            )) == 1)
        }
    }

    /** Called only after Finalizer has published audio and removed the segment files. */
    suspend fun completeCleanup(id: UUID) {
        database.withTransaction {
            check(dao.recording(id)?.status in setOf(RecordingStatus.FINALIZED, RecordingStatus.RECOVERED))
            dao.deleteSegments(id)
        }
    }
}
