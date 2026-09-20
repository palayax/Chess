package net.palaya.chessanalyzer.core.analysis

/** Estimated performance rating from game accuracy, per ANALYSIS_SPEC.md section 4. */
object RatingEstimator {

    /** (accuracy, elo) anchors, spec 4, in ascending order. */
    private val ANCHORS = listOf(
        60.0 to 800.0,
        70.0 to 1100.0,
        75.0 to 1300.0,
        80.0 to 1500.0,
        85.0 to 1800.0,
        90.0 to 2100.0,
        95.0 to 2500.0
    )

    /** Games with fewer plies than this have too little signal (spec 4). */
    const val LOW_CONFIDENCE_PLY_THRESHOLD = 20

    /**
     * Monotonic piecewise-linear map from accuracy to estimated Elo, extrapolated linearly
     * beyond the outermost anchors, clamped to [100, 3000].
     */
    fun estimate(accuracy: Double): Int {
        val elo = when {
            accuracy <= ANCHORS.first().first -> extrapolate(ANCHORS[0], ANCHORS[1], accuracy)
            accuracy >= ANCHORS.last().first -> extrapolate(ANCHORS[ANCHORS.size - 2], ANCHORS.last(), accuracy)
            else -> interpolate(accuracy)
        }
        return elo.coerceIn(100.0, 3000.0).let { Math.round(it).toInt() }
    }

    private fun interpolate(accuracy: Double): Double {
        for (i in 0 until ANCHORS.size - 1) {
            val (a0, e0) = ANCHORS[i]
            val (a1, e1) = ANCHORS[i + 1]
            if (accuracy in a0..a1) {
                val t = (accuracy - a0) / (a1 - a0)
                return e0 + t * (e1 - e0)
            }
        }
        return ANCHORS.last().second
    }

    private fun extrapolate(p0: Pair<Double, Double>, p1: Pair<Double, Double>, accuracy: Double): Double {
        val (a0, e0) = p0
        val (a1, e1) = p1
        val slope = (e1 - e0) / (a1 - a0)
        return e0 + slope * (accuracy - a0)
    }
}
