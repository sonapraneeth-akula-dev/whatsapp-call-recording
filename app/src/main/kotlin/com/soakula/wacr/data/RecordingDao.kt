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
    @Insert suspend fun insertRecording(recording: RecordingEntity)
    @Insert suspend fun insertSegment(segment: SegmentEntity)
    @Insert suspend fun insertRoute(event: RouteEventEntity)
    @Update suspend fun updateRecording(recording: RecordingEntity): Int

    @Query("SELECT * FROM recording WHERE id = :id")
    suspend fun recording(id: UUID): RecordingEntity?

    @Transaction
    @Query("SELECT * FROM recording ORDER BY started_at DESC, id")
    fun observeAll(): Flow<List<RecordingWithSession>>

    @Transaction
    @Query("SELECT * FROM recording ORDER BY started_at DESC, id LIMIT 5")
    fun observeRecent(): Flow<List<RecordingWithSession>>

    @Query("SELECT * FROM call_session WHERE id = :id")
    suspend fun session(id: Long): CallSessionEntity?

    @Query("UPDATE recording SET title = :title WHERE id = :id")
    suspend fun rename(id: UUID, title: String): Int

    @Query("SELECT * FROM segment WHERE recording_id = :id ORDER BY idx")
    suspend fun segments(id: UUID): List<SegmentEntity>

    @Query("SELECT * FROM route_event WHERE recording_id = :id ORDER BY at_ms, id")
    suspend fun routes(id: UUID): List<RouteEventEntity>

    @Query("SELECT * FROM route_event WHERE recording_id = :id ORDER BY at_ms DESC, id DESC LIMIT 1")
    suspend fun lastRoute(id: UUID): RouteEventEntity?

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

    @Query("DELETE FROM segment WHERE recording_id = :id AND idx = :index")
    suspend fun deleteSegment(id: UUID, index: Int)

    @Query("DELETE FROM recording WHERE id = :id")
    suspend fun deleteRecording(id: UUID)

    @Query("DELETE FROM call_session WHERE id = :id")
    suspend fun deleteSession(id: Long)
}
