package net.palaya.chessanalyzer.core.analysis

import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.PieceType
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.parseUci
import net.palaya.chessanalyzer.core.narration.RealGameFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The selector and judge on **recorded Stockfish analyses** (`core/src/test/resources/pacing/`,
 * MultiPV 3), not hand-built numbers. The two fixtures are fixed recordings, so the exact plies
 * below are regression pins of today's classifier; what must stay true whatever the classifier
 * does is the invariant test at the bottom.
 */
class PracticeRealGameTest {

    private val chesscomReport by lazy { RealGameFixture.chesscom.report() }
    private val immortalReport by lazy { RealGameFixture.immortal.report() }

    private fun puzzles(report: GameReport, side: Color): List<PracticePuzzle> =
        (PracticeSelector.select(report, side) as? PracticeSet.Puzzles)?.puzzles ?: emptyList()

    /** Everything that must hold for a puzzle built from real data. */
    private fun assertSound(p: PracticePuzzle) {
        val position = Position.fromFen(p.fenBefore)
        assertEquals("puzzle side is the side to move", p.sideToMove, position.sideToMove)
        val best = position.parseUci(p.bestUci) // throws if the best move is not legal
        assertTrue("best move must be in the accepted set (ply ${p.ply})", p.bestUci in p.acceptedUci)
        assertTrue("played move must not be accepted (ply ${p.ply})", p.playedUci !in p.acceptedUci)
        assertTrue("every accepted move is legal", p.acceptedUci.all { runCatching { position.parseUci(it) }.isSuccess })
        assertTrue("no under-promotion answer", best.promotion == null || best.promotion == PieceType.QUEEN)
        assertEquals(Verdict.Correct, PracticeJudge.judge(p, p.bestUci))
        assertTrue(PracticeJudge.judge(p, p.playedUci) is Verdict.PlayedInGame)
        assertTrue("a real mistake costs at least the floor", p.loss >= PracticeSelector.MIN_LOSS)
    }

    // ---------------------------------------------------------------- chesscom_style_game, Black loses

    @Test
    fun `chesscom game as Black, the losing side, gives the one qualifying mistake`() {
        val set = puzzles(chesscomReport, Color.BLACK)
        assertEquals(listOf(12), set.map { it.ply })
        val p = set.single()
        set.forEach(::assertSound)

        // 6...Nf6 (ply 12): Qf6 was the best move, Nf6 lost 11.1 win percent.
        assertEquals(Color.BLACK, p.sideToMove)
        assertEquals("g8f6", p.playedUci)
        assertEquals("d8f6", p.bestUci)
        assertEquals("Qf6", p.bestSan)
        assertEquals(setOf("d8f6"), p.acceptedUci) // the next line is 54 cp worse: not equal
        assertEquals(PuzzleGoal.BetterMove, p.goal)

        // Black's other candidate plies are correctly left out: ply 18 (b5, MISTAKE) started from
        // a position that was already lost (22.9 % < 25 %), and the rest are inaccuracies.
        val ply18 = chesscomReport.annotations[17]
        assertEquals(MoveClassification.MISTAKE, ply18.classification)
        assertTrue(ply18.winPercentBefore < PracticeSelector.MIN_WIN_BEFORE)
    }

    @Test
    fun `chesscom game as White, the winning side, has nothing to fix`() {
        assertSame(PracticeSet.Empty, PracticeSelector.select(chesscomReport, Color.WHITE))
    }

    @Test
    fun `chesscom game with no side chosen gives NoSide`() {
        assertSame(PracticeSet.NoSide, PracticeSelector.select(chesscomReport, null))
    }

