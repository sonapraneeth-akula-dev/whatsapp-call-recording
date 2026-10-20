package com.soakula.wacr.ui.library

import android.content.res.ColorStateList
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.doAfterTextChanged
import com.soakula.wacr.R
import com.soakula.wacr.data.RecordingStatus
import com.soakula.wacr.data.RecordingWithSession
import com.soakula.wacr.data.validRecordingTitle

@Composable
fun RecordingActions(
    entry: RecordingWithSession,
    state: RecordingActionsState,
    onRename: (String) -> Unit,
    onDelete: () -> Unit
) {
    var renaming by rememberSaveable(entry.recording.id) { mutableStateOf(false) }
    var confirmingDelete by rememberSaveable(entry.recording.id) { mutableStateOf(false) }
    val removable = entry.recording.status != RecordingStatus.RECORDING && !state.busy && state.undo == null
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { renaming = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.library_rename))
        }
        OutlinedButton(onClick = { confirmingDelete = true }, enabled = removable, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.library_delete))
        }
        if (state.undo != null) Text(stringResource(R.string.library_delete_wait))
    }
    if (renaming) RenameDialog(
        entry.recording.title ?: entry.session?.waDisplayName ?: stringResource(
            if (entry.session == null) R.string.library_manual else R.string.library_unknown_caller
        ),
        state, onRename, { renaming = false }
    )
    if (confirmingDelete) AlertDialog(
        onDismissRequest = { if (!state.busy) confirmingDelete = false },
        title = { Text(stringResource(R.string.library_delete_confirm)) },
        text = { Text(stringResource(R.string.library_delete_detail)) },
        confirmButton = {
            TextButton(onClick = { confirmingDelete = false; onDelete() }, enabled = removable) {
                Text(stringResource(R.string.library_delete))
            }
        },
        dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.library_cancel)) } }
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun RenameDialog(
    initial: String,
    state: RecordingActionsState,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var title by rememberSaveable { mutableStateOf(initial) }
    val originalVersion = rememberSaveable { state.renameVersion }
    LaunchedEffect(state.renameVersion) { if (state.renameVersion != originalVersion) onDismiss() }
    val valid = validRecordingTitle(title)
    val label = stringResource(R.string.library_recording_title)
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val tint = (if (valid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error).toArgb()
    val save by rememberUpdatedState({ if (valid && !state.busy) onRename(title) })
    BasicAlertDialog(onDismissRequest = { if (!state.busy) onDismiss() }) {
        Surface(shape = MaterialTheme.shapes.extraLarge) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(stringResource(R.string.library_rename), style = MaterialTheme.typography.headlineSmall)
                AndroidView(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    factory = { context ->
                        EditText(context).apply {
                            id = R.id.recording_title_input
                            hint = label
                            setSingleLine(true)
                            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                            setText(title)
                            doAfterTextChanged { title = it?.toString().orEmpty() }
                            setOnEditorActionListener { _, action, _ ->
                                if (action == EditorInfo.IME_ACTION_DONE) { save(); true } else false
                            }
                        }
                    },
                    update = {
                        it.isEnabled = !state.busy
                        it.setTextColor(textColor)
                        it.setHintTextColor(hintColor)
                        it.backgroundTintList = ColorStateList.valueOf(tint)
                    }
                )
                Text(stringResource(R.string.library_title_rules),
                    color = if (valid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.library_rename_detail))
                state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = !state.busy) { Text(stringResource(R.string.library_cancel)) }
                    TextButton(onClick = { onRename(title) }, enabled = valid && !state.busy) {
                        Text(stringResource(R.string.library_save))
                    }
                }
            }
        }
    }
}
