import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "net.palaya.chessanalyzer.engine"
    compileSdk = 36
    // NDK r28+ links with 16 KB ELF LOAD-segment alignment by default (Play requires 16 KB page-size
    // support for apps targeting 35+ with native code). CMakeLists.txt also passes
    // -Wl,-z,max-page-size=16384 explicitly, so a downgrade cannot silently undo it.
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 26
        // Keeps NativeBridge (the JNI entry points libstockfish.so binds to by name) through R8 in
        // any app that consumes this library. See consumer-rules.pro.
        consumerProguardFiles("consumer-rules.pro")
        // arm64-v8a and x86_64 are known-good (see engine/src/main/cpp/CMakeLists.txt).
        // armeabi-v7a is included too — see the module's build report for whether it
        // ultimately built cleanly or had to be dropped.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                // minSdk (26) drives the NDK platform level; Stockfish itself has
                // no Android-API dependency (it's stdin/stdout console code), so
                // building at the module's own minSdk is correct.
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DANDROID_ARM_NEON=ON"
                )
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}
dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

// ---------------------------------------------------------------------------
// generateNetworkConstants: derives the NNUE net filename Kotlin needs
// (NetStore installs/verifies it under filesDir/nets/) from the ONE authoritative place
// that name is defined — vendor/Stockfish/src/evaluate.h's
// `#define EvalFileDefaultName "..."` — instead of hand-copying it into
// Kotlin, where it could silently drift out of sync on a future Stockfish
// version bump. See NetStore.kt for how the generated constant is used.
// ---------------------------------------------------------------------------
val vendorEvaluateHeader = rootProject.file("vendor/Stockfish/src/evaluate.h")
val generatedNetConstDir = layout.buildDirectory.dir("generated/source/netconst/kotlin")

