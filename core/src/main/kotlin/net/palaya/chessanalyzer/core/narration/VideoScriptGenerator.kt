package net.palaya.chessanalyzer.core.narration

import net.palaya.chessanalyzer.core.analysis.ExchangeEvaluator
import net.palaya.chessanalyzer.core.analysis.GameReport
import net.palaya.chessanalyzer.core.analysis.MoveAnnotation
import net.palaya.chessanalyzer.core.analysis.MoveClassification
import net.palaya.chessanalyzer.core.analysis.MoveSequence
import net.palaya.chessanalyzer.core.analysis.MoveSequenceDetector
import net.palaya.chessanalyzer.core.analysis.PlayerReport
import net.palaya.chessanalyzer.core.analysis.TacticInstance
import net.palaya.chessanalyzer.core.analysis.TacticReferenceLibrary
import net.palaya.chessanalyzer.core.analysis.TacticSignificance
import net.palaya.chessanalyzer.core.analysis.TacticType
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Move
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.moveToSan
import net.palaya.chessanalyzer.core.chess.parseSan
import net.palaya.chessanalyzer.core.pgn.PgnGame
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Builds the narrated review script — the words that get spoken and what the board does while
 * they are spoken — from a finished [GameReport].
 *
 * Deterministic and offline: every sentence comes from a template chosen by the data, and the only
 * phrase variation ([PhrasePicker]) is seeded from the report itself, so the same report always
 * produces byte-identical output. No LLM, no randomness, no clock.
 *
 * **This class contains no prose.** It decides *which* [Sentence] to say and with which facts;
 * [strings] turns each one into words for its language. The generator joins finished sentences
 * with spaces and never composes words inside one — that is the property that lets Hebrew
 * restructure a sentence entirely (see [NarrationStrings]).
 *
 * The register is deliberately different from
 * [net.palaya.chessanalyzer.core.analysis.CommentaryGenerator]: that one writes a caption you read
 * next to the board, this one writes a paragraph somebody says out loud. Notation never reaches
 * [ScriptSegment.narration] — it goes in [ScriptSegment.caption] instead, and [NotationGuard]
 * catches anything that slips through from a PGN tag or a book name.
 *
 * @param userColor the side the viewer played, used to switch the narration into second person
 *   ("you had a winning move here"). Null means nobody is identified, and both sides are narrated
 *   neutrally by colour.
 * @param strings the language to narrate in. Defaults to English; the app resolves its language
 *   setting through [NarrationLocales.forTag].
 */
class VideoScriptGenerator(
    private val userColor: Color? = null,
    private val strings: NarrationStrings = NarrationLocales.default
) {

    fun generate(report: GameReport, game: PgnGame, options: NarrationOptions): VideoScript =
        ScriptBuilder(report, game, options, userColor, strings).build()

    companion object {

        /**
         * Word-count based speech estimate, plus a per-sentence pause allowance — TTS engines
         * breathe at full stops and the layout has to budget for it.
         */
        fun estimateSpeechMs(text: String, wpm: Int): Long {
            val words = text.split(WHITESPACE).count { it.isNotBlank() }
            if (words == 0) return 0L
            val effectiveWpm = wpm.coerceIn(60, 400)
            val speaking = words * 60_000L / effectiveWpm
            val sentences = max(1, text.count { it == '.' || it == '!' || it == '?' })
            return speaking + sentences * SENTENCE_PAUSE_MS
        }

        private const val SENTENCE_PAUSE_MS = 240L
        private val WHITESPACE = Regex("\\s+")
    }
}

// ---------------------------------------------------------------------------
// Implementation
// ---------------------------------------------------------------------------

/** Why a ply earned a spoken beat. Maps onto [SegmentKind] once the segment is emitted. */
private enum class BeatKind { MISSED_TACTIC, ERROR, FOUND_TACTIC, THREAT, KEY, NORMAL }

