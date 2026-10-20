package com.soakula.wacr.detection

import com.soakula.wacr.data.CallDirection
import com.soakula.wacr.data.CallType

enum class CallState { IDLE, RINGING, CONNECTED, ENDED }
enum class CallSuspension { RECONNECTING, HOLD }

data class CallNotification(
    val packageName: String,
    val key: String,
    val category: String?,
    val ongoing: Boolean,
    val template: String?,
    val styleCallType: Int?,
    val chronometer: Boolean,
    val instance: Long?,
    val caller: CallerName = CallerName("Unknown", null),
    val direction: CallDirection = CallDirection.UNKNOWN,
    val callType: CallType? = null,
    val isGroup: Boolean? = null,
    val onHold: Boolean? = null
)

data class DetectedCall(
    val sequence: Long,
    val packageName: String,
    val notificationKey: String,
    val instance: Long,
    val caller: CallerName,
    val direction: CallDirection,
    val callType: CallType?,
    val isGroup: Boolean?,
    val ringingAtMs: Long?,
    val connectedAtMs: Long
)

sealed interface CallSignal {
    data class Posted(val notification: CallNotification) : CallSignal
    data class Removed(val packageName: String, val key: String) : CallSignal
    data class AudioMode(val inCommunication: Boolean, val cellularCall: Boolean = false) : CallSignal
    data class Window(val packageName: String, val callWindow: Boolean) : CallSignal
    data object Tick : CallSignal
    data object Unavailable : CallSignal
}

sealed interface CallEvent {
    data class Transition(val from: CallState, val to: CallState) : CallEvent
    data class Started(val call: DetectedCall) : CallEvent
    data class Suspended(val call: DetectedCall, val atMs: Long, val reason: CallSuspension) : CallEvent
    data class Resumed(val call: DetectedCall, val atMs: Long) : CallEvent
    data class Ended(val call: DetectedCall, val endedAtMs: Long) : CallEvent
    data class TypeChanged(val call: DetectedCall, val change: CallTypeChange) : CallEvent
}

