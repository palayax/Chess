package net.palaya.chessanalyzer.ui.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** The order the board's TalkBack summary lists pieces in (U10). */
class BoardSemanticsTest {

    private fun sq(name: String): Square = algebraicToSquare(name)!!

    @Test
    fun theStartingPositionIsListedKingFirstAndPawnsLast() {
        val white = piecesInSpokenOrder(BoardState.startingPosition(), PieceColor.WHITE)
        assertEquals(16, white.size)
        assertEquals(PieceType.KING to "e1", white.first().second.type to white.first().first.algebraic())
        assertEquals(PieceType.QUEEN to "d1", white[1].second.type to white[1].first.algebraic())
        assertEquals(listOf("a1", "h1"), white.filter { it.second.type == PieceType.ROOK }.map { it.first.algebraic() })
        assertEquals(
            listOf("a2", "b2", "c2", "d2", "e2", "f2", "g2", "h2"),
            white.filter { it.second.type == PieceType.PAWN }.map { it.first.algebraic() },
        )
        assertEquals(PieceType.PAWN, white.last().second.type)
    }

    @Test
    fun onlyThePiecesOfTheAskedColourAreListed() {
        val black = piecesInSpokenOrder(BoardState.startingPosition(), PieceColor.BLACK)
        assertEquals(16, black.size)
        assertEquals(true, black.all { it.second.color == PieceColor.BLACK })
        assertEquals("e8", black.first().first.algebraic())
    }

    @Test
    fun samePiecesAreOrderedByFileThenRank() {
        val board = BoardState(
            pieces = mapOf(
                sq("e5") to Piece(PieceType.PAWN, PieceColor.WHITE),
                sq("e4") to Piece(PieceType.PAWN, PieceColor.WHITE),
                sq("a7") to Piece(PieceType.PAWN, PieceColor.WHITE),
                sq("h1") to Piece(PieceType.KING, PieceColor.WHITE),
            ),
        )
        assertEquals(
            listOf("h1", "a7", "e4", "e5"),
            piecesInSpokenOrder(board, PieceColor.WHITE).map { it.first.algebraic() },
        )
    }

    @Test
    fun anEmptySideListsNothing() {
        val board = BoardState(pieces = mapOf(sq("e1") to Piece(PieceType.KING, PieceColor.WHITE)))
        assertEquals(emptyList<Pair<Square, Piece>>(), piecesInSpokenOrder(board, PieceColor.BLACK))
    }
}
