package net.palaya.chessanalyzer.rephrase

/**
 * Whether this phone can run the wording model (docs/LLM_REPHRASE_DESIGN.md §2.2, §7): decided from facts the app
 * passes in, so it is a pure function the host tests cover. Never asked at launch: only the Settings row, the Setup
 * offer and the backend holder ask.
 */
object RephraseSupport {

    enum class Availability {
        AVAILABLE,

        /** armeabi-v7a (no librephrase.so is built for it), or an ABI the library is not packaged for. */
        UNSUPPORTED_ABI,

        /** An x86_64 CPU without AVX2/FMA: the x86_64 build would die on an illegal instruction. */
        UNSUPPORTED_CPU,

        /** Less than [MIN_TOTAL_RAM_BYTES] of RAM: the 1.1 GB model and its context do not fit next to the app. */
        LOW_MEMORY,
    }

    /** 4 GB, minus what the kernel reserves (a "4 GB" phone reports about 3.6 GB in MemoryInfo.totalMem). */
    const val MIN_TOTAL_RAM_BYTES: Long = 3_400L * 1024 * 1024

    /**
     * [primaryAbi] is `Build.SUPPORTED_ABIS[0]`; [cpuinfo] the text of /proc/cpuinfo (read only on x86_64);
     * [totalRamBytes] `ActivityManager.MemoryInfo.totalMem`.
     */
    fun availability(primaryAbi: String, totalRamBytes: Long, cpuinfo: () -> String = { "" }): Availability {
        when (primaryAbi) {
            "arm64-v8a" -> Unit
            "x86_64" -> {
                val flags = cpuinfo().lineSequence().firstOrNull { it.startsWith("flags") }.orEmpty().split(' ', '\t', ':').toSet()
                if ("avx2" !in flags || "fma" !in flags || "f16c" !in flags) return Availability.UNSUPPORTED_CPU
            }
            else -> return Availability.UNSUPPORTED_ABI
        }
        if (totalRamBytes in 1 until MIN_TOTAL_RAM_BYTES) return Availability.LOW_MEMORY
        return Availability.AVAILABLE
    }

    /**
     * Threads for llama.cpp: the cores whose maximum frequency is above the slowest cluster's (the big and middle
     * cores; on a Pixel 8 the X3 and the four A715 = 5), at least 2 and at most 6. With no frequency information
     * (or one cluster) it is the core count, capped at 4.
     */
    fun recommendedThreads(maxFreqKhzPerCore: List<Long>): Int {
        val known = maxFreqKhzPerCore.filter { it > 0 }
        if (known.isEmpty()) return 2
        val slowest = known.minOrNull()!!
        val fast = known.count { it > slowest }
        return if (fast == 0) known.size.coerceIn(2, 4) else fast.coerceIn(2, 6)
    }
}
