package net.palaya.chessanalyzer.ui.model

import net.palaya.chessanalyzer.core.analysis.MaterialBalance
import net.palaya.chessanalyzer.core.chess.PieceType as CorePieceType

/*
 * The pure logic behind the material strips (A4): what the Board shows beside each player's name and what
 * the Summary shows at the end. The arithmetic itself is `core.analysis.MaterialBalance`; this only shapes
 * it per side and per colour for the screens. No Compose and no Android, so every rule has a host test.
 *
 * Reference (owner's rule): chess.com's board shows, beside each player's name, the pieces that player has
 * captured and a "+N" for the side that is ahead on material. Our own drawing and words.
 */

/** One side's line: [taken] are the pieces this side has captured, [ahead] is its "+N" (0 = show nothing). */
data class SideMaterial(
    val color: PieceColor,
    /** The pieces this side has captured (they are the other side's colour), most valuable first. */
    val taken: List<CorePieceType>,
    /** How many points this side is ahead by; 0 when it is level or behind. */
    val ahead: Int,
) {
    /** The colour of the pieces in [taken]: the opponent's. */
    val takenColor: PieceColor get() = if (color == PieceColor.WHITE) PieceColor.BLACK else PieceColor.WHITE
}

/** Both sides' material lines for one position. */
data class MaterialView(val white: SideMaterial, val black: SideMaterial, val whitePoints: Int, val blackPoints: Int) {
    fun of(color: PieceColor): SideMaterial = if (color == PieceColor.WHITE) white else black

    /** True when neither side is ahead. */
    val isLevel: Boolean get() = whitePoints == blackPoints
}

/** The material view of [fen], or null when the FEN cannot be read (the strip is then drawn empty, never a crash). */
fun materialViewOrNull(fen: String?): MaterialView? {
    if (fen.isNullOrBlank()) return null
    val balance = try {
        MaterialBalance.fromFen(fen)
    } catch (e: IllegalArgumentException) {
        return null
    }
    return MaterialView(
        white = SideMaterial(PieceColor.WHITE, balance.capturedByWhite, balance.advantage(net.palaya.chessanalyzer.core.chess.Color.WHITE)),
        black = SideMaterial(PieceColor.BLACK, balance.capturedByBlack, balance.advantage(net.palaya.chessanalyzer.core.chess.Color.BLACK)),
        whitePoints = balance.points(net.palaya.chessanalyzer.core.chess.Color.WHITE),
        blackPoints = balance.points(net.palaya.chessanalyzer.core.chess.Color.BLACK),
    )
}

/** [taken] grouped by kind for drawing and speaking, most valuable kind first: queen 1, pawn 3, ... */
fun groupedTaken(taken: List<CorePieceType>): List<Pair<CorePieceType, Int>> =
    taken.groupingBy { it }.eachCount().entries
        .sortedByDescending { MaterialBalance.valueOf(it.key) }
        .map { it.key to it.value }

/**
 * Which side's strip goes above the board and which below: the side at the bottom of the board is the
 * one the viewer plays (or White, for a board seen from White's side).
 */
fun bottomColor(whiteAtBottom: Boolean): PieceColor = if (whiteAtBottom) PieceColor.WHITE else PieceColor.BLACK
