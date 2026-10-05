package net.palaya.chessanalyzer.ui.model

import net.palaya.chessanalyzer.core.analysis.PracticeJudge
import net.palaya.chessanalyzer.core.analysis.PracticePuzzle
import net.palaya.chessanalyzer.core.analysis.PracticeSet
import net.palaya.chessanalyzer.core.analysis.Verdict
import net.palaya.chessanalyzer.core.chess.Move
import net.palaya.chessanalyzer.core.chess.PieceType as CorePieceType
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.Square as CoreSquare
import net.palaya.chessanalyzer.core.chess.parseUci
import net.palaya.chessanalyzer.data.mapper.toCoreSquare
import net.palaya.chessanalyzer.data.mapper.toUiSquare

/*
 * Pure logic behind the Practise screen (docs/PRACTICE_DESIGN.md §2, §7.2; R3): the state machine of
 * one puzzle, the tap-to-move rules, the two-step hint, and the Summary's entry row. No Compose and
 * no Android here, so every rule has a host test (PracticeAttemptTest, PracticeLogicTest).
 *
 * Squares: the UI board numbers a8 = 0 (ui.model.Square) and `:core` numbers a1 = 0, so every tap is
 * converted with BoardMapper's toCoreSquare before it meets a Position, and every highlight goes back
 * with toUiSquare.
 */

/** Where one puzzle is. A wrong attempt is not a phase: the position stays a QUESTION. */
enum class PuzzlePhase { QUESTION, SOLVED, REVEALED }

/** What the last attempt said, shown on the card until the next attempt or hint. */
enum class PuzzleFeedback { NONE, WRONG, PLAYED_IN_GAME }

/** The two hint taps: the piece is pre-selected, then the target square is dotted. Never played. */
const val PRACTICE_HINT_STEPS = 2

/**
 * Everything that changes while one puzzle is on screen. It is saved across rotation and across a
 * trip into the walkthrough (the screen stores it with `rememberSaveable` through [toSaved] /
 * [puzzleUiStateFromSaved]).
 *
 * @property selected the tapped own piece, as a UI square (null = nothing selected).
 * @property hintStep 0 = no hint, 1 = the best move's piece is pre-selected, 2 = its target dotted.
 * @property shownUci the move drawn on the board in SOLVED / REVEALED (the attempt, or the best move).
 */
data class PuzzleUiState(
    val phase: PuzzlePhase = PuzzlePhase.QUESTION,
    val selected: Square? = null,
    val feedback: PuzzleFeedback = PuzzleFeedback.NONE,
    val hintStep: Int = 0,
    val shownUci: String? = null,
) {
    /** The hint button is offered until both taps are used, and only while the question is open. */
    val canHint: Boolean get() = phase == PuzzlePhase.QUESTION && hintStep < PRACTICE_HINT_STEPS

    /** Show answer is always available while the question is open. */
    val canShowAnswer: Boolean get() = phase == PuzzlePhase.QUESTION

    /** Taps on the board only count while the question is open. */
    val acceptsTaps: Boolean get() = phase == PuzzlePhase.QUESTION

    /** The way the state is written into a saved-instance bundle. */
    fun toSaved(): List<Any> = listOf(phase.ordinal, selected ?: -1, feedback.ordinal, hintStep, shownUci.orEmpty())
}

/** Inverse of [PuzzleUiState.toSaved]; anything malformed reads as a fresh puzzle. */
fun puzzleUiStateFromSaved(saved: List<Any>): PuzzleUiState = try {
    PuzzleUiState(
        phase = PuzzlePhase.entries[saved[0] as Int],
        selected = (saved[1] as Int).takeIf { it >= 0 },
        feedback = PuzzleFeedback.entries[saved[2] as Int],
        hintStep = (saved[3] as Int).coerceIn(0, PRACTICE_HINT_STEPS),
        shownUci = (saved[4] as String).takeIf { it.isNotEmpty() },
    )
} catch (e: Exception) {
    PuzzleUiState()
}

/** The position the puzzle starts from ([PracticePuzzle.fenBefore]). */
fun puzzlePosition(puzzle: PracticePuzzle): Position = Position.fromFen(puzzle.fenBefore)

/** [uci] played on [fen], or null when either is not valid. */
fun positionAfter(fen: String, uci: String): Position? = try {
    val position = Position.fromFen(fen)
    position.makeMove(position.parseUci(uci))
} catch (e: Exception) {
    null
}

