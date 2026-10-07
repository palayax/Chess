package net.palaya.chessanalyzer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.palaya.chessanalyzer.core.analysis.EngineLineInput
import net.palaya.chessanalyzer.core.analysis.PositionEval
import net.palaya.chessanalyzer.data.GameRepository
import net.palaya.chessanalyzer.data.PendingAnalysisStore
import net.palaya.chessanalyzer.ui.model.AnalysisStrength
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    // ---- F1: a killed process resumes instead of losing the game ----

    /** The request written when an analysis starts is what a new process reads back to resume it. */
    @Test
    fun thePendingRequestRoundTripsAndIsClearedOnlyForItsOwnGame() {
        val dir = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "pending-${System.nanoTime()}")
            .apply { mkdirs() }
        try {
            val store = PendingAnalysisStore(dir)
            assertNull(store.load())
            val request = PendingAnalysisStore.Request(
                gameId = "imported-1-1",
                pgnText = "[White \"Fouchon\"]\n\n1. e4 d5 0-1",
                depth = 18,
                multiPv = 3,
                username = "Sonarmind",
                startedAtMs = 1_791_288_000_000L,
            )
            store.save(request)
            assertEquals(request, store.load())
            store.clear("some-other-game")
            assertEquals("another game's clear leaves it", request, store.load())
            store.clear(request.gameId)
            assertNull(store.load())
            assertFalse(java.io.File(dir, PendingAnalysisStore.FILE_NAME).exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aDamagedPendingRequestIsDroppedNotFatal() {
        val dir = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "pending-${System.nanoTime()}")
            .apply { mkdirs() }
        try {
            java.io.File(dir, PendingAnalysisStore.FILE_NAME).writeText("{\"gameId\": \"x\", \"pgnTe")
            assertNull(PendingAnalysisStore(dir).load())
            assertFalse(java.io.File(dir, PendingAnalysisStore.FILE_NAME).exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    /** The capped flag survives the eval cache, so a reopened game still says how many were capped. */
    @Test
    fun theRequestedDepthRoundTripsThroughTheEvalCache() = runBlocking {
        val repo = GameRepository(InstrumentationRegistry.getInstrumentation().targetContext.filesDir)
        val key = "test-capped-${System.nanoTime()}"
        val capped = eval("fen-0").copy(depth = 16, requestedDepth = 18)
        val full = eval("fen-1").copy(depth = 18, requestedDepth = 18)
        val legacy = eval("fen-2")
        repo.savePartialEvalCache(key, listOf(capped, full, legacy))
        val loaded = repo.loadPartialEvalCache(key)!!
        repo.clearPartialEvalCache(key)
        assertEquals(listOf(true, false, false), loaded.map { it.isCapped })
        assertEquals(listOf(18, 18, null), loaded.map { it.requestedDepth })
    }

    /** A result is reused only under identical limits: the budget is part of the cache key. */
    @Test
    fun theSearchBudgetIsPartOfTheEvalCacheKey() {
        val repo = GameRepository(InstrumentationRegistry.getInstrumentation().targetContext.filesDir)
        val pgn = "1. e4 e5 *"
        val deep = repo.cacheKey(pgn, 18, 3, AnalysisStrength.DEEP.budget.cacheKeyPart)
        assertEquals(deep, repo.cacheKey(pgn, 18, 3, AnalysisStrength.DEEP.budget.cacheKeyPart))
        assertNotEquals("unbudgeted (pre-F1) results are not reused", repo.cacheKey(pgn, 18, 3), deep)
        assertNotEquals(deep, repo.cacheKey(pgn, 18, 3, AnalysisStrength.DEEP.budget.copy(nodes = 1L).cacheKeyPart))
        assertNotEquals(deep, repo.cacheKey(pgn, 18, 3, AnalysisStrength.DEEP.budget.copy(movetimeMs = 1L).cacheKeyPart))
    }
}
