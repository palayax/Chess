package net.palaya.chessanalyzer.core.analysis

import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Move
import net.palaya.chessanalyzer.core.chess.PieceType
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.Square
import net.palaya.chessanalyzer.core.chess.moveToSan
import net.palaya.chessanalyzer.core.chess.parseUci
import net.palaya.chessanalyzer.core.text.EnglishGrammar

/**
 * Deterministic, template-driven, offline commentary generation — ANALYSIS_SPEC.md section 7.
 * No LLM at runtime.
 *
 * **Every claim is verified before it is said** (ANALYSIS_SPEC §7.2). A card is shown to the user
 * as fact, so a sentence is only produced when the position (or the engine's own numbers) proves
 * it, and a sentence that cannot be made reliably true is **dropped, not hedged**. Concretely:
 *
 *  - A motif is described *for the side that owns it*. What the played move did comes from the
 *    mover's own motifs on that very move; what a better move would have done comes from the best
 *    move's motifs and is attributed to that move ("Better was c6, which attacks the bishop on
 *    b5"), never to the played one; what the opponent now has comes from the opponent's motifs,
 *    attributed to the opponent's reply ("This lets White play Nxg7+, which starts a forced
 *    mate"). A motif whose [TacticInstance.byColor] is not the side the sentence is about, or whose
 *    move is not the move the sentence is about, is discarded.
 *  - A verb says no more than the board shows. A move that merely *attacks* a loose piece
 *    "attacks" it - the opponent moves next and may save it - and only a capture that the exchange
 *    evaluator proves profitable (and that is not just taking back) "wins" anything. Pins, forks,
 *    skewers and discoveries are re-checked geometrically on the position after the move; a motif
 *    that does not survive that check is not mentioned.
 *  - A motif the detector proved by replaying the engine's line (deflection, clearance, ...) is
 *    stated as the engine's line ("In the engine's line, ..."), not as something the move
 *    certainly does: the opponent may reply differently.
 *  - "Allowed" is a charge, and is only made against a move that cost something: INACCURACY,
 *    MISTAKE or BLUNDER. A move the engine chose, or that was rated great or brilliant, never
 *    "allows" anything, and a move after which the opponent has a forced mate is not credited with
 *    motifs from the line in which it gets mated.
 *  - A sacrifice is called one only when the opponent really can take a piece of the mover's for a
 *    net gain.
 *
 * **The text never opens with the classification's own name.** Every surface that shows this text
 * (the board's comment card, the key-moment cards) already shows the classification as a badge and
 * a label, so "Blunder. This drops the queen…" said the same word twice. For the classes that have a
 * better move, the **last** sentence is "Better was X" (once; the app hides its own structured line
 * when the text already holds it). Names that are generated from data go through [EnglishGrammar]
 * so "a"/"an" agree.
 *
 * **Who it is about.** The viewer is "you", the other side "your opponent", and with no side
 * chosen (or "Not me") both are named by colour. The text carries no side-specific wording of its
 * own beyond that, so it can be written again for a different [userColor] from the stored
 * annotation alone: see [generate] taking a [MoveAnnotation], and [regenerate].
 */
class CommentaryGenerator {

