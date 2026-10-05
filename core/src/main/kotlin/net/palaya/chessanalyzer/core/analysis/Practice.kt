package net.palaya.chessanalyzer.core.analysis

import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.PieceType
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.Square
import net.palaya.chessanalyzer.core.chess.moveToSan
import net.palaya.chessanalyzer.core.chess.parseUci
import kotlin.math.abs
import kotlin.math.max

/**
 * "Practise your own mistakes" — ANALYSIS_SPEC.md §11 and docs/PRACTICE_DESIGN.md §1, §3, §7.1.
 *
 * Pure `:core`, no engine call: puzzles are the user's own MISTAKE / MISS / BLUNDER plies, and an
 * attempt is judged from the MultiPV lines the analysis already cached
 * ([MoveAnnotation.candidateLines]). A position is only admitted when those lines can decide
 * every move, so a "Wrong" verdict is never a guess.
 */

/** What the solver is told to look for. Reuses the narration's prize thresholds. */
sealed interface PuzzleGoal {
    /** A forced mate in [moves] for the side to move. */
    data class MateIn(val moves: Int) : PuzzleGoal

    /** The best missed tactic wins at least a rook (>= 500 cp). */
    object WinRookOrBetter : PuzzleGoal

    /** ... at least a minor piece (>= 320 cp). */
    object WinPiece : PuzzleGoal

    /** ... at least a pawn and a half (>= 150 cp). */
    object WinMaterial : PuzzleGoal

    /** No labelled prize: just "find the better move". */
    object BetterMove : PuzzleGoal
}

/**
 * One position of the user's own game, with everything the Practise screen needs precomputed.
 *
 * @property loss win-percent the played move lost (0..100).
 * @property evalSwingCp centipawns the played move cost the mover (mover-relative, never
 *   negative), or null when either side of the move is a forced mate, since a saturated mate
 *   value is not a number a learner should be shown.
 * @property acceptedUci every first move that counts as correct: the best move plus any cached
 *   line within [PracticeSelector.ACCEPT_LOSS] win-percent of it. Never contains [playedUci].
 * @property isMateInOne the best move checkmates (verified by the rules, not by the engine), so
 *   *any* checkmating move is correct whether or not it was cached.
 */
data class PracticePuzzle(
    val ply: Int,
    val moveNumber: Int,
    val sideToMove: Color,
    val fenBefore: String,
    val playedUci: String,
    val playedSan: String,
    val bestUci: String,
    val bestSan: String,
    val bestLineSan: List<String>,
    val goal: PuzzleGoal,
    val loss: Double,
    val evalSwingCp: Int?,
    val hintPiece: PieceType,
    val hintSquare: Square,
    val hintMotif: TacticType?,
    val acceptedUci: Set<String>,
    val isMateInOne: Boolean,
    val hasSimulation: Boolean
)

sealed interface PracticeSet {
    /** No side was chosen: no puzzles. */
    object NoSide : PracticeSet

    /** A side was chosen but nothing qualified ("Nothing to fix in this game"). */
    object Empty : PracticeSet

    /** 1..[PracticeSelector.MAX_PUZZLES] puzzles in ply order. */
    data class Puzzles(val puzzles: List<PracticePuzzle>) : PracticeSet
}

object PracticeSelector {
    /** The MISTAKE floor (MoveClassifier: loss < 10 is an inaccuracy). MISS is exempt. */
    const val MIN_LOSS = 10.0

    /** Not hopeless: the mover must have had at least this win percent before the move. */
    const val MIN_WIN_BEFORE = 25.0

    /** Best-or-near-best bound in win percent, the spec's own 2.0 (GREAT / BRILLIANT use it). */
    const val ACCEPT_LOSS = 2.0

    const val MAX_PUZZLES = 5

    /** Same best move within this many plies is one puzzle. */
    const val DEDUPE_WINDOW_PLIES = 4

