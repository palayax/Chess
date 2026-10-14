package net.palaya.chessanalyzer.core.narration

import net.palaya.chessanalyzer.core.analysis.BestLines
import net.palaya.chessanalyzer.core.analysis.ExchangeEvaluator
import net.palaya.chessanalyzer.core.analysis.GamePhases
import net.palaya.chessanalyzer.core.analysis.GameReport
import net.palaya.chessanalyzer.core.analysis.MaterialBalance
import net.palaya.chessanalyzer.core.analysis.MoveAnnotation
import net.palaya.chessanalyzer.core.analysis.MoveClassification
import net.palaya.chessanalyzer.core.analysis.MoveSequence
import net.palaya.chessanalyzer.core.analysis.MoveSequenceDetector
import net.palaya.chessanalyzer.core.analysis.Phase
import net.palaya.chessanalyzer.core.analysis.PhaseShift
import net.palaya.chessanalyzer.core.analysis.PhaseStanding
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
        run(report, game, options).first

    /**
     * [generate] and, beside the script, what its story sentences claimed (ANALYSIS_SPEC §9.9): each as the sentence
     * kind, the words and the plies it is about. The input of the story audit (`scripts/audit_commentary.py story`),
     * which re-derives every claim from the engine's recorded numbers without the generator's help.
     */
    internal fun generateWithStory(report: GameReport, game: PgnGame, options: NarrationOptions): Pair<VideoScript, List<StoryPart>> =
        run(report, game, options).let { (script, builder) -> script to builder.storyParts.toList() }

    private fun run(report: GameReport, game: PgnGame, options: NarrationOptions): Pair<VideoScript, ScriptBuilder> {
        var demoted: Map<Int, PacingTier> = emptyMap()
        var trim = 0
        var builder = ScriptBuilder(report, game, options, userColor, strings, demoted, trim)
        var script = builder.build()

        // ANALYSIS_SPEC 9.7: a script that overruns the length budget gives up its least
        // interesting beats - DWELL to BRIEF first, then BRIEF to SKIP, then the FULL moments to
        // DWELL, then the turning point itself (FULL to DWELL to BRIEF), and only then the structure
        // around the story (all but one lesson, the ratings, the opening summary, the hook, the last
        // lesson) - one step at a time, re-measured each round - until it fits, and never grows to
        // fill the budget. EVERY_MOVE is the explicit request for the whole game and is exempt, exactly as it
        // is from the significance threshold.
        if (options.depth != NarrationDepth.EVERY_MOVE) {
            val budget = builder.budgetMs
            var rounds = 0
            while (script.totalEstimatedMs > budget && rounds++ < MAX_BUDGET_ROUNDS) {
                val excess = script.totalEstimatedMs - budget
                var step = builder.demotions(script, excess)
                if (step.isEmpty()) step = builder.turningPointDemotion(PacingTier.DWELL)
                if (step.isEmpty()) step = builder.turningPointDemotion(PacingTier.BRIEF)
                if (step.isNotEmpty()) {
                    demoted = demoted + step
                } else if (trim < MAX_STRUCTURE_TRIM) {
                    trim++
                } else {
                    break
                }
                builder = ScriptBuilder(report, game, options, userColor, strings, demoted, trim)
                script = builder.build()
            }
        }
        // ANALYSIS_SPEC 9.8: the pace is laid over the finished story, after the budget has decided what
        // is told, so every pace tells the same story in the same words (and plays the same cached
        // narration); only the silent board time around the key moves differs.
        return builder.paced(script) to builder
    }

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

        /**
         * The length a game of [fullMoves] moves may run to (ANALYSIS_SPEC 9.7), in milliseconds:
         *
         *     min(720 s, 120 s + 14 s x moves, 23 s x moves - 32 s)
         *
         * The first two terms are the Round 13 budget, which a normal game (17 moves and up) still
         * gets unchanged. The third is the ramp for a tiny game: a four-move scholar's mate used to be
         * allowed 176 s because of the 120 s base and came out at 3 min 43 s; it is now allowed 60 s.
         * A budget is a ceiling and never a target: nothing is padded to reach it.
         */
        fun budgetMs(fullMoves: Int): Long =
            minOf(
                BUDGET_MAX_MS,
                BUDGET_BASE_MS + BUDGET_PER_MOVE_MS * fullMoves,
                BUDGET_RAMP_PER_MOVE_MS * fullMoves - BUDGET_RAMP_OFFSET_MS
            ).coerceAtLeast(BUDGET_MIN_MS)

        const val BUDGET_BASE_MS = 120_000L
        const val BUDGET_PER_MOVE_MS = 14_000L
        const val BUDGET_MAX_MS = 720_000L
        const val BUDGET_RAMP_PER_MOVE_MS = 23_000L
        const val BUDGET_RAMP_OFFSET_MS = 32_000L
        const val BUDGET_MIN_MS = 20_000L

        /**
         * The most [VideoPace] time a video of [fullMoves] moves may add on top of its story
         * (ANALYSIS_SPEC 9.8): [PACING_CAP_FRACTION] of the §9.7 budget. The five recorded games use
         * 2.5 to 11 percent of their budget at RELAXED, so the cap only binds on an unusual game.
         */
        fun pacingCapMs(fullMoves: Int): Long = (budgetMs(fullMoves) * PACING_CAP_FRACTION).toLong()

        const val PACING_CAP_FRACTION = 0.15

        /** The structure trims (lessons, ratings, opening summary), one level per round; see [ScriptBuilder]. */
        private const val MAX_STRUCTURE_TRIM = 5

        /** Each round demotes at least one beat, so this only bounds a pathological game. */
        private const val MAX_BUDGET_ROUNDS = 64
        private val WHITESPACE = Regex("\\s+")
    }
}

// ---------------------------------------------------------------------------
// Implementation
// ---------------------------------------------------------------------------

/**
 * One story sentence as it was said (ANALYSIS_SPEC §9.9): [id] the sentence kind (`Sentence` class name), [text] the
 * words as rendered, [plies] the plies it is about (the move it follows, a phase boundary's ply, a skipped stretch's two
 * ends). What the story audit reads.
 */
internal class StoryPart(val id: String, val text: String, val plies: List<Int>)

/** Why a ply earned a spoken beat. Maps onto [SegmentKind] once the segment is emitted. */
private enum class BeatKind { MISSED_TACTIC, ERROR, FOUND_TACTIC, THREAT, KEY, NORMAL }

/**
 * How much of the video a ply gets (ANALYSIS_SPEC 9.7), declared in order of weight so `min` and
 * `max` mean "lighter" and "heavier".
 *
 *  - [SKIP]: no beat of its own; a run of three or more becomes one "skip ahead" connective.
 *  - [BRIEF]: a sentence or two, never a walk of the missed line.
 *  - [DWELL]: a fuller explanation, and a variation of at most [ScriptBuilder.DWELL_EXCURSION_PLIES]
 *    plies for a missed tactic, with no "can you find it?" pause.
 *  - [FULL]: the puzzle pause, the reveal and the whole excursion. At most
 *    [ScriptBuilder.MAX_FULL_MOMENTS] per game.
 */
private enum class PacingTier { SKIP, BRIEF, DWELL, FULL }

