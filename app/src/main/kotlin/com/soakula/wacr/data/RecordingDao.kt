package com.soakula.wacr.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import java.util.UUID

@Dao
interface RecordingDao {
    @Insert suspend fun insertSession(session: CallSessionEntity): Long
    @Insert suspend fun insertRecording(recording: RecordingEntity)
    @Insert suspend fun insertSegment(segment: SegmentEntity)
    @Insert suspend fun insertRoute(event: RouteEventEntity)
    @Update suspend fun updateRecording(recording: RecordingEntity): Int

    @Query("SELECT * FROM recording WHERE id = :id")
    suspend fun recording(id: UUID): RecordingEntity?

    @Query("SELECT * FROM call_session WHERE id = :id")
    suspend fun session(id: Long): CallSessionEntity?

    @Query("SELECT * FROM segment WHERE recording_id = :id ORDER BY idx")
    suspend fun segments(id: UUID): List<SegmentEntity>

    @Query("SELECT * FROM route_event WHERE recording_id = :id ORDER BY at_ms, id")
    suspend fun routes(id: UUID): List<RouteEventEntity>

    @Query("SELECT MAX(idx) FROM segment WHERE recording_id = :id")
    suspend fun lastSegmentIndex(id: UUID): Int?

    @Query("""
        SELECT * FROM recording WHERE status = 'RECORDING'
        OR (status IN ('FINALIZED', 'RECOVERED') AND
            EXISTS (SELECT 1 FROM segment WHERE recording_id = recording.id))
        ORDER BY started_at, id
    """)
    suspend fun needsRecovery(): List<RecordingEntity>

    @Query("DELETE FROM segment WHERE recording_id = :id")
    suspend fun deleteSegments(id: UUID)

    @Query("DELETE FROM recording WHERE id = :id")
    suspend fun deleteRecording(id: UUID)

    @Query("DELETE FROM call_session WHERE id = :id")
    suspend fun deleteSession(id: Long)
}
