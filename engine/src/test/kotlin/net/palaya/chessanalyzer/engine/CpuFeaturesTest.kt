package net.palaya.chessanalyzer.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host tests for the /proc/cpuinfo dotprod detection that picks the arm64 Stockfish library. */
class CpuFeaturesTest {
    private val tensorG3 = """
        processor	: 0
        BogoMIPS	: 49.15
        Features	: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm jscvt fcma lrcpc dcpop sha3 sm3 sm4 asimddp sha512 sve asimdfhm dit uscat ilrcpc flagm ssbs sb paca pacg dcpodp sve2 sveaes svepmull svebitperm svesha3 svesm4 flagm2 frint svei8mm svebf16 i8mm bf16 dgh bti
        CPU implementer	: 0x41
        CPU architecture: 8
        CPU variant	: 0x1
        CPU part	: 0xd4e
        CPU revision	: 0

        processor	: 1
        BogoMIPS	: 49.15
        Features	: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm jscvt fcma lrcpc dcpop sha3 sm3 sm4 asimddp sha512
        CPU implementer	: 0x41
    """.trimIndent()

    private val cortexA53 = """
        Processor	: AArch64 Processor rev 4 (aarch64)
        processor	: 0
        BogoMIPS	: 38.40
        Features	: fp asimd evtstrm aes pmull sha1 sha2 crc32 cpuid
        CPU implementer	: 0x41
        CPU architecture: 8
        CPU variant	: 0x0
        CPU part	: 0xd03
        CPU revision	: 4
    """.trimIndent()

    @Test fun tensorG3HasDotProd() = assertTrue(CpuFeatures.hasDotProd(tensorG3))

    @Test fun cortexA53HasNoDotProd() = assertFalse(CpuFeatures.hasDotProd(cortexA53))

    @Test fun emptyHasNoDotProd() = assertFalse(CpuFeatures.hasDotProd(""))

    @Test fun tokenMustMatchWholeWord() {
        assertFalse(CpuFeatures.hasDotProd("Features\t: fp asimd asimddpx"))
        assertFalse(CpuFeatures.hasDotProd("Hardware\t: asimddp"))
    }

    @Test fun anyFeaturesLineCounts() {
        val mixed = "Features\t: fp asimd\n\nFeatures\t: fp asimd asimddp\n"
        assertTrue(CpuFeatures.hasDotProd(mixed))
    }
}
