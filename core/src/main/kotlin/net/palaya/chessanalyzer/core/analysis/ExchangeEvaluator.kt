package net.palaya.chessanalyzer.core.analysis

import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Move
import net.palaya.chessanalyzer.core.chess.PieceType
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.Square
import kotlin.math.max

/**
 * Material accounting for `core.analysis`, per ANALYSIS_SPEC.md 5.2's piece values.
 *
 * `core.analysis` must not depend on `core.tactics` (the real [SeeEvaluator] lives there and is
 * injected into [MoveClassifier] by the app), but the commentary and simulation text still has to
 * be able to tell a winning capture from a losing one without an engine. This object provides a
 * small, self-contained swap-off evaluation built purely on [Position.legalMoves] plus a plain
 * board material count.
 */
internal object ExchangeEvaluator {

    /** Depth guard — a single square can be contested by at most ~32 men. */
    private const val MAX_EXCHANGE_DEPTH = 32

    /**
     * Centipawns won (positive) or lost (negative) by [move] for the side making it, assuming
     * both sides then capture optimally on the destination square and either side may decline
     * to continue the exchange.
     */
    fun see(position: Position, move: Move): Int {
        val capturedValue = when {
            move.isEnPassant -> PieceValues.of(PieceType.PAWN)
            else -> position.pieceAt(move.to)?.type?.let { PieceValues.of(it) } ?: 0
        }
        val promotionGain = move.promotion
            ?.let { PieceValues.of(it) - PieceValues.of(PieceType.PAWN) }
            ?: 0
        val after = position.makeMove(move)
        return capturedValue + promotionGain - swapOff(after, move.to, 0)
    }

    /**
     * Value the side to move can win by capturing on [target], or 0 if capturing there is not
     * worth it. Mirrors the standard SEE recursion.
     */
    private fun swapOff(position: Position, target: Square, depth: Int): Int {
        if (depth >= MAX_EXCHANGE_DEPTH) return 0
        val occupant = position.pieceAt(target) ?: return 0
        if (occupant.color == position.sideToMove) return 0

        val recapture = position.legalMoves()
            .filter { it.to == target && it.isCapture && !it.isEnPassant }
            .minByOrNull { PieceValues.of(it.piece) }
            ?: return 0

        val promotionGain = recapture.promotion
            ?.let { PieceValues.of(it) - PieceValues.of(PieceType.PAWN) }
            ?: 0
        val gain = PieceValues.of(occupant.type) + promotionGain
        val next = position.makeMove(recapture)
        // The capturing side is never forced to start the exchange, hence max(0, ...).
        return max(0, gain - swapOff(next, target, depth + 1))
    }

    /** Total centipawn material for [color] on the board, kings excluded. */
    fun material(position: Position, color: Color): Int {
        var total = 0
        for (index in 0..63) {
            val piece = position.pieceAt(Square(index)) ?: continue
            if (piece.type == PieceType.KING) continue
            if (piece.color == color) total += PieceValues.of(piece.type)
        }
        return total
    }

    /** Material balance from [color]'s point of view (own material minus the opponent's). */
    fun balance(position: Position, color: Color): Int =
        material(position, color) - material(position, color.opposite())

    /**
     * The material [color] has netted between [from] and [to], positive when [color] is up on
     * the deal. Derived from the actual boards, never from a tactic's advertised swing.
     */
    fun netGain(from: Position, to: Position, color: Color): Int =
        balance(to, color) - balance(from, color)

    /** "a queen" / "a rook" / "a piece" / "a pawn" for a positive centipawn gain, else null. */
    fun describeGain(centipawns: Int): String? = when {
        centipawns >= 900 -> "a queen"
        centipawns >= 500 -> "a rook"
        centipawns >= 300 -> "a piece"
        centipawns >= 100 -> "a pawn"
        else -> null
    }
}
