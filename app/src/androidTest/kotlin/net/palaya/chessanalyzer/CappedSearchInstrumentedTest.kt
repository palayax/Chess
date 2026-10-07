package net.palaya.chessanalyzer

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.palaya.chessanalyzer.engine.SearchProgress
import net.palaya.chessanalyzer.ui.model.AnalysisStrength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F1 regression, on a device with the real engine: the position from games/game01.txt after
 * 23.Rdg1, which took 59-86 s and 80-129 M nodes on the host at depth 18 with no limit (and many
 * minutes on the owner's phone), now finishes within the Deep budget.
 *
 * The bound on the wall time is set from the emulator's measured speed (docs/ANALYSIS_SPEC.md §8.1):
 * chess34 searched this position at about 0.63 M nodes/s, so the whole 45 M Deep budget takes about
 * 72 s there; [WALL_BOUND_MS] is 2.5 times that, which absorbs a busy host without letting "forever"
 * (or the 270 s time cap, the slow-phone safety net) pass.
 * The hash is cleared first (`ucinewgame`): the cold hash is the measured worst case.
 */
@RunWith(AndroidJUnit4::class)
class CappedSearchInstrumentedTest {

    @Test
    fun game01AfterRdg1AtDeepFinishesWithinTheBudgetAndSaysHowDeepItGot() = runBlocking {
        TestApp.ensureSetUp()
        val engine = TestApp.app.engineController.ensureReady()
        engine.newGame()
        engine.setPosition(fen = GAME01_AFTER_RDG1)
        val budget = AnalysisStrength.DEEP.budget
        val depths = ArrayList<SearchProgress>()

        val started = SystemClock.elapsedRealtime()
        val result = withTimeout(WALL_BOUND_MS) {
            engine.analyze(
                multiPv = 3,
                depth = DEEP_DEPTH,
                movetimeMs = budget.movetimeMs,
                nodes = budget.nodes,
                onProgress = { depths += it },
            )
        }
        val wallMs = SystemClock.elapsedRealtime() - started
        val nps = if (wallMs > 0) result.nodes * 1000 / wallMs else 0
        Log.i(TAG, "game01 Rdg1 Deep: depth ${result.depth}/$DEEP_DEPTH nodes ${result.nodes} wall ${wallMs} ms nps $nps stoppedEarly ${result.stoppedEarly}")

        assertTrue("finished in $wallMs ms", wallMs < WALL_BOUND_MS)
        assertEquals("three lines", 3, result.lines.size)
        assertTrue("every line from one depth: ${result.lines.map { it.depth }}", result.lines.all { it.depth == result.depth })
        assertTrue("no bound scores", result.lines.none { it.bound })
        assertTrue("a real search happened", result.depth >= 10)

        // The flag must agree with the nodes used: below full depth only when a limit stopped it.
        val eval = TestApp.analysisService().toPositionEval(GAME01_AFTER_RDG1, result, DEEP_DEPTH)
        if (result.depth < DEEP_DEPTH) {
            assertTrue(result.stoppedEarly)
            assertTrue("flagged as capped", eval.isCapped)
            assertTrue(
                "stopped by a limit: nodes ${result.nodes} of ${budget.nodes}, $wallMs ms of ${budget.movetimeMs}",
                result.nodes >= budget.nodes || wallMs >= budget.movetimeMs,
            )
        } else {
            assertFalse(result.stoppedEarly)
            assertFalse("full depth is not capped", eval.isCapped)
            // Full depth is only claimed when no limit was hit: a search that reached the node
            // budget printed a stop batch, which may carry the requested depth's label (spec §8.2).
            assertTrue("full depth used less than the node budget: ${result.nodes}", result.nodes < budget.nodes)
            assertTrue("and less than the time cap: $wallMs ms", wallMs < budget.movetimeMs)
        }
        // The node limit is a real limit: Stockfish checks it every few hundred nodes per thread.
        assertTrue("nodes ${result.nodes} near the budget ${budget.nodes}", result.nodes <= budget.nodes + NODE_CHECK_SLACK)

        // The progress callback saw the search deepen, ending at least as deep as the result.
        assertTrue("progress was reported", depths.isNotEmpty())
        assertEquals("depths only grow", depths.map { it.depth }, depths.map { it.depth }.sorted())
        assertTrue(depths.last().depth >= result.depth)

        // And the engine is clean for the next position (no stray bestmove left in the pipe).
        engine.setPosition(fen = null)
        val next = withTimeout(60_000) { engine.analyze(multiPv = 1, depth = 8) }
        assertEquals(8, next.depth)
    }

    private companion object {
        const val TAG = "CappedSearch"
        const val DEEP_DEPTH = 18
        const val GAME01_AFTER_RDG1 = "r1q2rk1/pb1n1p1p/2pP2p1/2P1bB2/Q3N3/4Bp2/PP3P2/2K3RR b - - 1 23"

        /** Stockfish tests the node limit about every 512 nodes per thread; 4 threads, with margin. */
        const val NODE_CHECK_SLACK = 100_000L

        /** See the class doc and ANALYSIS_SPEC §8.1. */
        const val WALL_BOUND_MS = 180_000L
    }
}
