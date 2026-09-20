package net.palaya.chessanalyzer.core.tactics

import net.palaya.chessanalyzer.core.chess.PieceType
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.Square

/**
 * Board geometry shared by every motif detector: piece values, ray directions and the
 * collinearity tests that pins, skewers, x-rays and discovered attacks are all built on.
 *
 * Everything here is deliberately position-independent where it can be, so the detectors
 * can ask geometry questions once and reuse the answer across the before/after positions.
 */

// ---------------------------------------------------------------------------
// Material
// ---------------------------------------------------------------------------

/**
 * Centipawn values fixed by ANALYSIS_SPEC 5.2. The king's 20000 is not a real material
 * value - it only has to be large enough that a swap sequence never prefers losing it.
 */
fun valueOf(type: PieceType): Int = when (type) {
    PieceType.PAWN -> 100
    PieceType.KNIGHT -> 320
    PieceType.BISHOP -> 330
    PieceType.ROOK -> 500
    PieceType.QUEEN -> 900
    PieceType.KING -> 20000
}

val PIECE_VALUES: Map<PieceType, Int> = PieceType.values().associateWith { valueOf(it) }

// ---------------------------------------------------------------------------
// Directions
// ---------------------------------------------------------------------------

/** Ray directions as (fileStep, rankStep). */
val ROOK_DIRECTIONS: Array<Pair<Int, Int>> = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
val BISHOP_DIRECTIONS: Array<Pair<Int, Int>> = arrayOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)
val QUEEN_DIRECTIONS: Array<Pair<Int, Int>> = ROOK_DIRECTIONS + BISHOP_DIRECTIONS

val KNIGHT_DELTAS: Array<Pair<Int, Int>> =
    arrayOf(1 to 2, 2 to 1, 2 to -1, 1 to -2, -1 to -2, -2 to -1, -2 to 1, -1 to 2)
val KING_DELTAS: Array<Pair<Int, Int>> = QUEEN_DIRECTIONS

fun isSlider(type: PieceType): Boolean =
    type == PieceType.BISHOP || type == PieceType.ROOK || type == PieceType.QUEEN

/** Directions [type] can travel along; empty for non-sliders. */
fun slidingDirections(type: PieceType): Array<Pair<Int, Int>> = when (type) {
    PieceType.BISHOP -> BISHOP_DIRECTIONS
    PieceType.ROOK -> ROOK_DIRECTIONS
    PieceType.QUEEN -> QUEEN_DIRECTIONS
    else -> emptyArray()
}

/** True when a slider of [type] can move along [dir]. */
fun slidesAlong(type: PieceType, dir: Pair<Int, Int>): Boolean = when (type) {
    PieceType.BISHOP -> dir.first != 0 && dir.second != 0
    PieceType.ROOK -> dir.first == 0 || dir.second == 0
    PieceType.QUEEN -> true
    else -> false
}

private fun Int.unit(): Int = if (this > 0) 1 else if (this < 0) -1 else 0

/**
 * The unit step that walks from [a] to [b], or null when the two squares do not share a
 * rank, file or diagonal. This is the single place that decides "are these aligned?"; the
 * pin/skewer/x-ray code all funnels through it so they cannot disagree.
 */
fun directionBetween(a: Square, b: Square): Pair<Int, Int>? {
    if (a.index == b.index) return null
    val df = b.file - a.file
    val dr = b.rank - a.rank
    return when {
        df == 0 -> 0 to dr.unit()
        dr == 0 -> df.unit() to 0
        df == dr || df == -dr -> df.unit() to dr.unit()
        else -> null
    }
}

/** Squares strictly between [a] and [b]; empty when they are not aligned or are adjacent. */
fun lineBetween(a: Square, b: Square): List<Square> {
    val dir = directionBetween(a, b) ?: return emptyList()
    val out = ArrayList<Square>(6)
    var f = a.file + dir.first
    var r = a.rank + dir.second
    while (f in 0..7 && r in 0..7) {
        val sq = Square.of(f, r)
        if (sq.index == b.index) return out
        out.add(sq)
        f += dir.first
        r += dir.second
    }
    return emptyList() // ran off the board before reaching b - not actually aligned
}

/** True when all three squares lie on one common rank, file or diagonal. */
fun isOnLine(a: Square, b: Square, c: Square): Boolean {
    if (a.rank == b.rank && b.rank == c.rank) return true
    if (a.file == b.file && b.file == c.file) return true
    val da = a.rank - a.file; val db = b.rank - b.file; val dc = c.rank - c.file
    if (da == db && db == dc) return true
    val aa = a.rank + a.file; val ab = b.rank + b.file; val ac = c.rank + c.file
    return aa == ab && ab == ac
}

/** True when [mid] sits strictly between [a] and [b] on their shared line. */
fun isBetween(a: Square, mid: Square, b: Square): Boolean {
    if (!isOnLine(a, mid, b)) return false
    val toB = directionBetween(a, b) ?: return false
    val toMid = directionBetween(a, mid) ?: return false
    if (toB != toMid) return false
    // Same direction and on the same line, so "between" reduces to "closer than b".
    val distMid = maxOf(kotlin.math.abs(mid.file - a.file), kotlin.math.abs(mid.rank - a.rank))
    val distB = maxOf(kotlin.math.abs(b.file - a.file), kotlin.math.abs(b.rank - a.rank))
    return distMid < distB
}

/** Every square from [from] (exclusive) along [dir] to the edge of the board. */
fun ray(from: Square, dir: Pair<Int, Int>): List<Square> {
    val out = ArrayList<Square>(7)
    var f = from.file + dir.first
    var r = from.rank + dir.second
    while (f in 0..7 && r in 0..7) {
        out.add(Square.of(f, r))
        f += dir.first
        r += dir.second
    }
    return out
}

/** The first occupied square along [dir] starting from [from], or null for an empty ray. */
fun firstOccupied(position: Position, from: Square, dir: Pair<Int, Int>): Square? {
    var f = from.file + dir.first
    var r = from.rank + dir.second
    while (f in 0..7 && r in 0..7) {
        val sq = Square.of(f, r)
        if (position.pieceAt(sq) != null) return sq
        f += dir.first
        r += dir.second
    }
    return null
}

/** True when nothing stands on the squares strictly between [a] and [b]. */
fun isPathClear(position: Position, a: Square, b: Square): Boolean =
    lineBetween(a, b).all { position.pieceAt(it) == null }
