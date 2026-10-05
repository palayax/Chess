# Narration string catalogue (en)

Generated from `NarrationCatalogue.samples()` through `EnglishNarration`.
One row per sentence id; ids with several variants list each variant. The parameters
column shows the *typed facts* the sentence carries — a translation may use them in any
order, or ignore ones its grammar does not need. Where a `Subject` is second person the
`gender` field is what a gendered language inflects on.

| Sentence id | Parameters (sample) | en (COACH) | en (ANALYST, when different) |
|---|---|---|---|
| `VideoTitle` | white=MorphyFan, black=DukeAndCount | MorphyFan vs DukeAndCount |  |
| `VideoSubtitle` | openingName=Philidor Defense, eco=C41, result=1-0, depth=18 | Philidor Defense (C41) · 1-0 · analysed at depth 18 |  |
| `ChapterIntro` | *(empty)* | Intro |  |
| `ChapterOpening` | *(empty)* | The Opening |  |
| `ChapterMiddlegame` | *(empty)* | Middlegame |  |
| `ChapterMove` | moveNumber=12, san=Nf3 | Move 12: Nf3 |  |
| `ChapterTurningPoint` | *(empty)* | The Turning Point |  |
| `ChapterDamageReport` | *(empty)* | The Damage Report |  |
| `ChapterWorkOn` | *(empty)* | What To Work On |  |
| `CardOpeningLine` | name=Philidor Defense, eco=C41 | Philidor Defense (C41) |  |
| `CardResultLine` | result=1-0, fullMoves=17 | 1-0 · 17 moves |  |
| `CardResultLine` | result=1/2-1/2, fullMoves=1 | 1/2-1/2 · 1 move |  |
| `CardAccuracyLine` | whiteAccuracy=85.0, blackAccuracy=80.1 | White 85.0% · Black 80.1% |  |
| `CaptionOpening` | name=Philidor Defense, eco=C41 | Philidor Defense (C41) |  |
| `CaptionMoves` | fromMove=5, toMove=9 | Moves 5–9 |  |
| `CaptionMove` | moveNumber=12, color=BLACK, san=Nf6, glyph=? | 12... Nf6 ? |  |
| `CaptionMissed` | bestSan=Nf3, playedSan=e4 | Missed: Nf3 (played e4) |  |
| `CaptionMissedLine` | sans=[Nf3, e5, Nxe5] | Missed line — Nf3 e5 Nxe5 |  |
| `CaptionBackToGame` | moveNumber=12, color=WHITE, san=Nf3 | Back to the game — 12. Nf3 |  |
| `CaptionPuzzle` | side=WHITE | White to play — can you find it? |  |
| `CaptionTurningPoint` | moveNumber=23, san=Qh5, lossPercent=31.5 | Turning point — move 23 Qh5 (31.5% swing) |  |
| `CaptionOutro` | whiteAccuracy=85.0, whiteRating=1650, blackAccuracy=80.1, blackRating=1500 | White 85.0% (1650) · Black 80.1% (1500) |  |
| `CaptionTakeaway` | index=1, total=3 | Takeaway 1 of 3 |  |
| `CardFinalNumbersHeading` | *(empty)* | Final numbers |  |
| `CardFinalPlayerLine` | name=MorphyFan, accuracy=85.0, rating=1650 | MorphyFan — 85.0%, est. 1650 |  |
| `CardFinalCountsLine` | whiteBlunders=1, blackBlunders=2, whiteMistakes=0, blackMistakes=1 | Blunders 1–2 · Mistakes 0–1 |  |
| `CardWorkOnHeading` | *(empty)* | What to work on |  |
| `WalkthroughIntro` | firstSan=h5, tactic=HANGING_PIECE, point=The pawn on g4 cannot be held - taking it wins material. | Watch what happens: h5 starts the line. The pawn on g4 cannot be held - taking it wins material. |  |
| `WalkthroughIntro` | firstSan=null, tactic=FORK, point=null | Watch what happens. The tactic: fork. |  |
| `GameSummary` | kind=DECIDED_BY_ERROR, subject=Subject(color=WHITE, person=SECOND, gender=FEMININE), opponent=Subject(color=BLACK, person=THIRD, gender=UNSPECIFIED), viewerKnown=true, moveNumber=11, error=BLUNDER, fullMoves=null, byMate=false | You were fine until move 11, then a blunder decided it. |  |
| `GameSummary` | kind=DECIDED_BY_ERROR, subject=Subject(color=BLACK, person=THIRD, gender=UNSPECIFIED), opponent=null, viewerKnown=false, moveNumber=11, error=BLUNDER, fullMoves=null, byMate=false | Black was fine until move 11, then a blunder decided it. |  |
| `GameSummary` | kind=SEALED_BY_ERROR, subject=Subject(color=BLACK, person=THIRD, gender=UNSPECIFIED), opponent=Subject(color=WHITE, person=SECOND, gender=FEMININE), viewerKnown=true, moveNumber=21, error=MISTAKE, fullMoves=null, byMate=false | Your opponent was already under pressure, and a mistake on move 21 sealed it. |  |
| `GameSummary` | kind=COMEBACK, subject=Subject(color=WHITE, person=SECOND, gender=FEMININE), opponent=Subject(color=BLACK, person=THIRD, gender=UNSPECIFIED), viewerKnown=true, moveNumber=21, error=BLUNDER, fullMoves=null, byMate=false | You were behind when your opponent's blunder on move 21 turned the game around. |  |
| `GameSummary` | kind=COMEBACK, subject=Subject(color=BLACK, person=THIRD, gender=UNSPECIFIED), opponent=Subject(color=WHITE, person=THIRD, gender=UNSPECIFIED), viewerKnown=false, moveNumber=21, error=MISSED_WIN, fullMoves=null, byMate=false | Black was behind when White's missed win on move 21 turned the game around. |  |
| `GameSummary` | kind=WON_DESPITE_ERROR, subject=Subject(color=WHITE, person=SECOND, gender=FEMININE), opponent=Subject(color=BLACK, person=THIRD, gender=UNSPECIFIED), viewerKnown=true, moveNumber=23, error=BIG_SWING, fullMoves=null, byMate=false | You won, even after a big swing on move 23. |  |
| `GameSummary` | kind=CLEAN_WIN, subject=Subject(color=WHITE, person=SECOND, gender=FEMININE), opponent=Subject(color=BLACK, person=THIRD, gender=UNSPECIFIED), viewerKnown=true, moveNumber=null, error=null, fullMoves=30, byMate=true | You won by checkmate on move 30, and neither side made a big mistake. |  |
| `GameSummary` | kind=CLEAN_WIN, subject=Subject(color=WHITE, person=THIRD, gender=UNSPECIFIED), opponent=Subject(color=BLACK, person=THIRD, gender=UNSPECIFIED), viewerKnown=false, moveNumber=null, error=null, fullMoves=null, byMate=false | White won, and neither side made a big mistake. |  |
| `GameSummary` | kind=SHORT_GAME, subject=Subject(color=BLACK, person=THIRD, gender=UNSPECIFIED), opponent=Subject(color=WHITE, person=SECOND, gender=FEMININE), viewerKnown=true, moveNumber=null, error=null, fullMoves=8, byMate=true | A short game: your opponent mated you in 8 moves. |  |
| `GameSummary` | kind=SHORT_GAME, subject=Subject(color=WHITE, person=SECOND, gender=FEMININE), opponent=Subject(color=BLACK, person=THIRD, gender=UNSPECIFIED), viewerKnown=true, moveNumber=null, error=null, fullMoves=9, byMate=false | A short game: you won in 9 moves. |  |
| `GameSummary` | kind=SHORT_GAME, subject=null, opponent=null, viewerKnown=false, moveNumber=null, error=null, fullMoves=7, byMate=false | A short game: it ended in a draw after 7 moves. |  |
| `GameSummary` | kind=DRAW_WITH_SWING, subject=Subject(color=BLACK, person=THIRD, gender=UNSPECIFIED), opponent=Subject(color=WHITE, person=SECOND, gender=FEMININE), viewerKnown=true, moveNumber=14, error=MISTAKE, fullMoves=null, byMate=false | It ended in a draw, but your opponent's mistake on move 14 was the big swing. |  |
| `GameSummary` | kind=CLOSE_CLEAN, subject=null, opponent=null, viewerKnown=false, moveNumber=null, error=null, fullMoves=null, byMate=false | A close game: neither side made a big mistake. |  |
| `GameSummary` | kind=UNFINISHED, subject=null, opponent=null, viewerKnown=false, moveNumber=null, error=null, fullMoves=23, byMate=false | The game stops after 23 moves without a result. |  |
| `IntroNoNames` | *(empty)* | No names on this one, so it's White against Black. |  |
| `IntroPlayers` | white=MorphyFan, whiteRating=1650, black=DukeAndCount, blackRating=null | MorphyFan has the white pieces, rated 1650. DukeAndCount is on the other side. |  |
| `ResultDecisive` | winner=WHITE, byMate=true, fullMoves=17 | White finished it with mate in 17 moves. |  |
| `ResultDraw` | fullMoves=40 | It ended in a draw after 40 moves. |  |
| `ResultUnfinished` | fullMoves=23 | The game runs 23 moves and stops there. |  |
| `IntroYouWere` | subject=Subject(color=WHITE, person=SECOND, gender=FEMININE) | You were White here, so that's the side we're watching. |  |
| `HookMissedMate` | *(empty)* | And somewhere in here there's a forced mate that never got played. We'll get to it. |  |
| `HookBigTurn` | moveNumber=23 | But this whole game turns on one move, around move 23, and I'd bet most people watching walk straight past it.<br>The result is not the story. One move, around move 23, decides everything, and it is not the obvious one.<br>There's a single move in here, at move 23, that flips the entire game. Let's go find it. |  |
| `HookClose` | moveNumber=23 | It's closer than the result makes it look. One moment, around move 23, decides it. |  |
| `HookClean` | *(empty)* | This one is clean from both sides, but there's still a crack in it, and we're going to find it. |  |
| `HookNoMistakes` | *(empty)* | Not a mistake in sight from either player. So let's talk about what they both got right. |  |
| `OpeningLead` | name=Philidor Defense | This is the Philidor Defense.<br>Straight into the Philidor Defense.<br>We're in the Philidor Defense. |  |
| `OpeningNoBookName` | *(empty)* | No book name for this one. |  |
| `OpeningPlan` | family=PHILIDOR | Black props the centre up with the d-pawn instead of a knight. It's solid, it's a little passive, and it hands White easy development and more space. |  |
| `OpeningGeneric` | whiteFirst=SpokenMove(color=WHITE, piece=PAWN, from=e2, to=e4, castle=null, isCapture=false, captured=null, enPassant=false, promotion=null, ambiguous=false, outcome=NONE), blackFirst=SpokenMove(color=BLACK, piece=PAWN, from=e7, to=e5, castle=null, isCapture=false, captured=null, enPassant=false, promotion=null, ambiguous=false, outcome=NONE) | White opened with pawn to e four, Black answered with pawn to e five, and from there it's a fight for the centre and the faster development. |  |
| `OpeningGenericNoMoves` | *(empty)* | Both sides go for the centre and the faster development. |  |
| `OpeningBothDeveloped` | *(empty)* | Both sides just developed. |  |
| `TheoryRunsOut` | moveNumber=8 | Theory runs out around move 8. |  |
| `SkipAhead` | moveNumber=14 | The next few moves are just development and shuffling. Nothing breaks, so let's jump to move 14.<br>I'm skipping ahead here. A handful of quiet moves go by with nobody blinking, and we pick it up at move 14.<br>Nothing happens for a while. Both sides finish developing, and then we're at move 14, which is where it gets interesting. |  |
| `JumpTo` | moveNumber=14 | Forward to move 14. <br>Next stop, move 14. <br>Jumping to move 14.  |  |
| `ALittleLater` | *(empty)* | A couple of moves later, <br>Two moves on, <br>Shortly after that,  |  |
| `Played` | subject=Subject(color=WHITE, person=SECOND, gender=FEMININE), verb=PLAY, move=SpokenMove(color=WHITE, piece=KNIGHT, from=g1, to=f3, castle=null, isCapture=false, captured=null, enPassant=false, promotion=null, ambiguous=false, outcome=NONE), recapture=false | you play knight to f three |  |
| `Played` | subject=Subject(color=BLACK, person=THIRD, gender=UNSPECIFIED), verb=ANSWER_WITH, move=SpokenMove(color=BLACK, piece=BISHOP, from=g4, to=f3, castle=null, isCapture=true, captured=KNIGHT, enPassant=false, promotion=null, ambiguous=false, outcome=CHECK), recapture=false | Black takes the knight on f three with the bishop, with check |  |
| `PlayedUnknown` | subject=Subject(color=BLACK, person=THIRD, gender=UNSPECIFIED), verb=PLAY | Black plays on |  |
| `StillTheory` | *(empty)* | Still theory.<br>All book so far.<br>Nothing new yet. |  |
| `OnlyLegalMove` | *(empty)* | That was the only legal move, so there's no decision to make. |  |
| `KingTuckedAway` | *(empty)* | King's tucked away.<br>Safety first.<br>Good, the king's off the middle. |  |
| `BrilliantFlavour` | *(empty)* | And that is a brilliant move. It gives material away and the engine loves it anyway. |  |
| `GreatFlavour` | *(empty)* | That was the only move that held, and it got found. |  |
| `BestFlavour` | *(empty)* | Top of the engine's list.<br>Best move on the board.<br>The engine agrees. |  |
| `CheckForcesReply` | *(empty)* | Check, so the reply is forced. |  |
| `Filler` | *(empty)* | Sensible.<br>No complaints.<br>Fine.<br>*(empty)* |  |
| `MateClosing` | *(empty)* | And that's the game.<br>That's it. Game over.<br>And there it is. |  |
| `MateFinish` | subject=Subject(color=WHITE, person=SECOND, gender=FEMININE) | You finish it off. |  |
| `MateOnReceivingEnd` | *(empty)* | And that's you on the receiving end of it. |  |
| `JustTheTrade` | *(empty)* | That's just the trade going through.<br>Taking back, nothing more.<br>Even trade, and we move on. |  |
| `NothingDefendingIt` | *(empty)* | Nothing was defending it.<br>It was sitting there with no defender.<br>Completely undefended. |  |
| `MaterialInTheBank` | payoff=ROOK | That's a rook in the bank.<br>A rook, just like that.<br>So that's a rook. |  |
| `PressureNoMaterialYet` | *(empty)* | It doesn't win material yet, but the pressure is real. |  |
| `GoodSolid` | *(empty)* | Good, solid stuff. |  |
| `ErrorOpener` | classification=BLUNDER | Oh no.<br>And here it all falls apart.<br>This is the one.<br>Ouch. | This is the decisive error.<br>Here the evaluation collapses.<br>This is a blunder. |
| `ErrorOpener` | classification=MISS | The win was sitting right there.<br>This is the miss.<br>So close. | A winning continuation was available.<br>The decisive line was missed here. |
| `ErrorOpener` | classification=MISTAKE | Careful.<br>This is where it slips.<br>Hang on. | This is a mistake.<br>The position takes a turn here. |
| `ConsequenceUnchanged` | subject=Subject(color=WHITE, person=SECOND, gender=FEMININE), standing=WINNING, severity=REAL_GROUND | You are still winning, but that gave away real ground. |  |
| `ConsequenceChanged` | subject=Subject(color=BLACK, person=THIRD, gender=UNSPECIFIED), before=WINNING, after=ABOUT_LEVEL | Black was winning before that. Now Black is about level.<br>That's winning turning into about level in a single move.<br>Black has gone from winning to about level. |  |
| `BetterWas` | move=SpokenMove(color=WHITE, piece=KNIGHT, from=g1, to=f3, castle=null, isCapture=false, captured=null, enPassant=false, promotion=null, ambiguous=false, outcome=NONE) | Knight to f three was the move.<br>Instead, knight to f three, and everything holds.<br>The move was knight to f three. |  |
| `MateWasAvailable` | mateIn=3, move=SpokenMove(color=WHITE, piece=QUEEN, from=d1, to=h5, castle=null, isCapture=false, captured=null, enPassant=false, promotion=null, ambiguous=false, outcome=NONE) | There was mate in three on the board, starting with queen to h five. |  |
| `ThreatLetIn` | *(empty)* | and that's the move that lets it in.<br>and now look what's available.<br>and the reply is unpleasant. |  |
| `InaccuracyNote` | *(empty)* | It's not losing, it's just loose.<br>That's an inaccuracy — playable, but it gives something back.<br>Slightly off. |  |
| `EvalShift` | subject=Subject(color=WHITE, person=SECOND, gender=FEMININE), favours=true | The evaluation moves in your favour. |  |
| `EvalShift` | subject=Subject(color=BLACK, person=THIRD, gender=UNSPECIFIED), favours=false | The evaluation moves against Black. |  |
| `PivotIn` | *(empty)* | Now hold on. Let's rewind, because there was something much better here. <br>Stop. Freeze it right there — this position had far more in it. <br>Hold on, back up a move. There was a much better idea sitting right here.  | Pause here. A stronger continuation was available. <br>Hold the position a moment: there was a better move than the one played.  |
| `PivotReveal` | move=SpokenMove(color=WHITE, piece=KNIGHT, from=g1, to=f3, castle=null, isCapture=false, captured=null, enPassant=false, promotion=null, ambiguous=false, outcome=NONE) | The move is knight to f three.<br>It starts with knight to f three.<br>The move was knight to f three. | The move is knight to f three.<br>The correct continuation begins with knight to f three. |
| `PivotWalk` | *(empty)* | Let's play it out.<br>Let's walk it through, move by move.<br>Watch what happens if that goes in. | The line runs as follows.<br>Here it is, move by move. |
| `ExcursionMove` | subject=Subject(color=WHITE, person=SECOND, gender=FEMININE), verb=PLAY, move=SpokenMove(color=WHITE, piece=KNIGHT, from=g1, to=f3, castle=null, isCapture=false, captured=null, enPassant=false, promotion=null, ambiguous=false, outcome=NONE), step=FIRST, recapture=false | So: you play knight to f three.<br>It starts here. You play knight to f three.<br>Here we go. You play knight to f three. |  |
| `ExcursionMove` | subject=Subject(color=WHITE, person=SECOND, gender=FEMININE), verb=PLAY, move=SpokenMove(color=WHITE, piece=QUEEN, from=d1, to=h5, castle=null, isCapture=false, captured=null, enPassant=false, promotion=null, ambiguous=false, outcome=NONE), step=NEXT, recapture=false | Then you play queen to h five.<br>Next, you play queen to h five.<br>And then you play queen to h five. |  |
| `ExcursionMove` | subject=Subject(color=BLACK, person=THIRD, gender=UNSPECIFIED), verb=ANSWER_WITH, move=SpokenMove(color=BLACK, piece=KING, from=e1, to=d1, castle=null, isCapture=true, captured=ROOK, enPassant=false, promotion=null, ambiguous=false, outcome=NONE), step=REPLY, recapture=true | Black takes back on d one with the king. |  |
| `LineEndsInMate` | *(empty)* | The king has nowhere left to go, and the line stops there. |  |
| `MaterialTaken` | gain=ROOK | That is a rook off the board.<br>That is a rook, collected.<br>And there goes a rook. |  |
| `CheckMustBeAnswered` | *(empty)* | The king has to answer that first, which is the whole point of the move order.<br>Check, so the reply is not a choice.<br>The king comes under fire, and nothing else gets done. |  |
| `AlreadyUp` | subject=Subject(color=BLACK, person=THIRD, gender=UNSPECIFIED), gain=PIECE | Black is already a piece up here, and the pressure is not going away. |  |
| `QuietMove` | *(empty)* | No fireworks. It just keeps everything pointed at the same weakness.<br>That is the quiet move, and it is the one that makes the rest work.<br>Nothing is captured, and nothing has to be. The threat does the work. |  |
| `OnlyLegalReply` | *(empty)* | There is nothing else legal. |  |
| `ForcedByCheck` | *(empty)* | Forced — the king was in check, so nothing else was even legal.<br>Forced. The check has to be answered first. |  |
| `RecaptureNatural` | *(empty)* | Taking back is the natural answer, and it changes nothing.<br>The recapture is more or less forced, and it does not help. |  |
| `BestDefence` | *(empty)* | That is the best defence on offer.<br>That is the toughest try in the position.<br>That is the engine's own choice, so nothing better exists. |  |
| `PayoffLead` | *(empty)* | And there it is. <br>So add it up. <br>Now look at the end of that line.  | The result of the line: <br>At the end of the line,  |
| `PayoffMate` | mateIn=2 | That is checkmate in two. |  |
| `PayoffMaterial` | subject=Subject(color=WHITE, person=SECOND, gender=FEMININE), gain=ROOK | You come out of it a rook up. |  |
| `PayoffOutcome` | subject=Subject(color=BLACK, person=THIRD, gender=UNSPECIFIED), kind=DRAW_BY_REPETITION | Black forces a draw by repetition. |  |
| `PayoffOutcome` | subject=Subject(color=BLACK, person=THIRD, gender=UNSPECIFIED), kind=LINE_ENDS | That is as far as the line goes. |  |
| `MateBehindIt` | mateIn=4 | And there was mate in four behind it. |  |
| `PivotOut` | *(empty)* | Back in the real game, though, that got played instead —<br>But that is the line that never was. In the real game, this went on the board —<br>So, back to reality. What actually happened was this — | Returning to the game as played, the move actually chosen was this —<br>Back to the main line. What was played instead was this — |
| `ChanceGone` | *(empty)* | And the chance is gone. It does not come back.<br>The moment passes, and that is that.<br>And with that, the window shuts. | The opportunity does not recur.<br>That continuation is no longer available. |
| `InsteadPlayed` | subject=Subject(color=WHITE, person=SECOND, gender=FEMININE), move=SpokenMove(color=WHITE, piece=KNIGHT, from=g1, to=f3, castle=null, isCapture=false, captured=null, enPassant=false, promotion=null, ambiguous=false, outcome=NONE), recapture=false | Instead, you play knight to f three, and it's gone.<br>What actually happened: you play knight to f three. The chance never comes back.<br>But no — you play knight to f three, and the moment passes. |  |
| `TacticPoint` | type=HANGING_PIECE, target=f3, victim=KNIGHT, second=null | The knight on f three has nothing defending it. |  |
| `TacticPoint` | type=FORK, target=c7, victim=ROOK, second=e8 | It hits two things at once, on c seven and on e eight. |  |
| `TacticLesson` | type=FORK | Any time two of your opponent's big pieces sit a knight's jump apart, stop and hunt for the fork square. |  |
| `TextbookOffer` | type=FORK | There's a clean textbook fork waiting in the game report if you want to see the pattern on its own. |  |
| `PuzzlePrompt` | side=WHITE, prize=WINS_PIECE | White to play. There is a move here that wins a whole piece. Pause the video. Can you find it?<br>White to play, and there's a move here that wins a whole piece. Stop the video and have a proper look.<br>Right, White to play. Something here wins a whole piece. Pause it. See if you spot it. |  |
| `TurningPointLead` | *(empty)* | And that is the turning point of the whole game. <br>Stop there, because that is the moment the game changed hands. <br>That right there is where this game was decided.  | This is the largest evaluation swing in the game. <br>This move produces the decisive change in evaluation.  |
| `TurningPointSwing` | moveNumber=23 | Nothing else in this game swings the evaluation like move 23. |  |
| `TurningPointFromTo` | subject=Subject(color=WHITE, person=SECOND, gender=FEMININE), before=WINNING, after=ABOUT_LEVEL | You went from winning to about level on one move. |  |
| `OutroLead` | *(empty)* | Right, numbers. <br>Let's put numbers on it. <br>So where did that leave us?  |  |
| `OutroAccuracy` | white=MorphyFan, whiteAccuracy=85.0, black=DukeAndCount, blackAccuracy=80.1 | MorphyFan finished on 85.0 percent accuracy, DukeAndCount on 80.1 percent. |  |
| `OutroRatings` | whiteRating=1650, blackRating=1500 | That's play at roughly 1650 and 1500. |  |
| `NoMistakes` | name=MorphyFan | MorphyFan did not make a single mistake the engine cares about. |  |
| `ErrorCounts` | name=DukeAndCount, blunders=1, mistakes=2, misses=1, inaccuracies=0 | DukeAndCount had one blunder, two mistakes and one missed win. |  |
| `ShortGameCaveat` | *(empty)* | It's a short game, so treat those rating estimates as a rough guide rather than gospel. |  |
| `LessonLead` | count=3 | So what do you take away from this? <br>Here's what to actually work on. <br>Three things to take out of this game.  |  |
| `LessonLead` | count=1 | So what do you take away from this? <br>Here's the one thing to actually work on.  |  |
| `LessonRepeatedMiss` | subject=Subject(color=WHITE, person=SECOND, gender=FEMININE), type=FORK, count=3, squares=[e5, c7] | You walked past three forks in this game. They were on e five and c seven. |  |
| `LessonSingleMiss` | subject=Subject(color=WHITE, person=SECOND, gender=FEMININE), type=FORK, moveNumber=12, square=e5 | The one thing you left on the board was a fork at move 12. The one that hurt was on e five. |  |
| `LessonLateErrors` | subject=Subject(color=WHITE, person=SECOND, gender=FEMININE), count=3, afterMove=10 | Every one of your three errors came after move 10, once the pieces were out and real decisions started. That's a calculation problem, not an opening problem — give yourself an extra thirty seconds the moment the position opens up. |  |
| `LessonSpreadErrors` | subject=Subject(color=WHITE, person=SECOND, gender=FEMININE), firstMove=4, lastMove=30 | Your mistakes ran from move 4 to move 30, so they're spread right across the game rather than bunched at the end. That usually means the checks are missing, not the stamina. |  |
| `LessonOneMistake` | subject=Subject(color=WHITE, person=SECOND, gender=FEMININE), moveNumber=23, lossPercent=31.5 | One mistake. Move 23 cost 31.5 percent of the position on its own, and everything else you played was fine. A game like this is decided by one moment, so the drill is simple: on every move where material can change hands, stop and check the whole board. |  |
| `LessonGifts` | subject=Subject(color=WHITE, person=SECOND, gender=FEMININE), count=3 | You handed over a tactic three times in this game — moves that were fine in themselves but let something in. Before you commit, ask one question: what does this let them do next? |  |
| `LessonMatedButNotTheMistake` | subject=Subject(color=WHITE, person=SECOND, gender=FEMININE), turningMove=23 | You got mated at the end, but the mate wasn't the mistake — move 23 was. By the time the king was getting hit there was nothing left to defend with. Fix move 23 and the mate never happens. |  |
| `LessonOpponentMissedToo` | subject=Subject(color=WHITE, person=SECOND, gender=FEMININE), type=FORK, count=2, converted=1 | Worth knowing: the other side left two forks on the board too, and you converted one chance of your own. Both players are missing the same kind of thing, which means whoever drills it first wins the rematch. |  |
| `LessonPositive` | subject=Subject(color=WHITE, person=SECOND, gender=FEMININE), bestMoves=5, totalMoves=17, accuracy=85.0 | You found the engine's top move five times out of 17, for 85.0 percent. The plan-making is working; the gap is in the tactics, and tactics are the part you can drill. |  |
| `LessonRatingAnchor` | subject=Subject(color=WHITE, person=SECOND, gender=FEMININE), accuracy=85.0, rating=1650 | You came out of this at 85.0 percent, which the model reads as about 1650. The next step up is fewer than one blunder a game, and that's a habit, not talent. |  |
