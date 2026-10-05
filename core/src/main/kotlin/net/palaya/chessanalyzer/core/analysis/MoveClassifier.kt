package net.palaya.chessanalyzer.core.analysis

import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Move
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.Square
import net.palaya.chessanalyzer.core.chess.parseUci

/**
 * Implements the move-classification ladder from ANALYSIS_SPEC.md section 2, exactly, in
 * order, first-match-wins.
 *
 * Depends only on the [SeeEvaluator] interface from Contract.kt (never a concrete
 * `core.tactics` class), injected via the constructor so tests can supply a fake.
 */
class MoveClassifier(private val see: SeeEvaluator) {

    /**
     * @param positionBefore the position before [move] is played (side to move = the mover).
     * @param move the move actually played.
     * @param evalBefore engine evaluation of [positionBefore], at the configured depth/MultiPV.
     * @param evalAfter engine evaluation of the position after [move] is played, at the same depth.
     * @param isBookPosition whether [positionBefore] is within the bundled opening book.
     * @param isBookMove whether the resulting position (after [move]) is also within the book,
     *   i.e. [move] continues a catalogued line.
     * @param ply 1-based ply number of this move within the game.
     */
    fun classify(
        positionBefore: Position,
        move: Move,
        evalBefore: PositionEval,
        evalAfter: PositionEval,
        isBookPosition: Boolean,
        isBookMove: Boolean,
        ply: Int
    ): MoveClassification {
        val mover = positionBefore.sideToMove

        // 1. FORCED
        if (positionBefore.legalMoves().size == 1) return MoveClassification.FORCED

        // 2. BOOK
        if (isBookPosition && isBookMove && ply <= 20) return MoveClassification.BOOK

        val winBefore = WinProbability.winPercentForColor(evalBefore, mover)
        val winAfter = WinProbability.winPercentForColor(evalAfter, mover)
        val loss = (winBefore - winAfter).coerceAtLeast(0.0)

        val bestLine = evalBefore.best
        val bestMoveUci = bestLine?.pvUci?.firstOrNull()
        val isTopMove = bestMoveUci != null && bestMoveUci == move.toUci()

        // 3. BRILLIANT
        if (isBrilliant(positionBefore, move, mover, winBefore, winAfter, loss, evalAfter)) {
            return MoveClassification.BRILLIANT
        }

        // 4. GREAT (requires MultiPV >= 2)
        val secondLine = evalBefore.secondBest
        if (isTopMove && bestLine != null && secondLine != null && loss <= 2.0) {
            val gap = WinProbability.winPercentOfLine(bestLine) - WinProbability.winPercentOfLine(secondLine)
            if (gap >= 10.0) return MoveClassification.GREAT
        }

        // 5. MISS
        val mateAvailableBefore = (bestLine?.mateIn ?: 0) > 0
        val positionAfterMove = positionBefore.makeMove(move)
        val moveStillMates = positionAfterMove.isCheckmate() || ((evalAfter.best?.mateIn ?: 0) < 0)
        val throwsAwayWin = (winBefore >= 90.0 || mateAvailableBefore) && winAfter < 75.0
        val throwsAwayMate = mateAvailableBefore && !moveStillMates
        if (throwsAwayWin || throwsAwayMate) return MoveClassification.MISS

        // 6. BEST
        if (isTopMove) return MoveClassification.BEST

        // 7-11: loss-based ladder, with the already-decided guard applied to MISTAKE/BLUNDER.
        val raw = when {
            loss < 2.0 -> MoveClassification.EXCELLENT
            loss < 5.0 -> MoveClassification.GOOD
            loss < 10.0 -> MoveClassification.INACCURACY
            loss < 20.0 -> MoveClassification.MISTAKE
            else -> MoveClassification.BLUNDER
        }

        return applyDecidedGuard(raw, winBefore, winAfter)
    }

    /**
     * Guard: in an already-decided position, don't punish a move that doesn't actually change
     * who is winning. "Already decided" = winBefore >= 98 (decisively winning) or <= 2
     * (decisively losing). "Keeps the result" is read here as: the mover is still on the same
     * side of 50% afterwards (still winning, or still losing) — a move that flips a decisively
     * winning position into a losing one is still a real blunder and must NOT be clamped.
     */
    internal fun applyDecidedGuard(
        classification: MoveClassification,
        winBefore: Double,
        winAfter: Double
    ): MoveClassification {
        if (classification != MoveClassification.MISTAKE && classification != MoveClassification.BLUNDER) {
            return classification
        }
        val keepsResult = (winBefore >= 98.0 && winAfter >= 50.0) || (winBefore <= 2.0 && winAfter <= 50.0)
        return if (keepsResult) MoveClassification.GOOD else classification
    }

    private fun isBrilliant(
        positionBefore: Position,
        move: Move,
        mover: Color,
        winBefore: Double,
        winAfter: Double,
        loss: Double,
        evalAfter: PositionEval
    ): Boolean {
        if (winBefore >= 97.0) return false
        if (winAfter < 50.0) return false
        if (loss > 2.0) return false

        val positionAfter = positionBefore.makeMove(move)
        if (!isSacrifice(positionBefore, positionAfter, move)) return false
        if (!isRefutationGenuine(positionAfter, evalAfter)) return false

        return true
    }

    private fun isSacrifice(positionBefore: Position, positionAfter: Position, move: Move): Boolean {
        val seeValue = see.see(positionBefore, move)
        if (seeValue <= -200) return true

        // Or: the move leaves a friendly piece worth >= 300cp en prise to a legal capture.
        val moverColor = move.color
        for (sq in 0..63) {
            val square = Square(sq)
            val piece = positionAfter.pieceAt(square) ?: continue
            if (piece.color != moverColor) continue
            if (PieceValues.of(piece.type) < 300) continue
            // "To a *legal* capture" (spec §2): a piece the opponent cannot actually take - because
            // the only piece that attacks it is pinned to its king - is not en prise. (14.Rd1 in the
            // Opera Game was a "sacrifice" to a rook that is pinned on d7 by the bishop on b5.)
            if (see.isHanging(positionAfter, square) && canBeCapturedLegally(positionAfter, square)) return true
        }
        return false
    }

    private fun canBeCapturedLegally(position: Position, square: Square): Boolean =
        position.legalMoves().any { it.isCapture && it.to == square }

    /**
     * The refutation must genuinely lose material for the opponent — approximated here as:
     * either the opponent's engine-best reply is not a recapture at all, or (if it is) that
     * recapture itself loses material by SEE for the opponent. When there's no PV data to
     * inspect, we don't have grounds to disqualify the sacrifice, so it's treated as genuine.
     */
    private fun isRefutationGenuine(positionAfter: Position, evalAfter: PositionEval): Boolean {
        val bestReplyUci = evalAfter.best?.pvUci?.firstOrNull() ?: return true
        val replyMove = try {
            positionAfter.parseUci(bestReplyUci)
        } catch (e: Exception) {
            return true
        }
        if (!replyMove.isCapture) return true
        val seeForOpponent = see.see(positionAfter, replyMove)
        return seeForOpponent < -100
    }
}
