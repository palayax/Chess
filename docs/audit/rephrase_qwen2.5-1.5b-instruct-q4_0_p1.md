# Rephrase measurement: qwen2.5-1.5b-instruct-q4_0, prompt v1

Written by `RephraseMeasurementDumpTest` from `docs/audit/rephrase/raw_qwen2.5-1.5b-instruct-q4_0.jsonl` (host run of
`scripts/rephrase_measure.py`, llama.cpp b11190 CPU, greedy, n_ctx 2048, prefix in the KV cache).
Verdicts by the Kotlin `ClaimChecker`; `scripts/audit_commentary.py rephrase` re-checks them independently.
Corpus: every distinct card text of the three audited games (no side, White, Black) and every eligible
narration beat of the five pacing games at the Normal pace (three sides).

## Verdicts (design §5.5: rejection = REJECT / all; bar <= 25 % cards, <= 35 % narration)

| Surface | Texts | Accepted | Unchanged | Rejected | Rejection rate | Unchanged rate |
|---|---|---|---|---|---|---|
| card | 190 | 79 | 99 | 12 | 6.3 % | 52.1 % |
| narration | 97 | 41 | 30 | 26 | 26.8 % | 30.9 % |

### Rejections by reason

| Reason | Cards | Narration |
|---|---|---|
| BANNED | 5 | 1 |
| FACTS_BANDS | 1 | 1 |
| FACTS_HYPOTHETICAL | 0 | 1 |
| FACTS_NEGATION | 0 | 1 |
| FACTS_NUMBERS | 0 | 2 |
| FACTS_OUTCOMES | 3 | 3 |
| FACTS_PIECES | 0 | 1 |
| FACTS_PIECE_SQUARES | 0 | 1 |
| FACTS_PLAYERS | 0 | 3 |
| FACTS_SQUARES | 0 | 1 |
| FACTS_TERMS | 2 | 1 |
| SHAPE_LENGTH | 1 | 10 |

## Latency on the host (reference only; the Pixel 8 decides)

| Surface | Median ms | p90 ms | Median prompt tokens (after the cached prefix) | Median output tokens | Prefill tok/s | Decode tok/s |
|---|---|---|---|---|---|---|
| card | 3602 | 6317 | 75 | 24 | 47.0 | 13.1 |
| narration | 4355 | 7590 | 69 | 25 | 44.2 | 12.3 |

Key moments per game (the cards of class BLUNDER/MISTAKE/MISS/BRILLIANT/GREAT, no side, at most 5): total time

- chesscom: 5 cards, 22.0 s
- game01: 5 cards, 25.9 s
- immortal: 5 cards, 20.9 s

## Quality sample: original vs accepted rewrite (owner: fill the A/B column, prefer = R or O)

