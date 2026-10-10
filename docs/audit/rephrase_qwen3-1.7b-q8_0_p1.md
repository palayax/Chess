# Rephrase measurement: qwen3-1.7b-q8_0, prompt v1

Written by `RephraseMeasurementDumpTest` from `docs/audit/rephrase/raw_qwen3-1.7b-q8_0.jsonl` (host run of
`scripts/rephrase_measure.py`, llama.cpp b11190 CPU, greedy, n_ctx 2048, prefix in the KV cache).
Verdicts by the Kotlin `ClaimChecker`; `scripts/audit_commentary.py rephrase` re-checks them independently.
Corpus: every distinct card text of the three audited games (no side, White, Black) and every eligible
narration beat of the five pacing games at the Normal pace (three sides).
Texts of this run that today's generator no longer writes (V4 changed three connectives after the run): 1.

## Verdicts (design §5.5: rejection = REJECT / all; bar <= 25 % cards, <= 35 % narration)

| Surface | Texts | Accepted | Unchanged | Rejected | Rejection rate | Unchanged rate |
|---|---|---|---|---|---|---|
| card | 190 | 39 | 137 | 14 | 7.4 % | 72.1 % |
| narration | 97 | 41 | 48 | 8 | 8.2 % | 49.5 % |

### Rejections by reason

| Reason | Cards | Narration |
|---|---|---|
| BANNED | 12 | 0 |
| FACTS_NUMBERS | 0 | 4 |
| FACTS_PIECE_SQUARES | 0 | 2 |
| FACTS_PLAYERS | 0 | 1 |
| FACTS_TERMS | 2 | 0 |
| SHAPE_LENGTH | 0 | 1 |

## Latency on the host (reference only; the Pixel 8 decides)

| Surface | Median ms | p90 ms | Median prompt tokens (after the cached prefix) | Median output tokens | Prefill tok/s | Decode tok/s |
|---|---|---|---|---|---|---|
| card | 8731 | 17743 | 79 | 25 | 23.8 | 5.0 |
| narration | 10226 | 24390 | 73 | 25 | 20.3 | 4.7 |

Key moments per game (the cards of class BLUNDER/MISTAKE/MISS/BRILLIANT/GREAT, no side, at most 5): total time

- chesscom: 5 cards, 38.9 s
- game01: 5 cards, 78.2 s
- immortal: 5 cards, 53.4 s

## Quality sample: original vs accepted rewrite (owner: fill the A/B column, prefer = R or O)

