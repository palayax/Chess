import java.security.MessageDigest
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
    // The NNUE net (from :engine's assets) and the Kokoro voice tar are bundled and stored
    // UNCOMPRESSED: no inflate cost on first run, and debug builds skip deflating 257 MB. Compression
    // is decided when the final APK is packaged, so this has to be set here even for the net that
    // lives in :engine. Never use "" (that would also store the 32 MB classes.dex).
    androidResources { noCompress += listOf(".nnue", ".tar") }
    // Every connectedDebugAndroidTest pushes a ~371 MB APK; the default install timeout is too short for it.
    installation { timeOutInMs = 600_000 }
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

// ---------------------------------------------------------------------------
// Bundled Kokoro voice (docs/BUNDLED_MODELS_DESIGN.md). The 158 MB plain tar is not committed: it is
// fetched by scripts/fetch_models.sh into vendor/models/app-assets/tts/ and pinned by
// vendor/models/MODELS.lock. verifyBundledModels fails the build loudly if it is missing or has the
// wrong size or SHA-256, and writes GeneratedBundledVoiceConstants (the pinned hash, size and asset
// path) so BundledVoiceInstaller never restates them by hand. (:engine has the same task for the net.)
// ---------------------------------------------------------------------------
val modelsLockFile = rootProject.file("vendor/models/MODELS.lock")
val appAssetsDir = rootProject.file("vendor/models/app-assets")
val generatedVoiceConstDir = layout.buildDirectory.dir("generated/source/bundledvoice/kotlin")

val verifyBundledModels = tasks.register("verifyBundledModels") {
    inputs.file(modelsLockFile)
    inputs.files(fileTree(appAssetsDir))
    outputs.dir(generatedVoiceConstDir)
    doLast {
        fun fail(what: String): Nothing = throw org.gradle.api.GradleException(
            "$what. Run scripts/fetch_models.sh from the repo root (it downloads and verifies the bundled models)."
        )
        if (!modelsLockFile.exists()) fail("vendor/models/MODELS.lock not found")
        val lock = Properties().apply { modelsLockFile.inputStream().use { load(it) } }
        val expectedSha = lock.getProperty("kokoro.tar.sha256")?.trim()?.lowercase() ?: fail("MODELS.lock has no kokoro.tar.sha256")
        val expectedSize = lock.getProperty("kokoro.tar.size")?.trim()?.toLongOrNull() ?: fail("MODELS.lock has no kokoro.tar.size")
        val archiveUrl = lock.getProperty("kokoro.archive.url")?.trim() ?: fail("MODELS.lock has no kokoro.archive.url")
        val tarName = archiveUrl.substringAfterLast('/').removeSuffix(".bz2")
        if (!tarName.endsWith(".tar")) fail("Cannot derive the tar name from $archiveUrl")
        val tar = File(appAssetsDir, "tts/$tarName")
        if (!tar.isFile) fail("Bundled voice archive missing: ${tar.path}")
        if (tar.length() != expectedSize) fail("Bundled voice archive ${tar.name} is ${tar.length()} bytes, MODELS.lock pins $expectedSize")
        val stale = File(appAssetsDir, "tts").listFiles { f -> f.isFile && f.name != tarName }.orEmpty()
        if (stale.isNotEmpty()) fail("Stale files would be bundled next to the voice archive: ${stale.joinToString { it.name }}")
        val digest = MessageDigest.getInstance("SHA-256")
        tar.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                digest.update(buffer, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expectedSha) fail("Bundled voice archive ${tar.name} hashes to $actual, MODELS.lock pins $expectedSha")

        val pkgDir = generatedVoiceConstDir.get().asFile.resolve("net/palaya/chessanalyzer/video")
        pkgDir.mkdirs()
        pkgDir.resolve("GeneratedBundledVoiceConstants.kt").writeText(
            """
            |// GENERATED FILE - do not edit by hand.
            |// Written by the :app module's verifyBundledModels Gradle task from
            |// vendor/models/MODELS.lock (see app/build.gradle.kts).
            |package net.palaya.chessanalyzer.video
            |
            |internal object GeneratedBundledVoiceConstants {
            |    const val TAR_SHA256: String = "$expectedSha"
            |    const val TAR_SIZE_BYTES: Long = ${expectedSize}L
            |    const val ASSET_PATH: String = "tts/$tarName"
            |}
            |""".trimMargin()
        )
    }
}
android.sourceSets.getByName("main").assets.srcDir(appAssetsDir)
android.sourceSets.getByName("main").kotlin.srcDir(generatedVoiceConstDir)
tasks.named("preBuild") { dependsOn(verifyBundledModels) }
androidComponents {
    onVariants {
        tasks.matching { it.name.contains("Kotlin") && it.name.contains("Compile", ignoreCase = true) }
            .configureEach { dependsOn(verifyBundledModels) }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":engine"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    // On-device neural TTS (Apache 2.0) — see VoiceModelProvisioner/NeuralTtsProvider.
    // JitPack serves this multi-artifact build under an aggregator POM at
    // com.github.k2-fsa:sherpa-onnx that depends on BOTH the Android AAR module and a
    // "sherpa-onnx-jvm" desktop jar containing the same Kotlin classes (duplicate-class build
    // failure) — depending directly on the AAR's own sub-module coordinate
    // (com.github.k2-fsa.sherpa-onnx:sherpa-onnx) bypasses that aggregator and pulls in only the
    // Android AAR (with its bundled arm64-v8a/armeabi-v7a/x86_64/x86 native libs).
    implementation(libs.sherpa.onnx)
    // Pure-Java tar extraction for the bundled voice model (Apache 2.0).
    implementation(libs.commons.compress)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)
    // Semantics tests (AccessibilitySemanticsTest): read headings, states and actions that a uiautomator dump cannot show.
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.espresso)
}
