package com.soakula.wacr.detection

import android.app.Application
import com.soakula.wacr.data.CallDirection
import com.soakula.wacr.data.CallType
import com.soakula.wacr.health.exportDiagnostics
import com.soakula.wacr.util.AppLog
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class DetectionTraceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun everyTypedSignalAndEffectIsTracedWithoutRawIdentityEvenInDebugAndExports() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val rules = app.assets.open("detection_rules.json").use(DetectionRules::load)
        for (release in listOf(false, true)) {
            val directory = temporary.newFolder()
            val mirrored = mutableListOf<String>()
            val log = AppLog(directory, release, mirrored::add) { throw it }
            val trace = DetectionTrace(log, rules)
            val notification = CallNotification(
                "com.whatsapp", "SyntheticPrivateNotificationKey", "call", true, "CallStyle", 2, true, 123,
                CallerName("SyntheticPrivateCaller", "synthetic_private_match_key"), CallDirection.INCOMING
            )
            listOf(
                CallSignal.Posted(notification), CallSignal.Removed("com.whatsapp", "SyntheticPrivateNotificationKey"),
                CallSignal.Window("com.whatsapp", true), CallSignal.AudioMode(true), CallSignal.Tick, CallSignal.Unavailable
            ).forEachIndexed { index, signal -> trace.signal(signal, index.toLong()) }
            val call = DetectedCall(1, "com.whatsapp", "SyntheticPrivateNotificationKey", 123,
                notification.caller, CallDirection.INCOMING, null, null, 1, 2)
            listOf(
                CallEvent.Transition(CallState.IDLE, CallState.RINGING),
                CallEvent.Started(call), CallEvent.Suspended(call, 3, CallSuspension.HOLD),
                CallEvent.Suspended(call, 4, CallSuspension.RECONNECTING),
                CallEvent.Resumed(call, 5), CallEvent.Ended(call, 6),
                CallEvent.TypeChanged(call.copy(callType = CallType.VIDEO), CallTypeChange(CallType.VOICE, CallType.VIDEO, 7))
            ).forEach(trace::event)
            val raw = File(directory, "app.log").readText()
            assertEquals(mirrored.joinToString("\n", postfix = "\n"), raw)
            assertFalse(raw.contains("SyntheticPrivate"))
            assertFalse(raw.contains("synthetic_private_match_key"))
            assertFalse(raw.contains("\"instance\""))
            val events = mirrored.map { JSONObject(it).getString("event") }
            assertEquals(listOf(
                "rules_loaded", "signal_posted", "signal_removed", "signal_window", "signal_audio_mode",
                "signal_tick", "signal_unavailable", "state_idle_ringing", "call_detected", "call_suspended_hold",
                "call_suspended_reconnecting", "call_resumed", "call_ended", "call_type_changed"
            ), events)
            val destination = ByteArrayOutputStream()
            exportDiagnostics(app, log, destination)
            var exported: String? = null
            ZipInputStream(ByteArrayInputStream(destination.toByteArray())).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == "app.jsonl") exported = zip.readBytes().toString(Charsets.UTF_8)
                }
            }
            val diagnostics = checkNotNull(exported)
            assertFalse(diagnostics.contains("SyntheticPrivate"))
            assertFalse(diagnostics.contains("synthetic_private_match_key"))
            assertEquals(events, diagnostics.lineSequence().filter(String::isNotBlank).map { JSONObject(it).getString("event") }.toList())
        }
    }
}
