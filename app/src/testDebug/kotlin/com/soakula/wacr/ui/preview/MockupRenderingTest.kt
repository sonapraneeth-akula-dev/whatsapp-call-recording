package com.soakula.wacr.ui.preview

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MockupRenderingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test @Config(qualifiers = "+notnight") fun light() = render("light")
    @Test @Config(qualifiers = "+night") fun dark() = render("dark")
    @Test @Config(qualifiers = "+notnight") fun largeText() = render("large-text", 2f)
    @Test @Config(qualifiers = "w852dp-h393dp-land-notnight-mdpi") fun landscape() = render("landscape")

    private fun render(configuration: String, fontScale: Float = 1f) {
        val current = mutableStateOf(MockupScreen.ONBOARDING)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                key(current.value) { MockupGallery(current.value) }
            }
        }
        val output = File(File("build", "mockups"), configuration).apply { mkdirs() }
        for (screen in MockupScreen.entries) {
            compose.runOnIdle { current.value = screen }
            compose.onNodeWithText("DESIGN PREVIEW | SAMPLE DATA").assertIsDisplayed()
            capture(File(output, "${screen.name.lowercase()}.png"))
            compose.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 10_000f) }
            capture(File(output, "${screen.name.lowercase()}-end.png"))
        }
        File(output, "index.html").writeText("""
            <!doctype html><html lang="en"><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>WACR design proposals: $configuration</title>
            <style>
            body{font:16px system-ui;margin:24px;background:#ecefeb;color:#17241d}
            main{max-width:1100px;margin:auto}a{color:#006b57}section{margin:32px 0}
            .shots{display:flex;flex-wrap:wrap;gap:16px}figure{margin:0;max-width:100%}
            img{display:block;max-width:100%;height:auto;border:1px solid #697970;border-radius:16px}
            figcaption{padding:8px}h1{font-size:28px}
            </style><main><h1>WACR design proposals: $configuration</h1>
            <p>Sample data only. Inert mockups, not a functioning recorder. Top and scrolled-end viewport images,
            not full-page captures. Owner approval pending; transcript and app lock are later-phase concepts.</p>
            <nav><a href="../light/index.html">Light</a> | <a href="../dark/index.html">Dark</a> |
            <a href="../large-text/index.html">200% text</a> | <a href="../landscape/index.html">Landscape</a></nav>
            ${MockupScreen.entries.joinToString("\n") { screen ->
                val name = screen.name.lowercase()
                """<section><h2>${screen.title}</h2><div class="shots">
                <figure><img src="$name.png" alt="${screen.title}, top"><figcaption>Top</figcaption></figure>
                <figure><img src="$name-end.png" alt="${screen.title}, scrolled end"><figcaption>Scrolled end</figcaption></figure>
                </div></section>"""
            }}
            </main></html>
        """.trimIndent())
    }

    private fun capture(file: File) {
        val bitmap = compose.runOnIdle {
            val view = compose.activity.window.decorView
            // PixelCopy needs a real surface; draw the native view for headless review images.
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue("Rendered proposal must not be blank", pixels.asSequence().distinct().take(16).count() == 16)
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
