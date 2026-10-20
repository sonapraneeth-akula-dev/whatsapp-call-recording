package com.soakula.wacr.data

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.room.withTransaction
import com.soakula.wacr.recording.DeletionJobService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

class RecordingDeletion(
    context: Context,
    private val database: AppDatabase,
    private val files: RecordingFiles,
    private val now: () -> Long = SystemClock::elapsedRealtime
) {
    private val application = context.applicationContext
    private val dao = database.recordings()

    suspend fun request(id: UUID): PendingRecordingDeletion = database.withTransaction {
        val recording = checkNotNull(dao.recording(id)) { "Recording does not exist" }
        check(recording.status != RecordingStatus.RECORDING) { "Active recording cannot be deleted" }
        val time = now()
        require(time in 0..Long.MAX_VALUE - UNDO_MS) { "Invalid deletion time" }
        val boot = currentBoot()
        val pending = PendingRecordingDeletion(id, UUID.randomUUID(), time + UNDO_MS, boot)
        dao.insertDeletion(pending)
        val next = dao.pendingDeletions().minOf { remaining(it, time, boot) }
        if (!DeletionJobService.enqueue(application, next)) throw IOException("Cannot schedule recording deletion")
        pending
    }

    suspend fun undo(ticket: PendingRecordingDeletion) = database.withTransaction {
        val pending = checkNotNull(dao.deletion(ticket.recordingId)) { "Deletion does not exist" }
        val time = now()
        require(time >= 0) { "Invalid deletion time" }
        check(pending.requestId == ticket.requestId && !pending.committed &&
            pending.bootCount == currentBoot() && time < pending.undoUntilElapsedMs) {
            "Deletion can no longer be undone"
        }
        check(dao.recording(pending.recordingId) != null) { "Recording does not exist" }
        check(dao.removeDeletion(pending.recordingId, pending.requestId) == 1)
    }

    suspend fun pendingUndo(): PendingRecordingDeletion? = withContext(Dispatchers.IO) {
        val time = now()
        require(time >= 0) { "Invalid deletion time" }
        val boot = currentBoot()
        dao.pendingDeletions().filter {
            !it.committed && it.bootCount == boot && time < it.undoUntilElapsedMs
        }.maxByOrNull { it.undoUntilElapsedMs }
    }

    suspend fun purgeDue(): Long? = withContext(Dispatchers.IO) {
        cleanupLock.withLock {
            val time = now()
            require(time >= 0) { "Invalid deletion time" }
            val boot = currentBoot()
            for (snapshot in dao.pendingDeletions()) {
                val pending = database.withTransaction {
                    val current = dao.deletion(snapshot.recordingId)
                    if (current == null || current.requestId != snapshot.requestId ||
                        remaining(current, time, boot) > 0) return@withTransaction null
                    check(dao.recording(current.recordingId)?.status != RecordingStatus.RECORDING) {
                        "Active recording cannot be deleted"
                    }
                    // Persist the irreversible claim before touching any ciphertext.
                    check(dao.commitDeletion(current.recordingId, current.requestId) == 1)
                    current.copy(committed = true)
                } ?: continue
                files.deleteRecording(pending.recordingId)
                database.withTransaction {
                    check(dao.deletion(pending.recordingId) == pending) { "Deletion claim changed" }
                    dao.deleteRecording(pending.recordingId)
                    check(dao.removeDeletion(pending.recordingId, pending.requestId) == 1)
                }
            }
            dao.pendingDeletions().minOfOrNull { remaining(it, now(), currentBoot()) }
        }
    }

    private fun currentBoot(): Int = Settings.Global.getInt(application.contentResolver, Settings.Global.BOOT_COUNT, -1)
        .also { check(it >= 0) { "Device boot count is unavailable" } }

    private fun remaining(pending: PendingRecordingDeletion, time: Long, boot: Int): Long =
        if (pending.committed || pending.bootCount != boot) 0
        else (pending.undoUntilElapsedMs - time).coerceAtLeast(0)

    companion object {
        const val UNDO_MS = 5000L
        private val cleanupLock = Mutex()
    }
}
