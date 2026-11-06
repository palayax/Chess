package net.palaya.chessanalyzer.core.narration

import net.palaya.chessanalyzer.core.analysis.MoveClassification
import net.palaya.chessanalyzer.core.analysis.PhaseStanding
import net.palaya.chessanalyzer.core.analysis.TacticType
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.PieceType
import net.palaya.chessanalyzer.core.chess.Square

/**
 * One representative instance of every [Sentence], with realistic facts.
 *
 * Two jobs: the contract test renders all of them through every [NarrationLocales.all] entry to
 * prove no sentence is blank or speaks notation; and [markdown] turns them into the table a
 * translator receives — id, parameters, and the English reference — so that adding a language is
 * a content task done against a complete list rather than an archaeology of the generator.
 * Sentences with several variants list all of them.
 */
object NarrationCatalogue {

    private val e4 = SpokenMove(Color.WHITE, PieceType.PAWN, Square.fromAlgebraic("e2"), Square.fromAlgebraic("e4"))
    private val e5 = SpokenMove(Color.BLACK, PieceType.PAWN, Square.fromAlgebraic("e7"), Square.fromAlgebraic("e5"))
    private val nf3 = SpokenMove(Color.WHITE, PieceType.KNIGHT, Square.fromAlgebraic("g1"), Square.fromAlgebraic("f3"))
    private val bxf3 = SpokenMove(
        Color.BLACK, PieceType.BISHOP, Square.fromAlgebraic("g4"), Square.fromAlgebraic("f3"),
        isCapture = true, captured = PieceType.KNIGHT, outcome = MoveOutcome.CHECK
    )
    private val qh5 = SpokenMove(Color.WHITE, PieceType.QUEEN, Square.fromAlgebraic("d1"), Square.fromAlgebraic("h5"))
    private val kxd1 = SpokenMove(
        Color.BLACK, PieceType.KING, Square.fromAlgebraic("e1"), Square.fromAlgebraic("d1"),
        isCapture = true, captured = PieceType.ROOK
    )

    private val viewer = Subject(Color.WHITE, Person.SECOND, Gender.FEMININE)
    private val black = Subject(Color.BLACK, Person.THIRD)

