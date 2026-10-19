package com.soakula.wacr.spike.capture

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder.AudioSource
import android.os.Build
import android.os.VibrationEffect
import android.os.VibratorManager
import com.soakula.wacr.spike.Prefs
import com.soakula.wacr.spike.analysis.FrameMeter
import com.soakula.wacr.spike.analysis.MatrixReport
import com.soakula.wacr.spike.analysis.Rms
import com.soakula.wacr.spike.analysis.SourceResult
import com.soakula.wacr.spike.log.Names
import com.soakula.wacr.spike.log.SpikeLog
import com.soakula.wacr.spike.signals.AudioStateLogger

/**
 * Records each audio source in turn for 2 × sideSeconds:
 *   window 1 (1 buzz)  — owner speaks, other person silent;
 *   window 2 (2 buzzes) — owner says "your turn" then stays silent, other person speaks.
 * Refusals are logged, never thrown. 3 buzzes = sweep finished.
 */
class SourceSweep(private val ctx: Context, private val host: String, private val reason: String) {
    @Volatile var cancelled = false
    private val am = ctx.getSystemService(AudioManager::class.java)

    fun run() {
        val side = Prefs.sideSeconds
        SpikeLog.log("sweep", "start", mapOf(
            "host" to host, "reason" to reason, "sideSeconds" to side, "route" to AudioStateLogger.route(am),
            "sources" to SOURCES.map(Names::source), "process" to processState()
        ))
        for ((i, src) in SOURCES.withIndex()) {
            if (cancelled) break
            val r = one(src, side, i + 1)
            MatrixReport.add(r)
            SpikeLog.log("sweep", "source_result", mapOf(
                "source" to r.source, "route" to r.route, "host" to r.host, "status" to r.status, "ownP95" to r.ownP95,
                "remoteP95" to r.remoteP95, "zeroPct" to r.zeroPct, "silenced" to r.silenced, "own" to r.own, "remote" to r.remote, "file" to r.file
            ))
        }
        MatrixReport.write()
        vibrate(longArrayOf(0, 400, 200, 400, 200, 400))
        Notifier.status(ctx, if (cancelled) "Sweep stopped (call ended)" else "Sweep finished — open the app to see the matrix", ongoing = false)
        SpikeLog.log("sweep", "end", mapOf("cancelled" to cancelled, "host" to host))
    }

    @SuppressLint("MissingPermission")
    private fun one(src: Int, side: Int, index: Int): SourceResult {
        val name = Names.source(src)
        val route = AudioStateLogger.route(am)
        fun fail(status: String, e: Throwable? = null): SourceResult {
            SpikeLog.log("sweep", "source_failed", mapOf("source" to name, "status" to status, "error" to e?.toString()))
            return SourceResult(name, route, host, status)
        }
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(src, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE) * 2)
        } catch (e: Exception) {
            return fail("ctor:${e.javaClass.simpleName}", e)
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return fail("uninitialized") }
        try { rec.startRecording() } catch (e: Exception) { rec.release(); return fail("start:${e.javaClass.simpleName}", e) }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) { rec.release(); return fail("not_recording") }

        val rel = "audio/${Build.MODEL}_${index}_${name}_$route.wav"
        val meter = FrameMeter(RATE)
        val buf = ShortArray(RATE / 10)
        val total = RATE * side * 2
        var read = 0
        var nextTick = 0
        var silenced: Boolean? = null
        var otherCued = false
        cue("$index/${SOURCES.size} $name: YOU speak now", longArrayOf(0, 300))
        WavWriter(SpikeLog.file(rel), RATE).use { wav ->
            while (read < total && !cancelled) {
                if (!otherCued && read >= RATE * side) {
                    otherCued = true
                    cue("$index/${SOURCES.size} $name: say \"your turn\", then OTHER person speaks", longArrayOf(0, 200, 150, 200))
                }
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) { SpikeLog.log("sweep", "read_error", mapOf("source" to name, "code" to n)); break }
                wav.write(buf, n)
                meter.add(buf, n)
                read += n
                if (read >= nextTick) {
                    nextTick += RATE
                    val own = rec.activeRecordingConfiguration?.isClientSilenced
                    if (own == true) silenced = true else if (silenced == null && own == false) silenced = false
                    SpikeLog.log("sweep", "tick", mapOf(
                        "source" to name, "sec" to read / RATE, "dbfs" to meter.frames.lastOrNull(), "silenced" to own,
                        "route" to AudioStateLogger.route(am), "active" to AudioStateLogger.recordings(am.activeRecordingConfigurations)
                    ))
                }
            }
        }
        runCatching { rec.stop() }
        rec.release()
        val f = meter.frames
        val perSide = side * 10
        val own = f.subList(minOf(SKIP, f.size), minOf(perSide, f.size))
        val remote = if (f.size > perSide + SKIP) f.subList(perSide + SKIP, f.size) else emptyList()
        return SourceResult(
            name, route, host, if (read < total) "partial" else "ok",
            ownP95 = own.takeIf { it.isNotEmpty() }?.let { Rms.percentile(it, 95.0) },
            remoteP95 = remote.takeIf { it.isNotEmpty() }?.let { Rms.percentile(it, 95.0) },
            zeroPct = meter.zeroPct, silenced = silenced, file = rel
        )
    }

    private fun cue(text: String, pattern: LongArray) {
        vibrate(pattern)
        Notifier.status(ctx, text)
        SpikeLog.log("sweep", "cue", mapOf("text" to text))
    }

    private fun vibrate(pattern: LongArray) = runCatching {
        ctx.getSystemService(VibratorManager::class.java).defaultVibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    companion object {
        const val RATE = 16_000
        private const val SKIP = 20 // 2 s reaction time at the start of each window
        /** Privileged sources first (they fail fast), then the ones a normal app may get. */
        val SOURCES = listOf(
            AudioSource.VOICE_CALL, AudioSource.VOICE_DOWNLINK, AudioSource.VOICE_UPLINK,
            AudioSource.VOICE_COMMUNICATION, AudioSource.VOICE_RECOGNITION, AudioSource.MIC,
            AudioSource.CAMCORDER, AudioSource.UNPROCESSED,
        )

        fun processState(): Map<String, Any?> {
            val info = ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState)
            return mapOf("importance" to info.importance, "importanceReason" to info.importanceReasonCode)
        }
    }
}
