package net.palaya.chessanalyzer.core.tactics

import net.palaya.chessanalyzer.core.analysis.SeeEvaluator
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Move
import net.palaya.chessanalyzer.core.chess.PieceType
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.Square

/**
 * Static Exchange Evaluation: the standard swap-off algorithm, with x-ray re-evaluation.
 *
 * The evaluator works on a scratch copy of the position's own packed `IntArray` board
 * rather than on [Position] objects. That matters twice over: the swap loop mutates
 * occupancy once per half-move of the exchange, and it must be answerable for captures by
 * the side that is *not* to move (judging "is this enemy piece hanging after my move?" is
 * exactly that question), which the legal move list cannot express.
 *
 * X-rays fall out of the design rather than being special-cased: instead of caching an
 * attacker set, each half-move re-scans the rays around the contested square on the
 * *current* occupancy. Removing the piece that just captured therefore automatically
 * exposes any slider queued up behind it, which is the whole point.
 *
 * Known, deliberate limitations of standard SEE, documented so callers do not over-trust it:
 *  - pins and absolute pins are ignored (a pinned defender is still counted as a defender);
 *  - it answers only "what does this exchange on one square cost?", never "is there a
 *    better move elsewhere?".
 */
class StaticExchangeEvaluator : SeeEvaluator {

    override fun see(position: Position, move: Move): Int {
        val occ = position.board.copyOf()
        val fromIdx = move.from.index
        val toIdx = move.to.index
        val movingCode = occ[fromIdx]
        if (movingCode == 0) return 0
        val mover = Position.colorOfCode(movingCode)

        // gain[0] is what the first capture immediately pockets. A quiet move pockets
        // nothing but still has to survive the opponent's reply, so it runs the same loop.
        var captured = 0
        if (move.isEnPassant) {
            val epPawn = Square.of(move.to.file, move.from.rank).index
            if (occ[epPawn] != 0) {
                captured = valueOf(PieceType.PAWN)
                occ[epPawn] = 0
            }
        } else if (occ[toIdx] != 0) {
            captured = VALUE_BY_CODE[occ[toIdx]]
        }

        // Clear both squares. The capturing piece is tracked in `standing` instead of being
        // written to the board: leaving the contested square empty is what lets the ray
        // scans below run straight up to it.
        occ[toIdx] = 0
        occ[fromIdx] = 0

        val promoBonus = if (move.promotion != null) valueOf(move.promotion) - valueOf(PieceType.PAWN) else 0
        var standing = valueOf(move.promotion ?: Position.typeOfCode(movingCode))

        val gain = IntArray(MAX_SWAPS)
        gain[0] = captured + promoBonus
        var d = 0
        var side = mover.opposite()

        while (d < MAX_SWAPS - 1) {
            val atkSq = leastValuableAttacker(occ, toIdx, side)
            if (atkSq < 0) break
            val atkCode = occ[atkSq]
            val atkType = Position.typeOfCode(atkCode)

            if (atkType == PieceType.KING) {
                // A king may only end the exchange, never continue it: if the other side
                // still has an attacker, capturing with the king would be illegal.
                occ[atkSq] = 0
                val stillGuarded = leastValuableAttacker(occ, toIdx, side.opposite()) >= 0
                occ[atkSq] = atkCode
                if (stillGuarded) break
            }

            // A pawn that recaptures onto the last rank promotes, so it both banks the
            // promotion difference and leaves a queen (not a pawn) to be captured next.
            val atkRank = atkSq shr 3
            val promotes = atkType == PieceType.PAWN &&
                atkRank == (if (side == Color.WHITE) 6 else 1)
            val bonus = if (promotes) valueOf(PieceType.QUEEN) - valueOf(PieceType.PAWN) else 0

            d++
            gain[d] = standing + bonus - gain[d - 1]
            // Standard pruning: once both the "stand pat" and "capture" branches are losing
            // for the side on move, nothing deeper in the swap can rescue it.
            if (maxOf(-gain[d - 1], gain[d]) < 0) break

            occ[atkSq] = 0
            standing = valueOf(if (promotes) PieceType.QUEEN else atkType)
            side = side.opposite()
        }

        // Negamax back up the swap list: at every depth the side on move keeps the better of
        // "stop here" and "carry on capturing", so a bad exchange is simply declined.
        while (d > 0) {
            gain[d - 1] = -maxOf(-gain[d - 1], gain[d])
            d--
        }
        return gain[0]
    }

