plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "net.palaya.chessanalyzer.engine"
    compileSdk = 34
    ndkVersion = "26.1.10909125"
    defaultConfig {
        minSdk = 26
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
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

// ---------------------------------------------------------------------------
// generateNetworkConstants: derives the NNUE net filename Kotlin needs
// (NetworkProvider downloads/verifies it) from the ONE authoritative place
// that name is defined — vendor/Stockfish/src/evaluate.h's
// `#define EvalFileDefaultName "..."` — instead of hand-copying it into
// Kotlin, where it could silently drift out of sync on a future Stockfish
// version bump. See NetworkProvider.kt for how the generated constant is used.
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
