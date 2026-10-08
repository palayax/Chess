package net.palaya.chessanalyzer.core.analysis

import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Move
import net.palaya.chessanalyzer.core.chess.PieceType
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.Square
import kotlin.math.abs
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

    /**
     * [netGain] after the side that is *not* [winner] has taken back whatever it can: when it is
     * their move in [finalPosition], the best capture available to them (by [see]) is charged to the
     * winner, so a claim made about the line survives the next ply. A line that stops right after a
     * capture is not credited with a piece the opponent takes straight back.
     */
    fun settledGain(from: Position, finalPosition: Position, winner: Color): Int {
        val net = netGain(from, finalPosition, winner)
        if (finalPosition.sideToMove == winner) return net
        val bestTakeBack = finalPosition.legalMoves()
            .filter { it.isCapture }
            .maxOfOrNull { see(finalPosition, it) } ?: 0
        return net - max(0, bestTakeBack)
    }

    /**
     * "a queen" / "a rook" / "a piece" / "a pawn" when [centipawns] is within [GAIN_TOLERANCE_CP] of
     * exactly that much material, else null. A gain between two piece values is not named after
     * either: a rook taken for a bishop (+170) is not "a pawn", and a queen taken by a pawn that is
     * then recaptured (+800) is not "a rook". The caller says "material" for those.
     */
    fun describeGain(centipawns: Int): String? = when {
        abs(centipawns - 900) <= GAIN_TOLERANCE_CP -> "a queen"
        abs(centipawns - 500) <= GAIN_TOLERANCE_CP -> "a rook"
        abs(centipawns - 325) <= GAIN_TOLERANCE_CP -> "a piece"
        abs(centipawns - 100) <= GAIN_TOLERANCE_CP -> "a pawn"
        else -> null
    }

    /** How far a net gain may be from a whole piece's value and still be named after it. */
    const val GAIN_TOLERANCE_CP = 40

    /**
     * "the exchange" when [move] is a minor piece capturing a rook and the swap-off nets the rook's
     * value minus the minor's (the minor is taken back): `rook - knight` = 180, `rook - bishop` = 170,
     * both inside [EXCHANGE_MIN_CP]..[EXCHANGE_MAX_CP]. Otherwise [describeGain] of the swap-off value,
     * so a free rook is still "a rook" and an unnamed gain is null (C1, ANALYSIS_SPEC 6.1).
     */
    fun describeCapture(before: Position, move: Move): String? {
        val gain = see(before, move)
        if (isExchangeCapture(before, move) && gain in EXCHANGE_MIN_CP..EXCHANGE_MAX_CP) return "the exchange"
        return describeGain(gain)
    }

    /** A knight or bishop captures a rook (never en passant, never a promotion). */
    fun isExchangeCapture(before: Position, move: Move): Boolean {
        if (!move.isCapture || move.isEnPassant || move.promotion != null) return false
        val mover = before.pieceAt(move.from)?.type ?: return false
        val victim = before.pieceAt(move.to)?.type ?: return false
        return victim == PieceType.ROOK && (mover == PieceType.KNIGHT || mover == PieceType.BISHOP)
    }

    /**
     * True when, between [from] and [to], [winner] has given up exactly one minor piece and taken exactly
     * one rook, with no other change in either side's queens, rooks, minors or pawns: the shape every
     * player calls "winning the exchange". A count of pieces by type, not a centipawn band: a bishop for
     * two pawns is also worth about 130 and is not the exchange (ANALYSIS_SPEC 6.1).
     */
    fun winsTheExchange(from: Position, to: Position, winner: Color): Boolean {
        val loser = winner.opposite()
        fun delta(color: Color, type: PieceType) = count(to, color, type) - count(from, color, type)
        fun minors(color: Color, position: Position) =
            count(position, color, PieceType.KNIGHT) + count(position, color, PieceType.BISHOP)
        val winnerMinors = minors(winner, to) - minors(winner, from)
        val loserMinors = minors(loser, to) - minors(loser, from)
        return winnerMinors == -1 && loserMinors == 0 &&
            delta(loser, PieceType.ROOK) == -1 && delta(winner, PieceType.ROOK) == 0 &&
            delta(winner, PieceType.QUEEN) == 0 && delta(loser, PieceType.QUEEN) == 0 &&
            delta(winner, PieceType.PAWN) == 0 && delta(loser, PieceType.PAWN) == 0
    }

    /**
     * [describeGain] of the material [winner] netted between [from] and [to], settled, with "the exchange"
     * when [winsTheExchange]; "material" for a gain of at least [minimumCp] that is neither; null below.
     */
    fun describeSettled(from: Position, to: Position, winner: Color, minimumCp: Int = 100): String? {
        val gain = settledGain(from, to, winner)
        if (gain < minimumCp) return null
        if (winsTheExchange(from, settledPosition(to, winner), winner) && gain in EXCHANGE_MIN_CP..EXCHANGE_MAX_CP) return "the exchange"
        return describeGain(gain) ?: "material"
    }

    /**
     * [finalPosition] with the opponent's best take-back played when it is their move and it wins
     * something (the capture [settledGain] charges): the board the pieces are counted on, so a line that
     * stops right after Bxg7 is still "the exchange" once Kxg7 has been allowed for.
     */
    fun settledPosition(finalPosition: Position, winner: Color): Position {
        if (finalPosition.sideToMove == winner) return finalPosition
        val takeBack = finalPosition.legalMoves().filter { it.isCapture }.maxByOrNull { see(finalPosition, it) } ?: return finalPosition
        return if (see(finalPosition, takeBack) > 0) finalPosition.makeMove(takeBack) else finalPosition
    }

    private fun count(position: Position, color: Color, type: PieceType): Int {
        var n = 0
        for (index in 0..63) {
            val piece = position.pieceAt(Square(index)) ?: continue
            if (piece.color == color && piece.type == type) n++
        }
        return n
    }

    /** `rook - bishop` (170) and `rook - knight` (180), each [GAIN_TOLERANCE_CP] either way. */
    const val EXCHANGE_MIN_CP = 500 - 330 - GAIN_TOLERANCE_CP
    const val EXCHANGE_MAX_CP = 500 - 320 + GAIN_TOLERANCE_CP
}
