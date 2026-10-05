# Practise your own mistakes: design (Round 13, task 67)

Produced by a Fable 5.1 / high design agent (read-only), 2026-10-03, and saved by the orchestrator.
Evidence tags (`file:line`) are as the agent reported them. Paths are relative to the repo root.
The owner's two open questions were answered with the recommended defaults (§10).

**The feature in one sentence:** "Here's a position from your game where you went wrong. Find the better move."
One screen, one board, one card, two buttons. It is entirely local, using only the data the app already has.

## 0. What the tree already gives us

| Fact | Evidence | Consequence |
|---|---|---|
| `ChessBoard` **already takes tap input**: `onSquareClick`, `selectedSquare`, `legalMoveTargets`, orientation-aware `displayCellToSquare` | `ui/board/ChessBoard.kt:75-79, 93-103, 121-123` | Tap-to-move needs **no board change** |
| **Nobody uses it today** (only `ChessBoard.kt` mentions `onSquareClick`) | grep | The tap path has **never been exercised on a device**. The emulator check is its first real use |
| Board pinned LTR. UI squares number a8 = 0 and core squares a1 = 0, and `BoardMapper` converts | `ChessBoard.kt:86`; `data/mapper/BoardMapper.kt:30-37, 72-77` | Judge attempts in core squares |
| Each ply carries `fenBefore`, `uci`, `bestMoveUci`, `bestMoveSan`, `bestLineSan`, `loss`, `winPercentBefore/After`, `mateInBefore/After`, `evalSecondBestCp`, `tacticsMissed`, `simulation` | `core/.../analysis/Contract.kt:160-199` | Selection runs on `GameReport` alone |
| **Only line 2's score survives** (`evalSecondBestCp`); full MultiPV lines are not retained after analysis | `GameAnalyzer.kt:69-72`, `AnalysisService.kt:70-75`, `AnalysisViewModel.kt:258-265` | Judging "equally good" needs the top-k lines: one small addition to `MoveAnnotation` (§7.1) |
| Engine is one in-process instance. `setPosition` and `analyze` are two separate mutex acquisitions | `StockfishEngine.kt:167-174, 204`; `CLAUDE.md` | A concurrent judge search could corrupt the analysis loop, so v1 never calls the engine |
| The spec forbids comparing evals from different depths | `docs/ANALYSIS_SPEC.md:52-54` | A quick `movetime 500` judge would not be comparable with the cached depth-14 scores |
| Narration already has puzzle vocabulary: `PuzzlePrize`, `puzzlePrompt`, `PUZZLE_MIN_SWING_CP = 150`, `MATING_MOTIFS` | `NarrationStrings.kt:163`, `VideoScriptGenerator.kt:1336-1342, 552-553, 1833, 1843-1848` | Reuse the *decision* (same thresholds), not the spoken sentences |
| `userColor` comes from the username vs the PGN tags and is stored on the UI report and in `StoredGame.userColorName`. The U5 side chooser will set it | `AnalysisService.kt:237-246`, `GameModels.kt:221`, `GameRepository.kt:41` | With no side, there are no puzzles |
| `TacticSimulationScreen` already has the right shape (board + card + back arrow, `replayPositions`) | `ui/screens/TacticSimulationScreen.kt:97-111, 207-225` | The Practise screen copies its skeleton |

## 1. Which positions become puzzles (all in `:core`, constants in a new `ANALYSIS_SPEC.md §11`)

Input: the **unpruned** `GameReport.annotations` plus the user's colour. Puzzles are about mistakes, which are classification-based, so the §9.6 tactic gate does not apply.

