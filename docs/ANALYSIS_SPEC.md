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
at the same configured depth or the deltas become noise.

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
  `PAWN_FORK` when the forker is a pawn.
- **DOUBLE_ATTACK** — >= 2 enemy units attacked as a result of the move (including via
  discovery) that were not both attacked before.
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
  capture wins material by SEE.
- **BACK_RANK_MATE** — mate (or a mate threat within 2) on the 1st/8th rank where the
  enemy king's escape squares are blocked by its own pawns.
- **SMOTHERED_MATE** — knight mate with the king fully surrounded by its own pieces.
- **DEFLECTION / REMOVING_THE_DEFENDER** — the move attacks or captures a piece whose
  removal leaves another enemy unit or key square undefended, and the engine PV exploits
  exactly that.
- **DECOY** — the move (usually a sacrifice) lures an enemy piece or king to a square where
  it is then forked, skewered or mated in the PV.
- **OVERLOADED_PIECE** — an enemy piece is the sole defender of >= 2 things and the PV
  exploits it.
- **INTERFERENCE / CLEARANCE** — the move blocks an enemy line / vacates a friendly line
  and the PV uses it.
- **TRAPPED_PIECE** — an enemy piece worth >= 300 with no safe square (every legal
  destination loses material by SEE).
- **ZWISCHENZUG** — an in-between move (check or larger threat) inserted before the
  expected recapture, per the engine PV.
- **MATE_NET** — the engine reports mate in <= 5 and the move is part of it.
- **GREEK_GIFT** — Bxh7+/Bxh2+ sacrifice with Ng5+/Ng4+ and Qh5/Qh4 follow-up in the PV.
- **WINDMILL** — a repeating discovered-check-plus-capture cycle in the PV.
- **PROMOTION_TACTIC, UNDERPROMOTION, PASSED_PAWN_BREAKTHROUGH, DESPERADO,
  PERPETUAL_CHECK, STALEMATE_TRICK, FORTRESS, BATTERY, X_RAY** — detect per their standard
  definitions; these are lower priority and may report lower confidence.

**Confidence:** a tactic detected purely by static pattern = 0.6. If the engine PV confirms
the follow-up (the PV plays the exploiting move within 4 plies) = 0.95. Only report tactics
with confidence >= 0.6; sort by confidence, then by materialSwing.

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

---

## 7. Commentary generation

Each ply produces `MoveAnnotation`:

    classification, loss, evalBefore, evalAfter, bestMoveSan, bestLineSan: List<String>,
    tacticsFound: List<TacticInstance>, tacticsMissed: List<TacticInstance>,
    threatsAllowed: List<TacticInstance>, text: String, simulation: TacticSimulation?

`text` is generated deterministically from a template table keyed on classification plus
the highest-confidence tactic. Templates must read naturally and name concrete squares and
pieces. Examples:

- BLUNDER + missed HANGING_PIECE: "Blunder. This drops the knight on f6. Better was Qe2,
  keeping material level."
- BRILLIANT + DECOY: "Brilliant! The rook sacrifice on d7 drags the knight away from
  defending b8, and mate follows."
- MISS + MATE_NET: "Missed win. Qb8+ forced mate in 3."

No LLM at runtime — all commentary is template-generated and works offline.

---

## 8. Analysis engine settings

- Default depth **14**, MultiPV 3, one position at a time, configurable in Settings
  (depth 12 "fast" / 18 "standard" / 24 "deep").
- Analyse every ply of the game plus the final position.
- Progress reported per ply; analysis must be cancellable and resumable.
- Results cached per game so re-opening a game is instant.

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
  per-ply selection.
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

---

## 10. Tactic reference library

`core.analysis.TacticReferenceLibrary` holds one canonical teaching position per `TacticType`
where a clean one exists: a FEN, the solution line in SAN, and one teaching sentence. It exists
because a player's own missed fork is a fork buried in a messy position; the reference is the same
idea with nothing else on the board.

**Provenance is mandatory.** The source of truth is the `REFS` table in
`scripts/verify_tactic_references.py`, which checks every entry with python-chess for legality,
for the pattern's **own structural claim** (mate is mate, a fork attacks two valuable pieces, a pin
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
