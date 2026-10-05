package net.palaya.chessanalyzer.ui.model

import net.palaya.chessanalyzer.core.analysis.PracticePuzzle
import net.palaya.chessanalyzer.core.analysis.PracticeSet
import net.palaya.chessanalyzer.core.analysis.PuzzleGoal
import net.palaya.chessanalyzer.core.chess.Color as CoreColor
import net.palaya.chessanalyzer.core.chess.PieceType as CorePieceType
import net.palaya.chessanalyzer.core.chess.Square as CoreSquare
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Practise state machine beyond a single tap (R3): the two-step hint, Show answer, the saved
 * form, the Summary's entry row and the "Try it" visibility rule.
 */
class PracticeLogicTest {

    private fun ui(name: String): Square = algebraicToSquare(name)!!

    private val fen = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2"

    private fun puzzle(ply: Int = 3, bestUci: String = "g1f3", evalSwingCp: Int? = 100) = PracticePuzzle(
        ply = ply,
        moveNumber = (ply + 1) / 2,
        sideToMove = CoreColor.WHITE,
        fenBefore = fen,
        playedUci = "d2d3",
        playedSan = "d3",
        bestUci = bestUci,
        bestSan = "Nf3",
        bestLineSan = listOf("Nf3", "Nc6", "Bb5"),
        goal = PuzzleGoal.BetterMove,
        loss = 20.0,
        evalSwingCp = evalSwingCp,
        hintPiece = CorePieceType.KNIGHT,
        hintSquare = CoreSquare.fromAlgebraic(bestUci.substring(0, 2)),
        hintMotif = null,
        acceptedUci = setOf(bestUci),
        isMateInOne = false,
        hasSimulation = true,
    )

    private val p = puzzle()
    private val pos = puzzlePosition(p)

    // ---- the two-step hint ----

    @Test
    fun theFirstHintPreSelectsTheBestMovesPieceAndPlaysNothing() {
        val s = practiceHint(PuzzleUiState(), p)
        assertEquals(1, s.hintStep)
        assertEquals(ui("g1"), s.selected)
        assertEquals(PuzzlePhase.QUESTION, s.phase)
        assertNull("the hint must not play the move", s.shownUci)
        // Pre-selected means its legal targets are dotted, not only the answer.
        assertEquals(setOf(ui("f3"), ui("h3"), ui("e2")), practiceTargets(s, p, pos))
        assertTrue("the second tap is still on offer", s.canHint)
    }

    @Test
    fun theSecondHintDotsOnlyTheTargetSquareAndStillPlaysNothing() {
        val s = practiceHint(practiceHint(PuzzleUiState(), p), p)
        assertEquals(2, s.hintStep)
        assertEquals(ui("g1"), s.selected)
        assertEquals(setOf(ui("f3")), practiceTargets(s, p, pos))
        assertEquals(PuzzlePhase.QUESTION, s.phase)
        assertNull(s.shownUci)
        assertEquals(PuzzleFeedback.NONE, s.feedback)
    }

    @Test
    fun afterTheSecondHintTheButtonIsGoneAndAThirdPressChangesNothing() {
        val twice = practiceHint(practiceHint(PuzzleUiState(), p), p)
        assertFalse(twice.canHint)
        assertEquals(twice, practiceHint(twice, p))
        assertTrue("Show answer stays available", twice.canShowAnswer)
    }

    @Test
    fun theSecondHintDotIsPlayedByTappingIt() {
        val hinted = practiceHint(practiceHint(PuzzleUiState(), p), p)
        val solved = practiceTap(hinted, p, pos, ui("f3"))
        assertEquals(PuzzlePhase.SOLVED, solved.phase)
        assertEquals("g1f3", solved.shownUci)
    }

    @Test
    fun selectingAnotherPieceAfterTheSecondHintShowsThatPiecesOwnDots() {
        val hinted = practiceHint(practiceHint(PuzzleUiState(), p), p)
        val other = practiceTap(hinted, p, pos, ui("b1"))
        assertEquals(ui("b1"), other.selected)
        assertEquals(setOf(ui("a3"), ui("c3")), practiceTargets(other, p, pos))
    }

