package com.soakula.wacr.health

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.soakula.wacr.WacrApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

class HealthJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val running = mutableMapOf<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var retry = false
            try {
                (application as WacrApp).refreshHealth()
            } catch (error: CancellationException) {
                throw error
            } catch (error: SecurityException) {
                failed(error)
                retry = true
            } catch (error: IllegalStateException) {
                failed(error)
                retry = true
            } catch (error: IllegalArgumentException) {
                failed(error)
                retry = true
            } catch (error: PackageManager.NameNotFoundException) {
                failed(error)
                retry = true
            } finally {
                if (running[params.jobId] === currentCoroutineContext()[Job]) running.remove(params.jobId)
            }
            jobFinished(params, retry)
        }
        running[params.jobId] = job
        job.start()
        return true
    }

    private fun failed(error: Exception) {
        (application as WacrApp).log.log("health", "check_failed", mapOf("error_type" to error.javaClass.simpleName))
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        internal const val PERIODIC_ID = 1003
        internal const val CHECK_ID = 1004
        internal const val INTERVAL_MS = 15 * 60_000L

        fun schedule(context: Context): Boolean {
            val scheduler = checkNotNull(context.getSystemService(JobScheduler::class.java))
            if (scheduler.getPendingJob(PERIODIC_ID) == null && scheduler.schedule(
                JobInfo.Builder(PERIODIC_ID, ComponentName(context, HealthJobService::class.java))
                    .setPeriodic(INTERVAL_MS).setPersisted(true).build()
            ) != JobScheduler.RESULT_SUCCESS) return false
            return enqueue(context)
        }

        fun enqueue(context: Context): Boolean {
            val scheduler = checkNotNull(context.getSystemService(JobScheduler::class.java))
            return scheduler.schedule(
                JobInfo.Builder(CHECK_ID, ComponentName(context, HealthJobService::class.java))
                    .setMinimumLatency(0).setOverrideDeadline(0)
                    .setBackoffCriteria(60_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build()
            ) == JobScheduler.RESULT_SUCCESS
        }
    }
}