    override fun isHanging(position: Position, square: Square): Boolean {
        val piece = position.pieceAt(square) ?: return false
        if (piece.color == position.sideToMove) return false
        return isWinnableBy(position, square, position.sideToMove)
    }

    /**
     * ANALYSIS_SPEC 5.2's hanging test, asked on behalf of an arbitrary side. Uses the
     * *cheapest* attacker deliberately: if grabbing the piece with the least valuable
     * attacker already breaks even, the piece is not defended well enough to be safe.
     */
    fun isWinnableBy(position: Position, square: Square, byColor: Color): Boolean {
        val victim = position.pieceAt(square) ?: return false
        if (victim.color == byColor) return false
        val attackers = Attacks.attackersOf(position, square, byColor)
        if (attackers.isEmpty()) return false
        val cheapest = attackers.minByOrNull { valueOf(position.pieceAt(it)!!.type) }!!
        return see(position, Attacks.captureMove(position, cheapest, square)) >= 0
    }

    /** The best centipawn result [byColor] can get from capturing on [square] right now. */
    fun bestCaptureSee(position: Position, square: Square, byColor: Color): Int {
        val victim = position.pieceAt(square) ?: return 0
        if (victim.color == byColor) return 0
        val attackers = Attacks.attackersOf(position, square, byColor)
        if (attackers.isEmpty()) return 0
        return attackers.maxOf { see(position, Attacks.captureMove(position, it, square)) }
    }

    /**
     * Finds the cheapest [side] piece attacking [target] on the *current* occupancy.
     * Re-scanning per half-move (rather than incrementally updating an attacker set) is what
     * gives x-ray re-evaluation for free: a slider hidden behind a piece that has just been
     * removed shows up here on the very next call.
     */
    private fun leastValuableAttacker(occ: IntArray, target: Int, side: Color): Int {
        val file = target and 7
        val rank = target shr 3

        val pawnRank = rank - if (side == Color.WHITE) 1 else -1
        if (pawnRank in 0..7) {
            for (df in intArrayOf(-1, 1)) {
                val f = file + df
                if (f !in 0..7) continue
                val sq = pawnRank * 8 + f
                val c = occ[sq]
                if (c != 0 && Position.colorOfCode(c) == side &&
                    Position.typeOfCode(c) == PieceType.PAWN
                ) return sq
            }
        }

        for ((df, dr) in KNIGHT_DELTAS) {
            val f = file + df
            val r = rank + dr
            if (f !in 0..7 || r !in 0..7) continue
            val sq = r * 8 + f
            val c = occ[sq]
            if (c != 0 && Position.colorOfCode(c) == side &&
                Position.typeOfCode(c) == PieceType.KNIGHT
            ) return sq
        }

        var bishopSq = -1
        var rookSq = -1
        var queenSq = -1
        for (dir in QUEEN_DIRECTIONS) {
            var f = file + dir.first
            var r = rank + dir.second
            while (f in 0..7 && r in 0..7) {
                val sq = r * 8 + f
                val c = occ[sq]
                if (c != 0) {
                    if (Position.colorOfCode(c) == side) {
                        when (Position.typeOfCode(c)) {
                            PieceType.BISHOP -> if (dir.first != 0 && dir.second != 0) bishopSq = sq
                            PieceType.ROOK -> if (dir.first == 0 || dir.second == 0) rookSq = sq
                            PieceType.QUEEN -> queenSq = sq
                            else -> {}
                        }
                    }
                    break
                }
                f += dir.first
                r += dir.second
            }
        }
        if (bishopSq >= 0) return bishopSq
        if (rookSq >= 0) return rookSq
        if (queenSq >= 0) return queenSq

        // The king is the last resort; adjacency has to be checked directly because a king
        // sitting further along a ray is not an attacker at all.
        for ((df, dr) in KING_DELTAS) {
            val f = file + df
            val r = rank + dr
            if (f !in 0..7 || r !in 0..7) continue
            val sq = r * 8 + f
            val c = occ[sq]
            if (c != 0 && Position.colorOfCode(c) == side &&
                Position.typeOfCode(c) == PieceType.KING
            ) return sq
        }
        return -1
    }

    private companion object {
        /** A single square cannot be contested more than 32 times; the array is the swap list. */
        const val MAX_SWAPS = 34

        /** Piece value indexed by the board's own packed piece code, 0 = empty. */
        val VALUE_BY_CODE = IntArray(13).also { arr ->
            for (code in 1..12) arr[code] = valueOf(Position.typeOfCode(code))
        }
    }
}
