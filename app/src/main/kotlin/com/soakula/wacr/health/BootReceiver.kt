package com.soakula.wacr.health

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.soakula.wacr.WacrApp
import com.soakula.wacr.recording.RecoveryJobService
import com.soakula.wacr.recording.DeletionJobService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            if (!RecoveryJobService.enqueue(context)) {
                (context.applicationContext as WacrApp).log.log("recovery", "schedule_failed")
            }
            if (!DeletionJobService.enqueue(context)) {
                (context.applicationContext as WacrApp).log.log("deletion", "schedule_failed")
            }
            if (!HealthJobService.schedule(context)) {
                (context.applicationContext as WacrApp).log.log("health", "schedule_failed")
            }
        }
    }
}
