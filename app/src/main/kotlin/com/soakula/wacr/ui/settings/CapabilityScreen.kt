package com.soakula.wacr.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.soakula.wacr.R
import com.soakula.wacr.capability.CapabilityPhase
import com.soakula.wacr.data.CapabilityResult
import com.soakula.wacr.data.CapabilityStatus
import com.soakula.wacr.recording.CapabilityRequest
import com.soakula.wacr.recording.CapabilityRunState
import com.soakula.wacr.recording.CaptureSource
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CapabilityScreen(
    state: CapabilityUiState, actions: CapabilityActions, run: CapabilityRunState?, captureBusy: Boolean,
    playableIds: Set<UUID>, onStart: (CapabilityRequest) -> Unit, onStop: () -> Unit,
    onReview: (CapabilityResult, Boolean, Boolean) -> Unit, onPreview: (UUID) -> Unit,
    onRetry: () -> Unit, onDismissError: () -> Unit, onSetup: () -> Unit, onBack: () -> Unit
) {
    var selectedPackage by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedSource by rememberSaveable { mutableStateOf(CaptureSource.VOICE_RECOGNITION) }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.capability_title)) }, navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_back), stringResource(R.string.capability_back))
            }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text(stringResource(R.string.capability_instructions)) }
            item { Text(stringResource(R.string.capability_privacy)) }
            item { OutlinedButton(onClick = onSetup, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.setup_title))
            } }
            actions.error?.let { error -> item {
                Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                    Text(stringResource(error), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onDismissError) { Text(stringResource(R.string.library_dismiss)) }
                }
            } }
            when (state) {
                CapabilityUiState.Loading -> item { CircularProgressIndicator() }
                CapabilityUiState.Failed -> item {
                    Text(stringResource(R.string.capability_load_failed), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.library_retry)) }
                }
                is CapabilityUiState.Ready -> {
                    val device = if (selectedPackage == null) state.devices.firstOrNull()
                        else state.devices.firstOrNull { it.waPackage == selectedPackage }
                    item {
                        if (device == null) Text(stringResource(R.string.capability_app_unavailable))
                        ChoiceMenu(stringResource(R.string.capability_app), device?.waPackage ?: "-",
                            state.devices.map { it.waPackage }, !captureBusy && !actions.busy) { selectedPackage = it }
                        ChoiceMenu(stringResource(R.string.capability_source), selectedSource.name,
                            CaptureSource.entries.map { it.name }, !captureBusy && !actions.busy) {
                            selectedSource = CaptureSource.valueOf(it)
                        }
                        Text(stringResource(R.string.capability_route, state.route?.name
                            ?: stringResource(R.string.capability_unknown_route)))
                        TextButton(onClick = onRetry, enabled = !captureBusy && !actions.busy) {
                            Text(stringResource(R.string.capability_refresh))
                        }
                        Button(onClick = {
                            if (device != null && state.route != null) {
                                onStart(CapabilityRequest(state.route, selectedSource, device.waPackage))
                            }
                        }, enabled = device != null && state.route != null && !captureBusy && !actions.busy,
                            modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.capability_start)) }
                    }
                    item {
                        when {
                            run != null && !run.complete -> {
                                val progress = run.progress
                                Text(stringResource(when (progress?.phase) {
                                    CapabilityPhase.YOU -> R.string.capability_you_cue
                                    CapabilityPhase.OTHER -> R.string.capability_other_cue
                                    CapabilityPhase.SAVING -> R.string.recording_saving_detail
                                    null -> R.string.recording_preparing
                                }), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                                LinearProgressIndicator(
                                    progress = { ((progress?.elapsedMs ?: 0) / 30_000f).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(stringResource(R.string.capability_elapsed, (progress?.elapsedMs ?: 0) / 1000))
                                OutlinedButton(onClick = onStop, enabled = progress?.phase != CapabilityPhase.SAVING) {
                                    Text(stringResource(R.string.recording_stop))
                                }
                            }
                            run?.failure != null -> Text(stringResource(run.failure.message),
                                color = MaterialTheme.colorScheme.error)
                            captureBusy -> Text(stringResource(R.string.capability_busy))
                            run?.complete == true -> Text(stringResource(R.string.capability_finished))
                        }
                    }
                    if (state.results.isEmpty()) item { Text(stringResource(R.string.capability_empty)) }
                    items(state.results, key = { it.id }) { row ->
                        CapabilityResultCard(row, state.devices.any { it == row.device },
                            row.recordingId?.let { it in playableIds } == true, captureBusy || actions.busy, onPreview, onReview)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChoiceMenu(label: String, value: String, values: List<String>, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text("$label: $value")
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            values.forEach { choice ->
                DropdownMenuItem(text = { Text(choice) }, onClick = { onSelect(choice); expanded = false })
            }
        }
    }
}

@Composable
private fun CapabilityResultCard(
    row: CapabilityResult, current: Boolean, playable: Boolean, busy: Boolean,
    onPreview: (UUID) -> Unit, onReview: (CapabilityResult, Boolean, Boolean) -> Unit
) {
    var own by rememberSaveable(row.id, row.ownOk) { mutableStateOf(row.ownOk) }
    var remote by rememberSaveable(row.id, row.remoteOk) { mutableStateOf(row.remoteOk) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${row.route.name} - ${row.source.name}", style = MaterialTheme.typography.titleMedium)
            Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(row.testedAt)))
            Text("${row.device.model} - ${row.device.osBuild}")
            Text("${row.device.waPackage} ${row.device.waVersion} (${row.device.waVersionCode})")
            Text(stringResource(when (row.status) {
                CapabilityStatus.COMPLETE -> R.string.capability_complete
                CapabilityStatus.UNAVAILABLE -> R.string.capability_unavailable
                CapabilityStatus.CANCELLED -> R.string.capability_cancelled
                CapabilityStatus.ROUTE_CHANGED -> R.string.capability_route_changed
                CapabilityStatus.LOW_STORAGE -> R.string.recording_low_storage_saved
                CapabilityStatus.TIMED_OUT -> R.string.capability_timed_out
            }))
            fun level(value: Double?) = value?.let { String.format(Locale.getDefault(), "%.1f dBFS", it) } ?: "-"
            Text(stringResource(R.string.capability_levels, level(row.ownRmsDb), level(row.remoteRmsDb)))
            if (row.clientSilenced == true) Text(stringResource(R.string.capability_silenced))
            if (!current) Text(stringResource(R.string.capability_old_build))
            OutlinedButton(onClick = { row.recordingId?.let(onPreview) },
                enabled = playable && !busy) { Text(stringResource(R.string.capability_listen)) }
            Text(stringResource(R.string.capability_verdict, verdict(row.ownOk), verdict(row.remoteOk)))
            if (row.status == CapabilityStatus.COMPLETE && current && playable) {
                VerdictButtons(stringResource(R.string.capability_you), own, !busy) { own = it }
                VerdictButtons(stringResource(R.string.capability_other), remote, !busy) { remote = it }
                Button(onClick = {
                    val you = own
                    val other = remote
                    if (you != null && other != null) onReview(row, you, other)
                }, enabled = own != null && remote != null && !busy) {
                    Text(stringResource(R.string.capability_save_verdict))
                }
            }
        }
    }
}

@Composable
private fun verdict(value: Boolean?): String = stringResource(when (value) {
    true -> R.string.capability_heard
    false -> R.string.capability_not_heard
    null -> R.string.capability_unreviewed
})

@Composable
private fun VerdictButtons(label: String, selected: Boolean?, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Column(Modifier.selectableGroup()) {
        listOf(true, false).forEach { choice ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                selected = selected == choice, enabled = enabled, role = Role.RadioButton, onClick = { onChange(choice) }
            ), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RadioButton(selected == choice, onClick = null, enabled = enabled)
                Text(stringResource(R.string.capability_choice, label, verdict(choice)), Modifier.weight(1f))
            }
        }
    }
}
