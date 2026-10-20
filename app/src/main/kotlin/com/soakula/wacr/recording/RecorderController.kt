package com.soakula.wacr.recording

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.StringRes
import com.soakula.wacr.R
import com.soakula.wacr.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

sealed interface RecorderState {
    data object Idle : RecorderState
    data class Preparing(val id: UUID) : RecorderState
    data class Recording(val id: UUID, val startedAt: Long, val lowStorage: Boolean) : RecorderState
    data class Saving(val id: UUID) : RecorderState
    data class Finished(val id: UUID, val durationMs: Long, val reason: RecordingStopReason) : RecorderState
    data class Failed(val id: UUID?, val error: RecorderFailure) : RecorderState
}

enum class RecorderFailure(@param:StringRes val message: Int) {
    NO_MIC_PERMISSION(R.string.recording_error_mic),
    NOTIFICATIONS_DISABLED(R.string.recording_error_notifications),
    FGS_START_DENIED(R.string.recording_error_foreground),
    CAPTURE_UNAVAILABLE(R.string.recording_error_capture),
    LOW_STORAGE(R.string.recording_error_storage),
    EMPTY_RECORDING(R.string.recording_error_empty),
    IO_OR_CRYPTO(R.string.recording_error_io),
    DATABASE(R.string.recording_error_database),
    ENCODER(R.string.recording_error_encoder),
    RECOVERY(R.string.recording_error_recovery),
    NATIVE_UNAVAILABLE(R.string.recording_error_native),
    INTERRUPTED(R.string.recording_error_interrupted),
    INVALID_STATE(R.string.recording_error_state)
}

internal class RecordingRequest(val id: UUID, val sessionId: Long?) {
    val stopRequested = AtomicBoolean(false)
}

class RecorderController internal constructor(
    private val context: Context,
    private val log: AppLog,
    private val launch: (Intent) -> Unit = { context.startForegroundService(it) },
    private val notice: (Int) -> Unit = { showNotice(context, log, it) }
) {
    private val lock = Any()
    private var active: RecordingRequest? = null
    private val mutableState = MutableStateFlow<RecorderState>(RecorderState.Idle)
    val state = mutableState.asStateFlow()

    fun start(sessionId: Long? = null): UUID? = synchronized(lock) {
        require(sessionId == null || sessionId > 0) { "Session ID must be positive" }
        if (active != null) {
            log.log("recorder", "start_ignored_busy")
            return null
        }
        val readiness = readinessFailure()
        if (readiness != null) {
            mutableState.value = RecorderState.Failed(null, readiness)
            report(readiness)
            return null
        }
        val request = RecordingRequest(UUID.randomUUID(), sessionId)
        active = request
        mutableState.value = RecorderState.Preparing(request.id)
        context.getSystemService(NotificationManager::class.java).cancel(RecorderNotification.NOTICE_ID)
        try {
            launch(Intent(context, RecorderService::class.java)
                .setAction(RecorderService.ACTION_START)
                .putExtra(RecorderService.EXTRA_ID, request.id.toString()))
            request.id
        } catch (error: SecurityException) {
            failed(request, readinessFailure() ?: RecorderFailure.FGS_START_DENIED)
            null
        } catch (error: IllegalStateException) {
            failed(request, RecorderFailure.FGS_START_DENIED)
            null
        }
    }

    fun stop() = synchronized(lock) {
        active?.stopRequested?.set(true)
    }

    internal fun stop(id: UUID) = synchronized(lock) {
        if (active?.id == id) active?.stopRequested?.set(true) else log.log("recorder", "stale_stop_ignored")
    }

    internal fun request(id: UUID): RecordingRequest? = synchronized(lock) { active?.takeIf { it.id == id } }

    internal fun readinessFailure(): RecorderFailure? {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return RecorderFailure.NO_MIC_PERMISSION
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ||
            !manager.areNotificationsEnabled() ||
            manager.getNotificationChannel(RecorderNotification.CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) {
            return RecorderFailure.NOTIFICATIONS_DISABLED
        }
        return null
    }

    internal fun recording(request: RecordingRequest, at: Long, warning: Boolean) = synchronized(lock) {
        if (active === request) mutableState.value = RecorderState.Recording(request.id, at, warning)
    }

    internal fun saving(request: RecordingRequest) = synchronized(lock) {
        if (active === request) mutableState.value = RecorderState.Saving(request.id)
    }

    internal fun finished(request: RecordingRequest, result: RecordingResult?) = synchronized(lock) {
        if (active !== request) return
        active = null
        mutableState.value = if (result == null) RecorderState.Idle else RecorderState.Finished(
            request.id, result.audio.durationUs / 1000, result.reason
        )
        if (result?.reason == RecordingStopReason.LOW_STORAGE) notice(R.string.recording_low_storage_saved)
    }

    internal fun failed(request: RecordingRequest, failure: RecorderFailure) = synchronized(lock) {
        if (active !== request) return
        active = null
        mutableState.value = RecorderState.Failed(request.id, failure)
        report(failure)
    }

    private fun report(failure: RecorderFailure) {
        log.log("recorder", "failed", mapOf("reason" to failure.name))
        notice(failure.message)
    }
}

private fun showNotice(context: Context, log: AppLog, @StringRes message: Int) {
    Handler(Looper.getMainLooper()).post {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        val manager = context.getSystemService(NotificationManager::class.java)
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            manager.areNotificationsEnabled()) {
            try {
                manager.notify(RecorderNotification.NOTICE_ID, RecorderNotification(context).notice(message))
            } catch (error: SecurityException) {
                log.log("recorder", "notice_permission_changed")
            }
        }
    }
}
