package net.palaya.chessanalyzer.rephrase

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.palaya.chessanalyzer.core.text.RephrasePrompt
import net.palaya.chessanalyzer.core.text.RephraseRequest
import net.palaya.chessanalyzer.core.text.RephraseResult
import net.palaya.chessanalyzer.core.text.RephraseSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

/**
 * The real llama.cpp backend on a device (docs/LLM_REPHRASE_DESIGN.md §8.2): librephrase.so loads the real GGUF,
 * rephrases the prompt's own examples through the checker, is deterministic, can be cancelled, and refuses a
 * truncated or corrupted file without taking the process down. Emulator speed is not a measurement.
 *
 * The GGUF is NOT a seed asset (1.1 GB would push the test APK past 1.2 GB). Push it once:
 *     adb push vendor/models/rephrase-assets/qwen2.5-1.5b-instruct-q4_k_m.gguf /data/local/tmp/rephrase/
 * or pass another file with `-e ggufPath <device path>` (and `-e ggufArch qwen3` for a Qwen3 file). A missing file
 * FAILS the class (never a skip).
 */
@RunWith(AndroidJUnit4::class)
class LlamaRephraserInstrumentedTest {

    companion object {
        private const val TAG = "LlamaRephraserTest"
        const val DEFAULT_PATH = "/data/local/tmp/rephrase/qwen2.5-1.5b-instruct-q4_k_m.gguf"

        lateinit var gguf: File
        lateinit var arch: String

        @BeforeClass
        @JvmStatic
        fun locate() {
            val args = InstrumentationRegistry.getArguments()
            gguf = File(args.getString("ggufPath") ?: DEFAULT_PATH)
            arch = args.getString("ggufArch") ?: "qwen2"
            if (!gguf.isFile || !gguf.canRead()) fail("the GGUF is not on the device at $gguf (see the class doc: adb push it first)")
        }

        private val cacheDir: File get() = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

        /** One backend for the class: loading 1.1 GB per test would dominate the run. */
        val backend: LlamaRephraser by lazy {
            LlamaRephraser(gguf, gguf.nameWithoutExtension.lowercase(), family = if (arch == "qwen3") RephrasePrompt.Family.QWEN3 else RephrasePrompt.Family.QWEN2, threads = 4)
        }
    }

    @Test
    fun theLibraryLoadsAndTheRealFilePassesTheStructuralCheck() {
        assertTrue("librephrase.so did not load on ${android.os.Build.SUPPORTED_ABIS.toList()}", NativeRephrase.loaded)
        val info = GgufHeader.check(gguf, arch)
        assertTrue(info.dataOffset + info.dataBytes <= gguf.length())
    }

    @Test
    fun thePromptsOwnExamplesComeBackThroughTheChecker() = runBlocking {
        for (e in RephrasePrompt.EXAMPLES) {
            val t0 = System.nanoTime()
            val r = backend.rephrase(RephraseRequest(e.surface, e.text))
            Log.i(TAG, "example (${(System.nanoTime() - t0) / 1_000_000} ms, ${backend.lastStats}): $r")
            assertTrue("[${e.text}] -> $r", r is RephraseResult.Accepted || r == RephraseResult.Unchanged)
        }
        assertTrue("the prefix stays in the KV cache between calls", backend.lastStats!!.prefixReused)
    }

    @Test
    fun aRealCardIsRephrasedDeterministically() = runBlocking {
        val text = "This lets White play h4, which attacks the undefended queen on g5. That takes Black from about level to slightly worse. Better was h5."
        val a = backend.rephrase(RephraseRequest(RephraseSurface.CARD, text))
        val b = backend.rephrase(RephraseRequest(RephraseSurface.CARD, text))
        Log.i(TAG, "card: $a / ${backend.lastStats}")
        assertEquals("greedy decoding with the same file, threads and prompt is deterministic", a, b)
        assertTrue(a.toString(), a !is RephraseResult.Unavailable)
    }

    @Test
    fun aRealNarrationBeatKeepsTheSpokenSquares() = runBlocking {
        val text = "Black takes the rook on g one with the bishop. And the chance is gone. It does not come back. That's about level turning into clearly worse in a single move."
        val r = backend.rephrase(RephraseRequest(RephraseSurface.NARRATION, text))
        Log.i(TAG, "narration: $r / ${backend.lastStats}")
        assertTrue(r.toString(), r !is RephraseResult.Unavailable)
        if (r is RephraseResult.Accepted) assertTrue(r.text, "g one" in r.text)
    }

