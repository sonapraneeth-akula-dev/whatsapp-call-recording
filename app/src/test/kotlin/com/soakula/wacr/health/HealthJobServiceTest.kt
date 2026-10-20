package com.soakula.wacr.health

import android.Manifest
import android.app.NotificationManager
import android.app.job.JobParameters
import android.content.ComponentName
import com.soakula.wacr.WacrApp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.experimental.LazyApplication
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowLooper
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = WacrApp::class)
@LazyApplication(LazyApplication.LazyLoad.ON)
class HealthJobServiceTest {
    private val app get() = checkNotNull(RuntimeEnvironment.getApplication() as? WacrApp)
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Before fun prepare() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler ?: Thread.UncaughtExceptionHandler { _, error -> throw AssertionError(error) })
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After fun close() = runBlocking {
        try { app.detection.shutdown() } finally { Thread.setDefaultUncaughtExceptionHandler(previousHandler) }
    }

    @Test fun stoppedJobCancelsAWaitingHealthCheckWithoutPublishingOrReportingCompletion() = runBlocking {
        val lock = ReflectionHelpers.getField<Mutex>(app, "healthLock")
        val controller = Robolectric.buildService(HealthJobService::class.java).create()
        val params = Shadow.newInstanceOf(JobParameters::class.java)
        ReflectionHelpers.setField(params, "jobId", HealthJobService.CHECK_ID)
        lock.lock()
        try {
            assertTrue(controller.get().onStartJob(params))
            val job = ReflectionHelpers.getField<Map<Int, Job>>(controller.get(), "running").values.single()
            assertTrue(controller.get().onStopJob(params))
            withTimeout(10_000) {
                while (!job.isCompleted) {
                    ShadowLooper.idleMainLooper()
                    delay(10)
                }
            }
            assertTrue(job.isCancelled)
            assertFalse(shadowOf(controller.get()).isJobFinished)
            assertNull(shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(HealthNotification.ID))
        } finally {
            lock.unlock()
            controller.destroy()
        }
    }

    @Test fun protectedJobPublishesRealSetupIssuesWithoutMicrophoneAndSupportsCancellation() = runBlocking {
        val params = Shadow.newInstanceOf(JobParameters::class.java)
        ReflectionHelpers.setField(params, "jobId", HealthJobService.CHECK_ID)
        val controller = Robolectric.buildService(HealthJobService::class.java).create()
        try {
            assertTrue(controller.get().onStartJob(params))
            withTimeout(10_000) {
                while (!shadowOf(controller.get()).isJobFinished) {
                    ShadowLooper.idleMainLooper()
                    delay(10)
                }
            }
            assertFalse(shadowOf(controller.get()).isRescheduleNeeded)
            assertNotNull(shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(HealthNotification.ID))
            assertNull(shadowOf(app).nextStartedService)
            assertTrue(ReflectionHelpers.getField<Map<Int, Job>>(controller.get(), "running").isEmpty())
            assertTrue(controller.get().onStopJob(params))
            @Suppress("DEPRECATION")
            val info = app.packageManager.getServiceInfo(ComponentName(app, HealthJobService::class.java), 0)
            assertEquals("android.permission.BIND_JOB_SERVICE", info.permission)
        } finally {
            controller.destroy()
        }
    }
}