    /** A MISS ranks as at least this loss, because a thrown-away mate can lose little win percent. */
    const val MISS_RANK_FLOOR = 20.0

    /** Goal thresholds on the best missed tactic's `materialSwing`, same as the narration puzzle prompt. */
    const val GOAL_ROOK_CP = 500
    const val GOAL_PIECE_CP = 320
    const val GOAL_MATERIAL_CP = 150

    private val PUZZLE_CLASSES = setOf(MoveClassification.MISTAKE, MoveClassification.MISS, MoveClassification.BLUNDER)

    private class Candidate(val puzzle: PracticePuzzle, val rankLoss: Double)

    /**
     * @param report the **unpruned** report (puzzles are classification-based, so the §9.6 tactic
     *   gate does not apply).
     * @param userColor the side the user played; null means "not chosen yet".
     */
    fun select(report: GameReport, userColor: Color?): PracticeSet {
        if (userColor == null) return PracticeSet.NoSide

        val candidates = report.annotations
            .filter { it.color == userColor }
            .sortedBy { it.ply }
            .mapNotNull { candidateFor(it) }

        val kept = dedupe(candidates)
        if (kept.isEmpty()) return PracticeSet.Empty

        val chosen = kept
            .sortedWith(compareByDescending<Candidate> { it.rankLoss }.thenBy { it.puzzle.ply })
            .take(MAX_PUZZLES)
            .map { it.puzzle }
            .sortedBy { it.ply }
        return PracticeSet.Puzzles(chosen)
    }

    /** Same best move within [DEDUPE_WINDOW_PLIES] keeps the larger loss (the earlier ply on a tie). */
    private fun dedupe(inPlyOrder: List<Candidate>): List<Candidate> {
        val kept = ArrayList<Candidate>()
        for (c in inPlyOrder) {
            val twin = kept.indexOfLast {
                it.puzzle.bestUci == c.puzzle.bestUci && abs(c.puzzle.ply - it.puzzle.ply) <= DEDUPE_WINDOW_PLIES
            }
            if (twin < 0) {
                kept.add(c)
            } else if (c.puzzle.loss > kept[twin].puzzle.loss) {
                kept[twin] = c
            }
        }
        return kept
    }

    private fun candidateFor(a: MoveAnnotation): Candidate? {
        // Rules 2-3: class and loss floor (MISS exempt from the floor).
        if (a.classification !in PUZZLE_CLASSES) return null
        val isMiss = a.classification == MoveClassification.MISS
        if (a.loss < MIN_LOSS && !isMiss) return null
        // Rule 4: not hopeless.
        if (a.winPercentBefore < MIN_WIN_BEFORE) return null

        // Rule 5: there is a legal, different answer, and it is not an under-promotion.
        val bestUci = a.bestMoveUci ?: return null
        if (bestUci == a.uci) return null
        val position = try {
            Position.fromFen(a.fenBefore)
        } catch (e: Exception) {
            return null
        }
        val bestMove = try {
            position.parseUci(bestUci)
        } catch (e: Exception) {
            return null
        }
        if (bestMove.promotion != null && bestMove.promotion != PieceType.QUEEN) return null

        // Rule 6: judgeable from the cache.
        val lines = a.candidateLines.sortedBy { it.multiPv }
        val isMateInOne = position.makeMove(bestMove).isCheckmate()
        if (!isMateInOne && !cacheDecidesEveryMove(lines, position.legalMoves().size)) return null

        val bestWin = lines.firstOrNull()?.winPercent()
        val accepted = LinkedHashSet<String>()
        accepted.add(bestUci)
        if (bestWin != null) {
            lines.filter { bestWin - it.winPercent() <= ACCEPT_LOSS }.forEach { accepted.add(it.uci) }
        }
        accepted.remove(a.uci)

        val missed = a.tacticsMissed
            .filter { it.byColor == a.color }
            .maxWithOrNull(compareBy<TacticInstance> { it.materialSwing }.thenBy { it.confidence })

        val puzzle = PracticePuzzle(
            ply = a.ply,
            moveNumber = a.moveNumber,
            sideToMove = a.color,
            fenBefore = a.fenBefore,
            playedUci = a.uci,
            playedSan = a.san,
            bestUci = bestUci,
            bestSan = a.bestMoveSan ?: position.moveToSan(bestMove),
            bestLineSan = a.bestLineSan,
            goal = goalOf(a, missed, isMateInOne),
            loss = a.loss,
            evalSwingCp = evalSwingCp(a),
            hintPiece = bestMove.piece,
            hintSquare = bestMove.from,
            hintMotif = missed?.type,
            acceptedUci = accepted,
            isMateInOne = isMateInOne,
            hasSimulation = a.simulation != null
        )
        return Candidate(puzzle, if (isMiss) max(a.loss, MISS_RANK_FLOOR) else a.loss)
    }

