# Palaya Chess — Mobile UX simplification design (Round 13, subtask 63)

Status: design only, read-only pass over `C:\Claude\ChessAnalyzer` on 2026-10-03. Every claim about the current app cites a file:line or a screenshot in `docs/screenshots/`. Where the current UI could not be seen, it is said so. Assumptions taken from RUN_PLAN Round 13 (`RUN_PLAN.md:546-578`): Google Cloud voice is being removed (task 60), the Stockfish net and the voice model will be bundled (task 68), username/API import is cut, everything stays local with no external calls.

---

## 0. Executive summary

The app is not complex because it has many screens; it has eight, and the primary path is already share → analyse → review. It is complex because:

1. **The first screen the user lands on after analysis is the wrong one.** `ChessAnalyzerNavHost.kt:114` navigates to `Review` (board + 60-chip move list + eval bar), not to the report. The answer to "what went wrong" is one more tap away under a top-bar text link ("View game report", `ReviewScreen.kt:127-129`), next to a second text link ("Watch game review"). Three things are called review/report: the screen "Review", the "Game report", and the "game review" video (`strings.xml:33,64,223`).
2. **Settings exposes 26+ controls, most of them engine or TTS internals**: four sliders, a text field, 13 radio buttons, ~8 buttons, with labels like "Analysis lines (MultiPV)", "Search depth", "Time per move", "Only narrate moves worth ±0.5 pawns", "98.5 MB download · 150.6 MB on disk", "Auto-downloads on Wi-Fi only" (`SettingsScreen.kt:93-231`, `r10_settings_tier_picker.png`, `r9_settings_narration_voice.png`). Two of them do nothing (see §1.9).
3. **Every chip in the move list carries five data points** (move number, SAN, badge, score, swing) in six competing colours (`MoveList.kt:183-229`, `r6_review_move_list_sequence.png`), and the report shows 22 counts + 22 badges before any explanation (`GameReportScreen.kt:486-532`, `r5_05_game_report.png`).
4. **Jargon leaks through unfiltered**: PGN (five times on the home screen), plies, MultiPV, centipawns/pawns, neural network, "Est. performance rating", "Prepare narration", "Forced", "Book", 32 tactic names including "Zwischenzug"/"In-between move", "Desperado", "Windmill", "X-ray".

The redesign keeps all analysis capability and all the Round 6–11 fixes, and changes the *shape*: one home screen, one progress screen, **one results screen that answers "what went wrong and what do I do"**, with the board, the walkthroughs and the video reachable from it; Settings reduced to three visible rows plus an "Advanced" expander.

---

## 1. Complexity audit (evidence-based)

Counting convention: *actions* = things the user can tap; *labels* = static text pieces a first-time user must read; *jargon* = a term a club-level player who has never used an engine app would not know.

### 1.1 Import ("Analyze a game") — `ImportScreen.kt`, `r5_01_import.png`, `r5_09_recent_games.png`

| | Count | Evidence |
|---|---|---|
| Actions | 5 | settings gear (:116), "Choose PGN file" (:200), paste text field (:226), "Analyze pasted PGN" (:235), recent-game rows (:160) |
| Labels | 8 | title, headline, 31-word body, "Paste PGN", placeholder `[Event "Casual Game"]…`, "Recent games", empty text, row subtitle |
| Jargon | "PGN" ×5 on one screen (`strings.xml:16-20`), "plies" (`ImportScreen.kt:266`, hardcoded, screenshot shows "33 plies"), the placeholder shows raw PGN tag syntax |

Friction: the body copy explains three input methods in prose before any button; the paste card is permanently expanded with a 96dp empty field even though share-intent is the primary path (manifest filters `AndroidManifest.xml:54-119`); there is no visible "share from Chess.com" affordance, only text. The gamepad icon (`Icons.Filled.SportsEsports`, :180) signals "video game", not chess. The recent row shows a raw PGN date "2026.03.14".

### 1.2 Analysis progress — `AnalysisProgressScreen.kt`, `r5_03_analysis_progress.png`

| | Count |
|---|---|
| Actions | 1 (Cancel) |
| Labels | 2 ("Analyzing game", "Analyzing move 29 of 34") |
| Jargon | "Preparing engine…", "Downloading neural network (40 MB of 79 MB)" (`strings.xml:27-28`) — both phases disappear once the net is bundled |

This screen is fine. Two defects: errors are a modal `AlertDialog` whose only action pops back (`ChessAnalyzerNavHost.kt:128-139`) so the user loses the pasted text; and the dialog text can be raw internal English ("Nothing to analyze — the game text was lost. Please import it again." `AnalysisViewModel.kt:316`, "Could not parse this PGN: …" `AnalysisService.kt:76-80`), none of it in `strings.xml`.

### 1.3 Review (board) — `ReviewScreen.kt`, `MoveList.kt`, `CommentCard.kt`; `r5_04_review.png`, `r7_review_move_list_g6_mistake.png`, `r6_tactics_09_review_card_learn.png`, `r10_rtl_review.png`

| | Count |
|---|---|
| Actions | 6 transport icons (:231-253) + 2 top-bar text links (:121-130) + per-chip tap + "Show me" button + "Textbook: Skewer" link = **11+** |
| Data per chip | 5: move number, SAN, badge glyph, score (`+2.4`), swing (`+1.9`) (`MoveList.kt:197-228`) plus an optional sequence band with its own label |
| Colours on screen at once | up to 6 chip tints + red band + green arrow + yellow last-move + eval bar; `r6_review_move_list_sequence.png` shows green, orange, blue, dark-red, blue chips in one row under a red "?? Collapse" band |
| Jargon | "Best line: Nf6" (it is one move, not a line — `CommentCard.kt:86-99`), the swing numbers, "Textbook: Skewer", "+0.0" |
| Layout defect | The board `Row` is `weight(1f)` (:140-144) while the board is square, so on a 1080×2400 phone roughly a quarter of the screen between board and controls is empty (visible in all four review screenshots) and the eval bar stretches through it |
| Dead control | The autoplay toggle (:237-243) changes only its tint; nothing advances `currentPly` (no `LaunchedEffect(autoplay)` anywhere in the file) |
| No back affordance | The top bar has no `navigationIcon`; only About has one (`AboutScreen.kt:96-100`) |

RTL (`r10_rtl_review.png`): the two text links push the title to the far right and read "View game report  Watch game review  Review" — the mirrored chrome is correct per Round 10, but it shows how much the links crowd the bar.

### 1.4 Game report — `GameReportScreen.kt`; `r5_05_game_report.png`, `r6_eval_graph.png`, `r6_tactics_03_report_tactics_a.png`, `r6_tactics_14_report_minor_collapsed.png`

| | Count |
|---|---|
| Sections | 8 in a row: title, two accuracy cards, "Evaluation" graph, "Move breakdown", "Key moments", 4 tactic buckets (each with its own cards, "×N", "Textbook example", and a "17 minor (below ±0.5 pawns) Show" expander) |
| Numbers before any sentence | 2 accuracies + 2 ratings + 22 counts = 26 |
| Actions | top-bar "Watch game review"; key-moment rows; tactic occurrence rows; "Textbook example" per group; "Show/Hide" per bucket |
| Jargon | "Est. performance rating", "Evaluation" (the graph has no axis or legend), "Move breakdown", "Forced", "Book", "Miss" (vs "Mistake"), tactic names (`strings.xml:287-318`), "minor (below ±0.5 pawns)" |
| i18n violation | Bucket titles and empty messages are hardcoded English in Kotlin (`GameReportScreen.kt:183-232`: "Tactics you found", "No missed tactics — nice game.", "White found", …) — against the Round 10 contract |
| Dead parameter | `onShareClick` (:71) is never passed by the nav host, so `report_share` is unreachable |
| Order problem | The thing the user wants first ("Key moments" = the five worst moves, `GameAnalyzer.kt:157-162`) is the fourth section, below the fold on every screenshot |

