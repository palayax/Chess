# Rephrase measurement: qwen2.5-1.5b-instruct-q4_k_m, prompt v1

Written by `RephraseMeasurementDumpTest` from `docs/audit/rephrase/raw_qwen2.5-1.5b-instruct-q4_k_m.jsonl` (host run of
`scripts/rephrase_measure.py`, llama.cpp b11190 CPU, greedy, n_ctx 2048, prefix in the KV cache).
Verdicts by the Kotlin `ClaimChecker`; `scripts/audit_commentary.py rephrase` re-checks them independently.
Corpus: every distinct card text of the three audited games (no side, White, Black) and every eligible
narration beat of the five pacing games at the Normal pace (three sides).

## Verdicts (design §5.5: rejection = REJECT / all; bar <= 25 % cards, <= 35 % narration)

| Surface | Texts | Accepted | Unchanged | Rejected | Rejection rate | Unchanged rate |
|---|---|---|---|---|---|---|
| card | 190 | 74 | 104 | 12 | 6.3 % | 54.7 % |
| narration | 388 | 195 | 103 | 90 | 23.2 % | 26.5 % |

### Rejections by reason

| Reason | Cards | Narration |
|---|---|---|
| BANNED | 7 | 2 |
| FACTS_BANDS | 1 | 2 |
| FACTS_HYPOTHETICAL | 0 | 10 |
| FACTS_NAMES | 0 | 1 |
| FACTS_NEGATION | 0 | 10 |
| FACTS_NUMBERS | 0 | 6 |
| FACTS_ORDER | 1 | 0 |
| FACTS_OUTCOMES | 1 | 11 |
| FACTS_PIECES | 0 | 1 |
| FACTS_PIECE_SQUARES | 0 | 3 |
| FACTS_PLAYERS | 0 | 4 |
| FACTS_SQUARES | 0 | 1 |
| FACTS_TERMS | 1 | 7 |
| SHAPE_LENGTH | 1 | 32 |

## Latency on the host (reference only; the Pixel 8 decides)

| Surface | Median ms | p90 ms | Median prompt tokens (after the cached prefix) | Median output tokens | Prefill tok/s | Decode tok/s |
|---|---|---|---|---|---|---|
| card | 3842 | 7253 | 75 | 24 | 45.3 | 11.7 |
| narration | 3582 | 6792 | 69 | 25 | 46.8 | 12.1 |

Key moments per game (the cards of class BLUNDER/MISTAKE/MISS/BRILLIANT/GREAT, no side, at most 5): total time

- chesscom: 5 cards, 19.4 s
- game01: 5 cards, 32.5 s
- immortal: 5 cards, 21.2 s

## The narrated story after the post-pass (Normal pace, 169 wpm, no side; design §6.2's cap: <= 1.10 x and the §9.7 budget)

| Game | Beats reworded | Story before | Story after | Change | Budget |
|---|---|---|---|---|---|
| scholars | 2 of 5 | 52.5 s | 52.5 s | -0.0 s | 60.0 s |
| chesscom | 14 of 30 | 308.8 s | 302.9 s | -5.9 s | 358.0 s |
| immortal | 23 of 47 | 420.1 s | 406.6 s | -13.5 s | 442.0 s |
| game01 | 28 of 70 | 546.7 s | 540.6 s | -6.1 s | 582.0 s |
| byrne_fischer | 32 of 59 | 535.4 s | 524.9 s | -10.5 s | 694.0 s |

## Quality sample: original vs accepted rewrite (owner: fill the A/B column, prefer = R or O)