    /**
     * @param moveSan the played move in SAN (as recorded in the PGN, may include +/#).
     * @param move the played move (piece/color/to-square).
     * @param positionBefore the position before [move].
     * @param positionAfter the position after [move].
     * @param loss win-percent lost by this move.
     * @param bestMoveSan the engine's top move, in SAN, if known.
     * @param mateInBefore forced mate distance (mover-perspective, positive = mover mates)
     *   available before the move, if any — used for the MISS + mate wording.
     * @param tacticsFound motifs the played move executed (the raw detector output for the move).
     * @param tacticsMissed motifs the engine's best move would have executed instead.
     * @param threatsAllowed motifs this move hands to the opponent on the next ply.
     * @param userColor the viewer's side. The side that benefits from a threat is "you" when it is
     *   the viewer, "your opponent" when it is not, and a colour when this is null.
     * @param mateInAfter forced mate distance (mover-perspective: negative = the **opponent** mates)
     *   after the move, if the engine saw one.
     * @param previousMove the move the opponent just played, when known: a capture that merely takes
     *   back on the square the opponent just took on is not described as winning material.
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
        userColor: Color? = null,
        mateInAfter: Int? = null,
        previousMove: Move? = null
    ): String {
        val ctx = Ctx(positionBefore, move, positionAfter, userColor, previousMove)
        val opponentMates = mateInAfter != null && mateInAfter < 0
        return when (classification) {
            MoveClassification.FORCED -> "$moveSan was the only legal move."
            MoveClassification.BOOK -> "$moveSan follows known opening theory."
            MoveClassification.BRILLIANT ->
                joinSentences(brilliantLead(ctx, moveSan), foundSentence(ctx, tacticsFound, opponentMates))
            MoveClassification.GREAT -> joinSentences(
                "$moveSan was the only move that kept things on track.",
                foundSentence(ctx, tacticsFound, opponentMates)
            )
            MoveClassification.BEST -> joinSentences(
                "$moveSan matches the engine's top choice.", foundSentence(ctx, tacticsFound, opponentMates)
            )
            MoveClassification.EXCELLENT -> joinSentences(
                "$moveSan is very close to the best move.", foundSentence(ctx, tacticsFound, opponentMates)
            )
            MoveClassification.GOOD -> joinSentences(
                "$moveSan is a sound move.", foundSentence(ctx, tacticsFound, opponentMates)
            )
            // The classes that have a better move: what went wrong, then "Better was X" last so it
            // is the sentence the reader is left holding.
            MoveClassification.INACCURACY, MoveClassification.MISTAKE, MoveClassification.BLUNDER -> {
                val wrong = allowedSentence(ctx, threatsAllowed, opponentMates) ?: "$moveSan gives back ground."
                joinSentences(wrong, betterSentence(ctx, bestMoveSan, tacticsMissed, mateInBefore))
            }
            MoveClassification.MISS -> missSentence(bestMoveSan, mateInBefore)
        }
    }

    /**
     * The commentary for [annotation] written for [userColor], from the facts the annotation stores
     * (its FENs, the played move, the engine's numbers and the raw motifs) and nothing else - no
     * engine, no detector. [GameAnalyzer] writes the text through this same function, so the text
     * produced at analysis time and the text produced when the user later says which side they were
     * are identical for the same [userColor] by construction.
     *
     * @param previous the annotation of the ply before this one, when there is one.
     */
    fun generate(annotation: MoveAnnotation, userColor: Color?, previous: MoveAnnotation? = null): String {
        val before = Position.fromFen(annotation.fenBefore)
        val move = before.parseUci(annotation.uci)
        val after = before.makeMove(move)
        return generate(
            classification = annotation.classification,
            moveSan = annotation.san,
            move = move,
            positionBefore = before,
            positionAfter = after,
            loss = annotation.loss,
            bestMoveSan = annotation.bestMoveSan,
            mateInBefore = moverRelative(annotation.mateInBefore, annotation.color),
            tacticsFound = annotation.tacticsPlayed.ifEmpty { annotation.tacticsFound },
            tacticsMissed = annotation.tacticsMissed,
            threatsAllowed = annotation.threatsAllowed,
            userColor = userColor,
            mateInAfter = moverRelative(annotation.mateInAfter, annotation.color),
            previousMove = previous?.let { moveOf(it) }
        )
    }

    /**
     * [report] with every annotation's [MoveAnnotation.text] (and the key moments' summaries, which
     * are copies of it) written again for [userColor]. Nothing but wording changes: classifications,
     * evaluations, motifs, simulations and the one-sentence summary's inputs are untouched.
     */
    fun regenerate(report: GameReport, userColor: Color?): GameReport {
        val annotations = report.annotations.mapIndexed { index, a ->
            a.copy(text = safeGenerate(a, userColor, report.annotations.getOrNull(index - 1)))
        }
        val byPly = annotations.associateBy { it.ply }
        return report.copy(
            annotations = annotations,
            keyMoments = report.keyMoments.map { moment ->
                byPly[moment.ply]?.let { moment.copy(summary = it.text) } ?: moment
            }
        )
    }

    private fun safeGenerate(annotation: MoveAnnotation, userColor: Color?, previous: MoveAnnotation?): String =
        try {
            generate(annotation, userColor, previous)
        } catch (e: Exception) {
            // An annotation whose FEN or move cannot be replayed keeps the text it had.
            annotation.text
        }

    private fun moveOf(annotation: MoveAnnotation): Move? = try {
        Position.fromFen(annotation.fenBefore).parseUci(annotation.uci)
    } catch (e: Exception) {
        null
    }

