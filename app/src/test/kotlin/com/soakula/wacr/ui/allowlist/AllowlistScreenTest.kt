package com.soakula.wacr.ui.allowlist

import android.app.Application
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.soakula.wacr.R
import com.soakula.wacr.data.ContactRuleEntity
import com.soakula.wacr.recording.RecorderState
import com.soakula.wacr.ui.AppScaffold
import com.soakula.wacr.ui.home.HomeUiState
import com.soakula.wacr.ui.library.LibraryUiState
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
class AllowlistScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun navigationAddRemovalUndoAndCollisionDisclosureAreUsable() {
        val rule = ContactRuleEntity("Synthetic Contact", "synthetic contact", 1000, id = 1)
        val state = mutableStateOf<AllowlistUiState>(AllowlistUiState.Ready(listOf(rule)))
        val actions = mutableStateOf(AllowlistActionsState())
        var added = false
        var undone: RemovedContact? = null
        compose.setContent {
            MaterialTheme {
                AppScaffold(HomeUiState(loaded = true), RecorderState.Idle, LibraryUiState.Ready(emptyList()),
                    {}, {}, {}, {}, allowlistContent = {
                        AllowlistScreen(state.value, actions.value, { added = true }, {}, {
                            assertEquals(rule.id, it)
                            state.value = AllowlistUiState.Ready(emptyList())
                            actions.value = AllowlistActionsState(removed = RemovedContact(rule, SystemClock.elapsedRealtime() + 5000))
                        }, {
                            undone = it
                            state.value = AllowlistUiState.Ready(listOf(it.rule))
                            actions.value = AllowlistActionsState()
                        }, { actions.value = AllowlistActionsState() }, {})
                    }) { _, _ -> }
            }
        }
        compose.onNodeWithText(text(R.string.nav_allowlist)).performClick()
        compose.onNodeWithText(text(R.string.allowlist_matching_warning)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.allowlist_add)).performScrollTo().performClick()
        assertTrue(added)
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.allowlist_remove_name, rule.displayName))
            .performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.library_undo)).performScrollTo().performClick()
        assertEquals(rule, undone?.rule)
        compose.onNodeWithText(rule.displayName).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.nav_home)).performClick()
        compose.onNodeWithText(text(R.string.home_record)).assertIsDisplayed()
    }

    @Test fun failedLoadingIsNotEmptyAndRetryWorks() {
        val state = mutableStateOf<AllowlistUiState>(AllowlistUiState.Failed)
        var retried = false
        compose.setContent {
            MaterialTheme { AllowlistScreen(state.value, AllowlistActionsState(), {}, { retried = true }, {}, {}, {}, {}) }
        }
        compose.onNodeWithText(text(R.string.allowlist_add)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.allowlist_load_error)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.allowlist_empty)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.library_retry)).performScrollTo().performClick()
        assertTrue(retried)
        compose.runOnIdle { state.value = AllowlistUiState.Ready(emptyList()) }
        compose.onNodeWithText(text(R.string.allowlist_empty)).performScrollTo().assertIsDisplayed()
    }

    private fun text(resource: Int) = compose.activity.getString(resource)
}
