package net.palaya.chessanalyzer.ui.model

import net.palaya.chessanalyzer.core.chess.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The puzzle state machine of the "While you wait" panel: the user's move, the auto-played reply, the next move, the solution. */
class WaitingPuzzleLogicTest {

    private fun ui(name: String): Square = algebraicToSquare(name)!!

    private fun puzzle(fen: String, solution: List<String>, san: List<String>, goal: WaitingPuzzleGoal = WaitingPuzzleGoal.BestMove, bestCp: Int? = null, secondCp: Int? = null) =
        WaitingPuzzle(
            id = "t", gameId = "g", ply = 1, fen = fen, prevUci = null, goal = goal, solution = solution, san = san,
            bestCp = bestCp, secondCp = secondCp, depth = 22, gameTitle = "Test game", gameYear = 1900,
        )

    // White: Kg6, Ra1.  Black: Kh8.  1.Ra7 Kg8 (the only move) 2.Ra8#
    private val twoMover = puzzle(
        fen = "7k/8/6K1/8/8/8/8/R7 w - - 0 1",
        solution = listOf("a1a7", "h8g8", "a7a8"),
        san = listOf("Ra7", "Kg8", "Ra8#"),
        goal = WaitingPuzzleGoal.MateIn(2),
    )

    // Scholar's mate in one: Qxf7#
    private val oneMover = puzzle(
        fen = "r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4",
        solution = listOf("h5f7"),
        san = listOf("Qxf7#"),
        goal = WaitingPuzzleGoal.MateIn(1),
    )

    private fun tap(s: WaitingPuzzleSession, from: String, to: String): WaitingPuzzleSession =
        waitingPuzzleTap(waitingPuzzleTap(s, ui(from)), ui(to))

    @Test
    fun aSingleMoveSolutionIsSolvedByTheRightMove() {
        var s = newWaitingPuzzleSession(oneMover)
        assertEquals(WaitingPuzzleStatus.ASKING, s.status)
        assertFalse(s.touched)
        s = tap(s, "h5", "f7")
        assertEquals(WaitingPuzzleStatus.SOLVED, s.status)
        assertTrue(s.resolved)
        assertTrue(s.position.isCheckmate())
        assertEquals("h5f7", s.lastMoveUci)
        assertFalse(s.acceptsTaps)
    }

    @Test
    fun aWrongMoveIsRefusedAndTheBoardStaysAsItWas() {
        var s = newWaitingPuzzleSession(oneMover)
        val before = s.position
        s = tap(s, "h5", "e5")
        assertEquals(WaitingPuzzleFeedback.WRONG, s.feedback)
        assertEquals(1, s.wrongAttempts)
        assertEquals(WaitingPuzzleStatus.ASKING, s.status)
        assertEquals(before, s.position)
        assertNull(s.selected)
        assertTrue(s.inProgress)
        // and the right move still works afterwards
        s = tap(s, "h5", "f7")
        assertEquals(WaitingPuzzleStatus.SOLVED, s.status)
    }

    @Test
    fun theReplyIsAutoPlayedAndThenTheNextMoveIsAsked() {
        var s = newWaitingPuzzleSession(twoMover)
        s = tap(s, "a1", "a7")
        assertEquals(WaitingPuzzleStatus.REPLYING, s.status)
        assertEquals(WaitingPuzzleFeedback.CONTINUE, s.feedback)
        assertEquals("Kg8", s.pendingReplySan)
        assertFalse("no taps while the reply is pending", s.acceptsTaps)
        assertEquals(s, waitingPuzzleTap(s, ui("a7")))
        s = waitingPuzzleReply(s)
        assertEquals(WaitingPuzzleStatus.ASKING, s.status)
        assertEquals(2, s.step)
        assertEquals("h8g8", s.lastMoveUci)
        assertEquals(Color.WHITE, s.position.sideToMove)
        s = tap(s, "a7", "a8")
        assertEquals(WaitingPuzzleStatus.SOLVED, s.status)
        assertTrue(s.position.isCheckmate())
    }

