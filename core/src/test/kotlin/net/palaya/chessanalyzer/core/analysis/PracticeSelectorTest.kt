package net.palaya.chessanalyzer.core.analysis

import net.palaya.chessanalyzer.core.analysis.PracticeFixtures.AFTER_E4
import net.palaya.chessanalyzer.core.analysis.PracticeFixtures.MATE_IN_ONE
import net.palaya.chessanalyzer.core.analysis.PracticeFixtures.PROMOTION
import net.palaya.chessanalyzer.core.analysis.PracticeFixtures.START
import net.palaya.chessanalyzer.core.analysis.PracticeFixtures.THREE_MOVES
import net.palaya.chessanalyzer.core.analysis.PracticeFixtures.annotation
import net.palaya.chessanalyzer.core.analysis.PracticeFixtures.line
import net.palaya.chessanalyzer.core.analysis.PracticeFixtures.plies
import net.palaya.chessanalyzer.core.analysis.PracticeFixtures.report
import net.palaya.chessanalyzer.core.analysis.PracticeFixtures.single
import net.palaya.chessanalyzer.core.analysis.PracticeFixtures.tactic
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.PieceType
import net.palaya.chessanalyzer.core.chess.Square
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** ANALYSIS_SPEC.md section 11: every selection rule, boundary-pinned. */
class PracticeSelectorTest {

    private fun select(userColor: Color?, vararg a: MoveAnnotation) = PracticeSelector.select(report(*a), userColor)

    // ---------------------------------------------------------------- constants

    @Test
    fun `the spec constants have the spec values`() {
        assertEquals(10.0, PracticeSelector.MIN_LOSS, 0.0)
        assertEquals(25.0, PracticeSelector.MIN_WIN_BEFORE, 0.0)
        assertEquals(2.0, PracticeSelector.ACCEPT_LOSS, 0.0)
        assertEquals(5, PracticeSelector.MAX_PUZZLES)
        assertEquals(4, PracticeSelector.DEDUPE_WINDOW_PLIES)
        assertEquals(20.0, PracticeSelector.MISS_RANK_FLOOR, 0.0)
    }

    // ---------------------------------------------------------------- rules 1-4

    @Test
    fun `only the user's own side is selected`() {
        val a = arrayOf(
            annotation(1, Color.WHITE),
            annotation(2, Color.BLACK),
            annotation(3, Color.WHITE, best = "d2d4"),
            annotation(4, Color.BLACK, best = "d7d5")
        )
        assertEquals(listOf(1, 3), plies(select(Color.WHITE, *a)))
        assertEquals(listOf(2, 4), plies(select(Color.BLACK, *a)))
    }

    @Test
    fun `MISTAKE MISS and BLUNDER are in`() {
        val set = select(
            Color.WHITE,
            annotation(1, classification = MoveClassification.MISTAKE, best = "e2e4"),
            annotation(11, classification = MoveClassification.MISS, loss = 30.0, best = "d2d4"),
            annotation(21, classification = MoveClassification.BLUNDER, loss = 40.0, best = "g1f3")
        )
        assertEquals(listOf(1, 11, 21), plies(set))
    }

    @Test
    fun `INACCURACY GOOD and the other non-mistake classes are out even with a large loss`() {
        val out = listOf(
            MoveClassification.INACCURACY, MoveClassification.GOOD, MoveClassification.EXCELLENT,
            MoveClassification.BEST, MoveClassification.GREAT, MoveClassification.BRILLIANT,
            MoveClassification.BOOK, MoveClassification.FORCED
        )
        for (c in out) {
            assertEquals("$c must not be a puzzle", PracticeSet.Empty, select(Color.WHITE, annotation(1, classification = c, loss = 15.0)))
        }
    }

    @Test
    fun `loss 9_9 is out and loss 10_0 is in for a MISTAKE`() {
        assertEquals(PracticeSet.Empty, select(Color.WHITE, annotation(1, loss = 9.9)))
        assertEquals(listOf(1), plies(select(Color.WHITE, annotation(1, loss = 10.0))))
    }

    @Test
    fun `loss 9_9 is out for a BLUNDER too, the floor is the same`() {
        assertEquals(
            PracticeSet.Empty,
            select(Color.WHITE, annotation(1, classification = MoveClassification.BLUNDER, loss = 9.9))
        )
    }