When no username is set (the default — `EngineSettings.username = ""`), `report.userColor` is null and the buckets become "White found / White missed / Black found / Black missed" with up to four grey "didn't land any tactics" cards (`r6_tactics_14` shows two of them). The user never learns that typing their username in Settings is what turns this into "you".

### 1.5 Missed-tactic walkthrough / textbook example — `TacticSimulationScreen.kt`; `r6_tactics_11_missed_sim_offer.png`

| | Count |
|---|---|
| Actions | close X, prev, next, "See the textbook example", "Back to the game" — two exits for one screen (:104-107 and :197-199) |
| Jargon | "Missed tactic: Skewer", "Payoff: has invested material in the attack" — the payoff line is garbled core copy (`simulation.payoffDescription`), visible in the screenshot |

Otherwise this is the clearest screen in the app: one board, one stepper, one card. Keep its shape.

### 1.6 Video — `VideoScreen.kt`; `r5_07_video_screen.png`, `r5_11_video_ready.png`, `video_fullscreen.png`

| | Count |
|---|---|
| Top bar | truncated title ("MorphyFan1"), fullscreen icon, "Prepare narration", "Export video" (:321-346) — three actions competing in 360dp |
| Below the frame | scrub bar, 3 transport icons, "Speed" label + 5 chips, "Chapters" label + N chips (:382-448) |
| Jargon | "Prepare narration" / "Narration ready" (cache priming exposed as a feature, `strings.xml:252-255`), "Export video", chip "0.75x…2.0x", chapter chips named "Move 4: Bxf3" |
| Dialogs | two separate progress dialogs with the same shape (`PrepareNarrationDialog`, `ExportProgressDialog`); completion shows "572.8s · 99.0 MB" (`:731`, hardcoded format) |

`video_fullscreen.png` shows the rendered frame itself is already good; the chrome around it is the problem.

### 1.7 Settings — `SettingsScreen.kt`; `r10_settings_tier_picker.png`, `r9_settings_narration_voice.png`, `r10_settings_language.png`, `r10_rtl_settings_top.png`

| Section (`SettingsScreen.kt`) | Controls | Jargon in the labels |
|---|---|---|
| Engine (:93-121) | 3 sliders: "Search depth" 6–30, "Time per move" 100–3000 ms, "Analysis lines (MultiPV)" 1–5 | all three |
| Your account (:123-141) | username field + help | "auto-detect which side is you" |
| Narration content (:143-152) | threshold slider 0–300 cp shown as "±0.5 pawns" + 3-sentence help ("Sequences are judged on their combined swing") | pawns, swing, sequences |
| Narration voice (:154-178) | 3 provider radios (Device / Natural voice (on-device) / Cloud voice (Google)), 2 tier radios (Piper / Kokoro) each with size line, Wi-Fi note, Download/Cancel/Delete button, 6+ cloud voice radios grouped "English voices / Hebrew voices" with "free tier ≈ 142 reviews/month", key status line, "Set up key"/"Change key"/"Remove", "Narration audio: 0.0 MB" + Clear | Piper, Kokoro, on-device, tier, API key, free tier, MB on disk |
| Engine version (:180-198) | "Engine: Stockfish (sf_19 @ edb0d9d)", "Network: nn-1a298aa575a0.nnue", "Check for updates" button | all of it; the button calls the GitHub releases API (`NetworkProvider.kt:117-123`) — an external call the owner has ruled out |
| Language (:200-209) | 2 radios + help | fine |
| About (:211-230) | GPL body, "View GPLv3 license notice", "About Palaya Chess" | duplicates the About screen |

Total: 4 sliders, 1 text field, ≥13 radios, ≥8 buttons = **≥26 interactive controls**, no section collapsed. `r10_rtl_settings_top.png` also shows a bidi defect in the slider value labels: "ms 500" and "pawns ±0.5" render reversed because the value strings have no strong-direction character (same class of bug Round 10 fixed in the move list).

### 1.8 About — `AboutScreen.kt`, `about_screen.png`

Fine as a legal page: back arrow, logo, version, owner, three licence sections. Keep; only move the engine/net version lines here from Settings and fix `SOURCE_REPO_URL` when the owner supplies it (HANDOFF item 3).

### 1.9 Cross-cutting findings

- **Dead settings.** `timePerMoveMs` is persisted (`SettingsRepository.kt:28,62,95`) and shown as a slider but never read by `AnalysisService.analyze` (only `settings.depth` and `settings.multiPv`, `AnalysisService.kt:84,146`). `BOARD_ORIENTATION_WHITE_DOWN` has a flow and setter but no reader. The autoplay toggle (§1.3) does nothing.
- **Hardcoded UI English outside `strings.xml`**: `GameReportScreen.kt:183-232`, `ImportScreen.kt:266` ("plies"), `ImportScreen.kt:262`/`GameReportScreen.kt:109` ("vs"), `VideoScreen.kt:731` ("%.1fs · %.1f MB"), `AnalysisViewModel.kt:316`, `AnalysisService.kt:76-80,105,108,177`, `MoveList.kt:176-181` (content description "Move 4, Nc3, mistake, evaluation…").
- **First-run path today** (share from Chess.com): share sheet → "Analyze in Palaya Chess" → progress (plus, before bundling, "Preparing engine…" + 98 MB download) → Review board → tap "View game report" → scroll past 26 numbers to Key moments → tap a moment → back on the Review board at that ply → "Show me". That is 5 taps and two screen switches to reach the first explanation of a mistake. The design below makes it: share → progress → Summary (key moments visible without scrolling) → tap → walkthrough. 2 taps.
- **No consistent back model**: only About draws a back arrow; Review/Report/Video/Settings rely on the system gesture.
- **Three competing names for one game**: "Review" (board), "Game report", "Watch game review" (video).

---

## 2. Simplified information architecture

### 2.1 The journey

```
 Get a game in                 Analyse                Understand                Go deeper (optional)
 ─────────────                 ───────                ──────────                ───────────────────
 Share sheet ──┐                                       ┌─> Walkthrough (missed tactic / textbook)
 Open file ────┼─> [Analysing…] ─> [Game summary] ─────┼─> Board (move-by-move review, at a ply)
 Paste ────────┘                                       ├─> Video review (play / save)
 Recent game ──┘ (cached, instant)                     └─> Settings (gear on Home only)
```

Rule: **one hub per game (Summary); everything else is a leaf you return from.** No leaf links sideways to another leaf except Board → Walkthrough (which already exists and is natural) and Walkthrough → Textbook example.

### 2.2 Screen map (before → after)

| Today (`Destinations.kt`) | After | Decision |
|---|---|---|
| `Import` | **Home** | Keep route `import`. Simplify to one primary button, a paste sheet, recent games. |
| `AnalysisProgress` | **Analysing** | Keep. Add first-run "Setting up (one time)" phase for bundled extraction (task 68) and an inline error state. |
| `Review` | **Board** | Keep route `review/{gameId}?ply=`. Stop being the post-analysis landing. Lose top-bar text links; gain back arrow. |
| `GameReport` | **Summary** (the hub) | Keep route string `game_report/{gameId}` (no test pins it, but it avoids churn). Becomes the post-analysis landing. Restructured: key moments first, details collapsed. |
| `Simulation`, `Reference` | **Walkthrough** | Keep both routes; same screen, one exit. |
| `Video` | **Video review** | Keep. One primary action ("Save video"); "Prepare narration" removed. |
| `Settings` | **Settings** | Keep. 3 visible rows + "Advanced" expander. |
| `CloudVoiceSetup` | removed | Task 60. |
| `About` | **About** | Keep; absorbs engine/net version lines. |

### 2.3 Navigation model

