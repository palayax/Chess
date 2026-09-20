package net.palaya.chessanalyzer.core.chess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SanTest {

    @Test
    fun parsesSimplePawnAndPieceMoves() {
        val pos = Position.startPosition()
        val e4 = pos.parseSan("e4")
        assertEquals(Square.fromAlgebraic("e2"), e4.from)
        assertEquals(Square.fromAlgebraic("e4"), e4.to)
        assertEquals(true, e4.isDoublePawnPush)

        val nf3 = pos.parseSan("Nf3")
        assertEquals(Square.fromAlgebraic("g1"), nf3.from)
        assertEquals(Square.fromAlgebraic("f3"), nf3.to)
    }

    @Test
    fun parsesCastlingBothSides() {
        val pos = Position.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1")
        val kingside = pos.parseSan("O-O")
        assertEquals(true, kingside.isCastleKingside)
        val posAfter = pos.makeMove(kingside)
        val queenside = posAfter.parseSan("O-O-O")
        assertEquals(true, queenside.isCastleQueenside)
    }

    @Test
    fun disambiguatesByFileRankAndBoth() {
        // Two white knights can both reach d2: one from b1, one from f1 -> file disambiguation.
        val pos = Position.fromFen("4k3/8/8/8/8/8/8/1N2KN2 w - - 0 1")
        val move = pos.parseSan("Nbd2")
        assertEquals(Square.fromAlgebraic("b1"), move.from)
        assertEquals("Nbd2", pos.moveToSan(move))

        // Two rooks on the same file, different ranks -> rank disambiguation needed.
        val posRank = Position.fromFen("4k3/8/8/R7/8/8/8/R3K3 w - - 0 1")
        val rookMove = posRank.parseSan("R1a3")
        assertEquals(Square.fromAlgebraic("a1"), rookMove.from)
        assertEquals("R1a3", posRank.moveToSan(rookMove))

        // Three knights can all reach f5: h4's mover shares its file with h6 and its
        // rank with d4, so neither file nor rank alone disambiguates it -> full square.
        val posBoth = Position.fromFen("4k3/8/7N/8/3N3N/8/8/4K3 w - - 0 1")
        val nMove = posBoth.parseSan("Nh4f5")
        assertEquals(Square.fromAlgebraic("h4"), nMove.from)
        assertEquals("Nh4f5", posBoth.moveToSan(nMove))
    }

    @Test
    fun parsesPromotionWithCheck() {
        val pos = Position.fromFen("4k3/1P6/8/8/8/8/8/4K3 w - - 0 1")
        val move = pos.parseSan("b8=Q+")
        assertEquals(PieceType.QUEEN, move.promotion)
        val after = pos.makeMove(move)
        assertEquals(true, after.isInCheck())
        assertEquals("b8=Q+", pos.moveToSan(move))
    }

    @Test
    fun parsesCaptureWithPromotionAndMate() {
        // Back-rank mate: black king boxed in by its own pawns, white captures the
        // rook on a8 while promoting to deliver mate along the 8th rank.
        val pos = Position.fromFen("r5k1/1P3ppp/8/8/8/8/8/4K3 w - - 0 1")
        val move = pos.parseSan("bxa8=Q#")
        assertEquals(true, move.isCapture)
        assertEquals(PieceType.QUEEN, move.promotion)
        val after = pos.makeMove(move)
        assertEquals(true, after.isCheckmate())
        assertEquals("bxa8=Q#", pos.moveToSan(move))
    }

    @Test
    fun stripsAnnotationGlyphs() {
        val pos = Position.startPosition()
        for (suffix in listOf("", "!", "?", "!!", "??", "!?", "?!")) {
            val move = pos.parseSan("e4$suffix")
            assertEquals(Square.fromAlgebraic("e4"), move.to)
        }
    }

    @Test
    fun uciRoundTrip() {
        val pos = Position.startPosition()
        val move = pos.parseUci("e2e4")
        assertEquals("e2e4", move.toUci())

        val promoPos = Position.fromFen("4k3/1P6/8/8/8/8/8/4K3 w - - 0 1")
        val promoMove = promoPos.parseUci("b7b8q")
        assertEquals("b7b8q", promoMove.toUci())
    }

    @Test
    fun illegalSanThrows() {
        val pos = Position.startPosition()
        assertThrows(SanParseException::class.java) { pos.parseSan("Nf9") }
        assertThrows(SanParseException::class.java) { pos.parseSan("e5") }
    }
}
