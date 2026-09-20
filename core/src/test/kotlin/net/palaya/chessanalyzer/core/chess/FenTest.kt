package net.palaya.chessanalyzer.core.chess

import org.junit.Assert.assertEquals
import org.junit.Test

class FenTest {

    private val fens = listOf(
        Position.STANDARD_START_FEN,
        "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
        "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
        "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1",
        "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8",
        "4k3/8/8/8/8/8/8/4K2R w K - 0 1",
        "8/8/8/8/8/8/6k1/4K2R w K - 0 1",
        "r1bqkbnr/pppppppp/n7/8/8/N7/PPPPPPPP/R1BQKBNR w KQkq - 2 2",
        "rnbqkbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR w KQkq c6 0 2"
    )

    @Test
    fun roundTripsExactly() {
        for (fen in fens) {
            val pos = Position.fromFen(fen)
            assertEquals(fen, pos.toFen())
        }
    }

    @Test
    fun startPositionHasExpectedPieces() {
        val pos = Position.startPosition()
        assertEquals(Piece(PieceType.ROOK, Color.WHITE), pos.pieceAt(Square.fromAlgebraic("a1")))
        assertEquals(Piece(PieceType.KING, Color.WHITE), pos.pieceAt(Square.fromAlgebraic("e1")))
        assertEquals(Piece(PieceType.KING, Color.BLACK), pos.pieceAt(Square.fromAlgebraic("e8")))
        assertEquals(Piece(PieceType.PAWN, Color.BLACK), pos.pieceAt(Square.fromAlgebraic("h7")))
        assertEquals(null, pos.pieceAt(Square.fromAlgebraic("e4")))
        assertEquals(Color.WHITE, pos.sideToMove)
    }

    @Test
    fun makeMoveUpdatesFenFields() {
        var pos = Position.startPosition()
        pos = pos.makeMove(pos.parseUci("e2e4"))
        assertEquals("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1", pos.toFen())
        pos = pos.makeMove(pos.parseUci("c7c5"))
        assertEquals("rnbqkbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR w KQkq c6 0 2", pos.toFen())
        pos = pos.makeMove(pos.parseUci("g1f3"))
        assertEquals("rnbqkbnr/pp1ppppp/8/2p5/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2", pos.toFen())
    }
}