    @Test
    fun cancellingMidGenerationStopsQuicklyAndTheNextCallWorks() = runBlocking {
        val long = "Four things to take out of this game. Black walked past three hanging pieces in this game. They were on b five, f five and g four. " +
            "Before every single move, sweep the board for pieces with no defender. Theirs first, then yours."
        backend.rephrase(RephraseRequest(RephraseSurface.CARD, "Nf6 stays in book.")) // loaded and warm
        val job = async(Dispatchers.Default) { backend.rephrase(RephraseRequest(RephraseSurface.NARRATION, long)) }
        delay(400)
        val t0 = System.nanoTime()
        job.cancel()
        runCatching { job.await() }
        val stopMs = (System.nanoTime() - t0) / 1_000_000
        Log.i(TAG, "cancel took $stopMs ms")
        assertTrue("cancel took $stopMs ms", stopMs < 5_000)
        val after = backend.rephrase(RephraseRequest(RephraseSurface.CARD, "Nf6 stays in book."))
        assertTrue(after.toString(), after !is RephraseResult.Unavailable)
    }

    private fun copyPrefix(bytes: Long, name: String): File {
        val out = File(cacheDir, name)
        gguf.inputStream().use { input ->
            out.outputStream().use { o ->
                val buf = ByteArray(1 shl 20)
                var left = bytes
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    o.write(buf, 0, n)
                    left -= n
                }
            }
        }
        return out
    }

    @Test
    fun aTruncatedFileIsRefusedByTheStructuralCheckAndByLlamaCppWithoutACrash() {
        val cut = copyPrefix(64L * 1024 * 1024, "truncated.gguf")
        try {
            try {
                GgufHeader.check(cut, arch)
                fail("the structural check accepted a truncated file")
            } catch (e: GgufHeader.Invalid) {
                Log.i(TAG, "structural check: ${e.message}")
            }
            // Even handed straight to llama.cpp (the app never does this), the load fails cleanly.
            val h = NativeRephrase.load(cut.absolutePath, 2, 512)
            if (h != 0L) NativeRephrase.free(h)
            assertEquals("llama.cpp loaded a truncated file", 0L, h)
        } finally {
            cut.delete()
        }
    }

    @Test
    fun aCorruptedHeaderIsRefusedByTheStructuralCheckAndByLlamaCppWithoutACrash() {
        val bad = copyPrefix(8L * 1024 * 1024, "corrupt.gguf")
        try {
            RandomAccessFile(bad, "rw").use { raf ->
                raf.seek(4)
                raf.write(byteArrayOf(9, 0, 0, 0)) // version 9
            }
            try {
                GgufHeader.check(bad, arch)
                fail("the structural check accepted a corrupted file")
            } catch (e: GgufHeader.Invalid) {
                Log.i(TAG, "structural check: ${e.message}")
            }
            val h = NativeRephrase.load(bad.absolutePath, 2, 512)
            if (h != 0L) NativeRephrase.free(h)
            assertEquals(0L, h)
            // garbage after a valid magic and version
            RandomAccessFile(bad, "rw").use { raf ->
                raf.seek(4)
                raf.write(byteArrayOf(3, 0, 0, 0))
                raf.seek(24)
                raf.write(ByteArray(4096) { 0x7F })
            }
            val h2 = NativeRephrase.load(bad.absolutePath, 2, 512)
            if (h2 != 0L) NativeRephrase.free(h2)
            assertEquals(0L, h2)
        } finally {
            bad.delete()
        }
    }

    /** "No network on its own" (CLAUDE.md): loading and generating send and receive nothing (the uid's TrafficStats). */
    @Test
    fun loadingAndGeneratingMakeNoNetworkTraffic() = runBlocking {
        val uid = android.os.Process.myUid()
        backend.release()
        val tx0 = android.net.TrafficStats.getUidTxBytes(uid)
        val rx0 = android.net.TrafficStats.getUidRxBytes(uid)
        backend.rephrase(RephraseRequest(RephraseSurface.CARD, "Nf3 matches the engine's top choice. It hits the loose queen on h4."))
        backend.rephrase(RephraseRequest(RephraseSurface.NARRATION, "White plays knight to h five. The pawn on f four has nothing defending it."))
        val tx = android.net.TrafficStats.getUidTxBytes(uid) - tx0
        val rx = android.net.TrafficStats.getUidRxBytes(uid) - rx0
        Log.i(TAG, "traffic during load + 2 generations: tx $tx, rx $rx (uid $uid)")
        assertEquals("bytes sent", 0L, tx)
        assertEquals("bytes received", 0L, rx)
    }

    @Test
    fun releaseGivesTheMemoryBack() = runBlocking {
        backend.rephrase(RephraseRequest(RephraseSurface.CARD, "Nf6 stays in book."))
        val loaded = Debug.getNativeHeapAllocatedSize()
        val pssLoaded = Debug.getPss()
        backend.release()
        System.gc()
        val released = Debug.getNativeHeapAllocatedSize()
        val pssReleased = Debug.getPss()
        Log.i(TAG, "native heap loaded $loaded -> released $released; PSS $pssLoaded KB -> $pssReleased KB")
        assertTrue("native heap $loaded -> $released", released < loaded)
        assertNotEquals(0L, loaded)
        withTimeout(120_000) { backend.rephrase(RephraseRequest(RephraseSurface.CARD, "Nf6 stays in book.")) }
    }
}