    // -----------------------------------------------------------------------
    // Shared context
    // -----------------------------------------------------------------------

    private class Ctx(
        val before: Position,
        val move: Move,
        val after: Position,
        val userColor: Color?,
        val previousMove: Move?
    ) {
        val mover: Color = move.color
        val opponent: Color = mover.opposite()

        /** "you" / "your opponent" / "White" / "Black" for [side], as the viewer sees it. */
        fun who(side: Color): String = when {
            userColor == null -> if (side == Color.WHITE) "White" else "Black"
            side == userColor -> "you"
            else -> "your opponent"
        }
    }

    /** Finished sentences joined by a single space; blanks dropped, so no stray or doubled spaces. */
    private fun joinSentences(vararg sentences: String?): String =
        sentences.filterNotNull().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

    private fun moverRelative(whiteRelative: Int?, mover: Color): Int? =
        whiteRelative?.let { if (mover == Color.WHITE) it else -it }

    // -----------------------------------------------------------------------
    // A BRILLIANT move
    // -----------------------------------------------------------------------

    /**
     * A BRILLIANT move is called a sacrifice only when one is on the board: the opponent can take a
     * piece of the mover's for a net gain of at least [SACRIFICE_MIN_CP] by exchange evaluation. A
     * piece merely left open to an even trade is described as that; and a move with neither is
     * described by what the classifier actually proved (best or near-best), not by a sacrifice that
     * is not there.
     */
    private fun brilliantLead(ctx: Ctx, moveSan: String): String {
        val offer = bestOffer(ctx)
        return when {
            offer != null && offer.gain >= SACRIFICE_MIN_CP ->
                "$moveSan is a sacrifice: it offers the ${PieceValues.name(offer.type)} on ${offer.square}."
            offer != null ->
                "$moveSan leaves the ${PieceValues.name(offer.type)} on ${offer.square} open to capture, " +
                    "and the engine still rates it among the best moves."
            else -> "$moveSan is among the engine's best moves here."
        }
    }

    private class Offer(val square: Square, val type: PieceType, val gain: Int)

    /**
     * The mover's piece (worth at least a minor piece) that the opponent can capture for the biggest
     * net gain, by [ExchangeEvaluator]; null when no such capture exists or every one loses.
     */
    private fun bestOffer(ctx: Ctx): Offer? {
        var best: Offer? = null
        for (capture in ctx.after.legalMoves()) {
            if (!capture.isCapture || capture.isEnPassant) continue
            val victim = ctx.after.pieceAt(capture.to) ?: continue
            if (victim.color != ctx.mover || victim.type == PieceType.KING) continue
            if (PieceValues.of(victim.type) < MINOR_PIECE_CP) continue
            val gain = ExchangeEvaluator.see(ctx.after, capture)
            if (gain < 0) continue
            if (best == null || gain > best.gain) best = Offer(capture.to, victim.type, gain)
        }
        return best
    }

    // -----------------------------------------------------------------------
    // A motif, verified: what a move does
    // -----------------------------------------------------------------------

    /**
     * What a motif says about one move.
     *
     * @property phrase the verb phrase: "forks the king on g8 and the queen on d5".
     * @property engineLine true when the claim rests on the engine's line being played (deflection,
     *   clearance, ...): it is then said as "In the engine's line, ...".
     * @property value centipawns the claim is worth, to choose between several that are true.
     */
    private class Motif(val phrase: String, val engineLine: Boolean, val value: Int)

    /**
     * What the played move did, from the mover's own motifs on this very move. Only engine-confirmed
     * motifs (confidence at least [CONFIRMED]) or ones that win material ([MATERIAL_SWING_CP]) are
     * worth a card's attention - a static relative pin that wins nothing is not - and each is
     * re-verified on the board. Null when nothing survives, and always null when the opponent has a
     * forced mate after the move: whatever the detector read out of that line, it is the opponent's.
     */
    private fun foundSentence(ctx: Ctx, found: List<TacticInstance>, opponentMates: Boolean): String? {
        if (opponentMates) return null
        val motif = bestMotif(found, ctx.mover, ctx.before, ctx.move, ctx.after, ctx.previousMove, capturesCount = true)
            ?: return null
        return if (motif.engineLine) {
            "In the engine's line, ${ctx.before.moveToSan(ctx.move)} ${motif.phrase}."
        } else {
            "This ${motif.phrase}."
        }
    }

