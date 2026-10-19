package com.soakula.wacr.spike.log

import android.Manifest
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.ContactsContract
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.soakula.wacr.spike.Prefs
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds a zip of all runs (+ own logcat) for Save logs / Share logs. No USB or developer mode needed. */
object DebugBundle {
    private val TEXT = setOf("jsonl", "json", "md", "txt")

    fun build(ctx: Context, redact: Boolean, audio: Boolean, shots: Boolean): File {
        val redactor = if (redact) Redactor(Redactor.expand(knownNames(ctx))) else null
        val dir = File(ctx.cacheDir, "export").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault()).format(Instant.now())
        val out = File(dir, "WACR-logs_${Build.MODEL.replace(Regex("[^A-Za-z0-9]"), "-")}_$stamp.zip")
        val root = SpikeLog.runsRoot()
        var files = 0
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            root.walkTopDown().filter { it.isFile }.forEach { f ->
                if (!audio && f.extension == "wav") return@forEach
                if (!shots && f.extension == "jpg") return@forEach
                zip.putNextEntry(ZipEntry("runs/" + f.relativeTo(root).invariantSeparatorsPath))
                if (redactor != null && f.extension in TEXT) {
                    f.bufferedReader().useLines { lines -> lines.forEach { zip.write((redactor.apply(it) + "\n").toByteArray()) } }
                } else f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                files++
            }
            zip.putNextEntry(ZipEntry("logcat.txt"))
            runCatching {
                val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "threadtime"))
                p.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { zip.write(((redactor?.apply(it) ?: it) + "\n").toByteArray()) }
                }
            }.onFailure { zip.write("logcat failed: $it\n".toByteArray()) }
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("bundle.json"))
            zip.write(
                Json.encode(
                    linkedMapOf(
                        "created" to stamp, "redacted" to redact, "audioIncluded" to audio, "screenshotsIncluded" to shots,
                        "device" to DeviceInfo.collect(ctx),
                    )
                ).toByteArray()
            )
            zip.closeEntry()
        }
        SpikeLog.log("export", "bundle_built", mapOf("file" to out.name, "files" to files, "bytes" to out.length(), "redact" to redact, "audio" to audio, "shots" to shots))
        return out
    }

    /** Copies the zip to Download/WACR-logs (visible in My Files). Returns the visible path. */
    fun saveToDownloads(ctx: Context, f: File): String {
        val cr = ctx.contentResolver
        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, f.name)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/WACR-logs")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: error("MediaStore insert failed")
        cr.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
        cr.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        val path = "Download/WACR-logs/${f.name}"
        SpikeLog.log("export", "saved", mapOf("path" to path))
        return path
    }

    fun share(ctx: Context, f: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, f.name)
            clipData = ClipData.newRawUri(f.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Share WACR logs").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        SpikeLog.log("export", "share_opened", mapOf("file" to f.name))
    }

    fun deleteAllRuns() {
        SpikeLog.runsRoot().listFiles()?.filter { it.name != SpikeLog.runDir.name }?.forEach { it.deleteRecursively() }
    }

    /** WhatsApp notification titles seen so far + phone contacts (if READ_CONTACTS granted). */
    private fun knownNames(ctx: Context): Set<String> {
        val names = Prefs.knownNames.toMutableSet()
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            ctx.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY), null, null, null
            )?.use { c -> while (c.moveToNext()) c.getString(0)?.let(names::add) }
        }
        return names
    }
}
