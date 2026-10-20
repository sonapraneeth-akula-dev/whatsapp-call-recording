package com.soakula.wacr.recording

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.SystemClock
import androidx.annotation.RequiresPermission
import com.soakula.wacr.audio.Rms
import com.soakula.wacr.data.AudioRoute
import com.soakula.wacr.util.AppLog
import java.io.Closeable
import java.io.IOException

/** Single recording worker owns this engine; no audio focus, mode or device selection is changed. */
class CaptureEngine @RequiresPermission(Manifest.permission.RECORD_AUDIO) internal constructor(
    profile: CaptureProfile,
    route: AudioRoute?,
    private val log: AppLog,
    private val openInput: (CaptureSource) -> AudioRecord = ::buildInput,
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime
) : Closeable {
    private val sources = profile.sourcesFor(route)
    private var nextSource = 0
    private var input: AudioRecord? = null
    private var closed = false
    private var failed = false
    private var lastDataAt = elapsedMs()
    private var checkSilencingAt = 0L
    lateinit var source: CaptureSource
        private set
    var rmsDbfs: Double? = null
        private set
    val format = AacFormat(1)

    init { openNext() }

    /** Nonblocking PCM16 mono; the worker must wait before retrying a zero-length read. */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun read(pcm: ShortArray): Int {
        check(!closed && !failed) { "Capture engine is closed or failed" }
        require(pcm.isNotEmpty()) { "PCM buffer must not be empty" }
        failed = true
        while (true) {
            val recorder = checkNotNull(input)
            val now = elapsedMs()
            if (now >= checkSilencingAt) {
                checkSilencingAt = now + 1000
                if (recorder.activeRecordingConfiguration?.isClientSilenced == true) {
                    unavailable(source, IOException("Audio client was silenced"))
                    releaseInput()
                    openNext()
                    continue
                }
            }
            val count = recorder.read(pcm, 0, pcm.size, AudioRecord.READ_NON_BLOCKING)
            if (count == AudioRecord.ERROR_DEAD_OBJECT || count == AudioRecord.ERROR_INVALID_OPERATION ||
                (count == 0 && now - lastDataAt >= NO_DATA_TIMEOUT_MS)) {
                unavailable(source, IOException("Audio input unavailable: $count"))
                releaseInput()
                openNext()
                continue
            }
            if (count !in 0..pcm.size) throw IOException("Unexpected PCM read result: $count")
            if (count > 0) {
                lastDataAt = now
                var sum = 0.0
                for (index in 0 until count) sum += pcm[index].toDouble() * pcm[index]
                rmsDbfs = Rms.dbfs(sum, count)
            } else {
                rmsDbfs = null
            }
            failed = false
            return count
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun openNext() {
        while (nextSource < sources.size) {
            val candidate = sources[nextSource++]
            try {
                val recorder = openInput(candidate)
                var handedOff = false
                try {
                    if (recorder.state != AudioRecord.STATE_INITIALIZED) throw IOException("Audio input did not initialize")
                    recorder.startRecording()
                    if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        throw IOException("Audio input did not start")
                    }
                    input = recorder
                    source = candidate
                    lastDataAt = elapsedMs()
                    checkSilencingAt = 0
                    handedOff = true
                    log.log("capture", "source_opened", mapOf("source" to candidate.androidId))
                    return
                } finally {
                    if (!handedOff) recorder.release()
                }
            } catch (error: IOException) {
                unavailable(candidate, error)
            } catch (error: IllegalArgumentException) {
                unavailable(candidate, error)
            } catch (error: IllegalStateException) {
                unavailable(candidate, error)
            } catch (error: UnsupportedOperationException) {
                unavailable(candidate, error)
            }
        }
        throw IOException("No configured audio source is available")
    }

    private fun unavailable(source: CaptureSource, error: Exception) {
        log.log("capture", "source_unavailable", mapOf(
            "source" to source.androidId, "error_type" to error.javaClass.simpleName
        ))
    }

    private fun releaseInput() {
        val recorder = input
        input = null
        recorder?.release()
    }

    override fun close() {
        if (closed) return
        closed = true
        releaseInput()
    }

    companion object {
        const val NO_DATA_TIMEOUT_MS = 2000L

        @RequiresPermission(Manifest.permission.RECORD_AUDIO)
        internal fun buildInput(source: CaptureSource): AudioRecord {
            val minimum = AudioRecord.getMinBufferSize(
                AacFormat.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minimum <= 0) throw IOException("16 kHz PCM input is unavailable: $minimum")
            return AudioRecord.Builder()
                .setAudioSource(source.androidId)
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(AacFormat.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimum, 6400))
                .setPrivacySensitive(false)
                .build()
        }
    }
}
