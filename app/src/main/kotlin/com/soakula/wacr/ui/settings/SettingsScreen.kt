package com.soakula.wacr.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.soakula.wacr.R
import com.soakula.wacr.data.ThemeChoice

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SettingsScreen(
    state: SettingsUiState,
    actions: SettingsActionsState,
    onTheme: (ThemeChoice) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    onSetup: () -> Unit = {},
    onDiagnostics: () -> Unit = {}
) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(stringResource(R.string.settings_privacy)) }
            item {
                OutlinedButton(onClick = onSetup, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.setup_title))
                }
                OutlinedButton(onClick = onDiagnostics, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.diagnostics_title))
                }
            }
            if (actions.failed) item {
                Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                    Text(stringResource(R.string.settings_save_error), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onDismissError) { Text(stringResource(R.string.library_dismiss)) }
                }
            }
            when (state) {
                SettingsUiState.Loading -> item { CircularProgressIndicator() }
                SettingsUiState.Failed -> item {
                    Text(stringResource(R.string.settings_read_error), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.library_retry)) }
                }
                is SettingsUiState.Ready -> {
                    item { Text(stringResource(R.string.settings_appearance), style = MaterialTheme.typography.titleLarge) }
                    item {
                        Column(Modifier.selectableGroup()) {
                            ThemeChoice.entries.forEach { choice ->
                                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                                    selected = state.appearance.theme == choice, enabled = !actions.busy,
                                    role = Role.RadioButton, onClick = { onTheme(choice) }
                                ).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    RadioButton(state.appearance.theme == choice, onClick = null, enabled = !actions.busy)
                                    Text(stringResource(when (choice) {
                                        ThemeChoice.SYSTEM -> R.string.settings_theme_system
                                        ThemeChoice.LIGHT -> R.string.settings_theme_light
                                        ThemeChoice.DARK -> R.string.settings_theme_dark
                                    }), modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(
                            state.appearance.dynamicColor, enabled = !actions.busy, role = Role.Switch,
                            onValueChange = onDynamicColor
                        ).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(R.string.settings_wallpaper_colors), Modifier.weight(1f))
                            Switch(state.appearance.dynamicColor, onCheckedChange = null, enabled = !actions.busy)
                        }
                    }
                    item { Text(stringResource(R.string.settings_theme_detail), style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