| # | Id | Original | Rewrite | Prefer |
|---|---|---|---|---|
| 1 | card/immortal/BLACK/40 | Now your opponent can play Nxg7+, which sets a forced mate in 5 in motion. That takes you from clearly worse to decisively lost. Better was Ba6. | Your opponent can now play Nxg7+, setting a forced mate in 5 in motion, and you go from clearly worse to decisively lost. Better was Ba6. |  |
| 2 | card/immortal/NONE/35 | This hands Black Qxa1+, which wins a rook. The position swings from clearly better to about level for White. Better was Re1. | Black's Qxa1+ wins a rook. The position swings from clearly better to about level for White. Better was Re1. |  |
| 3 | card/immortal/NONE/40 | Now White can play Nxg7+, which sets a forced mate in 5 in motion. That takes Black from clearly worse to decisively lost. Better was Ba6. | White can now answer with Nxg7+, setting a forced mate in 5 in motion, and Black goes from clearly worse to decisively lost. Better was Ba6. |  |
| 4 | narr/immortal/NONE/28 | Black takes the rook on g one with the bishop. And the chance is gone. It does not come back. That's about level turning into clearly worse in a single move. | Black's bishop takes the rook on g one, and the chance is gone; it does not come back. In a single move, about level turns into clearly worse. |  |
| 5 | card/immortal/BLACK/19 | Now you can play Nf6, which piles up on the pawn on g4: more attackers than defenders. That takes your opponent from about level to slightly worse. Better was Ba4. | Now you can play Nf6, which piles up on the pawn on g4, creating more attackers than defenders. That takes your opponent from about level to slightly worse. Better was Ba4. |  |
| 6 | card/immortal/NONE/17 | Nf5 is an only move, and nothing else keeps the position on track. | Nf5 is the only move, and nothing else keeps the position on track. |  |
| 7 | card/immortal/NONE/23 | h4 is an only move, and nothing else keeps the position on track. It hits the loose queen on g5. | h4 is the only move, and nothing else keeps the position on track. It hits the loose queen on g5. |  |
| 8 | card/immortal/NONE/41 | Nxg7+ is an only move, and nothing else keeps the position on track. It starts a forced mate in 5. | Nxg7+ is the only move, and nothing else keeps the position on track. It starts a forced mate in 5. |  |
| 9 | narr/immortal/BLACK/11 | A couple of moves later, You play bishop to c five. Instead, queen to c six, which gives nothing away. | A couple of moves later, you play bishop to c five. Instead, queen to c six, which gives nothing away. |  |
| 10 | narr/immortal/NONE/36 | Back in the real game, though, that got played instead — | Back in the real game, though, that got played instead. |  |
| 11 | narr/immortal/BLACK/24 | So: you take the rook on a one with the queen, with check. And there goes a rook. | You take the rook on a one with the queen, with check. And there goes a rook. |  |
| 12 | narr/immortal/BLACK/33 | And then you play king to d eight. Nothing is captured, and nothing has to be. The threat does the work. | You play the king to d eight. Nothing is captured, and nothing has to be. The threat does the work. |  |
| 13 | narr/immortal/NONE/18 | Black answers with knight to c six. That is the engine's own choice, so nothing better exists. | Black answers with the knight to c six. That is the engine's own choice, so nothing better exists. |  |
| 14 | narr/immortal/NONE/24 | So: Black takes the rook on a one with the queen, with check. And there goes a rook. | Black takes the rook on a one with the queen, with check. And there goes a rook. |  |
| 15 | narr/immortal/NONE/32 | White answers with knight to c seven, with check. That is the best defence on offer. | White answers with the knight to c seven, with check. That is the best defence on offer. |  |
| 16 | narr/immortal/WHITE/25 | You answer with king to e two. Forced — the king was in check, so nothing else was even legal. | You answer with the king to e two. Forced — the king was in check, so nothing else was even legal. |  |
| 17 | narr/immortal/NONE/40 | Shortly after that, White goes for queen to f six, with check. It's a forced mate. The king has nowhere to run. | White plays queen to f six with check, resulting in a forced mate. The king has nowhere to run. |  |
| 18 | card/immortal/BLACK/28 | Now your opponent can play Nc3; in the engine's line it deflects the queen on g5 away from guarding f4. Better was Bb7. | Your opponent can now play Nc3, and in the engine's line, it deflected the queen on g5 away from guarding f4. Better was Bb7. |  |
| 19 | card/immortal/NONE/28 | Now White can play Nc3; in the engine's line it deflects the queen on g5 away from guarding f4. Better was Bb7. | White can now play Nc3; in the engine's line, it deflected the queen on g5 away from guarding f4. Better was Bb7. |  |
| 20 | card/immortal/WHITE/28 | Now you can play Nc3; in the engine's line it deflects the queen on g5 away from guarding f4. Better was Bb7. | Now you can play Nc3; in the engine's line, it deflected the queen on g5 away from guarding f4. Better was Bb7. |  |
| 21 | narr/chesscom/WHITE/6 | You answer with queen to b three. That is the toughest try in the position. | You answer with the queen to b three. That is the toughest try in the position. |  |
| 22 | narr/chesscom/NONE/20 | Shortly after that, White goes for rook to d one. A deflection: the defender is pulled away from f six, and what it was guarding is left open. It doesn't win material yet, but the pressure is real. | Shortly after that, White plays rook to d one. A deflection: the defender is pulled away from f six, and what it was guarding is left open. It doesn't win material yet, but the pressure is real. |  |
| 23 | narr/chesscom/WHITE/19 | You take the knight on d seven with the rook. It drags the defender off e seven, and once it's gone nothing holds together. Serious material, just like that. | You take the knight on d seven with the rook. It drags the defender off e seven, and once it's gone, nothing holds together. Serious material, just like that. |  |
| 24 | card/chesscom/BLACK/20 | cxb5 is not the most precise. The position swings from losing to decisively lost for you. Better was Qb4+: in the engine's line it clears e7 so that Bxb4+ can come through. | cxb5 is not the most precise. The position swings from losing to decisively lost for you. Better was Qb4+: in the engine's line, it clears e7 so that Bxb4+ can come through. |  |
| 25 | card/chesscom/NONE/20 | cxb5 is not the most precise. The position swings from losing to decisively lost for Black. Better was Qb4+: in the engine's line it clears e7 so that Bxb4+ can come through. | cxb5 is not the most precise. The position swings from losing to decisively lost for Black. Better was Qb4+: in the engine's line, it clears e7 so that Bxb4+ can come through. |  |
| 26 | card/chesscom/WHITE/20 | cxb5 is not the most precise. The position swings from losing to decisively lost for your opponent. Better was Qb4+: in the engine's line it clears e7 so that Bxb4+ can come through. | cxb5 is not the most precise. The position swings from losing to decisively lost for your opponent. Better was Qb4+: in the engine's line, it clears e7 so that Bxb4+ can come through. |  |
| 27 | card/chesscom/NONE/17 | Bg5 is nearly the engine's top choice. In the engine's line, Bg5 deflects the king on e8 away from guarding f7. | Bg5 is nearly the engine's top choice. In the engine's line, Bg5 deflected the king on e8 away from guarding f7. |  |
| 28 | card/chesscom/NONE/27 | Rd1 is the engine's first choice. In the engine's line, Rd1 deflects the queen on e7 away from guarding f6. | Rd1 is the engine's first choice. In the engine's line, Rd1 deflected the queen on e7 away from guarding f6. |  |
| 29 | card/chesscom/NONE/29 | Bxd7+ matches the engine's top choice. It wins the exchange. | Bxd7+ matches the engine's top choice and wins the exchange. |  |
| 30 | narr/chesscom/NONE/16 | White takes the pawn on b five with the knight. And that is a brilliant move. It gives material away and the engine loves it anyway. | White takes the pawn on b five with the knight. And that is a brilliant move. It gives material away, and the engine loves it anyway. |  |
| 31 | card/game01/BLACK/49 | Kb1 throws a big chunk of the position away. That takes your opponent from clearly better to about level. Better was Bxd7: it hits the queen on e8 with a bishop. | Kb1 throws a big chunk of the position away, and your opponent goes from clearly better to about level. Better was Bxd7: it hits the queen on e8 with a bishop. |  |
| 32 | card/game01/NONE/49 | Kb1 throws a big chunk of the position away. That takes White from clearly better to about level. Better was Bxd7: it hits the queen on e8 with a bishop. | Kb1 throws a big chunk of the position away, and White goes from clearly better to about level. Better was Bxd7: it hits the queen on e8 with a bishop. |  |
| 33 | card/game01/WHITE/49 | Kb1 throws a big chunk of the position away. That takes you from clearly better to about level. Better was Bxd7: it hits the queen on e8 with a bishop. | Kb1 throws a big chunk of the position away, and you go from clearly better to about level. Better was Bxd7: it hits the queen on e8 with a bishop. |  |
| 34 | card/game01/WHITE/55 | Now your opponent can play Rb8, which pins the pawn on b2 to the king on b1. That takes you from about level to losing. Better was Re1. | Your opponent can now play Rb8, which pins the pawn on b2 to the king on b1. That takes you from about level to losing. Better was Re1. |  |
| 35 | card/game01/BLACK/25 | Qxb7 lets the position slip. That takes your opponent from clearly better to slightly better. Better was c5; in the engine's line it clears c4 so that Bc4 can come through. | Qxb7 lets the position slip. That takes your opponent from clearly better to slightly better. Better was c5; in the engine's line, it clears c4 so that Bc4 can come through. |  |
| 36 | card/game01/BLACK/26 | Bc6 gives up real ground. The position swings from slightly worse to clearly worse for you. Better was Nc6: in the engine's line it clears b8 so that Rb8 can come through. | Bc6 gives up real ground, and the position swings from slightly worse to clearly worse for you. Better was Nc6: in the engine's line it clears b8 so that Rb8 can come through. |  |
| 37 | card/game01/NONE/25 | Qxb7 lets the position slip. That takes White from clearly better to slightly better. Better was c5; in the engine's line it clears c4 so that Bc4 can come through. | Qxb7 lets the position slip, and White goes from clearly better to slightly better. Better was c5; in the engine's line, it clears c4 so that Bc4 can come through. |  |
| 38 | card/game01/NONE/26 | Bc6 gives up real ground. The position swings from slightly worse to clearly worse for Black. Better was Nc6: in the engine's line it clears b8 so that Rb8 can come through. | Bc6 gives up real ground, and the position swings from slightly worse to clearly worse for Black. Better was Nc6: in the engine's line it clears b8 so that Rb8 can come through. |  |
| 39 | card/game01/WHITE/26 | Bc6 gives up real ground. The position swings from slightly worse to clearly worse for your opponent. Better was Nc6: in the engine's line it clears b8 so that Rb8 can come through. | Bc6 gives up real ground, and the position swings from slightly worse to clearly worse for your opponent. Better was Nc6: in the engine's line it clears b8 so that Rb8 can come through. |  |
| 40 | card/game01/NONE/24 | exf3 was the only move that kept things on track. This picks up a piece. | exf3 was the only move that kept things on track, and it picks up a piece. |  |
| 41 | card/game01/NONE/53 | Bxg7 is an only move, and nothing else keeps the position on track. It hits the queen on e5 with a bishop. | Bxg7 is the only move, and nothing else keeps the position on track. It hits the queen on e5 with a bishop. |  |
| 42 | narr/game01/NONE/60 | White takes the knight on d seven with the queen. Instead, bishop takes the knight on d seven, which gives nothing away. | White takes the knight on d seven with the queen. Instead, the bishop takes the knight on d seven, which gives nothing away. |  |
| 43 | narr/game01/WHITE/29 | Shortly after that, You play the rook from d one to g one. Instead, bishop to d four, which gives nothing away. | Shortly after that, you play the rook from d one to g one. Instead, the bishop moves to d four, which gives nothing away. |  |
| 44 | narr/game01/WHITE/60 | You take the knight on d seven with the queen. Instead, bishop takes the knight on d seven, which gives nothing away. | You take the knight on d seven with the queen. Instead, the bishop takes the knight on d seven, which gives nothing away. |  |
| 45 | narr/game01/BLACK/38 | You answer with bishop to e five. That is the toughest try in the position. | You answer with the bishop to e five. That is the toughest try in the position. |  |
| 46 | narr/game01/NONE/51 | And then White plays queen to c two. No fireworks. It just keeps everything pointed at the same weakness. | White plays queen to c two. No fireworks. It just keeps everything pointed at the same weakness. |  |
| 47 | narr/game01/WHITE/39 | Then you play queen to h four. No fireworks. It just keeps everything pointed at the same weakness. | Then you play the queen to h four. No fireworks. It just keeps everything pointed at the same weakness. |  |
| 48 | narr/game01/WHITE/51 | And then you play queen to c two. No fireworks. It just keeps everything pointed at the same weakness. | You play queen to c two. No fireworks. It just keeps everything pointed at the same weakness. |  |
| 49 | narr/game01/BLACK/4 | You play pawn to e five. It drags the defender off d one, and once it's gone nothing holds together. | You play pawn to e five. It drags the defender off d one, and once it's gone, nothing holds together. |  |
| 50 | narr/game01/NONE/28 | White plays bishop to f five. That's a relative pin: the piece on d seven is tied to something worth more behind it. | White plays the bishop to f five. That's a relative pin: the piece on d seven is tied to something worth more behind it. |  |