    @Test
    fun `a MISS with loss 3 is kept`() {
        val puzzle = single(annotation(1, classification = MoveClassification.MISS, loss = 3.0))
        assertNotNull(puzzle)
        assertEquals(3.0, puzzle!!.loss, 0.0)
    }

    @Test
    fun `winPercentBefore 24_9 is out and 25_0 is in`() {
        assertEquals(PracticeSet.Empty, select(Color.WHITE, annotation(1, winBefore = 24.9)))
        assertEquals(listOf(1), plies(select(Color.WHITE, annotation(1, winBefore = 25.0))))
    }

    // ---------------------------------------------------------------- rule 5

    @Test
    fun `a ply whose best move equals the played move is skipped`() {
        val a = annotation(1, played = "e2e4", best = "e2e4", lines = listOf(line(1, "e2e4", cp = 100), line(2, "d2d4", cp = -300)))
        assertEquals(PracticeSet.Empty, select(Color.WHITE, a))
    }

    @Test
    fun `a ply whose best move is illegal in fenBefore is skipped`() {
        // e2e5 is not a legal first move.
        val a = annotation(1, best = "e2e5", lines = listOf(line(1, "e2e5", cp = 100), line(2, "d2d4", cp = -300)))
        assertEquals(PracticeSet.Empty, select(Color.WHITE, a))
    }

    @Test
    fun `a ply with no best move at all is skipped`() {
        assertEquals(PracticeSet.Empty, select(Color.WHITE, annotation(1, best = null)))
    }

    @Test
    fun `a non-queen promotion as the best move is skipped but a queen promotion is kept`() {
        for (promo in listOf("r", "b", "n")) {
            val best = "a7a8$promo"
            val a = annotation(
                1, fen = PROMOTION, played = "a1a2", best = best,
                lines = listOf(line(1, best, cp = 100), line(2, "a1a2", cp = -300))
            )
            assertEquals("under-promotion $best must be skipped", PracticeSet.Empty, select(Color.WHITE, a))
        }
        val queen = annotation(
            1, fen = PROMOTION, played = "a1a2", best = "a7a8q",
            lines = listOf(line(1, "a7a8q", cp = 100), line(2, "a1a2", cp = -300))
        )
        assertEquals("a7a8q", single(queen)!!.bestUci)
    }

    // ---------------------------------------------------------------- rule 6, judgeability

    @Test
    fun `k = 1 is skipped when the best move is not a mate`() {
        val a = annotation(1, lines = listOf(line(1, "e2e4", cp = 100)))
        assertEquals(PracticeSet.Empty, select(Color.WHITE, a))
    }

    @Test
    fun `no cached lines at all is skipped when the best move is not a mate`() {
        assertEquals(PracticeSet.Empty, select(Color.WHITE, annotation(1, lines = emptyList())))
    }

    @Test
    fun `three near-equal lines with more than 3 legal moves are skipped`() {
        val a = annotation(
            1, lines = listOf(line(1, "e2e4", cp = 100), line(2, "d2d4", cp = 95), line(3, "g1f3", cp = 90))
        )
        // 20 legal moves in the start position, only 3 cached, and the last line is inside the band.
        assertEquals(PracticeSet.Empty, select(Color.WHITE, a))
    }

    @Test
    fun `three near-equal lines with exactly 3 legal moves are kept`() {
        val a = annotation(
            1, fen = THREE_MOVES, played = "a1a2", best = "a1b1",
            lines = listOf(line(1, "a1b1", cp = 0), line(2, "a1b2", cp = 0), line(3, "a1a2", cp = 0))
        )
        val puzzle = single(a)
        assertNotNull("all 3 legal moves are cached, so the cache decides every move", puzzle)
        // The other near-equal move is accepted; the played move never is.
        assertEquals(setOf("a1b1", "a1b2"), puzzle!!.acceptedUci)
    }

    @Test
    fun `two lines are enough when the second is below the band`() {
        val a = annotation(1, lines = listOf(line(1, "e2e4", cp = 100), line(2, "d2d4", cp = -300)))
        assertNotNull(single(a))
    }

