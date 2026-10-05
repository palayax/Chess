package net.palaya.chessanalyzer.ui.model

import net.palaya.chessanalyzer.core.analysis.EvalFormat
import net.palaya.chessanalyzer.ui.board.BoardOrientation
import net.palaya.chessanalyzer.ui.theme.MoveClassification

/*
 * Pure logic behind the Board screen (UX step U6, docs/MOBILE_UX_DESIGN.md §6.4): which side the
 * board is shown from, what a move chip says, and what the comment card says a mistake cost. No
 * Compose and no Android here, so every rule has a host test.
 */

/**
 * The orientation the board opens in: from Black's side when the user played Black, otherwise from
 * White's (including when the side is unknown or the user answered "Not me"). The manual flip in
 * the app bar is layered on top of this by the screen and is deliberately not persisted.
 */
fun defaultBoardOrientation(userColor: PieceColor?): BoardOrientation =
    if (userColor == PieceColor.BLACK) BoardOrientation.BLACK_DOWN else BoardOrientation.WHITE_DOWN

/** The orientation after [flipped] manual flips have been applied on top of [default]. */
fun BoardOrientation.flippedIf(flipped: Boolean): BoardOrientation = when {
    !flipped -> this
    this == BoardOrientation.WHITE_DOWN -> BoardOrientation.BLACK_DOWN
    else -> BoardOrientation.WHITE_DOWN
}

/**
 * What a move chip shows: the SAN, the engine score after the move, and, on White's moves only,
 * the move number as a quiet prefix. The swing is deliberately absent (it moved to the comment
 * card as "This cost about N pawns"). The badge is drawn from the move's classification.
 */
data class ChipLabel(val number: String?, val san: String, val score: String)

fun chipLabel(move: MoveRecord): ChipLabel = ChipLabel(
    number = if (move.ply % 2 == 1) "${move.moveNumber}." else null,
    // One formatter for the bar, the chips and the panel: `+0.9`, `M3`, `#`.
    score = EvalFormat.score(move.evalCp, move.mateInMoves),
    san = move.san,
)

/**
 * How much this move cost the player who made it, in **half pawns** (so 1 = "about half a pawn",
 * 4 = "about 2 pawns", 3 = "about 1.5 pawns"), or null when there is nothing honest to say:
 * the swing is unknown or crosses a mate boundary ([MoveRecord.evalSwingCp] is null there), the
 * move did not lose anything, or the loss rounds to nothing.
 */
fun pawnCostHalves(move: MoveRecord): Int? {
    val swing = move.evalSwingCp ?: return null
    // evalSwingCp is White-relative; the mover loses when it moves against them.
    val loss = if (move.moverColor == PieceColor.WHITE) -swing else swing
    if (loss <= 0) return null
    val halves = Math.round(loss / 50.0).toInt()
    return halves.takeIf { it >= 1 }
}

/** The label of the "show me" button: a mistake is something missed, anything else is just shown. */
fun showMeIsAboutAMiss(move: MoveRecord): Boolean = move.classification?.isMistake == true

/**
 * The "Better was X" line: only on a mistake class, only when the best move differs, and only when
 * the explanation under it does not already say so. `:core` words most mistakes as "... Better was
 * h5, keeping material level.", and the card must not say it twice.
 */
fun betterMoveToShow(move: MoveRecord): String? {
    if (move.classification?.isMistake != true) return null
    val best = move.bestMoveSan?.takeIf { it.isNotBlank() } ?: return null
    if (best == move.san) return null
    if (move.annotation?.contains("Better was $best") == true) return null
    return best
}

/**
 * The classification badge drawn on the board (B1): which square it sits on and which class it shows.
 * The corner placement and the 28% size are the board's business ([net.palaya.chessanalyzer.ui.board.ChessBoard]);
 * this only decides **whether** there is a badge and **where**, as a square, so the flip is the
 * board's `orientation` and nothing else.
 */
data class BoardBadgeSpec(val square: Square, val classification: MoveClassification)

/**
 * The badge for the current ply: only for the highlight tier ([MoveClassification.isHighlight]:
 * brilliancies and the four mistake classes), on the move's destination square. A quiet class
 * (Best, Excellent, Good, Book, Forced, Great) draws nothing: the chip in the move list already
 * carries its small badge, and a badge on every square would bury the pieces. Also null when the
 * ply has no classification or no destination (the start position).
 */
fun boardBadgeFor(classification: MoveClassification?, destination: Square?): BoardBadgeSpec? {
    if (classification == null || !classification.isHighlight || destination == null) return null
    return BoardBadgeSpec(destination, classification)
}

/**
 * The first key-moment ply strictly after [currentPly], or null at the last one (B2). The list is
 * the report's key moments in any order; the button must never go backwards or loop.
 */
fun nextKeyMomentPly(keyMomentPlies: List<Int>, currentPly: Int): Int? =
    keyMomentPlies.filter { it > currentPly }.minOrNull()
