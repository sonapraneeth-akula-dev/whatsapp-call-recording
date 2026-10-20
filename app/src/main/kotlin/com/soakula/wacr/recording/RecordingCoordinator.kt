package com.soakula.wacr.recording

import com.soakula.wacr.data.AppDatabase
import com.soakula.wacr.data.RecordingDecision
import com.soakula.wacr.data.RuleKind
import com.soakula.wacr.detection.CallDetectionState
import com.soakula.wacr.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

internal sealed interface CallRecordingResult {
    data class Started(val id: UUID) : CallRecordingResult
    data object Expired : CallRecordingResult
    data object Busy : CallRecordingResult
    data object NotVisible : CallRecordingResult
    data object Failed : CallRecordingResult
}

/** All operations belong to the detector's serialized input loop. */
internal class RecordingCoordinator(
    private val database: AppDatabase,
    private val recorder: RecorderController,
    private val asks: AskNotification,
    private val log: AppLog
) {
    private data class Owned(val session: Long, val id: UUID, var signalStop: Boolean = false)
    private var owned: Owned? = null
    private var current = CallDetectionState()
    private val approved = mutableSetOf<Long>()
    private val quiet = mutableSetOf<Long>()
    private val autoBlocked = mutableSetOf<Long>()

    suspend fun sync(state: CallDetectionState, retained: Set<Long>) {
        current = state
        approved.retainAll(retained)
        quiet.retainAll(retained)
        autoBlocked.retainAll(retained)
        reconcileOwner()
        owned?.let { active ->
            if (!state.ready || state.sessionId != active.session || state.suspension != null) onSignalLoss(active.session)
        }
        val id = state.sessionId
        val call = state.call
        if (!state.ready || id == null || call == null || state.suspension != null || id in quiet) {
            asks.cancel()
            return
        }
        if (owned?.session == id) {
            asks.cancel()
            return
        }
        val rule = call.caller.matchKey?.let { database.contactRules().matching(RuleKind.CONTACT, it) }
        val automatic = id !in approved && id !in autoBlocked &&
            RecordingPolicy.decide(call.caller, call.isGroup, rule) == RecordingPolicy.Decision.AUTO
        if (id !in approved && !automatic) {
            if (asks.show(id)) setDecision(id, RecordingDecision.ASKED)
            return
        }
        asks.cancel()
        if (owned != null || busy()) return
        if (automatic) setDecision(id, RecordingDecision.AUTO)
        val started = recorder.start(id)
        if (started == null) {
            autoBlocked += id
            approved.remove(id)
            if (asks.show(id)) setDecision(id, RecordingDecision.ASKED)
        } else {
            owned = Owned(id, started)
            log.log("policy", "capture_requested", mapOf("automatic" to automatic))
        }
    }

    private fun reconcileOwner() {
        val recording = recorder.state.value
        owned?.let { active ->
            val visibleId = when (recording) {
                is RecorderState.Preparing -> recording.id
                is RecorderState.Recording -> recording.id
                is RecorderState.Saving -> recording.id
                is RecorderState.Finished -> recording.id
                is RecorderState.Failed -> recording.id
                RecorderState.Idle -> null
            }
            val terminal = recording is RecorderState.Idle || recording is RecorderState.Finished ||
                recording is RecorderState.Failed || visibleId != active.id
            if (terminal) {
                if (recording is RecorderState.Failed && visibleId == active.id) {
                    approved.remove(active.session)
                    autoBlocked += active.session
                } else if (!active.signalStop) quiet += active.session
                owned = null
            } else if (recording is RecorderState.Saving && !active.signalStop) {
                quiet += active.session
                approved.remove(active.session)
            }
        }
    }

    fun onSignalLoss(sessionId: Long) {
        reconcileOwner()
        if (current.sessionId == sessionId) asks.cancel()
        val active = owned?.takeIf { it.session == sessionId && !it.signalStop } ?: return
        if (recorder.request(active.id)?.stopRequested?.get() == true) {
            quiet += active.session
            approved.remove(active.session)
        }
        active.signalStop = true
        recorder.stop(active.id)
        asks.cancel()
    }

    suspend fun confirm(sessionId: Long, decision: RecordingDecision, authorize: () -> Boolean): CallRecordingResult {
        require(sessionId > 0 && decision in setOf(RecordingDecision.ASKED, RecordingDecision.MANUAL))
        if (!current.ready || current.sessionId != sessionId || current.call == null || current.suspension != null) {
            log.log("policy", "stale_entry_rejected")
            return CallRecordingResult.Expired
        }
        if (busy()) return CallRecordingResult.Busy
        if (!withContext(Dispatchers.Main.immediate) { authorize() }) return CallRecordingResult.NotVisible
        setDecision(sessionId, decision)
        val result = withContext(Dispatchers.Main.immediate) {
            if (!authorize()) CallRecordingResult.NotVisible
            else recorder.start(sessionId)?.let(CallRecordingResult::Started) ?: CallRecordingResult.Failed
        }
        if (result is CallRecordingResult.Started) {
            quiet.remove(sessionId)
            autoBlocked.remove(sessionId)
            approved += sessionId
            owned = Owned(sessionId, result.id)
            asks.cancel()
            log.log("policy", "confirmed_capture_requested", mapOf("decision" to decision))
        }
        return result
    }

    fun close() {
        owned?.let { recorder.stop(it.id) }
        owned = null
        approved.clear()
        quiet.clear()
        autoBlocked.clear()
        current = CallDetectionState()
        asks.cancel()
    }

    private fun busy() = when (recorder.state.value) {
        is RecorderState.Preparing, is RecorderState.Recording, is RecorderState.Saving -> true
        else -> false
    }

    private suspend fun setDecision(id: Long, decision: RecordingDecision) {
        check(database.recordings().setSessionDecision(id, decision) == 1) { "Recording policy session is missing" }
    }
}