## Every rejection (candidate and reason)

- `card/immortal/NONE/19` **FACTS_TERMS** ({more attackers=1} -> {})  
  O: Now Black can play Nf6, which piles up on the pawn on g4: more attackers than defenders. That takes White from about level to slightly worse. Better was Ba4.  
  R: Black can now play Nf6, which piles up more attackers on the pawn on g4. That takes White from about level to slightly worse. Better was Ba4.
- `card/immortal/NONE/33` **BANNED** (allow*)  
  O: This lets Black play Qxb2, which attacks the rook on a1, which is en prise. From winning to clearly better in one move: that is what this cost White. Better was d4: it attacks the bishop on c5, which is en prise.  
  R: This allows Black to play Qxb2, which attacks the rook on a1, which is en prise. From winning to clearly better in one move: that is what this cost White. Better was d4: it attacks the bishop on c5, which is en prise.
- `card/immortal/NONE/36` **BANNED** (allow*)  
  O: This lets White play Re1, which leaves the bishop on g1 with no safe square. From about level to clearly worse in one move: that is what this cost Black. Better was Qxa1+, which picks up a rook.  
  R: This allows White to play Re1, which leaves the bishop on g1 with no safe square. From about level to clearly worse in one move: that is what this cost Black. Better was Qxa1+, which picks up a rook.
