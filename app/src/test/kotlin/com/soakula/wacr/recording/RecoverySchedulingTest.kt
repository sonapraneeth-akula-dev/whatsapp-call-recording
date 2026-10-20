package com.soakula.wacr.recording

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Intent
import com.soakula.wacr.health.BootReceiver
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, manifest = Config.NONE)
class RecoverySchedulingTest {
    @Test fun bootAndUpgradeQueueOneOfflineJobWithoutStartingMicrophoneServices() {
        val application = RuntimeEnvironment.getApplication()
        val scheduler = checkNotNull(application.getSystemService(JobScheduler::class.java))
        scheduler.cancelAll()
        val receiver = BootReceiver()
        receiver.onReceive(application, Intent("unrelated"))
        assertTrue(scheduler.allPendingJobs.isEmpty())
        receiver.onReceive(application, Intent(Intent.ACTION_BOOT_COMPLETED))
        receiver.onReceive(application, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertTrue(RecoveryJobService.enqueue(application))
        val job = scheduler.allPendingJobs.single()
        assertEquals(RecoveryJobService.JOB_ID, job.id)
        assertEquals(RecoveryJobService::class.java.name, job.service.className)
        assertNull(job.requiredNetwork)
        assertEquals(JobInfo.BACKOFF_POLICY_EXPONENTIAL, job.backoffPolicy)
        assertEquals(60_000L, job.initialBackoffMillis)
    }
}
