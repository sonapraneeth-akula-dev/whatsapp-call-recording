package com.soakula.wacr.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import java.util.UUID

enum class CallDirection { INCOMING, OUTGOING, UNKNOWN }
enum class CallType { VOICE, VIDEO, UNKNOWN }
enum class RecordingDecision { AUTO, ASKED, MANUAL, IGNORED }
enum class RecordingStatus { RECORDING, FINALIZED, RECOVERED, FAILED }
enum class AudioRoute { EARPIECE, SPEAKER, WIRED, BLUETOOTH, USB }

@Entity(tableName = "call_session", indices = [Index("wa_display_name"), Index("match_key"), Index("connected_at")])
data class CallSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "wa_display_name") val waDisplayName: String? = null,
    @ColumnInfo(name = "match_key") val matchKey: String? = null,
    @ColumnInfo(name = "is_group") val isGroup: Boolean = false,
    @ColumnInfo(name = "group_known", defaultValue = "0") val isGroupKnown: Boolean = isGroup,
    @ColumnInfo(name = "group_name") val groupName: String? = null,
    @ColumnInfo(name = "participants_json") val participantsJson: String? = null,
    val direction: CallDirection = CallDirection.UNKNOWN,
    @ColumnInfo(name = "call_type") val callType: CallType = CallType.UNKNOWN,
    @ColumnInfo(name = "ringing_at") val ringingAt: Long? = null,
    @ColumnInfo(name = "connected_at") val connectedAt: Long? = null,
    @ColumnInfo(name = "ended_at") val endedAt: Long? = null,
    val decision: RecordingDecision = RecordingDecision.MANUAL
)

@Entity(
    tableName = "call_type_event",
    foreignKeys = [ForeignKey(
        entity = CallSessionEntity::class, parentColumns = ["id"], childColumns = ["session_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["session_id", "at_ms"])]
)
data class CallTypeEventEntity(
    @ColumnInfo(name = "session_id") val sessionId: Long,
    @ColumnInfo(name = "from_type") val from: CallType,
    @ColumnInfo(name = "to_type") val to: CallType,
    @ColumnInfo(name = "at_ms") val atMs: Long,
    @PrimaryKey(autoGenerate = true) val id: Long = 0
)

@Entity(
    tableName = "recording",
    foreignKeys = [ForeignKey(
        entity = CallSessionEntity::class, parentColumns = ["id"], childColumns = ["session_id"],
        onDelete = ForeignKey.SET_NULL
    )],
    indices = [Index("session_id"), Index("status"), Index("started_at")]
)
data class RecordingEntity(
    @PrimaryKey val id: UUID,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    val channels: Int,
    @ColumnInfo(name = "capture_source") val captureSource: String,
    @ColumnInfo(name = "session_id") val sessionId: Long? = null,
    val title: String? = null,
    val status: RecordingStatus = RecordingStatus.RECORDING,
    @ColumnInfo(name = "duration_ms") val durationMs: Long? = null,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long? = null,
    @ColumnInfo(name = "remote_captured") val remoteCaptured: Boolean? = null,
    @ColumnInfo(name = "has_video") val hasVideo: Boolean = false,
    @ColumnInfo(name = "fallback_reason") val fallbackReason: String? = null,
    val starred: Boolean = false,
    @ColumnInfo(name = "audio_path") val audioPath: String? = null,
    @ColumnInfo(name = "video_path") val videoPath: String? = null
)

data class RecordingWithSession(
    @Embedded val recording: RecordingEntity,
    @Relation(parentColumn = "session_id", entityColumn = "id") val session: CallSessionEntity?
)

@Entity(tableName = "recording_deletion")
data class PendingRecordingDeletion(
    @PrimaryKey @ColumnInfo(name = "recording_id") val recordingId: UUID,
    @ColumnInfo(name = "request_id") val requestId: UUID,
    @ColumnInfo(name = "undo_until_elapsed_ms") val undoUntilElapsedMs: Long,
    @ColumnInfo(name = "boot_count") val bootCount: Int,
    val committed: Boolean = false
)

@Entity(
    tableName = "segment", primaryKeys = ["recording_id", "idx"],
    foreignKeys = [ForeignKey(
        entity = RecordingEntity::class, parentColumns = ["id"], childColumns = ["recording_id"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class SegmentEntity(
    @ColumnInfo(name = "recording_id") val recordingId: UUID,
    @ColumnInfo(name = "idx") val index: Int,
    @ColumnInfo(name = "duration_us") val durationUs: Long,
    @ColumnInfo(name = "plain_bytes") val plainBytes: Long,
    @ColumnInfo(name = "written_at") val writtenAt: Long
)

@Entity(
    tableName = "route_event", indices = [Index("recording_id")],
    foreignKeys = [ForeignKey(
        entity = RecordingEntity::class, parentColumns = ["id"], childColumns = ["recording_id"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class RouteEventEntity(
    @ColumnInfo(name = "recording_id") val recordingId: UUID,
    @ColumnInfo(name = "at_ms") val atMs: Long,
    val route: AudioRoute,
    @PrimaryKey(autoGenerate = true) val id: Long = 0
)
