package com.soakula.wacr.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModelStore
import com.soakula.wacr.WacrApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.experimental.LazyApplication
import org.robolectric.shadows.ShadowLooper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = WacrApp::class)
@LazyApplication(LazyApplication.LazyLoad.ON)
class DiagnosticsViewModelTest {
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private lateinit var app: WacrApp
    private lateinit var model: DiagnosticsViewModel
    private val store = ViewModelStore()
    private val selected = Uri.parse("content://synthetic.documents/document/selected")

    @Before fun prepare() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler ?: Thread.UncaughtExceptionHandler { _, error ->
            throw AssertionError("Unexpected uncaught test exception", error)
        })
        app = checkNotNull(RuntimeEnvironment.getApplication() as? WacrApp)
        model = DiagnosticsViewModel(app)
        store.put("diagnostics", model)
    }

    @After fun cleanup() {
        store.clear()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    @Test fun cancellationDoesNotClaimAnExportAndOnlyOnePickerIsAllowed() {
        assertTrue(model.chooseDestination())
        assertFalse(model.chooseDestination())
        assertEquals(DiagnosticExportState.CHOOSING, model.state.value)
        model.cancelSelection()
        assertEquals(DiagnosticExportState.IDLE, model.state.value)
    }

    @Test fun selectedDestinationReceivesClosedRedactedArchiveWithoutClearingSourceLog() = runBlocking {
        app.log.log("test", "private_event", mapOf("caller" to "SyntheticPrivateName", "text" to "SyntheticPrivateNotification"))
        val output = object : ByteArrayOutputStream() {
            var closed = false
            override fun close() { closed = true; super.close() }
        }
        shadowOf(app.contentResolver).registerOutputStream(selected, output)
        assertTrue(model.chooseDestination())
        model.export(selected)
        await(DiagnosticExportState.SAVED)
        assertTrue(output.closed)
        val archive = ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            buildMap {
                while (true) {
                    val entry = zip.nextEntry ?: break
                    put(entry.name, zip.readBytes().toString(Charsets.UTF_8))
                }
            }
        }
        assertEquals(setOf("device.json", "app.jsonl"), archive.keys)
        assertFalse(archive.values.any { it.contains("SyntheticPrivate") })
        assertTrue(File(File(app.filesDir, "logs"), "app.log").readText().contains("SyntheticPrivateName"))
        assertTrue(model.chooseDestination())
        model.cancelSelection()
        assertEquals(DiagnosticExportState.IDLE, model.state.value)
    }

    @Test fun destinationFailureClosesStreamAndInvalidFileUriNeverBecomesSuccess() = runBlocking {
        var closed = false
        val output = object : OutputStream() {
            override fun write(value: Int) { throw IOException("Synthetic document failure") }
            override fun close() { closed = true }
        }
        shadowOf(app.contentResolver).registerOutputStream(selected, output)
        model.export(selected)
        await(DiagnosticExportState.FAILED)
        assertTrue(closed)
        assertTrue(model.chooseDestination())
        model.export(Uri.parse("file:///unexpected.zip"))
        await(DiagnosticExportState.FAILED)
    }

    private suspend fun await(state: DiagnosticExportState) = withTimeout(10_000) {
        while (model.state.value != state) {
            ShadowLooper.idleMainLooper()
            delay(10)
        }
    }
}
