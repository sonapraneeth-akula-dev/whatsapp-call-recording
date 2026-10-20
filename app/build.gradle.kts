import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import com.android.build.api.artifact.SingleArtifact
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Element
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.soakula.wacr"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.soakula.wacr"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "WACR Debug")
        }
        release {
            resValue("string", "app_name", "WACR")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

abstract class VerifyNoInternet : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:OutputFile
    abstract val verifiedManifest: RegularFileProperty

    @TaskAction
    fun verify() {
        val input = mergedManifest.get().asFile
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
        val elements = factory.newDocumentBuilder().parse(input).getElementsByTagName("*")
        for (index in 0 until elements.length) {
            val element = elements.item(index) as Element
            if (element.localName.startsWith("uses-permission") &&
                element.getAttributeNS("http://schemas.android.com/apk/res/android", "name") == "android.permission.INTERNET"
            ) {
                throw GradleException("INTERNET permission forbidden in the production app")
            }
        }
        val output = verifiedManifest.get().asFile
        output.parentFile.mkdirs()
        input.copyTo(output, overwrite = true)
    }
}

val verifyNoInternet = tasks.register("verifyNoInternet") { group = "verification" }
androidComponents.onVariants { variant ->
    val verify = tasks.register<VerifyNoInternet>(
        "verify${variant.name.replaceFirstChar(Char::uppercaseChar)}NoInternet"
    )
    variant.artifacts.use(verify)
        .wiredWithFiles(VerifyNoInternet::mergedManifest, VerifyNoInternet::verifiedManifest)
        .toTransform(SingleArtifact.MERGED_MANIFEST)
    verifyNoInternet.configure { dependsOn(verify) }
}
tasks.named("check") { dependsOn(verifyNoInternet) }

// ponytail: add Navigation, persistence and DI when a feature consumes them.
dependencies {
    implementation(project(":logging"))
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.tink.android)
    testImplementation(libs.junit)
}
