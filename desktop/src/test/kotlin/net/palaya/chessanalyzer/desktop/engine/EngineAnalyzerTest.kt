package net.palaya.chessanalyzer.desktop.engine

import net.palaya.chessanalyzer.core.analysis.PositionEval
import net.palaya.chessanalyzer.core.pgn.PgnParser
import net.palaya.chessanalyzer.desktop.TestEnv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** The eval loop's checkpoint/resume contract (`AnalysisService.kt:113-121, 157-163, 253-258`). */
class EngineAnalyzerTest {

    private val settings = EngineSettings(depth = 8, multiPv = 3, movetimeCapMs = 8000, threads = TestEnv.THREADS, hashMb = TestEnv.HASH_MB)

    private fun fens(): List<String> {
        val game = PgnParser.parse(Files.readString(TestEnv.fixture("chesscom_style_game.pgn"))).first()
        return EngineAnalyzer.plyFens(game).also { assertEquals(game.moves.size + 1, it.size) }
    }

    @Test
    fun checkpointsEveryFivePositionsAndResumesOnlyTheRest() {
        val fens = fens()
        val checkpoints = ArrayList<List<PositionEval>>()
        val full = UciClient(TestEnv.stockfish).use { c ->
            c.start(settings.threads, settings.hashMb, settings.multiPv)
            val evals = EngineAnalyzer(c, settings).analyze(fens, checkpoint = { checkpoints.add(it) })
            // The final position of this game is checkmate: short-circuited, so one `go` fewer.
            assertEquals(fens.size - 1, c.searchesIssued)
            evals
        }
        assertEquals(fens.size, full.size)
        assertEquals((1..fens.size / 5).map { it * 5 }, checkpoints.map { it.size })
        assertEquals(fens, full.map { it.fen })

        // Resume from the 10-position checkpoint: only the rest is searched.
        val partial = checkpoints[1]
        UciClient(TestEnv.stockfish).use { c ->
            c.start(settings.threads, settings.hashMb, settings.multiPv)
            val sources = ArrayList<PlyTiming.Source>()
            val resumed = EngineAnalyzer(c, settings).analyze(fens, resumable = partial, onPosition = { _, t -> sources.add(t.source) })
            assertEquals(partial, resumed.take(10))
            assertEquals(fens.size - 10 - 1, c.searchesIssued)
            assertEquals(List(10) { PlyTiming.Source.RESUMED }, sources.take(10))
            assertEquals(PlyTiming.Source.TERMINAL, sources.last())
            assertEquals(fens, resumed.map { it.fen })
        }
    }

    @Test
    fun resumeStopsAtTheFirstMisalignedFen() {
        val fens = fens()
        val fake = fens.take(6).map { PositionEval(it, emptyList(), 1) }.toMutableList()
        fake[3] = PositionEval("8/8/8/8/8/8/8/K6k w - - 0 1", emptyList(), 1)
        val usable = EngineAnalyzer.usableResumePrefix(fens, fake)
        assertEquals(3, usable.size)
        assertTrue(EngineAnalyzer.usableResumePrefix(fens, emptyList()).isEmpty())
    }
}
