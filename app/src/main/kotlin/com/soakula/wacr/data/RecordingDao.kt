package com.soakula.wacr.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Dao
interface RecordingDao {
    @Insert suspend fun insertSession(session: CallSessionEntity): Long
    @Insert suspend fun insertCallTypeEvent(event: CallTypeEventEntity): Long
    @Insert suspend fun insertRecording(recording: RecordingEntity)
    @Insert suspend fun insertSegment(segment: SegmentEntity)
    @Insert suspend fun insertRoute(event: RouteEventEntity)
    @Update suspend fun updateRecording(recording: RecordingEntity): Int

    @Query("SELECT * FROM recording WHERE id = :id")
    suspend fun recording(id: UUID): RecordingEntity?

    @Transaction
    @Query("""
        SELECT * FROM recording WHERE NOT EXISTS
        (SELECT 1 FROM recording_deletion WHERE recording_id = recording.id)
        ORDER BY started_at DESC, id
    """)
    fun observeAll(): Flow<List<RecordingWithSession>>

    @Transaction
    @Query("""
        SELECT * FROM recording WHERE NOT EXISTS
        (SELECT 1 FROM recording_deletion WHERE recording_id = recording.id)
        ORDER BY started_at DESC, id LIMIT 5
    """)
    fun observeRecent(): Flow<List<RecordingWithSession>>

    @Query("SELECT * FROM call_session WHERE id = :id")
    suspend fun session(id: Long): CallSessionEntity?

    @Query("UPDATE call_session SET ended_at = :endedAt WHERE id = :id")
    suspend fun setSessionEnd(id: Long, endedAt: Long?): Int

    @Query("UPDATE call_session SET ended_at = :endedAt WHERE id = :id AND ended_at IS NULL")
    suspend fun closeOpenSession(id: Long, endedAt: Long): Int

    @Query("UPDATE call_session SET call_type = :type WHERE id = :id")
    suspend fun setSessionType(id: Long, type: CallType): Int

    @Query("UPDATE call_session SET decision = :decision WHERE id = :id")
    suspend fun setSessionDecision(id: Long, decision: RecordingDecision): Int

    @Query("SELECT * FROM call_type_event WHERE session_id = :id ORDER BY at_ms, id")
    suspend fun sessionTypeEvents(id: Long): List<CallTypeEventEntity>

    @Query("""
        UPDATE recording SET title = :title WHERE id = :id AND NOT EXISTS
        (SELECT 1 FROM recording_deletion WHERE recording_id = recording.id)
    """)
    suspend fun rename(id: UUID, title: String): Int

    @Insert suspend fun insertDeletion(deletion: PendingRecordingDeletion)

    @Query("SELECT * FROM recording_deletion WHERE recording_id = :id")
    suspend fun deletion(id: UUID): PendingRecordingDeletion?

    @Query("SELECT * FROM recording_deletion ORDER BY undo_until_elapsed_ms, recording_id")
    suspend fun pendingDeletions(): List<PendingRecordingDeletion>

    @Query("UPDATE recording_deletion SET committed = 1 WHERE recording_id = :id AND request_id = :request")
    suspend fun commitDeletion(id: UUID, request: UUID): Int

    @Query("DELETE FROM recording_deletion WHERE recording_id = :id AND request_id = :request")
    suspend fun removeDeletion(id: UUID, request: UUID): Int

    @Query("SELECT * FROM segment WHERE recording_id = :id ORDER BY idx")
    suspend fun segments(id: UUID): List<SegmentEntity>

    @Query("SELECT * FROM route_event WHERE recording_id = :id ORDER BY at_ms, id")
    suspend fun routes(id: UUID): List<RouteEventEntity>

    @Query("SELECT * FROM route_event WHERE recording_id = :id ORDER BY at_ms DESC, id DESC LIMIT 1")
    suspend fun lastRoute(id: UUID): RouteEventEntity?

    @Query("SELECT MAX(idx) FROM segment WHERE recording_id = :id")
    suspend fun lastSegmentIndex(id: UUID): Int?

    @Query("""
        SELECT * FROM recording WHERE (status = 'RECORDING'
        OR (status IN ('FINALIZED', 'RECOVERED') AND
            EXISTS (SELECT 1 FROM segment WHERE recording_id = recording.id)))
        AND NOT EXISTS (SELECT 1 FROM recording_deletion WHERE recording_id = recording.id)
        ORDER BY started_at, id
    """)
    suspend fun needsRecovery(): List<RecordingEntity>

    @Query("DELETE FROM segment WHERE recording_id = :id")
    suspend fun deleteSegments(id: UUID)

    @Query("DELETE FROM segment WHERE recording_id = :id AND idx = :index")
    suspend fun deleteSegment(id: UUID, index: Int)

    @Query("DELETE FROM recording WHERE id = :id")
    suspend fun deleteRecording(id: UUID)

    @Query("DELETE FROM call_session WHERE id = :id")
    suspend fun deleteSession(id: Long)
}
