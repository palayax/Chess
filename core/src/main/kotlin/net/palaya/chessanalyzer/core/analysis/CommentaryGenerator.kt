package net.palaya.chessanalyzer.core.analysis

import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Move
import net.palaya.chessanalyzer.core.chess.Position

/**
 * Deterministic, template-driven, offline commentary generation — ANALYSIS_SPEC.md section 7.
 * No LLM at runtime; every sentence is built from the classification, the highest-confidence
 * tactic, and concrete squares/pieces read off the position.
 *
 * Second person ("You allowed…") is reserved for the configured user's own plies. The opponent's
 * plies are described in the third person by colour ("Black allowed…"), and when no user colour
 * is configured both sides are named by colour.
 */
class CommentaryGenerator {

    private val positiveClassifications = setOf(
        MoveClassification.BRILLIANT, MoveClassification.GREAT, MoveClassification.BEST,
        MoveClassification.EXCELLENT, MoveClassification.GOOD, MoveClassification.BOOK
    )

    /**
     * @param moveSan the played move in SAN (as recorded in the PGN, may include +/#).
     * @param move the played move (piece/color/to-square).
     * @param positionBefore the position before [move].
     * @param positionAfter the position after [move].
     * @param loss win-percent lost by this move.
     * @param bestMoveSan the engine's top move, in SAN, if known.
     * @param mateInBefore forced mate distance (mover-perspective, positive = mover mates)
     *   available before the move, if any — used for the MISS + mate wording.
     * @param tacticsFound motifs the played move actually executed.
     * @param tacticsMissed motifs the engine's best move would have executed instead.
     * @param threatsAllowed motifs this move hands to the opponent on the next ply.
     * @param userColor the colour the report is written for. The mover is addressed as "you"
     *   only when `move.color == userColor`; otherwise, and when this is null, sides are named
     *   by colour.
     */
    fun generate(
        classification: MoveClassification,
        moveSan: String,
        move: Move,
        positionBefore: Position,
        positionAfter: Position,
        loss: Double,
        bestMoveSan: String?,
        mateInBefore: Int?,
        tacticsFound: List<TacticInstance>,
        tacticsMissed: List<TacticInstance>,
        threatsAllowed: List<TacticInstance>,
        userColor: Color? = null
    ): String {
        val voice = Voice(mover = move.color, userColor = userColor)
        val negativePool = (tacticsMissed + threatsAllowed)
            .sortedWith(compareByDescending<TacticInstance> { it.confidence }.thenByDescending { it.materialSwing })
        val positivePool = tacticsFound
            .sortedWith(compareByDescending<TacticInstance> { it.confidence }.thenByDescending { it.materialSwing })

        val isPositive = classification in positiveClassifications
        val primary = if (isPositive) positivePool.firstOrNull() else negativePool.firstOrNull()

        val body = when (classification) {
            MoveClassification.FORCED -> "Forced. $moveSan was the only legal move."
            MoveClassification.BOOK -> "Book move. $moveSan follows known opening theory."
            MoveClassification.BRILLIANT -> brilliantText(moveSan, positionAfter, primary)
            MoveClassification.GREAT -> "Great move! $moveSan was the only move that kept the position." +
                tacticClauseOrEmpty(primary, positionAfter, found = true)
            MoveClassification.BEST -> "Best move. $moveSan matches the engine's top choice." +
                tacticClauseOrEmpty(primary, positionAfter, found = true)
            MoveClassification.EXCELLENT -> "Excellent. $moveSan is very close to the best move." +
                tacticClauseOrEmpty(primary, positionAfter, found = true)
            MoveClassification.GOOD -> "Good move." + tacticClauseOrEmpty(primary, positionAfter, found = true)
            MoveClassification.INACCURACY -> "Inaccuracy. " + negativeBody(moveSan, positionBefore, positionAfter, primary, bestMoveSan)
            MoveClassification.MISTAKE -> "Mistake. " + negativeBody(moveSan, positionBefore, positionAfter, primary, bestMoveSan)
            MoveClassification.MISS -> missText(bestMoveSan, mateInBefore)
            MoveClassification.BLUNDER -> "Blunder. " + negativeBody(moveSan, positionBefore, positionAfter, primary, bestMoveSan)
        }

        val threatSuffix =
            if (primary == null || primary !in threatsAllowed) threatClauseOrEmpty(threatsAllowed, primary, voice) else ""
        return (body + threatSuffix).trim()
    }

    /**
     * Who the sentence is about. The mover is "you" only when the mover is the configured user;
     * everyone else — including both sides when no user colour is configured — is named by colour.
     */
    private class Voice(val mover: Color, val userColor: Color?) {

        val isUser: Boolean get() = userColor != null && mover == userColor

        /** Subject for the side that played the move: "You" / "White" / "Black". */
        fun subject(): String = if (isUser) "You" else colorName(mover)

