package net.palaya.chessanalyzer.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ANALYSIS_SPEC §9.4. The one that matters is [mate never renders as centipawns] — mate scores
 * reach the display fields saturated to ~10000cp by `cpFromMate`, and a UI that formats that as
 * a pawn count shows "+99.5" where it should show "M3".
 */
class EvalFormatTest {

    @Test
    fun `centipawn scores render as signed pawns with one decimal`() {
        assertEquals("+0.9", EvalFormat.score(90))
        assertEquals("-1.4", EvalFormat.score(-140))
        assertEquals("+3.0", EvalFormat.score(300))
        assertEquals("-12.5", EvalFormat.score(-1250))
    }

    @Test
    fun `a positive score always carries its plus sign`() {
        assertEquals("+0.1", EvalFormat.score(10))
        assertEquals("+0.2", EvalFormat.score(20))
    }

    @Test
    fun `values that round to zero render as a plain zero, never as minus zero`() {
        assertEquals("0.0", EvalFormat.score(0))
        assertEquals("0.0", EvalFormat.score(-4))
        assertEquals("0.0", EvalFormat.score(4))
    }

    @Test
    fun `mate never renders as centipawns`() {
        // What GameAnalyzer actually stores for a mate in 3 / mate in 2 against White.
        val whiteMatesCp = WinProbability.cpFromMate(3)
        val blackMatesCp = WinProbability.cpFromMate(-2)
        assertEquals("M3", EvalFormat.score(whiteMatesCp, mateIn = 3))
        assertEquals("-M2", EvalFormat.score(blackMatesCp, mateIn = -2))
    }

    @Test
    fun `a mate already on the board renders as a hash, not as M0`() {
        // The engine reports `mate 0` for a position that IS checkmate. "M0" reads as a mate in
        // no moves; the final frame of a mated game is the most-looked-at frame of any review.
        assertEquals("#", EvalFormat.score(WinProbability.cpFromMate(0), mateIn = 0))
    }

    @Test
    fun `mate wins over the centipawn value even when the two disagree`() {
        assertEquals("M1", EvalFormat.score(0, mateIn = 1))
    }

    @Test
    fun `an absent score renders as a dash rather than a fake zero`() {
        assertEquals("—", EvalFormat.score(null, null))
    }

    @Test
    fun `swing is signed from White's point of view`() {
        assertEquals("+1.2", EvalFormat.swing(120))
        assertEquals("-3.0", EvalFormat.swing(-300))
        assertEquals("0.0", EvalFormat.swing(0))
    }
}
