package net.palaya.chessanalyzer.core.narration

import net.palaya.chessanalyzer.core.analysis.MoveClassification
import net.palaya.chessanalyzer.core.analysis.TacticType
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.PieceType
import net.palaya.chessanalyzer.core.chess.Square
import net.palaya.chessanalyzer.core.text.EnglishGrammar

/**
 * English narration. The reference implementation of [NarrationStrings] and the only place in
 * `:core` that contains English prose for the narrated review.
 *
 * Everything here is written to be *heard*: short sentences, contractions, the concrete square
 * named wherever the facts give one, and never a piece of algebraic notation — squares come out
 * as "f three" because every TTS engine reads a bare "f3" differently.
 *
 * Where [NarrationStyle.ANALYST] has its own wording it is listed second; a sentence with no
 * analyst variant uses the coach one, since most sentences are neutral facts either way.
 */
object EnglishNarration : NarrationStrings {

    override val languageTag: String = "en"

    override val vocabulary: SpokenVocabulary = Vocabulary

    override fun render(sentence: Sentence, style: NarrationStyle): List<String> {
        val coach = style == NarrationStyle.COACH
        return when (sentence) {
            // -- on-screen ------------------------------------------------------------
            is Sentence.VideoTitle -> one("${sentence.white} vs ${sentence.black}")
            is Sentence.VideoSubtitle -> one(
                listOfNotNull(
                    sentence.openingName?.let { n -> sentence.eco?.let { "$n ($it)" } ?: n },
                    sentence.result,
                    "analysed at depth ${sentence.depth}"
                ).joinToString(" · ")
            )
            Sentence.ChapterIntro -> one("Intro")
            Sentence.ChapterOpening -> one("The Opening")
            Sentence.ChapterMiddlegame -> one("Middlegame")
            is Sentence.ChapterMove -> one("Move ${sentence.moveNumber}: ${sentence.san}")
            Sentence.ChapterTurningPoint -> one("The Turning Point")
            Sentence.ChapterDamageReport -> one("The Damage Report")
            Sentence.ChapterWorkOn -> one("What To Work On")
            is Sentence.CardOpeningLine -> one(sentence.eco?.let { "${sentence.name} ($it)" } ?: sentence.name)
            is Sentence.CardResultLine -> one("${sentence.result} · ${cardMoveCount(sentence.fullMoves)}")
            is Sentence.CardAccuracyLine -> one("White ${sentence.whiteAccuracy}% · Black ${sentence.blackAccuracy}%")
            is Sentence.CaptionOpening -> one(
                listOfNotNull(sentence.name, sentence.eco?.let { "($it)" }).joinToString(" ").ifBlank { "Opening" }
            )
            is Sentence.CaptionMoves -> one("Moves ${sentence.fromMove}–${sentence.toMove}")
            is Sentence.CaptionMove -> one(moveCaption(sentence.moveNumber, sentence.color, sentence.san) + (sentence.glyph?.let { " $it" } ?: ""))
            is Sentence.CaptionMissed -> one(sentence.bestSan?.let { "Missed: $it (played ${sentence.playedSan})" } ?: "What was missed")
            is Sentence.CaptionMissedLine -> one("Missed line — ${sentence.sans.joinToString(" ")}")
            is Sentence.CaptionBestLine -> one("Best line — " + numberedLine(sentence.firstMoveNumber, sentence.firstColor, sentence.sans))
            is Sentence.CaptionBackToGame -> one("Back to the game — ${moveCaption(sentence.moveNumber, sentence.color, sentence.san)}")
            is Sentence.CaptionPuzzle -> one("${side(sentence.side)} to play — can you find it?")
            is Sentence.CaptionTurningPoint -> one("Turning point — move ${sentence.moveNumber} ${sentence.san} (${sentence.lossPercent}% swing)")
            is Sentence.CaptionOutro -> one(
                "White ${sentence.whiteAccuracy}% (${sentence.whiteRating}) · Black ${sentence.blackAccuracy}% (${sentence.blackRating})"
            )
            is Sentence.CaptionTakeaway -> one("Takeaway ${sentence.index} of ${sentence.total}")
            Sentence.CardFinalNumbersHeading -> one("Final numbers")
            is Sentence.CardFinalPlayerLine -> one("${sentence.name} — ${sentence.accuracy}%, est. ${sentence.rating}")
            is Sentence.CardFinalCountsLine -> one(
                "Blunders ${sentence.whiteBlunders}–${sentence.blackBlunders} · Mistakes ${sentence.whiteMistakes}–${sentence.blackMistakes}"
            )
            Sentence.CardWorkOnHeading -> one("What to work on")
            is Sentence.WalkthroughIntro -> one(walkthroughIntro(sentence))
            is Sentence.GameSummary -> one(gameSummary(sentence))

            // -- intro ----------------------------------------------------------------
            Sentence.IntroNoNames -> one("No names on this one, so it's White against Black.")
            is Sentence.IntroPlayers -> one(
                buildString {
                    append("${sentence.white} has the white pieces")
                    sentence.whiteRating?.let { append(", rated $it") }
                    append(". ${sentence.black} is on the other side")
                    sentence.blackRating?.let { append(", rated $it") }
                    append(".")
                }
            )
            is Sentence.ResultDecisive -> {
                val length = moveCount(sentence.fullMoves)
                val w = side(sentence.winner)
                one(if (sentence.byMate) "$w finished it with mate in $length." else "$w won it in $length.")
            }
            is Sentence.ResultDraw -> one("It ended in a draw after ${moveCount(sentence.fullMoves)}.")
            is Sentence.ResultUnfinished -> one("The game runs ${moveCount(sentence.fullMoves)} and stops there.")
            is Sentence.IntroYouWere -> one("You were ${side(sentence.subject.color)} here, so that's the side we're watching.")
            Sentence.HookMissedMate -> one("And somewhere in here there's a forced mate that never got played. We'll get to it.")
            is Sentence.HookBigTurn -> listOf(
                "But this whole game turns on one move, around move ${sentence.moveNumber}, and I'd bet most people watching walk straight past it.",
                "The result is not the story. One move, around move ${sentence.moveNumber}, decides everything, and it is not the obvious one.",
                "There's a single move in here, at move ${sentence.moveNumber}, that flips the entire game. Let's go find it."
            )
            is Sentence.HookClose -> one("It's closer than the result makes it look. One moment, around move ${sentence.moveNumber}, decides it.")
            Sentence.HookClean -> one("This one is clean from both sides, but there's still a crack in it, and we're going to find it.")
            Sentence.HookNoMistakes -> one("Not a mistake in sight from either player. So let's talk about what they both got right.")

            // -- opening ---------------------------------------------------------------
            is Sentence.OpeningLead -> listOf("This is the ${sentence.name}.", "Straight into the ${sentence.name}.", "We're in the ${sentence.name}.")
            Sentence.OpeningNoBookName -> one("No book name for this one.")
            is Sentence.OpeningPlan -> one(openingPlan(sentence.family))
            is Sentence.OpeningGeneric -> one(
                "White opened with ${Vocabulary.movePhrase(sentence.whiteFirst)}, Black answered with " +
                    "${Vocabulary.movePhrase(sentence.blackFirst)}, and from there it's a fight for the centre and the faster development."
            )
            Sentence.OpeningGenericNoMoves -> one("Both sides go for the centre and the faster development.")
            Sentence.OpeningBothDeveloped -> one("Both sides just developed.")
            is Sentence.TheoryRunsOut -> one("Theory runs out around move ${sentence.moveNumber}.")

            // -- connectives ----------------------------------------------------------------
            is Sentence.SkipAhead -> listOf(
                "The next few moves are just development and shuffling. Nothing breaks, so let's jump to move ${sentence.moveNumber}.",
                "I'm skipping ahead here. A handful of quiet moves go by with nobody blinking, and we pick it up at move ${sentence.moveNumber}.",
                "Nothing happens for a while. Both sides finish developing, and then we're at move ${sentence.moveNumber}, which is where it gets interesting."
            )
            is Sentence.JumpTo -> listOf("Forward to move ${sentence.moveNumber}. ", "Next stop, move ${sentence.moveNumber}. ", "Jumping to move ${sentence.moveNumber}. ")
            Sentence.ALittleLater -> listOf("A couple of moves later, ", "Two moves on, ", "Shortly after that, ")

            // -- the played move ---------------------------------------------------------
            is Sentence.Played -> one(moveClause(sentence.subject, sentence.verb, sentence.move, sentence.recapture))
            is Sentence.PlayedUnknown -> one("${subject(sentence.subject)} ${conjugate(sentence.subject, verb(sentence.verb))} on")

            // -- flavour -----------------------------------------------------------------
            Sentence.StillTheory -> listOf("Still theory.", "All book so far.", "Nothing new yet.")
            Sentence.OnlyLegalMove -> one("That was the only legal move, so there's no decision to make.")
            Sentence.KingTuckedAway -> listOf("King's tucked away.", "Safety first.", "Good, the king's off the middle.")
            Sentence.BrilliantFlavour -> one("And that is a brilliant move. It gives material away and the engine loves it anyway.")
            Sentence.GreatFlavour -> one("That was the only move that held, and it got found.")
            Sentence.BestFlavour -> listOf("Top of the engine's list.", "Best move on the board.", "The engine agrees.")
            Sentence.CheckForcesReply -> one("Check, so the reply is forced.")
            Sentence.Filler -> listOf("Sensible.", "No complaints.", "Fine.", "")

            // -- mate --------------------------------------------------------------------
            Sentence.MateClosing -> listOf("And that's the game.", "That's it. Game over.", "And there it is.")
            is Sentence.MateFinish -> one(if (sentence.subject.person == Person.SECOND) "You finish it off." else "${side(sentence.subject.color)} wins.")
            Sentence.MateOnReceivingEnd -> one("And that's you on the receiving end of it.")

            // -- found tactic --------------------------------------------------------------
            Sentence.JustTheTrade -> listOf("That's just the trade going through.", "Taking back, nothing more.", "Even trade, and we move on.")
            Sentence.NothingDefendingIt -> listOf("Nothing was defending it.", "It was sitting there with no defender.", "Completely undefended.", "It was en prise, with nothing defending it.")
            is Sentence.MaterialInTheBank -> {
                val what = materialPayoff(sentence.payoff)
                listOf("That's $what in the bank.", "${cap(what)}, just like that.", "So that's $what.")
            }
            Sentence.PressureNoMaterialYet -> one("It doesn't win material yet, but the pressure is real.")
            Sentence.GoodSolid -> one("Good, solid stuff.")

            // -- errors --------------------------------------------------------------------
            is Sentence.ErrorOpener -> when (sentence.classification) {
                MoveClassification.BLUNDER ->
                    if (coach) listOf("Oh no.", "And here it all falls apart.", "This is the one.", "Ouch.")
                    else listOf("This is the decisive error.", "Here the evaluation collapses.", "This is a blunder.")
                MoveClassification.MISS ->
                    if (coach) listOf("The win was sitting right there.", "This is the miss.", "So close.")
                    else listOf("A winning continuation was available.", "The decisive line was missed here.")
                else ->
                    if (coach) listOf("Careful.", "This is where it slips.", "Hang on.")
                    else listOf("This is a mistake.", "The position takes a turn here.")
            }
            is Sentence.ConsequenceUnchanged -> {
                val s = sentence.subject
                one("${cap(subject(s))} ${be(s)} still ${standing(sentence.standing)}, but that gave away ${lossSeverity(sentence.severity)}.")
            }
            is Sentence.ConsequenceChanged -> {
                val s = sentence.subject
                val who = subject(s)
                val before = standing(sentence.before)
                val after = standing(sentence.after)
                listOf(
                    "${cap(who)} ${was(s)} $before before that. Now $who ${be(s)} $after.",
                    "That's $before turning into $after in a single move.",
                    "${cap(who)} ${have(s)} gone from $before to $after."
                )
            }
            is Sentence.BetterWas -> {
                val phrase = Vocabulary.movePhrase(sentence.move)
                // "and everything holds" claimed the better move holds a position that may already be
                // lost; the engine's top move gives nothing away by definition, which is what is said (C1).
                listOf("${cap(phrase)} was the move.", "Instead, $phrase, which gives nothing away.", "The move was $phrase.")
            }
            is Sentence.MateWasAvailable ->
                one("There was mate in ${number(sentence.mateIn)} on the board, starting with ${Vocabulary.movePhrase(sentence.move)}.")
            Sentence.ThreatLetIn -> listOf(
                "and that's the move that lets it in.",
                "and now look what's available.",
                "and the reply is unpleasant."
            )
            is Sentence.EvalShift -> one(
                if (sentence.favours) "The evaluation moves in ${possessive(sentence.subject)} favour."
                else "The evaluation moves against ${subject(sentence.subject)}."
            )
            Sentence.InaccuracyNote -> listOf(
                "It's not losing, it's just loose.",
                "That's an inaccuracy — playable, but it gives something back.",
                "Slightly off."
            )

            // -- excursion -----------------------------------------------------------------
            Sentence.PivotIn ->
                if (coach) listOf(
                    "Now hold on. Let's rewind, because there was something much better here. ",
                    "Stop. Freeze it right there — this position had far more in it. ",
                    "Hold on, back up a move. There was a much better idea sitting right here. "
                ) else listOf(
                    "Pause here. A stronger continuation was available. ",
                    "Hold the position a moment: there was a better move than the one played. "
                )
            is Sentence.PivotReveal -> {
                val phrase = Vocabulary.movePhrase(sentence.move)
                if (coach) listOf("The move is $phrase.", "It starts with $phrase.", "The move was $phrase.")
                else listOf("The move is $phrase.", "The correct continuation begins with $phrase.")
            }
            Sentence.PivotWalk ->
                if (coach) listOf("Let's play it out.", "Let's walk it through, move by move.", "Watch what happens if that goes in.")
                else listOf("The line runs as follows.", "Here it is, move by move.")
            is Sentence.ExcursionMove -> {
                val clause = moveClause(sentence.subject, sentence.verb, sentence.move, sentence.recapture)
                when (sentence.step) {
                    ExcursionStep.FIRST -> listOf("So: $clause.", "It starts here. ${cap(clause)}.", "Here we go. ${cap(clause)}.")
                    ExcursionStep.NEXT -> listOf("Then $clause.", "Next, $clause.", "And then $clause.")
                    ExcursionStep.REPLY -> one("${cap(clause)}.")
                }
            }
            Sentence.LineEndsInMate -> one("The king has nowhere left to go, and the line stops there.")
            is Sentence.MaterialTaken -> {
                val what = materialGain(sentence.gain)
                listOf("That is $what off the board.", "That is $what, collected.", "And there goes $what.")
            }
            Sentence.CheckMustBeAnswered -> listOf(
                "The king has to answer that first, which is the whole point of the move order.",
                "Check, so the reply is not a choice.",
                "The king comes under fire, and nothing else gets done."
            )
            is Sentence.AlreadyUp -> {
                val s = sentence.subject
                one("${cap(subject(s))} ${be(s)} already ${materialGain(sentence.gain)} up here, and the pressure is not going away.")
            }
            Sentence.QuietMove -> listOf(
                "No fireworks. It just keeps everything pointed at the same weakness.",
                "That is the quiet move, and it is the one that makes the rest work.",
                "Nothing is captured, and nothing has to be. The threat does the work."
            )
            Sentence.OnlyLegalReply -> one("There is nothing else legal.")
            Sentence.ForcedByCheck -> listOf(
                "Forced — the king was in check, so nothing else was even legal.",
                "Forced. The check has to be answered first."
            )
            Sentence.RecaptureNatural -> listOf(
                "Taking back is the natural answer, and it changes nothing.",
                "The recapture is more or less forced, and it does not help."
            )
            Sentence.BestDefence -> listOf(
                "That is the best defence on offer.",
                "That is the toughest try in the position.",
                "That is the engine's own choice, so nothing better exists."
            )
            Sentence.PayoffLead ->
                if (coach) listOf("And there it is. ", "So add it up. ", "Now look at the end of that line. ")
                else listOf("The result of the line: ", "At the end of the line, ")
            is Sentence.PayoffMate -> one("That is checkmate in ${number(sentence.mateIn)}.")
            is Sentence.PayoffMaterial -> {
                val s = sentence.subject
                one("${cap(subject(s))} ${conjugate(s, "come")} out of it ${materialGain(sentence.gain)} up.")
            }
            is Sentence.PayoffOutcome -> {
                val s = sentence.subject
                val who = cap(subject(s))
                one(
                    when (sentence.kind) {
                        PayoffKind.DRAW_BY_REPETITION -> "$who ${conjugate(s, "force")} a draw by repetition."
                        PayoffKind.STALEMATE -> "$who ${conjugate(s, "set")} up a stalemate the opponent cannot avoid."
                        PayoffKind.DESPERADO -> "$who ${conjugate(s, "get")} the most out of a piece that was lost anyway."
                        PayoffKind.PASSED_PAWN -> "$who ${conjugate(s, "create")} a passed pawn nobody can catch."
                        PayoffKind.SMALL_MATERIAL -> "$who ${conjugate(s, "win")} material."
                        PayoffKind.KING_UNDER_FIRE -> "$who ${conjugate(s, "keep")} the king under fire."
                        PayoffKind.MATING_NET -> "$who ${conjugate(s, "leave")} the king in a mating net."
                        PayoffKind.INVESTED_MATERIAL -> "$who ${have(s)} invested material in the attack."
                        PayoffKind.DECISIVE_ADVANTAGE -> "$who ${conjugate(s, "end")} up completely on top."
                        PayoffKind.LINE_ENDS -> "That is as far as the line goes."
                    }
                )
            }
            is Sentence.MateBehindIt -> one("And there was mate in ${number(sentence.mateIn)} behind it.")
            Sentence.PivotOut ->
                if (coach) listOf(
                    "Back in the real game, though, that got played instead —",
                    "But that is the line that never was. In the real game, this went on the board —",
                    "So, back to reality. What actually happened was this —"
                ) else listOf(
                    "Returning to the game as played, the move actually chosen was this —",
                    "Back to the main line. What was played instead was this —"
                )
            Sentence.ChanceGone ->
                if (coach) listOf(
                    "And the chance is gone. It does not come back.",
                    "The moment passes, and that is that.",
                    "And with that, the window shuts."
                ) else listOf(
                    "The opportunity does not recur.",
                    "That continuation is no longer available."
                )
            is Sentence.InsteadPlayed -> {
                val clause = moveClause(sentence.subject, MoveVerb.PLAY, sentence.move, sentence.recapture)
                listOf(
                    "Instead, $clause, and it's gone.",
                    "What actually happened: $clause. The chance never comes back.",
                    "But no — $clause, and the moment passes."
                )
            }
            is Sentence.TacticPoint -> tacticPoint(sentence)
            is Sentence.TacticLesson -> one(tacticLesson(sentence.type))
            is Sentence.TextbookOffer ->
                one("There's a clean textbook ${tacticName(sentence.type)} waiting in the game report if you want to see the pattern on its own.")

            // -- puzzle ---------------------------------------------------------------------
            is Sentence.PuzzlePrompt -> {
                val s = side(sentence.side)
                val prize = when (sentence.prize) {
                    PuzzlePrize.FORCES_MATE -> "forces mate"
                    PuzzlePrize.WINS_ROOK_OR_BETTER -> "wins a rook or better"
                    PuzzlePrize.WINS_PIECE -> "wins a whole piece"
                    PuzzlePrize.WINS_MATERIAL -> "wins material on the spot"
                }
                listOf(
                    "$s to play. There is a move here that $prize. Pause the video. Can you find it?",
                    "$s to play, and there's a move here that $prize. Stop the video and have a proper look.",
                    "Right, $s to play. Something here $prize. Pause it. See if you spot it."
                )
            }

            // -- turning point -------------------------------------------------------------
            Sentence.TurningPointLead ->
                if (coach) listOf(
                    "And that is the turning point of the whole game. ",
                    "Stop there, because that is the moment the game changed hands. ",
                    "That right there is where this game was decided. "
                ) else listOf(
                    "This is the largest evaluation swing in the game. ",
                    "This move produces the decisive change in evaluation. "
                )
            is Sentence.TurningPointSwing -> one("Nothing else in this game swings the evaluation like move ${sentence.moveNumber}.")
            is Sentence.TurningPointFromTo ->
                one("${cap(subject(sentence.subject))} went from ${standing(sentence.before)} to ${standing(sentence.after)} on one move.")

            // -- outro ----------------------------------------------------------------------
            Sentence.OutroLead -> listOf("Right, numbers. ", "Let's put numbers on it. ", "So where did that leave us? ")
            is Sentence.OutroAccuracy ->
                one("${sentence.white} finished on ${sentence.whiteAccuracy} percent accuracy, ${sentence.black} on ${sentence.blackAccuracy} percent.")
            is Sentence.OutroRatings -> one("That's play at roughly ${sentence.whiteRating} and ${sentence.blackRating}.")
            is Sentence.NoMistakes -> one("${sentence.name} did not make a single mistake the engine cares about.")
            is Sentence.ErrorCounts -> {
                val parts = ArrayList<String>()
                if (sentence.blunders > 0) parts.add("${number(sentence.blunders)} ${plural("blunder", sentence.blunders)}")
                if (sentence.mistakes > 0) parts.add("${number(sentence.mistakes)} ${plural("mistake", sentence.mistakes)}")
                if (sentence.misses > 0) parts.add("${number(sentence.misses)} missed ${plural("win", sentence.misses)}")
                if (sentence.inaccuracies > 0) parts.add("${number(sentence.inaccuracies)} ${plural("inaccuracy", sentence.inaccuracies)}")
                one("${sentence.name} had ${listJoin(parts)}.")
            }
            Sentence.ShortGameCaveat -> one("It's a short game, so treat those rating estimates as a rough guide rather than gospel.")

            // -- lessons ---------------------------------------------------------------------
            is Sentence.LessonLead -> buildList {
                add("So what do you take away from this? ")
                add(if (sentence.count == 1) "Here's the one thing to actually work on. " else "Here's what to actually work on. ")
                // A lead that counts the lessons is only used when the count is right.
                when (sentence.count) {
                    2 -> add("Two things to take out of this game. ")
                    3 -> add("Three things to take out of this game. ")
                    4 -> add("Four things to take out of this game. ")
                }
            }
            is Sentence.LessonRepeatedMiss -> {
                val squares = sentence.squares.map { Vocabulary.square(it) }
                val where = when (squares.size) {
                    0 -> ""
                    1 -> " The one that hurt was on ${squares[0]}."
                    else -> " They were on ${listJoin(squares)}."
                }
                one("${cap(subject(sentence.subject))} walked past ${countedTactic(sentence.type, sentence.count)} in this game.$where")
            }
            is Sentence.LessonSingleMiss -> {
                val at = sentence.moveNumber?.let { " at move $it" } ?: ""
                val where = sentence.square?.let { " The one that hurt was on ${Vocabulary.square(it)}." } ?: ""
                one("The one thing ${subject(sentence.subject)} left on the board was ${EnglishGrammar.withArticle(tacticName(sentence.type))}$at.$where")
            }
            is Sentence.LessonLateErrors -> one(
                "Every one of ${possessive(sentence.subject)} ${number(sentence.count)} errors came after move ${sentence.afterMove}, " +
                    "once the pieces were out and real decisions started. That's a calculation problem, not an opening problem — " +
                    "give yourself an extra thirty seconds the moment the position opens up."
            )
            is Sentence.LessonSpreadErrors -> one(
                "${cap(possessive(sentence.subject))} mistakes ran from move ${sentence.firstMove} to move ${sentence.lastMove}, " +
                    "so they're spread right across the game rather than bunched at the end. That usually means the checks are missing, not the stamina."
            )
            is Sentence.LessonOneMistake -> one(
                "One mistake. Move ${sentence.moveNumber} cost ${sentence.lossPercent} percent of the position on its own, and everything else " +
                    "${subject(sentence.subject)} played was fine. A game like this is decided by one moment, so the drill is " +
                    "simple: on every move where material can change hands, stop and check the whole board."
            )
            is Sentence.LessonGifts -> one(
                "${cap(subject(sentence.subject))} handed over a tactic ${number(sentence.count)} times in this game — moves that were fine " +
                    "in themselves but let something in. Before you commit, ask one question: what does this let them do next?"
            )
            is Sentence.LessonMatedButNotTheMistake -> one(
                "${cap(subject(sentence.subject))} got mated at the end, but the mate wasn't the mistake — move ${sentence.turningMove} was. " +
                    "By the time the king was getting hit there was nothing left to defend with. Fix move ${sentence.turningMove} and the mate never happens."
            )
            is Sentence.LessonOpponentMissedToo -> {
                val s = sentence.subject
                val their = if (s.person == Person.SECOND) "your" else "their"
                one(
                    "Worth knowing: the other side left ${countedTactic(sentence.type, sentence.count)} on the board too, and " +
                        "${subject(s)} converted ${number(sentence.converted)} ${plural("chance", sentence.converted)} of $their own. " +
                        "Both players are missing the same kind of thing, which means whoever drills it first wins the rematch."
                )
            }
            is Sentence.LessonPositive -> one(
                "${cap(subject(sentence.subject))} found the engine's top move ${timesWord(sentence.bestMoves)} out of ${sentence.totalMoves}, " +
                    "for ${sentence.accuracy} percent. The plan-making is working; the gap is in the tactics, and tactics are the part you can drill."
            )
            is Sentence.LessonRatingAnchor -> one(
                "${cap(subject(sentence.subject))} came out of this at ${sentence.accuracy} percent, which the model reads as about ${sentence.rating}. " +
                    "The next step up is fewer than one blunder a game, and that's a habit, not talent."
            )
        }
    }