| # | Id | Original | Rewrite | Prefer |
|---|---|---|---|---|
| 1 | card/immortal/BLACK/36 | This lets your opponent play Re1, which leaves the bishop on g1 with no safe square. From about level to clearly worse in one move: that is what this cost you. Better was Qxa1+, which picks up a rook. | This lets your opponent play Re1, leaving the bishop on g1 with no safe square. From about level to clearly worse in one move: that is what this cost you. Better was Qxa1+, which picks up a rook. |  |
| 2 | card/immortal/BLACK/40 | Now your opponent can play Nxg7+, which sets a forced mate in 5 in motion. That takes you from clearly worse to decisively lost. Better was Ba6. | Now your opponent can play Nxg7+, setting a forced mate in 5, and you go from clearly worse to decisively lost. Better was Ba6. |  |
| 3 | card/immortal/NONE/22 | Now White can play h4, which attacks the undefended queen on g5. That takes Black from about level to slightly worse. Better was h5, which attacks the pawn on g4 more often than it is defended. | Now White can play h4, attacking the undefended queen on g5, which takes Black from about level to slightly worse. Better was h5, which attacks the pawn on g4 more often than it is defended. |  |
| 4 | card/immortal/NONE/36 | This lets White play Re1, which leaves the bishop on g1 with no safe square. From about level to clearly worse in one move: that is what this cost Black. Better was Qxa1+, which picks up a rook. | This lets White play Re1, leaving the bishop on g1 with no safe square. From about level to clearly worse in one move: that is what this cost Black. Better was Qxa1+, which picks up a rook. |  |
| 5 | card/immortal/NONE/40 | Now White can play Nxg7+, which sets a forced mate in 5 in motion. That takes Black from clearly worse to decisively lost. Better was Ba6. | Now White can play Nxg7+, setting a forced mate in 5, which takes Black from clearly worse to decisively lost. Better was Ba6. |  |
| 6 | card/immortal/WHITE/36 | This lets you play Re1, which leaves the bishop on g1 with no safe square. From about level to clearly worse in one move: that is what this cost your opponent. Better was Qxa1+, which picks up a rook. | This lets you play Re1, leaving the bishop on g1 with no safe square. From about level to clearly worse in one move: that is what this cost your opponent. Better was Qxa1+, which picks up a rook. |  |
| 7 | card/immortal/WHITE/40 | Now you can play Nxg7+, which sets a forced mate in 5 in motion. That takes your opponent from clearly worse to decisively lost. Better was Ba6. | Now you can play Nxg7+, setting a forced mate in 5, and your opponent goes from clearly worse to decisively lost. Better was Ba6. |  |
| 8 | narr/immortal/NONE/28 | Black takes the rook on g one with the bishop. And the chance is gone. It does not come back. That's about level turning into clearly worse in a single move. | Black takes the rook on g one with the bishop, and the chance is gone; it does not come back. In a single move, about level turns into clearly worse. |  |
| 9 | card/immortal/BLACK/19 | Now you can play Nf6, which piles up on the pawn on g4: more attackers than defenders. That takes your opponent from about level to slightly worse. Better was Ba4. | Now you can play Nf6, which piles up on the pawn on g4, making more attackers than defenders. This takes your opponent from about level to slightly worse. Better was Ba4. |  |
| 10 | card/immortal/BLACK/33 | This lets you play Qxb2, which attacks the rook on a1, which is en prise. From winning to clearly better in one move: that is what this cost your opponent. Better was d4: it attacks the bishop on c5, which is en prise. | This lets you play Qxb2, which attacks the rook on a1, which is en prise. From winning to clearly better in one move: that is what this cost your opponent. Better was d4, which attacks the bishop on c5, which is en prise. |  |
| 11 | card/immortal/NONE/19 | Now Black can play Nf6, which piles up on the pawn on g4: more attackers than defenders. That takes White from about level to slightly worse. Better was Ba4. | Now Black can play Nf6, which piles up on the pawn on g4, creating more attackers than defenders. This takes White from about level to slightly worse. Better was Ba4. |  |
| 12 | card/immortal/NONE/33 | This lets Black play Qxb2, which attacks the rook on a1, which is en prise. From winning to clearly better in one move: that is what this cost White. Better was d4: it attacks the bishop on c5, which is en prise. | This lets Black play Qxb2, which attacks the rook on a1, which is en prise. From winning to clearly better in one move: that is what this cost White. Better was d4, which attacks the bishop on c5, which is en prise. |  |
| 13 | card/immortal/WHITE/19 | Now your opponent can play Nf6, which piles up on the pawn on g4: more attackers than defenders. That takes you from about level to slightly worse. Better was Ba4. | Now your opponent can play Nf6, which piles up on the pawn on g4, making more attackers than defenders. That takes you from about level to slightly worse. Better was Ba4. |  |
| 14 | card/immortal/WHITE/33 | This lets your opponent play Qxb2, which attacks the rook on a1, which is en prise. From winning to clearly better in one move: that is what this cost you. Better was d4: it attacks the bishop on c5, which is en prise. | This lets your opponent play Qxb2, which attacks the rook on a1, which is en prise. From winning to clearly better in one move: that is what this cost you. Better was d4, which attacks the bishop on c5, which is en prise. |  |
| 15 | card/immortal/NONE/25 | h5 is the only move here: the next-best option gives up real ground. It hits the queen on g6 with a pawn. | h5 is the only move here, giving up real ground and hitting the queen on g6 with a pawn. |  |
| 16 | card/immortal/NONE/41 | Nxg7+ is an only move, and nothing else keeps the position on track. It starts a forced mate in 5. | Nxg7+ is the only move, and nothing else keeps the position on track. It starts a forced mate in 5. |  |
| 17 | card/immortal/NONE/45 | Be7# was the only move that kept things on track. It is checkmate. | Be7# was the only move that kept things on track, and it is checkmate. |  |
| 18 | narr/immortal/BLACK/11 | A couple of moves later, You play bishop to c five. Instead, queen to c six, which gives nothing away. | A couple of moves later, you play bishop to c five. Instead, you play queen to c six, which gives nothing away. |  |
| 19 | narr/immortal/NONE/6 | Shortly after that, Black takes the bishop on b five with the pawn. The move was pawn to h five. | Shortly after, Black takes the bishop on b five with the pawn. The move was pawn to h five. |  |
| 20 | narr/immortal/BLACK/24 | So: you take the rook on a one with the queen, with check. And there goes a rook. | So, you take the rook on a one with the queen, with check. And there goes a rook. |  |
| 21 | card/chesscom/NONE/13 | Qb3 is the only move here: the next-best option gives up real ground. It ties the pawn on b7 to the knight on b8 with a relative pin. | Qb3 is the only move here: it ties the pawn on b7 to the knight on b8 with a relative pin. |  |
| 22 | card/chesscom/NONE/31 | Qb8+ is the only move here: the next-best option gives up real ground. It sets a forced mate in 2 in motion. | Qb8+ is the only move here, giving up real ground and setting a forced mate in 2 in motion. |  |
| 23 | card/chesscom/NONE/33 | Rd8# was the only move that kept things on track. It is checkmate. | Rd8# was the only move that kept things on track, and it is checkmate. |  |
| 24 | narr/chesscom/WHITE/24 | Two moves on, You play rook to d eight, and that is checkmate. And that's the game. You finish it off. | Two moves on, you play rook to d eight, and that's checkmate. And that's the game. You finish it off. |  |
| 25 | narr/chesscom/WHITE/6 | You answer with queen to b three. That is the toughest try in the position. | You respond with queen to b three. That is the toughest try in the position. |  |
| 26 | narr/chesscom/NONE/20 | Shortly after that, White goes for rook to d one. A deflection: the defender is pulled away from f six, and what it was guarding is left open. It doesn't win material yet, but the pressure is real. | Shortly after, White goes for the rook to d one. A deflection: the defender is pulled away from f six, and what it was guarding is left open. It doesn't win material yet, but the pressure is real. |  |
| 27 | narr/chesscom/WHITE/19 | You take the knight on d seven with the rook. It drags the defender off e seven, and once it's gone nothing holds together. Serious material, just like that. | You take the knight on d seven with the rook. It drags the defender off e seven, leaving nothing to hold together. Serious material, just like that. |  |
| 28 | card/chesscom/BLACK/20 | cxb5 is not the most precise. The position swings from losing to decisively lost for you. Better was Qb4+: in the engine's line it clears e7 so that Bxb4+ can come through. | cxb5 is not the most precise. The position swings from losing to decisively lost for you. Better was Qb4+: in the engine's line, it clears e7 so that Bxb4+ can come through. |  |
| 29 | card/chesscom/NONE/20 | cxb5 is not the most precise. The position swings from losing to decisively lost for Black. Better was Qb4+: in the engine's line it clears e7 so that Bxb4+ can come through. | cxb5 is not the most precise. The position swings from losing to decisively lost for Black. Better was Qb4+: in the engine's line, it clears e7 so that Bxb4+ can come through. |  |
| 30 | card/chesscom/WHITE/20 | cxb5 is not the most precise. The position swings from losing to decisively lost for your opponent. Better was Qb4+: in the engine's line it clears e7 so that Bxb4+ can come through. | cxb5 is not the most precise. The position swings from losing to decisively lost for your opponent. Better was Qb4+: in the engine's line, it clears e7 so that Bxb4+ can come through. |  |
| 31 | card/chesscom/NONE/3 | Nf3 is still opening theory. | Nf3 remains opening theory. |  |
| 32 | card/chesscom/NONE/6 | Bg4 is still opening theory. | Bg4 remains opening theory. |  |
| 33 | card/game01/BLACK/49 | Kb1 throws a big chunk of the position away. That takes your opponent from clearly better to about level. Better was Bxd7: it hits the queen on e8 with a bishop. | Kb1 throws a big chunk of the position away, taking your opponent from clearly better to about level. Better was Bxd7: it hits the queen on e8 with a bishop. |  |
| 34 | card/game01/NONE/49 | Kb1 throws a big chunk of the position away. That takes White from clearly better to about level. Better was Bxd7: it hits the queen on e8 with a bishop. | Kb1 throws a big chunk of the position away, taking White from clearly better to about level. Better was Bxd7: it hits the queen on e8 with a bishop. |  |
| 35 | card/game01/NONE/55 | Now Black can play Rb8, which pins the pawn on b2 to the king on b1. That takes White from about level to losing. Better was Re1. | Now Black plays Rb8, pinning the pawn on b2 to the king on b1. This takes White from about level to losing. Better was Re1. |  |
| 36 | card/game01/WHITE/49 | Kb1 throws a big chunk of the position away. That takes you from clearly better to about level. Better was Bxd7: it hits the queen on e8 with a bishop. | Kb1 throws a big chunk of the position away, taking you from clearly better to about level. Better was Bxd7: it hits the queen on e8 with a bishop. |  |
| 37 | card/game01/WHITE/55 | Now your opponent can play Rb8, which pins the pawn on b2 to the king on b1. That takes you from about level to losing. Better was Re1. | Now your opponent can play Rb8, pinning the pawn on b2 to the king on b1. That takes you from about level to losing. Better was Re1. |  |
| 38 | card/game01/BLACK/18 | This lets your opponent play hxg4, which uncovers the rook on h1, which now attacks the bishop on h5. From clearly worse to losing in one move: that is what this cost you. Better was exf3. | This lets your opponent play hxg4, which uncovers the rook on h1, which attacks the bishop on h5. From clearly worse to losing in one move: that is what this cost you. Better was exf3. |  |
| 39 | card/game01/BLACK/25 | Qxb7 lets the position slip. That takes your opponent from clearly better to slightly better. Better was c5; in the engine's line it clears c4 so that Bc4 can come through. | Qxb7 lets the position slip, taking your opponent from clearly better to slightly better. Better was c5; in the engine's line, c4 clears so that Bc4 can come through. |  |
| 40 | card/game01/BLACK/26 | Bc6 gives up real ground. The position swings from slightly worse to clearly worse for you. Better was Nc6: in the engine's line it clears b8 so that Rb8 can come through. | Bc6 gives up real ground. The position swings from slightly worse to clearly worse for you. Better was Nc6: in the engine's line, it clears b8 so that Rb8 can come through. |  |
| 41 | card/game01/NONE/18 | This lets White play hxg4, which uncovers the rook on h1, which now attacks the bishop on h5. From clearly worse to losing in one move: that is what this cost Black. Better was exf3. | This lets White play hxg4, uncovering the rook on h1 and attacking the bishop on h5. From clearly worse to losing in one move: that is what this cost Black. Better was exf3. |  |
| 42 | card/game01/NONE/25 | Qxb7 lets the position slip. That takes White from clearly better to slightly better. Better was c5; in the engine's line it clears c4 so that Bc4 can come through. | Qxb7 lets the position slip, taking White from clearly better to slightly better. Better was c5; in the engine's line, it clears c4 so that Bc4 can come through. |  |
| 43 | card/game01/NONE/26 | Bc6 gives up real ground. The position swings from slightly worse to clearly worse for Black. Better was Nc6: in the engine's line it clears b8 so that Rb8 can come through. | Bc6 gives up real ground. The position swings from slightly worse to clearly worse for Black. Better was Nc6: in the engine's line, it clears b8 so that Rb8 can come through. |  |
| 44 | card/game01/WHITE/18 | This lets you play hxg4, which uncovers the rook on h1, which now attacks the bishop on h5. From clearly worse to losing in one move: that is what this cost your opponent. Better was exf3. | This lets you play hxg4, uncovering the rook on h1 and attacking the bishop on h5. From clearly worse to losing in one move: that is what this cost your opponent. Better was exf3. |  |
| 45 | card/game01/WHITE/25 | Qxb7 lets the position slip. That takes you from clearly better to slightly better. Better was c5; in the engine's line it clears c4 so that Bc4 can come through. | Qxb7 lets the position slip, taking you from clearly better to slightly better. Better was c5; in the engine's line, c4 clears so that Bc4 can come through. |  |
| 46 | card/game01/WHITE/26 | Bc6 gives up real ground. The position swings from slightly worse to clearly worse for your opponent. Better was Nc6: in the engine's line it clears b8 so that Rb8 can come through. | Bc6 gives up real ground. The position swings from slightly worse to clearly worse for your opponent. Better was Nc6: in the engine's line, it clears b8 so that Rb8 can come through. |  |
| 47 | card/game01/BLACK/47 | The position swings from winning to clearly better for your opponent. Better was Rxh7: it keeps a winning position. | The position shifts from winning to clearly better for your opponent. Better was Rxh7: it maintains a winning position. |  |
| 48 | card/game01/NONE/47 | The position swings from winning to clearly better for White. Better was Rxh7: it keeps a winning position. | The position swings from winning to clearly better for White. Better was Rxh7: it maintains the winning position. |  |
| 49 | card/game01/WHITE/47 | The position swings from winning to clearly better for you. Better was Rxh7: it keeps a winning position. | The position swings from winning to clearly better for you. Better was Rxh7: it maintains a winning position. |  |
| 50 | card/game01/NONE/56 | Rb8 offers the knight on d7: a sacrifice the engine rates among the best moves here. This puts the pawn on b2 in an absolute pin against the king on b1. | Rb8 offers the knight on d7, a sacrifice the engine rates highly. This puts the pawn on b2 in an absolute pin against the king on b1. |  |
| 51 | card/game01/NONE/24 | exf3 was the only move that kept things on track. This picks up a piece. | exf3 was the only move that kept things on track, picking up a piece. |  |
| 52 | card/game01/NONE/53 | Bxg7 is an only move, and nothing else keeps the position on track. It hits the queen on e5 with a bishop. | Bxg7 is the only move, and nothing else keeps the position on track. It hits the queen on e5 with a bishop. |  |

