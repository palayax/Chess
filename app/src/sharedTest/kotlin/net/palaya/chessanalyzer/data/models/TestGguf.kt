package net.palaya.chessanalyzer.data.models

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * C2: a small, structurally valid GGUF v3 stand-in for the 1.1 GB wording model (host and instrumented tests): one
 * F32 tensor sized so the file is about [dataBytes] long, `general.architecture` = [arch]. It passes
 * `GgufHeader.check`; llama.cpp would refuse it (no vocabulary), which is what a trial failure looks like.
 */
object TestGguf {
    fun bytes(arch: String = "qwen2", dataBytes: Int = 64 * 1024, seed: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        fun u32(v: Long) { for (i in 0 until 4) out.write(((v shr (8 * i)) and 0xFF).toInt()) }
        fun u64(v: Long) { for (i in 0 until 8) out.write(((v shr (8 * i)) and 0xFF).toInt()) }
        fun str(s: String) { val b = s.toByteArray(); u64(b.size.toLong()); out.write(b) }
        out.write("GGUF".toByteArray(Charsets.ISO_8859_1))
        u32(3)
        u64(1) // tensors
        u64(2) // keys
        str("general.architecture"); u32(8); str(arch)
        str("general.name"); u32(8); str("test model $seed")
        val floats = (dataBytes / 4).toLong()
        str("weights"); u32(1); u64(floats); u32(0); u64(0)
        while (out.size() % 32 != 0) out.write(0)
        val data = ByteArray((floats * 4).toInt()) { (it * 31 + seed).toByte() }
        out.write(data)
        return out.toByteArray()
    }

    fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