- Post-analysis: `runAnalysis` callback navigates to `GameReport.createRoute(gameId)` with `popUpTo(AnalysisProgress) { inclusive = true }` (today `ChessAnalyzerNavHost.kt:114`).
- Every non-home screen draws `navigationIcon = Icons.AutoMirrored.Filled.ArrowBack` (as `AboutScreen.kt:96-100` does) calling `popBackStack()`. Walkthrough keeps the arrow and drops the X.
- Summary → Board uses the existing `?ply=` argument (`Destinations.kt:20-23`), so tapping a key moment opens the board *at that move*, which is the already-verified behaviour.
- The settings gear stays on Home only (`ImportScreen.kt:115-119`); it is not a per-game action.
- Recent game → Analysing → Summary (cached evals make this near-instant, `AnalysisService.kt:183-191`).

### 2.4 Merges and removals, justified

- **Merge "Report" + the landing role of "Review" into Summary.** The report already contains the key moments, tactics and accuracy; the board is a tool for inspecting one of them. Landing on the tool first is the main cause of the "too complex" impression.
- **Remove "Prepare narration".** It primes the `NarrationStore` cache that export and playback already fill on demand (`VideoScreen.kt:176-197`, class doc on `NarrationStore`). With the voice bundled there is no download to pre-empt. Export still shows "Preparing narration… (n/N)" progress (`VideoExporter.State.SynthesizingNarration`), so nothing is lost.
- **Remove the Settings "Engine version / Check for updates" card** (external API) and the Settings "About" card (duplicate of the About screen).
- **Do not add any new screen.** A "Practise" screen (task 67) can later hang off the Summary's "Practise these positions" section; the design reserves the slot.

---

## 3. Settings: progressive disclosure

### 3.1 Every current setting, with a decision

| Setting (key / UI label) | Today | Decision | Reason |
|---|---|---|---|
| `depth` / "Search depth" 6–30 | slider, default 14 (`SettingsRepository.kt:105`) | **Hide under Advanced as a 3-way preset** "Analysis strength: Quick / Standard / Deep" = 12 / 14 / 18 | Spec §8 names presets 12/18/24 (`ANALYSIS_SPEC.md:270-271`); 24 is impractical on a phone (emulator reached depth 12, `RUN_LOG.md:2034`). Store the same int key, clamp unchanged (6..30, `SettingsRepository.kt:94`). A user who has an old out-of-preset value sees "Custom (16)". |
| `time_per_move_ms` / "Time per move" | slider | **Remove from UI** (leave the key; nothing reads it) | Dead: never consumed by `AnalysisService` (§1.9). |
| `multi_pv` / "Analysis lines (MultiPV)" 1–5 | slider, default 3 | **Remove from UI; keep value 3 (auto)** | The significance gate uses the second-best line (`evalSecondBestCp`, `TacticGateMappingTest.kt:86-89`), so MultiPV < 2 silently degrades tactics. Not a user decision. |
| `username` / "Your username" | text field under "Your account" | **Keep, promote to the first visible row, rename "Your name on Chess.com / Lichess"** | It is the only thing that turns the report into "you / your opponent" (`AnalysisService.kt:216-225`). Also offered contextually on the Summary (see §6.3) so nobody has to find it. |
| `narration_threshold_cp` / "Only narrate moves worth ±0.5 pawns" | slider 0–300 cp, help text | **Hide under Advanced as a 3-way preset** "What the review talks about: Only big moments (100) / Balanced (50, default) / Every move (0)" | Default 50 is pinned by `NarrationThresholdSettingsTest.kt:56-63` and the clamp 0..300 by `:43-53`; presets stay inside both. The gate also drives the report's tactic buckets (`ChessAnalyzerNavHost.kt:184-185`), so the row's help says so in plain words. A stored value not in the set shows "Custom (±0.7)". |
| `app_language` / "Language" | 2 radios + help | **Keep visible** (second row), as a single row opening a dialog/list | Needed for Hebrew later (Round 10 groundwork); below API 33 keep the existing disabled explanation (`SettingsScreen.kt:242-266`). |
| `narration_provider` (Device/Neural/Cloud) | 3 radios | **Auto.** Neural (bundled Kokoro) always; CLOUD removed (task 60). Expose one Advanced switch "Use the phone's built-in voice instead" that writes `DEVICE` via `setProvider` | Keeps the fallback for a device where sherpa-onnx fails, without a choice on the main path. The `providerExplicitlyChosen` latch semantics (`NarrationSettingsRepository.kt:98-113`) are preserved because the switch calls `setProvider`. |
| `neural_voice_tier` (Piper/Kokoro) + Download/Delete + sizes + Wi-Fi note | tier picker | **Remove from UI** (bundled; task 68 decides whether Piper ships at all) | HANDOFF: Kokoro is the verified default (`HANDOFF.md:94-98`). The tier-picker layout values (16dp indent, 12dp button padding, "Smaller, faster") only matter if the picker survives; see §8. |
| `cloud_voice_id`, API key, "Set up key" | cloud section + wizard | **Remove** | Task 60. |
| "Narration audio: N MB" + Clear | row | **Hide under Advanced → "Storage"** as "Saved narration audio: 12.4 MB [Clear]" | Useful but rare. |
| "Engine: … / Network: … / Check for updates" | card | **Remove from Settings**; engine version line already exists on About (`AboutScreen.kt:356-360`); drop "Check for updates" entirely | External API call; contradicts "no external APIs". |
| "About" card (GPL body, notice, button) | card | **Replace with one row "About Palaya Chess ›"** | Duplicate of About screen. |
| Board orientation pref (`board_orientation_white_down`) | no UI, no reader | **Auto**: Board flips to the user's colour when `userColor` is known; the flip icon remains for manual override, not persisted | Removes a setting and a decision. |
| Playback speed (video) | 5 chips on the Video screen | **Keep as one cycle button in the player (1×, 1.25×, 1.5×)**, not a Settings row | The owner asked for "at most one simple choice"; a player control is more discoverable than a setting. Not persisted. |
| `NarrationOptions.depth` (HIGHLIGHTS / …), `style` (COACH / ANALYST), `includePuzzlePrompts` | not exposed today | **Stay unexposed** | Good defaults; task 64 is tuning pacing in core. |

### 3.2 Resulting Settings screen

Visible: **Your name**, **Language**, **Advanced ›** (collapsed), **About Palaya Chess ›**. Advanced expands in place to: Analysis strength (segmented), What the review talks about (segmented), Use the phone's built-in voice (switch), Saved narration audio (row + Clear). That is 4 visible controls, 4 more behind one tap, down from 26.

---

## 4. Plain-language copy

All strings stay in `app/src/main/res/values/strings.xml`; new keys are proposed with names so a coding agent can add them without inventing. Existing keys that are only reworded keep their key. Hardcoded Kotlin text listed in §1.9 moves into resources as part of this table.