    @Test
    fun `the band edge decides whether the last line is provably worse`() {
        val bestWin = WinProbability.winPercent(100)
        // The lowest cp that is still within 2.0 win percent of the best (+100 cp).
        val inside = (-200..100).first { bestWin - WinProbability.winPercent(it) <= PracticeSelector.ACCEPT_LOSS }
        val outside = inside - 1
        assertTrue(bestWin - WinProbability.winPercent(outside) > PracticeSelector.ACCEPT_LOSS)
        // Last line still inside the band: not provably worse, 20 legal moves > 2 cached.
        assertEquals(
            PracticeSet.Empty,
            select(Color.WHITE, annotation(1, lines = listOf(line(1, "e2e4", cp = 100), line(2, "d2d4", cp = inside))))
        )
        // One centipawn further: strictly below the band, judgeable.
        assertNotNull(single(annotation(1, lines = listOf(line(1, "e2e4", cp = 100), line(2, "d2d4", cp = outside)))))
    }

    @Test
    fun `a mate in 1 is kept even with a single cached line`() {
        val a = annotation(
            1, classification = MoveClassification.MISS, loss = 3.0, fen = MATE_IN_ONE, played = "e1d1", best = "a1a8",
            lines = listOf(line(1, "a1a8", mate = 1)), mateInBefore = 1
        )
        val puzzle = single(a)
        assertNotNull(puzzle)
        assertTrue(puzzle!!.isMateInOne)
    }

    @Test
    fun `a mate in 1 is kept even when near-equal lines leave the cache undecided`() {
        val a = annotation(
            1, classification = MoveClassification.MISS, loss = 3.0, fen = MATE_IN_ONE, played = "e1d1", best = "a1a8",
            lines = listOf(line(1, "a1a8", mate = 1), line(2, "a1a7", mate = 2), line(3, "a1a6", mate = 3))
        )
        assertTrue(single(a)!!.isMateInOne)
    }

    @Test
    fun `a best move that is not mate is not flagged as a mate in 1`() {
        assertEquals(false, single(annotation(1))!!.isMateInOne)
    }

    // ---------------------------------------------------------------- dedupe, cap, order

    @Test
    fun `the same best move at plies 11 and 13 is one puzzle, at 11 and 17 it is two`() {
        val near = select(Color.WHITE, annotation(11, loss = 15.0), annotation(13, loss = 25.0))
        assertEquals("kept the larger loss", listOf(13), plies(near))

        val nearOtherWay = select(Color.WHITE, annotation(11, loss = 25.0), annotation(13, loss = 15.0))
        assertEquals(listOf(11), plies(nearOtherWay))

        val far = select(Color.WHITE, annotation(11, loss = 15.0), annotation(17, loss = 25.0))
        assertEquals(listOf(11, 17), plies(far))
    }

    @Test
    fun `the dedupe window is inclusive at 4 plies and exclusive at 6`() {
        assertEquals(1, plies(select(Color.WHITE, annotation(11), annotation(15))).size)
        assertEquals(2, plies(select(Color.WHITE, annotation(11), annotation(17))).size)
    }

    @Test
    fun `an equal loss keeps the earlier ply`() {
        assertEquals(listOf(11), plies(select(Color.WHITE, annotation(11, loss = 20.0), annotation(13, loss = 20.0))))
    }

    @Test
    fun `different best moves close together are not deduped`() {
        val set = select(Color.WHITE, annotation(11, best = "e2e4"), annotation(13, best = "d2d4"))
        assertEquals(listOf(11, 13), plies(set))
    }

    private val sevenOpenings = listOf("e2e4", "d2d4", "g1f3", "c2c4", "b1c3", "g2g3", "f2f4")

    @Test
    fun `the cap is 5 and keeps the largest losses`() {
        val losses = listOf(12.0, 50.0, 14.0, 40.0, 30.0, 16.0, 25.0)
        val a = losses.mapIndexed { i, l -> annotation(1 + 2 * i, loss = l, best = sevenOpenings[i]) }
        val set = select(Color.WHITE, *a.toTypedArray())
        // 12 (ply 1) and 14 (ply 5) are the two smallest and fall off; 16 survives.
        assertEquals(listOf(3, 7, 9, 11, 13), plies(set))
    }

    @Test
    fun `a MISS ranks as at least 20 so it beats a smaller mistake but not a bigger one`() {
        val specs = listOf(
            Triple(MoveClassification.BLUNDER, 50.0, "e2e4"),
            Triple(MoveClassification.BLUNDER, 40.0, "d2d4"),
            Triple(MoveClassification.BLUNDER, 30.0, "g1f3"),
            Triple(MoveClassification.MISTAKE, 25.0, "c2c4"),
            Triple(MoveClassification.MISTAKE, 15.0, "b1c3"), // below the MISS floor: dropped
            Triple(MoveClassification.MISTAKE, 12.0, "g2g3"), // dropped
            Triple(MoveClassification.MISS, 3.0, "f2f4") // ranks as 20.0: kept
        )
        val a = specs.mapIndexed { i, (c, l, b) -> annotation(1 + 2 * i, classification = c, loss = l, best = b) }
        val set = select(Color.WHITE, *a.toTypedArray())
        assertEquals(listOf(1, 3, 5, 7, 13), plies(set))
    }

