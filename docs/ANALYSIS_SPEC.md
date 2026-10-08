# ANALYSIS_SPEC — authoritative spec for move scoring, tactics, and rating

Implementations in `:core` MUST follow this document exactly. It exists so that the
classification, accuracy, tactics, and rating modules agree on one model.

All formulas that approximate chess.com are labelled **[approximation]**. Chess.com's
exact internals are proprietary and partly undocumented; the published pieces of their
accuracy model are used where known, and the rest is calibrated to behave the same way
at the boundaries. The app must describe these as estimates in the UI.

---

## 1. Evaluation normalisation

Engine scores come back as centipawns (`cp`) or `mate in N`.

**Perspective — read this carefully, it is the easiest thing here to get subtly wrong.**
UCI reports scores relative to the **side to move**, and `EngineLineInput.scoreCp` /
`mateIn` in `Contract.kt` preserve that convention unchanged. Converting to a
**White-relative** value happens only at the storage/display boundary — the
`evalBeforeCp` / `evalAfterCp` fields and the `evalGraph` — using the side to move
derived from the position's FEN, never assumed from the ply index.

(An earlier draft of this section said to store everything White-relative, which
contradicted `Contract.kt`. `Contract.kt` is authoritative; this paragraph is the
corrected rule.)

Mate scores map to a saturating centipawn value:

    cpFromMate(n) = sign(n) * (MATE_CP - min(abs(n), 40) * MATE_STEP)
    MATE_CP = 10000, MATE_STEP = 50

### 1.1 Win probability [approximation — this sigmoid constant is chess.com's published one]

    winPercent(cp) = 50 + 50 * (2 / (1 + exp(-0.00368208 * clamp(cp, -1000, 1000))) - 1)

Result is 0..100, from the perspective of the side specified.

For a mate score, winPercent is 100 (for the mating side) / 0 (for the mated side).

### 1.2 Win-percent loss for a played move

For a move played by side S at ply p:

    before = winPercent(evalBefore, perspective = S)
    after  = winPercent(evalAfter,  perspective = S)
    loss   = max(0, before - after)

`evalBefore` = engine eval of the position before the move (depth D, MultiPV k).
`evalAfter`  = engine eval of the position after the move (depth D), negated to S's perspective.

**Important:** `evalBefore` must be the eval of the *best* move, and `evalAfter` the eval of
the *played* move. Never compare evals taken at different depths — analyse every position
at the same configured depth or the deltas become noise. The one exception is a position the
per-position search budget stopped early (§8.1): it is flagged as capped, never silently mixed in.

---

## 2. Move classification

Enum `MoveClassification`: BRILLIANT, GREAT, BEST, EXCELLENT, GOOD, BOOK, INACCURACY,
MISTAKE, MISS, BLUNDER, FORCED.

Evaluate the rules **in this order**; first match wins.

1. **FORCED** — the side to move has exactly one legal move.
2. **BOOK** — the position (before the move) is within the bundled opening book AND the
   played move is a book move AND ply <= 20.
3. **BRILLIANT** — all of:
   - the move is a **sacrifice**: it gives up material by Static Exchange Evaluation
     (`see(move) <= -200`, i.e. a minor piece down or more, OR it leaves a piece worth
     >= 300cp en prise to a legal capture next move), and
     **The capture must really be legal** (R1b): a piece whose only attacker is pinned to its king
     is not en prise. 14.Rd1 in the Opera Game was labelled Brilliant for "offering" a rook to a rook
     on d7 that Bb5 pins to the king - Rxd1 is illegal there - and is now an ordinary BEST move.
     (The SEE the classifier consults is pseudo-legal, so the classifier checks legality itself.)
   - after the move the side is **not losing**: `winPercent(after, S) >= 50`, and
   - the move is best or near-best: `loss <= 2.0`, and
   - the sacrifice is **not trivially recaptured for equal value** — the refutation must
     genuinely lose material for the opponent (verify: the engine's best reply is NOT the
     capture, or taking loses by >= 100cp), and
   - the position was not already completely winning (`winPercent(before, S) < 97`).
4. **GREAT** — the move is best AND it is the **only** move that preserves the outcome:
   the gap between MultiPV line 1 and line 2 in win% is >= 10, and `loss <= 2.0`.
   (Requires MultiPV >= 2.)
5. **MISS** — the side had a **forced win or mate** available and threw it away:
   `before >= 90` (or mate available) AND `after < 75`, OR mate-in-N was available and the
   played move does not mate. MISS outranks MISTAKE/BLUNDER for these cases.
6. **BEST** — the played move equals the engine's top move (MultiPV 1).
7. **EXCELLENT** — `loss < 2.0`
8. **GOOD** — `loss < 5.0`
9. **INACCURACY** — `loss < 10.0`
10. **MISTAKE** — `loss < 20.0`
11. **BLUNDER** — `loss >= 20.0`

**Guard:** in already-decided positions (`winPercent(before,S) >= 98` or `<= 2`) do not
label BLUNDER/MISTAKE for moves that keep the result — clamp to GOOD. This mirrors
chess.com not punishing sloppy moves in a totally won or lost game.

---

## 3. Accuracy

### 3.1 Per-move accuracy [approximation — chess.com's published curve]

    moveAccuracy = clamp(103.1668 * exp(-0.04354 * loss) - 3.1669, 0, 100)

### 3.2 Game accuracy per player [approximation]

Chess.com blends a volatility-weighted mean with a harmonic mean. Implement:

1. Compute the per-move accuracies for that player's moves.
2. **Volatility weight** per move = standard deviation of `winPercent` over a sliding
   window of the surrounding plies (window = max(2, ceil(totalMoves/10)) each side),
   clamped to [0.5, 12].
3. `weightedMean = sum(acc_i * w_i) / sum(w_i)`
4. `harmonicMean = n / sum(1 / max(acc_i, 1))`
5. `accuracy = (weightedMean + harmonicMean) / 2`, clamped to [0, 100], reported to 1 dp.

Exclude BOOK moves from accuracy.

---

## 4. Estimated performance rating [approximation — labelled as an estimate in the UI]

Map game accuracy to an estimated Elo with a monotonic piecewise-linear curve through
these anchors:

    accuracy 60 -> 800      accuracy 80 -> 1500
    accuracy 70 -> 1100     accuracy 85 -> 1800
    accuracy 75 -> 1300     accuracy 90 -> 2100
                            accuracy 95 -> 2500

Extrapolate linearly outside the anchors, clamp to [100, 3000].

Games under 20 plies have too little signal — return the estimate with
`lowConfidence = true` and the UI must show "not enough moves".

Also compute, per player, the count of each classification — that table is the game report.

---

## 5. Tactics and patterns

### 5.1 Taxonomy

`TacticType`: FORK, DOUBLE_ATTACK, PIN_ABSOLUTE, PIN_RELATIVE, SKEWER, DISCOVERED_ATTACK,
DISCOVERED_CHECK, DOUBLE_CHECK, HANGING_PIECE, TRAPPED_PIECE, DEFLECTION, DECOY,
OVERLOADED_PIECE, INTERFERENCE, CLEARANCE, ZWISCHENZUG, BACK_RANK_MATE, SMOTHERED_MATE,
GREEK_GIFT, WINDMILL, X_RAY, PROMOTION_TACTIC, UNDERPROMOTION, PASSED_PAWN_BREAKTHROUGH,
PAWN_FORK, REMOVING_THE_DEFENDER, MATE_NET, PERPETUAL_CHECK, STALEMATE_TRICK,
DESPERADO, BATTERY, FORTRESS.

Each detected instance is a `TacticInstance(type, byColor, moveUci, targetSquares,
involvedSquares, materialSwing, description, confidence)`.

### 5.2 Required primitives (implement in `core.tactics`)

- `attackMap(position, color): Map<Square, List<Square>>` — who attacks what.
- `see(position, move): Int` — Static Exchange Evaluation in centipawns. Standard swap
  algorithm with x-ray re-evaluation. Piece values: P=100, N=320, B=330, R=500, Q=900, K=20000.
- `isHanging(position, square): Boolean` — attacked by the enemy and the cheapest capture
  has `see >= 0` for the attacker.
- `lineBetween(a, b)`, `isOnLine(a, b, c)` for pins, skewers and x-rays.

### 5.3 Detection rules (applied to the position AFTER the candidate move)

