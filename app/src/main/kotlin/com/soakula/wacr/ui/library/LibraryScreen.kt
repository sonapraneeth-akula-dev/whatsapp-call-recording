package com.soakula.wacr.ui.library

import android.text.format.DateUtils
import android.text.format.Formatter
import android.icu.text.BreakIterator
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.soakula.wacr.R
import com.soakula.wacr.data.CallDirection
import com.soakula.wacr.data.CallType
import com.soakula.wacr.data.RecordingStatus
import com.soakula.wacr.data.RecordingWithSession
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import java.util.Locale
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState, onRetry: () -> Unit,
    route: LibraryRoute = LibraryRoute(), onRoute: (LibraryRoute) -> Unit = {},
    onOpen: (UUID) -> Unit = {}
) {
    val entries = (state as? LibraryUiState.Ready)?.entries.orEmpty()
    val groups = remember(entries) { groupRecordings(entries) }
    val zone = ZoneId.systemDefault()
    val visible = remember(groups, route, zone) { filterRecordingGroups(groups, route, zone) }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.library_title)) }) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "filters") { FilterBar(groups, route, onRoute) }
            when (state) {
                LibraryUiState.Loading -> item { CircularProgressIndicator() }
                LibraryUiState.Failed -> item { LibraryFailure(onRetry) }
                is LibraryUiState.Ready -> {
                    if (visible.isEmpty()) item { Text(stringResource(
                        when {
                            route == LibraryRoute() -> R.string.library_empty
                            route.contact != null && route.query.isEmpty() && route.from == null -> R.string.library_contact_empty
                            else -> R.string.library_filter_empty
                        }
                    )) }
                    visible.forEach { group ->
                        item(key = "contact:${group.key}") {
                            ContactHeader(group, if (route.contact == null) { { onRoute(route.copy(contact = group.key)) } } else null)
                        }
                        items(group.entries, key = { it.recording.id }) { RecordingRow(it, onOpen, Modifier.animateItem()) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactHeader(group: RecordingGroup, onContact: (() -> Unit)? = null) {
    val name = contactGroupName(group)
    val initials = remember(name) { contactInitials(name) }
    val actionLabel = stringResource(R.string.library_view_contact_description, name)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer, shape = CircleShape,
            modifier = Modifier.defaultMinSize(48.dp, 48.dp).clearAndSetSemantics {}
        ) {
            Box(Modifier.padding(8.dp), contentAlignment = Alignment.Center) {
                Text(initials, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(name, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            Text(pluralStringResource(R.plurals.library_group_count, group.entries.size, group.entries.size),
                style = MaterialTheme.typography.bodyMedium)
            if (group.kind == LibraryContactKind.UNVERIFIED) Text(stringResource(R.string.library_group_unverified),
                style = MaterialTheme.typography.bodySmall)
            if (group.kind == LibraryContactKind.GROUP) Text(stringResource(R.string.library_group_calls),
                style = MaterialTheme.typography.bodySmall)
            if (onContact != null) TextButton(
                onClick = onContact, modifier = Modifier.semantics { contentDescription = actionLabel }
            ) { Text(stringResource(R.string.library_view_contact)) }
        }
    }
}

@Composable
internal fun contactGroupName(group: RecordingGroup): String =
    when (group.kind) {
        LibraryContactKind.MANUAL -> stringResource(R.string.library_manual_calls)
        LibraryContactKind.UNKNOWN -> stringResource(R.string.library_unknown_calls)
        else -> checkNotNull(group.name)
    }
internal fun contactInitials(name: String): String {
    val words = name.trim().split(Regex("[\\p{Z}\\s]+")).filter { it.isNotEmpty() }
    val selected = if (words.size < 2) words else listOf(words.first(), words.last())
    val characters = BreakIterator.getCharacterInstance(Locale.getDefault())
    return selected.joinToString("") {
        characters.setText(it)
        it.substring(0, characters.next())
    }.uppercase(Locale.getDefault())
}

@Composable
fun LibraryFailure(onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.library_error), color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onRetry) { Text(stringResource(R.string.library_retry)) }
    }
}

@Composable
fun RecordingRow(entry: RecordingWithSession, onOpen: ((UUID) -> Unit)? = null, modifier: Modifier = Modifier) {
    val recording = entry.recording
    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(recording.title ?: entry.session?.waDisplayName ?: stringResource(
                if (entry.session == null) R.string.library_manual else R.string.library_unknown_caller
            ),
                style = MaterialTheme.typography.titleMedium)
            Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(recording.startedAt)))
            entry.session?.let { session ->
                Text(stringResource(
                    when (session.callType) {
                        CallType.VIDEO -> R.string.library_video
                        CallType.VOICE -> R.string.library_voice
                        CallType.UNKNOWN -> R.string.library_unknown_type
                    }
                ) + " / " + stringResource(when (session.direction) {
                    CallDirection.INCOMING -> R.string.library_incoming
                    CallDirection.OUTGOING -> R.string.library_outgoing
                    CallDirection.UNKNOWN -> R.string.library_unknown_direction
                }))
            }
            Text(stringResource(when (recording.status) {
                RecordingStatus.RECORDING -> R.string.recording_active
                RecordingStatus.FINALIZED -> R.string.home_saved
                RecordingStatus.RECOVERED -> R.string.library_recovered
                RecordingStatus.FAILED -> R.string.library_failed
            }))
            recording.durationMs?.let { Text(DateUtils.formatElapsedTime(it / 1000)) }
            recording.sizeBytes?.let { Text(Formatter.formatShortFileSize(LocalContext.current, it)) }
            Text(stringResource(when (recording.remoteCaptured) {
                true -> R.string.library_remote_yes
                false -> R.string.library_remote_no
                null -> R.string.library_remote_unknown
            }), style = MaterialTheme.typography.bodySmall)
            if (onOpen != null) {
                TextButton(onClick = { onOpen(recording.id) }) { Text(stringResource(R.string.library_open)) }
            }
        }
    }
}