    @Test
    fun aHintClearsAWrongAnswerAndAWrongAnswerKeepsTheHintStep() {
        val wrong = practiceTap(practiceTap(PuzzleUiState(), p, pos, ui("g1")), p, pos, ui("h3"))
        assertEquals(PuzzleFeedback.WRONG, wrong.feedback)
        val hinted = practiceHint(wrong, p)
        assertEquals(PuzzleFeedback.NONE, hinted.feedback)
        // The hint pre-selected the knight, so a single tap on a target is the attempt.
        val wrongAgain = practiceTap(hinted, p, pos, ui("h3"))
        assertEquals(PuzzleFeedback.WRONG, wrongAgain.feedback)
        assertEquals(1, wrongAgain.hintStep)
    }

    @Test
    fun noHintAfterTheQuestionIsClosed() {
        val revealed = practiceShowAnswer(PuzzleUiState(), p)
        assertFalse(revealed.canHint)
        assertEquals(revealed, practiceHint(revealed, p))
    }

    // ---- Show answer ----

    @Test
    fun showAnswerPlaysTheBestMoveAndEndsTheQuestion() {
        val s = practiceShowAnswer(practiceHint(PuzzleUiState(), p), p)
        assertEquals(PuzzlePhase.REVEALED, s.phase)
        assertEquals("g1f3", s.shownUci)
        assertNull(s.selected)
        assertEquals(PuzzleFeedback.NONE, s.feedback)
        assertFalse(s.canShowAnswer)
        assertEquals(emptySet<Square>(), practiceTargets(s, p, pos))
        // A revealed puzzle is not a solved one.
        assertFalse(justSolved(PuzzleUiState(), s))
    }

    @Test
    fun theBoardAfterTheShownMoveHasTheKnightOnF3() {
        val after = positionAfter(fen, "g1f3")!!
        assertEquals(CorePieceType.KNIGHT, after.pieceAt(CoreSquare.fromAlgebraic("f3"))?.type)
        assertNull(positionAfter(fen, "g1g5"))
        assertNull(positionAfter("not a fen", "g1f3"))
    }

    // ---- the saved form (rotation, and the trip into the walkthrough) ----

    @Test
    fun everyStateSurvivesBeingSavedAndRestored() {
        val states = listOf(
            PuzzleUiState(),
            practiceHint(PuzzleUiState(), p),
            practiceHint(practiceHint(PuzzleUiState(), p), p),
            practiceTap(practiceTap(PuzzleUiState(), p, pos, ui("g1")), p, pos, ui("h3")),
            practiceTap(practiceTap(PuzzleUiState(), p, pos, ui("g1")), p, pos, ui("f3")),
            practiceShowAnswer(PuzzleUiState(), p),
            practiceTap(PuzzleUiState(), p, pos, ui("b1")),
        )
        for (state in states) {
            assertEquals(state, puzzleUiStateFromSaved(state.toSaved()))
        }
    }

    @Test
    fun aMalformedSavedStateReadsAsAFreshPuzzle() {
        assertEquals(PuzzleUiState(), puzzleUiStateFromSaved(emptyList()))
        assertEquals(PuzzleUiState(), puzzleUiStateFromSaved(listOf("x", 1, 2, 3, 4)))
        assertEquals(PuzzleUiState(), puzzleUiStateFromSaved(listOf(99, -1, 0, 0, "")))
    }

    // ---- the cost line ----

    @Test
    fun theCostIsInHalfPawnsAndLeftOutAcrossAMate() {
        assertEquals(2, practiceCostHalves(puzzle(evalSwingCp = 100)))
        assertEquals(1, practiceCostHalves(puzzle(evalSwingCp = 25)))
        assertEquals(3, practiceCostHalves(puzzle(evalSwingCp = 150)))
        assertNull("rounds to nothing", practiceCostHalves(puzzle(evalSwingCp = 24)))
        assertNull("mate boundary", practiceCostHalves(puzzle(evalSwingCp = null)))
    }

    @Test
    fun theFollowUpSkipsTheBestMoveItself() {
        assertEquals(listOf("Nc6", "Bb5"), practiceFollowUp(p))
        assertEquals(listOf("Nc6"), practiceFollowUp(p, max = 1))
    }