| # | Id | Original | Rewrite | Prefer |
|---|---|---|---|---|
| 1 | card/immortal/BLACK/36 | This lets your opponent play Re1, which leaves the bishop on g1 with no safe square. From about level to clearly worse in one move: that is what this cost you. Better was Qxa1+, which picks up a rook. | This lets your opponent play Re1, leaving the bishop on g1 with no safe square. From about level to clearly worse in one move: that is what this cost you. Better was Qxa1+, which picks up a rook. |  |
| 2 | card/immortal/BLACK/40 | Now your opponent can play Nxg7+, which sets a forced mate in 5 in motion. That takes you from clearly worse to decisively lost. Better was Ba6. | Now your opponent can play Nxg7+, setting a forced mate in 5, and you go from clearly worse to decisively lost. Better was Ba6. |  |
| 3 | card/immortal/NONE/22 | Now White can play h4, which attacks the undefended queen on g5. That takes Black from about level to slightly worse. Better was h5, which attacks the pawn on g4 more often than it is defended. | Now White can play h4, attacking the undefended queen on g5, taking Black from about level to slightly worse. Better was h5, which attacks the pawn on g4 more often than it is defended. |  |
| 4 | card/immortal/NONE/36 | This lets White play Re1, which leaves the bishop on g1 with no safe square. From about level to clearly worse in one move: that is what this cost Black. Better was Qxa1+, which picks up a rook. | This lets White play Re1, leaving the bishop on g1 with no safe square. From about level to clearly worse in one move: that is what this cost Black. Better was Qxa1+, which picks up a rook. |  |
| 5 | card/immortal/NONE/40 | Now White can play Nxg7+, which sets a forced mate in 5 in motion. That takes Black from clearly worse to decisively lost. Better was Ba6. | Now White can play Nxg7+, setting a forced mate in 5, which takes Black from clearly worse to decisively lost. Better was Ba6. |  |
| 6 | card/immortal/WHITE/22 | Now you can play h4, which attacks the undefended queen on g5. That takes your opponent from about level to slightly worse. Better was h5, which attacks the pawn on g4 more often than it is defended. | Now you can play h4, which attacks the undefended queen on g5; that takes your opponent from about level to slightly worse. Better was h5, which attacks the pawn on g4 more often than it is defended. |  |
| 7 | card/immortal/WHITE/36 | This lets you play Re1, which leaves the bishop on g1 with no safe square. From about level to clearly worse in one move: that is what this cost your opponent. Better was Qxa1+, which picks up a rook. | This lets you play Re1, leaving the bishop on g1 with no safe square. From about level to clearly worse in one move: that is what this cost your opponent. Better was Qxa1+, which picks up a rook. |  |
| 8 | card/immortal/WHITE/40 | Now you can play Nxg7+, which sets a forced mate in 5 in motion. That takes your opponent from clearly worse to decisively lost. Better was Ba6. | Now you can play Nxg7+, setting a forced mate in 5, and your opponent goes from clearly worse to decisively lost. Better was Ba6. |  |
| 9 | narr/immortal/BLACK/28 | You take the rook on g one with the bishop. And the chance is gone. It does not come back. That's about level turning into clearly worse in a single move. | You take the rook on g one with the bishop, and the chance is gone; it does not come back. In a single move, about level turns into clearly worse. |  |
| 10 | narr/immortal/BLACK/37 | You play knight to a six. The moment passes, and that is that. You have gone from clearly worse to decisively lost. | You play knight to a six. The moment passes, and that is it. You have gone from clearly worse to decisively lost. |  |
| 11 | narr/immortal/NONE/28 | Black takes the rook on g one with the bishop. And the chance is gone. It does not come back. That's about level turning into clearly worse in a single move. | Black takes the rook on g one with the bishop, and the chance is gone; it does not come back. In a single move, about level turns into clearly worse. |  |
| 12 | narr/immortal/WHITE/21 | You play bishop to d six. And with that, the window shuts. You were clearly better before that. Now you are about level. | You play bishop to d six, and the window closes. You were clearly better before that. Now you are about level. |  |
| 13 | card/immortal/BLACK/19 | Now you can play Nf6, which piles up on the pawn on g4: more attackers than defenders. That takes your opponent from about level to slightly worse. Better was Ba4. | Now you can play Nf6, which piles up on the pawn on g4, creating more attackers than defenders. This takes your opponent from about level to slightly worse. Better was Ba4. |  |
| 14 | card/immortal/BLACK/33 | This lets you play Qxb2, which attacks the rook on a1, which is en prise. From winning to clearly better in one move: that is what this cost your opponent. Better was d4: it attacks the bishop on c5, which is en prise. | This lets you play Qxb2, which attacks the rook on a1, which is en prise. From winning to clearly better in one move: that is what this cost your opponent. Better was d4, which attacks the bishop on c5, which is en prise. |  |
| 15 | card/immortal/NONE/19 | Now Black can play Nf6, which piles up on the pawn on g4: more attackers than defenders. That takes White from about level to slightly worse. Better was Ba4. | Now Black can play Nf6, which piles up on the pawn on g4, making it more attackers than defenders. That takes White from about level to slightly worse. Better was Ba4. |  |
| 16 | card/immortal/NONE/33 | This lets Black play Qxb2, which attacks the rook on a1, which is en prise. From winning to clearly better in one move: that is what this cost White. Better was d4: it attacks the bishop on c5, which is en prise. | This lets Black play Qxb2, attacking the rook on a1, which is en prise. From winning to clearly better in one move: that is what this cost White. Better was d4, which attacks the bishop on c5, which is en prise. |  |
| 17 | card/immortal/WHITE/19 | Now your opponent can play Nf6, which piles up on the pawn on g4: more attackers than defenders. That takes you from about level to slightly worse. Better was Ba4. | Now your opponent can play Nf6, which piles up on the pawn on g4, making it more attackers than defenders. That takes you from about level to slightly worse. Better was Ba4. |  |
| 18 | card/immortal/WHITE/33 | This lets your opponent play Qxb2, which attacks the rook on a1, which is en prise. From winning to clearly better in one move: that is what this cost you. Better was d4: it attacks the bishop on c5, which is en prise. | This lets your opponent play Qxb2, which attacks the rook on a1, which is en prise. From winning to clearly better in one move: that is what this cost you. Better was d4, which attacks the bishop on c5, which is en prise. |  |
| 19 | card/immortal/NONE/25 | h5 is the only move here: the next-best option gives up real ground. It hits the queen on g6 with a pawn. | h5 is the only move here: the next-best option gives up real ground, hitting the queen on g6 with a pawn. |  |
| 20 | card/immortal/NONE/31 | Nc3 is the only move here: the next-best option gives up real ground. It attacks the undefended pawn on b5. | Nc3 is the only move here: the next-best option gives up real ground, attacking the undefended pawn on b5. |  |
| 21 | narr/chesscom/BLACK/14 | Careful. You play pawn to b five. That's clearly worse turning into losing in a single move. King to d eight was the move. | Careful. You play pawn to b five, which is clearly worse and turning into losing in a single move. King to d eight was the move. |  |
| 22 | card/chesscom/BLACK/18 | b5 goes wrong. From clearly worse to losing in one move: that is what this cost you. Better was Kd8. | b5 goes wrong; from clearly worse to losing in one move. That is what this cost you. Better was Kd8. |  |
| 23 | card/chesscom/NONE/18 | b5 goes wrong. From clearly worse to losing in one move: that is what this cost Black. Better was Kd8. | b5 goes wrong; from clearly worse to losing in one move. That is what this cost Black. Better was Kd8. |  |
| 24 | card/chesscom/WHITE/18 | b5 goes wrong. From clearly worse to losing in one move: that is what this cost your opponent. Better was Kd8. | b5 goes wrong; from clearly worse to losing in one move. That is what this cost your opponent. Better was Kd8. |  |
| 25 | card/chesscom/NONE/33 | Rd8# was the only move that kept things on track. It is checkmate. | Rd8# was the only move that kept things on track; it is checkmate. |  |
| 26 | narr/chesscom/NONE/15 | Stop there, because that is the moment the game changed hands. Nothing else in this game swings the evaluation like move 9. Black went from clearly worse to losing on one move. | That is the moment the game changed hands. Nothing else in this game swings the evaluation like move 9. Black went from clearly worse to losing on one move. |  |
| 27 | narr/chesscom/BLACK/24 | Two moves on, White plays rook to d eight, and that is checkmate. And that's the game. And that's you on the receiving end of it. | Two moves on, White plays rook to d eight, and that is checkmate. The game is over, and you are on the receiving end. |  |
| 28 | narr/chesscom/NONE/10 | But that is the line that never was. In the real game, this went on the board — | But that was the line that never was. In the real game, this went on the board —. |  |
| 29 | narr/chesscom/NONE/24 | Two moves on, White plays rook to d eight, and that is checkmate. And that's the game. White wins. | Two moves on, White plays rook to d eight, and that is checkmate. The game is over; White wins. |  |
| 30 | narr/chesscom/WHITE/24 | Two moves on, You play rook to d eight, and that is checkmate. And that's the game. You finish it off. | Two moves on, you play the rook to d eight, and that is checkmate. The game is over. |  |
| 31 | narr/chesscom/WHITE/3 | Two moves on, You play bishop to c four. The move was queen to b three. | Two moves on, you play bishop to c four. The move was queen to b three. |  |
| 32 | narr/chesscom/BLACK/7 | Next, you play knight to d seven. That is the quiet move, and it is the one that makes the rest work. | Next, you play knight to d seven. That is the quiet move, and it makes the rest work. |  |
| 33 | narr/chesscom/NONE/6 | White answers with queen to b three. That is the toughest try in the position. | White answers with the queen to b three, which is the toughest try in the position. |  |
| 34 | narr/chesscom/NONE/7 | Next, Black plays knight to d seven. That is the quiet move, and it is the one that makes the rest work. | Next, Black plays knight to d seven. That is the quiet move, and it makes the rest work. |  |
| 35 | narr/chesscom/NONE/9 | And there it is. That is as far as the line goes. | And that is as far as the line goes. |  |
| 36 | narr/chesscom/WHITE/6 | You answer with queen to b three. That is the toughest try in the position. | You answer with the queen to b three. That is the toughest try in the position. |  |
| 37 | narr/chesscom/NONE/12 | White plays queen to b three. That's a relative pin: the piece on b seven is tied to something worth more behind it. That's a pawn in the bank. | White plays the queen to b three, creating a relative pin: the piece on b seven is tied to something worth more behind it, making the pawn in the bank. |  |
| 38 | narr/chesscom/NONE/20 | Shortly after that, White goes for rook to d one. A deflection: the defender is pulled away from f six, and what it was guarding is left open. It doesn't win material yet, but the pressure is real. | Shortly after that, White goes for the rook to d one, creating a deflection where the defender is pulled away from f six, leaving what it was guarding open. The pressure is real, but it doesn't win material yet. |  |
| 39 | narr/chesscom/NONE/22 | White takes the rook on d seven with the bishop, with check. It clears the square for the piece coming in behind it. It doesn't win material yet, but the pressure is real. | White takes the rook on d seven with the bishop, putting check on the opponent. It clears the square for the piece coming in behind it. It doesn't win material yet, but the pressure is real. |  |
| 40 | narr/chesscom/WHITE/12 | You play queen to b three. That's a relative pin: the piece on b seven is tied to something worth more behind it. That's a pawn in the bank. | You play queen to b three, creating a relative pin: the piece on b seven is tied to something worth more behind it, making it a pawn in the bank. |  |
| 41 | card/game01/BLACK/49 | Kb1 throws a big chunk of the position away. That takes your opponent from clearly better to about level. Better was Bxd7: it hits the queen on e8 with a bishop. | Kb1 throws a big chunk of the position away, taking your opponent from clearly better to about level. Better was Bxd7, which hits the queen on e8 with a bishop. |  |
| 42 | card/game01/NONE/49 | Kb1 throws a big chunk of the position away. That takes White from clearly better to about level. Better was Bxd7: it hits the queen on e8 with a bishop. | Kb1 throws a big chunk of the position away, taking White from clearly better to about level. Better was Bxd7, which hits the queen on e8 with a bishop. |  |
| 43 | card/game01/NONE/55 | Now Black can play Rb8, which pins the pawn on b2 to the king on b1. That takes White from about level to losing. Better was Re1. | Now Black can play Rb8, pinning the pawn on b2 to the king on b1, taking White from about level to losing. Better was Re1. |  |
| 44 | card/game01/WHITE/49 | Kb1 throws a big chunk of the position away. That takes you from clearly better to about level. Better was Bxd7: it hits the queen on e8 with a bishop. | Kb1 throws a big chunk of the position away, taking you from clearly better to about level. Better was Bxd7, which hits the queen on e8 with a bishop. |  |
| 45 | narr/game01/NONE/45 | White plays king to b one. And the chance is gone. It does not come back. White was clearly better before that. Now White is about level. | White plays king to b one, and the chance is gone; it does not come back. White was clearly better before that. Now White is about level. |  |
| 46 | narr/game01/NONE/55 | White takes the pawn on c six with the queen. The moment passes, and that is that. That's about level turning into losing in a single move. | White takes the pawn on c six with the queen, and the moment passes; that is that. In a single move, about level turns into losing. |  |
| 47 | narr/game01/WHITE/45 | You play king to b one. And the chance is gone. It does not come back. You were clearly better before that. Now you are about level. | You play king to b one, and the chance is gone; it does not come back. You were clearly better before that. Now you are about level. |  |
| 48 | narr/game01/WHITE/55 | You take the pawn on c six with the queen. The moment passes, and that is that. That's about level turning into losing in a single move. | You take the pawn on c six with the queen, and the moment passes; that is that. In a single move, about level turns into losing. |  |
| 49 | card/game01/BLACK/18 | This lets your opponent play hxg4, which uncovers the rook on h1, which now attacks the bishop on h5. From clearly worse to losing in one move: that is what this cost you. Better was exf3. | This lets your opponent play hxg4, uncovering the rook on h1, which attacks the bishop on h5. From clearly worse to losing in one move: that is what this cost you. Better was exf3. |  |
| 50 | card/game01/NONE/18 | This lets White play hxg4, which uncovers the rook on h1, which now attacks the bishop on h5. From clearly worse to losing in one move: that is what this cost Black. Better was exf3. | This lets White play hxg4, uncovering the rook on h1, which now attacks the bishop on h5. From clearly worse to losing in one move: that is what this cost Black. Better was exf3. |  |
| 51 | card/game01/NONE/26 | Bc6 gives up real ground. The position swings from slightly worse to clearly worse for Black. Better was Nc6: in the engine's line it clears b8 so that Rb8 can come through. | Bc6 gives up real ground. The position swings from slightly worse to clearly worse for Black. Better was Nc6: in the engine's line, it clears b8 so that Rb8 can come through. |  |
| 52 | card/game01/WHITE/18 | This lets you play hxg4, which uncovers the rook on h1, which now attacks the bishop on h5. From clearly worse to losing in one move: that is what this cost your opponent. Better was exf3. | This lets you play hxg4, uncovering the rook on h1, which now attacks the bishop on h5. From clearly worse to losing in one move: that is what this cost your opponent. Better was exf3. |  |
| 53 | card/game01/WHITE/25 | Qxb7 lets the position slip. That takes you from clearly better to slightly better. Better was c5; in the engine's line it clears c4 so that Bc4 can come through. | Qxb7 lets the position slip, taking you from clearly better to slightly better. Better was c5; in the engine's line, c4 clears so that Bc4 can come through. |  |
| 54 | card/game01/WHITE/47 | The position swings from winning to clearly better for you. Better was Rxh7: it keeps a winning position. | The position swings from winning to clearly better for you. Better was Rxh7, which keeps a winning position. |  |
| 55 | card/game01/NONE/56 | Rb8 offers the knight on d7: a sacrifice the engine rates among the best moves here. This puts the pawn on b2 in an absolute pin against the king on b1. | Rb8 offers the knight on d7, a sacrifice the engine rates among the best moves here. This puts the pawn on b2 in an absolute pin against the king on b1. |  |
| 56 | card/game01/NONE/24 | exf3 was the only move that kept things on track. This picks up a piece. | exf3 was the only move that kept things on track, picking up a piece. |  |
| 57 | narr/game01/WHITE/56 | That right there is where this game was decided. Nothing else in this game swings the evaluation like move 28. You went from about level to losing on one move. | That's where this game was decided. Nothing else in this game swings the evaluation like move 28. You went from about level to losing on one move. |  |
| 58 | narr/game01/BLACK/15 | You play bishop to c six. Instead, knight to c six, which gives nothing away. | You play bishop to c six, but instead, knight to c six, which gives nothing away. |  |
| 59 | narr/game01/BLACK/5 | Two moves on, You play pawn to e four. Instead, pawn takes the pawn on d four, which gives nothing away. | Two moves on, you play pawn to e four. Instead, pawn takes the pawn on d four, which gives nothing away. |  |
| 60 | narr/game01/BLACK/64 | A couple of moves later, You play queen to c one, and that is checkmate. That's it. Game over. You finish it off. | A couple of moves later, you play queen to c one, and that is checkmate. Game over. You finish it off. |  |

