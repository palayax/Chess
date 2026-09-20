package net.palaya.chessanalyzer.core.analysis

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Accuracy maths from ANALYSIS_SPEC.md section 3, exactly. */
object AccuracyCalculator {

    /** Per-move accuracy from win-percent loss, spec 3.1. */
    fun moveAccuracy(loss: Double): Double =
        (103.1668 * exp(-0.04354 * loss) - 3.1669).coerceIn(0.0, 100.0)

    /**
     * Game accuracy for one player, spec 3.2.
     *
     * @param losses per-move win-percent loss for this player's moves, in ply order,
     *   with BOOK moves already excluded by the caller.
     * @param winPercentSeries the White-perspective win-percent at every ply of the whole
     *   game (i.e. the eval graph) — the source of volatility for the sliding window.
     * @param seriesIndices for each entry in [losses], the index into [winPercentSeries] of
     *   the position that move was played from/to (same order/length as [losses]).
     */
    fun gameAccuracy(
        losses: List<Double>,
        winPercentSeries: List<Double>,
        seriesIndices: List<Int>
    ): Double {
        require(losses.size == seriesIndices.size) { "losses and seriesIndices must be aligned" }
        if (losses.isEmpty()) return 100.0

        val n = winPercentSeries.size
        val windowRadius = max(2, ceil(n / 10.0).toInt())

        val accuracies = losses.map { moveAccuracy(it) }
        val weights = seriesIndices.map { idx ->
            val lo = max(0, idx - windowRadius)
            val hi = min(n - 1, idx + windowRadius)
            stdev(winPercentSeries.subList(lo, hi + 1)).coerceIn(0.5, 12.0)
        }

        val weightSum = weights.sum()
        val weightedMean = if (weightSum > 0.0) {
            accuracies.zip(weights).sumOf { (a, w) -> a * w } / weightSum
        } else {
            accuracies.average()
        }

        val harmonicMean = accuracies.size / accuracies.sumOf { 1.0 / max(it, 1.0) }

        return ((weightedMean + harmonicMean) / 2.0).coerceIn(0.0, 100.0)
    }

    /** Rounds to 1 decimal place, as required for the reported accuracy. */
    fun round1dp(value: Double): Double = Math.round(value * 10.0) / 10.0

    private fun stdev(values: List<Double>): Double {
        if (values.size <= 1) return 0.0
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        return sqrt(variance)
    }
}