    // -----------------------------------------------------------------------
    // Subjects and verb agreement
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // On-screen lines: the walkthrough intro and the one-sentence game summary
    // -----------------------------------------------------------------------

    /**
     * Two sentences, never one clause grafted into another: the detector's description is a
     * finished, capitalised sentence of its own (it may start with a move such as "Qxa1+"), so it
     * is placed after a full stop rather than lowercased behind "a line that".
     */
    private fun walkthroughIntro(s: Sentence.WalkthroughIntro): String {
        val lead = s.firstSan?.let { "Watch what happens: $it starts the line." } ?: "Watch what happens."
        val point = s.point ?: "The tactic: ${tacticName(s.tactic)}."
        return "$lead $point"
    }

    private fun gameSummary(s: Sentence.GameSummary): String {
        val subject = s.subject?.let { summaryWho(it, s.viewerKnown) }
        val opponent = s.opponent?.let { summaryWho(it, s.viewerKnown) }
        val was = s.subject?.let { if (it.person == Person.SECOND) "were" else "was" } ?: "was"
        val bare = errorName(s.error)
        val named = EnglishGrammar.withArticle(bare)
        val onMove = s.moveNumber?.let { " on move $it" } ?: ""
        return when (s.kind) {
            SummaryKind.DECIDED_BY_ERROR ->
                "${cap(subject.orEmpty())} $was fine until ${s.moveNumber?.let { "move $it, then " } ?: ""}$named decided it."
            SummaryKind.SEALED_BY_ERROR ->
                "${cap(subject.orEmpty())} $was already under pressure, and $named$onMove sealed it."
            SummaryKind.COMEBACK ->
                "${cap(subject.orEmpty())} $was behind when " +
                    "${s.opponent?.let { summaryPossessive(it, s.viewerKnown) }.orEmpty()} $bare$onMove turned the game around."
            SummaryKind.WON_DESPITE_ERROR ->
                "${cap(subject.orEmpty())} won, even after $named$onMove."
            SummaryKind.CLEAN_WIN -> {
                val how = if (s.byMate) " by checkmate" + (s.fullMoves?.let { " on move $it" } ?: "") else ""
                "${cap(subject.orEmpty())} won$how, and neither side made a big mistake."
            }
            SummaryKind.SHORT_GAME -> {
                val length = s.fullMoves?.let { moveCount(it) } ?: "a few moves"
                when {
                    subject != null && opponent != null && s.byMate -> "A short game: $subject mated $opponent in $length."
                    subject != null -> "A short game: $subject won in $length."
                    else -> "A short game: it ended in a draw after $length."
                }
            }
            SummaryKind.DRAW_WITH_SWING ->
                "It ended in a draw, but ${s.subject?.let { summaryPossessive(it, s.viewerKnown) }.orEmpty()} $bare$onMove was the big swing."
            SummaryKind.CLOSE_CLEAN -> "A close game: neither side made a big mistake."
            SummaryKind.UNFINISHED ->
                "The game stops after ${s.fullMoves?.let { moveCount(it) } ?: "a few moves"} without a result."
        }
    }

