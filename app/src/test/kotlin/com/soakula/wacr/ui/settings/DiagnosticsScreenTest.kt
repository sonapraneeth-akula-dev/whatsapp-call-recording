package com.soakula.wacr.ui.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.soakula.wacr.R
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiagnosticsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun exportStatesAndDestinationWarningRemainExplicitAndBackWorks() {
        val state = mutableStateOf(DiagnosticExportState.IDLE)
        var requested = false
        var back = false
        compose.setContent {
            MaterialTheme { DiagnosticsScreen(state.value, { requested = true }, { back = true }) }
        }
        scrollTo(R.string.diagnostics_destination_warning).assertIsDisplayed()
        scrollTo(R.string.diagnostics_export).performClick()
        assertTrue(requested)
        for (busy in listOf(DiagnosticExportState.CHOOSING, DiagnosticExportState.WRITING)) {
            compose.runOnIdle { state.value = busy }
            scrollTo(R.string.diagnostics_export).assertIsNotEnabled()
        }
        compose.runOnIdle { state.value = DiagnosticExportState.FAILED }
        scrollTo(R.string.diagnostics_failed).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.diagnostics_saved)).assertDoesNotExist()
        compose.runOnIdle { state.value = DiagnosticExportState.SAVED }
        scrollTo(R.string.diagnostics_saved).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.diagnostics_back)).performClick()
        assertTrue(back)
    }

    private fun scrollTo(resource: Int): androidx.compose.ui.test.SemanticsNodeInteraction {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text(resource)))
        return compose.onNodeWithText(text(resource))
    }

    private fun text(resource: Int) = compose.activity.getString(resource)
}
