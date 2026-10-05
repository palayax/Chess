package net.palaya.chessanalyzer.desktop.engine

import net.palaya.chessanalyzer.core.analysis.EngineLineInput
import net.palaya.chessanalyzer.core.analysis.PositionEval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The copied parser (`engine/.../AnalysisModels.kt:54-136`) and the search fold, replayed over a
 * captured Stockfish 19 transcript (`uci/transcript_multipv3.txt`) and checked against
 * hand-written `PositionEval`s — the parity check of design §4/§13 that needs no engine.
 */
class UciLineParserTest {

    private fun transcript(): List<String> {
        val stream = javaClass.getResourceAsStream("/uci/transcript_multipv3.txt")
            ?: throw AssertionError("fixture uci/transcript_multipv3.txt missing")
        return stream.bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") }
    }

    /** Replays the transcript through [SearchCollector] (the code [UciClient.analyze] runs). */
    private fun replay(): List<AnalysisResult> {
        val results = ArrayList<AnalysisResult>()
        var collector = SearchCollector()
        for (line in transcript()) {
            collector.accept(line)?.let {
                results.add(it)
                collector = SearchCollector()
            }
        }
        return results
    }

    @Test
    fun transcriptReplaysToExpectedPositionEvals() {
        val results = replay()
        assertEquals("three searches in the fixture", 3, results.size)
        val depths = listOf(6, 8, 5)
        val fens = listOf(
            "6k1/5ppp/8/8/8/8/5PPP/3R2K1 w - - 0 1",
            "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5Q2/PPPP1PPP/RNB1K1NR w KQkq - 2 3",
            "3R2k1/5ppp/8/8/8/8/5PPP/6K1 b - - 1 1",
        )
        val evals = results.mapIndexed { i, r -> EngineAnalyzer.toPositionEval(fens[i], r, depths[i]) }

        val expected = listOf(
            PositionEval(
                fens[0],
                listOf(
                    EngineLineInput(1, null, 1, 6, listOf("d1d8")),
                    EngineLineInput(2, 714, null, 6, listOf("g1h1", "f7f6", "d1d8", "g8f7", "d8d7", "f7g8")),
                    EngineLineInput(3, 710, null, 6, listOf("f2f4", "f7f6", "d1d8", "g8f7")),
                ),
                6,
            ),
            PositionEval(
                fens[1],
                listOf(
                    EngineLineInput(1, null, 1, 8, listOf("f3f7")),
                    EngineLineInput(2, 317, null, 8, listOf("c4f7", "e8e7", "g1e2", "g8f6", "f7b3", "d8e8")),
                    // The lowerbound line for slot 3 was superseded by the later exact line.
                    EngineLineInput(3, 90, null, 8, listOf("a2a3", "g8f6", "g1e2", "c6d4", "e2d4", "e5d4")),
                ),
                8,
            ),
            // `bestmove (none)` after `info depth 0 score mate 0`: parses, depth 0, mate 0.
            PositionEval(fens[2], listOf(EngineLineInput(1, null, 0, 0, emptyList())), 0),
        )
        assertEquals(expected, evals)
        assertEquals("d1d8", results[0].bestMoveUci)
        assertEquals("f3f7", results[1].bestMoveUci)
        assertTrue("bestmove (none) is terminal", results[2].isTerminal)
        assertEquals(AnalysisResult.NO_MOVE, results[2].bestMoveUci)
    }

    @Test
    fun bestMoveLines() {
        assertEquals("e2e4" to "e7e5", UciLineParser.parseBestMove("bestmove e2e4 ponder e7e5"))
        assertEquals("d1d8" to null, UciLineParser.parseBestMove("bestmove d1d8"))
        assertEquals("(none)" to null, UciLineParser.parseBestMove("bestmove (none)"))
        assertNull(UciLineParser.parseBestMove("info depth 1 score cp 10 pv e2e4"))
        assertNull(UciLineParser.parseBestMove("bestmove"))
    }

    @Test
    fun infoLines() {
        val bound = UciLineParser.parseInfo(
            "info depth 8 seldepth 10 multipv 3 score cp 120 lowerbound nodes 2100 nps 448000 hashfull 0 tbhits 0 time 5 pv a2a3"
        )!!
        assertEquals(EngineLine(3, 120, null, 8, 10, 2100, 448000, 5, listOf("a2a3")), bound)

        val upper = UciLineParser.parseInfo("info depth 20 multipv 1 score cp -35 upperbound nodes 5 pv e7e5 g1f3")!!
        assertEquals(-35, upper.scoreCp)
        assertEquals(listOf("e7e5", "g1f3"), upper.pvUci)

        val losing = UciLineParser.parseInfo("info depth 12 multipv 2 score mate -3 pv g8h8 d1d8")!!
        assertEquals(-3, losing.mateIn)
        assertNull(losing.scoreCp)

        assertNull("info string carries no depth", UciLineParser.parseInfo("info string Using 4 threads"))
        assertNull("currmove carries no score or pv", UciLineParser.parseInfo("info depth 8 currmove g1e2 currmovenumber 3"))
        assertNull(UciLineParser.parseInfo("readyok"))
    }

    @Test
    fun noInfoLinesFallsBackToTheBestMoveAlone() {
        // AnalysisService.toPositionEval: an engine that answers with a bare bestmove still yields one line.
        val eval = EngineAnalyzer.toPositionEval("fen", AnalysisResult(emptyList(), "e2e4", null, 0), 14)
        assertEquals(listOf(EngineLineInput(1, null, null, 14, listOf("e2e4"))), eval.lines)
        assertEquals(0, eval.depth)
    }
}