## Every rejection (candidate and reason)

- `card/immortal/NONE/32` **FACTS_ORDER** ([d4, ATTACK, c5, Qc6] -> [ATTACK, c5, d4, Qc6])  
  O: This hands White d4, which hits the loose bishop on c5. The position swings from clearly worse to losing for Black. Better was Qc6.  
  R: This hits the loose bishop on c5, giving White d4. The position swings from clearly worse to losing for Black. Better was Qc6.
- `card/chesscom/NONE/29` **FACTS_BANDS** ([] -> [winning])  
  O: Bxd7+ matches the engine's top choice. It wins the exchange.  
  R: Bxd7+ matches the engine's top choice, winning the exchange.
- `card/game01/NONE/25` **BANNED** (allow*)  
  O: Qxb7 lets the position slip. That takes White from clearly better to slightly better. Better was c5; in the engine's line it clears c4 so that Bc4 can come through.  
  R: Qxb7 lets the position slip, taking White from clearly better to slightly better. Better was c5; in the engine's line, c4 clears, allowing Bc4 to come through.
- `card/game01/NONE/28` **BANNED** (allow*)  
  O: Qc8 gives back ground. That takes Black from clearly worse to losing. Better was Na6: in the engine's line it clears b8 so that Rb8 can come through.  
  R: Qc8 gives back ground, taking Black from clearly worse to losing. Better was Na6: in the engine's line, it clears b8, allowing Rb8 to come through.
