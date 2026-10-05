package net.palaya.chessanalyzer.core.analysis

import net.palaya.chessanalyzer.core.analysis.PracticeFixtures.TWO_MATES
import net.palaya.chessanalyzer.core.analysis.PracticeFixtures.annotation
import net.palaya.chessanalyzer.core.analysis.PracticeFixtures.line
import net.palaya.chessanalyzer.core.analysis.PracticeFixtures.single
import net.palaya.chessanalyzer.core.chess.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** ANALYSIS_SPEC.md section 11.5: judging an attempt from the cached lines. */
class PracticeJudgeTest {

    private fun puzzle(a: MoveAnnotation): PracticePuzzle =
        single(a).also { assertNotNull("the fixture must be a puzzle", it) }!!

    /** The cp (at most [bestCp]) whose win percent is closest to [loss] below the best's. */
    private fun cpWithLoss(bestCp: Int, loss: Double): Int {
        val bestWin = WinProbability.winPercent(bestCp)
        return (-900..bestCp).minByOrNull { abs((bestWin - WinProbability.winPercent(it)) - loss) }!!
    }

    private fun lossOf(bestCp: Int, cp: Int) = WinProbability.winPercent(bestCp) - WinProbability.winPercent(cp)

    // ---------------------------------------------------------------- best, near-best, played, unknown

    @Test
    fun `the best move is correct`() {
        val p = puzzle(annotation(1))
        assertEquals(Verdict.Correct, PracticeJudge.judge(p, "e2e4"))
    }

    @Test
    fun `the second line is accepted at loss 1_9 and rejected at loss 2_1`() {
        val near = cpWithLoss(100, 1.9)
        val far = cpWithLoss(100, 2.1)
        assertTrue("fixture loss ${lossOf(100, near)}", lossOf(100, near) in 1.85..1.95)
        assertTrue("fixture loss ${lossOf(100, far)}", lossOf(100, far) in 2.05..2.15)

        fun judge(secondCp: Int, attempt: String): Verdict {
            // Third line far below, so the cache is judgeable either way.
            val a = annotation(
                1, lines = listOf(line(1, "e2e4", cp = 100), line(2, "d2d4", cp = secondCp), line(3, "g1f3", cp = -300))
            )
            return PracticeJudge.judge(puzzle(a), attempt)
        }

        assertEquals(Verdict.Correct, judge(near, "d2d4"))
        assertEquals(Verdict.Wrong, judge(far, "d2d4"))
        // The best move is correct in both.
        assertEquals(Verdict.Correct, judge(near, "e2e4"))
        assertEquals(Verdict.Correct, judge(far, "e2e4"))
    }

    @Test
    fun `the played move gives PlayedInGame with the loss and the swing`() {
        val p = puzzle(annotation(1, loss = 15.0, evalBeforeCp = 120, evalAfterCp = -80))
        assertEquals(Verdict.PlayedInGame(15.0, 200), PracticeJudge.judge(p, "a2a3"))
    }

    @Test
    fun `PlayedInGame carries a null swing across a mate boundary`() {
        val p = puzzle(annotation(1, loss = 40.0, mateInAfter = -2))
        assertEquals(Verdict.PlayedInGame(40.0, null), PracticeJudge.judge(p, "a2a3"))
    }

    @Test
    fun `the played move is never correct even if the cache rates it near-best`() {
        // MISS with a small loss: the cached line for the played move is within the 2.0 band.
        val a = annotation(
            1, classification = MoveClassification.MISS, loss = 3.0, played = "d2d4",
            lines = listOf(line(1, "e2e4", cp = 100), line(2, "d2d4", cp = 90), line(3, "g1f3", cp = -300))
        )
        val p = puzzle(a)
        assertEquals(setOf("e2e4"), p.acceptedUci)
        assertEquals(Verdict.Correct, PracticeJudge.judge(p, "e2e4"))
        assertEquals(Verdict.PlayedInGame(3.0, 0), PracticeJudge.judge(p, "d2d4"))
    }

    @Test
    fun `an unknown legal move is Wrong`() {
        val p = puzzle(annotation(1))
        // Legal in the start position, not cached, not played.
        assertEquals(Verdict.Wrong, PracticeJudge.judge(p, "h2h4"))
        // A cached but far-worse line is Wrong too.
        assertEquals(Verdict.Wrong, PracticeJudge.judge(p, PracticeFixtures.otherMove(p.fenBefore, "e2e4", "a2a3")))
    }

