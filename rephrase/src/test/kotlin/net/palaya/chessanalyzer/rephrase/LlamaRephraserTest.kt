package net.palaya.chessanalyzer.rephrase

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.palaya.chessanalyzer.core.text.ClaimChecker
import net.palaya.chessanalyzer.core.text.RephrasePrompt
import net.palaya.chessanalyzer.core.text.RephraseRequest
import net.palaya.chessanalyzer.core.text.RephraseResult
import net.palaya.chessanalyzer.core.text.RephraseSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The backend's contract around the native calls (design §2.2, §6.4), with a fake native side. */
class LlamaRephraserTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeNative(var output: (String) -> ByteArray? = { it.toByteArray() }) : RephraseNative {
        var loads = 0
        var frees = 0
        var cancels = 0
        var lastPrefix = ""
        var lastSuffix = ""
        var lastMax = 0
        var loadResult = 7L
        var block: CountDownLatch? = null
        val started = CompletableDeferred<Unit>()
        override fun load(path: String, threads: Int, ctxTokens: Int): Long { loads++; return loadResult }
        override fun free(handle: Long) { frees++ }
        override fun complete(handle: Long, prefix: String, suffix: String, maxTokens: Int): ByteArray? {
            lastPrefix = prefix; lastSuffix = suffix; lastMax = maxTokens
            started.complete(Unit)
            block?.let { if (!it.await(5, TimeUnit.SECONDS)) return null; if (cancels > 0) return null }
            val text = suffix.substringAfter("## Text\n").substringBefore("<|im_end|>")
            return output(text)
        }
        override fun cancel(handle: Long) { cancels++; block?.countDown() }
        override fun tokenCount(handle: Long, text: String): Int = text.split(' ').size
        override fun lastStats(handle: Long) = longArrayOf(700, 1, 60, 1500, 30, 2500)
    }

    private class Journal : LlamaRephraser.LoadJournal {
        var open = false
        var begins = 0
        override fun begin() { open = true; begins++ }
        override fun clear() { open = false }
    }

    private val text = "Nf3 matches the engine's top choice. It hits the loose queen on h4."

    private fun model() = tmp.newFile().apply { writeText("GGUF") }

    @Test
    fun aFaithfulOutputIsAcceptedAndThePromptIsThePinnedOne() = runBlocking {
        val native = FakeNative { "Nf3 matches the engine's top choice, hitting the loose queen on h4.".toByteArray() }
        val r = LlamaRephraser(model(), "qwen2.5-1.5b-instruct-q4_k_m", native = native)
        assertEquals("qwen2.5-1.5b-instruct-q4_k_m@p${RephrasePrompt.VERSION}", r.id)
        val result = r.rephrase(RephraseRequest(RephraseSurface.CARD, text))
        assertEquals(RephraseResult.Accepted("Nf3 matches the engine's top choice, hitting the loose queen on h4."), result)
        assertEquals(RephrasePrompt.prefix(), native.lastPrefix)
        assertEquals(RephrasePrompt.suffix(text, RephraseSurface.CARD), native.lastSuffix)
        assertEquals(RephrasePrompt.maxTokens(ClaimChecker.normalize(text).split(' ').size), native.lastMax)
        assertEquals(1, native.loads)
        r.rephrase(RephraseRequest(RephraseSurface.CARD, text))
        assertEquals("loaded once", 1, native.loads)
        assertTrue(r.lastStats!!.prefixReused)
    }

    @Test
    fun theOutputIsAlwaysJudged() = runBlocking {
        val r = LlamaRephraser(model(), "m", native = FakeNative { "Nf3 is a brilliant move that wins the queen on h4.".toByteArray() })
        val result = r.rephrase(RephraseRequest(RephraseSurface.CARD, text))
        assertTrue(result.toString(), result is RephraseResult.Rejected)
    }

    @Test
    fun aQwen3ModelGetsTheNoThinkSuffix() = runBlocking {
        val native = FakeNative()
        val r = LlamaRephraser(model(), "qwen3-1.7b-q8_0", family = LlamaRephraser.familyOf("qwen3-1.7b-q8_0"), native = native)
        r.rephrase(RephraseRequest(RephraseSurface.CARD, text))
        assertTrue(native.lastSuffix.endsWith("<think>\n\n</think>\n\n"))
    }

    @Test
    fun theLoadJournalStaysOpenUntilAFirstCompletionAndALoadFailureIsUnavailable() = runBlocking {
        val journal = Journal()
        val failing = FakeNative().apply { loadResult = 0 }
        val bad = LlamaRephraser(model(), "m", native = failing, journal = journal)
        assertTrue(bad.rephrase(RephraseRequest(RephraseSurface.CARD, text)) is RephraseResult.Unavailable)
        assertTrue("a failed load leaves the journal: the file is treated as damaged", journal.open)

        val ok = Journal()
        val good = LlamaRephraser(model(), "m", native = FakeNative(), journal = ok)
        good.rephrase(RephraseRequest(RephraseSurface.CARD, text))
        assertFalse(ok.open)
        assertEquals(1, ok.begins)
    }

    @Test
    fun noModelFileMeansUnavailableAndNoLoad() = runBlocking {
        val native = FakeNative()
        val r = LlamaRephraser(tmp.root.resolve("missing.gguf"), "m", native = native)
        assertTrue(r.rephrase(RephraseRequest(RephraseSurface.CARD, text)) is RephraseResult.Unavailable)
        assertEquals(0, native.loads)
    }

    @Test
    fun releaseFreesAndTheNextRequestLoadsAgain() = runBlocking {
        val native = FakeNative()
        val r = LlamaRephraser(model(), "m", native = native)
        r.rephrase(RephraseRequest(RephraseSurface.CARD, text))
        r.release()
        assertEquals(1, native.frees)
        assertFalse(r.isLoaded)
        r.rephrase(RephraseRequest(RephraseSurface.CARD, text))
        assertEquals(2, native.loads)
    }

    @Test
    fun cancellingTheCallerCancelsTheNativeGeneration() = runBlocking {
        val native = FakeNative().apply { block = CountDownLatch(1) }
        val r = LlamaRephraser(model(), "m", native = native)
        val job = async(Dispatchers.Default) { r.rephrase(RephraseRequest(RephraseSurface.CARD, text)) }
        withTimeout(5_000) { native.started.await() }
        job.cancel()
        runCatching { job.await() }
        assertEquals(1, native.cancels)
    }
}
