package com.soakula.wacr.ui.library

import androidx.lifecycle.ViewModelStore
import com.soakula.wacr.WacrApp
import com.soakula.wacr.data.RecordingEntity
import com.soakula.wacr.data.RecordingWithSession
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.experimental.LazyApplication
import org.robolectric.shadows.ShadowLooper
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = WacrApp::class)
@LazyApplication(LazyApplication.LazyLoad.ON)
class LibraryViewModelTest {
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private val store = ViewModelStore()

    @Before fun prepareApplication() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler ?: Thread.UncaughtExceptionHandler { _, error ->
            throw AssertionError("Unexpected uncaught test exception", error)
        })
    }

    @After fun cleanup() {
        store.clear()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    @Test fun libraryRetainsReactiveMetadataAndDistinguishesEmptySuccess() = runBlocking {
        val source = MutableStateFlow<List<RecordingWithSession>>(emptyList())
        val app = checkNotNull(RuntimeEnvironment.getApplication() as? WacrApp)
        val model = LibraryViewModel(app) { source }.also { store.put("library", it) }
        val observer = launch { model.state.collect {} }
        try {
            await { model.state.value is LibraryUiState.Ready }
            assertEquals(LibraryUiState.Ready(emptyList()), model.state.value)
            val entry = RecordingWithSession(RecordingEntity(UUID.randomUUID(), 1000, 1, "MIC"), null)
            source.value = listOf(entry)
            await { model.state.value == LibraryUiState.Ready(listOf(entry)) }
            source.value = emptyList()
            await { model.state.value == LibraryUiState.Ready(emptyList()) }
        } finally {
            observer.cancelAndJoin()
        }
    }

    @Test fun failedLoadingIsExplicitAndRetryResubscribesInsteadOfInventingEmptyLibrary() = runBlocking {
        val app = checkNotNull(RuntimeEnvironment.getApplication() as? WacrApp)
        var attempts = 0
        val model = LibraryViewModel(app) {
            flow {
                attempts++
                if (attempts == 1) throw IOException("Synthetic storage failure")
                emit(emptyList())
            }
        }.also { store.put("library", it) }
        val observer = launch { model.state.collect {} }
        try {
            await { model.state.value == LibraryUiState.Failed }
            assertEquals(1, attempts)
            model.retry()
            await { model.state.value is LibraryUiState.Ready }
            assertEquals(2, attempts)
        } finally {
            observer.cancelAndJoin()
        }
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(10_000) {
        while (!condition()) {
            ShadowLooper.idleMainLooper()
            kotlinx.coroutines.delay(10)
        }
    }
}