A ply is a **candidate** when ALL hold:
1. `color == userColor`.
2. `classification ∈ {MISTAKE, MISS, BLUNDER}`. INACCURACY is excluded on purpose.
3. `loss >= 10.0` (the MISTAKE floor, `MoveClassifier.kt:81`) **or** the class is MISS. MISS is exempt because a thrown-away forced mate can have a tiny win-% loss.
4. `winPercentBefore >= 25.0` (`MIN_WIN_BEFORE`): not hopeless.
5. There is an answer: `bestMoveUci != null`, `!= uci`, legal in `fenBefore`. **Skip puzzles whose best move is a non-queen promotion** (auto-queen is v1).
6. **Judgeable from the cache.** With `k = candidateLines.size` and `band = winPercent(best) − 2.0`, either:
   - `k >= 2` and (`candidateLines.last().winPercent < band` or `legalMoves(fenBefore).size <= k`), **or**
   - the position is a mate in 1 for the mover (core verifies by rules).

   So either every uncached move is provably worse, or all legal moves are cached. "Not in the cached lines" is never a guess. A position with several near-equal moves and more legal moves than cached lines is skipped as a poor puzzle.

**Dedupe:** same `bestMoveUci` within 4 plies keeps the one with the larger loss.
**Cap and order:** rank by `rankLoss = if (MISS) max(loss, 20.0) else loss`, take `MAX_PUZZLES = 5`, present in **ply order**.

**Goal label** (`PuzzleGoal`), reusing narration thresholds:
- `MATE_IN(n)` when `mateInBefore` favours the mover.
- else `WIN_ROOK_OR_BETTER` / `WIN_PIECE` / `WIN_MATERIAL` from the best missed tactic's `materialSwing` (≥ 500 / ≥ 320 / ≥ 150).
- else `BETTER_MOVE`.

**Edge cases:**
- No side chosen: `PracticeSet.NoSide`.
- "Not me": section hidden.
- No mistakes: `PracticeSet.Empty`, shown as "Nothing to fix in this game — nice."
- Equally good moves: accepted via the 2.0 win-% band.
- A mate puzzle: a move that merely wins the queen is not accepted. `winPercent(+1000cp) = 97.5`, so loss 2.5 > 2.0. **Pin this with a test.**

## 2. The interaction (tap-to-move only)

| State | Board | Card | Controls |
|---|---|---|---|
| QUESTION | position before the mistake, user's colour at the bottom, no arrows | "White to play" + goal + "From move 15 of your game" | `Hint`, `Show answer` |
| PIECE SELECTED | tapped own piece tinted, legal targets dotted | unchanged | tapping another own piece re-selects; same piece or an empty non-target square clears |
| WRONG | selection cleared | "Not quite. Try again." Or, if the attempt was the played move: "That's the move you played in the game. It cost about 2 pawns." | `Hint`, `Show answer` |
| HINT | the best move's **from-square is pre-selected** (legal targets dotted); no new drawing | "Look at the knight on f3." + if a missed motif exists, "Look for a fork." One level only | `Show answer` |
| SOLVED | correct move played, green arrow | "Correct." + the first `perPlyExplanation` of the simulation if any, else "Nf6 was the best move." | `Next` (or `Done` on the last); `Show me what I missed` only when a simulation exists |
| REVEALED | best move played, green arrow | "The best move was Nf6." + "Then: Qd2 Nxd5 …" | same as SOLVED |
| END | none | "That's all the positions from this game." + "Solved 2 of 3." | `Done` |

Rules:
- An illegal destination is ignored with no feedback, because the dots already show where the piece can go.
- Promotion auto-queens.
- `Show answer` is always available.
- `Hint` disappears once used.
- State is `rememberSaveable`, so it survives rotation and a trip into the walkthrough.

## 3. Judging an attempt

| Option | Cost | Accuracy | Verdict |
|---|---|---|---|
| **A. Cached MultiPV lines only** | zero, pure core | Exact, given the §1 rule 6 selection | **Recommended for v1** |
| B. Engine check at the report's depth | one full search per wrong tap, plus serialisation with analysis | comparable | Not needed |
| C. Quick `movetime 500` check | 0.5 s | **Not comparable**, which the spec forbids | Rejected |

