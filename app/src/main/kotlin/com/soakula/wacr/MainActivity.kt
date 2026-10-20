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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.soakula.wacr.health.HealthIssue
import com.soakula.wacr.recording.RecorderState
import com.soakula.wacr.ui.home.HomeScreen
import com.soakula.wacr.ui.home.HomeViewModel
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val app: WacrApp get() = checkNotNull(application as? WacrApp)
    private val home: HomeViewModel by viewModels()
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
                val state by home.state.collectAsState()
                val recorder by app.recorder.state.collectAsState()
                HomeScreen(state, recorder, ::requestRecording, ::stopRecording, ::openFix)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        home.refresh()
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

    private fun settingsFailed(error: Exception) {
        app.log.log("setup", "settings_open_failed", mapOf("error_type" to error.javaClass.simpleName))
        Toast.makeText(this, R.string.home_settings_error, Toast.LENGTH_LONG).show()
    }
}
