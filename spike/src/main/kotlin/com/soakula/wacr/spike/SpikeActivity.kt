package com.soakula.wacr.spike

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soakula.wacr.spike.analysis.MatrixReport
import com.soakula.wacr.spike.capture.CaptureService
import com.soakula.wacr.spike.capture.SweepController
import com.soakula.wacr.spike.log.DebugBundle
import com.soakula.wacr.spike.log.SpikeLog
import com.soakula.wacr.spike.signals.CallGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Throwaway, unstyled test screen. */
class SpikeActivity : ComponentActivity() {
    private var resumes by mutableIntStateOf(0)
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        SpikeLog.log("ui", "permissions_result", it.mapKeys { e -> e.key.substringAfterLast('.') })
        resumes++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Screen(resumes) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumes++
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun open(action: String, pkgUri: Boolean = false) = runCatching {
        startActivity(Intent(action).apply { if (pkgUri) data = Uri.parse("package:$packageName") })
    }.onFailure { SpikeLog.error("ui", "open_settings_failed", it, mapOf("action" to action)) }

    @Composable
    private fun Screen(resumes: Int) {
        val runId by SpikeLog.runId.collectAsState()
        val events by SpikeLog.recent.collectAsState()
        val matrix by MatrixReport.results.collectAsState()
        val sweeping by SweepController.running.collectAsState()
        val ready by CaptureService.ready.collectAsState()
        val gate by CallGate.state.collectAsState()
        val scope = rememberCoroutineScope()
        var host by remember { mutableStateOf(Prefs.host) }
        var auto by remember { mutableStateOf(Prefs.autoSweep) }
        var side by remember { mutableIntStateOf(Prefs.sideSeconds) }
        var tree by remember { mutableStateOf(Prefs.treeDump) }
        var shots by remember { mutableStateOf(Prefs.screenshots) }
        var redact by remember { mutableStateOf(Prefs.redact) }
        var withAudio by remember { mutableStateOf(Prefs.includeAudio) }
        var withShots by remember { mutableStateOf(Prefs.includeShots) }
        var callType by remember { mutableStateOf("voice") }
        var direction by remember { mutableStateOf("outgoing") }
        var exportMsg by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }

        LazyColumn(
            contentPadding = WindowInsets.safeDrawing.asPaddingValues(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            item {
                Text("WACR Spike", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · run $runId", fontSize = 12.sp)
                Text("Call: $gate${if (sweeping) " · SWEEP RUNNING" else ""}${if (ready) " · Ready FGS on" else ""}", fontWeight = FontWeight.SemiBold)
            }
            item {
                key(resumes) { // re-read setup state on every resume
                Section("1. Setup (all green before testing)") {
                    SetupRow("Microphone", granted(Manifest.permission.RECORD_AUDIO), "Allow") {
                        permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                    }
                    SetupRow("Notifications (sweep cues)", granted(Manifest.permission.POST_NOTIFICATIONS), "Allow") {
                        permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                    }
                    SetupRow("Contacts (optional: hides names in shared logs)", granted(Manifest.permission.READ_CONTACTS), "Allow") {
                        permissions.launch(arrayOf(Manifest.permission.READ_CONTACTS))
                    }
                    SetupRow("Allow restricted settings (App info › ⋮ menu) — do this first", null, "App info") {
                        open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri = true)
                    }
                    SetupRow("Accessibility: WACR Spike call detector", Access.a11yEnabled(this@SpikeActivity), "Open") {
                        open(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    }
                    SetupRow("Notification access: WACR Spike", Access.notificationAccess(this@SpikeActivity), "Open") {
                        open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    }
                    SetupRow(
                        "Battery: unrestricted",
                        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName), "Allow"
                    ) { open(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri = true) }
                }
                }
            }
            item {
                Section("2. Label the next test call") {
                    Chips(listOf("voice", "video", "group"), callType) { callType = it }
                    Chips(listOf("outgoing", "incoming", "missed"), direction) { direction = it }
                    Button(onClick = {
                        SpikeLog.newRun("$callType-$direction")
                        MatrixReport.reset()
                    }) { Text("Start new run: $callType-$direction") }
                }
            }
            item {
                Section("3. Recorder test") {
                    Toggle("Auto-sweep when a WhatsApp call connects", auto) { auto = it; Prefs.autoSweep = it }
                    Text("How the recorder starts (try each on both phones):", fontWeight = FontWeight.SemiBold)
                    HostOption(Host.ON_DETECT, "Start service on call (planned design)", host) { host = it; Prefs.host = it }
                    HostOption(Host.READY, "Keep a \"Ready\" service running (fallback)", host) { host = it; Prefs.host = it }
                    HostOption(Host.DIRECT, "No service — record inside accessibility", host) { host = it; Prefs.host = it }
                    if (host == Host.READY) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = !ready, onClick = {
                                startForegroundService(Intent(this@SpikeActivity, CaptureService::class.java).setAction(CaptureService.ACTION_READY))
                            }) { Text("Start Ready service") }
                            OutlinedButton(enabled = ready, onClick = { CaptureService.stop(this@SpikeActivity) }) { Text("Stop") }
                        }
                    }
                    Text("Seconds per side (you, then the other person):")
                    Chips(listOf("5", "10", "15"), side.toString()) { side = it.toInt(); Prefs.sideSeconds = side }
                    Toggle("Dump call-screen view tree each second", tree) { tree = it; Prefs.treeDump = it }
                    Toggle("Screenshots of call screen (1/s, stays on phone unless shared)", shots) { shots = it; Prefs.screenshots = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !sweeping, onClick = { SweepController.begin("manual") }) { Text("Sweep now") }
                        OutlinedButton(enabled = sweeping, onClick = { SweepController.stop("manual") }) { Text("Stop sweep") }
                    }
                }
            }
            if (matrix.isNotEmpty()) item {
                Section("4. Results (this run)") {
                    SelectionContainer { Text(MatrixReport.markdown(matrix), fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp) }
                }
            }
            item {
                Section("5. Logs — no USB needed") {
                    Toggle("Hide names & numbers (recommended)", redact) { redact = it; Prefs.redact = it }
                    Toggle("Include audio files", withAudio) { withAudio = it; Prefs.includeAudio = it }
                    Toggle("Include screenshots", withShots) { withShots = it; Prefs.includeShots = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        Button(enabled = !busy, onClick = {
                            busy = true; exportMsg = "Building zip…"
                            scope.launch {
                                exportMsg = runCatching {
                                    withContext(Dispatchers.IO) {
                                        "Saved to " + DebugBundle.saveToDownloads(this@SpikeActivity, DebugBundle.build(this@SpikeActivity, redact, withAudio, withShots))
                                    }
                                }.getOrElse { SpikeLog.error("export", "save_failed", it); "Save failed: $it" }
                                busy = false
                            }
                        }) { Text("Save logs") }
                        Button(enabled = !busy, onClick = {
                            busy = true; exportMsg = "Building zip…"
                            scope.launch {
                                exportMsg = runCatching {
                                    val f = withContext(Dispatchers.IO) { DebugBundle.build(this@SpikeActivity, redact, withAudio, withShots) }
                                    DebugBundle.share(this@SpikeActivity, f)
                                    "Shared ${f.name}"
                                }.getOrElse { SpikeLog.error("export", "share_failed", it); "Share failed: $it" }
                                busy = false
                            }
                        }) { Text("Share logs") }
                        OutlinedButton(onClick = { DebugBundle.deleteAllRuns(); exportMsg = "Old runs deleted" }) { Text("Delete old runs") }
                    }
                    if (exportMsg.isNotEmpty()) Text(exportMsg, fontSize = 12.sp)
                }
            }
            item { Text("Events (newest first)", fontWeight = FontWeight.SemiBold) }
            items(events.asReversed()) {
                Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 12.sp)
            }
        }
    }

    @Composable
    private fun Section(title: String, content: @Composable () -> Unit) = Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            HorizontalDivider()
            content()
        }
    }

    @Composable
    private fun SetupRow(label: String, ok: Boolean?, action: String, onClick: () -> Unit) =
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                when (ok) { true -> "✓"; false -> "✗"; null -> "•" },
                color = when (ok) { true -> Color(0xFF2E7D32); false -> Color(0xFFC62828); null -> Color.Gray },
                fontWeight = FontWeight.Bold, modifier = Modifier.width(20.dp)
            )
            Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
            if (ok != true) OutlinedButton(onClick = onClick) { Text(action) }
        }

    @Composable
    private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) =
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Switch(checked = value, onCheckedChange = onChange)
        }

    @Composable
    private fun HostOption(value: String, label: String, selected: String, onSelect: (String) -> Unit) =
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = value == selected, onClick = { onSelect(value) })
            Text(label, fontSize = 13.sp)
        }

    @Composable
    private fun Chips(options: List<String>, selected: String, onSelect: (String) -> Unit) =
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            options.forEach { FilterChip(selected = it == selected, onClick = { onSelect(it) }, label = { Text(it) }) }
        }
}
