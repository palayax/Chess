import java.util.Properties

// :rephrase (C2, docs/LLM_REPHRASE_DESIGN.md §2.2): llama.cpp compiled from the vendored source through the NDK into
// librephrase.so (arm64-v8a and x86_64 only; armeabi-v7a gets no library and reports UNSUPPORTED_ABI), the JNI bridge,
// and the Kotlin backend behind core's Rephraser interface. The weights are NOT here: the app downloads them on the
// user's tap and checks them against MODELS.lock. Mirrors :engine.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "net.palaya.chessanalyzer.rephrase"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["notAnnotation"] = "net.palaya.chessanalyzer.rephrase.ManualEvidenceTool"
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
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
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

// The pinned llama.cpp tag lives in two places that must agree: vendor/LLAMA_CPP_VERSION.txt (what
// scripts/fetch_llama_cpp.sh fetched) and rephrase.runtime.tag in MODELS.lock (what models.json's runtime range is
// judged against). A mismatch, or a missing checkout, stops the build with the fix.
val llamaVersionFile = rootProject.file("vendor/LLAMA_CPP_VERSION.txt")
val llamaHeader = rootProject.file("vendor/llama.cpp/include/llama.h")
val rephraseLock = providers.gradleProperty("palayaModelsLock").orNull?.let { rootProject.file(it) }
    ?: rootProject.file("vendor/models/MODELS.lock")
val generatedRuntimeDir = layout.buildDirectory.dir("generated/source/rephraseruntime/kotlin")
val generateRephraseRuntime = tasks.register("generateRephraseRuntime") {
    description = "Checks the vendored llama.cpp tag against MODELS.lock and writes GeneratedRephraseRuntime.kt"
    inputs.file(llamaVersionFile)
    inputs.file(rephraseLock)
    outputs.dir(generatedRuntimeDir)
    doLast {
        fun fail(what: String): Nothing = throw org.gradle.api.GradleException("generateRephraseRuntime: $what")
        if (!llamaHeader.isFile) fail("vendor/llama.cpp is missing; run scripts/fetch_llama_cpp.sh")
        val tag = Regex("""Tag:\s*(b\d+)""").find(llamaVersionFile.readText())?.groupValues?.get(1)
            ?: fail("no 'Tag: bNNNN' in ${llamaVersionFile.path}")
        val lock = Properties().apply { rephraseLock.inputStream().use { load(it) } }
        val lockTag = lock.getProperty("rephrase.runtime.tag")?.trim() ?: fail("rephrase.runtime.tag missing in ${rephraseLock.path}")
        if (lockTag != tag) fail("rephrase.runtime.tag $lockTag differs from the vendored llama.cpp $tag")
        val dir = generatedRuntimeDir.get().asFile.resolve("net/palaya/chessanalyzer/rephrase")
        dir.mkdirs()
        dir.resolve("GeneratedRephraseRuntime.kt").writeText(
            """
            |// GENERATED FILE - do not edit by hand. Written by :rephrase's generateRephraseRuntime task.
            |package net.palaya.chessanalyzer.rephrase
            |
            |object GeneratedRephraseRuntime {
            |    /** The llama.cpp tag compiled into librephrase.so (vendor/LLAMA_CPP_VERSION.txt = MODELS.lock). */
            |    const val LLAMA_CPP_TAG: String = "$tag"
            |    const val LLAMA_CPP_BUILD: Int = ${tag.removePrefix("b")}
            |}
            |""".trimMargin()
        )
    }
}
android.sourceSets.getByName("main").kotlin.srcDir(generatedRuntimeDir)
tasks.named("preBuild") { dependsOn(generateRephraseRuntime) }
androidComponents {
    onVariants {
        tasks.matching { it.name.contains("Kotlin") && it.name.contains("Compile", ignoreCase = true) }
            .configureEach { dependsOn(generateRephraseRuntime) }
    }
}
