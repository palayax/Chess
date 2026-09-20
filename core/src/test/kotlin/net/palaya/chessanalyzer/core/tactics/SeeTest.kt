package net.palaya.chessanalyzer.core.tactics

import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.Square
import net.palaya.chessanalyzer.core.chess.parseUci
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Static Exchange Evaluation against hand-computed numbers.
 *
 * Every expectation below is worked out by hand in the comment beside it, in the order the
 * swap actually happens. That is the point of the file: a SEE that is merely "plausible" is
 * useless, because the classification rules in ANALYSIS_SPEC 2 key off exact thresholds
 * (`see(move) <= -200` for a sacrifice), so an off-by-one-piece error changes verdicts.
 */
class SeeTest {

    private val see = StaticExchangeEvaluator()

    private fun see(fen: String, uci: String): Int {
        val pos = Position.fromFen(fen)
        return see.see(pos, pos.parseUci(uci))
    }

    // -----------------------------------------------------------------------
    // Baseline swaps
    // -----------------------------------------------------------------------

    @Test
    fun evenPawnTradeIsZero() {
        // exd5 wins a pawn (+100), cxd5 takes it straight back (-100). Net 0.
        assertEquals(0, see("4k3/8/2p5/3p4/4P3/8/8/4K3 w - - 0 1", "e4d5"))
    }

    @Test
    fun rookGrabbingAPawnDefendedByAPawnLosesTheExchangeAndMore() {
        // Rxd5 wins a pawn (+100) and loses a rook (-500) to cxd5. Net -400.
        assertEquals(-400, see("4k3/8/2p5/3p4/8/8/3R4/4K3 w - - 0 1", "d2d5"))
    }

    @Test
    fun capturingAnUndefendedQueenWinsItOutright() {
        assertEquals(900, see("3q3k/8/8/8/8/8/3R4/4K3 w - - 0 1", "d2d8"))
    }

    @Test
    fun quietMoveOntoAnAttackedSquareCostsThePieceItHangs() {
        // Qa4 is met by bxa4; nothing recaptures, so the move simply loses the queen.
        assertEquals(-900, see("6k1/6pp/8/1p6/8/8/6PP/3Q2K1 w - - 0 1", "d1a4"))
    }

    @Test
    fun quietMoveToASafeSquareIsZero() {
        assertEquals(0, see("6k1/6pp/8/8/8/8/6PP/3Q2K1 w - - 0 1", "d1d5"))
    }

    // -----------------------------------------------------------------------
    // X-ray re-evaluation: the whole reason SEE is not just "count the attackers"
    // -----------------------------------------------------------------------

    @Test
    fun xrayDoubledRooksTurnALosingCaptureIntoAWinningOne() {
        // White Rd2/Rd1 doubled, black Rd8 defends the d5 pawn.
        //   Rxd5 +100, Rxd5 -500, Rxd5 (the SECOND white rook, seen only once d2 empties) +500.
        // Net +100. Without x-ray handling this reads as -400.
        assertEquals(100, see("3r3k/8/8/3p4/8/8/3R4/3R2K1 w - - 0 1", "d2d5"))
    }

    @Test
    fun xrayQueenBehindBishopOnTheLongDiagonal() {
        // Bxf6 +100, gxf6 -330 (running total -230), Qxf6 +100 -> -130. White prefers to
        // carry on to -130 rather than stop at -230, so SEE reports -130.
        assertEquals(-130, see("6k1/6pp/5p2/8/8/8/1B6/Q5K1 w - - 0 1", "b2f6"))
    }

    @Test
    fun xrayOnBothSidesOfTheFile() {
        // White Rd2/Rd1 against black Rd7/Rd8, target a knight on d5:
        //   Rxd5 +320, Rxd5 -500 (-180), Rxd5 +500 (+320), Rxd5 -500 (-180).
        // Black takes the last one because leaving it would hand White +320.
        assertEquals(-180, see("3r2k1/3r2pp/8/3n4/8/8/3R4/3R2K1 w - - 0 1", "d2d5"))
    }

    // -----------------------------------------------------------------------
    // Promotion and king rules
    // -----------------------------------------------------------------------

    @Test
    fun promotingCaptureBanksTheRookAndTheNewQueen() {
        // bxa8=Q takes a rook (+500) and upgrades a pawn to a queen (+800). Nothing recaptures.
        assertEquals(1300, see("r6k/1P6/8/8/8/8/8/6K1 w - - 0 1", "b7a8q"))
    }

    @Test
    fun kingMayNotRecaptureWhileTheSquareIsStillGuarded() {
        // Rxd7 takes a pawn defended only by the black king. The king cannot recapture,
        // because the second white rook on d1 still covers d7 once d2 empties - so the swap
        // stops at +100. Without the king rule the loop would let Kxd7 happen and report -400.
        assertEquals(100, see("4k3/3p4/8/8/8/8/3R4/3R2K1 w - - 0 1", "d2d7"))
    }

    // -----------------------------------------------------------------------
    // isHanging, per ANALYSIS_SPEC 5.2
    // -----------------------------------------------------------------------

    @Test
    fun undefendedQueenIsHanging() {
        val pos = Position.fromFen("3q3k/8/8/8/8/8/3R4/4K3 w - - 0 1")
        assertTrue(see.isHanging(pos, Square.fromAlgebraic("d8")))
    }

    @Test
    fun adequatelyDefendedKnightIsNotHanging() {
        // Rd1 attacks the d5 knight, but it is held twice by pawns: Rxd5 loses material.
        val pos = Position.fromFen("6k1/6pp/2p1p3/3n4/8/8/6PP/3R2K1 w - - 0 1")
        assertFalse(see.isHanging(pos, Square.fromAlgebraic("d5")))
    }

    @Test
    fun ourOwnPieceIsNeverHangingToUs() {
        val pos = Position.fromFen("3q3k/8/8/8/8/8/3R4/4K3 w - - 0 1")
        assertFalse(see.isHanging(pos, Square.fromAlgebraic("d2")))
    }

    @Test
    fun unattackedPieceIsNotHanging() {
        val pos = Position.fromFen("6k1/6pp/8/8/8/8/6PP/3Q2K1 w - - 0 1")
        assertFalse(see.isHanging(pos, Square.fromAlgebraic("g7")))
    }
}
