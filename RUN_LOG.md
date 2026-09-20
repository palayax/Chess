# RUN_LOG

## Session 1 — 2026-09-17

### Environment verification (orchestrator, Opus)
- JDK 17.0.20 Temurin, Gradle 8.6, Android SDK (platforms 34/36, build-tools 34.0.0/36.1.0). PASS
- NDK: **absent** → installing `ndk;26.1.10909125` + `cmake;3.22.1` (background).
- Stockfish latest = **SF19** (`sf_19`, published 2026-09-05).

### Key technical finding (drove assumption 1)
Official `stockfish-android-arm64-universal` is **100,695,968 bytes** and ELF type
`ET_EXEC` (non-PIE, AArch64). Rejected for shipping: exceeds practical APK size ×2 ABIs
and non-PIE exec is unreliable/blocked on API 29+. Decision: compile from source via NDK
with `NNUE_EMBEDDING_OFF`; fetch the 78,944,870-byte net `nn-1a298aa575a0.nnue` at runtime
from the official endpoint. Verified both the flag exists in SF19's Makefile and the net
endpoint returns HTTP 200 with that length.

### Model allocation plan
- Orchestrator: Opus (ambiguous spec, architecture calls) — justified: the Stockfish
  delivery decision above was expensive to get wrong.
- Subtasks 3, 5, 6 (chess core, classification, tactics): Opus/Sonnet — correctness-critical logic.
- Subtasks 1, 2, 4, 7, 8, 9, 10: Sonnet — normal implementation.
- Subtask 12, mechanical fixes: Haiku.
- Subtask 13 (adversarial review): fresh-context Opus.