    /** "you" for the viewer, "your opponent" for the other side once the viewer is known, else the colour. */
    private fun summaryWho(s: Subject, viewerKnown: Boolean): String = when {
        s.person == Person.SECOND -> "you"
        viewerKnown -> "your opponent"
        else -> side(s.color)
    }

    private fun summaryPossessive(s: Subject, viewerKnown: Boolean): String = when {
        s.person == Person.SECOND -> "your"
        viewerKnown -> "your opponent's"
        else -> "${side(s.color)}'s"
    }

    private fun errorName(error: SummaryError?): String = when (error) {
        SummaryError.BLUNDER -> "blunder"
        SummaryError.MISTAKE -> "mistake"
        SummaryError.MISSED_WIN -> "missed win"
        SummaryError.BIG_SWING, null -> "big swing"
    }

    private fun subject(s: Subject): String = if (s.person == Person.SECOND) "you" else side(s.color)

    private fun possessive(s: Subject): String = if (s.person == Person.SECOND) "your" else "${side(s.color)}'s"

    private fun be(s: Subject): String = if (s.person == Person.SECOND) "are" else "is"
    private fun was(s: Subject): String = if (s.person == Person.SECOND) "were" else "was"
    private fun have(s: Subject): String = if (s.person == Person.SECOND) "have" else "has"

