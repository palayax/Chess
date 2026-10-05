# Chess.com reference alignment: Palaya Chess UI/UX audit

Produced 2026-10-04 by a Fable 5.1 / high design agent (read-only), saved by the orchestrator in condensed form.
**Rule:** chess.com is the UI/UX reference for every decision not derived from the owner's goals. Owner goals win. Reference only: patterns, never assets, icons, logos, exact wording, screenshots or branding (`CLAUDE.md`, "Design reference").
chess.com statements below are paraphrases from the sources in §0. **[memory]** = not read on a page this session. **[community]** = a user post, not documentation.

## 0. Sources read
- S1 support.chess.com/en/articles/8584089 (how Game Review works)
- S2 support.chess.com/en/articles/8572705 (move classification, updated Feb 2026)
- S3 support.chess.com/en/articles/10328363 (Game Review on the app)
- S4 .../8608686 (puzzles)
- S5 .../8708990 (daily puzzle, search snippet only)
- S6 chess.com/news/view/chesscom-launches-game-review-v2
- S7 chess.com/terms/game-review
- S8 .../10773754 (Game Rating)
- S9 chess.com/blog/FunPooler/every-chess-move-icon-explained **[community]**
- S10 App Store listing (Play listing truncated, screenshots not viewed)
- S11 .../10812255 (the coach)
- S12 .../8648715 (move highlighting setting)
- S13 .../8705896 (puzzle settings)
- S14 forum thread on Key Moments
- S15 .../8615318 (welcome)
- S16 .../8583757 (game analysis)
- S17 forum threads on PGN import (old)

Not possible without logging in: the live review screen, the mobile summary's exact element order below the graph, and store screenshots. Those statements are **[memory]**.

## 1. Reference map

| Area | chess.com pattern | Ours | Verdict |
|---|---|---|---|
| Home / import | Web analysis board has a paste field; the app has no real import path. The most-used action comes first (S15, S17) | One card, one green button, "Paste moves" sheet, recents | **ALIGNED** (ours is richer because import is our product) |
| Analysing | A progress state **[memory]** | Ring, "Move N of M", bar, Cancel | NO REFERENCE; fine |
| Summary header / accuracy | Graph first, then a one-line coach summary, then accuracy 0–100 per player, "Game Rating" beside the name (S1, S3, S8); v2 adds phase grades (S6) | Names, opening, moves, result, two accuracy bars, "Played like a ~1492" | **ADOPT** the one-sentence game summary. **DELIBERATE DIVERGENCE** (simple): graph and counts stay behind Details |
| Side chooser | The reviewer knows who you are, and a setting picks whose key moves are highlighted (S1) | "Which side were you?" | **DELIBERATE DIVERGENCE** (no account) |
| Key moments | Guided walk in game order; green "Next" jumps to the next key move; Retry on mistakes; Show plays the engine line (S1, S3, S7, S14) | List of cards with badge, move, sentence, "Show me what I missed" | **ALIGNED**, plus two ADOPTs: "Next key moment" on the Board, and a "Try it" per card |
| Details: graph, counts table | Graph first; counts table between the two players' columns **[memory]** | "Who was winning" graph plus a grouped "All moves" table, collapsed | **DELIBERATE DIVERGENCE** (simple) |
| Tactic buckets | None (themes chart and theme puzzles, S16) | Four you/opponent buckets | NO REFERENCE (owner feature); keep |
| Board and move chips | Eval bar; classification icon drawn **on the destination square**; best-move arrow; swipeable move strip; "All moves / Key moves" colouring (S1, S3, S12) | Eval bar, arrow, chips with SAN, badge and score, tinted only for highlight classes, 4-button transport | **ALIGNED**, one ADOPT: draw the badge on the board square |
| Comment card | Coach text is short, names the piece and threat, and **never repeats the icon's label** (S6, S1); a "Best" button reveals the best move (S3) | Badge "Blunder", then text starting "Blunder. This drops…", with "Better was h5" inside the prose | **ADOPT** |
| Transport | Arrows at the ends of the strip plus swipe; Next key move is a separate green button (S3) | First/prev/next/last row | **ALIGNED** (plus the Next-key-moment item) |
| Tactic walkthrough | "Show" autoplays with explanation (S3, S7) | Manual stepper | **DELIBERATE DIVERGENCE** (simple) |
| Video screen | No narrated-video product; closest is the coach's voice with a speaker icon to mute (S1, S11) | Player, chapters, speed chips, "Prepare narration", "Save video" | NO REFERENCE (owner feature). Take only the "one audio control" idea |
| Practise | Retry: play the move, instant right/wrong, optional hint, then Show. The hint is two-step: piece, then move (S3, S4, S5) | Tap-to-move, Hint (pre-selects the piece), Show answer | **ALIGNED**, two ADOPTs |
| Settings | About 9 toggles on web (S1); app cog: coach voice, avatar, best moves, eval-bar side, engine, strength (S3); puzzles: one difficulty choice (S13) | U9: Your name, Language, Advanced (4), About | **ALIGNED** after U9 (we end with fewer controls) |
| First run | Account, username, skill-level question, email (S17) | One "Setting up the engine (one time)…" phase | **DELIBERATE DIVERGENCE** (no account) |

