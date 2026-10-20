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

class DeletionJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as WacrApp
        running = scope.launch {
            var retry = false
            try {
                val deletions = app.deletions()
                while (true) {
                    val next = deletions.purgeDue() ?: break
                    delay(next.coerceIn(1, 5000))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                app.log.log("deletion", "job_io_error", mapOf("error_type" to error.javaClass.simpleName))
                retry = true
            } catch (error: SQLiteException) {
                app.log.log("deletion", "job_database_error", mapOf("error_type" to error.javaClass.simpleName))
                retry = true
            } catch (error: GeneralSecurityException) {
                app.log.log("deletion", "job_key_error", mapOf("error_type" to error.javaClass.simpleName))
            } catch (error: IllegalStateException) {
                app.log.log("deletion", "job_state_error", mapOf("error_type" to error.javaClass.simpleName))
            } catch (error: IllegalArgumentException) {
                app.log.log("deletion", "job_path_error", mapOf("error_type" to error.javaClass.simpleName))
            } catch (error: UnsatisfiedLinkError) {
                app.log.log("deletion", "job_native_error", mapOf("error_type" to error.javaClass.simpleName))
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
        internal const val JOB_ID = 1002

        fun enqueue(context: Context, delayMs: Long = 0): Boolean {
            require(delayMs >= 0)
            val scheduler = checkNotNull(context.getSystemService(JobScheduler::class.java))
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, DeletionJobService::class.java))
                .setMinimumLatency(delayMs)
                .setPersisted(true)
                .setBackoffCriteria(10_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build()
            return scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
        }
    }
}