    /** The base verb for second person, third-person singular otherwise. */
    private fun conjugate(s: Subject, base: String): String = if (s.person == Person.SECOND) base else thirdPerson(base)

    private fun verb(v: MoveVerb): String = when (v) {
        MoveVerb.PLAY -> "play"
        MoveVerb.GO_FOR -> "go for"
        MoveVerb.ANSWER_WITH -> "answer with"
        MoveVerb.CONTINUE_WITH -> "continue with"
        MoveVerb.REPLY_WITH -> "reply with"
        MoveVerb.FOLLOW_UP_WITH -> "follow up with"
        MoveVerb.FIND -> "find"
    }

    /**
     * A full clause with a subject. Three shapes, because one template cannot cover them and
     * still be English: "White castles kingside" (never "plays castles kingside"); "Black takes
     * the knight on f three with the bishop", which is how a commentator says a capture; and
     * "White plays knight to f three" for everything else.
     */
    private fun moveClause(s: Subject, verb: MoveVerb, m: SpokenMove, recapture: Boolean): String {
        val who = subject(s)
        val suffix = outcomeSuffix(m.outcome)
        if (m.castle != null) {
            val sideWord = if (m.castle == CastleSide.KINGSIDE) "kingside" else "queenside"
            return "$who ${conjugate(s, "castle")} $sideWord$suffix"
        }
        if (m.isCapture) return captureClause(s, m, recapture) + suffix
        return "$who ${conjugate(s, verb(verb))} ${Vocabulary.movePhrase(m)}"
    }

