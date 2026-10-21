buildscript {
    dependencies {
        classpath(platform(libs.netty.bom))
        constraints {
            add("classpath", libs.bouncycastle.prov) { because("CVE-2026-8763 and CVE-2026-13506") }
            add("classpath", libs.bouncycastle.pkix) { because("CVE-2026-5588; align Bouncy Castle modules") }
            add("classpath", libs.bouncycastle.util) { because("Align Bouncy Castle modules") }
            add("classpath", libs.jdom) { because("CVE-2021-33813") }
            add("classpath", libs.jose4j) { because("CVE-2024-29371") }
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.kapt) apply false
    alias(libs.plugins.room) apply false
}