`PracticeJudge.judge(puzzle, attemptUci): Verdict`:
1. `attempt ∈ accepted`, where `accepted = {line : winPercent(best) − winPercent(line) <= ACCEPT_LOSS = 2.0}`. This is the spec's own best-or-near-best bound, reused. → `Correct`.
2. A mate-in-1 puzzle and the attempt checkmates by rules → `Correct`.
3. `attempt == a.uci` → `PlayedInGame(loss, evalSwingCp)`.
4. Otherwise → `Wrong`.

If engine judging is ever wanted: add an outer engine mutex held by the analysis loop per ply, and make the judge take it too and refuse while analysis is active. Not in v1.

## 4. Progress and persistence

**v1 is in-memory only.** `AnalysisViewModel.solvedPuzzlePlies: MutableMap<gameId, Set<ply>>` serves two purposes: resuming at the first unsolved puzzle, and the Summary card text "N positions · 1 solved". There are no streaks, scores, badges or timers.
**Deferred (P5):** persist `solvedPuzzlePlies` as a JSON array on `StoredGame`, with `optJSONArray` defaulting to empty. About 20 lines and no migration.

## 5. Where it lives

Practise is a **leaf of the Summary**, not a top-level screen. It may link sideways only to the existing Walkthrough.
- **Route:** `Destination.Practice : Destination("practice/{gameId}")`, with no index argument.
- **Entry:** a new section on the Summary, between "Your key moments" and the two bottom buttons, as a tappable Card row. It is not a button, so the Summary keeps its single primary button.
  - Side unknown: "Choose which side you were to practise your mistakes." (not tappable)
  - "Not me": the section is hidden.
  - No puzzles: "Nothing to fix in this game — nice."
  - Otherwise: "N positions · M solved ›".
- **Screen** (`ui/screens/PracticeScreen.kt`, skeleton from `TacticSimulationScreen`):
  - Top bar: back arrow, "Practise", counter "2 / 3" (LRM-prefixed).
  - Board: `ChessBoard`, full width, user's colour down, no flip icon.
  - Card: scrolling, `weight(1f)`, with Hint and Show answer.
  - Bottom: a full-width `Next` button in SOLVED and REVEALED only.
  - Do **not** show `ClassificationBadge` ("?? Blunder") before the user has tried.
- **Navigation:** `Show me what I missed` goes to `Simulation.createRoute(gameId, ply)` and returns to the same puzzle.
- **RTL:** the board and SAN stay LTR, and the card and buttons mirror.
- **Large font:** the card scrolls with no fixed heights, and buttons wrap in a `FlowRow`.
- **360×800:** verified by arithmetic only, so confirm it on the emulator.

## 6. Copy (all in `strings.xml`, no literals in Kotlin)

`practice_title` Practise · `practice_counter` %1$d / %2$d · `practice_section_title` Practise these positions · `practice_entry_count` (plural) %1$d position(s) · `practice_entry_solved` %1$s · %2$d solved · `practice_entry_no_side` Choose which side you were to practise your mistakes. · `practice_entry_empty` Nothing to fix in this game — nice. · `practice_side_to_play_white`/`_black` White/Black to play · `practice_goal_mate` (plural) Find the checkmate in %1$d / Find the mate in %1$d · `practice_goal_rook_or_better` Find the move that wins a rook or more. · `practice_goal_piece` Find the move that wins a piece. · `practice_goal_material` Find the move that wins material. · `practice_goal_better_move` Find the better move. · `practice_from_move` From move %1$d of your game · `practice_hint` Hint · `practice_show_answer` Show answer · `practice_wrong` Not quite. Try again. · `practice_played_in_game` That's the move you played in the game. · `practice_hint_piece` Look at the %1$s on %2$s. · `practice_hint_idea` Look for a %1$s. · `practice_correct` Correct. · `practice_correct_best` %1$s was the best move. · `practice_revealed` The best move was %1$s. · `practice_revealed_line` Then: %1$s · `practice_next` Next · `practice_done_all` That's all the positions from this game. · `practice_done_solved` Solved %1$d of %2$d. · `piece_*` (6), only if absent.
- The cost line reuses `review_cost_pawns`, `review_cost_half_pawn` and `review_cost_pawns_decimal`, and is omitted across a mate boundary.
- Reuse `common_done`, `common_back`, `review_show_me_missed` and `side_white`/`side_black`.