## Every rejection (candidate and reason)

- `card/immortal/NONE/20` **FACTS_TERMS** ({engine=1, more attackers=1} -> {engine=1})  
  O: Nf6 matches the engine's top choice. This attacks the pawn on g4 more often than it is defended.  
  R: Nf6 matches the engine's top choice, attacking the pawn on g4 more frequently than it is defended.
- `card/immortal/NONE/31` **SHAPE_LENGTH** (13 words for 20)  
  O: Nc3 is the only move here: the next-best option gives up real ground. It attacks the undefended pawn on b5.  
  R: Nc3 is the only move here: it attacks the undefended pawn on b5.
- `card/chesscom/NONE/12` **BANNED** (allow*)  
  O: This lets White play Qb3, which pins the pawn on b7 to the knight on b8. From slightly worse to clearly worse in one move: that is what this cost Black. Better was Qf6.  
  R: This allows White to play Qb3, which pins the pawn on b7 to the knight on b8. From slightly worse to clearly worse in one move: that is what this cost Black. Better was Qf6.
- `card/chesscom/NONE/29` **FACTS_BANDS** ([] -> [winning])  
  O: Bxd7+ matches the engine's top choice. It wins the exchange.  
  R: Bxd7+ matches the engine's top choice, winning the exchange.
- `card/chesscom/NONE/32` **FACTS_OUTCOMES** ({FORCE=1} -> {})  
  O: Nxb8 was forced: the only legal move.  
  R: Nxb8 was the only legal move.
