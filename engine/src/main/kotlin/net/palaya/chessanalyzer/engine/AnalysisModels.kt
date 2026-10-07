package net.palaya.chessanalyzer.engine

/**
 * One parsed UCI "info ..." line for a single MultiPV slot.
 *
 * @param multiPv 1-based MultiPV index ("multipv N" in the UCI line; 1 when MultiPV is off).
 * @param scoreCp Centipawn score from the side to move's perspective, or null if this line
 *   reported a mate score instead ("score mate N").
 * @param mateIn Moves to mate from the side to move's perspective (positive = winning,
 *   negative = losing), or null if this line reported a centipawn score instead.
 * @param depth Search depth ("depth N").
 * @param seldepth Selective search depth ("seldepth N"), or null if absent.
 * @param nodes Nodes searched, or null if absent.
 * @param nps Nodes per second, or null if absent.
 * @param timeMs Time spent searching in milliseconds, or null if absent.
 * @param pvUci Principal variation as UCI move strings (e.g. "e2e4"), best move first.
 * @param bound True when the score is only a bound ("lowerbound"/"upperbound"): an aspiration
 *   fail-high/low or an aborted search. Such a line is never used as a final result
 *   (see [ConsistentLines]).
 */
data class EngineLine(
    val multiPv: Int,
    val scoreCp: Int?,
    val mateIn: Int?,
    val depth: Int,
    val seldepth: Int? = null,
    val nodes: Long? = null,
    val nps: Long? = null,
    val timeMs: Long? = null,
    val pvUci: List<String> = emptyList(),
    val bound: Boolean = false,
)

/**
 * Live progress of one search, reported to [StockfishEngine.analyze]'s optional callback as the
 * engine's "info" lines arrive.
 *
 * @param depth The deepest depth for which the engine has printed an exact (non-bound) line so far.
 * @param nodes Nodes searched so far (all threads), or null if the engine did not say.
 * @param timeMs Engine-reported search time so far, or null if the engine did not say.
 */
data class SearchProgress(val depth: Int, val nodes: Long?, val timeMs: Long?)

/**
 * Result of one analysis call (one "go ..." through the matching "bestmove ...").
 *
 * @param lines The latest [EngineLine] seen for each MultiPV slot, ordered by [EngineLine.multiPv].
 * @param bestMoveUci The move from the "bestmove" line, in UCI notation (e.g. "e2e4").
 * @param ponderUci The ponder move from the "bestmove" line, if the engine supplied one.
 * @param depth The depth every line in [lines] was searched to. All lines come from one output
 *   batch of one depth (see [ConsistentLines]), so they are comparable with each other.
 * @param nodes Nodes the whole search used (all threads), 0 if the engine never said.
 * @param timeMs Engine-reported search time, 0 if the engine never said.
 * @param requestedDepth The `depth` limit the search was given, or null when it had none.
 */
data class AnalysisResult(
    val lines: List<EngineLine>,
    val bestMoveUci: String,
    val ponderUci: String? = null,
    val depth: Int,
    val nodes: Long = 0,
    val timeMs: Long = 0,
    val requestedDepth: Int? = null,
) {
    /** True for a terminal position (checkmate or stalemate): the engine reported no legal move. */
    val isTerminal: Boolean get() = bestMoveUci == NO_MOVE

    /**
     * True when a node or time limit stopped the search before [requestedDepth]: [depth] is then
     * the deepest depth whose lines were all complete, below the one asked for.
     */
    val stoppedEarly: Boolean get() = requestedDepth != null && !isTerminal && depth < requestedDepth

    companion object {
        /** What UCI reports as the best move when the side to move has none: `bestmove (none)`. */
        const val NO_MOVE = "(none)"
    }
}

/** Parses a single line of raw UCI engine output. */
internal object UciLineParser {

    /**
     * Parses a "bestmove <uci> [ponder <uci>]" line, or null if [line] isn't a bestmove line.
     *
     * A terminal position (checkmate or stalemate) makes the engine answer `bestmove (none)`.
     * That **must** still parse as a bestmove line. It previously returned null here, which meant
     * [StockfishEngine.analyze]'s `while (result == null)` loop never saw its terminator and hung
     * forever — the engine had already finished and would send nothing more. Any game ending in
     * mate (i.e. most annotated games) froze analysis on its final position.
     * [AnalysisResult.NO_MOVE] is surfaced to the caller instead.
     */
    fun parseBestMove(line: String): Pair<String, String?>? {
        val tokens = line.trim().split(Regex("\\s+"))
        if (tokens.isEmpty() || tokens[0] != "bestmove" || tokens.size < 2) return null
        val best = tokens[1]
        var ponder: String? = null
        val ponderIdx = tokens.indexOf("ponder")
        if (ponderIdx in 0 until tokens.size - 1) {
            ponder = tokens[ponderIdx + 1]
        }
        return best to ponder
    }

