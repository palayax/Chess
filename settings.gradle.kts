pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // sherpa-onnx (on-device neural TTS, Apache 2.0) publishes its Android AAR only via
        // JitPack's on-demand build of the project's own GitHub release assets (see
        // https://github.com/k2-fsa/sherpa-onnx/blob/master/jitpack.yml) — there is no Maven
        // Central coordinate for it. Verified this resolves:
        // com.github.k2-fsa.sherpa-onnx:sherpa-onnx:1.13.8 (see app/build.gradle.kts for why that
        // exact coordinate, not the shorter com.github.k2-fsa:sherpa-onnx aggregator one).
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "ChessAnalyzer"
include(":app", ":core", ":engine")
