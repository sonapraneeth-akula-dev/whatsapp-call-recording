package com.soakula.wacr

import android.app.Application
import android.util.Log
import com.soakula.wacr.crypto.KeyManager
import com.soakula.wacr.crypto.CryptoStore
import com.soakula.wacr.data.RecordingFiles
import com.soakula.wacr.util.AppLog
import java.io.File

class WacrApp : Application() {
    val recordingFiles by lazy { RecordingFiles(filesDir) }
    val keys by lazy { KeyManager.open(this) }
    val crypto by lazy { CryptoStore(recordingFiles, keys.streamingAead) }

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
    }
}