## 2. Concrete changes

### (A) Steps not yet built
**U7 Video (`ui/screens/VideoScreen.kt`):**
1. Keep the U7 design (back arrow, "Video review", fullscreen icon only, bottom "Save video", speed cycle button). Remove "Prepare narration" from the bar and its dialog.
2. Add **one speaker icon** in the transport row that mutes narration while playback continues (S1). Content descriptions `video_mute` / `video_unmute`. Not persisted.
3. Rename the strings that still call the video a "game review" (`watch_game_review`, `video_export_notification_title`, `video_export_notification_done_text`, channel descriptions) to "video review". **"Game Review" is chess.com's product name.**

**U8 Walkthrough (`ui/screens/TacticSimulationScreen.kt`):**
1. Single exit (back arrow plus a "Next"/"Done" button). Remove "Back to the game".
2. Step caption: the first step reads "Before the mistake" (key `simulation_before`), later steps show the move played ("8. Bxa6") plus a "2 / 5" counter.
3. **Fix the intro defect** seen in `r13_u5_13`: "h5 starts a line that The pawn on g4 cannot be held - taking it wins material.." (a capital mid-sentence and a double full stop). Fix it **in core's sentence assembly**, with a test. A UI guard is only a stopgap.
4. On the last step, a "Try it yourself" `TextButton` that opens Practise at this ply, only when the ply is a practice puzzle.

**U9 Settings:** build exactly the four-row layout. Add nothing. No per-side "highlight key moves for" setting, because the Summary chooser covers it.

**P3/P4 Practise:**
1. **Two-step hint** (S5): the first tap pre-selects the piece (as designed); a second tap shows the target square as a dotted target without playing it; then the button disappears. New copy `practice_hint_second` "Show the square".
2. Add a green check or red cross badge beside the card title for SOLVED and WRONG, so colour is not the only signal. Reuse our `ClassificationBadge` shapes.
3. Route `practice/{gameId}?ply={ply}`, defaulting to the first unsolved puzzle. Each key-moment card whose ply is a puzzle shows an outlined **"Try it"** beside "Show me what I missed". The Summary still keeps one primary button.
4. **No** rating, points, streaks, hearts, daily cap or timer (S4).

**U10:** on-board badges carry a `contentDescription`; new numeric labels ("2 / 5", "1×") get an LRM prefix.

### (B) Rework for screens already built (ranked by user impact)

| # | Evidence | Pattern | Change | Size |
|---|---|---|---|---|
| B1 | Board `r13_u6_02` | The classification icon is drawn on the destination square (S1) | In `ChessBoard.kt`, draw the current ply's `ClassificationBadge` (our art) at the top-right of the destination square, about 28% of the square, for highlight-tier classes only. No setting | S–M |
| B2 | Summary and Board | A green "Next" jumps to the next key move (S3, S14) | In `CommentCard.kt`, when the Board was opened from a key moment and a later one exists, add a `TextButton` "Next key moment ›" (`review_next_key_moment`). Pass `keyMomentPlies` from the ViewModel into `ReviewScreen` | S |
| B3 | Comment card `r13_u6_02` and key-moment cards `r13_u5_01` | Coach text never restates the icon's label (S6) | Strip a leading "<ClassName>." from `annotation` when the badge is shown, in the cards too. Stop repeating "Better was X" when it is already rendered separately. **Preferably fix at the source in core's commentary text, not in the mapper** | S |
| B4 | Summary header `r13_u5_01` | The first line of the review is a one-sentence summary (S1, S3, S6) | One sentence under the accuracy row, derived from the report (result, biggest swing and whose), e.g. "Black was fine until move 11, then a blunder decided it." Needs a translatable `NarrationStrings` sentence in `:core` | M |
| B5 | Summary table `r13_u5_07` | Class names are single words (S2) | `classification_great` → "Great", `classification_best` → "Best". `PanelChipLabelTest` asserts via the resource, so check the fixture | S |