| Where | Today | Proposed | Key |
|---|---|---|---|
| Home title | "Analyze a game" | "Palaya Chess" | `home_title` (new) |
| Home headline | "Bring a game in to get started" | "Review a game you played" | `import_empty_headline` |
| Home body | 31-word paragraph naming PGN three times | "Share a game here from Chess.com or Lichess, or open a game file." | `import_empty_body` |
| Primary button | "Choose PGN file" | "Open a game file" | `import_choose_file` |
| Secondary button | "Paste PGN" / "Analyze pasted PGN" | "Paste moves" (opens sheet) / sheet button "Analyze" | `import_paste_label`, `import_paste_action` |
| Paste placeholder | `[Event "Casual Game"]\n1. e4 e5 2. Nf3 Nc6 …` | "1. e4 e5 2. Nf3 Nc6 … (or the whole PGN)" | `import_paste_placeholder` |
| Invalid paste | "That doesn't look like a valid PGN…" | "That doesn't look like a chess game. Paste the moves or the PGN text and try again." | `import_invalid_pgn` |
| Recent row subtitle | "2026.03.14 · 33 plies" (hardcoded) | "14 Mar 2026 · 17 moves" via `DateUtils`/`plurals` | `recent_moves_count` (plural, new) |
| Progress | "Preparing engine…", "Downloading neural network (…)" | first run only: "Setting up the engine (one time)…"; delete the download string | `progress_first_run_setup` (new); remove `progress_downloading_net` with task 68 |
| Progress error | AlertDialog with raw message | inline: "Couldn't analyze this game" + "Check the moves and try again." + [Try again] [Back] | `dialog_analysis_failed_title`, `analysis_failed_hint` (new) |
| Summary title | "Game report" | "Game summary" | `report_title` |
| "Est. performance rating" | | "Played like a ~1650" (number inside the sentence; low-confidence case: "Too short to estimate a rating") | `report_est_rating_sentence` (new), `report_rating_low_confidence` |
| "Evaluation" (graph header) | | "Who was winning" | `report_eval_graph` |
| "Move breakdown" | | "All moves" | `report_classification_breakdown` |
| "Key moments" | | "Your key moments" / "Key moments" when side unknown | `report_key_moments_you` (new), `report_key_moments` |
| Tactic buckets (hardcoded) | "Tactics you found / missed / your opponent found / missed" | same words, as resources; neutral fallback "White found / White missed / …" also as resources | `report_tactics_you_found`, `_you_missed`, `_opponent_found`, `_opponent_missed`, `_white_found`, … (8 new) |
| Bucket empty messages (hardcoded) | 8 variants | "Nothing here." for all found/missed-by-opponent; "No missed tactics — nice game." for your missed | `report_tactics_empty`, `report_tactics_empty_nice` (new) |
| Minor disclosure | "17 minor (below ±0.5 pawns)" + Show | "17 smaller ones" + "Show" | `report_minor_tactics_hidden` (drop the threshold arg; keep `%1$d`) |
| "Textbook example" | | "See how it works" | `report_learn_pattern`, `review_learn_pattern` → "See how a %1$s works" |
| Side chooser (new) | — | "Which side were you?" [White] [Black] [Not me] | `summary_which_side`, `side_white`, `side_black`, `summary_side_neither` (new) |
| Board title | "Review" | "Board" | `review_title` |
| "Best line: Nf6" | | "Better was Nf6" | `review_better_was` (new, `%1$s`) |
| Swing on chip | "+1.9" (10sp grey) | removed from chip; on the card for mistake classes only: "This cost about 2 pawns" / "…half a pawn" | `review_cost_pawns` (new, `%1$s`) |
| "Show me" | | "Show me what I missed" (missed tactic) / "Show me" when it was found | `review_show_me_missed` (new), `review_show_me` |
| Walkthrough title | "Missed tactic: Skewer" | "What you missed: Skewer" / textbook: "How a Skewer works" | `simulation_missed_title`, `review_learn_pattern` |
| "Payoff: …" | | "Result: wins a queen" | `simulation_payoff` → "Result: %1$s" (core must deliver a clean noun phrase, task 65) |
| "Back to the game" + X | | single back arrow; final step button "Done" | `common_done` (new) |
| Video top bar | title + "Prepare narration" + "Export video" | title "Video review"; bottom button "Save video" | `video_title` (new), `video_export_action` → "Save video" |
| "Export video" dialog title | | "Saving video…" | `video_export_action` reused; completion "Video saved" | `video_export_completed_title` |
| Completion body | "572.8s · 99.0 MB" | "9 min 33 s · 99 MB" via `DateUtils.formatElapsedTime` + `Formatter.formatShortFileSize` | no literal |
| "Speed" + 5 chips | | one button showing "1×" that cycles | `video_speed` (content description only) |
| "Chapters" label | | drop the label; chips alone | remove usage |
| Narration unavailable | "No voice installed on this device — showing captions only" | "Narration isn't available on this phone — showing captions only" | `video_narration_unavailable` |
| Settings sections | "Engine", "Your account", "Narration content", "Narration voice", "Engine version", "Language", "About" | "Your name", "Language", "Advanced", "About Palaya Chess" | `settings_username_header` → "Your name"; `settings_advanced` (new) |
| Username help | "Used to auto-detect which side is you when a PGN has player names." | "So the summary can say 'you' instead of 'White'." | `settings_username_help` |
| Analysis strength | "Search depth" | "Analysis strength" — Quick / Standard / Deep, help "Deeper is more accurate and slower." | `settings_depth`, `settings_depth_quick/standard/deep` (new) |
| Threshold | "Only narrate moves worth ±0.5 pawns" + 3 sentences | "What the review talks about" — Only big moments / Balanced / Every move; help "Also decides which tactics count in the summary." | `settings_narration_threshold`, `settings_threshold_big/balanced/all` (new) |
| Voice | Device / Natural voice (on-device) / Cloud | switch "Use the phone's built-in voice instead" | `settings_voice_use_device` (new) |
| Storage | "Narration audio: 0.0 MB" | "Saved narration audio: 0 MB" / "Nothing saved yet" | `settings_narration_storage_label`, `settings_narration_storage_empty` |
| Classification names (`strings.xml:205-215`) | "Great move", "Best move", "Miss", "Forced", "Book" | **Keep as is** (owner-requested chess.com vocabulary; panel label consistency test), but add one-line tooltips for the report legend: "Book — a standard opening move", "Forced — the only legal/sensible move", "Miss — a winning chance went by" | `classification_book_help`, `classification_forced_help`, `classification_miss_help` (new) |
| Tactic names | 32 names | keep (they are the pattern vocabulary) but never show one without its description sentence next to it | — |

Rules for the coding agent: no `Text("…")` literals; numbers with units through `%1$s` placeholders already formatted with `Locale`-aware formatters; any string that is only digits/symbols (e.g. "+0.4", "1×") gets `LocalLayoutDirection = Ltr` or a `\u200E` LRM prefix so RTL cannot reverse it (the `r10_rtl_settings_top.png` "ms 500" defect).

---

## 5. Visual simplification

Principles (each maps to a concrete rule the agent can check):

1. **One primary button per screen**, filled green (`GreenPrimary`), full width or bottom-anchored; everything else is tonal/outlined/text. Home: "Open a game file". Summary: none (cards are the actions) except the bottom "Watch the video review". Board: none. Walkthrough: "Next"/"Done". Video: "Save video". Settings: none.
2. **Top app bar = back arrow + title + at most one icon.** No text buttons in app bars anywhere (removes `ReviewScreen.kt:120-131`, `GameReportScreen.kt:85-96`, `VideoScreen.kt:324-345`).
3. **Colour budget per screen: green primary + one accent tier at a time.** Classification colours appear only on the elements that carry the classification (badge, chip, graph marker), never as card backgrounds.
4. **Move-quality colours, readable at a glance (owner feature, kept):**
   - Two tiers. **Highlight tier** (coloured chip tint + badge): BRILLIANT, GREAT, INACCURACY, MISTAKE, MISS, BLUNDER. **Quiet tier** (neutral chip, small badge only): BEST, EXCELLENT, GOOD, BOOK, FORCED. In `r6_review_move_list_sequence.png` this turns 6 colours into 3 (green/blue vs orange/red) and makes the mistakes pop. Implemented as a `val isHighlight: Boolean` on `MoveClassification` (ui.theme) — the palette itself (`Color.kt:47-57`) and glyphs are untouched, which is what §9.5 of the spec and the CVD argument require.
   - The report's "All moves" table groups the quiet tier into one row "Good moves (Best, Excellent, Good)" and one "Book / Forced" row by default, with "Show all 11" to expand — fewer categories by default, nothing lost.
   - Badge size minimum 18dp in lists, 26dp on the card (today 14dp on chips, `MoveList.kt:211`); glyph stays.
