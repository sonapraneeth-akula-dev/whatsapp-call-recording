package com.soakula.wacr.ui.preview

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import com.soakula.wacr.data.Appearance
import com.soakula.wacr.ui.theme.WacrTheme

enum class MockupScreen(val title: String) {
    ONBOARDING("Get ready"),
    HOME("Home"),
    LIBRARY("Recordings"),
    PLAYER("Recording"),
    ALLOWLIST("Always record"),
    SETTINGS("Settings"),
    TRANSCRIPT("Transcript concept")
}

class MockupScreens : PreviewParameterProvider<MockupScreen> {
    override val values = MockupScreen.entries.asSequence()
}

@Preview(name = "Light", group = "Proposals", widthDp = 393, heightDp = 852, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Dark", group = "Proposals", widthDp = 393, heightDp = 852, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "200% text", group = "Proposals", widthDp = 393, heightDp = 852, fontScale = 2f)
@Preview(name = "Landscape", group = "Proposals", widthDp = 852, heightDp = 393)
@Composable
fun MockupGallery(@PreviewParameter(MockupScreens::class) screen: MockupScreen) {
    WacrTheme(Appearance(dynamicColor = false)) { ProposalFrame(screen) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProposalFrame(screen: MockupScreen) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(screen.title) },
                navigationIcon = {
                    if (screen == MockupScreen.PLAYER || screen == MockupScreen.TRANSCRIPT) {
                        IconButton(onClick = {}) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    }
                }
            )
        },
        bottomBar = {
            if (screen != MockupScreen.ONBOARDING) {
                NavigationBar {
                    val selected = when (screen) {
                        MockupScreen.HOME -> 0
                        MockupScreen.SETTINGS, MockupScreen.ALLOWLIST -> 2
                        else -> 1
                    }
                    listOf(
                        Triple("Home", Icons.Default.Home, 0),
                        Triple("Library", Icons.AutoMirrored.Filled.List, 1),
                        Triple("Settings", Icons.Default.Settings, 2)
                    ).forEach { (label, icon, index) ->
                        NavigationBarItem(
                            selected = index == selected, onClick = {},
                            icon = { Icon(icon, contentDescription = null) }, label = { Text(label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "DESIGN PREVIEW | SAMPLE DATA",
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(8.dp),
                color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.labelSmall
            )
            when (screen) {
                MockupScreen.ONBOARDING -> OnboardingProposal()
                MockupScreen.HOME -> HomeProposal()
                MockupScreen.LIBRARY -> LibraryProposal()
                MockupScreen.PLAYER -> PlayerProposal()
                MockupScreen.ALLOWLIST -> AllowlistProposal()
                MockupScreen.SETTINGS -> SettingsProposal()
                MockupScreen.TRANSCRIPT -> TranscriptProposal()
            }
        }
    }
}

@Composable
private fun ProposalCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun OnboardingProposal() {
    Text("Private call recordings,\non your phone.", style = MaterialTheme.typography.headlineMedium)
    Text("No network access. Encrypted audio and metadata. You choose who is recorded.")
    Text("2 of 6 steps complete", style = MaterialTheme.typography.labelLarge)
    LinearProgressIndicator(progress = { 2f / 6 }, modifier = Modifier.fillMaxWidth())
    listOf(
        Triple("Microphone", "Capture your voice during calls.", true),
        Triple("Notifications", "Keep recording status and Stop within reach.", true),
        Triple("Accessibility", "Recognize WhatsApp calls and enable concurrent microphone capture.", false),
        Triple("Notification access", "Corroborate call state without reading unrelated apps.", false),
        Triple("Battery settings", "Review Samsung background limits so detection stays available.", false),
        Triple("Microphone test", "Check the supported source. Remote audio is not guaranteed.", false)
    ).forEach { (title, explanation, complete) ->
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(explanation) },
            leadingContent = { Icon(if (complete) Icons.Default.Check else Icons.Default.Info, null) },
            trailingContent = { TextButton(onClick = {}, enabled = !complete) { Text(if (complete) "Done" else "Review") } }
        )
    }
    Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("Finish setup") }
    Text("Android permission and restricted-settings prompts remain native.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun HomeProposal() {
    ProposalCard("Ready for WhatsApp calls") {
        Text("Auto-record only people on your always-record list.")
        Detail("Permissions", "All enabled")
        Detail("Private storage free", "24.8 GB")
        OutlinedButton(onClick = {}) { Text("Always-record list") }
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("WhatsApp call connected", style = MaterialTheme.typography.labelLarge)
            Text("Demo contact", style = MaterialTheme.typography.headlineSmall)
            Text("Voice call | Outgoing | Not on your always-record list")
            Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Record this call") }
        }
    }
    ProposalCard("Know what is captured") {
        Text("Your microphone is the supported path. The other side may only be audible on speakerphone.")
        Text("WACR never changes your audio route automatically.", style = MaterialTheme.typography.bodySmall)
    }
    Text("Recent recordings", style = MaterialTheme.typography.titleLarge)
    RecordingProposalRow("Demo contact", "Today, 10:42", "8 min | Voice | Outgoing", starred = true)
    RecordingProposalRow("Unknown caller", "Yesterday, 18:10", "2 min | Recovered", starred = false)
}

