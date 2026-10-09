package net.palaya.chessanalyzer.rephrase

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * The structural check of a downloaded GGUF, in Kotlin, BEFORE llama.cpp ever parses it (docs/LLM_REPHRASE_DESIGN.md
 * §1.4): magic `GGUF`, version 3, plausible tensor and key counts, `general.architecture` as pinned, every tensor's
 * type known and its data inside the file (a short file would be read past its end by llama.cpp). The file is also
 * pinned by size and SHA-256; this is the second guard, and the one that holds for an update's file too.
 *
 * Pure JVM code (no Android), host-tested with synthetic files (`GgufHeaderTest`).
 */
object GgufHeader {

    data class Info(
        val version: Int,
        val tensorCount: Long,
        val kvCount: Long,
        val architecture: String?,
        val alignment: Long,
        val dataOffset: Long,
        /** The end of the last tensor's data, from the data offset: the file must be at least dataOffset + this. */
        val dataBytes: Long,
    )

    class Invalid(message: String) : IOException(message)

    const val MAX_COUNT = 10_000L
    private const val MAX_STRING = 1L shl 20
    private const val MAX_ARRAY = 1L shl 24

    /** (block size, bytes per block) of each ggml tensor type a GGUF can hold (ggml.h / gguf-py GGML_QUANT_SIZES). */
    private val TYPE_SIZES: Map<Int, Pair<Long, Long>> = mapOf(
        0 to (1L to 4L), 1 to (1L to 2L), 2 to (32L to 18L), 3 to (32L to 20L), 6 to (32L to 22L), 7 to (32L to 24L),
        8 to (32L to 34L), 9 to (32L to 36L), 10 to (256L to 84L), 11 to (256L to 110L), 12 to (256L to 144L),
        13 to (256L to 176L), 14 to (256L to 210L), 15 to (256L to 292L), 16 to (256L to 66L), 17 to (256L to 74L),
        18 to (256L to 98L), 19 to (256L to 50L), 20 to (32L to 18L), 21 to (256L to 110L), 22 to (256L to 82L),
        23 to (256L to 136L), 24 to (1L to 1L), 25 to (1L to 2L), 26 to (1L to 4L), 27 to (1L to 8L), 28 to (1L to 8L),
        29 to (256L to 56L), 30 to (1L to 2L), 34 to (256L to 54L), 35 to (256L to 66L), 39 to (32L to 17L),
        40 to (64L to 36L), 41 to (128L to 18L), 42 to (64L to 18L),
    )

    /** Reads and checks [file]; throws [Invalid] with the reason. [expectedArch] null skips the architecture check. */
    fun check(file: File, expectedArch: String?): Info {
        if (!file.isFile) throw Invalid("not a file")
        val info = file.inputStream().buffered(1 shl 16).use { read(Reader(it)) }
        if (expectedArch != null && info.architecture != expectedArch) {
            throw Invalid("general.architecture is ${info.architecture}, expected $expectedArch")
        }
        val needed = info.dataOffset + info.dataBytes
        if (file.length() < needed) throw Invalid("file is ${file.length()} bytes, its tensors need $needed")
        return info
    }

    private class Reader(private val input: InputStream) {
        var pos = 0L
            private set

        fun bytes(n: Int): ByteArray {
            val b = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = input.read(b, off, n - off)
                if (r < 0) throw Invalid("truncated header at byte $pos")
                off += r
            }
            pos += n
            return b
        }

        fun skip(n: Long) {
            var left = n
            while (left > 0) {
                val s = input.skip(left)
                if (s <= 0) {
                    if (input.read() < 0) throw Invalid("truncated header at byte $pos")
                    left -= 1
                    pos += 1
                } else {
                    left -= s
                    pos += s
                }
            }
        }

        fun u32(): Long {
            val b = bytes(4)
            return (b[0].toLong() and 0xFF) or ((b[1].toLong() and 0xFF) shl 8) or ((b[2].toLong() and 0xFF) shl 16) or ((b[3].toLong() and 0xFF) shl 24)
        }

