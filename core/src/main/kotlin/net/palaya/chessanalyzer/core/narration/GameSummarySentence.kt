package net.palaya.chessanalyzer.core.narration

import net.palaya.chessanalyzer.core.analysis.GameReport
import net.palaya.chessanalyzer.core.analysis.MoveAnnotation
import net.palaya.chessanalyzer.core.analysis.MoveClassification
import net.palaya.chessanalyzer.core.analysis.RatingEstimator
import net.palaya.chessanalyzer.core.chess.Color

/**
 * The one sentence a review opens with: how the game unfolded, from the report alone.
 * ANALYSIS_SPEC §12 is the authoritative statement of the rules; every threshold is a named
 * constant below, mirrored in the spec.
 *
 * It states only what the report holds: the result tag, the length of the game, the full-move
 * number of the turning move and which side made it, and the class of that move. It never states
 * an evaluation, a percentage or a move the report does not contain, and it never contradicts the
 * result (a mate is claimed only when the last move is mate *and* the result tag agrees about who
 * won).
 *
 * **Side framing.** With the user's side known the subject reads "you" and the other side "your
 * opponent"; with no side chosen, or "Not me", both are named by colour. People are never
 * gendered here: the viewer's [Gender] rides on their [Subject] for a language that inflects on
 * it, the opponent's is always unspecified.
 */
object GameSummarySentence {

    /** A move that lost at least this much win-percent is a big error (the §2 BLUNDER line). */
    const val BIG_SWING = 20.0

    /** The mover had at least this win-percent before the turning move: "fine until move N". */
    const val FINE_FLOOR = 40.0

    /** The mover had at least this win-percent before the turning move: the other side was behind. */
    const val COMEBACK_LEAD = 65.0

    /** Fewer plies than this is a short game (the §4 low-confidence length). */
    const val SHORT_GAME_PLIES = RatingEstimator.LOW_CONFIDENCE_PLY_THRESHOLD

    /** White's win-percent stayed within this band the whole game: a close game. */
    const val CLOSE_LOW = 30.0
    const val CLOSE_HIGH = 70.0

    /**
     * The summary facts for [report], or null when the report has no moves.
     *
     * @param userColor the side the user played, or null when not known.
     * @param notMe the user said they did not play: colours are used even when [userColor] is set.
     * @param viewerGender grammatical gender of the viewer for languages that inflect on it.
     */
    fun build(
        report: GameReport,
        userColor: Color?,
        notMe: Boolean = false,
        viewerGender: Gender = Gender.UNSPECIFIED
    ): Sentence.GameSummary? {
        val moves = report.annotations
        if (moves.isEmpty()) return null

        val viewer = userColor.takeUnless { notMe }
        val known = viewer != null
        fun subject(color: Color) =
            if (color == viewer) Subject(color, Person.SECOND, viewerGender) else Subject(color, Person.THIRD)

        val fullMoves = (moves.size + 1) / 2
        val result = report.result.trim()
        val winner: Color? = when (result) {
            "1-0" -> Color.WHITE
            "0-1" -> Color.BLACK
            else -> null
        }
        val draw = result == "1/2-1/2" || result == "½-½"
        if (winner == null && !draw) {
            return Sentence.GameSummary(SummaryKind.UNFINISHED, fullMoves = fullMoves)
        }

        val last = moves.last()
        val byMate = winner != null && last.san.endsWith("#") && last.color == winner

        if (moves.size < SHORT_GAME_PLIES) {
            return if (winner != null) {
                Sentence.GameSummary(
                    SummaryKind.SHORT_GAME, subject(winner), subject(winner.opposite()), known,
                    fullMoves = fullMoves, byMate = byMate
                )
            } else {
                Sentence.GameSummary(SummaryKind.SHORT_GAME, viewerKnown = known, fullMoves = fullMoves)
            }
        }

        val turning = turningPoint(moves)
        val big = turning != null && turning.loss >= BIG_SWING

        if (winner != null) {
            val loser = winner.opposite()
            if (turning == null || !big) {
                return if (!byMate && isClose(report)) {
                    Sentence.GameSummary(SummaryKind.CLOSE_CLEAN, viewerKnown = known)
                } else {
                    Sentence.GameSummary(
                        SummaryKind.CLEAN_WIN, subject(winner), subject(loser), known,
                        fullMoves = if (byMate) fullMoves else null, byMate = byMate
                    )
                }
            }
            val error = errorOf(turning)
            if (turning.color == winner) {
                return Sentence.GameSummary(
                    SummaryKind.WON_DESPITE_ERROR, subject(winner), subject(loser), known,
                    moveNumber = turning.moveNumber, error = error
                )
            }
            val kind = when {
                turning.winPercentBefore >= COMEBACK_LEAD -> SummaryKind.COMEBACK
                turning.winPercentBefore >= FINE_FLOOR -> SummaryKind.DECIDED_BY_ERROR
                else -> SummaryKind.SEALED_BY_ERROR
            }
            // A comeback is about the winner (who was behind); the other two are about the loser.
            val (about, other) = if (kind == SummaryKind.COMEBACK) winner to loser else loser to winner
            return Sentence.GameSummary(
                kind, subject(about), subject(other), known, moveNumber = turning.moveNumber, error = error
            )
        }

        // A draw.
        if (turning == null || !big) return Sentence.GameSummary(SummaryKind.CLOSE_CLEAN, viewerKnown = known)
        return Sentence.GameSummary(
            SummaryKind.DRAW_WITH_SWING, subject(turning.color), subject(turning.color.opposite()), known,
            moveNumber = turning.moveNumber, error = errorOf(turning)
        )
    }

    /** [build] rendered in [strings]' language, or null when there is nothing to say. */
    fun text(
        report: GameReport,
        userColor: Color?,
        notMe: Boolean = false,
        strings: NarrationStrings = NarrationLocales.default,
        viewerGender: Gender = Gender.UNSPECIFIED
    ): String? = build(report, userColor, notMe, viewerGender)?.let { strings.render(it, NarrationStyle.COACH).first() }

    /**
     * The move that lost the most win-percent. Book and forced moves are never it (theory is not a
     * decision, a forced move is not a choice), a move must lose more than half a percent to count,
     * and a tie goes to the **later** move.
     */
    internal fun turningPoint(moves: List<MoveAnnotation>): MoveAnnotation? = moves
        .filter { it.loss > 0.5 && it.classification != MoveClassification.BOOK && it.classification != MoveClassification.FORCED }
        .maxWithOrNull(compareBy({ it.loss }, { it.ply }))

    private fun errorOf(move: MoveAnnotation): SummaryError = when (move.classification) {
        MoveClassification.BLUNDER -> SummaryError.BLUNDER
        MoveClassification.MISTAKE -> SummaryError.MISTAKE
        MoveClassification.MISS -> SummaryError.MISSED_WIN
        else -> SummaryError.BIG_SWING
    }

    /** White's win-percent never left [CLOSE_LOW]..[CLOSE_HIGH] at any position of the game. */
    private fun isClose(report: GameReport): Boolean {
        val graph = report.evalGraph
        if (graph.isEmpty()) return false
        return graph.all { it in CLOSE_LOW..CLOSE_HIGH }
    }
}