    @Test
    fun replyDoesNothingUnlessOneIsPending() {
        val s = newWaitingPuzzleSession(twoMover)
        assertEquals(s, waitingPuzzleReply(s))
    }

    @Test
    fun theTapRulesAreThePractiseOnes() {
        var s = newWaitingPuzzleSession(twoMover)
        // an opponent piece or an empty square selects nothing
        s = waitingPuzzleTap(s, ui("h8"))
        assertNull(s.selected)
        s = waitingPuzzleTap(s, ui("c4"))
        assertNull(s.selected)
        // an own piece is selected, tapping it again clears, another own piece re-selects
        s = waitingPuzzleTap(s, ui("a1"))
        assertEquals(ui("a1"), s.selected)
        assertTrue(ui("a7") in s.targets)
        s = waitingPuzzleTap(s, ui("a1"))
        assertNull(s.selected)
        s = waitingPuzzleTap(s, ui("a1"))
        s = waitingPuzzleTap(s, ui("g6"))
        assertEquals(ui("g6"), s.selected)
        // an illegal destination clears the selection without a verdict
        s = waitingPuzzleTap(s, ui("a1"))
        s = waitingPuzzleTap(s, ui("b3"))
        assertNull(s.selected)
        assertEquals(WaitingPuzzleFeedback.NONE, s.feedback)
        assertEquals(0, s.wrongAttempts)
    }

    @Test
    fun showSolutionPlaysEveryRemainingMoveFromWhereTheUserIs() {
        var s = newWaitingPuzzleSession(twoMover)
        s = waitingPuzzleReveal(s)
        assertEquals(WaitingPuzzleStatus.REVEALED, s.status)
        assertTrue(s.position.isCheckmate())
        assertEquals("a7a8", s.lastMoveUci)
        assertTrue(s.resolved)
        assertFalse(s.inProgress)

        // revealed half-way: after the first move and its reply
        var half = tap(newWaitingPuzzleSession(twoMover), "a1", "a7")
        half = waitingPuzzleReply(half)
        half = waitingPuzzleReveal(half)
        assertTrue(half.position.isCheckmate())
        assertEquals(3, half.step)
        // a solved puzzle stays solved
        val solved = tap(newWaitingPuzzleSession(oneMover), "h5", "f7")
        assertEquals(solved, waitingPuzzleReveal(solved))
    }

    @Test
    fun aStartedUnfinishedPuzzleIsInProgressAndAFinishedOneIsNot() {
        var s = newWaitingPuzzleSession(twoMover)
        assertFalse(s.inProgress)
        s = waitingPuzzleTap(s, ui("a1"))
        assertTrue(s.inProgress)
        s = waitingPuzzleReveal(s)
        assertFalse(s.inProgress)
    }

    @Test
    fun theSolutionLineIsWrittenWithMoveNumbersFromThePosition() {
        assertEquals("1.Ra7 Kg8 2.Ra8#", solutionLineText(twoMover))
        assertEquals("4.Qxf7#", solutionLineText(oneMover))
        // Black to move first: the line starts with the ellipsis form
        val black = puzzle("k7/8/1K6/8/8/8/8/R7 b - - 0 1", listOf("a8b8"), listOf("Kb8"))
        assertEquals("1…Kb8", solutionLineText(black))
    }

    @Test
    fun scoresAreWrittenFromTheSolversSide() {
        val p = puzzle(oneMover.fen, oneMover.solution, oneMover.san, bestCp = 310, secondCp = -40)
        assertEquals(WaitingPuzzleScores("+3.1", "-0.4"), waitingPuzzleScores(p))
        assertEquals(WaitingPuzzleScores("+3.1", null), waitingPuzzleScores(p.copy(secondCp = null)))
        assertNull(waitingPuzzleScores(oneMover))
    }
}
