package com.soakula.wacr.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.soakula.wacr.R
import com.soakula.wacr.health.HealthIssue
import com.soakula.wacr.recording.RecorderState
import com.soakula.wacr.ui.home.HomeScreen
import com.soakula.wacr.ui.home.HomeUiState
import com.soakula.wacr.ui.library.LibraryScreen
import com.soakula.wacr.ui.library.LibraryUiState
import com.soakula.wacr.data.RecordingWithSession
import com.soakula.wacr.data.PendingRecordingDeletion
import com.soakula.wacr.ui.library.RecordingActionsState
import kotlinx.coroutines.withTimeoutOrNull

private enum class Destination { HOME, LIBRARY, ALLOWLIST }

@Composable
fun AppScaffold(
    home: HomeUiState,
    recorder: RecorderState,
    library: LibraryUiState,
    onRecord: () -> Unit,
    onStop: () -> Unit,
    onFix: (HealthIssue) -> Unit,
    onRetryLibrary: () -> Unit,
    actions: RecordingActionsState = RecordingActionsState(),
    onUndo: (PendingRecordingDeletion) -> Unit = {},
    onDismissUndo: (PendingRecordingDeletion) -> Unit = {},
    onDismissError: () -> Unit = {},
    allowlistContent: @Composable () -> Unit = {},
    playerContent: @Composable (RecordingWithSession, () -> Unit) -> Unit
) {
    var destination by rememberSaveable { mutableStateOf(Destination.HOME) }
    val snackbar = remember { SnackbarHostState() }
    val undoMessage = stringResource(R.string.library_deleted)
    val undoLabel = stringResource(R.string.library_undo)
    LaunchedEffect(actions.undo) {
        val pending = actions.undo ?: return@LaunchedEffect
        val remaining = (pending.undoUntilElapsedMs - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0)
        val result = if (remaining > 0) withTimeoutOrNull(remaining) {
            snackbar.showSnackbar(undoMessage, undoLabel, withDismissAction = true, duration = SnackbarDuration.Indefinite)
        } else null
        if (result == SnackbarResult.ActionPerformed) onUndo(pending) else onDismissUndo(pending)
    }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = (library as? LibraryUiState.Ready)?.entries?.firstOrNull { it.recording.id.toString() == selectedId }
    LaunchedEffect(selectedId, library) {
        if (selectedId != null && library is LibraryUiState.Ready && selected == null) selectedId = null
    }
    BackHandler(selectedId != null || destination != Destination.HOME) {
        if (selectedId != null) selectedId = null else destination = Destination.HOME
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            actions.error?.let { message ->
                Surface(color = MaterialTheme.colorScheme.errorContainer) {
                    Column(Modifier.statusBarsPadding().padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                        Text(stringResource(message))
                        TextButton(onClick = onDismissError) { Text(stringResource(R.string.library_dismiss)) }
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = destination == Destination.HOME, onClick = { selectedId = null; destination = Destination.HOME },
                    icon = { Icon(painterResource(R.drawable.ic_home), null) },
                    label = { Text(stringResource(R.string.nav_home)) }
                )
                NavigationBarItem(
                    selected = destination == Destination.LIBRARY, onClick = { selectedId = null; destination = Destination.LIBRARY },
                    icon = { Icon(painterResource(R.drawable.ic_library), null) },
                    label = { Text(stringResource(R.string.nav_library)) }
                )
                NavigationBarItem(
                    selected = destination == Destination.ALLOWLIST,
                    onClick = { selectedId = null; destination = Destination.ALLOWLIST },
                    icon = { Icon(painterResource(R.drawable.ic_allowlist), null) },
                    label = { Text(stringResource(R.string.nav_allowlist)) }
                )
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
            when {
                selected != null -> key(selected.recording.id) { playerContent(selected) { selectedId = null } }
                destination == Destination.ALLOWLIST -> allowlistContent()
                destination == Destination.LIBRARY || selectedId != null -> LibraryScreen(library, onRetryLibrary) { selectedId = it.toString() }
                else -> HomeScreen(home, recorder, onRecord, onStop, onFix, library, onRetryLibrary) {
                    selectedId = it.toString()
                }
            }
        }
    }
}
