package net.palaya.chessanalyzer.data.models

/**
 * The downloader's fault matrix (design §6.1) on the host JVM. The cases live in
 * [ModelDownloaderFaultMatrix] (`app/src/sharedTest`), which the device also runs as
 * `ModelDownloaderInstrumentedTest` (D2d).
 */
class ModelDownloaderTest : ModelDownloaderFaultMatrix()