    private fun captureClause(s: Subject, m: SpokenMove, recapture: Boolean): String {
        val mover = piece(m.piece)
        val to = Vocabulary.square(m.to)
        val withPart = if (m.ambiguous) "with the $mover on ${Vocabulary.square(m.from)}" else "with the $mover"
        val v = if (recapture) conjugate(s, "take") + " back" else conjugate(s, "take")
        val obj = when {
            recapture -> "on $to"
            m.enPassant -> "on $to, en passant,"
            m.captured != null -> "the ${piece(m.captured)} on $to"
            else -> "on $to"
        }
        val promo = m.promotion?.let { ", promoting to ${aPiece(it)}" } ?: ""
        return "${subject(s)} $v $obj $withPart$promo"
    }

    /**
     * Naive but sufficient third-person-singular agreement. Only the head verb is conjugated, so
     * a phrasal verb comes out as "goes for", not "go fors".
     */
    internal fun thirdPerson(verb: String): String {
        val space = verb.indexOf(' ')
        if (space > 0) return thirdPerson(verb.substring(0, space)) + verb.substring(space)
        return when (verb) {
            "go" -> "goes"
            "do" -> "does"
            "have" -> "has"
            "be" -> "is"
            else -> when {
                verb.endsWith("s") || verb.endsWith("x") || verb.endsWith("sh") || verb.endsWith("ch") -> verb + "es"
                verb.endsWith("y") && verb.length > 1 && verb[verb.length - 2] !in "aeiou" -> verb.dropLast(1) + "ies"
                else -> verb + "s"
            }
        }
    }

    // -----------------------------------------------------------------------
    // Words for the enums
    // -----------------------------------------------------------------------

    /** The same words the card text uses for the same bands (`CommentaryGenerator.standingWords`, C1). */
    private fun standing(s: Standing): String = when (s) {
        Standing.COMPLETELY_WINNING -> "decisively winning"
        Standing.WINNING -> "winning"
        Standing.CLEARLY_BETTER -> "clearly better"
        Standing.A_LITTLE_BETTER -> "slightly better"
        Standing.ABOUT_LEVEL -> "about level"
        Standing.SLIGHTLY_WORSE -> "slightly worse"
        Standing.CLEARLY_WORSE -> "clearly worse"
        Standing.LOSING -> "losing"
        Standing.COMPLETELY_LOST -> "decisively lost"
    }

    private fun lossSeverity(l: LossSeverity): String = when (l) {
        LossSeverity.THE_WHOLE_GAME -> "the whole game"
        LossSeverity.MOST_OF_THE_ADVANTAGE -> "most of the advantage"
        LossSeverity.A_BIG_CHUNK -> "a big chunk of the position"
        LossSeverity.REAL_GROUND -> "real ground"
        LossSeverity.A_LITTLE -> "a little something"
    }

