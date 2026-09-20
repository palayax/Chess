package net.palaya.chessanalyzer.data.mapper

import net.palaya.chessanalyzer.core.chess.Color as CoreColor
import net.palaya.chessanalyzer.core.chess.Piece as CorePiece
import net.palaya.chessanalyzer.core.chess.PieceType as CorePieceType
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.Square as CoreSquare
import net.palaya.chessanalyzer.ui.model.BoardState
import net.palaya.chessanalyzer.ui.model.Piece as UiPiece
import net.palaya.chessanalyzer.ui.model.PieceColor as UiPieceColor
import net.palaya.chessanalyzer.ui.model.PieceType as UiPieceType
import net.palaya.chessanalyzer.ui.model.Square as UiSquare
import net.palaya.chessanalyzer.ui.model.squareOf

/**
 * The seam between `:core`'s [Position]/[CoreSquare] representation and the UI-owned
 * [BoardState]/[UiSquare] representation that [net.palaya.chessanalyzer.ui.board.ChessBoard]
 * consumes. `ChessBoard` itself is owned by another agent concurrently rewriting piece
 * rendering, so this file — not `BoardState`'s shape — is what changes as `:core` lands.
 *
 * The two square numbering schemes are deliberately different and both are load-bearing:
 *  - `core.chess.Square.index = rank * 8 + file`, rank 0 = rank "1" (a1 = 0, h8 = 63) — a
 *    natural fit for board arrays and Zobrist tables.
 *  - `ui.model.Square = rankFromTop * 8 + file`, rankFromTop 0 = rank "8" (a8 = 0, h1 = 63) —
 *    a natural fit for drawing a white-oriented board top-to-bottom.
 * so converting between them flips the rank, not just a relabeling.
 */

/** Converts a `:core` [CoreSquare] to the UI's top-left-origin [UiSquare] index. */
fun CoreSquare.toUiSquare(): UiSquare = squareOf(file, 7 - rank)

/** Converts a UI [UiSquare] index back to a `:core` [CoreSquare] (inverse of [toUiSquare]). */
fun UiSquare.toCoreSquare(): CoreSquare {
    val file = this % 8
    val rankFromTop = this / 8
    return CoreSquare.of(file, 7 - rankFromTop)
}

fun CoreColor.toUiPieceColor(): UiPieceColor =
    if (this == CoreColor.WHITE) UiPieceColor.WHITE else UiPieceColor.BLACK

fun CorePieceType.toUiPieceType(): UiPieceType = when (this) {
    CorePieceType.PAWN -> UiPieceType.PAWN
    CorePieceType.KNIGHT -> UiPieceType.KNIGHT
    CorePieceType.BISHOP -> UiPieceType.BISHOP
    CorePieceType.ROOK -> UiPieceType.ROOK
    CorePieceType.QUEEN -> UiPieceType.QUEEN
    CorePieceType.KING -> UiPieceType.KING
}

fun CorePiece.toUiPiece(): UiPiece = UiPiece(type.toUiPieceType(), color.toUiPieceColor())

/** Renders a full `:core` [Position] into the placeholder-shaped [BoardState] `ChessBoard` expects. */
fun Position.toBoardState(): BoardState {
    val pieces = LinkedHashMap<UiSquare, UiPiece>()
    for (index in 0..63) {
        val square = CoreSquare(index)
        val piece = pieceAt(square) ?: continue
        pieces[square.toUiSquare()] = piece.toUiPiece()
    }
    return BoardState(pieces = pieces, sideToMove = sideToMove.toUiPieceColor())
}

/** Renders a position from a FEN string; returns [BoardState.empty] if the FEN is unparsable. */
fun fenToBoardState(fen: String): BoardState = try {
    Position.fromFen(fen).toBoardState()
} catch (e: Exception) {
    BoardState.empty()
}

/** Convenience: the (from, to) UI-square pair for a UCI move string, or null if malformed. */
fun uciToUiSquarePair(uci: String): Pair<UiSquare, UiSquare>? {
    if (uci.length < 4) return null
    val from = CoreSquare.fromAlgebraic(uci.substring(0, 2)).toUiSquare()
    val to = CoreSquare.fromAlgebraic(uci.substring(2, 4)).toUiSquare()
    return from to to
}