/** The destination of the best move, as a UI square. */
fun bestTargetSquare(puzzle: PracticePuzzle): Square = CoreSquare.fromAlgebraic(puzzle.bestUci.substring(2, 4)).toUiSquare()

/** The from and to squares of a UCI move as UI squares, or null when it is malformed. */
fun uciToUiSquares(uci: String): Pair<Square, Square>? = try {
    CoreSquare.fromAlgebraic(uci.substring(0, 2)).toUiSquare() to CoreSquare.fromAlgebraic(uci.substring(2, 4)).toUiSquare()
} catch (e: Exception) {
    null
}

/** Whether [square] (a UI square) holds one of the solver's own pieces in [position]. */
fun isOwnPiece(position: Position, puzzle: PracticePuzzle, square: Square): Boolean =
    position.pieceAt(square.toCoreSquare())?.color == puzzle.sideToMove

/** The squares the piece on [from] (UI square) can legally move to, as UI squares. */
fun legalTargetsFrom(position: Position, from: Square): Set<Square> {
    val core = from.toCoreSquare()
    return position.legalMoves().filter { it.from == core }.mapTo(HashSet()) { it.to.toUiSquare() }
}

/**
 * The move for a from/to tap pair. A pawn reaching the last rank has four legal moves with the same
 * squares, one per promotion piece: the queen one is chosen (promotion auto-queens). Castling is the
 * king's move to its destination square, so tapping g1 with the king selected castles.
 */
fun chooseMove(position: Position, from: CoreSquare, to: CoreSquare): Move? {
    val candidates = position.legalMoves().filter { it.from == from && it.to == to }
    return candidates.firstOrNull { it.promotion == CorePieceType.QUEEN } ?: candidates.firstOrNull { it.promotion == null }
}

/**
 * The legal-move dots to draw. Normally the selected piece's legal targets; after the second hint tap
 * with the hinted piece selected, only the best move's target (dotted, not played).
 */
fun practiceTargets(state: PuzzleUiState, puzzle: PracticePuzzle, position: Position): Set<Square> {
    val selected = state.selected ?: return emptySet()
    if (state.phase != PuzzlePhase.QUESTION) return emptySet()
    val hintFrom = puzzle.hintSquare.toUiSquare()
    return if (state.hintStep >= PRACTICE_HINT_STEPS && selected == hintFrom) {
        setOf(bestTargetSquare(puzzle))
    } else {
        legalTargetsFrom(position, selected)
    }
}

/**
 * One tap on the board ([square] is a UI square, as `ChessBoard.onSquareClick` reports it).
 *
 *  - Nothing selected: an own piece is selected; anything else (an opponent piece, an empty square)
 *    does nothing.
 *  - Something selected: the same piece clears the selection; a legal target plays the move (judged
 *    by [PracticeJudge]); another own piece re-selects; any other square clears the selection. An
 *    illegal destination gives no feedback, because the dots already show where the piece can go.
 *  - Once the puzzle is SOLVED or REVEALED the board is not interactive.
 */
fun practiceTap(state: PuzzleUiState, puzzle: PracticePuzzle, position: Position, square: Square): PuzzleUiState {
    if (!state.acceptsTaps) return state
    val own = isOwnPiece(position, puzzle, square)
    val selected = state.selected
    if (selected == null) return if (own) state.copy(selected = square) else state
    if (selected == square) return state.copy(selected = null)
    val move = chooseMove(position, selected.toCoreSquare(), square.toCoreSquare())
    if (move != null) return attempt(state, puzzle, move)
    return if (own) state.copy(selected = square) else state.copy(selected = null)
}

private fun attempt(state: PuzzleUiState, puzzle: PracticePuzzle, move: Move): PuzzleUiState {
    val uci = move.toUci()
    return when (PracticeJudge.judge(puzzle, uci)) {
        Verdict.Correct -> state.copy(
            phase = PuzzlePhase.SOLVED,
            selected = null,
            feedback = PuzzleFeedback.NONE,
            shownUci = uci,
        )
        is Verdict.PlayedInGame -> state.copy(selected = null, feedback = PuzzleFeedback.PLAYED_IN_GAME)
        Verdict.Wrong -> state.copy(selected = null, feedback = PuzzleFeedback.WRONG)
    }
}