- `card/immortal/WHITE/19` **FACTS_TERMS** ({more attackers=1} -> {})  
  O: Now your opponent can play Nf6, which piles up on the pawn on g4: more attackers than defenders. That takes you from about level to slightly worse. Better was Ba4.  
  R: Your opponent can now play Nf6, which piles up more attackers on the pawn on g4. That takes you from about level to slightly worse. Better was Ba4.
- `card/immortal/WHITE/36` **BANNED** (allow*)  
  O: This lets you play Re1, which leaves the bishop on g1 with no safe square. From about level to clearly worse in one move: that is what this cost your opponent. Better was Qxa1+, which picks up a rook.  
  R: This allows you to play Re1, which leaves the bishop on g1 with no safe square. From about level to clearly worse in one move: that is what this cost your opponent. Better was Qxa1+, which picks up a rook.
- `card/immortal/BLACK/36` **BANNED** (allow*)  
  O: This lets your opponent play Re1, which leaves the bishop on g1 with no safe square. From about level to clearly worse in one move: that is what this cost you. Better was Qxa1+, which picks up a rook.  
  R: This allows your opponent to play Re1, which leaves the bishop on g1 with no safe square. From about level to clearly worse in one move: that is what this cost you. Better was Qxa1+, which picks up a rook.
- `card/chesscom/NONE/12` **BANNED** (allow*)  
  O: This lets White play Qb3, which pins the pawn on b7 to the knight on b8. From slightly worse to clearly worse in one move: that is what this cost Black. Better was Qf6.  
  R: This allows White to play Qb3, which pins the pawn on b7 to the knight on b8. From slightly worse to clearly worse in one move: that is what this cost Black. Better was Qf6.