5. **Touch targets ≥ 48dp**: the move chips (currently ~34dp tall) get `heightIn(min = 48.dp)`; transport icons use `IconButton` (48dp) already.
6. **Density**: move chips show SAN + badge + score (3 items), not 5. Sequence band stays (it is the owner's "collapse" feature) but is drawn 4dp tall with the label only on the first chip and the label moved into the comment card ("Part of a 3-move collapse"); the 9sp band text (`MoveList.kt:157`) is unreadable at 1080×2400 and illegal at large font scale.
7. **Empty and error states** are inline cards with one action, never bare text and never a modal with only "OK".
8. **Large font scale (1.3–2.0)**: all rows are `Column`-wrapping, no fixed-height text containers, `maxLines` only with `TextOverflow.Ellipsis`, slider value labels under the label rather than beside it, segmented buttons wrap to a column at ≥1.5× (use `FlowRow`).
9. **360×800 dp**: board = screen width − 2×12dp − eval bar 20dp = ~316dp square; at that size the Board screen fits board (316) + controls (56) + chips (56) + card (≥120) inside 800 − 64 app bar − 48 nav = 688 with the card scrolling. Verified arithmetic only; confirm on the emulator at `wm size 360x800` density 160.
10. **RTL**: unchanged pins (board, move list, transport, notation) per Round 10; new chrome mirrors naturally; segmented buttons and the speed cycle button carry LRM-prefixed numeric text.

---

## 6. Per-screen specifications

All screens: Material 3 `Scaffold`, dark theme as today (`MainActivity.kt:38`), `TopAppBar` with `navigationIcon` back arrow except Home.

### 6.1 Home (`ImportScreen.kt`, route `import`)

Purpose: get a game in with the least reading; show recent games.

```
┌──────────────────────────────┐
│ Palaya Chess             ⚙   │  TopAppBar; gear = Settings
├──────────────────────────────┤
│ ┌──────────────────────────┐ │
│ │ ♞  Review a game you      │ │  Card (surfaceContainerHigh), chess icon
│ │    played                 │ │  (use the app's own knight vector from PieceVectors)
│ │ Share a game here from    │ │
│ │ Chess.com or Lichess, or  │ │
│ │ open a game file.         │ │
│ │ [  Open a game file  ]    │ │  Button (filled, full width)
│ │ [    Paste moves     ]    │ │  OutlinedButton (full width)
│ └──────────────────────────┘ │
│ Recent games                 │  titleMedium; hidden when empty
│ ┌ MorphyFan1857 vs Duke… 1-0┐│  ListItem-style card, 56dp min
│ │ 14 Mar 2026 · 17 moves    ││
│ └──────────────────────────┘ │
└──────────────────────────────┘
```

- "Paste moves" opens a `ModalBottomSheet` (M3, available in BOM 2024.06) with a 6-line `OutlinedTextField`, a "Paste from clipboard" text button (reads `ClipboardManager`; purely local), and a full-width "Analyze" button enabled when non-blank. Sheet state is `rememberSaveable`.
- States: empty (card only, no "Recent games" header — today the header + "Games you analyze will show up here." adds two labels to an empty screen, `r5_01_import.png`); with games (card shrinks to a compact single-row header "Review another game  [Open file] [Paste]" above the list so recents dominate — implement as the same card with `expanded = recentGames.isEmpty()`); error (invalid file/paste) shown as a `Snackbar` with the `import_invalid_pgn` text instead of a `Toast` (`ChessAnalyzerNavHost.kt:89`).
- Removed: the permanently expanded paste card, the gamepad icon, "plies", the raw PGN placeholder, the "Recent games" empty text.
- Keeps: `PGN_PICKER_MIME_TYPES`, the SAF launcher, `onRecentGameSelected` → reopen path.

### 6.2 Analysing (`AnalysisProgressScreen.kt`)

Purpose: wait, with a sense of progress; recover from errors without losing the game.

Layout unchanged (centered ring, title, status, linear bar, Cancel — `r5_03_analysis_progress.png` is good). Changes:
- Status strings: `ANALYZING_MOVES` → "Move 29 of 34"; first-run bundled-asset extraction (new phase from task 68, e.g. `AnalysisPhase.FIRST_RUN_SETUP`) → "Setting up the engine (one time)…"; `PREPARING_ENGINE` → "Starting…". Delete the net-download phase string once task 68 lands; until then keep it.
- Error state replaces the `AlertDialog` (`ChessAnalyzerNavHost.kt:128-139`): the same column shows an error icon, "Couldn't analyze this game", a one-line hint, and two buttons [Try again] (re-runs `runAnalysis` for the same `gameId`; the pending PGN is still registered) and [Back]. Error messages from `AnalysisService` are mapped to resource strings by outcome type (`ParseError` → hint about moves; `EngineError` → "The engine couldn't start. Restart the app and try again."), never shown raw.
- Cancel keeps the partial-cache behaviour (`AnalysisService.kt:165-172`).

### 6.3 Game summary (`GameReportScreen.kt`, route `game_report/{gameId}`) — the hub

Purpose: answer "what went wrong and what do I do about it" in the first screenful; offer the board, the walkthroughs and the video.

```
┌──────────────────────────────┐
│ ←  Game summary              │
├──────────────────────────────┤
│ MorphyFan1857 (you) 1-0 Duke…│  header card: names, result, opening name
│ Philidor Defense · 17 moves  │  (opening from CoreGameReport.openingName — currently
│                              │   only in the video header)
│ You 97%  ▬▬▬▬▬▬▬▬▬▬  ○ 83%   │  one accuracy row, two numbers, your side first
│ Played like a ~2600          │  rating sentence; or "Too short to estimate"
├──────────────────────────────┤
│ Which side were you?         │  ONLY when userColor == null:
│ [ White ] [ Black ] [Not me] │  SegmentedButton row; choosing writes username
│                              │  = that PGN tag via settingsRepository and
│                              │  re-maps the report (uiReportFor) → "you" framing
├──────────────────────────────┤
│ Your key moments             │  up to 5 (GameAnalyzer.kt:157-162), yours first
│ ┌ ?? 15. c3                 ┐│  badge 26dp · move · one-line summary
│ │ Drops a piece to Nxe5.    ││  tap → Board at ply (existing onKeyMomentClick)
│ │ [Show me what I missed]   ││  only when move.core.simulation != null
│ └───────────────────────────┘│
│ …                            │
├──────────────────────────────┤
│ [   ▶ Watch the video review ]│  FilledTonalButton, full width
│ [   Open the board           ]│  OutlinedButton → Review at ply 0
├──────────────────────────────┤
│ Details                   ˅  │  collapsed expander (rememberSaveable)
│   Who was winning   (graph)  │  EvalGraph unchanged
│   All moves         (table)  │  grouped rows + "Show all 11"
│   Tactics you found / missed │  the four buckets, as today, with
│   Your opponent found/missed │  "See how it works" and "17 smaller ones · Show"
└──────────────────────────────┘
```

- States: loading (never — report is in memory; if `uiReportFor` returns null, pop back as today); no key moments (a mistake-free game) → card "No mistakes big enough to mention — nice game." and the Details expander opened by default; side unknown → the chooser row; side known → "(you)" marker on the header and "Your key moments" filters to the user's plies first, then the opponent's under a subheading "Your opponent's".
- "Not me" hides the chooser for this game (in-memory flag) and keeps neutral White/Black framing.
- Removed from the first screenful: the 22-row table, the graph, the four bucket headers, "Watch game review" top-bar link, the dead share icon.
- Kept verbatim: `EvalGraph` (with sequences), `ClassificationTable` logic (now grouped), the four buckets and the minor disclosure (`MinorTacticsDisclosure`), `onLearnPattern`, `onTacticClick` → Board at ply. The §9.6 gate and `tacticThresholdCp` plumbing (`ChessAnalyzerNavHost.kt:184-185`) is untouched.
- Large font: the accuracy row becomes two stacked rows at ≥1.3×.
- RTL: mirrors; the "97%" numbers are plain digits and safe.

### 6.4 Board (`ReviewScreen.kt`, `MoveList.kt`, `CommentCard.kt`, `EvalBar.kt`)

Purpose: look at one position and step through the game.

```
┌──────────────────────────────┐
│ ←  Board                  ⇅  │  flip icon in the app bar (only icon)
├──────────────────────────────┤
│ ▌┌──────────────────────┐    │  eval bar 20dp + board, Row height = board
│ ▌│        board         │    │  height (aspectRatio 1f on the Row, NOT weight(1f))
│ ▌└──────────────────────┘    │
│ 1. e4  e5  2. Nf3 … [g6 ?]…  │  LazyRow chips: SAN + badge (+ score below, 11sp)
│      ⏮    ◀    ▶    ⏭        │  4 IconButtons, prev/next 56dp, centred
│ ┌──────────────────────────┐ │  comment card fills the rest, scrolls
│ │ ? Mistake  g6            │ │
│ │ Better was Nf6           │ │
│ │ This opens a discovered  │ │
│ │ attack.                  │ │
│ │ This cost about 2 pawns  │ │  only for mistake classes
│ │ [Show me what I missed]  │ │  Button; only when simulation != null
│ │ See how a skewer works   │ │  TextButton; only when pattern has a reference
│ └──────────────────────────┘ │
└──────────────────────────────┘
```

- Fix the dead space: the board `Row` gets `Modifier.fillMaxWidth().aspectRatio(1f)` (eval bar inside it is `fillMaxHeight`, which is then the board height), and the `CommentCard` gets `weight(1f)` + `verticalScroll`.
- Transport: remove the autoplay toggle (dead, §1.3); keep first/prev/next/last; move flip to the app bar. Pin LTR as today (`ReviewScreen.kt:223`).
- Chips: remove the swing text (`MoveList.kt:221-227`), keep score, enforce `heightIn(48.dp)`, tint only highlight-tier classes (§5.4). The sequence band becomes 4dp with no text; its label moves into the comment card as a secondary line "Part of a collapse (moves 4–6)". The `contentDescription` builder (`MoveList.kt:176-181`) moves to a string resource with placeholders (keeps the accessibility promise from the file's class doc).
- Comment card: "Better was %s" instead of "Best line:", "Show me what I missed" copy, cost line computed from `move.evalSwingCp` rounded to halves and worded via plurals ("about half a pawn", "about 2 pawns"); for mate boundaries (`evalSwingCp == null`) no line.
- Start position: today a bare "Start position" text (`ReviewScreen.kt:200-207`); becomes a card "Starting position — tap a move or press ▶".
- Orientation default: `BLACK_DOWN` when `game`'s user colour is Black (needs `userColor` passed into `ReviewScreen`; available from `viewModel.reports[gameId]?.userColor`).
- Removed: both top-bar text links (`:121-130`), autoplay, swing on chips, 9sp band label.

### 6.5 Walkthrough (`TacticSimulationScreen.kt`)

Purpose: replay one missed tactic (or the textbook version) step by step. Shape is kept (`r6_tactics_11_missed_sim_offer.png`).

- App bar: back arrow + "What you missed: Skewer" (or "How a Skewer works"); the X (`:104-107`) is removed.
- Stepper row: "‹  8. Bxa6  ›" as today but with a step counter "2 / 5" under it and ≥48dp arrows.
- Card: explanation (bodyLarge), then on the last step "Result: wins a queen" and, if a reference exists, the offer text + "See how a skewer works" (FilledTonal). Bottom: one `Button` "Next" that advances and becomes "Done" on the last step (replaces the outlined "Back to the game"). Two exits become one.
- Core copy defect to raise with task 65: `payoffDescription` currently yields "has invested material in the attack" (screenshot); the UI should display `simulation_payoff` only when the string starts with a verb phrase the core guarantees ("wins…", "mates…"); otherwise omit the line.

### 6.6 Video review (`VideoScreen.kt`)

Purpose: play the narrated review; save it as a file.

```
┌──────────────────────────────┐
│ ←  Video review          ⛶   │  fullscreen icon only
├──────────────────────────────┤
│ ┌──────────────────────────┐ │  16:9 BoardSurfaceView (unchanged)
│ └──────────────────────────┘ │
│ ●━━━━━━━━━━━━━━━━━━━━━━━━━━ │  scrub
│    ⏮      ▶      ⏭     1×   │  3 transport + speed cycle button
│ [Intro] [Opening] [Move 4…]  │  chapter chips (no label)
│                              │
│ [        Save video         ]│  bottom-anchored Button
└──────────────────────────────┘
```

- Removed: "Prepare narration"/"Narration ready" button and its dialog (`VideoScreen.kt:328-341, 466-524`); the "Speed"/"Chapters" labels; the five chips.
- Speed: one `TextButton` cycling 1× → 1.25× → 1.5× → 1× calling `controller.setSpeed`; LRM-prefixed text.
- Export: the "Save video" button runs the existing `exportTapped()` path unchanged (permission → `VideoExportService.start(context, script, narrationProvider)` with the **selected provider**, the Round 8/9/11 fix at `:293`). `ExportProgressDialog` stays as the only dialog; completion body uses `DateUtils.formatElapsedTime` + `Formatter.formatShortFileSize`; buttons "Share" / "Open" as today.
- Narration-unavailable notice stays (reworded) for the device-without-voice case.
- Fullscreen behaviour (landscape, immersive, auto-hide, double-tap) is unchanged — it is verified and good (`video_fullscreen.png`).
- Loading state: the nav host's centred spinner while the script builds (`ChessAnalyzerNavHost.kt:234-237`) stays.

### 6.7 Settings (`SettingsScreen.kt`)

```
┌──────────────────────────────┐
│ ←  Settings                  │
├──────────────────────────────┤
│ Your name                    │  OutlinedTextField "Your name on Chess.com / Lichess"
│ [ dor_palaya            ]    │  help: "So the summary can say 'you' instead of 'White'."
│ Language              ›      │  row → dialog with System default / English (+ API-33 note)
│ Advanced              ˅      │  expander (rememberSaveable), collapsed by default
│   Analysis strength          │  SingleChoiceSegmentedButtonRow Quick/Standard/Deep
│   What the review talks about│  Only big moments / Balanced / Every move
│   Use the phone's built-in   │  Switch (DEVICE vs NEURAL via setProvider)
│   voice instead              │
│   Saved narration audio 12MB │  row + "Clear" TextButton (hidden when 0)
│ About Palaya Chess    ›      │  row → AboutScreen
└──────────────────────────────┘
```

- `onSettingsChange` keeps writing the whole `EngineSettings` snapshot (`SettingsRepository.save`), so depth and threshold presets are just `copy(depth = 12|14|18)` / `copy(narrationThresholdCp = 100|50|0)`; a stored value outside the preset set renders a fourth disabled segment "Custom (16)".
- Removed: the Engine card, the Narration content/voice cards, tier picker, cloud section, Engine version card, About card. The `SettingsScreen` parameter list shrinks accordingly (`onCheckForUpdates`, `onViewGplNotice`, `neuralModelState`, `onNeuralTierChange`, `onDownloadNeuralModel`, `onCancelNeuralModelDownload`, `onDeleteNeuralModel`, `onCloudVoiceChange`, `onOpenCloudSetup`, `onRemoveCloudKey` go; `narrationVoiceSettings`, `onNarrationProviderChange`, `narrationStorageBytes`, `onClearNarrationStorage` stay).
- Large font: segmented rows wrap via `FlowRow`; the switch row is a `ListItem` with trailing content.

### 6.8 About (`AboutScreen.kt`)

Unchanged apart from: the Stockfish/net version line it already shows (`:356-360`) now the only place it appears; the body string mentioning "downloaded on request" (`about_license_neural_body`, `strings.xml:197`) reworded to "bundled with the app" after task 68. Keep the GPL/CC attribution dialogs (`CLAUDE.md` licensing section requires them).

---

## 7. Implementation plan (ordered; one Gradle build per step; steps touching the same files are grouped)

Precondition: task 60 (Cloud removal) should land **before** step 7 below, and task 68 (bundling) before step 4's string deletions; the plan is written so each step builds green regardless of whether those have landed, by leaving dead strings in place until the step that removes them.

| # | Step | Files | Done-condition (emulator) | Regression risk |
|---|---|---|---|---|
| 1 | **Copy and resources pass.** Add every new key from §4; reword existing keys; move the hardcoded English from `GameReportScreen.kt:183-232`, `ImportScreen.kt:262,266`, `VideoScreen.kt:731`, `MoveList.kt:176-181`, `AnalysisViewModel.kt:316`, `AnalysisService.kt:76-80,105,108,177` into resources (the service returns an enum/sealed reason; the UI maps it to a string). Add `plurals` for moves/pawns. | `app/src/main/res/values/strings.xml`, `GameReportScreen.kt`, `ImportScreen.kt`, `VideoScreen.kt`, `MoveList.kt`, `AnalysisViewModel.kt`, `AnalysisService.kt` | `:app:assembleDebug` green; `grep -n 'Text("' ui/` returns only format-only literals; Android lint `HardcodedText` clean on `ui/` | `PanelChipLabelTest` asserts `"? Mistake"` from the core enum's `displayName` with default `PanelLabels` — unaffected as long as `classification_*` strings are not renamed (they are not). `NarrationThresholdSettingsTest` unaffected. `AnalysisService` outcome-type change touches `EndToEndAnalysisTest.kt:95-100` only through `is Outcome.Success` — keep the sealed class names. |
| 2 | **Navigation shell.** Land on Summary after analysis; back arrows on Board/Summary/Walkthrough/Video/Settings; replace the progress `AlertDialog` with an error state passed into `AnalysisProgressScreen`; Snackbar instead of Toast on Home. Remove the `CloudVoiceSetup` composable (if task 60 has not, keep the route and just stop linking to it). | `ui/navigation/ChessAnalyzerNavHost.kt`, `ui/navigation/Destinations.kt`, `ui/screens/AnalysisProgressScreen.kt` | Share the Opera-game PGN from a text app → progress → **Summary** appears; system back from Summary returns to Home; every screen has a visible back arrow; paste garbage → error state with "Try again" keeps the text | `TacticGateMappingTest.kt:126-128` asserts `Destination.Reference.createRoute` shape — do not change the `reference/{tacticType}` route. Keep `game_report/{gameId}` and `review/{gameId}?ply=` strings. |
| 3 | **Home.** Rebuild `ImportScreenContent` per §6.1 (card with two buttons, `ModalBottomSheet` for paste with clipboard button, compact mode when recents exist, formatted date + moves). | `ui/screens/ImportScreen.kt` | Fresh install shows only the card; after one analysis the list dominates; paste sheet analyzes; RTL (`cmd locale set-app-locales … he-IL`) mirrors cleanly; font scale 1.5 wraps without clipping | `ImportScreenContent` previews must still compile; `PGN_PICKER_MIME_TYPES` is referenced by nothing else but keep it public. |
| 4 | **Analysing.** Phase strings per §6.2; add the first-run phase enum value when task 68 defines it (otherwise skip); error state layout. | `ui/screens/AnalysisProgressScreen.kt`, `ui/model/GameModels.kt` (`AnalysisPhase`) | Progress reads "Move 12 of 34"; cancel returns to Home; re-running the same game resumes (partial cache) | `AnalysisPhase` is used by `AnalysisService` — adding a value is safe; removing `DOWNLOADING_NET` must wait for task 68. |
| 5 | **Summary.** Restructure `GameReportScreen` per §6.3: header card with opening name (pass `openingName` through `GameReport`/`DomainMapper.toUiReport` from `CoreGameReport.openingName`), accuracy row, rating sentence, side chooser, key moments with inline "Show me" (needs `simulation != null` per ply — pass a `Set<Int>` of plies with simulations from the ViewModel), two buttons, Details expander containing the existing graph/table/buckets. Grouped table rows + "Show all". Side chooser writes `settingsRepository` username = PGN tag and calls `uiReportFor` again (the mapper takes `userColor`; add a ViewModel method `setUserColorForGame(gameId, color)` that re-maps `coreArtifacts` with the chosen colour and re-saves `userColorName` in `GameRepository`). | `ui/screens/GameReportScreen.kt`, `ui/model/GameModels.kt` (add `openingName`, `plysWithSimulation`), `data/mapper/DomainMapper.kt`, `ui/viewmodel/AnalysisViewModel.kt`, `ChessAnalyzerNavHost.kt` (new callbacks) | After analysing the Opera game with no username: chooser appears; tap White → header shows "(you)", buckets say "Tactics you found"; key moments visible without scrolling at 360×800; Details collapsed; expanding shows graph + table + four buckets + "17 smaller ones · Show" | `TacticGateMappingTest` calls `toUiReport(header, PieceColor.WHITE, tacticThresholdCp)` — keep that signature (new fields default). `MoveSwingMappingTest` untouched. `GameReport` is constructed in `PlaceholderData.sampleReport` — update defaults. |
| 6 | **Board.** Layout fix (aspect-ratio row, scrolling card), transport without autoplay, flip in app bar, chip simplification + highlight tiers, comment card copy, orientation default from user colour. Add `isHighlight` to `MoveClassification` (ui.theme). | `ui/screens/ReviewScreen.kt`, `ui/components/MoveList.kt`, `ui/components/CommentCard.kt`, `ui/theme/MoveClassification.kt`, `ui/theme/ClassificationBadge.kt`, `ChessAnalyzerNavHost.kt` (pass `userColor`) | At 1080×2400 no empty band between board and controls; chips ≥48dp, only mistakes/brilliancies tinted; "Better was Nf6"; the "?? Collapse" run still shows as a continuous 4dp band; RTL screenshot reproduces `r10_rtl_review.png` behaviour (board and list LTR, chrome mirrored) | Do **not** touch the `LocalLayoutDirection = Ltr` pins (`MoveList.kt:89`, `ReviewScreen.kt:223`, `ChessBoard.kt:86`). `EvalGraph` reads `classification.color` — unchanged palette. `MoveSwingMappingTest` asserts `evalSwingCp` on the model, not the chip — safe. |
| 7 | **Video.** Remove prepare-narration UI and dialog, chips/labels; add speed cycle button and bottom "Save video"; format completion body. | `ui/screens/VideoScreen.kt` | Export from the button produces an MP4 in `/sdcard/Movies/ChessAnalyzer/` narrated by the neural voice (re-run `VideoExportNeuralVoiceEvidenceTest` or pull and listen); speed button cycles; fullscreen unchanged | `VideoExportServiceInstrumentedTest`/`VideoExporterInstrumentedTest` drive the service and exporter directly — unaffected. **Keep `VideoExportService.start(context, script, narrationProvider)` with the non-null provider** (HANDOFF Round 11). Removing `runPrepareNarration` removes the only caller of `NarrationCoordinator.isFullyPrepared` from UI; the function itself stays (tests may use it). |
| 8 | **Walkthrough.** Single exit, Next/Done button, step counter, titles, payoff guard. | `ui/screens/TacticSimulationScreen.kt` | From a key moment → "Show me what I missed" → steps → Done returns to Summary; textbook offer still navigates to `reference/…` | `TacticGateMappingTest.kt:115-130` replays the reference corpus through core, not the screen — safe. |
| 9 | **Settings + repository.** Rebuild per §6.7; shrink `SettingsScreen` parameters and the nav-host call site; remove `timePerMoveMs` from `EngineSettings` and `SettingsRepository.save` (leave the DataStore key unread); remove `checkForUpdates` from the ViewModel and `EngineController` UI usage (leave `NetworkProvider.checkForEngineUpdate` for now; task 68/70 can delete it); About screen string tweak. Must run after task 60 (cloud) or it will have to carry the cloud parameters one more step. | `ui/screens/SettingsScreen.kt`, `ui/model/GameModels.kt` (`EngineSettings`), `data/SettingsRepository.kt`, `ui/viewmodel/AnalysisViewModel.kt`, `ChessAnalyzerNavHost.kt`, `ui/screens/AboutScreen.kt`, `strings.xml` (delete dead keys) | Settings shows 4 rows; Advanced expands to 4 controls; choosing "Deep" then analysing a new game runs at depth 18 (log line from `EngineController`); the voice switch writes `DEVICE` and `providerExplicitlyChosen=true` (verify with `NarrationSettingsRepositoryTest` still green); RTL shows "±0.5" un-reversed | `NarrationThresholdSettingsTest` pins default 50, clamp 0..300, and `save(current.copy(...))` — presets comply, `save` must keep writing `NARRATION_THRESHOLD_CP`. `NarrationSettingsRepositoryTest.explicitProviderChoice…` depends on `setProvider`/`setProviderAutomatically` semantics — unchanged. `AutoVoicePolicyInstrumentedTest` and `ensureDefaultNeuralVoice` become obsolete with bundling; leave them to task 68. `EngineSettings(depth=12, multiPv=3, username=…)` is constructed in `EndToEndAnalysisTest.kt:88` — removing `timePerMoveMs` (which it does not pass) is safe. |
| 10 | **Accessibility, font-scale, RTL and screenshot pass.** `adb shell settings put system font_scale 1.5`, `wm size 360x800` + `wm density 160`, `cmd locale set-app-locales … he-IL`; fix clipping; capture `docs/screenshots/r13_*` for Home, Analysing, Summary (collapsed/expanded), Board, Walkthrough, Video, Settings (collapsed/expanded), each in LTR and RTL. Restore device state (HANDOFF Round 10 lists the flags). | screenshots, small fixes in any `ui/` file | Every screen readable at 1.5× with no truncated labels; RTL board/list/notation still LTR; `RUN_LOG.md` entry with the evidence | Re-run the full `:app:connectedDebugAndroidTest` and tally `skipped="0"` per HANDOFF. |

Sequencing notes: steps 1–2 are prerequisites for everything; 3, 4 are independent of 5–8 but share `strings.xml` with 1 (already done); 5 and 6 both touch `GameModels.kt` and `ChessAnalyzerNavHost.kt`, so run them back-to-back; 9 is last among code steps because it has the largest parameter churn and depends on task 60.

---

## 8. What not to change

| Verified behaviour | Where documented | Why it survives |
|---|---|---|
| Panel chip ↔ move-list label consistency: the video side panel labels a move with the *UI* classification string, once | `HANDOFF.md:118-125` (Round 10 app layer), `PanelChipLabelTest.kt` | Design keeps `classification_*` strings and `PanelLabels.from(context)`; no UI change touches `BoardFrameRenderer`. |
| RTL pins: board Canvas, move list `LazyRow`, transport row forced LTR; notation never mirrored | `RUN_LOG.md:1858-1880`, `MoveList.kt:84-89`, `ReviewScreen.kt:219-223`, `ChessBoard.kt:83-86`, `r10_rtl_review.png` | Step 6 must keep all three `CompositionLocalProvider(LocalLayoutDirection provides Ltr)` wrappers; new numeric labels get LRM. |
| One `EvalFormat` for bar, chips and panel (`+0.9`, `M3`, `#`) | `ANALYSIS_SPEC.md:356-366`, `MoveSwingMappingTest.kt:73-98` | Chips keep the score through `EvalFormat.score`. |
| No numeric swing across a mate boundary | `ANALYSIS_SPEC.md:300-302`, `GameModels.kt:80-89` | The "This cost about N pawns" line is derived from `evalSwingCp` which is already null there. |
| §9.6 tactic gate and the "minor tactics" disclosure; sequences pruned by the same threshold | `ANALYSIS_SPEC.md:377-385`, `DomainMapper.kt:151-153,174-175`, `TacticGateMappingTest` | Moves into the Details expander unchanged. |
| The four tactic buckets with you/opponent framing (owner-requested) | `HANDOFF.md:77-78`, `GameReportScreen.kt:160-235` | Kept, now also reachable via the side chooser without Settings. |
| 11-class palette + glyphs, colour never the only signal | `ANALYSIS_SPEC.md:368-375`, `MoveList.kt` class doc | Highlight/quiet tiers change *tinting*, not palette or glyphs. |
| Export runs in `VideoExportService` (FGS `dataSync`), survives leaving the screen, notifications never a gate | `HANDOFF.md:80-85`, `AndroidManifest.xml:9-25` | "Save video" calls the same `exportTapped()`. |
| Export passes the selected provider (`VideoScreen.kt:293`) — proven by cross-correlation 0.982 | `HANDOFF.md:106-116`, `RUN_LOG.md:1956-2000` | Explicitly listed as a must-keep in step 7. |
| `providerExplicitlyChosen` latch and reading `repository.current()` for decisions, not the eagerly-seeded StateFlow | `HANDOFF.md:194-199`, `NarrationSettingsRepository.kt:93-124` | The voice switch uses `setProvider`; no new decision reads `.value`. |
| Tier-picker layout values (16dp indent, 12dp button padding, 48dp inset, shortened hints) | `RUN_LOG.md:1740-1763`, `SettingsScreen.kt:558-562,637-642` | **Only if the picker survives.** This design removes it with bundling; if task 68 keeps a Piper/Kokoro choice, keep those values verbatim and the row inside Advanced. |
| Per-app language only on API 33+, no AppCompat | `RUN_LOG.md:1882-1892`, `AppLanguage.kt` | Language row keeps the disabled-with-reason behaviour. |
| Partial-cache resume on cancel/kill | `AnalysisService.kt:113-131,165-178` | Cancel/Try again reuse it. |
| Licensing surfaces (GPLv3 text, Cburnett attribution, sherpa-onnx attribution) | `CLAUDE.md` Licensing, `AboutScreen.kt` | About unchanged. |

---

## 9. Open questions for the owner (3)

1. **Land on the Summary after analysis, not the board?** The whole design assumes yes. Recommended default: **yes** — the board is one tap away via "Open the board" and every key moment opens it at the right ply.
2. **Add the "Which side were you?" chooser on the Summary** (writes the PGN name into the existing username setting) so "you / your opponent" works without visiting Settings? Recommended default: **yes**; it is the single biggest reason the report currently reads "White found / Black missed".
3. **Narration/playback speed as a cycle button in the player (1×/1.25×/1.5×, not persisted) rather than a Settings slider?** Recommended default: **cycle button**, keeping Settings' Advanced section at four controls. If the owner wants a persisted preference, add one DataStore key `playback_speed` and seed the button from it — a half-hour change.

---

### Critical Files for Implementation
- `C:\Claude\ChessAnalyzer\app\src\main\kotlin\net\palaya\chessanalyzer\ui\navigation\ChessAnalyzerNavHost.kt` — landing change, back arrows, error state, side-chooser and user-colour callbacks
- `C:\Claude\ChessAnalyzer\app\src\main\kotlin\net\palaya\chessanalyzer\ui\screens\GameReportScreen.kt` — becomes the Summary hub (key moments first, Details expander, side chooser)
- `C:\Claude\ChessAnalyzer\app\src\main\kotlin\net\palaya\chessanalyzer\ui\screens\SettingsScreen.kt` — collapse to 4 rows + Advanced; drop engine/voice/cloud/version cards
- `C:\Claude\ChessAnalyzer\app\src\main\kotlin\net\palaya\chessanalyzer\ui\screens\ReviewScreen.kt` (with `ui/components/MoveList.kt`, `ui/components/CommentCard.kt`) — layout fix, transport cleanup, chip simplification, highlight tiers
- `C:\Claude\ChessAnalyzer\app\src\main\res\values\strings.xml` — all copy changes and the hardcoded-English migration