package com.soakula.wacr.ui.library

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import com.soakula.wacr.ui.preview.LibraryBenchmarkScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryBenchmarkUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun thirtyUpdatesCompleteOnlyAfterActualContentDraws() {
        compose.setContent { MaterialTheme { LibraryBenchmarkScreen() } }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Run benchmark").performClick()
        val bitmap = Bitmap.createBitmap(393, 852, Bitmap.Config.ARGB_8888)
        try {
            repeat(75) {
                compose.mainClock.advanceTimeByFrame()
                compose.runOnIdle {
                    compose.activity.window.decorView.invalidate()
                    compose.activity.window.decorView.draw(Canvas(bitmap))
                    // View.post runs on Android's clock, not Compose's test clock.
                    ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS)
                }
            }
            compose.mainClock.autoAdvance = true
            compose.onNodeWithText("30 updates, median", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Run benchmark").assertIsEnabled()
        } finally {
            bitmap.recycle()
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun cancellationStopsThePendingDrawWithoutPublishingSuccess() {
        compose.setContent { MaterialTheme { LibraryBenchmarkScreen() } }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Run benchmark").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Cancel").performClick()
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("Cancelled:", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Run benchmark").assertIsEnabled()
        compose.onNodeWithText("30 updates, median", substring = true).assertDoesNotExist()
    }

    @Test fun leavingForegroundCancelsRatherThanPublishingAFalseTiming() {
        compose.setContent { MaterialTheme { LibraryBenchmarkScreen() } }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Run benchmark").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.mainClock.autoAdvance = true
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("Cancelled:", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Run benchmark").assertIsEnabled()
        compose.onNodeWithText("30 updates, median", substring = true).assertDoesNotExist()
    }
}
