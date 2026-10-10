package net.palaya.chessanalyzer.core.analysis

import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.PieceType
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.parseSan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** V4: the score line's material, chess.com style (captured pieces and "+N"), from one position. */
class MaterialBalanceTest {

    private fun after(vararg sans: String): Position {
        var p = Position.startPosition()
        for (s in sans) p = p.makeMove(p.parseSan(s))
        return p
    }

    @Test
    fun `the starting position is level with nothing captured`() {
        val m = MaterialBalance.of(Position.startPosition())
        assertEquals(39, m.points(Color.WHITE))
        assertEquals(39, m.points(Color.BLACK))
        assertEquals(0, m.advantage)
        assertEquals(0, m.lead(Color.WHITE))
        assertEquals(0, m.lead(Color.BLACK))
        assertTrue(m.captured(Color.WHITE).isEmpty())
        assertTrue(m.captured(Color.BLACK).isEmpty())
        assertEquals(m, MaterialBalance.fromFen(Position.STANDARD_START_FEN))
    }

    @Test
    fun `a capture shows on the captor's side and moves the lead`() {
        // 1.e4 d5 2.exd5: White has taken a pawn.
        val m = MaterialBalance.of(after("e4", "d5", "exd5"))
        assertEquals(listOf(PieceType.PAWN), m.captured(Color.WHITE))
        assertTrue(m.captured(Color.BLACK).isEmpty())
        assertEquals(1, m.advantage)
        assertEquals(1, m.lead(Color.WHITE))
        assertEquals(0, m.lead(Color.BLACK))
        // 2...Qxd5: back to level, each side has a pawn.
        val back = MaterialBalance.of(after("e4", "d5", "exd5", "Qxd5"))
        assertEquals(listOf(PieceType.PAWN), back.captured(Color.WHITE))
        assertEquals(listOf(PieceType.PAWN), back.captured(Color.BLACK))
        assertEquals(0, back.advantage)
    }

    @Test
    fun `captured pieces are listed weakest first, one entry per piece`() {
        // White: king, queen, one pawn. Black: king and two rooks. White took 2 knights, 2 bishops, 8 pawns, the queen.
        val m = MaterialBalance.fromFen("1r2k2r/8/8/8/8/8/4P3/3QK3 w - - 0 1")
        val byWhite = m.captured(Color.WHITE)
        assertEquals(List(8) { PieceType.PAWN } + List(2) { PieceType.KNIGHT } + List(2) { PieceType.BISHOP } + PieceType.QUEEN, byWhite)
        val byBlack = m.captured(Color.BLACK)
        assertEquals(List(7) { PieceType.PAWN } + List(2) { PieceType.KNIGHT } + List(2) { PieceType.BISHOP } + List(2) { PieceType.ROOK }, byBlack)
        // White 1 + 9 = 10, Black 5 + 5 = 10.
        assertEquals(0, m.advantage)
    }

    @Test
    fun `a promoted piece is a pawn that was promoted, not a captured one`() {
        // White has 7 pawns and 2 queens (h-pawn promoted), Black has lost its queen.
        val m = MaterialBalance.fromFen("rnb1kbnr/pppppppp/8/8/8/7Q/PPPPPPP1/RNBQKBNR w - - 0 1")
        assertTrue("no white piece was captured", m.captured(Color.BLACK).isEmpty())
        assertEquals(listOf(PieceType.QUEEN), m.captured(Color.WHITE))
        // White: 7 pawns + 2 knights + 2 bishops + 2 rooks + 2 queens = 7 + 6 + 6 + 10 + 18 = 47; Black 39 - 9 = 30.
        assertEquals(47, m.points(Color.WHITE))
        assertEquals(30, m.points(Color.BLACK))
        assertEquals(17, m.lead(Color.WHITE))
    }

    @Test
    fun `a promotion can never make a capture count negative`() {
        // Three white queens and no pawns: two pawns were promoted, six were captured.
        val m = MaterialBalance.fromFen("4k3/8/8/8/8/8/8/QQQ1K3 w - - 0 1")
        assertEquals(List(6) { PieceType.PAWN } + List(2) { PieceType.KNIGHT } + List(2) { PieceType.BISHOP } + List(2) { PieceType.ROOK },
            m.captured(Color.BLACK))
        assertTrue(m.captured(Color.BLACK).none { it == PieceType.QUEEN })
        assertEquals(27, m.points(Color.WHITE))
    }

    @Test
    fun `the FEN reader takes a bare placement and refuses a broken one`() {
        assertEquals(MaterialBalance.fromFen(Position.STANDARD_START_FEN), MaterialBalance.fromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"))
        assertThrows(IllegalArgumentException::class.java) { MaterialBalance.fromFen("8/8/8") }
        assertThrows(IllegalArgumentException::class.java) { MaterialBalance.fromFen("9/8/8/8/8/8/8/8 w - - 0 1") }
    }

    @Test
    fun `the Immortal Game's final position - Black is a queen, two rooks and a bishop up and still mated`() {
        // Anderssen-Kieseritzky, after 23.Be7#.
        val m = MaterialBalance.fromFen("r1bk3r/p2pBpNp/n4n2/1p1NP2P/6P1/3P4/P1P1K3/q5b1 b - - 1 23")
        // White: pawns a2 c2 d3 e5 g4 h5 (6), knights g7 d5 (6), bishop e7 (3) = 15. Black: pawns a7 b5 d7 f7 h7 (5),
        // knights a6 f6 (6), bishops c8 g1 (6), rooks a8 h8 (10), queen a1 (9) = 36.
        assertEquals(15, m.points(Color.WHITE))
        assertEquals(36, m.points(Color.BLACK))
        assertEquals(21, m.lead(Color.BLACK))
        // White is missing the b- and f-pawns, a bishop, both rooks and the queen; Black three pawns (b, e, g).
        assertEquals(listOf(PieceType.PAWN, PieceType.PAWN, PieceType.BISHOP, PieceType.ROOK, PieceType.ROOK, PieceType.QUEEN),
            m.captured(Color.BLACK))
        assertEquals(List(3) { PieceType.PAWN }, m.captured(Color.WHITE))
    }
}
