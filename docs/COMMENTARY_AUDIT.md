# Commentary audit - every generated text, claim by claim (R1b)

The card texts and the walkthrough texts of the app are shown to the user as fact. Round 13 ended with
five wrong chess claims already seen on screen; this audit generated **every annotation text and every
walkthrough text** of the two recorded real games - the Immortal Game (23 moves, depth 12) and the Opera
Game (17 moves, depth 20), `core/src/test/resources/pacing/` - for no side, for White and for Black, and
checked each claim against the recorded position and the recorded engine data. Spec: `ANALYSIS_SPEC.md`
§5.3, §6.1, §7.1, §7.2; the code is `CommentaryGenerator`, `SimulationBuilder`, `MotifDetector`.

## Method

- **Independent verification.** `scripts/audit_commentary.py` re-derives each claim with
  [python-chess](https://python-chess.readthedocs.io), an engine independent of `:core`'s own move
  generator and detectors: attacks, defenders, pins and skewers by ray, exchange values by its own swap-off,
  legality of the moves named, who is mated according to the recorded engine line, and replays of the
  recorded principal variations for every "in the engine's line" claim. It reads the texts as
  `CommentaryAuditDumpTest` writes them (`core/build/commentary_audit/after.jsonl`), and, for the "before"
  table, the dump of the generator as it was before R1b (`docs/audit/commentary_before_r1b.txt`, produced
  with the pre-R1b generator verbatim over the same raw motifs).
- **Negative controls.** The verifier was fed deliberately broken texts (a wrong piece on a square, a
  wrong square, a capture credited with winning a queen, an illegal reply, the wrong rear piece of a pin, a queen
  where the knight stands, a move that is not the engine's top move, a wrong follow-up move in a
  clearance, a mate that is not a smothered mate) and flagged every one of them.
- **Verdicts.** **supported** - the position or the engine's numbers prove it. **harmless flavour** -
  true, and asserting nothing beyond the classification it follows ("d3 is a sound move."). **WRONG** -
  false, attributed to the wrong move or side, or **not provable from the data**: a claim that cannot
  be verified must not be asserted, so an unprovable assertion is counted as WRONG, not as flavour.
  A text is WRONG when any of its sentences is; harmless flavour when all of them are.
- **What was not audited.** The narrated video's own sentences (typed facts in `NarrationStrings`) are
  outside this audit, apart from the ones that repeated a wrong walkthrough claim (below).
- **White / Black variants.** The texts for White and for Black are the no-side text with only the
  subject words changed; the audit checks that mechanically (before: 0 of
  156 differ; after: 0 of
  156 differ), so each verdict below holds for all three.

## Summary

| | Before (Round 13) | After (R1b) |
|---|---|---|
| Annotation texts, 78 | **29 supported, 0 harmless flavour, 49 WRONG** | **75 supported, 3 harmless flavour, 0 WRONG** |
| ...as sentences | 89 supported, 16 flavour, 68 WRONG | 105 supported, 12 flavour, 0 WRONG |
| Walkthroughs | 16: 1 supported, 15 WRONG | 15: 15 supported, 0 WRONG |
| ...as parts (intro sentence, step, payoff) | 120 supported, 51 WRONG | 159 supported, 0 WRONG |

(The walkthrough count differs by one: 4...Bxf3's missed "deflection" was not a deflection, so there is no
walkthrough for it any more.)

## The 119 WRONG claims, by cause

| Cause | Count | Fix (where) |
|---|---|---|
| **A. A motif credited to the wrong move or side**: "This drops the bishop on b5" (the bishop is the opponent's), "This pins the piece on b7" for a move that pins nothing, "This forces mate" (the opponent's mate), "This traps the piece on g1" (the opponent's trap), "This sets up a clearance / x-ray / deflection" (the better move's, or the opponent's reply's) | 15 | `CommentaryGenerator` describes a motif only for its owner and its move (§7.2) |
| **B. An outcome verb stronger than the board**: "This wins the queen on g5" for a pawn that attacks it, "is left hanging and can be taken for nothing", "cannot be held", "collecting the pawn", "winning a pawn" for a rook taken for a bishop, "winning a rook" for a queen net +580, "winning a pawn" for a recapture | 31 | the verbs are "attacks" unless a capture is proved to win; `HANGING_PIECE` descriptions say "attacked"; `describeGain` names a piece only within 40 cp; recaptures judged by the pair (§6.1, §7.2, §5.3) |
| **C. "X allowed Y" charged against a move the engine approves of** (BEST, GREAT, BRILLIANT, or EXCELLENT / GOOD with a motif that wins nothing): "White allowed a deflection on f6" on the move that starts a forced mate | 29 | a charge only on INACCURACY / MISTAKE / BLUNDER, only for a reply that wins or mates (§7.2) |
| **D. A detector motif that is not what it says**: a clearance by the piece that just moved or by a pawn, a "deflection" whose defender captures on the square it guarded, a deflection credited to the side that is being mated | 7 | `MotifDetector` (clearance, deflection); the commentary refuses motifs of a line in which the mover is mated (§5.3, §7.2) |
| **E. Unprovable additions**: "has invested material in the attack" and "gains a decisive advantage" (16 walkthroughs, on the last step and as the "Result:" row), "keeping material level" | 35 | the payoff is what the line proves, else nothing (§6.1); "keeping material level" is gone (§7.2) |
| **F. "A stunning sacrifice" when nothing can be taken**: 14.Rd1, whose only attacker is pinned | 1 | the classifier requires a *legal* capture (§2); a brilliant text says "sacrifice" only when one is on the board (§7.2) |
| **G. The walkthrough intro says the first move twice** ("Qb4+ starts the line. Qb4+ clears e7...") | 1 | `SimulationIntro` (§6) |

## Known defects 1-7

| # | Defect | Status |
|---|---|---|
| 1 | "This forces mate. Better was Ba6." on 20...Na6 | Fixed: "This lets White play Nxg7+, which starts a forced mate. Better was Ba6." |
| 2 | A brilliant move says it "allowed" the motif it sets up | Fixed: no charge on a brilliant move; 10.Nxb5 reads "Nxb5 is a sacrifice: it offers the knight on b5." |
| 3 | "pins the piece on b7" for 6...Nf6 | Fixed: "This lets White play Qb3, which pins the pawn on b7 to the knight on b8. Better was Qf6." The pin is White's reply, not Black's move |
| 4 | Last walkthrough step: "Result: has invested material in the attack" | Fixed: a line that proves nothing has no payoff and the screen has no "Result" row |
| 5 | Both walkthrough intros repeat the first move | Fixed (G) |
| 6 | A 4-move game produced a 3 min 43 s video | Fixed: 54 s (ANALYSIS_SPEC §9.7) |
| 7 | After choosing a side the card still says "White allowed..." | Fixed: the text is written again for the side (§7.1) |

## Found by the audit, beyond defects 1-7

Besides the seven, the audit found these (all in the counts above, all fixed): "wins the queen on h4" / "on g5" / "the pawn on e4" for moves that only attack (A-B); "White allowed ..." on
every kind of engine-approved move (C); the walkthrough step "Bxg1 ... winning a pawn" for a rook taken for
a bishop, and "Qxd6 ... winning a pawn", "Bxd6 ... winning a queen" for recaptures (B); the 14.Rd1
"sacrifice" (F); "clears c4 so that Be2 can come through" for the bishop returning to where it came from
(D); "deflects the pawn on e5 away from guarding d6" for exd6 Qxd6 (D); "deflects the queen on b3 away
from guarding b8" for Black's 15...Nxd7, in the line in which White mates (D). Separately, in the
narrated video (not part of this audit but wrong in the same way): "has invested material in the attack"
and "ends up completely on top" as payoffs of a missed line, "a rook up, for nothing", "the lessons:
Three things" before one lesson, "found the engine's top move one times out of 8", "converted one
chances of your own" (all fixed, `NarrationClaimsTest`).

## C1 (2026-10-07): professional terms and deterministic variety, re-audited

The owner asked for commentary that "sounds more natural and interesting" with "more professional
terminology". `CommentaryGenerator` was rewritten with two or three phrasings per template, a register per
class, the evaluation in words, and the terms of `docs/COMMENTARY_STYLE.md` (the vocabulary-to-proof table
there is the contract). The rule of this audit did not move: every term is a claim with a proof on the board
or in the engine's numbers, and the verifier here re-derives each one with python-chess.

**What the verifier now checks, per term.** A "fork" / "pawn fork": the moved piece attacks every named
target (and is a pawn). "Absolute pin": the rear piece is the king; "relative pin": a bigger piece.
"Skewer", "discovered attack", "discovered check", "double check": the geometry on the position after the
move. "En prise" / "loose": attacked by the moved piece, no defender. "Trapped": every legal move of the
piece loses material by its own swap-off. "Wins the exchange": a knight or bishop takes a rook for a net
inside `rook - minor` ± 40 (a card or a walkthrough step); a line or a caption: the pieces counted between
the start and the settled end show one minor given, one rook taken and nothing else. "Zwischenzug": check
or a capture of something bigger first, a non-losing capture waiting on an enemy piece worth a minor or
more, and the recorded PV makes it on the mover's next move or the one after. "Overloaded": on the position
before the move the named piece is the opponent's and the sole defender of both squares, each an attacked
opponent's piece, and the PV lands on one. "Desperado": the piece could be taken where it stood (null
move), the capture loses material, it can be taken where it landed. A back-rank "threat": king boxed in by
its own men with a pawn among them, and the named rook or queen move mates after a null move. "Forced mate
in N": the engine's own distance, before the move for the mover's and the better move's, after it for the
reply's. "Only move": the engine's top move with a MultiPV gap of 10 win-percent. The evaluation words:
`standing_words(wpBefore) -> standing_words(wpAfter)` on the mover's win-percent, a crossed band, an error
class, the right subject. "A winning position" / "a decisive advantage" on a MISS: 82..95 / 95 and up.
Every lead ("X is the engine's first choice", "X is an only move", "X goes wrong") names the move played and
carries its class's proof (the top move; the MultiPV gap; the loss band). A sentence the verifier does not
recognise is WRONG, so a template that is added without its verifier fails the audit.