    /**
     * Every uncached move is provably worse than the cached lines' floor, or every legal move is
     * cached. With fewer than two lines nothing is provable.
     */
    private fun cacheDecidesEveryMove(lines: List<CandidateLine>, legalMoveCount: Int): Boolean {
        val k = lines.size
        if (k < 2) return false
        val band = lines.first().winPercent() - ACCEPT_LOSS
        return lines.last().winPercent() < band || legalMoveCount <= k
    }

    private fun goalOf(a: MoveAnnotation, missed: TacticInstance?, isMateInOne: Boolean): PuzzleGoal {
        val mate = a.mateInBefore
        if (mate != null && mate != 0 && ((a.color == Color.WHITE) == (mate > 0))) {
            return PuzzleGoal.MateIn(abs(mate))
        }
        if (isMateInOne) return PuzzleGoal.MateIn(1)
        val swing = missed?.materialSwing ?: return PuzzleGoal.BetterMove
        return when {
            swing >= GOAL_ROOK_CP -> PuzzleGoal.WinRookOrBetter
            swing >= GOAL_PIECE_CP -> PuzzleGoal.WinPiece
            swing >= GOAL_MATERIAL_CP -> PuzzleGoal.WinMaterial
            else -> PuzzleGoal.BetterMove
        }
    }

    private fun evalSwingCp(a: MoveAnnotation): Int? {
        if (a.mateInBefore != null || a.mateInAfter != null) return null
        val sign = if (a.color == Color.WHITE) 1 else -1
        return max(0, sign * a.evalBeforeCp - sign * a.evalAfterCp)
    }
}

/** The answer to one attempt. */
sealed interface Verdict {
    object Correct : Verdict

    /** The user tapped the move they actually played. */
    data class PlayedInGame(val loss: Double, val evalSwingCp: Int?) : Verdict

    object Wrong : Verdict
}

object PracticeJudge {
    /**
     * @param attemptUci the attempted move in UCI, with a promotion letter when it promotes
     *   (the UI auto-queens).
     */
    fun judge(puzzle: PracticePuzzle, attemptUci: String): Verdict {
        if (attemptUci in puzzle.acceptedUci) return Verdict.Correct
        if (puzzle.isMateInOne && deliversMate(puzzle.fenBefore, attemptUci)) return Verdict.Correct
        if (attemptUci == puzzle.playedUci) return Verdict.PlayedInGame(puzzle.loss, puzzle.evalSwingCp)
        return Verdict.Wrong
    }

    private fun deliversMate(fenBefore: String, uci: String): Boolean = try {
        val position = Position.fromFen(fenBefore)
        position.makeMove(position.parseUci(uci)).isCheckmate()
    } catch (e: Exception) {
        false
    }
}

/** Win percent of the mover for this cached line (mate = 100 / 0, per spec §1.1). */
internal fun CandidateLine.winPercent(): Double =
    WinProbability.winPercentOfLine(EngineLineInput(multiPv, scoreCp, mateIn, depth = 0, pvUci = emptyList()))
