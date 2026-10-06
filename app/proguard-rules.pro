# Chess Analyzer app-module R8 rules. Release builds run R8 (isMinifyEnabled + isShrinkResources,
# see app/build.gradle.kts). The default proguard-android-optimize.txt is applied first; these are
# only what it and the libraries' own consumer rules do not cover.

# --- sherpa-onnx (on-device neural TTS) -----------------------------------------------------------
# The AAR ships an EMPTY proguard.txt. Its JNI library (libsherpa-onnx-jni.so) reads the config
# objects' fields by name (GetFieldID on OfflineTtsConfig, OfflineTtsModelConfig,
# OfflineTtsKokoroModelConfig, ...) and constructs result classes such as GeneratedAudio by name from
# C++. R8 cannot see any of that, so it would rename or strip those fields and the voice would fail
# at runtime (a JNI NoSuchFieldError aborts the process). Keep the whole package, members included.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# --- Stockfish JNI ---------------------------------------------------------------------------------
# NativeBridge is pinned by :engine's own consumer-rules.pro (shipped with the library), not here.

# --- FileProvider ----------------------------------------------------------------------------------
# Referenced only from the manifest (<provider android:name=...>); AAPT2 already emits a keep rule for
# manifest components, this is belt and braces. Its paths XML (@xml/file_paths) is referenced from the
# manifest meta-data, so the resource shrinker keeps it.
-keep class androidx.core.content.FileProvider { *; }

# --- DataStore -------------------------------------------------------------------------------------
# datastore-preferences ships consumer rules for its protobuf-lite schema; nothing extra is needed.
# The app stores only primitives and enum NAMES (read back with enumValueOf / valueOf, which the
# default rules keep for every enum).

# --- Serialization / reflection --------------------------------------------------------------------
# :app uses no kotlinx.serialization (only :desktop does) and no reflection of its own: games and the
# analysis cache are written with org.json (a platform API, never renamed).

# Readable stack traces from release builds (the mapping file is in app/build/outputs/mapping/release).
-keepattributes SourceFile, LineNumberTable