    /** Parses an "info ..." line into an [EngineLine], or null if it carries no PV/score data. */
    fun parseInfo(line: String): EngineLine? {
        val tokens = line.trim().split(Regex("\\s+"))
        if (tokens.isEmpty() || tokens[0] != "info") return null

        var multiPv = 1
        var depth: Int? = null
        var seldepth: Int? = null
        var scoreCp: Int? = null
        var mateIn: Int? = null
        var nodes: Long? = null
        var nps: Long? = null
        var timeMs: Long? = null
        var pv: List<String> = emptyList()
        var bound = false

        var i = 1
        while (i < tokens.size) {
            when (tokens[i]) {
                "depth" -> { depth = tokens.getOrNull(i + 1)?.toIntOrNull(); i += 2 }
                "seldepth" -> { seldepth = tokens.getOrNull(i + 1)?.toIntOrNull(); i += 2 }
                "multipv" -> { multiPv = tokens.getOrNull(i + 1)?.toIntOrNull() ?: 1; i += 2 }
                "nodes" -> { nodes = tokens.getOrNull(i + 1)?.toLongOrNull(); i += 2 }
                "nps" -> { nps = tokens.getOrNull(i + 1)?.toLongOrNull(); i += 2 }
                "time" -> { timeMs = tokens.getOrNull(i + 1)?.toLongOrNull(); i += 2 }
                "score" -> {
                    when (tokens.getOrNull(i + 1)) {
                        "cp" -> { scoreCp = tokens.getOrNull(i + 2)?.toIntOrNull(); i += 3 }
                        "mate" -> { mateIn = tokens.getOrNull(i + 2)?.toIntOrNull(); i += 3 }
                        else -> i += 2
                    }
                    // Score can be followed by "lowerbound"/"upperbound": the value is then only a
                    // bound, which the caller must not treat as the position's evaluation.
                    if (tokens.getOrNull(i) == "lowerbound" || tokens.getOrNull(i) == "upperbound") {
                        bound = true
                        i += 1
                    }
                }
                "pv" -> {
                    pv = tokens.subList(i + 1, tokens.size)
                    i = tokens.size
                }
                else -> i += 1
            }
        }

        val d = depth ?: return null
        if (scoreCp == null && mateIn == null && pv.isEmpty()) return null

        return EngineLine(
            multiPv = multiPv,
            scoreCp = scoreCp,
            mateIn = mateIn,
            depth = d,
            seldepth = seldepth,
            nodes = nodes,
            nps = nps,
            timeMs = timeMs,
            pvUci = pv,
            bound = bound,
        )
    }
}

/**
 * Picks the MultiPV lines a search result is built from, so that **every line comes from the same
 * depth** (docs/ANALYSIS_SPEC.md §8.2).
 *
 * Stockfish prints its lines in batches: one "info" line per MultiPV slot, slot 1 first. After a
 * completed iteration of depth d it prints all k slots at depth d. A search stopped by a `nodes` or
 * `movetime` limit prints one more batch at the stop (Stockfish 19, `search.cpp`
 * `start_searching`: `if (!uciPvSent ...) output_pv(...)`), and that batch cannot be trusted:
 * slots re-searched in the unfinished iteration say d+1, slots not reached yet are re-printed from
 * iteration d either as "depth d" or, if never touched, **with their old score under the new
 * depth's label**. So the stop batch can look like a clean batch of the new depth, even of the
 * requested depth itself (seen on the emulator: a search stopped 1,673 nodes past its 45 M budget
 * printed three "depth 18" lines). Taking the latest line per slot could also pair a depth-17 best
 * line with a depth-16 second line.
 *
 * The rule:
 * 1. Split the lines into batches (a new batch starts at slot 1 or when the slot number does not
 *    go up). The expected batch size k is the largest batch seen (MultiPV, or fewer when the
 *    position has fewer legal moves).
 * 2. A batch is *usable* when it has exactly slots 1..k, all at one depth, none a bound.
 * 3. When a limit was hit ([limitHit]: the last reported nodes reached the node limit, or the last
 *    reported time the time limit), the **final batch is the stop batch and is discarded**. When no
 *    limit was hit the search ended by reaching its depth, and Stockfish printed no stop batch.
 *    (If the depth was reached in the same instant as the limit, this discards a good batch and
 *    reports one depth less: conservative, never wrong.)
 * 4. The result is the last usable batch of the deepest depth among the rest.
 * 5. If nothing is usable (a tiny budget), fall back to the latest line per slot, with the
 *    shallowest of their depths as the result's depth.
 */
internal object ConsistentLines {

    data class Selection(val lines: List<EngineLine>, val depth: Int)

    fun select(infos: List<EngineLine>, limitHit: Boolean): Selection {
        if (infos.isEmpty()) return Selection(emptyList(), 0)
        val batches = ArrayList<MutableList<EngineLine>>()
        for (line in infos) {
            val current = batches.lastOrNull()
            if (current == null || line.multiPv == 1 || line.multiPv <= current.last().multiPv) {
                batches += mutableListOf(line)
            } else {
                current += line
            }
        }
        val expected = batches.maxOf { it.size }
        fun usable(batch: List<EngineLine>): Boolean {
            if (batch.size != expected) return false
            val d = batch[0].depth
            return batch.withIndex().all { (i, l) -> l.multiPv == i + 1 && l.depth == d && !l.bound }
        }
        val candidates = if (limitHit && batches.size > 1) batches.dropLast(1) else batches
        val best = candidates.withIndex()
            .filter { usable(it.value) }
            .maxWithOrNull(compareBy<IndexedValue<MutableList<EngineLine>>> { it.value[0].depth }.thenBy { it.index })
            ?.value
        if (best != null) return Selection(best.toList(), best[0].depth)

        val latestBySlot = LinkedHashMap<Int, EngineLine>()
        for (line in infos) if (!line.bound || line.multiPv !in latestBySlot) latestBySlot[line.multiPv] = line
        val lines = latestBySlot.values.sortedBy { it.multiPv }
        return Selection(lines, lines.minOf { it.depth })
    }
}
