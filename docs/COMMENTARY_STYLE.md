# Commentary style (C1, 2026-10-07)

The owner asked for the game description and the tactics commentary to "sound more natural and
interesting" and to "use more professional terminology and terms", like a good human commentator. An LLM
is deferred to a later version (C2), so this is the deterministic text generator improved: the card text
(`CommentaryGenerator`), the walkthrough steps and payoff (`SimulationBuilder`), the best-line caption
(`BestLineCaption`) and the narrated video's sentences (`EnglishNarration`, `NarrationVocabulary`).

The hard rule did not move: **every sentence is a verified claim** (ANALYSIS_SPEC §7.2). A term is used
only where a detector or the engine's numbers prove its definition, an unverifiable sentence is dropped
and never hedged, and `scripts/audit_commentary.py` re-derives every term with python-chess
(`docs/COMMENTARY_AUDIT.md`, "C1"). Chess.com is a pattern reference only: the move-quality names and the
coach-text shape (badge carries the label, the text goes to the piece and the threat); every word here is
our own.

## 1. Vocabulary-to-proof table

A term appears in a sentence only when the proof in its row holds on the position (or in the engine's
numbers) at generation time; the Kotlin generator re-checks it (`CommentaryGenerator.motifOf` and friends,
`CommentaryClaimsTest`) and the Python audit re-derives it independently.

