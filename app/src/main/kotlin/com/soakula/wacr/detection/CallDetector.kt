package com.soakula.wacr.detection

import com.soakula.wacr.data.CallDirection
import com.soakula.wacr.data.CallType

enum class CallState { IDLE, RINGING, CONNECTED, ENDED }

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
    val isGroup: Boolean? = null
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
    data class AudioMode(val inCommunication: Boolean) : CallSignal
    data class Window(val packageName: String, val callWindow: Boolean) : CallSignal
    data object Tick : CallSignal
    data object Unavailable : CallSignal
}

sealed interface CallEvent {
    data class Transition(val from: CallState, val to: CallState) : CallEvent
    data class Started(val call: DetectedCall) : CallEvent
    data class Ended(val call: DetectedCall, val endedAtMs: Long) : CallEvent
}

class CallDetector(
    private val packages: Set<String>,
    private val connection: ConnectionRules,
    private val endDebounceMs: Long
) {
    var state: CallState = CallState.IDLE
        private set
    var activeCall: DetectedCall? = null
        private set
    var nextDeadlineMs: Long? = null
        private set
    val callWindowPackages: Set<String> get() = windows.toSet()

    private data class Key(val packageName: String, val key: String)
    private data class Observed(val notification: CallNotification, val ringingAtMs: Long?)
    private val notifications = linkedMapOf<Key, Observed>()
    private val windows = mutableSetOf<String>()
    private var communication = false
    private var lastTimeMs = 0L
    private var sequence = 0L
    private var disconnectedAtMs: Long? = null

    init {
        require(packages.isNotEmpty() && packages.all { it == "com.whatsapp" || it == "com.whatsapp.w4b" })
        require(endDebounceMs in 0..5000)
        require(connection == ConnectionRules("call", "CallStyle", 2))
    }

    fun accept(signal: CallSignal, nowMs: Long): List<CallEvent> {
        require(nowMs >= lastTimeMs && nowMs <= Long.MAX_VALUE - endDebounceMs) { "Invalid detector time" }
        if (signal is CallSignal.Posted && signal.notification.packageName in packages) {
            val notification = signal.notification
            require(notification.key.isNotBlank() && notification.key.length <= 512) { "Invalid notification identity" }
            if (notification.category == connection.category && notification.ongoing) {
                require(Key(notification.packageName, notification.key) in notifications || notifications.size < 8) {
                    "Too many call notifications"
                }
            }
        }
        lastTimeMs = nowMs
        val events = mutableListOf<CallEvent>()
        expire(nowMs, events)
        when (signal) {
            is CallSignal.Posted -> update(signal.notification, nowMs)
            is CallSignal.Removed -> notifications.remove(Key(signal.packageName, signal.key))
            is CallSignal.AudioMode -> communication = signal.inCommunication
            is CallSignal.Window -> if (signal.packageName in packages) {
                if (signal.callWindow) windows.add(signal.packageName) else windows.remove(signal.packageName)
            }
            CallSignal.Unavailable -> {
                notifications.clear()
                windows.clear()
                communication = false
                finish(nowMs, events)
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
        if (notification.category != connection.category || !notification.ongoing) {
            notifications.remove(key)
            return
        }
        val previous = notifications.remove(key)
        val sameAttempt = previous?.takeUnless {
            connected(it.notification) && (!connected(notification) || it.notification.instance != notification.instance)
        }
        val direction = notification.direction.takeUnless { it == CallDirection.UNKNOWN }
            ?: sameAttempt?.notification?.direction ?: CallDirection.UNKNOWN
        notifications[key] = Observed(
            notification.copy(direction = direction),
            sameAttempt?.ringingAtMs ?: nowMs.takeUnless { connected(notification) }
        )
    }

    private fun connected(notification: CallNotification): Boolean =
        notification.category == connection.category && notification.ongoing &&
            notification.template == connection.template && notification.styleCallType == connection.ongoingCallType &&
            notification.chronometer && notification.instance != null && notification.instance > 0

    private fun reconcile(nowMs: Long, events: MutableList<CallEvent>) {
        val candidate = if (communication) notifications.values.lastOrNull { connected(it.notification) } else null
        val current = activeCall
        if (candidate != null) {
            val notification = candidate.notification
            if (current != null && !sameCall(current, notification)) finish(nowMs, events)
            disconnectedAtMs = null
            nextDeadlineMs = null
            if (activeCall == null) {
                val call = DetectedCall(
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
                events.add(CallEvent.Started(call))
            }
        } else if (current != null) {
            if (disconnectedAtMs == null) {
                disconnectedAtMs = nowMs
                nextDeadlineMs = Math.addExact(nowMs, endDebounceMs)
            }
        } else {
            val ringing = notifications.values.any { !connected(it.notification) }
            if (ringing) transition(CallState.RINGING, events)
            else if (state == CallState.RINGING) transition(CallState.IDLE, events)
        }
    }

    private fun expire(nowMs: Long, events: MutableList<CallEvent>) {
        val deadline = nextDeadlineMs ?: return
        if (nowMs >= deadline) finish(requireNotNull(disconnectedAtMs), events)
    }

    private fun finish(endedAtMs: Long, events: MutableList<CallEvent>) {
        val current = activeCall ?: return
        activeCall = null
        nextDeadlineMs = null
        disconnectedAtMs = null
        val key = Key(current.packageName, current.notificationKey)
        if (notifications[key]?.notification?.let { sameCall(current, it) } == true) notifications.remove(key)
        transition(CallState.ENDED, events)
        events.add(CallEvent.Ended(current, endedAtMs))
    }

    private fun sameCall(call: DetectedCall, notification: CallNotification): Boolean =
        call.packageName == notification.packageName && call.notificationKey == notification.key &&
            call.instance == notification.instance

    private fun transition(next: CallState, events: MutableList<CallEvent>) {
        if (state == next) return
        events.add(CallEvent.Transition(state, next))
        state = next
    }
}
