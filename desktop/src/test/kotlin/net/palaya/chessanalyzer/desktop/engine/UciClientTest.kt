package net.palaya.chessanalyzer.desktop.engine

import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.desktop.TestEnv
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Against the real `pc/bin/stockfish` binary (design §13). Threads 4 throughout. */
class UciClientTest {

    private lateinit var client: UciClient

    @Before
    fun setUp() {
        client = UciClient(TestEnv.stockfish)
        client.start(threads = TestEnv.THREADS, hashMb = TestEnv.HASH_MB, multiPv = 3)
    }

    @After
    fun tearDown() = client.close()

    @Test
    fun handshakeIdentifiesStockfish19() {
        println("engine: ${client.engineName}")
        assertEquals("Stockfish 19", client.engineName)
    }

    @Test
    fun mateInOneReportsMateInOneOnLineOne() {
        client.setPosition(BACK_RANK_MATE_IN_1)
        val r = client.analyze(multiPv = 3, depth = 10, movetimeMs = null)
        println("mate-in-1: ${r.lines}")
        assertEquals(1, r.lines.first().multiPv)
        assertEquals(1, r.lines.first().mateIn)
        assertNull(r.lines.first().scoreCp)
        assertEquals("d1d8", r.lines.first().pvUci.first())
        assertEquals("d1d8", r.bestMoveUci)
    }

    @Test
    fun multiPvThreeReturnsThreeDistinctLines() {
        client.setPosition(Position.STANDARD_START_FEN)
        val r = client.analyze(multiPv = 3, depth = 12, movetimeMs = null)
        println("startpos MultiPV 3: " + r.lines.joinToString { "${it.multiPv}:${it.pvUci.firstOrNull()} cp ${it.scoreCp}" })
        assertEquals(listOf(1, 2, 3), r.lines.map { it.multiPv })
        assertEquals("three different first moves", 3, r.lines.map { it.pvUci.first() }.toSet().size)
    }

    @Test
    fun depthReachesTheRequestedDepth() {
        client.setPosition(ITALIAN)
        // The movetime cap is set far above what depth 16 needs, so depth is the binding limit.
        val r = client.analyze(multiPv = 3, depth = 16, movetimeMs = 120_000)
        println("depth: result ${r.depth}, lines ${r.lines.map { it.depth }}, info lines ${client.lastSearchInfoLines}")
        assertTrue("result depth ${r.depth} >= 16", r.depth >= 16)
        r.lines.forEach { assertTrue("line ${it.multiPv} depth ${it.depth} >= 16", it.depth >= 16) }
    }

    @Test
    fun checkmatedAndStalematedPositionsAreShortCircuitedWithoutAGo() {
        val settings = EngineSettings(depth = 18, multiPv = 3, movetimeCapMs = 8000, threads = TestEnv.THREADS, hashMb = TestEnv.HASH_MB)
        val before = client.searchesIssued
        val evals = EngineAnalyzer(client, settings).analyze(listOf(CHECKMATED, STALEMATED))
        assertEquals("no `go` sent for terminal positions", before, client.searchesIssued)
        assertEquals(0, evals[0].best!!.mateIn)
        assertNull(evals[0].best!!.scoreCp)
        assertEquals(0, evals[1].best!!.scoreCp)
        assertNull(evals[1].best!!.mateIn)
        assertEquals(18, evals[0].depth)

        // And if the engine *is* asked, `bestmove (none)` must still terminate the read (the
        // hang StockfishEngine once had, AnalysisModels.kt:59-66).
        client.setPosition(CHECKMATED)
        val r = client.analyze(multiPv = 3, depth = 10, movetimeMs = null, timeoutMs = 20_000)
        assertTrue(r.isTerminal)
        assertEquals(before + 1, client.searchesIssued)
    }

    @Test
    fun stdinStaysOpenUntilBestmove() {
        // The RUN_LOG failure: with stdin closed, Stockfish aborts `go` and answers at once.
        client.setPosition(Position.STANDARD_START_FEN)
        val r = client.analyze(multiPv = 3, depth = 20, movetimeMs = null)
        println("stdin-open: depth ${r.depth}, ${client.lastSearchInfoLines} info lines, bestmove ${r.bestMoveUci}")
        assertTrue("depth ${r.depth} >= 20", r.depth >= 20)
        assertTrue("at least 20 depth lines seen, got ${client.lastSearchInfoLines}", client.lastSearchInfoLines >= 20)
        assertTrue("engine still alive after the search", client.isAlive)
        // The same process keeps answering.
        client.setPosition(BACK_RANK_MATE_IN_1)
        assertEquals(1, client.analyze(multiPv = 3, depth = 8, movetimeMs = null).lines.first().mateIn)
    }

    @Test
    fun negativeControlClosingStdinAbortsTheSearch() {
        // Proves the regression test above can fail: the naive driver (write, close stdin, read)
        // gets a bestmove without the requested depth.
        val p = ProcessBuilder(TestEnv.stockfish.toString()).redirectErrorStream(true).start()
        p.outputStream.bufferedWriter().use { w ->
            w.write("uci\nsetoption name Threads value ${TestEnv.THREADS}\nposition startpos\ngo depth 20\n")
        } // closes stdin
        val lines = p.inputStream.bufferedReader().readLines()
        assertTrue("process exits", p.waitFor(30, TimeUnit.SECONDS))
        val maxDepth = lines.mapNotNull { UciLineParser.parseInfo(it)?.depth }.maxOrNull() ?: 0
        println("closed-stdin control: max depth $maxDepth, bestmove line: ${lines.lastOrNull { it.startsWith("bestmove") }}")
        assertTrue("search aborted before depth 20 (reached $maxDepth)", maxDepth < 20)
    }

    companion object {
        const val BACK_RANK_MATE_IN_1 = "6k1/5ppp/8/8/8/8/5PPP/3R2K1 w - - 0 1"
        const val CHECKMATED = "3R2k1/5ppp/8/8/8/8/5PPP/6K1 b - - 1 1"
        const val STALEMATED = "7k/5Q2/6K1/8/8/8/8/8 b - - 0 1"
        const val ITALIAN = "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3"
    }
}
