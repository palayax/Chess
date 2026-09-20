# CMake build for libstockfish.so — compiles Stockfish 19 (vendored at
# ../../../../vendor/Stockfish, see vendor/STOCKFISH_VERSION.txt) directly
# from source into a JNI-loadable shared library, one architecture per ABI.
#
# Design notes (see engine module task brief for full rationale):
#  - src/main.cpp is EXCLUDED: it owns argv-parsing + a real `main()`, which
#    we don't want in a .so. jni_bridge.cpp replaces it, calling
#    Stockfish::UCIEngine directly (see main.cpp for the exact call sequence
#    this mirrors: Attacks::init(), Position::init(), construct UCIEngine
#    with a CommandLine, then uci->loop()).
#  - src/universal/* is EXCLUDED: those are the multi-arch (fat binary)
#    dispatch shims Stockfish uses for its official "universal" release
#    binaries that runtime-detect CPU features and jump to the right
#    variant. We compile one fixed baseline per Android ABI instead, so
#    none of that dispatch machinery is needed.
#  - NNUE_EMBEDDING_OFF is defined for every ABI. With it defined,
#    nnue/network.cpp takes the `#else` branch (see network.cpp around the
#    INCBIN macro) and never embeds nn-1a298aa575a0.nnue (79 MB) in the
#    binary; the net is downloaded at runtime by NetworkProvider (Kotlin)
#    and pointed to via the `EvalFile` UCI option instead.
#  - Flags below are transcribed from vendor/Stockfish/src/Makefile's own
#    flag tables for each target (armv8, armv7-neon, x86-64-sse41-popcnt),
#    not guessed — see the Makefile section headers referenced in comments.

cmake_minimum_required(VERSION 3.22.1)
project(stockfish_jni CXX)

set(SF_SRC "${CMAKE_CURRENT_SOURCE_DIR}/../../../../vendor/Stockfish/src")

if(NOT EXISTS "${SF_SRC}/uci.cpp")
    message(FATAL_ERROR
        "Vendored Stockfish source not found at ${SF_SRC}. "
        "Run scripts/fetch_stockfish.sh from the repo root first.")
endif()

# ---------------------------------------------------------------------------
# Stockfish sources (mirrors the Makefile's SRCS list, minus main.cpp; see
# vendor/Stockfish/src/Makefile "SRCS = ..." — main.cpp excluded per above).
# ---------------------------------------------------------------------------
set(SF_SOURCES
    ${SF_SRC}/attacks.cpp
    ${SF_SRC}/benchmark.cpp
    ${SF_SRC}/bitboard.cpp
    ${SF_SRC}/evaluate.cpp
    ${SF_SRC}/misc.cpp
    ${SF_SRC}/movegen.cpp
    ${SF_SRC}/movepick.cpp
    ${SF_SRC}/position.cpp
    ${SF_SRC}/search.cpp
    ${SF_SRC}/thread.cpp
    ${SF_SRC}/timeman.cpp
    ${SF_SRC}/tt.cpp
    ${SF_SRC}/uci.cpp
    ${SF_SRC}/ucioption.cpp
    ${SF_SRC}/tune.cpp
    ${SF_SRC}/syzygy/tbprobe.cpp
    ${SF_SRC}/nnue/nnue_accumulator.cpp
    ${SF_SRC}/nnue/nnue_misc.cpp
    ${SF_SRC}/nnue/network.cpp
    ${SF_SRC}/nnue/features/half_ka_v2_hm.cpp
    ${SF_SRC}/nnue/features/full_threats.cpp
    ${SF_SRC}/nnue/features/pp_3wide.cpp
    ${SF_SRC}/engine.cpp
    ${SF_SRC}/score.cpp
    ${SF_SRC}/memory.cpp
)

add_library(stockfish SHARED
    ${SF_SOURCES}
    jni_bridge.cpp
)

target_include_directories(stockfish PRIVATE
    ${SF_SRC}
)

# ---------------------------------------------------------------------------
# Common flags (Makefile section 3.1: CXXFLAGS = ... -fno-exceptions
# -std=c++17 ...). SF19 itself opts out of exceptions across all platforms,
# so this is not us disabling something it needs — it's how upstream builds.
# -DNDEBUG matches Makefile's `debug=no` default (release-mode SF drops its
# internal assert()s this way).
# ---------------------------------------------------------------------------
target_compile_features(stockfish PRIVATE cxx_std_17)
set_target_properties(stockfish PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
    CXX_EXTENSIONS OFF
)

target_compile_options(stockfish PRIVATE
    -fno-exceptions
    -O3
    -DNDEBUG
    -fno-rtti
    -Wall
)

target_compile_definitions(stockfish PRIVATE
    NNUE_EMBEDDING_OFF
)

# ---------------------------------------------------------------------------
# Per-ABI flags, transcribed from the Makefile's ARCH tables.
# ---------------------------------------------------------------------------
if(ANDROID_ABI STREQUAL "arm64-v8a")
    # Mirrors ARCH=armv8 (NOT armv8-dotprod / i8mm — those aren't guaranteed
    # present on all arm64 devices, only a safe universal baseline is used).
    # bits=64 -> -DIS_64BIT; popcnt=yes on arch armv8 -> plain -DUSE_POPCNT
    # (no -mpopcnt, that's an x86-only flag per the Makefile's arch filter);
    # neon=yes, arm_version=8 -> -DUSE_NEON=8, and no -mfpu=neon since AArch64
    # always has NEON and the Makefile skips that flag for arch==armv8.
    target_compile_definitions(stockfish PRIVATE IS_64BIT USE_POPCNT USE_NEON=8)

elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    # Mirrors ARCH=armv7-neon plus the Makefile's COMP=ndk/arch=armv7 block
    # (-mthumb -march=armv7-a -mfloat-abi=softfp -mfpu=neon), which is the
    # exact invocation upstream uses to cross-compile this ABI with the NDK.
    target_compile_definitions(stockfish PRIVATE USE_POPCNT USE_NEON=7)
    target_compile_options(stockfish PRIVATE
        -mthumb -march=armv7-a -mfloat-abi=softfp -mfpu=neon
    )

elseif(ANDROID_ABI STREQUAL "x86_64")
    # Mirrors ARCH=x86-64-sse41-popcnt: a safe, universally-supported-since-
    # ~2011 baseline (every real x86_64 CPU and every Android x86_64 emulator
    # image has SSE4.1+POPCNT), matching what the Makefile emits for that
    # ARCH value (IS_64BIT, USE_POPCNT, USE_SSE41, USE_SSSE3, USE_SSE2, plus
    # the corresponding -m<isa> compiler flags).
    target_compile_definitions(stockfish PRIVATE
        IS_64BIT USE_POPCNT USE_SSE41 USE_SSSE3 USE_SSE2
    )
    target_compile_options(stockfish PRIVATE
        -msse -msse2 -mssse3 -msse4.1 -msse3 -mpopcnt
    )
else()
    message(FATAL_ERROR "Unsupported ANDROID_ABI for libstockfish: ${ANDROID_ABI}")
endif()

# pthreads + the Android log/dl libs used by the JNI bridge.
find_library(log-lib log)
target_link_libraries(stockfish PRIVATE ${log-lib})
