package net.palaya.chessanalyzer.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Analysing screen's bar (spec §8.4, §8.5): unchanged for a fixed depth, shared by Auto's two passes, never 100% early. */
class AnalysisFractionTest {

    private val end = AnalysisFraction.START + AnalysisFraction.SPAN

    private fun auto(total: Int = 67) = AnalysisFraction(
        total, autoDepth = true,
        baseTypicalNodes = SearchBudget.QUICK.typicalNodes,
        deepTypicalNodes = AutoDepthBudget.TYPICAL_DEEPENED_NODES,
        expectedDeepPositions = AnalysisFraction.expectedDeepPositions(total),
    )

    @Test
    fun aFixedDepthRunFillsTheBarByPositionsExactlyAsBefore() {
        val bar = AnalysisFraction(67, autoDepth = false, SearchBudget.DEEP.typicalNodes, 0L, 0)
        assertEquals(1.0, bar.firstPassShare, 0.0)
        for (done in 0..67) assertEquals(0.3f + 0.68f * done / 67, bar.firstPass(done), 1e-6f)
    }

    @Test
    fun autosFirstPassEndsWellShortOfTheEndAndTheSecondPassFillsTheRest() {
        val bar = auto()
        val share = bar.firstPassShare
        assertTrue("share $share", share in 0.02..0.5)
        val afterFirst = bar.firstPass(67)
        assertEquals(AnalysisFraction.START + AnalysisFraction.SPAN * share.toFloat(), afterFirst, 1e-5f)
        assertTrue(afterFirst < end - 0.1f)
        assertTrue(bar.secondPass(5, 20) > afterFirst)
        assertEquals(end, bar.secondPass(20, 20), 1e-6f)
    }

    @Test
    fun aLaterRoundThatPicksMorePositionsNeverMovesTheBarBack() {
        val bar = auto()
        bar.firstPass(67)
        val atEndOfRoundOne = bar.secondPass(20, 20)
        // Round 2 picks 6 more: 20 of 26 would be lower; the bar holds, then rises again.
        assertEquals(atEndOfRoundOne, bar.secondPass(20, 26), 0f)
        assertEquals(atEndOfRoundOne, bar.secondPass(26, 26), 0f)
        assertTrue(atEndOfRoundOne <= end)
    }

    @Test
    fun theBarNeverPassesTheEngineEndBeforeTheReport() {
        val bar = auto()
        assertTrue(bar.firstPass(1_000) <= end)
        assertTrue(bar.secondPass(1_000, 3) <= end)
        assertTrue(bar.secondPass(0, 0) <= end)
    }

    @Test
    fun theExpectedSecondPassIsTheCalibratedShareOfTheGame() {
        assertEquals(Math.round(67 * AutoDepthBudget.EXPECTED_DEEP_FRACTION).toInt(), AnalysisFraction.expectedDeepPositions(67))
        assertEquals(0, AnalysisFraction.expectedDeepPositions(0))
    }
}
