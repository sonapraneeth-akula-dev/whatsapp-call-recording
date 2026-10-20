package com.soakula.wacr.ui.preview

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.soakula.wacr.data.Appearance
import com.soakula.wacr.ui.library.LibraryRoute
import com.soakula.wacr.ui.library.LibraryScreen
import com.soakula.wacr.ui.library.LibraryUiState
import com.soakula.wacr.ui.theme.WacrTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.Locale

class LibraryBenchmarkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent { WacrTheme(Appearance(dynamicColor = false)) { LibraryBenchmarkScreen() } }
    }
}

private class DrawSample(val started: Long, val finished: CompletableDeferred<Long> = CompletableDeferred())

@Composable
internal fun LibraryBenchmarkScreen() {
    val library = remember { LibraryUiState.Ready(libraryBenchmarkEntries()) }
    val cases = remember { libraryBenchmarkCases() }
    var route by remember { mutableStateOf(LibraryRoute()) }
    var sample by remember { mutableStateOf<DrawSample?>(null) }
    var runner by remember { mutableStateOf<Job?>(null) }
    var report by remember { mutableStateOf("1,000 synthetic recordings / 100 contacts. In-memory fixture; no real recording playback.") }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val owner = LocalLifecycleOwner.current
    val renderedSample = sample
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) runner?.cancel() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); runner?.cancel() }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Synthetic library benchmark", style = MaterialTheme.typography.titleMedium)
            Text("${Build.MANUFACTURER} ${Build.MODEL} / SDK ${Build.VERSION.SDK_INT}", style = MaterialTheme.typography.bodySmall)
            Text(report, style = MaterialTheme.typography.bodySmall)
            Text("Filter state to completed content draw; excludes input dispatch/GPU presentation. Debug build.",
                style = MaterialTheme.typography.bodySmall)
            Row {
                Button(enabled = runner == null, onClick = {
                    runner = scope.launch {
                        try {
                            val timings = mutableListOf<Double>()
                            repeat(5) {
                                for (case in cases) {
                                    withFrameNanos { }
                                    val current = DrawSample(System.nanoTime())
                                    sample = current
                                    route = LibraryRoute.parse(case.route.encode())
                                    val ended = withTimeout(5_000) { current.finished.await() }
                                    timings += (ended - current.started) / 1_000_000.0
                                }
                            }
                            val ordered = timings.sorted()
                            report = String.format(Locale.ROOT, "%s: %d updates, median %.1f ms, max %.1f ms; budget 200 ms.",
                                if (ordered.last() <= 200) "PASS" else "FAIL", ordered.size,
                                ordered[ordered.size / 2], ordered.last())
                        } catch (_: TimeoutCancellationException) {
                            report = "FAILED: no completed draw within 5 seconds. Retry with this screen foreground."
                        } catch (cancelled: CancellationException) {
                            report = "Cancelled: keep this screen foreground and leave filters untouched during a run."
                            throw cancelled
                        } finally {
                            sample?.finished?.cancel()
                            sample = null
                            runner = null
                        }
                    }
                    report = "Running 30 updates. Keep screen foreground; do not edit filters."
                }) { Text("Run benchmark") }
                if (runner != null) TextButton(onClick = { runner?.cancel() }) { Text("Cancel") }
            }
        }
        Box(Modifier.weight(1f).drawWithContent {
            val pending = renderedSample
            drawContent()
            if (pending != null && !pending.finished.isCompleted) {
                val ended = System.nanoTime()
                view.post { pending.finished.complete(ended) }
            }
        }) {
            LibraryScreen(library, {}, route, {
                runner?.cancel()
                route = it
            }) { report = "Synthetic metadata only: no playable recording." }
        }
    }
}
