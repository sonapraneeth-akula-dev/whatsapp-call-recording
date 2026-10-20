package com.soakula.wacr

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.net.Uri
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withResumed
import com.soakula.wacr.health.HealthIssue
import com.soakula.wacr.health.HealthProblem
import com.soakula.wacr.health.HealthNotification
import com.soakula.wacr.recording.RecorderState
import com.soakula.wacr.recording.RecordingEntry
import com.soakula.wacr.recording.CallRecordingResult
import com.soakula.wacr.data.RecordingDecision
import com.soakula.wacr.ui.AppScaffold
import com.soakula.wacr.ui.allowlist.AllowlistScreen
import com.soakula.wacr.ui.allowlist.AllowlistViewModel
import com.soakula.wacr.ui.home.HomeViewModel
import com.soakula.wacr.ui.library.LibraryViewModel
import com.soakula.wacr.ui.library.RecordingActions
import com.soakula.wacr.ui.player.PlayerRoute
import com.soakula.wacr.ui.settings.SettingsScreen
import com.soakula.wacr.ui.settings.SettingsUiState
import com.soakula.wacr.ui.settings.SettingsViewModel
import com.soakula.wacr.ui.settings.DiagnosticsViewModel
import com.soakula.wacr.ui.settings.DiagnosticsScreen
import com.soakula.wacr.ui.theme.WacrTheme
import com.soakula.wacr.ui.theme.isDark
import com.soakula.wacr.ui.onboarding.SetupChecklistScreen
import com.soakula.wacr.data.Appearance
import com.soakula.wacr.playback.createRecordingPlayer
import androidx.media3.common.util.UnstableApi
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@androidx.annotation.OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {
    private val app: WacrApp get() = checkNotNull(application as? WacrApp)
    private val home: HomeViewModel by viewModels()
    private val library: LibraryViewModel by viewModels()
    private val allowlist: AllowlistViewModel by viewModels()
    private val settings: SettingsViewModel by viewModels()
    private val diagnostics: DiagnosticsViewModel by viewModels()
    private val diagnosticDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) diagnostics.cancelSelection() else diagnostics.export(uri)
    }
    private val contactPicker = registerForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        if (uri != null) allowlist.addPickedContact(uri)
    }
    private var recordAfterPermission = false
    private var startWhenResumed = false
    private var manualConfirmation: UUID? = null
    private var stopConfirmation: UUID? = null
    private var entryJob: Job? = null
    private var startJob: Job? = null
    private var requestedSession: Long? = null
    private var requestedDecision = RecordingDecision.MANUAL
    private var setupRequest by mutableStateOf(0)
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
        if (savedInstanceState == null) {
            consumeSetupEntry(intent)
            consumeRecordingEntry(intent)
        }
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
            val appearanceState by settings.state.collectAsStateWithLifecycle()
            val appearanceActions by settings.actions.collectAsStateWithLifecycle()
            val appearance = (appearanceState as? SettingsUiState.Ready)?.appearance ?: Appearance()
            val firstRun = (appearanceState as? SettingsUiState.Ready)?.onboardingDone == false
            val dark = appearance.theme.isDark(isSystemInDarkTheme())
            LaunchedEffect(dark) {
                val bars = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            WacrTheme(appearance) {
                val state by home.state.collectAsStateWithLifecycle()
                val recorder by app.recorder.state.collectAsStateWithLifecycle()
                val recordings by library.state.collectAsStateWithLifecycle()
                val actions by library.actions.collectAsStateWithLifecycle()
                val contactRules by allowlist.state.collectAsStateWithLifecycle()
                val contactActions by allowlist.actions.collectAsStateWithLifecycle()
                val exportState by diagnostics.state.collectAsStateWithLifecycle()
                AppScaffold(state, recorder, recordings, ::requestRecording, ::stopRecording, ::openFix, library::retry,
                    actions = actions, onUndo = library::undo, onDismissUndo = library::dismissUndo,
                    onDismissError = library::dismissError,
                    allowlistContent = {
                        AllowlistScreen(contactRules, contactActions, ::pickContact, allowlist::retry,
                            allowlist::remove, allowlist::undo, allowlist::dismissUndo, allowlist::dismissError)
                    }, settingsContent = { openSetup ->
                        var showDiagnostics by rememberSaveable { mutableStateOf(false) }
                        if (showDiagnostics) {
                            BackHandler { showDiagnostics = false }
                            DiagnosticsScreen(exportState, ::chooseDiagnosticDocument) { showDiagnostics = false }
                        } else {
                            SettingsScreen(appearanceState, appearanceActions, settings::setTheme, settings::setDynamicColor,
                                settings::retry, settings::dismissError, onSetup = openSetup,
                                onDiagnostics = { showDiagnostics = true })
                        }
                    }, setupContent = { closeSetup ->
                        SetupChecklistScreen(state, appearanceActions.busy, appearanceActions.failed,
                            home::refresh, ::openFix, ::grantSetupPermission,
                            { openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", packageName, null))) },
                            { openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
                            { if (firstRun) settings.completeOnboarding() else closeSetup() })
                    }, setupRequest = setupRequest, forceSetup = firstRun, startupLoading = appearanceState is SettingsUiState.Loading,
                    appearanceError = appearanceState is SettingsUiState.Failed,
                    onRetryAppearance = settings::retry) { entry, back ->
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
                        loadWaveform = {
                            withContext(Dispatchers.IO) { app.database().recordings().waveform(entry.recording.id)?.decode() }
                        },
                        reportWaveformFailure = { error ->
                            app.log.log("playback", "waveform_load_failed", mapOf("error_type" to error.javaClass.simpleName))
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
        allowlist.expireUndo()
        if (startWhenResumed) startRecording()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeSetupEntry(intent)
        consumeRecordingEntry(intent)
    }

    private fun consumeSetupEntry(intent: Intent) {
        if (intent.action == HealthNotification.ACTION_SETUP) {
            intent.action = null
            setupRequest++
        }
    }

    private fun consumeRecordingEntry(intent: Intent) {
        val sessionId = RecordingEntry.consumeCall(this, intent)
        if (sessionId == null && !RecordingEntry.consume(this, intent)) return
        entryJob?.cancel()
        entryJob = lifecycleScope.launch {
            lifecycle.withResumed {
                if (sessionId == null) requestRecording() else {
                    requestedSession = sessionId
                    requestedDecision = RecordingDecision.ASKED
                    checkRecordingPermissions()
                }
            }
        }
    }

    override fun onStop() {
        entryJob?.cancel()
        entryJob = null
        startJob?.cancel()
        startJob = null
        requestedSession = null
        requestedDecision = RecordingDecision.MANUAL
        recordAfterPermission = false
        startWhenResumed = false
        manualConfirmation = null
        stopConfirmation = null
        super.onStop()
    }

    private fun missingPermissions() = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    private fun requestRecording() {
        val detected = app.detection.state.value
        requestedSession = detected.sessionId.takeIf { detected.ready && detected.suspension == null }
        requestedDecision = RecordingDecision.MANUAL
        checkRecordingPermissions()
    }

    private fun checkRecordingPermissions() {
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
        val sessionId = requestedSession
        if (sessionId == null) {
            manualConfirmation = app.recorder.start()
            return
        }
        val decision = requestedDecision
        startJob?.cancel()
        startJob = lifecycleScope.launch {
            when (val result = app.detection.recordCall(sessionId, decision) {
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            }) {
                is CallRecordingResult.Started -> {
                    manualConfirmation = result.id
                    val recording = app.recorder.state.value
                    if (recording is RecorderState.Recording && recording.id == result.id) {
                        window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        manualConfirmation = null
                    }
                }
                CallRecordingResult.Expired -> Toast.makeText(this@MainActivity, R.string.recording_call_expired, Toast.LENGTH_LONG).show()
                CallRecordingResult.Busy -> Toast.makeText(this@MainActivity, R.string.recording_busy, Toast.LENGTH_LONG).show()
                CallRecordingResult.NotVisible -> Unit
                CallRecordingResult.Failed -> Toast.makeText(this@MainActivity, R.string.recording_call_start_failed, Toast.LENGTH_LONG).show()
            }
        }
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
        openSettings(intent)
    }

    private fun grantSetupPermission(issue: HealthIssue) {
        val permission = when (issue.problem) {
            HealthProblem.MICROPHONE_PERMISSION -> Manifest.permission.RECORD_AUDIO
            HealthProblem.NOTIFICATIONS_DISABLED -> Manifest.permission.POST_NOTIFICATIONS
            else -> return
        }
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            openFix(issue)
        } else {
            recordAfterPermission = false
            permissions.launch(arrayOf(permission))
        }
    }

    private fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            settingsFailed(error)
        } catch (error: SecurityException) {
            settingsFailed(error)
        }
    }

    private fun chooseDiagnosticDocument() {
        if (!diagnostics.chooseDestination()) return
        try {
            diagnosticDocument.launch("wacr-diagnostics.zip")
        } catch (error: ActivityNotFoundException) {
            diagnostics.pickerFailed(error)
        } catch (error: SecurityException) {
            diagnostics.pickerFailed(error)
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