    /**
     * The most valuable true thing to say about [move] (played from [before]) from [tactics], or null.
     * Mating motifs outrank everything; then the larger material claim wins. A capture that the
     * exchange evaluator proves profitable is a candidate when [capturesCount], unless it merely takes
     * back on the square [previous] just captured on.
     */
    private fun bestMotif(
        tactics: List<TacticInstance>,
        side: Color,
        before: Position,
        move: Move,
        after: Position,
        previous: Move?,
        capturesCount: Boolean
    ): Motif? {
        val candidates = ArrayList<Motif>()
        for (tactic in tactics) {
            if (tactic.byColor != side || tactic.moveUci != move.toUci()) continue
            if (tactic.confidence < CONFIRMED && tactic.materialSwing < MATERIAL_SWING_CP) continue
            motifOf(tactic, before, move, after)?.let { candidates.add(it) }
        }
        // Only a capture of a piece is claimed: a pawn taken may simply be one that was lost a move or
        // two earlier ("dxe5 ... wins a pawn" after 4.dxe5), and a recapture is the second half of a
        // trade, which wins nothing by itself.
        val takesPiece = !move.isEnPassant && before.pieceAt(move.to)?.let { PieceValues.of(it.type) >= MINOR_PIECE_CP } == true
        if (capturesCount && move.isCapture && takesPiece) {
            val recapture = previous != null && previous.isCapture && previous.to == move.to
            if (!recapture) {
                val gain = ExchangeEvaluator.see(before, move)
                ExchangeEvaluator.describeGain(gain)?.let { candidates.add(Motif("wins $it", false, gain)) }
            }
        }
        return candidates.maxByOrNull { it.value }
    }

    // -----------------------------------------------------------------------
    // What went wrong, and what was better
    // -----------------------------------------------------------------------

    /**
     * The charge: the opponent's best reply now wins or mates. Only for a move that already cost
     * something (the caller guarantees an error class), and only from the opponent's own motifs on
     * the opponent's own reply, each verified on the board after that reply. A forced mate against
     * the mover is stated from the engine's number alone when no motif names the reply.
     */
    private fun allowedSentence(ctx: Ctx, threats: List<TacticInstance>, opponentMates: Boolean): String? {
        val worthSaying = threats.filter {
            it.byColor == ctx.opponent && (it.type in MATING_TYPES || it.materialSwing >= MATERIAL_SWING_CP)
        }
        // With a forced mate against the mover, only the mating reply is the charge.
        val pool = if (opponentMates) worthSaying.filter { it.type in MATING_TYPES } else worthSaying
        val byReply = pool.groupBy { it.moveUci }.entries.sortedByDescending { entry ->
            entry.value.maxOf { if (it.type in MATING_TYPES) MATE_VALUE else it.materialSwing }
        }
        for ((uci, tactics) in byReply) {
            val reply = try {
                ctx.after.parseUci(uci)
            } catch (e: Exception) {
                continue
            }
            if (reply.color != ctx.opponent) continue
            val afterReply = ctx.after.makeMove(reply)
            val motif = bestMotif(tactics, ctx.opponent, ctx.after, reply, afterReply, ctx.move, capturesCount = true)
                ?: continue
            val replySan = ctx.after.moveToSan(reply)
            val lead = "This lets ${ctx.who(ctx.opponent)} play $replySan"
            return if (motif.engineLine) "$lead; in the engine's line it ${motif.phrase}." else "$lead, which ${motif.phrase}."
        }
        return if (opponentMates) "This allows a forced mate." else null
    }

    /**
     * "Better was X." with, when it can be proved, what X does: it mates, it wins material by the
     * exchange evaluator, or it carries one of the best move's own verified motifs. The motifs are
     * attributed to the *best move*, not to the played one.
     */
    private fun betterSentence(
        ctx: Ctx,
        bestMoveSan: String?,
        missed: List<TacticInstance>,
        mateInBefore: Int?
    ): String? {
        val best = bestMoveSan ?: return null
        val motif = betterMotif(ctx, best, missed, mateInBefore) ?: return "Better was $best."
        return if (motif.engineLine) "Better was $best; in the engine's line it ${motif.phrase}."
        else "Better was $best, which ${motif.phrase}."
    }