- `card/game01/NONE/21` **BANNED** (allow*)  
  O: This lets Black play Bd7, which hits the queen on a4 with a bishop. Better was Nxe4.  
  R: This allows Black to play Bd7, which hits the queen on a4 with a bishop. Better was Nxe4.
- `card/game01/NONE/30` **BANNED** (allow*)  
  O: This lets White play Bh3, which attacks the queen on c8 with a bishop. Better was Bd7.  
  R: This allows White to play Bh3, which attacks the queen on c8 with a bishop. Better was Bd7.
- `card/game01/NONE/51` **FACTS_TERMS** ({sacrifice=2} -> {sacrifice=1})  
  O: Bh6 is a sacrifice: it offers the bishop on h6.  
  R: Bh6 is a sacrifice, offering the bishop on h6.
- `card/game01/NONE/57` **FACTS_OUTCOMES** ({DEFEND=1} -> {})  
  O: b3 is the engine's first choice. In the engine's line, b3 deflects the bishop on c8 away from guarding d7.  
  R: b3 is the engine's first choice. In the engine's line, b3 deflects the bishop on c8 away from d7.
- `card/game01/NONE/65` **FACTS_OUTCOMES** ({FORCE=1} -> {})  
  O: Ke1 was forced: the only legal move.  
  R: Ke1 was the only legal move.