class CallDetector(
    private val packages: Set<String>,
    private val connection: ConnectionRules,
    private val endDebounceMs: Long,
    private val reconnectWindowMs: Long
) {
    var state: CallState = CallState.IDLE
        private set
    var activeCall: DetectedCall? = null
        private set
    val nextDeadlineMs: Long? get() = listOfNotNull(disconnectDeadlineMs, closed.values.minOfOrNull { it.expiresAtMs }).minOrNull()
    val suspension: CallSuspension? get() = pauseReason
    val callWindowPackages: Set<String> get() = windows.toSet()
    val retainedSequences: Set<Long> get() =
        (held.values.map { it.sequence } + closed.values.map { it.call.sequence } + listOfNotNull(activeCall?.sequence)).toSet()

    private data class Key(val packageName: String, val key: String)
    private data class Observed(val notification: CallNotification, val ringingAtMs: Long?, val generation: Long)
    private data class Closed(val call: DetectedCall, val expiresAtMs: Long)
    private val notifications = linkedMapOf<Key, Observed>()
    private val windows = mutableSetOf<String>()
    private val held = linkedMapOf<Pair<Key, Long>, DetectedCall>()
    private val closed = linkedMapOf<Pair<Key, Long>, Closed>()
    private var communication = false
    private var cellularCall = false
    private var generation = 0L
    private var cellularBoundary = -1L
    private var lastTimeMs = 0L
    private var sequence = 0L
    private var disconnectedAtMs: Long? = null
    private var disconnectDeadlineMs: Long? = null
    private var pauseReason: CallSuspension? = null

    init {
        require(packages.isNotEmpty() && packages.all { it == "com.whatsapp" || it == "com.whatsapp.w4b" })
        require(endDebounceMs in 0..5000)
        require(reconnectWindowMs == 0L || reconnectWindowMs == 30_000L)
        require(connection == ConnectionRules("call", "CallStyle", 2))
    }

    fun accept(signal: CallSignal, nowMs: Long): List<CallEvent> {
        require(nowMs >= lastTimeMs && nowMs <= Long.MAX_VALUE - maxOf(endDebounceMs, reconnectWindowMs)) { "Invalid detector time" }
        if (signal is CallSignal.AudioMode) {
            require(!signal.inCommunication || !signal.cellularCall) { "Conflicting audio modes" }
        }
        if (signal is CallSignal.Posted && signal.notification.packageName in packages) {
            val notification = signal.notification
            require(notification.key.isNotBlank() && notification.key.length <= 512) { "Invalid notification identity" }
            if (relevant(notification)) {
                require(Key(notification.packageName, notification.key) in notifications || notifications.size < 8) {
                    "Too many call notifications"
                }
                val current = activeCall
                if (current != null && pauseReason == CallSuspension.HOLD &&
                    connected(notification) && notification.onHold != true && !sameSource(current, notification)
                ) {
                    require(held.size < 8 || (Key(notification.packageName, notification.key) to notification.instance) in held) {
                        "Too many held calls"
                    }
                }
                if (notification.onHold == true &&
                    (Key(notification.packageName, notification.key) to notification.instance) in closed
                ) {
                    require(held.size < 8) { "Too many held calls" }
                }
            }
        }
        lastTimeMs = nowMs
        generation++
        val events = mutableListOf<CallEvent>()
        expire(nowMs, events)
        when (signal) {
            is CallSignal.Posted -> update(signal.notification, nowMs)
            is CallSignal.Removed -> notifications.remove(Key(signal.packageName, signal.key))
            is CallSignal.AudioMode -> {
                if (cellularCall || signal.cellularCall) cellularBoundary = generation
                communication = signal.inCommunication
                cellularCall = signal.cellularCall
            }
            is CallSignal.Window -> if (signal.packageName in packages) {
                if (signal.callWindow) windows.add(signal.packageName) else windows.remove(signal.packageName)
            }
            CallSignal.Unavailable -> {
                notifications.clear()
                windows.clear()
                communication = false
                cellularCall = false
                finish(nowMs, events, allowReconnect = false)
                held.values.forEach { events.add(CallEvent.Ended(it, nowMs)) }
                held.clear()
                closed.clear()
            }
            CallSignal.Tick -> Unit
        }
        reconcile(nowMs, events)
        expire(nowMs, events)
        return events
    }

    private fun update(notification: CallNotification, nowMs: Long) {
        if (notification.packageName !in packages) return
        val key = Key(notification.packageName, notification.key)
        if (!relevant(notification)) {
            notifications.remove(key)
            return
        }
        val previous = notifications.remove(key)
        val sameAttempt = previous?.takeUnless {
            callerConflict(it.notification.caller, notification.caller) ||
                ((connected(it.notification) || it.notification.onHold == true) &&
                    ((!connected(notification) && notification.onHold != true) || it.notification.instance != notification.instance))
        }
        val direction = notification.direction.takeUnless { it == CallDirection.UNKNOWN }
            ?: sameAttempt?.notification?.direction ?: CallDirection.UNKNOWN
        notifications[key] = Observed(
            notification.copy(
                direction = direction,
                onHold = notification.onHold ?: sameAttempt?.notification?.onHold
            ),
            sameAttempt?.ringingAtMs ?: nowMs.takeUnless { connected(notification) },
            generation
        )
    }

    private fun connected(notification: CallNotification): Boolean =
        notification.category == connection.category && notification.ongoing &&
            notification.template == connection.template && notification.styleCallType == connection.ongoingCallType &&
            notification.chronometer && notification.instance != null && notification.instance > 0

    private fun relevant(notification: CallNotification): Boolean =
        notification.category == connection.category && (notification.ongoing ||
            (notification.template == connection.template && notification.styleCallType == 1))

    private fun reconcile(nowMs: Long, events: MutableList<CallEvent>) {
        val abandoned = held.filterValues { call ->
            val source = notifications[Key(call.packageName, call.notificationKey)]?.notification
            source == null || (source.instance == call.instance && !sameCall(call, source))
        }
        abandoned.forEach { (key, call) ->
            held.remove(key)
            events.add(CallEvent.Ended(call, nowMs))
            remember(call, nowMs)
        }
        for (observed in notifications.values) {
            val notification = observed.notification
            val instance = notification.instance ?: continue
            if (notification.onHold != true) continue
            val identity = Key(notification.packageName, notification.key) to instance
            val restored = closed[identity]?.call?.takeIf { sameCall(it, notification) } ?: continue
            closed.remove(identity)
            held[identity] = restored
            events.add(CallEvent.Suspended(restored, nowMs, CallSuspension.HOLD))
        }
        updateTypes(nowMs, events)
        val candidate = if (communication) notifications.values.lastOrNull {
            connected(it.notification) && it.notification.onHold != true && it.generation > cellularBoundary
        } else null
        val current = activeCall
        if (candidate != null) {
            val notification = candidate.notification
            if (current != null && !sameCall(current, notification)) {
                if ((pauseReason == CallSuspension.HOLD || isHeld(current)) && !sameSource(current, notification)) {
                    suspend(current, nowMs, CallSuspension.HOLD, events)
                    held[Key(current.packageName, current.notificationKey) to current.instance] = current
                    activeCall = null
                } else {
                    finish(disconnectedAtMs ?: nowMs, events)
                }
            }
            val wasSuspended = pauseReason != null
            pauseReason = null
            disconnectedAtMs = null
            disconnectDeadlineMs = null
            if (activeCall == null) {
                val key = Key(notification.packageName, notification.key)
                val identity = key to requireNotNull(notification.instance)
                val saved = held.remove(identity) ?: closed.remove(identity)?.call
                val resumed = saved?.takeIf { sameCall(it, notification) }
                val call = resumed?.copy(
                    caller = notification.caller,
                    direction = notification.direction.takeUnless { it == CallDirection.UNKNOWN } ?: resumed.direction,
                    callType = notification.callType,
                    isGroup = notification.isGroup
                ) ?: DetectedCall(
                    sequence = ++sequence,
                    packageName = notification.packageName,
                    notificationKey = notification.key,
                    instance = requireNotNull(notification.instance),
                    caller = notification.caller,
                    direction = notification.direction,
                    callType = notification.callType,
                    isGroup = notification.isGroup,
                    ringingAtMs = candidate.ringingAtMs,
                    connectedAtMs = nowMs
                )
                activeCall = call
                transition(CallState.CONNECTED, events)
                events.add(if (resumed != null) CallEvent.Resumed(call, nowMs) else CallEvent.Started(call))
            } else if (wasSuspended) {
                val resumed = requireNotNull(activeCall).copy(
                    caller = notification.caller, callType = notification.callType, isGroup = notification.isGroup
                )
                activeCall = resumed
                events.add(CallEvent.Resumed(resumed, nowMs))
            }
        } else if (current != null) {
            suspend(current, nowMs, if (isHeld(current)) CallSuspension.HOLD else CallSuspension.RECONNECTING, events)
        } else {
            val ringing = notifications.values.any { !connected(it.notification) && it.notification.onHold != true }
            if (ringing) transition(CallState.RINGING, events)
            else if (state == CallState.RINGING) transition(CallState.IDLE, events)
        }
    }

    private fun updateTypes(nowMs: Long, events: MutableList<CallEvent>) {
        for ((key, observed) in notifications) {
            val notification = observed.notification
            if (!connected(notification) && notification.onHold != true) continue
            val instance = notification.instance ?: continue
            fun classify(call: DetectedCall): DetectedCall {
                if (!sameCall(call, notification)) return call
                val change = CallTypeClassifier.change(call.callType, notification.callType, nowMs) ?: return call
                return call.copy(callType = change.to).also { events.add(CallEvent.TypeChanged(it, change)) }
            }
            activeCall = activeCall?.let(::classify)
            val identity = key to instance
            held[identity]?.let { held[identity] = classify(it) }
            closed[identity]?.let { closed[identity] = it.copy(call = classify(it.call)) }
        }
    }

    private fun expire(nowMs: Long, events: MutableList<CallEvent>) {
        closed.entries.removeAll { it.value.expiresAtMs <= nowMs }
        val deadline = disconnectDeadlineMs ?: return
        if (nowMs >= deadline) finish(requireNotNull(disconnectedAtMs), events)
    }

    private fun suspend(call: DetectedCall, nowMs: Long, reason: CallSuspension, events: MutableList<CallEvent>) {
        if (pauseReason != reason) events.add(CallEvent.Suspended(call, nowMs, reason))
        pauseReason = reason
        if (reason == CallSuspension.HOLD) {
            disconnectedAtMs = null
            disconnectDeadlineMs = null
        } else if (disconnectedAtMs == null) {
            disconnectedAtMs = nowMs
            disconnectDeadlineMs = Math.addExact(nowMs, endDebounceMs)
        }
    }

    private fun observation(call: DetectedCall): Observed? =
        notifications[Key(call.packageName, call.notificationKey)]?.takeIf { sameCall(call, it.notification) }

    private fun isHeld(call: DetectedCall): Boolean {
        val observed = observation(call)
        if (observed != null) return observed.let {
            it.notification.onHold == true || cellularCall || it.generation <= cellularBoundary
        }
        val replacement = notifications[Key(call.packageName, call.notificationKey)]?.notification
        return pauseReason == CallSuspension.HOLD && replacement != null && replacement.instance != call.instance
    }

    private fun remember(call: DetectedCall, endedAtMs: Long) {
        val expires = Math.addExact(endedAtMs, reconnectWindowMs)
        if (reconnectWindowMs == 0L || expires <= lastTimeMs) return
        val key = Key(call.packageName, call.notificationKey) to call.instance
        closed.remove(key)
        if (closed.size == 8) closed.remove(closed.keys.first())
        closed[key] = Closed(call, expires)
    }

    private fun finish(endedAtMs: Long, events: MutableList<CallEvent>, allowReconnect: Boolean = true) {
        val current = activeCall ?: return
        activeCall = null
        disconnectDeadlineMs = null
        disconnectedAtMs = null
        pauseReason = null
        val key = Key(current.packageName, current.notificationKey)
        if (notifications[key]?.notification?.let { sameCall(current, it) } == true) notifications.remove(key)
        transition(CallState.ENDED, events)
        events.add(CallEvent.Ended(current, endedAtMs))
        if (allowReconnect) remember(current, endedAtMs)
    }

    private fun sameSource(call: DetectedCall, notification: CallNotification): Boolean =
        call.packageName == notification.packageName && call.notificationKey == notification.key &&
            call.instance == notification.instance

    private fun callerConflict(first: CallerName, second: CallerName): Boolean =
        first.matchKey != null && second.matchKey != null && first.matchKey != second.matchKey

    private fun sameCall(call: DetectedCall, notification: CallNotification): Boolean =
        sameSource(call, notification) && !callerConflict(call.caller, notification.caller)

    private fun transition(next: CallState, events: MutableList<CallEvent>) {
        if (state == next) return
        events.add(CallEvent.Transition(state, next))
        state = next
    }
}
