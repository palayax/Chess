# Rephrase measurement: qwen2.5-0.5b-instruct-q8_0, prompt v1

Written by `RephraseMeasurementDumpTest` from `docs/audit/rephrase/raw_qwen2.5-0.5b-instruct-q8_0.jsonl` (host run of
`scripts/rephrase_measure.py`, llama.cpp b11190 CPU, greedy, n_ctx 2048, prefix in the KV cache).
Verdicts by the Kotlin `ClaimChecker`; `scripts/audit_commentary.py rephrase` re-checks them independently.
Corpus: every distinct card text of the three audited games (no side, White, Black) and every eligible
narration beat of the five pacing games at the Normal pace (three sides).
Texts of this run that today's generator no longer writes (V4 changed three connectives after the run): 1.

## Verdicts (design §5.5: rejection = REJECT / all; bar <= 25 % cards, <= 35 % narration)

| Surface | Texts | Accepted | Unchanged | Rejected | Rejection rate | Unchanged rate |
|---|---|---|---|---|---|---|
| card | 190 | 12 | 174 | 4 | 2.1 % | 91.6 % |
| narration | 97 | 16 | 79 | 2 | 2.1 % | 81.4 % |

### Rejections by reason

| Reason | Cards | Narration |
|---|---|---|
| BETTER_WAS | 2 | 0 |
| FACTS_BANDS | 1 | 0 |
| FACTS_MOVES | 1 | 0 |
| FACTS_PIECE_SQUARES | 0 | 1 |
| FACTS_TERMS | 0 | 1 |

## Latency on the host (reference only; the Pixel 8 decides)

| Surface | Median ms | p90 ms | Median prompt tokens (after the cached prefix) | Median output tokens | Prefill tok/s | Decode tok/s |
|---|---|---|---|---|---|---|
| card | 3088 | 6330 | 75 | 25 | 69.3 | 13.3 |
| narration | 2814 | 6634 | 69 | 26 | 73.3 | 14.1 |

Key moments per game (the cards of class BLUNDER/MISTAKE/MISS/BRILLIANT/GREAT, no side, at most 5): total time

- chesscom: 5 cards, 22.3 s
- game01: 5 cards, 21.8 s
- immortal: 5 cards, 11.7 s

## Quality sample: original vs accepted rewrite (owner: fill the A/B column, prefer = R or O)

