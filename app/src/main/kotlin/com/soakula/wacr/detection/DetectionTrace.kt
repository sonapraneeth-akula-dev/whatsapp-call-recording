package com.soakula.wacr.detection

import com.soakula.wacr.util.AppLog
import java.util.Locale

internal class DetectionTrace(private val log: AppLog, rules: DetectionRules) {
    init { log.log("detection", "rules_loaded", mapOf("version" to rules.version)) }

    fun signal(signal: CallSignal, atMs: Long) {
        val data = mutableMapOf<String, Any?>("at_elapsed_ms" to atMs)
        val event = when (signal) {
            is CallSignal.Posted -> {
                val value = signal.notification
                data += mapOf(
                    "business" to (value.packageName == "com.whatsapp.w4b"),
                    "ongoing" to value.ongoing, "chronometer" to value.chronometer,
                    "call_category" to (value.category == "call"),
                    "call_style" to (value.template == "CallStyle"),
                    "style_call_type" to value.styleCallType,
                    "caller_key_available" to (value.caller.matchKey != null),
                    "group_known" to (value.isGroup != null), "type_known" to (value.callType != null),
                    "hold_known" to (value.onHold != null)
                )
                "signal_posted"
            }
            is CallSignal.Removed -> {
                data["business"] = signal.packageName == "com.whatsapp.w4b"
                "signal_removed"
            }
            is CallSignal.Window -> {
                data["business"] = signal.packageName == "com.whatsapp.w4b"
                data["call_window"] = signal.callWindow
                "signal_window"
            }
            is CallSignal.AudioMode -> {
                data["communication"] = signal.inCommunication
                data["cellular"] = signal.cellularCall
                "signal_audio_mode"
            }
            CallSignal.Tick -> "signal_tick"
            CallSignal.Unavailable -> "signal_unavailable"
        }
        log.log("detection", event, data)
    }

    fun event(event: CallEvent) {
        val data = mutableMapOf<String, Any?>()
        val name = when (event) {
            is CallEvent.Transition -> "state_${event.from.name.lowercase(Locale.ROOT)}_${event.to.name.lowercase(Locale.ROOT)}"
            is CallEvent.Started -> {
                data["sequence"] = event.call.sequence
                data["at_elapsed_ms"] = event.call.connectedAtMs
                "call_detected"
            }
            is CallEvent.Suspended -> {
                data["sequence"] = event.call.sequence
                data["at_elapsed_ms"] = event.atMs
                "call_suspended_${event.reason.name.lowercase(Locale.ROOT)}"
            }
            is CallEvent.Resumed -> {
                data["sequence"] = event.call.sequence
                data["at_elapsed_ms"] = event.atMs
                "call_resumed"
            }
            is CallEvent.Ended -> {
                data["sequence"] = event.call.sequence
                data["at_elapsed_ms"] = event.endedAtMs
                "call_ended"
            }
        }
        log.log("detection", name, data)
    }
}
