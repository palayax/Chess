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
)

/**
 * Result of one analysis call (one "go ..." through the matching "bestmove ...").
 *
 * @param lines The latest [EngineLine] seen for each MultiPV slot, ordered by [EngineLine.multiPv].
 * @param bestMoveUci The move from the "bestmove" line, in UCI notation (e.g. "e2e4").
 * @param ponderUci The ponder move from the "bestmove" line, if the engine supplied one.
 * @param depth The depth of the deepest completed iteration across all MultiPV lines.
 */
data class AnalysisResult(
    val lines: List<EngineLine>,
    val bestMoveUci: String,
    val ponderUci: String? = null,
    val depth: Int,
) {
    /** True for a terminal position (checkmate or stalemate): the engine reported no legal move. */
    val isTerminal: Boolean get() = bestMoveUci == NO_MOVE

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
                    // Score can be followed by "lowerbound"/"upperbound"; skip if present.
                    if (tokens.getOrNull(i) == "lowerbound" || tokens.getOrNull(i) == "upperbound") {
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
        )
    }
}
