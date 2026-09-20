package net.palaya.chessanalyzer.core.tactics

import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Move
import net.palaya.chessanalyzer.core.chess.Piece
import net.palaya.chessanalyzer.core.chess.PieceType
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.Square

/**
 * Attack tables for a single position.
 *
 * "Attacks" here means *control*, not legality: a piece attacks a square even when the
 * square holds one of its own men (that is what makes it a defender) and even when the
 * piece is pinned. Motif detection needs control, not the legal move list - a pinned
 * knight still stops the enemy king walking onto the square it covers.
 *
 * Pawns attack only their two capture squares; pushes are moves, not attacks.
 */
object Attacks {

    /** Every square the piece on [from] controls. Empty when [from] is empty. */
    fun attacksFrom(position: Position, from: Square): List<Square> {
        val piece = position.pieceAt(from) ?: return emptyList()
        return when (piece.type) {
            PieceType.PAWN -> pawnAttacks(from, piece.color)
            PieceType.KNIGHT -> leaperAttacks(from, KNIGHT_DELTAS)
            PieceType.KING -> leaperAttacks(from, KING_DELTAS)
            else -> sliderAttacks(position, from, slidingDirections(piece.type))
        }
    }

    /** ANALYSIS_SPEC 5.2: attacker square -> the squares that attacker controls. */
    fun attackMap(position: Position, color: Color): Map<Square, List<Square>> {
        val map = LinkedHashMap<Square, List<Square>>(20)
        for (i in 0..63) {
            val sq = Square(i)
            val piece = position.pieceAt(sq) ?: continue
            if (piece.color != color) continue
            map[sq] = attacksFrom(position, sq)
        }
        return map
    }

    /**
     * Squares occupied by [byColor] pieces that control [square].
     *
     * Computed by scanning outward *from* [square] rather than by building the full attack
     * map, because the SEE swap loop and the hanging-piece tests ask this question far more
     * often than they ask for a whole map.
     */
    fun attackersOf(position: Position, square: Square, byColor: Color): List<Square> {
        val out = ArrayList<Square>(4)
        val file = square.file
        val rank = square.rank

        // A byColor pawn attacks `square` from one rank behind it, one file to either side.
        val pawnRank = rank - if (byColor == Color.WHITE) 1 else -1
        if (pawnRank in 0..7) {
            for (df in intArrayOf(-1, 1)) {
                val f = file + df
                if (f !in 0..7) continue
                val sq = Square.of(f, pawnRank)
                val p = position.pieceAt(sq)
                if (p != null && p.color == byColor && p.type == PieceType.PAWN) out.add(sq)
            }
        }

        for ((df, dr) in KNIGHT_DELTAS) {
            val f = file + df
            val r = rank + dr
            if (f !in 0..7 || r !in 0..7) continue
            val sq = Square.of(f, r)
            val p = position.pieceAt(sq)
            if (p != null && p.color == byColor && p.type == PieceType.KNIGHT) out.add(sq)
        }

        for ((df, dr) in KING_DELTAS) {
            val f = file + df
            val r = rank + dr
            if (f !in 0..7 || r !in 0..7) continue
            val sq = Square.of(f, r)
            val p = position.pieceAt(sq)
            if (p != null && p.color == byColor && p.type == PieceType.KING) out.add(sq)
        }

        for (dir in QUEEN_DIRECTIONS) {
            val blocker = firstOccupied(position, square, dir) ?: continue
            val p = position.pieceAt(blocker)!!
            if (p.color != byColor) continue
            if (slidesAlong(p.type, dir)) out.add(blocker)
        }
        return out
    }

    /** Friends of whatever stands on [square] that are defending it. */
    fun defendersOf(position: Position, square: Square): List<Square> {
        val piece = position.pieceAt(square) ?: return emptyList()
        return attackersOf(position, square, piece.color)
    }

    /** Explicit-colour form, for asking who of [byColor] guards a possibly-empty square. */
    fun defendersOf(position: Position, square: Square, byColor: Color): List<Square> =
        attackersOf(position, square, byColor)

    fun isAttackedBy(position: Position, square: Square, byColor: Color): Boolean =
        attackersOf(position, square, byColor).isNotEmpty()

    /** Enemy-occupied squares controlled by the piece standing on [from]. */
    fun attackedEnemiesFrom(position: Position, from: Square): List<Square> {
        val piece = position.pieceAt(from) ?: return emptyList()
        val enemy = piece.color.opposite()
        return attacksFrom(position, from).filter { position.pieceAt(it)?.color == enemy }
    }

    /**
     * All (attacker, victim) pairs for [color], where victim holds an enemy piece.
     * Detectors diff this set between the before/after positions to find what the move
     * newly attacked - which is exactly what discovered and double attacks are.
     */
    fun attackPairs(position: Position, color: Color): Set<Pair<Square, Square>> {
        val out = HashSet<Pair<Square, Square>>(32)
        val enemy = color.opposite()
        for (i in 0..63) {
            val from = Square(i)
            val piece = position.pieceAt(from) ?: continue
            if (piece.color != color) continue
            for (to in attacksFrom(position, from)) {
                if (position.pieceAt(to)?.color == enemy) out.add(from to to)
            }
        }
        return out
    }

    /** Squares of every [color] piece on the board. */
    fun piecesOf(position: Position, color: Color): List<Square> {
        val out = ArrayList<Square>(16)
        for (i in 0..63) {
            val sq = Square(i)
            if (position.pieceAt(sq)?.color == color) out.add(sq)
        }
        return out
    }

    /**
     * Builds the capture of [to] by the piece on [from] without consulting the legal move
     * list, so SEE can be asked about captures for the side that is *not* to move (which is
     * the normal case when judging the position after our own move).
     */
    fun captureMove(position: Position, from: Square, to: Square): Move {
        val attacker = position.pieceAt(from)!!
        val victim = position.pieceAt(to)
        val promoRank = if (attacker.color == Color.WHITE) 7 else 0
        val promotion =
            if (attacker.type == PieceType.PAWN && to.rank == promoRank) PieceType.QUEEN else null
        return Move(
            from = from, to = to, piece = attacker.type, color = attacker.color,
            promotion = promotion,
            isCapture = victim != null,
            capturedPiece = victim?.type
        )
    }

    fun pawnAttacks(from: Square, color: Color): List<Square> {
        val r = from.rank + if (color == Color.WHITE) 1 else -1
        if (r !in 0..7) return emptyList()
        val out = ArrayList<Square>(2)
        for (df in intArrayOf(-1, 1)) {
            val f = from.file + df
            if (f in 0..7) out.add(Square.of(f, r))
        }
        return out
    }

    private fun leaperAttacks(from: Square, deltas: Array<Pair<Int, Int>>): List<Square> {
        val out = ArrayList<Square>(8)
        for ((df, dr) in deltas) {
            val f = from.file + df
            val r = from.rank + dr
            if (f in 0..7 && r in 0..7) out.add(Square.of(f, r))
        }
        return out
    }

    private fun sliderAttacks(
        position: Position,
        from: Square,
        dirs: Array<Pair<Int, Int>>
    ): List<Square> {
        val out = ArrayList<Square>(14)
        for ((df, dr) in dirs) {
            var f = from.file + df
            var r = from.rank + dr
            while (f in 0..7 && r in 0..7) {
                val sq = Square.of(f, r)
                out.add(sq)
                if (position.pieceAt(sq) != null) break // control stops at the first blocker
                f += df
                r += dr
            }
        }
        return out
    }

    /** Convenience: the piece standing on [square], or null. */
    fun at(position: Position, square: Square): Piece? = position.pieceAt(square)
}
