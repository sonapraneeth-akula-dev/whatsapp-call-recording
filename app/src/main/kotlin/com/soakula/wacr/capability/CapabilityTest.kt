package com.soakula.wacr.capability

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresPermission
import androidx.room.ColumnInfo
import com.soakula.wacr.crypto.CryptoStore
import com.soakula.wacr.data.AppDatabase
import com.soakula.wacr.data.AudioRoute
import com.soakula.wacr.data.CapabilityResult
import com.soakula.wacr.data.CapabilityStatus
import com.soakula.wacr.data.RecordingFiles
import com.soakula.wacr.data.RecordingStatus
import com.soakula.wacr.detection.CallDetection
import com.soakula.wacr.recording.AacFormat
import com.soakula.wacr.recording.CaptureProfile
import com.soakula.wacr.recording.CaptureSource
import com.soakula.wacr.recording.CaptureUnavailableException
import com.soakula.wacr.recording.EmptyRecordingException
import com.soakula.wacr.recording.RecordingJob
import com.soakula.wacr.recording.RecordingStopReason
import com.soakula.wacr.recording.Recovery
import com.soakula.wacr.recording.StorageGuard
import com.soakula.wacr.util.AppLog
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.UUID

data class CapabilityDevice(
    @ColumnInfo(name = "device_model") val model: String,
    @ColumnInfo(name = "os_build") val osBuild: String,
    @ColumnInfo(name = "wa_package") val waPackage: String,
    @ColumnInfo(name = "wa_version") val waVersion: String,
    @ColumnInfo(name = "wa_version_code") val waVersionCode: Long
) {
    init {
        require(waPackage in CallDetection.PACKAGES) { "Unsupported WhatsApp package" }
        require(listOf(model, osBuild, waVersion).all { it.isNotBlank() && it.length <= 256 }) {
            "Missing or oversized capability device/version metadata"
        }
        require(waVersionCode >= 0) { "WhatsApp version code must be nonnegative" }
    }

    companion object {
        fun read(context: Context, waPackage: String): CapabilityDevice {
            require(waPackage in CallDetection.PACKAGES) { "Unsupported WhatsApp package" }
            val info = context.packageManager.getPackageInfo(waPackage, PackageManager.PackageInfoFlags.of(0))
            return CapabilityDevice(Build.MODEL, Build.FINGERPRINT, waPackage,
                info.versionName ?: throw IOException("WhatsApp version is unavailable"), info.longVersionCode)
        }
    }
}

data class CapabilityProgress(
    val source: CaptureSource, val index: Int, val total: Int,
    val phase: CapabilityPhase, val elapsedMs: Long
)

/** Blocking worker; caller owns the exclusive foreground microphone lease and completed recovery. */
class CapabilityTest(
    private val database: AppDatabase,
    private val files: RecordingFiles,
    private val crypto: CryptoStore,
    private val log: AppLog,
    private val storage: StorageGuard,
    private val currentRoute: () -> AudioRoute?,
    private val now: () -> Long = System::currentTimeMillis,
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime,
    private val clipTitle: (CaptureSource) -> String? = { null }
) {
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun run(
        device: CapabilityDevice,
        route: AudioRoute,
        sources: List<CaptureSource>,
        shouldStop: () -> Boolean,
        onProgress: (CapabilityProgress) -> Unit,
        onRecording: (Long, Boolean) -> Unit,
        onSaving: () -> Unit,
        runId: UUID = UUID.randomUUID()
    ): List<CapabilityResult> {
        require(sources.isNotEmpty() && sources.size == sources.distinct().size) { "Select unique capability sources" }
        if (shouldStop()) return emptyList()
        check(currentRoute() == route) { "Requested communication route is not active" }
        check(runBlocking { database.recordings().needsRecovery().isEmpty() }) { "Recovery must finish before capability capture" }
        val results = mutableListOf<CapabilityResult>()
        for ((index, source) in sources.withIndex()) {
            if (shouldStop()) break
            val id = UUID.randomUUID()
            val meter = CapabilityMeter()
            var cancelled = false
            var routeChanged = false
            var timedOut = false
            var startedElapsed: Long? = null
            var silenced: Boolean? = null
            fun progress(phase: CapabilityPhase) = onProgress(
                CapabilityProgress(source, index + 1, sources.size, phase, meter.sampleFrames * 1000 / AacFormat.SAMPLE_RATE)
            )
            val job = RecordingJob(
                database, files, crypto, log, storage, profile = CaptureProfile(listOf(source)), now = now, title = clipTitle(source),
                onPcm = { pcm, count, clientSilenced ->
                    meter.add(pcm, count, clientSilenced)
                    progress(meter.phase)
                }
            )
            val status = try {
                val result = job.run(id, null, {
                    cancelled = cancelled || shouldStop()
                    routeChanged = routeChanged || currentRoute() != route
                    val started = startedElapsed
                    if (started != null && !meter.complete && elapsedMs() - started >= 35_000) timedOut = true
                    cancelled || routeChanged || timedOut || meter.complete
                }, { at, low ->
                    if (startedElapsed == null) startedElapsed = elapsedMs()
                    onRecording(at, low)
                    progress(meter.phase)
                }, {
                    onSaving()
                    progress(CapabilityPhase.SAVING)
                })
                when {
                    cancelled -> CapabilityStatus.CANCELLED
                    routeChanged -> CapabilityStatus.ROUTE_CHANGED
                    timedOut -> CapabilityStatus.TIMED_OUT
                    result?.reason == RecordingStopReason.LOW_STORAGE -> CapabilityStatus.LOW_STORAGE
                    meter.complete && result != null -> CapabilityStatus.COMPLETE
                    else -> throw IOException("Capability capture ended without a complete result")
                }
            } catch (error: CaptureUnavailableException) {
                silenced = error.clientSilenced
                recoverPartial()
                CapabilityStatus.UNAVAILABLE
            } catch (error: EmptyRecordingException) {
                log.log("capability", "empty_source", mapOf("source" to source.androidId))
                when {
                    cancelled -> CapabilityStatus.CANCELLED
                    routeChanged -> CapabilityStatus.ROUTE_CHANGED
                    timedOut -> CapabilityStatus.TIMED_OUT
                    else -> CapabilityStatus.UNAVAILABLE
                }
            }
            val recording = runBlocking { database.recordings().recording(id) }
                ?.takeIf { it.status in setOf(RecordingStatus.FINALIZED, RecordingStatus.RECOVERED) }
            val result = CapabilityResult(
                runId, device, route, source, status, now(), recording?.id, meter.sampleFrames,
                meter.ownRmsDb, meter.remoteRmsDb, meter.zeroPct, silenced ?: meter.clientSilenced
            )
            val storedId = runBlocking { database.capabilities().insert(result) }
            results += result.copy(id = storedId)
            log.log("capability", "source_result", mapOf(
                "source" to source.androidId, "route" to route.name, "status" to status.name,
                "sample_frames" to meter.sampleFrames
            ))
            if (status != CapabilityStatus.COMPLETE && status != CapabilityStatus.UNAVAILABLE) break
        }
        return results
    }

    private fun recoverPartial() {
        val report = runBlocking { Recovery(database, files, crypto, log).run() }
        if (report.errors != 0) throw IOException("Capability partial recording recovery failed")
    }
}