- `card/chesscom/WHITE/12` **BANNED** (allow*)  
  O: This lets you play Qb3, which pins the pawn on b7 to the knight on b8. From slightly worse to clearly worse in one move: that is what this cost your opponent. Better was Qf6.  
  R: This allows you to play Qb3, which pins the pawn on b7 to the knight on b8. From slightly worse to clearly worse in one move: that is what this cost your opponent. Better was Qf6.
- `card/game01/NONE/18` **BANNED** (allow*)  
  O: This lets White play hxg4, which uncovers the rook on h1, which now attacks the bishop on h5. From clearly worse to losing in one move: that is what this cost Black. Better was exf3.  
  R: This allows White to play hxg4, which uncovers the rook on h1, now attacking the bishop on h5. From clearly worse to losing in one move: that is what this cost Black. Better was exf3.
- `card/game01/NONE/21` **BANNED** (allow*)  
  O: This lets Black play Bd7, which hits the queen on a4 with a bishop. Better was Nxe4.  
  R: This allows Black to play Bd7, which hits the queen on a4 with a bishop. Better was Nxe4.
- `card/game01/NONE/30` **BANNED** (allow*)  
  O: This lets White play Bh3, which attacks the queen on c8 with a bishop. Better was Bd7.  
  R: This allows White to play Bh3, which attacks the queen on c8 with a bishop. Better was Bd7.
