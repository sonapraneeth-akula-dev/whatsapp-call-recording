package com.soakula.wacr.detection

import android.app.Notification
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import com.soakula.wacr.WacrApp
import com.soakula.wacr.health.HealthJobService
import com.soakula.wacr.data.RecordingDecision
import com.soakula.wacr.recording.AskNotification
import com.soakula.wacr.recording.CallRecordingResult
import com.soakula.wacr.recording.RecordingCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

data class CallDetectionState(
    val ready: Boolean = false,
    val failed: Boolean = false,
    val call: DetectedCall? = null,
    val sessionId: Long? = null,
    val suspension: CallSuspension? = null,
    val rulesVersion: String? = null
)

class CallDetection internal constructor(private val app: WacrApp) {
    private sealed interface Input {
        data class Accessibility(val owner: Any, val connected: Boolean) : Input
        data class Listener(val owner: Any, val snapshot: (() -> Array<StatusBarNotification>?)?) : Input
        data class Posted(val owner: Any, val notification: StatusBarNotification) : Input
        data class Removed(val owner: Any, val pkg: String, val key: String) : Input
        data class Window(val owner: Any, val pkg: String, val className: String?) : Input
        data class Mode(val watcher: AudioModeWatcher, val mode: Int) : Input
        data object Recorder : Input
        data class RecordCall(
            val sessionId: Long, val decision: RecordingDecision, val authorize: () -> Boolean,
            val response: CompletableDeferred<CallRecordingResult>
        ) : Input
        data object Stop : Input
    }

    private val mutableState = MutableStateFlow(CallDetectionState())
    val state = mutableState.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inputs = Channel<Input>(64)
    private var accessibility: Any? = null
    private var listener: Any? = null
    private var snapshot: (() -> Array<StatusBarNotification>?)? = null
    private var watcher: AudioModeWatcher? = null
    private var core: CallDetector? = null
    private var trace: DetectionTrace? = null
    private var writer: CallSessionWriter? = null
    private var recording: RecordingCoordinator? = null
    private var recordingUpdates: Job? = null
    private var ids: Map<Long, Long> = emptyMap()
    private var operational = false
    private val elapsedOriginMs = SystemClock.elapsedRealtime()
    private val wallOriginMs = System.currentTimeMillis()