## 7. Data model and code plan

### 7.1 `:core`
- **`Contract.kt`:** add `CandidateLine(multiPv, uci, san?, scoreCp?, mateIn?)`, mover-relative like `EngineLineInput`, and `MoveAnnotation.candidateLines: List<CandidateLine> = emptyList()`. It is a defaulted trailing field, so existing callers keep compiling, and the desktop's field-by-field copy in `ReportFactory.kt:111` must be checked. Precedent: `evalSecondBestCp`.
- **`GameAnalyzer.kt`:** fill `candidateLines` next to `secondBestCp` (around lines 69-75) from `evalBefore.lines.sortedBy { multiPv }`.
- **New `core/.../analysis/Practice.kt`:**
  - `PracticePuzzle`, with everything the screen needs precomputed (ply, moveNumber, sideToMove, fenBefore, played/best uci and san, bestLineSan, goal, loss, evalSwingCp, hintPiece, hintSquare, hintMotif, acceptedUci, isMateInOne, hasSimulation).
  - `PuzzleGoal` and `PracticeSet {NoSide, Empty, Puzzles}`.
  - `PracticeSelector.select(report, userColor)`, with constants `MIN_LOSS = 10.0`, `MIN_WIN_BEFORE = 25.0`, `ACCEPT_LOSS = 2.0`, `MAX_PUZZLES = 5`, `DEDUPE_WINDOW_PLIES = 4`.
  - `Verdict {Correct, PlayedInGame, Wrong}` and `PracticeJudge`.
- **Spec:** add `ANALYSIS_SPEC.md §11 "Practice puzzles"` with every constant and the judgeability rule.
- **Tests:**
  - **Selection:**
    - own side only
    - the three classes in, INACCURACY and GOOD out
    - loss boundary 9.9 vs 10.0
    - MISS with loss 3 kept
    - winPercentBefore 24.9 vs 25.0
    - best equals played skipped
    - illegal best skipped
    - non-queen promotion skipped
    - judgeability: k = 1 skipped; three near-equal lines with more than 3 legal moves skipped, kept with exactly 3; mate-in-1 kept
    - dedupe at plies 11 and 13 vs 11 and 17
    - cap of 5 with MISS ranked at least 20
    - output in ply order
    - null side gives NoSide; a clean game gives Empty
    - goal derivation for mate, 500, 320, 150 and none
  - **Judging:**
    - the best move accepted
    - second line at loss 1.9 accepted and at 2.1 rejected
    - the played move gives PlayedInGame
    - an unknown legal move is Wrong
    - in a mate puzzle a +1000cp line is Wrong, and a slower mate is Correct
    - a mate-in-1 with an uncached second mate is Correct via rules
  - **Analyzer:** `GameAnalyzerTest` checks `candidateLines` has one entry per MultiPV line, best first, with SAN resolved.

### 7.2 `:app`
- `AnalysisViewModel`: `practiceSetFor(gameId)`, reading the **UI report's** `userColor` so it follows the U5 chooser, and `solvedPuzzlePlies` with `markSolved`.
- `Destinations.kt`: the `Practice` route.
- `ChessAnalyzerNavHost.kt`: the composable, with `onShowMissed` and `onDone`. A null set pops back, like the other routes.
- New `PracticeScreen.kt`:
  - A `PuzzleUiState` machine in `rememberSaveable`.
  - Tap → `toCoreSquare()`.
  - The first tap must hit an own piece.
  - The second tap resolves `Position.legalMoves().firstOrNull { from, to }`, auto-queening.
  - Then `PracticeJudge.judge`.