- `card/game01/WHITE/30` **BANNED** (allow*)  
  O: This lets you play Bh3, which attacks the queen on c8 with a bishop. Better was Bd7.  
  R: This allows you to play Bh3, which attacks the queen on c8 with a bishop. Better was Bd7.
- `card/game01/BLACK/30` **BANNED** (allow*)  
  O: This lets your opponent play Bh3, which attacks the queen on c8 with a bishop. Better was Bd7.  
  R: This allows your opponent to play Bh3, which attacks the queen on c8 with a bishop. Better was Bd7.
- `narr/scholars/BLACK/0` **FACTS_PLAYERS** ([WHITE, BLACK, WHITE, YOU, BLACK] -> [WHITE, BLACK, WHITE])  
  O: White has the white pieces. Black is on the other side. White finished it with mate in 4 moves. You were Black here, so that's the side we're watching. The result is not the story. One move, around move 3, decides everything, and it is not the obvious one.  
  R: White has the white pieces. Black is on the other side. White finished it with mate in 4 moves. The result is not the story. One move, around move 3, decides everything, and it is not the obvious one.
- `narr/chesscom/NONE/0` **FACTS_OUTCOMES** ({} -> {FORCE=1})  
  O: MorphyFan1857 has the white pieces, rated 1487. DukeAndCount is on the other side, rated 1502. White finished it with mate in 17 moves. It's closer than the result makes it look. One moment, around move 9, decides it.  
  R: MorphyFan1857 has the white pieces, rated 1487. DukeAndCount is on the other side, rated 1502. White finished it with a forced mate in 17 moves. It's closer than the result makes it look. Around move 9, White's strategy decided the game.
