package com.soakula.wacr.util

import com.soakula.wacr.logging.Json
import com.soakula.wacr.logging.Redactor
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

class AppLog(
    private val directory: File,
    private val redact: Boolean,
    private val mirror: (String) -> Unit,
    private val reportFailure: (IOException) -> Unit
) {
    @Synchronized
    fun log(tag: String, event: String, data: Map<String, Any?> = emptyMap()) {
        require(IDENTIFIER.matches(tag) && IDENTIFIER.matches(event)) { "Log identifiers must be static snake_case" }
        var line = Json.encode(mapOf("ts" to System.currentTimeMillis(), "tag" to tag, "event" to event, "data" to safe(data)))
        if (line.toByteArray(Charsets.UTF_8).size > 4096) {
            line = Json.encode(mapOf("ts" to System.currentTimeMillis(), "tag" to tag, "event" to event, "data_truncated" to true))
        }
        mirror(line)
        try {
            ensureDirectory()
            appendBounded((line + "\n").toByteArray(Charsets.UTF_8))
        } catch (error: IOException) {
            reportFailure(error)
        }
    }

    fun crashHandler(previous: Thread.UncaughtExceptionHandler) = Thread.UncaughtExceptionHandler { thread, error ->
        try {
            writeCrash(thread, error)
        } catch (failure: IOException) {
            reportFailure(failure)
        } finally {
            previous.uncaughtException(thread, error)
        }
    }

    @Synchronized
    private fun writeCrash(thread: Thread, error: Throwable) {
        ensureDirectory()
        val trace = if (!redact) error.stackTraceToString() else buildString {
            val seen = mutableSetOf<Throwable>()
            var cause: Throwable? = error
            while (cause != null && seen.add(cause)) {
                appendLine(cause.javaClass.name)
                cause.stackTrace.forEach { appendLine("\tat $it") }
                cause = cause.cause
            }
        }
        File.createTempFile("crash-${System.currentTimeMillis()}-", ".txt", directory).writeText(
            "thread=${safe(thread.name)}\n${trace.take(MAX_BYTES / 4)}",
            Charsets.UTF_8
        )
    }

    private fun ensureDirectory() {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create diagnostic directory")
    }

    private fun appendBounded(bytes: ByteArray) {
        // ponytail: one synchronized 1 MB ring; batch writes if tracing affects capture latency.
        val file = File(directory, "app.log")
        if (file.length() + bytes.size > MAX_BYTES) {
            val tail = RandomAccessFile(file, "r").use { input ->
                input.seek((input.length() - MAX_BYTES / 2).coerceAtLeast(0))
                ByteArray((input.length() - input.filePointer).toInt()).also(input::readFully)
            }
            val start = tail.indexOf('\n'.code.toByte()) + 1
            file.writeBytes(if (start == 0) byteArrayOf() else tail.copyOfRange(start, tail.size))
        }
        file.appendBytes(bytes)
    }

    private fun safe(value: Any?): Any? = when (value) {
        null, is Boolean, is Number -> value
        is Map<*, *> -> value.entries.associate { (key, item) -> key.toString() to safe(item) }
        is Iterable<*> -> value.map(::safe)
        is Array<*> -> value.map(::safe)
        else -> if (redact) Redactor.tag("value", value.toString()) else value.toString().take(2000)
    }

    companion object {
        const val MAX_BYTES = 1024 * 1024
        private val IDENTIFIER = Regex("[a-z][a-z0-9_]{0,63}")
    }
}
