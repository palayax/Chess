package net.palaya.chessanalyzer.ui.model

/**
 * How far one position's search may go, beyond its depth (F1, docs/ANALYSIS_SPEC.md §8.1).
 *
 * The search is `go depth D nodes N movetime T`: it stops at the requested depth, or when it has
 * used [nodes] (all threads together), or after [movetimeMs] of wall-clock time, whichever comes
 * first. A position stopped before depth D is flagged ("capped") with the depth it reached.
 *
 * - [nodes] is the real budget. Node counts do not depend on the phone's speed, so the same game is
 *   cut at the same places on every device. Calibrated on the host from Stockfish 19 node counts
 *   (spec §8.1): at least 95% of positions of the calibration games reach full depth, and the
 *   pathological game01 position (after 23.Rdg1) stops near depth 16 at Deep.
 * - [movetimeMs] is only a safety net for a very slow phone, generous on purpose: the desktop P0
 *   lesson was that a tight time cap silently made the analysis shallower and changed move
 *   classifications. It is set from the emulator's measured speed (spec §8.1) so that a device
 *   several times slower still spends its node budget before the clock stops it.
 */
data class SearchBudget(
    val nodes: Long,
    val movetimeMs: Long,
    /**
     * Nodes a position typically takes at this strength: the calibration games' mean of
     * min(nodes to full depth, [nodes]) (spec §8.1). Used only for the time-left estimate
     * (spec §8.4), never as a limit, and not part of the cache key.
     */
    val typicalNodes: Long = nodes,
) {

    /** Part of the eval-cache key: a result is reused only under identical limits. */
    val cacheKeyPart: String get() = "n$nodes:t$movetimeMs"

    companion object {
        // Calibration and the reasoning per number: docs/ANALYSIS_SPEC.md §8.1.
        val QUICK = SearchBudget(nodes = 4_000_000L, movetimeMs = 30_000L, typicalNodes = 500_000L)
        val STANDARD = SearchBudget(nodes = 25_000_000L, movetimeMs = 150_000L, typicalNodes = 3_450_000L)
        val DEEP = SearchBudget(nodes = 45_000_000L, movetimeMs = 270_000L, typicalNodes = 10_300_000L)

        fun forStrength(strength: AnalysisStrength): SearchBudget = when (strength) {
            AnalysisStrength.QUICK -> QUICK
            AnalysisStrength.STANDARD -> STANDARD
            AnalysisStrength.DEEP -> DEEP
        }

        /**
         * The budget for any stored depth (6..30). A preset depth gets its strength's budget; an
         * old custom depth gets the budget of the smallest preset at or above it, and anything
         * deeper than Deep gets Deep's (it is then usually capped, and flagged as such).
         */
        fun forDepth(depth: Int): SearchBudget {
            val strength = AnalysisStrength.entries.sortedBy { it.depth }.firstOrNull { depth <= it.depth }
                ?: AnalysisStrength.DEEP
            return forStrength(strength)
        }
    }
}
