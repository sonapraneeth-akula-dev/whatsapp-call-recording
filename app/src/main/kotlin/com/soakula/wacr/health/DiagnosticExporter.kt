package com.soakula.wacr.health

import android.content.Context
import android.content.pm.PackageManager
import android.database.SQLException
import android.os.Build
import com.soakula.wacr.logging.Json
import com.soakula.wacr.WacrApp
import com.soakula.wacr.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Streams redacted diagnostics and closes the supplied destination; never stages recording content. */
suspend fun exportDiagnostics(context: Context, log: AppLog, output: OutputStream) = withContext(Dispatchers.IO) {
    output.use { destination ->
        val application = context.applicationContext
        val capabilities = capabilityMetadata(application as? WacrApp, log)
        val snapshot = log.redactedSnapshot()
        val info = application.packageManager.getPackageInfo(application.packageName, PackageManager.PackageInfoFlags.of(0))
        val detection = (application as? WacrApp)?.detection?.state?.value
        val metadata = Json.encode(mapOf(
            "schema_version" to 2,
            "exported_at" to System.currentTimeMillis(),
            "application_id" to application.packageName,
            "version_name" to info.versionName,
            "version_code" to info.longVersionCode,
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "android_release" to Build.VERSION.RELEASE,
            "sdk_int" to Build.VERSION.SDK_INT,
            "security_patch" to Build.VERSION.SECURITY_PATCH,
            "health_issues" to ((application as? WacrApp)?.health ?: HealthMonitor(application)).check().map { it.problem.name },
            "detection_rules_version" to detection?.rulesVersion,
            "detection_ready" to detection?.ready,
            "detection_failed" to detection?.failed,
            "capability_results_limit" to 100,
            "capability_interpretation" to "cued_mixed_mono_energy_and_owner_listening_verdicts_not_current_call_audibility",
            "scope" to "redacted_ring_log_and_technical_status_only"
        ) + capabilities).toByteArray(Charsets.UTF_8)
        ZipOutputStream(destination).use { zip ->
            for ((name, bytes) in listOf("device.json" to metadata, "app.jsonl" to snapshot)) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }

    }
}

private suspend fun capabilityMetadata(app: WacrApp?, log: AppLog): Map<String, Any?> {
    if (app == null) return mapOf("capability_status" to "not_available", "capability_results" to null)
    return try {
        val rows = app.database().capabilities().diagnostics()
        mapOf(
            "capability_status" to if (rows.isEmpty()) "no_results" else "available",
            "capability_results_truncated" to (rows.size > 100),
            "capability_results" to rows.take(100).map { row ->
                mapOf(
                    "device_model" to row.device.model, "os_build" to row.device.osBuild,
                    "wa_package" to row.device.waPackage, "wa_version" to row.device.waVersion,
                    "wa_version_code" to row.device.waVersionCode, "tested_at" to row.testedAt,
                    "route" to row.route.name, "source" to row.source.name, "status" to row.status.name,
                    "sample_frames" to row.sampleFrames, "own_rms_db" to row.ownRmsDb,
                    "remote_rms_db" to row.remoteRmsDb, "zero_pct" to row.zeroPct,
                    "client_silenced" to row.clientSilenced, "own_ok" to row.ownOk, "remote_ok" to row.remoteOk
                )
            }
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        when (error) {
            is IOException, is GeneralSecurityException, is ProviderException, is SQLException,
            is IllegalStateException, is IllegalArgumentException, is SecurityException -> unavailableCapabilities(error, log)
            else -> throw error
        }
    } catch (error: UnsatisfiedLinkError) {
        unavailableCapabilities(error, log)
    }
}

private fun unavailableCapabilities(error: Throwable, log: AppLog): Map<String, Any?> {
    log.log("diagnostics", "capability_read_failed", mapOf("error_type" to error.javaClass.simpleName))
    return mapOf("capability_status" to "unavailable", "capability_results" to null,
        "capability_error_type" to error.javaClass.simpleName)
}
