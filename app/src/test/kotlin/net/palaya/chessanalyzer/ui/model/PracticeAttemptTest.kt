package net.palaya.chessanalyzer.ui.model

import net.palaya.chessanalyzer.core.analysis.PracticePuzzle
import net.palaya.chessanalyzer.core.analysis.PuzzleGoal
import net.palaya.chessanalyzer.core.chess.Color as CoreColor
import net.palaya.chessanalyzer.core.chess.PieceType as CorePieceType
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.Square as CoreSquare
import net.palaya.chessanalyzer.data.mapper.toCoreSquare
import net.palaya.chessanalyzer.data.mapper.toUiSquare
import net.palaya.chessanalyzer.ui.board.BoardOrientation
import net.palaya.chessanalyzer.ui.board.squareAtOffset
import net.palaya.chessanalyzer.ui.board.squareToDisplayCell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Practise tap-to-move path (R3, docs/PRACTICE_DESIGN.md §2 and §7.2): the own-piece gate, the
 * auto-queen, castling by tapping the king's destination, and the mapping between what the user
 * taps on a flipped board and the core squares. The board's tap path had never been used by a screen
 * before this step, so these pin it on the host.
 */
class PracticeAttemptTest {

    private fun ui(name: String): Square = algebraicToSquare(name)!!

    private fun puzzle(
        fen: String,
        bestUci: String,
        playedUci: String,
        sideToMove: CoreColor = CoreColor.WHITE,
        accepted: Set<String> = setOf(bestUci),
        hintPiece: CorePieceType = CorePieceType.KNIGHT,
        isMateInOne: Boolean = false,
    ) = PracticePuzzle(
        ply = 3,
        moveNumber = 2,
        sideToMove = sideToMove,
        fenBefore = fen,
        playedUci = playedUci,
        playedSan = "?",
        bestUci = bestUci,
        bestSan = "?",
        bestLineSan = listOf("?", "a", "b"),
        goal = PuzzleGoal.BetterMove,
        loss = 20.0,
        evalSwingCp = 100,
        hintPiece = hintPiece,
        hintSquare = CoreSquare.fromAlgebraic(bestUci.substring(0, 2)),
        hintMotif = null,
        acceptedUci = accepted,
        isMateInOne = isMateInOne,
        hasSimulation = false,
    )

    /** 1.e4 e5, White to play: the best move is Nf3, the move played in "the game" was d3. */
    private val openingFen = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2"
    private val opening get() = puzzle(openingFen, bestUci = "g1f3", playedUci = "d2d3")
    private fun position(p: PracticePuzzle) = Position.fromFen(p.fenBefore)

    private fun tap(state: PuzzleUiState, p: PracticePuzzle, vararg squares: String): PuzzleUiState {
        val pos = position(p)
        return squares.fold(state) { s, name -> practiceTap(s, p, pos, ui(name)) }
    }

    // ---- the own-piece gate ----

    @Test
    fun tappingAnOpponentPieceFirstDoesNothing() {
        val p = opening
        val after = tap(PuzzleUiState(), p, "e7")
        assertEquals(PuzzleUiState(), after)
        assertNull(after.selected)
    }

    @Test
    fun tappingAnEmptySquareFirstDoesNothing() {
        assertEquals(PuzzleUiState(), tap(PuzzleUiState(), opening, "a4"))
    }

    @Test
    fun tappingAnOwnPieceSelectsItAndTheDotsAreItsLegalTargets() {
        val p = opening
        val state = tap(PuzzleUiState(), p, "g1")
        assertEquals(ui("g1"), state.selected)
        // The knight on g1 goes to f3, h3 (e2 is blocked by nothing: the e-pawn moved, so e2 is free).
        assertEquals(setOf(ui("f3"), ui("h3"), ui("e2")), practiceTargets(state, p, position(p)))
    }

    @Test
    fun tappingAnotherOwnPieceReselectsAndTheSamePieceClears() {
        val p = opening
        val first = tap(PuzzleUiState(), p, "g1")
        assertEquals(ui("b1"), tap(first, p, "b1").selected)
        assertNull(tap(first, p, "g1").selected)
    }

    @Test
    fun anIllegalDestinationClearsTheSelectionWithNoFeedback() {
        val p = opening
        val state = tap(PuzzleUiState(), p, "g1", "g5")
        assertNull(state.selected)
        assertEquals(PuzzleFeedback.NONE, state.feedback)
        assertEquals(PuzzlePhase.QUESTION, state.phase)
        // Tapping an opponent piece that is not a target does the same.
        assertNull(tap(tap(PuzzleUiState(), p, "g1"), p, "e5").selected)
    }

