package com.soakula.wacr.spike.analysis

import android.os.Build
import com.soakula.wacr.audio.Rms
import com.soakula.wacr.spike.log.SpikeLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

data class SourceResult(
    val source: String,
    val route: String,
    val host: String,
    val status: String,
    val ownP95: Double? = null,
    val remoteP95: Double? = null,
    val zeroPct: Double? = null,
    val silenced: Boolean? = null,
    val file: String? = null,
) {
    private fun heard(p95: Double?) = status in RECORDED && silenced != true && p95 != null && p95 > Rms.SILENT_P95
    val own get() = heard(ownP95)
    val remote get() = heard(remoteP95)

    companion object { val RECORDED = setOf("ok", "partial") }
}

/** Source × route × side matrix for the current run; shown in-app and saved as matrix.md. */
object MatrixReport {
    val results = MutableStateFlow<List<SourceResult>>(emptyList())

    fun reset() { results.value = emptyList() }
    fun add(r: SourceResult) = results.update { it + r }

    fun markdown(list: List<SourceResult> = results.value): String = buildString {
        appendLine("# Capture matrix — ${Build.MODEL}, Android ${Build.VERSION.RELEASE} — ${SpikeLog.runId.value}")
        appendLine()
        appendLine("✓ = p95 of 100 ms levels > ${Rms.SILENT_P95.toInt()} dBFS (first 2 s of each window skipped) and never silenced.")
        appendLine()
        appendLine("| Source | Route | Host | Status | Own p95 | Remote p95 | Zero % | Silenced | Own | Remote |")
        appendLine("|---|---|---|---|---|---|---|---|---|---|")
        list.forEach {
            appendLine(
                "| ${it.source} | ${it.route} | ${it.host} | ${it.status} | ${f(it.ownP95)} | ${f(it.remoteP95)} | ${f(it.zeroPct)} | " +
                    "${it.silenced ?: "-"} | ${mark(it.status, it.own)} | ${mark(it.status, it.remote)} |"
            )
        }
    }

    fun write() = runCatching { SpikeLog.file("matrix.md").writeText(markdown()) }

    private fun f(v: Double?) = v?.let { String.format(Locale.US, "%.1f", it) } ?: "-"
    private fun mark(status: String, ok: Boolean) = if (status !in SourceResult.RECORDED) "-" else if (ok) "✓" else "✗"
}