**Not proposed, deliberately:** moving the graph back to the top (it is the owner's "too complex" complaint), autoplay in the walkthrough, a coach avatar, and phase grades.

## 3. Terminology

| Ours | chess.com | Recommendation |
|---|---|---|
| "Accuracy" | "Accuracy" 0–100 | Keep |
| "Played like a ~1492" | "Game Rating" beside the name, with a caveat (S8) | Keep ours. Add a one-line tap help: "An estimate from this game's moves, not a rating." Delete the unused `report_est_rating` |
| "Key moments" | "Key Moments" | Keep |
| "Great move", "Best move" | "Great", "Best" | Change (B5) |
| Miss, Book, Forced, Inaccuracy, Mistake, Blunder, Brilliant, Excellent, Good | the same words | Keep |
| "Show me what I missed" | "Show" plays the line; "Best" reveals it | Keep ours |
| "Better was Nf6" | "Best" with a reason | Keep |
| "This cost about 2 pawns" | numeric delta **[memory]** | Keep |
| "Practise", "Hint", "Show answer", "Not quite. Try again." | "Retry", "Hint", "Solution"/"Show" | Keep "Practise"; "Try it" on the per-moment button; Hint aligned |
| "Game summary" | "Game Review" (product name) | Keep ours. **Never use "Game Review" as a screen or feature name** |
| "Board" | "Self Analysis" via a magnifier | Keep |
| "Video review" | no equivalent | Rename the four notification and channel strings |
| "Who was winning", "All moves", "Which side were you?", "Not me", "Setting up the engine (one time)…" | n/a | Keep |

## 4. Do-not-copy list

- **Assets and art:** piece sets (ours is Cburnett CC BY-SA, keep), board textures, the classification icon artwork, coach avatars and names, logos, the knight mark, sound effects, any screenshot.
- **Exact colours.** Our `GreenPrimary = 0xFF81B64C` (`Color.kt:17`) and board squares `0xFFEBECD0` / `0xFF739552` (`Color.kt:22-23`) are, to the agent's knowledge **[memory]**, within a hair of chess.com's brand green and default green board. The *convention* (green primary, green board, a yellow→orange→red ramp) is allowed; their stylesheet values are not. **Owner decision pending, before any public listing** (see RUN_PLAN).
- **Wording:** no marketing sentences. No "Game Review", "Life Review", "Skills", "Coach", "Puzzle Rush" or "Daily Puzzle" as feature names.
- **Patterns that conflict with owner goals:** accounts, sign-in, skill-level onboarding, premium gating, upsells, cloud analysis, puzzle rating, points, hearts, streaks, timers, celebrity coach voices, anything that phones home.
- **Affiliation:** keep the disclaimer strings. Never say "chess.com-style" in user-facing copy or the listing (internal docs may).

## 5. Where we are already ahead
Fully local (no account, network or card, while chess.com gates deeper review and more puzzles behind paid tiers); both sides' tactics with you/opponent framing; puzzles from your own game with no daily cap; textbook examples one tap from the mistake; a narrated exportable video; four visible Settings rows versus about nine; Hebrew/RTL groundwork; and a side chooser that works without a profile.

## 6. Uncertainty
1. The live mobile review layout was not seen; reconstructed from S1, S3, S6 and memory. **The owner should open one review in the chess.com app and confirm B4's placement.**
2. Classification colours and the Miss icon come from a community post (S9). Whether Miss is grey or salmon is unverified; ours is salmon.
3. Whether our green and board hexes match chess.com's exactly is from memory.
4. Whether the Android app supports PGN import (S17 is old). It does not change our design.
5. Whether the guided Key Moments flow is fully present on Android (S14).
6. The free-tier "one review a day" limit is from memory.
7. Store screenshots were not viewed.
8. The walkthrough copy defect is a core sentence-assembly issue.
