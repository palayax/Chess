# While you wait (the waiting panel)

Owner request: "During the processing - The UI should give visual progress indication with time estimation and show game
insights so the user not just "wait" until the process ends. Other than insights, it could show "Fan facts" about chess,
famous games and players and chess puzzles (these items could be pre-downloaded)."

This document covers the **panel** (`WhileYouWaitPanel`): live insights about the game being analysed, then chess facts,
player profiles, famous games and puzzles. The progress bar, the stages and the time estimate belong to the analysis
screen itself and are not part of this change. Everything here is bundled in the APK; the app makes no network call for it.

## 1. Files

| File | What it is |
|---|---|
| `app/src/main/assets/waiting/facts.json` | 61 chess facts (one or two sentences each), our own words. |
| `app/src/main/assets/waiting/players.json` | 25 short player profiles. |
| `app/src/main/assets/waiting/puzzles.json` | 47 "find the best move" positions taken from moves played in the famous games, with the solution line and the engine numbers that verified them. About 40 KB for the three files together. |
| `app/src/main/assets/famous_games*.{pgn,tsv}` | **Reused**, not copied: the famous-games library (G1) supplies the game cards and the title and year of each puzzle's game. |
| `ui/model/WaitingContent.kt` | Data classes and the org.json parsing (host-tested against the real assets). |
| `ui/model/LiveInsights.kt` | The live insights: `liveInsights(sans, evals, userColor, book)` and `LiveInsightEngine`. |
| `ui/model/WaitingScheduler.kt` | `WaitingDeck` (seeded, no repeats) and `WaitingPanelController` (the rotation rules, with an explicit clock). |
| `ui/model/WaitingPuzzleLogic.kt` | The puzzle state machine: tap, reply, next move, show solution. |
| `data/WaitingContentStore.kt` | `WaitingContentStore.load(context)` (off the main thread, cached per process) and the opening book for the opening insight. |
| `ui/components/WhileYouWait.kt` | The composable `WhileYouWaitPanel` and its previews. `InsightStrings.kt` maps an insight kind to its `strings.xml` template. |
| `scripts/waiting_puzzles.py` | Mines and verifies the puzzles with python-chess and a local Stockfish (section 5). |
| `scripts/verify_waiting_facts.py` | Retrograde check of the king-and-queen / king-and-rook mate lengths (fact `kqk`). |

## 2. Facts and players, and their sources

