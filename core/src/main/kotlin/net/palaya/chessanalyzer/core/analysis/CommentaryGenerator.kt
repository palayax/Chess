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
 * **Professional vocabulary, each term with its proof (C1, docs/COMMENTARY_STYLE.md).** "Fork", "pawn
 * fork", "absolute pin" (the rear piece is the king), "relative pin", "skewer", "discovered attack",
 * "discovered check", "double check", "en prise" and "loose" (attacked by the mover, nothing defends
 * it), "trapped", "wins the exchange" (a minor piece takes a rook and is taken back,
 * [ExchangeEvaluator.describeCapture]), "zwischenzug" (a forcing move inserted before a capture the
 * engine's line still makes), "overloaded" (one defender, two attacked charges, the engine's line
 * cashes one), "desperado" (a piece lost where it stood captures on the way out), a "back-rank mate"
 * threat (the king boxed in by its own pawns, a heavy piece mates if the opponent could pass), "forced
 * mate in N" (the engine's own mate distance), an "only move" (the MultiPV gap of §2), and the
 * evaluation in words by the bands of §9 ("winning", "clearly better", "slightly better", ...). A term
 * whose proof is not on the board or in the numbers is not used.
 *
 * **Variety is deterministic.** Every template has a few phrasings; which one a card gets is decided
 * by [Variety] from the ply and the template, so the same game always produces the same text, two
 * consecutive cards with the same template never share a phrasing, and the narration cache (keyed by
 * text) and the audits stay reproducible. Tone follows the class: short sentences for a blunder or a
 * mate, a calmer register for an inaccuracy.
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
     * @param winPercentBefore the mover's win-percent before the move (§1), when known: with
     *   [winPercentAfter] it is what the evaluation-in-words sentence of an error is made of.
     * @param winPercentAfter the mover's win-percent after the move, when known.
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
        previousMove: Move? = null,
        winPercentBefore: Double? = null,
        winPercentAfter: Double? = null
    ): String {
        val ctx = Ctx(positionBefore, move, positionAfter, userColor, previousMove, Variety(plyOf(positionBefore)))
        val opponentMates = mateInAfter != null && mateInAfter < 0
        val v = ctx.variety
        return when (classification) {
            MoveClassification.FORCED -> v.pick(
                "forced",
                "$moveSan was the only legal move.",
                "$moveSan was forced: the only legal move.",
                "No choice here: $moveSan was the only legal move."
            )
            MoveClassification.BOOK -> v.pick(
                "book",
                "$moveSan follows known opening theory.",
                "$moveSan is still opening theory.",
                "$moveSan stays in book."
            )
            MoveClassification.BRILLIANT ->
                joinSentences(brilliantLead(ctx, moveSan), foundSentence(ctx, tacticsFound, opponentMates, mateInBefore))
            MoveClassification.GREAT -> joinSentences(
                v.pick(
                    "great",
                    "$moveSan was the only move that kept things on track.",
                    "$moveSan is the only move here: the next-best option gives up real ground.",
                    "$moveSan is an only move, and nothing else keeps the position on track."
                ),
                foundSentence(ctx, tacticsFound, opponentMates, mateInBefore)
            )
            MoveClassification.BEST -> joinSentences(
                v.pick(
                    "best",
                    "$moveSan matches the engine's top choice.",
                    "$moveSan is the engine's first choice.",
                    "$moveSan is the top engine move here."
                ),
                foundSentence(ctx, tacticsFound, opponentMates, mateInBefore)
            )
            MoveClassification.EXCELLENT -> joinSentences(
                v.pick(
                    "excellent",
                    "$moveSan is very close to the best move.",
                    "$moveSan is nearly the engine's top choice.",
                    "$moveSan comes within a whisker of the best move."
                ),
                foundSentence(ctx, tacticsFound, opponentMates, mateInBefore)
            )
            MoveClassification.GOOD -> joinSentences(
                v.pick("good", "$moveSan is a sound move.", "$moveSan is a reasonable move.", "$moveSan is a solid choice."),
                foundSentence(ctx, tacticsFound, opponentMates, mateInBefore)
            )
            // The classes that have a better move: what went wrong, what it did to the evaluation, then
            // "Better was X" last so it is the sentence the reader is left holding.
            MoveClassification.INACCURACY, MoveClassification.MISTAKE, MoveClassification.BLUNDER -> {
                val wrong = allowedSentence(ctx, threatsAllowed, opponentMates, mateInAfter)
                    ?: wrongSentence(ctx, classification, moveSan)
                joinSentences(
                    wrong,
                    consequenceSentence(ctx, winPercentBefore, winPercentAfter),
                    betterSentence(ctx, bestMoveSan, tacticsMissed, mateInBefore)
                )
            }
            MoveClassification.MISS -> joinSentences(
                consequenceSentence(ctx, winPercentBefore, winPercentAfter),
                missSentence(ctx, bestMoveSan, mateInBefore, winPercentBefore)
            )
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
            previousMove = previous?.let { moveOf(it) },
            winPercentBefore = annotation.winPercentBefore,
            winPercentAfter = annotation.winPercentAfter
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
        val previousMove: Move?,
        val variety: Variety
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
        val v = ctx.variety
        return when {
            offer != null && offer.gain >= SACRIFICE_MIN_CP -> {
                val piece = "the ${PieceValues.name(offer.type)} on ${offer.square}"
                v.pick(
                    "sacrifice",
                    "$moveSan is a sacrifice: it offers $piece.",
                    "$moveSan sacrifices $piece.",
                    "$moveSan offers $piece: a sacrifice the engine rates among the best moves here."
                )
            }
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
    private fun foundSentence(ctx: Ctx, found: List<TacticInstance>, opponentMates: Boolean, mateInBefore: Int?): String? {
        if (opponentMates) return null
        val motif = bestMotif(found, ctx.mover, ctx.before, ctx.move, ctx.after, ctx.previousMove, capturesCount = true, mateIn = mateInBefore)
            ?: return null
        val san = ctx.before.moveToSan(ctx.move)
        // The lead sentence has just named the move, so the second one does not say it again.
        return if (motif.engineLine) {
            ctx.variety.pick("found-line", "In the engine's line, $san ${motif.phrase}.", "The engine's line shows it: $san ${motif.phrase}.")
        } else {
            ctx.variety.pick("found", "This ${motif.phrase}.", "It ${motif.phrase}.")
        }
    }

    /**
     * The most valuable true thing to say about [move] (played from [before]) from [tactics], or null.
     * Mating motifs outrank everything; then the larger material claim wins. A capture that the
     * exchange evaluator proves profitable is a candidate when [capturesCount], unless it merely takes
     * back on the square [previous] just captured on. [mateIn] is the engine's forced-mate distance for
     * [side] from before [move], when it has one, so a mating motif can say "in N".
     */
    private fun bestMotif(
        tactics: List<TacticInstance>,
        side: Color,
        before: Position,
        move: Move,
        after: Position,
        previous: Move?,
        capturesCount: Boolean,
        mateIn: Int?,
        variety: Variety = Variety(plyOf(before))
    ): Motif? {
        val candidates = ArrayList<Motif>()
        for (tactic in tactics) {
            if (tactic.byColor != side || tactic.moveUci != move.toUci()) continue
            if (tactic.confidence < CONFIRMED && tactic.materialSwing < MATERIAL_SWING_CP) continue
            motifOf(tactic, before, move, after, mateIn, variety)?.let { candidates.add(it) }
        }
        // Only a capture of a piece is claimed: a pawn taken may simply be one that was lost a move or
        // two earlier ("dxe5 ... wins a pawn" after 4.dxe5), and a recapture is the second half of a
        // trade, which wins nothing by itself.
        val takesPiece = !move.isEnPassant && before.pieceAt(move.to)?.let { PieceValues.of(it.type) >= MINOR_PIECE_CP } == true
        if (capturesCount && move.isCapture && takesPiece) {
            val recapture = previous != null && previous.isCapture && previous.to == move.to
            if (!recapture) {
                val gain = ExchangeEvaluator.see(before, move)
                ExchangeEvaluator.describeCapture(before, move)?.let { what ->
                    candidates.add(Motif(variety.pick("wins", "wins $what", "picks up $what"), false, gain))
                }
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
    private fun allowedSentence(ctx: Ctx, threats: List<TacticInstance>, opponentMates: Boolean, mateInAfter: Int?): String? {
        val worthSaying = threats.filter {
            it.byColor == ctx.opponent && (it.type in MATING_TYPES || it.materialSwing >= MATERIAL_SWING_CP)
        }
        // With a forced mate against the mover, only the mating reply is the charge.
        val pool = if (opponentMates) worthSaying.filter { it.type in MATING_TYPES } else worthSaying
        val byReply = pool.groupBy { it.moveUci }.entries.sortedByDescending { entry ->
            entry.value.maxOf { if (it.type in MATING_TYPES) MATE_VALUE else it.materialSwing }
        }
        // The opponent's mate distance, counted from the reply: the engine's number after the move.
        val theirMate = mateInAfter?.takeIf { it < 0 }?.let { -it }
        for ((uci, tactics) in byReply) {
            val reply = try {
                ctx.after.parseUci(uci)
            } catch (e: Exception) {
                continue
            }
            if (reply.color != ctx.opponent) continue
            val afterReply = ctx.after.makeMove(reply)
            val motif = bestMotif(tactics, ctx.opponent, ctx.after, reply, afterReply, ctx.move, capturesCount = true, mateIn = theirMate, variety = ctx.variety)
                ?: continue
            val replySan = ctx.after.moveToSan(reply)
            val who = ctx.who(ctx.opponent)
            val lead = ctx.variety.pick(
                "allowed",
                "This lets $who play $replySan",
                "Now $who can play $replySan",
                "This hands $who $replySan"
            )
            return if (motif.engineLine) "$lead; in the engine's line it ${motif.phrase}." else "$lead, which ${motif.phrase}."
        }
        if (!opponentMates) return null
        val n = theirMate ?: return "This allows a forced mate."
        return ctx.variety.pick(
            "allowed-mate",
            "This allows a forced mate in $n.",
            "This walks into a forced mate in $n.",
            "After this, ${ctx.who(ctx.opponent)} has a forced mate in $n."
        )
    }

    /**
     * What an error cost when no reply of the opponent's names it, in the register of its class: calm
     * for an inaccuracy, blunt for a blunder. Each wording is a reading of the loss band that defines
     * the class (§2: at least 5, 10 and 20 win-percent) and of the §9 severity words.
     */
    private fun wrongSentence(ctx: Ctx, classification: MoveClassification, moveSan: String): String = when (classification) {
        MoveClassification.INACCURACY -> ctx.variety.pick(
            "wrong-inaccuracy",
            "$moveSan gives back ground.",
            "$moveSan is not the most precise.",
            "$moveSan concedes a little ground."
        )
        MoveClassification.MISTAKE -> ctx.variety.pick(
            "wrong-mistake",
            "$moveSan gives up real ground.",
            "$moveSan goes wrong.",
            "$moveSan lets the position slip."
        )
        else -> ctx.variety.pick(
            "wrong-blunder",
            "$moveSan gives up a big chunk of the position.",
            "$moveSan is a serious slip.",
            "$moveSan throws a big chunk of the position away."
        )
    }

    /**
     * The evaluation in words, before and after (§9's bands), for an error: "That takes White from
     * winning to about level." Nothing when the move did not cross a band, or when the numbers are
     * not known. Mover-relative, like [MoveAnnotation.winPercentBefore]; no verb has to agree with the
     * subject, so the same sentence serves "you" and a colour.
     */
    private fun consequenceSentence(ctx: Ctx, winPercentBefore: Double?, winPercentAfter: Double?): String? {
        val before = winPercentBefore?.let { standingWords(it) } ?: return null
        val after = winPercentAfter?.let { standingWords(it) } ?: return null
        if (before == after) return null
        val who = ctx.who(ctx.mover)
        return ctx.variety.pick(
            "consequence",
            "That takes $who from $before to $after.",
            "The position swings from $before to $after for $who.",
            "From $before to $after in one move: that is what this cost $who."
        )
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
        return if (motif.engineLine) {
            ctx.variety.pick("better-line", "Better was $best; in the engine's line it ${motif.phrase}.", "Better was $best: in the engine's line it ${motif.phrase}.")
        } else {
            ctx.variety.pick("better", "Better was $best, which ${motif.phrase}.", "Better was $best: it ${motif.phrase}.")
        }
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
            if (mateInBefore != null && mateInBefore > 0) return Motif(forcedMatePhrase(ctx.variety, mateInBefore), false, MATE_VALUE)
            return bestMotif(tactics, ctx.mover, ctx.before, bestMove, afterBest, ctx.previousMove, capturesCount = true, mateIn = mateInBefore, variety = ctx.variety)
        }
        return null
    }

    private fun sameMove(a: String, b: String): Boolean = a.trimEnd('+', '#') == b.trimEnd('+', '#')

    /**
     * The missed win: one sentence that carries the better move itself, so the card does not state it
     * a second time and it is the last thing said. What was kept is named by the §9 band of the
     * mover's win-percent before the move: "a decisive advantage" from 95, "a winning position" below
     * it (a MISS needs 90, or a mate); with no number the class alone proves "decisive".
     */
    private fun missSentence(ctx: Ctx, bestMoveSan: String?, mateInBefore: Int?, winPercentBefore: Double?): String {
        val mate = mateInBefore?.takeIf { it > 0 }
        val kept = if (winPercentBefore != null && winPercentBefore < DECISIVE_WIN_PERCENT) "a winning position" else "a decisive advantage"
        val v = ctx.variety
        return when {
            bestMoveSan != null && mate != null -> v.pick(
                "miss-mate",
                "Better was $bestMoveSan, forcing mate in $mate.",
                "Better was $bestMoveSan, with a forced mate in $mate.",
                "Better was $bestMoveSan: mate in $mate was on the board."
            )
            bestMoveSan != null -> v.pick(
                "miss",
                "Better was $bestMoveSan, keeping $kept.",
                "Better was $bestMoveSan, which holds on to $kept.",
                "Better was $bestMoveSan: it keeps $kept."
            )
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
     * reliably true. [mateIn] is the engine's mate distance for the mover before [move], if any.
     */
    private fun motifOf(tactic: TacticInstance, before: Position, move: Move, after: Position, mateIn: Int?, v: Variety): Motif? {
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
                after.isCheckmate() -> plain(v.pick("mate", "is checkmate", "is mate", "delivers checkmate"), MATE_VALUE)
                tactic.confidence >= CONFIRMED -> plain(forcedMatePhrase(v, mateIn?.takeIf { it > 0 }), MATE_VALUE)
                else -> null
            }
            TacticType.BACK_RANK_MATE -> {
                val king = after.kingSquare(them)
                when {
                    after.isCheckmate() && (king.rank == 0 || king.rank == 7) ->
                        plain(v.pick("back-rank", "is a back-rank mate", "is mate on the back rank"), MATE_VALUE)
                    !after.isCheckmate() -> backRankThreat(after, them, v)?.let { plain(it, MATE_VALUE / 2) }
                    else -> null
                }
            }
            TacticType.SMOTHERED_MATE ->
                if (isSmotheredMate(after, them, us)) {
                    plain(v.pick("smothered", "is a smothered mate", "is a smothered mate: the king is boxed in by its own pieces"), MATE_VALUE)
                } else null

            TacticType.FORK, TacticType.PAWN_FORK -> {
                val hit = tactic.targetSquares.filter { theirs(it) != null && BoardFacts.attacks(after, landing, it) }
                if (hit.size < 2) return null
                val targets = list(hit.mapNotNull(::nameOf))
                val pawn = after.pieceAt(landing)?.type == PieceType.PAWN
                plain(
                    if (pawn) v.pick("pawn-fork", "forks $targets with a pawn", "is a pawn fork, hitting $targets")
                    else v.pick("fork", "forks $targets", "is a fork, hitting $targets at once", "lands a fork on $targets")
                )
            }
            TacticType.DOUBLE_ATTACK -> {
                val hit = tactic.targetSquares.filter {
                    theirs(it) != null && BoardFacts.attackers(after, it, us).isNotEmpty()
                }
                if (hit.size >= 2) {
                    val targets = list(hit.mapNotNull(::nameOf))
                    plain(v.pick("double-attack", "attacks $targets at once", "creates a double attack on $targets"))
                } else null
            }
            TacticType.PIN_ABSOLUTE, TacticType.PIN_RELATIVE -> {
                val slider = tactic.involvedSquares.getOrNull(0)
                val rear = tactic.involvedSquares.getOrNull(1)
                val front = tactic.targetSquares.firstOrNull()
                if (slider != null && rear != null && front != null && isLine(after, us, slider, front, rear)) {
                    val f = nameOf(front)
                    val r = nameOf(rear)
                    if (after.pieceAt(rear)?.type == PieceType.KING) {
                        plain(v.pick("pin-absolute", "pins $f to $r", "puts $f in an absolute pin against $r"))
                    } else {
                        plain(v.pick("pin-relative", "pins $f to $r", "ties $f to $r with a relative pin"))
                    }
                } else null
            }
            TacticType.SKEWER -> {
                val slider = tactic.involvedSquares.getOrNull(0)
                val front = tactic.targetSquares.getOrNull(0)
                val rear = tactic.targetSquares.getOrNull(1)
                if (slider != null && front != null && rear != null && isLine(after, us, slider, front, rear)) {
                    val f = nameOf(front)
                    val r = nameOf(rear)
                    plain(v.pick("skewer", "skewers $f, with $r behind it", "is a skewer: it attacks $f, and $r stands behind it on the same line"))
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
                    val p = "the ${PieceValues.name(piece.type)} on $attacker"
                    plain(v.pick("discovered", "uncovers $p, which now attacks ${nameOf(target)}", "is a discovered attack: $p is unmasked against ${nameOf(target)}"))
                } else null
            }
            TacticType.DISCOVERED_CHECK -> {
                val attacker = tactic.involvedSquares.getOrNull(0)
                val piece = attacker?.let { after.pieceAt(it) }
                if (attacker != null && attacker != landing && piece?.color == us && after.isInCheck(them) &&
                    BoardFacts.attacks(after, attacker, after.kingSquare(them))
                ) {
                    val p = "the ${PieceValues.name(piece.type)} on $attacker"
                    plain(v.pick("discovered-check", "gives check by uncovering $p", "is a discovered check from $p"))
                } else null
            }
            TacticType.DOUBLE_CHECK ->
                if (BoardFacts.attackers(after, after.kingSquare(them), us).size >= 2) {
                    plain(v.pick("double-check", "gives double check", "is a double check: only a king move can answer it"))
                } else null
            TacticType.HANGING_PIECE -> plain(hangingPhrase(tactic, before, after, us, landing, v))
            TacticType.TRAPPED_PIECE -> {
                val target = tactic.targetSquares.firstOrNull()
                val piece = target?.let { theirs(it) }
                if (target != null && piece != null && piece.type != PieceType.KING &&
                    PieceValues.of(piece.type) >= MINOR_PIECE_CP && !after.isInCheck() &&
                    hasNoSafeSquare(after, target)
                ) {
                    val p = "the ${PieceValues.name(piece.type)} on $target"
                    plain(v.pick("trapped", "leaves $p with no safe square", "traps $p: every square it can reach loses material"))
                } else null
            }
            TacticType.PROMOTION_TACTIC ->
                move.promotion?.let { plain(v.pick("promotion", "promotes the pawn to ${withArticle(PieceValues.name(it))}", "promotes to ${withArticle(PieceValues.name(it))}")) }
            TacticType.UNDERPROMOTION ->
                move.promotion?.takeIf { it != PieceType.QUEEN }
                    ?.let { plain(v.pick("underpromotion", "underpromotes to ${withArticle(PieceValues.name(it))}", "is an underpromotion, to ${withArticle(PieceValues.name(it))}")) }
            TacticType.DESPERADO -> desperadoPhrase(before, move, after, us)?.let { plain(it) }
            TacticType.OVERLOADED_PIECE ->
                if (tactic.confidence >= CONFIRMED) overloadPhrase(tactic, before, us)?.let { Motif(it, true, tactic.materialSwing) } else null

            // The motifs the engine's own line proves (deflection, decoy, clearance, zwischenzug, ...):
            // the detector's description is built from that line and opens with the move's SAN, so it
            // is used as it stands, but only when it does open with this move - otherwise it is about
            // some other move and is not ours to say.
            TacticType.DEFLECTION, TacticType.REMOVING_THE_DEFENDER, TacticType.DECOY,
            TacticType.INTERFERENCE, TacticType.CLEARANCE, TacticType.GREEK_GIFT, TacticType.WINDMILL ->
                if (tactic.confidence >= CONFIRMED) engineLineMotif(tactic, before, move) else null
            TacticType.ZWISCHENZUG ->
                if (tactic.confidence >= CONFIRMED && isForcingBeforeCapture(tactic, before, move, after)) engineLineMotif(tactic, before, move) else null

            // No sentence about these can be made reliably true from the position alone.
            else -> null
        }
    }

    /** "starts a forced mate in 3" (the engine's own distance) or, without a number, "starts a forced mate". */
    private fun forcedMatePhrase(v: Variety, mateIn: Int?): String =
        if (mateIn == null || mateIn <= 0) "starts a forced mate"
        else v.pick("forced-mate", "starts a forced mate in $mateIn", "begins a forced mate in $mateIn", "sets a forced mate in $mateIn in motion")

    private fun engineLineMotif(tactic: TacticInstance, before: Position, move: Move): Motif? {
        val san = before.moveToSan(move)
        val description = tactic.description.trim().trimEnd('.')
        if (!description.startsWith("$san ")) return null
        val rest = description.removePrefix("$san ").takeIf { it.isNotBlank() } ?: return null
        return Motif(rest, true, tactic.materialSwing)
    }

    /**
     * A zwischenzug's own half of the proof, re-read off the board: the move is forcing (it gives
     * check, or captures something worth more than the mover) and the piece it postpones taking (the
     * motif's target) stood there before the move, worth a minor piece or more, with a capture of it
     * that does not lose material. The engine's line taking it afterwards is the detector's half.
     */
    private fun isForcingBeforeCapture(tactic: TacticInstance, before: Position, move: Move, after: Position): Boolean {
        val mover = before.pieceAt(move.from)?.type ?: return false
        val captured = before.pieceAt(move.to)?.type
        val forcing = after.isInCheck() || (captured != null && PieceValues.of(captured) > PieceValues.of(mover))
        if (!forcing) return false
        val pending = tactic.targetSquares.firstOrNull() ?: return false
        val victim = before.pieceAt(pending)?.takeIf { it.color != move.color && it.type != PieceType.KING } ?: return false
        if (PieceValues.of(victim.type) < MINOR_PIECE_CP) return false
        val takes = before.legalMoves().filter { it.isCapture && it.to == pending }
        val cheapest = takes.minByOrNull { PieceValues.of(it.piece) } ?: return false
        return ExchangeEvaluator.see(before, cheapest) >= 0
    }

    /**
     * "exploits the overloaded knight on d7, which cannot guard d8 and f6 at once": said as the engine's
     * line. Re-verified on the position before the move: the overloaded piece is the opponent's, and
     * each named square holds an opponent's piece that the mover attacks and that nothing but the
     * overloaded piece defends.
     */
    private fun overloadPhrase(tactic: TacticInstance, before: Position, us: Color): String? {
        val overloaded = tactic.involvedSquares.firstOrNull() ?: return null
        val piece = before.pieceAt(overloaded)?.takeIf { it.color != us } ?: return null
        val targets = tactic.targetSquares.distinct()
        if (targets.size < 2) return null
        for (t in targets) {
            val victim = before.pieceAt(t)?.takeIf { it.color != us && it.type != PieceType.KING } ?: return null
            if (BoardFacts.attackers(before, t, us).isEmpty()) return null
            if (BoardFacts.defenders(before, t) != listOf(overloaded)) return null
            if (victim.type == PieceType.KING) return null
        }
        return "exploits the overloaded ${PieceValues.name(piece.type)} on $overloaded, which cannot guard ${list(targets.map { it.toString() })} at once"
    }

    /**
     * "is a desperado: the knight was lost anyway, so it takes the pawn on e5 on the way out". Proved
     * on the boards: the mover's piece is worth a minor piece or more, the capture loses material by
     * exchange, the piece was winnable where it stood (if the opponent had the move) and is still
     * winnable where it landed.
     */
    private fun desperadoPhrase(before: Position, move: Move, after: Position, us: Color): String? {
        if (!move.isCapture || move.isEnPassant) return null
        val mover = before.pieceAt(move.from)?.takeIf { it.color == us } ?: return null
        if (PieceValues.of(mover.type) < MINOR_PIECE_CP) return null
        val victim = before.pieceAt(move.to)?.type ?: return null
        if (ExchangeEvaluator.see(before, move) >= 0) return null
        val passed = BoardFacts.passTurn(before) ?: return null
        val lostAnyway = (BoardFacts.bestCapture(passed, move.from) ?: -1) >= 0
        val stillLost = (BoardFacts.bestCapture(after, move.to) ?: -1) >= 0
        if (!lostAnyway || !stillLost) return null
        return "is a desperado: the ${PieceValues.name(mover.type)} was lost anyway, so it takes the ${PieceValues.name(victim)} on ${move.to} on the way out"
    }

    /**
     * "threatens Rd8, mate on the back rank": the opponent's king sits on its back rank with every
     * forward escape square taken by its own men, at least one of them a pawn, and if the opponent could
     * pass, a rook or queen move onto that rank would be mate (the first such move in UCI order, so
     * the same board always names the same move).
     */
    private fun backRankThreat(after: Position, them: Color, v: Variety): String? {
        if (after.isInCheck()) return null
        val king = after.kingSquare(them)
        val backRank = if (them == Color.WHITE) 0 else 7
        if (king.rank != backRank) return null
        val forward = if (them == Color.WHITE) 1 else -1
        val escapes = (-1..1).mapNotNull { df ->
            val f = king.file + df
            val r = king.rank + forward
            if (f in 0..7 && r in 0..7) Square.of(f, r) else null
        }
        if (escapes.isEmpty()) return null
        if (!escapes.all { after.pieceAt(it)?.color == them }) return null
        if (escapes.none { after.pieceAt(it)?.type == PieceType.PAWN }) return null
        val passed = BoardFacts.passTurn(after) ?: return null
        val mate = passed.legalMoves()
            .filter { it.to.rank == backRank && (it.piece == PieceType.ROOK || it.piece == PieceType.QUEEN) && passed.makeMove(it).isCheckmate() }
            .minByOrNull { it.toUci() } ?: return null
        val san = passed.moveToSan(mate).trimEnd('#', '+')
        return v.pick("back-rank-threat", "threatens $san, mate on the back rank", "sets up a back-rank mate: $san is the threat")
    }

    /**
     * "attacks the undefended bishop on b5", "attacks the queen on g5 with a pawn", "attacks the pawn
     * on g4 more often than it is defended": what the board shows about a piece that the detector
     * called hanging. Never "wins" - the opponent moves next. Only what the move created: if the
     * same fact was already true before the move, it was not this move that did it.
     */
    private fun hangingPhrase(tactic: TacticInstance, before: Position, after: Position, us: Color, landing: Square, v: Variety): String? {
        val target = tactic.targetSquares.firstOrNull() ?: return null
        val now = looseness(target, after, us, landing) ?: return null
        if (looseness(target, before, us, landing) == now) return null
        return now.phrase(v)
    }

    /** One verified fact about a loose piece, and its phrasings. Equality is by fact, not by wording. */
    private data class Looseness(val kind: String, val name: String, val target: Square, val attacker: String?, val direct: Boolean) {
        fun phrase(v: Variety): String = when (kind) {
            "undefended" ->
                if (direct) v.pick("loose-direct", "attacks the undefended $name on $target", "hits the loose $name on $target", "attacks the $name on $target, which is en prise")
                else v.pick("loose-indirect", "leaves the $name on $target undefended, with $attacker attacking it", "leaves the $name on $target en prise to $attacker")
            "cheaper" ->
                if (direct) v.pick("cheaper-direct", "attacks the $name on $target with $attacker", "hits the $name on $target with $attacker")
                else "leaves the $name on $target attacked by $attacker"
            else ->
                if (direct) v.pick("outnumbered-direct", "attacks the $name on $target more often than it is defended", "piles up on the $name on $target: more attackers than defenders")
                else "leaves the $name on $target attacked more often than it is defended"
        }
    }

    /**
     * What the board says about the piece on [target], or null when nothing is loose. When the moved
     * piece (on [landing]) is itself one of the attackers it "attacks"; otherwise the move did it by
     * uncovering or by removing a defender, and the sentence says what is attacking instead of
     * crediting the moved piece with an attack it does not make.
     */
    private fun looseness(target: Square, position: Position, us: Color, landing: Square): Looseness? {
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
                Looseness("undefended", name, target, if (direct) null else theArticle(position, cheapestSquare), direct)
            PieceValues.of(cheapest) < PieceValues.of(victim.type) ->
                Looseness("cheaper", name, target, withArticle(PieceValues.name(cheapest)), direct)
            attackers.size > defenders.size ->
                Looseness("outnumbered", name, target, null, direct)
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

        /** From this win-percent a position is "decisively" won (§9's top band). */
        const val DECISIVE_WIN_PERCENT = 95.0

        private const val MINOR_PIECE_CP = 300

        /** A mate outranks any material claim. */
        private const val MATE_VALUE = 1_000_000

        /** The motifs that describe a mate; they outrank everything else on a move (spec §5.3 "Reporting"). */
        private val MATING_TYPES = setOf(
            TacticType.MATE_NET, TacticType.BACK_RANK_MATE, TacticType.SMOTHERED_MATE
        )

        /** The ply a position is at: 1 before White's first move, 2 before Black's, and so on. */
        fun plyOf(position: Position): Int =
            (position.fullmoveNumber - 1) * 2 + if (position.sideToMove == Color.BLACK) 2 else 1

        /**
         * The evaluation in words, from the mover's win-percent, by the bands of ANALYSIS_SPEC §9
         * (`NarrationVocabulary.standing`): the same cut-offs as the narrated video, so a card and the
         * video never disagree about who stands how.
         */
        fun standingWords(winPercent: Double): String = when {
            winPercent >= 95.0 -> "decisively winning"
            winPercent >= 82.0 -> "winning"
            winPercent >= 68.0 -> "clearly better"
            winPercent >= 57.0 -> "slightly better"
            winPercent >= 43.0 -> "about level"
            winPercent >= 32.0 -> "slightly worse"
            winPercent >= 18.0 -> "clearly worse"
            winPercent >= 5.0 -> "losing"
            else -> "decisively lost"
        }
    }
}

/**
 * The deterministic choice between a template's phrasings (C1). A card at [ply] gets variant
 * `(ply + hash(template)) mod n`: the same game always reads the same, two consecutive cards that use
 * the same template never share a phrasing (the index steps with the ply), and different templates on
 * one card start at different offsets. `String.hashCode` is specified, so this is the same on every
 * JVM and device.
 */
internal class Variety(private val ply: Int) {

    fun index(template: String, size: Int): Int {
        require(size > 0)
        return Math.floorMod(ply + template.hashCode(), size)
    }

    fun pick(template: String, vararg options: String): String = options[index(template, options.size)]
}