**Result (2026-10-07, `after`, three games x three sides, 432 texts; the no-side 144 are audited claim by
claim and the two side variants must differ from them only in the subject words):** 144 texts: 137
supported, 7 harmless flavour, **0 WRONG**; 243 sentences: 218 supported, 25 flavour, 0 WRONG; 0 of 288 side
variants differ beyond the subject words. `lines`: 432 move records, 678 lines, 18 video lines, 3 captions
say "the exchange", 2478 checks, **0 WRONG**. Before C1 the same dump audited 144 texts / 221 sentences,
0 WRONG: the 22 added sentences are the evaluation-in-words sentences on errors.

**Negative controls (`mutate`).** The verifier is fed the recorded texts with one template or term broken
at a time and must flag every one: a fork on pieces the moved piece does not attack, a pawn fork by a
knight, "wins the exchange" said as "wins a rook" and the reverse, a relative pin called absolute, "en
prise" for a defended piece, "loose" for a piece with defenders, forced mate in N said as N+1, the two
evaluation bands swapped, a zwischenzug on a quiet move, an overloaded defender that is not the sole guard,
an "only move" lead on a plain best move, a desperado on an ordinary capture, a back-rank threat that is
not there, a trapped piece with a safe square, "Better was" naming the wrong move, a charge against a move
the engine approved of, "a decisive advantage" on a MISS at 92.5 percent, a sacrifice of a piece nobody can
take, a smothered mate that is a plain mate, "delivers checkmate" on a quiet move. **24 mutations, 21
applied (three found no sentence of that shape in the three games: a real fork, a real skewer, a direct
check to relabel), 21 flagged, 0 missed.** The script exits non-zero if a mutation is not flagged.

**Narration.** The "found, not fixed" items below from R1b are fixed by C1 where they were material words:
`materialGain` / `materialPayoff` now name a unit only within 40 cp ("a rook up" for +800 is "material"),
and a line that gives a minor for a rook is "the exchange" (`MaterialGain.EXCHANGE`). The spoken motif
sentences carry the same terms as the cards with the same proofs; the narration is still outside this
script's claim-by-claim audit (`NarrationClaimsTest`, `NarrationStringsTest` hold its rules).

## C2 (2026-10-09): reworded texts, held to the original's facts

The optional on-device wording model (docs/LLM_REPHRASE_DESIGN.md) may only reword a text this audit already proved.
A rewording cannot be parsed by the template verifiers above, so it is held to **closure over the original's facts**
instead: `core.text.ClaimChecker` (Kotlin) and `scripts/rephrase_check.py` (an independent Python implementation,
written from a prose spec without reading the Kotlin) compare the moves, squares, pieces on squares, sides (in order),
numbers, term and outcome-verb counts, band order, names, negations, alternative-move markers and the order of
moves/outcomes/check of the two texts, plus count rules for hedges, praise and judgement words. A rewording that
fails any rule is not shown; the original is.

**Negative controls** (`ClaimCheckerTest`, `audit_commentary.py mutate-rephrase`): 24 mutation kinds (a square, a
piece, a side, "Better was" dropped/moved/renamed, a number, swapped bands, a term added or weakened, an outcome verb
added, a hedge, praise, doubled text, a list, a preamble, padding, the classification name, you -> White, a negation
dropped, an alternative marker dropped, an effect moved onto the played move, the first person, notation in
narration) on every distinct recorded text (190 cards, 388 narration beats): every mutation rejected in both
implementations; the two agree on all 8,507 lines (`audit_commentary.py rephrase core/build/rephrase/mutations.jsonl`).

**The model's outputs** (host, llama.cpp b11190, prompt v1; full tables with every rejection and a 60-pair quality
sample for the owner in `docs/audit/rephrase_*_p1.md`):

| Model | Cards: accepted / unchanged / rejected | Narration: accepted / unchanged / rejected |
|---|---|---|
| Qwen2.5-1.5B-Instruct Q4_K_M (ship), all 578 texts | 74 / 104 / 12 of 190 (rejection 6.3 %) | 195 / 103 / 90 of 388 (rejection 23.2 %) |

The Kotlin and the Python checker agree on every one of the 578 verdicts (and on the 3 x 287 of the comparison runs).
Three rules were added after reading the accepted outputs of the first run, because each let a real meaning change
through: "Rook takes the pawn on h seven was the move" reworded as "The rook takes the pawn on h seven" (a move that
was never played: alternative markers are now counted), "This hands White d4, which hits the loose bishop on c5"
reworded as "This hits the loose bishop on c5, giving White d4" (who hits the bishop: the order of moves and outcomes is
now compared), and "...it clears c4 so that Bc4 can come through" reworded with "allowing" (any form of "allow" is now
banned, as C1 banned "allowed").
## V4 (2026-10-09): the spoken best line and "Back to the game now."

The video's best line (ANALYSIS_SPEC 9.8) is narrated since V4: one segment per move that says the move, then
"Back to the game now." over the game's position; the detour's pivot-out opens with the same sentence. Two new
narration templates, so two new verifiers in `scripts/audit_commentary.py`:

- **`lines`, the spoken moves.** `CommentaryAuditDumpTest.dumpBestLines` now writes, for every video line, what the
  segments after the key moment actually say and show (`says`, `sayFens`, `sayUci`, `sayKinds`, `back`, `backFen`).
  Each spoken move is re-derived from python-chess's board (`expected_line_move`: the piece on the from-square, its
  square when another piece of its kind could reach the target, the target, the piece taken, en passant, the
  promotion, check or mate) and must be that sentence exactly, over the board the line has reached, as the line's
  own move; the segments must be the moves and then one return; the return must say "Back to the game now." over
  the game's position before the move.