    private fun materialPayoff(p: MaterialPayoff): String = when (p) {
        MaterialPayoff.WHOLE_QUEEN -> "a whole queen"
        MaterialPayoff.ROOK -> "a rook"
        MaterialPayoff.PIECE -> "a piece"
        MaterialPayoff.SERIOUS_MATERIAL -> "serious material"
        MaterialPayoff.PAWN -> "a pawn"
        MaterialPayoff.MATERIAL -> "material"
        MaterialPayoff.BETTER_POSITION -> "a much better position"
    }

    private fun materialGain(g: MaterialGain?): String = when (g) {
        MaterialGain.QUEEN -> "a queen"
        MaterialGain.ROOK -> "a rook"
        MaterialGain.EXCHANGE -> "the exchange"
        MaterialGain.PIECE -> "a piece"
        MaterialGain.PAWN -> "a pawn"
        null -> "material"
    }

    private fun tacticName(type: TacticType): String = type.displayName.lowercase()

    /** Plural-safe spoken name, e.g. "two forks", "one hanging piece". */
    private fun countedTactic(type: TacticType, count: Int): String {
        val name = tacticName(type)
        val plural = when {
            count == 1 -> name
            name.endsWith("piece") -> name + "s"
            name.endsWith("h") || name.endsWith("s") -> name + "es"
            else -> name + "s"
        }
        return "${number(count)} $plural"
    }

    private fun number(n: Int): String = when (n) {
        0 -> "no"
        1 -> "one"
        2 -> "two"
        3 -> "three"
        4 -> "four"
        5 -> "five"
        6 -> "six"
        7 -> "seven"
        8 -> "eight"
        9 -> "nine"
        10 -> "ten"
        else -> n.toString()
    }

    private fun moveCount(fullMoves: Int): String = if (fullMoves == 1) "one move" else "$fullMoves moves"

    /** The on-screen form of a move count: digits, singular for exactly one ("1 move", "17 moves"). */
    private fun cardMoveCount(fullMoves: Int): String = if (fullMoves == 1) "1 move" else "$fullMoves moves"

    /** "once", "twice", "five times": how often something happened, never "one times". */
    private fun timesWord(n: Int): String = when (n) {
        0 -> "not once"
        1 -> "once"
        2 -> "twice"
        else -> "${number(n)} times"
    }

    private fun plural(word: String, n: Int): String = when {
        n == 1 -> word
        word.endsWith("y") -> word.dropLast(1) + "ies"
        else -> word + "s"
    }

    private fun listJoin(parts: List<String>): String = when (parts.size) {
        0 -> ""
        1 -> parts[0]
        2 -> "${parts[0]} and ${parts[1]}"
        else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
    }

    private fun moveCaption(moveNumber: Int, color: Color, san: String): String =
        if (color == Color.WHITE) "$moveNumber. $san" else "$moveNumber... $san"

    /** "18... Nf5 19. Qd2 Nd4": a number before every White move, and before a first move by Black. */
    private fun numberedLine(firstMoveNumber: Int, firstColor: Color, sans: List<String>): String {
        val parts = ArrayList<String>()
        var number = firstMoveNumber
        var color = firstColor
        for ((i, san) in sans.withIndex()) {
            parts += when {
                color == Color.WHITE -> "$number. $san"
                i == 0 -> "$number... $san"
                else -> san
            }
            if (color == Color.BLACK) number++
            color = color.opposite()
        }
        return parts.joinToString(" ")
    }

    private fun side(color: Color): String = if (color == Color.WHITE) "White" else "Black"

    private fun piece(type: PieceType): String = Vocabulary.piece(type)

    private fun aPiece(type: PieceType): String = "a ${piece(type)}"

    private fun outcomeSuffix(outcome: MoveOutcome): String = when (outcome) {
        MoveOutcome.CHECKMATE -> ", and that is checkmate"
        MoveOutcome.CHECK -> ", with check"
        MoveOutcome.NONE -> ""
    }

    private fun cap(s: String): String = if (s.isEmpty()) s else s[0].uppercaseChar() + s.substring(1)

    private fun one(s: String): List<String> = listOf(s)

    // -----------------------------------------------------------------------
    // Tactics, spoken
    // -----------------------------------------------------------------------