private class ScriptBuilder(
    rawReport: GameReport,
    private val game: PgnGame,
    private val options: NarrationOptions,
    private val userColor: Color?,
    private val strings: NarrationStrings
) {

    /**
     * The report with insignificant tactics removed (ANALYSIS_SPEC §9.6) — the same threshold that
     * prunes plies prunes motifs, so the narration never names a relative pin that had no bearing
     * on the game. Applied at every [NarrationDepth]: EVERY_MOVE exempts the *ply* selection (a
     * complete walkthrough was asked for) but not this — a walkthrough of every move is not a
     * request to hear every detector hit. A threshold of 0 disables it, as it disables everything.
     */
    private val report: GameReport = TacticSignificance.prune(rawReport, options.significanceThresholdCp)

    private val annotations = report.annotations
    private val coach = options.style == NarrationStyle.COACH

    /** Positions before each ply, rebuilt once from the annotation FENs and reused everywhere. */
    private val positionsBefore: List<Position> = annotations.map { safeFen(it.fenBefore) }
    private val moves: List<Move?> = annotations.mapIndexed { i, a ->
        positionsBefore[i].let { SpokenChess.moveOrNull(it, a.uci) }
    }

    /** The facts of each played move, for the locale to put into words. */
    private val spokenMoves: List<SpokenMove?> = moves.mapIndexed { i, m ->
        m?.let { SpokenChess.describe(positionsBefore[i], it) }
    }

    private val phrases = PhrasePicker(seedOf(report))
    private val segments = ArrayList<ScriptSegment>()
    private val chapters = ArrayList<ScriptChapter>()
    private var pendingChapter: String? = null

    /**
     * Runs of plies that tell one story (ANALYSIS_SPEC §9). Used by the significance filter so a
     * combination whose payoff only lands on its last move is not pruned move-by-move.
     */
    private val sequences: List<MoveSequence> = MoveSequenceDetector.detect(annotations)

    private val keyMomentPlies = report.keyMoments.map { it.ply }.toSet()
    private val turningPoint: MoveAnnotation? = annotations
        .filter { it.loss > 0.5 }
        .maxWithOrNull(compareBy({ it.loss }, { -it.ply }))

    // -----------------------------------------------------------------------
    // The one door to the words
    // -----------------------------------------------------------------------

    /** The words for [sentence] in this language, rotating through the locale's variants. */
    private fun say(sentence: Sentence): String =
        phrases.pick(sentence.poolKey, strings.render(sentence, options.style))

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------

    fun build(): VideoScript {
        chapter(Sentence.ChapterIntro)
        intro()
        chapter(Sentence.ChapterOpening)
        openingSummary()
        body()
        chapter(Sentence.ChapterDamageReport)
        outroSummary()
        chapter(Sentence.ChapterWorkOn)
        outroLessons()

        return VideoScript(
            title = videoTitle(),
            subtitle = videoSubtitle(),
            segments = segments,
            chapters = chapters,
            totalEstimatedMs = segments.sumOf { it.estimatedSpeechMs + it.holdAfterMs },
            userColor = userColor,
            header = header(),
            whiteAccuracy = report.white.accuracy,
            blackAccuracy = report.black.accuracy,
            whiteEstimatedRating = report.white.estimatedRating,
            blackEstimatedRating = report.black.estimatedRating
        )
    }

    /**
     * Players, result and opening for the title card and the side panel. PGN tags are optional and
     * chess.com writes "?" for anything it does not know, so both are treated as absent.
     */
    private fun header(): VideoGameHeader = VideoGameHeader(
        whiteName = tagName(report.white.name ?: game.tags["White"]) ?: strings.vocabulary.side(Color.WHITE),
        blackName = tagName(report.black.name ?: game.tags["Black"]) ?: strings.vocabulary.side(Color.BLACK),
        whiteRating = game.tags["WhiteElo"]?.toIntOrNull(),
        blackRating = game.tags["BlackElo"]?.toIntOrNull(),
        result = report.result,
        openingName = report.openingName,
        openingEco = report.openingEco,
        dateText = tagName(game.tags["Date"] ?: game.tags["UTCDate"])?.replace('.', '-')
    )

    private fun tagName(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() && it != "?" && !it.all { c -> c == '?' || c == '.' } }

    // -----------------------------------------------------------------------
    // Intro / opening
    // -----------------------------------------------------------------------

    private fun intro() {
        val whiteName = spokenName(report.white.name ?: game.tags["White"])
        val blackName = spokenName(report.black.name ?: game.tags["Black"])
        val whiteElo = game.tags["WhiteElo"]?.toIntOrNull()
        val blackElo = game.tags["BlackElo"]?.toIntOrNull()

        val sb = StringBuilder()
        if (whiteName == null && blackName == null) {
            sb.append(say(Sentence.IntroNoNames)).append(' ')
        } else {
            sb.append(
                say(
                    Sentence.IntroPlayers(
                        white = whiteName ?: strings.vocabulary.side(Color.WHITE),
                        whiteRating = whiteElo,
                        black = blackName ?: strings.vocabulary.side(Color.BLACK),
                        blackRating = blackElo
                    )
                )
            ).append(' ')
        }

        sb.append(resultSentence()).append(' ')
        if (userColor != null && options.addressUserAsYou && coach) {
            sb.append(say(Sentence.IntroYouWere(subj(userColor)))).append(' ')
        }
        sb.append(hookSentence())

        add(
            kind = SegmentKind.INTRO,
            ply = null,
            narration = sb.toString(),
            caption = videoTitle(),
            board = BoardDirective.Card(
                heading = videoTitle(),
                lines = listOfNotNull(
                    report.openingName?.let { say(Sentence.CardOpeningLine(it, report.openingEco)) },
                    say(Sentence.CardResultLine(report.result, annotations.size)),
                    say(Sentence.CardAccuracyLine(fmt1(report.white.accuracy), fmt1(report.black.accuracy)))
                )
            )
        )
    }

    private fun resultSentence(): String {
        val fullMoves = (annotations.size + 1) / 2
        val mated = annotations.lastOrNull()?.san?.endsWith("#") == true
        return when (report.result) {
            "1-0" -> say(Sentence.ResultDecisive(Color.WHITE, mated, fullMoves))
            "0-1" -> say(Sentence.ResultDecisive(Color.BLACK, mated, fullMoves))
            "1/2-1/2" -> say(Sentence.ResultDraw(fullMoves))
            else -> say(Sentence.ResultUnfinished(fullMoves))
        }
    }

    private fun hookSentence(): String {
        val missedMate = annotations.firstOrNull { it.mateInBefore != null && it.classification == MoveClassification.MISS }
        val biggest = turningPoint
        return when {
            missedMate != null -> say(Sentence.HookMissedMate)
            biggest != null && biggest.loss >= 30.0 -> say(Sentence.HookBigTurn(biggest.moveNumber))
            biggest != null && biggest.loss >= 12.0 -> say(Sentence.HookClose(biggest.moveNumber))
            biggest != null -> say(Sentence.HookClean)
            else -> say(Sentence.HookNoMistakes)
        }
    }

    private fun openingSummary() {
        val name = report.openingName
        val eco = report.openingEco
        val family = NarrationVocabulary.openingFamily(name)

        val sb = StringBuilder()
        sb.append(if (name != null) say(Sentence.OpeningLead(name)) else say(Sentence.OpeningNoBookName)).append(' ')
        sb.append(if (family != null) say(Sentence.OpeningPlan(family)) else genericOpeningPlan())

        val bookEndPly = annotations.lastOrNull { it.classification == MoveClassification.BOOK }?.ply
        if (bookEndPly != null && bookEndPly >= 4) {
            sb.append(' ').append(say(Sentence.TheoryRunsOut((bookEndPly + 1) / 2)))
        }

        // One ply drives the held position AND the eval bar, so the two can never disagree.
        val openingPly = bookEndPly ?: annotations.getOrNull(min(5, annotations.size - 1))?.ply
        val fen = openingPly?.let { annotations[it - 1].fenAfter }
            ?: game.startFen ?: Position.STANDARD_START_FEN

        add(
            kind = SegmentKind.OPENING_SUMMARY,
            ply = bookEndPly,
            narration = sb.toString(),
            caption = say(Sentence.CaptionOpening(name, eco)),
            board = BoardDirective.Hold(fen),
            eval = openingPly?.let { evalAfter(it) },
            moveNumber = openingPly?.let { moveNumberOf(it) }
        )
    }

    /** Falls back to describing what actually happened when the book has no plan blurb. */
    private fun genericOpeningPlan(): String {
        if (annotations.isEmpty()) return say(Sentence.OpeningBothDeveloped)
        val first = spokenMoves.getOrNull(0)
        val second = spokenMoves.getOrNull(1)
        return if (first != null && second != null) {
            say(Sentence.OpeningGeneric(first, second))
        } else {
            say(Sentence.OpeningGenericNoMoves)
        }
    }

    // -----------------------------------------------------------------------
    // Body
    // -----------------------------------------------------------------------

    private fun body() {
        if (annotations.isEmpty()) return
        val selected = selectPlies().sorted()
        val chapterPlies = chapterPlies(selected.toSet())
        var previous = 0
        var middlegameMarked = false
        val bookEnd = annotations.lastOrNull { it.classification == MoveClassification.BOOK }?.ply ?: 0

        for (ply in selected) {
            val gap = ply - previous - 1
            var leadIn = ""
            if (previous > 0 && gap > 0) {
                if (gap >= 3 && options.depth != NarrationDepth.MISTAKES_ONLY) {
                    connective(previous, ply)
                } else {
                    leadIn = skipLeadIn(ply)
                }
            }
            if (!middlegameMarked && ply > bookEnd + 1) {
                chapter(Sentence.ChapterMiddlegame)
                middlegameMarked = true
            }
            if (ply in chapterPlies) {
                val a = annotations[ply - 1]
                chapter(Sentence.ChapterMove(a.moveNumber, a.san))
            }
            emitBeat(ply, leadIn)
            if (turningPoint != null && turningPoint.ply == ply) {
                chapter(Sentence.ChapterTurningPoint)
                turningPointSegment(turningPoint)
            }
            previous = ply
        }
    }

    /**
     * Which plies get their own spoken beat.
     *
     * Mistakes, missed tactics, key moments, the turning point and the final move are mandatory at
     * every depth. HIGHLIGHTS tops those up with the most interesting quiet moments, but never
     * takes more than two thirds of the game — the rest becomes "let's jump ahead" narration, which
     * is what keeps a highlights script shorter than an every-move one.
     *
     * Whatever [NarrationDepth] proposes is then pruned by [applyThreshold]. The two filters
     * compose rather than override: depth decides *which kinds* of move are candidates, the
     * significance threshold decides *whether anything actually happened* on them.
     *
     * [NarrationDepth.EVERY_MOVE] is the one exception, and it is deliberate: that depth means
     * "every move, I want the complete walkthrough", which is an explicit request that the
     * threshold has no business overruling. It is the escape hatch, and it is documented as one
     * on the enum. Every other depth composes with the threshold.
     */
    private fun selectPlies(): Set<Int> {
        if (annotations.isEmpty()) return emptySet()
        if (options.depth == NarrationDepth.EVERY_MOVE) return annotations.map { it.ply }.toSet()

        val mandatory = LinkedHashSet<Int>()
        for (a in annotations) {
            when (beatKind(a)) {
                BeatKind.MISSED_TACTIC, BeatKind.ERROR -> mandatory.add(a.ply)
                else -> Unit
            }
        }
        mandatory.addAll(keyMomentPlies)
        turningPoint?.let { mandatory.add(it.ply) }
        annotations.lastOrNull()?.let { if (it.san.endsWith("#")) mandatory.add(it.ply) }

        if (options.depth == NarrationDepth.MISTAKES_ONLY) {
            return applyThreshold(
                if (mandatory.isEmpty()) setOfNotNull(annotations.lastOrNull()?.ply) else mandatory
            )
        }

        val cap = max(4, annotations.size * 2 / 3)
        val optional = annotations
            .filter { it.ply !in mandatory }
            .sortedWith(compareByDescending<MoveAnnotation> { interest(it) }.thenBy { it.ply })
            .take(max(0, cap - mandatory.size))
            .map { it.ply }

        val selected = LinkedHashSet(mandatory)
        selected.addAll(optional)
        if (selected.isEmpty()) annotations.firstOrNull()?.let { selected.add(it.ply) }
        return applyThreshold(selected)
    }

    // -----------------------------------------------------------------------
    // Significance threshold (ANALYSIS_SPEC §9)
    // -----------------------------------------------------------------------

    /**
     * Prunes [candidates] down to the plies that actually moved the evaluation.
     *
     * A ply survives when its own swing clears [NarrationOptions.significanceThresholdCp], or
     * when it belongs to a [MoveSequence] whose combined swing does — a three-move combination
     * whose payoff lands on the last move must not be sliced up by a per-move filter.
     *
     * Two guarantees, both tested:
     *  - **The checkmate always survives.** It is the game's result, which is a structural beat,
     *    and a review that omits how the game ended is broken regardless of any threshold.
     *  - **The result is never empty.** If the threshold rejects literally everything, this falls
     *    back to the single largest-swing ply (ties broken towards the earlier one) so the review
     *    still has a body between the intro and the outro. Emitting a script with no move beats at
     *    all would be a worse failure than showing one quiet move.
     *
     * Structural segments — intro, opening summary, outro summary (which speaks the final result)
     * and the lessons — are emitted outside this path entirely and are never filtered.
     */
    private fun applyThreshold(candidates: Set<Int>): Set<Int> {
        val threshold = options.significanceThresholdCp
        if (threshold <= 0 || candidates.isEmpty()) return candidates

        val kept = LinkedHashSet(candidates.filter { isSignificant(it) })
        annotations.lastOrNull()
            ?.takeIf { it.san.endsWith("#") && it.ply in candidates }
            ?.let { kept.add(it.ply) }
        if (kept.isNotEmpty()) return kept

        val largest = annotations.maxWithOrNull(compareBy({ swingCp(it) }, { -it.ply }))
        return setOfNotNull(largest?.ply)
    }

    /** `|evalAfter - evalBefore|`, both White-relative, so the number is perspective-free. */
    private fun swingCp(a: MoveAnnotation): Int = abs(a.evalAfterCp - a.evalBeforeCp)

    private fun isSignificant(ply: Int): Boolean {
        val a = annotations.getOrNull(ply - 1) ?: return false
        val threshold = options.significanceThresholdCp
        if (swingCp(a) >= threshold) return true
        // A ply carrying a tactic that survived the §9.6 gate is significant by that gate's own
        // verdict. This matters for FOUND tactics specifically: a best move's own swing is ~0 by
        // construction, so without this the fork that punished a blunder would be narrated as the
        // blunder alone, and the punishment — the instructive half — would be pruned.
        if (a.tacticsFound.isNotEmpty() || a.tacticsMissed.isNotEmpty()) return true
        return sequences.any { ply in it && it.totalSwingCp >= threshold }
    }

    /** How much a quiet ply is worth talking about, when there is room for it. */
    private fun interest(a: MoveAnnotation): Double {
        var score = a.loss
        score += (a.tacticsFound.maxOfOrNull { it.materialSwing } ?: 0) / 200.0
        score += (a.threatsAllowed.maxOfOrNull { it.materialSwing } ?: 0) / 400.0
        if (a.classification == MoveClassification.BRILLIANT) score += 20.0
        if (a.classification == MoveClassification.GREAT) score += 10.0
        if (a.san.endsWith("#") || a.san.endsWith("+")) score += 2.0
        if (a.classification == MoveClassification.BOOK) score -= 4.0
        return score
    }

    private fun beatKind(a: MoveAnnotation): BeatKind = when {
        significantMissed(a) != null -> BeatKind.MISSED_TACTIC
        a.classification == MoveClassification.BLUNDER ||
            a.classification == MoveClassification.MISTAKE ||
            a.classification == MoveClassification.MISS -> BeatKind.ERROR
        a.tacticsFound.isNotEmpty() && a.classification.isGood -> BeatKind.FOUND_TACTIC
        a.classification == MoveClassification.INACCURACY -> BeatKind.KEY
        a.threatsAllowed.isNotEmpty() && !a.classification.isGood -> BeatKind.THREAT
        a.ply in keyMomentPlies -> BeatKind.KEY
        else -> BeatKind.NORMAL
    }

    private fun significantMissed(a: MoveAnnotation): TacticInstance? {
        val best = a.bestMoveUci ?: return null
        if (best == a.uci) return null
        return a.tacticsMissed
            .filter { it.confidence >= 0.6 }
            .maxWithOrNull(compareBy({ it.confidence }, { it.materialSwing }))
    }

    /** Up to three named chapters for the worst moments, excluding the turning point's own. */
    private fun chapterPlies(selected: Set<Int>): Set<Int> = annotations
        .filter { it.ply in selected && it.ply != turningPoint?.ply }
        .filter { beatKind(it) == BeatKind.MISSED_TACTIC || beatKind(it) == BeatKind.ERROR }
        .sortedWith(compareByDescending<MoveAnnotation> { it.loss }.thenBy { it.ply })
        .take(3)
        .map { it.ply }
        .toSet()

    private fun connective(fromPly: Int, toPly: Int) {
        val target = annotations[toPly - 1]
        val fromMoveNo = annotations[fromPly - 1].moveNumber
        add(
            kind = SegmentKind.NORMAL_MOVE,
            ply = null,
            narration = say(Sentence.SkipAhead(target.moveNumber)),
            caption = say(Sentence.CaptionMoves(fromMoveNo, target.moveNumber)),
            board = BoardDirective.Hold(target.fenBefore),
            eval = evalBefore(toPly),
            moveNumber = target.moveNumber
        )
    }

    private fun skipLeadIn(toPly: Int): String {
        val moveNo = annotations[toPly - 1].moveNumber
        return if (options.depth == NarrationDepth.MISTAKES_ONLY) {
            say(Sentence.JumpTo(moveNo))
        } else {
            say(Sentence.ALittleLater)
        }
    }

    // -----------------------------------------------------------------------
    // The per-ply beats
    // -----------------------------------------------------------------------

    private fun emitBeat(ply: Int, leadIn: String) {
        val a = annotations[ply - 1]
        if (a.san.endsWith("#")) return matingBeat(a, leadIn)
        when (beatKind(a)) {
            BeatKind.MISSED_TACTIC -> missedTacticBeat(a, leadIn)
            BeatKind.ERROR -> errorBeat(a, leadIn)
            BeatKind.FOUND_TACTIC -> foundTacticBeat(a, leadIn)
            BeatKind.THREAT -> threatBeat(a, leadIn)
            BeatKind.KEY -> keyMomentBeat(a, leadIn)
            BeatKind.NORMAL -> normalBeat(a, leadIn)
        }
    }

    /** The finish deserves its own beat rather than being narrated as another quiet move. */
    private fun matingBeat(a: MoveAnnotation, leadIn: String) {
        val sb = StringBuilder(leadIn)
        sb.append(cap(playedClause(a, MoveVerb.PLAY))).append(". ")
        sb.append(say(Sentence.MateClosing)).append(' ')
        sb.append(
            when {
                isUser(a.color) -> say(Sentence.MateFinish(subj(a.color)))
                userColor == a.color.opposite() -> say(Sentence.MateOnReceivingEnd)
                else -> say(Sentence.MateFinish(subj(a.color)))
            }
        )
        add(
            kind = SegmentKind.KEY_MOMENT,
            ply = a.ply,
            narration = cap(sb.toString()),
            caption = caption(a),
            board = playMoveDirective(a),
            speakerColor = a.color,
            eval = evalAfter(a.ply),
            moveNumber = a.moveNumber
        )
    }

    private fun normalBeat(a: MoveAnnotation, leadIn: String) {
        val verb = pickVerb(
            "normalVerb",
            if (coach) listOf(MoveVerb.PLAY, MoveVerb.GO_FOR, MoveVerb.ANSWER_WITH, MoveVerb.CONTINUE_WITH, MoveVerb.REPLY_WITH, MoveVerb.FOLLOW_UP_WITH)
            else listOf(MoveVerb.PLAY, MoveVerb.CONTINUE_WITH, MoveVerb.REPLY_WITH, MoveVerb.ANSWER_WITH)
        )
        val sb = StringBuilder(leadIn)
        sb.append(playedClause(a, verb)).append('.')
        flavour(a)?.let { sb.append(' ').append(it) }
        add(
            kind = SegmentKind.NORMAL_MOVE,
            ply = a.ply,
            narration = cap(sb.toString()),
            caption = caption(a),
            board = playMoveDirective(a),
            speakerColor = a.color,
            eval = evalAfter(a.ply),
            moveNumber = a.moveNumber
        )
    }

    /** Rotates through a set of verbs the same way [say] rotates through variants. */
    private fun pickVerb(pool: String, verbs: List<MoveVerb>): MoveVerb {
        val index = phrases.pick(pool, verbs.indices.map { it.toString() }).toInt()
        return verbs[index]
    }

    private fun flavour(a: MoveAnnotation): String? = when {
        a.classification == MoveClassification.BOOK -> say(Sentence.StillTheory)
        a.classification == MoveClassification.FORCED -> say(Sentence.OnlyLegalMove)
        moves.getOrNull(a.ply - 1)?.isCastle == true -> say(Sentence.KingTuckedAway)
        a.classification == MoveClassification.BRILLIANT -> say(Sentence.BrilliantFlavour)
        a.classification == MoveClassification.GREAT -> say(Sentence.GreatFlavour)
        a.classification == MoveClassification.BEST && coach -> say(Sentence.BestFlavour)
        a.san.endsWith("+") -> say(Sentence.CheckForcesReply)
        else -> if (coach) say(Sentence.Filler).ifBlank { null } else null
    }

    private fun foundTacticBeat(a: MoveAnnotation, leadIn: String) {
        val tactic = a.tacticsFound
            .maxWithOrNull(compareBy({ it.confidence }, { it.materialSwing }))
        val pos = positionsBefore[a.ply - 1]
        val sb = StringBuilder(leadIn)
        sb.append(playedClause(a, pickVerb("foundVerb", listOf(MoveVerb.PLAY, MoveVerb.FIND, MoveVerb.GO_FOR)))).append(". ")
        if (isRecapture(a)) {
            sb.append(say(Sentence.JustTheTrade))
        } else if (tactic != null) {
            val onTheDestination = moves.getOrNull(a.ply - 1)?.to == tactic.targetSquares.firstOrNull()
            if (onTheDestination && tactic.type == TacticType.HANGING_PIECE) {
                // The clause just named that square; saying it again is how narration starts to drone.
                sb.append(say(Sentence.NothingDefendingIt)).append(' ')
            } else {
                sb.append(say(NarrationVocabulary.tacticPoint(tactic, pos))).append(' ')
            }
            if (tactic.materialSwing >= 100) {
                sb.append(say(Sentence.MaterialInTheBank(NarrationVocabulary.materialPayoff(tactic.materialSwing))))
            } else {
                sb.append(say(Sentence.PressureNoMaterialYet))
            }
        } else {
            sb.append(say(Sentence.GoodSolid))
        }
        add(
            kind = SegmentKind.FOUND_TACTIC,
            ply = a.ply,
            narration = cap(sb.toString()),
            caption = caption(a),
            board = playMoveDirective(a),
            tactic = tactic,
            speakerColor = a.color,
            eval = evalAfter(a.ply),
            moveNumber = a.moveNumber
        )
    }

    private fun errorBeat(a: MoveAnnotation, leadIn: String) {
        val sb = StringBuilder(leadIn)
        sb.append(say(Sentence.ErrorOpener(a.classification))).append(' ')
        sb.append(cap(playedClause(a, MoveVerb.PLAY))).append(". ")
        sb.append(consequence(a))
        betterMoveSentence(a)?.let { sb.append(' ').append(it) }

        add(
            kind = SegmentKind.BLUNDER,
            ply = a.ply,
            narration = cap(sb.toString()),
            caption = caption(a, a.classification.glyph),
            board = annotateDirective(a),
            speakerColor = a.color,
            eval = evalBefore(a.ply),
            moveNumber = a.moveNumber
        )
    }

    private fun threatBeat(a: MoveAnnotation, leadIn: String) {
        val threat = a.threatsAllowed
            .maxWithOrNull(compareBy({ it.confidence }, { it.materialSwing }))
        val sb = StringBuilder(leadIn)
        sb.append(cap(playedClause(a, MoveVerb.PLAY))).append(", ")
        sb.append(say(Sentence.ThreatLetIn)).append(' ')
        if (threat != null) {
            val after = safeFen(a.fenAfter)
            sb.append(say(NarrationVocabulary.tacticPoint(threat, after)))
        }
        sb.append(' ').append(consequence(a))
        add(
            kind = SegmentKind.THREAT_ALLOWED,
            ply = a.ply,
            narration = cap(sb.toString()),
            caption = caption(a),
            board = annotateDirective(a),
            tactic = threat,
            speakerColor = a.color,
            eval = evalBefore(a.ply),
            moveNumber = a.moveNumber
        )
    }

    private fun keyMomentBeat(a: MoveAnnotation, leadIn: String) {
        val sb = StringBuilder(leadIn)
        sb.append(cap(playedClause(a, MoveVerb.PLAY))).append(". ")
        sb.append(say(Sentence.InaccuracyNote))
        betterMoveSentence(a)?.let { sb.append(' ').append(it) }
        add(
            kind = SegmentKind.KEY_MOMENT,
            ply = a.ply,
            narration = cap(sb.toString()),
            caption = caption(a),
            board = annotateDirective(a),
            speakerColor = a.color,
            eval = evalBefore(a.ply),
            moveNumber = a.moveNumber
        )
    }

    /**
     * A missed tactic is not one beat, it is an **excursion**.
     *
     * The first version of this put the whole variation into a single [BoardDirective.PlayLine]
     * with one sentence over it, and it flashed past: the viewer watched four moves animate while
     * hearing one clause, which is the opposite of how a commentator handles a missed tactic.
     * This version breaks off from the game the way a person does — announce the detour, freeze
     * the position, walk the line one ply at a time with a reason for every move, state what it
     * actually won, and then say out loud that we are going back to what really happened.
     *
     * The shape, all of it emitted through the single [add] choke point so [NotationGuard] sees
     * every line and the indices stay contiguous:
     *
     *   PUZZLE_PROMPT    optional "can you find it?" pause — still strictly before the reveal
     *   MISSED_TACTIC    pivot-in: freeze the position before the move, name the move, arrow it
     *   MISSED_TACTIC    one segment per ply of the line, each a [BoardDirective.PlayMove]
     *   MISSED_TACTIC    payoff: what the line wins, on the final position of the line
     *   KEY_MOMENT       pivot-out: back on the real board, pointing at the move about to be played
     *   BLUNDER/NORMAL   the move that actually got played — the main line resumes here
     *
     * MISSED_TACTIC therefore means exactly "we are inside the detour", which is what lets the
     * renderer tint the excursion and drop the tint again the moment the pivot-out lands.
     */
    private fun missedTacticBeat(a: MoveAnnotation, leadIn: String) {
        val tactic = significantMissed(a) ?: return normalBeat(a, leadIn)
        val plies = excursionPlies(a, tactic)
        val worthAPuzzle = tactic.materialSwing >= 150 || tactic.type in MATING_MOTIFS ||
            (a.mateInBefore != null && a.color == Color.WHITE) || a.classification == MoveClassification.MISS

        if (options.includePuzzlePrompts && worthAPuzzle) {
            puzzlePrompt(a, tactic, plies)
        }
        val opening = if (options.includePuzzlePrompts && worthAPuzzle) "" else leadIn

        if (plies.isEmpty()) return missedWithoutALine(a, tactic, opening)

        excursionPivotIn(a, tactic, opening)
        for (index in plies.indices) excursionPlyBeat(a, tactic, plies, index)
        excursionPayoff(a, tactic, plies)
        excursionPivotOut(a)
        resumedMoveBeat(a)
    }

    /** One ply of a detour, with both positions kept so the narration can be built from facts. */
    private class ExcursionPly(
        val fenBefore: String,
        val uci: String,
        val san: String,
        val move: Move,
        val spoken: SpokenMove,
        val before: Position,
        val after: Position
    )

    /**
     * The plies the detour actually walks.
     *
     * Capped exactly the way [net.palaya.chessanalyzer.core.analysis.SimulationBuilder] caps a
     * simulation (ANALYSIS_SPEC section 6): at most [MAX_EXCURSION_PLIES], and stopping early on
     * mate or once the payoff has genuinely landed — the tactic's side is up the material it was
     * promised and the opponent has had the last word. Without that, a twenty-ply PV turns a
     * thirty-second point into a two-minute detour.
     *
     * Stops at the first move that will not parse too, so a stale or truncated PV degrades to the
     * part of the line that is still legal instead of throwing.
     */
    private fun excursionPlies(a: MoveAnnotation, tactic: TacticInstance): List<ExcursionPly> {
        val line = missedLine(a) ?: return emptyList()
        val start = positionsBefore[a.ply - 1]
        val payoffTarget = max(tactic.materialSwing, MIN_PAYOFF_CP)
        val out = ArrayList<ExcursionPly>()
        var pos = start
        for (uci in line.uci) {
            if (out.size >= MAX_EXCURSION_PLIES) break
            val move = SpokenChess.moveOrNull(pos, uci) ?: break
            val after = pos.makeMove(move)
            out.add(
                ExcursionPly(
                    fenBefore = pos.toFen(),
                    uci = uci,
                    san = pos.moveToSan(move),
                    move = move,
                    spoken = SpokenChess.describe(pos, move),
                    before = pos,
                    after = after
                )
            )
            pos = after
            if (after.isCheckmate()) break
            if (after.sideToMove == tactic.byColor &&
                ExchangeEvaluator.netGain(start, after, tactic.byColor) >= payoffTarget
            ) {
                break
            }
        }
        return out
    }

    // -- the beats of the excursion -----------------------------------------

    private fun excursionPivotIn(a: MoveAnnotation, tactic: TacticInstance, leadIn: String) {
        val pos = positionsBefore[a.ply - 1]
        val sb = StringBuilder(leadIn)
        sb.append(say(Sentence.PivotIn))
        val best = a.bestMoveUci?.let { SpokenChess.describeUci(pos, it) }
        if (best != null) {
            sb.append(say(Sentence.PivotReveal(best))).append(' ')
        }
        sb.append(say(NarrationVocabulary.tacticPoint(tactic, pos))).append(' ')
        sb.append(say(Sentence.PivotWalk))
        add(
            kind = SegmentKind.MISSED_TACTIC,
            ply = a.ply,
            narration = cap(sb.toString()),
            caption = say(Sentence.CaptionMissed(a.bestMoveSan, a.san)),
            board = BoardDirective.Annotate(
                fen = a.fenBefore,
                arrows = bestArrow(a),
                highlightSquares = (tactic.targetSquares + tactic.involvedSquares).distinct().map { it.toString() }
            ),
            tactic = tactic,
            speakerColor = a.color,
            eval = excursionEval(a),
            moveNumber = a.moveNumber
        )
    }

    /** One ply of the line: its own [BoardDirective.PlayMove], and its own reason for existing. */
    private fun excursionPlyBeat(
        a: MoveAnnotation,
        tactic: TacticInstance,
        plies: List<ExcursionPly>,
        index: Int
    ) {
        val p = plies[index]
        val sb = StringBuilder()
        if (p.move.color == tactic.byColor) {
            val step = if (index == 0) ExcursionStep.FIRST else ExcursionStep.NEXT
            sb.append(say(Sentence.ExcursionMove(subj(p.move.color), MoveVerb.PLAY, p.spoken, step))).append(' ')
            sb.append(attackingPoint(tactic, plies, index))
        } else {
            sb.append(
                say(Sentence.ExcursionMove(subj(p.move.color), MoveVerb.ANSWER_WITH, p.spoken, ExcursionStep.REPLY, recaptures(plies, index)))
            ).append(' ')
            sb.append(replyReason(plies, index))
        }
        add(
            kind = SegmentKind.MISSED_TACTIC,
            ply = a.ply,
            narration = cap(sb.toString()),
            caption = say(Sentence.CaptionMissedLine(plies.take(index + 1).map { it.san })),
            board = BoardDirective.PlayMove(fen = p.fenBefore, uci = p.uci, san = p.san),
            tactic = tactic,
            speakerColor = p.move.color,
            eval = excursionEval(a),
            moveNumber = a.moveNumber
        )
    }

    /**
     * Why the tactic side's move is the move. Every branch is read off the two boards either side
     * of the ply, so nothing here can claim material the line does not actually net.
     */
    private fun attackingPoint(tactic: TacticInstance, plies: List<ExcursionPly>, index: Int): String {
        val p = plies[index]
        val start = plies.first().before
        val winner = tactic.byColor
        val taken = ExchangeEvaluator.netGain(start, p.after, winner) -
            ExchangeEvaluator.netGain(start, p.before, winner)
        val running = ExchangeEvaluator.netGain(start, p.after, winner)
        return when {
            p.after.isCheckmate() -> say(Sentence.LineEndsInMate)
            taken >= MIN_PAYOFF_CP -> say(Sentence.MaterialTaken(NarrationVocabulary.materialGain(taken)))
            p.uci == tactic.moveUci && index > 0 -> say(NarrationVocabulary.tacticPoint(tactic, p.before))
            p.after.isInCheck() -> say(Sentence.CheckMustBeAnswered)
            running >= MIN_PAYOFF_CP -> say(Sentence.AlreadyUp(subj(winner), NarrationVocabulary.materialGain(running)))
            else -> say(Sentence.QuietMove)
        }
    }

    /** True when this ply takes back on the square the previous ply took on. */
    private fun recaptures(plies: List<ExcursionPly>, index: Int): Boolean {
        if (index == 0) return false
        val previous = plies[index - 1].move
        val move = plies[index].move
        return move.isCapture && previous.isCapture && previous.to == move.to
    }

    /** Why the opponent's reply is forced, or at least the best on offer. */
    private fun replyReason(plies: List<ExcursionPly>, index: Int): String {
        val p = plies[index]
        return when {
            p.before.legalMoves().size == 1 -> say(Sentence.OnlyLegalReply)
            p.before.isInCheck() -> say(Sentence.ForcedByCheck)
            recaptures(plies, index) -> say(Sentence.RecaptureNatural)
            else -> say(Sentence.BestDefence)
        }
    }

    /** What the line actually won, stated on the final position of the line. */
    private fun excursionPayoff(a: MoveAnnotation, tactic: TacticInstance, plies: List<ExcursionPly>) {
        val last = plies.last()
        val start = plies.first().before
        val winner = tactic.byColor
        val gain = ExchangeEvaluator.netGain(start, last.after, winner)
        val sb = StringBuilder()
        sb.append(say(Sentence.PayoffLead))
        when {
            last.after.isCheckmate() -> sb.append(say(Sentence.PayoffMate((plies.size + 1) / 2)))
            gain >= MIN_PAYOFF_CP -> sb.append(say(Sentence.PayoffMaterial(subj(winner), NarrationVocabulary.materialGain(gain))))
            else -> sb.append(say(Sentence.PayoffOutcome(subj(winner), payoffKind(tactic, last.after, gain))))
        }
        val mate = a.mateInBefore
        if (!last.after.isCheckmate() && mate != null && abs(mate) in 1..8) {
            sb.append(' ').append(say(Sentence.MateBehindIt(abs(mate))))
        }
        add(
            kind = SegmentKind.MISSED_TACTIC,
            ply = a.ply,
            narration = cap(sb.toString()),
            caption = say(Sentence.CaptionMissedLine(plies.map { it.san })),
            board = BoardDirective.Annotate(
                fen = last.after.toFen(),
                arrows = listOf(ArrowSpec(last.move.from.toString(), last.move.to.toString(), ArrowRole.BEST))
            ),
            tactic = tactic,
            speakerColor = a.color,
            eval = excursionEval(a),
            moveNumber = a.moveNumber
        )
    }

    /**
     * What a line that neither mates nor cashes a pawn's worth of material actually delivers —
     * the same reading of the final position that
     * [net.palaya.chessanalyzer.core.analysis.SimulationBuilder] makes for its payoff text, taken
     * from the boards here rather than parsed back out of that English.
     */
    private fun payoffKind(tactic: TacticInstance, finalPosition: Position, gain: Int): PayoffKind = when {
        finalPosition.isStalemate() -> PayoffKind.STALEMATE
        tactic.type == TacticType.PERPETUAL_CHECK -> PayoffKind.DRAW_BY_REPETITION
        tactic.type == TacticType.STALEMATE_TRICK -> PayoffKind.STALEMATE
        tactic.type == TacticType.DESPERADO -> PayoffKind.DESPERADO
        tactic.type == TacticType.PASSED_PAWN_BREAKTHROUGH -> PayoffKind.PASSED_PAWN
        gain > 0 -> PayoffKind.SMALL_MATERIAL
        finalPosition.isInCheck(tactic.byColor.opposite()) -> PayoffKind.KING_UNDER_FIRE
        tactic.type == TacticType.MATE_NET -> PayoffKind.MATING_NET
        gain <= -MIN_PAYOFF_CP -> PayoffKind.INVESTED_MATERIAL
        else -> PayoffKind.DECISIVE_ADVANTAGE
    }

    /** Back on the real board, still frozen, pointing at the move that is about to be played. */
    private fun excursionPivotOut(a: MoveAnnotation) {
        add(
            kind = SegmentKind.KEY_MOMENT,
            ply = a.ply,
            narration = say(Sentence.PivotOut),
            caption = say(Sentence.CaptionBackToGame(a.moveNumber, a.color, a.san)),
            board = BoardDirective.Annotate(a.fenBefore, arrows = playedArrow(a)),
            speakerColor = a.color,
            eval = evalBefore(a.ply),
            moveNumber = a.moveNumber
        )
    }

    /** The move that was really played. From here the main line is running again. */
    private fun resumedMoveBeat(a: MoveAnnotation) {
        val sb = StringBuilder()
        sb.append(cap(playedClause(a, MoveVerb.PLAY))).append(". ")
        sb.append(say(Sentence.ChanceGone)).append(' ')
        sb.append(consequence(a))
        val mistake = a.classification.isMistake
        add(
            kind = if (mistake) SegmentKind.BLUNDER else SegmentKind.NORMAL_MOVE,
            ply = a.ply,
            narration = cap(sb.toString()),
            caption = if (mistake) caption(a, a.classification.glyph) else caption(a),
            board = playMoveDirective(a),
            speakerColor = a.color,
            eval = evalAfter(a.ply),
            moveNumber = a.moveNumber
        )
    }

    /**
     * The degraded beat for a missed tactic whose line will not play: a stale PV, or a best move
     * the position does not accept. A detour is never promised and then not delivered — the
     * reveal and the real move are spoken over the static position instead.
     */
    private fun missedWithoutALine(a: MoveAnnotation, tactic: TacticInstance, leadIn: String) {
        val pos = positionsBefore[a.ply - 1]
        val sb = StringBuilder(leadIn)
        sb.append(say(Sentence.PivotIn))
        betterMoveSentence(a)?.let { sb.append(it).append(' ') }
        sb.append(say(NarrationVocabulary.tacticPoint(tactic, pos))).append(' ')
        val played = spokenMoves.getOrNull(a.ply - 1)
        sb.append(
            if (played != null) say(Sentence.InsteadPlayed(subj(a.color), played, isRecapture(a)))
            else say(Sentence.ChanceGone)
        )
        add(
            kind = SegmentKind.MISSED_TACTIC,
            ply = a.ply,
            narration = cap(sb.toString()),
            caption = a.bestMoveSan?.let { say(Sentence.CaptionMissed(it, a.san)) } ?: caption(a),
            board = annotateDirective(a),
            tactic = tactic,
            speakerColor = a.color,
            eval = evalBefore(a.ply),
            moveNumber = a.moveNumber
        )
    }

    // -- excursion plumbing --------------------------------------------------

    /**
     * The eval for every beat of an excursion.
     *
     * The line is the engine's own best play out of the position before the missed move, so in
     * White-relative terms the evaluation along it just *is* the evaluation of that position —
     * `evalBefore`. Nothing here can use per-ply numbers: none of these positions ever occurred in
     * the game, and the analysis only ever scored the game's own plies.
     */
    private fun excursionEval(a: MoveAnnotation): SegmentEval? = evalBefore(a.ply)

    private fun bestArrow(a: MoveAnnotation): List<ArrowSpec> {
        val best = a.bestMoveUci ?: return emptyList()
        if (best.length < 4) return emptyList()
        return listOf(ArrowSpec(best.substring(0, 2), best.substring(2, 4), ArrowRole.BEST))
    }

    private fun playedArrow(a: MoveAnnotation): List<ArrowSpec> =
        if (a.uci.length >= 4) {
            listOf(ArrowSpec(a.uci.substring(0, 2), a.uci.substring(2, 4), ArrowRole.PLAYED))
        } else {
            emptyList()
        }

    private fun puzzlePrompt(a: MoveAnnotation, tactic: TacticInstance, plies: List<ExcursionPly>) {
        val prize = when {
            plies.lastOrNull()?.after?.isCheckmate() == true -> PuzzlePrize.FORCES_MATE
            tactic.materialSwing >= 500 -> PuzzlePrize.WINS_ROOK_OR_BETTER
            tactic.materialSwing >= 320 -> PuzzlePrize.WINS_PIECE
            else -> PuzzlePrize.WINS_MATERIAL
        }
        val hold = (2500L + 500L * min(3, max(1, plies.size))).coerceIn(2500L, 4000L)
        add(
            kind = SegmentKind.PUZZLE_PROMPT,
            ply = a.ply,
            narration = say(Sentence.PuzzlePrompt(a.color, prize)),
            caption = say(Sentence.CaptionPuzzle(a.color)),
            board = BoardDirective.Annotate(
                fen = a.fenBefore,
                highlightSquares = (tactic.targetSquares + tactic.involvedSquares).distinct().map { it.toString() }
            ),
            holdAfterMs = hold,
            tactic = tactic,
            speakerColor = a.color,
            eval = evalBefore(a.ply),
            moveNumber = a.moveNumber
        )
    }

    private fun turningPointSegment(a: MoveAnnotation) {
        val sb = StringBuilder()
        sb.append(say(Sentence.TurningPointLead))
        sb.append(say(Sentence.TurningPointSwing(a.moveNumber))).append(' ')
        sb.append(
            say(
                Sentence.TurningPointFromTo(
                    subj(a.color),
                    NarrationVocabulary.standing(a.winPercentBefore),
                    NarrationVocabulary.standing(a.winPercentAfter)
                )
            )
        )
        add(
            kind = SegmentKind.TURNING_POINT,
            ply = a.ply,
            narration = sb.toString(),
            caption = say(Sentence.CaptionTurningPoint(a.moveNumber, a.san, fmt1(a.loss))),
            board = annotateDirective(a),
            speakerColor = a.color,
            eval = evalBefore(a.ply),
            moveNumber = a.moveNumber
        )
    }

    // -----------------------------------------------------------------------
    // Outro
    // -----------------------------------------------------------------------

    private fun outroSummary() {
        val w = report.white
        val b = report.black
        val whiteLabel = spokenName(w.name ?: game.tags["White"]) ?: strings.vocabulary.side(Color.WHITE)
        val blackLabel = spokenName(b.name ?: game.tags["Black"]) ?: strings.vocabulary.side(Color.BLACK)

        val sb = StringBuilder()
        sb.append(say(Sentence.OutroLead))
        sb.append(say(Sentence.OutroAccuracy(whiteLabel, fmt1(w.accuracy), blackLabel, fmt1(b.accuracy)))).append(' ')
        sb.append(say(Sentence.OutroRatings(w.estimatedRating, b.estimatedRating))).append(' ')
        sb.append(countsSentence(whiteLabel, w)).append(' ')
        sb.append(countsSentence(blackLabel, b))
        if (w.lowConfidence || b.lowConfidence) {
            sb.append(' ').append(say(Sentence.ShortGameCaveat))
        }

        add(
            kind = SegmentKind.OUTRO_SUMMARY,
            ply = null,
            narration = sb.toString(),
            caption = say(Sentence.CaptionOutro(fmt1(w.accuracy), w.estimatedRating, fmt1(b.accuracy), b.estimatedRating)),
            board = BoardDirective.Card(
                heading = say(Sentence.CardFinalNumbersHeading),
                lines = listOf(
                    say(Sentence.CardFinalPlayerLine(whiteLabel, fmt1(w.accuracy), w.estimatedRating)),
                    say(Sentence.CardFinalPlayerLine(blackLabel, fmt1(b.accuracy), b.estimatedRating)),
                    say(
                        Sentence.CardFinalCountsLine(
                            count(w, MoveClassification.BLUNDER), count(b, MoveClassification.BLUNDER),
                            count(w, MoveClassification.MISTAKE), count(b, MoveClassification.MISTAKE)
                        )
                    ),
                    report.result
                )
            )
        )
    }

    private fun countsSentence(label: String, p: PlayerReport): String {
        val blunders = count(p, MoveClassification.BLUNDER)
        val mistakes = count(p, MoveClassification.MISTAKE)
        val inaccuracies = count(p, MoveClassification.INACCURACY)
        val misses = count(p, MoveClassification.MISS)
        if (blunders + mistakes + inaccuracies + misses == 0) return say(Sentence.NoMistakes(label))
        return say(Sentence.ErrorCounts(label, blunders, mistakes, misses, inaccuracies))
    }

    private fun outroLessons() {
        val lessons = buildLessons()
        lessons.forEachIndexed { i, text ->
            add(
                kind = SegmentKind.OUTRO_LESSONS,
                ply = null,
                narration = if (i == 0) "${say(Sentence.LessonLead)}$text" else text,
                caption = say(Sentence.CaptionTakeaway(i + 1, lessons.size)),
                board = BoardDirective.Card(say(Sentence.CardWorkOnHeading), listOf(text))
            )
        }
    }

    /**
     * The most valuable part of the video, so it is built entirely from what happened in THIS
     * game: the motifs this player actually walked past, where in the game the errors clustered,
     * and how often a move handed the opponent something. Nothing here is generic advice.
     */
    private fun buildLessons(): List<String> {
        val focusColor = userColor ?: if (report.white.accuracy <= report.black.accuracy) Color.WHITE else Color.BLACK
        val focus = if (focusColor == Color.WHITE) report.white else report.black
        val other = if (focusColor == Color.WHITE) report.black else report.white
        val you = subj(focusColor)
        val focusMoves = annotations.filter { it.color == focusColor }
        val errors = focusMoves.filter { it.classification.isMistake }
        val out = ArrayList<String>()

        // 1. The motif this player kept missing, with the squares it happened on.
        val missedByType = focus.tacticsMissed.groupBy { it.type }
            .toList()
            .sortedWith(compareByDescending<Pair<TacticType, List<TacticInstance>>> { it.second.size }
                .thenByDescending { it.second.maxOf { t -> t.materialSwing } }
                .thenBy { it.first.ordinal })
        val top = missedByType.firstOrNull()
        if (top != null) {
            val (type, instances) = top
            val squares = instances.mapNotNull { it.targetSquares.firstOrNull() }.distinct().take(3)
            // The video stays in the game: a textbook detour would break the story and the
            // running time, so the reference example is *offered*, by name, and lives in the
            // report where the learner can step through it at their own pace (ANALYSIS_SPEC §10).
            val offer = if (TacticReferenceLibrary.hasReference(type)) " " + say(Sentence.TextbookOffer(type)) else ""
            val lead = if (instances.size >= 2) {
                say(Sentence.LessonRepeatedMiss(you, type, instances.size, squares))
            } else {
                val ply = annotations.firstOrNull { a -> a.tacticsMissed.any { it === instances[0] } }
                say(Sentence.LessonSingleMiss(you, type, ply?.moveNumber, squares.firstOrNull()))
            }
            out.add("$lead ${say(Sentence.TacticLesson(type))}$offer")
        }

        // 2. Where in the game the errors happened.
        if (errors.size >= 2) {
            val first = errors.first().moveNumber
            val last = errors.last().moveNumber
            val half = max(1, (annotations.size + 1) / 4)
            out.add(
                if (first > half) say(Sentence.LessonLateErrors(you, errors.size, half))
                else say(Sentence.LessonSpreadErrors(you, first, last))
            )
        } else if (errors.size == 1) {
            val e = errors.first()
            out.add(say(Sentence.LessonOneMistake(you, e.moveNumber, fmt1(e.loss))))
        }

        // 3. How often a move handed the opponent something.
        val gifts = focusMoves.count { it.threatsAllowed.isNotEmpty() && !it.classification.isGood }
        if (gifts >= 2) {
            out.add(say(Sentence.LessonGifts(you, gifts)))
        }

        // 4. The mate, or what the opponent left behind.
        val lastMove = annotations.lastOrNull()
        if (lastMove != null && lastMove.san.endsWith("#") && lastMove.color != focusColor && turningPoint != null) {
            out.add(say(Sentence.LessonMatedButNotTheMistake(you, turningPoint.moveNumber)))
        } else if (other.tacticsMissed.isNotEmpty()) {
            val oppType = other.tacticsMissed.groupBy { it.type }.maxByOrNull { it.value.size }
            if (oppType != null) {
                out.add(say(Sentence.LessonOpponentMissedToo(you, oppType.key, oppType.value.size, focus.tacticsFound.size)))
            }
        }

        // 5. Positive anchor, so the video never ends on pure criticism.
        if (out.size < 2) {
            val bestMoves = focusMoves.count { it.classification == MoveClassification.BEST || it.classification == MoveClassification.BRILLIANT }
            out.add(say(Sentence.LessonPositive(you, bestMoves, focusMoves.size, fmt1(focus.accuracy))))
        }
        if (out.size < 2) {
            out.add(say(Sentence.LessonRatingAnchor(you, fmt1(focus.accuracy), focus.estimatedRating)))
        }
        return out.take(4)
    }

    // -----------------------------------------------------------------------
    // Sentence fragments shared by several beats
    // -----------------------------------------------------------------------

    /** "you take the knight on f three with the bishop" / "White plays knight to f three". */
    private fun playedClause(a: MoveAnnotation, verb: MoveVerb): String {
        val spoken = spokenMoves.getOrNull(a.ply - 1)
            ?: return say(Sentence.PlayedUnknown(subj(a.color), verb))
        return say(Sentence.Played(subj(a.color), verb, spoken, isRecapture(a)))
    }

    /**
     * True when this move takes back on the square the opponent just took on. Worth knowing:
     * a recapture is not a tactic, it is the second half of a trade, and narrating every one of
     * them as "the piece on d seven has nothing defending it" is how a script starts sounding
     * like a machine.
     */
    private fun isRecapture(a: MoveAnnotation): Boolean {
        val move = moves.getOrNull(a.ply - 1) ?: return false
        val previous = moves.getOrNull(a.ply - 2) ?: return false
        return move.isCapture && previous.isCapture && previous.to == move.to
    }

    /** What the move did to the evaluation, in the mover's own terms. */
    private fun consequence(a: MoveAnnotation): String {
        val before = NarrationVocabulary.standing(a.winPercentBefore)
        val after = NarrationVocabulary.standing(a.winPercentAfter)
        if (before == after) {
            return say(Sentence.ConsequenceUnchanged(subj(a.color), after, NarrationVocabulary.lossSeverity(a.loss)))
        }
        return say(Sentence.ConsequenceChanged(subj(a.color), before, after))
    }

    private fun betterMoveSentence(a: MoveAnnotation): String? {
        val bestUci = a.bestMoveUci ?: return null
        if (bestUci == a.uci) return null
        val pos = positionsBefore[a.ply - 1]
        val best = SpokenChess.describeUci(pos, bestUci) ?: return null
        val mate = a.mateInBefore
        if (a.classification == MoveClassification.MISS && mate != null && abs(mate) in 1..8) {
            return say(Sentence.MateWasAvailable(abs(mate), best))
        }
        return say(Sentence.BetterWas(best))
    }

    // -----------------------------------------------------------------------
    // Board directives
    // -----------------------------------------------------------------------

    /**
     * The eval bar is White-relative by display convention (ANALYSIS_SPEC section 1), but
     * [MoveAnnotation.winPercentBefore] / [MoveAnnotation.winPercentAfter] are *mover*-relative.
     * Handing the mover's number straight to the bar makes it flip every time Black moves, which
     * is the most visible bug this feature can ship, so the White-relative value is taken from
     * [GameReport.evalGraph] (which is White-relative by construction) and only falls back to the
     * 100-complement flip when the graph is short.
     *
     * `evalGraph[i]` is the position before ply `i + 1`, i.e. the position AFTER ply `i`.
     */
    private fun evalBefore(ply: Int): SegmentEval? {
        val a = annotations.getOrNull(ply - 1) ?: return null
        return SegmentEval(
            winPercentWhite = report.evalGraph.getOrNull(ply - 1)
                ?: flipToWhite(a.winPercentBefore, a.color),
            evalCp = a.evalBeforeCp,
            mateIn = a.mateInBefore
        )
    }

    private fun evalAfter(ply: Int): SegmentEval? {
        val a = annotations.getOrNull(ply - 1) ?: return null
        return SegmentEval(
            winPercentWhite = report.evalGraph.getOrNull(ply)
                ?: flipToWhite(a.winPercentAfter, a.color),
            evalCp = a.evalAfterCp,
            mateIn = a.mateInAfter
        )
    }

    /** Mover-relative win percent to White-relative. The sigmoid is odd, so this is exact. */
    private fun flipToWhite(moverRelative: Double, mover: Color): Double =
        if (mover == Color.WHITE) moverRelative else 100.0 - moverRelative

    private fun moveNumberOf(ply: Int): Int? = annotations.getOrNull(ply - 1)?.moveNumber

    private fun playMoveDirective(a: MoveAnnotation): BoardDirective =
        BoardDirective.PlayMove(a.fenBefore, a.uci, a.san, a.classification)

    /** A static position with the played move and the engine's move drawn on it. */
    private fun annotateDirective(a: MoveAnnotation): BoardDirective {
        val arrows = ArrayList<ArrowSpec>()
        if (a.uci.length >= 4) {
            arrows.add(ArrowSpec(a.uci.substring(0, 2), a.uci.substring(2, 4), ArrowRole.PLAYED))
        }
        val best = a.bestMoveUci
        if (best != null && best != a.uci && best.length >= 4) {
            arrows.add(ArrowSpec(best.substring(0, 2), best.substring(2, 4), ArrowRole.BEST))
        }
        val threat = a.threatsAllowed.firstOrNull()?.moveUci
        if (threat != null && threat.length >= 4) {
            arrows.add(ArrowSpec(threat.substring(0, 2), threat.substring(2, 4), ArrowRole.THREAT))
        }
        return BoardDirective.Annotate(a.fenBefore, arrows)
    }

    private class MissedLine(val uci: List<String>, val san: List<String>)

    /** The line to play out for a missed tactic — the simulation if we have one, else the PV. */
    private fun missedLine(a: MoveAnnotation): MissedLine? {
        a.simulation?.let { if (it.pvUci.isNotEmpty()) return MissedLine(it.pvUci, it.pvSan) }
        if (a.bestLineSan.isEmpty()) {
            val best = a.bestMoveUci ?: return null
            val san = a.bestMoveSan ?: return null
            return MissedLine(listOf(best), listOf(san))
        }
        var pos = positionsBefore[a.ply - 1]
        val uci = ArrayList<String>()
        val san = ArrayList<String>()
        for (s in a.bestLineSan.take(8)) {
            val move = try {
                pos.parseSan(s)
            } catch (e: Exception) {
                break
            }
            uci.add(move.toUci())
            san.add(s)
            pos = pos.makeMove(move)
        }
        return if (uci.isEmpty()) null else MissedLine(uci, san)
    }

    // -----------------------------------------------------------------------
    // Plumbing
    // -----------------------------------------------------------------------

    private fun add(
        kind: SegmentKind,
        ply: Int?,
        narration: String,
        caption: String,
        board: BoardDirective,
        holdAfterMs: Long = 0L,
        tactic: TacticInstance? = null,
        speakerColor: Color? = null,
        eval: SegmentEval? = null,
        moveNumber: Int? = null
    ) {
        val clean = NotationGuard.scrub(narration, strings.vocabulary)
            .replace(Regex("\\s+"), " ").replace(" .", ".").trim()
        pendingChapter?.let {
            chapters.add(ScriptChapter(it, segments.size))
            pendingChapter = null
        }
        segments.add(
            ScriptSegment(
                index = segments.size,
                kind = kind,
                ply = ply,
                narration = clean,
                caption = caption,
                board = board,
                estimatedSpeechMs = VideoScriptGenerator.estimateSpeechMs(clean, options.speechWpm),
                holdAfterMs = holdAfterMs,
                tactic = tactic,
                speakerColor = speakerColor,
                eval = eval,
                moveNumber = moveNumber,
                evalSwingCp = swingForSegment(ply, board),
                classification = classificationForSegment(ply, board)
            )
        )
    }

    /**
     * The annotation this segment is about, or null when it is about no move that was actually
     * played.
     *
     * The test is structural rather than a flag threaded through thirty call sites: a segment is
     * about the real move at [ply] when its directive references that ply's real position (a
     * `PlayMove` of the played UCI, or an `Annotate`/`Hold` of the position either side of it).
     * Excursion beats — the "what you should have played" line — play hypothetical moves from
     * positions that never occurred, so they match none of those and correctly resolve to null.
     *
     * One predicate serves both the swing and the classification below, so the panel can never
     * show a number from one ply next to a verdict from another.
     */
    private fun annotationForSegment(ply: Int?, board: BoardDirective): MoveAnnotation? {
        val a = annotations.getOrNull((ply ?: return null) - 1) ?: return null
        val aboutRealMove = when (board) {
            is BoardDirective.PlayMove -> board.uci == a.uci && board.fen == a.fenBefore
            is BoardDirective.Annotate -> board.fen == a.fenBefore || board.fen == a.fenAfter
            is BoardDirective.Hold -> board.fen == a.fenBefore || board.fen == a.fenAfter
            else -> false
        }
        return if (aboutRealMove) a else null
    }

    /** The swing to show in the side panel. See [annotationForSegment] for when there is one. */
    private fun swingForSegment(ply: Int?, board: BoardDirective): Int? {
        val a = annotationForSegment(ply, board) ?: return null
        // Not across a mate boundary. `cpFromMate` saturates a mate to ~10000cp, so the
        // subtraction there produces "+99.0 pawns", which is arithmetic, not information. The
        // score readout already says `M2` / `#`; the threshold still uses the raw difference
        // (correctly — allowing mate is maximally significant), this is only about display.
        if (a.mateInBefore != null || a.mateInAfter != null) return null
        return a.evalAfterCp - a.evalBeforeCp
    }

    /**
     * The spec's verdict on this segment's move, for the panel's chip.
     *
     * Published on every segment shape that is about the real move — `Annotate` and `Hold` beats
     * as much as `PlayMove` ones — because the beat that *talks about* an error (`errorBeat`
     * annotates the position; it does not replay the move) is exactly the frame where the panel
     * was left with nothing but [SegmentKind] to label the move by, and called a MISTAKE a
     * "Blunder".
     */
    private fun classificationForSegment(ply: Int?, board: BoardDirective): MoveClassification? =
        annotationForSegment(ply, board)?.classification

    /** Chapters are queued, not written, so one can never point past the end of the script. */
    private fun chapter(title: Sentence) {
        pendingChapter = say(title)
    }

    private fun videoTitle(): String = say(
        Sentence.VideoTitle(
            white = report.white.name ?: game.tags["White"] ?: strings.vocabulary.side(Color.WHITE),
            black = report.black.name ?: game.tags["Black"] ?: strings.vocabulary.side(Color.BLACK)
        )
    )

    private fun videoSubtitle(): String = say(
        Sentence.VideoSubtitle(
            openingName = report.openingName,
            eco = report.openingEco,
            result = report.result.takeIf { it.isNotBlank() && it != "*" },
            depth = report.analysisDepth
        )
    )

    private fun isUser(color: Color): Boolean =
        userColor == color && options.addressUserAsYou && coach

    /** The grammatical subject for [color]: the viewer in second person, or the side by colour. */
    private fun subj(color: Color): Subject =
        if (isUser(color)) Subject(color, Person.SECOND, options.viewerGender) else Subject(color, Person.THIRD)

    private fun caption(a: MoveAnnotation, glyph: String? = null): String =
        say(Sentence.CaptionMove(a.moveNumber, a.color, a.san, glyph))

    private fun count(p: PlayerReport, c: MoveClassification): Int = p.classificationCounts[c] ?: 0

    /** A player name fit to be spoken, or null to fall back to the side's colour. */
    private fun spokenName(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        if (NotationGuard.containsNotation(raw)) return null
        return raw
    }

    private fun safeFen(fen: String): Position = try {
        Position.fromFen(fen)
    } catch (e: Exception) {
        Position.startPosition()
    }

    private fun cap(s: String): String =
        if (s.isEmpty()) s else s[0].uppercaseChar() + s.substring(1)

    private fun fmt1(v: Double): String {
        val scaled = (v * 10.0).roundToInt()
        return "${scaled / 10}.${abs(scaled % 10)}"
    }

    /** Stable across runs and JVMs: String.hashCode is specified, so this is reproducible. */
    private fun seedOf(report: GameReport): Long {
        var h = 1125899906842597L
        for (a in report.annotations) {
            h = h * 31 + a.san.hashCode()
            h = h * 31 + a.ply
        }
        h = h * 31 + report.result.hashCode()
        h = h * 31 + (report.openingName?.hashCode() ?: 0)
        return h
    }

    private companion object {

        /** Spec section 6's cap, applied to the spoken detour as well as the on-screen one. */
        const val MAX_EXCURSION_PLIES = 8

        /** Below this, a swing is noise rather than a realised material payoff. */
        const val MIN_PAYOFF_CP = 100

        val MATING_MOTIFS = setOf(
            TacticType.BACK_RANK_MATE,
            TacticType.SMOTHERED_MATE,
            TacticType.MATE_NET,
            TacticType.GREEK_GIFT
        )
    }
}
