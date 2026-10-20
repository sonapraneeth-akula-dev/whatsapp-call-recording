package com.soakula.wacr.recording

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.SQLException
import android.media.MediaCodec
import android.net.Uri
import android.os.IBinder
import com.soakula.wacr.WacrApp
import kotlinx.coroutines.*
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.UUID

class RecorderService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as WacrApp
    private val controller get() = app.recorder
    private val notifications by lazy { RecorderNotification(this) }
    private var current: RecordingRequest? = null
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val id = parseId(intent)
        if (intent?.action == ACTION_STOP && id != null) {
            controller.stop(id)
            if (current == null) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val request = id?.let(controller::request)
        if (intent?.action != ACTION_START || request == null) {
            app.log.log("recorder", "invalid_command_ignored")
            if (current == null) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (current != null) {
            app.log.log("recorder", "duplicate_start_ignored")
            return START_NOT_STICKY
        }
        val readiness = controller.readinessFailure()
        if (readiness != null) {
            controller.failed(request, readiness)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        current = request
        try {
            startForeground(
                RecorderNotification.ID, notifications.preparing(stopAction(id)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } catch (error: SecurityException) {
            reject(request, controller.readinessFailure() ?: RecorderFailure.FGS_START_DENIED)
            return START_NOT_STICKY
        } catch (error: IllegalStateException) {
            reject(request, RecorderFailure.FGS_START_DENIED)
            return START_NOT_STICKY
        }
        scope.launch { record(request) }
        return START_NOT_STICKY
    }

    private suspend fun record(request: RecordingRequest) {
        var result: RecordingResult? = null
        var failure: RecorderFailure? = null
        var recovered = false
        try {
            result = withContext(Dispatchers.IO) {
                if (request.stopRequested.get()) return@withContext null
                app.ensureRecovered()
                recovered = true
                if (request.stopRequested.get()) return@withContext null
                val database = app.database()
                runInterruptible {
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        throw SecurityException("Microphone permission revoked")
                    }
                    RecordingJob(
                        database, app.recordingFiles, app.crypto, app.log, StorageGuard.forDirectory(filesDir)
                    ).run(request.id, request.sessionId, request.stopRequested::get, { at, warning ->
                        post(notifications.recording(at, stopAction(request.id), warning))
                        controller.recording(request, at, warning)
                    }, {
                        post(notifications.saving())
                        controller.saving(request)
                    })
                }
            }
        } catch (error: CancellationException) {
            failure = RecorderFailure.INTERRUPTED
            throw error
        } catch (error: RecorderReadinessException) {
            failure = error.failure
        } catch (error: CaptureUnavailableException) {
            failure = RecorderFailure.CAPTURE_UNAVAILABLE
        } catch (error: LowStorageException) {
            failure = RecorderFailure.LOW_STORAGE
        } catch (error: EmptyRecordingException) {
            failure = RecorderFailure.EMPTY_RECORDING
        } catch (error: MediaCodec.CodecException) {
            failure = RecorderFailure.ENCODER
        } catch (error: SecurityException) {
            failure = RecorderFailure.NO_MIC_PERMISSION
        } catch (error: SQLException) {
            failure = RecorderFailure.DATABASE
        } catch (error: GeneralSecurityException) {
            failure = if (recovered) RecorderFailure.IO_OR_CRYPTO else RecorderFailure.RECOVERY
        } catch (error: IOException) {
            failure = if (recovered) RecorderFailure.IO_OR_CRYPTO else RecorderFailure.RECOVERY
        } catch (error: UnsatisfiedLinkError) {
            failure = RecorderFailure.NATIVE_UNAVAILABLE
        } catch (error: IllegalStateException) {
            failure = RecorderFailure.INVALID_STATE
        } catch (error: IllegalArgumentException) {
            failure = RecorderFailure.ENCODER
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                if (failure != null) {
                    app.requireRecovery()
                    try {
                        if (!RecoveryJobService.enqueue(this@RecorderService)) app.log.log("recovery", "schedule_failed")
                    } catch (error: IllegalStateException) {
                        app.log.log("recovery", "schedule_state_error")
                    } catch (error: SecurityException) {
                        app.log.log("recovery", "schedule_permission_error")
                    }
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                current = null
                val problem = failure
                if (problem == null) controller.finished(request, result) else controller.failed(request, problem)
                // A new start may already be queued when terminal state becomes observable.
                stopSelfResult(lastStartId)
            }
        }
    }

    private fun post(notification: Notification) {
        val manager = getSystemService(NotificationManager::class.java)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ||
            !manager.areNotificationsEnabled() ||
            manager.getNotificationChannel(RecorderNotification.CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) {
            throw RecorderReadinessException(RecorderFailure.NOTIFICATIONS_DISABLED)
        }
        try {
            manager.notify(RecorderNotification.ID, notification)
        } catch (error: SecurityException) {
            throw RecorderReadinessException(RecorderFailure.NOTIFICATIONS_DISABLED, error)
        }
    }

    private fun reject(request: RecordingRequest, failure: RecorderFailure) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        current = null
        controller.failed(request, failure)
        stopSelfResult(lastStartId)
    }

    private fun stopAction(id: UUID): PendingIntent = PendingIntent.getService(
        this, 0, Intent(this, RecorderService::class.java)
            .setAction(ACTION_STOP).setData(Uri.parse("wacr://recording/$id/stop"))
            .putExtra(EXTRA_ID, id.toString()), PendingIntent.FLAG_IMMUTABLE
    )

    override fun onDestroy() {
        current?.stopRequested?.set(true)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        internal const val ACTION_START = "com.soakula.wacr.action.START"
        internal const val ACTION_STOP = "com.soakula.wacr.action.STOP"
        internal const val EXTRA_ID = "recording_id"

        internal fun parseId(intent: Intent?): UUID? {
            val value = intent?.getStringExtra(EXTRA_ID) ?: return null
            return try {
                UUID.fromString(value).takeIf { it.toString() == value }
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}

private class RecorderReadinessException(val failure: RecorderFailure, cause: Throwable? = null) : Exception(cause)