- `card/game01/WHITE/18` **BANNED** (allow*)  
  O: This lets you play hxg4, which uncovers the rook on h1, which now attacks the bishop on h5. From clearly worse to losing in one move: that is what this cost your opponent. Better was exf3.  
  R: This allows you to play hxg4, which uncovers the rook on h1, which now attacks the bishop on h5. From clearly worse to losing in one move: that is what this cost your opponent. Better was exf3.
- `card/game01/WHITE/21` **BANNED** (allow*)  
  O: This lets your opponent play Bd7, which hits the queen on a4 with a bishop. Better was Nxe4.  
  R: This allows your opponent to play Bd7, which hits the queen on a4 with a bishop. Better was Nxe4.
- `card/game01/BLACK/30` **BANNED** (allow*)  
  O: This lets your opponent play Bh3, which attacks the queen on c8 with a bishop. Better was Bd7.  
  R: This allows your opponent to play Bh3, which attacks the queen on c8 with a bishop. Better was Bd7.
- `narr/chesscom/NONE/24` **FACTS_NUMBERS** ([2] -> [])  
  O: Two moves on, White plays rook to d eight, and that is checkmate. And that's the game. White wins.  
  R: White plays rook to d eight, and that is checkmate. And that's the game. White wins.
- `narr/chesscom/WHITE/24` **FACTS_NUMBERS** ([2] -> [])  
  O: Two moves on, You play rook to d eight, and that is checkmate. And that's the game. You finish it off.  
  R: You play rook to d eight, and that is checkmate. And that's the game. You finish it off.
- `narr/game01/NONE/46` **FACTS_PIECE_SQUARES** ([] -> [bishop@h6])  
  O: A couple of moves later, White follows up with bishop to h six. And that is a brilliant move. It gives material away and the engine loves it anyway.  
  R: A couple of moves later, White plays bishop on h six. And that is a brilliant move. It gives material away, and the engine loves it anyway.
- `narr/game01/WHITE/22` **FACTS_NUMBERS** ([2] -> [])  
  O: Two moves on, You play pawn to d six. It cuts the defender's line, and the piece it was covering is left open.  
  R: You play pawn to d six. It cuts the defender's line, and the piece it was covering is left open.
- `narr/game01/BLACK/52` **FACTS_PIECE_SQUARES** ([] -> [rook@b4])  
  O: You answer with rook to b four. That is the engine's own choice, so nothing better exists.  
  R: You answer with the rook on b four. That is the engine's own choice, so nothing better exists.
- `narr/game01/BLACK/62` **FACTS_NUMBERS** ([2] -> [])  
  O: Two moves on, you play queen to c three, with check. This is a forced mate, and the king cannot get out.  
  R: You play queen to c three, with check. This is a forced mate, and the king cannot get out.
- `narr/byrne_fischer/NONE/9` **SHAPE_LENGTH** (3 words for 7)  
  O: So add it up. White wins material.  
  R: White wins material.
- `narr/byrne_fischer/NONE/55` **FACTS_PLAYERS** ([YOU, WHITE] -> [WHITE])  
  O: So what do you take away from this? White walked past four x-rays in this game. They were on b seven, g four and a seven. Drill twenty of these on a puzzle trainer. Pattern recognition is the only real fix.  
  R: White walked past four x-rays in this game: on b seven, g four, and a seven. Drill twenty of these on a puzzle trainer. Pattern recognition is the only real fix.
