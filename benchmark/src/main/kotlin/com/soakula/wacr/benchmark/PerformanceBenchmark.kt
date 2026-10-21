package com.soakula.wacr.benchmark

import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TARGET = "com.soakula.wacr.benchmark"
private const val NAMESPACE = "com.soakula.wacr"
private const val TIMEOUT = 15_000L

private fun UiDevice.requireNode(tag: String) =
    checkNotNull(wait(Until.findObject(By.res(tag).pkg(TARGET)), TIMEOUT)) { "Missing ready node: $tag" }

private fun prepareFixture() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.context.startActivity(Intent().apply {
        component = ComponentName(TARGET, "$NAMESPACE.ui.benchmark.PerformanceFixtureActivity")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    })
    val device = UiDevice.getInstance(instrumentation)
    check(device.wait(Until.hasObject(By.desc("benchmark_fixture_ready").pkg(TARGET)), TIMEOUT)) {
        "Synthetic metadata preparation failed"
    }
    device.pressHome()
}

private fun UiDevice.navigateAndScroll() {
    requireNode("nav_library").click()
    val list = requireNode("library_list")
    list.setGestureMargin(displayWidth / 5)
    repeat(4) { list.fling(Direction.DOWN) }
    repeat(4) { list.fling(Direction.UP) }
    requireNode("nav_allowlist").click()
    requireNode("nav_settings").click()
    requireNode("nav_home").click()
}

@RunWith(AndroidJUnit4::class)
class PerformanceBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()
    @Before fun seed() = prepareFixture()

    @Test fun coldStartup() = benchmark.measureRepeated(
        packageName = TARGET,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.COLD,
        iterations = 10,
        setupBlock = { pressHome() }
    ) {
        startActivityAndWait()
        device.requireNode("startup_ready")
        device.requireNode("nav_home")
    }

    @Test fun navigationAndScroll() = benchmark.measureRepeated(
        packageName = TARGET,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(
            baselineProfileMode = BaselineProfileMode.Disable, warmupIterations = 3
        ),
        iterations = 10,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            device.requireNode("startup_ready")
            device.requireNode("nav_home").click()
        }
    ) {
        device.navigateAndScroll()
    }
}

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val profile = BaselineProfileRule()
    @Before fun seed() = prepareFixture()

    @Test fun startup() = profile.collect(
        packageName = TARGET,
        includeInStartupProfile = true
    ) {
        pressHome()
        startActivityAndWait()
        device.requireNode("startup_ready")
        device.requireNode("nav_home")
    }

    @Test fun navigation() = profile.collect(packageName = TARGET) {
        pressHome()
        startActivityAndWait()
        device.requireNode("startup_ready")
        device.requireNode("nav_home")
        device.navigateAndScroll()
    }
}