    private fun betterMotif(
        ctx: Ctx,
        bestMoveSan: String,
        missed: List<TacticInstance>,
        mateInBefore: Int?
    ): Motif? {
        // The motifs belong to the best move only if they name it.
        for ((uci, tactics) in missed.filter { it.byColor == ctx.mover }.groupBy { it.moveUci }) {
            val bestMove = try {
                ctx.before.parseUci(uci)
            } catch (e: Exception) {
                continue
            }
            if (bestMove.color != ctx.mover) continue
            if (!sameMove(ctx.before.moveToSan(bestMove), bestMoveSan)) continue
            val afterBest = ctx.before.makeMove(bestMove)
            if (afterBest.isCheckmate()) return Motif("is checkmate", false, MATE_VALUE)
            if (mateInBefore != null && mateInBefore > 0) return Motif("starts a forced mate", false, MATE_VALUE)
            return bestMotif(tactics, ctx.mover, ctx.before, bestMove, afterBest, ctx.previousMove, capturesCount = true)
        }
        return null
    }

    private fun sameMove(a: String, b: String): Boolean = a.trimEnd('+', '#') == b.trimEnd('+', '#')

    /**
     * The missed win: one sentence that carries the better move itself, so the card does not state it
     * a second time and it is the last thing said.
     */
    private fun missSentence(bestMoveSan: String?, mateInBefore: Int?): String {
        val mate = mateInBefore?.takeIf { it > 0 }
        return when {
            bestMoveSan != null && mate != null -> "Better was $bestMoveSan, forcing mate in $mate."
            bestMoveSan != null -> "Better was $bestMoveSan, keeping a decisive advantage."
            mate != null -> "A forced mate in $mate was on the board."
            else -> "A decisive advantage was on the board."
        }
    }

    // -----------------------------------------------------------------------
    // Motif phrases, each verified on the board
    // -----------------------------------------------------------------------

