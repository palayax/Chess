import java.util.Base64
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
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

// Where the app downloads the two model files from on first run (docs/MODEL_DOWNLOAD_DESIGN.md §3.1).
// The owner's public repo palayax/Chess (created 2026-10-07); the URL is this one line. First-run URL = base + release.tag + "/" + file name.
val defaultModelBaseUrl = "https://github.com/palayax/Chess/releases/download/"
// Debug-only override, e.g. -PpalayaModelBaseUrl=http://10.0.2.2:8787/ for scripts/model_test_server.py.
val modelBaseUrlOverride: String? = providers.gradleProperty("palayaModelBaseUrl").orNull?.trim()?.also {
    require(Regex("""https?://[^/\s]+/(\S*/)?""").matches(it)) {
        "-PpalayaModelBaseUrl must be an http(s) URL ending in '/', got: $it"
    }
}

// The Stockfish release this build compiles (vendor/STOCKFISH_VERSION.txt, "Tag:  sf_19"): the one source of truth
// for "Check for updates" to compare the official-stockfish/Stockfish releases against (A4). The file is committed.
val stockfishTag: String = rootProject.file("vendor/STOCKFISH_VERSION.txt").readLines()
    .firstNotNullOfOrNull { Regex("""^\s*Tag:\s*(sf_\d+(?:\.\d+)*)\s*$""").find(it)?.groupValues?.get(1) }
    ?: throw org.gradle.api.GradleException("vendor/STOCKFISH_VERSION.txt has no 'Tag: sf_<n>' line")

