package net.palaya.chessanalyzer.ui.model

/**
 * PLACEHOLDER — local to the app module until `:core` ships a real board/position type.
 *
 * A later integration pass should delete this file and point [net.palaya.chessanalyzer.ui.board.ChessBoard]
 * at whatever `:core` exposes (likely a `Position`/`Board` type with piece-at-square lookup and
 * a FEN/SAN layer). Keep the *shape* of [BoardState] (piece map keyed by 0..63 square index,
 * side to move, castling/ep bits) as the contract the board composable expects, or update
 * [net.palaya.chessanalyzer.ui.board.ChessBoard]'s signature to match the real type directly.
 */

enum class PieceColor { WHITE, BLACK }

enum class PieceType(val fenChar: Char) {
    KING('k'), QUEEN('q'), ROOK('r'), BISHOP('b'), KNIGHT('n'), PAWN('p'),
}

data class Piece(val type: PieceType, val color: PieceColor)

/** 0 = a8 .. 63 = h1 (top-left to bottom-right, matching how the board is drawn white-orientation-down). */
typealias Square = Int

fun squareOf(file: Int, rank0FromTop: Int): Square = rank0FromTop * 8 + file

/** File 0..7 (a..h) */
fun Square.file(): Int = this % 8

/** Rank as displayed from the top of a white-oriented board, 0..7 (0 = rank 8) */
fun Square.rankFromTop(): Int = this / 8

/** Standard algebraic square name, e.g. "e4". */
fun Square.algebraic(): String {
    val fileChar = ('a' + file())
    val rankNum = 8 - rankFromTop()
    return "$fileChar$rankNum"
}

fun algebraicToSquare(algebraic: String): Square? {
    if (algebraic.length != 2) return null
    val file = algebraic[0] - 'a'
    val rankNum = algebraic[1].digitToIntOrNull() ?: return null
    if (file !in 0..7 || rankNum !in 1..8) return null
    return squareOf(file, 8 - rankNum)
}

/**
 * Minimal placeholder position representation: enough to render a board.
 * No move legality / check detection lives here — that's `:core`'s job later.
 */
data class BoardState(
    val pieces: Map<Square, Piece>,
    val sideToMove: PieceColor = PieceColor.WHITE,
) {
    companion object {
        fun startingPosition(): BoardState {
            val back = listOf(
                PieceType.ROOK, PieceType.KNIGHT, PieceType.BISHOP, PieceType.QUEEN,
                PieceType.KING, PieceType.BISHOP, PieceType.KNIGHT, PieceType.ROOK,
            )
            val map = mutableMapOf<Square, Piece>()
            for (file in 0..7) {
                map[squareOf(file, 0)] = Piece(back[file], PieceColor.BLACK)
                map[squareOf(file, 1)] = Piece(PieceType.PAWN, PieceColor.BLACK)
                map[squareOf(file, 6)] = Piece(PieceType.PAWN, PieceColor.WHITE)
                map[squareOf(file, 7)] = Piece(back[file], PieceColor.WHITE)
            }
            return BoardState(map, PieceColor.WHITE)
        }

        fun empty(): BoardState = BoardState(emptyMap())
    }
}
