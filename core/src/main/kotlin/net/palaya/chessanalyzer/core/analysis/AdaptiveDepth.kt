package net.palaya.chessanalyzer.core.analysis

import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.parseUci
import net.palaya.chessanalyzer.core.pgn.PgnGame
import kotlin.math.abs
import kotlin.math.floor

/**
 * Auto-depth (docs/ANALYSIS_SPEC.md §8.5): which positions of a game get a second, deeper search.
 *
 * The app first searches every position at the base tier (Standard). This policy then reads those evaluations
 * and picks the positions whose classification is worth a deeper look: a move's label is computed from the
 * evaluation **before and after** it (§1.2, §2), so for every critical move both of its positions are deepened.
 * The deeper results can change the picture, so the policy is asked again ([plan] with the next round) with the
 * evaluations assembled so far, until it picks nothing new or the round or position limits are reached.
 *
 * Pure and deterministic: the same inputs give the same picks in the same order. The app relies on that to resume
 * a killed second pass (a replay of the rounds picks the same positions, whose saved deep results are reused).
 *
 * Every threshold is in [Config] and defined (with its reasoning and measurement) in the spec; do not change one
 * here without the spec, and raise [POLICY_VERSION] when the picks change (it is part of the eval-cache key).
 */
object AdaptiveDepth {

    /** Part of the Auto eval-cache key: a result is reused only under the same policy. */
    const val POLICY_VERSION = 1

    /** Why a position was picked. Several can apply; the diagnostic log lists them. */
    enum class Reason(val logName: String) {
        /** A move on the position is an inaccuracy or worse (or a Miss) by the evaluations so far. */
        MISTAKE("mistake"),
        /** A side has a forced mate in the best line before or after a move. */
        MATE("mate"),
        /** The White win percentage moves by at least [Config.swingWin] across a move. */
        SWING("swing"),
        /** A move is Great or Brilliant by the evaluations so far (the labels the deeper search most often takes back). */
        GREAT("great"),
        /** A deepened neighbour's evaluation moved by at least [Config.unstableWin] from its base one (round 2 on). */
        UNSTABLE("unstable"),
        /** A move's loss, or its Great gap, sits within the margin of a classification boundary. */
        BOUNDARY("boundary"),
        /** The played move was best but the second line is within [Config.closeGapWin] (Best vs Excellent hangs on it). */
        CLOSE("close"),
        ;
    }

    /** One position to search again at [tier]; [index] is into the eval list (0 = start position). */
    data class Deepening(val index: Int, val tier: Tier, val reasons: Set<Reason>)

    /** The deeper search tiers above the base one. Only one today: the Deep preset's depth and budget. */
    enum class Tier { DEEP }

    /** The thresholds of spec §8.5. The defaults ARE the spec values. */
    data class Config(
        /** SWING: |White win% after − before| across one move, in win-percentage points. */
        val swingWin: Double = 8.0,
        /** BOUNDARY: |loss − b| ≤ this × b for the loss boundaries b of §2 (2, 5, 10, 20): a margin relative to b. */
        val boundaryMargin: Double = 0.25,
        /** BOUNDARY: |line-1 win% − line-2 win% − 10| ≤ this, for a best move with loss ≤ 2 (the §2 Great gap). */
        val greatGapMarginWin: Double = 3.0,
        /** CLOSE: the best move was played and line 2 is within this many win% points (0 = rule off). */
        val closeGapWin: Double = 0.0,
        /** CLOSE only counts while the mover's win% before the move is inside (this, 100 − this). */
        val closeDecidedWin: Double = 10.0,
        /** UNSTABLE: |deep White win% − base White win%| on a deepened position. */
        val unstableWin: Double = 5.0,
        /** At most this share of the searchable positions is deepened in total (a safety net: Auto stays below Deep). */
        val maxFraction: Double = 0.6,
        /** Rounds of picking (round 1 reads only the base evaluations). */
        val maxRounds: Int = 3,
        /**
         * The rules in force; a rule not in the set never picks. GREAT and CLOSE were measured and are off (spec §8.5:
         * they cost much and changed no agreement; a Great move near its gap boundary is still caught by BOUNDARY).
         */
        val rules: Set<Reason> = DEFAULT_RULES,
        /**
         * Which moves win when the cap is reached: a move ranks by the first of its reasons in this list, then by the
         * size of its swing. Moves near a classification boundary come first (their label is the likeliest to change).
         */
        val priority: List<Reason> = DEFAULT_PRIORITY,
    )