        fun u64(): Long {
            val lo = u32()
            val hi = u32()
            val v = lo or (hi shl 32)
            if (v < 0) throw Invalid("a 64-bit count is out of range at byte $pos")
            return v
        }

        fun string(): String {
            val n = u64()
            if (n > MAX_STRING) throw Invalid("a string of $n bytes at byte $pos")
            return String(bytes(n.toInt()), Charsets.UTF_8)
        }
    }

    private fun scalarSize(type: Long): Long = when (type) {
        0L, 1L, 7L -> 1
        2L, 3L -> 2
        4L, 5L, 6L -> 4
        10L, 11L, 12L -> 8
        else -> -1
    }

    private fun read(r: Reader): Info {
        val magic = r.bytes(4)
        if (String(magic, Charsets.ISO_8859_1) != "GGUF") throw Invalid("not a GGUF file (magic)")
        val version = r.u32()
        if (version != 3L) throw Invalid("GGUF version $version, expected 3")
        val tensors = r.u64()
        val kvs = r.u64()
        if (tensors > MAX_COUNT || kvs > MAX_COUNT) throw Invalid("implausible counts: $tensors tensors, $kvs keys")
        var arch: String? = null
        var alignment = 32L
        repeat(kvs.toInt()) {
            val key = r.string()
            val type = r.u32()
            when (type) {
                8L -> {
                    val v = r.string()
                    if (key == "general.architecture") arch = v
                }
                9L -> {
                    val itemType = r.u32()
                    val n = r.u64()
                    if (n > MAX_ARRAY) throw Invalid("array $key of $n items")
                    if (itemType == 8L) {
                        repeat(n.toInt()) { r.string() }
                    } else {
                        val size = scalarSize(itemType)
                        if (size < 0) throw Invalid("array $key of unknown type $itemType")
                        r.skip(size * n)
                    }
                }
                else -> {
                    val size = scalarSize(type)
                    if (size < 0) throw Invalid("key $key has unknown type $type")
                    if (key == "general.alignment" && type == 4L) {
                        alignment = r.u32()
                    } else {
                        r.skip(size)
                    }
                }
            }
        }
        if (alignment <= 0 || alignment and (alignment - 1) != 0L || alignment > 65536) throw Invalid("alignment $alignment")
        var end = 0L
        repeat(tensors.toInt()) {
            val name = r.string()
            val dims = r.u32()
            if (dims !in 1..4) throw Invalid("tensor $name has $dims dimensions")
            val ne = LongArray(dims.toInt()) { r.u64() }
            val type = r.u32().toInt()
            val offset = r.u64()
            val (block, bytes) = TYPE_SIZES[type] ?: throw Invalid("tensor $name has unknown type $type")
            if (ne.any { it <= 0 } || ne[0] % block != 0L) throw Invalid("tensor $name has shape ${ne.toList()} for type $type")
            var size = ne[0] / block * bytes
            for (i in 1 until ne.size) {
                if (size > Long.MAX_VALUE / ne[i]) throw Invalid("tensor $name is too large")
                size *= ne[i]
            }
            if (offset % alignment != 0L) throw Invalid("tensor $name is not aligned")
            end = maxOf(end, offset + size)
        }
        val dataOffset = (r.pos + alignment - 1) / alignment * alignment
        return Info(version.toInt(), tensors, kvs, arch, alignment, dataOffset, end)
    }

    /** True when [input] starts with the GGUF magic (a cheap sniff, no parsing). */
    fun hasMagic(input: InputStream): Boolean = try {
        val b = ByteArray(4)
        var off = 0
        while (off < 4) {
            val n = input.read(b, off, 4 - off)
            if (n < 0) throw EOFException()
            off += n
        }
        String(b, Charsets.ISO_8859_1) == "GGUF"
    } catch (e: IOException) {
        false
    }
}