- `card/game01/NONE/51` **FACTS_TERMS** ({sacrifice=2} -> {sacrifice=1})  
  O: Bh6 is a sacrifice: it offers the bishop on h6.  
  R: Bh6 is a sacrifice, offering the bishop on h6.
- `card/game01/NONE/57` **FACTS_OUTCOMES** ({DEFEND=1} -> {})  
  O: b3 is the engine's first choice. In the engine's line, b3 deflects the bishop on c8 away from guarding d7.  
  R: b3 is the engine's first choice. In the engine's line, b3 deflects the bishop on c8 away from d7.
- `card/game01/NONE/58` **SHAPE_LENGTH** (12 words for 20)  
  O: Bb7 is the only move here: the next-best option gives up real ground. This attacks the undefended queen on c6.  
  R: Bb7 is the only move here, attacking the undefended queen on c6.
- `card/game01/WHITE/26` **BANNED** (allow*)  
  O: Bc6 gives up real ground. The position swings from slightly worse to clearly worse for your opponent. Better was Nc6: in the engine's line it clears b8 so that Rb8 can come through.  
  R: Bc6 gives up real ground. The position swings from slightly worse to clearly worse for your opponent. Better was Nc6: in the engine's line, it clears b8, allowing Rb8 to come through.
- `card/game01/WHITE/28` **BANNED** (allow*)  
  O: Qc8 gives back ground. That takes your opponent from clearly worse to losing. Better was Na6: in the engine's line it clears b8 so that Rb8 can come through.  
  R: Qc8 gives back ground, and your opponent goes from clearly worse to losing. Better was Na6: in the engine's line, it clears b8, allowing Rb8 to come through.
- `card/game01/BLACK/25` **BANNED** (allow*)  
  O: Qxb7 lets the position slip. That takes your opponent from clearly better to slightly better. Better was c5; in the engine's line it clears c4 so that Bc4 can come through.  
  R: Qxb7 lets the position slip, taking your opponent from clearly better to slightly better. Better was c5; in the engine's line, c4 clears, allowing Bc4 to come through.
- `card/game01/BLACK/26` **BANNED** (allow*)  
  O: Bc6 gives up real ground. The position swings from slightly worse to clearly worse for you. Better was Nc6: in the engine's line it clears b8 so that Rb8 can come through.  
  R: Bc6 gives up real ground. The position swings from slightly worse to clearly worse for you. Better was Nc6: in the engine's line, it clears b8, allowing Rb8 to come through.
- `card/game01/BLACK/28` **BANNED** (allow*)  
  O: Qc8 gives back ground. That takes you from clearly worse to losing. Better was Na6: in the engine's line it clears b8 so that Rb8 can come through.  
  R: Qc8 gives back the ground, and that takes you from clearly worse to losing. Better was Na6: in the engine's line, it clears b8, allowing Rb8 to come through.
- `narr/scholars/NONE/1` **FACTS_HYPOTHETICAL** (1 -> 0)  
  O: Black plays knight to f six. Pawn to g six was the move.  
  R: Black plays knight to f six and pawn to g six.
- `narr/scholars/WHITE/0` **FACTS_PLAYERS** ([WHITE, BLACK, WHITE, YOU, WHITE] -> [WHITE, BLACK, WHITE])  
  O: White has the white pieces. Black is on the other side. White finished it with mate in 4 moves. You were White here, so that's the side we're watching. The result is not the story. One move, around move 3, decides everything, and it is not the obvious one.  
  R: White has the white pieces, and Black is on the other side. White finished the game with mate in 4 moves. The result is not the story; one move, around move 3, decides everything, and it is not the obvious one.
- `narr/scholars/BLACK/0` **FACTS_PLAYERS** ([WHITE, BLACK, WHITE, YOU, BLACK] -> [WHITE, BLACK, WHITE])  
  O: White has the white pieces. Black is on the other side. White finished it with mate in 4 moves. You were Black here, so that's the side we're watching. The result is not the story. One move, around move 3, decides everything, and it is not the obvious one.  
  R: White has the white pieces, and Black is on the other side. White finished it with mate in 4 moves. The result is not the story; one move, around move 3, decides everything, and it is not the obvious one.
- `narr/scholars/BLACK/4` **SHAPE_LENGTH** (28 words for 47)  
  O: So what do you take away from this? The one thing you left on the board was a hanging piece at move 3. The one that hurt was on h five. Before every single move, sweep the board for pieces with no defender. Theirs first, then yours.  
  R: So what do you take away from this? The one piece you left on the board at move 3 was hanging at h five. Theirs first, then yours.
- `narr/chesscom/NONE/2` **FACTS_PIECES** ([bishop, knight, knight] -> [bishop, knight])  
  O: Black takes the knight on f three with the bishop. Instead, knight to c six, which gives nothing away.  
  R: Black takes the knight on f three with the bishop, then moves it to c six, which gives nothing away.
- `narr/chesscom/NONE/5` **FACTS_NEGATION** (1 -> 0)  
  O: So: Black plays queen to f six. No fireworks. It just keeps everything pointed at the same weakness.  
  R: So, Black plays queen to f six. It just keeps everything pointed at the same weakness.
- `narr/chesscom/NONE/11` **SHAPE_LENGTH** (15 words for 24)  
  O: Black plays knight to f six. The moment passes, and that is that. Black was slightly worse before that. Now Black is clearly worse.  
  R: Black plays knight to f six, and the moment passes; now Black is clearly worse.