- `narr/chesscom/NONE/12` **BANNED** (behind)  
  O: White plays queen to b three. That's a relative pin: the piece on b seven is tied to something worth more behind it. That's a pawn in the bank.  
  R: White plays queen to b three, creating a relative pin: the pawn behind the queen is tied to something worth more behind it.
- `narr/chesscom/NONE/16` **SHAPE_LENGTH** (18 words for 26)  
  O: White takes the pawn on b five with the knight. And that is a brilliant move. It gives material away and the engine loves it anyway.  
  R: White takes the pawn on b five with the knight, giving material away and the engine loves it.
- `narr/chesscom/BLACK/0` **FACTS_PLAYERS** ([WHITE, YOU, BLACK] -> [WHITE, YOU, BLACK, WHITE])  
  O: MorphyFan1857 has the white pieces, rated 1487. DukeAndCount is on the other side, rated 1502. White finished it with mate in 17 moves. You were Black here, so that's the side we're watching. It's closer than the result makes it look. One moment, around move 9, decides it.  
  R: MorphyFan1857 has the white pieces, rated 1487. DukeAndCount is on the other side, rated 1502. White finished it with a forced mate in 17 moves. You were Black here, so that's the side we're watching. It's closer than the result makes it look. Around move 9, White's strategy decides the outcome.
