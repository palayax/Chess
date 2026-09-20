package net.palaya.chessanalyzer.core.analysis

import net.palaya.chessanalyzer.core.chess.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ANALYSIS_SPEC §9.3 — grouping consecutive plies into tactics and collapses. */
class MoveSequencesTest {

    private fun ply(
        n: Int,
        classification: MoveClassification = MoveClassification.GOOD,
        evalBefore: Int = 0,
        evalAfter: Int = 0,
        found: List<TacticInstance> = emptyList(),
        missed: List<TacticInstance> = emptyList()
    ) = MoveAnnotation(
        ply = n,
        moveNumber = (n + 1) / 2,
        color = if (n % 2 == 1) Color.WHITE else Color.BLACK,
        san = "Nf3",
        uci = "g1f3",
        fenBefore = "8/8/8/8/8/8/8/8 w - - 0 1",
        fenAfter = "8/8/8/8/8/8/8/8 b - - 0 1",
        classification = classification,
        loss = 0.0,
        winPercentBefore = 50.0,
        winPercentAfter = 50.0,
        evalBeforeCp = evalBefore,
        evalAfterCp = evalAfter,
        tacticsFound = found,
        tacticsMissed = missed
    )

    private fun fork(by: Color = Color.WHITE, confidence: Double = 0.95) =
        TacticInstance(TacticType.FORK, by, "a1a2", confidence = confidence)

    @Test
    fun `consecutive plies sharing one motif group into a tactic run`() {
        val runs = MoveSequenceDetector.detect(
            listOf(
                ply(1),
                ply(2, found = listOf(fork()), evalBefore = 0, evalAfter = 100),
                ply(3, found = listOf(fork()), evalBefore = 100, evalAfter = 300),
                ply(4)
            )
        )
        assertEquals(1, runs.size)
        val run = runs.single()
        assertEquals(MoveSequenceKind.TACTIC, run.kind)
        assertEquals(2, run.startPly)
        assertEquals(3, run.endPly)
        assertEquals(Color.WHITE, run.byColor)
        assertEquals("Fork", run.label)
        assertEquals(300, run.totalSwingCp)
    }

    @Test
    fun `a single isolated motif is not a sequence`() {
        val runs = MoveSequenceDetector.detect(listOf(ply(1), ply(2, found = listOf(fork())), ply(3)))
        assertTrue(runs.isEmpty())
    }

    @Test
    fun `a motif below the reportable confidence floor does not form a run`() {
        val weak = fork(confidence = 0.5)
        val runs = MoveSequenceDetector.detect(
            listOf(ply(1, found = listOf(weak)), ply(2, found = listOf(weak)))
        )
        assertTrue(runs.isEmpty())
    }

    @Test
    fun `motifs of different colours do not group together`() {
        val runs = MoveSequenceDetector.detect(
            listOf(ply(1, found = listOf(fork(Color.WHITE))), ply(2, found = listOf(fork(Color.BLACK))))
        )
        assertTrue(runs.isEmpty())
    }

    @Test
    fun `a missed motif groups exactly like a found one`() {
        val runs = MoveSequenceDetector.detect(
            listOf(ply(1, missed = listOf(fork())), ply(2, missed = listOf(fork())))
        )
        assertEquals(1, runs.size)
        assertEquals(MoveSequenceKind.TACTIC, runs.single().kind)
    }

    @Test
    fun `consecutive own-turn mistakes group into a collapse spanning the replies`() {
        val runs = MoveSequenceDetector.detect(
            listOf(
                ply(1),
                ply(2),
                ply(3, MoveClassification.MISTAKE, evalBefore = 0, evalAfter = -200),
                ply(4),
                ply(5, MoveClassification.BLUNDER, evalBefore = -200, evalAfter = -700),
                ply(6)
            )
        )
        assertEquals(1, runs.size)
        val run = runs.single()
        assertEquals(MoveSequenceKind.COLLAPSE, run.kind)
        assertEquals(3, run.startPly)
        assertEquals(5, run.endPly)
        assertEquals(Color.WHITE, run.byColor)
        assertEquals(700, run.totalSwingCp)
        // Coloured by the worst move in the run, not the first.
        assertEquals(MoveClassification.BLUNDER, run.classification)
    }

    @Test
    fun `one bad move on its own is not a collapse`() {
        val runs = MoveSequenceDetector.detect(
            listOf(ply(1), ply(2), ply(3, MoveClassification.BLUNDER), ply(4))
        )
        assertTrue(runs.isEmpty())
    }

    @Test
    fun `a good move between two bad ones breaks the collapse`() {
        val runs = MoveSequenceDetector.detect(
            listOf(
                ply(1, MoveClassification.BLUNDER),
                ply(2),
                ply(3, MoveClassification.BEST),
                ply(4),
                ply(5, MoveClassification.BLUNDER)
            )
        )
        assertTrue(runs.isEmpty())
    }

    @Test
    fun `a ply never belongs to two sequences`() {
        val runs = MoveSequenceDetector.detect(
            listOf(
                ply(1, MoveClassification.MISTAKE, found = listOf(fork())),
                ply(2, found = listOf(fork())),
                ply(3, MoveClassification.MISTAKE, found = listOf(fork())),
                ply(4, found = listOf(fork())),
                ply(5, MoveClassification.MISTAKE)
            )
        )
        val seen = HashSet<Int>()
        for (run in runs) {
            for (p in run.plies) {
                assertTrue("ply $p is in two sequences", seen.add(p))
            }
        }
        assertTrue(runs.isNotEmpty())
    }

    @Test
    fun `sequences come back in play order`() {
        val runs = MoveSequenceDetector.detect(
            listOf(
                ply(1, found = listOf(fork())),
                ply(2, found = listOf(fork())),
                ply(3),
                ply(4),
                ply(5, MoveClassification.BLUNDER),
                ply(6),
                ply(7, MoveClassification.BLUNDER)
            )
        )
        assertEquals(runs.map { it.startPly }, runs.map { it.startPly }.sorted())
        assertEquals(2, runs.size)
    }

    @Test
    fun `a game too short to contain a run yields nothing`() {
        assertTrue(MoveSequenceDetector.detect(emptyList()).isEmpty())
        assertTrue(MoveSequenceDetector.detect(listOf(ply(1, MoveClassification.BLUNDER))).isEmpty())
    }
}