    // ---- which puzzle opens first ----

    private val three = listOf(puzzle(ply = 11), puzzle(ply = 21), puzzle(ply = 31))

    @Test
    fun theRequestedPlyWinsThenTheFirstUnsolvedThenTheFirst() {
        assertEquals(1, initialPuzzleIndex(three, emptySet(), requestedPly = 21))
        assertEquals("a requested ply wins even if it is solved", 0, initialPuzzleIndex(three, setOf(11), requestedPly = 11))
        assertEquals(0, initialPuzzleIndex(three, emptySet(), requestedPly = null))
        assertEquals("resume at the first unsolved", 1, initialPuzzleIndex(three, setOf(11), requestedPly = null))
        assertEquals(2, initialPuzzleIndex(three, setOf(11, 21), requestedPly = null))
        assertEquals("all solved opens at the first", 0, initialPuzzleIndex(three, setOf(11, 21, 31), requestedPly = null))
        assertEquals("an unknown ply falls back", 1, initialPuzzleIndex(three, setOf(11), requestedPly = 99))
    }

    // ---- the Summary's entry row ----

    @Test
    fun notMeHidesThePracticeSectionEvenWhenThereArePuzzles() {
        assertEquals(PracticeEntryState.Hidden, practiceEntryState(SideChoice.NOT_ME, PracticeSet.Puzzles(three), emptySet()))
        assertEquals(PracticeEntryState.Hidden, practiceEntryState(SideChoice.NOT_ME, PracticeSet.Empty, emptySet()))
    }

    @Test
    fun anUnknownSideAsksForTheSide() {
        assertEquals(PracticeEntryState.NoSide, practiceEntryState(SideChoice.UNKNOWN, PracticeSet.NoSide, emptySet()))
        assertEquals(PracticeEntryState.NoSide, practiceEntryState(SideChoice.UNKNOWN, null, emptySet()))
    }

    @Test
    fun aChosenSideWithNothingToFixSaysSo() {
        assertEquals(PracticeEntryState.Empty, practiceEntryState(SideChoice.WHITE, PracticeSet.Empty, emptySet()))
        assertEquals(PracticeEntryState.Empty, practiceEntryState(SideChoice.BLACK, PracticeSet.Empty, setOf(1, 2)))
    }

    @Test
    fun aChosenSideWithPuzzlesShowsTheCountAndHowManyAreSolved() {
        val set = PracticeSet.Puzzles(three)
        assertEquals(PracticeEntryState.Count(3, 0), practiceEntryState(SideChoice.WHITE, set, emptySet()))
        assertEquals(PracticeEntryState.Count(3, 2), practiceEntryState(SideChoice.BLACK, set, setOf(11, 31)))
        // A solved ply that is not one of this side's puzzles is not counted.
        assertEquals(PracticeEntryState.Count(3, 1), practiceEntryState(SideChoice.WHITE, set, setOf(21, 99)))
        assertEquals(3, solvedCount(three, setOf(11, 21, 31, 99)))
    }

    @Test
    fun aChosenSideWithoutASetShowsNothing() {
        assertEquals(PracticeEntryState.Hidden, practiceEntryState(SideChoice.WHITE, null, emptySet()))
        assertEquals(PracticeEntryState.Hidden, practiceEntryState(SideChoice.WHITE, PracticeSet.NoSide, emptySet()))
    }

    // ---- "Try it" ----

    @Test
    fun tryItShowsOnlyOnAPlyThatIsAPuzzle() {
        val plies = practicePlies(PracticeSet.Puzzles(three))
        assertEquals(setOf(11, 21, 31), plies)
        assertTrue(canTryIt(21, plies))
        assertFalse("a mistake the selector left out", canTryIt(22, plies))
        assertFalse("an opponent move", canTryIt(12, plies))
    }

    @Test
    fun tryItIsNeverShownWithoutPuzzles() {
        for (set in listOf(PracticeSet.NoSide, PracticeSet.Empty, null)) {
            val plies = practicePlies(set)
            assertTrue(plies.isEmpty())
            assertFalse(canTryIt(11, plies))
        }
    }
}
