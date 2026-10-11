package net.palaya.chessanalyzer.ui.model

import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.PieceType
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.Square
import net.palaya.chessanalyzer.core.chess.parseSan
import net.palaya.chessanalyzer.core.chess.perft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import net.palaya.chessanalyzer.ui.model.WaitingTestSupport as T

/**
 * The facts of waiting/facts.json that a computer can check, checked by `:core` (docs/WAITING_CONTENT.md marks
 * these "computed"). The other facts are history and records and carry a source instead.
 */
class WaitingFactsComputedTest {

    private val facts by lazy { parseWaitingFacts(T.assetText(WaitingAssets.FACTS)).associateBy { it.id } }

    private fun play(sans: String, from: Position = Position.startPosition()): Position {
        var pos = from
        for (san in sans.trim().split(Regex("\\s+"))) pos = pos.makeMove(pos.parseSan(san))
        return pos
    }

    private fun text(id: String) = facts.getValue(id).text

    @Test
    fun whiteHasTwentyFirstMoves_sixteenPawnAndFourKnight() {
        val moves = Position.startPosition().legalMoves()
        assertEquals(20, moves.size)
        assertEquals(16, moves.count { it.piece == PieceType.PAWN })
        assertEquals(4, moves.count { it.piece == PieceType.KNIGHT })
        assertTrue(text("first-moves").contains("exactly 20"))
    }

    @Test
    fun moveSequenceCountsOfTheStartPosition() {
        val start = Position.startPosition()
        assertEquals(400L, start.perft(2))
        assertEquals(197_281L, start.perft(4))
        assertEquals(4_865_609L, start.perft(5))
        assertTrue(text("perft-2").contains("400") && text("perft-2").contains("197,281"))
        assertTrue(text("perft-5").contains("4,865,609"))
    }

    /** A position's identity for "different positions": placement, side, castling, and an en-passant square only if a capture is possible. */
    private fun identity(p: Position): String {
        val fields = p.toFen().split(' ')
        val ep = if (p.legalMoves().any { it.isEnPassant }) fields[3] else "-"
        return "${fields[0]} ${fields[1]} ${fields[2]} $ep"
    }

    @Test
    fun fourHalfMovesReach72078DifferentPositions() {
        var layer = setOf(identity(Position.startPosition())) to listOf(Position.startPosition())
        val counts = ArrayList<Int>()
        repeat(4) {
            val seen = HashSet<String>()
            val next = ArrayList<Position>()
            for (p in layer.second) for (m in p.legalMoves()) {
                val q = p.makeMove(m)
                if (seen.add(identity(q))) next += q
            }
            counts += seen.size
            layer = seen to next
        }
        assertEquals(listOf(20, 400, 5_362, 72_078), counts)
        assertTrue(text("transpositions").contains("72,078") && text("transpositions").contains("197,281"))
    }

    @Test
    fun eightQueensHave92SolutionsAnd12DistinctOnes() {
        val solutions = ArrayList<IntArray>()
        fun place(row: Int, cols: IntArray) {
            if (row == 8) {
                solutions += cols.clone()
                return
            }
            for (c in 0 until 8) {
                if ((0 until row).all { r -> cols[r] != c && kotlin.math.abs(cols[r] - c) != row - r }) {
                    cols[row] = c
                    place(row + 1, cols)
                }
            }
        }
        place(0, IntArray(8))
        assertEquals(92, solutions.size)
        // symmetry classes under the 8 rotations/reflections of the board
        fun images(s: IntArray): List<List<Int>> {
            val cells = s.mapIndexed { r, c -> r to c }
            val maps: List<(Pair<Int, Int>) -> Pair<Int, Int>> = listOf(
                { (r, c) -> r to c }, { (r, c) -> c to 7 - r }, { (r, c) -> 7 - r to 7 - c }, { (r, c) -> 7 - c to r },
                { (r, c) -> r to 7 - c }, { (r, c) -> 7 - r to c }, { (r, c) -> c to r }, { (r, c) -> 7 - c to 7 - r },
            )
            return maps.map { f -> cells.map(f).sortedBy { it.first }.map { it.second } }
        }
        val classes = HashSet<List<Int>>()
        for (s in solutions) classes += images(s).minWithOrNull(compareBy<List<Int>> { it.joinToString("") })!!
        assertEquals(12, classes.size)
        assertTrue(text("eight-queens").contains("92") && text("eight-queens").contains("12"))
    }

    @Test
    fun chess960HasExactly960StartingArrangements() {
        // Bishops on opposite colours, the king somewhere between the two rooks.
        val pieces = listOf('B', 'B', 'N', 'N', 'R', 'R', 'Q', 'K')
        val arrangements = HashSet<String>()
        fun permute(rest: List<Char>, acc: String) {
            if (rest.isEmpty()) {
                arrangements += acc
                return
            }
            for (i in rest.indices) {
                if (i > 0 && rest[i] == rest[i - 1]) continue
                permute(rest.filterIndexed { j, _ -> j != i }, acc + rest[i])
            }
        }
        permute(pieces.sorted(), "")
        val valid = arrangements.filter { a ->
            val bishops = a.indices.filter { a[it] == 'B' }
            val rooks = a.indices.filter { a[it] == 'R' }
            val king = a.indexOf('K')
            bishops[0] % 2 != bishops[1] % 2 && king in (rooks[0] + 1) until rooks[1]
        }
        assertEquals(960, valid.size)
        assertTrue(text("chess960").contains("960"))
    }

