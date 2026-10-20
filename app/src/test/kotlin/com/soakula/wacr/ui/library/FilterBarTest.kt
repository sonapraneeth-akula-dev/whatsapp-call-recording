package com.soakula.wacr.ui.library

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.soakula.wacr.R
import com.soakula.wacr.data.CallSessionEntity
import com.soakula.wacr.data.RecordingEntity
import com.soakula.wacr.data.RecordingWithSession
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "en-rUS-w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FilterBarTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val contact = CallSessionEntity(waDisplayName = "Demo Contact", matchKey = "demo contact", isGroupKnown = true)
    private fun entry(id: Long, day: Int, title: String, session: CallSessionEntity) = RecordingWithSession(
        RecordingEntity(UUID(0, id), LocalDate.of(2026, 1, day).atTime(12, 0).atZone(ZoneId.systemDefault())
            .toInstant().toEpochMilli(), 1, "MIC", title = title), session
    )
    private val entries get() = listOf(
        entry(1, 15, "Demo older", contact), entry(2, 16, "Renamed memo", contact),
        entry(3, 17, "Other clip", contact.copy(waDisplayName = "Other Person", matchKey = "other person"))
    )

    @Test fun contactSelectorSearchTitlesAndClearFiltersUpdateCanonicalRouteAndVisibleResults() {
        val route = mutableStateOf(LibraryRoute())
        compose.setContent {
            MaterialTheme {
                LibraryScreen(LibraryUiState.Ready(entries), {}, route.value, { route.value = LibraryRoute.parse(it.encode()) })
            }
        }
        compose.onNodeWithText(text(R.string.library_search_name)).performTextInput("dEmO")
        compose.onNodeWithText("Other clip").assertDoesNotExist()
        compose.onNodeWithText("Demo Contact").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.library_choose_contact)).performClick()
        compose.onNodeWithText("Other Person").performClick()
        compose.onNodeWithText(text(R.string.library_filter_empty)).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("CONTACT:other person", route.value.contact)
            assertEquals("dEmO", route.value.query)
        }
        compose.onNodeWithText(compose.activity.getString(R.string.library_selected_contact, "Other Person")).performClick()
        compose.onNodeWithText(text(R.string.library_all_contacts)).performClick()
        compose.onNodeWithText("Demo Contact").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.library_search_name)).performTextReplacement("memo")
        compose.onNodeWithText("Renamed memo").assertIsDisplayed()
        compose.onNodeWithText("Demo older").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.library_clear_filters)).performClick()
        compose.onNodeWithText("Other clip").assertIsDisplayed()
        compose.runOnIdle { assertEquals(LibraryRoute(), route.value) }
    }

    @Test fun nativeRangeInputAppliesInclusiveLocalDatesAndAllDatesClearsOnlyTheRange() {
        val route = mutableStateOf(LibraryRoute(query = "clip"))
        compose.setContent {
            MaterialTheme {
                LibraryScreen(LibraryUiState.Ready(entries), {}, route.value, { route.value = LibraryRoute.parse(it.encode()) })
            }
        }
        compose.onNodeWithText(text(R.string.library_choose_dates)).performClick()
        compose.onNodeWithContentDescription("Switch to text input mode").performClick()
        compose.onNodeWithText("Start date").performTextReplacement("01152026")
        compose.onNodeWithText("End date").performTextReplacement("01162026")
        compose.onNodeWithText(text(R.string.library_apply_dates)).assertIsEnabled().performClick()
        compose.onNodeWithText(text(R.string.library_filter_empty)).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(LocalDate.of(2026, 1, 15), route.value.from)
            assertEquals(LocalDate.of(2026, 1, 16), route.value.to)
            assertEquals("clip", route.value.query)
        }
        compose.onNodeWithText("Jan 15, 2026 to Jan 16, 2026").performClick()
        compose.onNodeWithText(compose.activity.getString(android.R.string.cancel)).performClick()
        compose.runOnIdle { assertEquals(LocalDate.of(2026, 1, 16), route.value.to) }
        compose.onNodeWithText("Jan 15, 2026 to Jan 16, 2026").performClick()
        compose.onNodeWithText(text(R.string.library_all_dates)).performClick()
        compose.onNodeWithText("Other clip").assertIsDisplayed()
        compose.runOnIdle { assertEquals(LibraryRoute(query = "clip"), route.value) }
    }

    private fun text(resource: Int) = compose.activity.getString(resource)
}