    @Test
    fun `candidateLines on the real analysis are the recorded lines, mover-relative and untouched`() {
        val game = RealGameFixture.chesscom
        // Ply 12 is Black's move: the recorded scores are from Black's side (negative = Black is worse).
        val ann = chesscomReport.annotations[11]
        val recorded = game.evals[11].lines.sortedBy { it.multiPv }
        assertEquals(3, ann.candidateLines.size)
        assertEquals(recorded.map { it.multiPv }, ann.candidateLines.map { it.multiPv })
        assertEquals(recorded.map { it.pvUci.first() }, ann.candidateLines.map { it.uci })
        assertEquals(recorded.map { it.scoreCp }, ann.candidateLines.map { it.scoreCp })
        assertEquals(recorded.map { it.mateIn }, ann.candidateLines.map { it.mateIn })
        assertTrue("SAN resolved", ann.candidateLines.all { it.san != null })
        assertEquals(ann.bestMoveUci, ann.candidateLines.first().uci)
        assertEquals(ann.bestMoveSan, ann.candidateLines.first().san)
        assertTrue("mover-relative: Black is worse here, so the best score is negative", ann.candidateLines.first().scoreCp!! < 0)
    }

    // ---------------------------------------------------------------- the Immortal Game

    @Test
    fun `immortal game as White does NOT come out as nothing to fix, the engine finds three mistakes`() {
        // docs/PRACTICE_DESIGN.md section 7.2 predicted "Nothing to fix" for White. The recorded
        // depth-12 analysis says otherwise: the Immortal Game is full of sacrifices that Stockfish
        // rates as mistakes (Anderssen's 10.g4 and 17.Nd5 are among them).
        val set = puzzles(immortalReport, Color.WHITE)
        set.forEach(::assertSound)
        assertEquals(listOf(19, 33, 35), set.map { it.ply })
        assertEquals(listOf("b5a4", "d3d4", "a1e1"), set.map { it.bestUci })

        // Ply 35 (18.Bd6): Re1 (384 cp) and d4 (373 cp) are within 2.0 win percent of each other, so both count.
        assertEquals(setOf("a1e1", "d3d4"), set[2].acceptedUci)
        assertEquals(Verdict.Correct, PracticeJudge.judge(set[2], "d3d4"))
    }

    @Test
    fun `immortal game as Black gives three puzzles`() {
        val set = puzzles(immortalReport, Color.BLACK)
        set.forEach(::assertSound)
        assertEquals(listOf(22, 36, 40), set.map { it.ply })
        assertEquals(listOf("h7h5", "b2a1", "c8a6"), set.map { it.bestUci })
    }

    @Test
    fun `immortal game puzzles never include a mistake from a position that was already lost`() {
        // Black's ply 32 (Bc5, MISTAKE) started at 22.9 %.
        val ply32 = immortalReport.annotations[31]
        assertEquals(MoveClassification.MISTAKE, ply32.classification)
        assertTrue(ply32.winPercentBefore < PracticeSelector.MIN_WIN_BEFORE)
        assertTrue(puzzles(immortalReport, Color.BLACK).none { it.ply == 32 })
    }

    // ---------------------------------------------------------------- invariants, whatever the classifier does

    @Test
    fun `every real puzzle judges every legal move consistently`() {
        for ((name, report) in listOf("chesscom" to chesscomReport, "immortal" to immortalReport)) {
            for (side in Color.values()) {
                for (p in puzzles(report, side)) {
                    assertSound(p)
                    val position = Position.fromFen(p.fenBefore)
                    for (move in position.legalMoves()) {
                        val uci = move.toUci()
                        val verdict = PracticeJudge.judge(p, uci)
                        val expected = when {
                            uci in p.acceptedUci -> Verdict.Correct
                            uci == p.playedUci -> Verdict.PlayedInGame(p.loss, p.evalSwingCp)
                            else -> Verdict.Wrong
                        }
                        assertEquals("$name $side ply ${p.ply} move $uci", expected, verdict)
                    }
                    // The accepted answers are never worse than the band, by construction.
                    assertTrue(p.bestSan.isNotBlank())
                    assertTrue("swing is never negative", (p.evalSwingCp ?: 0) >= 0)
                }
            }
        }
    }
}