    /**
     * The claim [tactic] makes about [move] (played from [before] to [after] by the motif's own
     * side), or null when the board does not bear it out or the motif has no phrase that can be made
     * reliably true.
     */
    private fun motifOf(tactic: TacticInstance, before: Position, move: Move, after: Position): Motif? {
        if (tactic.moveUci != move.toUci() || tactic.byColor != move.color) return null
        val us = move.color
        val them = us.opposite()
        val landing = move.to
        fun theirs(square: Square) = after.pieceAt(square)?.takeIf { it.color == them }
        fun nameOf(square: Square): String? =
            theirs(square)?.let { "the ${PieceValues.name(it.type)} on $square" }
        fun plain(phrase: String?, value: Int = tactic.materialSwing): Motif? =
            phrase?.let { Motif(it, false, value) }

        return when (tactic.type) {
            // Engine-confirmed: the detector gives a mate motif confidence 0.95 only when the move
            // mates or the engine's line ends in mate.
            TacticType.MATE_NET -> when {
                after.isCheckmate() -> plain("is checkmate", MATE_VALUE)
                tactic.confidence >= CONFIRMED -> plain("starts a forced mate", MATE_VALUE)
                else -> null
            }
            TacticType.BACK_RANK_MATE -> {
                val king = after.kingSquare(them)
                if (after.isCheckmate() && (king.rank == 0 || king.rank == 7)) plain("is a back-rank mate", MATE_VALUE) else null
            }
            TacticType.SMOTHERED_MATE ->
                if (isSmotheredMate(after, them, us)) plain("is a smothered mate", MATE_VALUE) else null

            TacticType.FORK, TacticType.PAWN_FORK -> {
                val hit = tactic.targetSquares.filter { theirs(it) != null && BoardFacts.attacks(after, landing, it) }
                if (hit.size >= 2) plain("forks ${list(hit.mapNotNull(::nameOf))}") else null
            }
            TacticType.DOUBLE_ATTACK -> {
                val hit = tactic.targetSquares.filter {
                    theirs(it) != null && BoardFacts.attackers(after, it, us).isNotEmpty()
                }
                if (hit.size >= 2) plain("attacks ${list(hit.mapNotNull(::nameOf))} at once") else null
            }
            TacticType.PIN_ABSOLUTE, TacticType.PIN_RELATIVE -> {
                val slider = tactic.involvedSquares.getOrNull(0)
                val rear = tactic.involvedSquares.getOrNull(1)
                val front = tactic.targetSquares.firstOrNull()
                if (slider != null && rear != null && front != null && isLine(after, us, slider, front, rear)) {
                    plain("pins ${nameOf(front)} to ${nameOf(rear)}")
                } else null
            }
            TacticType.SKEWER -> {
                val slider = tactic.involvedSquares.getOrNull(0)
                val front = tactic.targetSquares.getOrNull(0)
                val rear = tactic.targetSquares.getOrNull(1)
                if (slider != null && front != null && rear != null && isLine(after, us, slider, front, rear)) {
                    plain("skewers ${nameOf(front)}, with ${nameOf(rear)} behind it")
                } else null
            }
            TacticType.DISCOVERED_ATTACK -> {
                val attacker = tactic.involvedSquares.getOrNull(0)
                val target = tactic.targetSquares.firstOrNull()
                val piece = attacker?.let { after.pieceAt(it) }
                if (attacker != null && target != null && attacker != landing && piece?.color == us &&
                    theirs(target) != null && BoardFacts.attacks(after, attacker, target) &&
                    !BoardFacts.attacks(before, attacker, target)
                ) {
                    plain("uncovers the ${PieceValues.name(piece.type)} on $attacker, which now attacks ${nameOf(target)}")
                } else null
            }
            TacticType.DISCOVERED_CHECK -> {
                val attacker = tactic.involvedSquares.getOrNull(0)
                val piece = attacker?.let { after.pieceAt(it) }
                if (attacker != null && attacker != landing && piece?.color == us && after.isInCheck(them) &&
                    BoardFacts.attacks(after, attacker, after.kingSquare(them))
                ) plain("gives check by uncovering the ${PieceValues.name(piece.type)} on $attacker") else null
            }
            TacticType.DOUBLE_CHECK ->
                if (BoardFacts.attackers(after, after.kingSquare(them), us).size >= 2) plain("gives double check") else null
            TacticType.HANGING_PIECE -> plain(hangingPhrase(tactic, before, after, us, landing))
            TacticType.TRAPPED_PIECE -> {
                val target = tactic.targetSquares.firstOrNull()
                val piece = target?.let { theirs(it) }
                if (target != null && piece != null && piece.type != PieceType.KING &&
                    PieceValues.of(piece.type) >= MINOR_PIECE_CP && !after.isInCheck() &&
                    hasNoSafeSquare(after, target)
                ) plain("leaves the ${PieceValues.name(piece.type)} on $target with no safe square") else null
            }
            TacticType.PROMOTION_TACTIC ->
                move.promotion?.let { plain("promotes the pawn to ${withArticle(PieceValues.name(it))}") }
            TacticType.UNDERPROMOTION ->
                move.promotion?.takeIf { it != PieceType.QUEEN }
                    ?.let { plain("underpromotes to ${withArticle(PieceValues.name(it))}") }

            // The motifs the engine's own line proves (deflection, decoy, clearance, ...): the
            // detector's description is built from that line and opens with the move's SAN, so it is
            // used as it stands, but only when it does open with this move - otherwise it is about
            // some other move and is not ours to say.
            TacticType.DEFLECTION, TacticType.REMOVING_THE_DEFENDER, TacticType.DECOY,
            TacticType.INTERFERENCE, TacticType.CLEARANCE, TacticType.GREEK_GIFT, TacticType.WINDMILL ->
                if (tactic.confidence >= CONFIRMED) engineLineMotif(tactic, before, move) else null

            // No sentence about these can be made reliably true from the position alone.
            else -> null
        }
    }

    private fun engineLineMotif(tactic: TacticInstance, before: Position, move: Move): Motif? {
        val san = before.moveToSan(move)
        val description = tactic.description.trim().trimEnd('.')
        if (!description.startsWith("$san ")) return null
        val rest = description.removePrefix("$san ").takeIf { it.isNotBlank() } ?: return null
        return Motif(rest, true, tactic.materialSwing)
    }

    /**
     * "attacks the undefended bishop on b5", "attacks the queen on g5 with a pawn", "attacks the pawn
     * on g4 more often than it is defended": what the board shows about a piece that the detector
     * called hanging. Never "wins" - the opponent moves next. Only what the move created: if the
     * same phrase was already true before the move, it was not this move that did it.
     */
    private fun hangingPhrase(tactic: TacticInstance, before: Position, after: Position, us: Color, landing: Square): String? {
        val target = tactic.targetSquares.firstOrNull() ?: return null
        val now = looseness(target, after, us, landing) ?: return null
        return if (looseness(target, before, us, landing) == now) null else now
    }

