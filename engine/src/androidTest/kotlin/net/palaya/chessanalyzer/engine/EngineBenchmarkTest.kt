package net.palaya.chessanalyzer.engine

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis

/**
 * Measures how long a full-game analysis actually takes, so the default search depth is chosen
 * from evidence rather than from the spec's suggestion.
 *
 * A real game is ~35 distinct positions (each ply's "after" position is the next ply's "before"),
 * analysed at MultiPV 3. This walks a genuine opening line rather than re-searching the start
 * position, so the middlegame positions — which are the slow ones — are represented.
 *
 * Results are logged under the tag "EngineBench"; read them with:
 *     adb logcat -d -s EngineBench
 *
 * **Depth 18 runs under the production Deep budget** (D2d). Since F1 the app never sends a bare
 * `go depth 18`: every position is `go depth 18 nodes 45000000 movetime 270000` (`SearchBudget.DEEP` in
 * `:app`, docs/ANALYSIS_SPEC.md §8.1; `:engine` cannot import it, so the two numbers are restated in
 * [DEEP_NODES] / [DEEP_MOVETIME_MS] and must follow it). The old unbounded search measured a behaviour the
 * app no longer has, and its 900 s per-position timeout was hit once on a loaded host (F1, chess36). With
 * the budget the search stops by itself, so the per-position timeout is the movetime cap plus a margin
 * for the engine's own wind-down, and the test cannot hang. It still logs total/mean/worst time, plus how
 * many positions the budget capped (each capped position also reports the depth it reached).
 */
@RunWith(AndroidJUnit4::class)
class EngineBenchmarkTest {


    /** The Opera Game, as UCI moves — the same fixture the app is tested with. */
    private val moves = listOf(
        "e2e4", "e7e5", "g1f3", "d7d6", "d2d4", "c8g4", "d4e5", "g4f3", "d1f3", "d6e5",
        "f1c4", "g8f6", "f3b3", "d8e7", "b1c3", "c7c6", "c1g5", "b7b5", "c3b5", "c6b5",
        "c4b5", "b8d7", "e1c1", "a8d8", "d1d7", "d8d7", "h1d1", "e7e6", "b5d7", "f6d7",
        "b3b8", "d7b8", "d1d8"
    )

    private fun benchmarkAtDepth(depth: Int, nodes: Long? = null, movetimeMs: Long? = null): Unit = runBlocking {
        val engine = StockfishEngine()
        try {
            engine.start()
            withTimeout(60_000) { engine.uci() }
            engine.setEvalFile(TestNet.net().absolutePath)
            engine.setOption("Threads", "4")
            engine.setOption("Hash", "96")
            engine.newGame()

            // Unbudgeted (depth 12, seconds per position): the old generous cap. Budgeted: the
            // movetime cap plus a minute for the stop to land and the last batch to be read.
            val perPositionTimeoutMs = movetimeMs?.let { it + 60_000 } ?: 900_000L
            val perPosition = mutableListOf<Long>()
            val capped = mutableListOf<String>()
            val total = measureTimeMillis {
                for (i in 0..moves.size) {
                    val played = moves.take(i)
                    perPosition += measureTimeMillis {
                        engine.setPosition(fen = null, moves = played)
                        val r = withTimeout(perPositionTimeoutMs) {
                            engine.analyze(multiPv = 3, depth = depth, movetimeMs = movetimeMs, nodes = nodes)
                        }
                        check(r.isTerminal || r.lines.isNotEmpty()) { "no lines at ply $i" }
                        if (r.stoppedEarly) capped += "ply $i: depth ${r.depth}, ${r.nodes} nodes, ${r.timeMs} ms"
                    }
                }
            }

            val n = perPosition.size
            val mean = perPosition.average()
            val worst = perPosition.max()
            Log.i(
                "EngineBench",
                "depth=$depth nodes=${nodes ?: "-"} movetimeMs=${movetimeMs ?: "-"} positions=$n totalMs=$total " +
                    "meanMs=${"%.0f".format(mean)} worstMs=$worst totalSec=${"%.1f".format(total / 1000.0)} " +
                    "capped=${capped.size}" + (if (capped.isEmpty()) "" else " [${capped.joinToString("; ")}]")
            )
        } finally {
            engine.shutdown()
        }
    }

    @Test fun benchmarkDepth12(): Unit = benchmarkAtDepth(12)

    /** Deep, exactly as the app runs it since F1: depth 18 under the Deep node budget and time cap. */
    @Test fun benchmarkDepth18(): Unit = benchmarkAtDepth(18, nodes = DEEP_NODES, movetimeMs = DEEP_MOVETIME_MS)

    private companion object {
        /** `SearchBudget.DEEP.nodes` in `:app` (docs/ANALYSIS_SPEC.md §8.1). */
        const val DEEP_NODES = 45_000_000L

        /** `SearchBudget.DEEP.movetimeMs` in `:app`: a safety net for slow phones, not the real limit. */
        const val DEEP_MOVETIME_MS = 270_000L
    }
}