    /**
     * One spoken sentence about a motif, in a commentator's vocabulary (C1): the motif is named by its
     * proper term ("a fork", "an absolute pin", "a zwischenzug", "en prise") and the sentence says what
     * the detector proved and no more - "is attacked", "is exposed", never "falls" for a piece the
     * opponent may still save. Several phrasings where the facts allow; the generator rotates them.
     */
    private fun tacticPoint(t: Sentence.TacticPoint): List<String> {
        val targetSpoken = t.target?.let { Vocabulary.square(it) }
        val victim = t.victim?.let { piece(it) }
        val second = t.second?.let { Vocabulary.square(it) }
        return when (t.type) {
            TacticType.HANGING_PIECE ->
                if (victim != null && targetSpoken != null) listOf(
                    "The $victim on $targetSpoken has nothing defending it.",
                    "The $victim on $targetSpoken is en prise: nothing defends it.",
                    "The $victim on $targetSpoken is loose, with no defender at all."
                ) else one("There's a piece sitting there with no defender at all.")

            TacticType.FORK, TacticType.PAWN_FORK -> {
                val forkWord = if (t.type == TacticType.PAWN_FORK) "pawn fork" else "fork"
                if (targetSpoken != null && second != null) listOf(
                    "It hits two things at once, on $targetSpoken and on $second.",
                    "That's a $forkWord: $targetSpoken and $second are both attacked, and only one of them can get away.",
                    "A $forkWord. Two targets, on $targetSpoken and on $second, and only one can be saved."
                ) else listOf(
                    "It hits two things at once, and only one of them can run.",
                    "That's a $forkWord: two pieces attacked by one, and only one of them can get away."
                )
            }

            TacticType.DOUBLE_ATTACK ->
                if (targetSpoken != null && second != null) listOf(
                    "It hits two things at once, on $targetSpoken and on $second.",
                    "That's a double attack: $targetSpoken and $second are both hit, and only one can be saved."
                ) else one("It hits two things at once, and only one of them can run.")

            TacticType.PIN_ABSOLUTE ->
                if (targetSpoken != null) listOf(
                    "The piece on $targetSpoken is pinned to the king, so it cannot leave that line.",
                    "That's an absolute pin: the piece on $targetSpoken is tied to its own king and cannot step off the line."
                ) else one("That piece is pinned to the king and cannot leave the line.")

            TacticType.PIN_RELATIVE ->
                if (targetSpoken != null) listOf(
                    "The piece on $targetSpoken is pinned, and moving it costs more than staying put.",
                    "That's a relative pin: the piece on $targetSpoken is tied to something worth more behind it."
                ) else one("That piece is pinned, and moving it costs more than staying put.")

            TacticType.SKEWER ->
                if (targetSpoken != null && second != null) listOf(
                    "The piece on $targetSpoken has to step aside, and the one behind it on $second is exposed.",
                    "That's a skewer: the piece on $targetSpoken is attacked, and whatever stands behind it on $second is next."
                ) else one("The front piece has to move, and whatever is behind it is exposed.")

            TacticType.DISCOVERED_ATTACK -> listOf(
                "Moving that piece uncovers an attack from the one standing behind it.",
                "A discovered attack: the piece steps aside and unmasks the one behind it."
            )
            TacticType.DISCOVERED_CHECK -> listOf(
                "Moving that piece gives check from behind, so the reply is forced.",
                "A discovered check: the piece steps aside, and the one behind it checks the king."
            )
            TacticType.DOUBLE_CHECK -> listOf(
                "It's a double check. Against a double check the king has to move, nothing else is even legal.",
                "Double check. The king has to move, and nothing else is legal."
            )

            TacticType.BACK_RANK_MATE ->
                if (targetSpoken != null) listOf(
                    "It's a back rank mate on $targetSpoken. The king's own pawns are the problem.",
                    "Back-rank mate on $targetSpoken: the king's own pawns shut it in."
                ) else one("It's a back rank mate, and the king's own pawns are the problem.")

            TacticType.SMOTHERED_MATE -> one("It's a smothered mate. The king suffocates between its own pieces.")
            TacticType.GREEK_GIFT -> one("It's the Greek gift. Bishop goes in, the king gets dragged out, the knight and queen finish it.")
            TacticType.WINDMILL -> one("It's a windmill. Check, take, check, take, and the material just piles up.")

            TacticType.DEFLECTION ->
                if (targetSpoken != null) listOf(
                    "It drags the defender off $targetSpoken, and once it's gone nothing holds together.",
                    "A deflection: the defender is pulled away from $targetSpoken, and what it was guarding is left open."
                ) else one("It drags the defender away from the job it was doing.")

            TacticType.DECOY -> listOf(
                "It lures a piece onto a square where it gets hit, and that's the whole point.",
                "A decoy: it drags a piece onto the wrong square, and that is the whole idea."
            )
            TacticType.OVERLOADED_PIECE -> listOf(
                "One piece is doing two jobs here, and it can't do both.",
                "An overloaded defender: one piece holding two things, and it can only keep one."
            )
            TacticType.REMOVING_THE_DEFENDER ->
                if (targetSpoken != null) listOf(
                    "Take the defender first, and then $targetSpoken is left without its guard.",
                    "Removing the defender: once it is gone, $targetSpoken has nothing holding it."
                ) else one("Take the defender first, and then everything it was holding is left open.")

            TacticType.TRAPPED_PIECE ->
                if (targetSpoken != null) listOf(
                    "The piece on $targetSpoken has no squares left. It's trapped.",
                    "The piece on $targetSpoken is trapped: every square it can reach loses it."
                ) else one("That piece has run out of squares.")

            TacticType.INTERFERENCE -> one("It cuts the defender's line, and the piece it was covering is left open.")
            TacticType.CLEARANCE -> one("It clears the square for the piece coming in behind it.")
            TacticType.ZWISCHENZUG -> listOf(
                "A zwischenzug, an in-between move: something forcing goes in first, and the capture is still there afterwards.",
                "The in-between move comes first. The capture is not going anywhere."
            )
            TacticType.MATE_NET -> listOf(
                "It's a forced mate. The king has nowhere to run.",
                "This is a forced mate, and the king cannot get out."
            )
            TacticType.PERPETUAL_CHECK -> one("It's a perpetual. Check, check, and the game is drawn by repetition.")
            TacticType.STALEMATE_TRICK -> one("There's a stalemate trick here, and it saves the half point.")
            TacticType.PROMOTION_TACTIC -> one("The pawn is going to make a new queen and nothing stops it.")
            TacticType.UNDERPROMOTION -> one("The pawn promotes to a knight instead of a queen, and that's the only move that works.")
            TacticType.PASSED_PAWN_BREAKTHROUGH -> one("The pawn breaks through, and the queening square can't be covered.")
            TacticType.DESPERADO -> listOf(
                "That piece is lost anyway, so it takes something on its way out.",
                "A desperado: the piece is lost anyway, so it sells itself as dearly as it can."
            )
            TacticType.BATTERY -> one("The queen and the rook are lined up on the same file, and that is a lot of pressure.")
            TacticType.X_RAY -> one("The attack goes straight through the piece in the middle.")
            TacticType.FORTRESS -> one("It builds a fortress. Extra material means nothing if it can't get in.")
        }
    }

    private fun tacticLesson(type: TacticType): String = when (type) {
        TacticType.FORK, TacticType.PAWN_FORK, TacticType.DOUBLE_ATTACK ->
            "Any time two of your opponent's big pieces sit a knight's jump apart, stop and hunt for the fork square."
        TacticType.HANGING_PIECE ->
            "Before every single move, sweep the board for pieces with no defender. Theirs first, then yours."
        TacticType.PIN_ABSOLUTE, TacticType.PIN_RELATIVE ->
            "A pinned piece stops defending. Count it as if it were not on the board, then look again at what it was holding."
        TacticType.SKEWER ->
            "Look for two valuable pieces on one line. The front one moves, the back one drops."
        TacticType.BACK_RANK_MATE ->
            "Check your own back rank every few moves. One pawn move to give the king air is cheap insurance."
        TacticType.DISCOVERED_ATTACK, TacticType.DISCOVERED_CHECK, TacticType.DOUBLE_CHECK ->
            "When one of your pieces stands in front of a rook, a bishop or the queen, ask what happens if it steps aside."
        TacticType.DEFLECTION, TacticType.REMOVING_THE_DEFENDER, TacticType.OVERLOADED_PIECE ->
            "Find the piece doing two jobs, then take it, chase it, or attack whatever it is defending."
        TacticType.TRAPPED_PIECE ->
            "Count escape squares for any piece that wanders into their half. If the answer is zero, it is already lost."
        TacticType.MATE_NET, TacticType.SMOTHERED_MATE ->
            "Once the enemy king is down to two squares or fewer, stop counting material and start counting mate."
        TacticType.PROMOTION_TACTIC, TacticType.PASSED_PAWN_BREAKTHROUGH, TacticType.UNDERPROMOTION ->
            "Passed pawns are pieces. Give one a move whenever you are not sure what else to do."
        else ->
            "Drill twenty of these on a puzzle trainer. Pattern recognition is the only real fix."
    }

    // -----------------------------------------------------------------------
    // Openings
    // -----------------------------------------------------------------------