/**
 * One press of the hint button. The first press pre-selects the best move's piece (its legal targets
 * are dotted); the second shows the best move's target square as the only dot. Neither plays the
 * move, and after the second the button is gone ([PuzzleUiState.canHint]).
 */
fun practiceHint(state: PuzzleUiState, puzzle: PracticePuzzle): PuzzleUiState {
    if (!state.canHint) return state
    return state.copy(
        hintStep = state.hintStep + 1,
        selected = puzzle.hintSquare.toUiSquare(),
        feedback = PuzzleFeedback.NONE,
    )
}

/** "Show answer": the best move is played on the board and the card names it. */
fun practiceShowAnswer(state: PuzzleUiState, puzzle: PracticePuzzle): PuzzleUiState {
    if (!state.canShowAnswer) return state
    return state.copy(
        phase = PuzzlePhase.REVEALED,
        selected = null,
        feedback = PuzzleFeedback.NONE,
        shownUci = puzzle.bestUci,
    )
}

/** True exactly when [new] is the moment a puzzle became solved (the screen then marks it solved). */
fun justSolved(old: PuzzleUiState, new: PuzzleUiState): Boolean =
    old.phase != PuzzlePhase.SOLVED && new.phase == PuzzlePhase.SOLVED

/**
 * "This cost about N pawns" in half pawns, for the line under "That's the move you played", or null
 * when the cost crosses a mate boundary (`evalSwingCp` null) or rounds to nothing. Same rounding as
 * the Board's comment card ([pawnCostHalves]).
 */
fun practiceCostHalves(puzzle: PracticePuzzle): Int? {
    val swing = puzzle.evalSwingCp ?: return null
    return Math.round(swing / 50.0).toInt().takeIf { it >= 1 }
}

/** The moves of the best line after the best move itself, for "Then: Qd2 Nxd5 ...". At most [max]. */
fun practiceFollowUp(puzzle: PracticePuzzle, max: Int = 4): List<String> {
    val line = puzzle.bestLineSan
    val rest = if (line.firstOrNull() == puzzle.bestSan) line.drop(1) else line
    return rest.take(max)
}

/**
 * Which puzzle the screen opens at: the one at [requestedPly] when a "Try it" asked for it, otherwise
 * the first unsolved one, otherwise (everything solved) the first.
 */
fun initialPuzzleIndex(puzzles: List<PracticePuzzle>, solvedPlies: Set<Int>, requestedPly: Int?): Int {
    if (requestedPly != null) {
        val at = puzzles.indexOfFirst { it.ply == requestedPly }
        if (at >= 0) return at
    }
    val firstUnsolved = puzzles.indexOfFirst { it.ply !in solvedPlies }
    return if (firstUnsolved >= 0) firstUnsolved else 0
}

/** How many of [puzzles] are in [solvedPlies]. */
fun solvedCount(puzzles: List<PracticePuzzle>, solvedPlies: Set<Int>): Int = puzzles.count { it.ply in solvedPlies }

/**
 * The state of the Summary's entry row (design §5):
 *  - "Not me": [PracticeEntryState.Hidden] (the feature's promise is "your own mistakes");
 *  - the side not chosen: [PracticeEntryState.NoSide];
 *  - a side chosen: "Nothing to fix" or the count with how many are solved.
 */
fun practiceEntryState(side: SideChoice, set: PracticeSet?, solvedPlies: Set<Int>): PracticeEntryState = when {
    side == SideChoice.NOT_ME -> PracticeEntryState.Hidden
    side == SideChoice.UNKNOWN -> PracticeEntryState.NoSide
    else -> when (set) {
        PracticeSet.Empty -> PracticeEntryState.Empty
        is PracticeSet.Puzzles -> PracticeEntryState.Count(set.puzzles.size, solvedCount(set.puzzles, solvedPlies))
        PracticeSet.NoSide, null -> PracticeEntryState.Hidden
    }
}

/** The plies that are practice puzzles (empty for any set without puzzles). */
fun practicePlies(set: PracticeSet?): Set<Int> =
    (set as? PracticeSet.Puzzles)?.puzzles?.mapTo(HashSet()) { it.ply }.orEmpty()

/**
 * Whether a "Try it" button is shown for the move at [ply]: on a Summary key-moment card and on the
 * last step of the Walkthrough, only when that move is a practice puzzle. Otherwise it is omitted.
 */
fun canTryIt(ply: Int, practicePlies: Set<Int>): Boolean = ply in practicePlies