android {
    namespace = "net.palaya.chessanalyzer"
    // Google Play requires targetSdk 36 (Android 16) for new apps and updates from 31 Aug 2026.
    compileSdk = 36
    // Same NDK as :engine. :app compiles no C++, but AGP uses this NDK's llvm-strip to strip every
    // .so it packages; left unset, AGP 8.9 looks for its own default NDK (27.0), which is not
    // installed, and silently packages unstripped libraries (libstockfish.so 17 MB instead of 1.6 MB).
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "net.palaya.chessanalyzer"
        minSdk = 26
        targetSdk = 36
        // 1 = the bundled R7 build (dist/PalayaChess-1.0-release.apk, models inside, no network).
        // 2 = the first downloading build (D2f). Every upload to Play must raise it. The model
        // manifest's minVersionCode (`publish_models.sh --min-version-code N`) is compared with it:
        // publish with 2, the first build that can read the manifest at all.
        versionCode = 2
        versionName = "1.1"
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
        // The sherpa-onnx runtime the voice model must be compatible with (D2e reads it for upgrades).
        buildConfigField("String", "SHERPA_ONNX_VERSION", "\"${libs.versions.sherpaOnnx.get()}\"")
        // The Stockfish release compiled into :engine, e.g. "sf_19" (A4: compared with the upstream releases).
        buildConfigField("String", "STOCKFISH_TAG", "\"$stockfishTag\"")
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
        debug {
            val base = modelBaseUrlOverride ?: defaultModelBaseUrl
            buildConfigField("String", "MODEL_BASE_URL", "\"$base\"")
            buildConfigField("String", "MODEL_MANIFEST_URL", "\"${base}models/models.json\"")
        }
        release {
            // Never the debug override (the task graph check below fails a release build that sets it).
            buildConfigField("String", "MODEL_BASE_URL", "\"$defaultModelBaseUrl\"")
            buildConfigField("String", "MODEL_MANIFEST_URL", "\"${defaultModelBaseUrl}models/models.json\"")
            // R8: code shrinking, optimisation and resource shrinking. Keep rules for the JNI
            // bridges (Stockfish via :engine's consumer-rules.pro, sherpa-onnx here) are in
            // proguard-rules.pro; the release build was verified end to end on a device (D1).
            isMinifyEnabled = true
            isShrinkResources = true
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
    // Per-ABI APKs for direct installs (D1). `assembleRelease` writes one APK per ABI
    // (app-arm64-v8a-release.apk is the one for practically every phone) plus the universal
    // app-universal-release.apk with all three. Only for release ASSEMBLE tasks: debug builds and
    // connectedDebugAndroidTest keep the single app-debug.apk the docs and scripts use. Not when a
    // bundle task is in the same invocation either: AGP 8.9's buildReleasePreBundle then fails with
    // "Sequence contains more than one matching element" (seen in D1). Run `:app:bundleRelease` and
    // `:app:assembleRelease` as two separate Gradle invocations to get both. Play splits by ABI itself.
    splits {
        abi {
            val tasks = gradle.startParameter.taskNames
            isEnable = tasks.any { it.contains("assembleRelease", ignoreCase = true) } &&
                tasks.none { it.contains("bundle", ignoreCase = true) }
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }
    // The models are not in the app APK (D2a); the androidTest APK carries them as seed assets (D2d,
    // ~201 MB since D2f, see "Seed assets" below), and the default install timeout is too short for that push.
    installation { timeOutInMs = 600_000 }
    // The seed assets are stored, not deflated: no 201 MB deflate on every test build, and the seed
    // tests prove "stored" with openFd(). The app APK has no such files (checked with unzip -l, D2d).
    androidResources { noCompress += listOf(".nnue", ".tar", ".gz", ".seed") }
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
// generateModelPins (D2a, docs/MODEL_DOWNLOAD_DESIGN.md §3.1). The 158 MB Kokoro voice tar is NOT in
// the APK any more: a fresh install downloads it once (ModelSetup) and VoiceStore checks it against
// these pins. The task reads vendor/models/MODELS.lock, checks it is well formed, and writes
// GeneratedModelPins (voice file name, size and SHA-256, and the GitHub release tag that holds both
// model files). It does NOT need the tar itself; if the tar IS present under vendor/models/ it is
// verified against the lock too (developer safety). :engine has the same task for the net.
//
// The lock path can be overridden with -PpalayaModelsLock=<file> (used only to prove that an
// inconsistent lock fails the build without touching the real one; see RUN_LOG D2a).
// ---------------------------------------------------------------------------
val modelsLockFile = providers.gradleProperty("palayaModelsLock").orNull?.let { rootProject.file(it) }
    ?: rootProject.file("vendor/models/MODELS.lock")
val appModelDir = rootProject.file("vendor/models/app-assets")
val generatedModelPinsDir = layout.buildDirectory.dir("generated/source/modelpins/kotlin")
// D2e: the public half of the maintainers' manifest-signing key (P-256, X.509 SubjectPublicKeyInfo DER,
// committed). The private half is keystore/models-signing.pem, never in the repo (*.pem is gitignored);
// see docs/PUBLISHING.md "Model manifest signing". Losing it means no model updates until an app update
// ships a new public key here.
val manifestPublicKeyFile = rootProject.file("vendor/models/manifest_public_key.der")

val generateModelPins = tasks.register("generateModelPins") {
    description = "Checks vendor/models/MODELS.lock and writes GeneratedModelPins.kt"
    inputs.file(modelsLockFile)
    inputs.file(manifestPublicKeyFile)
    inputs.files(fileTree(appModelDir))
    outputs.dir(generatedModelPinsDir)
    doLast {
        fun fail(what: String): Nothing = throw org.gradle.api.GradleException(
            "generateModelPins: $what (lock: $modelsLockFile)"
        )
        if (!modelsLockFile.isFile) fail("MODELS.lock not found")
        val lock = Properties().apply { modelsLockFile.inputStream().use { load(it) } }
        fun prop(key: String): String = lock.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
            ?: fail("$key is missing or empty; run scripts/fetch_models.sh, which pins it")

        val sha = prop("kokoro.tar.sha256").lowercase()
        if (!Regex("[0-9a-f]{64}").matches(sha)) fail("kokoro.tar.sha256 is not 64 hex digits: $sha")
        val size = prop("kokoro.tar.size").toLongOrNull()?.takeIf { it > 0 } ?: fail("kokoro.tar.size is not a positive number")
        val archiveUrl = prop("kokoro.archive.url")
        val tarName = archiveUrl.substringAfterLast('/').removeSuffix(".bz2")
        if (!Regex("""[A-Za-z0-9._-]+\.tar""").matches(tarName)) fail("cannot derive a .tar file name from $archiveUrl")
        // A4: the upstream archive this voice was made from, so "Check for updates" can tell whether k2-fsa
        // re-published that file (name, size and SHA-256 are all pinned by fetch_models.sh).
        val archiveName = archiveUrl.substringAfterLast('/')
        if (!Regex("""[A-Za-z0-9._-]+\.tar\.bz2""").matches(archiveName)) fail("cannot derive a .tar.bz2 file name from $archiveUrl")
        val archiveSha = prop("kokoro.archive.sha256").lowercase()
        if (!Regex("[0-9a-f]{64}").matches(archiveSha)) fail("kokoro.archive.sha256 is not 64 hex digits: $archiveSha")
        val archiveSize = prop("kokoro.archive.size").toLongOrNull()?.takeIf { it > 0 } ?: fail("kokoro.archive.size is not a positive number")
        // D2f: what the app downloads is the tar gzipped (`gzip -9 -n`), pinned on its own.
        val gzName = "$tarName.gz"
        val gzSha = prop("kokoro.targz.sha256").lowercase()
        if (!Regex("[0-9a-f]{64}").matches(gzSha)) fail("kokoro.targz.sha256 is not 64 hex digits: $gzSha")
        val gzSize = prop("kokoro.targz.size").toLongOrNull()?.takeIf { it > 0 } ?: fail("kokoro.targz.size is not a positive number")
        if (gzSize >= size) fail("kokoro.targz.size ($gzSize) is not smaller than kokoro.tar.size ($size)")
        val tag = prop("release.tag")
        if (!Regex("""models-\d{4}\.\d{2}(\.\d+)?""").matches(tag)) fail("release.tag $tag does not match models-YYYY.MM[.n]")
        // The net fields belong to :engine's task, but a lock without them is inconsistent for the app too.
        val netSha = prop("net.sha256").lowercase()
        if (!Regex("[0-9a-f]{64}").matches(netSha)) fail("net.sha256 is not 64 hex digits: $netSha")

        // Developer safety only: the build never needs the file, but a present one must match the pin
        // (the .tar.gz, and the tar it inflates to: a lock whose two voice pins disagree fails here).
        val gz = File(appModelDir, "tts/$gzName")
        if (gz.isFile) {
            if (gz.length() != gzSize) fail("${gz.path} is ${gz.length()} bytes, the lock pins $gzSize")
            val gzDigest = MessageDigest.getInstance("SHA-256")
            val tarDigest = MessageDigest.getInstance("SHA-256")
            var tarBytes = 0L
            GZIPInputStream(DigestInputStream(gz.inputStream().buffered(1 shl 20), gzDigest), 1 shl 16).use { input ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buffer)
                    if (n == -1) break
                    tarDigest.update(buffer, 0, n)
                    tarBytes += n
                }
            }
            val actualGz = gzDigest.digest().joinToString("") { "%02x".format(it) }
            if (actualGz != gzSha) fail("${gz.path} hashes to $actualGz, the lock pins $gzSha")
            val actualTar = tarDigest.digest().joinToString("") { "%02x".format(it) }
            if (tarBytes != size || actualTar != sha) fail("${gz.path} inflates to $tarBytes bytes / $actualTar, the lock pins $size / $sha")
        }
        val stale = File(appModelDir, "tts").listFiles { f -> f.isFile && f.name != gzName }.orEmpty()
        if (stale.isNotEmpty()) fail("stale files next to the voice in vendor/models: ${stale.joinToString { it.name }}")

        // The manifest-signing public key: exactly a P-256 SubjectPublicKeyInfo (91 bytes, fixed header).
        if (!manifestPublicKeyFile.isFile) fail("${manifestPublicKeyFile.path} is missing (it is committed; see docs/PUBLISHING.md)")
        val der = manifestPublicKeyFile.readBytes()
        val p256Header = "3059301306072a8648ce3d020106082a8648ce3d030107034200"
        val derHex = der.joinToString("") { "%02x".format(it) }
        if (der.size != 91 || !derHex.startsWith(p256Header)) {
            fail("${manifestPublicKeyFile.name} is not a P-256 public key in X.509 DER (${der.size} bytes)")
        }
        val derBase64 = Base64.getEncoder().encodeToString(der)
        val keySha = MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }

        val pkgDir = generatedModelPinsDir.get().asFile.resolve("net/palaya/chessanalyzer/data/models")
        pkgDir.mkdirs()
        pkgDir.resolve("GeneratedModelPins.kt").writeText(
            """
            |// GENERATED FILE - do not edit by hand.
            |// Written by the :app module's generateModelPins Gradle task from vendor/models/MODELS.lock
            |// (see app/build.gradle.kts and docs/MODEL_DOWNLOAD_DESIGN.md §3.1).
            |package net.palaya.chessanalyzer.data.models
            |
            |object GeneratedModelPins {
            |    /** The Kokoro voice archive the app downloads: the tar below, gzipped (`gzip -9 -n`, D2f). */
            |    const val VOICE_FILE_NAME: String = "$gzName"
            |    const val VOICE_DOWNLOAD_SIZE_BYTES: Long = ${gzSize}L
            |    const val VOICE_DOWNLOAD_SHA256: String = "$gzSha"
            |    /** The tar inside it: what the unpacked stream is checked against, and the installed voice's marker. */
            |    const val VOICE_TAR_NAME: String = "$tarName"
            |    const val VOICE_SIZE_BYTES: Long = ${size}L
            |    const val VOICE_SHA256: String = "$sha"
            |    /** The upstream archive (k2-fsa/sherpa-onnx, release tts-models) the tar was made from, for the A4 upstream check. */
            |    const val VOICE_UPSTREAM_ARCHIVE_NAME: String = "$archiveName"
            |    const val VOICE_UPSTREAM_ARCHIVE_SIZE_BYTES: Long = ${archiveSize}L
            |    const val VOICE_UPSTREAM_ARCHIVE_SHA256: String = "$archiveSha"
            |    /** The GitHub release (immutable tag) holding both model files for a first-run download. */
            |    const val RELEASE_TAG: String = "$tag"
            |    /**
            |     * The public key that signs models.json (ECDSA P-256, X.509 DER, base64), from
            |     * vendor/models/manifest_public_key.der. SHA-256 of the DER: $keySha
            |     */
            |    const val MANIFEST_PUBLIC_KEY_DER_BASE64: String = "$derBase64"
            |    const val MANIFEST_PUBLIC_KEY_SHA256: String = "$keySha"
            |}
            |""".trimMargin()
        )
    }
}
android.sourceSets.getByName("main").kotlin.srcDir(generatedModelPinsDir)
tasks.named("preBuild") { dependsOn(generateModelPins) }
androidComponents {
    onVariants {
        tasks.matching { it.name.contains("Kotlin") && it.name.contains("Compile", ignoreCase = true) }
            .configureEach { dependsOn(generateModelPins) }
    }
}