- **`lines`, every return.** `video_returns.jsonl` lists every segment that says "Back to the game": it must open
  with the sentence, say it once, follow a best line's last move or a detour's payoff, and show the game's position
  before the move it is about.

**Result (2026-10-09, three games x three sides, Normal pace):** `lines` - 432 move records, 678 lines (246
alternatives), 15 video lines, 57 spoken line moves, 33 returns to the game (15 after a best line, 18 after a
detour); **2646 checks, 2646 supported, 0 WRONG** (2478 before V4: the new checks are the spoken moves, the
returns and their boards; three fewer video lines because game01's 13...Bc6 no longer fits the pace cap once its
line is spoken). `after` - unchanged: 144 texts, 0 WRONG; 0 of 288 side variants differ. `mutate` - the 24 card
mutations as before (0 missed), plus 7 on the spoken line: a move on the wrong square, a capture said as a quiet
move, the wrong piece, a check left out or added, notation instead of words, "Back to the game now." over the
line's last position instead of the game's, the return sentence missing: **7 applied, 7 flagged, 0 missed.**

## Found, not fixed

- `LessonPositive` and `LessonOpponentMissedToo` (below) are unchanged by C1.
- Terms the owner listed that C1 did not add, because their definition is not on the board or in one
  position's numbers: "simplifies", "liquidates", "trades into a winning endgame" (needs an endgame
  definition and an eval after the trade the data does not hold), "loses a tempo" (no provable definition
  of a lost tempo), "converts" (a judgement about a whole game), "x-ray" and "battery" (static, worth
  nothing by themselves; the card drops them as before).
- `LessonPositive` ("The plan-making is working; the gap is in the tactics...") and `LessonOpponentMissedToo`
  ("converted N chances of your own", counted from found motifs whether or not they were chances) assert a
  diagnosis the data does not support. They are narration, not annotation text.
- A BOOK move that the engine says lost 5 percent or more (2.f4 in the Immortal Game) still offers a "Show me"
  walkthrough of the better move, next to a card that says "f4 follows known opening theory." Both are true.
- The engine-line motifs are heuristics over the principal variation: a "deflection" may be an engine's
  best reply that was not induced. They are now stated as the engine's line ("In the engine's line, ..."),
  which is true, and the ones the audit could disprove are fixed, but a human might name some differently.
- A relative pin or x-ray that wins nothing is dropped from the card; it remains in the report's tactic buckets
  behind the significance gate, as before.

## Tests that keep it fixed

`CommentaryGeneratorTest` (defects 1-3 on real positions, ownership, the charge rule, the sacrifice leads;
since C1 also each new term on a real or constructed position with its negative: a zwischenzug on a quiet
move, an overload with a second defender, a desperado of a safe queen, a back-rank threat with luft, the
exchange against a free rook, the absolute pin, the mate distance, the evaluation bands, the variety's
determinism and stepping), `CommentaryClaimsTest` (every text of the three games for all three sides: every
sentence is one of the catalogued templates, every term is re-verified on the board in Kotlin as well, no
unprovable wording; "Better was" is the engine's move, once, last; a reply is a legal opponent move with
the right beneficiary; every piece named on a square stands there; second person only with a side; no two
consecutive cards of one class share a lead phrasing; text regenerated for a side equals the text an
analysis for that side would have written), `MotifClaimsTest` (clearance, deflection, descriptions),
`MoveClassifierTest` (a pinned attacker is not a sacrifice), `SimulationBuilderTest` /
`SimulationIntroTest` (collecting, recaptures, settled payoff, blank payoff, repeated first move),
`ExchangeEvaluatorTest`, `NarrationClaimsTest`, `PacingTinyGameTest`, and in `:app` `SideCommentaryMappingTest`.

## Re-running it

    ./gradlew :core:test --tests '*CommentaryAuditDumpTest*'
    python scripts/audit_commentary.py after  core/build/commentary_audit/after.jsonl
    python scripts/audit_commentary.py before docs/audit/commentary_before_r1b.txt
    python scripts/audit_commentary.py lines  core/build/commentary_audit/best_lines.jsonl
    python scripts/audit_commentary.py mutate core/build/commentary_audit/after.jsonl

`after` covers the Immortal Game, the Opera Game and game01 since C1 (the dump test writes all three),
and exits non-zero on any WRONG claim or side variant; `mutate` is the negative control (above).

The `lines` mode (V2, ANALYSIS_SPEC §6.2) audits every engine line the Board's "Show the best line" can display
and every line the video plays, for the Immortal Game, the Opera Game and game01 and all three sides: each move
legal and its SAN python-chess's, a prefix of the recorded PV cut by `min(PV, depth / 2, 8)` (shorter only at
mate), alternatives within 2 win-% and never the move played, and every caption sentence re-derived (the
engine's score and mate, checkmate from the board, the settled material gain by python-chess's own exchange
evaluation and the 40 cp names, "the exchange" by the piece count since C1). Result on 2026-10-07: 432 move
records, 678 lines, 18 video lines, 2478 checks, 0 WRONG; after C1 the same, with 3 captions saying "the exchange".

(Needs `python-chess`; `scripts/record_analysis.py` also needs a Stockfish binary and is only used to record
the analyses the tests replay.)

---

## Table 1 - annotation texts, before (Round 13)

Text as shown for no side chosen. "Sentences" is one letter per sentence: S supported, F harmless flavour, W WRONG.

78 texts: 29 supported, 0 harmless flavour, 49 WRONG. 173 sentences: 89 supported, 16 flavour, 68 WRONG.