private class ScriptBuilder(
    rawReport: GameReport,
    private val game: PgnGame,
    private val options: NarrationOptions,
    private val userColor: Color?,
    private val strings: NarrationStrings,
    /** Per-ply tier ceilings the length budget has imposed (ANALYSIS_SPEC 9.7). */
    private val demoted: Map<Int, PacingTier> = emptyMap(),
    /**
     * How much of the structure around the story the length budget has taken (ANALYSIS_SPEC 9.7):
     * 1 keeps only the first lesson, without the textbook offer; 2 also drops the rating and
     * error-count sentences of the summary (and the caveat about the ratings); 3 also drops the opening
     * summary; 4 also drops the intro's hook; 5 also drops the lessons. Each level is only reached when
     * everything lighter has already gone, the turning point included.
     */
    private val trim: Int = 0
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

    /**
     * The key-moment beats whose narration names the better move while the board only annotates the
     * position: an error told at DWELL or FULL length without a detour, the turning point that was an
     * inaccuracy, and a brief beat on one of the report's key moments ([keyMomentPlies], the five
     * costliest errors). [paced] plays the engine's line on them after the speech (ANALYSIS_SPEC 9.8, V2).
     */
    private val betterMoveBeats = HashSet<Int>()
    private val chapters = ArrayList<ScriptChapter>()
    private var pendingChapter: String? = null

    /**
     * Runs of plies that tell one story (ANALYSIS_SPEC §9). Used by the significance filter so a
     * combination whose payoff only lands on its last move is not pruned move-by-move.
     */
    private val sequences: List<MoveSequence> = MoveSequenceDetector.detect(annotations)

    private val keyMomentPlies = report.keyMoments.map { it.ply }.toSet()

    /**
     * The White win-percent of every position (index `k` = the board after ply `k`, 0 = the start), the series
     * [GamePhases] reads (ANALYSIS_SPEC §9.9). The report's own graph when it has one position per ply plus the
     * start, else rebuilt from the annotations.
     */
    private val whiteSeries: List<Double> =
        if (annotations.isNotEmpty() && report.evalGraph.size == annotations.size + 1) {
            report.evalGraph
        } else if (annotations.isEmpty()) {
            emptyList()
        } else {
            listOf(flipToWhite(annotations[0].winPercentBefore, annotations[0].color)) +
                annotations.map { flipToWhite(it.winPercentAfter, it.color) }
        }

    /** The stretches of the game in which one side held one standing, and the boundaries between them. */
    private val phases: List<Phase> = GamePhases.detect(whiteSeries)
    private val shiftAt: Map<Int, PhaseShift> = GamePhases.shifts(phases).associateBy { it.ply }

    /** The plies whose beat already said how the standing changed (an error beat says it in its own words). */
    private val phaseTold = HashSet<Int>().also { told ->
        // The opening summary says where the book left the game; the boundaries inside the book are not told again.
        val bookEnd = annotations.lastOrNull { it.classification == MoveClassification.BOOK }?.ply ?: 0
        for (ply in shiftAt.keys) if (ply <= bookEnd) told.add(ply)
    }

    /** The last ply of a mating sequence that was announced or stepped, and who was mating (A5). */
    private var lastMatePly = -10
    private var lastMateColor: Color? = null

    /**
     * The one move that changed the game most. Book moves and forced moves are never it: theory is
     * not a decision, and a forced move is not a choice, so neither can be where the game turned.
     */
    private val turningPoint: MoveAnnotation? = annotations
        .filter { it.loss > 0.5 && it.classification != MoveClassification.BOOK && it.classification != MoveClassification.FORCED }
        .maxWithOrNull(compareBy({ it.loss }, { -it.ply }))

    /** Every ply's pacing tier, after the FULL cap and any budget demotions (ANALYSIS_SPEC 9.7). */
    private val tiers: Map<Int, PacingTier> = planTiers()

    /** The total length this game is allowed (ANALYSIS_SPEC 9.7). */
    val budgetMs: Long = VideoScriptGenerator.budgetMs((annotations.size + 1) / 2)

    // -----------------------------------------------------------------------
    // The one door to the words
    // -----------------------------------------------------------------------

    /** The words for [sentence] in this language, rotating through the locale's variants. */
    private fun say(sentence: Sentence): String =
        phrases.pick(sentence.poolKey, strings.render(sentence, options.style))

    /** Every story sentence said, for the audit; see [StoryPart]. */
    val storyParts = ArrayList<StoryPart>()

    /** [say] for a sentence that makes a claim the story audit re-derives; remembers what was said and about which plies. */
    private fun story(sentence: Sentence, vararg plies: Int): String {
        val text = say(sentence)
        storyParts.add(StoryPart(sentence::class.simpleName ?: "sentence", text, plies.toList()))
        return text
    }

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

        val header = header()
        return VideoScript(
            title = videoTitle(),
            subtitle = videoSubtitle(),
            segments = segments,
            chapters = chapters,
            totalEstimatedMs = segments.sumOf { it.estimatedSpeechMs + it.holdAfterMs },
            userColor = userColor,
            header = header,
            whiteAccuracy = report.white.accuracy,
            blackAccuracy = report.black.accuracy,
            whiteEstimatedRating = report.white.estimatedRating,
            blackEstimatedRating = report.black.estimatedRating,
            recap = GameRecap.build(
                report, header.whiteName, header.blackName,
                userColor.takeIf { options.addressUserAsYou }, strings, options.viewerGender
            ),
            // V4: the final-numbers card's table, straight from the report's counts.
            qualityCounts = if (annotations.isEmpty()) emptyList() else QUALITY_TABLE_CLASSES.map {
                QualityCount(it, count(report.white, it), count(report.black, it))
            }
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

        // The cold open (ANALYSIS_SPEC §9.9): one sentence about the stakes, proven by the game's phases, then how it
        // ended; a game whose graph proves no such sentence keeps the plain result.
        val arc = StoryArc.of(phases, report.result, annotations.size)
        if (arc != null) {
            sb.append(story(stakesSentence(arc))).append(' ').append(endingSentence()).append(' ')
        } else {
            sb.append(resultSentence()).append(' ')
        }
        if (userColor != null && options.addressUserAsYou && coach) {
            sb.append(say(Sentence.IntroYouWere(subj(userColor)))).append(' ')
        }
        if (trim < 4) sb.append(hookSentence())

        add(
            kind = SegmentKind.INTRO,
            ply = null,
            narration = sb.toString(),
            caption = videoTitle(),
            // The title card's contract (R6c): the heading is the title, and the lines are exactly
            // [subtitle, accuracy], each fact once. The subtitle is the result with the move count, then
            // the opening, on ONE line; the accuracy line is whole percent, as the Summary writes it.
            // The names are in the heading only and are not repeated by the renderer.
            // V4: the card is drawn on the game's starting position, so the board is there from the first
            // frame (chess.com's Game Review opens on the board, the players and the coach's words around it).
            board = BoardDirective.Card(
                heading = videoTitle(),
                lines = listOf(
                    listOfNotNull(
                        say(Sentence.CardResultLine(report.result, (annotations.size + 1) / 2)),
                        report.openingName?.let { say(Sentence.CardOpeningLine(it, report.openingEco)) },
                    ).joinToString(CARD_SEPARATOR),
                    say(Sentence.CardAccuracyLine(accuracyWhole(report.white.accuracy), accuracyWhole(report.black.accuracy)))
                ),
                boardFen = annotations.firstOrNull()?.fenBefore?.takeIf { it.isNotBlank() } ?: game.startFen ?: Position.STANDARD_START_FEN
            ),
            // The eval bar beside that board: the engine's number for the starting position.
            eval = if (annotations.isNotEmpty()) evalBefore(1) else null
        )
    }

    private fun stakesSentence(arc: StoryArc): Sentence = when (arc) {
        is StoryArc.Comeback -> Sentence.StakesComeback(arc.winner, arc.moves)
        is StoryArc.TookOver -> Sentence.StakesTookOver(arc.winner, arc.moveNumber, arc.fromTheStart)
        StoryArc.TightDraw -> Sentence.StakesTightDraw
        is StoryArc.WastedLead -> Sentence.StakesWastedLead(arc.leader, arc.moves)
    }

    /** How the game ended, said after a stakes sentence that has already named the winner. */
    private fun endingSentence(): String {
        val fullMoves = (annotations.size + 1) / 2
        val mated = annotations.lastOrNull()?.san?.endsWith("#") == true
        return when (report.result) {
            "1/2-1/2" -> say(Sentence.ResultDraw(fullMoves))
            else -> if (mated) story(Sentence.ResultEndsInMate(fullMoves)) else story(Sentence.ResultLasted(fullMoves))
        }
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
            biggest != null && biggest.loss >= 30.0 -> story(Sentence.HookBigTurn(biggest.moveNumber), biggest.ply)
            biggest != null && biggest.loss >= 12.0 -> story(Sentence.HookClose(biggest.moveNumber), biggest.ply)
            biggest != null -> story(Sentence.HookClean, biggest.ply)
            else -> say(Sentence.HookNoMistakes)
        }
    }

    private fun openingSummary() {
        if (trim >= 3) return
        val name = report.openingName
        val eco = report.openingEco
        val family = NarrationVocabulary.openingFamily(name)

        val sb = StringBuilder()
        sb.append(if (name != null) say(Sentence.OpeningLead(name)) else say(Sentence.OpeningNoBookName)).append(' ')
        sb.append(if (family != null) say(Sentence.OpeningPlan(family)) else genericOpeningPlan())

        val bookEndPly = annotations.lastOrNull { it.classification == MoveClassification.BOOK }?.ply

        // One ply drives the held position AND the eval bar, so the two can never disagree.
        val openingPly = bookEndPly ?: annotations.getOrNull(min(5, annotations.size - 1))?.ply

        // How the opening left the game (ANALYSIS_SPEC §9.9): the band of the board after the last book move.
        val standingThere = openingPly?.let { whiteSeries.getOrNull(it) }?.let { PhaseStanding.of(it) }
        if (bookEndPly != null && bookEndPly >= 4 && standingThere != null) {
            sb.append(' ').append(story(Sentence.TheoryEnds((bookEndPly + 1) / 2, standingThere), openingPly!!))
        } else if (standingThere != null) {
            sb.append(' ').append(story(Sentence.OpeningVerdict(standingThere), openingPly!!))
        }
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
            // A phase boundary whose beat did not say it (an error beat tells the standing in its own words).
            phaseBeatIfUntold(ply)
            // A turning point the budget has taken down to BRIEF is told by its own beat alone.
            if (turningPoint != null && turningPoint.ply == ply && tiers[ply] != PacingTier.BRIEF) {
                chapter(Sentence.ChapterTurningPoint)
                turningPointSegment(turningPoint)
            }
            previous = ply
        }
    }

    /**
     * Which plies get their own spoken beat: every ply the pacing plan ([planTiers]) did not SKIP.
     *
     * [NarrationDepth.EVERY_MOVE] is the one exception, and it is deliberate: that depth means
     * "every move, I want the complete walkthrough", which is an explicit request that neither the
     * threshold nor the length budget has any business overruling. It is the escape hatch, and it is
     * documented as one on the enum.
     */
    private fun selectPlies(): Set<Int> {
        if (annotations.isEmpty()) return emptySet()
        if (options.depth == NarrationDepth.EVERY_MOVE) return annotations.map { it.ply }.toSet()
        return tiers.filter { it.value != PacingTier.SKIP }.keys
    }

    // -----------------------------------------------------------------------
    // Pacing tiers (ANALYSIS_SPEC 9.7) and the significance threshold (9.2)
    // -----------------------------------------------------------------------

    /**
     * Gives every ply a [PacingTier].
     *
     * Three things compose, in this order, and none overrides another:
     *  1. **Depth** picks the candidate plies ([NarrationDepth.MISTAKES_ONLY] narrows them to the
     *     errors; the others consider every ply).
     *  2. The **significance threshold** (ANALYSIS_SPEC 9.2) prunes: a ply that did not move the
     *     evaluation, and belongs to no sequence and carries no surviving tactic, is SKIP at any
     *     tier. Mate always survives. A threshold of 0 prunes nothing.
     *  3. The **tier rules** ([baseTier]) say how much each survivor gets, and at most
     *     [MAX_FULL_MOMENTS] of them are FULL, ranked by loss, the turning point first.
     *
     * The result is never empty: if everything was pruned, the single largest-swing ply (ties to
     * the earlier one) is narrated, because a review with no body between the intro and the outro
     * is a worse failure than one quiet move.
     *
     * Structural segments - intro, opening summary, outro summary (which speaks the result) and the
     * lessons - are emitted outside this path entirely and are never filtered.
     */
    private fun planTiers(): Map<Int, PacingTier> {
        if (annotations.isEmpty()) return emptyMap()
        val depth = options.depth
        val everyMove = depth == NarrationDepth.EVERY_MOVE
        val pruning = !everyMove && options.significanceThresholdCp > 0
        val candidates: Set<Int>? = if (depth == NarrationDepth.MISTAKES_ONLY) mistakesOnlyCandidates() else null

        val tier = LinkedHashMap<Int, PacingTier>()
        for (a in annotations) {
            var t = baseTier(a)
            if (candidates != null && a.ply !in candidates) t = PacingTier.SKIP
            if (pruning && t != PacingTier.SKIP && !isMate(a) && !isSignificant(a.ply)) t = PacingTier.SKIP
            tier[a.ply] = t
        }

        // A phase boundary is told at the move that caused it (ANALYSIS_SPEC §9.9): at least a BRIEF beat, whatever
        // else the plan said about the ply. Not in MISTAKES_ONLY, which is only the errors.
        if (depth != NarrationDepth.MISTAKES_ONLY) {
            val bookEnd = annotations.lastOrNull { it.classification == MoveClassification.BOOK }?.ply ?: 0
            for (ply in shiftAt.keys) if (ply > bookEnd && tier[ply] == PacingTier.SKIP) tier[ply] = PacingTier.BRIEF
        }

        // The turning point is the story of the game, so it is always FULL (if it survived at all).
        turningPoint?.let { if (tier[it.ply] != PacingTier.SKIP) tier[it.ply] = PacingTier.FULL }

        // At most MAX_FULL_MOMENTS full treatments; the rest are still told, at DWELL length.
        annotations
            .filter { tier[it.ply] == PacingTier.FULL }
            .sortedWith(
                compareByDescending<MoveAnnotation> { it.ply == turningPoint?.ply }
                    .thenByDescending { drama(it) }
                    .thenBy { it.ply }
            )
            .drop(MAX_FULL_MOMENTS)
            .forEach { tier[it.ply] = PacingTier.DWELL }

        // Length-budget demotions, never below the floor: the checkmate keeps a DWELL beat.
        for ((ply, ceiling) in demoted) {
            val current = tier[ply] ?: continue
            if (current > ceiling) tier[ply] = ceiling
        }
        // ...and so do the protected beats, but only against the *budget*: a protected ply that the
        // threshold pruned (nothing happened on it) stays pruned.
        for (a in annotations) {
            val floored = isMate(a) || (isProtected(a) && baseTier(a) != PacingTier.SKIP && tier[a.ply] != PacingTier.SKIP)
            if (floored && (tier[a.ply] ?: PacingTier.SKIP) < PacingTier.DWELL) tier[a.ply] = PacingTier.DWELL
        }

        if (!everyMove && tier.values.all { it == PacingTier.SKIP }) {
            val fallback = if (candidates != null && candidates.isEmpty()) {
                annotations.last()
            } else {
                annotations.maxWithOrNull(compareBy({ swingCp(it) }, { -it.ply }))
            }
            fallback?.let { tier[it.ply] = PacingTier.BRIEF }
        }
        return tier
    }

    /** The plies MISTAKES_ONLY considers: the errors, the key moments, the turning point, the mate. */
    private fun mistakesOnlyCandidates(): Set<Int> {
        val out = LinkedHashSet<Int>()
        for (a in annotations) {
            when (beatKind(a)) {
                BeatKind.MISSED_TACTIC, BeatKind.ERROR -> out.add(a.ply)
                else -> Unit
            }
        }
        out.addAll(keyMomentPlies)
        turningPoint?.let { out.add(it.ply) }
        annotations.lastOrNull()?.let { if (isMate(it)) out.add(it.ply) }
        return out
    }

    private fun isMate(a: MoveAnnotation): Boolean = a.san.endsWith("#")

    /**
     * How much a FULL candidate deserves one of the [MAX_FULL_MOMENTS] slots (ANALYSIS_SPEC 9.7).
     * Loss, except that a brilliancy - which by definition loses nothing - scores as a
     * [BRILLIANT_DRAMA]-point loss, a mid-sized blunder, so in a game with three errors it can still
     * take a slot instead of always ranking last.
     */
    private fun drama(a: MoveAnnotation): Double =
        if (a.classification == MoveClassification.BRILLIANT) max(a.loss, BRILLIANT_DRAMA) else a.loss

    /**
     * A beat the length budget may not shorten below DWELL: brilliant and great moves, and a move
     * that plays a forced mate (the queen sacrifice that mates is the point of the game, however
     * the engine labels it). The checkmate itself is protected separately.
     */
    private fun isProtected(a: MoveAnnotation): Boolean =
        a.classification == MoveClassification.BRILLIANT || a.classification == MoveClassification.GREAT ||
            a.tacticsFound.any { it.type in MATING_MOTIFS && it.confidence >= FOUND_TACTIC_CONFIDENCE }

    /**
     * What a ply earns on its own merits, before the FULL cap and the length budget. ANALYSIS_SPEC
     * 9.7 is the authoritative statement of these rules; every threshold is named there.
     */
    private fun baseTier(a: MoveAnnotation): PacingTier {
        val c = a.classification
        if (isMate(a)) return PacingTier.DWELL
        if (c == MoveClassification.BOOK || c == MoveClassification.FORCED) return PacingTier.SKIP
        if (c == MoveClassification.BLUNDER || c == MoveClassification.MISS || c == MoveClassification.BRILLIANT) {
            return PacingTier.FULL
        }
        if (c == MoveClassification.MISTAKE) {
            val missed = significantMissed(a)
            return if (missed != null && winsMaterialOrMate(a, missed)) PacingTier.FULL else PacingTier.DWELL
        }
        // An inaccuracy is told in a sentence or two and never earns a walk of the line it missed,
        // however much that line would have won: that is what made a 17-move game eleven minutes.
        if (c == MoveClassification.INACCURACY) return PacingTier.BRIEF
        if (c == MoveClassification.GREAT) return PacingTier.DWELL
        // Taking back is the second half of a trade, not a decision.
        if (isRecapture(a)) return PacingTier.SKIP
        if (a.tacticsFound.any { it.confidence >= FOUND_TACTIC_CONFIDENCE }) return PacingTier.DWELL
        // Everything else is routine unless it moved the evaluation (or carried a sequence): a
        // significant-but-small swing earns a line, a quiet move earns nothing.
        return if (isSignificant(a.ply)) PacingTier.BRIEF else PacingTier.SKIP
    }

    /**
     * A missed tactic big enough to stop the video for: it wins material, or it mates.
     *
     * The tactic's own [TacticInstance.materialSwing] is only a lower bound - the highest-confidence
     * motif on a move is often a clearance or a deflection that is "worth" nothing by itself while
     * the line it opens wins a rook - so a quiet motif is also judged by what its line actually
     * does on the board.
     */
    private fun winsMaterialOrMate(a: MoveAnnotation, tactic: TacticInstance): Boolean =
        tactic.materialSwing >= PUZZLE_MIN_SWING_CP || tactic.type in MATING_MOTIFS ||
            moverHadForcedMate(a) || missedLineWins(a, tactic)

    private fun missedLineWins(a: MoveAnnotation, tactic: TacticInstance): Boolean {
        val plies = excursionPlies(a, tactic, MAX_EXCURSION_PLIES)
        if (plies.isEmpty()) return false
        val start = plies.first().before
        return plies.any { p ->
            p.after.isCheckmate() ||
                (p.after.sideToMove == tactic.byColor &&
                    ExchangeEvaluator.netGain(start, p.after, tactic.byColor) >= PUZZLE_MIN_SWING_CP)
        }
    }

    /** True when the engine saw a forced mate for the side that moved (the score is White-relative). */
    private fun moverHadForcedMate(a: MoveAnnotation): Boolean {
        val mate = a.mateInBefore ?: return false
        return mate != 0 && (mate > 0) == (a.color == Color.WHITE)
    }

    // -----------------------------------------------------------------------
    // The pace (ANALYSIS_SPEC 9.8, V3)
    // -----------------------------------------------------------------------

    /**
     * [script] with [NarrationOptions.pace] laid over it: board time around the key moves, and the narrated
     * best lines. The story's segments keep their words and their order; only [ScriptSegment.leadIn] and
     * [ScriptSegment.holdAfterMs] change on them, the best lines' segments are inserted after their key
     * moments (V4), and [VideoScript.pacingMs] says how much all of it adds in total.
     *
     *  - A **key move** (see [isKeyMoveBeat]) gets a lead-in: the one or two game moves the story
     *    skipped since the board last showed a game position are played first, at the line rate, and
     *    then the position before the move is held still with its two squares lit for
     *    [VideoPace.keyLeadInMs], unless the beat before is already a still picture of exactly that
     *    position (the "back to the game" beat after a detour, a puzzle), which is that pause. After
     *    its narration the result is held for [VideoPace.keyHoldAfterMs], unless the next beat is
     *    another key move starting from it (its own pause shows that position).
     *  - Every move of a played-out line (a detour's hypothetical moves) is on screen for at least
     *    [VideoPace.lineMoveMinMs], and the line's final position for [VideoPace.lineFinalHoldMs] more.
     *  - A key moment on a MISTAKE, MISS or BLUNDER whose narration names the better move over a still
     *    board is followed by the engine's best line (V2, [SegmentBestLine], [bestLinePlan]), narrated since
     *    V4: one [SegmentKind.BEST_LINE] segment per move that says the move ([Sentence.LineMove]), each on
     *    screen at least [VideoPace.lineMoveMinMs], the final position held [VideoPace.lineFinalHoldMs] more,
     *    then "Back to the game now." ([Sentence.BackToTheGame]) over the game's position again. At most
     *    [BestLines.VIDEO_MAX_PLIES] plies, and all of it (speech included) is pace time inside the room the
     *    rest of the pace time leaves under the cap at the slowest pace, so the §9.7 story is untouched.
     */
    fun paced(script: VideoScript): VideoScript {
        val times = PaceTimes.of(options.pace)
        val cap = VideoScriptGenerator.pacingCapMs((annotations.size + 1) / 2)
        // V2: which key moments play their best line, and how many plies each, is decided once, at the
        // slowest pace, from the room the V3 pace time leaves under the cap. So every pace plays (and says)
        // the same moves, and the lines never push the V3 pauses and holds down (spec 9.8).
        val headroom = cap - paced(script, PaceTimes.of(VideoPace.RELAXED), emptyMap()).pacingMs
        val plan = bestLinePlan(script, headroom)
        val full = paced(script, times, plan)
        // The pace sits outside the story budget, but not without limit (spec 9.8): a game with an
        // unusual number of key moves has every pace time scaled down until it fits the cap.
        if (full.pacingMs <= cap) return full
        return paced(script, times.scaled(cap.toDouble() / full.pacingMs), plan)
    }

    /**
     * The key moments that play their best line after the speech, and how many plies each (V2, spec 9.8):
     * the beats that name the better move over a still board ([betterMoveBeats]) on a MISTAKE, MISS or
     * BLUNDER (an inaccuracy is BRIEF, "never a walk of the missed line", spec 9.7), most important first
     * (tier, then loss, then the earlier move). Each gets up to [BestLines.VIDEO_MAX_PLIES] plies while the
     * line's time at [VideoPace.RELAXED] ([lineCostMs]: since V4 its spoken moves and its "back to the game"
     * sentence included) fits in [headroomMs], and as many as fit (at least one) when four do not; a moment
     * for which not even one ply fits keeps its arrow. Keyed by segment index.
     */
    private fun bestLinePlan(script: VideoScript, headroomMs: Long): Map<Int, Int> {
        if (headroomMs <= 0) return emptyMap()
        val relaxed = PaceTimes.of(VideoPace.RELAXED)
        val candidates = script.segments
            .filter { it.index in betterMoveBeats && it.board is BoardDirective.Annotate }
            .mapNotNull { seg ->
                val a = annotations.getOrNull((seg.ply ?: return@mapNotNull null) - 1) ?: return@mapNotNull null
                if (!isErrorClass(a)) return@mapNotNull null
                val board = seg.board as BoardDirective.Annotate
                if (boardKey(board.fen) != boardKey(a.fenBefore)) return@mapNotNull null
                val line = bestLineTail(seg, board, relaxed, BestLines.VIDEO_MAX_PLIES) ?: return@mapNotNull null
                Triple(seg.index, a, line)
            }
            .sortedWith(
                compareByDescending<Triple<Int, MoveAnnotation, SegmentBestLine>> { tiers[it.second.ply] ?: PacingTier.SKIP }
                    .thenByDescending { it.second.loss }
                    .thenBy { it.second.ply }
            )
        val plan = LinkedHashMap<Int, Int>()
        var left = headroomMs
        for ((index, _, line) in candidates) {
            val plies = (line.uci.size downTo 1).firstOrNull { k -> lineCostMs(line, k, relaxed) <= left } ?: continue
            plan[index] = plies
            left -= lineCostMs(line, plies, relaxed)
        }
        return plan
    }

    /**
     * The pace time the first [plies] moves of [line] add at [pace] (V4): what [bestLineSegments] puts into
     * the script for them, i.e. each move's speech estimate plus the hold that keeps it on screen at least
     * [PaceTimes.lineMoveMinMs] (the timeline's 900 ms floor and 250 ms gap counted, as the excursion's
     * moves count them), the final position's [PaceTimes.lineFinalHoldMs], and the "back to the game"
     * sentence.
     */
    private fun lineCostMs(line: SegmentBestLine, plies: Int, pace: PaceTimes): Long {
        var total = 0L
        for (k in 0 until minOf(plies, line.uci.size)) {
            val speech = VideoScriptGenerator.estimateSpeechMs(line.spoken[k], options.speechWpm)
            total += speech + lineMoveHold(speech, pace)
        }
        return total + pace.lineFinalHoldMs + VideoScriptGenerator.estimateSpeechMs(line.backToGame, options.speechWpm)
    }

    /** The hold that keeps a spoken line move on screen at least [PaceTimes.lineMoveMinMs] (floor and gap counted). */
    private fun lineMoveHold(speechMs: Long, pace: PaceTimes): Long {
        val onScreen = max(speechMs, ScriptTiming.MIN_SEGMENT_MS) + ScriptTiming.INTER_SEGMENT_GAP_MS
        return (pace.lineMoveMinMs - onScreen).coerceAtLeast(0L)
    }

    private fun paced(script: VideoScript, pace: PaceTimes, linePlan: Map<Int, Int>): VideoScript {
        val segs = script.segments
        val out = ArrayList<ScriptSegment>(segs.size)
        // Where each of the story's segments lands once the best lines' segments are inserted (V4).
        val newIndex = IntArray(segs.size)
        var pacing = 0L
        for ((i, s) in segs.withIndex()) {
            var seg = s
            var after: List<ScriptSegment> = emptyList()
            val d = s.board
            if (d is BoardDirective.PlayMove && isKeyMoveBeat(s)) {
                val a = annotations[s.ply!! - 1]
                val prev = segs.getOrNull(i - 1)
                val approach = approachPlies(a.ply, prev?.let { endBoard(it.board) })
                val prevIsStillHere = prev != null && approach.isEmpty() &&
                    (prev.board is BoardDirective.Annotate || prev.board is BoardDirective.Hold) &&
                    endBoard(prev.board)?.let { boardKey(it) } == boardKey(a.fenBefore)
                val pause = if (prevIsStillHere) 0L else pace.keyLeadInMs
                if (approach.isNotEmpty() || pause > 0) {
                    seg = seg.copy(
                        leadIn = SegmentLeadIn(
                            fen = approach.firstOrNull()?.fenBefore ?: a.fenBefore,
                            approachUci = approach.map { it.uci },
                            approachSan = approach.map { it.san },
                            approachCaptions = approach.map { caption(it) },
                            stepMs = if (approach.isEmpty()) 0L else pace.lineMoveMinMs,
                            pauseMs = pause,
                            highlightSquares = if (a.uci.length >= 4) listOf(a.uci.substring(0, 2), a.uci.substring(2, 4)) else emptyList(),
                            eval = evalBefore(a.ply)
                        )
                    )
                    pacing += seg.leadInMs
                }
                val next = segs.getOrNull(i + 1)
                val nextBoard = next?.board as? BoardDirective.PlayMove
                val nextIsKeyFromHere = next != null && nextBoard != null && isKeyMoveBeat(next) &&
                    boardKey(nextBoard.fen) == boardKey(a.fenAfter)
                if (!nextIsKeyFromHere) {
                    seg = seg.copy(holdAfterMs = seg.holdAfterMs + pace.keyHoldAfterMs)
                    pacing += pace.keyHoldAfterMs
                }
            } else if (d is BoardDirective.PlayMove && s.kind == SegmentKind.MISSED_TACTIC && s.classification == null) {
                // A move of a played-out line: never on screen for less than the line rate.
                val onScreen = max(s.estimatedSpeechMs, ScriptTiming.MIN_SEGMENT_MS) + s.holdAfterMs + ScriptTiming.INTER_SEGMENT_GAP_MS
                val extra = (pace.lineMoveMinMs - onScreen).coerceAtLeast(0L)
                if (extra > 0) {
                    seg = seg.copy(holdAfterMs = seg.holdAfterMs + extra)
                    pacing += extra
                }
            } else if (s.kind == SegmentKind.MISSED_TACTIC && d is BoardDirective.Annotate && isAfterLineMove(segs, i)) {
                // The line's final position (its payoff beat): held a little longer.
                seg = seg.copy(holdAfterMs = seg.holdAfterMs + pace.lineFinalHoldMs)
                pacing += pace.lineFinalHoldMs
            } else if (d is BoardDirective.Annotate && linePlan.containsKey(s.index)) {
                // V2 + V4: the better move the narration named is played out after the speech, one spoken
                // move at a time, and then the board comes back to the game.
                bestLineTail(s, d, pace, linePlan.getValue(s.index))?.let { line ->
                    seg = seg.copy(bestLine = line)
                    after = bestLineSegments(s, d, line, pace)
                    pacing += after.sumOf { it.estimatedSpeechMs + it.holdAfterMs }
                }
            }
            newIndex[i] = out.size
            out.add(seg)
            out.addAll(after)
        }
        val segments = out.mapIndexed { k, x -> if (x.index == k) x else x.copy(index = k) }
        return script.copy(
            segments = segments,
            chapters = script.chapters.map { c -> c.copy(startSegmentIndex = newIndex.getOrElse(c.startSegmentIndex) { segments.size }) },
            totalEstimatedMs = segments.sumOf { it.estimatedSpeechMs + it.leadInMs + it.holdAfterMs },
            pacingMs = pacing
        )
    }

    /**
     * The engine's best line for the key moment [s] is about, as the video plays it after the speech
     * (ANALYSIS_SPEC 9.8, V2): the same line the Board's line mode shows ([BestLines.bestFor], so the
     * same legal moves and the same depth rule), cut to [plies] (at most [BestLines.VIDEO_MAX_PLIES], from
     * [bestLinePlan]), with the pace's line rate and final hold, each move's caption and (V4) the sentence
     * that says it, and the sentence said when the board comes back. Null when the annotation has no line or
     * the line does not start from the position the beat shows.
     */
    private fun bestLineTail(s: ScriptSegment, d: BoardDirective.Annotate, pace: PaceTimes, plies: Int): SegmentBestLine? {
        val a = annotations.getOrNull((s.ply ?: return null) - 1) ?: return null
        if (boardKey(d.fen) != boardKey(a.fenBefore)) return null
        val line = BestLines.bestFor(a) ?: return null
        val steps = line.steps.take(minOf(plies, BestLines.VIDEO_MAX_PLIES))
        if (steps.isEmpty()) return null
        val first = steps.first()
        // What each move's segment says: the move in words, read off the board it is played on.
        val spoken = ArrayList<String>(steps.size)
        var pos = safeFen(line.startFen)
        for (step in steps) {
            val move = SpokenChess.moveOrNull(pos, step.uci) ?: return null
            spoken.add(speak(Sentence.LineMove(SpokenChess.describe(pos, move))))
            pos = pos.makeMove(move)
        }
        return SegmentBestLine(
            fen = line.startFen,
            uci = steps.map { it.uci },
            san = steps.map { it.san },
            captions = steps.indices.map { k ->
                say(Sentence.CaptionBestLine(first.moveNumber, first.color, steps.take(k + 1).map { it.san }))
            },
            stepMs = pace.lineMoveMinMs,
            finalHoldMs = pace.lineFinalHoldMs,
            spoken = spoken,
            backToGame = speak(Sentence.BackToTheGame)
        )
    }

    /**
     * The segments that play [line] after the key moment [s] (V4): one [SegmentKind.BEST_LINE] `PlayMove` per
     * move, which says the move and shows the line so far in its caption, held so it is on screen at least the
     * line rate (the last one also [PaceTimes.lineFinalHoldMs]); then the board back on the game's position
     * ([d], the beat's own picture) with "Back to the game now." Indices are set by the caller. None of the
     * line's moves was played, so they carry no verdict and no swing; the eval bar keeps the beat's (the
     * engine's best play from that position is what its evaluation already assumes).
     */
    private fun bestLineSegments(s: ScriptSegment, d: BoardDirective.Annotate, line: SegmentBestLine, pace: PaceTimes): List<ScriptSegment> {
        val a = annotations[s.ply!! - 1]
        val out = ArrayList<ScriptSegment>()
        var pos = safeFen(line.fen)
        for (k in line.uci.indices) {
            val move = SpokenChess.moveOrNull(pos, line.uci[k]) ?: break
            val speech = VideoScriptGenerator.estimateSpeechMs(line.spoken[k], options.speechWpm)
            val last = k == line.uci.lastIndex
            out.add(
                ScriptSegment(
                    index = -1,
                    kind = SegmentKind.BEST_LINE,
                    ply = a.ply,
                    narration = line.spoken[k],
                    caption = line.captions[k],
                    board = BoardDirective.PlayMove(fen = pos.toFen(), uci = line.uci[k], san = line.san[k]),
                    estimatedSpeechMs = speech,
                    holdAfterMs = lineMoveHold(speech, pace) + if (last) pace.lineFinalHoldMs else 0L,
                    speakerColor = move.color,
                    eval = s.eval,
                    moveNumber = s.moveNumber
                )
            )
            pos = pos.makeMove(move)
        }
        out.add(
            ScriptSegment(
                index = -1,
                kind = SegmentKind.KEY_MOMENT,
                ply = a.ply,
                narration = line.backToGame,
                caption = say(Sentence.CaptionBackToGame(a.moveNumber, a.color, a.san)),
                board = d,
                estimatedSpeechMs = VideoScriptGenerator.estimateSpeechMs(line.backToGame, options.speechWpm),
                speakerColor = a.color,
                eval = s.eval,
                moveNumber = s.moveNumber,
                evalSwingCp = s.evalSwingCp,
                classification = s.classification
            )
        )
        return out
    }

    /**
     * A sentence with one wording, rendered without the phrase rotation: the best line's words are decided
     * in [paced], which runs more than once per script (once per pace measured), so they must not depend on
     * how often the picker was asked. Cleaned the way [add] cleans every narration ([NotationGuard]).
     */
    private fun speak(sentence: Sentence): String =
        NotationGuard.scrub(strings.render(sentence, options.style).first(), strings.vocabulary)
            .replace(Regex("\\s+"), " ").replace(" .", ".").trim()

    private fun isAfterLineMove(segs: List<ScriptSegment>, i: Int): Boolean {
        val prev = segs.getOrNull(i - 1) ?: return false
        return prev.kind == SegmentKind.MISSED_TACTIC && prev.board is BoardDirective.PlayMove
    }

    /**
     * A beat that plays a real game move the review dwells on: the mate, a brilliant or great move, a
     * found tactic told in its own beat, and any move whose tier is DWELL or FULL (an error played
     * after its detour, a key move told in full). A routine BRIEF move is not one: the pace is for the
     * moments the owner found too fast, not for every move.
     */
    private fun isKeyMoveBeat(s: ScriptSegment): Boolean {
        val d = s.board as? BoardDirective.PlayMove ?: return false
        val a = annotations.getOrNull((s.ply ?: return false) - 1) ?: return false
        if (s.classification == null || d.uci != a.uci || d.fen != a.fenBefore) return false
        return isMate(a) ||
            a.classification == MoveClassification.BRILLIANT || a.classification == MoveClassification.GREAT ||
            s.kind == SegmentKind.FOUND_TACTIC ||
            (tiers[a.ply] ?: PacingTier.SKIP) >= PacingTier.DWELL
    }

    /**
     * The game moves between the position the board shows ([shownFen], the end of the previous beat)
     * and the position before [ply]: one or two plies the story skipped, which the lead-in plays so
     * the board never jumps. Empty when the board is already there, when it shows no game position
     * (a card, a detour's line) or when more plies are missing (a spoken "skip ahead" covers those).
     */
    private fun approachPlies(ply: Int, shownFen: String?): List<MoveAnnotation> {
        val shown = shownFen?.let { boardKey(it) } ?: return emptyList()
        if (shown == boardKey(annotations[ply - 1].fenBefore)) return emptyList()
        for (missing in 1..SegmentLeadIn.MAX_APPROACH_PLIES) {
            val first = ply - missing
            if (first < 1) break
            if (boardKey(annotations[first - 1].fenBefore) == shown) {
                return (first until ply).map { annotations[it - 1] }
            }
        }
        return emptyList()
    }

    /** The position a directive leaves on the board, or null for a card. */
    private fun endBoard(d: BoardDirective): String? = when (d) {
        is BoardDirective.PlayMove -> {
            val p = safeFen(d.fen)
            val m = SpokenChess.moveOrNull(p, d.uci)
            if (m == null) d.fen else p.makeMove(m).toFen()
        }
        is BoardDirective.PlayLine -> null
        is BoardDirective.Annotate -> d.fen
        is BoardDirective.Hold -> d.fen
        is BoardDirective.Card -> null
    }

    /** Pieces and side to move: two FENs of one position can differ in their move counters. */
    private fun boardKey(fen: String): String = fen.split(' ').take(2).joinToString(" ")

    // -----------------------------------------------------------------------
    // The length budget (ANALYSIS_SPEC 9.7)
    // -----------------------------------------------------------------------

    /**
     * The beats to give up next, to claw back about [excessMs] of speech: the lowest-interest DWELL
     * beats become BRIEF first; only when none is left do the lowest-interest BRIEF beats become
     * SKIP. The turning point, the checkmate, and every protected beat ([isProtected]: brilliant and
     * great moves, forced mates played) are never taken below DWELL, and at least one body beat
     * always survives. Only when nothing lighter is left does a FULL beat fall to DWELL, the
     * cheapest first and a brilliancy last. Savings are estimated conservatively (a DWELL beat shrinks by about half, a
     * BRIEF beat vanishes), so the loop in [VideoScriptGenerator.generate] may take another round
     * but will not overshoot into an empty review.
     */
    fun demotions(script: VideoScript, excessMs: Long): Map<Int, PacingTier> {
        val msByPly = HashMap<Int, Long>()
        for (s in script.segments) {
            val ply = s.ply ?: continue
            msByPly[ply] = (msByPly[ply] ?: 0L) + s.estimatedSpeechMs + s.holdAfterMs
        }
        fun movable(tier: PacingTier) = annotations
            .filter {
                tiers[it.ply] == tier && !isMate(it) && !isProtected(it) && it.ply != turningPoint?.ply &&
                    !(tier == PacingTier.BRIEF && it.ply in shiftAt)
            }
            .sortedWith(compareBy<MoveAnnotation> { interest(it) }.thenByDescending { it.ply })

        val out = LinkedHashMap<Int, PacingTier>()
        var saved = 0L
        val dwell = movable(PacingTier.DWELL)
        if (dwell.isNotEmpty()) {
            for (a in dwell) {
                if (saved >= excessMs) break
                out[a.ply] = PacingTier.BRIEF
                saved += (msByPly[a.ply] ?: 0L) / 2
            }
            return out
        }
        val brief = movable(PacingTier.BRIEF)
        val bodyBeats = tiers.values.count { it != PacingTier.SKIP }
        for (a in brief) {
            if (saved >= excessMs || bodyBeats - out.size <= 1) break
            out[a.ply] = PacingTier.SKIP
            saved += msByPly[a.ply] ?: 0L
        }
        if (out.isNotEmpty()) return out

        // Last resort, after everything lighter is gone: a game so short that its FULL moments alone
        // overrun the budget gives up the *least* costly of them to DWELL length. The turning point
        // stays FULL. (A 17-move game never gets here; a six-move game with three blunders does.)
        val full = annotations
            .filter { tiers[it.ply] == PacingTier.FULL && !isMate(it) && it.ply != turningPoint?.ply }
            .sortedWith(
                compareBy<MoveAnnotation> { it.classification == MoveClassification.BRILLIANT }
                    .thenBy { drama(it) }
                    .thenByDescending { it.ply }
            )
        for (a in full) {
            if (saved >= excessMs) break
            out[a.ply] = PacingTier.DWELL
            saved += (msByPly[a.ply] ?: 0L) / 2
        }
        return out
    }

    /**
     * The turning point gives way, one step at a time, only when everything else is gone (the
     * budget outranks "the turning point is always FULL" for a game so short that its one story
     * does not fit): FULL to DWELL, then DWELL to BRIEF, both before any of the structure around the
     * story (the lessons, the ratings, the opening summary) is trimmed. A protected beat (brilliant, great, a forced mate played) and the checkmate never go below
     * DWELL. Returns the new ceiling for the turning point's ply, or nothing.
     */
    fun turningPointDemotion(toTier: PacingTier): Map<Int, PacingTier> {
        val tp = turningPoint ?: return emptyMap()
        val current = tiers[tp.ply] ?: return emptyMap()
        if (current <= toTier || isMate(tp)) return emptyMap()
        if (toTier < PacingTier.DWELL && isProtected(tp)) return emptyMap()
        return mapOf(tp.ply to toTier)
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

    /** A named chapter for each FULL moment, except the turning point, which has its own. */
    private fun chapterPlies(selected: Set<Int>): Set<Int> = annotations
        .filter { it.ply in selected && it.ply != turningPoint?.ply && tiers[it.ply] == PacingTier.FULL }
        .sortedWith(compareByDescending<MoveAnnotation> { it.loss }.thenBy { it.ply })
        .take(MAX_FULL_MOMENTS)
        .map { it.ply }
        .toSet()

    /**
     * What the plies between two told beats were like, as far as the boards prove it: no capture and no check and
     * hardly any change in the evaluation (QUIET), trades in both directions (TRADES), or nothing more (PLAIN).
     */
    private fun skipKind(fromPly: Int, toPly: Int): SkipKind {
        val skipped = (fromPly + 1) until toPly
        var capturesWhite = 0
        var capturesBlack = 0
        var checks = 0
        var swing = 0.0
        for (k in skipped) {
            val move = moves.getOrNull(k - 1)
            if (move?.isCapture == true) if (move.color == Color.WHITE) capturesWhite++ else capturesBlack++
            val san = annotations[k - 1].san
            if (san.endsWith("+") || san.endsWith("#")) checks++
            swing = max(swing, abs(whiteSeries.getOrElse(k) { 50.0 } - whiteSeries.getOrElse(k - 1) { 50.0 }))
        }
        return when {
            capturesWhite == 0 && capturesBlack == 0 && checks == 0 && swing <= QUIET_SWING -> SkipKind.QUIET
            capturesWhite >= 1 && capturesBlack >= 1 -> SkipKind.TRADES
            else -> SkipKind.PLAIN
        }
    }

    private fun connective(fromPly: Int, toPly: Int) {
        val target = annotations[toPly - 1]
        val fromMoveNo = annotations[fromPly - 1].moveNumber
        add(
            kind = SegmentKind.NORMAL_MOVE,
            ply = null,
            narration = story(Sentence.SkipAhead(target.moveNumber, skipKind(fromPly, toPly)), fromPly, toPly),
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

    /**
     * One ply's beat, shaped by its [PacingTier] (ANALYSIS_SPEC 9.7). A ply that EVERY_MOVE narrates
     * although the plan skipped it is told at BRIEF length.
     */
    private fun emitBeat(ply: Int, leadIn: String) {
        val a = annotations[ply - 1]
        if (isMate(a)) return matingBeat(a, leadIn)
        // A move of a mating sequence that is already under way is said bare, whatever tier it earned.
        if (continuesMate(a) && moverMate(a) != null && options.depth != NarrationDepth.EVERY_MOVE) return mateStepBeat(a)
        when (tiers[ply] ?: PacingTier.BRIEF) {
            PacingTier.FULL -> fullBeat(a, leadIn)
            PacingTier.DWELL -> dwellBeat(a, leadIn)
            PacingTier.BRIEF, PacingTier.SKIP -> briefBeat(a, leadIn)
        }
    }

    // -- The story (ANALYSIS_SPEC §9.9) --------------------------------------------------------

    /** A lead-in fragment ("A little later, ") made a sentence of its own, for a beat that opens with an interjection. */
    private fun asSentence(leadIn: String): String {
        val t = leadIn.trim().trimEnd(',')
        if (t.isEmpty()) return ""
        return if (t.endsWith(".") || t.endsWith("?")) "$t " else "$t. "
    }

    /** The mover-relative forced mate the move started: its distance (counting the move itself), or null. */
    private fun moverMate(a: MoveAnnotation): Int? {
        val m = a.mateInBefore ?: return null
        val rel = if (a.color == Color.WHITE) m else -m
        return rel.takeIf { it in 1..MAX_MATE_SPOKEN }
    }

    /** True when [a] carries on a mating sequence the previous told beat started (the same side, at most one move apart). */
    private fun continuesMate(a: MoveAnnotation): Boolean = lastMateColor == a.color && a.ply - lastMatePly <= 2

    private fun noteMate(a: MoveAnnotation) {
        lastMatePly = a.ply
        lastMateColor = a.color
    }

    /**
     * Why the standing changed at [shift], when the boards prove it, in this order: the side that lost ground played a
     * mistake, a miss or a blunder at the boundary move or the one before; the side that gained ground started a
     * forced mate; the gaining side came out ahead on material across those two moves (settled, named by the 40 cp rule).
     */
    private fun phaseCause(shift: PhaseShift): PhaseCause? {
        val gainer = shift.benefiting ?: return null
        val loser = gainer.opposite()
        val window = listOf(shift.ply, shift.ply - 1).filter { it in 1..annotations.size }.map { annotations[it - 1] }
        window.filter { it.color == loser && it.classification in PHASE_ERRORS }
            .maxWithOrNull(compareBy<MoveAnnotation> { it.loss }.thenBy { -it.ply })
            ?.let { return PhaseCause.Error(loser, it.classification, it.moveNumber) }
        window.firstOrNull { it.color == gainer && moverMate(it) != null }
            ?.let { return PhaseCause.MateFound(gainer, moverMate(it)!!) }
        val start = positionAt(max(0, shift.ply - 2))
        val end = positionAt(shift.ply)
        val settled = ExchangeEvaluator.settledGain(start, end, gainer)
        if (settled >= MIN_PAYOFF_CP) {
            return PhaseCause.WonMaterial(gainer, NarrationVocabulary.materialGainAlong(start, end, gainer, settled))
        }
        return null
    }

    /** The board after ply [k] (0 = the start). */
    private fun positionAt(k: Int): Position =
        if (k <= 0) positionsBefore.firstOrNull() ?: Position.startPosition() else safeFen(annotations[k - 1].fenAfter)

    /** The sentence for the phase boundary caused by [a]'s move, once; null when there is none or it was told already. */
    private fun takePhase(a: MoveAnnotation): String? {
        val shift = shiftAt[a.ply] ?: return null
        if (!phaseTold.add(a.ply)) return null
        return story(Sentence.PhaseTurn(shift.from, shift.to, phaseCause(shift)), shift.ply)
    }

    /** A phase boundary that no beat of its ply has told: said over the board as it stands after the move. */
    private fun phaseBeatIfUntold(ply: Int) {
        val a = annotations[ply - 1]
        val text = takePhase(a) ?: return
        add(
            kind = SegmentKind.NORMAL_MOVE,
            ply = null,
            narration = text,
            caption = caption(a),
            board = BoardDirective.Hold(a.fenAfter),
            eval = evalAfter(a.ply),
            moveNumber = a.moveNumber
        )
    }

    /** What the played move let the opponent do, as far as the engine's own numbers prove it. */
    private sealed interface Allows {
        data class Mate(val mateIn: Int) : Allows
        data class Reply(val reply: SpokenMove, val gain: MaterialGain?) : Allows
    }

    private fun allowsOf(a: MoveAnnotation): Allows? {
        val after = a.mateInAfter
        if (after != null) {
            val rel = if (a.color == Color.WHITE) after else -after
            if (rel < 0 && -rel <= MAX_MATE_SPOKEN) return Allows.Mate(-rel)
        }
        val reply = annotations.getOrNull(a.ply)?.bestMoveUci ?: return null
        val board = safeFen(a.fenAfter)
        val move = SpokenChess.moveOrNull(board, reply) ?: return null
        if (!move.isCapture) return null
        // Taking back on the square the move just took on is the other half of a trade, not something it let in.
        val played = moves.getOrNull(a.ply - 1)
        if (played != null && played.isCapture && played.to == move.to) return null
        val see = ExchangeEvaluator.see(board, move)
        if (see < MIN_PAYOFF_CP) return null
        return Allows.Reply(SpokenChess.describe(board, move), NarrationVocabulary.materialGain(see))
    }

    /**
     * What the engine's best move did, from the position before the error: it started a forced mate for the mover, or
     * along the engine's own line (the first [BETTER_LINE_PLIES] plies, settled) it nets at least a pawn; else nothing
     * more than the move can be said.
     */
    private fun betterResult(a: MoveAnnotation): BetterResult {
        moverMate(a)?.let { return BetterResult.Mates(it) }
        val gain = bestLineGain(a)
        if (gain >= MIN_PAYOFF_CP) return BetterResult.Wins(NarrationVocabulary.materialGain(gain))
        return BetterResult.Plain
    }

    /** The most the mover nets, settled, at any point of the first plies of the engine's best line. */
    private fun bestLineGain(a: MoveAnnotation): Int {
        val line = missedLine(a) ?: return 0
        val start = positionsBefore[a.ply - 1]
        var pos = start
        var best = 0
        for (uci in line.uci.take(BETTER_LINE_PLIES)) {
            val move = SpokenChess.moveOrNull(pos, uci) ?: break
            pos = pos.makeMove(move)
            best = max(best, ExchangeEvaluator.settledGain(start, pos, a.color))
            if (pos.isCheckmate()) break
        }
        return best
    }

    private fun isErrorClass(a: MoveAnnotation): Boolean =
        a.classification == MoveClassification.BLUNDER ||
            a.classification == MoveClassification.MISTAKE ||
            a.classification == MoveClassification.MISS

    /**
     * FULL: the whole treatment. A missed tactic gets the puzzle pause (when it is worth one), the
     * reveal and the walked line; a blunder or a miss gets the error beat; a brilliancy gets its
     * tactic and its flavour.
     */
    private fun fullBeat(a: MoveAnnotation, leadIn: String) {
        val missed = significantMissed(a)
        when {
            // The turning point of a game with nothing worse in it can be a mere inaccuracy. It is
            // still the story, but an inaccuracy never earns a walk of the line it missed.
            a.classification == MoveClassification.INACCURACY -> keyMomentBeat(a, leadIn)
            // The puzzle and the long walk are for a tactic that wins material or mates. A blunder
            // that merely missed some quieter motif is still FULL (it is the worst of the game) but
            // is shown the short way.
            missed != null -> missedTacticBeat(
                a, leadIn, missed,
                full = winsMaterialOrMate(a, missed) || a.classification == MoveClassification.MISS
            )
            isErrorClass(a) -> errorBeat(a, leadIn)
            a.tacticsFound.isNotEmpty() && a.classification.isGood -> foundTacticBeat(
                a, leadIn,
                extra = if (a.classification == MoveClassification.BRILLIANT) say(Sentence.BrilliantFlavour) else null
            )
            else -> normalBeat(a, leadIn)
        }
    }

    /**
     * DWELL: a fuller explanation than a passing mention. A missed tactic still gets its reveal and
     * a variation, but at most [DWELL_EXCURSION_PLIES] plies of it and no puzzle pause.
     */
    private fun dwellBeat(a: MoveAnnotation, leadIn: String) {
        val missed = significantMissed(a)
        when {
            missed != null -> missedTacticBeat(a, leadIn, missed, full = false)
            isErrorClass(a) -> errorBeat(a, leadIn)
            a.tacticsFound.isNotEmpty() && a.classification.isGood -> foundTacticBeat(a, leadIn)
            a.threatsAllowed.isNotEmpty() && !a.classification.isGood -> threatBeat(a, leadIn)
            else -> normalBeat(a, leadIn)
        }
    }

    /** BRIEF: the move and one remark, never a walk of the line it missed. */
    private fun briefBeat(a: MoveAnnotation, leadIn: String) {
        // EVERY_MOVE is the complete walkthrough and names every tactic that survived the gate, even
        // one the plan would have left to a passing mention.
        if (options.depth == NarrationDepth.EVERY_MOVE && a.tacticsFound.isNotEmpty() && a.classification.isGood) {
            return foundTacticBeat(a, leadIn)
        }
        if (a.classification.isMistake) return briefMistakeBeat(a, leadIn)
        if (options.depth == NarrationDepth.EVERY_MOVE) return normalBeat(a, leadIn)
        briefMoveBeat(a, leadIn)
    }

    /**
     * "White plays X." and then the one thing that makes it worth a beat: the tactic it carries, how the standing
     * changed (a phase boundary), or what it did to the evaluation when that was worth saying. Never a pleasantry -
     * "Top of the engine's list" and "No complaints" say nothing, and a beat that has nothing to say stops at the move.
     */
    private fun briefMoveBeat(a: MoveAnnotation, leadIn: String) {
        val tactic = a.tacticsFound
            .filter { it.confidence >= FOUND_TACTIC_CONFIDENCE }
            .maxWithOrNull(compareBy({ it.confidence }, { it.materialSwing }))
        val sb = StringBuilder(leadIn)
        sb.append(cap(playedClause(a, MoveVerb.PLAY))).append(". ")
        val phase = takePhase(a)
        when {
            tactic != null -> {
                sb.append(say(NarrationVocabulary.tacticPoint(tactic, positionsBefore[a.ply - 1])))
                phase?.let { sb.append(' ').append(it) }
            }
            phase != null -> sb.append(phase)
            abs(a.winPercentAfter - a.winPercentBefore) >= REMARK_MIN_SWING ->
                sb.append(story(Sentence.SmallSwing(subj(a.color), a.winPercentAfter >= a.winPercentBefore), a.ply))
        }
        add(
            kind = if (tactic != null) SegmentKind.FOUND_TACTIC else SegmentKind.NORMAL_MOVE,
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

    /** "White plays X. Y was the move." - what an inaccuracy deserves, and no more. */
    private fun briefMistakeBeat(a: MoveAnnotation, leadIn: String) {
        val sb = StringBuilder(leadIn)
        val spoken = spokenMoves.getOrNull(a.ply - 1)
        // Two shapes, so a run of small slips does not repeat one cadence: the move and the better move as two
        // sentences, or the move with its verdict in the same breath.
        var saidVerdict = false
        if (spoken != null && a.classification == MoveClassification.INACCURACY && thinned("slip", 2)) {
            sb.append(cap(story(Sentence.PlayedSlip(subj(a.color), MoveVerb.PLAY, spoken, isRecapture(a)), a.ply))).append(' ')
            saidVerdict = true
        } else {
            sb.append(cap(playedClause(a, MoveVerb.PLAY))).append(". ")
        }
        val better = betterMoveSentence(a)
        sb.append(better ?: if (saidVerdict) "" else say(Sentence.InaccuracyNote))
        takePhase(a)?.let { sb.append(' ').append(it) }
        add(
            kind = SegmentKind.KEY_MOMENT,
            ply = a.ply,
            narration = cap(sb.toString()),
            caption = caption(a, a.classification.glyph),
            board = annotateDirective(a),
            speakerColor = a.color,
            eval = evalBefore(a.ply),
            moveNumber = a.moveNumber
        )
        // A brief beat plays the line only when it is one of the report's key moments (V2).
        if (better != null && a.ply in keyMomentPlies) betterMoveBeats.add(segments.last().index)
    }

    /** The finish deserves its own beat rather than being narrated as another quiet move. */
    private fun matingBeat(a: MoveAnnotation, leadIn: String) {
        val continuing = continuesMate(a)
        val sb = StringBuilder(if (continuing) "" else leadIn)
        if (continuing) {
            // The sequence was announced and stepped through: the mating move is said as bare as the steps were.
            spokenMoves.getOrNull(a.ply - 1)?.let { sb.append(cap(strings.vocabulary.movePhrase(it))).append(". ") }
                ?: sb.append(cap(playedClause(a, MoveVerb.PLAY))).append(". ")
        } else {
            sb.append(cap(playedClause(a, MoveVerb.PLAY))).append(". ")
        }
        sb.append(
            when {
                isUser(a.color) -> story(Sentence.MateFinish(subj(a.color)), a.ply)
                userColor == a.color.opposite() -> say(Sentence.MateOnReceivingEnd)
                else -> story(Sentence.MateFinish(subj(a.color)), a.ply)
            }
        )
        phaseTold.add(a.ply)
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
        takePhase(a)?.let { sb.append(' ').append(it) }
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

    private fun foundTacticBeat(a: MoveAnnotation, leadIn: String, extra: String? = null) {
        val tactic = a.tacticsFound
            .maxWithOrNull(compareBy({ it.confidence }, { it.materialSwing }))
        val pos = positionsBefore[a.ply - 1]
        val mate = moverMate(a)
        val mating = tactic != null && tactic.type in MATING_MOTIFS || mate != null
        // A mating sequence (ANALYSIS_SPEC §9.9): its aim is announced once, with the engine's own distance, and the
        // moves after it are said bare.
        if (mating && continuesMate(a)) return mateStepBeat(a, extra)
        val sb = StringBuilder(leadIn)
        sb.append(playedClause(a, pickVerb("foundVerb", listOf(MoveVerb.PLAY, MoveVerb.FIND, MoveVerb.GO_FOR)))).append(". ")
        if (isRecapture(a)) {
            sb.append(say(Sentence.JustTheTrade))
        } else if (tactic != null) {
            if (mating) {
                noteMate(a)
                // A named pattern (back-rank, smothered, the Greek gift) is worth its sentence; a mating net is not.
                if (tactic.type != TacticType.MATE_NET || mate == null) sb.append(say(NarrationVocabulary.tacticPoint(tactic, pos))).append(' ')
                if (mate != null) sb.append(story(Sentence.MateAnnounce(mate), a.ply))
            } else {
                val onTheDestination = moves.getOrNull(a.ply - 1)?.to == tactic.targetSquares.firstOrNull()
                if (onTheDestination && tactic.type == TacticType.HANGING_PIECE) {
                    // The clause just named that square; saying it again is how narration starts to drone.
                    sb.append(say(Sentence.NothingDefendingIt)).append(' ')
                } else {
                    sb.append(say(NarrationVocabulary.tacticPoint(tactic, pos))).append(' ')
                }
                if (tactic.materialSwing >= 100) {
                    sb.append(say(Sentence.MaterialInTheBank(NarrationVocabulary.materialPayoff(tactic.materialSwing))))
                } else if (thinned("pressure", 2)) {
                    sb.append(say(Sentence.PressureNoMaterialYet))
                }
            }
        } else {
            sb.append(say(Sentence.GoodSolid))
        }
        extra?.let { sb.append(' ').append(it) }
        takePhase(a)?.let { sb.append(' ').append(it) }
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

    /** A later move of a mating sequence: the move, said bare ("Queen to f six, with check."). */
    private fun mateStepBeat(a: MoveAnnotation, extra: String? = null) {
        noteMate(a)
        val spoken = spokenMoves.getOrNull(a.ply - 1)
        val sb = StringBuilder()
        if (spoken != null) sb.append(story(Sentence.MateStep(spoken), a.ply)) else sb.append(cap(playedClause(a, MoveVerb.PLAY))).append('.')
        extra?.let { sb.append(' ').append(it) }
        phaseTold.add(a.ply)
        add(
            kind = SegmentKind.FOUND_TACTIC,
            ply = a.ply,
            narration = cap(sb.toString()),
            caption = caption(a),
            board = playMoveDirective(a),
            tactic = a.tacticsFound.maxWithOrNull(compareBy({ it.confidence }, { it.materialSwing })),
            speakerColor = a.color,
            eval = evalAfter(a.ply),
            moveNumber = a.moveNumber
        )
    }

    private val thinCounts = HashMap<String, Int>()

    /** True on every [every]th call for [key], starting with the first: a remark that would otherwise be said every time. */
    private fun thinned(key: String, every: Int): Boolean {
        val n = thinCounts.merge(key, 1, Int::plus)!!
        return (n - 1) % every == 0
    }

    /**
     * An error told as cause and consequence (ANALYSIS_SPEC §9.9): the move and what it let the opponent do (the
     * engine's mate against it, or its best reply and what that wins), how the standing changed, and the better move
     * with what it did (a mate, material along the engine's line) when the boards prove one.
     */
    private fun errorBeat(a: MoveAnnotation, leadIn: String) {
        val sb = StringBuilder()
        sb.append(say(Sentence.ErrorOpener(a.classification))).append(' ')
        sb.append(leadIn)
        appendPlayedAndAllowed(sb, a)
        sb.append(consequence(a))
        val better = betterMoveSentence(a)
        better?.let { sb.append(' ').append(it) }

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
        if (better != null) betterMoveBeats.add(segments.last().index)
    }

    /** "Black takes the bishop on b five with the pawn." and, when the engine proves one, what it lets in. */
    private fun appendPlayedAndAllowed(sb: StringBuilder, a: MoveAnnotation) {
        val spoken = spokenMoves.getOrNull(a.ply - 1)
        when (val allows = allowsOf(a)) {
            is Allows.Reply -> if (spoken != null) {
                sb.append(
                    cap(story(Sentence.PlayedThenReply(subj(a.color), MoveVerb.PLAY, spoken, isRecapture(a), Subject(a.color.opposite(), Person.THIRD), allows.reply, allows.gain), a.ply))
                ).append(' ')
                return
            }
            is Allows.Mate -> {
                sb.append(cap(playedClause(a, MoveVerb.PLAY))).append(". ")
                sb.append(story(Sentence.AllowsMate(allows.mateIn), a.ply)).append(' ')
                // The aim of the opponent's mate has been said: its first move is said bare, not announced again.
                lastMatePly = a.ply
                lastMateColor = a.color.opposite()
                return
            }
            null -> Unit
        }
        sb.append(cap(playedClause(a, MoveVerb.PLAY))).append(". ")
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
        val better = betterMoveSentence(a)
        better?.let { sb.append(' ').append(it) }
        takePhase(a)?.let { sb.append(' ').append(it) }
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
        if (better != null) betterMoveBeats.add(segments.last().index)
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
    private fun missedTacticBeat(a: MoveAnnotation, leadIn: String, tactic: TacticInstance, full: Boolean) {
        val plies = excursionPlies(a, tactic, if (full) MAX_EXCURSION_PLIES else DWELL_EXCURSION_PLIES)
        // Only a FULL moment stops the video to ask. A DWELL one just shows the line.
        val worthAPuzzle = full && options.includePuzzlePrompts &&
            (winsMaterialOrMate(a, tactic) || a.classification == MoveClassification.MISS)

        if (worthAPuzzle) {
            puzzlePrompt(a, tactic, plies)
        }
        val opening = if (worthAPuzzle) "" else leadIn

        if (plies.isEmpty()) return missedWithoutALine(a, tactic, opening)

        excursionPivotIn(a, tactic, opening, asked = !worthAPuzzle)
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
     * Capped the way [net.palaya.chessanalyzer.core.analysis.SimulationBuilder] caps a simulation
     * (ANALYSIS_SPEC section 6): at most [maxPlies] ([MAX_EXCURSION_PLIES] for a FULL moment,
     * [DWELL_EXCURSION_PLIES] for a DWELL one, ANALYSIS_SPEC 9.7), and stopping early on
     * mate or once the payoff has genuinely landed — the tactic's side is up the material it was
     * promised and the opponent has had the last word. Without that, a twenty-ply PV turns a
     * thirty-second point into a two-minute detour.
     *
     * Stops at the first move that will not parse too, so a stale or truncated PV degrades to the
     * part of the line that is still legal instead of throwing.
     */
    private fun excursionPlies(a: MoveAnnotation, tactic: TacticInstance, maxPlies: Int): List<ExcursionPly> {
        val line = missedLine(a) ?: return emptyList()
        val start = positionsBefore[a.ply - 1]
        val payoffTarget = max(tactic.materialSwing, MIN_PAYOFF_CP)
        val out = ArrayList<ExcursionPly>()
        var pos = start
        for (uci in line.uci) {
            if (out.size >= maxPlies) break
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

    private fun excursionPivotIn(a: MoveAnnotation, tactic: TacticInstance, leadIn: String, asked: Boolean) {
        val pos = positionsBefore[a.ply - 1]
        // The pivot is an interjection of its own ("Hold on, back up a move"), which already says that time passed.
        val sb = StringBuilder()
        // A missed tactic with no pause for the viewer still asks the question, once, before the answer.
        sb.append(if (asked) story(Sentence.PivotQuestion(a.color), a.ply) else say(Sentence.PivotIn))
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
        val before = ExchangeEvaluator.netGain(start, p.before, winner)
        return when {
            p.after.isCheckmate() -> say(Sentence.LineEndsInMate)
            taken >= MIN_PAYOFF_CP -> say(Sentence.MaterialTaken(NarrationVocabulary.materialGain(taken)))
            p.uci == tactic.moveUci && index > 0 -> say(NarrationVocabulary.tacticPoint(tactic, p.before))
            // The first time the line is up material is worth saying; every move after it is not.
            running >= MIN_PAYOFF_CP && before < MIN_PAYOFF_CP ->
                say(Sentence.AlreadyUp(subj(winner), NarrationVocabulary.materialGainAlong(start, p.after, winner, running)))
            // A move with no reason of its own is said bare: not every move of a line needs a remark.
            else -> ""
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
            else -> ""
        }
    }

    /** What the line actually won, stated on the final position of the line. */
    private fun excursionPayoff(a: MoveAnnotation, tactic: TacticInstance, plies: List<ExcursionPly>) {
        val last = plies.last()
        val start = plies.first().before
        val winner = tactic.byColor
        // Settled: a line that stops right after a capture is not credited with what is taken back.
        val gain = ExchangeEvaluator.settledGain(start, last.after, winner)
        val sb = StringBuilder()
        // What the line delivers is only said when the board proves it: a mate, material netted, or
        // one of the outcomes payoffKind can read off the final position. A line that does none of
        // these used to end "X has invested material in the attack" or "X ends up completely on
        // top", which nothing in the data supports; it now says only that the line ends (spec 6.1).
        when {
            last.after.isCheckmate() -> sb.append(say(Sentence.PayoffMate((plies.size + 1) / 2)))
            gain >= MIN_PAYOFF_CP ->
                sb.append(say(Sentence.PayoffMaterial(subj(winner), NarrationVocabulary.materialGainAlong(start, last.after, winner, gain))))
            else -> {
                val kind = payoffKind(tactic, last.after, gain)
                // A line that proves nothing else says where the engine's own play leaves the mover.
                if (kind == PayoffKind.LINE_ENDS) sb.append(story(Sentence.PayoffStanding(subj(winner), NarrationVocabulary.standing(a.winPercentBefore)), a.ply))
                else sb.append(say(Sentence.PayoffOutcome(subj(winner), kind)))
            }
        }
        val mate = a.mateInBefore
        if (!last.after.isCheckmate() && mate != null && abs(mate) in 1..8) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(say(Sentence.MateBehindIt(abs(mate))))
        }
        sb.insert(0, say(Sentence.PayoffLead))
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
        // The detector gives a mate motif confidence 0.95 only when the engine's line ends in mate.
        tactic.type == TacticType.MATE_NET && tactic.confidence >= FOUND_TACTIC_CONFIDENCE -> PayoffKind.MATING_NET
        // Neither "has invested material in the attack" nor "ends up completely on top" can be
        // proved from the boards, so the line is simply said to end.
        else -> PayoffKind.LINE_ENDS
    }

    /** Back on the real board, still frozen, pointing at the move that is about to be played. */
    private fun excursionPivotOut(a: MoveAnnotation) {
        add(
            kind = SegmentKind.KEY_MOMENT,
            ply = a.ply,
            // V4: the same words wherever a simulated line ends and the board is the game's again.
            narration = say(Sentence.BackToTheGame) + " " + say(Sentence.PivotOut),
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
        appendPlayedAndAllowed(sb, a)
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
        val sb = StringBuilder()
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

    /**
     * The turning point (ANALYSIS_SPEC §9.9): that it was, that nothing else cost as much, and what followed when the
     * phases prove it. The standing change itself was told by the error beat just before, so it is not said twice.
     */
    private fun turningPointSegment(a: MoveAnnotation) {
        val sb = StringBuilder()
        sb.append(story(Sentence.TurningPointLead(a.loss >= DECISIVE_LOSS), a.ply))
        sb.append(story(Sentence.TurningPointSwing(a.moveNumber), a.ply)).append(' ')
        when (val after = StoryArc.aftermath(phases, a.ply, a.color)) {
            is StoryArc.Aftermath.NeverBack ->
                sb.append(story(Sentence.TurningAftermath(AftermathKind.NEVER_BACK, after.leader, null, null), a.ply))
            is StoryArc.Aftermath.FoughtBack ->
                sb.append(story(Sentence.TurningAftermath(AftermathKind.FOUGHT_BACK, after.side, after.moveNumber, after.standing), a.ply))
            null -> Unit
        }
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

    /**
     * The finish (ANALYSIS_SPEC §9.9): how the game ended and the move that decided it. No accuracy and no rating is
     * spoken: the numbers are on the card and on the Summary screen, and a voice reading statistics is the one thing
     * the owner called the worst of the old script.
     */
    private fun outroSummary() {
        val w = report.white
        val b = report.black
        val whiteLabel = spokenName(w.name ?: game.tags["White"]) ?: strings.vocabulary.side(Color.WHITE)
        val blackLabel = spokenName(b.name ?: game.tags["Black"]) ?: strings.vocabulary.side(Color.BLACK)

        val winner = when (report.result) {
            "1-0" -> Color.WHITE
            "0-1" -> Color.BLACK
            else -> null
        }
        val mated = annotations.lastOrNull()?.san?.endsWith("#") == true
        val sb = StringBuilder()
        sb.append(story(Sentence.OutroEnding(winner, report.result == "1/2-1/2", mated && winner != null, (annotations.size + 1) / 2)))
        if (trim < 2 && annotations.isNotEmpty()) {
            sb.append(' ').append(story(decider(), turningPoint?.ply ?: 0))
        }

        add(
            kind = SegmentKind.OUTRO_SUMMARY,
            ply = null,
            narration = sb.toString(),
            caption = say(Sentence.CaptionOutro(accuracyWhole(w.accuracy), w.estimatedRating, accuracyWhole(b.accuracy), b.estimatedRating)),
            board = BoardDirective.Card(
                heading = say(Sentence.CardFinalNumbersHeading),
                lines = listOf(
                    // Names are bidi-isolated so a Hebrew name cannot reorder the numbers around it.
                    say(Sentence.CardFinalPlayerLine(isolate(whiteLabel), accuracyWhole(w.accuracy), w.estimatedRating)),
                    say(Sentence.CardFinalPlayerLine(isolate(blackLabel), accuracyWhole(b.accuracy), b.estimatedRating)),
                    // V4: the per-class counts are the card's table (VideoScript.qualityCounts), which replaced
                    // the "Blunders 1–2 · Mistakes 0–1" line (each fact is printed once).
                    report.result
                )
            )
        )
    }

    /** The biggest swing of the game, in its mover's own bands, or a plain "no single decisive mistake". */
    private fun decider(): Sentence {
        val tp = turningPoint ?: return Sentence.OutroNoDecider
        val before = NarrationVocabulary.standing(tp.winPercentBefore)
        val after = NarrationVocabulary.standing(tp.winPercentAfter)
        if (tp.loss < DECISIVE_LOSS || before == after) return Sentence.OutroNoDecider
        return Sentence.OutroDecider(subj(tp.color), tp.classification, tp.moveNumber, before, after)
    }

    private fun outroLessons() {
        val lessons = when {
            trim >= 5 -> emptyList()
            else -> buildLessons()
        }
        lessons.forEachIndexed { i, text ->
            add(
                kind = SegmentKind.OUTRO_LESSONS,
                ply = null,
                narration = if (i == 0) "${say(Sentence.LessonLead(lessons.size))}$text" else text,
                caption = say(Sentence.CaptionTakeaway(i + 1, lessons.size)),
                board = BoardDirective.Card(say(Sentence.CardWorkOnHeading), listOf(text))
            )
        }
    }

    /**
     * The one takeaway (ANALYSIS_SPEC §9.9), tied to the move the game turned on: the viewer's own worst error when the
     * viewer is known, else the error of the biggest swing. Two sentences: what that move was (a missed tactic, a move
     * that let the opponent in, or what it gave up) and the habit that goes with it. Everything it says about the move
     * is read off the same facts as the beats ([significantMissed], [allowsOf], the win-percent bands).
     */
    private fun buildLessons(): List<String> {
        val focusColor = userColor ?: turningPoint?.color
            ?: if (report.white.accuracy <= report.black.accuracy) Color.WHITE else Color.BLACK
        val you = subj(focusColor)
        val worst = annotations
            .filter {
                it.color == focusColor && it.classification.isMistake &&
                    (it.loss >= DECISIVE_LOSS - 2.0 || significantMissed(it) != null)
            }
            .maxWithOrNull(compareBy<MoveAnnotation> { it.loss }.thenBy { significantMissed(it)?.materialSwing ?: 0 }.thenBy { -it.ply })
        if (worst == null) return listOf(story(Sentence.TakeawayClean))

        val missed = significantMissed(worst)
        val allows = allowsOf(worst)
        val before = NarrationVocabulary.standing(worst.winPercentBefore)
        val after = NarrationVocabulary.standing(worst.winPercentAfter)
        val opponent = focusColor.opposite()
        val first = when {
            missed != null -> Sentence.TakeawayMissed(
                you, worst.moveNumber, missed.type,
                worst.bestMoveUci?.let { SpokenChess.describeUci(positionsBefore[worst.ply - 1], it) }
            )
            allows is Allows.Mate -> Sentence.TakeawayAllowed(you, worst.moveNumber, opponent, allows.mateIn, null)
            allows is Allows.Reply -> Sentence.TakeawayAllowed(you, worst.moveNumber, opponent, null, allows.reply)
            else -> Sentence.TakeawayCostly(you, worst.moveNumber, NarrationVocabulary.lossSeverity(worst.loss), before, after)
        }
        val last = annotations.last()
        val matedAfterIt = last.san.endsWith("#") && last.color != focusColor && worst.ply == turningPoint?.ply
        val text = StringBuilder(story(first, worst.ply)).append(' ')
        when {
            matedAfterIt -> text.append(story(Sentence.LessonMatedButNotTheMistake(you, worst.moveNumber), worst.ply))
            missed != null -> {
                text.append(say(Sentence.TacticLesson(missed.type)))
                // The video stays in the game: a textbook detour would break the story and the running time, so the
                // reference example is offered, by name, and lives in the report (ANALYSIS_SPEC §10).
                if (trim < 1 && TacticReferenceLibrary.hasReference(missed.type)) text.append(' ').append(say(Sentence.TextbookOffer(missed.type)))
            }
            allows != null -> text.append(story(Sentence.TakeawayHabitAllowed, worst.ply))
            else -> text.append(story(Sentence.TakeawayHabitStanding(before), worst.ply))
        }
        return listOf(text.toString())
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

    /** What the move did to the evaluation, in the mover's own terms. The standing change is said here, once. */
    private fun consequence(a: MoveAnnotation): String {
        phaseTold.add(a.ply)
        val before = NarrationVocabulary.standing(a.winPercentBefore)
        val after = NarrationVocabulary.standing(a.winPercentAfter)
        if (before == after) {
            return story(Sentence.ConsequenceUnchanged(subj(a.color), after, NarrationVocabulary.lossSeverity(a.loss)), a.ply)
        }
        return story(Sentence.ConsequenceChanged(subj(a.color), before, after), a.ply)
    }

    /** The engine's better move with what it did, or null when it is the move played / not legal. */
    private fun betterMoveSentence(a: MoveAnnotation): String? {
        val bestUci = a.bestMoveUci ?: return null
        if (bestUci == a.uci) return null
        val pos = positionsBefore[a.ply - 1]
        val best = SpokenChess.describeUci(pos, bestUci) ?: return null
        val mate = a.mateInBefore
        if (a.classification == MoveClassification.MISS && mate != null && abs(mate) in 1..8) {
            return say(Sentence.MateWasAvailable(abs(mate), best))
        }
        return story(Sentence.BetterHere(best, betterResult(a)), a.ply)
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

    /**
     * An accuracy as the Summary screen writes it: whole percent (`"%.0f"`). Every accuracy printed ON A
     * CARD goes through this; the spoken sentences keep one decimal. `roundToInt` and `%.0f` agree on
     * every value (both round half up; the app's `CardTextTest` sweeps 0..100 to prove it).
     */
    private fun accuracyWhole(v: Double): String = v.roundToInt().toString()

    /** First-strong isolate .. pop: the name keeps its own direction inside the line it is printed in. */
    private fun isolate(text: String): String = "\u2068$text\u2069"

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

        /** Between the parts of a one-line card subtitle ("1-0 · 17 moves · Philidor Defense (C41)"). */
        const val CARD_SEPARATOR = " · "

        /** ANALYSIS_SPEC §9.9: a skipped stretch is "quiet" only if no ply moved the White win-percent by more than this. */
        const val QUIET_SWING = 5.0

        /** ANALYSIS_SPEC §9.9: the evaluation change (win-percent, mover's side) below which a quiet move gets no remark. */
        const val REMARK_MIN_SWING = 4.0

        /** ANALYSIS_SPEC §9.9: the biggest-swing move is called "decisive" in the outro from this loss on. */
        const val DECISIVE_LOSS = 12.0

        /** Spec section 6's cap, applied to the spoken detour of a FULL moment. */
        const val MAX_EXCURSION_PLIES = 8

        /** ANALYSIS_SPEC 9.7: a DWELL moment may show a variation, but only a short one. */
        const val DWELL_EXCURSION_PLIES = 4

        /** ANALYSIS_SPEC 9.7: the most FULL (puzzle plus walked line) moments in one game. */
        const val MAX_FULL_MOMENTS = 3

        /** ANALYSIS_SPEC 9.7: a brilliancy ranks for a FULL slot as if it were a blunder of this loss. */
        const val BRILLIANT_DRAMA = 25.0

        /** ANALYSIS_SPEC 9.7: a found tactic earns a DWELL beat only when the engine confirmed it. */
        const val FOUND_TACTIC_CONFIDENCE = 0.95

        /** ANALYSIS_SPEC 9.7: a missed tactic is worth a puzzle once it wins at least this much. */
        const val PUZZLE_MIN_SWING_CP = 150

        /** ANALYSIS_SPEC §9.9: the plies of the engine's best line read for what the better move won. */
        const val BETTER_LINE_PLIES = 4

        /** ANALYSIS_SPEC §9.9: the longest forced mate the narration states. */
        const val MAX_MATE_SPOKEN = 8

        /** ANALYSIS_SPEC §9.9: the error classes a phase boundary is said to follow. */
        val PHASE_ERRORS = setOf(MoveClassification.BLUNDER, MoveClassification.MISTAKE, MoveClassification.MISS)

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
