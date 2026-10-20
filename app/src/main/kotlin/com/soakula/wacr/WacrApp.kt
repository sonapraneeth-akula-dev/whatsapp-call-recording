package com.soakula.wacr

import android.app.Application
import android.util.Log
import com.soakula.wacr.crypto.KeyManager
import com.soakula.wacr.crypto.CryptoStore
import com.soakula.wacr.data.RecordingFiles
import com.soakula.wacr.data.AppDatabase
import com.soakula.wacr.data.AppPreferences
import com.soakula.wacr.data.RecordingDeletion
import com.soakula.wacr.util.AppLog
import com.soakula.wacr.recording.Recovery
import com.soakula.wacr.recording.RecoveryJobService
import com.soakula.wacr.recording.RecoveryReport
import com.soakula.wacr.recording.RecorderController
import com.soakula.wacr.recording.DeletionJobService
import com.soakula.wacr.health.HealthMonitor
import com.soakula.wacr.detection.CallDetection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException

class WacrApp : Application() {
    val recordingFiles by lazy { RecordingFiles(filesDir) }
    val keys by lazy { KeyManager.open(this) }
    val crypto by lazy { CryptoStore(recordingFiles, keys.streamingAead) }
    private val databaseLock = Mutex()
    private var openDatabase: AppDatabase? = null
    private val recoveryLock = Mutex()
    private var recovered: RecoveryReport? = null
    val recorder by lazy { RecorderController(this, log) }
    val detection by lazy { CallDetection(this) }
    val health by lazy { HealthMonitor(this) { detection.state.value.ready } }
    val preferences by lazy { AppPreferences.open(this) }

    internal suspend fun requireRecovery() = recoveryLock.withLock { recovered = null }

    suspend fun database(): AppDatabase = databaseLock.withLock {
        openDatabase ?: AppDatabase.open(this, keys.aead).also { openDatabase = it }
    }

    suspend fun deletions(): RecordingDeletion = RecordingDeletion(this, database(), recordingFiles)

    suspend fun ensureRecovered(): RecoveryReport = withContext(Dispatchers.IO) {
        recoveryLock.withLock {
            recovered ?: Recovery(database(), recordingFiles, crypto, log).run().also {
                if (it.errors != 0) throw IOException("Recording recovery incomplete; original files retained")
                recovered = it
            }
        }
    }

    lateinit var log: AppLog
        private set

    override fun onCreate() {
        super.onCreate()
        log = AppLog(
            directory = File(filesDir, "logs"),
            redact = !BuildConfig.DEBUG,
            mirror = { Log.i("WACR", it) },
            reportFailure = { Log.e("WACR", "diagnostic_write_failed: ${it.javaClass.simpleName}") }
        )
        Thread.setDefaultUncaughtExceptionHandler(
            log.crashHandler(checkNotNull(Thread.getDefaultUncaughtExceptionHandler()))
        )
        log.log("app", "created")
        if (!RecoveryJobService.enqueue(this)) log.log("recovery", "schedule_failed")
        if (!DeletionJobService.enqueue(this)) log.log("deletion", "schedule_failed")
    }
}