    private val worker = scope.launch(start = CoroutineStart.LAZY) {
        try {
            val rules = app.assets.open("detection_rules.json").use(DetectionRules::load)
            val detector = CallDetector(rules.packages, rules.connection, rules.endDebounceMs, rules.reconnectWindowMs)
            core = detector
            val reader = CallNotificationReader(rules)
            trace = DetectionTrace(app.log, rules)
            mutableState.update { it.copy(rulesVersion = rules.version) }
            while (!mutableState.value.failed) {
                val delay = detector.nextDeadlineMs?.let { (it - SystemClock.elapsedRealtime()).coerceAtLeast(0) }
                val input = if (delay == null) inputs.receive() else withTimeoutOrNull(delay) { inputs.receive() }
                if (input == Input.Stop) break
                if (input == null) accept(CallSignal.Tick) else handle(input, rules, reader)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            fail(error)
        } finally {
            inputs.close()
            operational = false
            mutableState.update { it.copy(ready = false, call = null, sessionId = null, suspension = null) }
            try {
                recordingUpdates?.cancel()
                recording?.close()
                stopWatching()
                val now = SystemClock.elapsedRealtime()
                trace?.signal(CallSignal.Unavailable, now)
                core?.accept(CallSignal.Unavailable, now)?.forEach { trace?.event(it) }
                writer?.closeAll(now)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                fail(error)
            }
            accessibility = null
            listener = null
            snapshot = null
            while (true) {
                val pending = inputs.tryReceive().getOrNull() ?: break
                if (pending is Input.RecordCall) pending.response.complete(CallRecordingResult.Expired)
            }
        }
    }

    init { worker.start() }

    internal fun accessibilityConnected(owner: Any) = send(Input.Accessibility(owner, true))
    internal fun accessibilityDisconnected(owner: Any) = send(Input.Accessibility(owner, false))
    internal fun listenerConnected(owner: Any, snapshot: () -> Array<StatusBarNotification>?) = send(Input.Listener(owner, snapshot))
    internal fun listenerDisconnected(owner: Any) = send(Input.Listener(owner, null))
    internal fun window(owner: Any, pkg: String, className: String?) {
        if (pkg in PACKAGES) send(Input.Window(owner, pkg, className))
    }

    internal fun posted(owner: Any, notification: StatusBarNotification) {
        if (notification.packageName !in PACKAGES) return
        if (notification.notification.category != Notification.CATEGORY_CALL ||
            notification.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
        ) send(Input.Removed(owner, notification.packageName, notification.key))
        else send(Input.Posted(owner, notification))
    }

    internal fun removed(owner: Any, notification: StatusBarNotification) {
        if (notification.packageName in PACKAGES) send(Input.Removed(owner, notification.packageName, notification.key))
    }

    internal suspend fun recordCall(
        sessionId: Long, decision: RecordingDecision, authorize: () -> Boolean
    ): CallRecordingResult {
        require(sessionId > 0)
        val response = CompletableDeferred<CallRecordingResult>(currentCoroutineContext()[Job])
        try {
            inputs.send(Input.RecordCall(sessionId, decision, authorize, response))
        } catch (error: ClosedSendChannelException) {
            app.log.log("policy", "entry_unavailable")
            response.complete(CallRecordingResult.Expired)
            return CallRecordingResult.Expired
        }
        return response.await()
    }

    internal suspend fun shutdown() {
        try {
            if (!worker.isCompleted) inputs.send(Input.Stop)
        } catch (error: ClosedSendChannelException) {
            // The worker can close its channel before its final database cleanup completes.
        }
        worker.join()
    }

    private fun send(input: Input) {
        if (mutableState.value.failed || worker.isCompleted) return
        if (inputs.trySend(input).isFailure) {
            fail(IOException("Call signal queue overflow"))
        }
    }

    private suspend fun handle(input: Input, rules: DetectionRules, reader: CallNotificationReader) {
        when (input) {
            is Input.Accessibility -> {
                if (input.connected) {
                    if (accessibility !== input.owner) reset()
                    accessibility = input.owner
                } else {
                    if (accessibility !== input.owner) return
                    accessibility = null
                    reset()
                }
                startWatching(reader)
            }
            is Input.Listener -> {
                if (input.snapshot != null) {
                    if (listener !== input.owner) reset()
                    listener = input.owner
                    snapshot = input.snapshot
                } else {
                    if (listener !== input.owner) return
                    listener = null
                    snapshot = null
                    reset()
                }
                startWatching(reader)
            }
            is Input.Posted -> if (listener === input.owner && watcher != null) read(input.notification, reader)
            is Input.Removed -> if (listener === input.owner && watcher != null) accept(CallSignal.Removed(input.pkg, input.key))
            is Input.Window -> if (accessibility === input.owner && watcher != null) {
                val profile = version(input.pkg)?.let { rules.profile(input.pkg, it, Build.MODEL, Build.VERSION.SDK_INT) }
                accept(CallSignal.Window(input.pkg, input.className in profile?.callWindowClasses.orEmpty()))
            }
            is Input.Mode -> if (watcher === input.watcher) accept(mode(input.mode))
            Input.Recorder -> recording?.sync(snapshotState(), ids.values.toSet())
            is Input.RecordCall -> try {
                if (input.response.isActive) input.response.complete(
                    recording?.confirm(input.sessionId, input.decision) {
                        input.response.isActive && input.authorize()
                    } ?: CallRecordingResult.Expired
                )
            } finally {
                if (!input.response.isCompleted) input.response.complete(CallRecordingResult.Failed)
            }
            Input.Stop -> Unit
        }
    }

    private suspend fun startWatching(reader: CallNotificationReader) {
        if (accessibility == null || listener == null || watcher != null) return
        lateinit var next: AudioModeWatcher
        next = AudioModeWatcher(app) { mode -> send(Input.Mode(next, mode)) }
        watcher = next
        accept(mode(next.start(app)))
        val notifications = checkNotNull(snapshot).invoke()
        if (notifications == null) {
            listener = null
            snapshot = null
            reset()
            return
        }
        notifications.forEach { notification ->
            if (notification.packageName in PACKAGES && notification.notification.category == Notification.CATEGORY_CALL) {
                read(notification, reader)
            }
        }
        operational = true
        recording?.sync(snapshotState(), ids.values.toSet())
        publish()
    }

    private suspend fun read(notification: StatusBarNotification, reader: CallNotificationReader) {
        reader.read(notification, version(notification.packageName), Build.MODEL, Build.VERSION.SDK_INT)?.let { accept(it) }
    }

    private fun version(pkg: String): String? = try {
        app.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0)).versionName
    } catch (error: PackageManager.NameNotFoundException) {
        app.log.log("detection", "caller_profile_unavailable")
        null
    }

    private fun mode(value: Int) = CallSignal.AudioMode(
        value == AudioManager.MODE_IN_COMMUNICATION, value == AudioManager.MODE_IN_CALL
    )

    private suspend fun reset() {
        stopWatching()
        accept(CallSignal.Unavailable)
    }

    private fun stopWatching() {
        operational = false
        val wasReady = mutableState.value.ready
        mutableState.update { it.copy(ready = false) }
        if (wasReady) refreshHealth()
        val old = watcher
        watcher = null
        old?.close()
    }

    private suspend fun accept(signal: CallSignal) {
        val detector = checkNotNull(core)
        val now = SystemClock.elapsedRealtime()
        trace?.signal(signal, now)
        val events = detector.accept(signal, now)
        events.forEach { trace?.event(it) }
        events.forEach { event ->
            val sequence = when (event) {
                is CallEvent.Suspended -> event.call.sequence
                is CallEvent.Ended -> event.call.sequence
                else -> null
            }
            sequence?.let(ids::get)?.let { recording?.onSignalLoss(it) }
        }
        if (writer == null && events.any { it is CallEvent.Started }) {
            val database = app.database()
            writer = CallSessionWriter(database, elapsedOriginMs, wallOriginMs)
            recording = RecordingCoordinator(database, app.recorder, AskNotification(app, app.log), app.log)
            recordingUpdates = scope.launch { app.recorder.state.collect { send(Input.Recorder) } }
        }
        writer?.let { ids = it.apply(events, detector.retainedSequences) }
        recording?.sync(snapshotState(), ids.values.toSet())
        publish()
    }

    private fun snapshotState(): CallDetectionState {
        val detector = core
        return CallDetectionState(
            ready = operational,
            call = detector?.activeCall,
            sessionId = detector?.activeCall?.sequence?.let(ids::get),
            suspension = detector?.suspension,
            rulesVersion = mutableState.value.rulesVersion
        )
    }

    private fun publish() {
        val updated = snapshotState()
        val wasReady = mutableState.value.ready
        mutableState.update { if (it.failed) it else updated }
        if (wasReady != mutableState.value.ready) refreshHealth()
    }

    private fun fail(error: Exception) {
        app.log.log("detection", "failed", mapOf("error_type" to error.javaClass.simpleName))
        val wasFailed = mutableState.value.failed
        mutableState.update { CallDetectionState(failed = true, rulesVersion = it.rulesVersion) }
        if (!wasFailed) refreshHealth()
    }

    private fun refreshHealth() {
        try {
            if (!HealthJobService.enqueue(app)) app.log.log("health", "schedule_failed")
        } catch (error: SecurityException) {
            app.log.log("health", "schedule_permission_error")
        } catch (error: IllegalStateException) {
            app.log.log("health", "schedule_state_error")
        }
    }

    companion object {
        internal val PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
    }
}