    fun samples(): List<Sentence> = listOf(
        Sentence.VideoTitle("MorphyFan", "DukeAndCount"),
        Sentence.VideoSubtitle("Philidor Defense", "C41", "1-0", 18),
        Sentence.ChapterIntro,
        Sentence.ChapterOpening,
        Sentence.ChapterMiddlegame,
        Sentence.ChapterMove(12, "Nf3"),
        Sentence.ChapterTurningPoint,
        Sentence.ChapterDamageReport,
        Sentence.ChapterWorkOn,
        Sentence.CardOpeningLine("Philidor Defense", "C41"),
        Sentence.CardResultLine("1-0", 17),
        Sentence.CardResultLine("1/2-1/2", 1),
        Sentence.CardAccuracyLine("85.0", "80.1"),
        Sentence.CaptionOpening("Philidor Defense", "C41"),
        Sentence.CaptionMoves(5, 9),
        Sentence.CaptionMove(12, Color.BLACK, "Nf6", "?"),
        Sentence.CaptionMissed("Nf3", "e4"),
        Sentence.CaptionMissedLine(listOf("Nf3", "e5", "Nxe5")),
        Sentence.CaptionBestLine(18, Color.BLACK, listOf("Nf5", "Qd2", "Nd4")),
        Sentence.CaptionBackToGame(12, Color.WHITE, "Nf3"),
        Sentence.CaptionPuzzle(Color.WHITE),
        Sentence.CaptionTurningPoint(23, "Qh5", "31.5"),
        Sentence.CaptionOutro("85.0", 1650, "80.1", 1500),
        Sentence.CaptionTakeaway(1, 3),
        Sentence.CardFinalNumbersHeading,
        Sentence.CardFinalPlayerLine("MorphyFan", "85.0", 1650),
        Sentence.CardWorkOnHeading,
        Sentence.WalkthroughIntro("h5", TacticType.HANGING_PIECE, "The pawn on g4 cannot be held - taking it wins material."),
        Sentence.WalkthroughIntro(null, TacticType.FORK, null),
        Sentence.GameSummary(SummaryKind.DECIDED_BY_ERROR, viewer, black, viewerKnown = true, moveNumber = 11, error = SummaryError.BLUNDER),
        Sentence.GameSummary(SummaryKind.DECIDED_BY_ERROR, black, null, viewerKnown = false, moveNumber = 11, error = SummaryError.BLUNDER),
        Sentence.GameSummary(SummaryKind.SEALED_BY_ERROR, black, viewer, viewerKnown = true, moveNumber = 21, error = SummaryError.MISTAKE),
        Sentence.GameSummary(SummaryKind.COMEBACK, viewer, black, viewerKnown = true, moveNumber = 21, error = SummaryError.BLUNDER),
        Sentence.GameSummary(SummaryKind.COMEBACK, black, Subject(Color.WHITE, Person.THIRD), viewerKnown = false, moveNumber = 21, error = SummaryError.MISSED_WIN),
        Sentence.GameSummary(SummaryKind.WON_DESPITE_ERROR, viewer, black, viewerKnown = true, moveNumber = 23, error = SummaryError.BIG_SWING),
        Sentence.GameSummary(SummaryKind.CLEAN_WIN, viewer, black, viewerKnown = true, fullMoves = 30, byMate = true),
        Sentence.GameSummary(SummaryKind.CLEAN_WIN, Subject(Color.WHITE, Person.THIRD), black, viewerKnown = false),
        Sentence.GameSummary(SummaryKind.SHORT_GAME, black, viewer, viewerKnown = true, fullMoves = 8, byMate = true),
        Sentence.GameSummary(SummaryKind.SHORT_GAME, viewer, black, viewerKnown = true, fullMoves = 9),
        Sentence.GameSummary(SummaryKind.SHORT_GAME, fullMoves = 7),
        Sentence.GameSummary(SummaryKind.DRAW_WITH_SWING, black, viewer, viewerKnown = true, moveNumber = 14, error = SummaryError.MISTAKE),
        Sentence.GameSummary(SummaryKind.CLOSE_CLEAN),
        Sentence.GameSummary(SummaryKind.UNFINISHED, fullMoves = 23),
        Sentence.IntroNoNames,
        Sentence.IntroPlayers("MorphyFan", 1650, "DukeAndCount", null),
        Sentence.ResultDecisive(Color.WHITE, byMate = true, fullMoves = 17),
        Sentence.ResultDraw(40),
        Sentence.ResultUnfinished(23),
        Sentence.IntroYouWere(viewer),
        Sentence.StakesComeback(Color.BLACK, 16),
        Sentence.StakesTookOver(Color.WHITE, 11, fromTheStart = false),
        Sentence.StakesTookOver(Color.WHITE, 3, fromTheStart = true),
        Sentence.StakesTightDraw,
        Sentence.StakesWastedLead(Color.WHITE, 12),
        Sentence.ResultEndsInMate(33),
        Sentence.ResultLasted(40),
        Sentence.HookMissedMate,
        Sentence.HookBigTurn(23),
        Sentence.HookClose(23),
        Sentence.HookClean,
        Sentence.HookNoMistakes,
        Sentence.OpeningLead("Philidor Defense"),
        Sentence.OpeningNoBookName,
        Sentence.OpeningPlan(OpeningFamily.PHILIDOR),
        Sentence.OpeningGeneric(e4, e5),
        Sentence.OpeningGenericNoMoves,
        Sentence.OpeningBothDeveloped,
        Sentence.TheoryEnds(8, PhaseStanding.BALANCED),
        Sentence.TheoryEnds(8, PhaseStanding.WHITE_EDGE),
        Sentence.OpeningVerdict(PhaseStanding.BLACK_CLEAR),
        Sentence.PhaseTurn(PhaseStanding.BALANCED, PhaseStanding.WHITE_EDGE, null),
        Sentence.PhaseTurn(PhaseStanding.BALANCED, PhaseStanding.WHITE_CLEAR, PhaseCause.Error(Color.BLACK, MoveClassification.BLUNDER, 11)),
        Sentence.PhaseTurn(PhaseStanding.WHITE_CLEAR, PhaseStanding.WHITE_DECISIVE, PhaseCause.WonMaterial(Color.WHITE, MaterialGain.PIECE)),
        Sentence.PhaseTurn(PhaseStanding.WHITE_DECISIVE, PhaseStanding.WHITE_EDGE, null),
        Sentence.PhaseTurn(PhaseStanding.WHITE_CLEAR, PhaseStanding.BALANCED, PhaseCause.Error(Color.WHITE, MoveClassification.MISTAKE, 17)),
        Sentence.PhaseTurn(PhaseStanding.WHITE_CLEAR, PhaseStanding.BALANCED, PhaseCause.MateFound(Color.BLACK, 3)),
        Sentence.PhaseTurn(PhaseStanding.WHITE_CLEAR, PhaseStanding.BLACK_CLEAR, PhaseCause.Error(Color.WHITE, MoveClassification.MISS, 22)),
        Sentence.SkipAhead(14, SkipKind.QUIET),
        Sentence.SkipAhead(14, SkipKind.TRADES),
        Sentence.SkipAhead(14, SkipKind.PLAIN),
        Sentence.JumpTo(14),
        Sentence.ALittleLater,
        Sentence.Played(viewer, MoveVerb.PLAY, nf3),
        Sentence.Played(black, MoveVerb.ANSWER_WITH, bxf3),
        Sentence.PlayedUnknown(black, MoveVerb.PLAY),
        Sentence.StillTheory,
        Sentence.OnlyLegalMove,
        Sentence.KingTuckedAway,
        Sentence.BrilliantFlavour,
        Sentence.GreatFlavour,
        Sentence.BestFlavour,
        Sentence.CheckForcesReply,
        Sentence.Filler,
        Sentence.MateAnnounce(3),
        Sentence.MateStep(bxf3),
        Sentence.MateFinish(viewer),
        Sentence.MateFinish(black),
        Sentence.MateOnReceivingEnd,
        Sentence.JustTheTrade,
        Sentence.NothingDefendingIt,
        Sentence.MaterialInTheBank(MaterialPayoff.ROOK),
        Sentence.PressureNoMaterialYet,
        Sentence.GoodSolid,
        Sentence.ErrorOpener(MoveClassification.BLUNDER),
        Sentence.ErrorOpener(MoveClassification.MISS),
        Sentence.ErrorOpener(MoveClassification.MISTAKE),
        Sentence.ConsequenceUnchanged(viewer, Standing.WINNING, LossSeverity.REAL_GROUND),
        Sentence.ConsequenceChanged(black, Standing.WINNING, Standing.ABOUT_LEVEL),
        Sentence.BetterHere(nf3, BetterResult.Plain),
        Sentence.BetterHere(nf3, BetterResult.Mates(3)),
        Sentence.BetterHere(bxf3, BetterResult.Wins(MaterialGain.ROOK)),
        Sentence.BetterHere(bxf3, BetterResult.Wins(null)),
        Sentence.AllowsMate(4),
        Sentence.PlayedThenReply(black, MoveVerb.PLAY, e5, recapture = false, opponent = Subject(Color.WHITE, Person.THIRD), reply = qh5, gain = null),
        Sentence.PlayedThenReply(viewer, MoveVerb.PLAY, nf3, recapture = false, opponent = black, reply = kxd1, gain = MaterialGain.ROOK),
        Sentence.MateWasAvailable(3, qh5),
        Sentence.ThreatLetIn,
        Sentence.InaccuracyNote,
        Sentence.PlayedSlip(black, MoveVerb.PLAY, e5),
        Sentence.PlayedSlip(viewer, MoveVerb.PLAY, nf3),
        Sentence.SmallSwing(viewer, favours = true),
        Sentence.SmallSwing(black, favours = false),
        Sentence.PivotIn,
        Sentence.PivotQuestion(Color.WHITE),
        Sentence.PivotReveal(nf3),
        Sentence.PivotWalk,
        Sentence.ExcursionMove(viewer, MoveVerb.PLAY, nf3, ExcursionStep.FIRST),
        Sentence.ExcursionMove(viewer, MoveVerb.PLAY, qh5, ExcursionStep.NEXT),
        Sentence.ExcursionMove(black, MoveVerb.ANSWER_WITH, kxd1, ExcursionStep.REPLY, recapture = true),
        Sentence.LineEndsInMate,
        Sentence.MaterialTaken(MaterialGain.ROOK),
        Sentence.AlreadyUp(black, MaterialGain.PIECE),
        Sentence.OnlyLegalReply,
        Sentence.ForcedByCheck,
        Sentence.RecaptureNatural,
        Sentence.PayoffLead,
        Sentence.PayoffMate(2),
        Sentence.PayoffMaterial(viewer, MaterialGain.ROOK),
        Sentence.PayoffOutcome(black, PayoffKind.DRAW_BY_REPETITION),
        Sentence.PayoffOutcome(black, PayoffKind.LINE_ENDS),
        Sentence.PayoffStanding(viewer, Standing.CLEARLY_BETTER),
        Sentence.MateBehindIt(4),
        Sentence.PivotOut,
        Sentence.LineMove(bxf3),
        Sentence.LineMove(nf3),
        Sentence.BackToTheGame,
        Sentence.ChanceGone,
        Sentence.InsteadPlayed(viewer, nf3),
        Sentence.TacticPoint(TacticType.HANGING_PIECE, Square.fromAlgebraic("f3"), PieceType.KNIGHT, null),
        Sentence.TacticPoint(TacticType.FORK, Square.fromAlgebraic("c7"), PieceType.ROOK, Square.fromAlgebraic("e8")),
        Sentence.TacticPoint(TacticType.PIN_ABSOLUTE, Square.fromAlgebraic("c6"), PieceType.KNIGHT, null),
        Sentence.TacticPoint(TacticType.SKEWER, Square.fromAlgebraic("d5"), PieceType.QUEEN, Square.fromAlgebraic("a8")),
        Sentence.TacticPoint(TacticType.ZWISCHENZUG, Square.fromAlgebraic("d4"), PieceType.KNIGHT, null),
        Sentence.PayoffMaterial(viewer, MaterialGain.EXCHANGE),
        Sentence.MaterialInTheBank(MaterialPayoff.MATERIAL),
        Sentence.TacticLesson(TacticType.FORK),
        Sentence.TextbookOffer(TacticType.FORK),
        Sentence.PuzzlePrompt(Color.WHITE, PuzzlePrize.WINS_PIECE),
        Sentence.TurningPointLead(significant = true),
        Sentence.TurningPointLead(significant = false),
        Sentence.TurningPointSwing(23),
        Sentence.TurningAftermath(AftermathKind.NEVER_BACK, Color.WHITE, null, null),
        Sentence.TurningAftermath(AftermathKind.FOUGHT_BACK, Color.BLACK, 31, PhaseStanding.BALANCED),
        Sentence.TurningAftermath(AftermathKind.FOUGHT_BACK, Color.BLACK, 31, PhaseStanding.BLACK_EDGE),
        Sentence.OutroEnding(Color.BLACK, drawn = false, byMate = true, fullMoves = 33),
        Sentence.OutroEnding(Color.WHITE, drawn = false, byMate = false, fullMoves = 40),
        Sentence.OutroEnding(null, drawn = true, byMate = false, fullMoves = 40),
        Sentence.OutroEnding(null, drawn = false, byMate = false, fullMoves = 23),
        Sentence.OutroDecider(black, MoveClassification.BLUNDER, 28, Standing.ABOUT_LEVEL, Standing.LOSING),
        Sentence.OutroDecider(viewer, MoveClassification.MISS, 12, Standing.WINNING, Standing.A_LITTLE_BETTER),
        Sentence.OutroNoDecider,
        Sentence.LessonLead(1),
        Sentence.LessonLead(3),
        Sentence.TakeawayMissed(viewer, 12, TacticType.FORK, nf3),
        Sentence.TakeawayMissed(black, 12, TacticType.X_RAY, null),
        Sentence.TakeawayAllowed(viewer, 28, Color.BLACK, mateIn = 3, reply = null),
        Sentence.TakeawayAllowed(black, 28, Color.WHITE, mateIn = null, reply = qh5),
        Sentence.TakeawayAllowed(black, 28, Color.WHITE, mateIn = null, reply = null),
        Sentence.TakeawayCostly(viewer, 23, LossSeverity.MOST_OF_THE_ADVANTAGE, Standing.CLEARLY_BETTER, Standing.ABOUT_LEVEL),
        Sentence.TakeawayCostly(black, 23, LossSeverity.REAL_GROUND, Standing.WINNING, Standing.WINNING),
        Sentence.TakeawayClean,
        Sentence.TakeawayHabitAllowed,
        Sentence.TakeawayHabitStanding(Standing.WINNING),
        Sentence.TakeawayHabitStanding(Standing.ABOUT_LEVEL),
        Sentence.TakeawayHabitStanding(Standing.LOSING),
        Sentence.LessonMatedButNotTheMistake(viewer, 23)
    )

