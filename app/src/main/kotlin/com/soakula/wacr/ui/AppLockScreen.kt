package com.soakula.wacr.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.soakula.wacr.R
import com.soakula.wacr.security.AppLockState

@Composable
fun AppLockScreen(
    state: AppLockState,
    busy: Boolean,
    @StringRes error: Int?,
    onUnlock: () -> Unit,
    onRetry: () -> Unit,
    onSecuritySettings: () -> Unit,
    onSetup: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.app_lock_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.app_lock_detail))
        when (state) {
            AppLockState.Loading -> {
                CircularProgressIndicator()
                Text(stringResource(R.string.settings_loading))
            }
            AppLockState.Failed -> {
                Text(stringResource(R.string.app_lock_failed), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Button(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.library_retry)) }
            }
            is AppLockState.Locked -> {
                error?.let {
                    Text(stringResource(it), color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                Button(onClick = onUnlock, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.app_lock_unlock))
                }
                OutlinedButton(onClick = onSecuritySettings, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.app_lock_security_settings))
                }
            }
            AppLockState.Disabled, is AppLockState.Unlocked -> Unit
        }
        TextButton(onClick = onSetup, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.setup_title)) }
    }
}