    @Test
    fun `an illegal or malformed attempt is Wrong, never an exception`() {
        val p = puzzle(annotation(1))
        assertEquals(Verdict.Wrong, PracticeJudge.judge(p, "e2e5"))
        assertEquals(Verdict.Wrong, PracticeJudge.judge(p, "zzzz"))
        assertEquals(Verdict.Wrong, PracticeJudge.judge(p, ""))
    }

    @Test
    fun `judging works for Black with the same rules`() {
        val p = puzzle(annotation(2, Color.BLACK))
        assertEquals(Verdict.Correct, PracticeJudge.judge(p, "e7e5"))
        assertEquals(Verdict.PlayedInGame(15.0, 0), PracticeJudge.judge(p, "a7a6"))
        assertEquals(Verdict.Wrong, PracticeJudge.judge(p, "h7h5"))
    }

    // ---------------------------------------------------------------- the mate-puzzle property

    private fun matePuzzle(): PracticePuzzle = puzzle(
        annotation(
            1, classification = MoveClassification.MISS, loss = 12.0, mateInBefore = 3,
            lines = listOf(
                line(1, "e2e4", mate = 3),
                line(2, "d2d4", cp = 1000), // wins a queen, but is not a mate
                line(3, "g1f3", mate = 5), // a slower mate
                line(4, "c2c4", cp = 300)
            )
        )
    )

    @Test
    fun `in a mate puzzle a +1000 cp line is Wrong`() {
        // The property behind it: a mate is 100 %, +1000 cp is 97.5 %, and 2.5 > 2.0.
        val queenWin = WinProbability.winPercent(1000)
        assertTrue(queenWin in 97.4..97.6)
        assertTrue(100.0 - queenWin > PracticeSelector.ACCEPT_LOSS)

        val p = matePuzzle()
        assertEquals(PuzzleGoal.MateIn(3), p.goal)
        assertEquals(Verdict.Wrong, PracticeJudge.judge(p, "d2d4"))
        assertEquals(Verdict.Correct, PracticeJudge.judge(p, "e2e4"))
    }

    @Test
    fun `in a mate puzzle a slower mate is Correct`() {
        val p = matePuzzle()
        assertEquals(setOf("e2e4", "g1f3"), p.acceptedUci)
        assertEquals(Verdict.Correct, PracticeJudge.judge(p, "g1f3"))
        assertEquals(Verdict.Wrong, PracticeJudge.judge(p, "c2c4"))
    }

    @Test
    fun `a mate for the opponent is 0 percent, so it is never accepted`() {
        val a = annotation(
            1, loss = 30.0,
            lines = listOf(line(1, "e2e4", cp = 100), line(2, "d2d4", mate = -2), line(3, "g1f3", cp = -400))
        )
        assertEquals(Verdict.Wrong, PracticeJudge.judge(puzzle(a), "d2d4"))
    }

    // ---------------------------------------------------------------- mate in 1, by the rules

    private fun mateInOnePuzzle(): PracticePuzzle = puzzle(
        annotation(
            1, classification = MoveClassification.MISS, loss = 3.0, fen = TWO_MATES, played = "e1d1", best = "a1a8",
            // Only the first mate is cached.
            lines = listOf(line(1, "a1a8", mate = 1)), mateInBefore = 1
        )
    )

    @Test
    fun `a mate in 1 with an uncached second mate is Correct by the rules`() {
        val p = mateInOnePuzzle()
        assertTrue(p.isMateInOne)
        assertEquals(setOf("a1a8"), p.acceptedUci)
        // Rb8# is also mate but was never cached.
        assertEquals(Verdict.Correct, PracticeJudge.judge(p, "b2b8"))
        assertEquals(Verdict.Correct, PracticeJudge.judge(p, "a1a8"))
    }

    @Test
    fun `a mate in 1 puzzle still rejects a move that is not mate`() {
        val p = mateInOnePuzzle()
        // Rb7 and Kf1 are legal but do not mate.
        assertEquals(Verdict.Wrong, PracticeJudge.judge(p, "b2b7"))
        assertEquals(Verdict.Wrong, PracticeJudge.judge(p, "e1f1"))
        assertEquals(Verdict.PlayedInGame(3.0, null), PracticeJudge.judge(p, "e1d1"))
    }

    @Test
    fun `the checkmate-by-rules shortcut applies only to mate in 1 puzzles`() {
        val p = mateInOnePuzzle().copy(isMateInOne = false)
        assertEquals(Verdict.Wrong, PracticeJudge.judge(p, "b2b8"))
    }
}