- `narr/chesscom/BLACK/11` **SHAPE_LENGTH** (14 words for 24)  
  O: You play knight to f six. The moment passes, and that is that. You were slightly worse before that. Now you are clearly worse.  
  R: You play knight to f six. The moment passes, and you are clearly worse.
- `narr/chesscom/BLACK/28` **FACTS_PLAYERS** ([YOU] -> [YOU, OPPONENT])  
  O: You handed over a tactic four times in this game — moves that were fine in themselves but let something in. Before you commit, ask one question: what does this let them do next?  
  R: You handed over four tactics in this game — moves that were fine in themselves but let something in. Before committing, ask yourself what this lets your opponent do next.
- `narr/immortal/NONE/2` **FACTS_HYPOTHETICAL** (1 -> 0)  
  O: Black plays knight to h five. Instead, pawn to c six, which gives nothing away.  
  R: Black plays knight to h five and pawn to c six, which gives nothing away.
- `narr/immortal/NONE/44` **SHAPE_LENGTH** (17 words for 32)  
  O: Black's mistakes ran from move 7 to move 20, so they're spread right across the game rather than bunched at the end. That usually means the checks are missing, not the stamina.  
  R: Black's mistakes span from move 7 to move 20, indicating a lack of checks rather than stamina.
- `narr/immortal/WHITE/25` **SHAPE_LENGTH** (11 words for 20)  
  O: You answer with king to e two. Forced — the king was in check, so nothing else was even legal.  
  R: You respond with king to e two, forced by the check.
- `narr/immortal/WHITE/44` **SHAPE_LENGTH** (16 words for 32)  
  O: Your mistakes ran from move 8 to move 18, so they're spread right across the game rather than bunched at the end. That usually means the checks are missing, not the stamina.  
  R: Your mistakes span moves 8 to 18, indicating a lack of checks rather than stamina issues.
- `narr/immortal/BLACK/43` **FACTS_NUMBERS** ([3, 4] -> [3])  
  O: Four things to take out of this game. You walked past three hanging pieces in this game. They were on b five, f five and g four. Before every single move, sweep the board for pieces with no defender. Theirs first, then yours. There's a clean textbook hanging piece waiting in the game report if you want to see the pattern on its own.  
  R: Three pieces were hanging in this game: b five, f five, and g four. Before every move, sweep the board for pieces with no defender. Theirs first, then yours. There's a clean textbook hanging piece waiting in the game report if you want to see the pattern on its own.
- `narr/game01/NONE/0` **FACTS_OUTCOMES** ({} -> {FORCE=1})  
  O: Fouchon has the white pieces, rated 2035. Sonarmind is on the other side, rated 2043. Black finished it with mate in 33 moves. The result is not the story. One move, around move 28, decides everything, and it is not the obvious one.  
  R: Fouchon has the white pieces, rated 2035, and Sonarmind is on the other side, rated 2043. Black finished it with a forced mate in 33 moves. The result is not the story; one move, around move 28, decides everything, and it is not the obvious one.
