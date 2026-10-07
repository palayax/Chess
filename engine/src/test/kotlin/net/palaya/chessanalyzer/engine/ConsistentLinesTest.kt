package net.palaya.chessanalyzer.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host tests for the rule that every MultiPV line of a result comes from one depth (F1,
 * docs/ANALYSIS_SPEC.md §8.2), against real Stockfish 19 output.
 */
class ConsistentLinesTest {

    private fun parse(transcript: String): List<EngineLine> =
        transcript.trimIndent().lines().mapNotNull { UciLineParser.parseInfo(it) }

    /**
     * Stockfish 19 on the game01 position after 23.Rdg1 (Threads 4, Hash 96, MultiPV 3),
     * `go depth 18 nodes 12000000`, recorded on the host (PVs shortened). Iteration 15 finished at
     * 9.3 M nodes; the node limit stopped iteration 16, and the stop batch re-prints the depth-15
     * scores and PVs **labelled depth 16**. Trusting that batch would claim depth 16 for numbers
     * searched to depth 15.
     */
    private val cappedGame01 = """
        info string Using 4 threads
        info depth 14 seldepth 48 multipv 1 score cp -572 nodes 4682540 nps 881170 time 5314 pv d7c5 e4c5 c8f5
        info depth 14 seldepth 33 multipv 2 score cp -620 nodes 4682540 nps 881170 time 5314 pv b7a6 e4g5 c8d8
        info depth 14 seldepth 28 multipv 3 score cp -663 nodes 4682540 nps 881170 time 5314 pv d7b6 f5c8 b6a4
        info depth 15 currmove d7c5 currmovenumber 1
        info depth 15 seldepth 48 multipv 1 score cp -598 nodes 9345856 nps 889827 time 10503 pv d7c5 e4c5 c8f5
        info depth 15 seldepth 34 multipv 2 score cp -614 nodes 9345856 nps 889827 time 10503 pv b7a6 e3d4 e5f4
        info depth 15 seldepth 33 multipv 3 score cp -686 nodes 9345856 nps 889827 time 10503 pv c8e8 e4g5 e5b2
        info depth 16 seldepth 48 multipv 1 score cp -598 nodes 12000085 nps 901177 time 13316 pv d7c5 e4c5 c8f5
        info depth 16 seldepth 34 multipv 2 score cp -614 nodes 12000085 nps 901177 time 13316 pv b7a6 e3d4 e5f4
        info depth 16 seldepth 33 multipv 3 score cp -686 nodes 12000085 nps 901177 time 13316 pv c8e8 e4g5 e5b2
    """

    @Test
    fun aStoppedSearchDiscardsTheStopBatchAndUsesTheLastCompleteIteration() {
        val chosen = ConsistentLines.select(parse(cappedGame01), limitHit = true)
        assertEquals(15, chosen.depth)
        assertEquals(listOf(1, 2, 3), chosen.lines.map { it.multiPv })
        assertTrue(chosen.lines.all { it.depth == 15 })
        assertEquals(listOf(-598, -614, -686), chosen.lines.map { it.scoreCp })
        assertEquals(9_345_856L, chosen.lines.first().nodes)
    }

    @Test
    fun aSearchThatEndedByReachingItsDepthUsesItsLastBatch() {
        // Same lines, but no limit was hit: the search ended because depth 16 was done, and
        // Stockfish printed no stop batch, so the last batch is a real iteration.
        val chosen = ConsistentLines.select(parse(cappedGame01), limitHit = false)
        assertEquals(16, chosen.depth)
        assertEquals(12_000_085L, chosen.lines.first().nodes)
    }

    /**
     * Seen on the emulator (F1): the Deep budget (45 M nodes) stopped the game01 search 1,673 nodes
     * past the limit, and the stop batch printed all three slots as "depth 18", the requested depth.
     * It must still be discarded: the depth-18 iteration was not finished. (Modelled on that run:
     * the final node count is the recorded one, the scores and the depth-17 batch are illustrative.)
     */
    @Test
    fun aStopBatchLabelledWithTheRequestedDepthIsStillDiscarded() {
        val lines = parse(
            """
            info depth 17 multipv 1 score cp -590 nodes 31000000 time 49000 pv d7c5 e4c5
            info depth 17 multipv 2 score cp -610 nodes 31000000 time 49000 pv b7a6 e3d4
            info depth 17 multipv 3 score cp -680 nodes 31000000 time 49000 pv c8e8 e4g5
            info depth 18 multipv 1 score cp -595 nodes 45001673 time 71700 pv d7c5 e4c5
            info depth 18 multipv 2 score cp -610 nodes 45001673 time 71700 pv b7a6 e3d4
            info depth 18 multipv 3 score cp -680 nodes 45001673 time 71700 pv c8e8 e4g5
            """,
        )
        val chosen = ConsistentLines.select(lines, limitHit = true)
        assertEquals(17, chosen.depth)
        assertEquals(31_000_000L, chosen.lines.first().nodes)
    }

