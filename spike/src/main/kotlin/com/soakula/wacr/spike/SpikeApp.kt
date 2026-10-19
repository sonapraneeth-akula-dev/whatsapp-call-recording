package com.soakula.wacr.spike

import android.app.Application
import com.soakula.wacr.spike.analysis.MatrixReport
import com.soakula.wacr.spike.capture.Notifier
import com.soakula.wacr.spike.capture.SweepController
import com.soakula.wacr.spike.log.SpikeLog
import com.soakula.wacr.spike.signals.AudioStateLogger

class SpikeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        SpikeLog.init(this)
        MatrixReport.reset()
        Notifier.init(this)
        SweepController.init(this)
        AudioStateLogger.start(this)
        SpikeLog.log("app", "created", mapOf("process" to getProcessName()))
    }
}
