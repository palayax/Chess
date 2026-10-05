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

    private fun benchmarkAtDepth(depth: Int): Unit = runBlocking {
        val engine = StockfishEngine()
        try {
            engine.start()
            withTimeout(60_000) { engine.uci() }
            engine.setEvalFile(TestNet.net().absolutePath)
            engine.setOption("Threads", "4")
            engine.setOption("Hash", "96")
            engine.newGame()

            val perPosition = mutableListOf<Long>()
            val total = measureTimeMillis {
                for (i in 0..moves.size) {
                    val played = moves.take(i)
                    perPosition += measureTimeMillis {
                        engine.setPosition(fen = null, moves = played)
                        withTimeout(900_000) { engine.analyze(multiPv = 3, depth = depth) }
                    }
                }
            }

            val n = perPosition.size
            val mean = perPosition.average()
            val worst = perPosition.max()
            Log.i(
                "EngineBench",
                "depth=$depth positions=$n totalMs=$total meanMs=${"%.0f".format(mean)} " +
                    "worstMs=$worst totalSec=${"%.1f".format(total / 1000.0)}"
            )
        } finally {
            engine.shutdown()
        }
    }

    @Test fun benchmarkDepth12(): Unit = benchmarkAtDepth(12)

    @Test fun benchmarkDepth18(): Unit = benchmarkAtDepth(18)
}
