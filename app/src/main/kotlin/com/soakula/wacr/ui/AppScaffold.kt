package com.soakula.wacr.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.soakula.wacr.R
import com.soakula.wacr.health.HealthIssue
import com.soakula.wacr.recording.RecorderState
import com.soakula.wacr.ui.home.HomeScreen
import com.soakula.wacr.ui.home.HomeUiState
import com.soakula.wacr.ui.library.LibraryScreen
import com.soakula.wacr.ui.library.LibraryRoute
import com.soakula.wacr.ui.library.LibraryUiState
import com.soakula.wacr.data.RecordingWithSession
import com.soakula.wacr.data.PendingRecordingDeletion
import com.soakula.wacr.ui.library.RecordingActionsState
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

private enum class Destination { HOME, LIBRARY, ALLOWLIST, SETTINGS }

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
    settingsContent: @Composable (() -> Unit) -> Unit = {},
    setupContent: @Composable (() -> Unit) -> Unit = {},
    setupRequest: Int = 0,
    settingsRequest: Int = 0,
    playbackRequest: Pair<UUID, Int>? = null,
    forceSetup: Boolean = false,
    startupLoading: Boolean = false,
    appearanceError: Boolean = false,
    onRetryAppearance: () -> Unit = {},
    onRetryHome: () -> Unit = {},
    modifier: Modifier = Modifier,
    playerContent: @Composable (RecordingWithSession, () -> Unit) -> Unit
) {
    var destination by rememberSaveable { mutableStateOf(Destination.HOME) }
    var showSetup by rememberSaveable { mutableStateOf(false) }
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
    var libraryRoute by rememberSaveable { mutableStateOf(LibraryRoute().encode()) }
    val contactRoute = remember(libraryRoute) { LibraryRoute.parse(libraryRoute) }
    val selected = (library as? LibraryUiState.Ready)?.entries?.firstOrNull { it.recording.id.toString() == selectedId }
    LaunchedEffect(setupRequest) {
        if (setupRequest != 0) {
            selectedId = null
            showSetup = true
        }
    }
    LaunchedEffect(settingsRequest) {
        if (settingsRequest != 0) {
            selectedId = null
            showSetup = false
            destination = Destination.SETTINGS
        }
    }
    LaunchedEffect(playbackRequest) {
        if (playbackRequest != null) {
            selectedId = playbackRequest.first.toString()
            showSetup = false
        }
    }
    LaunchedEffect(selectedId, library) {
        if (selectedId != null && library is LibraryUiState.Ready && selected == null) selectedId = null
    }
    BackHandler(!forceSetup && (showSetup || selectedId != null || destination != Destination.HOME)) {
        if (showSetup) showSetup = false
        else if (selectedId != null) selectedId = null
        else if (destination == Destination.LIBRARY && contactRoute != LibraryRoute()) libraryRoute = LibraryRoute().encode()
        else destination = Destination.HOME
    }
    Scaffold(
        modifier = modifier.semantics { testTagsAsResourceId = true },
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (appearanceError || actions.error != null) {
                Surface(color = MaterialTheme.colorScheme.errorContainer) {
                    Column(Modifier.statusBarsPadding().padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                        if (appearanceError) {
                            Text(stringResource(R.string.settings_read_error))
                            TextButton(onClick = onRetryAppearance) { Text(stringResource(R.string.library_retry)) }
                        }
                        actions.error?.let { message ->
                            Text(stringResource(message))
                            TextButton(onClick = onDismissError) { Text(stringResource(R.string.library_dismiss)) }
                        }
                    }
                }
            }
        },
        bottomBar = {
            if (!forceSetup && !startupLoading) Column {
                val largeCompact = LocalDensity.current.fontScale >= 1.5f && LocalConfiguration.current.screenWidthDp < 600
                val rows = Destination.entries.chunked(if (largeCompact) 2 else 4)
                rows.forEachIndexed { index, row ->
                    NavigationBar(windowInsets = if (index == rows.lastIndex) NavigationBarDefaults.windowInsets else WindowInsets(0)) {
                        row.forEach { item ->
                            NavigationBarItem(
                                modifier = Modifier.testTag("nav_${item.name.lowercase(java.util.Locale.ROOT)}"),
                                selected = destination == item,
                                onClick = { showSetup = false; selectedId = null; destination = item },
                                icon = { Icon(painterResource(when (item) {
                                    Destination.HOME -> R.drawable.ic_home
                                    Destination.LIBRARY -> R.drawable.ic_library
                                    Destination.ALLOWLIST -> R.drawable.ic_allowlist
                                    Destination.SETTINGS -> R.drawable.ic_settings
                                }), null) },
                                label = { Text(stringResource(when (item) {
                                    Destination.HOME -> R.string.nav_home
                                    Destination.LIBRARY -> R.string.nav_library
                                    Destination.ALLOWLIST -> R.string.nav_allowlist
                                    Destination.SETTINGS -> R.string.nav_settings
                                })) }
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            when {
                startupLoading -> Column(Modifier.align(Alignment.Center).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.settings_loading))
                }
                forceSetup || showSetup -> setupContent { showSetup = false }
                selected != null -> key(selected.recording.id) { playerContent(selected) { selectedId = null } }
                destination == Destination.ALLOWLIST -> allowlistContent()
                destination == Destination.SETTINGS -> settingsContent { showSetup = true }
                destination == Destination.LIBRARY || selectedId != null -> LibraryScreen(
                    library, onRetryLibrary, contactRoute, { libraryRoute = it.encode() }
                ) { selectedId = it.toString() }
                else -> HomeScreen(home, recorder, onRecord, onStop, onFix, library, onRetryLibrary, onRetryHome) {
                    selectedId = it.toString()
                }
            }
        }
    }
}