| Game | Ply | Move | Class | Text | Sentences | Verdict |
|---|---|---|---|---|---|---|
| immortal | 1 | e4 | BOOK | e4 follows known opening theory. | S | supported |
| immortal | 2 | e5 | BOOK | e5 follows known opening theory. | S | supported |
| immortal | 3 | f4 | BOOK | f4 follows known opening theory. | S | supported |
| immortal | 4 | exf4 | BOOK | exf4 follows known opening theory. | S | supported |
| immortal | 5 | Bc4 | BOOK | Bc4 follows known opening theory. | S | supported |
| immortal | 6 | Qh4+ | BOOK | Qh4+ follows known opening theory. | S | supported |
| immortal | 7 | Kf1 | BOOK | Kf1 follows known opening theory. | S | supported |
| immortal | 8 | b5 | BOOK | b5 follows known opening theory. | S | supported |
| immortal | 9 | Bxb5 | BEST | Bxb5 matches the engine's top choice. This sets up a clearance on e2. White allowed a hanging piece on e4. | SWW | WRONG |
| immortal | 10 | Nf6 | BEST | Nf6 matches the engine's top choice. This wins the pawn on e4. Black allowed a hanging piece on h4. | SWW | WRONG |
| immortal | 11 | Nf3 | BEST | Nf3 matches the engine's top choice. This wins the queen on h4. White allowed a relative pin on h2. | SWW | WRONG |
| immortal | 12 | Qh6 | BEST | Qh6 matches the engine's top choice. This pins the piece on h2. | SS | supported |
| immortal | 13 | d3 | GOOD | d3 is a sound move. This pins the piece on f4. White allowed a hanging piece on b5. | FSF | supported |
| immortal | 14 | Nh5 | INACCURACY | This drops the bishop on b5. Black allowed an x-ray on g7. Better was c6, keeping material level. | WFW | WRONG |
| immortal | 15 | Nh4 | INACCURACY | This sets up an x-ray on g7. Better was Rg1. | WS | WRONG |
| immortal | 16 | Qg5 | EXCELLENT | Qg5 is very close to the best move. This wins the bishop on b5. Black allowed a clearance on h4. | SWW | WRONG |
| immortal | 17 | Nf5 | GREAT | Nf5 was the only move that kept things on track. This sets up a clearance on h4. White allowed a hanging piece on f5. | SWW | WRONG |
| immortal | 18 | c6 | INACCURACY | This drops the knight on f5. Black allowed an x-ray on d7. Better was g6, keeping material level. | WFW | WRONG |
| immortal | 19 | g4 | MISTAKE | This sets up a clearance on h5. Better was Ba4. | WS | WRONG |
| immortal | 20 | Nf6 | BEST | Nf6 matches the engine's top choice. This sets up a clearance on h5. Black allowed an x-ray on g5. | SWW | WRONG |
| immortal | 21 | Rg1 | BEST | Rg1 matches the engine's top choice. This sets up an x-ray on g5. White allowed a hanging piece on g4. | SSW | WRONG |
| immortal | 22 | cxb5 | BLUNDER | This drops the pawn on g4. Black allowed a hanging piece on g5. Better was h5, keeping material level. | WSW | WRONG |
| immortal | 23 | h4 | GREAT | h4 was the only move that kept things on track. This wins the queen on g5. White allowed a relative pin on g4. | SWW | WRONG |
| immortal | 24 | Qg6 | GREAT | Qg6 was the only move that kept things on track. This pins the piece on g4. Black allowed a deflection on h5. | SSW | WRONG |
| immortal | 25 | h5 | GREAT | h5 was the only move that kept things on track. This sets up a deflection on h5. White allowed a clearance on c6. | SWW | WRONG |
| immortal | 26 | Qg5 | GOOD | Qg5 is a sound move. This pins the piece on g4. Black allowed a hanging piece on f4. | FSF | supported |
| immortal | 27 | Qf3 | BEST | Qf3 matches the engine's top choice. This wins the pawn on f4. White allowed a relative pin on e4. | SSW | WRONG |
| immortal | 28 | Ng8 | INACCURACY | This sets up a deflection on f4. Better was Bb7. | WS | WRONG |
| immortal | 29 | Bxf4 | GOOD | Bxf4 is a sound move. This wins the queen on g5. White allowed a relative pin on b2. | FWF | WRONG |
| immortal | 30 | Qf6 | BEST | Qf6 matches the engine's top choice. This pins the piece on b2. Black allowed a hanging piece on b5. | SSW | WRONG |
| immortal | 31 | Nc3 | GREAT | Nc3 was the only move that kept things on track. This wins the pawn on b5. White allowed an x-ray on c2. | SWW | WRONG |
| immortal | 32 | Bc5 | MISTAKE | This drops the bishop on c5. Better was Qc6, keeping material level. | WW | WRONG |
| immortal | 33 | Nd5 | MISTAKE | This drops the rook on a1. Better was d4, keeping material level. | WW | WRONG |
| immortal | 34 | Qxb2 | BEST | Qxb2 matches the engine's top choice. This wins the rook on a1. Black allowed an x-ray on e8. | SWW | WRONG |
| immortal | 35 | Bd6 | BLUNDER | This sets up a clearance on b2. Better was Re1. | WS | WRONG |
| immortal | 36 | Bxg1 | BLUNDER | This traps the piece on g1. Better was Qxa1+. | WS | WRONG |
| immortal | 37 | e5 | GOOD | e5 is a sound move. This wins the pawn on g7. White allowed a fork on f1. | FWF | WRONG |
| immortal | 38 | Qxa1+ | BEST | Qxa1+ matches the engine's top choice. This forks pieces on f1 and a2. | SS | supported |
| immortal | 39 | Ke2 | GREAT | Ke2 was the only move that kept things on track. White allowed an x-ray on d3. | SW | WRONG |
| immortal | 40 | Na6 | BLUNDER | This forces mate. Better was Ba6. | WS | WRONG |
| immortal | 41 | Nxg7+ | GREAT | Nxg7+ was the only move that kept things on track. This forces mate. White allowed a deflection on f6. | SSW | WRONG |
| immortal | 42 | Kd8 | FORCED | Kd8 was the only legal move. | S | supported |
| immortal | 43 | Qf6+ | BEST | Qf6+ matches the engine's top choice. This forces mate. White allowed a hanging piece on d5. | SSW | WRONG |
| immortal | 44 | Nxf6 | BEST | Nxf6 matches the engine's top choice. This wins the knight on d5. Black allowed a mating net on d8. | SWW | WRONG |
| immortal | 45 | Be7# | GREAT | Be7# was the only move that kept things on track. This forces mate. | SS | supported |
| chesscom | 1 | e4 | BOOK | e4 follows known opening theory. | S | supported |
| chesscom | 2 | e5 | BOOK | e5 follows known opening theory. | S | supported |
| chesscom | 3 | Nf3 | BOOK | Nf3 follows known opening theory. | S | supported |
| chesscom | 4 | d6 | BOOK | d6 follows known opening theory. | S | supported |
| chesscom | 5 | d4 | BOOK | d4 follows known opening theory. | S | supported |
| chesscom | 6 | Bg4 | BOOK | Bg4 follows known opening theory. | S | supported |
| chesscom | 7 | dxe5 | BOOK | dxe5 follows known opening theory. | S | supported |
| chesscom | 8 | Bxf3 | INACCURACY | This sets up a deflection on d6. Black allowed a relative pin on f7. Better was Nc6. | WFS | WRONG |
| chesscom | 9 | Qxf3 | BEST | Qxf3 matches the engine's top choice. This pins the piece on f7. | SS | supported |
| chesscom | 10 | dxe5 | BEST | dxe5 matches the engine's top choice. Black allowed a relative pin on b7. | SW | WRONG |
| chesscom | 11 | Bc4 | INACCURACY | This pins the piece on b7. White allowed a skewer on f3. Better was Qb3. | WFS | WRONG |
| chesscom | 12 | Nf6 | MISTAKE | This pins the piece on b7. Better was Qf6. | WS | WRONG |
| chesscom | 13 | Qb3 | GREAT | Qb3 was the only move that kept things on track. This pins the piece on b7. White allowed a deflection on b4. | SSW | WRONG |
| chesscom | 14 | Qe7 | BEST | Qe7 matches the engine's top choice. This sets up a deflection on b4. Black allowed a deflection on b4. | SSW | WRONG |
| chesscom | 15 | Nc3 | EXCELLENT | Nc3 is very close to the best move. | S | supported |
| chesscom | 16 | c6 | BEST | c6 matches the engine's top choice. Black allowed a clearance on c1. | SW | WRONG |
| chesscom | 17 | Bg5 | EXCELLENT | Bg5 is very close to the best move. This sets up a deflection on f7. | SS | supported |
| chesscom | 18 | b5 | MISTAKE | This sets up a deflection on b4. Better was Kd8. | WS | WRONG |
| chesscom | 19 | Nxb5 | BRILLIANT | Nxb5 is a stunning sacrifice. This sets up a deflection on b4. White allowed a deflection on b4. | SWW | WRONG |
| chesscom | 20 | cxb5 | INACCURACY | This sets up a deflection on b4. Black allowed a discovered attack on f7. Better was Qb4+. | WFS | WRONG |
| chesscom | 21 | Bxb5+ | GREAT | Bxb5+ was the only move that kept things on track. This opens a discovered attack. | SS | supported |
| chesscom | 22 | Nbd7 | BEST | Nbd7 matches the engine's top choice. | S | supported |
| chesscom | 23 | O-O-O | BEST | O-O-O matches the engine's top choice. White allowed a deflection on d7. | SW | WRONG |
| chesscom | 24 | Rd8 | EXCELLENT | Rd8 is very close to the best move. This sets up a deflection on d7. Black allowed a deflection on e7. | SWF | WRONG |
| chesscom | 25 | Rxd7 | BEST | Rxd7 matches the engine's top choice. This sets up a deflection on e7. White allowed a removing the defender on e7. | SWW | WRONG |
| chesscom | 26 | Rxd7 | EXCELLENT | Rxd7 is very close to the best move. Black allowed a deflection on f6. | SW | WRONG |
| chesscom | 27 | Rd1 | BRILLIANT | Rd1 is a stunning sacrifice. This sets up a deflection on f6. White allowed a clearance on c5. | WSW | WRONG |
| chesscom | 28 | Qe6 | EXCELLENT | Qe6 is very close to the best move. This sets up an x-ray on e4. Black allowed a clearance on b8. | SSW | WRONG |
| chesscom | 29 | Bxd7+ | BEST | Bxd7+ matches the engine's top choice. This sets up a clearance on b8. White allowed a desperado on d7. | SSW | WRONG |
| chesscom | 30 | Nxd7 | GOOD | Nxd7 is a sound move. This sets up a deflection on b8. Black allowed a mating net on e8. | FWF | WRONG |
| chesscom | 31 | Qb8+ | GREAT | Qb8+ was the only move that kept things on track. This forces mate. | SS | supported |
| chesscom | 32 | Nxb8 | FORCED | Nxb8 was the only legal move. | S | supported |
| chesscom | 33 | Rd8# | GREAT | Rd8# was the only move that kept things on track. This forces mate. | SS | supported |