        private fun colorName(color: Color): String = if (color == Color.WHITE) "White" else "Black"
    }

    private fun missText(bestMoveSan: String?, mateInBefore: Int?): String {
        val move = bestMoveSan ?: "The winning move"
        return if (mateInBefore != null && mateInBefore > 0) {
            "Missed win. $move forced mate in $mateInBefore."
        } else {
            "Missed win. $move kept a decisive advantage."
        }
    }

    private fun brilliantText(moveSan: String, positionAfter: Position, primary: TacticInstance?): String {
        val base = "Brilliant! $moveSan is a stunning sacrifice."
        if (primary == null) return "$base The point becomes clear a few moves later."
        val clause = describeTactic(primary, positionAfter, found = true)
        return if (clause.isNotBlank()) "$base $clause" else "$base The point becomes clear a few moves later."
    }

    private fun negativeBody(
        moveSan: String,
        positionBefore: Position,
        positionAfter: Position,
        primary: TacticInstance?,
        bestMoveSan: String?
    ): String {
        val tacticSentence = if (primary != null) describeTactic(primary, positionAfter, found = false) else ""
        val lossSentence = if (tacticSentence.isNotBlank()) tacticSentence else "$moveSan gives back ground."
        val suggestion = if (bestMoveSan != null) {
            if (primary?.type == TacticType.HANGING_PIECE) {
                " Better was $bestMoveSan, keeping material level."
            } else {
                " Better was $bestMoveSan."
            }
        } else ""
        return (lossSentence + suggestion).trim()
    }

    private fun tacticClauseOrEmpty(primary: TacticInstance?, position: Position, found: Boolean): String {
        if (primary == null) return ""
        val clause = describeTactic(primary, position, found)
        return if (clause.isBlank()) "" else " $clause"
    }

    private fun threatClauseOrEmpty(
        threatsAllowed: List<TacticInstance>,
        alreadyDescribed: TacticInstance?,
        voice: Voice
    ): String {
        val threat = threatsAllowed.filter { it != alreadyDescribed }
            .maxByOrNull { it.confidence } ?: return ""
        val square = threat.targetSquares.firstOrNull()?.toString() ?: threat.involvedSquares.firstOrNull()?.toString()
        val motif = threat.type.displayName.lowercase()
        return if (square != null) {
            " ${voice.subject()} allowed a $motif on $square."
        } else {
            " ${voice.subject()} allowed a $motif."
        }
    }

    /** Builds a concrete, square-naming sentence fragment for [tactic]. */
    private fun describeTactic(tactic: TacticInstance, position: Position, found: Boolean): String {
        val square = tactic.targetSquares.firstOrNull()
        return when (tactic.type) {
            TacticType.HANGING_PIECE -> {
                val sq = square ?: tactic.involvedSquares.firstOrNull()
                val pieceName = sq?.let { position.pieceAt(it)?.type }?.let { PieceValues.name(it) } ?: "piece"
                val sqStr = sq?.toString() ?: "the board"
                if (found) "This wins the $pieceName on $sqStr." else "This drops the $pieceName on $sqStr."
            }
            TacticType.FORK, TacticType.PAWN_FORK -> {
                val targets = tactic.targetSquares.joinToString(" and ") { it.toString() }
                if (targets.isBlank()) "This forks two pieces." else "This forks pieces on $targets."
            }
            TacticType.PIN_ABSOLUTE, TacticType.PIN_RELATIVE -> {
                val sq = square?.toString() ?: "the pinned piece"
                "This pins the piece on $sq."
            }
            TacticType.SKEWER -> {
                val sq = square?.toString() ?: "the piece behind it"
                "This skewers the piece on $sq."
            }
            TacticType.DISCOVERED_ATTACK, TacticType.DISCOVERED_CHECK -> "This opens a discovered attack."
            TacticType.DOUBLE_CHECK -> "This delivers a double check."
            TacticType.DECOY -> {
                val decoyed = tactic.involvedSquares.getOrNull(0)?.toString()
                val defended = tactic.involvedSquares.getOrNull(1)?.toString() ?: tactic.targetSquares.firstOrNull()?.toString()
                if (decoyed != null && defended != null) {
                    "It drags the piece on $decoyed away from defending $defended, and mate follows."
                } else {
                    "It lures the defender away, and mate follows."
                }
            }
            TacticType.MATE_NET -> "This forces mate."
            TacticType.TRAPPED_PIECE -> {
                val sq = square?.toString() ?: "the piece"
                "This traps the piece on $sq."
            }
            else -> {
                val sq = square?.toString()
                if (sq != null) "This sets up a ${tactic.type.displayName.lowercase()} on $sq."
                else "This sets up a ${tactic.type.displayName.lowercase()}."
            }
        }
    }
}