| Term (as said) | Where | Proof (re-verified on the board or in the numbers) |
|---|---|---|
| **fork** "forks the king on g8 and the queen on d5" / "is a fork, hitting ... at once" / "lands a fork on ..." | card, narration | the moved piece attacks every named target on the position after the move; each target is the opponent's and the piece named stands there |
| **pawn fork** "forks ... with a pawn" / "is a pawn fork, hitting ..." | card, narration | as a fork, and the moved piece is a pawn |
| **double attack** "attacks A and B at once" / "creates a double attack on A and B" | card, narration | each named piece is the opponent's and attacked by the mover after the move |
| **absolute pin** "puts the knight on c6 in an absolute pin against the king on e8" / "pins ... to the king on e8" | card, narration | a slider of the mover attacks the front piece and the king stands directly behind it on the same line |
| **relative pin** "ties the pawn on b7 to the knight on b8 with a relative pin" / "pins ... to ..." | card, narration | as above with a non-king rear piece worth more than the front piece |
| **skewer** "skewers the queen on d5, with the rook on a8 behind it" / "is a skewer: it attacks ..., and ... stands behind it on the same line" | card, narration | a slider attacks the front piece, the rear piece stands directly behind it; the description says "attacked", never "falls" |
| **discovered attack** "uncovers the rook on h1, which now attacks the bishop on h5" / "is a discovered attack: the rook on h1 is unmasked against ..." | card, narration | the attacker is the mover's slider, not the moved piece, attacks the target after the move and did not before, through the vacated square |
| **discovered check** "gives check by uncovering the ..." / "is a discovered check from the ..." | card, narration | the checker is not the moved piece |
| **double check** "gives double check" / "is a double check: only a king move can answer it" | card, narration | two checkers |
| **en prise**, **loose** "attacks the queen on g5, which is en prise" / "hits the loose bishop on b5" / "leaves the pawn on g7 en prise to the knight on f5" | card, narration | the piece is the opponent's, attacked by the moved piece (or by the named piece), and has no defender |
| **more attackers than defenders** "piles up on the pawn on g4: more attackers than defenders" | card | attackers of the mover outnumber the defenders |
| **trapped** "traps the queen on g5: every square it can reach loses material" / "leaves ... with no safe square" | card, narration | the opponent is not in check and every legal move of the piece loses material by exchange evaluation |
| **wins the exchange** (card, walkthrough step "winning the exchange", caption "In this line White wins the exchange.", narration "the exchange up") | all | a knight or bishop takes a rook for a swap-off net of `rook - minor` ± 40; a line: the pieces counted between its start and its settled end show exactly one minor given and one rook taken, nothing else (`ExchangeEvaluator.winsTheExchange`) |
| **wins a queen / a rook / a piece / a pawn** "wins a rook" / "picks up a rook" | all | a capture that exchange evaluation nets within 40 cp of that unit, and not a recapture |
| **zwischenzug** "In the engine's line, Bb5+ is a zwischenzug: it comes first, and Qxd4 follows." | card (as the engine's line), narration | the move gives check or captures something worth more than the mover; a capture that does not lose material was waiting on an enemy piece worth a minor or more; the engine's line makes it on the mover's next move or the one after |
| **overloaded** "In the engine's line, Nxf6+ exploits the overloaded bishop on d8, which cannot guard c7 and f6 at once." | card (as the engine's line), narration | on the position before the move the named piece is the opponent's and the sole defender of both squares, each an opponent's piece the mover attacks; the engine's line lands on one of them |
| **desperado** "is a desperado: the queen was lost anyway, so it takes the rook on d8 on the way out" | card, narration | the mover's piece (a minor or more) could be taken where it stood if the opponent had the move, the capture loses material by exchange, and it can be taken where it landed |
| **back-rank mate** "is a back-rank mate" / "is mate on the back rank"; the threat "threatens Re8, mate on the back rank" / "sets up a back-rank mate: Re8 is the threat" | card, narration | mate with the king on its back rank; the threat: the king on its back rank, every forward square held by its own men with a pawn among them, and the named rook or queen move mates if the opponent could pass |
| **smothered mate** "is a smothered mate: the king is boxed in by its own pieces" | card, narration | a lone knight checks, every neighbour of the king holds its own piece |
| **forced mate in N** "starts a forced mate in 3" / "begins ..." / "sets a forced mate in 3 in motion"; "This walks into a forced mate in 5." | card, caption, narration | the engine's own mate distance (before the move for the mover's and the better move's mate, after it for the opponent's reply) |
| **only move** "Nf5 is an only move, and nothing else keeps the position on track." / "... the only move here: the next-best option gives up real ground." | card (GREAT) | the engine's top move with a MultiPV gap of at least 10 win-percent (§2), which is the "real ground" band of §9 |
| **evaluation in words** "That takes White from winning to about level." | card (errors), narration | the mover's win-percent before and after, by the bands of §9: decisively winning 95, winning 82, clearly better 68, slightly better 57, about level 43, slightly worse 32, clearly worse 18, losing 5, decisively lost; said only when a band was crossed |
| **a winning position / a decisive advantage** (MISS: "Better was Rxh7: it keeps a winning position.") | card | the mover's win-percent before the move: 82..95 / 95 and up (a MISS needs 90 or a mate) |
| **sacrifice** "Nxb5 sacrifices the knight on b5." / "Rb8 offers the knight on d7: a sacrifice the engine rates among the best moves here." | card (BRILLIANT) | the opponent can take the named piece for a net of at least 200 by exchange; the third wording also needs the class's loss of at most 2 |
| **the engine's line** "In the engine's line, ..." / "The engine's line shows it: ..." | card | deflection, decoy, clearance, removing the defender, interference, Greek gift, windmill, zwischenzug, overload: proved by replaying the recorded PV, and said as the engine's line because the opponent may reply differently |
| **a spoken line move** (V4) "Knight takes the pawn on f seven, with check." / "Castles queenside, with check." / "The rook on a one to d one." | narration (the video's best line) | the move is the engine's line's next move, legal on the board it is played on (the Board's line, `BestLines`); the piece named stands on its from-square; "the X on <square>" only when another piece of that kind could also reach the target; "takes the Y on" names the piece standing on the target (en passant said as such); the promotion piece; "with check" / "and that is checkmate" read off the position after the move. One wording, no rotation. |
| **back to the game** (V4) "Back to the game now." | narration (after the video's best line; first sentence of a detour's pivot-out) | a simulated line (the best line's last move, or a detour's payoff) has just ended, and the board this sentence is said over is the game's own position before the move the moment is about |

**Terms not added, and why.** "Simplifies", "liquidates", "trades into a winning endgame" need an endgame
definition and an evaluation after the trade that one position's numbers do not hold. "Loses a tempo" has
no provable definition from the data. "Converts" is a judgement about a whole game. "X-ray" and "battery"
are static patterns worth nothing by themselves and stay out of the card as before. Any plan, idea,
intention or "strategic" judgement is never claimed.

## 2. The variety scheme

Every template has two or three phrasings. Which one a card gets is decided by `Variety`:

    index = (ply + hash(templateKey)) mod n

with `ply` read off the position (`CommentaryGenerator.plyOf`: 1 before White's first move) and `hash` Java's
`String.hashCode`, which is specified, so the result is the same on every device. Properties:

- the same game always produces the same text, so the narration cache (keyed by text) and the recorded
  audits are reproducible, and `regenerate` for a side is byte-identical with analysis-time text;
- two consecutive cards that use the same template never share a phrasing (the index steps with the ply),
  so a run of best moves does not read "matches the engine's top choice" three times;
- different templates on one card start at different offsets, so a card does not rhyme with itself;
- no randomness, no clock, no game-level state: the text stays a pure function of the annotation and
  the viewer's side (§7.1).

The second sentence of a praised move opens "This ..." or "It ..." and never repeats the move the lead just
named. The narrated video keeps its own rotation (`PhrasePicker`, seeded from the report), which now has
more to rotate: two or three spoken phrasings per motif.

## 3. Tone rules

- **Blunder, mate: short.** "Kb1 throws a big chunk of the position away." "It is checkmate." The charge
  names the reply and what it does, and stops.
- **Mistake: direct.** "b5 goes wrong." "Qxb7 lets the position slip."
- **Inaccuracy: calm.** "Be3 is not the most precise." "Nh4 concedes a little ground."
- Each "wrong" wording is a reading of the loss band that defines its class (§2: at least 5, 10 and 20
  win-percent) and of the severity words of §9 ("real ground" is 10 or more, "a big chunk" 18 or more).
- **The viewer's side is "you".** "Now you can play Nxg7+ ..." / "That takes your opponent from clearly
  worse to decisively lost." Sentences avoid verbs that have to agree with the subject, so the "you" text
  is the colour text with the subject word changed and nothing else (the audit checks that mechanically).
- **The engine is the engine.** A score is never a result; "the engine's first choice", "the engine's line",
  "the engine sees a forced mate in 3".
- **Never the classification's own name** at the start of a text (the badge shows it), never a hedge
  ("probably", "might"), never a plan or an intention.

## 4. Before and after

The card texts of the three recorded games, no side chosen, generated by the pipeline before and after C1
(`CommentaryAuditDumpTest`, `scripts/audit_commentary.py after`: 0 WRONG both times). Ten per game.

### The Immortal Game (depth 12)

| Ply | Before | After |
|---|---|---|
| 11 Nf3 | Nf3 matches the engine's top choice. This attacks the undefended queen on h4. | Nf3 matches the engine's top choice. It hits the loose queen on h4. |
| 17 Nf5 | Nf5 was the only move that kept things on track. | Nf5 is an only move, and nothing else keeps the position on track. |
| 19 g4 | This lets Black play Nf6, which attacks the pawn on g4 more often than it is defended. Better was Ba4. | Now Black can play Nf6, which piles up on the pawn on g4: more attackers than defenders. That takes White from about level to slightly worse. Better was Ba4. |
| 22 cxb5 | This lets White play h4, which attacks the undefended queen on g5. Better was h5, which attacks the pawn on g4 more often than it is defended. | Now White can play h4, which attacks the undefended queen on g5. That takes Black from about level to slightly worse. Better was h5, which attacks the pawn on g4 more often than it is defended. |
| 27 Qf3 | Qf3 matches the engine's top choice. This leaves the queen on g5 with no safe square. | Qf3 is the engine's first choice. It traps the queen on g5: every square it can reach loses material. |
| 33 Nd5 | This lets Black play Qxb2, which attacks the undefended rook on a1. Better was d4, which attacks the undefended bishop on c5. | This lets Black play Qxb2, which attacks the rook on a1, which is en prise. From winning to clearly better in one move: that is what this cost White. Better was d4: it attacks the bishop on c5, which is en prise. |
| 35 Bd6 | This lets Black play Qxa1+, which wins a rook. Better was Re1. | This hands Black Qxa1+, which wins a rook. The position swings from clearly better to about level for White. Better was Re1. |
| 36 Bxg1 | This lets White play Re1, which leaves the bishop on g1 with no safe square. Better was Qxa1+, which wins a rook. | This lets White play Re1, which leaves the bishop on g1 with no safe square. From about level to clearly worse in one move: that is what this cost Black. Better was Qxa1+, which picks up a rook. |
| 40 Na6 | This lets White play Nxg7+, which starts a forced mate. Better was Ba6. | Now White can play Nxg7+, which sets a forced mate in 5 in motion. That takes Black from clearly worse to decisively lost. Better was Ba6. |
| 41 Nxg7+ | Nxg7+ was the only move that kept things on track. This starts a forced mate. | Nxg7+ is an only move, and nothing else keeps the position on track. It starts a forced mate in 5. |

### game01 (the owner's game, depth 12)

| Ply | Before | After |
|---|---|---|
| 11 Be3 | Be3 gives back ground. Better was Be2. | Be3 is not the most precise. The position swings from slightly better to about level for White. Better was Be2. |
| 14 e4 | This lets White play h3, which attacks the bishop on g4 with a pawn. Better was exd4, which attacks the bishop on e3 with a pawn. | This hands White h3, which attacks the bishop on g4 with a pawn. The position swings from about level to clearly worse for Black. Better was exd4, which attacks the bishop on e3 with a pawn. |
| 19 hxg4 | hxg4 matches the engine's top choice. This uncovers the rook on h1, which now attacks the bishop on h5. | hxg4 is the top engine move here. It is a discovered attack: the rook on h1 is unmasked against the bishop on h5. |
| 24 exf3 | exf3 was the only move that kept things on track. This wins a piece. | exf3 was the only move that kept things on track. This picks up a piece. |
| 33 Qa4 | Qa4 is a sound move. This pins the pawn on a7 to the rook on a8. | Qa4 is a solid choice. It ties the pawn on a7 to the rook on a8 with a relative pin. |
| 47 Bh3 | Better was Rxh7, keeping a decisive advantage. | The position swings from winning to clearly better for White. Better was Rxh7: it keeps a winning position. |
| 49 Kb1 | Kb1 gives back ground. Better was Bxd7, which attacks the queen on e8 with a bishop. | Kb1 throws a big chunk of the position away. That takes White from clearly better to about level. Better was Bxd7: it hits the queen on e8 with a bishop. |
| 55 Qxc6 | This lets Black play Rb8, which pins the pawn on b2 to the king on b1. Better was Re1. | Now Black can play Rb8, which pins the pawn on b2 to the king on b1. That takes White from about level to losing. Better was Re1. |
| 56 Rb8 | Rb8 is a sacrifice: it offers the knight on d7. This pins the pawn on b2 to the king on b1. | Rb8 offers the knight on d7: a sacrifice the engine rates among the best moves here. This puts the pawn on b2 in an absolute pin against the king on b1. |
| 59 Qxd7 | This lets Black play Bxe4+, which starts a forced mate. Better was Bxd7, which wins a piece. | This hands Black Bxe4+, which starts a forced mate in 4. The position swings from losing to decisively lost for White. Better was Bxd7: it wins a piece. |

### The Opera Game (`fixtures/chesscom_style_game.pgn`, depth 20)

| Ply | Before | After |
|---|---|---|
| 8 Bxf3 | Bxf3 gives back ground. Better was Nc6. | Bxf3 is not the most precise. Better was Nc6. |
| 11 Bc4 | Bc4 gives back ground. Better was Qb3, which pins the pawn on b7 to the knight on b8. | Bc4 is not the most precise. The position swings from clearly better to slightly better for White. Better was Qb3: it ties the pawn on b7 to the knight on b8 with a relative pin. |
| 12 Nf6 | This lets White play Qb3, which pins the pawn on b7 to the knight on b8. Better was Qf6. | This lets White play Qb3, which pins the pawn on b7 to the knight on b8. From slightly worse to clearly worse in one move: that is what this cost Black. Better was Qf6. |
| 13 Qb3 | Qb3 was the only move that kept things on track. This pins the pawn on b7 to the knight on b8. | Qb3 is the only move here: the next-best option gives up real ground. It ties the pawn on b7 to the knight on b8 with a relative pin. |
| 18 b5 | b5 gives back ground. Better was Kd8. | b5 goes wrong. From clearly worse to losing in one move: that is what this cost Black. Better was Kd8. |
| 19 Nxb5 | Nxb5 is a sacrifice: it offers the knight on b5. | Nxb5 sacrifices the knight on b5. |
| 20 cxb5 | cxb5 gives back ground. Better was Qb4+; in the engine's line it clears e7 so that Bxb4+ can come through. | cxb5 is not the most precise. The position swings from losing to decisively lost for Black. Better was Qb4+: in the engine's line it clears e7 so that Bxb4+ can come through. |
| 29 Bxd7+ | Bxd7+ matches the engine's top choice. In the engine's line, Bxd7+ clears b5 so that Qb8+ can come through. | Bxd7+ matches the engine's top choice. It wins the exchange. |
| 31 Qb8+ | Qb8+ was the only move that kept things on track. This starts a forced mate. | Qb8+ is the only move here: the next-best option gives up real ground. It sets a forced mate in 2 in motion. |
| 33 Rd8# | Rd8# was the only move that kept things on track. This is checkmate. | Rd8# was the only move that kept things on track. It is checkmate. |

(29 Bxd7+: 14...Rxd7 left a rook on d7, so 15.Bxd7+ Nxd7 is a bishop for a rook. Before C1 the clearance
motif was the best true thing to say; the exchange, worth 170 by the swap-off, now outranks it.)

### Narration (the spoken video, Normal pace, no side chosen; `PaceMeasurementDumpTest`)

The narrated review says its own sentences from typed facts (`NarrationStrings`), so the register and the
rotation are its own; C1 gave its motif sentences the same terms with the same proofs, aligned its evaluation
and material words with the cards, and removed two claims it should never have made (a found mate ended
"So that's a whole queen"; "and everything holds" after a better move). Ten beats per game; a beat that did
not change is shown once.

**The Immortal Game**

| Beat | Before | After |
|---|---|---|
| 7...Nh5 | Black plays knight to h five. Instead, pawn to c six, and everything holds. | Black plays knight to h five. Instead, pawn to c six, which gives nothing away. |
| 14.Qf3 | White plays queen to f three. The pawn on f four has nothing defending it. | (unchanged) |
| 18.Bd6 reveal | Hold on, back up a move. There was a much better idea sitting right here. The move was rook to e one. The attack goes straight through the piece in the middle. Let's play it out. | (unchanged) |
| 18...Bxg1 reveal | Now hold on. Let's rewind, because there was something much better here. The move is queen takes the rook on a one, with check. It hits two things at once, on f one and on a two. Let's walk it through, move by move. | Now hold on. Let's rewind, because there was something much better here. The move is queen takes the rook on a one, with check. A fork. Two targets, on f one and on a two, and only one can be saved. Let's walk it through, move by move. |
| 18...Bxg1 payoff | So add it up. Black comes out of it a rook up. | (unchanged) |
| 20...Na6 | Black plays knight to a six. The moment passes, and that is that. Black has gone from clearly worse to completely lost. | Black plays knight to a six. The moment passes, and that is that. Black has gone from clearly worse to decisively lost. |
| turning point | Stop there, because that is the moment the game changed hands. Nothing else in this game swings the evaluation like move 20. Black went from clearly worse to completely lost on one move. | ... Black went from clearly worse to decisively lost on one move. |
| 21.Nxg7+ | White takes the pawn on g seven with the knight, with check. It's a forced mate. The king has nowhere to run. So that's a whole queen. | White takes the pawn on g seven with the knight, with check. This is a forced mate, and the king cannot get out. |
| 22.Qf6+ | Shortly after that, White goes for queen to f six, with check. It's a forced mate. The king has nowhere to run. That's a whole queen in the bank. | Shortly after that, White goes for queen to f six, with check. It's a forced mate. The king has nowhere to run. |
| 23.Be7# | A couple of moves later, White plays bishop to e seven, and that is checkmate. And there it is. White wins. | (unchanged) |

**game01**

| Beat | Before | After |
|---|---|---|
| 6...e5 | Black plays pawn to e five. It drags the defender off d one, and once it's gone nothing holds together. | (unchanged) |
| 7...e4 | Two moves on, Black plays pawn to e four. Instead, pawn takes the pawn on d four, and everything holds. | Two moves on, Black plays pawn to e four. Instead, pawn takes the pawn on d four, which gives nothing away. |
| 9.g4 | White plays pawn to g four. The bishop on h five has nothing defending it. | White plays pawn to g four. The bishop on h five is loose, with no defender at all. |
| 19.d6 | Two moves on, White plays pawn to d six. It cuts the defender's line, and the piece it was covering falls. | Two moves on, White plays pawn to d six. It cuts the defender's line, and the piece it was covering is left open. |
| 19...Bf6 | Black plays bishop to f six. That's losing turning into completely lost in a single move. | Black plays bishop to f six. That's losing turning into decisively lost in a single move. |
| 22.Bf5 | White plays bishop to f five. The piece on d seven is pinned, and moving it costs more than staying put. | White plays bishop to f five. That's a relative pin: the piece on d seven is tied to something worth more behind it. |
| 28.Qxc6 | White takes the pawn on c six with the queen. The moment passes, and that is that. That's about level turning into losing in a single move. | (unchanged) |
| 29.b3 | White plays pawn to b three. It drags the defender off d seven, and once it's gone nothing holds together. | White plays pawn to b three. A deflection: the defender is pulled away from d seven, and what it was guarding is left open. |
| 29...Bb7 | Black finds bishop to b seven. The queen on c six has nothing defending it. That's a whole queen in the bank. | Black finds bishop to b seven. The queen on c six is en prise: nothing defends it. That's a whole queen in the bank. |
| 30...Bxe4+ | Black takes the knight on e four with the bishop, with check. It's a forced mate. The king has nowhere to run. A whole queen, just like that. | Black takes the knight on e four with the bishop, with check. It's a forced mate. The king has nowhere to run. |

**The Opera Game**

| Beat | Before | After |
|---|---|---|
| 4...Bxf3 | Black takes the knight on f three with the bishop. Instead, knight to c six, and everything holds. | Black takes the knight on f three with the bishop. Instead, knight to c six, which gives nothing away. |
| 6...Nf6 reveal | Now hold on. Let's rewind, because there was something much better here. The move was queen to f six. The front piece has to step aside, and the one behind it on f three drops. Watch what happens if that goes in. | ... The move was queen to f six. The piece on f three has to step aside, and the one behind it on f two is exposed. Watch what happens if that goes in. |
| 6...Nf6 | Black plays knight to f six. The moment passes, and that is that. Black was slightly worse before that. Now Black is clearly worse. | (unchanged) |
| 7.Qb3 | White plays queen to b three. The piece on b seven is pinned, and moving it costs more than staying put. That's a pawn in the bank. | White plays queen to b three. That's a relative pin: the piece on b seven is tied to something worth more behind it. That's a pawn in the bank. |
| turning point | Stop there, because that is the moment the game changed hands. Nothing else in this game swings the evaluation like move 9. Black went from clearly worse to losing on one move. | (unchanged) |
| 10...cxb5 | Black takes back on b five with the pawn. Instead, queen to b four, with check, and everything holds. | Black takes back on b five with the pawn. Instead, queen to b four, with check, which gives nothing away. |
| 13.Rxd7 | White takes the knight on d seven with the rook. It drags the defender off e seven, and once it's gone nothing holds together. A rook, just like that. | White takes the knight on d seven with the rook. It drags the defender off e seven, and once it's gone nothing holds together. Serious material, just like that. |
| 14.Rd1 | Shortly after that, White goes for rook to d one. It drags the defender off f six, and once it's gone nothing holds together. It doesn't win material yet, but the pressure is real. | Shortly after that, White goes for rook to d one. A deflection: the defender is pulled away from f six, and what it was guarding is left open. It doesn't win material yet, but the pressure is real. |
| 16.Qb8+ | A couple of moves later, White finds queen to b eight, with check. It's a forced mate. The king has nowhere to run. So that's a whole queen. | A couple of moves later, White finds queen to b eight, with check. It's a forced mate. The king has nowhere to run. |
| 17.Rd8# | Two moves on, White plays rook to d eight, and that is checkmate. And that's the game. White wins. | (unchanged) |

(13.Rxd7: the deflection's advertised swing is the queen the line wins back less what it costs, 570 cp, which is
no whole unit; "a rook" was the old "500 and up" cut-off. 6...Nf6 reveal: the skewer's rear piece is the pawn on
f2 behind the queen on f3; the old sentence named the front square and said it "drops".)

The old 6...Nf6 reveal and the old mate beats are the two kinds of sentence this pass retired: a verb stronger
than the board ("drops") and a material claim for a mate.

## 5. Measured: pacing and video length

`PaceMeasurementDumpTest` (Stockfish 19 recordings, 169 wpm, story = speech estimates plus puzzle holds; the pace time
of §9.8 is unchanged by C1 because no segment was added or removed). Before C1 -> after C1:

| Game (plies) | Budget | Story before | Story after | Change | Relaxed total before -> after |
|---|---|---|---|---|---|
| scholar's mate (7) | 60.0 s | 52.5 s | 52.5 s | 0 | 61.0 -> 61.0 s |
| Opera Game (33) | 358.0 s | 306.9 s | 308.8 s | +1.9 s | 345.4 -> 347.3 s |
| Immortal Game (45) | 442.0 s | 422.0 s | 420.1 s | -1.9 s | 466.5 -> 464.6 s |
| game01 (66) | 582.0 s | 544.6 s | 546.7 s | +2.1 s | 631.1 -> 633.2 s |
| Byrne-Fischer (82) | 694.0 s | 537.3 s | 535.4 s | -1.9 s | 588.8 -> 586.9 s |

The longer motif sentences add one to two seconds; the dropped material sentence after a found mate takes two
to four back. Every game is inside its §9.7 budget as before; the Immortal Game is still the one the budget
trims (its plan overran and was cut to 420 s). The protected tiers, the FULL cap and the 15 percent pace cap
hold (`PacingTiersTest`, `PaceTimingTest`, unchanged). The recap card, the "about N min left" estimate and the
"N of M" count are untouched: no segment was added, and the card text is not spoken.

## 6. Spoken respellings (C1-device)

The card text keeps the real term. Two terms are misread by the Kokoro voice (espeak-ng's English rules, measured on a device and
with the phonemizer shipped in sherpa-onnx 1.13.8): "zwischenzug" came out "ZWISH-un-zug" (`zwˈɪʃənzˌʌɡ`) and "en prise" "en PRIZE"
(`ˈɛn pɹˈaɪz`). The narration therefore hands the voice a respelling and nowhere else: "zwischenzug" -> "zwishentsuuk"
(`zwˈɪʃəntsˌuːk`), "en prise" -> "on preez" (`ˌɔn pɹˈiːz`). The table is `SpokenRespelling` (app, `video/`), applied by
`NeuralTtsProvider.synthesize`; the narration cache key stays the real sentence, and the provider fingerprint carries the table's id so an
edit to the table never reuses older audio. Desperado, skewer, decisively and "the exchange up" are read correctly and are not respelled. A new
entry needs the phonemes of the term and of the respelling, pinned in `SpokenRespellingTest`. The device voice is not respelled.

## 7. The spoken best line (V4)

The owner found the video's simulated lines confusing because their moves were played in silence. Since V4 every
move of the engine's best line after a key moment is said as it is played, in the words every other narrated move
uses ("Pawn takes the pawn on d four.", "Knight to c six.", "Castles queenside, with check."), and the board's
return to the game is said in five words: "Back to the game now." The detour of a missed tactic already said each
move ("So: queen takes the rook on a one, with check."); its pivot-out now opens with the same "Back to the game
now." and its variants were reworded so they do not say "back" twice ("In the real game, though, this got played
instead -", "What actually happened was this -").

Both sentences are claims and are verified like the rest: the spoken move against the board in Kotlin
(`CommentaryClaimsTest`, worked out from the board without the narration's vocabulary) and with python-chess
(`scripts/audit_commentary.py lines`), the return against the game's position; `mutate` breaks each and every break
is flagged (`docs/COMMENTARY_AUDIT.md`, "V4"). A line move has one wording on purpose: a move is a fact, and the
same move must sound the same wherever it is said.

