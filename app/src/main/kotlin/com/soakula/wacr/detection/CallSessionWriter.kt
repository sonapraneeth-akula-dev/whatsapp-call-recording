package com.soakula.wacr.detection

import androidx.room.withTransaction
import com.soakula.wacr.data.AppDatabase
import com.soakula.wacr.data.CallSessionEntity
import com.soakula.wacr.data.CallType
import com.soakula.wacr.data.CallTypeEventEntity
import com.soakula.wacr.data.RecordingDecision

/** Owned by one serialized detector loop; only committed transactions advance its identity map. */
internal class CallSessionWriter(
    private val database: AppDatabase,
    private val elapsedOriginMs: Long,
    private val wallOriginMs: Long
) {
    private var sessions: Map<Long, Long> = emptyMap()

    init {
        require(elapsedOriginMs >= 0 && wallOriginMs > 0) { "Invalid call clock origin" }
    }

    suspend fun apply(events: List<CallEvent>, retained: Set<Long>): Map<Long, Long> {
        require(retained.size <= 17 && retained.all { it > 0 }) { "Invalid retained call identities" }
        val updated = sessions.toMutableMap()
        val dao = database.recordings()
        if (events.any { it !is CallEvent.Transition }) database.withTransaction {
            for (event in events) {
                when (event) {
                    is CallEvent.Started -> if (event.call.sequence !in updated) {
                        val call = event.call
                        require(call.sequence > 0) { "Invalid call identity" }
                        updated[call.sequence] = dao.insertSession(CallSessionEntity(
                            waDisplayName = call.caller.displayName.takeUnless { it == "Unknown" && call.caller.matchKey == null },
                            matchKey = call.caller.matchKey,
                            isGroup = call.isGroup == true,
                            isGroupKnown = call.isGroup != null,
                            direction = call.direction,
                            callType = call.callType ?: CallType.UNKNOWN,
                            ringingAt = call.ringingAtMs?.let(::wallTime),
                            connectedAt = wallTime(call.connectedAtMs),
                            decision = RecordingDecision.IGNORED
                        ))
                    }
                    is CallEvent.Ended -> check(dao.setSessionEnd(
                        checkNotNull(updated[event.call.sequence]) { "Missing ended call identity" }, wallTime(event.endedAtMs)
                    ) == 1) { "Ended call session is missing" }
                    is CallEvent.Resumed -> reopen(updated, event.call.sequence)
                    is CallEvent.Suspended -> reopen(updated, event.call.sequence)
                    is CallEvent.TypeChanged -> {
                        val id = checkNotNull(updated[event.call.sequence]) { "Missing classified call identity" }
                        val change = event.change
                        require(change.to != CallType.UNKNOWN && change.from != change.to && change.to == event.call.callType &&
                            change.atMs >= event.call.connectedAtMs) { "Invalid call-type change" }
                        check(dao.setSessionType(id, change.to) == 1) { "Classified call session is missing" }
                        dao.insertCallTypeEvent(CallTypeEventEntity(id, change.from, change.to, wallTime(change.atMs)))
                    }
                    is CallEvent.Transition -> Unit
                }
            }
            check(updated.size <= 32) { "Too many persisted call identities" }
        }
        sessions = updated.filterKeys { it in retained }
        return sessions.toMap()
    }

    private suspend fun reopen(sessions: Map<Long, Long>, sequence: Long) {
        check(database.recordings().setSessionEnd(
            checkNotNull(sessions[sequence]) { "Missing resumed call identity" }, null
        ) == 1) { "Resumed call session is missing" }
    }

    suspend fun closeAll(elapsedMs: Long) {
        val endedAt = wallTime(elapsedMs)
        database.withTransaction {
            sessions.values.forEach { database.recordings().closeOpenSession(it, endedAt) }
        }
        sessions = emptyMap()
    }

    private fun wallTime(elapsedMs: Long): Long {
        require(elapsedMs >= 0) { "Invalid call time" }
        return Math.addExact(wallOriginMs, Math.subtractExact(elapsedMs, elapsedOriginMs))
            .also { require(it > 0) { "Invalid persisted call time" } }
    }
}
