package net.palaya.chessanalyzer.data.models

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import kotlin.random.Random
import net.palaya.chessanalyzer.engine.NetPins
import net.palaya.chessanalyzer.video.VoiceDownload
import net.palaya.chessanalyzer.video.VoiceStore
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream

/**
 * Small stand-ins for the two model files, so the host tests exercise the real download, verify and
 * install code without the 257 MB originals: a "net" whose 12-byte NNUE header matches its own pins,
 * and a Kokoro-shaped tar (one top-level directory, the three model files, espeak-ng-data/).
 */
object TestModelFiles {

    const val NET_VERSION = 0x6a448afaL
    const val NET_ARCH = 0xa85b2205L

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A net body: version, arch hash, description length 12, a description, then random bytes. */
    fun netBytes(size: Int = 300_000, seed: Int = 7, version: Long = NET_VERSION, arch: Long = NET_ARCH): ByteArray {
        val b = Random(seed).nextBytes(size)
        fun put(off: Int, v: Long) { for (i in 0..3) b[off + i] = ((v ushr (8 * i)) and 0xFF).toByte() }
        put(0, version)
        put(4, arch)
        put(8, 12)
        "Test network".toByteArray().copyInto(b, 12)
        return b
    }

    /** Pins that [bytes] satisfies, with a name that encodes its hash like a real net's. */
    fun pinsFor(bytes: ByteArray, version: Long = NET_VERSION, arch: Long = NET_ARCH): NetPins {
        val sha = sha256(bytes)
        return NetPins(fileName = "nn-${sha.take(12)}.nnue", sizeBytes = bytes.size.toLong(), sha256 = sha, archHash = arch, version = version)
    }

    /** A Kokoro-shaped tar; [complete] false leaves out voices.bin (a "damaged" archive). */
    fun voiceTar(complete: Boolean = true, payload: Int = 200_000, top: String = "kokoro-int8-en-v0_19"): ByteArray {
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(out).use { tar ->
            fun dir(name: String) {
                tar.putArchiveEntry(TarArchiveEntry("$name/"))
                tar.closeArchiveEntry()
            }
            fun file(name: String, data: ByteArray) {
                val e = TarArchiveEntry(name)
                e.size = data.size.toLong()
                tar.putArchiveEntry(e)
                tar.write(data)
                tar.closeArchiveEntry()
            }
            dir(top)
            file("$top/${VoiceStore.KOKORO_MODEL_FILE}", Random(1).nextBytes(payload))
            if (complete) file("$top/${VoiceStore.KOKORO_VOICES_FILE}", Random(2).nextBytes(5_000))
            file("$top/${VoiceStore.KOKORO_TOKENS_FILE}", "a 1\nb 2\n".toByteArray())
            dir("$top/espeak-ng-data")
            file("$top/espeak-ng-data/phontab", Random(3).nextBytes(1_000))
        }
        return out.toByteArray()
    }

    /** [bytes] gzipped, as the publisher's `gzip -9` does (D2f: the voice is downloaded as a `.tar.gz`). */
    fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        object : GZIPOutputStream(out) { init { def.setLevel(9) } }.use { it.write(bytes) }
        return out.toByteArray()
    }

    /**
     * A [VoiceStore] under [filesDir] whose tar pins are [tar]'s and whose download is [download] (by
     * default the plain tar itself; pass `gzip(tar)` for the `.tar.gz` the app really downloads), served
     * under this build's voice file name.
     */
    fun voiceStoreFor(filesDir: File, tar: ByteArray, free: Long = Long.MAX_VALUE, download: ByteArray = tar): VoiceStore =
        VoiceStore(
            filesDir,
            usableSpace = { free },
            pinnedSha256 = sha256(tar),
            pinnedSizeBytes = tar.size.toLong(),
            download = VoiceDownload(GeneratedModelPins.VOICE_FILE_NAME, download.size.toLong(), sha256(download)),
        )
}