    private fun openingPlan(family: OpeningFamily): String = when (family) {
        OpeningFamily.PHILIDOR ->
            "Black props the centre up with the d-pawn instead of a knight. It's solid, it's a little passive, " +
                "and it hands White easy development and more space."
        OpeningFamily.PETROV ->
            "Black copies White straight back and heads for the symmetrical structure. White has to work for anything at all."
        OpeningFamily.RUY_LOPEZ ->
            "White leans on the knight holding Black's centre together. Black has to decide early how much space to give up."
        OpeningFamily.ITALIAN ->
            "Both bishops come out fast and point straight at the weakest square in the other camp. It's a race to castle and strike first."
        OpeningFamily.SCOTCH ->
            "White rips the centre open immediately and plays for quick piece activity rather than a slow build-up."
        OpeningFamily.FOUR_KNIGHTS ->
            "Everything comes out symmetrically. Whoever breaks the symmetry first decides what kind of game this becomes."
        OpeningFamily.VIENNA ->
            "White develops the queen's knight first and keeps the option of a big pawn storm through the middle."
        OpeningFamily.KINGS_GAMBIT ->
            "White throws a pawn at the centre to rip the kingside open. Black can take it, but has to hand it back at the right moment."
        OpeningFamily.SICILIAN ->
            "Black trades a wing pawn for a centre pawn and plays for a queenside counter-attack. White castles fast and goes at the king."
        OpeningFamily.FRENCH ->
            "Black builds a pawn chain and accepts a bad bishop for a rock-solid structure, then chips away at the base of White's chain."
        OpeningFamily.CARO_KANN ->
            "Black supports the centre with a pawn and keeps the light-squared bishop free. Slow, sturdy, hard to crack."
        OpeningFamily.SCANDINAVIAN ->
            "Black brings the queen out early and accepts losing a move in exchange for a clean, simple structure."
        OpeningFamily.PIRC_MODERN ->
            "Black hands White the centre on purpose, then attacks it from a distance with the fianchettoed bishop."
        OpeningFamily.ALEKHINE ->
            "Black invites White's pawns forward and plans to prove they've overextended."
        OpeningFamily.QUEENS_GAMBIT ->
            "White offers a wing pawn to pull Black's centre pawn off the middle. Black's whole problem is what to do with the light-squared bishop."
        OpeningFamily.SLAV ->
            "Black holds the centre with pawns and keeps the light-squared bishop's path open. Very solid, very hard to break."
        OpeningFamily.NIMZO ->
            "Black pins the knight and plays against White's doubled pawns and dark squares instead of holding the centre with pawns."
        OpeningFamily.KINGS_INDIAN ->
            "Black hands over the centre, castles, and then throws the whole kingside at White's king. White counters on the other wing."
        OpeningFamily.GRUNFELD ->
            "Black lets White build a big pawn centre and then attacks it from the side. It's the sharpest answer to the queen's pawn."
        OpeningFamily.BENONI ->
            "Black takes an unbalanced structure and a queenside pawn majority in exchange for less space. Nobody here is playing for a draw."
        OpeningFamily.DUTCH ->
            "Black grabs kingside space with the f-pawn and plays for an attack, at the cost of a slightly airy king."
        OpeningFamily.LONDON ->
            "White sets up the same solid structure no matter what Black does, then plays for a slow squeeze."
        OpeningFamily.ENGLISH ->
            "White starts on the flank and keeps the centre flexible. These structures transpose all over the place."
        OpeningFamily.RETI ->
            "White holds the centre back and controls it with pieces instead of pawns."
        OpeningFamily.BIRD ->
            "White grabs kingside space with the f-pawn straight away and plays for a direct attack."
    }

    // -----------------------------------------------------------------------
    // The chess vocabulary
    // -----------------------------------------------------------------------

    /**
     * Squares are always rendered letter-then-spelled-digit ("f three"), because every TTS engine
     * reads a bare "f3" differently and some read it as "f thirty-three".
     */
    object Vocabulary : SpokenVocabulary {

        private val RANK_WORDS = arrayOf("one", "two", "three", "four", "five", "six", "seven", "eight")

        override fun square(square: Square): String = "${'a' + square.file} ${RANK_WORDS[square.rank]}"

        override fun piece(type: PieceType): String = when (type) {
            PieceType.PAWN -> "pawn"
            PieceType.KNIGHT -> "knight"
            PieceType.BISHOP -> "bishop"
            PieceType.ROOK -> "rook"
            PieceType.QUEEN -> "queen"
            PieceType.KING -> "king"
        }

        override fun side(color: Color): String = if (color == Color.WHITE) "White" else "Black"

        /**
         * "knight to f three", "bishop takes the knight on f three", "castles queenside",
         * "pawn to e eight, promoting to a queen" — a noun phrase that can follow "plays".
         */
        override fun movePhrase(move: SpokenMove, includeOutcome: Boolean): String {
            val base = when (move.castle) {
                CastleSide.KINGSIDE -> "castles kingside"
                CastleSide.QUEENSIDE -> "castles queenside"
                null -> normalMovePhrase(move)
            }
            return if (includeOutcome) base + outcomeSuffix(move.outcome) else base
        }

        private fun normalMovePhrase(m: SpokenMove): String {
            val mover = piece(m.piece)
            val to = square(m.to)
            val subject = if (m.ambiguous) "the $mover on ${square(m.from)}" else mover
            val core = when {
                m.enPassant -> "$subject takes on $to, en passant"
                m.isCapture -> if (m.captured != null) "$subject takes the ${piece(m.captured)} on $to" else "$subject takes on $to"
                m.ambiguous -> "the $mover from ${square(m.from)} to $to"
                else -> "$subject to $to"
            }
            return m.promotion?.let { "$core, promoting to a ${piece(it)}" } ?: core
        }

        override fun linePhrase(moves: List<SpokenMove>): String {
            val parts = moves.map { movePhrase(it) }
            return when (parts.size) {
                0 -> ""
                1 -> parts[0]
                else -> parts.first() + ", then " + parts.drop(1).joinToString(", then ")
            }
        }

        override fun spokenNotation(notation: ParsedNotation): String = when (notation) {
            is ParsedNotation.Castle -> if (notation.side == CastleSide.KINGSIDE) "castles kingside" else "castles queenside"
            is ParsedNotation.PieceMove -> {
                val name = piece(notation.piece)
                val origin = when {
                    notation.fromFile != null && notation.fromRank != null ->
                        "the $name on ${square(Square.of(notation.fromFile - 'a', notation.fromRank - 1))}"
                    notation.fromFile != null -> "the ${notation.fromFile} file $name"
                    notation.fromRank != null -> "the $name on the ${ordinal(notation.fromRank)} rank"
                    else -> name
                }
                val verb = if (notation.capture) "takes on" else "to"
                "$origin $verb ${square(notation.to)}${promotion(notation.promotion)}${outcomeSuffix(notation.outcome)}"
            }
            is ParsedNotation.PawnCapture ->
                "the ${notation.fromFile} pawn takes on ${square(notation.to)}${promotion(notation.promotion)}${outcomeSuffix(notation.outcome)}"
            is ParsedNotation.PawnPush ->
                "pawn to ${square(notation.to)}${promotion(notation.promotion)}${outcomeSuffix(notation.outcome)}"
            is ParsedNotation.BareSquare -> square(notation.square)
        }

        private fun promotion(type: PieceType?): String = type?.let { ", promoting to a ${piece(it)}" } ?: ""

        private fun ordinal(rank: Int): String = when (rank) {
            1 -> "first"
            2 -> "second"
            3 -> "third"
            4 -> "fourth"
            5 -> "fifth"
            6 -> "sixth"
            7 -> "seventh"
            else -> "eighth"
        }
    }
}
