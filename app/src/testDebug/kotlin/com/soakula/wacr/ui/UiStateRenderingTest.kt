package com.soakula.wacr.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.soakula.wacr.R
import com.soakula.wacr.security.AppLockState
import com.soakula.wacr.capability.CapabilityDevice
import com.soakula.wacr.capability.CapabilityMeter
import com.soakula.wacr.data.*
import com.soakula.wacr.recording.*
import com.soakula.wacr.ui.allowlist.*
import com.soakula.wacr.ui.home.*
import com.soakula.wacr.ui.library.*
import com.soakula.wacr.ui.onboarding.SetupChecklistScreen
import com.soakula.wacr.ui.player.*
import com.soakula.wacr.ui.settings.*
import com.soakula.wacr.ui.theme.WacrTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w393dp-h852dp-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiStateRenderingTest(private val dark: Boolean, private val fontScale: Float) {
    companion object {
        @JvmStatic @Parameters(name = "dark={0}, font={1}")
        fun configurations(): List<Array<Any>> = listOf(
            arrayOf(false, 1f), arrayOf(true, 1f), arrayOf(false, 2f), arrayOf(true, 2f)
        )
    }
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val id = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val ready = HomeUiState(loaded = true, availableBytes = 2_000_000_000)
    private val clip = RecordingWithSession(RecordingEntity(id, 1, 1, "MIC", title = "Sample recording",
        status = RecordingStatus.FINALIZED, durationMs = 30_000, audioPath = "$id/audio.aac.enc"), null)
    private data class Frame(
        val name: String, val message: Int, val progress: Boolean = false, val draw: @Composable () -> Unit
    )

    @Test fun appStates() = render("app",
        Frame("startup", R.string.settings_loading, true) {
            AppScaffold(ready, RecorderState.Idle, LibraryUiState.Loading, {}, {}, {}, {},
                startupLoading = true) { _, _ -> }
        },
        Frame("preference-error", R.string.settings_read_error) {
            AppScaffold(ready, RecorderState.Idle, LibraryUiState.Ready(emptyList()), {}, {}, {}, {},
                appearanceError = true) { _, _ -> }
        },
        Frame("navigation", R.string.nav_settings) {
            AppScaffold(ready, RecorderState.Idle, LibraryUiState.Ready(emptyList()), {}, {}, {}, {}) { _, _ -> }
        }
    )

    @Test fun homeStates() = render("home",
        Frame("loading", R.string.home_manual_recording, true) { HomeScreen(HomeUiState(), RecorderState.Idle, {}, {}, {}) },
        Frame("empty", R.string.library_empty) {
            HomeScreen(ready, RecorderState.Idle, {}, {}, {}, LibraryUiState.Ready(emptyList()))
        },
        Frame("health-error", R.string.home_health_error) {
            HomeScreen(ready.copy(error = R.string.home_health_error), RecorderState.Idle, {}, {}, {})
        },
        Frame("preparing", R.string.recording_preparing, true) { HomeScreen(ready, RecorderState.Preparing(id), {}, {}, {}) },
        Frame("recording", R.string.recording_active) {
            HomeScreen(ready, RecorderState.Recording(id, System.currentTimeMillis(), true), {}, {}, {})
        },
        Frame("saving", R.string.recording_saving, true) { HomeScreen(ready, RecorderState.Saving(id), {}, {}, {}) },
        Frame("capture-error", R.string.recording_error_mic) {
            HomeScreen(ready, RecorderState.Failed(id, RecorderFailure.NO_MIC_PERMISSION), {}, {}, {})
        }
    )

    @Test fun libraryStates() = render("library",
        Frame("loading", R.string.library_title, true) { LibraryScreen(LibraryUiState.Loading, {}) },
        Frame("error", R.string.library_error) { LibraryScreen(LibraryUiState.Failed, {}) },
        Frame("empty", R.string.library_empty) { LibraryScreen(LibraryUiState.Ready(emptyList()), {}) },
        Frame("filtered-empty", R.string.library_filter_empty) {
            LibraryScreen(LibraryUiState.Ready(listOf(clip)), {}, LibraryRoute(query = "No matching sample"))
        },
        Frame("ready", R.string.library_remote_unknown) { LibraryScreen(LibraryUiState.Ready(listOf(clip)), {}) }
    )

    @Test fun allowlistStates() = render("allowlist",
        Frame("loading", R.string.allowlist_title, true) {
            AllowlistScreen(AllowlistUiState.Loading, AllowlistActionsState(), {}, {}, {}, {}, {}, {})
        },
        Frame("error", R.string.allowlist_load_error) {
            AllowlistScreen(AllowlistUiState.Failed, AllowlistActionsState(), {}, {}, {}, {}, {}, {})
        },
        Frame("empty", R.string.allowlist_empty) {
            AllowlistScreen(AllowlistUiState.Ready(emptyList()), AllowlistActionsState(), {}, {}, {}, {}, {}, {})
        },
        Frame("action-error", R.string.allowlist_load_error) {
            AllowlistScreen(AllowlistUiState.Ready(emptyList()),
                AllowlistActionsState(error = R.string.allowlist_load_error), {}, {}, {}, {}, {}, {})
        }
    )

    @Test fun setupStates() = render("setup",
        Frame("loading", R.string.setup_intro, true) {
            SetupChecklistScreen(HomeUiState(), false, false, {}, {}, {}, {}, {}, {})
        },
        Frame("error", R.string.home_health_error) {
            SetupChecklistScreen(ready.copy(error = R.string.home_health_error), false, false, {}, {}, {}, {}, {}, {})
        },
        Frame("save-error", R.string.settings_save_error) {
            SetupChecklistScreen(ready, false, true, {}, {}, {}, {}, {}, {})
        },
        Frame("checked", R.string.setup_intro) { SetupChecklistScreen(ready, false, false, {}, {}, {}, {}, {}, {}) }
    )

    @Test fun settingsStates() = render("settings",
        Frame("loading", R.string.settings_title, true) {
            SettingsScreen(SettingsUiState.Loading, SettingsActionsState(), {}, {}, {}, {})
        },
        Frame("error", R.string.settings_read_error) {
            SettingsScreen(SettingsUiState.Failed, SettingsActionsState(), {}, {}, {}, {})
        },
        Frame("save-error", R.string.settings_save_error) {
            SettingsScreen(SettingsUiState.Ready(Appearance()), SettingsActionsState(failed = true), {}, {}, {}, {})
        },
        Frame("ready", R.string.settings_appearance) {
            SettingsScreen(SettingsUiState.Ready(Appearance()), SettingsActionsState(), {}, {}, {}, {})
        },
        Frame("lock-enabled", R.string.settings_app_lock) {
            SettingsScreen(SettingsUiState.Ready(Appearance(), appLock = true), SettingsActionsState(), {}, {}, {}, {})
        },
        Frame("authentication-unavailable", R.string.app_lock_auth_unavailable) {
            SettingsScreen(SettingsUiState.Ready(Appearance()),
                SettingsActionsState(failed = true, error = R.string.app_lock_auth_unavailable), {}, {}, {}, {})
        }
    )

    @Test fun lockStates() = render("lock",
        Frame("loading", R.string.settings_loading, true) {
            AppLockScreen(AppLockState.Loading, false, null, {}, {}, {}, {})
        },
        Frame("failed", R.string.app_lock_failed) {
            AppLockScreen(AppLockState.Failed, false, null, {}, {}, {}, {})
        },
        Frame("locked", R.string.app_lock_unlock) {
            AppLockScreen(AppLockState.Locked(1), false, null, {}, {}, {}, {})
        },
        Frame("authenticating", R.string.app_lock_unlock) {
            AppLockScreen(AppLockState.Locked(1), true, null, {}, {}, {}, {})
        },
        Frame("authentication-error", R.string.app_lock_auth_error) {
            AppLockScreen(AppLockState.Locked(1), false, R.string.app_lock_auth_error, {}, {}, {}, {})
        }
    )

    @Test fun playerStates() = render("player",
        Frame("loading", R.string.player_title, true) { PlayerScreen(clip, PlaybackUiState(), false, {}, {}, {}) },
        Frame("error", R.string.player_error) {
            PlayerScreen(clip, PlaybackUiState(failed = true), false, {}, {}, {})
        },
        Frame("unavailable", R.string.player_unavailable) {
            PlayerScreen(clip.copy(recording = clip.recording.copy(audioPath = null)), PlaybackUiState(), false, {}, {}, {})
        },
        Frame("capture-blocked", R.string.player_capture_active) {
            PlayerScreen(clip, PlaybackUiState(ready = true), true, {}, {}, {})
        },
        Frame("ready", R.string.player_play) {
            PlayerScreen(clip, PlaybackUiState(ready = true, durationMs = 30_000), false, {}, {}, {})
        }
    )

    @Test fun diagnosticsStates() = render("diagnostics", *DiagnosticExportState.entries.map { state ->
        Frame(state.name.lowercase(), when (state) {
            DiagnosticExportState.IDLE -> R.string.diagnostics_idle
            DiagnosticExportState.CHOOSING -> R.string.diagnostics_choosing
            DiagnosticExportState.WRITING -> R.string.diagnostics_writing
            DiagnosticExportState.SAVED -> R.string.diagnostics_saved
            DiagnosticExportState.FAILED -> R.string.diagnostics_failed
        }, state == DiagnosticExportState.WRITING) { DiagnosticsScreen(state, {}, {}) }
    }.toTypedArray())

    @Test fun capabilityStates() {
        val empty = CapabilityUiState.Ready(emptyList(), null, emptyList())
        val device = CapabilityDevice("Sample device", "sample-build", "com.whatsapp", "sample-version", 1)
        val result = CapabilityResult(id, device, AudioRoute.EARPIECE, CaptureSource.MIC, CapabilityStatus.COMPLETE,
            1, id, CapabilityMeter.TOTAL_FRAMES, -20.0, -30.0, 0.0, id = 1)
        render("capability",
            Frame("loading", R.string.capability_title, true) {
                CapabilityScreen(CapabilityUiState.Loading, CapabilityActions(), null, false, emptySet(),
                    {}, {}, { _, _, _ -> }, {}, {}, {}, {}, {})
            },
            Frame("error", R.string.capability_load_failed) {
                CapabilityScreen(CapabilityUiState.Failed, CapabilityActions(), null, false, emptySet(),
                    {}, {}, { _, _, _ -> }, {}, {}, {}, {}, {})
            },
            Frame("empty", R.string.capability_empty) {
                CapabilityScreen(empty, CapabilityActions(), null, false, emptySet(),
                    {}, {}, { _, _, _ -> }, {}, {}, {}, {}, {})
            },
            Frame("review-error", R.string.capability_review_failed) {
                CapabilityScreen(empty, CapabilityActions(error = R.string.capability_review_failed), null, false, emptySet(),
                    {}, {}, { _, _, _ -> }, {}, {}, {}, {}, {})
            },
            Frame("unreviewed", R.string.capability_save_verdict) {
                CapabilityScreen(CapabilityUiState.Ready(listOf(device), AudioRoute.EARPIECE, listOf(result)),
                    CapabilityActions(), null, false, setOf(id), {}, {}, { _, _, _ -> }, {}, {}, {}, {}, {})
            }
        )
    }

    private fun render(screen: String, vararg frames: Frame) {
        val current = mutableStateOf(frames.first())
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                WacrTheme(Appearance(dynamicColor = false), systemDark = dark) {
                    key(current.value.name) { current.value.draw() }
                }
            }
        }
        val configuration = "${if (dark) "dark" else "light"}-${if (fontScale == 2f) "2x" else "1x"}"
        val output = File("build\\ui-states\\$configuration\\$screen").apply { mkdirs() }
        val configurationRoot = checkNotNull(output.parentFile)
        for (frame in frames) {
            compose.runOnIdle { current.value = frame }
            capture(File(output, "${frame.name}.png"))
            auditVisibleControls()
            visible(hasText(compose.activity.getString(frame.message)))
            if (frame.progress) visible(SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate))
            if (compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty()) {
                compose.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
            }
            capture(File(output, "${frame.name}-end.png"))
            auditVisibleControls()
        }
        File(output, "index.html").writeText("""
            <!doctype html><html lang="en"><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1"><title>WACR $screen states</title>
            <style>body{font:16px system-ui;margin:24px}img{max-width:100%;height:auto}figure{display:inline-block;margin:8px}</style>
            <h1>Production $screen states: $configuration</h1><p>Synthetic data; native host renderings, not approved pixel baselines or phone evidence.
            Top and scrolled-end viewports, not full-page captures.</p>
            ${frames.joinToString("\n") { frame -> """<h2>${frame.name}</h2>
            <figure><img src="${frame.name}.png" alt="${frame.name}, top"></figure>
            <figure><img src="${frame.name}-end.png" alt="${frame.name}, end"></figure>""" }}
            </html>
        """.trimIndent())
        File(configurationRoot, "index.html").writeText("""
            <!doctype html><html lang="en"><meta charset="utf-8"><title>WACR production UI states</title>
            <h1>Production UI state review</h1><p>Synthetic host renderings, not owner approval or phone acceptance.</p>
            ${listOf("app", "home", "library", "allowlist", "setup", "settings", "lock", "player", "diagnostics", "capability")
                .joinToString("\n") { """<p><a href="$it/index.html">$it</a></p>""" }}
            </html>
        """.trimIndent())
        File(checkNotNull(configurationRoot.parentFile), "index.html").writeText("""
            <!doctype html><html lang="en"><meta charset="utf-8"><title>WACR production UI review matrix</title>
            <h1>Production UI review matrix</h1><p>Synthetic native host renderings and semantics checks,
            not approved pixel baselines, Accessibility Scanner, TalkBack or phone performance evidence.</p>
            ${listOf("light-1x", "dark-1x", "light-2x", "dark-2x")
                .joinToString("\n") { """<p><a href="$it/index.html">$it</a></p>""" }}
            </html>
        """.trimIndent())
    }

    private fun auditVisibleControls() {
        val viewport = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density
        val controls = hasClickAction() or SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)
        for (node in compose.onAllNodes(controls).fetchSemanticsNodes()) {
            val bounds = node.touchBoundsInRoot
            if (bounds.width + 1 < node.size.width || bounds.height + 1 < node.size.height) continue
            if (bounds.top <= viewport.top || bounds.bottom >= viewport.bottom ||
                bounds.left < viewport.left || bounds.right > viewport.right) continue
            val label = node.config.getOrNull(SemanticsProperties.ContentDescription)?.any(String::isNotBlank) == true ||
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.isNotBlank() } == true
            assertTrue("Clickable control ${node.id} requires a readable label: ${node.config}", label)
            if (node.config.contains(SemanticsProperties.Disabled)) continue
            assertTrue("Control ${node.id} touch target must be >=48dp, was $bounds: ${node.config}",
                bounds.width >= 48 * density - 1 && bounds.height >= 48 * density - 1)
        }
        for (node in compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult)).fetchSemanticsNodes()) {
            if (node.config.contains(SemanticsProperties.EditableText)) continue
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            if (node.config.getOrNull(SemanticsProperties.Role) == Role.Tab) {
                assertTrue("Navigation label must remain a single complete line: ${node.config}",
                    layouts.all { it.lineCount == 1 })
            }
            // Paragraph constraint width can exceed wrapped text's actual layout width.
            val clipped = layouts.any { layout ->
                layout.multiParagraph.didExceedMaxLines || (0 until layout.lineCount).any { line ->
                    layout.getLineLeft(line) < -1 || layout.getLineRight(line) > layout.size.width + 1 ||
                        layout.getLineBottom(line) > layout.size.height + 1
                }
            }
            assertTrue("Rendered text lines must fit their layout: ${node.config}", !clipped)
        }
    }

    private fun visible(matcher: SemanticsMatcher) {
        val node = compose.onNode(matcher)
        if (!node.isDisplayed()) {
            if (compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty())
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(matcher)
            else node.performScrollTo()
        }
        node.assertIsDisplayed()
    }

    private fun capture(file: File) {
        val bitmap = compose.runOnIdle {
            val view = compose.activity.window.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue("Rendered state must not be blank", pixels.asSequence().distinct().take(16).count() == 16)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }
}