## Table 2 - annotation texts, after (R1b)

78 texts: 75 supported, 3 harmless flavour, 0 WRONG. 117 sentences: 105 supported, 12 flavour, 0 WRONG.

| Game | Ply | Move | Class | Text | Sentences | Verdict |
|---|---|---|---|---|---|---|
| immortal | 1 | e4 | BOOK | e4 follows known opening theory. | S | supported |
| immortal | 2 | e5 | BOOK | e5 follows known opening theory. | S | supported |
| immortal | 3 | f4 | BOOK | f4 follows known opening theory. | S | supported |
| immortal | 4 | exf4 | BOOK | exf4 follows known opening theory. | S | supported |
| immortal | 5 | Bc4 | BOOK | Bc4 follows known opening theory. | S | supported |
| immortal | 6 | Qh4+ | BOOK | Qh4+ follows known opening theory. | S | supported |
| immortal | 7 | Kf1 | BOOK | Kf1 follows known opening theory. | S | supported |
| immortal | 8 | b5 | BOOK | b5 follows known opening theory. | S | supported |
| immortal | 9 | Bxb5 | BEST | Bxb5 matches the engine's top choice. | S | supported |
| immortal | 10 | Nf6 | BEST | Nf6 matches the engine's top choice. This attacks the undefended pawn on e4. | SS | supported |
| immortal | 11 | Nf3 | BEST | Nf3 matches the engine's top choice. This attacks the undefended queen on h4. | SS | supported |
| immortal | 12 | Qh6 | BEST | Qh6 matches the engine's top choice. | S | supported |
| immortal | 13 | d3 | GOOD | d3 is a sound move. | F | flavour |
| immortal | 14 | Nh5 | INACCURACY | Nh5 gives back ground. Better was c6, which attacks the undefended bishop on b5. | FS | supported |
| immortal | 15 | Nh4 | INACCURACY | Nh4 gives back ground. Better was Rg1. | FS | supported |
| immortal | 16 | Qg5 | EXCELLENT | Qg5 is very close to the best move. This attacks the undefended bishop on b5. | SS | supported |
| immortal | 17 | Nf5 | GREAT | Nf5 was the only move that kept things on track. | S | supported |
| immortal | 18 | c6 | INACCURACY | c6 gives back ground. Better was g6, which attacks the knight on f5 with a pawn. | FS | supported |
| immortal | 19 | g4 | MISTAKE | This lets Black play Nf6, which attacks the pawn on g4 more often than it is defended. Better was Ba4. | SS | supported |
| immortal | 20 | Nf6 | BEST | Nf6 matches the engine's top choice. This attacks the pawn on g4 more often than it is defended. | SS | supported |
| immortal | 21 | Rg1 | BEST | Rg1 matches the engine's top choice. | S | supported |
| immortal | 22 | cxb5 | BLUNDER | This lets White play h4, which attacks the undefended queen on g5. Better was h5, which attacks the pawn on g4 more often than it is defended. | SS | supported |
| immortal | 23 | h4 | GREAT | h4 was the only move that kept things on track. This attacks the undefended queen on g5. | SS | supported |
| immortal | 24 | Qg6 | GREAT | Qg6 was the only move that kept things on track. | S | supported |
| immortal | 25 | h5 | GREAT | h5 was the only move that kept things on track. This attacks the queen on g6 with a pawn. | SS | supported |
| immortal | 26 | Qg5 | GOOD | Qg5 is a sound move. | F | flavour |
| immortal | 27 | Qf3 | BEST | Qf3 matches the engine's top choice. This leaves the queen on g5 with no safe square. | SS | supported |
| immortal | 28 | Ng8 | INACCURACY | This lets White play Nc3; in the engine's line it deflects the queen on g5 away from guarding f4. Better was Bb7. | SS | supported |
| immortal | 29 | Bxf4 | GOOD | Bxf4 is a sound move. This attacks the undefended queen on g5. | FS | supported |
| immortal | 30 | Qf6 | BEST | Qf6 matches the engine's top choice. This pins the pawn on b2 to the rook on a1. | SS | supported |
| immortal | 31 | Nc3 | GREAT | Nc3 was the only move that kept things on track. This attacks the undefended pawn on b5. | SS | supported |
| immortal | 32 | Bc5 | MISTAKE | This lets White play d4, which attacks the undefended bishop on c5. Better was Qc6. | SS | supported |
| immortal | 33 | Nd5 | MISTAKE | This lets Black play Qxb2, which attacks the undefended rook on a1. Better was d4, which attacks the undefended bishop on c5. | SS | supported |
| immortal | 34 | Qxb2 | BEST | Qxb2 matches the engine's top choice. This attacks the undefended rook on a1. | SS | supported |
| immortal | 35 | Bd6 | BLUNDER | This lets Black play Qxa1+, which wins a rook. Better was Re1. | SS | supported |
| immortal | 36 | Bxg1 | BLUNDER | This lets White play Re1, which leaves the bishop on g1 with no safe square. Better was Qxa1+, which wins a rook. | SS | supported |
| immortal | 37 | e5 | GOOD | e5 is a sound move. This leaves the pawn on g7 undefended, with the knight on f5 attacking it. | FS | supported |
| immortal | 38 | Qxa1+ | BEST | Qxa1+ matches the engine's top choice. This wins a rook. | SS | supported |
| immortal | 39 | Ke2 | GREAT | Ke2 was the only move that kept things on track. | S | supported |
| immortal | 40 | Na6 | BLUNDER | This lets White play Nxg7+, which starts a forced mate. Better was Ba6. | SS | supported |
| immortal | 41 | Nxg7+ | GREAT | Nxg7+ was the only move that kept things on track. This starts a forced mate. | SS | supported |
| immortal | 42 | Kd8 | FORCED | Kd8 was the only legal move. | S | supported |
| immortal | 43 | Qf6+ | BEST | Qf6+ matches the engine's top choice. This starts a forced mate. | SS | supported |
| immortal | 44 | Nxf6 | BEST | Nxf6 matches the engine's top choice. | S | supported |
| immortal | 45 | Be7# | GREAT | Be7# was the only move that kept things on track. This is checkmate. | SS | supported |
| chesscom | 1 | e4 | BOOK | e4 follows known opening theory. | S | supported |
| chesscom | 2 | e5 | BOOK | e5 follows known opening theory. | S | supported |
| chesscom | 3 | Nf3 | BOOK | Nf3 follows known opening theory. | S | supported |
| chesscom | 4 | d6 | BOOK | d6 follows known opening theory. | S | supported |
| chesscom | 5 | d4 | BOOK | d4 follows known opening theory. | S | supported |
| chesscom | 6 | Bg4 | BOOK | Bg4 follows known opening theory. | S | supported |
| chesscom | 7 | dxe5 | BOOK | dxe5 follows known opening theory. | S | supported |
| chesscom | 8 | Bxf3 | INACCURACY | Bxf3 gives back ground. Better was Nc6. | FS | supported |
| chesscom | 9 | Qxf3 | BEST | Qxf3 matches the engine's top choice. | S | supported |
| chesscom | 10 | dxe5 | BEST | dxe5 matches the engine's top choice. | S | supported |
| chesscom | 11 | Bc4 | INACCURACY | Bc4 gives back ground. Better was Qb3, which pins the pawn on b7 to the knight on b8. | FS | supported |
| chesscom | 12 | Nf6 | MISTAKE | This lets White play Qb3, which pins the pawn on b7 to the knight on b8. Better was Qf6. | SS | supported |
| chesscom | 13 | Qb3 | GREAT | Qb3 was the only move that kept things on track. This pins the pawn on b7 to the knight on b8. | SS | supported |
| chesscom | 14 | Qe7 | BEST | Qe7 matches the engine's top choice. | S | supported |
| chesscom | 15 | Nc3 | EXCELLENT | Nc3 is very close to the best move. | S | supported |
| chesscom | 16 | c6 | BEST | c6 matches the engine's top choice. | S | supported |
| chesscom | 17 | Bg5 | EXCELLENT | Bg5 is very close to the best move. In the engine's line, Bg5 deflects the king on e8 away from guarding f7. | SS | supported |
| chesscom | 18 | b5 | MISTAKE | b5 gives back ground. Better was Kd8. | FS | supported |
| chesscom | 19 | Nxb5 | BRILLIANT | Nxb5 is a sacrifice: it offers the knight on b5. | S | supported |
| chesscom | 20 | cxb5 | INACCURACY | cxb5 gives back ground. Better was Qb4+; in the engine's line it clears e7 so that Bxb4+ can come through. | FS | supported |
| chesscom | 21 | Bxb5+ | GREAT | Bxb5+ was the only move that kept things on track. | S | supported |
| chesscom | 22 | Nbd7 | BEST | Nbd7 matches the engine's top choice. | S | supported |
| chesscom | 23 | O-O-O | BEST | O-O-O matches the engine's top choice. | S | supported |
| chesscom | 24 | Rd8 | EXCELLENT | Rd8 is very close to the best move. | S | supported |
| chesscom | 25 | Rxd7 | BEST | Rxd7 matches the engine's top choice. In the engine's line, Rxd7 drags the knight on f6 off the same diagonal, and Bxe7 follows. | SS | supported |
| chesscom | 26 | Rxd7 | EXCELLENT | Rxd7 is very close to the best move. | S | supported |
| chesscom | 27 | Rd1 | BEST | Rd1 matches the engine's top choice. In the engine's line, Rd1 deflects the queen on e7 away from guarding f6. | SS | supported |
| chesscom | 28 | Qe6 | EXCELLENT | Qe6 is very close to the best move. | S | supported |
| chesscom | 29 | Bxd7+ | BEST | Bxd7+ matches the engine's top choice. In the engine's line, Bxd7+ clears b5 so that Qb8+ can come through. | SS | supported |
| chesscom | 30 | Nxd7 | GOOD | Nxd7 is a sound move. | F | flavour |
| chesscom | 31 | Qb8+ | GREAT | Qb8+ was the only move that kept things on track. This starts a forced mate. | SS | supported |
| chesscom | 32 | Nxb8 | FORCED | Nxb8 was the only legal move. | S | supported |
| chesscom | 33 | Rd8# | GREAT | Rd8# was the only move that kept things on track. This is checkmate. | SS | supported |

