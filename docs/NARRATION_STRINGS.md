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
| `CaptionBestLine` | firstMoveNumber=18, firstColor=BLACK, sans=[Nf5, Qd2, Nd4] | Best line — 18... Nf5 19. Qd2 Nd4 |  |
| `CaptionBackToGame` | moveNumber=12, color=WHITE, san=Nf3 | Back to the game — 12. Nf3 |  |
| `CaptionPuzzle` | side=WHITE | White to play — can you find it? |  |
| `CaptionTurningPoint` | moveNumber=23, san=Qh5, lossPercent=31.5 | Turning point — move 23 Qh5 (31.5% swing) |  |
| `CaptionOutro` | whiteAccuracy=85.0, whiteRating=1650, blackAccuracy=80.1, blackRating=1500 | White 85.0% (1650) · Black 80.1% (1500) |  |
| `CaptionTakeaway` | index=1, total=3 | Takeaway 1 of 3 |  |
| `CardFinalNumbersHeading` | *(empty)* | Final numbers |  |
| `CardFinalPlayerLine` | name=MorphyFan, accuracy=85.0, rating=1650 | MorphyFan — 85.0%, est. 1650 |  |
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
| `StakesComeback` | winner=BLACK, moves=16 | Black won, but for 16 moves this was White's game.<br>Black won it, though for 16 moves White was clearly ahead.<br>For 16 moves White was clearly better, and Black still won. |  |
| `StakesTookOver` | winner=WHITE, moveNumber=11, fromTheStart=false | It was level until move 11, and then White took over for good.<br>Nothing separated them until move 11, when White took over for good. |  |
| `StakesTookOver` | winner=WHITE, moveNumber=3, fromTheStart=true | White was on top from the opening and never let go.<br>White got ahead early and stayed there. |  |
| `StakesTightDraw` | *(empty)* | Neither side ever got more than a slight edge.<br>It was close all the way: neither side was ever more than slightly better. |  |
| `StakesWastedLead` | leader=WHITE, moves=12 | White was clearly better for 12 moves, but it ended in a draw.<br>White held the upper hand for 12 moves and still could not win. |  |
| `ResultEndsInMate` | fullMoves=33 | It ended in mate on move 33.<br>The finish was a checkmate on move 33. |  |
| `ResultLasted` | fullMoves=40 | It lasted 40 moves.<br>All over after 40 moves. |  |
| `HookMissedMate` | *(empty)* | And somewhere in here there's a forced mate that never got played. We'll get to it. |  |
| `HookBigTurn` | moveNumber=23 | One move, around move 23, swings the whole game, and it's easy to walk past.<br>Everything here turns on a single move, around move 23.<br>There's one move in here, around move 23, that changes everything. Let's find it. |  |
| `HookClose` | moveNumber=23 | One moment, around move 23, matters more than any other.<br>The key moment comes around move 23. |  |
| `HookClean` | *(empty)* | This one is clean from both sides. The biggest slip is small, but it is worth a look. |  |
| `HookNoMistakes` | *(empty)* | Not a mistake in sight from either player. So let's talk about what they both got right. |  |
| `OpeningLead` | name=Philidor Defense | This is the Philidor Defense.<br>Straight into the Philidor Defense.<br>We're in the Philidor Defense. |  |
| `OpeningNoBookName` | *(empty)* | No book name for this one. |  |
| `OpeningPlan` | family=PHILIDOR | Black props the centre up with the d-pawn instead of a knight. It's solid, it's a little passive, and it hands White easy development and more space. |  |
| `OpeningGeneric` | whiteFirst=SpokenMove(color=WHITE, piece=PAWN, from=e2, to=e4, castle=null, isCapture=false, captured=null, enPassant=false, promotion=null, ambiguous=false, outcome=NONE), blackFirst=SpokenMove(color=BLACK, piece=PAWN, from=e7, to=e5, castle=null, isCapture=false, captured=null, enPassant=false, promotion=null, ambiguous=false, outcome=NONE) | White opened with pawn to e four, Black answered with pawn to e five, and from there it's a fight for the centre and the faster development. |  |
| `OpeningGenericNoMoves` | *(empty)* | Both sides go for the centre and the faster development. |  |
| `OpeningBothDeveloped` | *(empty)* | Both sides just developed. |  |
| `TheoryEnds` | moveNumber=8, standing=BALANCED | Theory runs out around move 8, and the position is about level.<br>By move 8 the book is spent, and the position is about level. |  |
| `TheoryEnds` | moveNumber=8, standing=WHITE_EDGE | Theory runs out around move 8, and White is slightly better.<br>By move 8 the book is spent, and White is slightly better. |  |
| `OpeningVerdict` | standing=BLACK_CLEAR | Out of the opening, Black is clearly better.<br>Coming out of the opening, Black is clearly better. |  |
| `PhaseTurn` | from=BALANCED, to=WHITE_EDGE, cause=null | White edged ahead.<br>White got a small edge. |  |
| `PhaseTurn` | from=BALANCED, to=WHITE_CLEAR, cause=Error(color=BLACK, classification=BLUNDER, moveNumber=11) | White built a clear edge, after Black's blunder on move 11.<br>White took control, after Black's blunder on move 11. |  |
| `PhaseTurn` | from=WHITE_CLEAR, to=WHITE_DECISIVE, cause=WonMaterial(color=WHITE, gain=PIECE) | White was winning now, after winning a piece.<br>White's edge became a winning one, after winning a piece. |  |
| `PhaseTurn` | from=WHITE_DECISIVE, to=WHITE_EDGE, cause=null | White's grip loosened: only slightly better now.<br>White gave some ground, and was only slightly better now. |  |
| `PhaseTurn` | from=WHITE_CLEAR, to=BALANCED, cause=Error(color=WHITE, classification=MISTAKE, moveNumber=17) | White gave up the advantage with a mistake on move 17, and it was level again.<br>It was level again, after White's mistake on move 17. |  |
| `PhaseTurn` | from=WHITE_CLEAR, to=BALANCED, cause=MateFound(color=BLACK, mateIn=3) | Black fought back to level, with a forced mate in three.<br>It was level again, with a forced mate in three. |  |
| `PhaseTurn` | from=WHITE_CLEAR, to=BLACK_CLEAR, cause=Error(color=WHITE, classification=MISS, moveNumber=22) | The game turned, after White's missed win on move 22: Black was clearly better now.<br>Black took over, after White's missed win on move 22, and was clearly better. |  |
| `SkipAhead` | moveNumber=14, kind=QUIET | A few quiet moves follow, no captures and no checks, and we pick it up at move 14.<br>Nothing is taken and nobody gives check for a while, so on to move 14.<br>Quiet moves, no captures, no checks. We rejoin at move 14. |  |
| `SkipAhead` | moveNumber=14, kind=TRADES | There are trades along the way, and we pick it up at move 14.<br>Some pieces come off, and then we're at move 14.<br>A few trades later, it's move 14. |  |
| `SkipAhead` | moveNumber=14, kind=PLAIN | Let's jump to move 14.<br>Skipping ahead to move 14.<br>On to move 14. |  |
| `JumpTo` | moveNumber=14 | Forward to move 14. <br>Next stop, move 14. <br>Jumping to move 14.  |  |
| `ALittleLater` | *(empty)* | A little later, <br>After that, <br>Shortly after that,  |  |
| `Played` | subject=Subject(color=WHITE, person=SECOND, gender=FEMININE), verb=PLAY, move=SpokenMove(color=WHITE, piece=KNIGHT, from=g1, to=f3, castle=null, isCapture=false, captured=null, enPassant=false, promotion=null, ambiguous=false, outcome=NONE), recapture=false | you play knight to f three |  |
| `Played` | subject=Subject(color=BLACK, person=THIRD, gender=UNSPECIFIED), verb=ANSWER_WITH, move=SpokenMove(color=BLACK, piece=BISHOP, from=g4, to=f3, castle=null, isCapture=true, captured=KNIGHT, enPassant=false, promotion=null, ambiguous=false, outcome=CHECK), recapture=false | Black takes the knight on f three with the bishop, with check |  |
| `PlayedUnknown` | subject=Subject(color=BLACK, person=THIRD, gender=UNSPECIFIED), verb=PLAY | Black plays on |  |
| `StillTheory` | *(empty)* | Still theory.<br>All book so far.<br>Nothing new yet. |  |
| `OnlyLegalMove` | *(empty)* | That was the only legal move, so there's no decision to make. |  |
| `KingTuckedAway` | *(empty)* | King's tucked away.<br>Safety first.<br>Good, the king's off the middle. |  |
| `BrilliantFlavour` | *(empty)* | A brilliant move! It gives material away, and the engine loves it anyway.<br>That's brilliant! The material goes, and the engine still approves. |  |
| `GreatFlavour` | *(empty)* | That was the only move that held, and it got found.<br>The only move that held, and it was found.<br>Only move. And it was found. |  |
| `BestFlavour` | *(empty)* | Top of the engine's list.<br>Best move on the board.<br>The engine agrees. |  |
| `CheckForcesReply` | *(empty)* | Check, so the reply is forced. |  |
| `Filler` | *(empty)* | Sensible.<br>No complaints.<br>Fine.<br>*(empty)* |  |
| `MateAnnounce` | mateIn=3 | This is a forced mate in three.<br>From here it is mate in three, by force.<br>Mate in three, and nothing can stop it. |  |
| `MateStep` | move=SpokenMove(color=BLACK, piece=BISHOP, from=g4, to=f3, castle=null, isCapture=true, captured=KNIGHT, enPassant=false, promotion=null, ambiguous=false, outcome=CHECK) | Bishop takes the knight on f three, with check.<br>Then bishop takes the knight on f three, with check. |  |
| `MateFinish` | subject=Subject(color=WHITE, person=SECOND, gender=FEMININE) | You win it!<br>Game over, and you win! |  |
| `MateFinish` | subject=Subject(color=BLACK, person=THIRD, gender=UNSPECIFIED) | Game over. Black wins!<br>And that's the game: Black wins!<br>Black wins it! |  |
| `MateOnReceivingEnd` | *(empty)* | And that's you on the receiving end of it. |  |
| `JustTheTrade` | *(empty)* | That's just the trade going through.<br>Taking back, nothing more.<br>Even trade, and we move on. |  |
| `NothingDefendingIt` | *(empty)* | Nothing was defending it.<br>It was sitting there with no defender.<br>Completely undefended.<br>It was en prise, with nothing defending it. |  |
| `MaterialInTheBank` | payoff=ROOK | That's a rook in the bank.<br>A rook, just like that.<br>So that's a rook. |  |
| `PressureNoMaterialYet` | *(empty)* | It doesn't win material yet, but the pressure is real.<br>No material changes hands yet, but the pressure is on.<br>Nothing is won yet, but the pressure builds. |  |
| `GoodSolid` | *(empty)* | Good, solid stuff. |  |
| `ErrorOpener` | classification=BLUNDER | Oh no.<br>And here it all falls apart.<br>This is the one.<br>Ouch. | This is the decisive error.<br>Here the evaluation collapses.<br>This is a blunder. |
| `ErrorOpener` | classification=MISS | The win was sitting right there.<br>This is the miss.<br>So close. | A winning continuation was available.<br>The decisive line was missed here. |
| `ErrorOpener` | classification=MISTAKE | Careful.<br>This is where it slips.<br>Hang on. | This is a mistake.<br>The position takes a turn here. |
| `ConsequenceUnchanged` | subject=Subject(color=WHITE, person=SECOND, gender=FEMININE), standing=WINNING, severity=REAL_GROUND | You are still winning, but that gave away real ground. |  |
| `ConsequenceChanged` | subject=Subject(color=BLACK, person=THIRD, gender=UNSPECIFIED), before=WINNING, after=ABOUT_LEVEL | Black was winning. Now Black is about level.<br>That takes Black from winning to about level.<br>Black has slipped from winning to about level. |  |
| `BetterHere` | move=SpokenMove(color=WHITE, piece=KNIGHT, from=g1, to=f3, castle=null, isCapture=false, captured=null, enPassant=false, promotion=null, ambiguous=false, outcome=NONE), result=Plain | Knight to f three was the move.<br>The move was knight to f three.<br>Instead, knight to f three. |  |
| `BetterHere` | move=SpokenMove(color=WHITE, piece=KNIGHT, from=g1, to=f3, castle=null, isCapture=false, captured=null, enPassant=false, promotion=null, ambiguous=false, outcome=NONE), result=Mates(mateIn=3) | Knight to f three was the move: mate in three.<br>The move was knight to f three, forcing mate in three.<br>Instead, knight to f three, and it is mate in three. |  |
| `BetterHere` | move=SpokenMove(color=BLACK, piece=BISHOP, from=g4, to=f3, castle=null, isCapture=true, captured=KNIGHT, enPassant=false, promotion=null, ambiguous=false, outcome=CHECK), result=Wins(gain=ROOK) | Bishop takes the knight on f three, with check was the move, winning a rook.<br>The move was bishop takes the knight on f three, with check, which wins a rook.<br>Instead, bishop takes the knight on f three, with check wins a rook. |  |
| `BetterHere` | move=SpokenMove(color=BLACK, piece=BISHOP, from=g4, to=f3, castle=null, isCapture=true, captured=KNIGHT, enPassant=false, promotion=null, ambiguous=false, outcome=CHECK), result=Wins(gain=null) | Bishop takes the knight on f three, with check was the move, winning material.<br>The move was bishop takes the knight on f three, with check, which wins material.<br>Instead, bishop takes the knight on f three, with check wins material. |  |
| `AllowsMate` | mateIn=4 | That allows a forced mate in four.<br>It walks into a forced mate in four.<br>After that, there is a forced mate in four. |  |
| `PlayedThenReply` | subject=Subject(color=BLACK, person=THIRD, gender=UNSPECIFIED), verb=PLAY, move=SpokenMove(color=BLACK, piece=PAWN, from=e7, to=e5, castle=null, isCapture=false, captured=null, enPassant=false, promotion=null, ambiguous=false, outcome=NONE), recapture=false, opponent=Subject(color=WHITE, person=THIRD, gender=UNSPECIFIED), reply=SpokenMove(color=WHITE, piece=QUEEN, from=d1, to=h5, castle=null, isCapture=false, captured=null, enPassant=false, promotion=null, ambiguous=false, outcome=NONE), gain=null | Black plays pawn to e five, and now White has queen to h five.<br>Black plays pawn to e five, but White has the reply queen to h five.<br>Black plays pawn to e five, and White can answer with queen to h five. |  |
| `PlayedThenReply` | subject=Subject(color=WHITE, person=SECOND, gender=FEMININE), verb=PLAY, move=SpokenMove(color=WHITE, piece=KNIGHT, from=g1, to=f3, castle=null, isCapture=false, captured=null, enPassant=false, promotion=null, ambiguous=false, outcome=NONE), recapture=false, opponent=Subject(color=BLACK, person=THIRD, gender=UNSPECIFIED), reply=SpokenMove(color=BLACK, piece=KING, from=e1, to=d1, castle=null, isCapture=true, captured=ROOK, enPassant=false, promotion=null, ambiguous=false, outcome=NONE), gain=ROOK | you play knight to f three, and now Black has king takes the rook on d one, winning a rook.<br>you play knight to f three, but Black has the reply king takes the rook on d one, winning a rook.<br>you play knight to f three, and Black can answer with king takes the rook on d one, winning a rook. |  |
| `MateWasAvailable` | mateIn=3, move=SpokenMove(color=WHITE, piece=QUEEN, from=d1, to=h5, castle=null, isCapture=false, captured=null, enPassant=false, promotion=null, ambiguous=false, outcome=NONE) | There was mate in three on the board, starting with queen to h five. |  |
| `ThreatLetIn` | *(empty)* | and that's the move that lets it in.<br>and now look what's available.<br>and the reply is unpleasant. |  |
| `InaccuracyNote` | *(empty)* | A small slip.<br>That's an inaccuracy: playable, but it gives something back.<br>Slightly off. |  |
| `PlayedSlip` | subject=Subject(color=BLACK, person=THIRD, gender=UNSPECIFIED), verb=PLAY, move=SpokenMove(color=BLACK, piece=PAWN, from=e7, to=e5, castle=null, isCapture=false, captured=null, enPassant=false, promotion=null, ambiguous=false, outcome=NONE), recapture=false | Black plays pawn to e five, a small slip.<br>Black plays pawn to e five, which is slightly off.<br>Black plays pawn to e five, but not the most precise. |  |
| `PlayedSlip` | subject=Subject(color=WHITE, person=SECOND, gender=FEMININE), verb=PLAY, move=SpokenMove(color=WHITE, piece=KNIGHT, from=g1, to=f3, castle=null, isCapture=false, captured=null, enPassant=false, promotion=null, ambiguous=false, outcome=NONE), recapture=false | you play knight to f three, a small slip.<br>you play knight to f three, which is slightly off.<br>you play knight to f three, but not the most precise. |  |
| `SmallSwing` | subject=Subject(color=WHITE, person=SECOND, gender=FEMININE), favours=true | That helps you.<br>It nudges the game your way.<br>Your position improves. |  |
| `SmallSwing` | subject=Subject(color=BLACK, person=THIRD, gender=UNSPECIFIED), favours=false | That gives a little back.<br>It costs Black a little.<br>Black's position slips a little. |  |
| `PivotIn` | *(empty)* | Now hold on. Let's rewind, because there was something much better here. <br>Stop. Freeze it right there — this position had far more in it. <br>Hold on, back up a move. There was a much better idea sitting right here.  | Pause here. A stronger continuation was available. <br>Hold the position a moment: there was a better move than the one played.  |
| `PivotQuestion` | side=WHITE | Hold on, back up. Can you see what White missed? <br>Let's rewind. Do you see what White missed here? <br>Freeze it there. Can you see what White missed?  | A stronger move was available to White. <br>White had something better here.  |
| `PivotReveal` | move=SpokenMove(color=WHITE, piece=KNIGHT, from=g1, to=f3, castle=null, isCapture=false, captured=null, enPassant=false, promotion=null, ambiguous=false, outcome=NONE) | The move is knight to f three.<br>It starts with knight to f three.<br>The move was knight to f three. | The move is knight to f three.<br>The correct continuation begins with knight to f three. |
| `PivotWalk` | *(empty)* | Let's play it out.<br>Let's walk it through, move by move.<br>Watch what happens if that goes in. | The line runs as follows.<br>Here it is, move by move. |
| `ExcursionMove` | subject=Subject(color=WHITE, person=SECOND, gender=FEMININE), verb=PLAY, move=SpokenMove(color=WHITE, piece=KNIGHT, from=g1, to=f3, castle=null, isCapture=false, captured=null, enPassant=false, promotion=null, ambiguous=false, outcome=NONE), step=FIRST, recapture=false | So: you play knight to f three.<br>It starts here. You play knight to f three.<br>Here we go. You play knight to f three. |  |
| `ExcursionMove` | subject=Subject(color=WHITE, person=SECOND, gender=FEMININE), verb=PLAY, move=SpokenMove(color=WHITE, piece=QUEEN, from=d1, to=h5, castle=null, isCapture=false, captured=null, enPassant=false, promotion=null, ambiguous=false, outcome=NONE), step=NEXT, recapture=false | Then you play queen to h five.<br>Next, you play queen to h five.<br>And then you play queen to h five. |  |
| `ExcursionMove` | subject=Subject(color=BLACK, person=THIRD, gender=UNSPECIFIED), verb=ANSWER_WITH, move=SpokenMove(color=BLACK, piece=KING, from=e1, to=d1, castle=null, isCapture=true, captured=ROOK, enPassant=false, promotion=null, ambiguous=false, outcome=NONE), step=REPLY, recapture=true | Black takes back on d one with the king. |  |
| `LineEndsInMate` | *(empty)* | The king has nowhere left to go, and the line stops there. |  |
| `MaterialTaken` | gain=ROOK | That is a rook off the board.<br>That is a rook, collected.<br>And there goes a rook. |  |
| `AlreadyUp` | subject=Subject(color=BLACK, person=THIRD, gender=UNSPECIFIED), gain=PIECE | Black is already a piece up.<br>Black is a piece up already. |  |
| `OnlyLegalReply` | *(empty)* | There is nothing else legal. |  |
| `ForcedByCheck` | *(empty)* | Forced — the king was in check, so nothing else was even legal.<br>Forced. The check has to be answered first. |  |
| `RecaptureNatural` | *(empty)* | Taking back is the natural answer, and it changes nothing.<br>The recapture is more or less forced, and it does not help. |  |
| `PayoffLead` | *(empty)* | And there it is. <br>So add it up. <br>Now look at the end of that line.  | The result of the line: <br>At the end of the line,  |
| `PayoffMate` | mateIn=2 | That is checkmate in two. |  |
| `PayoffMaterial` | subject=Subject(color=WHITE, person=SECOND, gender=FEMININE), gain=ROOK | You come out of it a rook up. |  |
| `PayoffOutcome` | subject=Subject(color=BLACK, person=THIRD, gender=UNSPECIFIED), kind=DRAW_BY_REPETITION | Black forces a draw by repetition. |  |
| `PayoffOutcome` | subject=Subject(color=BLACK, person=THIRD, gender=UNSPECIFIED), kind=LINE_ENDS | That is as far as the line goes. |  |
| `PayoffStanding` | subject=Subject(color=WHITE, person=SECOND, gender=FEMININE), standing=CLEARLY_BETTER | The line ends there, with you clearly better.<br>That is as far as it goes, and you are clearly better. |  |
| `MateBehindIt` | mateIn=4 | And there was mate in four behind it. |  |
| `PivotOut` | *(empty)* | In the real game, though, this got played instead —<br>But that is the line that never was. In the real game, this went on the board —<br>What actually happened was this — | The move actually chosen was this —<br>What was played instead was this — |
| `LineMove` | move=SpokenMove(color=BLACK, piece=BISHOP, from=g4, to=f3, castle=null, isCapture=true, captured=KNIGHT, enPassant=false, promotion=null, ambiguous=false, outcome=CHECK) | Bishop takes the knight on f three, with check. |  |
| `LineMove` | move=SpokenMove(color=WHITE, piece=KNIGHT, from=g1, to=f3, castle=null, isCapture=false, captured=null, enPassant=false, promotion=null, ambiguous=false, outcome=NONE) | Knight to f three. |  |
| `BackToTheGame` | *(empty)* | Back to the game now. |  |
| `ChanceGone` | *(empty)* | And the chance is gone.<br>The moment passes.<br>And with that, the window shuts. | The opportunity does not recur.<br>That continuation is no longer available. |
| `InsteadPlayed` | subject=Subject(color=WHITE, person=SECOND, gender=FEMININE), move=SpokenMove(color=WHITE, piece=KNIGHT, from=g1, to=f3, castle=null, isCapture=false, captured=null, enPassant=false, promotion=null, ambiguous=false, outcome=NONE), recapture=false | Instead, you play knight to f three, and it's gone.<br>What actually happened: you play knight to f three. The chance never comes back.<br>But no — you play knight to f three, and the moment passes. |  |
| `TacticPoint` | type=HANGING_PIECE, target=f3, victim=KNIGHT, second=null | The knight on f three has nothing defending it.<br>The knight on f three is en prise: nothing defends it.<br>The knight on f three is loose, with no defender at all. |  |
| `TacticPoint` | type=FORK, target=c7, victim=ROOK, second=e8 | It hits two things at once, on c seven and on e eight.<br>That's a fork: c seven and e eight are both attacked, and only one of them can get away.<br>A fork. Two targets, on c seven and on e eight, and only one can be saved. |  |
| `TacticPoint` | type=PIN_ABSOLUTE, target=c6, victim=KNIGHT, second=null | The piece on c six is pinned to the king, so it cannot leave that line.<br>That's an absolute pin: the piece on c six is tied to its own king and cannot step off the line. |  |
| `TacticPoint` | type=SKEWER, target=d5, victim=QUEEN, second=a8 | The piece on d five has to step aside, and the one behind it on a eight is exposed.<br>That's a skewer: the piece on d five is attacked, and whatever stands behind it on a eight is next. |  |
| `TacticPoint` | type=ZWISCHENZUG, target=d4, victim=KNIGHT, second=null | A zwischenzug, an in-between move: something forcing goes in first, and the capture is still there afterwards.<br>The in-between move comes first. The capture is not going anywhere. |  |
| `PayoffMaterial` | subject=Subject(color=WHITE, person=SECOND, gender=FEMININE), gain=EXCHANGE | You come out of it the exchange up. |  |
| `MaterialInTheBank` | payoff=MATERIAL | That's material in the bank.<br>Material, just like that.<br>So that's material. |  |
| `TacticLesson` | type=FORK | Any time two of your opponent's big pieces sit a knight's jump apart, stop and hunt for the fork square. |  |
| `TextbookOffer` | type=FORK | There's a clean textbook fork waiting in the game report if you want to see the pattern on its own. |  |
| `PuzzlePrompt` | side=WHITE, prize=WINS_PIECE | White to play. There is a move here that wins a whole piece. Pause the video. Can you find it?<br>White to play, and there's a move here that wins a whole piece. Stop the video and have a proper look.<br>Right, White to play. Something here wins a whole piece. Pause it. See if you spot it. |  |
| `TurningPointLead` | significant=true | That was the turning point. <br>That's where the game turned. <br>And that was the turning point.  | This is the largest evaluation swing in the game. <br>This move produces the decisive change in evaluation.  |
| `TurningPointLead` | significant=false | That was the biggest slip of the game. <br>If there is a turning point, that is it.  | This is the largest evaluation swing in the game.  |
| `TurningPointSwing` | moveNumber=23 | No other move in this game cost as much.<br>Nothing else in this game swings the evaluation like move 23. |  |
| `TurningAftermath` | kind=NEVER_BACK, side=WHITE, moveNumber=null, standing=null | From there, White never gave the lead back.<br>From then on, White stayed ahead. |  |
| `TurningAftermath` | kind=FOUGHT_BACK, side=BLACK, moveNumber=31, standing=BALANCED | It wasn't over: Black was back to level by move 31. |  |
| `TurningAftermath` | kind=FOUGHT_BACK, side=BLACK, moveNumber=31, standing=BLACK_EDGE | It wasn't over: Black was ahead again by move 31. |  |
| `OutroEnding` | winner=BLACK, drawn=false, byMate=true, fullMoves=33 | Black won by checkmate on move 33.<br>It ended in checkmate: Black wins on move 33. |  |
| `OutroEnding` | winner=WHITE, drawn=false, byMate=false, fullMoves=40 | White won after 40 moves.<br>White took the win after 40 moves. |  |
| `OutroEnding` | winner=null, drawn=true, byMate=false, fullMoves=40 | It ended in a draw after 40 moves.<br>The game was drawn after 40 moves. |  |
| `OutroEnding` | winner=null, drawn=false, byMate=false, fullMoves=23 | The game stops after 23 moves. |  |
| `OutroDecider` | mover=Subject(color=BLACK, person=THIRD, gender=UNSPECIFIED), classification=BLUNDER, moveNumber=28, before=ABOUT_LEVEL, after=LOSING | The decisive moment was Black's blunder on move 28, which took Black from about level to losing.<br>It all came down to Black's blunder on move 28: Black went from about level to losing. |  |
| `OutroDecider` | mover=Subject(color=WHITE, person=SECOND, gender=FEMININE), classification=MISS, moveNumber=12, before=WINNING, after=A_LITTLE_BETTER | The decisive moment was your missed win on move 12, which took you from winning to slightly better.<br>It all came down to your missed win on move 12: You went from winning to slightly better. |  |
| `OutroNoDecider` | *(empty)* | There was no single decisive mistake.<br>Neither side made one decisive mistake. |  |
| `LessonLead` | count=1 | So what's the takeaway? <br>Here's the one thing to take away. <br>One thing to take out of this game.  |  |
| `LessonLead` | count=3 | So what do you take away from this? <br>Here's what to actually work on. <br>Three things to take out of this game.  |  |
| `TakeawayMissed` | subject=Subject(color=WHITE, person=SECOND, gender=FEMININE), moveNumber=12, type=FORK, better=SpokenMove(color=WHITE, piece=KNIGHT, from=g1, to=f3, castle=null, isCapture=false, captured=null, enPassant=false, promotion=null, ambiguous=false, outcome=NONE) | At move 12, you missed a fork. Knight to f three was the move.<br>Look at move 12. You missed a fork. Knight to f three was the move. |  |
| `TakeawayMissed` | subject=Subject(color=BLACK, person=THIRD, gender=UNSPECIFIED), moveNumber=12, type=X_RAY, better=null | At move 12, Black missed an x-ray.<br>Look at move 12. Black missed an x-ray. |  |
| `TakeawayAllowed` | subject=Subject(color=WHITE, person=SECOND, gender=FEMININE), moveNumber=28, opponent=BLACK, mateIn=3, reply=null | Move 28 let Black in. It allowed a forced mate in three.<br>At move 28, the door opened for Black. It allowed a forced mate in three. |  |
| `TakeawayAllowed` | subject=Subject(color=BLACK, person=THIRD, gender=UNSPECIFIED), moveNumber=28, opponent=WHITE, mateIn=null, reply=SpokenMove(color=WHITE, piece=QUEEN, from=d1, to=h5, castle=null, isCapture=false, captured=null, enPassant=false, promotion=null, ambiguous=false, outcome=NONE) | Move 28 let White in. White could answer with queen to h five.<br>At move 28, the door opened for White. White could answer with queen to h five. |  |
| `TakeawayAllowed` | subject=Subject(color=BLACK, person=THIRD, gender=UNSPECIFIED), moveNumber=28, opponent=WHITE, mateIn=null, reply=null | Move 28 let White in.<br>At move 28, the door opened for White. |  |
| `TakeawayCostly` | subject=Subject(color=WHITE, person=SECOND, gender=FEMININE), moveNumber=23, severity=MOST_OF_THE_ADVANTAGE, before=CLEARLY_BETTER, after=ABOUT_LEVEL | Move 23 gave up most of the advantage, from clearly better to about level.<br>At move 23, you gave up most of the advantage, from clearly better to about level. |  |
| `TakeawayCostly` | subject=Subject(color=BLACK, person=THIRD, gender=UNSPECIFIED), moveNumber=23, severity=REAL_GROUND, before=WINNING, after=WINNING | Move 23 gave up real ground.<br>At move 23, Black gave up real ground. |  |
| `TakeawayClean` | *(empty)* | Neither side made a decisive mistake, so the habit to keep is simple: check what every move allows. |  |
| `TakeawayHabitAllowed` | *(empty)* | Before you commit, ask one question: what does this let them do next? |  |
| `TakeawayHabitStanding` | standing=WINNING | When you're ahead, slow down. Check every capture and every check before you move. |  |
| `TakeawayHabitStanding` | standing=ABOUT_LEVEL | In a level position, every move counts. Check every capture and every check before you move. |  |
| `TakeawayHabitStanding` | standing=LOSING | Even when you're worse, don't rush. Check every capture and every check before you move. |  |
| `LessonMatedButNotTheMistake` | subject=Subject(color=WHITE, person=SECOND, gender=FEMININE), turningMove=23 | You got mated at the end, but the mate wasn't the mistake. Move 23 was. Fix it and the mate never happens. |  |