// ---------------------------------------------------------------------------
// FaultHttpServer (app/src/sharedTest) is shared by the host tests and the instrumented tests
// (docs/MODEL_DOWNLOAD_DESIGN.md §5, §6.1).
// ---------------------------------------------------------------------------
android.sourceSets.getByName("test").kotlin.srcDir("src/sharedTest/kotlin")
android.sourceSets.getByName("androidTest").kotlin.srcDir("src/sharedTest/kotlin")

// ---------------------------------------------------------------------------
// Seed assets for the instrumented tests (D2d, docs/MODEL_DOWNLOAD_DESIGN.md §6.2). The androidTest APK,
// never the app APK, carries both model files from vendor/models/ (fetched by scripts/fetch_models.sh):
// `nnue/<net>` from engine-assets and `tts/<voice .tar.gz>.seed` (the file setup downloads, D2f, copied by
// prepareTestSeedAssets: AAPT gunzips any asset whose name ends in ".gz" and drops the suffix, so the test APK
// held the 158 MB tar instead of the downloaded bytes until the ".seed" suffix was added). TestApp.ensureSetUp() installs
// them through NetStore.installVerified and VoiceStore.installFromStream, the tails the download uses,
// and the setup tests serve them over the in-process FaultHttpServer. No test needs a host server, so the
// suite also runs on a physical phone. checkTestSeedAssets stops the test build early, with the fix,
// when the files were never fetched (generateModelPins verifies them against the lock when present).
// ---------------------------------------------------------------------------
val engineModelDirForTests = rootProject.file("vendor/models/engine-assets")
val testSeedAssetsDir = layout.buildDirectory.dir("generated/testSeedAssets").get().asFile
val prepareTestSeedAssets = tasks.register<Sync>("prepareTestSeedAssets") {
    description = "Copies the voice .tar.gz into the androidTest assets as tts/<name>.seed (AAPT would gunzip a .gz asset)"
    from(File(appModelDir, "tts")) {
        include("*.tar.gz")
        rename { "$it.seed" }
    }
    into(File(testSeedAssetsDir, "tts"))
}
android.sourceSets.getByName("androidTest").assets.srcDirs(engineModelDirForTests, testSeedAssetsDir)
val checkTestSeedAssets = tasks.register("checkTestSeedAssets") {
    description = "Fails the androidTest build when the two seed model files under vendor/models/ are missing"
    doLast {
        val nets = File(engineModelDirForTests, "nnue").listFiles { f -> f.isFile && f.name.endsWith(".nnue") }.orEmpty()
        val tars = File(appModelDir, "tts").listFiles { f -> f.isFile && f.name.endsWith(".tar.gz") }.orEmpty()
        if (nets.isEmpty() || tars.isEmpty()) {
            throw org.gradle.api.GradleException(
                "The instrumented tests need both model files as seed assets " +
                    "(vendor/models/engine-assets/nnue/*.nnue and vendor/models/app-assets/tts/*.tar.gz). " +
                    "Run scripts/fetch_models.sh from the repo root first."
            )
        }
    }
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("AndroidTestAssets") }
    .configureEach { dependsOn(checkTestSeedAssets, generateModelPins, prepareTestSeedAssets) }

