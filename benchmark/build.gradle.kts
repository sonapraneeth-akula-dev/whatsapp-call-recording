import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.soakula.wacr.benchmark.tests"
    compileSdk = 36
    targetProjectPath = ":app"
    defaultConfig {
        minSdk = 33
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["disableAnalytics"] = "true"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes {
        create("benchmark") {
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

androidComponents.beforeVariants { it.enable = it.buildType == "benchmark" }
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation(libs.androidx.benchmark)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.junit)
    constraints {
        implementation(libs.wire.runtime) { because("CVE-2026-63126 in Macrobenchmark trace decoding") }
    }
}
