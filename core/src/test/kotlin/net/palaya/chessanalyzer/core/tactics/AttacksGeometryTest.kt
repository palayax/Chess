package net.palaya.chessanalyzer.core.tactics

import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.Square
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ANALYSIS_SPEC 5.2 primitives: attack tables and the line geometry pins are built on. */
class AttacksGeometryTest {

    private fun sq(name: String) = Square.fromAlgebraic(name)
    private fun names(squares: List<Square>) = squares.map { it.toString() }.sorted()

    // -----------------------------------------------------------------------
    // Geometry
    // -----------------------------------------------------------------------

    @Test
    fun lineBetweenWalksRanksFilesAndDiagonals() {
        assertEquals(listOf("b1", "c1", "d1"), names(lineBetween(sq("a1"), sq("e1"))))
        assertEquals(listOf("d3", "d4", "d5"), names(lineBetween(sq("d2"), sq("d6"))))
        assertEquals(listOf("b2", "c3", "d4"), names(lineBetween(sq("a1"), sq("e5"))))
        assertEquals(listOf("f2", "g3"), names(lineBetween(sq("h4"), sq("e1"))))
    }

    @Test
    fun lineBetweenIsEmptyForUnalignedOrAdjacentSquares() {
        assertTrue(lineBetween(sq("a1"), sq("b3")).isEmpty())
        assertTrue(lineBetween(sq("d4"), sq("d5")).isEmpty())
        assertTrue(lineBetween(sq("d4"), sq("d4")).isEmpty())
    }

    @Test
    fun isOnLineRecognisesAllFourLineTypes() {
        assertTrue(isOnLine(sq("a1"), sq("d1"), sq("h1")))   // rank
        assertTrue(isOnLine(sq("c2"), sq("c5"), sq("c8")))   // file
        assertTrue(isOnLine(sq("a1"), sq("d4"), sq("h8")))   // diagonal
        assertTrue(isOnLine(sq("h1"), sq("e4"), sq("a8")))   // anti-diagonal
        assertFalse(isOnLine(sq("a1"), sq("d4"), sq("h7")))
    }

    @Test
    fun isBetweenIsDirectionalAndStrict() {
        assertTrue(isBetween(sq("d1"), sq("d4"), sq("d8")))
        assertFalse(isBetween(sq("d1"), sq("d8"), sq("d4")))  // beyond, not between
        assertFalse(isBetween(sq("d1"), sq("d1"), sq("d8")))  // endpoints excluded
        assertFalse(isBetween(sq("d1"), sq("e4"), sq("d8")))  // off the line
    }

    @Test
    fun pieceValuesMatchTheSpec() {
        assertEquals(100, valueOf(net.palaya.chessanalyzer.core.chess.PieceType.PAWN))
        assertEquals(320, valueOf(net.palaya.chessanalyzer.core.chess.PieceType.KNIGHT))
        assertEquals(330, valueOf(net.palaya.chessanalyzer.core.chess.PieceType.BISHOP))
        assertEquals(500, valueOf(net.palaya.chessanalyzer.core.chess.PieceType.ROOK))
        assertEquals(900, valueOf(net.palaya.chessanalyzer.core.chess.PieceType.QUEEN))
        assertEquals(20000, valueOf(net.palaya.chessanalyzer.core.chess.PieceType.KING))
    }

    // -----------------------------------------------------------------------
    // Attack tables
    // -----------------------------------------------------------------------

    @Test
    fun pawnsAttackOnlyDiagonally() {
        val pos = Position.fromFen("4k3/8/8/8/8/8/4P3/4K3 w - - 0 1")
        assertEquals(listOf("d3", "f3"), names(Attacks.attacksFrom(pos, sq("e2"))))
    }

    @Test
    fun sliderControlStopsAtTheFirstBlockerButIncludesIt() {
        // Rd1 with its own pawn on d4: it controls d2, d3 and d4 (defending the pawn), no further.
        val pos = Position.fromFen("4k3/8/8/8/3P4/8/8/3RK3 w - - 0 1")
        val controlled = names(Attacks.attacksFrom(pos, sq("d1")))
        assertTrue(controlled.containsAll(listOf("d2", "d3", "d4")))
        assertFalse(controlled.contains("d5"))
    }

    @Test
    fun attackersOfFindsEveryAttackerOfASquare() {
        // d5 is attacked by the c4 pawn, the f4 knight, the g2 bishop and the d1 rook.
        val pos = Position.fromFen("4k3/8/8/8/2P2N2/8/6B1/3RK3 w - - 0 1")
        assertEquals(
            listOf("c4", "d1", "f4", "g2"),
            names(Attacks.attackersOf(pos, sq("d5"), Color.WHITE))
        )
    }

    @Test
    fun attackersOfSeesThroughNothingAndIgnoresTheWrongColour() {
        val pos = Position.fromFen("3rk3/8/8/3n4/8/8/8/3RK3 w - - 0 1")
        // The white rook is blocked by the knight, so it attacks d5 but not d8.
        assertEquals(listOf("d1"), names(Attacks.attackersOf(pos, sq("d5"), Color.WHITE)))
        assertTrue(Attacks.attackersOf(pos, sq("d8"), Color.WHITE).isEmpty())
    }

    @Test
    fun defendersOfReadsTheColourOffTheOccupant() {
        val pos = Position.fromFen("4k3/8/2p5/3p4/8/8/8/4K3 w - - 0 1")
        assertEquals(listOf("c6"), names(Attacks.defendersOf(pos, sq("d5"))))
    }

    @Test
    fun attackMapCoversEveryPieceOfOneColourAndNoOther() {
        val pos = Position.startPosition()
        val white = Attacks.attackMap(pos, Color.WHITE)
        assertEquals(16, white.size)
        assertTrue(white.keys.all { pos.pieceAt(it)!!.color == Color.WHITE })
        // The b1 knight eyes a3, c3 and d2 from the starting array.
        assertEquals(listOf("a3", "c3", "d2"), names(white[sq("b1")]!!))
    }

    @Test
    fun attackPairsOnlyRecordsEnemyOccupiedTargets() {
        val pos = Position.fromFen("4k3/8/8/3p4/8/8/8/3RK3 w - - 0 1")
        val pairs = Attacks.attackPairs(pos, Color.WHITE)
        assertTrue(pairs.contains(sq("d1") to sq("d5")))
        assertFalse(pairs.any { it.second == sq("d3") }) // empty square, not a target
    }
}