- `narr/game01/NONE/8` **FACTS_TERMS** ({undefended=2} -> {})  
  O: White plays pawn to g four. The bishop on h five is loose, with no defender at all.  
  R: White plays pawn to g four, leaving the bishop on h five without any defender.
- `narr/game01/NONE/24` **FACTS_BANDS** ([decisively winning, winning] -> [winning, decisively winning])  
  O: White plays pawn to c five. White has gone from decisively winning to winning.  
  R: White plays pawn to c five. White has gone from winning to decisively winning.
- `narr/game01/NONE/28` **FACTS_PIECE_SQUARES** ([] -> [bishop@f5])  
  O: White plays bishop to f five. That's a relative pin: the piece on d seven is tied to something worth more behind it.  
  R: White places the bishop on f five, creating a relative pin: the piece on d seven is tied to something worth more behind it.
- `narr/game01/NONE/32` **SHAPE_LENGTH** (6 words for 11)  
  O: Black plays queen to e eight. The evaluation moves against Black.  
  R: Black plays queen to e eight.
- `narr/game01/NONE/56` **FACTS_NUMBERS** ([28] -> [28, 28])  
  O: That right there is where this game was decided. Nothing else in this game swings the evaluation like move 28. White went from about level to losing on one move.  
  R: That move 28 was the deciding factor in this game. Nothing else in this game swung the evaluation like move 28. White went from about level to losing on one move.
- `narr/game01/WHITE/51` **FACTS_NEGATION** (1 -> 0)  
  O: And then you play queen to c two. No fireworks. It just keeps everything pointed at the same weakness.  
  R: And then you play queen to c two. It keeps everything pointed at the same weakness.
- `narr/game01/BLACK/67` **SHAPE_LENGTH** (16 words for 32)  
  O: Your mistakes ran from move 7 to move 23, so they're spread right across the game rather than bunched at the end. That usually means the checks are missing, not the stamina.  
  R: Your mistakes span moves 7 to 23, indicating a lack of checks rather than stamina issues.
- `narr/byrne_fischer/NONE/9` **SHAPE_LENGTH** (4 words for 7)  
  O: So add it up. White wins material.  
  R: So, White wins material.
- `narr/byrne_fischer/NONE/14` **FACTS_PIECES** ([knight, knight, pawn] -> [knight, pawn, pawn])  
  O: Black takes the knight on c three with the knight. Removing the defender: once it is gone, e four has nothing holding it. So that's a pawn.  
  R: Black takes the knight on c three with the pawn. Removing the defender: once it is gone, e four has nothing holding it. So that's a pawn.
- `narr/byrne_fischer/NONE/43` **FACTS_OUTCOMES** ({CAPTURE=1, DEFEND=2} -> {CAPTURE=1, DEFEND=1})  
  O: Black takes the pawn on a two with the rook. A deflection: the defender is pulled away from f two, and what it was guarding is left open. A pawn, just like that.  
  R: Black takes the pawn on a two with the rook. A deflection: the defender is pulled away from f two, leaving it open. A pawn, just like that.
- `narr/byrne_fischer/WHITE/9` **SHAPE_LENGTH** (4 words for 7)  
  O: So add it up. You win material.  
  R: So, you win material.
- `narr/byrne_fischer/BLACK/37` **FACTS_SQUARES** ([c4, e2] -> [c4])  
  O: You take the bishop on c four with the bishop, with check. Take the defender first, and then e two is left without its guard. It doesn't win material yet, but the pressure is real.  
  R: You take the bishop on c four with the bishop, checking. E two is left without its guard, and it doesn't win material yet, but the pressure is real.
- `narr/byrne_fischer/BLACK/48` **SHAPE_LENGTH** (8 words for 14)  
  O: You play knight to g three, with check. The evaluation moves in your favour.  
  R: You play knight to g three, with check.