val generateNetworkConstants = tasks.register("generateNetworkConstants") {
    inputs.file(vendorEvaluateHeader)
    outputs.dir(generatedNetConstDir)
    doLast {
        if (!vendorEvaluateHeader.exists()) {
            throw org.gradle.api.GradleException(
                "vendor/Stockfish/src/evaluate.h not found. Run scripts/fetch_stockfish.sh " +
                    "from the repo root to vendor the Stockfish source first."
            )
        }
        val text = vendorEvaluateHeader.readText()
        val match = Regex("""#define\s+EvalFileDefaultName\s+"([^"]+)"""").find(text)
            ?: throw org.gradle.api.GradleException(
                "Could not find '#define EvalFileDefaultName \"...\"' in $vendorEvaluateHeader " +
                    "— did the Stockfish source layout change?"
            )
        val netName = match.groupValues[1]
        val pkgDir = generatedNetConstDir.get().asFile
            .resolve("net/palaya/chessanalyzer/engine")
        pkgDir.mkdirs()
        pkgDir.resolve("GeneratedNetworkConstants.kt").writeText(
            """
            |// GENERATED FILE — do not edit by hand.
            |// Derived from vendor/Stockfish/src/evaluate.h's
            |// `#define EvalFileDefaultName "..."` by the :engine module's
            |// generateNetworkConstants Gradle task (see engine/build.gradle.kts),
            |// so the net filename has a single source of truth instead of being
            |// duplicated by hand into Kotlin.
            |package net.palaya.chessanalyzer.engine
            |
            |internal object GeneratedNetworkConstants {
            |    const val EVAL_FILE_DEFAULT_NAME: String = "$netName"
            |}
            |""".trimMargin()
        )
    }
}

androidComponents {
    onVariants {
        tasks.matching { it.name.contains("Kotlin") && it.name.contains("Compile", ignoreCase = true) }
            .configureEach { dependsOn(generateNetworkConstants) }
    }
}
tasks.named("preBuild") { dependsOn(generateNetworkConstants) }

android.sourceSets.getByName("main").kotlin.srcDir(generatedNetConstDir)

// ---------------------------------------------------------------------------
// generateModelPins (D2a, docs/MODEL_DOWNLOAD_DESIGN.md §3.1). The 98.5 MB net is NOT in the APK any
// more: a fresh install downloads it once and NetStore verifies it against these pins. The task reads
// vendor/models/MODELS.lock and evaluate.h, checks they are consistent, and writes GeneratedNetPins
// (size, full SHA-256, NNUE architecture hash and file version). It does NOT need the net itself, so a
// clean clone builds without scripts/fetch_models.sh. If the net IS present under vendor/models/ it is
// verified against the lock too (developer safety: a stale net would be published or seeded into tests).
//
// The lock path can be overridden with -PpalayaModelsLock=<file> (used only to prove that an
// inconsistent lock fails the build without touching the real one; see RUN_LOG D2a).
// ---------------------------------------------------------------------------
val modelsLockFile = providers.gradleProperty("palayaModelsLock").orNull?.let { rootProject.file(it) }
    ?: rootProject.file("vendor/models/MODELS.lock")
val nnueCommonHeader = rootProject.file("vendor/Stockfish/src/nnue/nnue_common.h")
val engineModelDir = rootProject.file("vendor/models/engine-assets")
val generatedNetPinsDir = layout.buildDirectory.dir("generated/source/netpins/kotlin")

val generateModelPins = tasks.register("generateModelPins") {
    description = "Checks vendor/models/MODELS.lock against evaluate.h and writes GeneratedNetPins.kt"
    inputs.file(vendorEvaluateHeader)
    inputs.file(modelsLockFile)
    inputs.files(nnueCommonHeader)
    inputs.files(fileTree(engineModelDir))
    outputs.dir(generatedNetPinsDir)
    doLast {
        fun fail(what: String): Nothing = throw org.gradle.api.GradleException(
            "generateModelPins: $what (lock: $modelsLockFile)"
        )
        if (!modelsLockFile.isFile) fail("MODELS.lock not found")
        if (!vendorEvaluateHeader.exists()) fail("vendor/Stockfish/src/evaluate.h not found; run scripts/fetch_stockfish.sh")
        val lock = Properties().apply { modelsLockFile.inputStream().use { load(it) } }
        fun prop(key: String): String = lock.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
            ?: fail("$key is missing or empty; run scripts/fetch_models.sh, which pins it")

        val size = prop("net.size").toLongOrNull()?.takeIf { it > 0 } ?: fail("net.size is not a positive number")
        val sha = prop("net.sha256").lowercase()
        if (!Regex("[0-9a-f]{64}").matches(sha)) fail("net.sha256 is not 64 hex digits: $sha")
        val arch = prop("net.arch_hash").lowercase()
        if (!Regex("[0-9a-f]{8}").matches(arch)) fail("net.arch_hash is not 8 hex digits: $arch")
        val version = prop("net.version").lowercase()
        if (!Regex("0x[0-9a-f]{8}").matches(version)) fail("net.version is not 0x plus 8 hex digits: $version")
        val tag = prop("release.tag")
        if (!Regex("""models-\d{4}\.\d{2}(\.\d+)?""").matches(tag)) fail("release.tag $tag does not match models-YYYY.MM[.n]")

        val netName = Regex("""#define\s+EvalFileDefaultName\s+"([^"]+)"""")
            .find(vendorEvaluateHeader.readText())?.groupValues?.get(1)
            ?: fail("cannot read EvalFileDefaultName from evaluate.h")
        val prefix = Regex("""nn-([0-9a-f]{12})\.nnue""").matchEntire(netName)?.groupValues?.get(1)
            ?: fail("net name $netName does not encode a 12-hex SHA-256 prefix")
        if (!sha.startsWith(prefix)) fail("net.sha256 $sha does not start with $prefix, the prefix in the net's name $netName")

        // The file version the compiled engine accepts is a literal in nnue_common.h: the pin must agree.
        if (nnueCommonHeader.isFile) {
            val compiled = Regex("""constexpr\s+u32\s+Version\s*=\s*0x([0-9A-Fa-f]{8})u?""")
                .find(nnueCommonHeader.readText())?.groupValues?.get(1)?.lowercase()
                ?: fail("cannot read Version from nnue_common.h")
            if (version != "0x$compiled") fail("net.version $version differs from the engine's Version 0x$compiled (nnue_common.h)")
        }

        // Developer safety only: the build never needs the file, but a present one must match the pin.
        val net = File(engineModelDir, "nnue/$netName")
        if (net.isFile) {
            if (net.length() != size) fail("${net.path} is ${net.length()} bytes, the lock pins $size")
            val digest = MessageDigest.getInstance("SHA-256")
            net.inputStream().use { input ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buffer)
                    if (n == -1) break
                    digest.update(buffer, 0, n)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != sha) fail("${net.path} hashes to $actual, the lock pins $sha")
            val header = net.inputStream().use { it.readNBytes(8) }
            fun le32(off: Int) = (0..3).joinToString("") { "%02x".format(header[off + 3 - it]) }
            if ("0x" + le32(0) != version) fail("${net.path} has NNUE version 0x${le32(0)}, the lock pins $version")
            if (le32(4) != arch) fail("${net.path} has architecture hash ${le32(4)}, the lock pins $arch")
        }
        val stale = File(engineModelDir, "nnue").listFiles { f -> f.isFile && f.name != netName }.orEmpty()
        if (stale.isNotEmpty()) fail("stale files next to the net in vendor/models: ${stale.joinToString { it.name }}")

        val pkgDir = generatedNetPinsDir.get().asFile.resolve("net/palaya/chessanalyzer/engine")
        pkgDir.mkdirs()
        pkgDir.resolve("GeneratedNetPins.kt").writeText(
            """
            |// GENERATED FILE - do not edit by hand.
            |// Written by the :engine module's generateModelPins Gradle task from vendor/models/MODELS.lock
            |// (see engine/build.gradle.kts and docs/MODEL_DOWNLOAD_DESIGN.md §3.1).
            |package net.palaya.chessanalyzer.engine
            |
            |internal object GeneratedNetPins {
            |    const val NET_SIZE_BYTES: Long = ${size}L
            |    const val NET_SHA256: String = "$sha"
            |    /** Bytes 4..7 of the net, little-endian: Stockfish's architecture hash. */
            |    const val NET_ARCH_HASH: Long = 0x${arch}L
            |    /** Bytes 0..3 of the net, little-endian: the NNUE file version. */
            |    const val NET_VERSION: Long = ${version}L
            |}
            |""".trimMargin()
        )
    }
}
android.sourceSets.getByName("main").kotlin.srcDir(generatedNetPinsDir)
tasks.named("preBuild") { dependsOn(generateModelPins) }
androidComponents {
    onVariants {
        tasks.matching { it.name.contains("Kotlin") && it.name.contains("Compile", ignoreCase = true) }
            .configureEach { dependsOn(generateModelPins) }
    }
}

// ---------------------------------------------------------------------------
// Seed asset for the instrumented tests (D2d, docs/MODEL_DOWNLOAD_DESIGN.md §6.2). The net is in no
// APK the user installs; this module's androidTest APK carries a copy (`nnue/<name>`, from
// vendor/models/engine-assets, fetched by scripts/fetch_models.sh) so TestNet can install it into the
// test package's filesDir through NetStore.installVerified, the download's own tail. The androidTest
// source set never reaches a consumer of this library. Stored, not deflated (noCompress): no 98 MB
// deflate on every test build, and NetStoreInstrumentedTest proves "stored" with openFd().
// checkTestSeedAssets stops the test build early, with the fix, when the net was never fetched
// (generateModelPins already verifies it against the lock when it is there).
// ---------------------------------------------------------------------------
android.androidResources.noCompress += listOf(".nnue")
android.sourceSets.getByName("androidTest").assets.srcDir(engineModelDir)
val checkTestSeedAssets = tasks.register("checkTestSeedAssets") {
    description = "Fails the androidTest build when vendor/models/engine-assets/nnue/<net> is missing"
    val net = File(engineModelDir, "nnue")
    doLast {
        val nets = net.listFiles { f -> f.isFile && f.name.endsWith(".nnue") }.orEmpty()
        if (nets.isEmpty()) {
            throw org.gradle.api.GradleException(
                "The instrumented tests need the NNUE net as a seed asset (${net.path}). " +
                    "Run scripts/fetch_models.sh from the repo root first."
            )
        }
    }
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("AndroidTestAssets") }
    .configureEach { dependsOn(checkTestSeedAssets, generateModelPins) }