    // ---- verdicts ----

    @Test
    fun theBestMoveSolvesThePuzzle() {
        val state = tap(PuzzleUiState(), opening, "g1", "f3")
        assertEquals(PuzzlePhase.SOLVED, state.phase)
        assertEquals("g1f3", state.shownUci)
        assertNull(state.selected)
        assertEquals(PuzzleFeedback.NONE, state.feedback)
        assertFalse(state.acceptsTaps)
    }

    @Test
    fun aWrongTargetSaysNotQuiteAndClearsTheSelection() {
        val state = tap(PuzzleUiState(), opening, "g1", "h3")
        assertEquals(PuzzlePhase.QUESTION, state.phase)
        assertEquals(PuzzleFeedback.WRONG, state.feedback)
        assertNull(state.selected)
        assertNull(state.shownUci)
    }

    @Test
    fun theMovePlayedInTheGameIsReportedAsSuch() {
        val state = tap(PuzzleUiState(), opening, "d2", "d3")
        assertEquals(PuzzleFeedback.PLAYED_IN_GAME, state.feedback)
        assertEquals(PuzzlePhase.QUESTION, state.phase)
    }

    @Test
    fun aNewAttemptReplacesTheOldFeedbackAndASolveClearsIt() {
        val p = opening
        val wrong = tap(PuzzleUiState(), p, "g1", "h3")
        assertEquals(PuzzleFeedback.WRONG, wrong.feedback)
        val solved = tap(wrong, p, "g1", "f3")
        assertEquals(PuzzlePhase.SOLVED, solved.phase)
        assertEquals(PuzzleFeedback.NONE, solved.feedback)
        assertTrue(justSolved(wrong, solved))
        assertFalse(justSolved(solved, solved))
    }

    @Test
    fun theBoardIsNotInteractiveOnceSolvedOrRevealed() {
        val p = opening
        val solved = tap(PuzzleUiState(), p, "g1", "f3")
        assertEquals(solved, tap(solved, p, "b1"))
        val revealed = practiceShowAnswer(PuzzleUiState(), p)
        assertEquals(revealed, tap(revealed, p, "b1"))
    }

    // ---- promotion auto-queens ----

    @Test
    fun aPawnReachingTheLastRankAutoQueens() {
        // White Ka1 and Pa7 against Kh7: a8 is legal as Q, R, B or N, and the screen picks the queen.
        val fen = "8/P6k/8/8/8/8/8/K7 w - - 0 1"
        val p = puzzle(fen, bestUci = "a7a8q", playedUci = "a1a2", hintPiece = CorePieceType.PAWN)
        val pos = position(p)
        val move = chooseMove(pos, CoreSquare.fromAlgebraic("a7"), CoreSquare.fromAlgebraic("a8"))
        assertNotNull(move)
        assertEquals(CorePieceType.QUEEN, move!!.promotion)
        assertEquals("a7a8q", move.toUci())
        val state = tap(PuzzleUiState(), p, "a7", "a8")
        assertEquals(PuzzlePhase.SOLVED, state.phase)
        assertEquals("a7a8q", state.shownUci)
    }

    // ---- castling is the king's move to its destination ----

    private val castlingFen = "r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R w KQkq - 0 1"

    @Test
    fun tappingTheKingsDestinationCastles() {
        val kingside = puzzle(castlingFen, bestUci = "e1g1", playedUci = "a2a3", hintPiece = CorePieceType.KING)
        val s1 = tap(PuzzleUiState(), kingside, "e1")
        // The king's own dots include both castling destinations, g1 and c1.
        assertTrue(ui("g1") in practiceTargets(s1, kingside, position(kingside)))
        assertTrue(ui("c1") in practiceTargets(s1, kingside, position(kingside)))
        val done = tap(s1, kingside, "g1")
        assertEquals(PuzzlePhase.SOLVED, done.phase)
        assertEquals("e1g1", done.shownUci)

        val queenside = puzzle(castlingFen, bestUci = "e1c1", playedUci = "a2a3", hintPiece = CorePieceType.KING)
        assertEquals("e1c1", tap(PuzzleUiState(), queenside, "e1", "c1").shownUci)
    }

    @Test
    fun tappingTheRookInsteadOfTheKingsDestinationReselectsTheRook() {
        // The core's castling move targets g1, not the rook square, so h1 is "another own piece".
        val p = puzzle(castlingFen, bestUci = "e1g1", playedUci = "a2a3", hintPiece = CorePieceType.KING)
        val state = tap(PuzzleUiState(), p, "e1", "h1")
        assertEquals(ui("h1"), state.selected)
        assertEquals(PuzzlePhase.QUESTION, state.phase)
    }

