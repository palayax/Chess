# CMake build for librephrase.so (C2, docs/LLM_REPHRASE_DESIGN.md §2.2): llama.cpp, compiled from the vendored
# source at the pinned tag (vendor/LLAMA_CPP_VERSION.txt, scripts/fetch_llama_cpp.sh), linked statically with the
# JNI bridge into ONE shared library per ABI: one System.loadLibrary, one R8 keep rule, one file to align.
#
# Not used, on purpose:
#  - GGML_BACKEND_DL / GGML_CPU_ALL_VARIANTS (the upstream Android example): several dlopen'ed libggml-cpu-*.so
#    MODULE targets that our 16 KB link flag does not reach and that multiply the files to keep and align.
#  - GGML_CPU_KLEIDIAI: its CMake downloads the KleidiAI sources at configure time (FetchContent from GitHub),
#    which a reproducible, offline build must not do; and the shipped Q4_K_M uses K-quants, which KleidiAI does
#    not accelerate. Q4_0 still gets ggml's own runtime repack (GGML_CPU_REPACK, on by default).
#  - OpenMP and llamafile (unsupported on Android per llama.cpp's docs/android.md), CURL/OpenSSL, common, tools.
cmake_minimum_required(VERSION 3.22.1)

# The debug variant is built optimised too: an -O0 llama.cpp is unusably slow, even on an emulator.
set(CMAKE_C_FLAGS_DEBUG "-O3 -DNDEBUG" CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS_DEBUG "-O3 -DNDEBUG" CACHE STRING "" FORCE)

project(rephrase_jni C CXX)

set(LLAMA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../vendor/llama.cpp")
if(NOT EXISTS "${LLAMA_DIR}/include/llama.h")
    message(FATAL_ERROR
        "Vendored llama.cpp source not found at ${LLAMA_DIR}. "
        "Run scripts/fetch_llama_cpp.sh from the repo root first.")
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS OFF CACHE BOOL "" FORCE)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    # dotprod and fp16 are on every arm64 phone of the last six years (ARMv8.2); ggml's own variable, not a
    # global -march (llama.cpp's docs advise against the latter).
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
elseif(ANDROID_ABI STREQUAL "x86_64")
    # The emulators (correctness only). AVX2/FMA: the Kotlin side checks /proc/cpuinfo before loading, so a
    # CPU without them reports UNSUPPORTED instead of dying on an illegal instruction.
    set(GGML_AVX ON CACHE BOOL "" FORCE)
    set(GGML_AVX2 ON CACHE BOOL "" FORCE)
    set(GGML_FMA ON CACHE BOOL "" FORCE)
    set(GGML_F16C ON CACHE BOOL "" FORCE)
else()
    message(FATAL_ERROR "librephrase is built for arm64-v8a and x86_64 only, not ${ANDROID_ABI}")
endif()

add_subdirectory("${LLAMA_DIR}" llama EXCLUDE_FROM_ALL)

add_library(rephrase SHARED jni_bridge.cpp)
target_compile_features(rephrase PRIVATE cxx_std_17)
target_link_libraries(rephrase PRIVATE llama ggml)

# 16 KB page-size support (Play, targetSdk 35+), as in :engine. Verify: llvm-readelf -lW librephrase.so (Align
# 0x4000 on every LOAD). --exclude-libs keeps llama/ggml's own symbols out of the dynamic table (only the JNI
# entry points are exported), --gc-sections drops what the bridge never reaches.
target_link_options(rephrase PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,--exclude-libs,ALL"
    "-Wl,--gc-sections"
)

find_library(log-lib log)
target_link_libraries(rephrase PRIVATE ${log-lib})
