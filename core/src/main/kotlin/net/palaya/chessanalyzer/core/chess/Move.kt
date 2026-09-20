package net.palaya.chessanalyzer.core.chess

/**
 * A single chess move. Immutable and self-describing: it carries enough information
 * (capture/en-passant/castling/promotion flags) to be applied or displayed without
 * consulting the position it was generated from, though [Position.makeMove] still
 * validates it against the actual board state.
 */
data class Move(
    val from: Square,
    val to: Square,
    val piece: PieceType,
    val color: Color,
    val promotion: PieceType? = null,
    val isCapture: Boolean = false,
    val capturedPiece: PieceType? = null,
    val isEnPassant: Boolean = false,
    val isCastleKingside: Boolean = false,
    val isCastleQueenside: Boolean = false,
    val isDoublePawnPush: Boolean = false
) {
    val isCastle: Boolean get() = isCastleKingside || isCastleQueenside

    /** UCI long-algebraic form, e.g. "e2e4", "e7e8q". */
    fun toUci(): String {
        val promoChar = promotion?.sanLetter?.lowercaseChar()?.toString() ?: ""
        return "$from$to$promoChar"
    }
}
