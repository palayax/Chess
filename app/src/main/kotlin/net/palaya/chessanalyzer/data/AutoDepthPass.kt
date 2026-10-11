package net.palaya.chessanalyzer.data

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import net.palaya.chessanalyzer.core.analysis.AdaptiveDepth
import net.palaya.chessanalyzer.core.analysis.MoveClassifier
import net.palaya.chessanalyzer.core.analysis.OpeningBook
import net.palaya.chessanalyzer.core.analysis.PositionEval
import net.palaya.chessanalyzer.core.pgn.PgnGame

/**
 * Auto-depth's second pass (docs/ANALYSIS_SPEC.md §8.5), without Android or the engine so the host can test it:
 * `AnalysisService` hands in the first-pass evaluations, a deep search, the deep results it already has and a
 * checkpoint; this runs the policy's rounds and returns the assembled evaluations.
 *
 * **Resume.** [AdaptiveDepth.plan] is deterministic, so replaying the rounds over the same first pass picks the same
 * positions in the same order. A picked position whose deep result is already [known] (the Auto checkpoint, or a
 * Deep eval cache of the same game) takes it instead of searching again; nothing else is reused. A known result is
 * accepted only for the same FEN and the deep tier's requested depth ([knownDeepEvals]), so a first-pass result can
 * never pass for a deep one and the tiers are never mixed silently.
 */
class AutoDepthPass(
    private val game: PgnGame,
    private val book: OpeningBook?,
    private val classifier: MoveClassifier,
    private val config: AdaptiveDepth.Config = AdaptiveDepth.Config(),
) {

    /** What happened to one picked position. */
    data class Deepened(
        val index: Int,
        val eval: PositionEval,
        val reasons: Set<AdaptiveDepth.Reason>,
        /** True when the result came from [known] (no search). */
        val reused: Boolean,
        val round: Int,
        /** Positions done and picked so far in this pass (picked grows when a later round adds some). */
        val done: Int,
        val picked: Int,
    )

    data class Result(val evals: List<PositionEval>, val deepened: List<Deepened>, val rounds: Int)

    /**
     * @param base the first-pass evaluations, one per position.
     * @param known deep results available without a search, by position index (see [knownDeepEvals]).
     * @param search the deep search of one position ([done] / [picked] for the progress line).
     * @param onRound the positions a round picked, before they are searched (for the diagnostic log).
     * @param onDeepened after each picked position has its deep result (the caller reports and logs it).
     * @param checkpoint after each picked position, with the evaluations assembled so far.
     */
    suspend fun run(
        base: List<PositionEval>,
        known: Map<Int, PositionEval>,
        search: suspend (index: Int, done: Int, picked: Int) -> PositionEval,
        onRound: (round: Int, picks: List<AdaptiveDepth.Deepening>) -> Unit = { _, _ -> },
        onDeepened: (Deepened) -> Unit = {},
        checkpoint: suspend (List<PositionEval>) -> Unit = {},
    ): Result {
        val current = base.toMutableList()
        val deepened = LinkedHashSet<Int>()
        val log = ArrayList<Deepened>()
        var picked = 0
        var round = 1
        while (true) {
            coroutineContext.ensureActive()
            val picks = AdaptiveDepth.plan(game, base, current, deepened, round, book, classifier, config)
            if (picks.isEmpty()) break
            picked += picks.size
            onRound(round, picks)
            for (p in picks) {
                coroutineContext.ensureActive()
                val reusedEval = known[p.index]
                val eval = reusedEval ?: search(p.index, deepened.size, picked)
                current[p.index] = eval
                deepened += p.index
                val entry = Deepened(p.index, eval, p.reasons, reusedEval != null, round, deepened.size, picked)
                log += entry
                onDeepened(entry)
                checkpoint(current.toList())
            }
            round++
        }
        return Result(current, log, round - 1)
    }

    companion object {
        /**
         * The deep results usable without a search, by position index: from the Auto checkpoint first, then a Deep
         * eval cache of the same game (identical search: the Deep preset's depth and budget, same net folder), then
         * a Deep checkpoint prefix. Each must hold the position's FEN and the deep tier's requested depth.
         */
        fun knownDeepEvals(
            positions: List<String>,
            deepDepth: Int,
            vararg sources: List<PositionEval>?,
        ): Map<Int, PositionEval> {
            val known = HashMap<Int, PositionEval>()
            for (source in sources) {
                source ?: continue
                for ((i, eval) in source.withIndex()) {
                    if (i >= positions.size || i in known) continue
                    if (eval.fen == positions[i] && eval.requestedDepth == deepDepth) known[i] = eval
                }
            }
            return known
        }
    }
}