- **FORK** — the moved piece now attacks >= 2 enemy units where at least two are
  (the king) or (value >= the moved piece's value) or (undefended).
  `PAWN_FORK` when the forker is a pawn. **The forker must survive to cash it in** (Round 13):
  when the opponent can take it and not lose material by SEE — an even trade counts, because the
  threats die with it and nothing was won by them — what happened is a capture or an exchange, not
  a fork. (Bxd7+ Nxd7 "forked" king and queen with a bishop that was simply taken; the first
  reference pawn fork, 1.e4 against a knight and a bishop, failed the same test because the
  forked bishop took the pawn.)
- **DOUBLE_ATTACK** — >= 2 enemy units attacked as a result of the move (including via
  discovery) that were not both attacked before. When the moved piece is the *only* new attacker
  it must survive, by the same test as a fork.
- **PIN_ABSOLUTE** — an enemy piece on a line between our slider and the enemy king.
  **PIN_RELATIVE** — same, but the rear piece is merely more valuable.
- **SKEWER** — our slider attacks an enemy piece that is **at least as valuable as the enemy
  piece directly behind it** on the same line, so that moving the front piece exposes the rear
  one. The front piece must be worth a rook or more, or be the king.
  (An earlier draft of this line described the front piece as *less* valuable than the rear one,
  which is the definition of a relative pin and made SKEWER and PIN_RELATIVE overlap. A skewer is
  the inverse of a pin; this corrected wording makes the two partition cleanly.)
- **DISCOVERED_ATTACK / DISCOVERED_CHECK** — the moved piece vacated a line, exposing an
  attack from a friendly slider onto an enemy piece or king. **DOUBLE_CHECK** when the
  moved piece also gives check.
- **HANGING_PIECE** — an enemy piece is hanging and worth >= 300, or any piece whose
  capture wins material by SEE. Its description says what the detector checked and no more:
  "The pawn on e5 is attacked and nothing defends it." / "The knight on f5 is attacked, and taking
  it would win material." The opponent moves next and may save the piece, so the earlier wording
  ("is left hanging and can be taken for nothing", "cannot be held") promised a future the
  detector never examined (R1b).
- **BACK_RANK_MATE** — mate (or a mate threat within 2) on the 1st/8th rank where the
  enemy king's escape squares are blocked by its own pawns.
- **SMOTHERED_MATE** — knight mate with the king fully surrounded by its own pieces.
- **DEFLECTION / REMOVING_THE_DEFENDER** — the move attacks or captures a piece whose
  removal leaves another enemy unit or key square undefended, and the engine PV exploits
  exactly that. A deflection needs the defender to **end up somewhere that no longer guards**
  the square (R1b): a defender that simply captures *on* that square (4...Nc6 exd6 Qxd6: the pawn
  takes on d6) was exchanged there, not deflected from it, and one that still sees the square from
  its new square was not deflected at all.
- **DECOY** — the move (usually a sacrifice) lures an enemy piece or king to a square where
  it is then forked, skewered or mated in the PV.
- **OVERLOADED_PIECE** — an enemy piece is the sole defender of >= 2 things and the PV
  exploits it.
- **INTERFERENCE / CLEARANCE** — the move blocks an enemy line / vacates a friendly line
  and the PV uses it. A clearance gets **another piece** through (R1b): the piece that just
  moved coming back along the line it left clears nothing for anyone (5.Bxb5 ... Be2), and a pawn
  stepping onto the square the moved piece vacated is not using a line (9.Nf5 ... h4).
- **TRAPPED_PIECE** — an enemy piece worth >= 300 with no safe square (every legal
  destination loses material by SEE). **Not reported when the move gives check** (Round 13): while
  the king is in check the legal-move list is whatever answers the check, so every other piece
  looks "trapped" simply because it is not allowed to move. A checking move traps nothing.
- **ZWISCHENZUG** — an in-between move (check or larger threat) inserted before the
  expected recapture, per the engine PV.
- **MATE_NET** — the engine reports mate in <= 5 and the move is part of it.
- **GREEK_GIFT** — Bxh7+/Bxh2+ sacrifice with Ng5+/Ng4+ and Qh5/Qh4 follow-up in the PV.
- **WINDMILL** — a repeating discovered-check-plus-capture cycle in the PV (Torre–Lasker, 1925).
  Inside one unbroken run of the mover's checks it requires: at least **4 consecutive checking
  moves** (`WINDMILL_MIN_CHECKS`), every reply in the run a king move; at least **2 discovered
  checks** (the checking piece is not the one that moved) and at least **2 captures**; and **the
  same piece returning to a square it already landed on** — the blade coming round again, not two
  different pieces visiting one square. (The first rule, "a slider lands on one square twice, two
  captures, three checks somewhere in the PV", accepted any long forcing line: 15.Bxd7+ was a
  "windmill" because a bishop and, later, a rook both landed on d7.)
- **PROMOTION_TACTIC, UNDERPROMOTION, PASSED_PAWN_BREAKTHROUGH, DESPERADO,
  PERPETUAL_CHECK, STALEMATE_TRICK, FORTRESS, BATTERY, X_RAY** — detect per their standard
  definitions; these are lower priority and may report lower confidence.

The skewer description says the piece behind "is attacked" when the front piece moves, not that it
"falls" (R1b): whether it falls depends on whether it is defended.

**Whose motif it is (R1b).** `TacticInstance.byColor` and `moveUci` say which side's *which move* the
motif belongs to, and every consumer must respect both: a motif found for the played move belongs to
the mover, a missed motif to the engine's best move, a threat to the opponent's best reply. The
commentary (§7) discards a motif that is not owned by the side, or not about the move, that the sentence
is about. Detectors that replay the engine's line read "our" moves and "their" moves off the line by
position, so a line in which the mover is being mated must not be credited to the mover: §7.2.

**Confidence:** a tactic detected purely by static pattern = 0.6. If the engine PV confirms
the follow-up (the PV plays the exploiting move within 4 plies) = 0.95. Only report tactics
with confidence >= 0.6; sort by confidence, then by materialSwing.

**Reporting (Round 13).** The detectors are deliberately generous: one move routinely trips several
of them for the same underlying fact. A real game had 17.Rd8# tagged Fork, Double attack, Hanging
piece and Skewer on top of the mate. `MotifDetector.detectRaw` returns everything the rules above
recognise (the detector tests and the reference corpus assert against it); `MotifDetector.detect`,
which the app calls, reduces it:

1. **A checkmating move carries its mating pattern and nothing else** — `BACK_RANK_MATE`,
   `SMOTHERED_MATE`, `MATE_NET` (a plain checkmate is a `MATE_NET`).
2. **A motif that merely restates another on the same move is dropped:** a `HANGING_PIECE` on a
   square another motif already names *and accounts for at least as much material* (the fork or pin
   is the mechanism, the hanging piece its consequence — a zero-swing x-ray explains nothing); a
   `DOUBLE_ATTACK` whose targets a `FORK`/`PAWN_FORK` on the same move already covers; a plain
   `PROMOTION_TACTIC` next to the `UNDERPROMOTION` that is the interesting half of the move.
3. **Rank and cap.** Mating motifs first, then confidence, then `materialSwing`; **at most 2
   survive per move** (`MAX_TACTICS_PER_MOVE`).

The §9.6 significance gate then runs on this reduced list, unchanged.

### 5.4 Found vs missed — the four required buckets

For every ply, with S = side to move and O = opponent:

- **RECOGNISED BY MOVER** (`foundByPlayer`) — the played move is classified
  BEST/GREAT/BRILLIANT **and** >= 1 tactic is detected for the played move.
- **MISSED BY MOVER** (`missedByPlayer`) — the engine's best move yields >= 1 tactic, the
  played move differs from it, and `loss >= 5.0` (missing it actually cost something).
  Record the missed tactic AND the engine PV that demonstrates it.

The same two buckets are produced for the opponent by running the identical per-ply
analysis over the opponent's plies. The UI groups by `byColor` against the configured user
colour, giving: **recognised by user / missed by user / recognised by opponent / missed by
opponent.**

Additionally record **THREAT_ALLOWED**: the played move allows the opponent a tactic on the
next ply (detected by running detection on the opponent's best reply). This drives
"you allowed a fork" commentary.

---

## 6. Missed-tactic simulation flow

For any `missedByPlayer` / `missedByOpponent` entry the UI can enter a guided simulation:

    TacticSimulation(startFen, pvUci: List<String>, perPlyExplanation: List<String>, tactic)

- Built from the engine PV at that ply, truncated to at most 8 plies or until the tactic's
  material or mate payoff is realised.
- Each ply gets: the move in SAN, an arrow to draw, and a generated explanation sentence
  (e.g. "Nf6+ forks the king on g8 and the queen on d5.").
- The user steps forward and back; the final frame states the payoff ("wins a rook",
  "mates in 3").
- The simulation must be replayable from the same start position and must never mutate the
  main game line.
- The lead-in shown on the starting position is `SimulationIntro` (typed `Sentence.WalkthroughIntro`):
  "Watch what happens: h5 starts the line." followed by the tactic's own description as a **separate,
  normalised sentence** (one capital, one full stop). A description is a finished sentence and may
  open with a move ("Qxa1+ clears b2...") or a square ("h5 forks..."), so it is never lowercased and
  grafted into a clause ("a line that The pawn... .."). With no description the line names the
  tactic type instead. **A description that opens with the line's first move carries the whole
  lead-in** (R1b): "Watch what happens. Qb4+ clears e7 so that Bxb4+ can come through." The
  "starts the line" half is dropped rather than said twice ("Qb4+ starts the line. Qb4+ clears...").
  Only the opening of the description counts: a pawn move is spelled like a square, and "the pawn on
  e4" in the middle of a sentence is not the move e4.

### 6.1 What a walkthrough may claim (R1b)

A walkthrough is read as fact, so each sentence is something the board proves:

- **A step names what the move does, no more.** The piece lands, captures, castles; it "attacks" what
  it attacks (the king, or a unit worth at least the mover). A HANGING_PIECE motif says "attacking
  the pawn on e5, which nothing defends" - only when the target really is attacked by the mover and
  undefended - never "collecting" it: the opponent moves next.
- **"Winning ..." is the exchange evaluator's verdict on the capture, and a recapture is judged by the
  pair.** `Qxd6` straight after `exd6` takes back a pawn: what counts is what the side took minus what
  it had just lost, so an even pair says nothing and a pair that wins material says so. (Exd6 Qxd6 Qxd6
  Bxd6 used to read "winning a pawn", "winning a queen" on the two recaptures.)
- **A gain is named only when it is within 40 cp of a whole piece** (`GAIN_TOLERANCE_CP`): queen 900,
  rook 500, piece 325, pawn 100. Anything between is "material": a rook taken for a bishop (+170) is
  not "a pawn", and a queen taken by a pawn that is then recaptured (+800) is not "a rook".
- **"The exchange" is counted in pieces, not in centipawns (C1).** A single capture "wins the exchange"
  when a knight or bishop takes a rook and the swap-off nets `rook - minor` (130..220 cp,
  `ExchangeEvaluator.describeCapture`); a line wins it when, between its start and its settled end
  (`settledPosition`: the opponent's best take-back played when it is their move), the winner has given
  up exactly one minor piece and taken exactly one rook with no other change in queens, rooks, minors or
  pawns (`winsTheExchange`). A bishop for two pawns is worth about the same and is "material".
- **The payoff is what the line proves, settled.** A line that ends in checkmate says "mates in N".
  Otherwise it is the material the winning side has netted between the start and the end of the
  line - *after the opponent has taken back* the most the exchange evaluator says it can
  (`ExchangeEvaluator.settledGain`), so a line that stops right after a capture is not credited with a
  piece that is taken straight back - named by the rule above. The drawing motifs (perpetual,
  stalemate trick, desperado, passed pawn) keep their one-line outcomes, and a reference example keeps
  the corpus-verified sentence of `TacticReferenceLibrary`.
- **A line that neither mates nor nets material has no payoff** (`payoffDescription == ""`): nothing is
  appended to the last step and the screen shows no "Result" row. The Round 13 fallbacks - "has
  invested material in the attack", "gains a decisive advantage" - were not supported by anything in
  the data and are gone, as is "keeps the king under fire" (an engine-confirmed MATE_NET keeps "leaves
  the king in a mating net": the PV that confirmed it ends in mate).
- The narrated video follows the same rule: its payoff beat ends "That is as far as the line goes."
  (`PayoffKind.LINE_ENDS`) instead of those two sentences, and "for nothing" is gone from
  "You come out of it a rook up".

### 6.2 The best line on the Board (V2)

Added in Round 14 for the owner's "the alternate best tactics is not simulated and only appear with color
arrow". Before V2 the engine's better move was an arrow on the Board, a sentence on the Summary, and an
arrow in the video; only a move with a detected missed motif (§5.4) had a walkthrough (§6), so a mistake
whose best line won nothing a detector named, every inaccuracy, and every key moment that was not a miss
showed no sequence at all. `MoveAnnotation.candidateLines` held only each line's first move; V2 adds its
whole PV and its depth (`CandidateLine.pvUci`, `CandidateLine.depth`, from the position's one depth, §8.2).

**What is shown (`core.analysis.BestLines`, `BestLine`).**
- From the position BEFORE the move, the PV of MultiPV line 1, replayed with the core move generator; it
  stops at the first move that is not legal, and a line whose first move is illegal is no line.
- **At most `min(PV length, depth / 2, 8)` plies, and nothing after a checkmate.** Stockfish's PV is longer
  than its search depth (on the five recorded games, the best line before every move that lost at least 5
  win-percent was 8 to 29 plies at depth 12 to 20, longer than the depth in 38 of 41 cases): its tail comes
  from extensions and the quiescence search. A move at ply k was chosen with about `depth - k` plies of
  search below it, so `depth / 2` keeps every shown move backed by at least half the search: 6 plies at
  Quick (12), 7 at Standard (14), 8 at Deep (18). 8 is the walkthrough's cap (§6), so the two agree. An
  unknown depth (0) shows the first move only. "Up to the point the tactic resolves" was measured and
  rejected: the material in those PVs kept changing until ply 12 in the median case (last capture between
  ply 0 and 25), so "resolved" is no stable point inside what the depth supports.
- **Lines 2 and 3** are offered beside it when the engine rates them within `ALTERNATIVE_MARGIN` = 2.0
  win-percent of line 1 (the best-or-near-best bound of §2 and §11, `PracticeSelector.ACCEPT_LOSS`), cut by
  the same rule, and never when they start with the move actually played.
- **Which moves offer it:** every INACCURACY, MISTAKE, MISS and BLUNDER, and every key moment the Summary
  lists (whatever its class), when the annotation has a line. A key moment whose move WAS the engine's
  choice shows "The engine's line from 8. h3" instead of "Best line instead of ...".

**The caption (`BestLineCaption`, rules of §6.1 and §7.2; checked in `CommentaryClaimsTest` and by
`scripts/audit_commentary.py lines`).**
1. The engine's number, said as the engine's: "The engine rates this line +2.3." (White-relative, §9.4), or,
   for an engine mate, "The engine sees a forced mate in 3 for White." (the winner is the mover for a
   positive mate, the other side for a negative one). A score is never called a win.
2. What the shown plies prove on the board: "The line ends in checkmate." when its last move mates;
   otherwise, when the material the mover has netted, settled (`ExchangeEvaluator.settledGain`: the
   opponent's best take-back charged when it is their move), is at least 100 cp, "In this line White wins a
   piece." named by the 40 cp rule of §6.1, "the exchange" by its piece count (§6.1, C1), "material" between two piece values. Nothing for a line that
   nets nothing or gives material up.
3. Who: "you" / "your opponent" for a chosen side, colours otherwise (§7 rule 4).

**The Board's line mode (`ReviewScreen`).** "Show the best line" on the comment card (and, from a Summary
key moment that has no walkthrough, "Show the best line" opens the Board straight into it). The board plays
the line on the same screen with the shared line player (`ui.components.LinePlayer`, also the Walkthrough's):
Back / Next / Play-pause (Play at the video's line rate, Settings, Video, Pace), the move just played named
with its number ("12... Qg6") and who is to move, the move just played highlighted and the next one as a
green arrow, a "2 / 7" counter, the eval bar on the line's score, the line in move-number notation with the
current move marked, the caption, "Engine depth 14", chips for the alternatives, and "Back to the game"
(the system back does the same). Stepping the game leaves the mode.

**Reference (chess.com, pattern only).** Game Review's "Show" plays the engine line from the position of
the mistake and "Best" reveals the better move (docs/CHESSCOM_REFERENCE_ALIGNMENT.md, S1/S3/S7/S14); the
analysis board lists the engine's top lines with their scores and steps through any of them move by move.
Taken: play the line from the position before the move on the same board, step it forward and back or let
it play, show the top lines with their evaluation, keep the notation with move numbers, a way back to the
game. Our own words, layout and art.

---

## 7. Commentary generation

Each ply produces `MoveAnnotation`:

    classification, loss, evalBefore, evalAfter, bestMoveSan, bestLineSan: List<String>,
    tacticsFound: List<TacticInstance>, tacticsMissed: List<TacticInstance>,
    threatsAllowed: List<TacticInstance>, tacticsPlayed: List<TacticInstance>,
    text: String, simulation: TacticSimulation?

`tacticsPlayed` (R1b) is the detector's raw output for the move actually played, whatever its class;
`tacticsFound` is that list filtered to BEST / GREAT / BRILLIANT (§5.4). Keeping the raw list is what
lets the text be written again later (§7.1).

`text` is generated deterministically by `CommentaryGenerator` from the classification, the motifs and
concrete squares and pieces read off the position, and from the engine's own numbers. It is shown as
fact, so **a claim is only made when the data proves it, and a sentence that cannot be made reliably
true is dropped, not hedged** (§7.2). Examples of what comes out:

- BLUNDER, the opponent's reply wins: "This lets White play Qxa1+, which wins a rook. Better was Re1."
- BLUNDER that allows mate: "This lets White play Nxg7+, which starts a forced mate. Better was Ba6."
- INACCURACY, a better move that attacks a loose piece: "Nh5 gives back ground. Better was c6, which
  attacks the undefended bishop on b5."
- BRILLIANT: "Nxb5 is a sacrifice: it offers the knight on b5."
- MISS + mate: "Better was Qb8+, forcing mate in 3."
- BEST with a motif: "Qb3 matches the engine's top choice. This pins the pawn on b7 to the knight on b8."

The shapes, per class (each lead has two or three phrasings, chosen by the rule in §7.3; the first is shown):

| Class | Text |
|---|---|
| FORCED, BOOK | "X was the only legal move." / "X follows known opening theory." |
| BRILLIANT | the sacrifice lead (below), then what the move does |
| GREAT | "X was the only move that kept things on track." (an *only move*: the MultiPV gap of §2), then what the move does |
| BEST, EXCELLENT, GOOD | one lead sentence about the engine's verdict, then what the move does |
| INACCURACY, MISTAKE, BLUNDER | what went wrong (the opponent's reply, or "X gives back ground." in the register of the class), then the evaluation in words when the move crossed a band ("That takes White from winning to about level.", §7.3), then "Better was Y[, which ...]." |
| MISS | the evaluation in words when a band was crossed, then "Better was Y, forcing mate in N." / "..., keeping a winning position." (below 95 win-percent) / "..., keeping a decisive advantage." (from 95) |

Rules every template obeys:

1. **The text never opens with the classification's own name.** The app shows the classification as a
   badge and a label beside the text (comment card, key-moment cards), so "Blunder. This drops the
   knight..." said the same word twice. The text goes straight to the piece and the threat. (chess.com's
   coach text works the same way: the icon carries the label.)
2. **"Better was X" is the final sentence, and appears once**, for INACCURACY, MISTAKE and BLUNDER.
   What the opponent's reply wins ("This lets White play Nxg7+, ...") comes *before* it. A MISS
   carries the better move inside its one sentence. The app hides its own structured "Better was X"
   line whenever the text already contains it.
3. **Generated names agree with their article.** "a"/"an" is decided by `EnglishGrammar` on the first
   *sound* of the name ("an underpromotion", "an undefended bishop"), never by `"a " + name`.
4. **Who it is about.** The viewer is "you", the other side "your opponent" ("This lets you play h4,
   which ..."); with no side chosen, or "Not me", both are named by colour ("This lets White play h4,
   which ..."). The text contains no other side-specific wording, so it can be rewritten for any side.

The annotation text is **not** part of the narrated video: the video says its own sentences from typed
facts (`NarrationStrings`), so these rules do not touch it.

No LLM at runtime - all commentary is template-generated and works offline.

### 7.1 The text follows the side the user chooses (R1b)

The analysis writes the text before the user has said which side they played, so it names colours.
Whenever the side becomes known - detected from the username, answered on the Summary ("Which side
were you?"), or "Not me" - the app writes it again with `CommentaryGenerator.regenerate(report,
userColor)` and rebuilds the move cards and the key-moment cards from the result, so they say "you /
your opponent" exactly like the Summary's buckets and headings. This needs the engine and the detector
not at all: the text is a pure function of the annotation (its two FENs and UCI move, the engine's
numbers, `tacticsPlayed` / `tacticsMissed` / `threatsAllowed`, `bestMoveSan`, mate distances) and of the
viewer's colour. `GameAnalyzer` writes the text through the very same function, so the text produced
at analysis time and the text produced on a side change are identical for the same side (tested for
both recorded games and all three sides). With no side, or "Not me", the neutral colour wording stays
(and comes back after a side was chosen). Only wording changes: classifications, evaluations, motifs,
simulations, the buckets and the one-sentence summary's inputs are untouched.

### 7.2 Every claim is verified (R1b)

`docs/COMMENTARY_AUDIT.md` audits every text of the two recorded games claim by claim; this is the
rule set it produced. A sentence is produced only when its claim is verified on the board (or in the
engine's own numbers) at generation time:

- **Whose motif.** What the played move did comes from the mover's own motifs *on that very move*
  (`byColor` and `moveUci` both match). What a better move would have done comes from the best move's
  motifs and is said about that move: "Better was c6, which attacks the undefended bishop on b5." -
  never "This drops/pins/forks ...", which credited the played move with it ("Better was Ba6" after a
  move that "forces mate"). What the opponent now has comes from the opponent's motifs, said about the
  opponent's reply: "This lets White play Qb3, which pins the pawn on b7 to the knight on b8."
- **Said no stronger than the board shows.** A move that merely attacks a loose piece "attacks" it
  ("attacks the undefended pawn on e4", "attacks the queen on g5 with a pawn", "attacks the pawn on g4
  more often than it is defended", "leaves the pawn on g7 undefended, with the knight on f5 attacking
  it"): the opponent moves next. Only a **capture of a piece that exchange evaluation proves wins
  material, and that is not just taking back**, "wins" anything ("Better was Qxa1+, which wins a rook").
  A pawn capture is never claimed (it may regain a pawn lost a move earlier). A recapture wins nothing
  by itself.
- **Re-checked on the position.** Pins, skewers, forks, double attacks, discoveries, double checks,
  promotions and the mating patterns are re-verified geometrically on the position after the move; a
  motif that does not survive is not mentioned. "Leaves the bishop on g1 with no safe square" requires
  every legal move of the piece to lose material by exchange. A motif is mentioned only if it is
  engine-confirmed (confidence >= 0.95) or wins material (swing >= 100): a static relative pin that
  wins nothing is not worth a card.
- **The engine's line is said as the engine's line.** Deflection, decoy, clearance, removing the
  defender, interference, the Greek gift and the windmill are proved by replaying the engine's PV,
  so they read "In the engine's line, Rxd7 drags the knight on f6 off the same diagonal, and Bxe7
  follows." / "Better was Qb4+; in the engine's line it clears e7 so that Bxb4+ can come through." -
  the opponent may reply differently.
- **"Allowed" is a charge, made only against a move that cost something.** INACCURACY, MISTAKE and
  BLUNDER only, and only when the opponent's reply mates or wins material (swing >= 100), and the
  reply is a legal move that the engine's line makes. A move the engine chose, or rated great or
  brilliant, never "allows" anything. A forced mate against the mover is "This allows a forced mate."
  when no motif names the reply. A move after which the opponent has a forced mate is not credited
  with the motifs of that line (they are the opponent's combination).
- **A sacrifice is called one only when it is one.** BRILLIANT says "X is a sacrifice: it offers the
  knight on b5" when the opponent can really take a piece of the mover's for a net gain of at least 200
  cp (the classifier's own `see <= -200`); "X leaves the rook on d1 open to capture, and the engine
  still rates it among the best moves" when it is an even trade; and "X is among the engine's best
  moves here" when nothing can be taken (the classifier requires a *legal* capture, §2).
- **Unprovable additions are not made**: "keeping material level", "stunning", "the point becomes clear
  a few moves later", "sets up a clearance on e2" (a motif's name with a square) are gone.
- **A professional term is used only where its definition is proved (C1, `docs/COMMENTARY_STYLE.md`).**
  The vocabulary-to-proof table there is binding: "fork" / "pawn fork" (the moved piece attacks every
  named target; a pawn for the pawn fork), "absolute pin" (the rear piece is the king) and "relative
  pin" (the rear piece is worth more), "skewer", "discovered attack" / "discovered check" (a new line
  through the vacated square), "double check" (two checkers), "en prise" and "loose" (attacked by the
  mover, no defender), "trapped" (every legal move of the piece loses material by exchange), "wins the
  exchange" (§6.1), "zwischenzug" (the move gives check or captures something worth more than the mover,
  a capture that does not lose material was waiting on an enemy piece worth a minor or more, and the
  engine's line makes it on the mover's next move or the one after; said as the engine's line),
  "overloaded" (on the position before the move the named piece is the opponent's and the sole defender
  of two of its pieces that the mover attacks, and the engine's line lands on one of them; said as the
  engine's line), "desperado" (the mover's piece, worth a minor or more, could have been taken where it
  stood if the opponent had the move, the capture loses material by exchange, and the piece can still be
  taken where it landed), a back-rank mate "threat" (the king on its back rank, every forward square
  held by its own men with at least one pawn among them, and a rook or queen move onto that rank would
  mate if the opponent could pass; the move named is the first such move in UCI order), "forced mate in
  N" (the engine's own mate distance: before the move for the mover's and the better move's mate,
  after the move for the reply's), "only move" (GREAT: the engine's top move with a MultiPV gap of at
  least 10 win-percent, which is the "real ground" of §9), and the evaluation words (§7.3). Terms whose
  proof is not on the board or in the numbers are not used: "simplifies", "liquidates", "trades into a
  winning endgame", "loses a tempo", "converts", and any plan, idea or intention.

### 7.3 Variety and the evaluation in words (C1)

**Variety is deterministic.** Every template has two or three phrasings (`docs/COMMENTARY_STYLE.md` lists
them all). Which one a card gets is `Variety(ply).index(template, n) = (ply + hash(template)) mod n`, with
`ply` read off the position (`CommentaryGenerator.plyOf`) and `hash` Java's `String.hashCode` of the
template's key. So: the same game always produces the same text (the narration cache is keyed by text, and
the audits replay the recordings); two consecutive cards that use the same template never share a
phrasing, because the index steps with the ply; and different templates on one card start at different
offsets. The second sentence of a praised move says "This ..." or "It ..." and never repeats the move the
lead just named. No randomness, no clock, no game-level state: the text stays a pure function of the
annotation and the viewer's side (§7.1), and `regenerate` is byte-identical with the analysis-time text.

**Tone follows the class.** A blunder or a mate gets short sentences ("Kb1 throws a big chunk of the
position away."); an inaccuracy a calmer register ("Be3 is not the most precise."); a mistake sits between
("b5 goes wrong."). Each "wrong" wording is a reading of the loss band that defines its class (§2: at least
5, 10 and 20 win-percent) and of the severity words of §9 ("real ground" is a loss of 10 or more, "a big
chunk" 18 or more).

**The evaluation in words.** An error (INACCURACY, MISTAKE, BLUNDER, MISS) that moved the mover's
win-percent across one of the bands of §9 (`NarrationVocabulary.standing`) says so, mover-relative, with
the same words the narrated video uses: decisively winning (95 and up), winning (82), clearly better (68),
slightly better (57), about level (43), slightly worse (32), clearly worse (18), losing (5), decisively
lost. "That takes White from winning to about level." / "The position swings from winning to about level
for White." / "From winning to about level in one move: that is what this cost White." No sentence when the
move stayed inside one band, never on a move the engine approved of, and never a band the numbers do not
give. The sentence has no verb that agrees with its subject, so the "you" form is the colour form with the
subject word changed (§7 rule 4). The MISS wording keeps "a winning position" below 95 and "a decisive
advantage" from 95 (a MISS needs 90 win-percent or a mate before the move, §2).

---

## 8. Analysis engine settings

- Strength presets in Settings: **Quick = depth 12, Standard = depth 14 (default), Deep = depth 18**
  (`SettingsLogic.AnalysisStrength`). MultiPV 3, Threads `min(cores, 4)`, Hash 96 MB, one position at
  a time, the hash kept from position to position. A stored custom depth (6..30, from older versions)
  is still honoured.
- Analyse every ply of the game plus the final position (a checkmate or stalemate is not searched).
- Progress reported per ply; analysis must be cancellable and resumable (§8.3).
- Results cached per game so re-opening a game is instant. The cache key is
  SHA-256(PGN text, depth, MultiPV, search budget); a result is reused only under identical settings.

### 8.1 Per-position search budget (F1)

Every position is searched with `go depth D nodes N movetime T` (`ui/model/SearchBudget.kt`): it stops
at depth D, or at N nodes (all threads together), or after T ms, whichever comes first.

**Why.** Before F1 the search had no limit but the depth. Stockfish's work per position is very
uneven: on the owner's game (`games/game01.txt`) the position after 23.Rdg1 took 59-86 s and 80-129 M
nodes on the host at depth 18, against 1-15 s for every other position, and many minutes on a phone.
The analysis looked stuck at "move 23".

**The node budget N is the real limit.** Node counts do not depend on the device's speed, so the same
game is cut at the same places on every phone. Rule for choosing N: **at least 95% of positions of the
calibration games still reach full depth, and the game01 outlier stops near depth 16 at Deep.**

**The time cap T is only a safety net for a slow phone, and generous on purpose.** The desktop P0 lesson
applies: a tight `movetime` silently made the analysis shallower and changed move classifications.
T is set so that a phone several times slower than the emulator still spends its node budget before
the clock stops it.

| Strength | Depth | Node budget N | Time cap T |
|---|---|---|---|
| Quick | 12 | 4,000,000 | 30 s |
| Standard | 14 | 25,000,000 | 150 s |
| Deep | 18 | 45,000,000 | 270 s |

A custom depth uses the budget of the smallest preset at or above it (deeper than 18: Deep's).

**Calibration (F1, 2026-10-06).** Host: Stockfish 19 (`pc/bin/stockfish`), Threads 4, Hash 96,
MultiPV 3, `ucinewgame` once per game, then every position in order with `go depth D` and no other
limit (the hash kept, as the app does). For each position, the nodes at which iteration D finished
(the last exact multipv-1 line at depth D). 226 positions: `games/game01.txt` (66), the two recorded
games of `scripts/audit_commentary.py` (`fixtures/chesscom_style_game.pgn` 33 and
`fixtures/immortal.pgn` 45; the Immortal Game is one of them) and `fixtures/byrne_fischer.pgn` (82)
for more data. Checkmates excluded (not searched).

| Depth | Median | p90 | p95 | p99 | Max | Full depth at the budget |
|---|---|---|---|---|---|---|
| 12 | 0.3 M | 0.9 M | 1.5 M | 5.4 M | 6.5 M | 4 M: 221/226 = 97.8% (2 M would give 95.6%) |
| 14 | 1.2 M | 10.3 M | 21.1 M | 59.6 M | 102.0 M | 25 M: 217/226 = 96.0% (20 M: 94.2%) |
| 18 | 6.3 M | 26.2 M | 42.2 M | 83.9 M | 100.5 M | 45 M: 217/226 = 96.0% (40 M: 94.2%) |

The tails are real and not only game01's: at depth 14 the chess.com game's 28...Qe6 needed 102 M nodes
(54 s on the host) and at depth 18 the Immortal Game's 19...Qxa1+ 100.5 M (99 s). Node counts with 4
threads are not repeatable: **the game01 position after 23.Rdg1** finished depth 18 at 24.9 M in the
whole-game run above (warm hash), and in four cold runs (fresh engine, `go depth 18`) the depths
completed at:

| Cold run | d15 | d16 | d17 | d18 | Reached with 45 M |
|---|---|---|---|---|---|
| owner's report | | 30 M | 78 M | 80-129 M | 16 |
| F1 run 1 | 2.0 M | 12.7 M | 16.3 M | 30.9 M | 18 |
| F1 run 2 | 3.6 M | 13.8 M | 30.8 M | 76.5 M | 17 |
| F1 run 3 | 10.9 M | 19.5 M | 37.5 M | 132.1 M | 17 |

So at 45 M the outlier stops at depth 16-17 when it blows up, and finishes when it does not. On the
emulator (`CappedSearchInstrumentedTest`, cold hash) it stopped at depth 16 after 45.0 M nodes and 72 s
in one run and finished depth 18 in 21.0 M nodes and 30 s in the next.

**Time caps from the emulator's speed.** chess34 (API 34, 4 vCPUs, 4 engine threads), two full game01
Deep runs read from the diagnostic log: searches of more than 10 M nodes ran at 510-~600 k nodes/s
(median 570 k; 2-10 M-node searches 400-1,100 k, median 590 k; the host does about 1.5 M). At a
rounded-down 0.5 M nodes/s the node budgets take 8 s (Quick), 50 s (Standard) and 90 s (Deep) on the
emulator; the caps (30 / 150 / 270 s) are at least 3 times that, so a phone up to 3 times slower than
the emulator still spends its whole node budget and the clock never decides the result there.
`SearchBudgetTest` asserts this relation. (The first calibration used one 0.63 M nodes/s sample and a
240 s Deep cap; the full runs showed slower searches, so the cap was raised to 270 s.)

**A capped position is flagged, honestly.** `PositionEval.requestedDepth` is the depth asked for and
`PositionEval.depth` the depth every line was actually searched to; `isCapped` = depth < requested.
The flag is stored in the eval cache. The Summary's Details ends with one quiet line when any position
was capped ("To save time, N positions were searched less deeply than the rest."); the diagnostic log
lists every position with requested and reached depth, nodes, time and the capped flag.

A capped position's evaluation is compared with neighbours searched to the full depth, which §1.2
otherwise forbids. That is accepted: the alternative is an analysis that does not finish. The budget is
chosen so this affects about one position in twenty at most, those positions are typically the
sharpest ones (where a few plies less matter least to the win-percent bands of §2), and they are
flagged.

### 8.2 Every line of a result comes from one depth (pipe safety)

A search stopped by `nodes` or `movetime` still ends with `bestmove`, so the pipe protocol is unchanged
(`StockfishEngine.analyze` waits for `bestmove` exactly as before, and cancellation still sends `stop` and
drains to `bestmove`). What changes is which "info" lines can be trusted. Stockfish 19 prints one batch
of lines (one per MultiPV slot) per completed iteration, and **one more batch at the stop**
(`search.cpp`, `start_searching`: `if (!uciPvSent ...) output_pv(...)`). That stop batch mixes depths:
slots re-searched in the unfinished iteration carry the new depth, slots not yet re-searched are
re-printed from the previous iteration either at "depth − 1" or, if never touched, **with their old
score under the new depth's label** (seen on the host: the depth-15 scores and PVs re-printed as
"depth 16"). Secondary PVs can also lag a depth within a normal iteration.

Rule (`engine/AnalysisModels.kt`, `ConsistentLines`, host-tested in `ConsistentLinesTest` against
recorded Stockfish 19 output):
1. Split the lines into batches (a new batch starts at slot 1). k = the largest batch size (MultiPV,
   or fewer when the position has fewer legal moves).
2. A batch is usable when it has exactly slots 1..k, all at one depth, none a `lowerbound` /
   `upperbound`.
3. If a limit was hit (the engine's last reported nodes reached the node limit, or its reported time
   the time limit), **the final batch is the stop batch and is discarded**, even when it looks clean:
   on the emulator a search stopped 1,673 nodes past its 45 M budget printed all three slots as
   "depth 18", the requested depth. If no limit was hit, the search ended by reaching its depth and
   there is no stop batch. (When the depth is reached in the same instant as the limit, or the stop
   falls exactly between two iterations, this discards a good batch and reports one depth less than
   was searched: conservative, never wrong.)
4. Use the last usable batch of the deepest depth among the rest.
5. Nothing usable (a tiny budget): the latest line per slot, with the shallowest of their depths.

So a result is "full depth" only when the search used less than its node budget and its time cap.

The `bestmove` of a stopped search (Stockfish's pick from the unfinished iteration) is not used when
lines exist; the best line is slot 1 of the chosen batch, like every other position.

### 8.3 Resuming after the process is killed

Android may kill a backgrounded app during a long analysis (no foreground service: none of Play's
types fits an analysis, and the owner rejected adding one). So:
- The partial eval cache is checkpointed **after every ply** (`AnalysisService.CHECKPOINT_EVERY_PLIES`
  = 1; a temp file then a rename).
- The request (game text, depth, MultiPV, the name used to find the user's side, start time) is
  written to `filesDir/pending_analysis.json` (`PendingAnalysisStore`, excluded from backup) when the
  analysis starts, and deleted when it succeeds, when the text cannot be parsed, or when the user
  cancels (Cancel or Back). The partial eval cache is kept on cancel.
- A new process resumes it: a restored Analysing screen reads the request from disk instead of
  reporting "The game text was lost"; an app that starts fresh at Home opens the Analysing screen for
  it once, unless the request is more than 24 hours old (then it is dropped). The resume starts at the
  checkpoint (`usableResumePrefix`, FEN-aligned) with the request's own settings, so the cache key
  matches.

### 8.4 The Analysing screen's time bar

Under the progress bar: the elapsed time of this run; "About N min left" once 6 positions have been
searched in this run; and "Thinking deeper on this move… depth d of D" when one position has taken more
than 3 s (d = the deepest depth the engine has completed on it, from `StockfishEngine.analyze`'s progress
callback).

The estimate (`ui/model/AnalysisTimeLeft.kt`) is **positions left x `SearchBudget.typicalNodes` x this
run's measured milliseconds per node**. `typicalNodes` is the calibration games' mean of min(nodes to full
depth, budget): Quick 0.5 M, Standard 3.45 M, Deep 10.3 M. Node counts do not depend on the device; the
device's speed is measured. The export's estimator (measured time per item x items left,
`video/ExportTimeLeft.kt`) was tried first and is wrong here, because the opening is cheap and the
middlegame dear: on the emulator's game01 Deep run it said 3 min after 6 positions with 19 left. Replayed
on that run's log the node estimate says 14 min (19.3 left), 12 after 30 positions (12.5 left), 6 after
50 (4.6 left). The display rules are the export's: minutes rounded up, "Less than a minute left" at the
end, and the shown number rises only when it was off by 2 minutes or 25%. The game's own node counts are
not blended in (its cheap opening would pull the estimate down when it matters most); a game much heavier
than the calibration games is underestimated until that rule lets the number rise.

The estimate and the "thinking deeper" line are a polite live region; the ticking clock is not.

---

## 9. Narration significance and move sequences

Added in Round 6, for "show me the score per move" + "only narrate what matters" +
"colour-code move and sequence quality". Nothing here changes §2 classification; it is a
presentation and selection layer on top of it.

### 9.1 Move swing

For a ply `p` played by side S:

    swingCp(p) = abs(evalAfterCp(p) - evalBeforeCp(p))

`evalBeforeCp` / `evalAfterCp` are the **White-relative** centipawn values of §1, so the
subtraction is perspective-free and `swingCp` is the same number for either player.
The **signed** form (`evalAfterCp - evalBeforeCp`) is what the UI displays as the move's
swing: positive means the move moved the evaluation towards White.

Mate scores reach these fields through `cpFromMate` (§1) and therefore saturate near
±10000cp. A swing into or out of mate is consequently enormous, which is correct for
**selection** — allowing mate is maximally significant — but is not a number anybody can
read: the subtraction renders as "+99 pawns". So:

- the threshold compares against the raw swing, mate included;
- the UI **publishes no numeric swing when either end of the move is a mate score**. The
  score readout beside it already says `M2` or `#`, which is the information that matters.

### 9.2 Significance threshold

`NarrationOptions.significanceThresholdCp`, **default 50 centipawns (±0.5 pawns)**.

A ply is *significant* when:

    swingCp(p) >= threshold
      OR  p belongs to a MoveSequence (§9.3) whose totalSwingCp >= threshold

Interpretation note, recorded because the request was ambiguous: the owner asked to narrate
moves "scoring higher/lower than XXX ... default -+0.5". This is implemented as the **swing
the move caused**, not the absolute evaluation of the position. A filter on absolute
evaluation fires on nearly every move of any decisive game — once a side is a pawn up every
later move is "beyond ±0.5" — which selects the whole game and defeats the stated purpose of
focusing on what matters. The swing reading selects the moments where something changed.

Rules:

- The threshold **composes with** `NarrationDepth`, it does not replace it. Depth chooses the
  candidate plies; the threshold prunes them. A threshold of 0 disables pruning entirely.
- **`EVERY_MOVE` is exempt.** That depth is an explicit request for the complete walkthrough,
  and a significance filter silently deleting moves out of "every move" would make the option
  a lie. It is the escape hatch; every other depth composes with the threshold.
- **Structural beats are never filtered**: intro, opening summary, chapter transitions, the
  outro summary (which states the final result) and the closing lessons are emitted outside
  per-ply selection. Only the length budget (§9.7) may thin them, and only for a game so short that
  its story cannot fit in its budget any other way; the result, the intro's players and the
  accuracy line always stay.
- **A checkmating final move always survives** the threshold — it is the game's result.
- **The selection is never empty.** If the threshold rejects every ply, fall back to
  narrating the single largest-swing ply (ties resolved towards the earlier ply).

### 9.3 Move sequences

A `MoveSequence` is a maximal run of consecutive plies that reads as one unit. Minimum run
length is **2 plies**. Two kinds:

- **TACTIC** — consecutive plies that each carry a `TacticInstance` of the same `TacticType`
  and the same `byColor`, at or above the reportable confidence floor of **0.6** from
  `Contract.kt`. Found and missed motifs both count: a combination the player missed for
  three moves running is one story, not three.
- **COLLAPSE** — consecutive *turns of the same player* whose moves are all
  `MoveClassification.isMistake`. The run spans from the first to the last guilty ply
  inclusive, so the opponent's replies sit inside the span; that span is what reads as "this
  is where it fell apart".

`totalSwingCp = abs(evalAfterCp(endPly) - evalBeforeCp(startPly))`.

A run is coloured by its most extreme member: the **worst** classification in a COLLAPSE, the
**best** in a TACTIC (`MoveClassification`'s declaration order is severity order).

Overlaps are resolved longest-first, then earliest-first; a ply belongs to at most one
sequence, so no UI ever has to draw two bands over one move.

### 9.3a Material and evaluation words in the narration (C1)

The narrated video names material the way the card text and the walkthrough do (§6.1): a unit only
within 40 cp of its value (`NarrationVocabulary.materialGain`: queen 900, rook 500, piece 325, pawn 100;
"material" otherwise), "the exchange" when a line's settled boards show a minor piece given for a rook
and nothing else (`materialGainAlong`, `MaterialGain.EXCHANGE`), and a tactic's advertised swing by the
same tolerance (`materialPayoff`: "serious material" from 200 and "material" from 100 when no unit
fits). Before C1 "a rook in the bank" was said for any swing of 500 or more and "a pawn up" for a
settled +170 (the exchange). The evaluation words (`Standing`) are the card's: "decisively winning",
"winning", "clearly better", "slightly better", "about level", "slightly worse", "clearly worse",
"losing", "decisively lost" (§7.3). The spoken motif sentences (`Sentence.TacticPoint`) carry the
professional terms of §7.2 with the same proofs behind them, in two or three phrasings each, and say what
the detector proved and no more ("is attacked", "is exposed", never "falls" for a piece the opponent may
still save).

### 9.4 Score display

One formatter (`core.analysis.EvalFormat`) serves the eval bar, the review move list and the
video side panel, so the same position cannot read `+0.9` in one place and `0.9` in another.

- Centipawns render as pawns to **one decimal with an explicit sign**: `+0.9`, `-1.4`, `0.0`.
- A mate renders as a mate, **never** as its saturated centipawn value: `M3` when White
  mates, `-M2` when Black does, and `#` when the mate is already on the board (the engine
  reports `mate 0` there; "M0" reads as "mate in no moves" and is not a thing).
- Values are White-relative; the sign is always shown so a reader never has to infer which
  side a number favours.

### 9.5 Colour coding

Per-move and per-sequence colour comes from the existing 11-class palette in
`app/ui/theme/MoveClassification.kt`. No second palette. **Colour is never the only signal**:
the existing glyphs (`!!`, `!`, `★`, `✓`, `?!`, `?`, `✗`, `??`, `□`) are always rendered
alongside it, because red/green is the most common colour-vision deficiency axis and the
INACCURACY/MISTAKE/BLUNDER ramp lives on exactly that axis. Sequences additionally carry a
text label (the motif name, or "Collapse").

### 9.6 Tactic significance

Detection (§5.3) is deliberately generous, which is right for commentary and wrong for a report:
one real game listed Deflection ×5, Relative pin ×3 and Skewer ×3, most of which had no bearing on
the result. Detected tactics are therefore gated by **the same threshold as moves**
(`NarrationOptions.significanceThresholdCp`, §9.2). The gate is a presentation layer
(`core.analysis.TacticSignificance`): the §5.4 buckets on `MoveAnnotation` are computed unchanged,
and the report screen, the sequence detector's input and the narration all consume the pruned
view. Nothing is deleted — the UI keeps pruned motifs behind a "minor tactics" disclosure.

**Swing of a tactic.** For a *missed* tactic at ply `p`:

    missedSwingCp(p) = swingCp(p)                       (§9.1 — what missing it cost)

For a *found* tactic the ply's own swing cannot be used: the played move **is** the engine's best
move, so `evalBeforeCp` already assumed it would be played and `swingCp(p) ≈ 0` by construction.
Its significance is the counterfactual — what *not* finding it would have cost:

    foundSwingCp(p) = max( |evalBeforeCp(p) − evalSecondBestCp(p)|,      (MultiPV margin, 0 if absent)
                           |evalAfterCp(p) − evalBeforeCp(p−1)| )        (the allowing move + the tactic)

`evalSecondBestCp` is the White-relative centipawn value of MultiPV line 2 before the move, recorded
on `MoveAnnotation` for this purpose. The second term exists so a MultiPV-1 analysis still credits
the fork that punished a blunder.

A tactic is **significant** when its swing `>= threshold`, or it belongs to a TACTIC `MoveSequence`
(§9.3) of the same motif and colour whose `totalSwingCp >= threshold`. A threshold of 0 disables the
gate.

A *found* tactic must additionally be **engine-confirmed** (`confidence >= 0.95`, the §5.3 value
for "the PV plays the exploiting move within 4 plies"). The swing proves the *move* mattered; the
confirmation proves the *motif* is what mattered. A forced recapture has an enormous MultiPV margin,
and a static relative pin that merely rides on it — the engine never cashes it in — is exactly the
noise this gate exists to remove. This applies before the decisive rules below: a checkmating move
protects the mating net the engine confirmed, not a "skewer" a static detector saw on the same move. The gate applies at every `NarrationDepth`: `EVERY_MOVE` exempts the *ply* selection, not this
— a walkthrough of every move is not a request to hear every detector hit.

**Decisive tactics survive any threshold.** A naive swing gate deletes checkmates: a queen up, the
mating move barely moves a saturated evaluation. The following are therefore kept regardless of
swing, because they *are* the point of the game:

- a found tactic on the **checkmating move** (by `#` in the SAN, or by the position when the PGN
  omitted it);
- a found tactic played while the mover has a **forced mate** on the board afterwards
  (`mateInAfter` in the mover's favour) — this covers the quiet move that completes a mating net,
  whose swing is one `MATE_STEP` or less;
- a found tactic on a **BRILLIANT** or **GREAT** move — §2 already proved those significant (a sound
  sacrifice; the only move by a ≥10 win-% margin);
- a missed tactic where a **forced mate was available** (`mateInBefore` in the mover's favour), and
  any missed tactic on a **MISS**.

**A ply carrying a significant tactic is itself significant** for §9.2's ply selection. Without this
the blunder would be narrated and the punishment pruned, since the punishing move has no swing.

**No largest-swing fallback.** Unlike §9.2, an empty tactic bucket is an honest answer ("nothing
tactically decisive happened"); a review with no body is broken, a report with no tactics is not.
The "Show me" simulation on a ply is kept only while at least one missed motif on that ply survives.

### 9.7 Pacing tiers and the length budget

Added in Round 13. The first narration selected every mistake and every missed tactic and walked
each missed line for up to eight plies, so a 17-move game came out at 83 beats and eleven minutes
and the 23-move Immortal Game at 195 beats and twenty-three. A review video for a phone is a
short story, not an audit, so every ply is now assigned a **pacing tier** (the shape follows
`docs/pc_research/VIDEO_FORMAT.md` §2) and the whole script is held to a length budget.

**The tiers.** `VideoScriptGenerator` decides a tier for every ply; the rules are evaluated in
this order and the first that matches wins:

| Rule | Tier |
|---|---|
| the game's checkmate | **DWELL** at least (it is never lower; see the mating beat) |
| `BOOK` or `FORCED` classification | **SKIP** |
| `BLUNDER`, `MISS` or `BRILLIANT` | **FULL** candidate |
| `MISTAKE` whose missed tactic wins material or mates (below) | **FULL** candidate |
| any other `MISTAKE` | **DWELL** |
| `INACCURACY` | **BRIEF**, always — never a walk of the missed line, however much it would have won |
| `GREAT` | **DWELL** |
| a recapture (takes back on the square the opponent just took on) | **SKIP** |
| a found tactic with `confidence >= 0.95` (`FOUND_TACTIC_CONFIDENCE`; the §9.6 gate already requires it) | **DWELL** |
| significant (§9.2: swing `>= significanceThresholdCp`, or a member of a §9.3 sequence — the plies where the plan changes) but none of the above | **BRIEF** |
| anything else: routine best/excellent/good moves with a small swing | **SKIP** |

A missed tactic **wins material or mates** when its own `materialSwing >= 150`
(`PUZZLE_MIN_SWING_CP`), or it is a mating motif (`BACK_RANK_MATE`, `SMOTHERED_MATE`, `MATE_NET`,
`GREEK_GIFT`), or the mover had a forced mate before the move, or the engine line that realises it
checkmates or nets `>= 150cp` on the board (the highest-confidence motif is often a clearance
worth nothing by itself while the line it opens wins a rook).

**What each tier says.**

- **SKIP** — no beat of its own. A run of **three or more** consecutive skipped plies becomes one
  "skip ahead" connective; one or two get a lead-in ("two moves on") on the next beat.
- **BRIEF** — one or two sentences, and the second one has to *say* something: an inaccuracy gets
  the better move; any other move gets the tactic it carries, or what it did to the evaluation
  ("White has gone from clearly better to winning", or "The evaluation moves in White's favour").
  Never a pleasantry such as "Top of the engine's list" or "No complaints": those survive only in
  `EVERY_MOVE`, the complete walkthrough, which is never budget-demoted. No excursion.
- **DWELL** — the fuller explanation (the error beat, the found-tactic beat, the threat beat). A
  missed tactic still gets its reveal and a variation, but **at most 4 plies**
  (`DWELL_EXCURSION_PLIES`) and **no puzzle pause**.
- **FULL** — the puzzle pause (when the missed tactic wins material or mates, or the move is a
  `MISS`), the reveal, and the excursion walk of up to 8 plies (`MAX_EXCURSION_PLIES`, §6). A
  `BLUNDER` or `MISS` whose missed motif does *not* win material or mate is still FULL (it is among
  the worst moments) but is shown the short DWELL way.

**At most 3 FULL moments per game** (`MAX_FULL_MOMENTS`), ranked by a **drama score**, ties going to
the earlier move. The drama score is the move's `loss`, except that a `BRILLIANT` move (which loses
nothing by definition) scores as a loss of **25** (`BRILLIANT_DRAMA`, a mid-sized blunder), so in a
game with three or more errors a brilliancy can still take a slot. **The turning point is FULL** and
ranks first (for a game too short for its budget it is the last body beat to give way: below). It is the largest-loss move
with `loss > 0.5` that is neither `BOOK` nor `FORCED` (a book move is not a decision, a forced move
is not a choice). Every FULL candidate beyond the cap is demoted to DWELL. A brilliancy beyond the
cap is therefore still told, as a DWELL found-tactic beat.

**Protected beats.** A `BRILLIANT` or `GREAT` move, and a move that plays a forced mate (a found
`MATE_NET`, `BACK_RANK_MATE`, `SMOTHERED_MATE` or `GREEK_GIFT` with `confidence >= 0.95` — the queen
sacrifice that mates is the point of the game however the engine labels it), is **never taken below
DWELL by the length budget**, and neither is the checkmate. (The §9.2 threshold is a different filter:
a protected ply on which nothing happened at all is still pruned there.)

**Composition with the other filters.** Depth picks the candidate plies (`MISTAKES_ONLY` narrows them
to the errors, the key moments, the turning point and the mate), the §9.2 threshold then prunes — a
ply that moved nothing and belongs to no sequence is SKIP at every tier, the mate always survives,
a threshold of 0 prunes nothing — and the tier rules above shape what is left. The §9.6 tactic gate
is untouched and still decides which tactics a ply is said to carry. `EVERY_MOVE` narrates every ply
at the length its tier gives (a SKIP/BRIEF ply is told as a single normal beat) and is exempt from
the threshold and the budget, as before. If everything is pruned, the single largest-swing ply is
narrated (§9.2), as a BRIEF beat.

**The length budget.** The script's story length `storyMs` (speech from `estimateSpeechMs` at
`NarrationOptions.speechWpm`, plus the puzzle holds; since V3 the pace time of §9.8 is outside it) may not exceed

    budgetMs = min(720 000, 120 000 + 14 000 × fullMoves, 23 000 × fullMoves − 32 000)
    floor 20 000                                                   fullMoves = (plies + 1) / 2

(`BUDGET_MAX_MS`, `BUDGET_BASE_MS`, `BUDGET_PER_MOVE_MS`, `BUDGET_RAMP_PER_MOVE_MS`,
`BUDGET_RAMP_OFFSET_MS`, `BUDGET_MIN_MS`; the one function is `VideoScriptGenerator.budgetMs`).

| Moves | 4 | 8 | 12 | 17 | 23 | 40 | 43 and up |
|---|---|---|---|---|---|---|---|
| Budget | 60 s | 152 s | 244 s | 358 s | 442 s | 680 s | 720 s |

The first two terms are the Round 13 budget. Its 120 s base was never meant for a tiny game: a
four-move scholar's mate was allowed 176 s and came out at **3 min 43 s** on the emulator (21 beats, a
puzzle, an eight-ply walk of the missed 3...g6, three lessons). The third term is the ramp for such a
game; it meets the old line at 17 moves (`23 x 17 - 32 = 359 s`, against 358 s), so **every game of 17
moves or more has exactly the budget it had** and only shorter ones got smaller. The budget is a
ceiling, never a target: **the script is not padded** to fill it, and a quiet game comes out far under
it (the first eight moves of Byrne-Fischer: 90 s of a 152 s budget). When a script overruns it, the
beats with the least `interest` give way in this order, one batch at a time, rebuilding and
re-measuring until it fits:

1. DWELL beats that are not protected become BRIEF, lowest interest first (ties: the later ply goes first);
2. only when none is left, BRIEF beats become SKIP, lowest interest first;
3. only when nothing lighter remains (a six-move game with three blunders; a game whose brilliancies
   alone fill the budget): the other FULL beats become DWELL, the cheapest first - lowest drama score,
   a brilliancy last of all - and never below DWELL;
4. only then **the turning point itself**: FULL to DWELL, then DWELL to BRIEF (one beat, no puzzle, no
   walk, no separate "turning point" segment). A protected move and the checkmate stay at DWELL;
5. only then **the structure around the story**, one level per round (`ScriptBuilder.trim`):
   1. all lessons but the first, and no textbook offer after it;
   2. the rating and error-count sentences of the summary, and the caveat about the ratings;
   3. the opening summary;
   4. the intro's hook sentence;
   5. the last lesson.
   The result and the players in the intro, the accuracy line and the mating beat are never trimmed.

The checkmate and every protected beat are never demoted below DWELL, and at least one body beat always
survives. Because protected beats are exempt, a game rich in brilliant and great moves may
legitimately **overrun the budget** by the cost of those beats; the budget is not raised for that, and
nothing is added to fill the space either. The lesson lead counts its lessons truthfully ("Three
things to take out of this game" is only said before three).
`interest` is the existing ply score (loss, tactic swing, brilliancy and greatness bonuses, check or
mate, minus a book penalty).

**Measured (Stockfish 19, MultiPV 3, replayed through this pipeline).** `fixtures/chesscom_style_game.pgn`
(17 moves, depth 20): 70 beats / 629 s before, 30 beats / 313 s after, with 4...Bxf3 (an inaccuracy)
down from about 70 s to one sentence. `fixtures/immortal.pgn` (23 moves, depth 12): 160 beats / 1319 s
before, 50 beats / 441 s after (the unconstrained plan was 702 s; the budget removed the rest). After the Round 13 follow-up
(protected beats, drama ranking, no filler): chesscom 30 beats / 327 s, immortal 47 beats / 441 s; in
the Immortal Game the queen sacrifice 22.Qf6+ (11 s), the knight sacrifice 21.Nxg7+ (11.5 s) and the
mate are told at DWELL length or more, and 18.Bd6, which leaves both rooks en prise, keeps its
reveal and a four-ply variation.

**Measured after R1b (the same pipeline, Stockfish 19, MultiPV 3, depth 12 for the two recordings made
for it, `scripts/record_analysis.py`).** The scholar's mate 4 moves: 236 s / 21 beats before, **54 s / 5
beats** after. The first 8 moves of Byrne-Fischer: 102 s before, 90 s after. The Opera Game (17 moves):
327 s before, 314 s after. The Immortal Game (23): 441 s before, 431 s after. The first 40 moves of
Byrne-Fischer: 600 s before, 543 s after. The 8-, 17- and 40-move games are not budget-bound; their
few seconds' difference is the text no longer carrying the retired payoff sentences and the detectors
no longer reporting a clearance, a deflection or a sacrifice that was not one (§5.3, §2). The Immortal
Game is the one game here that the budget still trims (661 s planned, 442 s allowed, 431 s told).


**Recap card (R6b) is outside this budget.** An exported video ends with one silent end card,
`VideoScript.recap`, built by `GameRecap` in `:core` and drawn by `BoardFrameRenderer.renderRecapFrame`.
It is **not a segment**: it has no narration, so it adds nothing to `totalEstimatedMs`, to the budget
comparison above, to the segment count behind the export's "N of M" and the time-left estimate
(§ time left, `ExportTimeLeft`), or to the in-app player's timeline (playback ends on the last lesson
card; only the MP4 carries the recap). It adds its own **4 to 6 seconds** (`recapDurationMs`: 3 s plus
100 ms per word of the two sentences on it, clamped to 4 s..6 s; the audio track is padded with
silence so both tracks end together) after the budgeted length, so the exported file is that much
longer than the narrated script. What it says, and what proves each part:

| Part | Source |
|---|---|
| both names | the same header names as the title card |
| accuracy per side | `PlayerReport.accuracy`, shown as the Summary screen shows it (`%.0f%%`, same 85 / 65 colour bands) |
| one sentence | `GameSummarySentence` (§12), rendered for the same viewer as the Summary |
| "Biggest moment: move N, SAN (Class by Side)" | `GameSummarySentence.turningPoint`, the move §12 names, and only when it lost at least `BIG_SWING` (20) win-percent; otherwise no line |
| count chips | `PlayerReport.classificationCounts` for Brilliant, Great, Inaccuracy, Mistake, Miss, Blunder, non-zero only; equal to the number of that side's moves with that class |

**Title cards (R6c).** The intro, the final-numbers card and the lesson cards of the video are laid out by
`video/CardLayout.kt` (pure) and drawn by `BoardFrameRenderer.renderCardFrame`; they carry no new claim and
change no timing. Rules: (1) each fact is printed once: the intro is the title "Name (rating) vs Name (rating)"
(from the header; the names are bidi-isolated, the rating sits outside the isolate), one grey subtitle line
"1-0 · 17 moves · Opening (ECO)" and one green accuracy line "White 84% · Black 79%" (`VideoScriptGenerator`
writes `[subtitle, accuracy]` as the intro card's lines); the final-numbers card prints each name and each
accuracy once (the structured sub-lines that repeated them are gone); the intro and the final numbers draw no
caption bar, since the card says it; (2) every accuracy on a card or caption is whole percent, the Summary's
`%.0f` (the generator's `roundToInt` agrees with it on every value, swept in `CardTextTest`; the spoken
sentences keep one decimal); (3) text stays in a column 10 percent in from each side, between the chapter bar
and a zone of 9.5 percent (the caption bar) plus 3 percent air at the bottom, whether or not a caption is drawn:
the title shrinks, then stacks the two names on two lines, then ends in an ellipsis (`layoutTitle`, `fitLine`);
a body paragraph shrinks, then ends in an ellipsis (`fitParagraph`, `fitParagraphToHeight`); the block is
centred and, if still too tall, every size steps down together.

No new chess claim is made: every field is a report number or the §12 sentence. `GameRecapTest`
checks each against the report on the four recorded games.

### 9.8 The video pace (V3)

Added in Round 14 for the owner's "the analysis is a bit too fast for key moves/sequences". The pace is a
**presentation layer over the finished story**: `VideoScriptGenerator.generate` first builds the script and
holds it to the §9.7 budget exactly as before, and only then lays the pace over it (`ScriptBuilder.paced`).
So every pace tells **the same story in the same words**: the same segments, indices, chapters, narration,
speech estimates and recap; only `ScriptSegment.leadIn` and `ScriptSegment.holdAfterMs` differ. The pace
never stretches the speech, and a narration clip cached at one pace is the clip played at every pace.

**What was measured (before).** `PaceMeasurementDumpTest` lays the scripts of the Immortal Game and
`games/game01.txt` (recorded at depth 12, MultiPV 3) on the app's time axis (speech estimated at 169 wpm, the
900 ms floor, 250 ms gaps). Narration and board were in step (each move slides in the first 400 ms of the
beat that speaks it), and the detour lines were not fast (5.7 to 9.5 s per move: every move has its own
sentence). What was rushed was the **key moves themselves**:

- the decisive moves started sliding on the first frame of their beat, with **0 ms** on the position before
  them, at the same instant the voice started: in the Immortal Game 21.Nxg7+, 22.Qf6+ and 23.Be7#; in game01
  10 of its 15 key moves (6...e5, 9.g4, 12...exf3, 14.Qb3, 19.d6, 26.Bh6, 28...Rb8,
  30...Bxe4+, 31...Qc3+, 33...Qc1#);
- the **replies in a sequence were never shown**: the board jumped from the end of one beat to the position
  before the next (the Immortal Game 18 jumps in 47 beats, game01 27 in 70), so the mating sequence
  21.Nxg7+ Kd8 22.Qf6+ Nxf6 23.Be7# was three slides with 21...Kd8 and 22...Nxf6 on screen for 0 ms each.

**The pace.** `NarrationOptions.pace`, a `VideoPace`; the app's Settings, Video, **Pace** (default
**Relaxed**, because the owner's complaint was exactly that these moments were too fast; Normal is the
reference the owner's numbers are stated at, Brisk is close to the old feel):

| | Relaxed | Normal | Brisk |
|---|---|---|---|
| pause on the position before a key move (`keyLeadInMs`) | 1.5 s | 1.2 s | 0.7 s |
| hold on the result after the key move's speech (`keyHoldAfterMs`) | 1.5 s | 1.0 s | 0.5 s |
| per move of a played sequence (`lineMoveMinMs`) | 2.0 s | 1.5 s | 1.1 s |
| extra hold on a detour line's final position (`lineFinalHoldMs`) | 1.5 s | 1.0 s | 0.5 s |

- A **key move** is a beat that plays a real game move (the segment carries its verdict) and is the mate, a
  brilliant or great move, a found tactic told in its own beat, or a move whose tier is DWELL or FULL. A
  routine BRIEF move is not one.
- Its **lead-in** (`SegmentLeadIn`, silent): first the **approach**, the one or two game moves
  (`MAX_APPROACH_PLIES = 2`) between the position the previous beat left on the board and the position before
  the key move, one every `lineMoveMinMs` (each slides in 400 ms and then rests, with its own caption); three
  or more missing plies already have a spoken "skip ahead" beat (§9.7) and are not walked. Then the **pause**:
  the position before the move, still, the moving piece's square and its destination lit, the eval bar on the
  position before, the panel chip "Key moment" and no verdict yet. Only then does the piece move, and the
  narration starts with it. When the beat before is already a still picture of exactly that position (the
  "back to the game" beat after a detour, a puzzle prompt) that picture is the pause, and none is added.
- After its speech the result is held `keyHoldAfterMs`, unless the next beat is another key move starting from
  that position (its own pause shows it).
- A detour line's move is on screen at least `lineMoveMinMs` (speech, the 900 ms floor and the gap included;
  the narrated lines are already longer), and the line's final position gets `lineFinalHoldMs` more.

The in-app player and the MP4 exporter play the same script on the same timeline (`TimelineBuilder`: lead-in,
then speech, then hold and gap): the exporter writes the lead-in as silence before the segment's speech and
`SegmentFrameBuilder` draws the lead-in and starts the segment's own board after it; the player starts the
voice at `TimedSegment.speechStartMs`. So the two cannot disagree.

**The budget (§9.7) and the pace: decided.** The budget is **not** scaled with the pace. It holds the
**story** (`VideoScript.storyMs` = speech estimates plus puzzle pauses, which is what `totalEstimatedMs` was
before V3); the pace time (`VideoScript.pacingMs`) comes on top, like the recap card. Reasons: (1) a slower
pace must not be paid for by cutting what is said, and a pace-scaled budget would make Relaxed drop beats that
Brisk keeps, i.e. a different story per pace; (2) the same story means the same narration text, so switching
the pace re-uses every cached clip and never re-synthesizes; (3) the cost is small and measured. The pace time
is still bounded: at most `PACING_CAP_FRACTION` = **15 percent** of the budget
(`VideoScriptGenerator.pacingCapMs`); a game that would pass it has every pace time scaled down until it fits
(`PaceTimes.scaled`, a line move never below 700 ms). On the five recorded games it never binds:

| Game (plies) | Budget | Story | Relaxed pace time | Normal | Brisk |
|---|---|---|---|---|---|
| scholar's mate (7) | 60 s | 52.5 s | +5.0 s (9.5%) | +3.7 s | +2.3 s |
| chesscom_style_game, 17 moves (33) | 358 s | 306.9 s | +29.0 s (9.4%) | +21.2 s | +12.6 s |
| Immortal Game (45) | 442 s | 422.0 s | +25.5 s (6.0%) | +18.1 s | +10.4 s |
| game01 (66) | 582 s | 544.6 s | +64.0 s (11.8%) | +47.1 s | +28.7 s |
| Byrne-Fischer 41 moves (82) | 694 s | 537.3 s | +51.5 s (9.6%) | +37.5 s | +22.2 s |

(Percentages of the story.) So a video is at most its §9.7 budget plus 15 percent of it, plus the 250 ms gaps
and the 4-6 s recap; in practice 2.5 to 12 percent longer than the story. The protected beats (§9.7) are
untouched: the pace runs after every tier and budget decision, so BRILLIANT/GREAT, forced-mate and mate beats
are exactly what they were, and only gain a pause.

**Recap and time left.** Unchanged: the recap card is still after the last segment and outside both numbers;
the export's "N of M" and "about N min left" count segments and measure synthesis time, and the pace adds no
segment and no speech.

**The best line in the video (V2).** A key moment on a MISTAKE, MISS or BLUNDER whose narration names the
better move over a still board (`ScriptBuilder.betterMoveBeats`: the error beat told at DWELL or FULL length
without a detour, and a brief beat on one of the report's key moments, the five costliest errors) plays the
engine's best line on the board **after its speech** (`ScriptSegment.bestLine`, `SegmentBestLine`), instead
of leaving the better move as an arrow: the same moves the Board shows (`BestLines.bestFor`), at most
`BestLines.VIDEO_MAX_PLIES` = 4 (the DWELL variation length of §9.7), one every `lineMoveMinMs`, each sliding in
400 ms and resting with its caption ("Best line — 18... Nf5 19. Qd2"), the final position held
`lineFinalHoldMs`, drawn as an excursion (tinted border, the chip "Engine's best line", no verdict; the eval
bar keeps the beat's eval). An inaccuracy keeps its arrow: it is BRIEF, "never a walk of the missed line"
(§9.7). A missed tactic with a detour already walks its line with narration (§9.7 FULL / DWELL).

- **Decided: the line extends the segment by pace time, inside the cap.** Narration stays the source of
  timing: the line starts when the speech ends (`SegmentFrameBuilder` takes the timeline's own speech
  length, so the player and the MP4 start it at the same instant) and lives in the segment's hold
  (`holdAfterMs` = the line's time + any other hold), so no timeline consumer needs anything new and the
  exporter's audio is silence there. It is counted in `VideoScript.pacingMs`, never in the story, so the
  §9.7 budget, the words, the cached narration, the recap card and the "N of M" / "about N min left" export
  figures are unchanged (no new segment, no new speech).
- **Which moments and how many plies (`ScriptBuilder.bestLinePlan`)**, decided once, at the slowest pace
  (Relaxed), so every pace plays the same moves: the room under the 15 percent cap that the V3 pace time
  leaves at Relaxed is shared out most important first (tier, then loss, then the earlier move); each moment
  gets up to 4 plies while its Relaxed time fits, and as many as fit (at least one) when four do not; a
  moment for which not even one ply fits keeps its arrow. So the lines never scale the V3 pauses and holds
  down, and a game's pace time stays under its cap at every pace.
- **Measured (host recordings, 169 wpm, `BestLineVideoTest`, `core/build/pace/v2_best_lines.txt`):** the
  Opera Game 1 moment (9...b5, 4 plies), the Immortal Game 2 (11...cxb5, 16...Bc5, 4 plies each), game01 3
  (24.Bh3 and 7...e4 4 plies, 13...Bc6 1 ply: the room ran out), the scholar's mate 1 (3...Nf6, 1 ply),
  Byrne-Fischer none (its arrow-only moments are inaccuracies). The pace time at Relaxed / Normal / Brisk
  rose by 9.5 / 7.0 / 4.9 s (Opera), 19.0 / 14.0 / 9.8 s (Immortal), 22.5 / 16.5 / 11.4 s (game01); every
  game stays under its cap (game01 Relaxed 86.5 of 87.3 s).

**Reference (chess.com Game Review, pattern only).** Stepping through a review shows a key move on its own:
the board sits on the position, the move is made, its classification appears with it, and the coach's
explanation follows while the board holds the result; a best line is stepped one move at a time, never
jumped. Taken: the pause on the position before the key move with the move's squares marked, the verdict
appearing only with the move, the hold on the result, and a steady per-move rate for a sequence with no
skipped replies. Our own timing values and words; no assets.

---

## 10. Tactic reference library

`core.analysis.TacticReferenceLibrary` holds one canonical teaching position per `TacticType`
where a clean one exists: a FEN, the solution line in SAN, and one teaching sentence. It exists
because a player's own missed fork is a fork buried in a messy position; the reference is the same
idea with nothing else on the board.

**Provenance is mandatory.** The source of truth is the `REFS` table in
`scripts/verify_tactic_references.py`, which checks every entry with python-chess for legality,
for the pattern's **own structural claim** (mate is mate, a fork attacks two valuable pieces and its forker cannot simply be taken for free, a pin
leaves a pinned piece, a discovered check comes from a piece that did not move, a deflected
defender no longer guards the square, a windmill alternates discovered and direct checks with
forced replies, a stalemate trick leaves *every* reply stalemated, …) and, for combinations, for
soundness by a small material search or an exhaustive forced-mate solver. It **fails closed**: a
pattern with no structural check is rejected. Only when everything passes does it regenerate
`fixtures/tactic_references.json` and `core/.../TacticReferenceCorpus.kt`. `TacticReferenceCorpusTest`
then replays every line through `:core`'s own move generator and asserts the two corpora are
identical, so two independent engines must agree before a position reaches a learner. Positions
are never hand-edited in Kotlin or JSON.

**Presentation.** A reference is played through the same `TacticSimulation` machinery (§6) as the
missed-tactic "Show me" flow — one board, one stepper, one explanation card — with the whole line
shown (no §6 payoff truncation; a windmill is nine plies on purpose). It is *offered from* a real
occurrence rather than taught cold: on a tactic group in the game report, on the review card of a
move that carries the motif, and at the final step of a missed-tactic walkthrough ("want to see
this pattern done cleanly?"). The narrated video **stays in the game**: it names the pattern and
the lessons mention that a textbook example is waiting in the report, but it never plays the
reference position — a detour would break both the story and the running time.

---

## 11. Practice puzzles

`core.analysis.PracticeSelector` and `PracticeJudge` (`Practice.kt`) turn the user's own mistakes into
"find the better move" puzzles. Design: `docs/PRACTICE_DESIGN.md`. Everything is local and uses only
data the analysis already produced; **no engine call is made to select or judge**. Puzzles are
classification-based, so the §9.6 tactic gate does not apply and the selector runs on the
**unpruned** report.

### 11.1 Cached lines

`MoveAnnotation.candidateLines` holds the MultiPV lines of the position *before* the move, best
first (ascending `multiPv`), one `CandidateLine(multiPv, uci, san, scoreCp, mateIn)` per line.
Scores are **mover-relative**, exactly like `EngineLineInput` (positive = good for the side to
move; `mateIn` > 0 = the mover mates). They are not flipped to White's perspective. A line's win
percent is `WinProbability.winPercentOfLine` (§1.1: a mate is 100 / 0). Positions are analysed at
one depth (§1.2), so the lines are comparable with each other and with the played move's eval.

### 11.2 Constants

| Constant | Value | Meaning |
|---|---|---|
| `MIN_LOSS` | 10.0 | win-percent loss floor, the MISTAKE floor of §2 (MISS exempt) |
| `MIN_WIN_BEFORE` | 25.0 | the mover's win percent before the move must be at least this |
| `ACCEPT_LOSS` | 2.0 | a cached line within this many win-percent of the best is accepted (the same best-or-near-best bound as GREAT / BRILLIANT in §2) |
| `MAX_PUZZLES` | 5 | puzzles per game |
| `DEDUPE_WINDOW_PLIES` | 4 | same best move within this many plies is one puzzle |
| `MISS_RANK_FLOOR` | 20.0 | a MISS ranks as `max(loss, 20.0)` |
| `GOAL_ROOK_CP` / `GOAL_PIECE_CP` / `GOAL_MATERIAL_CP` | 500 / 320 / 150 | goal labels, the narration's puzzle-prompt thresholds |

### 11.3 A ply is a candidate when ALL hold

1. `color == userColor`. With `userColor == null` the result is `PracticeSet.NoSide`.
2. `classification` is MISTAKE, MISS or BLUNDER. INACCURACY is excluded on purpose.
3. `loss >= MIN_LOSS`, or the class is MISS (a thrown-away forced mate can lose little win percent).
4. `winPercentBefore >= MIN_WIN_BEFORE`.
5. There is an answer: `bestMoveUci` is non-null, differs from the played `uci`, and is legal in
   `fenBefore`. A puzzle whose best move is a **non-queen promotion is skipped** (promotion
   auto-queens in v1, so the answer could not be entered).
6. **Judgeable from the cache.** With `k = candidateLines.size` and
   `band = winPercent(best line) - ACCEPT_LOSS`, either
   - `k >= 2` and (`winPercent(last line) < band` **or** `legalMoves(fenBefore).size <= k`), or
   - the best move delivers checkmate (mate in 1, verified by the rules with
     `Position.makeMove(...).isCheckmate()`).

   So either every uncached move is provably no better than a line that is already worse than the
   band, or every legal move is cached. "Not in the cached lines" is never a guess. A position with
   several near-equal moves and more legal moves than cached lines is skipped as a poor puzzle.

No candidates gives `PracticeSet.Empty`.

**Dedupe** (before ranking, in ply order): two candidates with the same `bestMoveUci` whose plies
differ by at most `DEDUPE_WINDOW_PLIES` are one puzzle; the one with the larger `loss` is kept (the
earlier on a tie). **Cap and order:** rank by `rankLoss = if (MISS) max(loss, 20.0) else loss`
(ties: earlier ply), take `MAX_PUZZLES`, then present in **ply order**.

### 11.4 Goal label

1. `MateIn(n)` when `mateInBefore` is a mate *for the mover* (`n = |mateInBefore|`), or, when it is
   absent, when the best move checkmates (`MateIn(1)`).
2. Else from the best missed tactic of the mover (largest `materialSwing`, then confidence):
   `WinRookOrBetter` at >= 500, `WinPiece` at >= 320, `WinMaterial` at >= 150.
3. Else `BetterMove`.

The same tactic gives the puzzle's `hintMotif`. `evalSwingCp` is the mover-relative centipawn cost of
the played move (never negative), and **null when `mateInBefore` or `mateInAfter` is set**, because
a saturated mate value is not shown to the learner.

### 11.5 Judging an attempt (`PracticeJudge.judge`)

`acceptedUci` = the best move plus every cached line with
`winPercent(best) - winPercent(line) <= ACCEPT_LOSS`, **minus the move actually played** (the puzzle's
premise is that the played move was a mistake, so tapping it must never read as correct). In order:

1. attempt in `acceptedUci` gives `Correct`;
2. a mate-in-1 puzzle and the attempt checkmates by the rules gives `Correct` (the second mate need
   not be cached);
3. attempt equals the played move gives `PlayedInGame(loss, evalSwingCp)`;
4. otherwise `Wrong`.

**Mate puzzles.** In a mate puzzle the best line is 100 %, so a move that merely wins a queen
(+1000 cp = 97.5 %) loses 2.5 > 2.0 and is `Wrong`; a slower mate is also 100 % and is `Correct`.
This falls out of the band automatically and is pinned by tests.

Engine-backed judging (a search per attempt) is deliberately not used: §1.2 forbids comparing evals
from different depths, and the engine is a single in-process instance owned by the analysis loop.

---

## 12. One-sentence game summary

The first line of a review is one sentence on how the game unfolded (pattern: chess.com's review
opens with a one-line summary; the wording is our own). `GameSummarySentence.build(report, userColor,
notMe)` produces it from the **report alone** as a typed `Sentence.GameSummary`, rendered by
`NarrationStrings`; the app shows it under the accuracy row of the Summary header.

### 12.1 Inputs and constants

Everything is read from `GameReport`: `result` (the tag), `annotations` (ply, move number, colour,
SAN, `loss`, `winPercentBefore`, classification) and `evalGraph`. Nothing else, so the sentence can
never state a number or a move the report does not hold, and it re-derives instantly when the side
chooser changes.

| Constant | Value | Meaning |
|---|---|---|
| `BIG_SWING` | 20.0 | win-percent lost by one move that counts as a big error (the §2 BLUNDER line) |
| `FINE_FLOOR` | 40.0 | the mover's win-percent before the turning move at or above which they were "fine" |
| `COMEBACK_LEAD` | 65.0 | the mover's win-percent before the turning move at or above which the *other* side was behind |
| `SHORT_GAME_PLIES` | 20 | fewer plies than this is a short game (the §4 low-confidence length) |
| `CLOSE_LOW` / `CLOSE_HIGH` | 30.0 / 70.0 | White's win-percent staying inside this band at every position is a close game |

**Turning point** = the annotation with the largest `loss` among moves that lost more than 0.5 and
are neither BOOK nor FORCED; **a tie goes to the later move**. (`VideoScriptGenerator` picks the
*earlier* move on an exact tie; a tie of two floating-point losses is vanishingly rare, but the two
are not the same rule.)

**Result.** `1-0` and `0-1` name the winner, `1/2-1/2` is a draw, anything else is "no result". A
**mate is claimed only when** the result names a winner, the last move's SAN ends in `#` **and** that
move was played by the winner; a tag that disagrees with the board wins, so the sentence can never
contradict the result. The PGN result tag cannot tell a resignation from a flag fall, so no sentence
claims either.

### 12.2 Cases, first match wins

| # | Condition | Kind | English (you as viewer) |
|---|---|---|---|
| 0 | no moves | *(no sentence)* | |
| 1 | no result | `UNFINISHED` | The game stops after 23 moves without a result. |
| 2 | plies < `SHORT_GAME_PLIES` | `SHORT_GAME` | A short game: you mated your opponent in 8 moves. / ... won in 8 moves. / A short game: it ended in a draw after 9 moves. |
| 3 | decisive, turning loss >= `BIG_SWING`, **winner made it** | `WON_DESPITE_ERROR` | You won, even after a blunder on move 15. |
| 4 | decisive, big swing by the loser, loser's `winPercentBefore` >= `COMEBACK_LEAD` | `COMEBACK` | You were behind when your opponent's blunder on move 21 turned the game around. |
| 5 | decisive, big swing by the loser, `winPercentBefore` >= `FINE_FLOOR` | `DECIDED_BY_ERROR` | You were fine until move 11, then a blunder decided it. |
| 6 | decisive, big swing by the loser, `winPercentBefore` < `FINE_FLOOR` | `SEALED_BY_ERROR` | You were already under pressure, and a blunder on move 21 sealed it. |
| 7 | decisive, no big swing, **no mate**, White's win-percent inside the close band throughout | `CLOSE_CLEAN` | A close game: neither side made a big mistake. |
| 8 | decisive, no big swing | `CLEAN_WIN` | You won by checkmate on move 30, and neither side made a big mistake. / You won, and neither side made a big mistake. |
| 9 | draw, turning loss >= `BIG_SWING` | `DRAW_WITH_SWING` | It ended in a draw, but your mistake on move 14 was the big swing. |
| 10 | draw, no big swing | `CLOSE_CLEAN` | A close game: neither side made a big mistake. |

The error word comes from the turning move's classification: BLUNDER "blunder", MISTAKE "mistake",
MISS "missed win", anything else "big swing". Move numbers are the annotation's full-move number;
game lengths are `(plies + 1) / 2`.

### 12.3 Side framing

With the user's side known (`userColor` set and not "Not me") the user's side is `Person.SECOND`
("you") and the other side `Person.THIRD` with `viewerKnown = true` ("your opponent"; possessive
"your opponent's"). With no side, or "Not me", both are `Person.THIRD` and named by colour. This uses
the same `Subject` mechanism as every other sentence: the viewer's `Gender` rides on their `Subject`
for a language that inflects on it, the opponent's is always `UNSPECIFIED`, and English never
genders anyone. "Black was fine until move 11, then a blunder decided it." is the colour form of case 5.