    /** The rules in force by default (spec §8.5). */
    val DEFAULT_RULES: Set<Reason> = setOf(Reason.MISTAKE, Reason.MATE, Reason.SWING, Reason.UNSTABLE, Reason.BOUNDARY)

    /** The default ranking under the cap (spec §8.5). */
    val DEFAULT_PRIORITY: List<Reason> = listOf(
        Reason.BOUNDARY, Reason.MATE, Reason.MISTAKE, Reason.SWING, Reason.UNSTABLE, Reason.GREAT, Reason.CLOSE,
    )

    private val LOSS_BOUNDARIES = doubleArrayOf(2.0, 5.0, 10.0, 20.0)
    private const val GREAT_GAP = 10.0
    private const val GREAT_MAX_LOSS = 2.0

    /**
     * The positions to deepen in [round] (1-based), best first by priority, then in game order.
     *
     * @param base the first-pass evaluations, one per position (`game.moves.size + 1`).
     * @param current the evaluations so far: [base] with every deepened position replaced (equal to [base] in round 1).
     * @param deepened the indices already searched at a deeper tier (never picked again).
     * @param book the opening book (a book move's label does not depend on the engine), or null.
     * @param classifier the §2 classifier, used on [current] exactly as the report will use it.
     * @return the new positions for this round, empty when the policy is done.
     */
    fun plan(
        game: PgnGame,
        base: List<PositionEval>,
        current: List<PositionEval>,
        deepened: Set<Int>,
        round: Int,
        book: OpeningBook?,
        classifier: MoveClassifier,
        config: Config = Config(),
    ): List<Deepening> {
        require(base.size == game.moves.size + 1 && current.size == base.size) {
            "one evaluation per position expected (${game.moves.size + 1}), got ${base.size} / ${current.size}"
        }
        if (round < 1 || round > config.maxRounds) return emptyList()

        val positions = base.map { Position.fromFen(it.fen) }
        val searchable = positions.indices.filter { positions[it].legalMoves().isNotEmpty() }.toSet()
        val cap = floor(searchable.size * config.maxFraction).toInt()
        var room = cap - deepened.size
        if (room <= 0) return emptyList()

        // Candidate moves (1-based ply m joins positions m-1 and m), each with its reasons and a sort key.
        data class Candidate(val ply: Int, val reasons: Set<Reason>, val priority: Int, val swing: Double)
        val candidates = ArrayList<Candidate>()
        for (m in 1..game.moves.size) {
            val reasons = moveReasons(game, m, positions, current, book, classifier, config)
            if (reasons.isNotEmpty()) {
                candidates += Candidate(m, reasons, rank(reasons, config), swingOf(current, m))
            }
        }
        if (round > 1 && Reason.UNSTABLE in config.rules) {
            // UNSTABLE: a deepened position whose evaluation moved a lot also moves the moves on its other sides.
            for (p in deepened.sorted()) {
                val moved = abs(whiteWin(current[p]) - whiteWin(base[p]))
                if (moved < config.unstableWin) continue
                for (m in listOf(p, p + 1)) {
                    if (m < 1 || m > game.moves.size || !isEngineJudged(game, m, positions, book)) continue
                    candidates += Candidate(m, setOf(Reason.UNSTABLE), rank(setOf(Reason.UNSTABLE), config), moved)
                }
            }
        }

        // Merge the reasons per position; a position takes the best priority of the moves that want it.
        val wanted = LinkedHashMap<Int, MutableSet<Reason>>()
        val order = candidates.sortedWith(
            compareBy<Candidate> { it.priority }.thenByDescending { it.swing }.thenBy { it.ply },
        )
        val picked = ArrayList<Int>()
        for (c in order) {
            val sides = listOf(c.ply - 1, c.ply).filter { it in searchable && it !in deepened }
            val fresh = sides.filter { it !in wanted }
            // Both sides or nothing: one deepened side alone would compare two depths across the critical move.
            if (fresh.size > room) continue
            for (s in sides) {
                val set = wanted.getOrPut(s) { mutableSetOf() }
                if (set.isEmpty()) {
                    room--
                    picked += s
                }
                set += c.reasons
            }
        }
        return picked.map { Deepening(it, Tier.DEEP, wanted.getValue(it).toSortedSet(compareBy { r -> r.ordinal })) }
    }

