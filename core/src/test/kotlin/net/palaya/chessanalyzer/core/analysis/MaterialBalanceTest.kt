package net.palaya.chessanalyzer.core.analysis

import java.io.File
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.PieceType
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.pgn.PgnParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaterialBalanceTest {

    private val queen = PieceType.QUEEN
    private val rook = PieceType.ROOK
    private val bishop = PieceType.BISHOP
    private val knight = PieceType.KNIGHT
    private val pawn = PieceType.PAWN

    @Test
    fun `the start position is level at 39 points with nothing taken`() {
        val b = MaterialBalance.fromFen(Position.STANDARD_START_FEN)
        assertEquals(39, b.white)
        assertEquals(39, b.black)
        assertEquals(0, b.difference)
        assertNull(b.ahead)
        assertTrue(b.capturedByWhite.isEmpty())
        assertTrue(b.capturedByBlack.isEmpty())
        assertEquals(0, b.advantage(Color.WHITE))
        assertEquals(0, b.advantage(Color.BLACK))
    }

    @Test
    fun `piece values are 1 3 3 5 9 and the king has none`() {
        assertEquals(1, MaterialBalance.valueOf(pawn))
        assertEquals(3, MaterialBalance.valueOf(knight))
        assertEquals(3, MaterialBalance.valueOf(bishop))
        assertEquals(5, MaterialBalance.valueOf(rook))
        assertEquals(9, MaterialBalance.valueOf(queen))
        assertEquals(0, MaterialBalance.valueOf(PieceType.KING))
    }

    @Test
    fun `after 1 e4 d5 2 exd5 White is a pawn up and has taken one pawn`() {
        val b = MaterialBalance.fromFen("rnbqkbnr/ppp1pppp/8/3P4/8/8/PPPP1PPP/RNBQKBNR b KQkq - 0 2")
        assertEquals(39, b.white)
        assertEquals(38, b.black)
        assertEquals(1, b.difference)
        assertEquals(Color.WHITE, b.ahead)
        assertEquals(listOf(pawn), b.capturedByWhite)
        assertTrue(b.capturedByBlack.isEmpty())
        assertEquals(1, b.advantage(Color.WHITE))
        assertEquals(0, b.advantage(Color.BLACK))
    }

    @Test
    fun `captured pieces come most valuable first and the side behind has no plus`() {
        // White has lost a queen, a rook and a knight; Black has lost two pawns.
        val b = MaterialBalance.fromFen("rnbqkbnr/pppppp2/8/8/8/8/PPPPPPPP/1NB1KB1R w Kkq - 0 1")
        // White: 8 pawns, 1 knight, 2 bishops, 1 rook = 8 + 3 + 6 + 5 = 22; Black: 6 pawns + full back rank = 6 + 6+6+10+9 = 37
        assertEquals(22, b.white)
        assertEquals(37, b.black)
        assertEquals(listOf(queen, rook, knight), b.capturedByBlack)
        assertEquals(listOf(pawn, pawn), b.capturedByWhite)
        assertEquals(Color.BLACK, b.ahead)
        assertEquals(15, b.advantage(Color.BLACK))
        assertEquals(0, b.advantage(Color.WHITE))
    }

    @Test
    fun `an extra promoted queen counts as material but not as a lost pawn`() {
        // White promoted its h-pawn to a second queen: 7 pawns, 2 queens. Nothing of White's has been captured.
        val b = MaterialBalance.fromFen("rnbqkbnr/pppppppp/8/8/8/7Q/PPPPPPP1/RNBQKBNR w KQkq - 0 1")
        assertEquals(7 + 6 + 6 + 10 + 18, b.white)
        assertEquals(47, b.white)
        assertEquals(8, b.difference)
        assertTrue("a promotion is not a capture", b.capturedByBlack.isEmpty())
        // Black's missing pawn is still a capture-less position here: nothing of Black's is gone.
        assertTrue(b.capturedByWhite.isEmpty())
    }

    @Test
    fun `a position and its fen give the same balance`() {
        val fen = "r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4"
        assertEquals(MaterialBalance.fromFen(fen), MaterialBalance.of(Position.fromFen(fen)))
    }

    @Test
    fun `a malformed placement is rejected`() {
        val failed = runCatching { MaterialBalance.fromFen("8/8/8 w - - 0 1") }.exceptionOrNull()
        assertTrue(failed is IllegalArgumentException)
        val badPiece = runCatching { MaterialBalance.fromFen("8/8/8/8/8/8/8/7X w - - 0 1") }.exceptionOrNull()
        assertTrue(badPiece is IllegalArgumentException)
    }

    @Test
    fun `replaying real games the inferred captures equal the captures actually made at every ply`() {
        for (name in listOf("immortal.pgn", "byrne_fischer.pgn", "chesscom_style_game.pgn")) {
            val game = PgnParser.parse(fixture(name)).first()
            // Only the captures a replay can see; none of these games promotes, which the assertion below checks.
            val byWhite = ArrayList<PieceType>()
            val byBlack = ArrayList<PieceType>()
            var position = Position.fromFen(game.startFen ?: Position.STANDARD_START_FEN)
            game.moves.forEachIndexed { i, pgnMove ->
                val move = position.legalMoves().first { it.toUci() == pgnMove.uci }
                assertNull("$name has a promotion; the replay oracle does not cover it", move.promotion)
                move.capturedPiece?.let { if (move.color == Color.WHITE) byWhite += it else byBlack += it }
                position = position.makeMove(move)
                val b = MaterialBalance.of(position)
                assertEquals("$name ply ${i + 1}: taken by White", byWhite.sortedByDescending { MaterialBalance.valueOf(it) }.sorted(), b.capturedByWhite.sorted())
                assertEquals("$name ply ${i + 1}: taken by Black", byBlack.sorted(), b.capturedByBlack.sorted())
                // The difference is what the captures add up to.
                val taken = byWhite.sumOf { MaterialBalance.valueOf(it) } - byBlack.sumOf { MaterialBalance.valueOf(it) }
                assertEquals("$name ply ${i + 1}: difference", taken, b.difference)
            }
        }
    }

    @Test
    fun `the Immortal Game ends with White far behind in material and still winning`() {
        val game = PgnParser.parse(fixture("immortal.pgn")).first()
        val b = MaterialBalance.fromFen(game.moves.last().positionFenAfter)
        // White gave a queen and both rooks and a bishop; Black's pieces are almost all still there.
        assertEquals(Color.BLACK, b.ahead)
        assertTrue(b.advantage(Color.BLACK) >= 10)
        assertTrue(b.capturedByBlack.containsAll(listOf(queen, rook, rook)))
    }

    private fun fixture(name: String): String {
        var dir = File(".").absoluteFile
        repeat(6) {
            val candidate = File(dir, "fixtures/$name")
            if (candidate.exists()) return candidate.readText()
            dir = dir.parentFile ?: return@repeat
        }
        throw IllegalStateException("Could not locate fixtures/$name")
    }
}
