package net.palaya.chessanalyzer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.palaya.chessanalyzer.core.analysis.EngineLineInput
import net.palaya.chessanalyzer.core.analysis.PositionEval
import net.palaya.chessanalyzer.data.GameRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Resumable analysis** — a cancelled run used to discard every eval already computed. These
 * cover the partial-eval cache round trip and, more importantly, the alignment rule that decides
 * how much of a cache is safe to reuse: a misaligned prefix reused wholesale would produce a
 * confidently wrong report rather than an error.
 */
@RunWith(AndroidJUnit4::class)
class ResumeAnalysisTest {

    private fun eval(fen: String) = PositionEval(
        fen = fen,
        lines = listOf(EngineLineInput(multiPv = 1, scoreCp = 10, mateIn = null, depth = 12, pvUci = listOf("e2e4"))),
        depth = 12,
    )

    @Test
    fun partialEvalCacheRoundTripsAndIsClearable() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = GameRepository(context.filesDir)
        val key = "test-resume-${System.nanoTime()}"

        assertNull("no partial cache should exist yet", repo.loadPartialEvalCache(key))

        val partial = listOf(eval("fen-0"), eval("fen-1"), eval("fen-2"))
        repo.savePartialEvalCache(key, partial)

        val loaded = repo.loadPartialEvalCache(key)
        assertNotNull("partial cache should load back", loaded)
        assertEquals(3, loaded!!.size)
        assertEquals("fen-0", loaded[0].fen)
        assertEquals("fen-2", loaded[2].fen)

        repo.clearPartialEvalCache(key)
        assertNull("partial cache should be gone after clear", repo.loadPartialEvalCache(key))
    }

    @Test
    fun resumePrefixUsesAllAlignedEvals() {
        val service = TestApp.analysisService()

        val positions = listOf("a", "b", "c", "d", "e")
        val cached = listOf(eval("a"), eval("b"), eval("c"))

        val prefix = service.usableResumePrefix(positions, cached)

        assertEquals("all three aligned evals should be reusable", 3, prefix.size)
        assertEquals(listOf("a", "b", "c"), prefix.map { it.fen })
    }

    /**
     * The important one: a misaligned cache must be truncated at the mismatch, not used wholesale.
     * Reusing misaligned evals would yield a confidently wrong report rather than an error.
     */
    @Test
    fun resumePrefixStopsAtFirstFenMismatch() {
        val service = TestApp.analysisService()

        val positions = listOf("a", "b", "c", "d")
        // Index 2 disagrees with the game being analysed.
        val cached = listOf(eval("a"), eval("b"), eval("WRONG"), eval("d"))

        val prefix = service.usableResumePrefix(positions, cached)

        assertEquals("must stop before the mismatch", 2, prefix.size)
        assertEquals(listOf("a", "b"), prefix.map { it.fen })
    }

    @Test
    fun resumePrefixIgnoresCacheLongerThanTheGame() {
        val service = TestApp.analysisService()

        val positions = listOf("a", "b")
        val cached = listOf(eval("a"), eval("b"), eval("c"), eval("d"))

        val prefix = service.usableResumePrefix(positions, cached)

        assertEquals("cannot reuse more evals than the game has positions", 2, prefix.size)
    }

    @Test
    fun resumePrefixIsEmptyForAnEmptyCache() {
        val service = TestApp.analysisService()
        assertTrue(service.usableResumePrefix(listOf("a", "b"), emptyList()).isEmpty())
    }
}
