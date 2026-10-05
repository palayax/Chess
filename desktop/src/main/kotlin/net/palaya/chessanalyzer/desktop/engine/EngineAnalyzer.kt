package net.palaya.chessanalyzer.desktop.engine

import net.palaya.chessanalyzer.core.analysis.EngineLineInput
import net.palaya.chessanalyzer.core.analysis.PositionEval
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.pgn.PgnGame

/** The engine limits one analysis run uses. Threads/Hash are performance knobs, not semantics. */
data class EngineSettings(
    val depth: Int,
    val multiPv: Int,
    /** Safety net for pathological positions; `null` = depth only (what the app does). */
    val movetimeCapMs: Long?,
    val threads: Int,
    val hashMb: Int,
)

/** Timing of one position of the loop. */
data class PlyTiming(
    /** Index into the eval list: 0 = start position, i = after ply i. */
    val index: Int,
    val seconds: Double,
    /** How the eval was obtained. */
    val source: Source,
    val depthReached: Int,
    val nps: Long?,
) {
    enum class Source { SEARCHED, TERMINAL, RESUMED }
}

/**
 * The per-ply eval loop of `AnalysisService.analyze` (`app/.../data/AnalysisService.kt:110-183`),
 * reproduced for the desktop (docs/PC_PRODUCER_DESIGN.md §4):
 *
 *  - one `position fen` + `go` per position, positions = FEN before ply 1 + FEN after every ply
 *    (`plyFens`, `:206-214`), so `evals.size == moves.size + 1`;
 *  - a checkmated/stalemated position is never searched (`terminalEvalOrNull`, `:234-243`):
 *    `mate 0` for checkmate, `cp 0` for stalemate;
 *  - `PositionEval` mapping identical to `toPositionEval` (`:260-268`), including the
 *    "no info lines → one line holding just the bestmove" fallback;
 *  - checkpoint every [CHECKPOINT_EVERY_PLIES] (`:284`) and on failure, resume from the longest
 *    FEN-aligned prefix of a previous partial run (`usableResumePrefix`, `:253-258`).
 */
class EngineAnalyzer(
    private val client: UciClient,
    private val settings: EngineSettings,
) {

    /**
     * @param resumable evals from an earlier, interrupted run; reused only as far as their FENs
     *   line up with [fens] index by index.
     * @param onPosition called after every position with its eval and timing.
     * @param checkpoint called with the evals so far every [CHECKPOINT_EVERY_PLIES] positions and,
     *   before rethrowing, when the loop fails.
     */
    fun analyze(
        fens: List<String>,
        resumable: List<PositionEval> = emptyList(),
        onPosition: (PositionEval, PlyTiming) -> Unit = { _, _ -> },
        checkpoint: (List<PositionEval>) -> Unit = {},
    ): List<PositionEval> {
        val computed = ArrayList<PositionEval>(fens.size)
        usableResumePrefix(fens, resumable).forEachIndexed { i, eval ->
            computed.add(eval)
            onPosition(eval, PlyTiming(i, 0.0, PlyTiming.Source.RESUMED, eval.depth, null))
        }
        val startIndex = computed.size
        try {
            for ((index, fen) in fens.withIndex()) {
                if (index < startIndex) continue
                val t0 = System.nanoTime()
                val terminal = terminalEvalOrNull(fen, settings.depth)
                val eval: PositionEval
                val timing: PlyTiming
                if (terminal != null) {
                    eval = terminal
                    timing = PlyTiming(index, secondsSince(t0), PlyTiming.Source.TERMINAL, terminal.depth, null)
                } else {
                    client.setPosition(fen)
                    val result = client.analyze(
                        multiPv = settings.multiPv,
                        depth = settings.depth,
                        movetimeMs = settings.movetimeCapMs,
                        timeoutMs = (settings.movetimeCapMs ?: UciClient.DEFAULT_SEARCH_TIMEOUT_MS) + SEARCH_GRACE_MS,
                    )
                    eval = toPositionEval(fen, result, settings.depth)
                    timing = PlyTiming(index, secondsSince(t0), PlyTiming.Source.SEARCHED, result.depth, client.lastNps)
                }
                computed.add(eval)
                onPosition(eval, timing)
                if ((index + 1) % CHECKPOINT_EVERY_PLIES == 0) checkpoint(computed.toList())
            }
        } catch (e: Exception) {
            checkpoint(computed.toList())
            throw e
        }
        return computed
    }

    companion object {
        /** `AnalysisService.CHECKPOINT_EVERY_PLIES`. */
        const val CHECKPOINT_EVERY_PLIES = 5

        /** Added to the movetime cap before a search is declared hung. */
        const val SEARCH_GRACE_MS = 60_000L

        /** FEN before ply 1, FEN after each ply — `moves.size + 1` positions (`plyFens`). */
        fun plyFens(game: PgnGame): List<String> {
            if (game.moves.isEmpty()) return listOf(game.startFen ?: Position.STANDARD_START_FEN)
            val fens = ArrayList<String>(game.moves.size + 1)
            fens.add(game.moves.first().positionFenBefore)
            game.moves.forEach { fens.add(it.positionFenAfter) }
            return fens
        }

        /** `AnalysisService.terminalEvalOrNull`. */
        fun terminalEvalOrNull(fen: String, depth: Int): PositionEval? {
            val position = Position.fromFen(fen)
            if (position.legalMoves().isNotEmpty()) return null
            val line = if (position.isInCheck()) {
                EngineLineInput(multiPv = 1, scoreCp = null, mateIn = 0, depth = depth, pvUci = emptyList())
            } else {
                EngineLineInput(multiPv = 1, scoreCp = 0, mateIn = null, depth = depth, pvUci = emptyList())
            }
            return PositionEval(fen = fen, lines = listOf(line), depth = depth)
        }

        /** `AnalysisService.usableResumePrefix`. */
        fun usableResumePrefix(positions: List<String>, cached: List<PositionEval>): List<PositionEval> =
            cached.withIndex()
                .takeWhile { (i, eval) -> i < positions.size && positions[i] == eval.fen }
                .map { it.value }

        /** `AnalysisService.toPositionEval`. */
        fun toPositionEval(fen: String, result: AnalysisResult, depth: Int): PositionEval {
            val lines = result.lines.map {
                EngineLineInput(multiPv = it.multiPv, scoreCp = it.scoreCp, mateIn = it.mateIn, depth = it.depth, pvUci = it.pvUci)
            }
            val effectiveLines = if (lines.isEmpty()) {
                listOf(EngineLineInput(multiPv = 1, scoreCp = null, mateIn = null, depth = depth, pvUci = listOf(result.bestMoveUci)))
            } else lines
            return PositionEval(fen = fen, lines = effectiveLines, depth = result.depth)
        }

        private fun secondsSince(t0: Long) = (System.nanoTime() - t0) / 1e9
    }
}