| # | Id | Original | Rewrite | Prefer |
|---|---|---|---|---|
| 1 | card/immortal/BLACK/40 | Now your opponent can play Nxg7+, which sets a forced mate in 5 in motion. That takes you from clearly worse to decisively lost. Better was Ba6. | Now your opponent can play Nxg7+, setting a forced mate in 5 in motion, and you go from clearly worse to decisively lost. Better was Ba6. |  |
| 2 | card/immortal/NONE/40 | Now White can play Nxg7+, which sets a forced mate in 5 in motion. That takes Black from clearly worse to decisively lost. Better was Ba6. | Now White can play Nxg7+, which sets a forced mate in 5 in motion, and Black goes from clearly worse to decisively lost. Better was Ba6. |  |
| 3 | card/immortal/WHITE/40 | Now you can play Nxg7+, which sets a forced mate in 5 in motion. That takes your opponent from clearly worse to decisively lost. Better was Ba6. | Now you can play Nxg7+, which sets a forced mate in 5 in motion, and your opponent goes from clearly worse to decisively lost. Better was Ba6. |  |
| 4 | narr/immortal/NONE/28 | Black takes the rook on g one with the bishop. And the chance is gone. It does not come back. That's about level turning into clearly worse in a single move. | Black takes the rook on g one with the bishop, and the chance is gone; it does not come back. In a single move, about level turns into clearly worse. |  |
| 5 | narr/immortal/NONE/36 | Back in the real game, though, that got played instead — | Back in the real game, though, that got played instead. |  |
| 6 | narr/immortal/NONE/6 | Shortly after that, Black takes the bishop on b five with the pawn. The move was pawn to h five. | Shortly after that, Black takes the bishop on b five with the pawn, and the move was pawn to h five. |  |
| 7 | narr/immortal/BLACK/33 | And then you play king to d eight. Nothing is captured, and nothing has to be. The threat does the work. | And then you play king to d eight, nothing is captured, and nothing has to be. The threat does the work. |  |
| 8 | narr/immortal/NONE/40 | Shortly after that, White goes for queen to f six, with check. It's a forced mate. The king has nowhere to run. | Shortly after that, White goes for queen to f six, with a check. It's a forced mate. The king has nowhere to run. |  |
| 9 | card/immortal/NONE/27 | Qf3 is the engine's first choice. It traps the queen on g5: every square it can reach loses material. | The engine's first choice is Qf3. It traps the queen on g5, since every square it can reach loses material. |  |
| 10 | card/immortal/NONE/30 | Qf6 is the engine's first choice. This pins the pawn on b2 to the rook on a1. | The engine's first choice is Qf6. This pin the pawn on b2 to the rook on a1. |  |
| 11 | card/immortal/NONE/38 | Qxa1+ matches the engine's top choice. This picks up a rook. | Qxa1+ matches the engine's top choice, picking up a rook. |  |
| 12 | narr/immortal/NONE/10 | White takes the pawn on f four with the bishop. The evaluation moves against White. | White takes the pawn on f four with the bishop, and the evaluation moves against White. |  |
| 13 | narr/chesscom/NONE/8 | White takes the pawn on b seven with the queen. That is the engine's own choice, so nothing better exists. | White takes the pawn on b seven with the queen, and that is the engine's own choice, so nothing better exists. |  |
| 14 | narr/chesscom/NONE/16 | White takes the pawn on b five with the knight. And that is a brilliant move. It gives material away and the engine loves it anyway. | White takes the pawn on b five with the knight, and that is a brilliant move. It gives material away and the engine loves it anyway. |  |
| 15 | card/game01/BLACK/49 | Kb1 throws a big chunk of the position away. That takes your opponent from clearly better to about level. Better was Bxd7: it hits the queen on e8 with a bishop. | Kb1 throws a big chunk of the position away, and your opponent goes from clearly better to about level. Better was Bxd7: it hits the queen on e8 with a bishop. |  |
| 16 | card/game01/BLACK/55 | Now you can play Rb8, which pins the pawn on b2 to the king on b1. That takes your opponent from about level to losing. Better was Re1. | Now you can play Rb8, which pins the pawn on b2 to the king on b1, and your opponent goes from about level to losing. Better was Re1. |  |
| 17 | card/game01/NONE/49 | Kb1 throws a big chunk of the position away. That takes White from clearly better to about level. Better was Bxd7: it hits the queen on e8 with a bishop. | Kb1 throws a big chunk of the position away, and White goes from clearly better to about level. Better was Bxd7: it hits the queen on e8 with a bishop. |  |
| 18 | card/game01/NONE/55 | Now Black can play Rb8, which pins the pawn on b2 to the king on b1. That takes White from about level to losing. Better was Re1. | Now Black can play Rb8, which pins the pawn on b2 to the king on b1, and White goes from about level to losing. Better was Re1. |  |
| 19 | card/game01/WHITE/49 | Kb1 throws a big chunk of the position away. That takes you from clearly better to about level. Better was Bxd7: it hits the queen on e8 with a bishop. | Kb1 throws a big chunk of the position away, and you go from clearly better to about level. Better was Bxd7: it hits the queen on e8 with a bishop. |  |
| 20 | narr/game01/NONE/60 | White takes the knight on d seven with the queen. Instead, bishop takes the knight on d seven, which gives nothing away. | White takes the knight on d seven with the queen, instead of bishop taking the knight on d seven, which gives nothing away. |  |
| 21 | narr/game01/NONE/64 | A couple of moves later, Black plays queen to c one, and that is checkmate. That's it. Game over. Black wins. | A couple of moves later, Black plays queen to c one, and that is checkmate. Game over. Black wins. |  |
| 22 | narr/game01/NONE/36 | Black takes back on d seven with the queen. The recapture is more or less forced, and it does not help. | Black takes back on d seven with the queen, and the recapture is more or less forced, and it does not help. |  |
| 23 | narr/game01/WHITE/51 | And then you play queen to c two. No fireworks. It just keeps everything pointed at the same weakness. | And then you play queen to c two, no fireworks. It just keeps everything pointed at the same weakness. |  |
| 24 | card/game01/NONE/43 | Bf5 is the top engine move here. It piles up on the pawn on h7: more attackers than defenders. | Bf5 is the top engine move here. It piles up on the pawn on h7, more attackers than defenders. |  |

## Every rejection (candidate and reason)

- `card/chesscom/NONE/27` **FACTS_MOVES** ([Rd1, Rd1] -> [Rd1])  
  O: Rd1 is the engine's first choice. In the engine's line, Rd1 deflects the queen on e7 away from guarding f6.  
  R: Rd1 is the engine's first choice. In the engine's line, it deflects the queen on e7 away from guarding f6.
- `card/chesscom/NONE/29` **FACTS_BANDS** ([] -> [winning])  
  O: Bxd7+ matches the engine's top choice. It wins the exchange.  
  R: Bxd7+ matches the engine's top choice, winning the exchange.
- `card/game01/NONE/46` **BETTER_WAS** (Ba6 -> null (0, last: Bg7 gives back ground, and better was Ba6.))  
  O: Bg7 gives back ground. Better was Ba6.  
  R: Bg7 gives back ground, and better was Ba6.
- `card/game01/WHITE/16` **BETTER_WAS** (Bd7 -> null (0, last: Now you can play g4, which attacks the bishop on h5 with a pawn, and better was Bd7.))  
  O: Now you can play g4, which attacks the bishop on h5 with a pawn. Better was Bd7.  
  R: Now you can play g4, which attacks the bishop on h5 with a pawn, and better was Bd7.
- `narr/byrne_fischer/NONE/14` **FACTS_TERMS** ({removing the defender=1} -> {})  
  O: Black takes the knight on c three with the knight. Removing the defender: once it is gone, e four has nothing holding it. So that's a pawn.  
  R: Black takes the knight on c three with the knight, and once it is gone, e four has nothing holding it. So that's a pawn.
- `narr/byrne_fischer/BLACK/3` **FACTS_PIECE_SQUARES** ([] -> [bishop@f3])  
  O: You go for bishop to g four. The piece on f three is pinned, and moving it costs more than staying put. It doesn't win material yet, but the pressure is real.  
  R: You go for bishop to g four. The bishop on f three is pinned, and moving it costs more than staying put. It doesn't win material yet, but the pressure is real.