    /**
     * The translator's table: every sentence id with its parameters and the reference rendering
     * in [strings] (one row per variant). Regenerate `docs/NARRATION_STRINGS.md` from this when
     * a sentence is added.
     */
    fun markdown(strings: NarrationStrings = NarrationLocales.default): String = buildString {
        appendLine("# Narration string catalogue (${strings.languageTag})")
        appendLine()
        appendLine("Generated from `NarrationCatalogue.samples()` through `${strings::class.simpleName}`.")
        appendLine("One row per sentence id; ids with several variants list each variant. The parameters")
        appendLine("column shows the *typed facts* the sentence carries — a translation may use them in any")
        appendLine("order, or ignore ones its grammar does not need. Where a `Subject` is second person the")
        appendLine("`gender` field is what a gendered language inflects on.")
        appendLine()
        appendLine("| Sentence id | Parameters (sample) | ${strings.languageTag} (COACH) | ${strings.languageTag} (ANALYST, when different) |")
        appendLine("|---|---|---|---|")
        for (sentence in samples()) {
            val id = sentence::class.simpleName ?: continue
            val params = paramsOf(sentence)
            val coach = strings.render(sentence, NarrationStyle.COACH)
            val analyst = strings.render(sentence, NarrationStyle.ANALYST)
            val analystCell = if (analyst == coach) "" else analyst.joinToString("<br>") { cell(it) }
            appendLine("| `$id` | ${cell(params)} | ${coach.joinToString("<br>") { cell(it) }} | $analystCell |")
        }
    }

    private fun paramsOf(sentence: Sentence): String {
        val text = sentence.toString()
        val open = text.indexOf('(')
        return if (open < 0) "" else text.substring(open + 1, text.length - 1)
    }

    private fun cell(s: String): String = s.replace("|", "\\|").replace("\n", " ").ifEmpty { "*(empty)*" }
}