// ---------------------------------------------------------------------------
// -PpalayaModelBaseUrl is a DEBUG-only override (a debug build pointed at scripts/model_test_server.py,
// e.g. http://10.0.2.2:8787/). A release build must never carry it: the release field always holds the
// default, and a release task in the same invocation fails before anything runs.
// ---------------------------------------------------------------------------
gradle.taskGraph.whenReady {
    if (modelBaseUrlOverride != null && allTasks.any { it.project == project && it.name.contains("Release") }) {
        throw org.gradle.api.GradleException(
            "-PpalayaModelBaseUrl is for debug builds only; remove it to build a release (it would never be used there)."
        )
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
    // On-device neural TTS (Apache 2.0) — see VoiceStore/NeuralTtsProvider.
    // JitPack serves this multi-artifact build under an aggregator POM at
    // com.github.k2-fsa:sherpa-onnx that depends on BOTH the Android AAR module and a
    // "sherpa-onnx-jvm" desktop jar containing the same Kotlin classes (duplicate-class build
    // failure) — depending directly on the AAR's own sub-module coordinate
    // (com.github.k2-fsa.sherpa-onnx:sherpa-onnx) bypasses that aggregator and pulls in only the
    // Android AAR (with its bundled arm64-v8a/armeabi-v7a/x86_64/x86 native libs).
    implementation(libs.sherpa.onnx)
    // Pure-Java tar extraction for the downloaded voice model (Apache 2.0).
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
    // org.json on the host: the Android stub throws "not mocked"; ModelManifest and the activation journal
    // parse with org.json and are host-tested (docs/MODEL_DOWNLOAD_DESIGN.md §3.2).
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.espresso)
}
