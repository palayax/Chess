package net.palaya.chessanalyzer.engine

import java.io.File

/**
 * CPU feature detection for choosing between the two arm64 Stockfish libraries
 * (libstockfish_dotprod.so needs the Armv8.2 dot-product instructions, libstockfish.so does not).
 */
internal object CpuFeatures {
    /**
     * Pure parser: true when any "Features" line of a /proc/cpuinfo dump lists the token `asimddp`
     * (the kernel's name for the Armv8.2 SDOT/UDOT instructions). No Android access here, so it is
     * host-testable.
     */
    fun hasDotProd(cpuinfo: String): Boolean =
        cpuinfo.lineSequence().any { line ->
            val colon = line.indexOf(':')
            colon > 0 &&
                line.substring(0, colon).trim().equals("Features", ignoreCase = true) &&
                line.substring(colon + 1).split(' ', '\t').any { it == "asimddp" }
        }

    /** True on an arm64-v8a device whose /proc/cpuinfo reports dotprod. Any failure means false. */
    fun deviceSupportsDotProd(): Boolean = try {
        android.os.Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a" &&
            hasDotProd(File("/proc/cpuinfo").readText())
    } catch (_: Throwable) {
        false
    }
}
