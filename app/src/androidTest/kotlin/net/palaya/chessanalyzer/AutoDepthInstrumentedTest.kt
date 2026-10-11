package net.palaya.chessanalyzer

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import net.palaya.chessanalyzer.core.analysis.PositionEval
import net.palaya.chessanalyzer.data.AnalysisService
import net.palaya.chessanalyzer.ui.model.AnalysisPhase
import net.palaya.chessanalyzer.ui.model.AnalysisStrength
import net.palaya.chessanalyzer.ui.model.AutoDepthBudget
import net.palaya.chessanalyzer.ui.model.EngineSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Auto-depth end to end with the real engine (ANALYSIS_SPEC §8.5): the Scholar's mate at Auto. Its blunder
 * (3...Nf6??) and the mate are critical, so the second pass searches positions again at Deep; everything else keeps
 * its Quick result. Checks what the host cannot: the two passes through `AnalysisService` and the engine, the
 * eval caches they leave (a Quick result and an Auto one), the progress bar, and `onPositionEvaluated`.
 */
@RunWith(AndroidJUnit4::class)
class AutoDepthInstrumentedTest {

    private val pgn = """
        [Event "Scholar's mate"]
        [White "White"]
        [Black "Black"]
        [Result "1-0"]

        1. e4 e5 2. Bc4 Nc6 3. Qh5 Nf6 4. Qxf7# 1-0
    """.trimIndent()

    private val settings = EngineSettings(depth = AnalysisStrength.AUTO.depth, multiPv = 3, autoDepth = true)

    @Test
    fun autoSearchesTheCriticalPositionsAgainAtDeepAndCachesBothResults() = runBlocking {
        TestApp.ensureSetUp()
        val repo = TestApp.app.gameRepository
        val prefix = repo.currentNetPrefix()
        val baseKey = repo.cacheKey(pgn, settings.depth, 3, settings.searchBudget.cacheKeyPart)
        val autoKey = repo.cacheKey(pgn, settings.depth, 3, AutoDepthBudget.cacheKeyPart)
        // A pristine start: earlier runs of this test leave both results behind.
        val dir = repo.evalCache.dirFor(prefix)
        for (key in listOf(baseKey, autoKey)) {
            File(dir, "$key.json").delete()
            repo.clearPartialEvalCache(key, prefix)
        }

        val reported = ArrayList<Pair<Int, PositionEval>>()
        val fractions = ArrayList<Float>()
        var sawSecondPass = false
        val outcome = TestApp.analysisService().analyze(
            pgnText = pgn,
            username = "",
            settings = settings,
            onProgress = { p ->
                if (p.phase == AnalysisPhase.ANALYZING_MOVES) fractions += p.fractionComplete
                if (p.deepPassTotal > 0) sawSecondPass = true
            },
            onPositionEvaluated = { i, e -> reported += i to e },
        )
        assertTrue("analysis did not succeed: $outcome", outcome is AnalysisService.Outcome.Success)
        assertEquals(7, (outcome as AnalysisService.Outcome.Success).report.annotations.size)

        val autoOrNull = repo.loadEvalCache(autoKey, prefix)
        val baseOrNull = repo.loadEvalCache(baseKey, prefix)
        assertNotNull("the Auto result is cached", autoOrNull)
        assertNotNull("the first pass is cached as a Quick result", baseOrNull)
        val auto = autoOrNull!!
        val base = baseOrNull!!
        assertEquals(8, auto.size)
        assertTrue("the first pass is a Quick result", base.all { it.requestedDepth == AnalysisStrength.QUICK.depth })
        val deepened = auto.indices.filter { auto[it].requestedDepth == AnalysisStrength.DEEP.depth }
        assertTrue("the blunder 3...Nf6 (positions 5 and 6) is searched again: $deepened", deepened.containsAll(listOf(5, 6)))
        assertTrue("the checkmate on the board (7) is never searched", 7 !in deepened)
        for (i in auto.indices) if (i !in deepened) assertEquals("position $i keeps its first-pass result", base[i], auto[i])

        // Every position reported, the deepened ones twice: the first pass's result, then the deep one.
        assertEquals((0..7).toSet(), reported.map { it.first }.toSet())
        for (i in deepened) {
            val calls = reported.filter { it.first == i }.map { it.second }
            assertEquals("position $i", 2, calls.size)
            assertEquals(AnalysisStrength.QUICK.depth, calls[0].requestedDepth)
            assertEquals(auto[i], calls[1])
        }

        // The bar rises, shows the second pass, and stays short of the end until the report.
        assertTrue(sawSecondPass)
        assertTrue("never backwards: $fractions", fractions.zipWithNext().all { (a, b) -> b >= a })
        assertTrue("never full during the analysis: $fractions", fractions.all { it < 1f })

        // Reopened: the Auto result comes from the cache, each position reported once with its final result.
        val again = ArrayList<Pair<Int, PositionEval>>()
        val second = TestApp.analysisService().analyze(pgnText = pgn, username = "", settings = settings, onPositionEvaluated = { i, e -> again += i to e })
        assertTrue(second is AnalysisService.Outcome.Success)
        assertEquals(auto.indices.map { it to auto[it] }, again)
    }
}