@Composable
private fun RecordingProposalRow(name: String, date: String, detail: String, starred: Boolean) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.Person, contentDescription = null) }
            Column(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(date, style = MaterialTheme.typography.bodySmall)
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
            if (starred) Icon(Icons.Default.Star, contentDescription = "Starred")
        }
    }
}

@Composable
private fun LibraryProposal() {
    OutlinedTextField(
        value = "", onValueChange = {}, readOnly = true, modifier = Modifier.fillMaxWidth(),
        label = { Text("Search contact or title") }, leadingIcon = { Icon(Icons.Default.Search, null) }
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = true, onClick = {}, label = { Text("All") })
        FilterChip(selected = false, onClick = {}, label = { Text("Starred") })
        FilterChip(selected = false, onClick = {}, label = { Text("Dates") })
    }
    Text("3 recordings | 12 min | 2.8 MB", style = MaterialTheme.typography.bodySmall)
    Text("Demo contact", style = MaterialTheme.typography.titleMedium)
    RecordingProposalRow("Sample call", "Today, 10:42", "8 min | Voice | Outgoing", starred = true)
    Text("Demo group", style = MaterialTheme.typography.titleMedium)
    RecordingProposalRow("Group voice call", "Yesterday, 20:15", "2 min | Group | Manual", starred = false)
    Text("Unknown caller", style = MaterialTheme.typography.titleMedium)
    RecordingProposalRow("Recovered call", "Yesterday, 18:10", "2 min | Recovered", starred = false)
    Text("Private on this device. Remote audibility remains unverified.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun PlayerProposal() {
    Text("Demo contact", style = MaterialTheme.typography.headlineMedium)
    Text("Today, 10:42 | Voice | Outgoing")
    SuggestionChip(onClick = {}, label = { Text("Remote audio unverified") }, icon = { Icon(Icons.Default.Info, null) })
    Spacer(Modifier.height(16.dp))
    val played = MaterialTheme.colorScheme.primary
    val remaining = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(80.dp).semantics { contentDescription = "Sample audio waveform" }) {
        repeat(60) { index ->
            val x = (index + 0.5f) * size.width / 60
            val amplitude = ((index * 17 % 37) + 4) / 41f * size.height / 2
            drawLine(
                if (index < 21) played else remaining,
                Offset(x, size.height / 2 - amplitude), Offset(x, size.height / 2 + amplitude), strokeWidth = 3.dp.toPx()
            )
        }

    }
    Slider(value = 0.35f, onValueChange = {}, modifier = Modifier.fillMaxWidth())
    Detail("00:42", "02:00")
    Row(
        Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(onClick = {}) { Text("-10 s") }
        FilledIconButton(onClick = {}, modifier = Modifier.size(64.dp)) {
            Icon(Icons.Default.PlayArrow, contentDescription = "Play recording")
        }
        OutlinedButton(onClick = {}) { Text("+10 s") }
    }
    OutlinedButton(onClick = {}) { Text("Playback speed: 1x") }
    ProposalCard("Recording details") {
        Detail("Source", "VOICE_RECOGNITION")
        Detail("Audio", "16 kHz | Mono")
        Detail("Route", "Earpiece")
        Detail("File size", "0.5 MB")
        Text("Playback stays on this phone. No plaintext audio files.", style = MaterialTheme.typography.bodySmall)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = {}) { Text("Rename") }
        TextButton(onClick = {}) { Text("Star") }
        TextButton(onClick = {}, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
            Text("Delete")
        }
    }
}