The sources are not shown in the app. A host test (`WaitingContentTest`) fails if a fact or a player has no row below.
"computed" means a host test (`WaitingFactsComputedTest`, using `:core`'s own move generator) or a script proves the
claim, as well as the source; "source only" means the claim is history or a record and rests on the cited page, which was
read when the fact was written. Wikipedia pages are the source for the claim, never for the wording (the wording is ours).

Wording rules used: a number is stated only if the source states it; an estimate is called an estimate (the Shannon
number is "an estimate, not a count", and the atoms of the universe are "thought to be"); a record carries its year and
holder, so it cannot silently go stale; no "first/only/longest" is stated unless the cited page says so.

### Facts

| id | source | check |
|---|---|---|
| first-moves | https://www.chessprogramming.org/Perft_Results | computed: legal move count of the start position (WaitingFactsComputedTest) |
| perft-2 | https://www.chessprogramming.org/Perft_Results | computed: perft 2 and 4 |
| transpositions | https://oeis.org/A083276 | computed: distinct positions after 1-4 plies = 20, 400, 5362, 72078 |
| perft-5 | https://www.chessprogramming.org/Perft_Results | computed: perft 5 (also pinned by PerftTest) |
| shannon | https://en.wikipedia.org/wiki/Shannon_number | source only; wording keeps it an estimate and a lower bound |
| eight-queens | https://en.wikipedia.org/wiki/Eight_queens_puzzle | computed: backtracking count and symmetry classes |
| knight-tours | https://en.wikipedia.org/wiki/Knight%27s_tour | source only (read on the page: 26,534,728,821,064 directed closed tours) |
| chess960 | https://en.wikipedia.org/wiki/Fischer_random_chess | computed: 960 arrangements; 1996 from the source |
| max-moves | https://lichess.org/@/Tobs40/blog/there-is-no-reachable-chess-position-with-more-than-218-moves/a5xdxeqs | computed: the 218-move FEN has 218 legal moves; 1964 and Petrovic from the source |
| nine-queens | https://handbook.fide.com/chapter/E012023 (Article 3.7.5, promotion) | rule |
| fools-mate | https://en.wikipedia.org/wiki/Fool%27s_mate | computed: the line is mate, and no line of 3 plies or fewer is |
| scholars-mate | https://en.wikipedia.org/wiki/Scholar%27s_mate | computed: the line is mate |
| loyd-stalemate | https://www.chess.com/blog/Rocky64/shortest-games-to-stalemate-and-mr-robot-chess-scene | computed: the line is a stalemate after 10 white moves |
| en-passant-unique | https://en.wikipedia.org/wiki/En_passant | computed: no other capture lands on an empty square (walk of 3 plies) |
| castling-two-pieces | https://handbook.fide.com/chapter/E012023 (Article 3.8.2, castling) | rule |
| promotion | https://handbook.fide.com/chapter/E012023 (Article 3.7.5) | computed: a pawn on the 7th rank has exactly the four promotions |
| repetition | https://handbook.fide.com/chapter/E012023 (Articles 9.2 and 9.6) | rule |
| fifty-move | https://en.wikipedia.org/wiki/Fifty-move_rule | rule (FIDE Articles 9.3 and 9.6) |
| stalemate | https://handbook.fide.com/chapter/E012023 (Article 5.2.1) | rule |
| dead-position | https://handbook.fide.com/chapter/E012023 (Article 5.2.2) | computed: isInsufficientMaterial for K, KB, KN against K |
| two-knights | https://en.wikipedia.org/wiki/Two_knights_endgame | source only |
| bishop-colour | https://en.wikipedia.org/wiki/Bishop_(chess) | definition of the move |
| knight-colour | https://en.wikipedia.org/wiki/Knight_(chess) | computed: all knight moves change (file + rank) parity |
| board-orientation | https://handbook.fide.com/chapter/E012023 (Article 2.1) | definitional; checked on the corner squares |
| piece-values | https://en.wikipedia.org/wiki/Chess_piece_relative_value | source only |
| kqk | https://en.wikipedia.org/wiki/Chess_endgame | computed: retrograde analysis over every legal position, scripts/verify_waiting_facts.py |
| chaturanga | https://en.wikipedia.org/wiki/Chaturanga | source only (read on the page: first known from India around the 7th century, earliest clear reference 6th century) |
| checkmate-word | https://en.wikipedia.org/wiki/Checkmate | source only (Barnhart: shah mat, the King is helpless) |
| rook-chariot | https://en.wikipedia.org/wiki/Rook_(chess) | source only |
| bishop-names | https://en.wikipedia.org/wiki/Bishop_(chess) | source only |
| queen-power | https://en.wikipedia.org/wiki/Queen_(chess) | source only (modern move in Spain, 15th century) |
| two-square-pawn | https://en.wikipedia.org/wiki/En_passant | source only (13th to 16th centuries) |
| lewis-chessmen | https://en.wikipedia.org/wiki/Lewis_chessmen | source only |
| turk | https://en.wikipedia.org/wiki/Mechanical_Turk | source only |
| chess-clock | https://en.wikipedia.org/wiki/Chess_clock | source only |
| fide-founded | https://en.wikipedia.org/wiki/FIDE | source only; Chess_Olympiad for the unofficial 1924 event |
| first-olympiad | https://en.wikipedia.org/wiki/Chess_Olympiad | source only; Vera_Menchik for her title |
| ioc | https://en.wikipedia.org/wiki/FIDE | source only |
| elo | https://en.wikipedia.org/wiki/Elo_rating_system | source only |
| first-gms | https://en.wikipedia.org/wiki/Grandmaster_(chess) | source only |
| first-champion | https://en.wikipedia.org/wiki/World_Chess_Championship | source only |
| lasker-reign | https://en.wikipedia.org/wiki/Emanuel_Lasker | source only |
| alekhine-died | https://en.wikipedia.org/wiki/Alexander_Alekhine | source only |
| soviet-era | https://en.wikipedia.org/wiki/World_Chess_Championship | source only (list of champions) |
| aborted-match | https://en.wikipedia.org/wiki/Karpov%E2%80%93Kasparov_rivalry | source only |
| kasparov-youngest | https://en.wikipedia.org/wiki/Dommaraju_Gukesh | source only; Garry_Kasparov for his age |
| gukesh-score | https://en.wikipedia.org/wiki/Dommaraju_Gukesh | source only |
| carlsen-peak | https://en.wikipedia.org/wiki/Magnus_Carlsen | source only |
| carlsen-gm | https://en.wikipedia.org/wiki/Magnus_Carlsen | source only |
| youngest-gm | https://www.fide.com/abhimanyu-mishra-is-the-youngest-grandmaster-in-history/ | source only |
| polgar-gm | https://en.wikipedia.org/wiki/Judit_Polg%C3%A1r | source only |
| polgar-kasparov | https://en.wikipedia.org/wiki/Judit_Polg%C3%A1r | source only |
| polgar-peak | https://en.wikipedia.org/wiki/Judit_Polg%C3%A1r | source only |
| menchik | https://en.wikipedia.org/wiki/Vera_Menchik | source only |
| deep-blue-1996 | https://en.wikipedia.org/wiki/Deep_Blue_versus_Garry_Kasparov | source only |
| deep-blue-1997 | https://en.wikipedia.org/wiki/Deep_Blue_versus_Garry_Kasparov | source only |
| stockfish | https://stockfishchess.org/about/ | source only; this repository's LICENSE (GPLv3) |
| opening-book | https://github.com/lichess-org/chess-openings | computed: OpeningBook.load reads more than 3,000 lines (WaitingContentTest) |
| touch-move | https://handbook.fide.com/chapter/E012023 (Article 4.3) | rule |
| kings-apart | https://handbook.fide.com/chapter/E012023 (Articles 3.9 and 3.1) | computed: no reachable position within 3 plies has adjacent kings |
| chess-in-space | https://en.wikipedia.org/wiki/Soyuz_9 | source only |


### Players

| id | source |
|---|---|
| philidor | https://en.wikipedia.org/wiki/Fran%C3%A7ois-Andr%C3%A9_Danican_Philidor |
| anderssen | https://en.wikipedia.org/wiki/Adolf_Anderssen |
| morphy | https://en.wikipedia.org/wiki/Paul_Morphy |
| steinitz | https://en.wikipedia.org/wiki/Wilhelm_Steinitz |
| lasker | https://en.wikipedia.org/wiki/Emanuel_Lasker |
| capablanca | https://en.wikipedia.org/wiki/Jos%C3%A9_Ra%C3%BAl_Capablanca |
| alekhine | https://en.wikipedia.org/wiki/Alexander_Alekhine |
| menchik | https://en.wikipedia.org/wiki/Vera_Menchik |
| euwe | https://en.wikipedia.org/wiki/Max_Euwe |
| botvinnik | https://en.wikipedia.org/wiki/Mikhail_Botvinnik |
| tal | https://en.wikipedia.org/wiki/Mikhail_Tal |
| petrosian | https://en.wikipedia.org/wiki/Tigran_Petrosian (reigns from World_Chess_Championship) |
| spassky | https://en.wikipedia.org/wiki/Boris_Spassky |
| fischer | https://en.wikipedia.org/wiki/Bobby_Fischer (reigns from World_Chess_Championship) |
| korchnoi | https://en.wikipedia.org/wiki/Viktor_Korchnoi |
| karpov | https://en.wikipedia.org/wiki/Anatoly_Karpov (reigns from World_Chess_Championship) |
| kasparov | https://en.wikipedia.org/wiki/Garry_Kasparov |
| polgar | https://en.wikipedia.org/wiki/Judit_Polg%C3%A1r |
| gaprindashvili | https://en.wikipedia.org/wiki/Nona_Gaprindashvili |
| kramnik | https://en.wikipedia.org/wiki/Vladimir_Kramnik |
| anand | https://en.wikipedia.org/wiki/Viswanathan_Anand |
| hou-yifan | https://en.wikipedia.org/wiki/Hou_Yifan |
| carlsen | https://en.wikipedia.org/wiki/Magnus_Carlsen |
| ding | https://en.wikipedia.org/wiki/Ding_Liren |
| gukesh | https://en.wikipedia.org/wiki/Dommaraju_Gukesh |


### Considered and dropped

- The longest tournament game (Nikolic - Arsovic, Belgrade 1989, 269 moves, a draw): the figures recur on many sites, but
  the only sources found were blogs and trivia pages, and the Wikipedia page for the rule does not mention it. Dropped.
- "More possible games than atoms in the universe" in its popular form: replaced by the precise Shannon wording (fact `shannon`).
- Shannon's estimate of the number of positions: the figure differs between his paper (about 10^43) and what the Wikipedia
  summary prints; not stated.
- "Judit Polgar is the only woman to have beaten a reigning world number one": the cited page says so, but Hou Yifan beat Carlsen
  in 2017 while he was the world number one, so the claim is not safe. The fact states only what happened in 2002.
- Camel/elephant etymology details beyond the Russian and French names; the date of the stalemate rule changes; AlphaZero
  training-time claims (disputed comparisons).

## 3. Live insights

`liveInsights(sans, evals, userColor, book)`: `sans` is the whole game, `evals` maps a position index (0 = start, i = after
ply i) to the `PositionEval` the analysis has produced so far, `userColor` is the user's side or null. Every score goes
through `WinProbability.cpWhiteRelative`/`mateWhiteRelative` and `EvalFormat.score`, so the text is the same text the rest of the
app prints (`+0.4`, `-2.8`, `M3`, `#`), White-relative unless the template says "for Black". There are no judgement words:
`LiveInsightsTest` pins every template with the real `strings.xml` sentence, and a test fails on words such as "great" or
"blunder".

| Insight | Exact derivation |
|---|---|
| Opening: name (ECO) | the deepest catalogued position the game passes through (the app's `OpeningBook`, CC0 data); omitted without a book. |
| The game left the opening book with 4.O-O | the first ply whose position is not inside any catalogued line. |
| Biggest swing so far: 17...Qxd4 (White's view: +0.4 -> -2.8) | over every ply with both neighbouring evaluations known, the largest absolute change of the White-relative score (mate scores saturate as in `WinProbability.cpFromMate`); ties go to the earlier move; omitted if the two printed scores are equal. With a side chosen it says "your move" or "your opponent's move". |
| Material after 17...Qxd4: White +2 / level | `MaterialBalance` of the latest analysed position, teaching points 1/3/3/5/9. |
| Longest precise run so far: White, 5 moves in a row (moves 1 to 5), each losing under 2 points of win chance | a side's consecutive own moves for which `WinProbability.loss` (spec section 1.2) is under 2.0, the bound below which the classifier calls a move Excellent or better (spec section 2); only runs of 2 or more; earliest longest run. |
| Best evaluation for White so far: +3.0 (after 3...Bc5) | the highest score each side reached after a move, from that side's own point of view; only if it was above 0.0. |
| Times the evaluation has switched between White and Black so far: 2 | sign changes of the printed score, readings that print `0.0` skipped. |
| Checks / captures in the moves analysed so far | SAN marks `+`/`#` and `x`, counted per side up to the latest analysed ply. |
| First position without queens: after 4...Bxe7 | the first ply after which no queen is on the board. |
| White castled kingside on move 9 / did not castle in this game | the SAN tokens `O-O`, `O-O-O` of the whole game. |
| Pawn promotions, Moves in this game, Analysed 24 of 61 positions | SAN `=` count; the SAN list length; how many indices of `evals` lie in 0..n. |

The signature that decides whether the insights changed leaves the progress counter out, which changes every second.

## 4. Rotation rules

`WaitingPanelController` (pure, explicit clock, `WaitingSchedulerTest`):

- The insights card is first, and returns whenever the insights signature changed since it was last shown; never twice in a
  row. Each showing lists the next 3 insights of the list (wrapping), so the same lines do not repeat.
- Other cards come from `WaitingDeck`: one seeded shuffle per kind, interleaved fact, puzzle, game, fact, player, puzzle; no card
  repeats until all have been shown once (about 130 cards).
- Dwell: 12 s for text and insights, 15 s for a game, 40 s for an untouched puzzle.
- A touch anywhere on the panel holds the rotation for 25 s. A puzzle the user has started and not finished is **never**
  rotated away. Pause stops it; going back in the history stops it (it resumes at the newest card).
- With TalkBack (touch exploration) on the panel does not rotate by itself at all (WCAG 2.2.2); the arrows still work.
- Swipe left/right or the 48 dp arrows move between cards; the history is kept, so Previous goes back to the same cards.

## 5. Puzzles

### Where they come from

Every puzzle is a decisive moment that was **played** in one of the app's own famous games. The first solution move is the
move the player made in the game; the card says so after the result ("The first move is the one played in the game"). Later
moves of a multi-move solution are the engine's, and the card does not claim the game went that way.

### Rules a puzzle must pass (`scripts/waiting_puzzles.py`)

Scores are centipawns for the side to move; engine Stockfish 19 (local Stockfish, one thread per position, fresh hash for every
position so the numbers do not depend on search order).

1. The first move is the move played in the game and the engine's first choice.
2. **Mate puzzle**: the engine sees a forced mate in N (2 to 4) and no other first move mates in N or fewer. For N up to 2 this
   is also proved **without the engine** by exhaustive search with python-chess, and `WaitingContentTest` repeats the proof with
   `:core`'s move generator (a different implementation again). Mates in 3 and 4 rest on the engine at two depths: a full-width
   proof in Python takes hours per puzzle.
3. **Other puzzle**: the best move scores at least +150 and the second-best line scores at least **150 centipawns less** (a pawn and
   a half). The two scores are stored in the file and printed after the result.
4. Not obvious: a depth-1 search (which includes the engine's capture search) does not already choose the answer. This removes mate in one and the plainest free captures and recaptures.
5. Rules 1 to 4 hold for the first move at depth 18 **and** again at depth 20, with the same solution line. The later steps of a
   multi-move solution (the reply and the next solver move) are checked at depth 16 by `mine`, and at depth 18 by `verify`, which
   also repeats the first-move check at depth 20 from scratch on 4 threads with a different hash size.
6. A multi-move solution continues only while the opponent's reply is forced or near-forced (only legal move; every other reply at
   least 150 cp worse for the opponent; or, in a mate puzzle, every reply loses to mate and the engine's longest defence is
   played) and the solver's next move again passes rule 2 or 3. At most 2 solver moves, or the line ends in checkmate (mates in
   up to 4).
7. At most 2 puzzles per game, at least 12 plies apart.

A wrong move is simply "not the best move here": by rules 2 and 3 every other move is at least 150 cp worse or does not mate.

### How to reproduce

```
python scripts/waiting_puzzles.py mine   --engine <stockfish.exe>   # about 40 minutes on 14 workers when the machine is not shared
python scripts/waiting_puzzles.py verify --engine <stockfish.exe>   # exit 1 on any failure
python scripts/verify_waiting_facts.py                              # the mate-length fact
```

### What came out

The miner screened every position of the 91 games (depth 14, about 40 minutes), found 500 candidates, and 111 passed depths 18 and 20.
Most rejections are rule 4: in a famous game the winning move is very often a plain capture. One or two per game were kept (73), and then
`verify --prune` re-checked them on 4 threads, which does not repeat the miner's single-thread numbers exactly: a puzzle whose margin or
whose reply sat near its threshold failed on some run and was removed. Pruning was repeated until two consecutive full `verify` runs
passed with no change. The file holds **47 puzzles from 40 games** (44 "best move", 10 of them with a second solver move; 3 mates in 2),
29 with White to play. The smallest best-versus-next-best margin stored is 158 cp, and the smallest seen in the last `verify` run 165 cp
(the median stored margin is 375 cp). Ids keep the numbering of the miner, so there are gaps.

The miner found 7 mate puzzles (five mates in 2, one in 3, one in 4). `verify --prune` removed four of them: the mate in 3 and the
mate in 4 (the opponent's reply was no longer the single best defence in the 4-thread run) and two mates in 2 (again a reply that was not the
only clear best defence). Famous games are mostly decided by material, which is also why most puzzles are "best move" puzzles.

### The puzzles

| id | game | move | to play | goal | solution | best / next best (cp) |
|---|---|---|---|---|---|---|
| p002 | The Immortal Game | 22 | White | mate in 2 | Qf6+ Nxf6 Be7# | - |
| p003 | Paulsen vs Morphy, New York 1857 | 18 | Black | best move | Rg6+ Kh1 Bh3 | 508 / -305 |
| p004 | Paulsen vs Morphy, New York 1857 | 28 | Black | best move | Be3 | 866 / 338 |
| p005 | The Opera Game | 16 | White | mate in 2 | Qb8+ Nxb8 Rd8# | - |
| p006 | Dubois vs Steinitz, London 1862 | 25 | Black | best move | R8h2+ Kf3 Rxf1+ | 644 / -212 |
| p007 | Dubois vs Steinitz, London 1862 | 32 | Black | best move | Rxb1 | 686 / 528 |
| p009 | The first world championship, game 1 | 37 | Black | best move | Qxf2 | 752 / 24 |
| p010 | The first world championship, game 20 | 16 | White | best move | Bxh6 | 595 / 423 |
| p011 | Lasker vs Bauer, Amsterdam 1889 | 17 | White | best move | Bxg7 Kxg7 Qg4+ | 710 / -409 |
| p015 | Lasker vs Napier, Cambridge Springs 1904 | 23 | White | best move | Rb1 | 248 / 36 |
| p018 | The Gold Coins Game | 20 | Black | best move | Ref8 | 535 / 0 |
| p019 | Lasker vs Capablanca, Saint Petersburg 1914 | 32 | White | best move | Rh3 | 558 / 317 |
| p020 | Réti vs Alekhine, Baden-Baden 1925 | 32 | Black | best move | Nxf2 | 336 / 47 |
| p021 | Alekhine takes the title, 1927 | 77 | White | best move | Re5 | 521 / 21 |
| p023 | The Peruvian Immortal | 13 | White | mate in 2 | Qxc6+ bxc6 Ba6# | - |
| p024 | The Pearl of Zandvoort | 45 | White | best move | Re6+ | 669 / 331 |
| p025 | Botvinnik vs Capablanca, AVRO 1938 | 25 | White | best move | Rxe1 | 324 / -51 |
| p026 | Botvinnik vs Capablanca, AVRO 1938 | 32 | White | best move | Qg5+ | 847 / -697 |
| p027 | The Uruguayan Immortal | 27 | Black | best move | Nde5 | 341 / 112 |
| p029 | The Game of the Century | 17 | Black | best move | Be6 Qxc3 Qxc5 | 266 / -142 |
| p031 | Botvinnik vs Tal, 1961, game 21 | 26 | White | best move | Rf1 | 534 / -49 |
| p032 | Botvinnik vs Tal, 1961, game 21 | 33 | White | best move | d6+ | 811 / 575 |
| p033 | Petrosian vs Botvinnik, 1963, game 19 | 64 | White | best move | Kh3 | 421 / 25 |
| p034 | Petrosian vs Spassky, 1966, game 10 | 27 | White | best move | Nxd6 | 567 / 205 |
| p035 | Spassky vs Petrosian, 1969, game 19 | 24 | White | best move | Ng5 Qxg5 Rxg5 | 728 / 476 |
| p036 | Larsen vs Spassky, Belgrade 1970 | 12 | Black | best move | h4 | 685 / 320 |
| p037 | Spassky vs Fischer, 1972, game 13 | 70 | Black | best move | f3 | 380 / 5 |
| p038 | Bagirov vs Gufeld, Kirovabad 1973 | 26 | Black | best move | Nd3 | 929 / 0 |
| p040 | Kasparov vs Karpov, 1986, game 16 | 37 | White | best move | d6+ Ke6 Re8+ | 725 / -626 |
| p041 | Kasparov vs Karpov, 1986, game 22 | 46 | White | best move | Qd4 Bh7 Qxc3 | 610 / -170 |
| p042 | Kasparov vs Karpov, 1987, game 24 | 32 | White | best move | Rxc8+ Nxc8 Qb5 | 550 / 11 |
| p043 | Kasparov vs Karpov, 1987, game 24 | 57 | White | best move | e5 | 399 / 167 |
| p045 | Kasparov's Immortal | 37 | White | best move | Rd7 Rxd7 Bxc4 | 490 / 1 |
| p047 | Anand vs Radjabov, Dortmund 2003 | 33 | Black | best move | e3 | 485 / 214 |
| p050 | Kramnik vs Topalov, 2006, rapid tiebreak | 41 | White | best move | Rb3 | 279 / 0 |
| p051 | Anand vs Kramnik, 2008, game 6 | 44 | White | best move | Nf6+ | 642 / 293 |
| p052 | Topalov vs Anand, 2010, game 12 | 32 | Black | best move | Qxe4+ | 581 / 44 |
| p053 | Topalov vs Anand, 2010, game 12 | 42 | Black | best move | Rxg3+ hxg3 Qg4+ | 676 / 0 |
| p057 | Anand vs Carlsen, 2013, game 6 | 61 | Black | best move | Rg6 | 466 / 148 |
| p058 | Anand vs Carlsen, 2013, game 9 | 28 | Black | best move | Qe1 | 684 / 0 |
| p063 | Carlsen vs Caruana, 2018, rapid tiebreak | 44 | White | best move | a4 | 321 / 105 |
| p065 | Ding vs Nepomniachtchi, 2023, game 4 | 29 | White | best move | Rxd4 | 274 / 70 |
| p067 | Nepomniachtchi vs Ding, 2023, rapid tiebreak | 52 | Black | best move | Rc6 | 510 / 317 |
| p069 | Gukesh vs Ding, 2024, game 11 | 29 | White | best move | Qxc6 | 675 / 308 |
| p071 | Deep Blue vs Kasparov, 1996, game 1 | 33 | White | best move | Nd6 | 589 / 10 |
| p072 | Kasparov vs Deep Blue, 1996, game 2 | 61 | White | best move | exf3 | 514 / 268 |
| p073 | Kasparov vs Deep Blue, 1996, game 2 | 72 | White | best move | Bxd7 | 634 / -843 |


## 6. Design reference (owner's chess.com rule)

Patterns taken from chess.com's game-review loading screen and its puzzles, in our own words and art:

- Loading: one tip card with a small muted kind label above the content while the bar runs; the tip is short and the screen never
  asks for attention. Taken: one card at a time, a kind label ("Chess fact", "Puzzle"), no sound, nothing that blocks the progress.
- Puzzles: the side to move is named above the board and the board is oriented to the solver; a wrong move gets a short message and
  the piece goes back; a hint or solution is on demand; once solved or revealed a clear "Next". Taken: the same flow (tap piece, tap
  square; "Show solution"; "Next"). Not taken: ratings, streaks, timers, the retry counter (Practise has none either).
- Famous-game and player cards use the same card, label and type scale as the rest of the app; the mini board is the existing
  `ChessBoard`, read-only.

## 7. Accessibility

- The board describes itself (`ChessBoard`: a read-only board is one stop listing the position; a puzzle board has 64 square
  nodes with a click action).
- Arrows and the pause button are 48 dp; the card text wraps at font scale 2.0 and the puzzle buttons are a `FlowRow`.
- The changing insights are **not** a live region (they change every second); every other card, and the puzzle feedback, is a polite
  live region, so a card the user moves to is read out.
- Landscape: puzzle and game cards put the board on the left and the text on the right.

## 8. Wiring

See the example in the hand-off report; in short: collect the `StateFlow<Map<Int, PositionEval>>`, keep a `LiveInsightEngine` in
`remember(sans)`, load the content with `produceState`, and pass both to `WhileYouWaitPanel`.
