package net.palaya.chessanalyzer.rephrase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

/** docs/LLM_REPHRASE_DESIGN.md §1.4: the Kotlin structural check of a GGUF, on synthetic files. */
class GgufHeaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A minimal GGUF v3 writer: string and u32 keys, tensors of the given (type, shape) laid out back to back. */
    class Builder {
        private val out = ByteArrayOutputStream()
        var version = 3L
        var magic = "GGUF"
        val kvs = ArrayList<Pair<String, Any>>()
        val tensors = ArrayList<Triple<String, Int, LongArray>>()
        var alignment = 32

        private fun u32(v: Long) { for (i in 0 until 4) out.write(((v shr (8 * i)) and 0xFF).toInt()) }
        private fun u64(v: Long) { for (i in 0 until 8) out.write(((v shr (8 * i)) and 0xFF).toInt()) }
        private fun str(s: String) { val b = s.toByteArray(); u64(b.size.toLong()); out.write(b) }

        fun size(type: Int, shape: LongArray): Long = when (type) {
            0 -> 4 * shape.reduce(Long::times)
            12 -> shape[0] / 256 * 144 * shape.drop(1).fold(1L, Long::times)
            else -> error("test type")
        }

        fun build(dataBytes: Long? = null): ByteArray {
            out.write(magic.toByteArray(Charsets.ISO_8859_1))
            u32(version)
            u64(tensors.size.toLong())
            u64(kvs.size.toLong())
            for ((k, v) in kvs) {
                str(k)
                when (v) {
                    is String -> { u32(8); str(v) }
                    is Int -> { u32(4); u32(v.toLong()) }
                    is List<*> -> { u32(9); u32(8); u64(v.size.toLong()); v.forEach { str(it as String) } }
                    else -> error("type")
                }
            }
            var offset = 0L
            for ((name, type, shape) in tensors) {
                str(name)
                u32(shape.size.toLong())
                shape.forEach { u64(it) }
                u32(type.toLong())
                u64(offset)
                offset += size(type, shape)
                offset = (offset + alignment - 1) / alignment * alignment
            }
            while (out.size() % alignment != 0) out.write(0)
            val data = dataBytes ?: offset
            repeat(data.toInt()) { out.write(0) }
            return out.toByteArray()
        }
    }

    private fun valid() = Builder().apply {
        kvs += "general.architecture" to "qwen2"
        kvs += "general.alignment" to 32
        kvs += "tokenizer.ggml.tokens" to listOf("a", "b", "<|im_end|>")
        tensors += Triple("token_embd.weight", 12, longArrayOf(256, 4))
        tensors += Triple("output_norm.weight", 0, longArrayOf(16))
    }

    private fun file(bytes: ByteArray): File = tmp.newFile().apply { writeBytes(bytes) }

    private fun assertInvalid(f: File, arch: String? = "qwen2", contains: String) {
        try {
            GgufHeader.check(f, arch)
            fail("accepted")
        } catch (e: GgufHeader.Invalid) {
            assertTrue(e.message, e.message!!.contains(contains))
        }
    }

    @Test
    fun aWellFormedFileIsReadWithItsArchitectureAndDataSize() {
        val info = GgufHeader.check(file(valid().build()), "qwen2")
        assertEquals(3, info.version)
        assertEquals(2L, info.tensorCount)
        assertEquals("qwen2", info.architecture)
        assertEquals(0L, info.dataOffset % 32)
        assertEquals(576L + 64, info.dataBytes) // 4 x 144 bytes, aligned to 32, then 16 floats
    }

    @Test
    fun theWrongMagicVersionOrArchitectureIsRefused() {
        assertInvalid(file(valid().apply { magic = "GGML" }.build()), contains = "magic")
        assertInvalid(file(valid().apply { version = 2 }.build()), contains = "version 2")
        assertInvalid(file(valid().build()), arch = "llama", contains = "architecture")
    }

    @Test
    fun aTruncatedFileIsRefusedBeforeLlamaCppReadsPastItsEnd() {
        val full = valid().build()
        assertInvalid(file(full.copyOf(full.size - 100)), contains = "need")
        assertInvalid(file(full.copyOf(40)), contains = "truncated")
        assertInvalid(file(ByteArray(0)), contains = "truncated")
    }

    @Test
    fun anUnknownTensorTypeOrImplausibleCountsAreRefused() {
        assertInvalid(file(valid().apply { tensors += Triple("x", 0, longArrayOf(0)) }.build()), contains = "shape")
        val bytes = valid().build()
        // tensor count at bytes 8..15: make it huge
        val huge = bytes.copyOf().also { it[13] = 0x7F }
        assertInvalid(file(huge), contains = "implausible")
    }

    @Test
    fun theMagicSniffNeedsOnlyFourBytes() {
        assertTrue(GgufHeader.hasMagic("GGUFxxxx".byteInputStream()))
        assertFalse(GgufHeader.hasMagic("GGU".byteInputStream()))
        assertFalse(GgufHeader.hasMagic("<htm".byteInputStream()))
    }

    /** The real file, when present (developer machines; P2a's download): exactly its tensors, nothing missing. */
    @Test
    fun theDownloadedCandidatesParseWhenPresent() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "vendor/models/MODELS.lock").isFile) dir = dir.parentFile
        val models = File(dir, "vendor/models/rephrase-assets").listFiles { f -> f.name.endsWith(".gguf") }.orEmpty()
        for (m in models) {
            val arch = if (m.name.startsWith("Qwen3")) "qwen3" else "qwen2"
            val info = GgufHeader.check(m, arch)
            assertTrue("${m.name}: ${info.dataOffset} + ${info.dataBytes} vs ${m.length()}", info.dataOffset + info.dataBytes <= m.length())
            assertTrue("${m.name}: ${m.length() - info.dataOffset - info.dataBytes} bytes past the last tensor", m.length() - info.dataOffset - info.dataBytes < 64)
        }
    }
}
