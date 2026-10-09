package net.palaya.chessanalyzer.rephrase

import net.palaya.chessanalyzer.rephrase.RephraseSupport.Availability
import org.junit.Assert.assertEquals
import org.junit.Test

/** Design §2.2/§7: who can run the wording model, and how many threads it gets. */
class RephraseSupportTest {

    private val gb = 1024L * 1024 * 1024

    @Test
    fun arm64WithEnoughMemoryIsAvailableAndV7aIsNot() {
        assertEquals(Availability.AVAILABLE, RephraseSupport.availability("arm64-v8a", 8 * gb))
        assertEquals(Availability.UNSUPPORTED_ABI, RephraseSupport.availability("armeabi-v7a", 8 * gb))
        assertEquals(Availability.UNSUPPORTED_ABI, RephraseSupport.availability("x86", 8 * gb))
    }

    @Test
    fun aPhoneUnderFourGigabytesIsLowMemory() {
        assertEquals(Availability.LOW_MEMORY, RephraseSupport.availability("arm64-v8a", 3 * gb))
        assertEquals(Availability.AVAILABLE, RephraseSupport.availability("arm64-v8a", 3_600L * 1024 * 1024))
    }

    @Test
    fun x86NeedsAvx2FmaAndF16c() {
        val with = "processor\t: 0\nflags\t\t: fpu sse4_2 avx avx2 fma f16c\n"
        val without = "processor\t: 0\nflags\t\t: fpu sse4_2 avx\n"
        assertEquals(Availability.AVAILABLE, RephraseSupport.availability("x86_64", 4 * gb) { with })
        assertEquals(Availability.UNSUPPORTED_CPU, RephraseSupport.availability("x86_64", 4 * gb) { without })
    }

    @Test
    fun threadsAreTheBigAndMiddleCores() {
        // Pixel 8 (Tensor G3): 4 x A510 1.70 GHz, 4 x A715 2.37 GHz, 1 x X3 2.91 GHz
        val pixel8 = List(4) { 1_704_000L } + List(4) { 2_367_000L } + listOf(2_910_000L)
        assertEquals(5, RephraseSupport.recommendedThreads(pixel8))
        assertEquals(4, RephraseSupport.recommendedThreads(List(4) { 2_400_000L }))
        assertEquals(2, RephraseSupport.recommendedThreads(emptyList()))
        assertEquals(6, RephraseSupport.recommendedThreads(List(2) { 1L } + List(8) { 3L }))
    }
}
