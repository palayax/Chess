# Rephrase measurement: the candidates side by side, prompt v1

On the 287 texts every run judged (190 card texts, 97 narration beats).
Host CPU (llama.cpp b11190); latency is a reference only.

| Model | Card rejection | Card unchanged | Card accepted | Narration rejection | Narration unchanged | Narration accepted | Median ms card | Median ms narration | Decode tok/s |
|---|---|---|---|---|---|---|---|---|---|
| qwen2.5-0.5b-instruct-q8_0 | 2.1 % | 91.6 % | 6.3 % | 2.1 % | 81.4 % | 16.5 % | 3088 | 2814 | 13.5 |
| qwen2.5-1.5b-instruct-q4_0 | 6.3 % | 52.1 % | 41.6 % | 26.8 % | 30.9 % | 42.3 % | 3602 | 4355 | 13.0 |
| qwen2.5-1.5b-instruct-q4_k_m | 6.3 % | 54.7 % | 38.9 % | 18.6 % | 27.8 % | 53.6 % | 3842 | 3524 | 11.9 |
| qwen3-1.7b-q8_0 | 7.4 % | 72.1 % | 20.5 % | 8.2 % | 49.5 % | 42.3 % | 8731 | 10226 | 5.0 |

## The same texts, accepted by every model (first 12): how each one words it

- `card/game01/BLACK/49`  
  original: Kb1 throws a big chunk of the position away. That takes your opponent from clearly better to about level. Better was Bxd7: it hits the queen on e8 with a bishop.  
  qwen2.5-0.5b-instruct-q8_0: Kb1 throws a big chunk of the position away, and your opponent goes from clearly better to about level. Better was Bxd7: it hits the queen on e8 with a bishop.  
  qwen2.5-1.5b-instruct-q4_0: Kb1 throws a big chunk of the position away, taking your opponent from clearly better to about level. Better was Bxd7: it hits the queen on e8 with a bishop.  
  qwen2.5-1.5b-instruct-q4_k_m: Kb1 throws a big chunk of the position away, taking your opponent from clearly better to about level. Better was Bxd7, which hits the queen on e8 with a bishop.  
  qwen3-1.7b-q8_0: Kb1 throws a big chunk of the position away, and your opponent goes from clearly better to about level. Better was Bxd7: it hits the queen on e8 with a bishop.  
- `card/game01/NONE/43`  
  original: Bf5 is the top engine move here. It piles up on the pawn on h7: more attackers than defenders.  
  qwen2.5-0.5b-instruct-q8_0: Bf5 is the top engine move here. It piles up on the pawn on h7, more attackers than defenders.  
  qwen2.5-1.5b-instruct-q4_0: Bf5 is the top engine move here, piling up on the pawn on h7: more attackers than defenders.  
  qwen2.5-1.5b-instruct-q4_k_m: Bf5 is the top engine move here, piling up on the pawn on h7 with more attackers than defenders.  
  qwen3-1.7b-q8_0: Bf5 is the top engine move here. It piles up on the pawn on h7, adding more attackers than defenders.  
- `card/game01/NONE/49`  
  original: Kb1 throws a big chunk of the position away. That takes White from clearly better to about level. Better was Bxd7: it hits the queen on e8 with a bishop.  
  qwen2.5-0.5b-instruct-q8_0: Kb1 throws a big chunk of the position away, and White goes from clearly better to about level. Better was Bxd7: it hits the queen on e8 with a bishop.  
  qwen2.5-1.5b-instruct-q4_0: Kb1 throws a big chunk of the position away, taking White from clearly better to about level. Better was Bxd7: it hits the queen on e8 with a bishop.  
  qwen2.5-1.5b-instruct-q4_k_m: Kb1 throws a big chunk of the position away, taking White from clearly better to about level. Better was Bxd7, which hits the queen on e8 with a bishop.  
  qwen3-1.7b-q8_0: Kb1 throws a big chunk of the position away, and White goes from clearly better to about level. Better was Bxd7: it hits the queen on e8 with a bishop.  
- `card/game01/WHITE/49`  
  original: Kb1 throws a big chunk of the position away. That takes you from clearly better to about level. Better was Bxd7: it hits the queen on e8 with a bishop.  
  qwen2.5-0.5b-instruct-q8_0: Kb1 throws a big chunk of the position away, and you go from clearly better to about level. Better was Bxd7: it hits the queen on e8 with a bishop.  
  qwen2.5-1.5b-instruct-q4_0: Kb1 throws a big chunk of the position away, taking you from clearly better to about level. Better was Bxd7: it hits the queen on e8 with a bishop.  
  qwen2.5-1.5b-instruct-q4_k_m: Kb1 throws a big chunk of the position away, taking you from clearly better to about level. Better was Bxd7, which hits the queen on e8 with a bishop.  
  qwen3-1.7b-q8_0: Kb1 throws a big chunk of the position away, and you go from clearly better to about level. Better was Bxd7: it hits the queen on e8 with a bishop.  
- `card/immortal/BLACK/40`  
  original: Now your opponent can play Nxg7+, which sets a forced mate in 5 in motion. That takes you from clearly worse to decisively lost. Better was Ba6.  
  qwen2.5-0.5b-instruct-q8_0: Now your opponent can play Nxg7+, setting a forced mate in 5 in motion, and you go from clearly worse to decisively lost. Better was Ba6.  
  qwen2.5-1.5b-instruct-q4_0: Now your opponent can play Nxg7+, setting a forced mate in 5, and you go from clearly worse to decisively lost. Better was Ba6.  
  qwen2.5-1.5b-instruct-q4_k_m: Now your opponent can play Nxg7+, setting a forced mate in 5, and you go from clearly worse to decisively lost. Better was Ba6.  
  qwen3-1.7b-q8_0: Your opponent can now play Nxg7+, setting a forced mate in 5 in motion, and you go from clearly worse to decisively lost. Better was Ba6.  
