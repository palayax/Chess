package net.palaya.chessanalyzer.core.analysis

import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.PieceType
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.Square
import kotlin.math.abs

/**
 * Plain facts about a board, used to **verify** a sentence before it is said.
 *
 * `core.analysis` may not depend on `core.tactics` (see [ExchangeEvaluator]), and the commentary has
 * to be able to check a claim ("the knight on f6 attacks the queen on d5, and nothing defends it")
 * against the actual position rather than take a detector's word for it. These are pseudo-legal
 * attack facts - a pinned piece still "attacks" - which is exactly the sense the sentences use.
 */
internal object BoardFacts {

    /** True when the piece on [from] attacks [to] (an occupied or empty square), blockers considered. */
    fun attacks(position: Position, from: Square, to: Square): Boolean {
        val piece = position.pieceAt(from) ?: return false
        if (from.index == to.index) return false
        val df = to.file - from.file
        val dr = to.rank - from.rank
        return when (piece.type) {
            PieceType.PAWN -> dr == (if (piece.color == Color.WHITE) 1 else -1) && abs(df) == 1
            PieceType.KNIGHT -> (abs(df) == 1 && abs(dr) == 2) || (abs(df) == 2 && abs(dr) == 1)
            PieceType.KING -> abs(df) <= 1 && abs(dr) <= 1
            PieceType.BISHOP -> abs(df) == abs(dr) && clearBetween(position, from, to)
            PieceType.ROOK -> (df == 0 || dr == 0) && clearBetween(position, from, to)
            PieceType.QUEEN ->
                (df == 0 || dr == 0 || abs(df) == abs(dr)) && clearBetween(position, from, to)
        }
    }

    /** Squares holding a [byColor] piece that attacks [square], cheapest piece first. */
    fun attackers(position: Position, square: Square, byColor: Color): List<Square> {
        val out = ArrayList<Square>(4)
        for (index in 0..63) {
            val from = Square(index)
            val piece = position.pieceAt(from) ?: continue
            if (piece.color != byColor) continue
            if (attacks(position, from, square)) out.add(from)
        }
        return out.sortedBy { PieceValues.of(position.pieceAt(it)!!.type) }
    }

    /** The pieces of the colour that *owns* the piece on [square] that attack it - its defenders. */
    fun defenders(position: Position, square: Square): List<Square> {
        val owner = position.pieceAt(square)?.color ?: return emptyList()
        return attackers(position, square, owner)
    }

    /** True when every square strictly between [a] and [b] (on a shared line) is empty. */
    fun clearBetween(position: Position, a: Square, b: Square): Boolean {
        val stepFile = Integer.signum(b.file - a.file)
        val stepRank = Integer.signum(b.rank - a.rank)
        var file = a.file + stepFile
        var rank = a.rank + stepRank
        while (file != b.file || rank != b.rank) {
            if (file !in 0..7 || rank !in 0..7) return false
            if (position.pieceAt(Square.of(file, rank)) != null) return false
            file += stepFile
            rank += stepRank
        }
        return true
    }

    /** True when [a], [b] share a rank, file or diagonal. */
    fun aligned(a: Square, b: Square): Boolean {
        val df = abs(b.file - a.file)
        val dr = abs(b.rank - a.rank)
        return df == 0 || dr == 0 || df == dr
    }

    /** Every neighbour of [square] on the board. */
    fun neighbours(square: Square): List<Square> {
        val out = ArrayList<Square>(8)
        for (df in -1..1) for (dr in -1..1) {
            if (df == 0 && dr == 0) continue
            val f = square.file + df
            val r = square.rank + dr
            if (f in 0..7 && r in 0..7) out.add(Square.of(f, r))
        }
        return out
    }
}