- `narr/chesscom/NONE/14` **FACTS_BANDS** ([clearly worse, losing] -> [losing])  
  O: Careful. Black plays pawn to b five. That's clearly worse turning into losing in a single move. King to d eight was the move.  
  R: Careful. Black plays pawn to b five, turning into losing in a single move. King to d eight was the move.
- `narr/chesscom/NONE/17` **FACTS_HYPOTHETICAL** (1 -> 0)  
  O: Black takes back on b five with the pawn. Instead, queen to b four, with check, which gives nothing away.  
  R: Black takes back on b five with the pawn, then moves the queen to b four, with check, which gives nothing away.
- `narr/chesscom/NONE/19` **SHAPE_LENGTH** (20 words for 29)  
  O: White takes the knight on d seven with the rook. It drags the defender off e seven, and once it's gone nothing holds together. Serious material, just like that.  
  R: White takes the knight on d seven with the rook, dragging the defender off e seven, and nothing holds together.
- `narr/chesscom/NONE/23` **FACTS_PIECE_SQUARES** ([] -> [queen@b8])  
  O: A couple of moves later, White finds queen to b eight, with check. It's a forced mate. The king has nowhere to run.  
  R: A couple of moves later, White finds the queen on b eight with check. It's a forced mate, and the king has nowhere to run.
- `narr/chesscom/NONE/25` **FACTS_NUMBERS** ([1662, 2, 2, 2668, 82.7, 97.1] -> [2, 2, 82.7, 97.1])  
  O: Let's put numbers on it. MorphyFan1857 finished on 97.1 percent accuracy, DukeAndCount on 82.7 percent. That's play at roughly 2668 and 1662. MorphyFan1857 had one inaccuracy. DukeAndCount had two mistakes and two inaccuracies.  
  R: Let's put numbers on it: MorphyFan1857 finished at 97.1 percent accuracy, and DukeAndCount at 82.7 percent. MorphyFan1857 had one inaccuracy, while DukeAndCount had two mistakes and two inaccuracies.
- `narr/chesscom/NONE/26` **SHAPE_LENGTH** (42 words for 63)  
  O: Here's what to actually work on. The one thing Black left on the board was a clearance at move 10. The one that hurt was on b four. Drill twenty of these on a puzzle trainer. Pattern recognition is the only real fix. There's a clean textbook clearance waiting in the game report if you want to see the pattern on its own.  
  R: Here's what to work on: the one thing Black left on the board was a clearance at move 10. The one that hurt was on b four. Drill twenty of these on a puzzle trainer. Pattern recognition is the only real fix.
- `narr/chesscom/WHITE/19` **SHAPE_LENGTH** (20 words for 29)  
  O: You take the knight on d seven with the rook. It drags the defender off e seven, and once it's gone nothing holds together. Serious material, just like that.  
  R: You take the knight on d seven with the rook, dragging the defender off e seven, and nothing holds together.
- `narr/chesscom/WHITE/20` **FACTS_OUTCOMES** ({DEFEND=2, WIN=1} -> {DEFEND=1, WIN=1})  
  O: Shortly after that, you go for rook to d one. A deflection: the defender is pulled away from f six, and what it was guarding is left open. It doesn't win material yet, but the pressure is real.  
  R: Shortly after that, you move the rook to d one, creating a deflection where the defender is pulled away from f six, leaving the square open. The pressure is real, but it doesn't yet win material.
- `narr/chesscom/WHITE/22` **BANNED** (set up)  
  O: You take the rook on d seven with the bishop, with check. It clears the square for the piece coming in behind it. It doesn't win material yet, but the pressure is real.  
  R: You take the rook on d seven with the bishop, setting up a check. It clears the square for the piece coming in behind it. The pressure is real, but it doesn't yet win material.
- `narr/chesscom/BLACK/5` **FACTS_NEGATION** (1 -> 0)  
  O: So: you play queen to f six. No fireworks. It just keeps everything pointed at the same weakness.  
  R: So: you play queen to f six. It just keeps everything pointed at the same weakness.
- `narr/chesscom/BLACK/11` **SHAPE_LENGTH** (15 words for 24)  
  O: You play knight to f six. The moment passes, and that is that. You were slightly worse before that. Now you are clearly worse.  
  R: You play knight to f six. The moment passes, and you are now clearly worse.
- `narr/chesscom/BLACK/15` **SHAPE_LENGTH** (10 words for 32)  
  O: Stop there, because that is the moment the game changed hands. Nothing else in this game swings the evaluation like move 9. You went from clearly worse to losing on one move.  
  R: You went from clearly worse to losing on one move.
- `narr/chesscom/BLACK/17` **FACTS_HYPOTHETICAL** (1 -> 0)  
  O: You take back on b five with the pawn. Instead, queen to b four, with check, which gives nothing away.  
  R: You take back on b five with the pawn, then the queen moves to b four, with check, which gives nothing away.
- `narr/chesscom/BLACK/26` **SHAPE_LENGTH** (30 words for 63)  
  O: Here's what to actually work on. The one thing you left on the board was a clearance at move 10. The one that hurt was on b four. Drill twenty of these on a puzzle trainer. Pattern recognition is the only real fix. There's a clean textbook clearance waiting in the game report if you want to see the pattern on its own.  
  R: Here's what to work on: the clearance at move 10, which hurt on b four. Practice twenty of these on a puzzle trainer. Pattern recognition is the only real fix.
- `narr/immortal/NONE/5` **FACTS_HYPOTHETICAL** (1 -> 0)  
  O: White plays pawn to g four. Instead, bishop to a four, which gives nothing away.  
  R: White plays pawn to g four, but bishop to a four, which gives nothing away.
- `narr/immortal/NONE/8` **FACTS_OUTCOMES** ({DEFEND=1} -> {})  
  O: White plays queen to f three. The pawn on f four has nothing defending it.  
  R: White plays queen to f three, leaving the pawn on f four undefended.
- `narr/immortal/NONE/9` **FACTS_HYPOTHETICAL** (1 -> 0)  
  O: Black plays knight to g eight. Bishop to b seven was the move.  
  R: Black plays knight to g eight, bishop to b seven.
- `narr/immortal/NONE/11` **FACTS_HYPOTHETICAL** (1 -> 0)  
  O: A couple of moves later, Black plays bishop to c five. Instead, queen to c six, which gives nothing away.  
  R: A couple of moves later, Black plays bishop to c five, then queen to c six, which gives nothing away.
- `narr/immortal/NONE/15` **SHAPE_LENGTH** (9 words for 22)  
  O: Here we go. White plays rook to e one. Nothing is captured, and nothing has to be. The threat does the work.  
  R: Here we go. White plays rook to e one.
- `narr/immortal/NONE/17` **FACTS_NEGATION** (1 -> 0)  
  O: Next, White plays queen to g three. No fireworks. It just keeps everything pointed at the same weakness.  
  R: Next, White plays queen to g three. It just keeps everything pointed at the same weakness.
