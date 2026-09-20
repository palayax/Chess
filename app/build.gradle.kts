import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Release signing credentials live in keystore.properties, which is gitignored. When it is
// absent (CI, a fresh clone, anyone else's machine) the release build falls back to unsigned
// rather than failing the whole configuration phase.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseSigning = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "net.palaya.chessanalyzer"
    compileSdk = 34
    defaultConfig {
        applicationId = "net.palaya.chessanalyzer"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Keeps evidence-producing tools out of the automated suite — see ManualEvidenceTool's
        // doc for why they cannot usefully run under Gradle at all (it uninstalls the app, and
        // with it every artifact they wrote). This is a runner *filter*, not a skip: excluded
        // methods never appear in the result XML, so `skipped="0"` stays meaningful.
        testInstrumentationRunnerArguments["notAnnotation"] = "net.palaya.chessanalyzer.ManualEvidenceTool"
        // Match :engine's ABI set (see engine/build.gradle.kts) so the sherpa-onnx AAR's bundled
        // x86 native libs (arm64-v8a/armeabi-v7a/x86_64/x86 are all present in the upstream AAR)
        // don't get packaged for an ABI nothing else in this app supports.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // buildConfig must be opted into under AGP 8; the About screen reads BuildConfig.VERSION_NAME.
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}
// ---------------------------------------------------------------------------
// generateEngineVersionConstants: surfaces vendor/STOCKFISH_VERSION.txt (tag + commit) as a
// Kotlin constant for the Settings screen, the same pattern :engine uses for the net filename
// (see engine/build.gradle.kts's generateNetworkConstants) — one source of truth in the
// vendored file instead of a hand-copied version string that can silently drift.
// ---------------------------------------------------------------------------
val stockfishVersionFile = rootProject.file("vendor/STOCKFISH_VERSION.txt")
val generatedVersionConstDir = layout.buildDirectory.dir("generated/source/engineversion/kotlin")

val generateEngineVersionConstants = tasks.register("generateEngineVersionConstants") {
    inputs.file(stockfishVersionFile)
    outputs.dir(generatedVersionConstDir)
    doLast {
        val label = if (stockfishVersionFile.exists()) {
            val text = stockfishVersionFile.readText()
            val tag = Regex("""Tag:\s*(\S+)""").find(text)?.groupValues?.get(1) ?: "unknown"
            val commit = Regex("""Commit:\s*(\S+)""").find(text)?.groupValues?.get(1)?.take(7) ?: ""
            "Stockfish ($tag${if (commit.isNotEmpty()) " @ $commit" else ""})"
        } else {
            "Stockfish (version unknown)"
        }
        val pkgDir = generatedVersionConstDir.get().asFile
            .resolve("net/palaya/chessanalyzer/data")
        pkgDir.mkdirs()
        pkgDir.resolve("GeneratedEngineVersion.kt").writeText(
            """
            |// GENERATED FILE — do not edit by hand.
            |// Derived from vendor/STOCKFISH_VERSION.txt by the :app module's
            |// generateEngineVersionConstants Gradle task (see app/build.gradle.kts).
            |package net.palaya.chessanalyzer.data
            |
            |internal object GeneratedEngineVersion {
            |    const val LABEL: String = "$label"
            |}
            |""".trimMargin()
        )
    }
}
androidComponents {
    onVariants {
        tasks.matching { it.name.contains("Kotlin") && it.name.contains("Compile", ignoreCase = true) }
            .configureEach { dependsOn(generateEngineVersionConstants) }
    }
}
tasks.named("preBuild") { dependsOn(generateEngineVersionConstants) }
android.sourceSets.getByName("main").kotlin.srcDir(generatedVersionConstDir)

dependencies {
    implementation(project(":core"))
    implementation(project(":engine"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    // On-device neural TTS (Apache 2.0) — see VoiceModelProvisioner/NeuralTtsProvider.
    // JitPack serves this multi-artifact build under an aggregator POM at
    // com.github.k2-fsa:sherpa-onnx that depends on BOTH the Android AAR module and a
    // "sherpa-onnx-jvm" desktop jar containing the same Kotlin classes (duplicate-class build
    // failure) — depending directly on the AAR's own sub-module coordinate
    // (com.github.k2-fsa.sherpa-onnx:sherpa-onnx) bypasses that aggregator and pulls in only the
    // Android AAR (with its bundled arm64-v8a/armeabi-v7a/x86_64/x86 native libs).
    implementation(libs.sherpa.onnx)
    // Pure-Java tar+bzip2 extraction for the downloaded voice-model archives (Apache 2.0) — no
    // native/system bzip2 binary is available on Android, and the model archives are .tar.bz2.
    implementation(libs.commons.compress)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.espresso)
}
