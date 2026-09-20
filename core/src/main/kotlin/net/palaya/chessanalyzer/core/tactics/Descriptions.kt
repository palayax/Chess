package net.palaya.chessanalyzer.core.tactics

import net.palaya.chessanalyzer.core.chess.Piece
import net.palaya.chessanalyzer.core.chess.PieceType
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.Square

/**
 * Sentence fragments for [net.palaya.chessanalyzer.core.analysis.TacticInstance.description].
 *
 * These strings go straight onto the board screen, so they are written as English, not as a
 * dump of the detector's internal state: concrete piece names, concrete squares, one
 * sentence, no enum names and no centipawn numbers.
 */

internal fun nounFor(type: PieceType): String = when (type) {
    PieceType.PAWN -> "pawn"
    PieceType.KNIGHT -> "knight"
    PieceType.BISHOP -> "bishop"
    PieceType.ROOK -> "rook"
    PieceType.QUEEN -> "queen"
    PieceType.KING -> "king"
}

/** "the knight on e5" */
internal fun named(piece: Piece, square: Square): String = "the ${nounFor(piece.type)} on $square"

/** "the knight on e5", read off the board; falls back gracefully on an empty square. */
internal fun named(position: Position, square: Square): String =
    position.pieceAt(square)?.let { named(it, square) } ?: "the piece on $square"

internal fun capitalise(text: String): String =
    if (text.isEmpty()) text else text[0].uppercaseChar() + text.substring(1)

/** "a, b and c" */
internal fun joinNatural(parts: List<String>): String = when (parts.size) {
    0 -> ""
    1 -> parts[0]
    2 -> "${parts[0]} and ${parts[1]}"
    else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
}

internal fun ordinalRank(rankIndex: Int): String = when (rankIndex) {
    0 -> "1st"
    1 -> "2nd"
    2 -> "3rd"
    else -> "${rankIndex + 1}th"
}

/** "the d-file", "the 7th rank" or "the same diagonal", for describing lines. */
internal fun lineName(a: Square, b: Square): String = when {
    a.file == b.file -> "the ${'a' + a.file}-file"
    a.rank == b.rank -> "the ${ordinalRank(a.rank)} rank"
    else -> "the same diagonal"
}