    // ---- the flipped board: taps and squares ----

    private val boardPx = 800f

    private fun centreOf(cell: Pair<Int, Int>): Pair<Float, Float> =
        (cell.first + 0.5f) * boardPx / 8f to (cell.second + 0.5f) * boardPx / 8f

    @Test
    fun theCornersMapToTheRightSquaresInBothOrientations() {
        // White at the bottom: top-left is a8 and bottom-right is h1.
        assertEquals(ui("a8"), squareAtOffset(1f, 1f, boardPx, BoardOrientation.WHITE_DOWN))
        assertEquals(ui("h1"), squareAtOffset(boardPx - 1f, boardPx - 1f, boardPx, BoardOrientation.WHITE_DOWN))
        // Black at the bottom the board is turned half way round: top-left is h1, bottom-right is a8.
        assertEquals(ui("h1"), squareAtOffset(1f, 1f, boardPx, BoardOrientation.BLACK_DOWN))
        assertEquals(ui("a8"), squareAtOffset(boardPx - 1f, boardPx - 1f, boardPx, BoardOrientation.BLACK_DOWN))
        // An edge tap is clamped onto the board, never out of range.
        assertEquals(ui("h1"), squareAtOffset(boardPx, boardPx, boardPx, BoardOrientation.WHITE_DOWN))
    }

    @Test
    fun everySquareRoundTripsThroughItsDisplayCellInBothOrientations() {
        for (orientation in BoardOrientation.entries) {
            for (square in 0..63) {
                val (x, y) = centreOf(squareToDisplayCell(square, orientation))
                assertEquals("square $square, $orientation", square, squareAtOffset(x, y, boardPx, orientation))
            }
        }
    }

    @Test
    fun uiSquaresConvertToTheRightCoreSquares() {
        // The UI numbers a8 = 0, the core numbers a1 = 0: e2 is core 12, g8 is core 62.
        assertEquals(CoreSquare.fromAlgebraic("e2"), ui("e2").toCoreSquare())
        assertEquals(CoreSquare.fromAlgebraic("g8"), ui("g8").toCoreSquare())
        assertEquals(0, ui("a1").toCoreSquare().index)
        assertEquals(ui("h8"), CoreSquare.fromAlgebraic("h8").toUiSquare())
    }

    @Test
    fun blackSolvesAPuzzleByTappingTheFlippedBoard() {
        // Black to play after 1.e4: the best move is ...e5. Black's colour is at the bottom.
        val fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"
        val p = puzzle(fen, bestUci = "e7e5", playedUci = "a7a6", sideToMove = CoreColor.BLACK, hintPiece = CorePieceType.PAWN)
        val orientation = BoardOrientation.BLACK_DOWN
        val pos = position(p)
        fun tapAt(state: PuzzleUiState, name: String): PuzzleUiState {
            val (x, y) = centreOf(squareToDisplayCell(ui(name), orientation))
            return practiceTap(state, p, pos, squareAtOffset(x, y, boardPx, orientation))
        }
        // A White pawn is not Black's: ignored. Black's own pawn selects, its target solves.
        assertEquals(PuzzleUiState(), tapAt(PuzzleUiState(), "e4"))
        val selected = tapAt(PuzzleUiState(), "e7")
        assertEquals(ui("e7"), selected.selected)
        assertEquals(setOf(ui("e6"), ui("e5")), practiceTargets(selected, p, pos))
        val solved = tapAt(selected, "e5")
        assertEquals(PuzzlePhase.SOLVED, solved.phase)
        assertEquals("e7e5", solved.shownUci)
    }

    // ---- an alternative mate is accepted by the rules ----

    @Test
    fun anyCheckmatingMoveSolvesAMateInOnePuzzleEvenIfItWasNotCached() {
        // Two rooks, two mates: Ra8# is the cached best move, Rb8# is not in acceptedUci but is mate.
        val fen = "6k1/5ppp/8/8/8/8/8/RR2K3 w - - 0 1"
        val p = puzzle(fen, bestUci = "a1a8", playedUci = "e1e2", hintPiece = CorePieceType.ROOK, isMateInOne = true)
        assertFalse("b1b8" in p.acceptedUci)
        val state = tap(PuzzleUiState(), p, "b1", "b8")
        assertEquals(PuzzlePhase.SOLVED, state.phase)
        assertEquals("b1b8", state.shownUci)
        // A move that is not mate is not accepted just because the puzzle is a mate in one.
        assertEquals(PuzzleFeedback.WRONG, tap(PuzzleUiState(), p, "b1", "b7").feedback)
    }
}