- `narr/immortal/NONE/21` **SHAPE_LENGTH** (15 words for 23)  
  O: White plays bishop to d six. And with that, the window shuts. White was clearly better before that. Now White is about level.  
  R: White plays bishop to d six, and the window closes. Now White is about level.
- `narr/immortal/NONE/27` **SHAPE_LENGTH** (4 words for 10)  
  O: So, back to reality. What actually happened was this —  
  R: So, back to reality.
- `narr/immortal/NONE/33` **SHAPE_LENGTH** (14 words for 21)  
  O: And then Black plays king to d eight. Nothing is captured, and nothing has to be. The threat does the work.  
  R: Black plays king to d eight, and nothing is captured, making the threat work.
- `narr/immortal/NONE/34` **SHAPE_LENGTH** (10 words for 18)  
  O: White takes the bishop on a six with the knight. That is the toughest try in the position.  
  R: White takes the bishop on a six with the knight.
- `narr/immortal/NONE/35` **FACTS_HYPOTHETICAL** (2 -> 1)  
  O: Now look at the end of that line. That is as far as the line goes.  
  R: Now look at the end of that line; it goes as far as it goes.
- `narr/immortal/NONE/37` **FACTS_BANDS** ([clearly worse, decisively lost] -> [decisively lost])  
  O: Black plays knight to a six. The moment passes, and that is that. Black has gone from clearly worse to decisively lost.  
  R: Black's knight to a six is a moment that passes, and Black is now decisively lost.
- `narr/immortal/NONE/44` **SHAPE_LENGTH** (15 words for 32)  
  O: Black's mistakes ran from move 7 to move 20, so they're spread right across the game rather than bunched at the end. That usually means the checks are missing, not the stamina.  
  R: Black's mistakes span moves 7 to 20, indicating a lack of checks rather than stamina.
- `narr/immortal/WHITE/8` **FACTS_OUTCOMES** ({DEFEND=1} -> {})  
  O: You play queen to f three. The pawn on f four has nothing defending it.  
  R: You play queen to f three, leaving the pawn on f four undefended.
- `narr/immortal/WHITE/17` **FACTS_NEGATION** (1 -> 0)  
  O: Next, you play queen to g three. No fireworks. It just keeps everything pointed at the same weakness.  
  R: Next, you play queen to g three. It keeps everything pointed at the same weakness.
- `narr/immortal/WHITE/40` **FACTS_NEGATION** (1 -> 0)  
  O: Shortly after that, you go for queen to f six, with check. It's a forced mate. The king has nowhere to run.  
  R: Shortly after that, you play queen to f six with check, setting a forced mate for the king.
- `narr/immortal/BLACK/16` **SHAPE_LENGTH** (7 words for 15)  
  O: You answer with bishop to b seven. That is the toughest try in the position.  
  R: You answer with bishop to b seven.
- `narr/immortal/BLACK/26` **SHAPE_LENGTH** (8 words for 12)  
  O: So add it up. You come out of it a rook up.  
  R: So, you end up with a rook up.
- `narr/immortal/BLACK/43` **FACTS_NUMBERS** ([3, 4] -> [3])  
  O: Four things to take out of this game. You walked past three hanging pieces in this game. They were on b five, f five and g four. Before every single move, sweep the board for pieces with no defender. Theirs first, then yours. There's a clean textbook hanging piece waiting in the game report if you want to see the pattern on its own.  
  R: Three hanging pieces were on b five, f five, and g four. Before every move, sweep the board for pieces with no defender. Theirs first, then yours. There's a clean textbook hanging piece waiting in the game report if you want to see the pattern on its own.
- `narr/game01/NONE/0` **FACTS_NAMES** ([] -> [Sonarmind])  
  O: Fouchon has the white pieces, rated 2035. Sonarmind is on the other side, rated 2043. Black finished it with mate in 33 moves. The result is not the story. One move, around move 28, decides everything, and it is not the obvious one.  
  R: Fouchon has the white pieces, rated 2035, and Sonarmind is on the other side, rated 2043. Black finished the game with a mate in 33 moves. The result is not the story; one move, around move 28, decides everything, and it is not the obvious one.
- `narr/game01/NONE/1` **FACTS_OUTCOMES** ({DEFEND=1, LOSE=1} -> {DEFEND=1})  
  O: Straight into the Scandinavian Defense: Marshall Variation. Black brings the queen out early and accepts losing a move in exchange for a clean, simple structure. Theory runs out around move 4.  
  R: Straight into the Scandinavian Defense: Marshall Variation. Black brings the queen out early and accepts a losing move in exchange for a clean, simple structure. Theory runs out around move 4.
- `narr/game01/NONE/3` **SHAPE_LENGTH** (6 words for 13)  
  O: White plays bishop to e three. Bishop to e two was the move.  
  R: White plays bishop to e three.
- `narr/game01/NONE/7` **SHAPE_LENGTH** (6 words for 13)  
  O: Black plays bishop to h five. The move was bishop to d seven.  
  R: Black plays bishop to h five.
- `narr/game01/NONE/8` **FACTS_TERMS** ({undefended=2} -> {})  
  O: White plays pawn to g four. The bishop on h five is loose, with no defender at all.  
  R: White plays pawn to g four, leaving the bishop on h five without a defender.
- `narr/game01/NONE/12` **SHAPE_LENGTH** (7 words for 13)  
  O: White plays queen to b three. The move was queen to c two.  
  R: White plays the queen to b three.
- `narr/game01/NONE/15` **FACTS_HYPOTHETICAL** (1 -> 0)  
  O: Black plays bishop to c six. Instead, knight to c six, which gives nothing away.  
  R: Black plays bishop to c six, but knight to c six, which gives nothing away.
- `narr/game01/NONE/23` **SHAPE_LENGTH** (11 words for 16)  
  O: Black plays bishop to f six. That's losing turning into decisively lost in a single move.  
  R: Black plays bishop to f six, losing in a single move.
- `narr/game01/NONE/30` **SHAPE_LENGTH** (6 words for 13)  
  O: Black plays bishop to g seven. The move was bishop to a six.  
  R: Black plays bishop to g seven.
- `narr/game01/NONE/31` **FACTS_HYPOTHETICAL** (1 -> 0)  
  O: White plays bishop to h three. Rook takes the pawn on h seven was the move.  
  R: White plays the bishop to h three. The rook takes the pawn on h seven.
- `narr/game01/NONE/37` **SHAPE_LENGTH** (8 words for 21)  
  O: And then White plays knight to g three. Nothing is captured, and nothing has to be. The threat does the work.  
  R: And then White plays knight to g three.
- `narr/game01/NONE/51` **FACTS_NEGATION** (1 -> 0)  
  O: And then White plays queen to c two. No fireworks. It just keeps everything pointed at the same weakness.  
  R: And then White plays queen to c two. It keeps everything pointed at the same weakness.
