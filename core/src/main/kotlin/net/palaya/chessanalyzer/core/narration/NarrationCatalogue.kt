package net.palaya.chessanalyzer.core.narration

import net.palaya.chessanalyzer.core.analysis.MoveClassification
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
        Sentence.CardResultLine("1-0", 34),
        Sentence.CardAccuracyLine("85.0", "80.1"),
        Sentence.CaptionOpening("Philidor Defense", "C41"),
        Sentence.CaptionMoves(5, 9),
        Sentence.CaptionMove(12, Color.BLACK, "Nf6", "?"),
        Sentence.CaptionMissed("Nf3", "e4"),
        Sentence.CaptionMissedLine(listOf("Nf3", "e5", "Nxe5")),
        Sentence.CaptionBackToGame(12, Color.WHITE, "Nf3"),
        Sentence.CaptionPuzzle(Color.WHITE),
        Sentence.CaptionTurningPoint(23, "Qh5", "31.5"),
        Sentence.CaptionOutro("85.0", 1650, "80.1", 1500),
        Sentence.CaptionTakeaway(1, 3),
        Sentence.CardFinalNumbersHeading,
        Sentence.CardFinalPlayerLine("MorphyFan", "85.0", 1650),
        Sentence.CardFinalCountsLine(1, 2, 0, 1),
        Sentence.CardWorkOnHeading,
        Sentence.IntroNoNames,
        Sentence.IntroPlayers("MorphyFan", 1650, "DukeAndCount", null),
        Sentence.ResultDecisive(Color.WHITE, byMate = true, fullMoves = 17),
        Sentence.ResultDraw(40),
        Sentence.ResultUnfinished(23),
        Sentence.IntroYouWere(viewer),
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
        Sentence.TheoryRunsOut(8),
        Sentence.SkipAhead(14),
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
        Sentence.MateClosing,
        Sentence.MateFinish(viewer),
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
        Sentence.BetterWas(nf3),
        Sentence.MateWasAvailable(3, qh5),
        Sentence.ThreatLetIn,
        Sentence.InaccuracyNote,
        Sentence.PivotIn,
        Sentence.PivotReveal(nf3),
        Sentence.PivotWalk,
        Sentence.ExcursionMove(viewer, MoveVerb.PLAY, nf3, ExcursionStep.FIRST),
        Sentence.ExcursionMove(viewer, MoveVerb.PLAY, qh5, ExcursionStep.NEXT),
        Sentence.ExcursionMove(black, MoveVerb.ANSWER_WITH, kxd1, ExcursionStep.REPLY, recapture = true),
        Sentence.LineEndsInMate,
        Sentence.MaterialTaken(MaterialGain.ROOK),
        Sentence.CheckMustBeAnswered,
        Sentence.AlreadyUp(black, MaterialGain.PIECE),
        Sentence.QuietMove,
        Sentence.OnlyLegalReply,
        Sentence.ForcedByCheck,
        Sentence.RecaptureNatural,
        Sentence.BestDefence,
        Sentence.PayoffLead,
        Sentence.PayoffMate(2),
        Sentence.PayoffMaterial(viewer, MaterialGain.ROOK),
        Sentence.PayoffOutcome(black, PayoffKind.DRAW_BY_REPETITION),
        Sentence.MateBehindIt(4),
        Sentence.PivotOut,
        Sentence.ChanceGone,
        Sentence.InsteadPlayed(viewer, nf3),
        Sentence.TacticPoint(TacticType.HANGING_PIECE, Square.fromAlgebraic("f3"), PieceType.KNIGHT, null),
        Sentence.TacticPoint(TacticType.FORK, Square.fromAlgebraic("c7"), PieceType.ROOK, Square.fromAlgebraic("e8")),
        Sentence.TacticLesson(TacticType.FORK),
        Sentence.TextbookOffer(TacticType.FORK),
        Sentence.PuzzlePrompt(Color.WHITE, PuzzlePrize.WINS_PIECE),
        Sentence.TurningPointLead,
        Sentence.TurningPointSwing(23),
        Sentence.TurningPointFromTo(viewer, Standing.WINNING, Standing.ABOUT_LEVEL),
        Sentence.OutroLead,
        Sentence.OutroAccuracy("MorphyFan", "85.0", "DukeAndCount", "80.1"),
        Sentence.OutroRatings(1650, 1500),
        Sentence.NoMistakes("MorphyFan"),
        Sentence.ErrorCounts("DukeAndCount", 1, 2, 1, 0),
        Sentence.ShortGameCaveat,
        Sentence.LessonLead,
        Sentence.LessonRepeatedMiss(viewer, TacticType.FORK, 3, listOf(Square.fromAlgebraic("e5"), Square.fromAlgebraic("c7"))),
        Sentence.LessonSingleMiss(viewer, TacticType.FORK, 12, Square.fromAlgebraic("e5")),
        Sentence.LessonLateErrors(viewer, 3, 10),
        Sentence.LessonSpreadErrors(viewer, 4, 30),
        Sentence.LessonOneMistake(viewer, 23, "31.5"),
        Sentence.LessonGifts(viewer, 3),
        Sentence.LessonMatedButNotTheMistake(viewer, 23),
        Sentence.LessonOpponentMissedToo(viewer, TacticType.FORK, 2, 1),
        Sentence.LessonPositive(viewer, 5, 17, "85.0"),
        Sentence.LessonRatingAnchor(viewer, "85.0", 1650)
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