- `GameReportScreen.kt`: the entry section, fed by `PracticeEntryState {NoSide, Hidden, Empty, Count(n, solved)}`.
- `GameModels.kt`: `PracticeEntryState` only.
- `strings.xml`: the §6 keys.
- `ChessBoard.kt`: **no change**.
- New host test `app/src/test/.../ui/PracticeAttemptTest.kt`: own-piece gate, auto-queen, castling by tapping the king's destination.

**Emulator checks:**
1. Analyse `fixtures/chesscom_style_game.pgn` with the losing side chosen.
2. The Summary shows "N positions".
3. Open Practise: the user's colour is at the bottom.
4. Tapping an opponent piece does nothing, and tapping an own piece shows dots.
5. A wrong target gives "Not quite", and the played move gives "That's the move you played…" with the cost line.
6. Hint pre-selects the right piece.
7. The correct move gives "Correct." plus an arrow.
8. Next through all, then Done: the Summary shows "N solved".
9. Re-entering resumes at the first unsolved puzzle.
10. `Show me what I missed` → walkthrough → back returns to the same puzzle.
11. Rotation keeps the state.
12. RTL: board and SAN stay LTR.
13. Font scale 1.5: the card scrolls.
14. `wm size 360x800`: everything fits.
15. `fixtures/immortal.pgn` as White → "Nothing to fix" (to be verified).
16. With no side chosen → the "Choose which side…" row.

Screenshots go to `docs/screenshots/r13_practice_*.png`, and device flags are restored afterwards.

## 8. Phased plan (one Gradle build at a time)

| # | Step | Files | Done-condition | Risk |
|---|---|---|---|---|
| P1 | Contract plus analyzer: `CandidateLine`, `candidateLines`, fill it, spec §11 text | `Contract.kt`, `GameAnalyzer.kt`, `ANALYSIS_SPEC.md`, `GameAnalyzerTest.kt` | `:core:test` green, 0 skipped; `:desktop:compileKotlin` still compiles | Positional `MoveAnnotation(...)` calls would break (named args found so far) |
| P2 | Selector plus judge | `Practice.kt`, `PracticeSelectorTest.kt`, `PracticeJudgeTest.kt` | `:core:test` green, boundary cases listed by name | None outside core |
| P3 | Screen, route, ViewModel, temporary Summary `TextButton` | `strings.xml`, `Destinations.kt`, `ChessAnalyzerNavHost.kt`, `PracticeScreen.kt`, `AnalysisViewModel.kt`, `GameModels.kt` | assembleDebug green; the §7.2 script except the Summary-card items | Touches files U5/U6 also edit, so **run after U5 and U6** |
| P4 | Summary entry card, RTL/font-scale pass, screenshots | `GameReportScreen.kt`, `ChessAnalyzerNavHost.kt`, `strings.xml` | full §7.2 script; instrumented suite re-run with skipped=0 | Depends on U5's restructured Summary |
| P5 (optional) | Persist solved plies | `GameRepository.kt`, `AnalysisViewModel.kt` | "1 of 3 solved" survives a restart | `parseStoredGame` must default the field |

P1 and P2 share no files with the UX steps and can run before them.

## 9. Left out of v1

Engine-backed judging and graded wrong answers; drag-to-move; promotion choice; multi-level hints; spaced repetition; cross-game puzzle sets; streaks, scores and badges; the opponent's mistakes as puzzles; "both sides" when no side is chosen; persistence (P5); stepping through the whole best line on the puzzle board; any voice or video integration.

## 10. Owner questions, answered with the recommended defaults

1. **"Not me" on the Summary:** hide Practise (the feature's promise is "your own mistakes").
2. **Should INACCURACY ever become a puzzle?** No. A game with only inaccuracies shows "Nothing to fix — nice."