- `card/immortal/NONE/27`  
  original: Qf3 is the engine's first choice. It traps the queen on g5: every square it can reach loses material.  
  qwen2.5-0.5b-instruct-q8_0: The engine's first choice is Qf3. It traps the queen on g5, since every square it can reach loses material.  
  qwen2.5-1.5b-instruct-q4_0: Qf3 is the engine's first choice. It traps the queen on g5, as every square it can reach loses material.  
  qwen2.5-1.5b-instruct-q4_k_m: Qf3 is the engine's first choice. It traps the queen on g5, since every square it can reach loses material.  
  qwen3-1.7b-q8_0: Qf3 is the engine's first choice. It traps the queen on g5, since every square it can reach loses material.  
- `card/immortal/NONE/38`  
  original: Qxa1+ matches the engine's top choice. This picks up a rook.  
  qwen2.5-0.5b-instruct-q8_0: Qxa1+ matches the engine's top choice, picking up a rook.  
  qwen2.5-1.5b-instruct-q4_0: Qxa1+ matches the engine's top choice, picking up a rook.  
  qwen2.5-1.5b-instruct-q4_k_m: Qxa1+ matches the engine's top choice, picking up a rook.  
  qwen3-1.7b-q8_0: Qxa1+ matches the engine's top choice, and it picks up a rook.  
- `card/immortal/NONE/40`  
  original: Now White can play Nxg7+, which sets a forced mate in 5 in motion. That takes Black from clearly worse to decisively lost. Better was Ba6.  
  qwen2.5-0.5b-instruct-q8_0: Now White can play Nxg7+, which sets a forced mate in 5 in motion, and Black goes from clearly worse to decisively lost. Better was Ba6.  
  qwen2.5-1.5b-instruct-q4_0: Now White can play Nxg7+, setting a forced mate in 5, which takes Black from clearly worse to decisively lost. Better was Ba6.  
  qwen2.5-1.5b-instruct-q4_k_m: Now White can play Nxg7+, setting a forced mate in 5, which takes Black from clearly worse to decisively lost. Better was Ba6.  
  qwen3-1.7b-q8_0: White can now answer with Nxg7+, setting a forced mate in 5 in motion, and Black goes from clearly worse to decisively lost. Better was Ba6.  
- `narr/byrne_fischer/NONE/35`  
  original: White takes the queen on b six with the bishop. And the chance is gone. It does not come back. White was clearly worse before that. Now White is losing.  
  qwen2.5-0.5b-instruct-q8_0: White takes the queen on b six with the bishop, and the chance is gone. It does not come back. White was clearly worse before that. Now White is losing.  
  qwen2.5-1.5b-instruct-q4_0: White takes the queen on b six with the bishop, and the chance is gone; it does not come back. White was clearly worse before that. Now White is losing.  
  qwen2.5-1.5b-instruct-q4_k_m: White takes the queen on b six with the bishop, and the chance is gone; it does not come back. White was clearly worse before that. Now White is losing.  
  qwen3-1.7b-q8_0: White takes the queen on b six with the bishop. And the chance is gone; it does not come back. White was clearly worse before that. Now White is losing.  
- `narr/game01/NONE/60`  
  original: White takes the knight on d seven with the queen. Instead, bishop takes the knight on d seven, which gives nothing away.  
  qwen2.5-0.5b-instruct-q8_0: White takes the knight on d seven with the queen, instead of bishop taking the knight on d seven, which gives nothing away.  
  qwen2.5-1.5b-instruct-q4_0: White takes the knight on d seven with the queen. Instead, the bishop takes the knight on d seven, which gives nothing away.  
  qwen2.5-1.5b-instruct-q4_k_m: White takes the knight on d seven with the queen. Instead, the bishop takes the knight on d seven, which gives nothing away.  
  qwen3-1.7b-q8_0: White takes the knight on d seven with the queen. Instead, the bishop takes the knight on d seven, which gives nothing away.  
- `narr/immortal/BLACK/33`  
  original: And then you play king to d eight. Nothing is captured, and nothing has to be. The threat does the work.  
  qwen2.5-0.5b-instruct-q8_0: And then you play king to d eight, nothing is captured, and nothing has to be. The threat does the work.  
  qwen2.5-1.5b-instruct-q4_0: And then you play king to d eight. Nothing is captured, and nothing has to be done. The threat does the work.  
  qwen2.5-1.5b-instruct-q4_k_m: And then you play king to d eight. Nothing is captured, and nothing has to be done. The threat does the work.  
  qwen3-1.7b-q8_0: You play the king to d eight. Nothing is captured, and nothing has to be. The threat does the work.  
- `narr/immortal/NONE/28`  
  original: Black takes the rook on g one with the bishop. And the chance is gone. It does not come back. That's about level turning into clearly worse in a single move.  
  qwen2.5-0.5b-instruct-q8_0: Black takes the rook on g one with the bishop, and the chance is gone; it does not come back. In a single move, about level turns into clearly worse.  
  qwen2.5-1.5b-instruct-q4_0: Black takes the rook on g one with the bishop, and the chance is gone; it does not come back. In a single move, about level turns into clearly worse.  
  qwen2.5-1.5b-instruct-q4_k_m: Black takes the rook on g one with the bishop, and the chance is gone; it does not come back. In a single move, about level turns into clearly worse.  
  qwen3-1.7b-q8_0: Black's bishop takes the rook on g one, and the chance is gone; it does not come back. In a single move, about level turns into clearly worse.  