@Composable
private fun AllowlistProposal() {
    Text("Only these people record automatically.", style = MaterialTheme.typography.headlineSmall)
    Text("Matched by WhatsApp display name, not phone number. People with the same name cannot be distinguished.")
    Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Add from contacts") }
    Text("Android contact picker only. No broad contacts permission.", style = MaterialTheme.typography.bodySmall)
    listOf("Demo contact", "Second demo").forEach { name ->
        OutlinedCard(Modifier.fillMaxWidth()) {
            ListItem(
                headlineContent = { Text(name) },
                supportingContent = { Text("Always record | Audio only") },
                leadingContent = { Icon(Icons.Default.Person, contentDescription = null) },
                trailingContent = {
                    IconButton(onClick = {}) { Icon(Icons.Default.Delete, contentDescription = "Remove $name") }
                }
            )
        }
    }
    ProposalCard("Other calls stay manual") {
        Text("Unknown callers and groups are never auto-recorded in v1. Use Record this call when needed.")
        Text("Contact renamed in WhatsApp? Update this list to match.", style = MaterialTheme.typography.bodySmall)
    }
    Snackbar(action = { TextButton(onClick = {}) { Text("Undo") } }) { Text("Sample contact removed") }
}

@Composable
private fun SettingsProposal() {
    ProposalCard("Recording") {
        Detail("Mode", "Audio only")
        Detail("Preferred source", "VOICE_RECOGNITION")
        Text("Provisional A73 profile. Repeat the microphone test after WhatsApp or system updates.")
        OutlinedButton(onClick = {}) { Text("Microphone test") }
    }
    ProposalCard("Appearance") {
        listOf("Follow system", "Light", "Dark").forEachIndexed { index, label ->
            ListItem(
                headlineContent = { Text(label) },
                leadingContent = {
                    RadioButton(
                        selected = index == 0, onClick = {},
                        modifier = Modifier.semantics { contentDescription = "$label theme" }
                    )
                }
            )
        }
        Text("Material You colors follow your wallpaper. Teal shown here is the preview fallback.")
    }
    ProposalCard("Privacy") {
        Text("Audio and metadata encrypted on this device. No network access or cloud backup.")
        ListItem(
            headlineContent = { Text("App lock | v1.1 concept") },
            supportingContent = { Text("Locks browsing, not background recording.") },
            trailingContent = {
                Switch(
                    checked = false, onCheckedChange = {}, enabled = false,
                    modifier = Modifier.semantics { contentDescription = "Future app lock" }
                )
            }
        )
    }
    ProposalCard("Health and diagnostics") {
        Detail("Accessibility", "Enabled")
        Detail("Notification access", "Enabled")
        Detail("Battery", "Unrestricted")
        Text("Bounded local logs; no automatic sharing.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = {}) { Text("View diagnostics") }
        OutlinedButton(onClick = {}) { Text("Save diagnostics") }
    }
    Text("Video, retention, export and transcription are not v1 settings.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun TranscriptProposal() {
    Text("FUTURE PHASE | v1.2", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
    Text("Demo contact", style = MaterialTheme.typography.headlineMedium)
    Text("Sample conversation only. No transcription engine or model has been implemented or selected.")
    Text("Romanized first, native script below. Tap a segment to seek.", style = MaterialTheme.typography.bodyMedium)
    listOf(
        Triple("You | 00:12", "Kal baat karte hain.", "\u0915\u0932 \u092c\u093e\u0924 \u0915\u0930\u0924\u0947 \u0939\u0948\u0902\u0964"),
        Triple("Demo contact | 00:16", "Sare, tomorrow works.", "\u0c38\u0c30\u0c47, tomorrow works."),
        Triple("You | 00:20", "I will call at ten.", "")
    ).forEach { (speaker, romanized, native) ->
        ProposalCard(speaker) {
            Text(romanized, style = MaterialTheme.typography.bodyLarge)
            if (native.isNotEmpty()) Text(native, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = {}) { Text("Play from this segment") }
        }
    }
    ProposalCard("Audio quality limits transcription") {
        Text("Only audible recorded speech can be transcribed. Missing remote audio cannot be reconstructed.")
        Text("Speaker labels and language accuracy need a later model bake-off.", style = MaterialTheme.typography.bodySmall)
    }
}
