package com.soakula.wacr.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.soakula.wacr.R
import com.soakula.wacr.health.HealthIssue
import com.soakula.wacr.recording.RecorderState
import com.soakula.wacr.ui.home.HomeScreen
import com.soakula.wacr.ui.home.HomeUiState
import com.soakula.wacr.ui.library.LibraryScreen
import com.soakula.wacr.ui.library.LibraryUiState

@Composable
fun AppScaffold(
    home: HomeUiState,
    recorder: RecorderState,
    library: LibraryUiState,
    onRecord: () -> Unit,
    onStop: () -> Unit,
    onFix: (HealthIssue) -> Unit,
    onRetryLibrary: () -> Unit
) {
    var showLibrary by rememberSaveable { mutableStateOf(false) }
    BackHandler(showLibrary) { showLibrary = false }
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = !showLibrary, onClick = { showLibrary = false },
                    icon = { Icon(painterResource(R.drawable.ic_home), null) },
                    label = { Text(stringResource(R.string.nav_home)) }
                )
                NavigationBarItem(
                    selected = showLibrary, onClick = { showLibrary = true },
                    icon = { Icon(painterResource(R.drawable.ic_library), null) },
                    label = { Text(stringResource(R.string.nav_library)) }
                )
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
            if (showLibrary) LibraryScreen(library, onRetryLibrary)
            else HomeScreen(home, recorder, onRecord, onStop, onFix, library, onRetryLibrary)
        }
    }
}