    @Test
    fun `a MISS with a loss above 20 ranks by its real loss`() {
        val specs = listOf(
            Triple(MoveClassification.MISTAKE, 45.0, "e2e4"),
            Triple(MoveClassification.MISTAKE, 44.0, "d2d4"),
            Triple(MoveClassification.MISTAKE, 43.0, "g1f3"),
            Triple(MoveClassification.MISTAKE, 42.0, "c2c4"),
            Triple(MoveClassification.MISTAKE, 41.0, "b1c3"),
            Triple(MoveClassification.MISS, 30.0, "g2g3")
        )
        val a = specs.mapIndexed { i, (c, l, b) -> annotation(1 + 2 * i, classification = c, loss = l, best = b) }
        assertEquals(listOf(1, 3, 5, 7, 9), plies(select(Color.WHITE, *a.toTypedArray())))
    }

    @Test
    fun `output is in ply order whatever the ranking and the input order`() {
        val shuffled = listOf(
            annotation(21, loss = 50.0, best = "e2e4"),
            annotation(3, loss = 20.0, best = "d2d4"),
            annotation(41, loss = 30.0, best = "g1f3"),
            annotation(9, loss = 40.0, best = "c2c4")
        )
        val set = PracticeSelector.select(report(*shuffled.toTypedArray()), Color.WHITE)
        assertEquals(listOf(3, 9, 21, 41), plies(set))
    }

    // ---------------------------------------------------------------- NoSide / Empty

    @Test
    fun `a null side gives NoSide`() {
        assertSame(PracticeSet.NoSide, select(null, annotation(1), annotation(2, Color.BLACK)))
    }

    @Test
    fun `a clean game gives Empty`() {
        val clean = arrayOf(
            annotation(1, classification = MoveClassification.BEST, loss = 0.0),
            annotation(2, Color.BLACK, classification = MoveClassification.GOOD, loss = 3.0),
            annotation(3, classification = MoveClassification.INACCURACY, loss = 8.0)
        )
        assertSame(PracticeSet.Empty, select(Color.WHITE, *clean))
        assertSame(PracticeSet.Empty, select(Color.BLACK, *clean))
    }

    @Test
    fun `a game with no annotations gives Empty`() {
        assertSame(PracticeSet.Empty, PracticeSelector.select(report(), Color.WHITE))
    }

    @Test
    fun `the other side's mistakes do not make the user's set non-empty`() {
        val set = select(Color.WHITE, annotation(2, Color.BLACK, loss = 40.0))
        assertSame(PracticeSet.Empty, set)
    }

    // ---------------------------------------------------------------- goal derivation

    private fun goalOf(a: MoveAnnotation) = single(a)!!.goal

    private fun withMissed(swing: Int?, color: Color = Color.WHITE): MoveAnnotation = annotation(
        1, missed = if (swing == null) emptyList() else listOf(tactic(swing, color))
    )

    @Test
    fun `goal is MateIn when mateInBefore favours the mover`() {
        assertEquals(PuzzleGoal.MateIn(3), goalOf(annotation(1, mateInBefore = 3)))
        // mateInBefore is White-relative: a mate for Black is negative.
        assertEquals(PuzzleGoal.MateIn(2), goalOf(annotation(2, Color.BLACK, mateInBefore = -2)))
    }

    @Test
    fun `a mate against the mover is not a mate goal`() {
        assertEquals(PuzzleGoal.BetterMove, goalOf(annotation(1, mateInBefore = -3)))
        assertEquals(PuzzleGoal.BetterMove, goalOf(annotation(2, Color.BLACK, mateInBefore = 3)))
    }

    @Test
    fun `a mate goal wins over a material goal`() {
        assertEquals(PuzzleGoal.MateIn(4), goalOf(annotation(1, mateInBefore = 4, missed = listOf(tactic(900)))))
    }