- `narr/game01/NONE/56` **FACTS_NUMBERS** ([28] -> [28, 28])  
  O: That right there is where this game was decided. Nothing else in this game swings the evaluation like move 28. White went from about level to losing on one move.  
  R: That move 28 is where the game was decided. Nothing else in this game swings the evaluation like move 28. White went from about level to losing on one move.
- `narr/game01/NONE/58` **FACTS_OUTCOMES** ({DEFEND=2} -> {DEFEND=1})  
  O: White plays pawn to b three. A deflection: the defender is pulled away from d seven, and what it was guarding is left open.  
  R: White plays pawn to b three, creating a deflection where the defender is pulled away from d seven, leaving the square open.
- `narr/game01/NONE/59` **FACTS_PIECE_SQUARES** ([queen@c6] -> [bishop@b7, queen@c6])  
  O: Black finds bishop to b seven. The queen on c six is en prise: nothing defends it. That's a whole queen in the bank.  
  R: Black finds the bishop on b seven, and the queen on c six is en prise: nothing defends it. That's a whole queen in the bank.
- `narr/game01/NONE/65` **FACTS_NUMBERS** ([1192, 1800, 2, 3, 4, 5, 72.3, 85.0] -> [2, 3, 4, 5, 72.3, 85.0])  
  O: So where did that leave us? Fouchon finished on 72.3 percent accuracy, Sonarmind on 85.0 percent. That's play at roughly 1192 and 1800. Fouchon had two blunders, one mistake, one missed win and five inaccuracies. Sonarmind had three mistakes and four inaccuracies.  
  R: Fouchon finished with 72.3 percent accuracy, while Sonarmind had 85.0 percent. Fouchon made two blunders, one mistake, and one missed win, with five inaccuracies. Sonarmind had three mistakes and four inaccuracies.
- `narr/game01/NONE/67` **SHAPE_LENGTH** (15 words for 32)  
  O: White's mistakes ran from move 6 to move 30, so they're spread right across the game rather than bunched at the end. That usually means the checks are missing, not the stamina.  
  R: White's mistakes span moves 6 to 30, indicating a lack of checks rather than stamina.
- `narr/game01/WHITE/0` **FACTS_PLAYERS** ([BLACK, YOU, WHITE] -> [BLACK])  
  O: Fouchon has the white pieces, rated 2035. Sonarmind is on the other side, rated 2043. Black finished it with mate in 33 moves. You were White here, so that's the side we're watching. The result is not the story. One move, around move 28, decides everything, and it is not the obvious one.  
  R: Fouchon has the white pieces, rated 2035, and Sonarmind is on the other side, rated 2043. Black finished it with mate in 33 moves. The result is not the story; one move, around move 28, decides everything, and it is not the obvious one.
- `narr/game01/WHITE/8` **FACTS_TERMS** ({undefended=2} -> {})  
  O: You play pawn to g four. The bishop on h five is loose, with no defender at all.  
  R: You play pawn to g four, leaving the bishop on h five without a defender.
- `narr/game01/WHITE/12` **SHAPE_LENGTH** (6 words for 13)  
  O: You play queen to b three. The move was queen to c two.  
  R: You play queen to b three.
- `narr/game01/WHITE/31` **FACTS_HYPOTHETICAL** (1 -> 0)  
  O: You play bishop to h three. Rook takes the pawn on h seven was the move.  
  R: You play bishop to h three. The rook takes the pawn on h seven.
- `narr/game01/WHITE/39` **FACTS_NEGATION** (1 -> 0)  
  O: Then you play queen to h four. No fireworks. It just keeps everything pointed at the same weakness.  
  R: Then you play queen to h four. It just keeps everything pointed at the same weakness.
- `narr/game01/WHITE/51` **FACTS_NEGATION** (1 -> 0)  
  O: And then you play queen to c two. No fireworks. It just keeps everything pointed at the same weakness.  
  R: And then you play queen to c two. It keeps everything pointed at the same weakness.
- `narr/game01/WHITE/58` **FACTS_OUTCOMES** ({DEFEND=2} -> {DEFEND=1})  
  O: You play pawn to b three. A deflection: the defender is pulled away from d seven, and what it was guarding is left open.  
  R: You play pawn to b three, creating a deflection where the defender is pulled away from d seven, leaving the square open.
- `narr/game01/WHITE/67` **SHAPE_LENGTH** (15 words for 32)  
  O: Your mistakes ran from move 6 to move 30, so they're spread right across the game rather than bunched at the end. That usually means the checks are missing, not the stamina.  
  R: Your mistakes span moves 6 to 30, indicating a lack of checks rather than stamina.
- `narr/game01/BLACK/0` **FACTS_PLAYERS** ([BLACK, YOU, BLACK] -> [BLACK])  
  O: Fouchon has the white pieces, rated 2035. Sonarmind is on the other side, rated 2043. Black finished it with mate in 33 moves. You were Black here, so that's the side we're watching. The result is not the story. One move, around move 28, decides everything, and it is not the obvious one.  
  R: Fouchon has the white pieces, rated 2035. Sonarmind is on the other side, rated 2043. Black finished it with mate in 33 moves. The result is not the story; one move, around move 28, decides everything, and it is not the obvious one.
- `narr/game01/BLACK/30` **SHAPE_LENGTH** (6 words for 13)  
  O: You play bishop to g seven. The move was bishop to a six.  
  R: You play bishop to g seven.
- `narr/game01/BLACK/40` **FACTS_NEGATION** (1 -> 0)  
  O: You answer with pawn to f five. That is the engine's own choice, so nothing better exists.  
  R: You answer with pawn to f five, which is the engine's own choice.
- `narr/game01/BLACK/66` **FACTS_NUMBERS** ([2] -> [])  
  O: Here's what to actually work on. You walked past two hanging pieces in this game. They were on e three and c three. Before every single move, sweep the board for pieces with no defender. Theirs first, then yours. There's a clean textbook hanging piece waiting in the game report if you want to see the pattern on its own.  
  R: Here's what to work on: sweep the board for pieces with no defender before every move. The pieces on e three and c three were hanging. There's a clean textbook hanging piece waiting in the game report if you want to see the pattern on its own.
- `narr/byrne_fischer/NONE/5` **SHAPE_LENGTH** (9 words for 20)  
  O: It starts here. White plays bishop to e two. No fireworks. It just keeps everything pointed at the same weakness.  
  R: It starts here. White plays bishop to e two.
- `narr/byrne_fischer/NONE/6` **SHAPE_LENGTH** (10 words for 18)  
  O: Black takes the knight on f three with the bishop. That is the toughest try in the position.  
  R: Black takes the knight on f three with the bishop.
- `narr/byrne_fischer/NONE/9` **SHAPE_LENGTH** (4 words for 7)  
  O: So add it up. White wins material.  
  R: So, White wins material.