    @Test
    fun foolsMateIsMateOnTheFourthPlyAndNoGameIsMatedSooner() {
        val fools = play("f3 e5 g4 Qh4#")
        assertTrue(fools.isCheckmate())
        // no sequence of up to three plies ends in checkmate
        fun anyMate(p: Position, depth: Int): Boolean {
            if (p.isCheckmate()) return true
            if (depth == 0) return false
            return p.legalMoves().any { anyMate(p.makeMove(it), depth - 1) }
        }
        assertFalse(anyMate(Position.startPosition(), 3))
        assertTrue(text("fools-mate").contains("1.f3 e5 2.g4 Qh4#"))
    }

    @Test
    fun scholarsMateIsMateInFourMoves() {
        assertTrue(play("e4 e5 Bc4 Nc6 Qh5 Nf6 Qxf7#").isCheckmate())
        assertTrue(text("scholars-mate").contains("1.e4 e5 2.Bc4 Nc6 3.Qh5 Nf6 4.Qxf7#"))
    }

    @Test
    fun loydsTenMoveStalemate() {
        val sans = "e3 a5 Qh5 Ra6 Qxa5 h5 h4 Rah6 Qxc7 f6 Qxd7+ Kf7 Qxb7 Qd3 Qxb8 Qh7 Qxc8 Kg6 Qe6"
        assertEquals(19, sans.split(' ').size)
        val end = play(sans)
        assertTrue(end.isStalemate())
        assertEquals(Color.BLACK, end.sideToMove)
        assertTrue(text("loyd-stalemate").contains("ten moves") && text("loyd-stalemate").contains("1.e3 a5 2.Qh5 Ra6"))
    }

    @Test
    fun aPositionWith218LegalMoves() {
        val p = Position.fromFen("R6R/3Q4/1Q4Q1/4Q3/2Q4Q/Q4Q2/pp1Q4/kBNN1KB1 w - - 0 1")
        assertEquals(218, p.legalMoves().size)
        assertTrue(text("max-moves").contains("218"))
    }

    @Test
    fun enPassantIsTheOnlyCaptureOntoAnEmptySquare() {
        val start = play("e4 a6 e5 d5")
        var enPassantSeen = 0
        fun walk(p: Position, depth: Int) {
            for (m in p.legalMoves()) {
                val target = p.pieceAt(m.to)
                if (m.isEnPassant) {
                    enPassantSeen++
                    assertEquals(null, target)
                } else if (m.isCapture) {
                    assertNotNull("capture onto an empty square: $m", target)
                }
                if (depth > 1) walk(p.makeMove(m), depth - 1)
            }
        }
        walk(start, 3)
        assertTrue(enPassantSeen > 0)
        assertTrue(text("en-passant-unique").contains("only capture"))
    }

    @Test
    fun theLoneKingAndMinorPieceEndingsAreDeadPositions() {
        for (fen in listOf("8/8/8/4k3/8/8/8/K7 w - - 0 1", "8/8/8/4k3/8/8/8/KB6 w - - 0 1", "8/8/8/4k3/8/8/8/KN6 w - - 0 1")) {
            assertTrue(fen, Position.fromFen(fen).isInsufficientMaterial())
        }
        assertFalse(Position.fromFen("8/8/8/4k3/8/8/8/KNN5 w - - 0 1").isInsufficientMaterial())
    }

    @Test
    fun aKnightAlwaysChangesSquareColour() {
        for (sq in 0..63) {
            val from = Square(sq)
            for (df in listOf(-2, -1, 1, 2)) for (dr in listOf(-2, -1, 1, 2)) {
                if (kotlin.math.abs(df) == kotlin.math.abs(dr)) continue
                val f = from.file + df
                val r = from.rank + dr
                if (f !in 0..7 || r !in 0..7) continue
                assertTrue((from.file + from.rank) % 2 != (f + r) % 2)
            }
        }
        assertTrue(text("knight-colour").contains("changes the colour"))
    }

    @Test
    fun theCornersOfTheBoardMatchTheOrientationFact() {
        // a1 is dark; each player's right-hand corner (h1 for White, a8 for Black) is light
        fun dark(file: Int, rank: Int) = (file + rank) % 2 == 0
        assertTrue(dark(0, 0))
        assertFalse(dark(7, 0))
        assertFalse(dark(0, 7))
        assertTrue(text("board-orientation").contains("a1 a dark square"))
    }

    @Test
    fun kingsNeverStandOnNeighbouringSquares() {
        fun kingsApart(p: Position): Boolean {
            val a = p.kingSquare(Color.WHITE)
            val b = p.kingSquare(Color.BLACK)
            return kotlin.math.abs(a.file - b.file) > 1 || kotlin.math.abs(a.rank - b.rank) > 1
        }
        fun walk(p: Position, depth: Int) {
            for (m in p.legalMoves()) {
                val q = p.makeMove(m)
                assertTrue(kingsApart(q))
                if (depth > 1) walk(q, depth - 1)
            }
        }
        walk(Position.startPosition(), 3)
        walk(Position.fromFen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"), 3)
        // and a king ending, where the kings come close
        walk(Position.fromFen("8/8/8/3k4/8/3K4/8/8 w - - 0 1"), 4)
        assertTrue(text("kings-apart").contains("never stand on neighbouring squares"))
    }
}
