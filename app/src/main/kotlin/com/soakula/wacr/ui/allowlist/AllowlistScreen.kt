package com.soakula.wacr.ui.allowlist

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.soakula.wacr.R
import com.soakula.wacr.data.RuleKind
import kotlinx.coroutines.delay

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AllowlistScreen(
    state: AllowlistUiState,
    actions: AllowlistActionsState,
    onAdd: () -> Unit,
    onRetry: () -> Unit,
    onRemove: (Long) -> Unit,
    onUndo: (RemovedContact) -> Unit,
    onDismissUndo: (RemovedContact) -> Unit,
    onDismissError: () -> Unit
) {
    val removed = actions.removed
    LaunchedEffect(removed) {
        if (removed != null) {
            delay((removed.deadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            onDismissUndo(removed)
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.allowlist_title)) }) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Text(stringResource(R.string.allowlist_matching_warning)) }
            item { Text(stringResource(R.string.allowlist_detection_pending), style = MaterialTheme.typography.bodySmall) }
            item {
                Button(onClick = onAdd, enabled = state is AllowlistUiState.Ready && !actions.busy,
                    modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.allowlist_add)) }
            }
            actions.error?.let { error ->
                item {
                    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                        Text(stringResource(error), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onDismissError) { Text(stringResource(R.string.library_dismiss)) }
                    }
                }
            }
            if (removed != null && removed.deadlineMs > SystemClock.elapsedRealtime()) {
                item {
                    OutlinedCard(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
                        Column(Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.allowlist_removed, removed.rule.displayName))
                            TextButton(onClick = { onUndo(removed) }, enabled = !actions.busy) {
                                Text(stringResource(R.string.library_undo))
                            }
                        }
                    }
                }
            }
            when (state) {
                AllowlistUiState.Loading -> item { CircularProgressIndicator() }
                AllowlistUiState.Failed -> item {
                    Text(stringResource(R.string.allowlist_load_error), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.library_retry)) }
                }
                is AllowlistUiState.Ready -> {
                    if (state.rules.isEmpty()) item { Text(stringResource(R.string.allowlist_empty)) }
                    items(state.rules, key = { it.id }) { rule ->
                        val removeLabel = stringResource(R.string.allowlist_remove_name, rule.displayName)
                        OutlinedCard(Modifier.animateItem().fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(rule.displayName, style = MaterialTheme.typography.titleMedium)
                                if (rule.kind == RuleKind.GROUP) Text(stringResource(R.string.allowlist_group_manual))
                                TextButton(
                                    onClick = { onRemove(rule.id) },
                                    enabled = !actions.busy && removed == null,
                                    modifier = Modifier.semantics { contentDescription = removeLabel }
                                ) { Text(stringResource(R.string.allowlist_remove)) }
                            }
                        }
                    }
                }
            }
        }
    }
}