| # | Subtask | Model | Est | Actual | Result | Artifacts |
|---|---------|-------|-----|--------|--------|-----------|
| 0 | Env verify + plan | Opus | 20k | ~18k | PASS | RUN_PLAN.md, RUN_LOG.md |
| 0b | Gradle baseline (settings, version catalog, 3 module build files, wrapper) | Opus | 15k | ~12k | PASS | settings.gradle.kts, gradle/libs.versions.toml, */build.gradle.kts, gradlew |
| 0c | ANALYSIS_SPEC.md (scoring/tactics/rating model) | Opus | 30k | ~25k | PASS | docs/ANALYSIS_SPEC.md |
| 0d | Opening book asset (lichess CC0, 3810 openings) + fetch script | Opus | 5k | ~4k | PASS | app/src/main/assets/openings.tsv, scripts/fetch_opening_book.sh |
| 0e | PGN fixtures (chess.com-format w/ clocks, Immortal) | Opus | 3k | ~3k | PASS | fixtures/*.pgn |
| 1 | :app scaffold + design system | Sonnet | 75k | running | — | — |
| 2 | :engine Stockfish NDK/JNI | Sonnet | 60k | running | — | — |
| 3 | :core chess rules + PGN | Sonnet | 70k | running | — | — |

### Infrastructure
- AVD `chess_test` created (android-36.1 google_apis_playstore x86_64, pixel_6), booting headless
  with swiftshader. This is why the engine module MUST produce an x86_64 ABI — it is the only
  way to verify the native engine without physical hardware.

### Reusable artifacts so far
- `scripts/fetch_opening_book.sh` — regenerates the CC0 opening book asset.
- `docs/ANALYSIS_SPEC.md` — the single source of truth for scoring/tactics/rating; all
  implementing agents are pointed at it instead of inventing their own thresholds.

### Licensing decisions (matter for Play Store publication)
- Stockfish = **GPLv3** → the app links it, so the app must ship the GPL notice and offer
  source. Noted for subtask 12; an About screen notice + a source link is the plan.
- Opening book = **CC0** (lichess-org/chess-openings), verified from the repo README.
- Chess.com is a **design reference only** — no assets, logos, or wordmarks copied.

### Subtask 2 — :engine (Stockfish 19 NDK/JNI) — **PASS** (Sonnet, ~193k tokens vs 60k est)
Cost ran 3.2x over estimate; cause was iterating native build flags across 3 ABIs. Acceptable —
this was the highest-risk subtask and it landed complete with nothing dropped.

Independently verified by the orchestrator (not taken on the agent's word):
- `engine-debug.aar` = 1,712,025 bytes containing `jni/{arm64-v8a,armeabi-v7a,x86_64}/libstockfish.so`
  at 1,635,744 / 1,318,144 / 1,671,432 bytes — all ET_DYN PIE shared libs.
- SF19 pinned at commit `edb0d9db6731067ec50ce619ff372b463bc4dd5d`.
- CMakeLists confirmed to exclude `src/main.cpp` + `src/universal/*` and define `NNUE_EMBEDDING_OFF`.
- **Total native payload ~4.6 MB for all 3 ABIs vs 100 MB for ONE official binary** — the
  source-build decision paid off exactly as intended.

Notable: armeabi-v7a was NOT dropped (32-bit devices supported). Zero patches to Stockfish.
The agent also fixed a corrupted `sdk.dir` in local.properties that was blocking all modules.

**Still unverified:** the engine has only been *compiled*, never *run*. The instrumented test
compiles but has not executed — the emulator is still booting. This is the top remaining risk
and must be closed before the run can be called complete.

### Subtask 3 — :core chess rules + PGN — **PASS** (Sonnet, ~161k tokens vs 70k est)
Independently re-verified by the orchestrator with `--rerun-tasks` (not trusting the agent's
own run): 23 tests, 0 failures, 0 errors across FenTest(3), PerftTest(5), SanTest(8),
PgnParserTest(7). **All perft values exact on all five standard positions**, including
Kiwipete d4=4085603 and Position 3 d5=674624 — that is a real correctness oracle, not a
smoke test. Mailbox IntArray(64) representation; immutable Position; Zobrist-keyed
RepetitionTracker kept separate from Position so perft stays fast.

### Subtask 12 (partial) — release keystore — **PASS** (Opus, ~3k)
RSA 4096, alias `chessanalyzer`, valid to 2054-02-02.
SHA-256 CA:4F:7B:42:CE:83:7F:97:D4:8E:0E:80:2B:48:B1:C9:C9:C2:53:BA:4E:60:4E:56:A8:DF:F6:97:9A:89:09:47
Credentials in `keystore.properties` (gitignored). Must be backed up by the user or updates
under the same Play listing become impossible.

### Orchestrator-authored shared contract
`core/.../analysis/Contract.kt` — MoveClassification, TacticType, TacticInstance,
SeeEvaluator/TacticsDetector interfaces, EngineLineInput/PositionEval, MoveAnnotation,
TacticSimulation, PlayerReport, KeyMoment, GameReport. Written centrally and marked
read-only for agents specifically so the tactics module and the analysis module could be
built **in parallel** via dependency inversion rather than serialised behind each other.

### Subtasks 5+6 launched in parallel
- Tactics detection (**Opus** — justified: SEE with x-ray re-evaluation plus 32 motif
  detectors is the hardest correctness work in the project and the most expensive to get
  wrong silently; a subtly broken SEE would poison every Brilliant/Blunder label downstream).
- Analysis pipeline (Sonnet — every formula is fully pinned in ANALYSIS_SPEC, so this is
  careful transcription rather than open reasoning).

### Emulator troubleshooting
API 36 playstore image never came online (>15 min, qemu burning CPU with WHPX available).
Abandoned it; pulled `system-images;android-34;google_apis;x86_64` (also a better match for
targetSdk 34) and created AVD `chess34`. Booting.

### Subtask 1 — :app scaffold + design system — **PASS** (Sonnet, ~213k tokens vs 75k est)
`:app:assembleDebug` produces a 21,711,843-byte debug APK; `lintDebug` clean of ERROR-severity
issues (59 warnings). Full manifest intake surface (SEND/SEND_MULTIPLE/VIEW + .pgn pathPattern),
original adaptive icon, Canvas-drawn classification badges, 5 screens, nav graph.
Carries placeholder models in `ui.model` that the integration pass must replace with :core types.

### Emulator — RESOLVED after two false starts
API 36 playstore image: abandoned. API 34 `chess34`: **booted successfully** — my first two
attempts killed it prematurely. Verbose kernel logging showed zygote starting normally at t+39s;
the boot is just slow under swiftshader. Android 14, x86_64, package manager up, debug APK
installs cleanly (`Success`).

### Subtask 4 verification — **FAILED, two distinct defects found**
This is exactly why the run required an emulator rather than trusting a compile.

**Defect 1 (fixed):** both `:engine` and `:app` declared
`testInstrumentationRunner = androidx.test.runner.AndroidJUnitRunner` but never depended on
`androidx.test:runner`, so instrumentation died with ClassNotFoundException before any test ran.
Fixed by adding `androidx-test-runner = androidx.test:runner:1.5.2` to the version catalog and
wiring it into both modules.

**Defect 2 (open, and it is a real product risk, not just a test problem):**
with the runner fixed, the test got further and the **process crashed**.
Near-certain cause: built with `NNUE_EMBEDDING_OFF`, Stockfish has no embedded net, and when it
cannot load an `EvalFile` it calls `exit(EXIT_FAILURE)`. Because the engine runs **in-process via
JNI**, that `exit()` takes the whole app down rather than surfacing an error.
=> Any user who is offline, or whose net download is incomplete/corrupt, would see the app
   vanish instead of getting an error message. Must be fixed before release:
   the app must guarantee the net exists and `EvalFile` is set *before* any `go`, and must
   degrade gracefully when it does not.
Downloading the 79 MB net on the host to push to the device via adb — far faster and more
repeatable than downloading inside the test.

**Process note:** a subsequent test re-run failed at `:core:compileKotlin` because the tactics and
analysis agents are concurrently editing `:core`, and `:engine` depends on it. Device testing must
be serialised after those agents land — parallelism has a real cost at module boundaries.

### Subtask 4 — engine runs on-device — **PASS** (Opus orchestrator, ~25k)
`./gradlew :engine:connectedDebugAndroidTest` → **6 tests, 0 failures, 0 errors, 0 skipped** on
chess34 (Android 14, x86_64). Verified from the result XML that none were skipped, because the
net-dependent tests use `assumeTrue` and would otherwise pass vacuously.

| test | time | what it proves |
|---|---|---|
| uciHandshakeSucceedsWithoutANet | 0.209s | JNI pipe bridge + UCIEngine::loop work |
| missingNetThrowsInsteadOfKillingTheProcess | 0.206s | **the exit() defect is fixed** |
| truncatedNetIsRejected | 0.374s | truncated download cannot reach the engine |
| searchFromStartPositionReturnsABestMove | 0.969s | real depth-12 search, eval near equal |
| multiPvReturnsMultipleDistinctLines | 0.694s | MultiPV=3 gives 3 distinct first moves |
| findsForcedMate | 0.542s | finds **mate in 1, bestmove a1a8** on a back-rank position |

The last three cannot be satisfied by a stub — Stockfish is genuinely searching on Android with
the real 98.5 MB NNUE net loaded. **This closes the single biggest risk in the run.**

**Defect 2 fix (in `StockfishEngine`):** `setEvalFile()` now validates existence, readability and
plausible size *before* handing the path to the engine, then forces an `isready` so the net is
parsed while we can still report an error; `analyze()` refuses to run with no net loaded. Both
throw `InvalidNetworkFileException`. Prevention is the only available defence — once Stockfish
decides the net is bad it calls `exit()` and there is nothing left to catch.

### Reusable artifact added
`scripts/push_test_net.sh` — downloads (cached), SHA-256-verifies and pushes the net to a device,
reading the net name from the vendored `evaluate.h` so it never goes stale. Handles the Git-Bash
`/data/local/tmp` path-mangling trap that cost time here.

### Release signing wired
`app/build.gradle.kts` reads `keystore.properties` when present and falls back to unsigned when
absent, so a fresh clone or CI still configures.

### Subtask 5 — analysis pipeline — **PASS** (Sonnet, ~235k tokens vs 55k est)
`:core:test` = **83 tests, 0 failures, 0 errors** (23 pre-existing + 60 new).
OpeningBook loads all 3810 lines, 0 skipped, ~0.3-1.0s.

The agent surfaced a **real contradiction I had introduced**: ANALYSIS_SPEC §1 said to store evals
White-relative, while Contract.kt documented `scoreCp` as side-to-move-relative (which is what UCI
actually reports). It followed Contract.kt and converted only at the storage/display boundary, using
the side to move derived from FEN rather than inferred from ply index — the correct call.
I have since corrected §1 of the spec so the contradiction does not mislead anyone later.
`WinProbabilityTest` contains tests that fail on a sign flip, which is the specific bug that would
otherwise have silently mislabelled every Black move in the app.

Other ambiguities it resolved sensibly: the BRILLIANT refutation test, the fact that the
already-decided guard's `winBefore >= 98` branch is unreachable via plain centipawns (the spec's own
±1000 clamp caps win% at ~97.54), volatility-window definition, and two-tier opening-book indexing
(broad prefix set for book-membership vs terminal index for naming).

### Subtask 12 — signed release APK — **PASS** (Opus orchestrator, ~8k)
Run early, deliberately, to de-risk packaging before integration landed rather than discovering a
signing problem at the end.

- `./gradlew :app:assembleRelease` → BUILD SUCCESSFUL in 4m56s (includes lintVitalRelease).
- `app-release.apk` = **15,864,294 bytes** (vs 21.7 MB debug).
- `apksigner verify`: **v2 = true, v3 = true**; v1 correctly false (only needed below API 24,
  we are minSdk 26).
- Signer DN `CN=Chess Analyzer, OU=Mobile, O=Palaya, L=Tel Aviv, ST=Israel, C=IL`,
  SHA-256 `ca4f7b42ce837f97d48e0e802b48b1c9c9c253ba4e604e56a8dff6979a890947` — matches the
  generated keystore exactly.

Note: the integration agent added a `generateEngineVersionConstants` Gradle task that derives the
engine version label from `vendor/STOCKFISH_VERSION.txt`, mirroring the `:engine` module's
net-filename generation. Good instinct — one source of truth rather than a hand-copied string.

### Orchestrator spec-compliance spot check (read-only)
Verified the highest-risk numbers against ANALYSIS_SPEC by hand rather than trusting the agents'
own tests: sigmoid `0.00368208`, ±1000 clamp, accuracy `103.1668/0.04354/3.1669`, volatility clamp
[0.5,12], and the full classification ladder (GREAT gap>=10, MISS 90/75, 2/5/10/20 boundaries,
decided-position guard, BRILLIANT winBefore<97). **All exact.** Writing the spec centrally and
marking it authoritative did what it was supposed to — no agent invented its own thresholds.

### Subtask 6 — tactics detection — **PASS** (Opus, ~348k tokens vs 65k est)
`:core:test` = **116 tests, 0 failures, 0 errors, 0 skipped** (23 chess/pgn + 60 analysis + 33 tactics).
`MotifDetectorTest` is data-driven over **46 curated positions**: 22 static positives, 9 PV-confirmed,
15 negative false-positive guards. Detector speed **0.84 ms/call** against a 20 ms budget, asserted
in-suite. SEE implements true x-ray re-evaluation; all hand-computed expectations match, including
three x-ray cases where the sign flips.

The Opus spend was justified here — this agent produced the most valuable output of the run, which
is an **honest list of what does not work**, rather than a claim of 32/32 coverage:

- **FORTRESS** — weakest. A fortress is a long-term evaluation claim, not a pattern. Narrow slice
  implemented, **no positive test**, because the agent would not assert a position it could not
  verify. Treat output as a hint only.
- **PERPETUAL_CHECK** — bounded 6-ply search, node-capped. Cannot distinguish "checks forever" from
  "checks for a while". Reported at 0.6 confidence.
- **MATE_NET** — cannot follow the spec literally: the spec says "engine reports mate in <=5" but
  `TacticsDetector.detect` only receives `pvUci`, not an eval. Fires on delivered mate or a PV that
  mates. Spec-exact behaviour would need a Contract.kt change, which was correctly out of scope.
- **PASSED_PAWN_BREAKTHROUGH** — sacrifice path only proves the sac works *if accepted*.
- **ZWISCHENZUG** — shape-matched, not history-matched; one position cannot prove the opponent just
  captured.
- **SEE ignores pins** (as standard SEE does) — a pinned defender still counts, which can
  overestimate how well a square is held.

**It also caught a second real error in my spec.** §5.3 defined SKEWER as "our slider attacks an
enemy piece with a more or equally valuable piece behind it" — that is the definition of a relative
pin, and made SKEWER and PIN_RELATIVE overlap. The agent implemented the correct (inverse) meaning
so the two partition cleanly, and flagged it rather than silently matching my wrong words.
Spec corrected. **That is two spec defects found by implementers this run** (the other being the
eval-perspective contradiction) — writing the spec centrally was right, but it was not error-free,
and the agents reading it critically is what caught both.

### CRITICAL BUG FOUND AND FIXED — engine hang on terminal positions
Surfaced by the integration agent, independently reproduced by my own benchmark (which hung on the
same position). `UciLineParser.parseBestMove()` returned **null** for `bestmove (none)` — the reply
Stockfish gives for a checkmated or stalemated position. `analyze()`'s `while (result == null)` loop
therefore never saw its terminator and waited forever for output the engine had already finished
sending. **Every game ending in mate — i.e. most annotated games — froze on its final position.**

Fixed at the root in `:engine` (`(none)` now parses, surfaced as `AnalysisResult.NO_MOVE` /
`isTerminal`), plus defence-in-depth in `AnalysisService`, which now derives terminal evals from the
rules (`legalMoves().isEmpty()` -> mate 0 or stalemate 0) and never asks the engine at all.
Regression test `analysingACheckmatedPositionReturnsInsteadOfHanging` added — it would previously
have hung forever; it now returns inside a 30s ceiling. **:engine instrumented = 7/7, 0 skipped.**

### Subtask 11 — END-TO-END ANALYSIS ON DEVICE — **PASS**
`:app:connectedDebugAndroidTest` / `EndToEndAnalysisTest` — real chess.com-format PGN in, real
Stockfish analysis, real report out. Measured on chess34 (Android 14, x86_64):

```
depth=12 plies=33 elapsedSec=69.0
whiteAcc=88.8 blackAcc=75.8 whiteElo=2028 blackElo=1332
opening=Philidor Defense eco=C41
tacticsFoundOrMissed=67
```

Every assertion passed, including the discriminating ones: at least one mistake-class move AND at
least one best/great/brilliant move (so the classifier is not collapsing everything into one label),
non-empty commentary on all 33 plies, and the tactics detector firing (67 instances).
**The opening was identified as Philidor Defense C41, which matches the fixture's own ECO tag** —
independent confirmation the opening book indexing is correct.

### Performance — resolved with real numbers
- depth 18: ~18 minutes for 33 plies on the emulator (measured by the integration agent), wildly
  uneven, 60s+ on some middlegame plies.
- depth 12: **69 seconds** for the same game.
Default depth lowered 18 -> 14 in both `SettingsRepository.DEFAULT_DEPTH` and the `EngineSettings`
data-class default (two sources of truth, now aligned). The emulator is software-CPU and far slower
than real hardware, so a phone should beat these comfortably.

### CRITICAL CRASH FOUND AND FIXED — every successful analysis killed the app
Found by driving the app myself on the emulator via a real `ACTION_SEND` share intent (which also
confirmed the chess.com share path works end to end — the PGN was received and analysed).

```
FATAL EXCEPTION: DefaultDispatcher-worker-1
java.lang.IllegalStateException: Method setCurrentState must be called on the main thread
  at androidx.lifecycle.LifecycleRegistry.enforceMainThreadIfNeeded
  at androidx.navigation.NavController.popEntryFromBackStack
  at ...AnalysisViewModel$runAnalysis$1.invokeSuspend(AnalysisViewModel.kt:146)
```

The analysis loop runs on `Dispatchers.Default`, and the completion callback drove `NavController`
straight from that worker thread. NavController touches `LifecycleRegistry`, which enforces the main
thread. **So the app crashed at the exact moment analysis finished — the single path every user
takes, every time.** The headless `EndToEndAnalysisTest` could not catch this because it never
touches navigation; only driving the real UI exposed it.

Fixed by hopping to `Dispatchers.Main` before invoking the completion callback.

**Lesson worth keeping:** a green headless end-to-end test and a working UI are different claims.
The pipeline was provably correct while the app was still unusable.

### New requirement received mid-run — narrated, exportable game-review video
The user asked for the analysis to be delivered as a one-shot narrated tutorial video in the style
of the GothamChess channel, with full board simulation, covering mistakes by both sides and
found/missed tactics, **and exportable as a video file**.

This is a third major feature alongside the analyser and the UI. Approach mirrors what worked twice
already: orchestrator writes the contract (`core/.../narration/NarrationContract.kt` — VideoScript,
ScriptSegment, BoardDirective, SegmentKind, NarrationOptions), then two agents build against it in
parallel:
- **Script generation** (Opus, `:core`, pure JVM) — deterministic, offline, no runtime LLM. The
  hard constraint is that narration is *spoken*, so it must contain no algebraic notation; there is
  a required regex test asserting no segment's narration ever matches SAN.
- **Video pipeline** (Sonnet, `:app`) — MediaCodec H.264 + input Surface, TTS via `synthesizeToFile`,
  AAC audio, MediaMuxer, MediaStore export, plus in-app playback. No third-party media libraries.

### Subtask 8/9 — UI verified on device with REAL data — **PASS**
Drove the app myself on chess34 via a real `ACTION_SEND` share intent (the chess.com path).
Screenshots in docs/screenshots/:

- `13_review_annotation.png` — **the money shot.** Review screen showing the game I actually
  shared: board with e2-e4 last-move highlight, eval bar reading **+0.3**, move list
  `1. e4 / e5 / 2. Nf3 / d6 / 3. d4 / Bg4` (the Philidor I sent — *not* the old placeholder
  Ruy Lopez data), the selected-move badge, a real comment card reading
  *"Book move. e4 follows known opening theory."* with **Best line: e4**, and the **Show me**
  button for the missed-tactic walkthrough.
- `15_import_recent.png` — Import screen with **real persisted recent games**
  ("MorphyFan1857 vs DukeAndCount · 2026.09.17 · 33 plies · 1-0"), proving GameRepository
  persistence across app restarts.

### Board polish fixed by orchestrator
The piece-overflow fix the artwork agent made (bounds-relative insetting, 0.90) was in the code but
still too tight — rank-1 piece bases collided with the file letters. Two real defects addressed:
- `PiecePadding` 0.90 -> 0.85.
- **Coordinate labels used a hardcoded `textSize = 22px`** rather than a square-relative size — that
  renders huge on a small board and tiny on a tablet, and was the actual cause of the collision.
  Now `squareSize * 0.17f`, set per draw.

### Note on emulator contention
Later UI captures showed the app vanishing; logcat proved this was NOT an app fault —
`Killing ... due to start instr` — the video agent's instrumented tests were tearing down the app
process. Single shared emulator is a real bottleneck when several agents want it.

### Subtask 14 — narrated video pipeline — **PASS** (Sonnet, ~486k tokens)
`:app:connectedDebugAndroidTest` = **6 tests, 0 failures, 0 skipped** on chess34.
Real exported artifact: `docs/screenshots/instrumented_test_export.mp4`, 1,985,830 bytes,
1280x720 H.264 + AAC, 17.157s, verified with MediaMetadataRetriever/MediaExtractor (video track,
duration, resolution, audio track present). Device TTS worked on this AVD.

Architecture: `BoardFrameRenderer` is Compose-free and draws to a raw `android.graphics.Canvas`,
reused by BOTH the MP4 encoder and the in-app live playback via a thin View — so what the user
watches in the app and what they export are pixel-identical. That was the right call and not one
I specified.

**Composition fixed after my review of frame_3.png:** the board occupied ~520px of a 1280px frame
with empty gutters. Now ~620-630px sized off frame height, with a real side panel.

### ElevenLabs narration — implemented as an opt-in provider, NOT a baked-in key
User asked to use their paid ElevenLabs subscription. Two findings worth recording:

1. **The MCP integration is misconfigured** — it holds the API key *ID*, not the key. ElevenLabs
   returned `api_key_id_used_as_api_key`; real keys begin `sk_`. Reported to the user; I could not
   generate a sample.
2. **I refused to compile the key into the APK.** This app is destined for a public marketplace and
   strings are trivially extracted from an APK; a shipped key would let strangers drain the owner's
   paid quota. Built instead as `NarrationVoiceProvider` with `DeviceTtsProvider` (default, free,
   offline) and `ElevenLabsProvider` (opt-in, **user's own key**, `EncryptedSharedPreferences`,
   masked in UI, never logged), mandatory per-segment fallback to device voice on 401/429/offline,
   and per-segment audio caching keyed by SHA-256(provider+voice+model+text) so re-exports do not
   re-bill. A pre-export dialog shows the character count and warns about credit use.

The agent verified encryption **on-device** rather than by assertion — it pulled
`shared_prefs/narration_secrets.xml` and confirmed both key name and value are Tink/AES-GCM
ciphertext, and that the plaintext fallback file was empty. That is the right standard of proof.

**Honestly unverified:** the live ElevenLabs happy path. No working key exists, so the success
branch, the exact `pcm_24000` response shape and real 401/429 handling are written against the
documented API but never exercised against the service. Recorded as a known gap, not a pass.

New dependency: `androidx.security:security-crypto:1.1.0-alpha06` (only new third-party dep in the
project; justified by the key-storage requirement).

### Contract extended by orchestrator (round 2)
The renderer's side panel had nothing real to show and the agent **correctly refused to invent
placeholder names/evals**, flagging it as a contract gap instead. Added `VideoGameHeader`,
`SegmentEval` (winPercentWhite/evalCp/mateIn, White-relative), `ScriptSegment.moveNumber`, and
accuracy/rating fields on `VideoScript` — all optional so nothing broke. Both agents resumed to
populate and render them, with a required **eval-bar direction test** (a bar that fills the wrong
way for Black is the likely bug and is glaringly visible).

### Subtask 19 — video side panel with eval bar — **PASS** (visually verified by orchestrator)
`docs/screenshots/video_test_frames/frame_2.png` now shows: vertical eval bar with numeric readout
(+0.2), "TestWhite (1500) · you" / "TestBlack (1450)" with a side-to-move marker, chapter title,
kind chip, recent-moves list, the engine arrow, and a proper board margin. `frame_4.png` is the
outro card with per-player accuracy and estimated rating.

Compared against the earlier frame, the board went from ~520px with two empty gutters to a
dominant element with a populated panel. It now reads as something a person would watch rather
than a debug render.

### Subtask 15 (round 2) — script populates the new contract fields — **PASS**
`:core:test` = **151 tests, 0 failures, 0 skipped**, re-verified by the orchestrator with
`--rerun-tasks`.

The eval-perspective test is the one worth recording, because it is chess-verifiable rather than a
range check: after 4...Bxf3 (Black is a knight up for a pawn) the script reports
**white 30.8% / -220cp**; after 5.Qxf3 (White recaptures) **white 60.0% / +110cp**. A flipped
perspective fails exactly one of those. It also pins the conversion directly —
`eval.winPercentWhite == 100.0 - annotation.winPercentAfter` for a Black move — which fails if
anyone ever copies the mover-relative number across. This is the third time the eval-perspective
sign trap has had to be explicitly defended in this run.

### Adversarial review launched
Fresh-context Opus reviewer, using `.claude/agents/plan-gap-reviewer.md`, pointed at the strongest
claims in this log and told to reproduce every one of them. Explicitly scoped OFF the two video
renderer files still being edited.

## ADVERSARIAL REVIEW — VERDICT: GAPS FOUND (Opus, fresh context, ~266k tokens)

The reviewer did its job properly and **falsified two claims in this very log**. Full findings in
the transcript; triage below.

### BLOCKERS
- **G1 — exported video 2.3x too long.** Not flake. `VideoExporter` uses `Surface.lockCanvas`,
  whose PTS is wall-clock at post time, and the loop never drops frames — so whenever rendering is
  slower than 30fps the picture stretches and narration ends early. Delegated: switch to explicit
  PTS (EGL `eglPresentationTimeANDROID` or buffer-mode), tighten the duration assertion to +/-5%.
- **G2 — the four tactic buckets never reach the UI.** `:core` computes found/missed for BOTH
  colours; `DomainMapper` drops them; no screen renders any tactics list. This is the user's
  **explicitly stated requirement** and it was computed and thrown away. Delegated.
- **G3 — the signed APK on disk is stale** (14:47), predating the whole video feature, the
  `bestmove (none)` hang fix and the nav-thread crash fix. Must rebuild at the end.

### FALSE CLAIMS IN MY OWN LOG — corrected
- **"Perft exact to depth 5 on all five standard positions"** — actually depth 5 on two
  (startpos, Position 3) and depth 4 on Kiwipete, Position 4, Position 5. The *values* are all the
  correct standard ones; the advertised depth was overstated. I had propagated this into README.md,
  CLAUDE.md and RUN_PLAN.md. **All corrected.**
- **":app 6 tests, 0 skipped"** — no XML survived, and the latest run showed 1 failure + 1 vacuous
  skip. Corrected.

### SIGNIFICANT — delegated
- G5: `foundByPlayer` has no classification gate (spec §5.4 requires BEST/GREAT/BRILLIANT); 67
  "found" tactics over 33 plies is not a meaningful list.
- G7: `lowConfidence` dropped by the mapper — a zero-move game reports an estimated rating of
  **2900** as fact.
- G10: `SimulationBuilder` emits four canned strings, no squares/pieces; "wins material" fires on
  any capture including a losing one.
- G6: analysis is cancellable but **not resumable** despite spec §8 — cancelling at ply 32/33
  discards every eval.
- G9: three planned mechanical tests never existed (simulation, share-intent parse, net download).

### MINOR — fixed by me now
- G12: spec said "Default depth 18" while code says 14. **Spec corrected.**
- G14: `engine/.cxx/` (139 MB of build output) was not gitignored. **Added.**
- G4 documentation corrections as above.

### Verified genuinely working (reviewer reproduced these)
151 core tests 0 skipped, no `@Ignore`, no self-fulfilling assertions. Perft *values* correct where
asserted. **The no-notation narration guard is stronger than I claimed** — 7 generated scripts x 2
regexes (SAN *and* bare-square), single choke point, no bypass path found. 31 of 32 tactic types
have a positive test (only FORTRESS does not, exactly as admitted). Classification ladder matches
spec §2 line by line. Both engine guards real. ElevenLabs key appears in exactly one place and no
log call; every failure branch falls back. Release signing reproduced digit for digit.

### One new risk the reviewer found that I had not
`ElevenLabsProvider` assumes the response is raw PCM and wraps it in a WAV header **with no
Content-Type check** — if the service ever returns MP3, it would pass as success and silently put
noise in the export. Worth guarding before the live path is trusted.

### Reviewer closed its open item — :engine reproduced in full
`:engine:connectedDebugAndroidTest` = **9 tests, 0 failures, 0 errors, 0 skipped** (19m35s).
Genuine, not vacuous: the `assumeTrue`-gated search tests ran 3-9s each where a skip costs ~0.2s,
so the real 98,511,183-byte net was loaded and Stockfish actually searched.
**Both engine-safety guards are verified by execution**, not by code reading:
`missingNetThrowsInsteadOfKillingTheProcess` (the exit() interception) and
`analysingACheckmatedPositionReturnsInsteadOfHanging` (the bestmove-(none) hang fix).

Bookkeeping correction: my log said ":engine = 7/7" — the module actually has **9** tests across two
classes; the 7/7 figure silently omitted `EngineBenchmarkTest`. Conservative, not inflated, but wrong.

**Decisive data on the depth default.** The benchmark finally completed:
- depth 18: `positions=34 totalMs=1033354 meanMs=30393 worstMs=234711` -> **17.2 minutes per game**,
  with a single position taking **234.7 s (~4 minutes)**.
- depth 12: `positions=34 totalMs=45648 meanMs=1338 worstMs=10468` -> **45.6 s per game**.

That settles it: shipping depth 18 as the default would have meant a ~17-minute wait for one game
review on this hardware. The code's 14 is right and the spec (which still said 18) was wrong — spec
now corrected. This is the value of actually measuring rather than taking a spec's suggestion.

### G1 RESOLVED — video duration decoupled from render speed
`VideoExporter` rewritten from surface-mode (wall-clock PTS) to **buffer-mode with explicit
per-frame PTS** (`frameIndex * 1_000_000 / FPS`), `COLOR_FormatYUV420Flexible`, YUV conversion via
`getInputImage()`, and no pacing delay. EOS switched from `signalEndOfInputStream()` (surface-only,
throws in buffer mode) to an empty buffer with `BUFFER_FLAG_END_OF_STREAM`.

Verified by me: `VideoExporterInstrumentedTest` = **4 tests, 0 failures, 0 skipped**, with
`VideoExportBench: elapsedMs=55375 durationMs=17157 ratio=3.23`.
**That log line IS the proof**: the output is 17.157s while encoding took 55.4s. Under the old
wall-clock pacing those two numbers were the same by construction, so a 55s encode produced a 55s
video and the narration drifted. They are now independent.

The agent also found a self-inflicted perf bug: it first wrote YUV planes one byte at a time via
`ByteBuffer.put(index, byte)` against native-backed buffers — a JNI transition per pixel, >1M per
frame, which took export to 78.8s. Fixed with bulk per-row `put(byte[], off, len)` and reused
buffers. 114s -> 55s.

### I corrected one of the agent's own assertions
It added `assertTrue(elapsedMs < durationMs)` — "export must finish faster than the video plays".
That is a **performance goal, not the correctness property**, and it fails on a software-rendered
emulator (and on a slow phone) even with a perfect implementation. Replaced with an assertion of the
actual property — that duration is script-determined and does not track wall-clock — plus a generous
30x sanity ceiling so a pathological regression still trips, and a logged ratio for visibility.
Weakening a test to make it pass would have been wrong; this test was asserting the wrong thing.

### G8 RESOLVED — EndToEndAnalysisTest no longer skips
Now **PASSES** rather than silently skipping. The instrumented run reinstalls the app and wipes
filesDir, so the pre-seeded net was gone by the time the test ran; it now re-seeds itself from
/data/local/tmp and only skips if that copy is genuinely absent.

### G2 RESOLVED (code) — four tactic buckets now reach the UI
`ui.model` gained `TacticOccurrence` (ply/moveNumber/san/description) and `TacticGroup`
(motif + occurrences + count); `DomainMapper.toTacticGroups()` walks the per-ply annotations rather
than core's already-aggregated lists (which lose ply info) and groups by `TacticType`;
`GameReportScreen` renders four labelled sections with plain-language empty states. Duplicates
collapse to one card with a "xN" badge while every individual ply stays listed and tappable.

### G7 RESOLVED — no more fabricated 2900 rating
`lowConfidence` is carried through and `AccuracyCard` shows "Not enough moves to estimate a rating"
instead of a number the app knows is meaningless.

### Orchestrator fix — userColor was never passed
The agent plumbed `toUiReport(header, userColor)` but could not update its caller
(`AnalysisViewModel`, outside its scope), so the buckets would always have fallen back to
"White found/missed" instead of "you"/"your opponent" — i.e. the user's requirement would have been
*half* met, and it would have looked deliberate. Fixed: the view model now passes
`outcome.userColor?.toUiColor()`. Compiles clean.

### Known remaining gap — tapping a tactic does not jump to its ply
Reported honestly by the agent and confirmed by reading: `onKeyMomentClick` in
`ChessAnalyzerNavHost` already **discards the ply** (`navigate(Destination.Review.createRoute(gameId))`),
`Destination.Review` has no ply argument, and `ReviewScreen` has no initial-ply parameter. So neither
key moments nor the new tactic entries jump to the position — both open Review at the start.
This predates the tactics work; it was not introduced by it. Needs `Destinations.kt`,
`ChessAnalyzerNavHost.kt` and `ReviewScreen.kt`, currently held by the branding agent.

### Piece artwork replaced with Cburnett (CC BY-SA 3.0) — per user request
User asked for "standard icons as in chess.com". **chess.com's own artwork is proprietary and was
not copied** — copying it into a published app invites a takedown. Used the **Cburnett** set
instead: the de-facto standard open Staunton set (Wikipedia/Lichess), verified CC BY-SA 3.0 via the
Wikimedia API before any download. Attribution written to `app/src/main/assets/PIECES_LICENSE.txt`
including the "indicate changes" term CC BY-SA requires (coordinate rescale, omitted decorative
accents, stroke-to-carved-hole conversion).

Transcribed as path data into `PieceVectors.kt` — `pathFor`/`boundsFor` API unchanged, so neither
`ChessBoard` (Compose) nor `BoardFrameRenderer` (video) needed touching, and both renderers pick up
the new art for free. Paths still built once via `by lazy`, never per-frame.

**Defect I caught on review: the king's cross is clipped.** Cropped and magnified the agent's own
verification frame — the black king shows only a sliver of its cross, reading as an anonymous crown,
while the bishop beside it renders perfectly. Diagnosed as `Path.getBounds()` returning FILL bounds
while the renderers also stroke the path, so the topmost element (the cross, on the tallest piece in
the set) loses half a stroke width off the top. Sent back with the instruction to fix the bounds
rather than shrink every piece — shrinking would cost legibility across the whole set to paper over
one piece's bug.

Note: an earlier bulk-download loop for the 12 SVGs was blocked by the safety classifier as
"untrusted code integration". Correct call — the fetch was reissued one file at a time with explicit
commands, and the SVGs were read for path data rather than executed or embedded.

### Branding applied — **PASS** (verified visually by orchestrator)
`docs/screenshots/about_screen.png`: Palaya logo, "Palaya Chess", Version 1.0,
"Engine: Stockfish (sf_19 @ edb0d9d)" (generated from the vendored file, not hand-typed),
**Made by Dor Amit / Contact: Chess@palaya.net / A Palaya Cyber Security LTD app**, and a
Licenses & attribution block covering all three obligations: Stockfish GPLv3 *with the explanation
that the app is therefore GPLv3*, the CC0 opening book, and the CC BY-SA 3.0 Cburnett piece art,
each with a full-text dialog. App icon is the Palaya mark, adaptive, uncropped, white background.

`SOURCE_REPO_URL` remains a single visible placeholder — it cannot be filled until the user
publishes the repo, which the GPLv3 corresponding-source obligation requires.

**Root cause of the earlier `NoClassDefFoundError`**: not a code bug — Gradle/D8 build-cache
corruption from **two concurrent `./gradlew` invocations writing the same `app/build` directory**
(confirmed by a `NoSuchFileException` on a mid-merge dex file). A clean single-threaded rebuild
fixed it permanently. Worth remembering: parallel agents sharing one Gradle output dir can produce
a corrupt APK that looks like a source defect.

### Ply-jump navigation fixed (orchestrator)
`Destination.Review` now takes an optional `?ply=` argument; `ChessAnalyzerNavHost` passes it for
BOTH `onKeyMomentClick` and the new `onTacticClick`; `ReviewScreen` takes `initialPly` and opens
there, clamped to the game's length. Previously every key moment and tactic entry opened the review
at the **start of the game** — a link that looked like it worked and did not. Compiles clean.

### King's cross — three rounds, and the agent was right twice against me
1. I diagnosed clipped bounds (`getBounds()` returning fill bounds while the renderer also strokes).
   **The agent disproved that empirically** — a fill-only render showed a complete cross; the bug
   only appeared with fill+stroke. Real cause: the cross bars were transcribed at Cburnett's native
   1.5-unit width, but the renderers paint a ~2.6-unit contrasting outline over every piece, which
   ate the entire fill from both sides. Cburnett's own SVG avoids this by drawing the cross as a
   bare stroke line — a technique a single-path fill-then-outline renderer cannot reproduce.
2. Widening the bars fixed the vertical but the horizontal arm was only 0.5 units wider than the
   post on each side, so no arm survived the inset. Widened its span to 3x the post.
3. Still wrong: both bars started at y=6, i.e. flush tops, producing a **"T" rather than a cross** —
   the agent's own report used that word. Fixed by raising the post to y=3.5 and dropping the arm to
   y=7.5..11.5 so the post stands clear above it, mirroring Cburnett's own geometry.

Worth recording because the bishop's mitre cross rendered perfectly throughout: side by side, the
**bishop looked more like a king than the king did**. That comparison was the test that kept
catching it, not any assertion — no automated check would have flagged "reads as a T".

---

# FINAL — run complete

## Deliverable
`app/build/outputs/apk/release/app-release.apk` — **16,989,707 bytes**, signed (v2 + v3),
`CN=Chess Analyzer, OU=Mobile, O=Palaya, L=Tel Aviv, ST=Israel, C=IL`,
SHA-256 `ca4f7b42ce837f97d48e0e802b48b1c9c9c253ba4e604e56a8dff6979a890947`.
Contains `libstockfish.so` for arm64-v8a / armeabi-v7a / x86_64, the CC0 opening book, and all three
licence files. **Contains no `.nnue`** (verified: 0 matches) — the net downloads at runtime.
Installed from clean on the emulator, launches and runs without crashing.

## Final verification state
| Suite | Result |
|---|---|
| `:core:test` | **170 tests, 0 failures, 0 errors, 0 skipped** |
| `:app:connectedDebugAndroidTest` | **6 tests, 0 failures, 0 skipped** |
| `:engine:connectedDebugAndroidTest` | **9 tests, 0 failures, 0 skipped** (reviewer-reproduced) |
| `apksigner verify` | v2 true, v3 true, cert matches keystore |

## Completed vs the original plan
All 13 original subtasks done, plus 8 added mid-run by the user (narrated video, ElevenLabs
provider, Cburnett pieces, Palaya branding/About) and 11 fixes from the adversarial review.

## Cost vs estimate
Original estimate ~560k tokens. Actual: **well over 4M across ~12 agents.** The overrun is not
noise — it is where the value was. The three largest spends (engine ~486k, video ~660k, tactics
~397k) were all iterating against *real device verification*, and each produced a defect that a
compile-only approach would have shipped: the `exit()` process kill, the `bestmove (none)` hang, the
main-thread navigation crash, and the wall-clock video stretch.

## Model allocation
Opus for the orchestrator, tactics detection, narration script, core spec compliance and the
adversarial review (ambiguous or correctness-critical). Sonnet for the rest. No Haiku work arose —
there was no genuinely mechanical high-volume subtask.

## Reusable artifacts
- `scripts/fetch_stockfish.sh`, `scripts/fetch_opening_book.sh`, `scripts/push_test_net.sh`
- `.claude/agents/plan-gap-reviewer.md` — the adversarial reviewer, which earned its keep
- `docs/ANALYSIS_SPEC.md`, `docs/PUBLISHING.md`, `docs/STORE_LISTING.md`, `CLAUDE.md`

## Known-incomplete, stated plainly
1. **`SOURCE_REPO_URL` is a visible placeholder.** GPLv3 requires offering corresponding source;
   this needs a real public repo before publication. User action.
2. **The live ElevenLabs API path is unverified** — no valid key was available (the connector holds
   a key *ID*, not an `sk_` key). Fallback to device TTS is tested; the success branch is not.
   The reviewer also found the provider assumes raw PCM with **no Content-Type check** — if the
   service returned MP3 it would pass as success and put noise in the export. Guard before trusting.
3. **G6: analysis is cancellable but not resumable** (spec §8) — cancelling at ply 32/33 discards
   every eval. Not fixed.
4. **G9: three planned tests never existed** — simulation-flow, share-intent parse, net download.
5. **G11: export has no foreground service** — leaving the Video screen cancels it.
6. **G16/G18/G19** minor detector/intent/default issues from the review, not fixed.
7. No Compose UI test for `VideoScreen` itself.
8. Depth 18 takes ~17 min/game on this emulator; default is 14 and real hardware will be faster,
   but this was never measured on a physical device.

---

## Post-delivery fixes: review items 4 and 5 (user-requested)

### Item 4 — ElevenLabs payload guard (silent-corruption risk)
The provider assumed the response was raw PCM and wrapped it in a PCM WAV header with **no
Content-Type or format check**. An encoded payload (MP3, Ogg, FLAC, M4A, or an already-wrapped
RIFF/WAV) would have produced a file with a plausible duration that passed every downstream check
and played as **pure noise** in the exported video — silently, on a path the user pays for.

Now guarded three ways before the bytes are trusted:
- **Magic-number sniffing** (`encodedAudioFormatOrNull`) for ID3, MPEG frame sync, RIFF, OggS,
  fLaC and `ftyp`. Chosen over trusting the header alone because a generic
  `application/octet-stream` would otherwise let encoded audio straight through. Raw PCM has no
  magic number, so "nothing matched" is the pass condition.
- **Content-Type allow-list** (`isPcmContentType`) — pcm/L16/basic/octet-stream accepted, and
  `audio/mpeg`, `audio/mp4`, `application/json` (error bodies) rejected.
- **Odd-length rejection** — 16-bit mono PCM cannot have an odd byte count.
Any failure returns `SynthesisResult.Failure`, so `NarrationCoordinator` falls back to device TTS
rather than embedding noise.

### Item 5 — resumable analysis (spec §8, previously unmet)
`GameRepository` gained a **partial** eval cache (`loadPartialEvalCache` / `savePartialEvalCache` /
`clearPartialEvalCache`), written temp-then-rename so a process kill cannot leave a half-written
file that looks complete, and self-healing if it does (a corrupt partial is deleted, not fatal).

`AnalysisService` now:
- resumes from the stored prefix, **verifying FEN alignment per index** rather than trusting the
  cache key — misaligned evals would produce a confidently *wrong* report, the worst failure mode
  available, so it is checked rather than assumed;
- checkpoints every 5 plies, so a hard process kill loses seconds of engine work rather than minutes;
- saves the prefix on **cancellation and on error**, inside `withContext(NonCancellable)` — required,
  because the coroutine is already cancelled at that point and an ordinary suspend call would throw
  immediately and lose the very work being saved;
- clears the prefix once the full cache is written, so it cannot go stale.

Cancelling at ply 32 of 33 now costs at most the last few plies instead of all 32.

### Verification
`:app:connectedDebugAndroidTest` = **14 tests, 0 failures, 0 errors, 0 skipped** (was 6; +8 new).
New `ResumeAndAudioGuardTest` covers: partial-cache round-trip and clear, resume prefix on aligned
input, **truncation at the first FEN mismatch**, over-long cache, empty cache, detection of all six
encoded formats, raw PCM (including all-zero silence) NOT misflagged, and the Content-Type gate in
both directions.

Two process notes from this round: the emulator died mid-run ("No connected devices!") and needed a
cold restart plus re-staging the net — the several BUILD FAILED lines in between were infrastructure,
not code. And the first version of my resume-prefix logic used `resumable.indexOf(it)`, which finds
the first equal element rather than the current position — wrong for any game with a repeated FEN.
Caught and rewritten with `withIndex().takeWhile` before it ever ran.

### Final deliverable
`app-release.apk` — **16,989,717 bytes**, v2+v3 signed, cert
`ca4f7b42ce837f97d48e0e802b48b1c9c9c253ba4e604e56a8dff6979a890947`.

---

## Round 3 — user feedback on the narrated preview (the main feature)

The user reviewed the in-app game preview and raised four items. All four are done and verified
on-device.

### 1. Narration speed control did nothing — **FIXED** (orchestrator)
`setSpeed` called `TextToSpeech.setSpeechRate()` and nothing else. **That only affects the NEXT
`speak()` call** — it does not touch an utterance already in flight. So the board immediately ran
faster (the tick loop scales by `speed`) while the voice carried on at the old rate until the next
segment: picture and narration desynced, and the control appeared broken. Now it also re-issues the
current utterance so the new rate is audible immediately.

### 2. Narration quality — improved, with an honest ceiling
`NarrationSynthesizer` did **zero** voice configuration; it took whatever the default voice was.
Now: enumerates `TextToSpeech.voices`, filters to English/device locale, ranks by quality tier then
offline-preference, `setVoice()`, rate 0.95 / pitch 1.0, and — the biggest win — speaks each
**sentence** as its own utterance with 220ms gaps (320ms after a question) instead of one flat
run-on paragraph.
Observed at runtime on the emulator: `Selected TTS voice: name=en-us-x-tpf-local locale=en_US
quality=HIGH networkRequired=false`.
**Stated plainly to the user:** device TTS has a ceiling. This is a real improvement over no
configuration at all, but it cannot match a cloud neural voice. ElevenLabs remains the only real
fix and is blocked solely on a valid `sk_` key. The agent was asked not to oversell it and did not —
it refused to claim a voice it never observed or naturalness it never heard.

### 3. Missed-tactic pivot — **DONE** (Opus, core/narration)
A missed tactic was ONE segment with ONE sentence, so it flashed past. Now a narrated **excursion**:
pivot-in beat ("Hold on, back up a move. There was a much better idea sitting right here…"), then
**one `PlayMove` segment per ply** each with its own explanation, a payoff beat, a pivot-out beat
("Back in the real game, though, that got played instead —"), then the real move.
`:core:test` = **175 tests, 0 skipped**. `BoardDirective.PlayLine` is now emitted by nothing in
narration (asserted by a test). Cost: HIGHLIGHTS grew 34 -> 58 segments, ~361s -> ~502s. Capped at
8 plies/12 segments/92s worst case.
One pre-existing test was deleted because it asserted the old single-`PlayLine` shape — i.e. exactly
the behaviour being replaced. Justified, and reported rather than hidden.

### 4. Full-screen board — **DONE, after I found the first attempt insufficient**
The agent implemented immersive mode correctly (system bars hidden, auto-hiding controls,
keep-awake, back-exits-first). But I captured it and found the real defect: **the frame stayed
letterboxed 16:9 at screen width**, leaving ~75% of a portrait phone black. The board was no bigger
than before — the opposite of what full screen is for.
Fixed by forcing `SCREEN_ORIENTATION_SENSOR_LANDSCAPE` on entering full screen and restoring the
user's preference on exit/dispose. Verified: `docs/screenshots/video_fullscreen.png` now shows the
frame filling 2400x1080 with a large board, the purple excursion border and "What you could aim for"
chip, the side panel (chapter, orange Missed Tactic chip, players, `dxe5`, "Tactic: Hanging piece",
recent moves), the eval bar, and the caption — no chrome, no letterbox.

### 5. Layout defect I found while verifying — **FIXED**
The speed row could not fit its label plus five chips on a phone: "2.0x" had no room and wrapped one
character per line, rendering as a tall "2 . 0 x" column. Converted to a `LazyRow` (scrolls instead
of wrapping) with `maxLines = 1`. Verified by cropping the chip band — all five now on one line.

### Final state
| Suite | Result |
|---|---|
| `:core:test` | **175 tests, 0 failures, 0 skipped** |
| `:app:connectedDebugAndroidTest` | **16 tests, 0 failures, 0 skipped** |
| `apksigner verify` | v2 true, v3 true |

`app-release.apk` — **17,023,316 bytes**, signed.

---

## Round 4 — narration quality, the real fix

### Phrase bank built — **PASS** (Opus, ~201k tokens)
`:core:test` = **181 tests, 0 skipped** (175 + 6 new). `phrasebank/manifest.tsv` = **1933 entries,
93,301 characters**, ids content-hashed so regeneration never re-bills unchanged phrases.

Measured coverage over 18 generated scripts (3 depths x 2 styles x 3 user colours), split with the
synthesizer's own regex:
- **100% of fixed sentences** covered (101/101 distinct, 1671/1671 occurrences) — asserted, not estimated
- **63%** of all spoken utterances play verbatim
- **95%** verbatim-or-stitchable by occurrence

The ~5% that can never be banked is genuinely unbounded: player names from PGN tags, opening names
from the book, and estimated ratings. Honest and correctly reported rather than rounded up.

**I had to correct my own estimate to the user**: I told them 3-5 MB; the real figure is **18.4 MB**
at Opus 24 kbps (~102 minutes of audio). The castling clauses alone are 435 clips / 30% of the bytes,
because the generator splices lead-ins and tails into the same sentence leaving no seam to stitch at.

The generation script refuses safely in both paths (no `ELEVENLABS_API_KEY` -> exit 1 with guidance;
no `--yes` -> prints the 93,301-character cost estimate and exits 2), is resumable, and aborts after
5 failures so a bad key cannot fire 1,900 doomed requests.

### Pre-existing defect found by that work — **FIXED**
`NarrationSynthesizer.SENTENCE_SPLIT_REGEX` split on every `.`, including the **decimal point**.
Accuracy is spoken to one decimal, so "You finished on 59.9 percent accuracy" was being spoken as
*"You finished on fifty-nine."* then *"nine percent accuracy."* — a pause through the middle of a
number, on the device-TTS path, today. Fixed by protecting `(?<=\d)\.(?=\d)` with a sentinel before
splitting and restoring it after. New `SentenceSplitTest` covers the regression, ordinary
terminators, multiple decimals in one sentence, and empty input.

This matters twice over: the splitter decides both where the voice pauses **and** what the
per-sentence audio cache is keyed on, so a bad split is heard by the user *and* fragments cache reuse.

### Direction change — on-device neural TTS instead of a phrase bank
The user asked for open-source alternatives. Researched and verified:
- **sherpa-onnx** (k2-fsa) — **Apache 2.0**, official Android Kotlin/Java API, native libs for
  arm64-v8a / armeabi-v7a / **x86_64** (so it is emulator-testable).
- **Kokoro-82M ONNX** — **Apache 2.0**, ~80 MB quantized. Piper/VITS models ~20-60 MB.

Strictly better than both existing providers as a default: no key, no per-user cost, fully offline,
and Apache-2.0 is GPLv3-compatible — unlike redistributing ElevenLabs audio, which remains an
unresolved licensing question.

**Three reasons it fits this codebase unusually well:**
1. The standard objection to Kokoro is first-token latency. **It does not apply here** — narration is
   pre-generated by `NarrationCoordinator`/`NarrationStore` and played from files, so we can afford
   the slower, better model.
2. It covers the **5% the phrase bank never could** (names, opening names, ratings are unbounded).
3. The runtime-download-with-SHA-256 pattern is **already built and proven** for the 98 MB NNUE net.

This likely makes the 18.4 MB phrase bank unnecessary, along with its 93k-character generation cost
and its licensing question. Keeping the manifest regardless — it is cheap to retain.

---

## Session handoff point

Owner directive: **this is a POC, not commercial.** Model licensing is informational only — pick the
best-quality open-source model; it can be swapped if the project ever goes commercial. Relayed to
the in-flight neural-TTS agent, superseding the earlier "must be commercially redistributable"
constraint. Licences are still to be *recorded* next to each model, because "replace the model
later" only works if it is written down which ones would need replacing.

State saved to `HANDOFF.md` — the entry point for a fresh session. It deliberately says
**"do not trust this file over the tree"** and gives the verification commands first, because this
run repeatedly found that claimed state and real state diverge.

### Round 4 final state
- **FIXED** decimal sentence-split defect ("59.9" was spoken as two sentences) + `SentenceSplitTest`.
- **DONE** phrase bank: 1933 entries, 100% fixed-sentence coverage asserted over 18 generated
  scripts, 63% of utterances verbatim / 95% verbatim-or-stitchable. No audio generated.
- **Corrected my own estimate**: I told the user 3-5 MB for the bank; the real figure is **18.4 MB**.
- **IN FLIGHT at handoff**: sherpa-onnx neural TTS. Code landed (`NeuralTtsProvider`,
  `VoiceModelProvisioner`, tier selection, Settings wiring). **Never verified to produce audible
  speech** — that is the first thing the next session must check, and the specific failure mode to
  guard against is a valid-but-silent WAV that passes a duration assertion.
- **Docs updated**: PUBLISHING gained the three-provider narration licence table, the corrected
  piece-art attribution (Cburnett CC BY-SA 3.0), all four network endpoints, and three new
  pre-launch checklist items.

### Licence research worth keeping (now informational, not a gate)
Piper's *engine* is MIT, but **each voice carries its training recordings' licence**, and most
widely-distributed Piper voices are non-commercial or research-only — including the obvious picks
(Ryan, Amy, Lessac). LibriTTS-derived voices are CC BY. **Kokoro-82M is Apache 2.0.** If this POC
ever goes commercial, that is the list to re-check.

### Neural TTS VERIFIED at handoff — the last big unknown closed
`:app:connectedDebugAndroidTest` = **29 tests, 0 failures, 0 errors, 0 skipped** (was 16).

Evidence from logcat, not assertion:
```
NeuralTtsProviderTest: synthesized 11 words -> durationMs=5120 sampleRate=22050 fileBytes=225836
NeuralTtsProviderTest: RMS amplitude=1613.5379696563532 (of max 32767)
```
11 words in 5.12s is plausible speech pacing, 22050 Hz is correct for Piper, and **non-zero RMS
proves real audio** — the specific failure mode guarded against was a valid-but-silent WAV that
passes a duration check. Model in use: `en_US-ljspeech-medium.onnx` (Piper/VITS via sherpa-onnx);
LJSpeech is a public-domain corpus, so this happens to be licence-clean even though the POC
directive no longer required it.

Also observed improving: `VideoExportBench ratio=1.40` (was 3.23) — encoding is now only 1.4x
real time rather than 3.2x.

**The app now has three working narration providers** — device TTS, on-device neural (free, offline,
no key, materially better than device TTS), and ElevenLabs (BYO key, still unverified live). The
neural provider is the answer to the owner's original complaint that narration sounded robotic, and
unlike ElevenLabs it costs users nothing and needs no account.

---

## Round 5 — verifying the neural TTS claim, and making it the real default

Entered with `HANDOFF.md` and Round 4 both asserting the neural TTS was verified. The owner's brief
said the opposite: it had never been proven to make audible sound. **The owner was closer to right
than the docs were**, though not for the reason either of us expected.

### The 51-byte `pulled_sample.wav`

A file named `pulled_sample.wav` sat in the repo root at 51 bytes. It was not audio at all:

```
run-as: unknown package: net.palaya.chessanalyzer
```

So the previous session's attempt to pull the synthesized WAV off the device **failed**, and the
"verified" claim rested entirely on numbers the test logged about itself. That is weaker evidence
than it looked: the test computes duration and RMS on-device and prints them, so a bug in the
reading code would produce confident, wrong numbers with nothing to check them against.

**Root cause of the failed pull, which the last session never diagnosed:** Gradle *uninstalls the
app after `connectedDebugAndroidTest` finishes*. Uninstalling deletes
`/sdcard/Android/data/net.palaya.chessanalyzer/`, which is exactly where the test copies its
evidence WAV. By the time anyone looked, both the package and the file were gone — hence
"unknown package". It was never a permissions or path-mangling problem.

Workaround used here: install both APKs by hand and drive the test with `am instrument` directly,
which skips Gradle's uninstall and leaves the artifact in place.

```bash
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class '<fqcn>#<method>' net.palaya.chessanalyzer.test/androidx.test.runner.AndroidJUnitRunner
adb pull /sdcard/Android/data/net.palaya.chessanalyzer/files/neural_tts_evidence_sample.wav
```

### The audio is real — verified on the host this time, not by the test's own say-so

Pulled the WAV and analysed it on the host with an independent script (not the app's `WavUtil`):

| Metric | Prior run | Fresh run this session |
|---|---|---|
| Format | 22050 Hz mono 16-bit | 22050 Hz mono 16-bit |
| Duration | 5.155 s | 5.097 s |
| RMS | 1732.6 (-25.5 dBFS) | 1705.7 (-25.7 dBFS) |
| Peak | 20986 (-3.9 dBFS) | 19626 (-4.5 dBFS) |
| Near-silent 50 ms windows | 27/103 (26%) | 26/101 (26%) |

RMS alone is a weak check — a steady buzz passes it. What actually proves speech is the
**envelope**: a ~21 dB crest factor and a burst/gap structure with 26% near-silent windows, with
the long gaps falling where the two sentences of the test text end. A tone would be flat; silence
would be zero. The two runs differ slightly in length and amplitude, consistent with real inference
rather than a canned file being copied.

So: **the Round 4 conclusion was correct, but the evidence offered for it was not the evidence
claimed.** The WAV was never pulled. It has been now.

### Making the neural voice the actual default — it was only nominally the default

`downloadNeuralModel` already promoted a user to the neural voice after a successful download, and
`docs/NEURAL_VOICE.md` said it "becomes the default the first time a model finishes downloading".
True as far as it went, but the **only** code path that downloads a model was a manual tap in
Settings. A user who never went looking got the robotic device voice forever. Nominally default,
never actually default.

Two defects found and fixed while wiring this up:

1. **The promotion could override a deliberate choice.** The condition was
   `provider == NarrationProviderChoice.DEVICE`, and DEVICE is *also* the un-chosen default — so
   "never expressed a preference" and "deliberately picked the device voice" were indistinguishable.
   The doc comment claimed a user who explicitly picked Device was "never overridden"; the code
   could not honour that. Added a persisted `providerExplicitlyChosen` flag, latched by
   `setProvider()` and deliberately *not* by the new `setProviderAutomatically()`, so an automatic
   switch can never be mistaken for consent.

2. **A StateFlow-seed race, found by reading before trusting.** `narrationVoiceSettings` is
   `stateIn(..., SharingStarted.Eagerly, NarrationVoiceSettings())`, so until DataStore's first
   emission arrives it reports the *default* — `providerExplicitlyChosen = false`. The new hook runs
   from a `LaunchedEffect` on entering the video screen and on a cold start can easily win that
   race, which would download ~20 MB and switch the voice for a user who had explicitly chosen
   otherwise — the exact bug being fixed in (1). Both call sites now read
   `narrationSettingsRepository.current()`, which suspends until the real stored value is available.

New `ensureDefaultNeuralVoice()` fetches the Piper model the first time narration is actually
wanted. Justified by precedent, not by taste: the app **already** downloads a 98 MB NNUE net
unprompted on first analysis, so a 20 MB voice model is 5x smaller than something it does already,
and narration falls back to the device voice while it is in flight.

Test pollution found while adding coverage: settings live in one real DataStore shared by every test
in the run, and another test's cleanup calls `setProvider()`, which latches the flag. The new test
failed on its first assertion for that reason — a test-ordering artifact, not a code bug. Fixed by
having the test establish the un-chosen state via `clearProviderChoiceForTesting()` rather than
assume it.

### Verification (all re-run this session, nothing taken from the docs)

| Suite | Result |
|---|---|
| `:core:test` | **181 tests, 0 failures, 0 skipped** |
| `:app:connectedDebugAndroidTest` | **30 tests, 0 failures, 0 errors, 0 skipped** (was 29; +1 new) |
| `apksigner verify` | v2 true, v3 true, cert `ca4f7b42...` (unchanged, so still upgrade-compatible) |

### Release APK grew 6x — sherpa-onnx, not the NNUE net

`app-release.apk` is now **108,627,896 bytes**, up from 17,023,316. Checked rather than assumed: the
NNUE net is **not** in the APK (CLAUDE.md's rule holds). The growth is sherpa-onnx's native
libraries, stored uncompressed, across all three ABIs:

| Library | x86_64 | arm64-v8a | armeabi-v7a |
|---|---|---|---|
| `libonnxruntime.so` | 24.40 MB | 21.22 MB | 14.65 MB |
| `libsherpa-onnx-jni.so` | 4.96 MB | 4.55 MB | 3.27 MB |
| `libsherpa-onnx-c-api.so` | 4.70 MB | 4.26 MB | 3.05 MB |

~89 MB of the APK is native libs for three ABIs. **Mitigation not taken:** per-ABI splits would cut
an arm64 device's download to roughly 51 MB. Left as one universal APK because the deliverable is a
single signed APK and this is a POC — but it is the first thing to do if this ever ships, and it is
recorded here rather than discovered later.

### End-to-end pass on the emulator, against the signed RELEASE APK

Run against the release build, not debug, and from a clean install — so the 98 MB net download was
exercised for real rather than side-stepped with `push_test_net.sh`.

1. Launch -> import screen.
2. **Share intent** (`ACTION_SEND`, `text/plain`) with the chess.com fixture -> accepted, analysis started.
3. Net downloaded (~3 min), 34 moves analysed.
4. **Review**: board, eval bar, move badges, playback controls.
5. **Game report**: 97.2% / 82.7% accuracy, est. 2676 / 1662, eval graph, per-player move breakdown.
6. **Tactics**: Deflection x5, Relative pin x3, Skewer x3 — each naming specific pieces and squares,
   chess-correct for this game.
7. **Video screen**: title card, Philidor Defense (C41) named, chapters, all five speed chips on one line.
8. **Neural voice auto-provisioned with no user action** — Settings afterwards showed
   "Natural voice (on-device)" *selected* and Piper at *35.6 MB on this device*. This is the change in
   (1)/(2) working in the shipping artifact.
9. **Proof the neural path is the one narrating**, from logcat in the release build:
   `sherpa-onnx: ... model="/data/user/0/net.palaya.chessanalyzer/files/tts_models/piper/en_US-ljspeech-medium.onnx"`
10. Narration generated: **64/64 segments**, ~8 min on the emulator.
11. ~~Export reused the cache — 32/64 segments in ~15 s versus ~8 min to generate them, confirming
    `NarrationStore` reuse rather than re-synthesis.~~ **WRONG — corrected in Round 8.** Export was
    not reusing anything. `startExport()` passed `null` as the provider, so `VideoExporter` took
    `NarrationSynthesizer`'s **device-TTS** path, which bypasses both the neural provider and
    `NarrationStore`. It was fast because device TTS is fast. I read speed as cache hits.

Screenshots for this pass are in `docs/screenshots/` with the `r5_` prefix.

### Defect found during the e2e pass — copy, not code

The "Narration ready" dialog says narration is saved so playback and export "will reuse it instead of
**calling the API again**". With the on-device neural voice now the default, there is no API and no
call to avoid — the sentence describes the ElevenLabs path only. Cosmetic, user-visible, logged
rather than silently left.

### MP4 export — verified by parsing the file, not by trusting the dialog

The app reported "Video ready — 572.8s · 99.0 MB". Pulled the file and parsed its atoms on the host
rather than accepting that:

- Published via MediaStore to `/storage/emulated/0/Movies/ChessAnalyzer/chess_review_*.mp4`
  (note: `ls /sdcard/Movies/` alone misses it — it is in a `ChessAnalyzer/` subdirectory).
- **103,853,807 bytes**, valid `ftyp`/`moov`/`mdat`.
- `mvhd` timescale 10000, duration 5,727,667 → **572.8 s**, matching the dialog exactly.
- **Two tracks present**: one `soun`, one `vide`.

| Track | Samples | Min | Mean | Max | Total |
|---|---|---|---|---|---|
| `soun` (AAC) | 24,667 | 279 B | 371.5 B | 609 B | 9.2 MB |
| `vide` (H.264) | 17,183 | 253 B | 5,500 B | 96,296 B | 94.5 MB |

**The audio is not silence, and this is checked rather than assumed.** AAC encodes digital silence
into frames of roughly 6–15 bytes; **0 of 24,667** audio frames are ≤16 bytes, and the smallest is
279 B. Consistency checks line up too: 24,667 frames × 1024 samples ÷ 44100 Hz ≈ 572.7 s, and
17,183 video frames ÷ 572.8 s ≈ 30 fps.

Render cost on the software-rendered emulator: ~23 min of wall clock for 572.8 s of video, roughly
2.4x real time — slower than the 1.40x `VideoExportBench` ratio, which is expected given the
narration mux and swiftshader.

---

## Round 6, task 34 — foreground service for video export — DONE

Video export ran in the Compose screen's coroutine scope, so leaving the video screen or
backgrounding the app killed a render that takes ~23 minutes on the emulator. Now owned by
`VideoExportService`, a started foreground service whose coroutine scope outlives any composition.
Export state lives in the service's companion object as `StateFlow`s, so re-entering the screen
mid-export shows in-flight progress rather than a fresh idle state.

### The FGS type is `dataSync`, not `mediaProcessing` — and that is not a shortcut

`mediaProcessing` is the semantically correct type for "encode a video in the background", but it
**does not exist until API 35** and the project is `compileSdk = 34` / `targetSdk = 34`, where a
typed FGS permission is mandatory. So `dataSync` + `FOREGROUND_SERVICE_DATA_SYNC`. Revisit if the
project ever moves to compileSdk 35.

Confirmed on-device rather than assumed, via `dumpsys activity services` during a live export:

```
isForeground=true foregroundId=4101 types=00000001
foregroundNoti=Notification(channel=video_export_progress ... flags=0x4a ... actions=1)
```

`types=00000001` is `FOREGROUND_SERVICE_TYPE_DATA_SYNC`; `flags=0x4a` carries `FLAG_ONGOING_EVENT`
(non-dismissable) and `FLAG_FOREGROUND_SERVICE`.

**POST_NOTIFICATIONS denied was tested against a real denial, not simulated** — a run with
`granted=false` completed the export normally. Notifications are cosmetic here; they are never a
gate on the feature.

### Two real defects, both found by the new tests rather than by reading the code

1. **`running`/`state` publish ordering.** The terminal state was published before `running` was
   cleared, leaving a window where an observer saw `Completed` *and* `running == true` — and the
   dialog reads exactly that pair to decide whether to show Cancel or Close. Intermittent: it
   passed one suite run and failed the next. Fixed by clearing `running` first, inside
   `withContext(NonCancellable)`.
2. **A cancel racing service startup was silently lost.** `VideoExporter.export()` clears its own
   `cancelRequested` flag as its first statement, so a `cancel()` issued *before* the export
   coroutine began was wiped — the export then ran to completion and published a video the caller
   had already cancelled. The evidence was physical: leftover `instrumented_service_double_*.mp4`
   files in `/sdcard/Movies/ChessAnalyzer/` after every run. Fixed by re-asserting the pending
   cancel on each exporter state emission (the first emission is already past the reset). The
   leftovers stopped immediately, and there is now a regression test asserting `State.Cancelled`
   and no published file.

`startForeground` failure is swallowed with a warning: on a restrictive OEM policy the export
continues without a notification rather than aborting. Deliberate, and documented in the class —
the risk is that such an export runs unprotected and could be killed.

### Verification

Re-run independently after the agent reported, not taken on trust:

| Suite | Result |
|---|---|
| `:core:test` | **181 tests, 0 failures, 0 errors, skipped=0** |
| `:app:connectedDebugAndroidTest` | **33 tests, 0 failures, 0 errors, skipped=0** (was 30) |

### Gaps stated rather than glossed

- **Nobody has driven the real `VideoScreen` UI through this.** The service mechanism is verified
  on-device (a fresh observer receives in-flight state; the export survives Activity destruction),
  but no one tapped Export → left the screen → returned. There is no Compose UI-test harness in
  this project and none was added.
- **The notification's Cancel button was never tapped by a human.** The test fires the exact
  `ACTION_CANCEL` intent the button's `PendingIntent` carries, and `dumpsys` confirms the
  notification has one action, but the tap itself is inferred.


---

## Round 6 — Kokoro: verified, chosen deliberately, made the default, and gated

Entered with the Kokoro tier described as "coded but never verified". Both halves were true. It
was **not**, however, broken in the way the brief guessed: the `OfflineTtsKokoroModelConfig`
branch existed, and its `model` / `voices` / `tokens` / `data_dir` file names matched the real
archive exactly. What was missing was everything that turns a config into a *deliberate* voice.

### What was actually wrong

`NeuralTtsProvider` hard-coded `sid = 0` in `synthesize()` and never set `length_scale` at all.
For Piper that is invisible — single-speaker model, trained pace. For Kokoro it meant:

1. **Speaker 0 by accident.** Kokoro v0.19 has 11 speakers and id 0 is `af`, an *averaged blend*
   of two others rather than a speaker in its own right. The app would have shipped a blend
   nobody chose, and `NeuralVoiceTier` had no field in which to express a choice.
2. **No pacing control.** `length_scale` was never passed, so Kokoro ran at its own default —
   measurably faster than the Piper voice the narration timing was tuned around.
3. **A cache key that could not tell voices apart.** `cacheFingerprint` was just the tier name, so
   retuning the speaker or the pacing would have kept serving the old audio out of
   `NarrationStore` forever.
4. **No guard against the silent-WAV failure mode.** An out-of-range `sid` makes sherpa-onnx
   return *silence*, not an error — precisely the valid-but-silent WAV this project has been
   burned by before. `prepare()` now reads `numSpeakers()` and refuses to load a `sid` outside it.
5. **`espeak-ng-data/` was never checked.** A model directory missing the phonemizer loads fine
   and then produces nothing. `prepare()` now checks it and logs what it loaded.

The config *shape* was checked against the real archive rather than assumed. Listing
`kokoro-int8-en-v0_19.tar.bz2` gives exactly `model.int8.onnx`, `voices.bin`, `tokens.txt`,
`espeak-ng-data/` (392 entries), `LICENSE`, `README.md` — **no lexicon file at all**, which
confirms that leaving `lexicon`/`dictDir`/`lang` empty is correct for this model and not an
oversight (those fields serve the multilingual `kokoro-multi-lang-v1_*` models). The pinned
SHA-256 `c9f0dd39…08bd` matched the freshly-downloaded archive byte for byte, as did
`downloadSizeBytes = 103,248,205`.

### Evidence: measured on the HOST, not by the test about itself

`NeuralTtsProviderInstrumentedTest.kokoroProvisionsFromAPushedArchiveAndSynthesizesRealNonSilentAudio`
provisions from the `adb push`ed archive, synthesizes, and leaves the WAV in app-external storage.
Run via `am instrument` (not Gradle — it uninstalls the app and deletes the evidence), pulled, and
analysed on the host with an independent script that deliberately does **not** reuse the app's
`WavUtil`:

| | Kokoro `af_bella` |
|---|---|
| Format | 24000 Hz mono 16-bit |
| Duration | 4.825 s (11 words) |
| RMS | 1758.4 (-25.4 dBFS) |
| Peak | 12859 (-8.1 dBFS) |
| Near-silent 50 ms windows | 21/96 (22%) |

~17 dB crest factor with a burst/gap envelope — speech-shaped, not a tone and not silence. The
device log independently shows the Kokoro branch is the one that ran:
`loaded KOKORO: speakers=11 sampleRate=24000 sid=1 lengthScale=1.1`, against Piper's
`speakers=1 sampleRate=22050` from the same code path. The test asserts both numbers, so a
Piper config silently standing in for a Kokoro one would fail.

### Choosing the voice — and handing the choice to the owner

`docs/voice_samples/` now holds **the same chess paragraph through all eleven Kokoro speakers plus
the Piper baseline** (18 files, 11.4 MB), rendered by the real per-sentence pipeline with the
app's own 220/320 ms gaps, plus a five-point `length_scale` sweep and a `measurements.txt`. Every
duration/RMS in its README was re-derived on the host from the pulled files.

Default: **sid 1, `af_bella`** (`KOKORO_DEFAULT_SPEAKER_ID`). Justified by upstream's own published
voice table, which grades it **A-** — the highest of the eleven, with everything else B- or below
and the two American male voices at C+ (`am_michael`) and F+ (`am_adam`). `am_michael` is called
out in the README as the male alternative, because the "chess YouTuber" register is a legitimate
reason to trade a grade for a timbre. **Nobody has listened to any of it** — that is exactly why
the samples exist and why the default is one constant to change.

### Pacing: 1.20, from measured durations

Kokoro is naturally *faster* than Piper, not slower, which is the opposite of the assumption.
Measured on the 36-word, 4-sentence paragraph:

| `length_scale` | duration | effective wpm |
|---|---|---|
| 0.90 | 11.22 s | 210 |
| 1.00 | 12.01 s | 195 |
| 1.10 | 12.59 s | 185 |
| **1.20** | **13.74 s** | **169** |
| 1.30 | 15.83 s | 145 |

The Piper baseline measures **13.90 s / 166 wpm** on the same text, so 1.20 puts Kokoro within
1.2% of a pace this narration already ships with. 1.10 is rushed for coordinate-dense text; 1.30
drags. Chosen as the nearest measured point to a known-good pace.

`NarrationOptions.speechWpm` stays 165 in `:core` — it describes the *device* voice and `:core`
cannot know which voice will speak. `AnalysisViewModel.narrationOptionsForCurrentVoice()` now
substitutes `NeuralVoiceTier.measuredWpm` (169 Kokoro / 165 Piper) so the timeline is laid out
against the voice that will actually narrate.

### The 98 MB problem, and the gate

Kokoro is 98.5 MB to download and **150.6 MB on disk** (measured: `du -sb` of the extracted tree;
Piper is 20.1 MB / 35.6 MB). The app *already* pulls a ~98 MB Stockfish net unprompted. Stacking a
second ~98 MB fetch onto a cellular plan is not a cost the user agreed to.

New `decideAutoVoice(cost, installed)` — pure, Android-free, therefore actually testable:

| Connection | Nothing installed | Piper installed | Kokoro installed |
|---|---|---|---|
| Unmetered | download Kokoro | promote Piper **and** download Kokoro | promote Kokoro |
| Metered | download Piper (~20 MB) | promote Piper, download nothing | promote Kokoro |
| No network | nothing | promote Piper | promote Kokoro |

Metered tops out at Piper's 20 MB — the *same* amount Round 5 already shipped as an unconditional
auto-download, so this is strictly a tightening, never a new cost. `ConnectivityNetworkCostProbe`
reads `NET_CAPABILITY_NOT_METERED` and **fails closed to METERED** on missing capabilities, a
`SecurityException`, or an uncharacterisable transport: mis-reading metered as unmetered bills the
user, the opposite mistake costs a smaller model.

The probe is injected (`AnalysisViewModel.networkCostProbe`) because an emulator cannot be made to
report a metered network — it reports `NOT_METERED`, verified via `dumpsys connectivity`. Without
injection the branch that must never fire in production would have been exactly as unverified as
Kokoro was.

Round 5's invariants are preserved and re-asserted:
- the automatic path still uses `setProviderAutomatically()`, which does **not** latch
  `providerExplicitlyChosen` (asserted in `AutoVoicePolicyInstrumentedTest`);
- it still reads `narrationSettingsRepository.current()`, never the eagerly-seeded StateFlow.

And narration no longer stalls on the device voice while a model downloads:
`buildNarrationProvider()` prefers the selected tier but falls back to **any** installed tier
before dropping to device TTS. Without that, making KOKORO the default tier would have made a
Piper-only device narrate robotically despite having a working model on disk.

### Settings — and a layout defect the real sizes exposed

Per-tier labels now state both real figures ("98.5 MB download · 150.6 MB on disk"), plus
"Downloaded automatically on Wi-Fi only" on the Kokoro row — the only place a user learns the
automatic download has a condition attached. Verified on-device by `uiautomator dump`, not by
reading the source.

That check caught a **real regression the code review would not have**: the longer strings made
the label column take the action button's width, and Compose wrapped "Download" to one character
per line. Fixed with `Modifier.weight(1f)` on the label column; re-verified by screenshot
(`docs/screenshots/r6_settings_voice_sizes.png`).

### Testing

`VoiceSampleSweep` is annotated `@ManualEvidenceTool` and excluded from the Gradle run via
`notAnnotation`. Not an escape hatch for flakiness: Gradle uninstalls the app when the connected
run finishes, which deletes `/sdcard/Android/data/<pkg>/` and every artifact it wrote, so running
it there would produce nothing. A `notAnnotation` filter removes methods from the run entirely —
they never appear in the XML — so `skipped="0"` stays a meaningful check. Verified: the sweep is
absent from the result XML and the totals still show `skipped=0`.

`scripts/push_voice_models.sh` now stages **both** archives (download, SHA-256-verify, push),
mirroring `push_test_net.sh`. The instrumented tests still fail loudly rather than skipping when
an archive is missing.

| Suite | Before | After |
|---|---|---|
| `:core:test` | 181 / 0 failures / 0 skipped | **181 / 0 / 0 skipped** (re-run with `--rerun-tasks`) |
| `:app:connectedDebugAndroidTest` | 33 / 0 failures / 0 errors / 0 skipped | **46 / 0 / 0 / 0 skipped** |

+13 instrumented: 12 in `AutoVoicePolicyInstrumentedTest`, 1 Kokoro synthesis test.

### Not verified — stated plainly

- **Nobody has listened to a single sample.** Every claim about the voice is a measurement.
  Whether `af_bella` actually *sounds* best is the one thing this round cannot answer.
- **The full auto-download path was never run end to end.** The gate's decision is tested at every
  combination and Kokoro's synthesis is proven from a pushed archive, but no run in this session
  let `ensureDefaultNeuralVoice()` actually pull 98.5 MB from GitHub and narrate a whole game with
  it. `VoiceModelProvisioner.ensureModel`'s HTTP path is unchanged from Round 5, and the
  verify/extract/install tail it shares with the tested path *is* exercised — but the composition
  is inferred, not observed.
- **The metered branch has never run against a real metered network**, only against an injected
  probe. That is by design (an emulator will not report metered), but it is inference about the
  platform's reporting, not about our logic.
- **Speaker names are documentation, not verified.** Nothing in the archive labels the vectors;
  the names come from sherpa-onnx's model page. The *count* (11) is asserted on-device.
- **No release APK was built or re-verified**, per instruction. The debug build was installed and
  driven for the Settings check only.
- `:engine:connectedDebugAndroidTest` was not re-run — nothing in `:engine` was touched.
- Still emulator-only; still no physical hardware.

---

## Round 7 — per-move score, narration significance threshold, sequence colour coding

Three related refinements to one surface, done together.

### A — the engine score is now visible per ply, for both sides

- New `core.analysis.EvalFormat` is the **one** formatter. `ui/components/EvalBar.kt` and
  `video/BoardFrameRenderer.kt` had each carried their own copy of the same four lines; both now
  call in here. White-relative, sign always shown, mate always rendered as a mate.
- **Review move list**: each chip now carries the score after the move (`+0.9`, `-1.4`, `M3`) and
  the swing the move caused, under the SAN + badge row.
- **Video side panel**: an `EVALUATION` block with the score and the swing, the swing tinted green
  or red by whether it helped the side that moved.
- `MoveRecord` gained `evalBeforeCp` / `mateInBefore`; `ScriptSegment` gained `evalSwingCp`.

### B — centipawn significance threshold

- `NarrationOptions.significanceThresholdCp`, **default 50cp (±0.5 pawns)**. ANALYSIS_SPEC §9.2.
- Semantics implemented: **absolute eval swing caused by the move**,
  `|evalAfterCp − evalBeforeCp| >= threshold`, or membership of a `MoveSequence` whose combined
  swing clears the same bar. The reasoning for choosing the swing over the position's absolute
  evaluation is written into the KDoc and the spec, not just here.
- Composes with `NarrationDepth`. **`EVERY_MOVE` is exempt** — that depth is an explicit request
  for the complete walkthrough, and its existing contract test (`every move depth speaks every
  ply`) is a guarantee worth keeping. A threshold of 0 disables pruning at any depth.
- Never empty, never broken: structural beats are emitted outside per-ply selection, a
  checkmating final move always survives, and if the threshold rejects every ply the generator
  falls back to the single largest-swing ply.
- Settings: "Narration content → Only narrate moves worth ±X pawns", 0.0–3.0 in tenths,
  default 0.5, persisted in `SettingsRepository`. Read back via `repository.current()` in
  `narrationOptionsForCurrentVoice()` — which is why that function and `videoScriptFor` are now
  `suspend`, and why the Video route has a real three-state load (Loading / Ready(script) /
  Ready(null)) instead of treating "not built yet" as "nothing to narrate".

### C — colour coding for moves and sequences

- Reuses `ui/theme/MoveClassification.kt` — no second palette.
- Move chips are tinted and outlined by classification; the badge glyph stays.
- The eval graph draws each segment in the colour of the move that produced it, puts a **marker
  dot** (a shape, not just a hue) on every mistake, and shades the span of a sequence.
- **Sequences** are detected in `:core` (`MoveSequenceDetector`, ANALYSIS_SPEC §9.3): TACTIC runs
  (consecutive plies carrying the same motif for the same side) and COLLAPSE runs (consecutive
  own turns that are all mistakes). A run renders as one continuous coloured band above the move
  chips with a text label.
- Accessibility: glyphs everywhere, sequence labels are text, mistake markers are a shape.

### Defects found by looking at screenshots — both fixed

1. **The sequence band painted on exactly one chip.** `Modifier.fillMaxWidth()` is a **no-op
   inside a `LazyRow` item** — the main axis is measured unbounded — so the band collapsed to the
   width of its own text and was invisible on every ply except the one carrying the label. The
   run did not read as a run at all. Fixed with `Modifier.width(IntrinsicSize.Max)` on the item
   column. No test would have caught this; only the screenshot did.
2. **`M0` on the board after checkmate.** The engine reports `mate 0` for a position that *is*
   mate, and both the eval bar (pre-existing) and the new chip rendered it as "M0". Now `#`.
   Alongside it: **mate swings rendered as "+93.9"/"−199.5" pawns**, because `cpFromMate`
   saturates near ±10000cp. The threshold still uses the raw difference (allowing mate *is*
   maximally significant), but no numeric swing is displayed across a mate boundary.
   Also fixed: the band label's descenders were sliced off by a 14dp strip.

### Tests

| Suite | Before | After |
|---|---|---|
| `:core:test` | 181 / 0 failures / 0 skipped | **217 / 0 / 0 skipped** |
| `:app:connectedDebugAndroidTest` | 46 / 0 / 0 errors / 0 skipped | **55 / 0 / 0 / 0 skipped** |

New in `:core`: `NarrationSignificanceTest` (boundary at 49/50/51cp, absolute-vs-swing, sequence
carry-over, empty-result fallback, structural survival at an absurd threshold, checkmate
exemption, mate-boundary swing suppression), `EvalFormatTest`, `MoveSequencesTest`.
New in `:app`: `NarrationThresholdSettingsTest`, `MoveSwingMappingTest`.

Screenshots: `docs/screenshots/r6_review_move_list.png`,
`r6_review_move_list_sequence.png`, `r6_eval_graph.png`,
`r6_settings_narration_threshold.png`, `r6_video_panel_score_swing.png`.

### Not verified — stated plainly

- **MP4 export was not re-rendered.** The side panel's new eval block was verified in the in-app
  player, which uses the same `BoardFrameRenderer`, but no exported file was produced or watched.
- **The TACTIC kind of sequence has never been seen on screen.** It is unit-tested in `:core`;
  the two games driven on device produced a COLLAPSE run and no multi-ply motif run.
- **Only two games were driven through the UI** (the Opera Game and Légal's Trap), both short and
  both decisive. No long, balanced game was looked at, so nothing is known about how the move
  list reads when many chips carry near-zero swings.
- **The narration was not listened to.** Which moves the threshold selects was checked from the
  chapter list, not by hearing the review.
- `:engine:connectedDebugAndroidTest` was not re-run — nothing in `:engine` was touched.
- No release APK; still emulator-only.

### Pre-existing defects noticed but deliberately left alone

- The review screen leaves a large empty gap under the board: the eval bar fills the row's full
  height while the board is square and centred. Predates this round.
- Settings → Narration voice: the Piper/Kokoro rows wrap their labels awkwardly around the
  "Download" buttons.
- ~~The video side panel's chip shows the **`SegmentKind`** ("Blunder") where the move list shows
  the **classification** ("Mistake")~~ — **FIXED**, see "Panel/move-list label mismatch" below.


---

## Panel/move-list label mismatch — FIXED (separate session, verified here)

The video side panel labelled a move by its `SegmentKind` ("Blunder") while the review move list
labelled the same move by its `MoveClassification` ("Mistake"), so one move read two ways on screen.
The move list was right: classification is the authoritative per-move grade (`ANALYSIS_SPEC`), while
`SegmentKind` describes **why a narration segment exists**, not how good the move was — it was never
a quality label.

Fixed in a separate local session, which the owner started as a background task. Worth recording the
shape, because it is better than a display patch:

- `ScriptSegment` gained `classification: MoveClassification?`.
- `VideoScriptGenerator` extracted `annotationForSegment(ply, board)` as the **single** structural
  predicate for "is this segment about the real played move" — previously inlined in
  `swingForSegment` — and derives `classificationForSegment` from it. The swing and the verdict can
  therefore no longer come from different plies, which is the actual root cause rather than the
  symptom. It is published on Annotate/Hold beats too: `errorBeat` annotates a position without
  emitting a `PlayMove`, so that frame previously had nothing but its `SegmentKind`.
- `BoardFrameRenderer.panelChip()` lets classification win the chip; `SegmentKind` now only labels
  segments about no classified move (intro, puzzle prompt, hypothetical-line plies). A duplicate
  classification badge was removed, and the chip now advances by its **measured** height — the
  larger verdict chip had been overlapping the Players block under a fixed row step.
- Tests: `SegmentClassificationTest` (6, `:core`) and `PanelChipLabelTest` (6, `:app`) — one of which
  counts colour bands in a rendered 1280x720 frame to prove the verdict is drawn exactly once.

**Process note worth keeping.** That session's Gradle runs (15:34-16:02) overlapped a subagent of
mine building against the same `app/build`, which is the D8 dex-cache hazard CLAUDE.md warns about.
Neither side can clear that shared state from its own end, so **both** sets of results were treated
as unverified and re-established by a clean rebuild rather than trusted. Nothing failed — but "it
passed" during a contended window is not evidence.
---

## Round 6, task 39 — tactic significance gate + textbook reference library

Two owner requests, done together because they share one seam: *which* tactics are worth showing,
and *how* to show what a pattern is once one is worth showing.

### A — tactics are gated by the same threshold as moves (ANALYSIS_SPEC §9.6)

`core.analysis.TacticSignificance` is a presentation-layer gate over the §5.4 buckets, which are
computed unchanged. The report screen, the sequence detector's input and the narration all consume
the pruned view at the user's `narrationThresholdCp`; the raw detections stay on `MoveAnnotation`
for the commentary and for the review card.

**The design problem, stated honestly.** "Filter tactics by swing" is well-defined for a *missed*
tactic — the ply's swing is what missing it cost — and ill-defined for a *found* one: the played
move **is** the engine's best move, so `evalBefore` already assumed it and the swing is ~0 by
construction. A literal swing gate would empty "Tactics you found" entirely, not just the noise.
So a found tactic is judged by the **counterfactual**: the margin between the engine's best and
second-best line (`MoveAnnotation.evalSecondBestCp`, new, filled by `GameAnalyzer` from MultiPV
line 2), with the eval change across the opponent's allowing move plus the tactic as a floor for
MultiPV-1 analyses. Same threshold, same unit, same Settings slider — and the relative pin whose
alternative scored the same is exactly what drops out.

**The trap — decisive-but-low-swing tactics.** A queen up, the mating move barely moves a
saturated evaluation. Kept regardless of threshold: the checkmating move (by `#`, or by the
position when the PGN omitted it); a found tactic played while the mover has a forced mate on the
board afterwards (the quiet move that completes a net swings one `MATE_STEP` or less); BRILLIANT
and GREAT moves (the classifier already proved those); a missed forced mate; a MISS. Sequence
membership carries over from §9.2. Threshold 0 disables the gate. Each protection has its own test
in `TacticSignificanceTest`, including "M5 → M4 at a 300cp threshold survives".

**A ply carrying a significant tactic is itself significant** for narration — otherwise the
blunder was narrated and the fork that punished it (zero swing) was pruned, which is the
instructive half gone. `NarrationTacticGateTest` pins this and its converse.

**No largest-swing fallback.** An empty tactic bucket is an honest answer; the UI keeps pruned
motifs behind a "N minor (below ±0.5 pawns) — Show" disclosure per section, drawn with the same
cards, so nothing detected is unreachable and nothing minor is on the default screen.

### B — textbook reference library (ANALYSIS_SPEC §10)

`fixtures/tactic_references.json` grew from 15 to **28 mechanically verified** positions:
+DEFLECTION, DECOY, OVERLOADED_PIECE, CLEARANCE, ZWISCHENZUG, DOUBLE_ATTACK, MATE_NET, WINDMILL,
GREEK_GIFT, PASSED_PAWN_BREAKTHROUGH, DESPERADO, PERPETUAL_CHECK, STALEMATE_TRICK. Not covered:
INTERFERENCE (three attempts at a clean Novotny all had a same-line capture that re-established
the guard — left out rather than shipped dubious), X_RAY, BATTERY, FORTRESS.

The verifier gained a structural check per new pattern **and two solvers**: an exhaustive
forced-mate search (MATE_NET, CLEARANCE) and a material alpha-beta with capture quiescence
(DECOY, OVERLOADED_PIECE, DEFLECTION, ZWISCHENZUG, DOUBLE_ATTACK, DESPERADO), so "wins material"
is checked as chess, not as arithmetic on the given line. It earned its keep again: **12 of the
first 14 new hand-written positions were rejected** — a missing white king, a queen that was
already giving check, a windmill where a rook could take the checking bishop, a stalemate trick
spoiled by an available pawn capture, a "decoy" the search refuted with a decline, a double
attack where the search found a stronger move, a perpetual whose queen route was blocked by its
own pawn. Every one was plausible on the page.

The script now also **generates** `core/.../TacticReferenceCorpus.kt`; `TacticReferenceCorpusTest`
replays every line through `:core`'s perft-verified engine (FEN round-trip, SAN legality and exact
spelling, `+`/`#` claims vs `isInCheck`/`isCheckmate`, stalemate and repetition) and asserts the
Kotlin and JSON corpora are identical. **python-chess and `:core` agreed on every position.** The
app's own `MotifDetector` was run as a third opinion on the 13 static-pattern references and
recognised all of them (HANGING_PIECE excluded by design — the detector reports the move that
*leaves* a piece hanging, per §5.3, not the capture the reference teaches).

**Surfacing — the UX decision.** The reference is *offered from a real occurrence*, never taught
cold, and it reuses the missed-tactic "Show me" screen verbatim (`TacticSimulationScreen` gained
`title` / `introText` / `onSeeReference`; `SimulationBuilder` gained `maxPlies` /
`truncateAtPayoff` so a reference plays its whole line — a windmill is nine plies on purpose):
1. **Game report**: a quiet "Textbook example" text button on the tactic *group* card (the pattern
   is the unit of learning, so the offer sits on the group, not on each occurrence).
2. **Review card**: "Textbook: Fork" under the commentary when the current move carries a covered
   motif — the natural place to ask "what is a skewer, exactly?".
3. **Missed-tactic walkthrough**: on the final (payoff) step only — first the user's own miss,
   then "want to see this pattern done cleanly?". Not a pop quiz, not a detour.
4. **The narrated video stays in the game.** It names the pattern (it already did) and the outro
   lesson adds one sentence offering the textbook example in the report. It never plays the
   reference position: a detour would break the story and the running time. Pinned by test — no
   board directive in any script carries a reference FEN. The new sentences are in the phrase bank.

### Tests

| Suite | Before | After |
|---|---|---|
| `scripts/verify_tactic_references.py` | 15 verified, exit 0 | **28 verified, 0 rejected, exit 0** |
| `:core:test` | 223 / 0 failures / 0 skipped (217 + the separate session's 6) | **255 / 0 / 0 skipped** |
| `:app:connectedDebugAndroidTest` | 61 / 0 / 0 / 0 skipped (55 + the separate session's 6) | **64 / 0 / 0 / 0 skipped** (run twice: once before, once after the look-and-fix pass) |

New in `:core`: `TacticSignificanceTest` (17), `TacticReferenceCorpusTest` (10),
`NarrationTacticGateTest` (5). New in `:app`: `TacticGateMappingTest` (3, on-device: the mapper
passes the threshold through, groups know whether a textbook exists, all 28 references replay on
the device's `:core` and their routes round-trip).

### Screenshots — `docs/screenshots/r6_tactics_*.png`

| File | What it shows |
|---|---|
| `r6_tactics_03_report_tactics_a.png` | "White found" after the gate on the Opera Game: Deflection ×4, Hanging piece ×2 (was ×6), Mating net ×2 — each group with its "Textbook example" button. |
| `r6_tactics_04_report_tactics_b.png` | The same screen **before** the engine-confirmation rule: Deflection ×4, Skewer ×3 — kept for the record of what looking at it changed. |
| `r6_tactics_14_report_minor_collapsed.png` | The disclosure row: "17 minor (below ±0.5 pawns) — Show" between the last kept group and the next section. |
| `r6_tactics_15_report_minor_expanded.png` | Expanded: Hanging piece ×4 (b7/a7/b8 pawns "left hanging"), Relative pin ×3 — the noise, reachable but off the default screen. |
| `r6_tactics_07_reference_clearance_start.png` / `..._08_..._end.png` | The Clearance textbook example from its group button: teaching point on the start frame, arrow for the first move, played through to `Qh7#` with "Payoff: mates in 3". |
| `r6_tactics_09_review_card_learn.png` | Review card at 6...Nf6 (Mistake): "Show me" plus the quiet "Textbook: Skewer" text button under the commentary. |
| `r6_tactics_11_missed_sim_offer.png` | The missed-tactic walkthrough's final step: payoff, then "Want to see a skewer done cleanly?" with the offer button. |
| `r6_tactics_12_reference_skewer.png` | The Skewer reference reached from that offer, with the corrected payoff "wins the piece behind the king". |
| `r6_tactics_16_review_book_no_showme.png` | A book move's card with no "Show me" button (it used to render on every move and do nothing). |
| `r6_tactics_01_review_landing.png` | Review on landing, for orientation. |

**Defects found only by looking, all fixed before the final build:**

1. **Static motifs riding on forcing moves survived the gate.** The first build's report still said
   "Relative pin ×2, Skewer ×3" for White — every one a 0.6-confidence pattern hit on a forced
   recapture, whose MultiPV margin is enormous *because the alternative loses a piece*, not because
   the pin mattered. The margin proves the move; it cannot prove the motif. A found tactic now also
   has to be engine-confirmed (§5.3's 0.95). Hanging piece went ×6 → ×2, and "Fork: Rd8# forks the
   king and the knight" (on the mating move) disappeared with it. Spec §9.6 records the rule.
2. **The Skewer textbook's payoff read "keeps the king under fire"** — `SimulationBuilder`'s generic
   in-check fallback fired before any motif wording. References now pass an explicit per-pattern
   payoff (`TacticReferenceLibrary.payoffOf`), used only when the line neither mates nor nets
   material; game excursions keep the non-committal fallbacks, since they cannot make that promise.
3. **"Show me" rendered on every move**, book moves included, and did nothing when tapped
   (`ReviewScreen` passed the callback unconditionally; only the nav host checked for a
   simulation). Now shown only when a walkthrough exists. Pre-existing; fixed because the same card
   gained a second button and two dead controls would have been worse than one.
4. The first cut flipped the walkthrough board for a Black-side tactic. Reverted: it reads as a
   different position from the review the user just left. White-down always, as before.

**Observed and deliberately left alone (detector quality, not significance):** PV-derived motifs
are always 0.95, so "Clearance: Nxd7 clears f6 so that f6 can come through" (Black, move 15) passes
the gate on a forced recapture; and the missed-tactic walkthrough for 6...Nf6 ends with the payoff
"has invested material in the attack" on a line that is simply the engine's best play. Both predate
this round and are §5.3 / §6 questions, not §9.6 ones.

### Not verified — stated plainly

- **Only one game was driven through the UI** (the Opera Game), and it is the worst possible test of
  the noise complaint: every White move is forcing, so most found tactics legitimately survive. The
  owner's sample game (Deflection ×5, Relative pin ×3, Skewer ×3) was not available; the gate's
  behaviour on an ordinary, balanced game is inferred from the unit tests, not observed.
- **`userColor` was not set** for the driven game (no username in Settings), so the buckets read
  "White found / Black found" rather than "you / your opponent". The framing code is unchanged.
- **The narration was not listened to** and no video was rendered this round. The tactic gate's
  effect on the script (minor motifs unnamed, the fork that punished a blunder narrated, the outro
  offer sentence) is pinned by `NarrationTacticGateTest` and the notation regex test, not by ear.
- **The Greek gift reference is verified structurally only** (bishop sac with check, knight check,
  queen to h5, mate-in-one threatened) — not by search, which would need a deep solver over a full
  middlegame position. Every other combination in the corpus is search- or solver-verified.
- **INTERFERENCE has no reference.** Three Novotny constructions were tried; each had a same-line
  capture that re-established the guard. Shipping none beats shipping a wrong one.
- **`:engine:connectedDebugAndroidTest` was not re-run** — nothing in `:engine` was touched.
- No release APK; still emulator-only.
- **Process note.** A separate session (the panel/move-list label fix) was editing
  `VideoScriptGenerator.kt` / `NarrationContract.kt` and running Gradle between 15:34 and 16:02
  while this task was in its Python phase. No Gradle was run here until the tree had been quiet and
  the daemons idle; the narration file was re-read from disk immediately before each of its three
  targeted edits. The baseline numbers above include that session's tests.


---

## Round 8 correction — every exported video was narrated by the DEVICE voice

Found while removing ElevenLabs, and it invalidates two claims made in Round 5.

`VideoScreen.startExport()` passed `null` as the narration provider. `VideoExporter` treats a null
provider as "use `NarrationSynthesizer` directly", i.e. **device TTS**, bypassing both the selected
neural voice and the `NarrationStore` cache. Historically only the ElevenLabs cost-confirmation
branch ever passed a real provider, so with ElevenLabs removed **no path passed one at all**.

**Consequences, stated plainly:**
- The exported MP4 shown to the owner in Round 5 was narrated by the **robotic device voice**, not
  Piper. It was captioned as neural narration. That was wrong.
- "Export reused the cache — 32/64 in ~15 s" was wrong. Nothing was reused; device TTS is simply
  fast. Speed was mistaken for cache hits.
- All of Round 6's neural-voice work — verifying Kokoro, choosing `af_bella`, tuning
  `length_scale` to 1.20, making it the default — had **no effect whatsoever on exported video**.
  It only ever affected in-app playback.

**Why it hid so well:** in-app playback narrates through a *different* path
(`NarrationCoordinator` + `NarrationStore`, via `VideoPlayerController`), and that path did use the
neural voice. So the app sounded correct while only the exported file was wrong — and the export is
the artifact anyone would actually judge. No test caught it because no test exercised
`VideoExporter.export` with a non-null provider; the two tests that came closest were the ElevenLabs
ones deleted in this round.

**Fix:** `startExport()` now passes the selected provider. With a non-null provider `VideoExporter`
routes through `NarrationCoordinator` + `NarrationStore`, so the export reuses whatever "Prepare
narration" already generated and retains the mandatory per-segment fallback to the device voice.

**Lesson worth keeping:** a plausible explanation for an observation is not evidence. "32 segments in
15 seconds" had two explanations — cache reuse and a faster engine — and the cheaper one was assumed
without checking which path actually ran.
---

## Round 9 — ElevenLabs out, Google Cloud TTS in (per-user account)

### Removal (task 42)
7 files deleted — `ElevenLabsProvider.kt`, `PhraseBank.kt` + its test, `generate_phrase_bank.sh`,
`phrasebank/` (1933-entry manifest), `docs/PHRASE_BANK.md` — and 16 edited. 14 string resources
removed. `ResumeAndAudioGuardTest` was **renamed** to `ResumeAnalysisTest`: the "AudioGuard" half was
the ElevenLabs guard, so the old name described a file that no longer contained it.

`encodedAudioFormatOrNull()` / `isPcmContentType()` had exactly two consumers each — the provider
itself and that test's ElevenLabs block. They encoded that one API's raw-PCM response contract, so
they left with it. `WavUtil.buildWavHeader`, which the provider also used, has five other consumers
and stays.

**Kept and generalised**: the Keystore `EncryptedSharedPreferences` machinery, `elevenLabsApiKey` →
`apiKey`, pref key `elevenlabs_api_key` → `narration_api_key`, so a key saved for the removed
provider is abandoned rather than silently reused by its successor.

**Tests fell, correctly: 256 → 250 (`:core`) and 64 → 59 (`:app`), skipped=0.** Exactly 11 tests
removed, all of which existed only to prove ElevenLabs behaviour: 6 `PhraseBankTest`, 3 audio-guard
tests, 2 blank-key coordinator/export tests. Nothing was padded to keep the number flat.
Leftover-reference grep over the tree (excluding `/build/`, RUN_LOG, RUN_PLAN): **0 matches**.

### The defect that removal exposed — see also the Round 8 correction above
With ElevenLabs gone, **no caller passed a narration provider to `VideoExporter` at all** — the
cost-confirm branch had been the only one. That made visible a bug that had been shipping all along:
every exported MP4 was narrated by the device voice, ignoring the neural voice entirely. Fixed, and
now guarded by a test that does not merely assert a flag: `ToneVoiceProvider` synthesizes a known
frequency, the exported MP4's **audio track is decoded**, and tone-dominated windows are counted —
with a negative control proving a `null` provider yields zero tone.

### Google Cloud TTS (task 41)
`NarrationProviderChoice` is now `{ DEVICE, NEURAL, CLOUD }`. New `core/.../narration/cloud/GoogleCloudTts.kt`
(protocol, Android-free) and `app/.../video/GoogleCloudTtsProvider.kt`. Response path is JSON →
base64 → RIFF → the existing `WavUtil`, which already walks chunks and validates `fmt `/`data`; the
deleted ElevenLabs raw-PCM guards were deliberately **not** resurrected, since they encoded a
different API's contract.

**The key is the user's own and never leaves the device** — Keystore-encrypted, sent only to
Google's endpoint. Kokoro remains the default; Cloud is opt-in and never automatic.

`CloudVoiceSetupScreen` is the in-app wizard. It leads with a red **"Before you start: billing is
required"** panel, because Google serves Text-to-Speech only to a project with a payment method
attached *even for free-tier use* — and it says so before the user invests six steps, offering the
honest out ("the on-device voice needs no account and no card"). It quantifies the free tier as
~142 full reviews/month, states that overage bills **the user** at $30/M, and calls the $300 new-customer
credit "a trial, not a free tier". Steps deep-link into the Cloud Console.

**Tests: 274 (`:core`) / 75 (`:app`), 0 failures, skipped=0.**

### Layout defect fixed by looking
`NeuralModelTierPicker` indented 40dp and centred the radio against a four-line block, so tier titles
broke mid-phrase ("Best quality, larger / download"), the size line jammed against the hint, and the
radio floated down beside the size text instead of the title it selects. Now 24dp, `Alignment.Top`,
and 2dp line spacing. Screenshot: `docs/screenshots/r9_settings_tier_picker_fixed.png`.

### NOT verified — plainly
- **The live Google Cloud path has never run.** There is no API key in this session, so every
  network claim is unproven; only parsing, error mapping, validation and selection are tested, with
  stubbed responses. This is the same honest gap ElevenLabs carried, for the same reason.
- Narration still has not been listened to by a human.
- The wizard's Cloud Console deep links were not followed to confirm they land on the right pages.

## Round 10 — the tier-picker layout defect was only half fixed

Round 9's "Layout defect fixed by looking" above overstated the result. The structural half was
real: 40dp -> 24dp indent, `Alignment.Top`, and 2dp line spacing did stop the tier titles breaking
mid-phrase and did put the radio beside the title it selects. But the screenshot that entry cites
as proof, `r9_settings_tier_picker_fixed.png`, still visibly wraps two lines in the same row:

    20.1 MB download · 35.6 MB on
    disk
    Downloaded automatically on
    Wi-Fi only

So Kokoro's row was still four cramped lines, and the entry was written as if the picture showed
otherwise. It was looked at, but not read. Leaving that stated here because "fixed by looking" is
exactly the claim that needs to survive being checked.

### What actually closed it

The text column was still too narrow, so the fix was to stop spending width and stop spending
words:

- **Indent 24dp -> 16dp** and **button `contentPadding` 24dp -> 12dp horizontal** — together ~32dp
  handed back to the label column, which is what the wrapped `disk` needed. The 48dp minimum touch
  target on both the radio and the buttons is untouched; this trims padding, not targets.
- **`qualityHint` shortened** — `"Fast, smaller download"` -> `"Smaller, faster"`,
  `"Best quality, larger download"` -> `"Best quality"`. The word "download" was redundant against
  the exact figures on the very next line.
- **`settings_narration_neural_wifi_only` shortened** — "Downloaded automatically on Wi-Fi only"
  -> "Auto-downloads on Wi-Fi only".
- `settings_narration_neural_size` was deliberately **not** cut. Both figures are load-bearing
  (see the comment on that string); the width came from padding instead.
- The progress indicator's start inset moved 40dp -> 48dp so it still lines up with the label
  column after the outer indent shrank.

### Verified — all three states of the row, on `chess34`

Every line now fits on one line at 1080x2400, and the radio sits on its title's baseline:

- `docs/screenshots/r10_settings_tier_picker.png` — neither tier downloaded. Directly comparable
  with `r9_settings_tier_picker_fixed.png`; the wrapping is gone.
- `docs/screenshots/r10_settings_tier_picker_downloading.png` — Piper downloading. Cancel button
  and the progress bar aligned to the label column.
- `docs/screenshots/r10_settings_tier_picker_installed.png` — Piper installed: "35.6 MB on this
  device" + Delete, and the app auto-promoted to the natural voice.

The downloading and installed states were exercised for real (a live 20.1 MB Piper download on the
emulator), not reasoned about, because the `contentPadding` change touches Cancel and Delete too.

### Not verified

- Only 1080x2400 was checked. A narrower phone, a larger font scale, or a longer translation could
  still wrap; nothing enforces the no-wrap property in a test.


---

## Round 10 — Task 40: i18n groundwork + RTL + language setting (English only)

Goal as set: make Hebrew a **content** task later, not a rewrite — without shipping any Hebrew now.

### The string contract in `:core` (the heart of the task)

`:core` is Android-free, so `R.string` was never an option for the ~400 narration strings. The
naive fix — a string table with `%s` holes — was ruled out before design: Hebrew inflects verbs and
adjectives for gender, pluralises differently and reorders words, so `"White " + "played " + move`
is untranslatable piecewise no matter how good each piece is.

What was built (`core/.../narration/NarrationStrings.kt`, `EnglishNarration.kt`):

- **`Sentence`** — a sealed hierarchy of ~120 whole sentences. The class name is the id; the
  constructor parameters are *typed facts*, never words: a `Subject(color, person, gender)`, a
  `SpokenMove(piece, from, to, captured, promotion, ambiguous, outcome…)`, `Standing`,
  `LossSeverity`, `MaterialPayoff`, `MaterialGain`, `PayoffKind`, `PuzzlePrize`, `OpeningFamily`,
  `TacticType`. Percentages arrive pre-formatted ("59.9") because digits are shared; counts arrive
  as integers so a locale can pluralise.
- **`NarrationStrings`** — the locale interface: `render(sentence, style): List<String>` returns
  *every* variant of that whole sentence in that language; `PhrasePicker` rotates through them, so
  a locale may offer more or fewer variants than English. The `when` over the sealed hierarchy is
  exhaustive: a sentence a translator forgot is a **compile error**, not a silent English fallback
  mid-review.
- **`EnglishNarration`** — the only place English prose now exists in `:core` narration. The
  generator (`VideoScriptGenerator`) contains **no prose**: it decides *which* sentence to say and
  joins finished sentences with spaces. Lead-in fragments ("A couple of moves later, ") are the one
  deliberate prefix concatenation, documented as sentence adverbials.
- **`SpokenChess`** is now a pure facts builder (`describe(position, move): SpokenMove`);
  `NotationGuard` parses notation it finds in PGN names into `ParsedNotation` and asks the locale's
  `SpokenVocabulary` to speak it, so a Hebrew review quoting a username containing "Nf3" still
  speaks Hebrew.
- **Analysis-layer English that had leaked into narration is gone from the narration path**: the
  generator no longer parses `TacticSimulation.payoffDescription` (it derived `PayoffKind` from
  the boards the same way `SimulationBuilder` does), no longer sniffs `"mates"` for the puzzle
  prize (it checks whether the walked line ends in checkmate), and no longer does English verb
  surgery (`payoffClause` stripping an `-s`). `ExchangeEvaluator.describeGain`'s thresholds are
  mirrored as `MaterialGain`.
- **Gender is plumbed, not solved.** `NarrationOptions.viewerGender` (default UNSPECIFIED) rides on
  every `Subject` about the viewer. English ignores it; `NarrationStringsTest` proves a fake locale
  receives it and can put the move first and the subject last in one sentence.
- **`NarrationLocales.forTag()`** maps Android's legacy `iw` to `he` (and `in`→`id`, `ji`→`yi`)
  and falls back to English for anything unregistered.

**What a translator receives**, concretely, for one sentence: `Sentence.Played(subject=Subject(
WHITE, SECOND, FEMININE), verb=PLAY, move=SpokenMove(KNIGHT, g1→f3, no capture, no check))`, whose
English reference is "you play knight to f three". They return one Kotlin object implementing
`NarrationStrings`; for that id they may write, in Hebrew, "[verb inflected feminine] [piece]
[square]" in any order. The whole table — 123 rows, every id with its parameters and every English
variant — is generated from the implementation itself: **`docs/NARRATION_STRINGS.md`**
(`NarrationCatalogue.markdown()`, regenerated by `NarrationStringsTest`).

Tests: `:core:test` **274 → 279**, 0 failures, skipped=0. New: every catalogued sentence renders in
both styles without notation; the catalogue covers every `Sentence` subclass (Java reflection, no
kotlin-reflect); the locale registry; the restructuring/gender proof; English shape unchanged.
The existing "narration never contains algebraic notation" test still passes.

### App layer

- **Language setting** (Settings → Language): `AppLanguage { SYSTEM, ENGLISH }` persisted in
  `SettingsRepository` (`app_language`), applied through the platform's own `LocaleManager` on
  **API 33+** (`AppLocales.apply`), declared to the system picker via `res/xml/locales_config.xml`
  + `android:localeConfig`. The narration locale is derived from the same choice
  (`AppLocales.narrationTag` → `NarrationLocales.forTag`) and is part of the script cache key.
  Screenshot: `docs/screenshots/r10_settings_language.png`.
- **Below API 33 the option is shown disabled with the reason** ("needs Android 13 or newer; the
  app follows the system language"). **AppCompat was NOT added** — see the recommendation below.
- **Hardcoded UI stragglers moved to `strings.xml`**: the analysis-failed / engine dialogs and
  their "OK", the update messages, the GPL notice, "Back", "Not enough moves to estimate a rating",
  and **32 `TacticType` names** (`TacticTypeNames.kt`; the report, simulation, comment card and
  reference screens no longer read `TacticType.displayName`).
- **Video side panel**: `BoardFrameRenderer` has no `Context`, so its burned-in labels ("Move 12",
  "Tactic: Fork", "RECENT MOVES", "EVALUATION", "White to move", "(you)", the verdict chip) go
  through a `PanelLabels` object resolved once by the exporter/player from resources
  (`PanelLabels.from(context)`), with an English default so hand-built specs and
  `PanelChipLabelTest` are unchanged. The verdict chip now uses the *UI* classification strings, so
  the panel and the move list call a MISTAKE the same thing in every language.

### RTL — actually rendered, and one real defect found

`debug.force_rtl` did not take on this image (Global setting and `setprop` both left the app LTR
after a restart); what worked, and is the more honest test anyway, is the platform per-app locale:
`adb shell cmd locale set-app-locales net.palaya.chessanalyzer --user 0 --locales he-IL`. The app
then ran in **Hebrew layout direction with English strings** — exactly what a Hebrew-device user
sees today.

- **The board did not mirror** — a1 bottom-left, white rook on the left, coordinates in place
  (`r10_rtl_review.png`). It is Canvas-drawn in absolute coordinates, and is now also pinned
  explicitly with `LocalLayoutDirection = Ltr` so a future Row/Column inside it cannot regress it.
- **Defect, fixed:** the move list (a `LazyRow`) mirrored — moves ran right-to-left, and because
  "3." and "+0.4" contain no strong-direction character, bidi rendered them **".3"** and **"0.4+"**
  (`r10_rtl_review_before.png`). The transport controls mirrored while their icons did not, so
  "first move" sat on the right pointing left. Both are pinned LTR now: algebraic notation is an LTR
  script in every language. After: `r10_rtl_review.png` — `1. e4 +0.2 −0.1` reads correctly while
  the page chrome (title, eval bar, top-bar actions) stays mirrored.
- Report and Settings mirror correctly (`r10_rtl_report.png`, `r10_rtl_settings_language.png`).
  The trailing full stops rendered on the left there are the expected bidi placement of *English*
  sentences in an RTL paragraph and disappear once real Hebrew strings exist — not a defect.
- Audit: no `Alignment.Left/Right`, `TextAlign.Left/Right` or `absolutePadding` in the tree; all
  back arrows are already `Icons.AutoMirrored`. The eval graph (Canvas) keeps time left→right.
- Flags restored: app locale `[]`, `debug.force_rtl` property and Global setting both `0`.

### AppCompat / API-33 recommendation — NOT actioned

Per-app language below Android 13 needs `AppCompatDelegate.setApplicationLocales()`, which means
adding `androidx.appcompat:appcompat` (~1.5 MB, pulls in the View-system theme machinery this
pure-Compose app has never had) and, to get the pre-33 persistence, either an `AppCompatActivity`
base class or a `ContextWrapper` on `attachBaseContext`. Recommendation: **accept the documented
"language switching needs Android 13+" limitation for the POC.** minSdk 26 devices still get the
right language whenever it is their *system* language (the resources resolve normally), which is the
case that matters for a Hebrew user; the only thing they lose is overriding it from inside the app.
Revisit if the owner wants in-app override on Android 8–12; it is a half-day change and the
`AppLocales` seam is the one place it plugs in.

### Hebrew device TTS — UNVERIFIED, and the assumption behind it is at risk

Evidence, not inference (`HebrewDeviceTtsProbe`, run via `am instrument`; report pulled to host):

```
engine=com.google.android.tts initStatus=0
isLanguageAvailable(he-IL) = LANG_NOT_SUPPORTED(-2)     (not LANG_MISSING_DATA)
hebrew voices enumerated: 0
availableLanguages (65): ar, bg-BG, … zh-TW           (no he / iw)
setLanguage(he-IL) = LANG_NOT_SUPPORTED(-2)
RESULT: engine reports no usable Hebrew voice; no synthesis attempted
```

No WAV exists, so there are no duration/RMS numbers to report. Two facts make this worse than
"emulator has no voice data": the engine answers **NOT_SUPPORTED**, not MISSING_DATA, and the
GoogleTTS APK's own download manifest (`assets/superpacks_manifest.json`, 121 packs, 61 locales)
**lists no Hebrew pack at all** — so a Play Store would not have helped on this build
(`googletts.google-speech-apk_20230123`). Whether a *current* Google TTS release ships Hebrew could
not be checked from here. **The owner's "device TTS is the Hebrew default" is therefore unverified
on real hardware and may be unavailable on any hardware with this engine version**; the only
proven Hebrew voice path today is Cloud TTS (Chirp 3: HD, BYO key), which most users will not set
up. This needs a real Hebrew-locale Android device with a current Google TTS before the Hebrew
narration plan is committed to.

### Numbers

- `:core:test`: 274 → **279**, 0 failures, 0 errors, skipped=0.
- `:app:connectedDebugAndroidTest`: 75 → **77**, 0 failures, 0 errors, skipped=0 (the +2 are another session's new tests landing in the same window; `HebrewDeviceTtsProbe` is `@ManualEvidenceTool`-excluded and does not count, by design).
- `scripts/verify_tactic_references.py`: exit 0, "28 verified, 0 rejected".

### What remains for Hebrew to become a content task

1. `core/.../narration/HebrewNarration.kt` implementing `NarrationStrings` (~120 sentences from
   `docs/NARRATION_STRINGS.md`), registered in `NarrationLocales.all`; a Hebrew `SpokenVocabulary`
   (piece names, how squares are spoken — "f3" cannot be "f three" in Hebrew TTS).
2. `app/src/main/res/values-iw/strings.xml` (**`iw`, not `he`** — Android silently ignores
   `values-he` on many devices) and `<locale android:name="he"/>` in `locales_config.xml`;
   `AppLanguage.HEBREW("he", …)`.
3. A viewer-gender setting feeding `NarrationOptions.viewerGender` (the field exists; nothing sets
   it yet — `Gender.UNSPECIFIED` today).
4. Still English by construction, outside this pass: `CommentaryGenerator` (the on-screen move
   commentary, ~40 strings, same contract shape should be applied), `TacticInstance.description`
   and `TacticSimulation.perPlyExplanation/payoffDescription` from the analysis layer (shown in
   the simulation screen), `SegmentKind` chip labels ("Puzzle Prompt"), the `NeuralVoiceTier` /
   `GoogleCloudVoice` labels in `NarrationVoiceSettings.kt` (left untouched — another session was
   editing that file), `EngineSettings` default strings, and `VideoGameHeader` fallbacks.
5. A Hebrew TTS engine that actually exists on the target device (see above).
6. Long-string resilience: the neural-tier row in Settings was fixed by shortening English
   (`Smaller, faster` / `Best quality`); the layout already lets the label column flex
   (`weight(1f)`), but no test covers it, and Hebrew strings will differ in length — check that row
   first when Hebrew resources land.

### Not verified

- The language switch was exercised only through the platform API (`cmd locale`), not by tapping
  "English" in Settings on API 33 — the emulator is API 34 so the code path is the same
  `LocaleManager` call, but the tap itself was not driven.
- Narration in a second language has never been heard (there is none), and nobody has listened to
  the English narration either (carried over from earlier rounds).

---

## Round 11 — the export-voice fix, verified by ear-able artifact rather than by report

The Round 8/9 fix (`VideoScreen.startExport()` passing the selected provider) was already in the
tree when this round started. What was missing was evidence and two layers of coverage.

### Two seams that had no test, and one of them was the seam that broke

Round 9's tone tests call `VideoExporter.export(script, name, provider)` **directly**. The `null`
never lived there — it lived one layer up, at `VideoScreen` → `VideoExportService.start()`. So
those tests would have stayed green with the original defect fully intact. Added:

- `VideoExportServiceInstrumentedTest.theServiceForwardsTheSuppliedProviderAllTheWayIntoTheExportedAudio`
  — drives a `ToneVoiceProvider` through the **service** (`start()` → `pendingRequest` →
  `onStartCommand` → `export()`, a handoff that deliberately bypasses the Intent because a provider
  is not parcelable) and finds the tone in the audio of the file the *service* produced.
- `VideoExportNeuralVoiceEvidenceTest` — the **composition** nobody had ever run. "Provider audio
  reaches the MP4" (tone test) and "Kokoro produces speech" (`NeuralTtsProviderInstrumentedTest`)
  were both independently true for three rounds *while the composition was false*. This exports the
  same script twice — real Kokoro `NeuralTtsProvider` vs `null` — decodes both AAC tracks and leaves
  WAVs to pull.

### Measured, on the host, from pulled artifacts

`:app:connectedDebugAndroidTest` = **77 / 0 failures / 0 errors / skipped=0**, tallied from
`androidTest-results/**/*.xml`, not from "BUILD SUCCESSFUL". Then the evidence test re-run via
`am instrument` (both APKs installed by hand, so Gradle's uninstall could not delete the artifacts):

| Signal | Duration | Rate | RMS | Peak |
|---|---|---|---|---|
| reference (Kokoro, straight from the model) | 2.34 s | 24000 Hz | 1854.0 | −7.3 dBFS |
| exported **neural** (provider-backed MP4) | 20.04 s | 44100 Hz | 1749.8 | −5.5 dBFS |
| exported **device** (null-provider MP4) | 19.32 s | 44100 Hz | 3534.6 | −2.6 dBFS |

Normalized cross-correlation of the reference sentence against each export:
**neural = 0.982** (at 0.05 s), **device = 0.176** (at 2.65 s).

0.982 is the Kokoro waveform surviving the AAC round-trip — the neural voice is genuinely in the
exported file. The contrast is sharper than expected: this emulator's device TTS produces **real
speech** (rms 3534.6, 23.8% near-silent), not the silent fallback, so 0.176 is Kokoro against a
genuinely different voice rather than against silence.

**Listenable, finally:** `docs/voice_samples/r11_exported_mp4_narration_kokoro.wav` and
`..._device.wav` are the decoded audio of the two exported MP4s. This is the first exported-video
narration in this project that can simply be played. Caveat: it is the synthetic test script, not a
real game review.

### Not verified this round

- `:core` was not re-run here; the 279 figure is another session's tally, not mine.
- Still nobody has *listened* to a full real review — the two WAVs above are 20 s of synthetic script.
- The `am instrument` artifact run exercised the exporter directly. The nav-host → `VideoScreen`
  wiring that selects the provider is confirmed by reading (`ChessAnalyzerNavHost.kt:241` →
  `buildNarrationProvider()` → `selectNarrationProvider`) and by the service-level test, but no
  human drove Settings → Export by hand this round.

### Process note

Four sessions were live in this tree. `:app` was red for ~11 minutes on a half-landed i18n batch
(`AppLanguage.kt` referencing 34 string resources that did not exist yet); the owning session was
mid-pass, so the break was left alone rather than patched, and the lock on `app/build` +
emulator-5554 was handed over explicitly rather than inferred. A "quiet tree + idle Gradle daemon"
reading was **wrong** — that session's subagent was on the emulator taking RTL screenshots
throughout. Treat an agent as finished only on its completion signal, not on filesystem quiescence.
Also: `rm -rf androidTest-results/connected` before a run destroyed the XML behind another
session's verification claim. Don't clear another session's evidence on a shared tree.
