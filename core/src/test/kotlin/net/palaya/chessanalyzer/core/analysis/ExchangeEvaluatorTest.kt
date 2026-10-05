package net.palaya.chessanalyzer.core.analysis

import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.parseSan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The material words the commentary and the walkthrough use must be true of the material. */
class ExchangeEvaluatorTest {

    @Test
    fun `a gain is named only when it is within a whole piece's value`() {
        assertEquals("a queen", ExchangeEvaluator.describeGain(900))
        assertEquals("a rook", ExchangeEvaluator.describeGain(500))
        assertEquals("a piece", ExchangeEvaluator.describeGain(320))
        assertEquals("a piece", ExchangeEvaluator.describeGain(330))
        assertEquals("a pawn", ExchangeEvaluator.describeGain(100))
        // Between two piece values it is neither: a rook taken for a bishop is +170, not "a pawn".
        assertNull(ExchangeEvaluator.describeGain(170))
        assertNull(ExchangeEvaluator.describeGain(220))
        // A queen taken by a pawn that is taken back is +800: not "a rook", not "a queen".
        assertNull(ExchangeEvaluator.describeGain(800))
        assertNull(ExchangeEvaluator.describeGain(580))
        assertNull(ExchangeEvaluator.describeGain(50))
        assertNull(ExchangeEvaluator.describeGain(0))
        assertNull(ExchangeEvaluator.describeGain(-300))
    }

    @Test
    fun `settled gain charges the opponent's best take-back when it is their move`() {
        // Nxe5 wins a pawn on the board, and dxe5 takes the knight: nothing is won.
        val start = Position.fromFen("6k1/8/3p4/4p3/8/5N2/8/4K3 w - - 0 1")
        val after = start.makeMove(start.parseSan("Nxe5"))
        assertEquals(100, ExchangeEvaluator.netGain(start, after, Color.WHITE))
        assertTrue(ExchangeEvaluator.settledGain(start, after, Color.WHITE) <= 0)
        // When it is the winner's move (the exchange is over) nothing is charged.
        val over = after.makeMove(after.parseSan("dxe5"))
        assertEquals(ExchangeEvaluator.netGain(start, over, Color.WHITE), ExchangeEvaluator.settledGain(start, over, Color.WHITE))
    }
}
