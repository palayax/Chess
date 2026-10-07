package net.palaya.chessanalyzer.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 12-byte NNUE header (`nnue/network.cpp` `read_header`), from synthetic bytes (design §6.1). */
class NetHeaderTest {

    private fun header(version: Long, arch: Long, descLen: Long, extra: Int = 4): ByteArray {
        val b = ByteArray(12 + extra)
        fun put(off: Int, v: Long) { for (i in 0..3) b[off + i] = ((v ushr (8 * i)) and 0xFF).toByte() }
        put(0, version)
        put(4, arch)
        put(8, descLen)
        return b
    }

    private val pins = NetPins("nn-0123456789ab.nnue", 100, "0123456789ab" + "0".repeat(52), archHash = 0xa85b2205L, version = 0x6a448afaL)

    @Test
    fun readsTheThreeLittleEndianFields() {
        // The real nn-1a298aa575a0.nnue starts fa 8a 44 6a | 05 22 5b a8 | 54 00 00 00.
        val bytes = byteArrayOf(0xfa.toByte(), 0x8a.toByte(), 0x44, 0x6a, 0x05, 0x22, 0x5b, 0xa8.toByte(), 0x54, 0, 0, 0, 0x4e, 0x65)
        val h = NetStore.parseHeader(bytes)!!
        assertEquals(0x6a448afaL, h.version)
        assertEquals(0xa85b2205L, h.archHash)
        assertEquals(84L, h.descriptionLength)
    }

    @Test
    fun highBitValuesStayPositive() {
        val h = NetStore.parseHeader(header(0xffffffffL, 0x80000000L, 0))!!
        assertEquals(0xffffffffL, h.version)
        assertEquals(0x80000000L, h.archHash)
    }

    @Test
    fun fewerThanTwelveBytesIsNoHeader() {
        assertNull(NetStore.parseHeader(ByteArray(11)))
        assertNull(NetStore.parseHeader(ByteArray(0)))
    }

    @Test
    fun aHeaderMatchesOnlyTheEngineItWasBuiltFor() {
        assertTrue(NetStore.parseHeader(header(0x6a448afaL, 0xa85b2205L, 84))!!.matches(pins))
        assertFalse("other file version", NetStore.parseHeader(header(0x7af32f20L, 0xa85b2205L, 84))!!.matches(pins))
        assertFalse("other architecture", NetStore.parseHeader(header(0x6a448afaL, 0xa85b2206L, 84))!!.matches(pins))
        assertFalse("description too long to be a net", NetStore.parseHeader(header(0x6a448afaL, 0xa85b2205L, 1024))!!.matches(pins))
        assertTrue(NetStore.parseHeader(header(0x6a448afaL, 0xa85b2205L, 1023))!!.matches(pins))
    }

    @Test
    fun theCompiledPinsAreConsistentWithTheNetsName() {
        val p = NetPins.COMPILED
        assertEquals(NetStore.NET_FILENAME, p.fileName)
        val prefix = NetStore.prefixOf(p.fileName)
        assertTrue("the net name encodes a 12-hex prefix: ${p.fileName}", prefix != null)
        assertTrue(Regex("[0-9a-f]{64}").matches(p.sha256))
        assertTrue("${p.sha256} must start with $prefix", p.sha256.startsWith(prefix!!))
        assertTrue(p.sizeBytes > StockfishEngine.MIN_PLAUSIBLE_NET_BYTES)
        assertTrue(p.version in 0..0xffffffffL && p.archHash in 0..0xffffffffL)
    }

    @Test
    fun netNamesAreRecognised() {
        assertEquals("1a298aa575a0", NetStore.prefixOf("nn-1a298aa575a0.nnue"))
        assertNull(NetStore.prefixOf("nn-1A298AA575A0.nnue"))
        assertNull(NetStore.prefixOf("nn-1a298aa575a.nnue"))
        assertNull(NetStore.prefixOf("nn-1a298aa575a0.nnue.part"))
        assertNull(NetStore.prefixOf("../nn-1a298aa575a0.nnue"))
    }
}