    @Test
    fun aMixedDepthBatchIsNeverUsed() {
        // Stop batch with slot 1 re-searched at 16 and slots 2-3 re-printed from 15: must not mix.
        val lines = parse(
            """
            info depth 15 multipv 1 score cp 10 nodes 100 time 1 pv a2a3
            info depth 15 multipv 2 score cp 5 nodes 100 time 1 pv b2b3
            info depth 15 multipv 3 score cp 0 nodes 100 time 1 pv c2c3
            info depth 16 multipv 1 score cp 40 nodes 200 time 2 pv d2d4
            info depth 15 multipv 2 score cp 10 nodes 200 time 2 pv a2a3
            info depth 15 multipv 3 score cp 5 nodes 200 time 2 pv b2b3
            """,
        )
        // Even with no limit recorded, the mixed batch is never usable.
        val chosen = ConsistentLines.select(lines, limitHit = false)
        assertEquals(15, chosen.depth)
        assertEquals(listOf("a2a3", "b2b3", "c2c3"), chosen.lines.map { it.pvUci.first() })
    }

    @Test
    fun boundLinesAreNeverPartOfAResult() {
        val lines = parse(
            """
            info depth 10 multipv 1 score cp 10 nodes 100 time 1 pv a2a3
            info depth 10 multipv 2 score cp 5 nodes 100 time 1 pv b2b3
            info depth 11 multipv 1 score cp 60 lowerbound nodes 150 time 1 pv a2a3
            info depth 11 multipv 2 score cp 5 nodes 150 time 1 pv b2b3
            info depth 11 multipv 1 score cp 30 nodes 200 time 2 pv a2a3
            """,
        )
        assertTrue(lines[2].bound)
        assertFalse(lines[3].bound)
        // The limit was hit, so the last batch is dropped as the stop batch, and the bound batch is
        // unusable: depth 10.
        val chosen = ConsistentLines.select(lines, limitHit = true)
        assertEquals(10, chosen.depth)
        assertEquals(listOf(10, 5), chosen.lines.map { it.scoreCp })
    }

    @Test
    fun aPositionWithFewerLegalMovesThanMultiPvUsesItsOwnBatchSize() {
        val lines = parse(
            """
            info depth 11 multipv 1 score cp -20 nodes 50 time 1 pv g8h8
            info depth 12 multipv 1 score cp -25 nodes 90 time 1 pv g8h8
            """,
        )
        val chosen = ConsistentLines.select(lines, limitHit = false)
        assertEquals(12, chosen.depth)
        assertEquals(1, chosen.lines.size)
    }

    @Test
    fun aTerminalPositionsSingleDepthZeroLineIsKept() {
        val chosen = ConsistentLines.select(parse("info depth 0 score mate 0"), limitHit = false)
        assertEquals(0, chosen.depth)
        assertEquals(0, chosen.lines.single().mateIn)
    }

    @Test
    fun withNothingUsableItFallsBackToTheLatestLinePerSlotAtTheShallowestDepth() {
        val lines = parse(
            """
            info depth 1 multipv 1 score cp 10 nodes 20 time 1 pv a2a3
            info depth 2 multipv 1 score cp 12 nodes 40 time 1 pv a2a3
            info depth 1 multipv 2 score cp 3 nodes 40 time 1 pv b2b3
            """,
        )
        val chosen = ConsistentLines.select(lines, limitHit = true)
        assertEquals(listOf(1, 2), chosen.lines.map { it.multiPv })
        assertEquals(1, chosen.depth)
    }

    @Test
    fun noLinesAtAllIsAnEmptySelection() {
        val chosen = ConsistentLines.select(emptyList(), limitHit = true)
        assertTrue(chosen.lines.isEmpty())
        assertEquals(0, chosen.depth)
    }

    @Test
    fun stoppedEarlyIsReportedOnlyBelowTheRequestedDepth() {
        assertTrue(AnalysisResult(emptyList(), "e2e4", depth = 15, requestedDepth = 18).stoppedEarly)
        assertFalse(AnalysisResult(emptyList(), "e2e4", depth = 18, requestedDepth = 18).stoppedEarly)
        assertFalse(AnalysisResult(emptyList(), AnalysisResult.NO_MOVE, depth = 0, requestedDepth = 18).stoppedEarly)
        assertFalse(AnalysisResult(emptyList(), "e2e4", depth = 9, requestedDepth = null).stoppedEarly)
    }
}
