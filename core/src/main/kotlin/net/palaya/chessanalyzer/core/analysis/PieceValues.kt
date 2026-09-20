package net.palaya.chessanalyzer.core.analysis

import net.palaya.chessanalyzer.core.chess.PieceType

/**
 * Standard centipawn piece values, per spec 5.2. Kept local to `core.analysis` (rather than
 * depending on anything from `core.tactics`, which this package must not touch) since the
 * classifier's BRILLIANT sacrifice check and the commentary generator both need them.
 */
object PieceValues {
    fun of(type: PieceType): Int = when (type) {
        PieceType.PAWN -> 100
        PieceType.KNIGHT -> 320
        PieceType.BISHOP -> 330
        PieceType.ROOK -> 500
        PieceType.QUEEN -> 900
        PieceType.KING -> 20000
    }

    /** Lowercase English name, e.g. "knight" — used by commentary templates. */
    fun name(type: PieceType): String = when (type) {
        PieceType.PAWN -> "pawn"
        PieceType.KNIGHT -> "knight"
        PieceType.BISHOP -> "bishop"
        PieceType.ROOK -> "rook"
        PieceType.QUEEN -> "queen"
        PieceType.KING -> "king"
    }
}
