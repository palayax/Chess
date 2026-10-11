package net.palaya.chessanalyzer.ui.model

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The Analysing screen's progress bar for one run (ANALYSIS_SPEC §8.4, §8.5), pure and host-tested.
 *
 * The engine part of the bar runs from [START] to [START] + [SPAN] (0.30 to 0.98; the rest is the engine's
 * preparation before and the report after). A fixed-depth run fills it by positions: `done / total`, exactly as
 * before Auto-depth.
 *
 * An Auto run has two passes. The bar is shared by their expected work in nodes: the first pass gets
 * `total x base typical nodes`, the second `expected deep positions x deep typical nodes`, so the first pass ends
 * well short of the end (about half way on a typical game) and the second fills the rest by its positions done of
 * those picked so far. A later round can pick more positions, which would move the bar back; it never does: the
 * bar only rises, and it never reaches the end before the analysis is done ("never show 100% and then go on").
 */
class AnalysisFraction(
    private val total: Int,
    autoDepth: Boolean,
    baseTypicalNodes: Long,
    deepTypicalNodes: Long,
    expectedDeepPositions: Int,
) {
    /** The first pass's share of the engine work: 1 for a fixed-depth run. */
    val firstPassShare: Double = run {
        val baseWork = total.toDouble() * baseTypicalNodes
        val deepWork = expectedDeepPositions.toDouble() * deepTypicalNodes
        if (!autoDepth || baseWork <= 0.0 || deepWork <= 0.0) 1.0 else baseWork / (baseWork + deepWork)
    }

    private var shown = 0f

    /** The bar while the first (or only) pass has [done] of the game's positions. */
    @Synchronized
    fun firstPass(done: Int): Float {
        val share = if (total <= 0) 0.0 else done.coerceIn(0, total).toDouble() / total
        return rise(START + SPAN * (firstPassShare * share).toFloat())
    }

    /** The bar during Auto's second pass: [done] of the [picked] positions picked so far are searched again. */
    @Synchronized
    fun secondPass(done: Int, picked: Int): Float {
        val share = if (picked <= 0) 0.0 else done.coerceIn(0, picked).toDouble() / picked
        return rise(START + SPAN * (firstPassShare + (1.0 - firstPassShare) * share).toFloat())
    }

    private fun rise(value: Float): Float {
        shown = max(shown, value.coerceAtMost(START + SPAN))
        return shown
    }

    companion object {
        const val START = 0.30f
        const val SPAN = 0.68f

        /** Auto's expected second-pass positions for a game of [positions] (`AutoDepthBudget.EXPECTED_DEEP_FRACTION`). */
        fun expectedDeepPositions(positions: Int): Int = (positions * AutoDepthBudget.EXPECTED_DEEP_FRACTION).roundToInt()
    }
}
