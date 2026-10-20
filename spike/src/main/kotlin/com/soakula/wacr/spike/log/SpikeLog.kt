package com.soakula.wacr.spike.log

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.soakula.wacr.logging.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.concurrent.Executors

/**
 * Structured run log: files/runs/<runId>/events.jsonl (+ device.json, crash.txt, audio/, tree/, shots/),
 * mirrored to logcat tag WACR and to an in-memory list of the last 200 lines for the UI.
 */
object SpikeLog {
    private const val TAG = "WACR"
    private val io = Executors.newSingleThreadExecutor()
    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault())
    private lateinit var app: Context
    private var writer: BufferedWriter? = null

    @Volatile lateinit var runDir: File
        private set
    val runId = MutableStateFlow("")
    val recent = MutableStateFlow<List<String>>(emptyList())

    fun runsRoot(): File = File(app.filesDir, "runs")

    fun init(ctx: Context) {
        app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                File(runDir, "crash.txt").appendText("thread=${t.name} time=${Date()}\n${Log.getStackTraceString(e)}\n")
            }
            prev?.uncaughtException(t, e)
        }
        newRun("app-start")
    }

    @Synchronized
    fun newRun(label: String) {
        val id = stamp.format(Instant.now()) + "_" + label.replace(Regex("[^A-Za-z0-9_-]"), "-").take(40)
        val dir = File(runsRoot(), id).apply { mkdirs() }
        val old = writer
        io.execute { runCatching { old?.close() } }
        runDir = dir
        writer = BufferedWriter(FileWriter(File(dir, "events.jsonl"), true))
        runId.value = id
        recent.value = emptyList()
        io.execute { runCatching { File(dir, "device.json").writeText(Json.encode(DeviceInfo.collect(app))) } }
        log("run", "start", mapOf("runId" to id, "label" to label))
    }

    fun log(tag: String, event: String, data: Map<String, Any?> = emptyMap()) {
        val ts = System.currentTimeMillis()
        val line = Json.encode(
            linkedMapOf("ts" to ts, "elapsedMs" to SystemClock.elapsedRealtime(), "tag" to tag, "event" to event, "data" to data)
        )
        val w = synchronized(this) { writer }
        io.execute { runCatching { w?.apply { write(line); newLine(); flush() } } }
        val short = "$tag/$event " + if (data.isEmpty()) "" else Json.encode(data).take(300)
        Log.i(TAG, short)
        recent.update { (it + (clock.format(Instant.ofEpochMilli(ts)) + " " + short)).takeLast(200) }
    }

    fun error(tag: String, event: String, e: Throwable, data: Map<String, Any?> = emptyMap()) =
        log(tag, event, data + mapOf("error" to e.javaClass.name, "message" to e.message))

    /** File inside the current run folder; parent dirs created. */
    fun file(rel: String): File = File(runDir, rel).also { it.parentFile?.mkdirs() }
}
