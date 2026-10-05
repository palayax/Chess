package net.palaya.chessanalyzer.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end test against the real native engine (libstockfish.so) on a device/emulator.
 *
 * The NNUE net is bundled in the APK's assets (we build with NNUE_EMBEDDING_OFF, so it is not
 * compiled in) and [TestNet] copies it to the test package's filesDir through [BundledNetProvider],
 * exactly as the app does. Nothing is pushed to the device and no test skips itself.
 *
 * Note: [StockfishEngine.start] redirects the whole process's STDIN_FILENO/STDOUT_FILENO to pipes
 * talking to the native engine thread (see engine/src/main/cpp/jni_bridge.cpp). That is
 * process-global, so each test starts and shuts down its own engine in a try/finally.
 */
@RunWith(AndroidJUnit4::class)
class StockfishEngineInstrumentedTest {

    @Test
    fun uciHandshakeSucceedsWithoutANet() = runBlocking {
        val engine = StockfishEngine()
        try {
            engine.start()
            withTimeout(30_000) { engine.uci() }
            assertTrue("expected readyok", withTimeout(30_000) { engine.isReady() })
        } finally {
            engine.shutdown()
        }
    }

    /**
     * The regression test for the defect that killed the whole app: Stockfish's
     * nnue/network.cpp calls exit(EXIT_FAILURE) when it cannot load a net, which in-process
     * takes the app down. [StockfishEngine.setEvalFile] must reject bad paths *before* the
     * engine ever sees them, so this must throw rather than terminate the test process.
     */
    @Test
    fun missingNetThrowsInsteadOfKillingTheProcess() = runBlocking {
        val engine = StockfishEngine()
        try {
            engine.start()
            withTimeout(30_000) { engine.uci() }

            var threw = false
            try {
                engine.setEvalFile("/data/local/tmp/definitely-not-a-net.nnue")
            } catch (e: InvalidNetworkFileException) {
                threw = true
            }
            assertTrue("setEvalFile must reject a missing net", threw)

            // Analysing with no net loaded must also throw, not crash.
            var threwOnAnalyze = false
            try {
                engine.setPosition(fen = null, moves = emptyList())
                engine.analyze(multiPv = 1, depth = 4)
            } catch (e: InvalidNetworkFileException) {
                threwOnAnalyze = true
            }
            assertTrue("analyze without a net must throw", threwOnAnalyze)

            // And the engine must still be alive and responsive afterwards.
            assertTrue("engine should survive", withTimeout(30_000) { engine.isReady() })
        } finally {
            engine.shutdown()
        }
    }

    @Test
    fun truncatedNetIsRejected() = runBlocking {
        val engine = StockfishEngine()
        val truncated = File.createTempFile("truncated", ".nnue").apply { writeBytes(ByteArray(1024)) }
        try {
            engine.start()
            withTimeout(30_000) { engine.uci() }
            var threw = false
            try {
                engine.setEvalFile(truncated.absolutePath)
            } catch (e: InvalidNetworkFileException) {
                threw = true
            }
            assertTrue("a 1 KB file is not a net and must be rejected", threw)
        } finally {
            truncated.delete()
            engine.shutdown()
        }
    }

    @Test
    fun searchFromStartPositionReturnsABestMove() = runBlocking {
        val engine = StockfishEngine()
        try {
            engine.start()
            withTimeout(30_000) { engine.uci() }
            engine.setEvalFile(TestNet.net().absolutePath)
            engine.newGame()
            engine.setPosition(fen = null, moves = emptyList())

            val result = withTimeout(120_000) { engine.analyze(multiPv = 1, depth = 12) }

            assertNotNull(result.bestMoveUci)
            assertTrue("bestmove should be a UCI move", result.bestMoveUci.matches(Regex("[a-h][1-8][a-h][1-8][qrbn]?")))
            assertTrue("should have at least one PV line", result.lines.isNotEmpty())
            // The start position is roughly balanced; a sane engine will not report a huge score.
            val cp = result.lines.first().scoreCp
            assertNotNull("expected a centipawn score at the start position", cp)
            assertTrue("start position eval should be near equal, got $cp", kotlin.math.abs(cp!!) < 200)
        } finally {
            engine.shutdown()
        }
    }

    /** MultiPV must actually produce multiple distinct lines — the analysis pipeline depends on it. */
    @Test
    fun multiPvReturnsMultipleDistinctLines() = runBlocking {
        val engine = StockfishEngine()
        try {
            engine.start()
            withTimeout(30_000) { engine.uci() }
            engine.setEvalFile(TestNet.net().absolutePath)
            engine.newGame()
            engine.setPosition(fen = null, moves = emptyList())

            val result = withTimeout(120_000) { engine.analyze(multiPv = 3, depth = 12) }

            assertEquals("expected 3 PV lines", 3, result.lines.size)
            val firstMoves = result.lines.mapNotNull { it.pvUci.firstOrNull() }
            assertEquals("each PV must start with a different move", 3, firstMoves.toSet().size)
        } finally {
            engine.shutdown()
        }
    }

    /**
     * Regression test. A position with no legal moves makes Stockfish answer `bestmove (none)`.
     * The parser used to discard that line, so `analyze()` waited forever for a terminator that had
     * already been sent — and since most annotated games end in mate, that froze analysis on the
     * final position of a typical game. Must return promptly and report the position as terminal.
     */
    @Test
    fun analysingACheckmatedPositionReturnsInsteadOfHanging() = runBlocking {
        val engine = StockfishEngine()
        try {
            engine.start()
            withTimeout(30_000) { engine.uci() }
            engine.setEvalFile(TestNet.net().absolutePath)
            engine.newGame()
            // Black has just been mated (the Opera Game's final position, Rd8#).
            engine.setPosition(fen = "1n1Rkb1r/p4ppp/4q3/4p1B1/4P3/8/PPP2PPP/2K5 b k - 0 17")

            // A generous ceiling that is still far below "forever" — the bug hung indefinitely.
            val result = withTimeout(30_000) { engine.analyze(multiPv = 1, depth = 12) }

            assertTrue("engine should report a terminal position", result.isTerminal)
            assertEquals(AnalysisResult.NO_MOVE, result.bestMoveUci)
            // And the engine must still be usable for the next position.
            assertTrue("engine should survive", withTimeout(30_000) { engine.isReady() })
        } finally {
            engine.shutdown()
        }
    }

    /** A forced mate must be found and reported as a mate score, not a centipawn score. */
    @Test
    fun findsForcedMate() = runBlocking {
        val engine = StockfishEngine()
        try {
            engine.start()
            withTimeout(30_000) { engine.uci() }
            engine.setEvalFile(TestNet.net().absolutePath)
            engine.newGame()
            // Back-rank mate in 1: Ra8#.
            engine.setPosition(fen = "6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1", moves = emptyList())

            val result = withTimeout(120_000) { engine.analyze(multiPv = 1, depth = 12) }

            val line = result.lines.first()
            assertNotNull("expected a mate score, got cp=${line.scoreCp}", line.mateIn)
            assertEquals("expected mate in 1", 1, line.mateIn)
            assertEquals("a8 rook mate", "a1a8", result.bestMoveUci)
        } finally {
            engine.shutdown()
        }
    }
}
