package com.soakula.wacr.health

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.soakula.wacr.logging.Json
import com.soakula.wacr.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Streams redacted diagnostics and closes the supplied destination; never stages recording content. */
suspend fun exportDiagnostics(context: Context, log: AppLog, output: OutputStream) = withContext(Dispatchers.IO) {
    output.use { destination ->
        val snapshot = log.redactedSnapshot()
        val application = context.applicationContext
        val info = application.packageManager.getPackageInfo(application.packageName, PackageManager.PackageInfoFlags.of(0))
        val metadata = Json.encode(mapOf(
            "schema_version" to 1,
            "exported_at" to System.currentTimeMillis(),
            "application_id" to application.packageName,
            "version_name" to info.versionName,
            "version_code" to info.longVersionCode,
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "android_release" to Build.VERSION.RELEASE,
            "sdk_int" to Build.VERSION.SDK_INT,
            "security_patch" to Build.VERSION.SECURITY_PATCH,
            "health_issues" to HealthMonitor(application).check().map { it.problem.name },
            "capability_results" to null,
            "capability_status" to "not_available_in_this_build",
            "scope" to "redacted_ring_log_and_technical_status_only"
        )).toByteArray(Charsets.UTF_8)
        ZipOutputStream(destination).use { zip ->
            for ((name, bytes) in listOf("device.json" to metadata, "app.jsonl" to snapshot)) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }
}