## Table 3 - walkthroughs, before (Round 13)

Intro column: one letter per intro sentence; Steps: how many step sentences are supported (S) and WRONG (W).

16 walkthroughs: 1 supported, 15 WRONG. 171 parts (intro sentences, steps, payoff): 120 supported, 51 WRONG.

| Game | Ply | Move | Intro | Steps | Payoff | Verdict |
|---|---|---|---|---|---|---|
| immortal | 3 | f4 | SW | 6S 2W | gains a decisive advantage | WRONG |
| immortal | 14 | Nh5 | SW | 6S 2W | has invested material in the attack | WRONG |
| immortal | 15 | Nh4 | SS | 5S 3W | has invested material in the attack | WRONG |
| immortal | 18 | c6 | SW | 6S 2W | has invested material in the attack | WRONG |
| immortal | 19 | g4 | SS | 7S 1W | has invested material in the attack | WRONG |
| immortal | 22 | cxb5 | SW | 6S 2W | gains a decisive advantage | WRONG |
| immortal | 28 | Ng8 | SS | 6S 2W | has invested material in the attack | WRONG |
| immortal | 32 | Bc5 | SS | 6S 2W | gains a decisive advantage | WRONG |
| immortal | 33 | Nd5 | SW | 6S 2W | gains a decisive advantage | WRONG |
| immortal | 35 | Bd6 | SS | 7S 1W | has invested material in the attack | WRONG |
| immortal | 36 | Bxg1 | SS | 2S 0W | wins a rook | supported |
| immortal | 40 | Na6 | SS | 7S 1W | has invested material in the attack | WRONG |
| chesscom | 8 | Bxf3 | SWS | 5S 3W | gains a decisive advantage | WRONG |
| chesscom | 11 | Bc4 | SS | 6S 2W | gains a decisive advantage | WRONG |
| chesscom | 12 | Nf6 | SS | 7S 1W | has invested material in the attack | WRONG |
| chesscom | 20 | cxb5 | SS | 4S 4W | has invested material in the attack | WRONG |

## Table 4 - walkthroughs, after (R1b)

15 walkthroughs: 15 supported, 0 WRONG. 159 parts (intro sentences, steps, payoff): 159 supported, 0 WRONG.

| Game | Ply | Move | Intro | Steps | Payoff | Verdict |
|---|---|---|---|---|---|---|
| immortal | 3 | f4 | SS | 8S 0W | (none) | supported |
| immortal | 14 | Nh5 | SS | 8S 0W | (none) | supported |
| immortal | 15 | Nh4 | SS | 8S 0W | (none) | supported |
| immortal | 18 | c6 | SS | 8S 0W | (none) | supported |
| immortal | 19 | g4 | SS | 8S 0W | (none) | supported |
| immortal | 22 | cxb5 | SS | 8S 0W | (none) | supported |
| immortal | 28 | Ng8 | SS | 8S 0W | (none) | supported |
| immortal | 32 | Bc5 | SS | 8S 0W | (none) | supported |
| immortal | 33 | Nd5 | SS | 8S 0W | (none) | supported |
| immortal | 35 | Bd6 | SS | 8S 0W | (none) | supported |
| immortal | 36 | Bxg1 | SS | 2S 0W | wins a rook | supported |
| immortal | 40 | Na6 | SS | 8S 0W | (none) | supported |
| chesscom | 11 | Bc4 | SS | 8S 0W | (none) | supported |
| chesscom | 12 | Nf6 | SS | 8S 0W | (none) | supported |
| chesscom | 20 | cxb5 | SS | 8S 0W | (none) | supported |

## Every WRONG claim, quoted (before)

