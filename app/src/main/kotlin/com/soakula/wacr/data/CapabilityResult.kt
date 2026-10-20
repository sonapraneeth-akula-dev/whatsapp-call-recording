package com.soakula.wacr.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import com.soakula.wacr.capability.CapabilityDevice
import com.soakula.wacr.capability.CapabilityMeter
import com.soakula.wacr.recording.CaptureSource
import kotlinx.coroutines.flow.Flow
import java.util.UUID

enum class CapabilityStatus { COMPLETE, UNAVAILABLE, CANCELLED, ROUTE_CHANGED, LOW_STORAGE, TIMED_OUT }

@Entity(
    tableName = "capability_result",
    foreignKeys = [ForeignKey(
        entity = RecordingEntity::class, parentColumns = ["id"], childColumns = ["recording_id"],
        onDelete = ForeignKey.SET_NULL
    )],
    indices = [
        Index(value = ["run_id", "source"], unique = true), Index(value = ["route", "source"]),
        Index("recording_id"), Index("tested_at")
    ]
)
data class CapabilityResult(
    @ColumnInfo(name = "run_id") val runId: UUID,
    @Embedded val device: CapabilityDevice,
    val route: AudioRoute,
    val source: CaptureSource,
    val status: CapabilityStatus,
    @ColumnInfo(name = "tested_at") val testedAt: Long,
    @ColumnInfo(name = "recording_id") val recordingId: UUID? = null,
    @ColumnInfo(name = "sample_frames") val sampleFrames: Long = 0,
    @ColumnInfo(name = "own_rms_db") val ownRmsDb: Double? = null,
    @ColumnInfo(name = "remote_rms_db") val remoteRmsDb: Double? = null,
    @ColumnInfo(name = "zero_pct") val zeroPct: Double? = null,
    @ColumnInfo(name = "client_silenced") val clientSilenced: Boolean? = null,
    @ColumnInfo(name = "own_ok") val ownOk: Boolean? = null,
    @ColumnInfo(name = "remote_ok") val remoteOk: Boolean? = null,
    @PrimaryKey(autoGenerate = true) val id: Long = 0
) {
    init {
        require(testedAt >= 0 && id >= 0) { "Capability identifiers and time must be nonnegative" }
        require(sampleFrames in 0..CapabilityMeter.TOTAL_FRAMES) { "Invalid capability sample count" }
        require(status != CapabilityStatus.COMPLETE || sampleFrames == CapabilityMeter.TOTAL_FRAMES) {
            "Complete capability measurement requires thirty seconds of PCM"
        }
        require(status != CapabilityStatus.COMPLETE || (ownRmsDb != null && remoteRmsDb != null && zeroPct != null)) {
            "Complete capability measurement requires both cued windows"
        }
        require(status == CapabilityStatus.COMPLETE || (ownOk == null && remoteOk == null)) {
            "Incomplete measurements cannot carry listening verdicts"
        }
        require(listOf(ownRmsDb, remoteRmsDb).all { it == null || (it.isFinite() && it in -120.0..0.0) }) {
            "Invalid capability level"
        }
        require(zeroPct == null || (zeroPct.isFinite() && zeroPct in 0.0..100.0)) { "Invalid zero percentage" }
    }
}

@Dao
interface CapabilityDao {
    @Insert suspend fun insert(result: CapabilityResult): Long

    @Query("SELECT * FROM capability_result ORDER BY tested_at DESC, id DESC LIMIT 100")
    fun observeRecent(): Flow<List<CapabilityResult>>

    @Query("SELECT * FROM capability_result ORDER BY tested_at DESC, id DESC LIMIT 101")
    suspend fun diagnostics(): List<CapabilityResult>

    @Query("SELECT * FROM capability_result WHERE run_id = :runId ORDER BY id")
    suspend fun run(runId: UUID): List<CapabilityResult>

    @Query("SELECT * FROM capability_result WHERE id = :id")
    suspend fun result(id: Long): CapabilityResult?

    @Query("""
        SELECT r.* FROM capability_result r
        WHERE device_model = :model AND os_build = :build AND wa_package = :pkg
        AND wa_version = :version AND wa_version_code = :versionCode
        AND NOT EXISTS (SELECT 1 FROM capability_result n WHERE n.id > r.id
            AND n.device_model = r.device_model AND n.os_build = r.os_build
            AND n.wa_package = r.wa_package AND n.wa_version = r.wa_version
            AND n.wa_version_code = r.wa_version_code AND n.route = r.route AND n.source = r.source)
        ORDER BY r.id
    """)
    suspend fun latest(model: String, build: String, pkg: String, version: String, versionCode: Long): List<CapabilityResult>

    @Query("""
        UPDATE capability_result SET own_ok = :own, remote_ok = :remote
        WHERE id = :id AND status = 'COMPLETE' AND EXISTS (
            SELECT 1 FROM recording r WHERE r.id = capability_result.recording_id
            AND r.status IN ('FINALIZED', 'RECOVERED') AND r.duration_ms > 0
            AND r.capture_source = capability_result.source
            AND r.audio_path = CAST(r.id AS TEXT) || '/audio.aac.enc'
            AND NOT EXISTS (SELECT 1 FROM recording_deletion d WHERE d.recording_id = r.id))
    """)
    suspend fun review(id: Long, own: Boolean, remote: Boolean): Int
}
