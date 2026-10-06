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
    // The NNUE net is bundled as an asset (BundledNetProvider copies it to filesDir once). It is
    // stored uncompressed: no inflate cost on first run, and this keeps the library's own androidTest
    // APK from deflating 98 MB. (:app sets the same suffixes, because compression is decided when the
    // final APK is packaged.)
    androidResources { noCompress += listOf(".nnue", ".tar") }
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
// (BundledNetProvider copies/verifies it) from the ONE authoritative place
// that name is defined — vendor/Stockfish/src/evaluate.h's
// `#define EvalFileDefaultName "..."` — instead of hand-copying it into
// Kotlin, where it could silently drift out of sync on a future Stockfish
// version bump. See BundledNetProvider.kt for how the generated constant is used.
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
// Bundled NNUE net (docs/BUNDLED_MODELS_DESIGN.md). The 98.5 MB net is not committed: it is
// fetched by scripts/fetch_models.sh into vendor/models/engine-assets/nnue/<name> and pinned by
// vendor/models/MODELS.lock. It is merged into this module's assets, and from there into the APK.
//
// verifyBundledModels fails the build loudly when the net is missing or has the wrong size or
// hash, rather than letting an APK ship that cannot analyse anything. It also writes
// GeneratedBundledNetConstants (the pinned size) for BundledNetProvider. The net NAME is not
// re-stated anywhere: it still comes from evaluate.h (CLAUDE.md engine gotcha 5).
// ---------------------------------------------------------------------------
val modelsLockFile = rootProject.file("vendor/models/MODELS.lock")
val engineAssetsDir = rootProject.file("vendor/models/engine-assets")
val generatedBundledNetDir = layout.buildDirectory.dir("generated/source/bundlednet/kotlin")

val verifyBundledModels = tasks.register("verifyBundledModels") {
    inputs.file(vendorEvaluateHeader)
    inputs.file(modelsLockFile)
    inputs.files(fileTree(engineAssetsDir))
    outputs.dir(generatedBundledNetDir)
    doLast {
        fun fail(what: String): Nothing = throw org.gradle.api.GradleException(
            "$what. Run scripts/fetch_models.sh from the repo root (it downloads and verifies the bundled models)."
        )
        if (!modelsLockFile.exists()) fail("vendor/models/MODELS.lock not found")
        if (!vendorEvaluateHeader.exists()) fail("vendor/Stockfish/src/evaluate.h not found")
        val lock = Properties().apply { modelsLockFile.inputStream().use { load(it) } }
        val expectedSize = lock.getProperty("net.size")?.trim()?.toLongOrNull()
            ?: fail("MODELS.lock has no net.size")
        val netName = Regex("""#define\s+EvalFileDefaultName\s+"([^"]+)"""")
            .find(vendorEvaluateHeader.readText())?.groupValues?.get(1)
            ?: fail("Could not read EvalFileDefaultName from evaluate.h")
        val prefix = Regex("""nn-([0-9a-f]+)\.nnue""").find(netName)?.groupValues?.get(1)
            ?: fail("Net name $netName does not encode a SHA-256 prefix")
        val net = File(engineAssetsDir, "nnue/$netName")
        if (!net.isFile) fail("Bundled NNUE net missing: ${net.path}")
        if (net.length() != expectedSize) {
            fail("Bundled NNUE net ${net.name} is ${net.length()} bytes, MODELS.lock pins $expectedSize")
        }
        val stale = File(engineAssetsDir, "nnue").listFiles { f -> f.isFile && f.name != netName }.orEmpty()
        if (stale.isNotEmpty()) fail("Stale files would be bundled next to the net: ${stale.joinToString { it.name }}")
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
        if (!actual.startsWith(prefix)) fail("Bundled NNUE net ${net.name} hashes to $actual, which does not start with $prefix")

        val pkgDir = generatedBundledNetDir.get().asFile.resolve("net/palaya/chessanalyzer/engine")
        pkgDir.mkdirs()
        pkgDir.resolve("GeneratedBundledNetConstants.kt").writeText(
            """
            |// GENERATED FILE - do not edit by hand.
            |// Written by the :engine module's verifyBundledModels Gradle task from
            |// vendor/models/MODELS.lock (see engine/build.gradle.kts).
            |package net.palaya.chessanalyzer.engine
            |
            |internal object GeneratedBundledNetConstants {
            |    const val NET_SIZE_BYTES: Long = ${expectedSize}L
            |}
            |""".trimMargin()
        )
    }
}
android.sourceSets.getByName("main").assets.srcDir(engineAssetsDir)
android.sourceSets.getByName("main").kotlin.srcDir(generatedBundledNetDir)
tasks.named("preBuild") { dependsOn(verifyBundledModels) }
androidComponents {
    onVariants {
        tasks.matching { it.name.contains("Kotlin") && it.name.contains("Compile", ignoreCase = true) }
            .configureEach { dependsOn(verifyBundledModels) }
    }
}
