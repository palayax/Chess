package net.palaya.chessanalyzer.ui.model

import net.palaya.chessanalyzer.core.analysis.EvalFormat
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.moveToSan
import net.palaya.chessanalyzer.core.chess.parseUci
import net.palaya.chessanalyzer.data.mapper.toCoreSquare

/*
 * The state machine of one "While you wait" puzzle (docs/WAITING_CONTENT.md §5). Pure Kotlin, host-tested
 * (WaitingPuzzleLogicTest), and deliberately small:
 *
 *   ASKING  -> the user taps a piece and a square;
 *             the move equals the solution's next move      -> REPLYING (more to come) or SOLVED (the line is done)
 *             anything else                                -> ASKING again, feedback WRONG
 *   REPLYING-> the UI shows the user's move, waits a moment, and calls [playReply]: the opponent's reply from the
 *             solution is played and the state is ASKING for the next solver move
 *   "Show solution" from ASKING plays every remaining move                                 -> REVEALED
 *
 * Why a wrong move is simply "wrong": every puzzle was verified so that the solution's move is the engine's
 * choice and every other move is at least 150 centipawns worse (or fails to mate), scripts/waiting_puzzles.py.
 *
 * Squares: taps arrive as UI squares (a8 = 0), exactly as ChessBoard.onSquareClick reports them; the tap-to-move
 * rules (own piece first, promotion auto-queens, castling is the king's move) are PracticeLogic's `chooseMove` and
 * `legalTargetsFrom`, reused rather than copied.
 */

enum class WaitingPuzzleStatus { ASKING, REPLYING, SOLVED, REVEALED }

enum class WaitingPuzzleFeedback { NONE, WRONG, CONTINUE }

data class WaitingPuzzleSession(
    val puzzle: WaitingPuzzle,
    /** Index into [WaitingPuzzle.solution] of the next move to be played (even = the solver's). */
    val step: Int = 0,
    val status: WaitingPuzzleStatus = WaitingPuzzleStatus.ASKING,
    /** The selected own piece, as a UI square. */
    val selected: Square? = null,
    val feedback: WaitingPuzzleFeedback = WaitingPuzzleFeedback.NONE,
    val wrongAttempts: Int = 0,
    /** True once the user has tapped the board. */
    val touched: Boolean = false,
    val lastMoveUci: String? = puzzle.prevUci,
    val position: Position = Position.fromFen(puzzle.fen),
) {
    val acceptsTaps: Boolean get() = status == WaitingPuzzleStatus.ASKING
    val resolved: Boolean get() = status == WaitingPuzzleStatus.SOLVED || status == WaitingPuzzleStatus.REVEALED

    /** The user has started this puzzle and not finished it: the panel must not rotate it away. */
    val inProgress: Boolean get() = touched && !resolved

    /** The reply the opponent is about to play (only while REPLYING). */
    val pendingReplySan: String? get() = if (status == WaitingPuzzleStatus.REPLYING) puzzle.san.getOrNull(step) else null

    /** The last move to highlight, as UI squares. */
    val lastMove: Pair<Square, Square>? get() = lastMoveUci?.let { uciToUiSquares(it) }

    /** Dots for the selected piece. */
    val targets: Set<Square> get() = if (acceptsTaps && selected != null) legalTargetsFrom(position, selected) else emptySet()
}

fun newWaitingPuzzleSession(puzzle: WaitingPuzzle): WaitingPuzzleSession = WaitingPuzzleSession(puzzle)

/** One tap on the board ([square] is a UI square). */
fun waitingPuzzleTap(s: WaitingPuzzleSession, square: Square): WaitingPuzzleSession {
    if (!s.acceptsTaps) return s
    val own = s.position.pieceAt(square.toCoreSquare())?.color == s.position.sideToMove
    val selected = s.selected
    val touched = s.copy(touched = true)
    if (selected == null) return if (own) touched.copy(selected = square) else touched
    if (selected == square) return touched.copy(selected = null)
    val move = chooseMove(s.position, selected.toCoreSquare(), square.toCoreSquare())
        ?: return if (own) touched.copy(selected = square) else touched.copy(selected = null)
    val uci = move.toUci()
    if (uci != s.puzzle.solution[s.step]) {
        return touched.copy(
            selected = null,
            feedback = WaitingPuzzleFeedback.WRONG,
            wrongAttempts = s.wrongAttempts + 1,
        )
    }
    val next = s.position.makeMove(move)
    val step = s.step + 1
    return if (step >= s.puzzle.solution.size) {
        touched.copy(step = step, status = WaitingPuzzleStatus.SOLVED, selected = null, feedback = WaitingPuzzleFeedback.NONE, lastMoveUci = uci, position = next)
    } else {
        touched.copy(step = step, status = WaitingPuzzleStatus.REPLYING, selected = null, feedback = WaitingPuzzleFeedback.CONTINUE, lastMoveUci = uci, position = next)
    }
}

/** Plays the opponent's reply (the UI calls this a moment after the user's move). Does nothing unless REPLYING. */
fun waitingPuzzleReply(s: WaitingPuzzleSession): WaitingPuzzleSession {
    if (s.status != WaitingPuzzleStatus.REPLYING) return s
    val uci = s.puzzle.solution[s.step]
    val next = s.position.makeMove(s.position.parseUci(uci))
    return s.copy(step = s.step + 1, status = WaitingPuzzleStatus.ASKING, selected = null, lastMoveUci = uci, position = next)
}

/** "Show solution": every remaining move is played; the session is REVEALED. A solved puzzle stays SOLVED. */
fun waitingPuzzleReveal(s: WaitingPuzzleSession): WaitingPuzzleSession {
    if (s.resolved) return s
    var position = s.position
    var lastUci = s.lastMoveUci
    for (i in s.step until s.puzzle.solution.size) {
        val uci = s.puzzle.solution[i]
        position = position.makeMove(position.parseUci(uci))
        lastUci = uci
    }
    return s.copy(
        step = s.puzzle.solution.size,
        status = WaitingPuzzleStatus.REVEALED,
        selected = null,
        feedback = WaitingPuzzleFeedback.NONE,
        lastMoveUci = lastUci,
        position = position,
        touched = true,
    )
}

/**
 * The solution written with move numbers from the puzzle's own position: "24.Rxd4+ Kxd4 25.Qd2+", or
 * "24…Rxd4 25.Qd2+" when Black starts. The SAN comes from `:core` replaying the UCI moves, not from the stored
 * text, so the two are cross-checked by the tests.
 */
fun solutionLineText(puzzle: WaitingPuzzle): String {
    var pos = Position.fromFen(puzzle.fen)
    var number = puzzle.moveNumber
    val parts = ArrayList<String>()
    puzzle.solution.forEachIndexed { i, uci ->
        val move = pos.parseUci(uci)
        val san = pos.moveToSan(move)
        val white = pos.sideToMove == Color.WHITE
        parts += when {
            white -> "$number.$san"
            i == 0 -> "$number…$san"
            else -> san
        }
        if (!white) number++
        pos = pos.makeMove(move)
    }
    return parts.joinToString(" ")
}

/** The two engine scores of a "best move" puzzle as text, from the solver's side, or null for a mate puzzle. */
data class WaitingPuzzleScores(val best: String, val secondBest: String?)

fun waitingPuzzleScores(puzzle: WaitingPuzzle): WaitingPuzzleScores? {
    val best = puzzle.bestCp ?: return null
    return WaitingPuzzleScores(EvalFormat.score(best), puzzle.secondCp?.let { EvalFormat.score(it) })
}
