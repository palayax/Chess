# Chess Analyzer app-module ProGuard/R8 rules.
# Release builds currently ship with isMinifyEnabled = false (see app/build.gradle.kts),
# so none of this runs yet — these rules are here ready for when minification is turned on.

# Keep FileProvider's manifest-referenced meta-data resolution working.
-keep class androidx.core.content.FileProvider { *; }

# Kotlin coroutines / kotlinx metadata housekeeping.
-dontwarn kotlinx.coroutines.**
-keepattributes *Annotation*, InnerClasses, Signature, SourceFile, LineNumberTable

# Compose compiler-generated classes rely on these attributes for tooling/preview.
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations

# Jetpack Navigation Safe Args / reflection-based Class lookups for nav destinations.
-keepnames class * extends androidx.activity.ComponentActivity

# --- Integration TODO ---
# Once :engine's JNI/UCI bridge lands, add -keep rules here for any native-method-bearing
# classes and the UCI message data classes reflectively (de)serialized, if applicable.
# Once a JSON/serialization library is chosen for the game-report export, add its rules too.
