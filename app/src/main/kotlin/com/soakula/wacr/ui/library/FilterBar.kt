package com.soakula.wacr.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.soakula.wacr.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FilterBar(groups: List<RecordingGroup>, route: LibraryRoute, onRoute: (LibraryRoute) -> Unit) {
    var choosingContact by rememberSaveable { mutableStateOf(false) }
    var choosingDates by rememberSaveable { mutableStateOf(false) }
    val contact = groups.firstOrNull { it.key == route.contact }
    val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            route.query, { onRoute(route.copy(query = it)) }, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.library_search_name)) }, singleLine = true
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { choosingContact = true }, enabled = groups.isNotEmpty()) {
                Text(if (route.contact == null) stringResource(R.string.library_choose_contact)
                else stringResource(R.string.library_selected_contact, contact?.let { contactGroupName(it) }
                    ?: stringResource(R.string.library_missing_contact)))
            }
            TextButton(onClick = { choosingDates = true }) {
                Text(if (route.from == null) stringResource(R.string.library_choose_dates)
                else stringResource(R.string.library_selected_dates,
                    formatter.format(route.from), formatter.format(route.to)))
            }
            if (route != LibraryRoute()) TextButton(onClick = { onRoute(LibraryRoute()) }) {
                Text(stringResource(R.string.library_clear_filters))
            }
        }
    }
    if (choosingContact) AlertDialog(
        onDismissRequest = { choosingContact = false },
        title = { Text(stringResource(R.string.library_choose_contact)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                item {
                    TextButton(onClick = { onRoute(route.copy(contact = null)); choosingContact = false },
                        modifier = Modifier.fillMaxWidth().semantics { selected = route.contact == null }) {
                        Text(stringResource(R.string.library_all_contacts))
                    }
                }
                items(groups, key = { it.key }) { group ->
                    TextButton(
                        onClick = { onRoute(route.copy(contact = group.key)); choosingContact = false },
                        modifier = Modifier.fillMaxWidth().semantics { selected = route.contact == group.key }
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(contactGroupName(group))
                            if (group.kind == LibraryContactKind.UNVERIFIED)
                                Text(stringResource(R.string.library_group_unverified), style = MaterialTheme.typography.bodySmall)
                            if (group.kind == LibraryContactKind.GROUP)
                                Text(stringResource(R.string.library_group_calls), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { choosingContact = false }) { Text(stringResource(android.R.string.cancel)) } }
    )
    if (choosingDates) LibraryDateRangeDialog(route, {
        onRoute(it)
        choosingDates = false
    }, { choosingDates = false })
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun LibraryDateRangeDialog(route: LibraryRoute, onApply: (LibraryRoute) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = route.from?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        initialSelectedEndDateMillis = route.to?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        yearRange = 1..9999
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                fun date(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC).toLocalDate()
                onApply(route.copy(from = date(checkNotNull(state.selectedStartDateMillis)),
                    to = date(checkNotNull(state.selectedEndDateMillis))))
            }, enabled = state.selectedStartDateMillis != null && state.selectedEndDateMillis != null) {
                Text(stringResource(R.string.library_apply_dates))
            }
        },
        dismissButton = {
            FlowRow {
                if (route.from != null) TextButton(onClick = { onApply(route.copy(from = null, to = null)) }) {
                    Text(stringResource(R.string.library_all_dates))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
            }
        }
    ) {
        DateRangePicker(state, Modifier.fillMaxWidth().heightIn(max = 500.dp),
            title = { Text(stringResource(R.string.library_choose_dates), Modifier.padding(16.dp)) })
    }
}
