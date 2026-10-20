package com.soakula.wacr

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.soakula.wacr.health.HealthIssue
import com.soakula.wacr.recording.RecorderState
import com.soakula.wacr.ui.AppScaffold
import com.soakula.wacr.ui.allowlist.AllowlistScreen
import com.soakula.wacr.ui.allowlist.AllowlistViewModel
import com.soakula.wacr.ui.home.HomeViewModel
import com.soakula.wacr.ui.library.LibraryViewModel
import com.soakula.wacr.ui.library.RecordingActions
import com.soakula.wacr.ui.player.PlayerRoute
import com.soakula.wacr.playback.createRecordingPlayer
import androidx.media3.common.util.UnstableApi
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@androidx.annotation.OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {
    private val app: WacrApp get() = checkNotNull(application as? WacrApp)
    private val home: HomeViewModel by viewModels()
    private val library: LibraryViewModel by viewModels()
    private val allowlist: AllowlistViewModel by viewModels()
    private val contactPicker = registerForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        if (uri != null) allowlist.addPickedContact(uri)
    }
    private var recordAfterPermission = false
    private var startWhenResumed = false
    private var manualConfirmation: UUID? = null
    private var stopConfirmation: UUID? = null
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (recordAfterPermission) {
            recordAfterPermission = false
            if (missingPermissions().isEmpty()) {
                startWhenResumed = true
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) startRecording()
            } else {
                Toast.makeText(this, R.string.home_permissions_denied, Toast.LENGTH_LONG).show()
            }
        }
        home.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                app.recorder.state.collect { state ->
                    if (state is RecorderState.Recording && state.id == manualConfirmation) {
                        window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        manualConfirmation = null
                    } else if (state is RecorderState.Finished) {
                        if (state.id == stopConfirmation) {
                            window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        }
                        manualConfirmation = null
                        stopConfirmation = null
                    } else if (state is RecorderState.Failed || state is RecorderState.Idle) {
                        manualConfirmation = null
                        stopConfirmation = null
                    }
                }
            }
        }
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(this)
                else dynamicLightColorScheme(this)
            ) {
                val state by home.state.collectAsStateWithLifecycle()
                val recorder by app.recorder.state.collectAsStateWithLifecycle()
                val recordings by library.state.collectAsStateWithLifecycle()
                val actions by library.actions.collectAsStateWithLifecycle()
                val contactRules by allowlist.state.collectAsStateWithLifecycle()
                val contactActions by allowlist.actions.collectAsStateWithLifecycle()
                AppScaffold(state, recorder, recordings, ::requestRecording, ::stopRecording, ::openFix, library::retry,
                    actions = actions, onUndo = library::undo, onDismissUndo = library::dismissUndo,
                    onDismissError = library::dismissError,
                    allowlistContent = {
                        AllowlistScreen(contactRules, contactActions, ::pickContact, allowlist::retry,
                            allowlist::remove, allowlist::undo, allowlist::dismissUndo, allowlist::dismissError)
                    }) { entry, back ->
                    PlayerRoute(entry,
                        blocked = recorder is RecorderState.Preparing || recorder is RecorderState.Recording || recorder is RecorderState.Saving,
                        onBack = back,
                        createPlayer = {
                            val crypto = withContext(Dispatchers.IO) { app.crypto }
                            createRecordingPlayer(this, crypto, app.log)
                        },
                        reportFailure = { error ->
                            app.log.log("playback", "open_failed", mapOf("error_type" to error.javaClass.simpleName))
                        },
                        actions = { pause ->
                            RecordingActions(entry, actions, { title -> library.rename(entry.recording.id, title) }) {
                                pause()
                                library.delete(entry.recording.id)
                            }
                        })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        home.refresh()
        library.restoreUndo()
        if (startWhenResumed) startRecording()
    }

    override fun onStop() {
        recordAfterPermission = false
        startWhenResumed = false
        manualConfirmation = null
        stopConfirmation = null
        super.onStop()
    }

    private fun missingPermissions() = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    private fun requestRecording() {
        val missing = missingPermissions()
        if (missing.isEmpty()) {
            startWhenResumed = true
            startRecording()
        } else {
            recordAfterPermission = true
            permissions.launch(missing.toTypedArray())
        }
    }

    private fun startRecording() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        startWhenResumed = false
        manualConfirmation = app.recorder.start()
    }

    private fun stopRecording() {
        stopConfirmation = when (val state = app.recorder.state.value) {
            is RecorderState.Preparing -> state.id
            is RecorderState.Recording -> state.id
            else -> null
        }
        app.recorder.stop()
    }

    private fun openFix(issue: HealthIssue) {
        val intent = issue.fixIntent ?: return
        try {
            startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            settingsFailed(error)
        } catch (error: SecurityException) {
            settingsFailed(error)
        }
    }

    private fun pickContact() {
        try {
            contactPicker.launch(null)
        } catch (error: ActivityNotFoundException) {
            allowlist.pickerFailed(error)
        } catch (error: SecurityException) {
            allowlist.pickerFailed(error)
        }
    }

    private fun settingsFailed(error: Exception) {
        app.log.log("setup", "settings_open_failed", mapOf("error_type" to error.javaClass.simpleName))
        Toast.makeText(this, R.string.home_settings_error, Toast.LENGTH_LONG).show()
    }
}
