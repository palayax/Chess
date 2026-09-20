package net.palaya.chessanalyzer.core.analysis

import net.palaya.chessanalyzer.core.chess.Color
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

/**
 * Evaluation-normalisation maths from ANALYSIS_SPEC.md section 1.
 *
 * All the functions here are perspective-explicit on purpose: [EngineLineInput.scoreCp] and
 * [EngineLineInput.mateIn] are documented (see Contract.kt) as being from the perspective of
 * the side to move *in that position*. [PositionEval.fen] tells us who that is. Getting this
 * flip wrong (using White-relative maths where the value was actually side-relative, or vice
 * versa) is the single easiest bug to introduce in this module, so every entry point below
 * takes an explicit [Color] rather than a bare Int, and the FEN's side-to-move field is always
 * consulted rather than assumed.
 */
object WinProbability {

    const val MATE_CP = 10000
    const val MATE_STEP = 50
    private const val SIGMOID_K = 0.00368208

    /** Saturating centipawn value for a mate score. [n] > 0 means the side to move mates. */
    fun cpFromMate(n: Int): Int {
        val sign = if (n >= 0) 1 else -1
        return sign * (MATE_CP - min(abs(n), 40) * MATE_STEP)
    }

    /**
     * Win percent (0..100) for the side whose perspective [cp] is already expressed in.
     * The sigmoid is odd around 0, so `winPercent(cp) + winPercent(-cp) == 100` always holds;
     * callers rely on that symmetry to get the opposite side's win percent by negating cp
     * rather than by calling this twice with different formulas.
     */
    fun winPercent(cp: Int): Double {
        val clamped = cp.coerceIn(-1000, 1000)
        return 50.0 + 50.0 * (2.0 / (1.0 + exp(-SIGMOID_K * clamped)) - 1.0)
    }

    /** Raw centipawn value of one engine line (mate scores saturated via [cpFromMate]). */
    fun cpOfLine(line: EngineLineInput): Int =
        line.mateIn?.let { cpFromMate(it) } ?: (line.scoreCp ?: 0)

    /**
     * Win percent for the line's own perspective (i.e. the side to move in the position it
     * was computed for). Mate scores are 100/0 exactly, per spec 1.1.
     */
    fun winPercentOfLine(line: EngineLineInput): Double {
        val mateIn = line.mateIn
        if (mateIn != null) return if (mateIn > 0) 100.0 else 0.0
        return winPercent(line.scoreCp ?: 0)
    }

    /** Side to move parsed from a FEN's second field. */
    fun sideToMoveOf(fen: String): Color {
        val field = fen.trim().split(Regex("\\s+")).getOrNull(1)
        return if (field == "b") Color.BLACK else Color.WHITE
    }

    /**
     * Win percent (0..100) for [color] in the position described by [eval], using its top
     * (MultiPV 1) line. If [color] is not the side to move in that position, the line's own
     * win percent is flipped (100 - x) rather than recomputed with a negated cp — both are
     * mathematically identical thanks to the sigmoid's odd symmetry, but flipping the already
     * clamped/saturated percentage is the operation the spec actually describes.
     */
    fun winPercentForColor(eval: PositionEval, color: Color): Double {
        val line = eval.best ?: return 50.0
        val stm = sideToMoveOf(eval.fen)
        val winForStm = winPercentOfLine(line)
        return if (color == stm) winForStm else 100.0 - winForStm
    }

    /** White-relative centipawns for [eval]'s top line — used for storage/graphing (spec 1). */
    fun cpWhiteRelative(eval: PositionEval): Int {
        val line = eval.best ?: return 0
        val cp = cpOfLine(line)
        return if (sideToMoveOf(eval.fen) == Color.WHITE) cp else -cp
    }

    /** White-relative mate distance for [eval]'s top line, or null if it isn't a mate score. */
    fun mateWhiteRelative(eval: PositionEval): Int? {
        val line = eval.best ?: return null
        val m = line.mateIn ?: return null
        return if (sideToMoveOf(eval.fen) == Color.WHITE) m else -m
    }

    /**
     * Win-percent loss for a move played by [mover], per spec 1.2:
     *
     *     before = winPercent(evalBefore, perspective = mover)
     *     after  = winPercent(evalAfter,  perspective = mover)
     *     loss   = max(0, before - after)
     *
     * [before] is the position before the move (mover to move there); [after] is the position
     * after the move (the opponent to move there). Both are consulted through
     * [winPercentForColor] so the perspective flip is always derived from the FEN, never
     * assumed — this is what makes the maths immune to a White/Black sign-flip bug.
     */
    fun loss(before: PositionEval, after: PositionEval, mover: Color): Double {
        val winBefore = winPercentForColor(before, mover)
        val winAfter = winPercentForColor(after, mover)
        return (winBefore - winAfter).coerceAtLeast(0.0)
    }
}
