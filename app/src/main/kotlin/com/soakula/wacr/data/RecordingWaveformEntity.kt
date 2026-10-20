package com.soakula.wacr.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import com.soakula.wacr.recording.WaveformPeaks
import java.util.UUID

@Entity(
    tableName = "recording_waveform",
    foreignKeys = [ForeignKey(entity = RecordingEntity::class, parentColumns = ["id"],
        childColumns = ["recording_id"], onDelete = ForeignKey.CASCADE)]
)
data class RecordingWaveformEntity(
    @PrimaryKey @ColumnInfo(name = "recording_id") val recordingId: UUID,
    @ColumnInfo(name = "frames_per_peak") val framesPerPeak: Long,
    @ColumnInfo(name = "frame_count") val frameCount: Long,
    val peaks: ByteArray
) {
    fun decode(): WaveformPeaks = WaveformPeaks(framesPerPeak, frameCount, peaks)

    companion object {
        fun encode(id: UUID, waveform: WaveformPeaks) =
            RecordingWaveformEntity(id, waveform.framesPerPeak, waveform.frameCount, waveform.encodedPeaks())
    }
}