- `narr/byrne_fischer/NONE/12` **FACTS_OUTCOMES** ({DEFEND=2} -> {DEFEND=1})  
  O: Black plays knight to a four. A deflection: the defender is pulled away from e four, and what it was guarding is left open. A pawn, just like that. And that is a brilliant move. It gives material away and the engine loves it anyway.  
  R: Black plays knight to a four, creating a deflection that pulls the defender away from e four, leaving it open. The pawn is just like that, and it's a brilliant move. It gives material away, and the engine loves it anyway.
- `narr/byrne_fischer/NONE/13` **FACTS_PIECE_SQUARES** ([] -> [queen@a3])  
  O: White finds queen to a three. That's a relative pin: the piece on e seven is tied to something worth more behind it. It doesn't win material yet, but the pressure is real.  
  R: White finds the queen on a three. That's a relative pin: the piece on e seven is tied to something worth more behind it. It doesn't win material yet, but the pressure is real.
- `narr/byrne_fischer/NONE/17` **SHAPE_LENGTH** (14 words for 24)  
  O: Here we go. Black plays queen to d five. That is the quiet move, and it is the one that makes the rest work.  
  R: Black plays queen to d five, a quiet move that makes the rest work.
- `narr/byrne_fischer/NONE/24` **FACTS_TERMS** ({clearance=1, material=1} -> {material=1})  
  O: White plays bishop to c four. It clears the square for the piece coming in behind it. It doesn't win material yet, but the pressure is real.  
  R: White plays the bishop to c four, clearing the square for the piece coming in behind it. The pressure is real, but it doesn't win material yet.
- `narr/byrne_fischer/NONE/27` **SHAPE_LENGTH** (22 words for 33)  
  O: Two moves on, Black finds bishop to e six. It drags the defender off c five, and once it's gone nothing holds together. It doesn't win material yet, but the pressure is real.  
  R: Two moves on, Black finds bishop to e six, dragging the defender off c five, and once it's gone, nothing holds together.
- `narr/byrne_fischer/NONE/36` **FACTS_NUMBERS** ([18] -> [18, 18])  
  O: That right there is where this game was decided. Nothing else in this game swings the evaluation like move 18. White went from clearly worse to losing on one move.  
  R: That move 18 is where this game was decided. Nothing else in this game swings the evaluation like move 18. White went from clearly worse to losing on one move.
- `narr/byrne_fischer/NONE/37` **FACTS_OUTCOMES** ({CAPTURE=2, DEFEND=2, WIN=1} -> {CAPTURE=1, DEFEND=2, WIN=1})  
  O: Black takes the bishop on c four with the bishop, with check. Take the defender first, and then e two is left without its guard. It doesn't win material yet, but the pressure is real.  
  R: Black takes the bishop on c four with the bishop, with check. The defender is taken first, leaving e two without its guard. It doesn't win material yet, but the pressure is real.
- `narr/byrne_fischer/NONE/39` **FACTS_TERMS** ({check=1, engine=1, material=1, zwischenzug=1} -> {check=1, engine=1, zwischenzug=1})  
  O: Black takes the pawn on d four with the knight, with check. The in-between move comes first. The capture is not going anywhere. That's a piece in the bank. And that is a brilliant move. It gives material away and the engine loves it anyway.  
  R: Black takes the pawn on d four with the knight, with check. The in-between move comes first, and the capture is not going anywhere. That's a piece in the bank. And that is a brilliant move, as the engine loves it anyway.
- `narr/byrne_fischer/NONE/43` **FACTS_OUTCOMES** ({CAPTURE=1, DEFEND=2} -> {CAPTURE=1, DEFEND=1})  
  O: Black takes the pawn on a two with the rook. A deflection: the defender is pulled away from f two, and what it was guarding is left open. A pawn, just like that.  
  R: Black takes the pawn on a two with the rook, creating a deflection where the defender is pulled away from f two, leaving the square open. It's just like that pawn.
- `narr/byrne_fischer/WHITE/5` **SHAPE_LENGTH** (9 words for 20)  
  O: It starts here. You play bishop to e two. No fireworks. It just keeps everything pointed at the same weakness.  
  R: It starts here. You play bishop to e two.
- `narr/byrne_fischer/WHITE/9` **FACTS_TERMS** ({material=1} -> {material=2})  
  O: So add it up. You win material.  
  R: So add up the material. You win material.
- `narr/byrne_fischer/WHITE/24` **FACTS_TERMS** ({clearance=1, material=1} -> {material=1})  
  O: You play bishop to c four. It clears the square for the piece coming in behind it. It doesn't win material yet, but the pressure is real.  
  R: You play the bishop to c four, clearing the square for the piece coming in behind it. The pressure is real, but it doesn't win material yet.
- `narr/byrne_fischer/WHITE/36` **BANNED** (decisive)  
  O: That right there is where this game was decided. Nothing else in this game swings the evaluation like move 18. You went from clearly worse to losing on one move.  
  R: That move 18 was the decisive moment in this game. Nothing else in this game swings the evaluation like move 18. You went from clearly worse to losing on one move.
- `narr/byrne_fischer/BLACK/8` **FACTS_NEGATION** (1 -> 0)  
  O: You answer with the knight from f six to d seven. That is the engine's own choice, so nothing better exists.  
  R: You answer with the knight from f six to d seven, which is the engine's own choice.
- `narr/byrne_fischer/BLACK/12` **FACTS_OUTCOMES** ({DEFEND=2} -> {DEFEND=1})  
  O: You play knight to a four. A deflection: the defender is pulled away from e four, and what it was guarding is left open. A pawn, just like that. And that is a brilliant move. It gives material away and the engine loves it anyway.  
  R: You play knight to a four, creating a deflection that pulls the defender away from e four, leaving it open. A pawn, just like that. This is a brilliant move, giving material away and the engine loves it anyway.
- `narr/byrne_fischer/BLACK/27` **FACTS_OUTCOMES** ({DEFEND=2, WIN=1} -> {DEFEND=1, WIN=1})  
  O: Two moves on, you find bishop to e six. It drags the defender off c five, and once it's gone nothing holds together. It doesn't win material yet, but the pressure is real.  
  R: Two moves on, you find the bishop to e six, dragging the defender off c five. The pressure is real, but it doesn't yet win material.
- `narr/byrne_fischer/BLACK/37` **FACTS_SQUARES** ([c4, e2] -> [c4])  
  O: You take the bishop on c four with the bishop, with check. Take the defender first, and then e two is left without its guard. It doesn't win material yet, but the pressure is real.  
  R: You take the bishop on c four with the bishop, putting check. E two is left without its guard, and it doesn't win material yet, but the pressure is real.
- `narr/byrne_fischer/BLACK/39` **FACTS_TERMS** ({check=1, engine=1, material=1, zwischenzug=1} -> {check=1, engine=1, zwischenzug=1})  
  O: You take the pawn on d four with the knight, with check. The in-between move comes first. The capture is not going anywhere. That's a piece in the bank. And that is a brilliant move. It gives material away and the engine loves it anyway.  
  R: You take the pawn on d four with the knight, with check. The in-between move comes first, and the capture is not going anywhere. That's a piece in the bank. And the engine loves it anyway.