    /**
     * The reasons each move (1-based ply) is critical by [evals], ignoring the cap and the positions already deepened:
     * what round 1 of [plan] starts from. For the diagnostic log and the host measurement (spec §8.5).
     */
    fun criticalMoves(
        game: PgnGame,
        evals: List<PositionEval>,
        book: OpeningBook?,
        classifier: MoveClassifier,
        config: Config = Config(),
    ): Map<Int, Set<Reason>> {
        val positions = evals.map { Position.fromFen(it.fen) }
        return (1..game.moves.size).associateWith { moveReasons(game, it, positions, evals, book, classifier, config) }
            .filterValues { it.isNotEmpty() }
    }

    /** False for a move whose label the engine cannot change: forced (one legal move) or a book move (§2 rules 1-2). */
    private fun isEngineJudged(game: PgnGame, m: Int, positions: List<Position>, book: OpeningBook?): Boolean {
        val before = positions[m - 1]
        if (before.legalMoves().size == 1) return false
        if (book != null && m <= 20 && book.isBookPosition(game.moves[m - 1].positionFenBefore) &&
            book.isBookPosition(game.moves[m - 1].positionFenAfter)
        ) return false
        return true
    }

    private fun moveReasons(
        game: PgnGame,
        m: Int,
        positions: List<Position>,
        current: List<PositionEval>,
        book: OpeningBook?,
        classifier: MoveClassifier,
        config: Config,
    ): Set<Reason> {
        if (!isEngineJudged(game, m, positions, book)) return emptySet()
        val before = current[m - 1]
        val after = current[m]
        val position = positions[m - 1]
        val mover = position.sideToMove
        val move = position.parseUci(game.moves[m - 1].uci)
        val label = classifier.classify(position, move, before, after, isBookPosition = false, isBookMove = false, ply = m)
        val winBefore = WinProbability.winPercentForColor(before, mover)
        val winAfter = WinProbability.winPercentForColor(after, mover)
        val loss = (winBefore - winAfter).coerceAtLeast(0.0)
        val reasons = mutableSetOf<Reason>()

        if (label.isMistake) reasons += Reason.MISTAKE
        if (label == MoveClassification.GREAT || label == MoveClassification.BRILLIANT) reasons += Reason.GREAT
        if (swingOf(current, m) >= config.swingWin) reasons += Reason.SWING
        if (hasMate(before) || hasMate(after)) reasons += Reason.MATE
        if (LOSS_BOUNDARIES.any { abs(loss - it) <= config.boundaryMargin * it }) reasons += Reason.BOUNDARY

        val best = before.best
        val second = before.secondBest
        val playedBest = best?.pvUci?.firstOrNull() == move.toUci()
        if (playedBest && best != null && second != null) {
            val gap = WinProbability.winPercentOfLine(best) - WinProbability.winPercentOfLine(second)
            if (loss <= GREAT_MAX_LOSS * (1 + config.boundaryMargin) && abs(gap - GREAT_GAP) <= config.greatGapMarginWin) {
                reasons += Reason.BOUNDARY
            }
            if (config.closeGapWin > 0 && gap < config.closeGapWin &&
                winBefore > config.closeDecidedWin && winBefore < 100.0 - config.closeDecidedWin
            ) reasons += Reason.CLOSE
        }
        return reasons.filterTo(mutableSetOf()) { it in config.rules }
    }

    /** The rank of a move with [reasons]: the position of its best reason in [Config.priority] (lower = first). */
    private fun rank(reasons: Set<Reason>, config: Config): Int =
        reasons.minOf { r -> config.priority.indexOf(r).let { if (it < 0) config.priority.size else it } }

    /** A mate score in the best line of a searched position (a terminal position's "mate 0" is exact, not searched). */
    private fun hasMate(eval: PositionEval): Boolean {
        val m = eval.best?.mateIn ?: return false
        return m != 0
    }

    private fun whiteWin(eval: PositionEval): Double = WinProbability.winPercentForColor(eval, Color.WHITE)

    private fun swingOf(evals: List<PositionEval>, m: Int): Double = abs(whiteWin(evals[m]) - whiteWin(evals[m - 1]))
}
