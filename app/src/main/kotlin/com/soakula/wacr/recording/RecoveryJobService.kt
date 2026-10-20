package com.soakula.wacr.recording

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.database.sqlite.SQLiteException
import com.soakula.wacr.WacrApp
import kotlinx.coroutines.*
import java.io.IOException
import java.security.GeneralSecurityException

class RecoveryJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as WacrApp
        running = scope.launch {
            var retry = false
            try {
                app.log.log("health", "checked", mapOf("issues" to app.health.check().map { it.problem.name }))
                app.ensureRecovered()
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                app.log.log("recovery", "job_io_error", mapOf("error_type" to error.javaClass.simpleName))
                retry = true
            } catch (error: GeneralSecurityException) {
                app.log.log("recovery", "job_key_error", mapOf("error_type" to error.javaClass.simpleName))
            } catch (error: SQLiteException) {
                app.log.log("recovery", "job_database_error", mapOf("error_type" to error.javaClass.simpleName))
                retry = true
            } catch (error: IllegalStateException) {
                app.log.log("recovery", "job_state_error", mapOf("error_type" to error.javaClass.simpleName))
            } catch (error: UnsatisfiedLinkError) {
                app.log.log("recovery", "job_native_error", mapOf("error_type" to error.javaClass.simpleName))
            }
            jobFinished(params, retry)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        internal const val JOB_ID = 1001

        fun enqueue(context: Context): Boolean {
            val scheduler = checkNotNull(context.getSystemService(JobScheduler::class.java))
            if (scheduler.getPendingJob(JOB_ID) != null) return true
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, RecoveryJobService::class.java))
                .setMinimumLatency(0)
                .setBackoffCriteria(60_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build()
            return scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
        }
    }
}