- immortal ply 9 Bxb5: "This sets up a clearance on e2." - the same piece comes back, or a pawn steps in: nothing is cleared for anyone
- immortal ply 9 Bxb5: "White allowed a hanging piece on e4." - charges a move rated BEST (the engine's own choice or better) with 'allowing' something
- immortal ply 10 Nf6: "This wins the pawn on e4." - the move does not capture it and the engine's line does not take it: it at most attacks it
- immortal ply 10 Nf6: "Black allowed a hanging piece on h4." - charges a move rated BEST (the engine's own choice or better) with 'allowing' something
- immortal ply 11 Nf3: "This wins the queen on h4." - the move does not capture it and the engine's line does not take it: it at most attacks it
- immortal ply 11 Nf3: "White allowed a relative pin on h2." - charges a move rated BEST (the engine's own choice or better) with 'allowing' something
- immortal ply 14 Nh5: "This drops the bishop on b5." - the bishop belongs to the OPPONENT: the mover does not drop it
- immortal ply 14 Nh5: "Better was c6, keeping material level." - 'keeping material level' is not provable (nothing says the move holds material)
- immortal ply 15 Nh4: "This sets up an x-ray on g7." - the motif belongs to a different move (the better move or the opponent's reply)
- immortal ply 16 Qg5: "This wins the bishop on b5." - the move does not capture it and the engine's line does not take it: it at most attacks it
- immortal ply 16 Qg5: "Black allowed a clearance on h4." - charges a move rated EXCELLENT with 'allowing' a motif that wins nothing
- immortal ply 17 Nf5: "This sets up a clearance on h4." - the same piece comes back, or a pawn steps in: nothing is cleared for anyone
- immortal ply 17 Nf5: "White allowed a hanging piece on f5." - charges a move rated GREAT (the engine's own choice or better) with 'allowing' something
- immortal ply 18 c6: "This drops the knight on f5." - the knight belongs to the OPPONENT: the mover does not drop it
- immortal ply 18 c6: "Better was g6, keeping material level." - 'keeping material level' is not provable (nothing says the move holds material)
- immortal ply 19 g4: "This sets up a clearance on h5." - the motif belongs to a different move (the better move or the opponent's reply)
- immortal ply 20 Nf6: "This sets up a clearance on h5." - the same piece comes back, or a pawn steps in: nothing is cleared for anyone
- immortal ply 20 Nf6: "Black allowed an x-ray on g5." - charges a move rated BEST (the engine's own choice or better) with 'allowing' something
- immortal ply 21 Rg1: "White allowed a hanging piece on g4." - charges a move rated BEST (the engine's own choice or better) with 'allowing' something
- immortal ply 22 cxb5: "This drops the pawn on g4." - the pawn belongs to the OPPONENT: the mover does not drop it
- immortal ply 22 cxb5: "Better was h5, keeping material level." - 'keeping material level' is not provable (nothing says the move holds material)
- immortal ply 23 h4: "This wins the queen on g5." - the move does not capture it and the engine's line does not take it: it at most attacks it
- immortal ply 23 h4: "White allowed a relative pin on g4." - charges a move rated GREAT (the engine's own choice or better) with 'allowing' something
- immortal ply 24 Qg6: "Black allowed a deflection on h5." - charges a move rated GREAT (the engine's own choice or better) with 'allowing' something
- immortal ply 25 h5: "This sets up a deflection on h5." - the 'deflected' piece lands on the very square it was supposed to be deflected from (an exchange, not a deflection)
- immortal ply 25 h5: "White allowed a clearance on c6." - charges a move rated GREAT (the engine's own choice or better) with 'allowing' something
- immortal ply 27 Qf3: "White allowed a relative pin on e4." - charges a move rated BEST (the engine's own choice or better) with 'allowing' something
- immortal ply 28 Ng8: "This sets up a deflection on f4." - the motif belongs to a different move (the better move or the opponent's reply)
- immortal ply 29 Bxf4: "This wins the queen on g5." - the move does not capture it and the engine's line does not take it: it at most attacks it
- immortal ply 30 Qf6: "Black allowed a hanging piece on b5." - charges a move rated BEST (the engine's own choice or better) with 'allowing' something
- immortal ply 31 Nc3: "This wins the pawn on b5." - the move does not capture it and the engine's line does not take it: it at most attacks it
- immortal ply 31 Nc3: "White allowed an x-ray on c2." - charges a move rated GREAT (the engine's own choice or better) with 'allowing' something
- immortal ply 32 Bc5: "This drops the bishop on c5." - not actually droppable
- immortal ply 32 Bc5: "Better was Qc6, keeping material level." - 'keeping material level' is not provable (nothing says the move holds material)
- immortal ply 33 Nd5: "This drops the rook on a1." - not actually droppable
- immortal ply 33 Nd5: "Better was d4, keeping material level." - 'keeping material level' is not provable (nothing says the move holds material)
- immortal ply 34 Qxb2: "This wins the rook on a1." - the move does not capture it and the engine's line does not take it: it at most attacks it
- immortal ply 34 Qxb2: "Black allowed an x-ray on e8." - charges a move rated BEST (the engine's own choice or better) with 'allowing' something
- immortal ply 35 Bd6: "This sets up a clearance on b2." - the motif belongs to a different move (the better move or the opponent's reply)
- immortal ply 36 Bxg1: "This traps the piece on g1." - the trapped piece is the MOVER's own: it is the opponent's threat, not the mover's trap
- immortal ply 37 e5: "This wins the pawn on g7." - the move does not capture it and the engine's line does not take it: it at most attacks it
- immortal ply 39 Ke2: "White allowed an x-ray on d3." - charges a move rated GREAT (the engine's own choice or better) with 'allowing' something
- immortal ply 40 Na6: "This forces mate." - the engine's mate is the OPPONENT's, or there is none
- immortal ply 41 Nxg7+: "White allowed a deflection on f6." - charges a move rated GREAT (the engine's own choice or better) with 'allowing' something
- immortal ply 43 Qf6+: "White allowed a hanging piece on d5." - charges a move rated BEST (the engine's own choice or better) with 'allowing' something
- immortal ply 44 Nxf6: "This wins the knight on d5." - the move does not capture it and the engine's line does not take it: it at most attacks it
- immortal ply 44 Nxf6: "Black allowed a mating net on d8." - charges a move rated BEST (the engine's own choice or better) with 'allowing' something
- chesscom ply 8 Bxf3: "This sets up a deflection on d6." - the motif belongs to a different move (the better move or the opponent's reply)
- chesscom ply 10 dxe5: "Black allowed a relative pin on b7." - charges a move rated BEST (the engine's own choice or better) with 'allowing' something
- chesscom ply 11 Bc4: "This pins the piece on b7." - the played move does not pin that piece
- chesscom ply 12 Nf6: "This pins the piece on b7." - the played move does not pin that piece
- chesscom ply 13 Qb3: "White allowed a deflection on b4." - charges a move rated GREAT (the engine's own choice or better) with 'allowing' something
- chesscom ply 14 Qe7: "Black allowed a deflection on b4." - charges a move rated BEST (the engine's own choice or better) with 'allowing' something
- chesscom ply 16 c6: "Black allowed a clearance on c1." - charges a move rated BEST (the engine's own choice or better) with 'allowing' something
- chesscom ply 18 b5: "This sets up a deflection on b4." - the motif belongs to a different move (the better move or the opponent's reply)
- chesscom ply 19 Nxb5: "This sets up a deflection on b4." - the 'deflected' piece lands on the very square it was supposed to be deflected from (an exchange, not a deflection)
- chesscom ply 19 Nxb5: "White allowed a deflection on b4." - charges a move rated BRILLIANT (the engine's own choice or better) with 'allowing' something
- chesscom ply 20 cxb5: "This sets up a deflection on b4." - the motif belongs to a different move (the better move or the opponent's reply)
- chesscom ply 23 O-O-O: "White allowed a deflection on d7." - charges a move rated BEST (the engine's own choice or better) with 'allowing' something
- chesscom ply 24 Rd8: "This sets up a deflection on d7." - the 'deflected' piece lands on the very square it was supposed to be deflected from (an exchange, not a deflection)
- chesscom ply 25 Rxd7: "This sets up a deflection on e7." - the 'deflected' piece lands on the very square it was supposed to be deflected from (an exchange, not a deflection)
- chesscom ply 25 Rxd7: "White allowed a removing the defender on e7." - charges a move rated BEST (the engine's own choice or better) with 'allowing' something
- chesscom ply 26 Rxd7: "Black allowed a deflection on f6." - charges a move rated EXCELLENT with 'allowing' a motif that wins nothing
- chesscom ply 27 Rd1: "Rd1 is a stunning sacrifice." - no sacrifice: the best legal capture of a piece nets None
- chesscom ply 27 Rd1: "White allowed a clearance on c5." - charges a move rated BRILLIANT (the engine's own choice or better) with 'allowing' something
- chesscom ply 28 Qe6: "Black allowed a clearance on b8." - charges a move rated EXCELLENT with 'allowing' a motif that wins nothing
- chesscom ply 29 Bxd7+: "White allowed a desperado on d7." - charges a move rated BEST (the engine's own choice or better) with 'allowing' something
- chesscom ply 30 Nxd7: "This sets up a deflection on b8." - credited to a move after which the OPPONENT has a forced mate: the line is the opponent's combination
- immortal ply 3 f4: "[intro] The pawn on e5 is left hanging and can be taken for nothing." - claims the piece is lost; it is only attacked and the opponent moves next
- immortal ply 3 f4: "[step 1] Nf3: the knight lands on f3, collecting the pawn on e5." - 'collecting' claims a win the move does not make
- immortal ply 3 f4: "[step 8] b5: the pawn lands on b5, attacking the bishop on a4. White gains a decisive advantage." - unprovable payoff sentence on the last step
- immortal ply 3 f4: "[payoff] gains a decisive advantage" - a payoff the data cannot back
- immortal ply 14 Nh5: "[intro] The bishop on b5 is left hanging and can be taken for nothing." - claims the piece is lost; it is only attacked and the opponent moves next
- immortal ply 14 Nh5: "[step 1] c6: the pawn lands on c6, collecting the bishop on b5." - 'collecting' claims a win the move does not make
- immortal ply 14 Nh5: "[step 8] Bxe3: the bishop captures the bishop on e3, an even trade. Black has invested material in the attack." - unprovable payoff sentence on the last step
- immortal ply 14 Nh5: "[payoff] has invested material in the attack" - a payoff the data cannot back
- immortal ply 15 Nh4: "[step 6] Bxg1: the bishop captures the rook on g1, winning a pawn." - claims a pawn but the capture nets 170
- immortal ply 15 Nh4: "[step 7] Kxg1: the king captures the bishop on g1, winning a piece." - claims a piece but the pair nets -170
- immortal ply 15 Nh4: "[step 8] Qg6: the queen lands on g6. White has invested material in the attack." - unprovable payoff sentence on the last step
- immortal ply 15 Nh4: "[payoff] has invested material in the attack" - a payoff the data cannot back
- immortal ply 18 c6: "[intro] The knight on f5 cannot be held - taking it wins material." - claims the piece is lost; it is only attacked and the opponent moves next
- immortal ply 18 c6: "[step 1] g6: the pawn lands on g6, collecting the knight on f5." - 'collecting' claims a win the move does not make
- immortal ply 18 c6: "[step 8] exf5: the pawn captures the queen on f5, winning a rook, attacking the pawn on g6. Black has invested material in the attack." - unprovable payoff sentence on the last step; claims a rook but the pair nets 580
- immortal ply 18 c6: "[payoff] has invested material in the attack" - a payoff the data cannot back
- immortal ply 19 g4: "[step 8] Nxa4: the knight captures the bishop on a4, an even trade, attacking the knight on c3. White has invested material in the attack." - unprovable payoff sentence on the last step
- immortal ply 19 g4: "[payoff] has invested material in the attack" - a payoff the data cannot back
- immortal ply 22 cxb5: "[intro] The pawn on g4 cannot be held - taking it wins material." - claims the piece is lost; it is only attacked and the opponent moves next
- immortal ply 22 cxb5: "[step 1] h5: the pawn lands on h5, collecting the pawn on g4." - 'collecting' claims a win the move does not make
- immortal ply 22 cxb5: "[step 8] Qe1: the queen lands on e1. Black gains a decisive advantage." - unprovable payoff sentence on the last step
- immortal ply 22 cxb5: "[payoff] gains a decisive advantage" - a payoff the data cannot back
- immortal ply 28 Ng8: "[step 7] Nxf3: the knight captures the queen on f3, winning a rook, attacking the rook on g1 and the bishop on g5." - claims a rook but the capture nets 580
- immortal ply 28 Ng8: "[step 8] Kxf3: the king captures the knight on f3, winning a piece. Black has invested material in the attack." - unprovable payoff sentence on the last step; claims a piece but the pair nets -580
- immortal ply 28 Ng8: "[payoff] has invested material in the attack" - a payoff the data cannot back
- immortal ply 32 Bc5: "[step 4] Rxa4: the rook captures the pawn on a4, winning a pawn." - claims a pawn but the pair nets 0
- immortal ply 32 Bc5: "[step 8] Bd6: the bishop lands on d6, attacking the bishop on f8. Black gains a decisive advantage." - unprovable payoff sentence on the last step
- immortal ply 32 Bc5: "[payoff] gains a decisive advantage" - a payoff the data cannot back
- immortal ply 33 Nd5: "[intro] The bishop on c5 is left hanging and can be taken for nothing." - claims the piece is lost; it is only attacked and the opponent moves next
- immortal ply 33 Nd5: "[step 1] d4: the pawn lands on d4, collecting the bishop on c5." - 'collecting' claims a win the move does not make
- immortal ply 33 Nd5: "[step 8] Kd8: the king lands on d8. White gains a decisive advantage." - unprovable payoff sentence on the last step
- immortal ply 33 Nd5: "[payoff] gains a decisive advantage" - a payoff the data cannot back
- immortal ply 35 Bd6: "[step 8] Nxf5: the knight captures the knight on f5, an even trade, attacking the queen on g3. White has invested material in the attack." - unprovable payoff sentence on the last step
- immortal ply 35 Bd6: "[payoff] has invested material in the attack" - a payoff the data cannot back
- immortal ply 40 Na6: "[step 8] Nxc7: the knight captures the queen on c7, winning a rook, attacking the rook on a8. Black has invested material in the attack." - unprovable payoff sentence on the last step; claims a rook but the pair nets 570
- immortal ply 40 Na6: "[payoff] has invested material in the attack" - a payoff the data cannot back
- chesscom ply 8 Bxf3: "[intro] Nc6 deflects the pawn on e5 away from guarding d6." - repeats the first move
- chesscom ply 8 Bxf3: "[step 3] Qxd6: the queen captures the pawn on d6, winning a pawn, attacking the queen on d1." - claims a pawn but the pair nets 0
- chesscom ply 8 Bxf3: "[step 5] Bxd6: the bishop captures the queen on d6, winning a queen." - claims a queen but the pair nets 0
- chesscom ply 8 Bxf3: "[step 8] Nc3: the knight lands on c3. Black gains a decisive advantage." - unprovable payoff sentence on the last step
- chesscom ply 8 Bxf3: "[payoff] gains a decisive advantage" - a payoff the data cannot back
- chesscom ply 11 Bc4: "[step 6] Nxc5: the knight captures the bishop on c5, winning a piece, attacking the queen on b3." - claims a piece but the pair nets 0
- chesscom ply 11 Bc4: "[step 8] Nd7: the knight lands on d7. White gains a decisive advantage." - unprovable payoff sentence on the last step
- chesscom ply 11 Bc4: "[payoff] gains a decisive advantage" - a payoff the data cannot back
- chesscom ply 12 Nf6: "[step 8] Bxa6: the bishop captures the queen on a6, winning a queen. Black has invested material in the attack." - unprovable payoff sentence on the last step; claims a queen but the pair nets 0
- chesscom ply 12 Nf6: "[payoff] has invested material in the attack" - a payoff the data cannot back
- chesscom ply 20 cxb5: "[step 3] Bxb4+: the bishop captures the queen on b4, winning a queen, attacking the king on e1, with check." - claims a queen but the pair nets 0
- chesscom ply 20 cxb5: "[step 5] cxb5: the pawn captures the knight on b5, winning a pawn, attacking the bishop on c4." - claims a pawn but the capture nets 220
- chesscom ply 20 cxb5: "[step 6] Bxb5+: the bishop captures the pawn on b5, winning a pawn, attacking the king on e8, with check." - claims a pawn but the pair nets -220
- chesscom ply 20 cxb5: "[step 8] cxb4: the pawn captures the bishop on b4, winning a piece. Black has invested material in the attack." - unprovable payoff sentence on the last step
- chesscom ply 20 cxb5: "[payoff] has invested material in the attack" - a payoff the data cannot back