    /**
     * What the board says about the piece on [target], or null when nothing is loose. When the moved
     * piece (on [landing]) is itself one of the attackers it "attacks"; otherwise the move did it by
     * uncovering or by removing a defender, and the sentence says what is attacking instead of
     * crediting the moved piece with an attack it does not make.
     */
    private fun looseness(target: Square, position: Position, us: Color, landing: Square): String? {
        val victim = position.pieceAt(target)?.takeIf { it.color != us && it.type != PieceType.KING } ?: return null
        val attackers = BoardFacts.attackers(position, target, us)
        if (attackers.isEmpty()) return null
        val defenders = BoardFacts.defenders(position, target)
        val name = PieceValues.name(victim.type)
        val cheapestSquare = attackers.first()
        val cheapest = position.pieceAt(cheapestSquare)!!.type
        val direct = landing in attackers
        return when {
            defenders.isEmpty() ->
                if (direct) "attacks the undefended $name on $target"
                else "leaves the $name on $target undefended, with ${theArticle(position, cheapestSquare)} attacking it"
            PieceValues.of(cheapest) < PieceValues.of(victim.type) ->
                if (direct) "attacks the $name on $target with ${withArticle(PieceValues.name(cheapest))}"
                else "leaves the $name on $target attacked by ${withArticle(PieceValues.name(cheapest))}"
            attackers.size > defenders.size ->
                if (direct) "attacks the $name on $target more often than it is defended"
                else "leaves the $name on $target attacked more often than it is defended"
            else -> null
        }
    }

    private fun theArticle(position: Position, square: Square): String =
        "the ${PieceValues.name(position.pieceAt(square)!!.type)} on $square"

    /** Every legal move of the piece on [square] loses material by exchange evaluation. */
    private fun hasNoSafeSquare(position: Position, square: Square): Boolean {
        val escapes = position.legalMoves().filter { it.from == square }
        return escapes.isNotEmpty() && escapes.all { ExchangeEvaluator.see(position, it) < 0 }
    }

    /** A smothered mate: a lone knight checks a king whose every neighbour holds its own piece. */
    private fun isSmotheredMate(after: Position, them: Color, us: Color): Boolean {
        if (!after.isCheckmate()) return false
        val king = after.kingSquare(them)
        val checkers = BoardFacts.attackers(after, king, us)
        if (checkers.size != 1 || after.pieceAt(checkers[0])?.type != PieceType.KNIGHT) return false
        return BoardFacts.neighbours(king).all { after.pieceAt(it)?.color == them }
    }

    /** [slider] (ours) attacks [front] and, through it, [rear], both the opponent's, on one line. */
    private fun isLine(after: Position, us: Color, slider: Square, front: Square, rear: Square): Boolean {
        val s = after.pieceAt(slider) ?: return false
        val f = after.pieceAt(front) ?: return false
        val r = after.pieceAt(rear) ?: return false
        if (s.color != us || f.color == us || r.color == us) return false
        if (s.type == PieceType.PAWN || s.type == PieceType.KNIGHT || s.type == PieceType.KING) return false
        if (!BoardFacts.attacks(after, slider, front)) return false
        // One straight line: the step from slider to front is the step from front to rear.
        if (Integer.signum(front.file - slider.file) != Integer.signum(rear.file - front.file)) return false
        if (Integer.signum(front.rank - slider.rank) != Integer.signum(rear.rank - front.rank)) return false
        return BoardFacts.aligned(front, rear) && BoardFacts.clearBetween(after, front, rear)
    }

    private fun list(parts: List<String>): String = when (parts.size) {
        0 -> ""
        1 -> parts[0]
        else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
    }

    private fun withArticle(name: String): String = EnglishGrammar.withArticle(name)

    companion object {
        /** `Contract.kt` / spec §5.3: the PV confirmed the follow-up. */
        const val CONFIRMED = 0.95

        /** A motif worth a sentence if it wins at least a pawn. */
        const val MATERIAL_SWING_CP = 100

        /** The classifier's own sacrifice line (spec §2 BRILLIANT: `see <= -200`). */
        const val SACRIFICE_MIN_CP = 200

        private const val MINOR_PIECE_CP = 300

        /** A mate outranks any material claim. */
        private const val MATE_VALUE = 1_000_000

        /** The motifs that describe a mate; they outrank everything else on a move (spec §5.3 "Reporting"). */
        private val MATING_TYPES = setOf(
            TacticType.MATE_NET, TacticType.BACK_RANK_MATE, TacticType.SMOTHERED_MATE
        )
    }
}