    @Test
    fun `goal is MateIn(1) for a rules-verified mate in 1 even without a mate score`() {
        val a = annotation(
            1, classification = MoveClassification.MISS, loss = 3.0, fen = MATE_IN_ONE, played = "e1d1", best = "a1a8",
            lines = listOf(line(1, "a1a8", mate = 1))
        )
        assertEquals(PuzzleGoal.MateIn(1), goalOf(a))
    }

    @Test
    fun `goal thresholds 500 320 150 and none`() {
        assertEquals(PuzzleGoal.WinRookOrBetter, goalOf(withMissed(500)))
        assertEquals(PuzzleGoal.WinRookOrBetter, goalOf(withMissed(900)))
        assertEquals(PuzzleGoal.WinPiece, goalOf(withMissed(499)))
        assertEquals(PuzzleGoal.WinPiece, goalOf(withMissed(320)))
        assertEquals(PuzzleGoal.WinMaterial, goalOf(withMissed(319)))
        assertEquals(PuzzleGoal.WinMaterial, goalOf(withMissed(150)))
        assertEquals(PuzzleGoal.BetterMove, goalOf(withMissed(149)))
        assertEquals(PuzzleGoal.BetterMove, goalOf(withMissed(null)))
    }

    @Test
    fun `a missed tactic of the opponent does not set the goal`() {
        assertEquals(PuzzleGoal.BetterMove, goalOf(withMissed(900, color = Color.BLACK)))
    }

    @Test
    fun `the largest missed tactic sets the goal and the hint motif`() {
        val a = annotation(
            1,
            missed = listOf(
                tactic(200, type = TacticType.PIN_RELATIVE),
                tactic(600, type = TacticType.FORK),
                tactic(320, type = TacticType.SKEWER)
            )
        )
        val puzzle = single(a)!!
        assertEquals(PuzzleGoal.WinRookOrBetter, puzzle.goal)
        assertEquals(TacticType.FORK, puzzle.hintMotif)
    }

    // ---------------------------------------------------------------- precomputed fields

    @Test
    fun `the puzzle carries everything the screen needs`() {
        val sim = TacticSimulation(START, listOf("e2e4"), listOf("e4"), listOf("x"), tactic(300), "pay")
        val puzzle = single(annotation(7, simulation = sim, evalBeforeCp = 120, evalAfterCp = -80))!!
        assertEquals(7, puzzle.ply)
        assertEquals(4, puzzle.moveNumber)
        assertEquals(Color.WHITE, puzzle.sideToMove)
        assertEquals(START, puzzle.fenBefore)
        assertEquals("a2a3", puzzle.playedUci)
        assertEquals("played", puzzle.playedSan)
        assertEquals("e2e4", puzzle.bestUci)
        assertEquals("best-e2e4", puzzle.bestSan)
        assertEquals(listOf("a", "b"), puzzle.bestLineSan)
        assertEquals(15.0, puzzle.loss, 0.0)
        assertEquals(200, puzzle.evalSwingCp)
        assertEquals(PieceType.PAWN, puzzle.hintPiece)
        assertEquals(Square.fromAlgebraic("e2"), puzzle.hintSquare)
        assertNull(puzzle.hintMotif)
        assertEquals(setOf("e2e4"), puzzle.acceptedUci)
        assertEquals(true, puzzle.hasSimulation)
    }

    @Test
    fun `evalSwingCp is mover-relative for Black, never negative, and null across a mate`() {
        // White-relative +100 before, +300 after: Black lost 200.
        assertEquals(200, single(annotation(2, Color.BLACK, evalBeforeCp = 100, evalAfterCp = 300))!!.evalSwingCp)
        // The eval improved for the mover: clamped to 0.
        assertEquals(0, single(annotation(1, evalBeforeCp = 50, evalAfterCp = 400))!!.evalSwingCp)
        assertNull(single(annotation(1, mateInBefore = 3))!!.evalSwingCp)
        assertNull(single(annotation(1, mateInAfter = -2))!!.evalSwingCp)
    }

    @Test
    fun `a Black puzzle is described from Black's side`() {
        val puzzle = single(annotation(2, Color.BLACK))!!
        assertEquals(Color.BLACK, puzzle.sideToMove)
        assertEquals(AFTER_E4, puzzle.fenBefore)
        assertEquals(Square.fromAlgebraic("e7"), puzzle.hintSquare)
    }

    @Test
    fun `without a SAN on the annotation the best move is spelled out from the position`() {
        val a = annotation(1).copy(bestMoveSan = null)
        assertEquals("e4", single(a)!!.bestSan)
    }
}
