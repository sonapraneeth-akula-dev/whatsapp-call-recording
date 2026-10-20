package com.soakula.wacr.ui.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.soakula.wacr.R
import com.soakula.wacr.health.HealthIssue
import com.soakula.wacr.health.HealthProblem
import com.soakula.wacr.ui.home.HomeUiState
import com.soakula.wacr.ui.home.healthDetail

internal fun setupStatus(state: HomeUiState, problem: HealthProblem): Int = when {
    !state.loaded || state.error != null -> R.string.setup_unverified
    state.issues.any { it.problem == problem } -> R.string.setup_action_required
    (problem == HealthProblem.ACCESSIBILITY_DISABLED || problem == HealthProblem.NOTIFICATION_ACCESS_DISABLED) &&
        state.issues.any { it.problem == HealthProblem.DETECTION_UNAVAILABLE } -> R.string.setup_unverified
    else -> R.string.setup_checked
}

internal fun setupTitle(problem: HealthProblem): Int = when (problem) {
    HealthProblem.MICROPHONE_PERMISSION -> R.string.setup_microphone
    HealthProblem.NOTIFICATIONS_DISABLED -> R.string.setup_notifications
    HealthProblem.RECORDING_CHANNEL_DISABLED -> R.string.setup_channel
    HealthProblem.REQUEST_CHANNEL_DISABLED -> R.string.setup_request_channel
    HealthProblem.DETECTION_UNAVAILABLE -> R.string.setup_detector
    HealthProblem.ACCESSIBILITY_DISABLED -> R.string.setup_accessibility
    HealthProblem.NOTIFICATION_ACCESS_DISABLED -> R.string.setup_listener
    HealthProblem.BATTERY_OPTIMIZED -> R.string.setup_battery
    HealthProblem.BACKGROUND_RESTRICTED -> R.string.setup_background
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SetupChecklistScreen(
    state: HomeUiState,
    saving: Boolean,
    saveFailed: Boolean,
    onRefresh: () -> Unit,
    onFix: (HealthIssue) -> Unit,
    onGrant: (HealthIssue) -> Unit,
    onAppSettings: () -> Unit,
    onBatterySettings: () -> Unit,
    onContinue: () -> Unit
) {
    LaunchedEffect(Unit) { onRefresh() }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.setup_title)) }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(stringResource(R.string.setup_intro))
                Button(onClick = onContinue, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.setup_continue))
                }
                if (saveFailed) Text(stringResource(R.string.settings_save_error), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRefresh) { Text(stringResource(R.string.setup_recheck)) }
            }
            if (!state.loaded) item { CircularProgressIndicator() }
            state.error?.let { error -> item { Text(stringResource(error), color = MaterialTheme.colorScheme.error) } }
            HealthProblem.entries.forEach { problem ->
                val issue = state.issues.firstOrNull { it.problem == problem }
                item(key = problem) {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(setupTitle(problem)), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(if (problem == HealthProblem.DETECTION_UNAVAILABLE &&
                                setupStatus(state, problem) == R.string.setup_checked) R.string.setup_detector_checked_detail
                                else healthDetail(problem)))
                            Text(stringResource(setupStatus(state, problem)),
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                            if (issue != null) {
                                if (problem == HealthProblem.MICROPHONE_PERMISSION || problem == HealthProblem.NOTIFICATIONS_DISABLED) {
                                    TextButton(onClick = { onGrant(issue) }) {
                                        Text(stringResource(R.string.setup_grant))
                                    }

                                }
                                if (issue.fixIntent != null) {
                                    TextButton(onClick = { onFix(issue) }) {
                                        Text(stringResource(R.string.home_review_settings))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.setup_sideload_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.setup_sideload_detail))
                        TextButton(onClick = onAppSettings) { Text(stringResource(R.string.setup_app_info)) }
                    }
                }
            }
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.setup_samsung_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.setup_samsung_detail))
                        TextButton(onClick = onBatterySettings) { Text(stringResource(R.string.setup_battery_list)) }
                    }
                }
            }
        }
    }
}
