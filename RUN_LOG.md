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

---

## Round 12 — PC producer, setup

Owner chose option 1 (the PC producer), with the videos for their own use, and asked for a better
TTS, a better LLM, and a GothamChess-style result. Scope is in RUN_PLAN.md, Round 12.

- **Hardware measured:** i9-10885H 8C/16T, 31.7 GB RAM, GTX 1650 Ti Max-Q 4 GB, 415 GB free.
- **ffmpeg** installed via `winget install Gyan.FFmpeg`. Verified: `ffmpeg version 9.0.2-full_build`.
- **Stockfish 19** downloaded from the official release `sf_19` as
  `stockfish-windows-x86-64-universal.zip` (81,431,614 bytes) into `pc/bin/stockfish/`. It is the
  same tag the Android `:engine` vendors.
- **Stockfish verified:** startpos with 8 threads and 512 MB hash, 5 s movetime → **depth 31**, 3.43M nps.
  The emulator reached depth 12.
- **Pipe gotcha:** Stockfish exits as soon as stdin closes, which silently aborts `go`. The first
  smoke test printed `bestmove a2a3` with no search output. The driver must keep stdin open until
  `bestmove` arrives.
- Three research agents launched: TTS, LLM, and video format.
- **Research done**, saved to `docs/pc_research/{VIDEO_FORMAT,LLM,TTS}.md`. The picks:
  - **LLM:** Gemma 4 26B-A4B Q4_K_M running in llama-server. colibri is rejected: its own benchmarks put 32 GB machines at about 0.08 tok/s, which would take about 12 h per script.
  - **TTS:** Chatterbox-Turbo (English, with laugh/gasp tags) and Chatterbox Multilingual V3 (Hebrew plus an exaggeration dial) are the candidates. VoxCPM2 is the Hebrew bet.
- **llama.cpp b11190** (CUDA 12.4 build plus cudart) is unpacked into `pc/bin/llama/`.
- **Gemma download:** the first attempt died partway (curl exit 18). It was restarted with `-C -` and retries.
- **Launched:** the TTS listening spike (task 54, GPU) and the Fable design agent (task 53, read-only).
  - The LLM spike (task 55) waits until the TTS spike releases the GPU, since 4 GB can't hold both.
- Gemma 4 26B-A4B UD-Q4_K_M: 16,947,541,728 bytes, and its sha256 f2c28b3d…293f matches the HF LFS oid.

### P0: desktop skeleton — DONE (agent), with three findings acted on

- **Built:** a `:desktop` module with the CLI, work dir, UCI client with the stdin kept open, the engine analyzer with checkpoint and resume, and the analysis stage. It writes `analysis.json` and `report.json`.
- **Tests (read from the XML):** desktop 17/0/0 skipped, core 279/0.
- **Parity test:** it uses Threads 1, because at Threads 4 three immortal runs at depth 14 disagreed on 5–6 classifications. Multithreaded Stockfish is not reproducible, so a regression fixture needs one thread.
- **Movetime cap defect (P0 agent):** with the 8 s cap, 8 of 34 plies were cut off at depth 13–15 while the TTS spike was loading the CPU, at 1.08 Mnps against 3.4 when idle. As a result 15...Nxd7, which walks into mate in 2, was classified BEST. With the cap removed it is GOOD, and 16.Qb8+ becomes GREAT.
  - **Action for P1:** raise the cap and flag capped plies in the report, so a truncated search is never silent.
- **Core bug fixed:** `WinProbability.cpFromMate(0)` returned **+10000**. mateIn=0 is what `AnalysisService` records for a checkmated side to move, so every mating move got a wrong-sign centipawn value, which fed `TacticSignificance.swingCp`. `winPercentOfLine` was already right (0.0).
  - Fix: `sign = if (n > 0) 1 else -1`. This also corrects the Android app, since the code is shared.
- **Core PGN parser made lenient:**
  - It accepts `1.e4` / `12...Nf6` with the move number glued on, and movetext with no tag section.
  - A bare `12...` is still just a move number, via a capture that cannot start with a dot.
  - Garbage still fails.
- **Regression tests added** for both core changes. **`:core:test` is now 282/0/0/0.**
- **Noted for P2, not yet fixed:** the tactic tags are noisy. 17.Rd8# gets Fork, Double attack, Hanging piece and Skewer, and 15.Bxd7+ gets Windmill and Trapped piece. The LLM fact sheet must use the pruned or high-confidence tactics only, or it will narrate nonsense.

### P1: crude end-to-end — DONE (agent), verified by frames I read myself

**Output:** `pc/work/p1_kokoro.mp4`, 671.2 s long.
- **Video:** h264 1920x1080 at 30 fps, 20,134 frames.
- **Audio:** aac 48 kHz stereo, −14.4 LUFS integrated, RMS −17.4 dBFS, 23.6% near-silent.
- **Timing:** container duration vs `timeline.totalMs` Δ = 79 ms.

**Frames:** the orchestrator read two of them directly (10.Nxb5 with the !! badge, and the final mate).
- The final position matches the PGN's `CurrentPosition` FEN square for square.
- Pieces, the badge, the check glow and captions all render correctly.

**Tests:** desktop 25/0/0 skipped. `EndToEndTest` finds the fake-voice tone at all 231 line starts, with an exact frame count.

**Timings.** These were measured while the TTS spike was hammering the CPU, so treat them as pessimistic:

| Stage | Measured |
|---|---|
| Engine | 16.6 s/position at 0.87 Mnps |
| Kokoro (CPU) | RTF about 5 per worker; 3 parallel workers → 2× real time |
| Render + encode | 53 fps (736 frames drawn, 19,398 reused), bound by the pipe and x264 |

**Known P1 ugliness:**
- The right panel is 60% empty.
- The chapter label is stuck on "Move 9".
- The eval text is tiny.
- Arrows cover pieces.
- It uses system fonts.

**Content: the real problem, for Director v1.** The core generator gives 83 beats and 11 min for a 17-move game, and 195 beats and about 23 min for the immortal game. It walks 8-ply missed lines for mere inaccuracies. The P2 tiering and cap exist to fix exactly this.

**Engine cap:** the new default is depth 20 with a 30 s cap, and capped plies are flagged. Under contention, 9 of 34 positions still hit the cap. **Re-measure on an idle CPU** before judging the cap.

### Task 54: TTS listening spike — DONE (agent); samples sent to owner

Files are in `docs/voice_samples/pc/`, with `RESULTS.md`. As an intelligibility proxy, the agent transcribed each sample with Whisper and scored the word error rate (WER) against the script.

| Candidate | Device | RTF | WER | Notes |
|---|---|---|---|---|
| Kokoro (baseline) | CPU | 4–8 (contended CPU) | 0 | |
| **Chatterbox-Turbo** | CUDA fp32 | **0.84–1.56** | ≤0.03 | ~3 GB VRAM. The only expressive model that runs at about real time here. Tags add laughs/gasps: +2.8–5.1 s of non-speech |
| Chatterbox Multilingual V3 | CUDA fp32 | 8–17 | 0–0.10 | The GPU thermal-throttled at 1035/2100 MHz. fp16 is slower on this card (0.23 vs 1.23 TFLOPS). Peaks reach 0 dBFS with a few clipped samples, so it must be normalised before mixing |
| MTL V3 Hebrew, with dicta | CUDA | 11–17 | **0.05** | Without dicta WER is 0.42. That confirms Chatterbox silently skips vowel marking unless given the model path |
| VoxCPM2 | CPU fp32 | 36–41 | 0–0.16 | The 4.96 GB checkpoint doesn't fit 4 GB of VRAM |

**Consequences:**
- For English, Turbo means a 12-min video takes roughly 12–20 min to synthesize.
- For Hebrew, MTL V3 means 2–3.5 h per video: an overnight job, unless a faster Hebrew path turns up.
- **Hebrew override list needed:** dicta vocalized הפרש as "hefresh" (difference) instead of "happarash" (the knight). Whisper can't catch that. Chess vocabulary needs an override list.

**Awaiting:** the owner's listening verdict.

---

## Round 13 — mobile focus

The owner pivoted to the Android app after testing on a real phone: UI/UX too complex; do all improvement ideas; drop Google Cloud; everything local, no external APIs; a big APK is fine.
- The PC producer is paused (P0/P1 done; P2 and the LLM spike stopped without leaving work).
- The scope and my three decisions (cut username import; bundle the models; Fable-designed UX) are in RUN_PLAN, Round 13.
- **Task 63 UX design — done (Fable 5.1, high).** `docs/MOBILE_UX_DESIGN.md`, with ten ordered implementation steps (U1–U10) and the owner's three open questions answered by the design's defaults. The plan is in RUN_PLAN, Round 13.
- Gradle serialisation: the cleanup agent (60/61/62) is still running, so the core tasks 64/65 and the UX steps wait their turn.

### Tasks 60/61/62: Google Cloud removed, backup rules fixed, tests re-run — DONE, verified

- **Verified by me, from the files:**
  - No Cloud references remain, apart from one test planting a fake legacy key to prove it gets purged.
  - `:core` 258/0/0 skipped, which is 282 minus the 24 deleted Cloud tests.
  - App unit 16/0, instrumented 66/0 skipped.
  - Release APK 108,052,265 bytes, signed.
- **Migration:** a stored `CLOUD` or unknown provider falls back to DEVICE and clears the "explicitly chosen" flag, so the automatic neural promotion still applies. That clearing is the agent's own addition.
  - `LegacyKeyStoragePurge` deletes the old key files and the Keystore master key at every start.
- **Backup finding:** the old `engine/` exclusion excluded nothing, because the 98 MB net sits in the root of `filesDir`.
  - It would have blown the 25 MB quota and could have stopped all backups. It is now excluded by exact filename, with a test that fails if the name drifts.
  - Live check with `bmgr`: 11 MB of excluded junk plus one game produced a 7 KB archive containing only the game and the general settings.
- **Pre-existing, not fixed yet:** `lintDebug` fails with one `MissingPermission` error at `VideoExportService.kt:322` (`notify()`). It goes into U1.
- **Latent:** the app has no corrupt-DataStore handler. A bad file crashes on launch. Worth a small hardening step.

### Tasks 64/65: pacing tiers and tactic noise — DONE, with one defect sent back

- **Verified by me from the XML:** core 301/0/0 skipped, app unit 16/0, instrumented 66/0 (run before the agent's last test-only edits), corpus 28 verified and 0 rejected.
- **Results (desktop `--dry-run`):**

| Fixture | Before | After |
|---|---|---|
| 17-move game | 70 beats / 629 s | 30 beats / 314 s |
| Immortal | 160 beats / 1320 s | 50 beats / 441 s |

- **Tactics:** 17.Rd8# went from Mate net, Fork, Double attack, Hanging piece and Skewer to **Mate net only**. 15.Bxd7+ went from Windmill, Clearance, Fork and Trapped piece to **Clearance only**.
  - Windmill now needs at least 4 consecutive checks, at least 2 discovered checks, at least 2 captures and the same piece returning to a square.
- **Found by the agent, and a real bug:** a Black-to-move puzzle check misread the White-relative mate score. It is fixed for the pacing rules.
- **PAWN_FORK reference was unsound.** After 1.e4 the forked bishop simply takes the pawn. The verifier now fails closed on a capturable forker, and the entry was replaced with a two-knight position that verifies.
- **Defect sent back:** the budget demoted the Immortal Game's sacrifices to BRIEF, and brilliancies rank last for FULL slots because their loss is about 0. The agent was resumed to rank by drama, never demote BRILLIANT/GREAT below DWELL, and remove filler lines like "Top of the engine's list."
- **Desktop cache gotcha:** `--dry-run` reuses a cached storyboard after a core change. Use `--from storyboard`.

### Task 64 follow-up: brilliancies protected — DONE
- **Verified from the XML:** core 306/0/0 skipped, app unit 16/0.
- **FULL ranking by a drama score:** a BRILLIANT move scores 25, a mid-sized blunder.
- **Protected from the budget (never below DWELL):**
  - BRILLIANT and GREAT.
  - The checkmate.
  - Forced-mate moves. The agent added this case because the engine labels 22.Qf6+ "BEST", not brilliant.
- **Demotion order:**
  1. Unprotected DWELL goes to BRIEF.
  2. BRIEF goes to SKIP.
  3. Cheapest FULL goes to DWELL, brilliancy last.
- **Filler removed:** a BRIEF beat now states the tactic or the evaluation shift. One new sentence, `EvalShift`, is English only.
- **Immortal Game, now told at DWELL length or more:** 22.Qf6+, 21.Nxg7+, 17.Nf5, 13.h5, 18.Bd6, 18...Bxg1 and 20...Na6 (the turning point). Duration is 441 s, with nothing padded.
- **Not re-run:** the instrumented suite after this last change (it was 66/0 before).

### UX U1 (copy and resources) + U2 (navigation shell) — DONE, verified
- **Verified by me:**
  - From the XML: core 306/0/0 skipped, app unit 21/0 (16 plus 5 new), instrumented 66/0/0 skipped.
  - From a screenshot (`docs/screenshots/r13_u1u2_04_summary.png`): after analysis the app lands on "Game summary", with "Watch the video review" and "Open the board" buttons.
- **Lint:** 0 errors, down from the 1 `MissingPermission` error. `VideoExportService.notifySafely` now checks `POST_NOTIFICATIONS`.
- **Every DataStore** now has a corruption handler. A corrupt file resets to defaults instead of crashing at launch.
  - A source scan test fails if a `preferencesDataStore(` is added without the handler.
- **Navigation:**
  - After analysis the app opens the Summary.
  - Back arrows are on Summary, Board, Walkthrough, Video and Settings.
  - Analysis errors show in-screen with "Try again" and "Back", and the pasted text is kept.
  - A null Summary report pops back instead of showing a blank screen.
- **Extras:**
  - System back on the progress screen cancels the analysis. Before, it kept running and later pushed the user onto the Summary.
  - `review_learn_pattern` was reworded for grammar ("See how it works: …").
- **Left for later:**
  - The Summary still shows the 22-row "All moves" table. U5 restructures it.
  - The video poster says "45 plies", which comes from core.
  - The Video top bar is cramped until U7.
  - The Snackbar and the Walkthrough back arrow were not driven by hand.

- **Task 67 design (practise your mistakes): DONE (Fable 5.1, high).** Saved as `docs/PRACTICE_DESIGN.md`.
  - **Finding:** `ChessBoard` already supports tap-to-move, but no screen uses it, so the emulator check will be its first real use.
  - **Judging:** from the cached MultiPV lines, not the engine. A quick engine check would not be comparable with the cached depths, which the spec forbids. A selection rule makes "not in the cache" provably wrong.
  - **Contract change needed:** `MoveAnnotation.candidateLines`, because only line 2's score survives analysis today.

- **Task 68 design (bundling the models): DONE (Fable 5.1, high).** Saved as `docs/BUNDLED_MODELS_DESIGN.md`.
  - **Findings:**
    - Stockfish cannot read the net from inside the APK (`std::ifstream` on a path, and `exit()` on failure), so it is copied once to `filesDir`.
    - The internet permission can be removed entirely, because every network caller is a file being deleted.
    - `espeak-ng-data` is GPL-3.0-or-later and the About text never mentioned it, a pre-existing licence gap.
- **B0 started:** `.gitignore` now ignores `vendor/models/*` except `MODELS.lock`. I verified that with `git check-ignore`. The repo has commits, so ignoring first matters.
  - `scripts/fetch_models.sh` was written and is running. It pins the inner-tar hash on its first successful run.

### B0: bundled-model repo prep — DONE, verified
- **Net:** `vendor/models/engine-assets/nnue/nn-1a298aa575a0.nnue`, 98,511,183 bytes, SHA-256 prefix matches the filename.
- **Voice archive:** the sherpa-onnx `kokoro-int8-en-v0_19.tar.bz2` was downloaded, and its 103,248,205 bytes and SHA-256 `c9f0dd39…08bd` match the pre-existing pin in `VoiceModelProvisioner.kt`.
- **Plain tar pinned:** the inner tar is 158,269,440 bytes, SHA-256 `7190c4801645bf31d10996477a04082019d9cf492ad3d5aeef7b1f7cf10a5dea`, written into `vendor/models/MODELS.lock`.
  - It has 398 entries, which is 360 files plus 38 directories, as the design measured.
- **Git:** `git status -uall vendor/models` shows only `MODELS.lock`. The 257 MB of models are ignored. `git check-ignore` confirmed the lock is tracked.
- **Idempotent:** a re-run re-verifies and changes nothing.
- **Stale nets:** the script removes them, so a Stockfish bump never ships two.
- **To commit later:** `vendor/models/MODELS.lock` and `scripts/fetch_models.sh`.

### UX U3 (Home) + U4 (Analysing) — DONE, verified
- **Verified by me:** the XML gives core 306/0/0 skipped, app unit 29/0 (21 plus 8 new), instrumented 66/0/0 skipped. I read two screenshots (`r13_u3u4_01_home_fresh.png`, `r13_u3u4_16_home_two_games.png`):
  - Fresh install shows one card: "Review a game you played", with "Open a game file" as the primary button and "Paste moves".
  - After games exist it becomes the compact "Review another game [Open file] [Paste]" with the recent list below.
- **Paste sheet:** a bottom sheet with a clipboard button and a full-width Analyze button that stays visible with the keyboard open. Rows are 56 dp minimum, and the result is LRM-prefixed.
- **Deviation:** progress counts **whole moves**, "Move 5 of 17", to match Home's "17 moves". The engine counts positions, so the raw counter would have said "of 34".
- **"PGN" removed from the user-facing copy** on Home. `about_disclaimer` still says it, and About is out of scope.
- **Resume verified:** a cancelled analysis resumed at "Move 5 of 17" in 0.6 s.
- **RTL defects found and fixed:**
  - "17 moves" was reordered to "moves 17" beside a Hebrew date, so it is now wrapped in bidi isolates.
  - An English sentence's full stop landed on the wrong end, so those texts now use `TextDirection.Content`.
- **Stress passes** at font 1.5, 360x800 at 160 dpi, and RTL: no clipping. Device state was restored.
- **Not driven:** the Home Snackbar for an invalid file.

### Practice P1 + P2 (core): candidate lines, selector, judge — DONE, verified
- **Verified by me from the XML:** core 373/0/0 skipped (306 plus 67 new), app unit 29/0. `ANALYSIS_SPEC.md` has the new §11.
- **Real data contradicted the design:** it predicted "Nothing to fix" for the Immortal Game as White. The recorded Stockfish analysis gives:

| Fixture | Side | Puzzles |
|---|---|---|
| chesscom game | Black | 1 (ply 12: Nf6 was played, Qf6 best; only it is accepted, since the next line is 54 cp worse) |
| chesscom game | White | none |
| Immortal | White | 3, at plies 19 (10.g4), 33 (17.Nd5), 35 (18.Bd6); at ply 35 both Re1 and d4 are accepted |
| Immortal | Black | 3, at plies 22, 36, 40 |

  - **Why Black's ply 18 `b5` is excluded:** it started at 22.9% win, below the 25% floor, as designed.
  - **Pinned:** the tests pin what the data gives. The Immortal Game's puzzle count is engine-depth dependent, so the emulator check must expect puzzles, not "nothing to fix".
- **Agent decisions:**
  - `acceptedUci` excludes the move actually played. Otherwise a small-loss MISS could count its own played move as correct.
  - `evalSwingCp` is null across a mate boundary.
  - The dedupe window is inclusive, |Δply| ≤ 4.
  - A rules-verified mate in 1 gets goal `MateIn(1)`.
- **Judgeability rule held on real data:** no real qualifying ply was dropped, because all have k=3 with the last line far below the band.
- **desktop:** `:desktop:compileKotlin` passes unchanged.

### UX U5 (Summary hub) — DONE, verified
- **Verified by me:** the XML gives core 373/0/0 skipped, app unit 55/0 (29 plus 26 new), instrumented 66/0/0 skipped. I read the Summary screenshots (`r13_u5_01_summary_nouser.png`, `r13_u5_03_tapped_white.png`).
- **What it looks like:**
  - **Header card:** names, opening, move count, result, accuracy row, rating sentence.
  - **Chooser:** "Which side were you?" with White, Black and Not me.
  - **Key moments:** each has a "Show me what I missed" button.
  - **After choosing White:** "Adolf Anderssen (you)", "You / Opponent", and "Your key moments".
- **Side-chooser state:** `SideChoice {UNKNOWN, WHITE, BLACK, NOT_ME}`, with "Not me" kept separate from "no username", so Practise can show "choose a side" or hide itself. The choice persists on reopen.
- **Agent decisions:**
  - **Username:** the chooser fills the Settings username only when it is empty, so a user's other account is never overwritten.
  - **Chooser visibility:** it stays visible after an answer, so the answer can be changed.
  - **Brilliancies:** added as key moments, at most 2, in the mapper.
  - **Accuracy:** shown as a whole number.
- **Fix outside U5:** `MainActivity` re-read the launch share intent on every recreation (rotation, font scale, language), which re-imported the game and pushed a new analysis over the current screen. It now reads the intent only on first creation.
- **Known remaining:** annotation text from `:core` ("White drops a piece…") keeps the neutral wording after a side is chosen later, while the framing and buckets switch. Fixing it needs a `GameAnalyzer` re-run from the cached evals plus a Board refresh.
- **Observation, not a bug:** at the emulator's depth the Immortal Game's 18.Bd6 reads "Blunder". The app default is depth 14, and the Settings "Deep" preset is 18.
- **Practise slot:** marked with a comment, ready for P4.

- **2026-10-04: owner's standing rule — chess.com is the UX reference for every UI/UX decision not derived from the owner's goals.** Reference only: no assets, icons, copy or branding, and no implied affiliation.
  - Recorded in `CLAUDE.md` ("Design reference"), in memory, and in RUN_PLAN.
  - All earlier design decisions (U1–U6, the practice design) were made without a systematic chess.com reference. A Fable 5.1 alignment audit is running, and U7 and later wait for it.

### Chess.com reference alignment audit — DONE (Fable 5.1, high)
- Saved as `docs/CHESSCOM_REFERENCE_ALIGNMENT.md`. It used 17 public sources, with no login and no account. Items from memory are marked, and the live review screen and the store screenshots were not viewable.
- **Verdict:** the structure was already largely aligned. It adds five small adoptions (B1–B5) and edits to the steps not yet built (U7, U8, P3/P4). The divergences are deliberate and come from the owner's goals.
- **Defect found by the audit:** the walkthrough intro reads "…starts a line that The pawn on g4 cannot be held - taking it wins material..", a capital mid-sentence and a double full stop. It is a core sentence-assembly bug and goes into R1.
- **Colour caveat (owner decision, not blocking):** our green and board hexes are close to chess.com's brand values. A recommendation to use our own before any public listing is recorded in RUN_PLAN. Nothing changed now.
- **Queue revised** to R1–R7 (RUN_PLAN). The U6 agent's final verification is still running.

### UX U6 (Board) — DONE, verified (agent resumed after a session cut-off)
- **Verified by me from the XML:** core 373/0/0 skipped, app unit 68/0/0 (55 plus 13 new `BoardLogicTest`), instrumented 66/0/0 skipped. I also viewed `r13_u6_02_board_key_moment.png`.
- **What it does:**
  - **Layout:** the board row is exactly board-sized, with no empty band. Below it come chips, transport (first/previous/next/last, no autoplay) and a scrolling comment card. Flip is the only app-bar icon and is not persisted.
  - **Orientation:** it defaults from the user's colour.
  - **Chips:** each shows SAN, a quiet 18 dp badge and the score, at 48 dp minimum. The swing is gone from the chip.
  - **Highlight tier:** only BRILLIANT, INACCURACY, MISTAKE, MISS and BLUNDER are tinted.
  - **Collapse run:** a continuous 4 dp red band. The comment card names it and gives the move range.
  - **Mate chips:** M2, M1 and #.
- **Fixes the agent found by looking:**
  - The eval label wrapped at font 1.5.
  - "2.0+" was reversed in RTL.
  - Both are fixed. The label is pinned to font scale 1 and LTR, and the bar is 28 dp.
- **Deviations:**
  - GREAT is quiet, not tinted, because three GREAT chips in a row drowned out the mistake. This matches the chess.com "key moves" convention.
  - Chips keep the move number on White's moves, so about four items per chip.
- **Honest leftovers:**
  - A selected chip hides its tint. The badge still shows.
  - Core's annotation text still opens with the class name, and has a grammar slip ("a in-between move"). Both go into R1.
  - Landscape and split-screen are untested.
  - The chip row plus transport takes about a third of the screen.
  - The agent did not consult chess.com, because the rule arrived after it started. B1, B2 and B3 cover the board.
- **Emulator** has exited, and the app is uninstalled. The device state was restored before it exited.

### R1 (core text fixes: B3 coach text, walkthrough intro, B4 summary sentence) — DONE, verified
- **Reference used (owner's chess.com rule):** the coach text goes straight to the piece and the threat and never restates the icon's label (B3); a review opens with one sentence on how the game unfolded (B4). Patterns only; every word is ours.
- **Verified from the XML:** `:core` 424/0/0 skipped (373 plus 51 new), `:app` unit 72/0 (68 plus 4), `lintDebug` 0 errors, `:desktop:compileKotlin` and `:app:assembleDebug` OK (`--rerun-tasks`). Instrumented: no test asserts on annotation, intro or summary text (they only check `text.isNotBlank()`), so it was not re-run.
- **B3 / grammar (`CommentaryGenerator`):** the text never opens with the class word. "Better was X" is now the final sentence, once (a threat sentence comes before it). A MISS is one sentence ("Better was Qb8+, forcing mate in 3."). The a/an rule is one function, `core.text.EnglishGrammar`, used by the commentary and by the narration's `LessonSingleMiss`. The video narration does not use annotation text at all, so it is untouched.
- **Walkthrough intro:** core's `SimulationIntro` (typed `Sentence.WalkthroughIntro`) replaces the Android string. Two sentences: "Watch what happens: h5 starts the line." then the description normalised to one sentence. The old template grafted a finished sentence into "a line that ...".
- **B4 summary:** `GameSummarySentence` (spec §12, typed `Sentence.GameSummary`, nine kinds, all thresholds named). `GameReport.summarySentence` is filled by `toUiReport` and shown under the accuracy row.
- **Not done (stretch):** re-writing annotation text when the side changes. See the R1 report: the card and key-moment texts still say "White allowed ..." until a re-analysis; it needs the raw played tactics stored on the annotation and the VM's `games` map re-mapped on a side change.

### R1 (core text fixes + game-summary sentence) — DONE, verified
- **Verified by me from the XML:** core 424/0/0 skipped (373 plus 51 new), app unit 72/0.
- The agent ran lint (0 errors) and `:desktop:compileKotlin`. It skipped the instrumented suite, since the only instrumented text assertion is `isNotBlank()`.
- **Card text:**
  - It never opens with the class word now.
  - "Better was X" is the last sentence, once, after any threat the move let in.
  - **Before** (Immortal ply 22): "Blunder. This drops the pawn on g4. Better was h5, keeping material level. Black allowed a hanging piece on g5."
  - **After:** "This drops the pawn on g4. Black allowed a hanging piece on g5. Better was h5, keeping material level."
  - **Grammar:** a single a/an rule now handles "an x-ray", "an in-between move" and the like, and it is swept over every tactic type.
- **Walkthrough intro:**
  - **Before:** "…starts a line that The pawn on g4 cannot be held - taking it wins material.."
  - **After:** "Watch what happens: h5 starts the line. The pawn on g4 cannot be held - taking it wins material."
  - The sweep covers every `TacticType`, 15 awkward descriptions, every textbook reference, and all walkthroughs from both recorded games for no side, White and Black.
- **Summary sentence** (thresholds in spec §12): 
  - Immortal, as Black: "You were already under pressure, and a blunder on move 20 sealed it."
  - Chesscom, as White: "You won by checkmate on move 17, and neither side made a big mistake."
  - It never claims a resignation, since the report cannot tell that from a flag fall.
- **Video narration is untouched** (it does not use annotation text).
- **Stretch not done:** the card text still says "White allowed…" after a side is chosen later. It needs raw tactics stored on `MoveAnnotation`, regeneration in the mapper, and re-mapping the ViewModel's games.
- **Defects the agent found and left alone** go into R1b; they are listed in RUN_PLAN.

### R2 (board polish B1/B2/B5, U7 Video, U8 Walkthrough) — DONE (instrumented result below)
- **Chess.com patterns used (owner's rule; patterns only, our own art and words):**
  - **B1:** the classification icon sits on the move's destination square (our `ClassificationBadge`, top-right corner, 28% of a square, highlight tier only).
  - **B2:** a "Next" action jumps to the next key moment (ours: a text button "Next key moment ›").
  - **B5:** single-word class names ("Great", "Best").
  - **U7:** the coach has one audio control (one speaker icon that mutes the narration; playback continues).
  - **U8:** a walkthrough reads as a numbered guided sequence (our "Before the mistake", move label and "2 / 5" counter).
- **Files:** `ChessBoard.kt` (`BoardBadge`, overlay, inside the LTR pin, font scale pinned to 1 for the glyph), `ReviewScreen.kt`, `CommentCard.kt`, `ChessAnalyzerNavHost.kt` (`keyMomentPlies`), `BoardLogic.kt`/`SummaryLogic.kt` (`boardBadgeFor`, `nextKeyMomentPly`, `GameReport.keyMomentPlies`), `VideoLogic.kt` + `WalkthroughLogic.kt` (new), `VideoPlayerController.kt` (`setMuted`/`toggleMuted`), `VideoScreen.kt`, `TacticSimulationScreen.kt`, `strings.xml`.
- **Video:** bar is back arrow, "Video review", fullscreen only; bottom full-width "Save video" (`exportTapped()`, `VideoExportService.start(context, script, narrationProvider)` untouched); "Prepare narration" and its dialog removed (`NarrationCoordinator.isFullyPrepared` kept); speed is one cycle button (1x, 1.25x, 1.5x, not persisted); one mute icon (not persisted); transport row and scrub bar pinned LTR. "Game review" wording renamed to "video review" in the five strings.
- **Walkthrough:** back arrow plus one "Next"/"Done" button (pinned at the bottom); "Back to the game" removed; first step "Before the mistake" (textbook example keeps "Starting position"), later steps "8. Rg1" / "11... Qg6" numbered from the start FEN, plus an LRM-prefixed counter; arrows are auto-mirrored. "Try it yourself" deliberately not added (R3).
- **B5:** `classification_great` = "Great", `classification_best` = "Best" (now equal to core's `displayName`, which `PanelChipLabelTest` compares against).
- **Verified:** `:core` 424/0/0 skipped, `:app` unit 88/0 (72 plus 16 new), `lintDebug` 0 errors. Emulator evidence in `docs/screenshots/r13_r2_*.png`; an exported MP4 was pulled and checked on the host (see the R2 report).

### R2 (board badge, Next key moment, class names, Video U7, Walkthrough U8) — DONE, verified
- **Verified by me from the XML:** core 424/0/0 skipped, app unit 88/0 (72 plus 16 new), instrumented 66/0/0 skipped.
- **chess.com patterns used (patterns only):**
  - B1: the class icon sits on the destination square.
  - B2: a "Next" jumps to the next key move.
  - B5: single-word class names.
  - U7: one speaker control.
  - U8: a numbered guided sequence.
- **B1:** the badge is at the top-right of the destination square, 28% of the square, for highlight-tier classes only. It follows the square when the board is flipped and stays top-right in RTL. Its content description reads, for example, "Blunder on f3".
- **B2:** "Next key moment ›" walks the key moments and disappears at the last. It is hidden when the board is opened without a ply.
- **U7:**
  - Back arrow, "Video review", fullscreen only; a bottom "Save video".
  - One speed cycle button, 1× → 1.25× → 1.5×.
  - A mute icon, backed by `VideoPlayerController.setMuted`.
  - "Prepare narration" and its dialog are gone.
  - "game review" now reads "video review" everywhere.
- **U8:**
  - A single exit: the back arrow plus Next/Done, so "Back to the game" is gone.
  - The first step is "Before the mistake", then "8. Rg1" with a counter "2 / 5".
  - RTL fixes: a misplaced full stop, and auto-mirrored arrows.
- **Export evidence (scholar's-mate game):**
  - **File:** 222.9 s MP4 from `/sdcard/Movies/ChessAnalyzer/`, AAC 44.1 kHz mono plus h264. The audio is not silent: RMS −25.3 dB, peak −6.3 dB.
  - **Voice:** it was the Kokoro neural voice. A cached 24 kHz narration WAV cross-correlated with the MP4's audio at **0.999** (193.2 s), the log shows `loaded KOKORO`, and 21 segments took about 8 minutes.
  - **Source of Kokoro:** the app's own `ensureDefaultNeuralVoice` fetched it over the emulator network. That goes away with bundling.
- **Problems found (go to R1b):**
  - A 4-move game gave a 3 min 43 s video. Pacing is from core, and the budget base is too large for tiny games.
  - The last walkthrough step reads "Result: has invested material in the attack".
- **Known:** unmuting a device-TTS segment mid-sentence stays silent until the next segment. Fullscreen video and Board landscape were not tested.

### R3 (Practise screens P3 + P4) — DONE, verified
- **Verified by me from the XML:** core 424/0/0 skipped, app unit 127/0 (88 plus 39 new), instrumented 66/0/0 skipped. I viewed `r13_r3_11_hint2.png` (the two-step hint) and `r13_r3_02_summary_white_bottom.png` (the Summary with the entry card).
- **Real puzzle counts on the emulator** (live depth, not the recording):

| Game | Side | Puzzles |
|---|---|---|
| Immortal | White | 3 (plies about 15, 19, 35; the recording had 19, 33, 35) |
| Immortal | Black | 3 |
| chesscom | Black | 2 (recorded 1) |
| chesscom | White | 0, with the "Nothing to fix" card |

  - Counts are depth-dependent, as predicted.
- **Practise flow verified on the emulator:**
  - **Tap-to-move:** an opponent piece does nothing, an own piece shows dots, and a wrong target gives "Not quite" with a red cross.
  - **Hint:** two steps. The first pre-selects the piece, the second dots the target without playing it.
  - **Correct:** shows the arrow and a green check.
  - **Next, Done, resume:** Next runs through all puzzles to "Solved N of M", and re-entering resumes at the first unsolved.
  - **Entry points:** "Try it" on key-moment cards, and "Try it yourself" on the last walkthrough step.
  - **State:** it survives rotation and a trip into the walkthrough.
- **Bug found and fixed in the board's tap path (never exercised by any screen before):**
  - `ChessBoard`'s `pointerInput` captured the first `onSquareClick` lambda forever, so a lambda closing over per-puzzle values acted on stale data after Next.
  - It now uses `rememberUpdatedState`, and hit-testing moved into a pure `squareAtOffset`, with tests for both orientations and all 64 squares.
  - Verified on the device only, since there is no compose-test dependency for the stale closure itself.
- **chess.com patterns used:**
  - A two-step hint.
  - A check or cross badge.
  - "Try it" on key moments.
  - No rating, points, streaks or timer.
- **Judgement calls:**
  - The last puzzle's button reads "Next", leading to the "Solved N of M" card whose "Done" exits.
  - "Look for a x-ray" was fixed in code via `EnglishGrammar.withArticle`.
  - Landscape is usable but poor.
- **Seen in the Summary screenshot, for R1b:** "20… Na6 Mistake. This forces mate." claims the mover forces mate.
- **Not done:** P5 (persisting solved puzzles), and the walkthrough "Result: has invested material in the attack" line.

### R1b (commentary claims, tiny-game pacing, side-aware text) — DONE, verified
- **Verified by me from the XML:** core 478/0/0 skipped (424 plus 54), app unit 132/0, instrumented 66/0/0 skipped.
- **The audit** (`docs/COMMENTARY_AUDIT.md`, `scripts/audit_commentary.py`): every annotation text and walkthrough of both recorded games was re-derived with python-chess, independent of `:core`.

| | Before | After |
|---|---|---|
| 78 annotation texts (supported / flavour / WRONG) | 29 / 0 / **49** | 75 / 3 / **0** |
| Walkthroughs | 16 total, **15 WRONG** | 15, all supported |

- **The 119 WRONG claims by cause:**
  - 15 motifs credited to the wrong move or side.
  - 31 outcome verbs stronger than the board ("wins the queen" when a pawn merely attacks it).
  - 29 "allowed" charges against engine-approved moves.
  - 7 detector motifs that were not what they said.
  - 35 unprovable additions ("has invested material in the attack").
  - 1 "stunning sacrifice" that was not one.
  - 1 intro that repeats the first move.
- **Rule now:** "attacks, never wins", and every claim is verified on the board. An unverifiable sentence is dropped, not hedged.
  - Spec §7.2 and §6.1 record the rules.
  - A testing-standards note was added to `CLAUDE.md`.
- **Classification change:** 14.Rd1 was "Brilliant". Its only attacker is pinned, so it is no longer called a sacrifice.
- **Examples:** 20...Na6 now reads "This lets White play Nxg7+, which starts a forced mate. Better was Ba6." The brilliant 10.Nxb5 reads "Nxb5 is a sacrifice: it offers the knight on b5."
- **Budget:** `min(720 s, 120 s + 14 s × moves, 23 s × moves − 32 s)`.

| Moves | Game | Before | After |
|---|---|---|---|
| 4 | scholar's mate | 236 s | 54 s |
| 8 | Byrne–Fischer, first 8 moves | 102 s | 90 s |
| 17 | Opera Game | 327 s | 314 s |
| 23 | Immortal Game | 441 s | 431 s |
| 40 | Byrne–Fischer, first 40 moves | 600 s | 543 s |

- **Side-aware text:** `MoveAnnotation.tacticsPlayed` was added, and `CommentaryGenerator.regenerate` is a pure function of annotation plus side. The ViewModel re-maps on a side change.
  - Live on the emulator, the same card reads: "This lets Black play Qg5…" (no side), "…your opponent…" (as White), "This lets you play…" (as Black), and neutral again after Not me.
- **Honesty note from the agent:** it added most tests after the fixes and proved they fail on the old behaviour (43 do), instead of writing them first.
- **Found, not fixed:**
  - The video's material cut-offs name a rook for any gain of +500 or more.
  - `LessonPositive`'s "gap is in the tactics" diagnosis is unsupported.
  - A BOOK move that loses 5% or more still offers "Show me" beside "follows known opening theory".
  - Engine-line motifs remain heuristics, labelled as the engine's line.


### R4a checkpoint B1 (:engine bundled net) — DONE, verified
- **Done:** `:engine` has the assets srcDir for `vendor/models/engine-assets`, `androidResources.noCompress`, a `verifyBundledModels` task (size + SHA-256 prefix from `MODELS.lock` and `evaluate.h`; writes `GeneratedBundledNetConstants`), and `BundledNetProvider` (`ensureNet(onProgress)`, restart-on-failure `.part`, atomic move, in-process verified-stamp so a verified net is not re-hashed). `NetworkProvider`, OkHttp and `org.json` are gone from `:engine`. `GeneratedNetworkConstants` and the `setEvalFile()`/`analyze()` guards are untouched; `missingNetThrowsInsteadOfKillingTheProcess` is unchanged.
- **Engine tests:** both rewritten to take the net from the provider (no `/data/local/tmp`, no `assumeTrue`), plus the new `BundledNetProviderInstrumentedTest` (11 tests). `:engine:connectedDebugAndroidTest` = 20 tests, 0 failures, 0 skipped (XML). The library androidTest APK reads its assets, so the design's fallback (keeping `push_test_net.sh`) was **not** needed.
- **Build state:** `:engine:assembleDebug` OK. `:app` does not compile until B2 (in progress). **Next:** B2 (`:app`), then B3 (tests).

### R4a checkpoint B2 (:app bundled models) — DONE, `assembleDebug` + unit tests green
- **Done:** `:app` build file (assets srcDir, `noCompress` `.nnue`/`.tar`, `verifyBundledModels` + `GeneratedBundledVoiceConstants`, `installation.timeOutInMs`, no OkHttp, `libs.okhttp` removed from the catalog); `BundledVoiceInstaller` (replaces `VoiceModelProvisioner`; marker holds the pinned tar hash); `FirstRunSetup` + `SetupResult`; `AnalysisService` runs setup after the parse and before the eval-cache lookup (phase `FIRST_RUN_SETUP`, failures `SETUP_STORAGE`/`SETUP_DAMAGED` with strings); `AutoVoicePolicy.kt`, `NetworkProvider`, `ensureDefaultNeuralVoice`, download/cancel/delete/tier methods, `checkForUpdates`, `DOWNLOADING_NET`, the byte fields and the metered gate are deleted; Settings lost only the tier picker, Download/Cancel/Delete/Wi-Fi-only UI and "Check for updates"; `NeuralVoiceTier` is `KOKORO` only; `NarrationVoiceSettings.provider` and `ResolvedProvider` default to `NEURAL`; `setProviderAutomatically` removed; manifest has no `INTERNET`, `ACCESS_NETWORK_STATE` or `usesCleartextTraffic`; backup rules use the `.part` suffix.
- **Build state:** `:app:testDebugUnitTest` 142 tests, 0 failures, 0 skipped (132 plus 10 new: `ManifestPermissionsTest` 6, `BackupRulesTest` +2, `ResolvedProviderTest` +2). `:app:assembleDebug` OK: the APK is 371,058,258 bytes and `unzip -lv` shows both assets **Stored** (`classes.dex` is deflated).
- **Next:** B3 (instrumented tests: `BundledVoiceInstallerInstrumentedTest`, `FirstRunSetupInstrumentedTest`, `NoNetworkPermissionTest` written; run `:app:connectedDebugAndroidTest`), then the emulator verification.

### R4a checkpoint B3 (:app instrumented tests) — written; first full run 75/78 green, 3 fixed
- **Tests:** deleted `AutoVoicePolicyInstrumentedTest` (12) and the Piper test; rewrote `NeuralTtsProviderInstrumentedTest` (Kokoro installed from the APK, duration and RMS assertions and the pullable evidence WAV kept), `VideoExportNeuralVoiceEvidenceTest`, `VoiceSampleSweep`, `EndToEndAnalysisTest` (no seeding, no `assumeTrue`), `NarrationSettingsRepositoryTest` (NEURAL default), `NarrationProviderSelectionTest`, `ResumeAnalysisTest`; added `BundledVoiceInstallerInstrumentedTest` (15), `FirstRunSetupInstrumentedTest` (6), `NoNetworkPermissionTest` (4), host `ManifestPermissionsTest`. Deleted `scripts/push_test_net.sh` and `push_voice_models.sh`.
- **First full run (39 min): 78 tests, 3 failures, 0 skipped.** (1) and (2) were my new `NoNetworkPermissionTest` assertions: the platform denies the socket with **EPERM** (`socket failed: EPERM (Operation not permitted)`), not the design's EACCES (the test now accepts the two permission errnos and rejects ECONNREFUSED), and the package also requests an AndroidX own-namespace signature permission (the test filters to `android.permission.*`). (3) `VideoPlayerControllerInstrumentedTest.playbackFallsBackToTtsWhenNoFileIsCached` timed out at 8 s waiting for the Google TTS service to cold-start (logcat: the TTS process was started 5-6 s before); it passes alone and after the setup tests, so its TTS-init wait is now 30 s.
- **Emulator check:** done (screenshots `docs/screenshots/r13_r4a_*.png`). **Next:** the full gate and both connected runs.

### R4a final gate (B1+B2+B3) — results, read from the XML
- `:core:test` 478 tests, **1 failure**, 0 skipped: `OpeningBookTest.loads all 3810 openings quickly` asserts a 2000 ms wall clock and measured 2.8-8.7 s on this (loaded) host, also when run alone. `:core` is untouched by this round; a timing flake, not a defect. `:desktop:compileKotlin`, `:engine:assembleDebug`, `:app:assembleDebug` OK; `:app:testDebugUnitTest` 142/0/0; `lintDebug` 0 errors.
- `:app:connectedDebugAndroidTest` **78 tests, 0 failures, 0 skipped** (was 66: minus 12 AutoVoicePolicy and 1 Piper test, plus 15 installer, 6 first-run, 4 no-network, and the rest renamed or merged).
- `:engine:connectedDebugAndroidTest` **20 tests, 0 failures, 0 skipped.** One earlier run of `benchmarkDepth18` hit its own 300 s per-position cap on the slow host; the cap is now 900 s.

### R4a (bundled models B1 + B2 + B3) — DONE, verified
- **Verified by me from the XML:**
  - **Core:** 478 tests with 1 failure, which I traced and fixed (below). After the fix, 478/0/0 skipped, from a clean `--rerun-tasks`.
  - **App unit:** 142/0/0.
  - **App instrumented:** 78/0/0 skipped, which is 66 minus 13 (12 `AutoVoicePolicy` tests plus the Piper test) plus 25 new (15 installer, 6 first-run, 4 no-network).
  - **Engine instrumented:** 20/0/0.
  - **Lint:** 0 errors.
  - I viewed `r13_r4a_01_setup.png`: "Analyzing game / Setting up the engine (one time)…", a determinate bar, no byte counter.
- **Debug APK:** 371,058,258 bytes. The net and the voice tar are both **Stored**.
- **First run on the emulator:** setup took about 20–30 s, and the second analysis showed no setup phase.
- **No network, proved on the device:**
  - No `shared_prefs` directory.
  - logcat has 0 matches for `stockfishchess|github.com|okhttp`.
  - `dumpsys` lists only `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` and `POST_NOTIFICATIONS`.
  - A socket without INTERNET fails with **EPERM** on API 34, not the design's EACCES. The test accepts either permission error and rejects ECONNREFUSED.
- **Flaky test fixed by me:** `OpeningBookTest` "loads all 3810 openings quickly" asserted a 2 s wall clock.
  - It measured 0.88 s alone but 2.8–8.7 s while an emulator suite shared the host.
  - I loosened it to 15 s as a catastrophic-regression guard only. All correctness assertions are unchanged.
- **Other agent-reported adjustments:**
  - **TTS init wait:** 8 s raised to 30 s in `VideoPlayerControllerInstrumentedTest`.
  - **Benchmark cap:** `EngineBenchmarkTest`'s 300 s cap raised to 900 s.
  - **SETUP_DAMAGED:** it hides "Try again".
- **A slip in the agent's check:** the helper shared the Immortal Game first, so the first analysis was that game, not the chesscom game. Not a defect in the app.
- **Not done:** a narrated export on the bundled copy (the instrumented Kokoro and evidence tests did run from the bundled copy and pass). The airplane-mode proof is R5.
- **Stale text left for B4:** About still says the voice is "downloaded on request" and names Piper; `CLAUDE.md`, `HANDOFF.md` and the docs still describe seeding and the push scripts; the espeak-ng credit is missing.
- **Runtime note:** the full `:app` connected run takes 20–40 minutes, and a 371 MB `adb install` about 60 s.

### R4b / UX U9 (Settings rebuild) — DONE, verified
- **Verified by me from the XML:** core 478/0/0 skipped, app unit 164/0/0 (142 plus 22 new `SettingsLogicTest`), app instrumented 78/0/0 skipped. I viewed `r13_r4b_02_advanced_expanded.png`.
- **Controls:** 26+ before. Now 4 visible rows (Your name, Language, Advanced ›, About ›) and 4 behind Advanced: Analysis strength (Quick / Standard / Deep), What the review talks about (Only big moments / Balanced / Every move), a built-in-voice switch, and Saved narration audio with Clear.
- **Evidence it takes effect (on the emulator):**
  - **Analysis strength:** after "Deep", the stored game and all 36 cache entries read `"depth":18`. After "Quick", all read `"depth":12`.
  - **Voice switch:** the DataStore holds `DEVICE` with `explicit=true` when on, and `NEURAL` with `explicit=true` when off.
  - **Custom values:** a crafted depth 16 / threshold 70 shows "Custom (16)" and "Custom (±0.7)".
  - **Language:** English stores `en`.
  - **Saved audio:** the Clear button empties the folder.
- **Removed:**
  - **Time per move:** dead, since nothing read it.
  - **MultiPV:** now always 3, so an old stored value can no longer degrade the tactic gate.
  - **Settings UI:** the Engine card, the About card and the orientation preference.
  - **Strings:** the dead GPL notice and dialog strings.
  - **Settings model:** `EngineSettings` lost `timePerMoveMs`, `engineVersion` and `netVersion`.
- **Deviations (agent):**
  - A stored value outside the presets shows as a "Custom (N)" line below the segments, not a fourth segment, because at 360 dp "Standard" broke mid-word.
  - From font scale 1.3 the segmented rows become vertical radio rows.
- **Not done:**
  - The below-API-33 disabled Language path was not seen (the emulator is API 34).
  - The `about_license_*` strings are untouched and go to the docs step.
  - `EngineInfo.VERSION_LABEL` is unread and left in place.
- **Observation:** the "Your name on Chess.com / Lichess" label names the sites descriptively (to explain which username), not as affiliation. The About disclaimer stands.

### R4c (B4: docs, licence texts, About strings) — DONE, verified
- **Verified by me:** app unit 164/0/0 and core 478/0/0 skipped from the XML. The agent ran `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug --rerun-tasks` green (lint 0 errors, 71 warnings). No test asserts on About or licence text, so it skipped the 20–40 min instrumented suite.
- **Docs made true:** `CLAUDE.md`, `HANDOFF.md`, `README.md`, `docs/PUBLISHING.md`, `docs/STORE_LISTING.md`, `docs/NEURAL_VOICE.md`, `vendor/STOCKFISH_VERSION.txt`.
  - The "Re-seed the NNUE net" gotcha is gone ("Nothing to seed").
  - All engine guard text is kept, plus a "never point it at `/proc/self/fd/N`" line.
- **espeak-ng licence, verified from primary sources:**
  - The Kokoro tar holds a `LICENSE` (Apache 2.0) and a `README.md` that only links to Hugging Face. `espeak-ng-data/` (392 files) has **no licence file of its own**.
  - The sherpa-onnx README never mentions espeak-ng or GPL, and its issue #4002 asking for provenance was open with no reply.
  - The espeak-ng README says it is "released under the GPL version 3 or later license", and its `COPYING` is plain GPLv3 with no separate data licence.
  - **Conclusion:** the project's licence is confirmed as GPL-3.0-or-later. That the bundled data is under exactly that licence file by file is **unverified**, and the docs say so. About reads "espeak-ng pronunciation data (from the espeak-ng project, GPL version 3 or later)".
- **NNUE provenance** (Leela Chess Zero data, ODbL) is now in About, with no claim about whether a notice is required.
- **Cleanup:**
  - 13 certainly dead strings deleted.
  - `EngineInfo` and `VERSION_LABEL` removed (a grep proved they were unread).
  - The About licence-dialog rulers were shortened because they wrapped badly.
- **Grep gate:** the remaining mentions of Piper, `ElevenLabs` and the like are deliberate history. The one stale item, a comment in `engine/src/main/cpp/CMakeLists.txt` ("downloaded at runtime by NetworkProvider"), I fixed myself.
- **Noticed, for R6:** About shows a doubled "Stockfish chess engine (Stockfish (sf_19 @ edb0d9d))". `progress_downloading_engine` is used but misnamed (its text is "Starting…").
- **Debug APK:** 371,046,958 bytes, with both assets stored.

### R6a (U10 accessibility / large-font / landscape / RTL pass, measured export time left, Share) — DONE, verified
- **Verified from the XML:** `:core` 478/0/0 skipped, `:app` unit 193/0/0 (164 plus 29 new: `ExportTimeLeftTest` 17, `ThemeContrastTest` 8, `BoardSemanticsTest` 4), `:app` instrumented **91/0/0 skipped** (78 plus 13: `AccessibilitySemanticsTest` 9 and `VideoShareInstrumentedTest` 4; neither uses `assumeTrue`), `lintDebug` 0 errors (70 warnings, was 71). Gate run with `--rerun-tasks`.
- **chess.com pattern used (owner's rule, pattern only):** the board takes the whole width in Practise and sits beside the controls in landscape (chess.com's board takes the whole short side); nothing was copied.
- **What was wrong and is fixed** (per screen; `uiautomator` shows only text, descriptions, bounds and clickability, so headings, states, live regions and actions are asserted by `AccessibilitySemanticsTest` instead):
  - **Every screen:** titles were not headings and clipped at 2.0 (`AppBarTitle`: heading, one line, capped at 1.3x); the move-quality badge glyph was read as "question mark question mark" (hidden unless it stands alone).
  - **Home:** the paste field had only a placeholder (now a label); two titles are headings; the recent-game name was cut to two lines at 2.0.
  - **Analysing:** two progress bars were two TalkBack stops (the ring is hidden, the bar says "Move 5 of 23"); the error title is announced; setup, progress and error scroll instead of clipping in landscape and at 2.0; buttons 48 dp.
  - **Summary:** six section titles were not headings; tactic and "smaller ones" rows were 32 to 40 dp tall (now 48); the table was read cell by cell (now one sentence per row); the accuracy bars repeated the percentage; the tactic name was squeezed to "Hangin / g piece" at 2.0 (the button drops below the name); descriptions were cut at four lines at 2.0.
  - **Board:** the board was an unlabelled canvas (read-only board: one stop listing both sides' pieces; eval bar and eval graph now speak); move chips were read twice and had no role or selected state; the card is a polite live region so a step is spoken; landscape was unusable (board 160 dp with the controls off screen): board left, chips, transport and card right.
  - **Walkthrough:** the landscape board was 800 dp tall (now side by side); the step card is a live region; the textbook offer lost its full stop in RTL (`TextDirection.Content`).
  - **Practise:** the playable board was invisible to TalkBack (64 square nodes, "White pawn on e2", selected and possible-move states, double tap plays); wrong, hint, correct and revealed are spoken (one polite live region); landscape put the card off screen (fixed); the portrait board is edge to edge so squares are 51 dp.
  - **Video:** the speed button said only "1x" (now "Playback speed, 1x", using the kept `video_speed`); the scrub bar had no name or position; the frame had no description; the mute toggle is announced; landscape put every control below a full-width picture (picture left, controls right); the completed dialog has Close, Open and Share that wrap at 2.0 and scroll.
  - **Settings:** the built-in-voice switch had no label (the row is now the switch); radio rows had no selected state; "Clear" was ambiguous (now "Clear saved narration audio"); the name label overflowed its card at 2.0.
  - **About:** the back arrow had `contentDescription = null` (the only unlabelled clickable found by reading the source); four link rows were 18 to 24 dp (now 48); headings; the version lines are centred; the doubled engine line is fixed ("Palaya Chess uses the chess engine Stockfish (sf_19 @ edb0d9d).").
  - Unused strings: `cd_board_square`, `cd_piece` and `video_speed` are now used; none was deleted.
- **Contrast (WCAG AA, computed from `Color.kt`, asserted by `ThemeContrastTest`):** failures found and fixed in the semantic roles: error text 3.2:1 on a card (new `ErrorText`, 4.3:1 or more); selected chip / segment 3.2:1 (new `GreenContainer`, 7.2:1; the unspecified segmented-button container had been the baseline purple); secondary text on a tinted chip 4.0:1 (`OnDarkSecondary` raised, 4.9:1 or more); outlines of text fields, outlined buttons and the switch 1.2:1 (new `OutlineStrong`, 3.2:1); board coordinates 2.8:1 (new inks, 6.4:1 and 5.1:1); class-name text on a card 3.2 to 4.4:1 for six classes (`legibleTextColor` lightens only as far as needed); badge glyph white on the palette 2.2 to 3.6:1 (near-black ink, 4.9:1 or more). The move-quality palette (`Class*`) and the glyph characters are unchanged. **The visible consequence is that badge glyphs are now dark on the badge colour; if the owner prefers white glyphs, `BadgeGlyphInk` is one constant** (white fails AA).
- **Touch targets:** every clickable is 48 dp except the board squares (width / 8: 51 dp on a 411 dp phone with the edge-to-edge Practise board, 45 dp at 360 dp, about 41 dp in landscape); that is geometry, not a fixable defect.
- **Emulator (chess34, API 34), `uiautomator` audits (no unlabelled clickable and none under 48 dp once nodes cut by the viewport are excluded, apart from the board squares above):** Home, Analysing (setup phase and error state), Summary and Details, Board, Walkthrough, Practise, Video, Settings (collapsed and Advanced) and About at font 2.0, at 360x800 / 160 dpi (also with font 2.0), in he-IL, and in landscape (Board, Practise, Walkthrough, Summary, Video). Screenshots `docs/screenshots/r13_r6a_*.png`, each viewed.
- **Export "about N min left" (`ExportTimeLeft.kt`, fed by `VideoExportService`, pure and unit tested):** shown on the narration step from the third finished segment (two clean measurements: the first segment pays for start-up), rounded up to whole minutes, "Less than a minute left" at the end; a running mean of the measured per-segment time with each sample capped at twice the median; the display only falls unless the estimate rises by 2 minutes or 25 percent. No guessed constants. **The first version showed "About 34 min left" at 2/48 and "12 min" four segments later** (Immortal Game, 48 segments, host busy with Gradle), so it now starts at 3 done and allows a rise only by max(2 min, 25 percent). Final run, 17-move game, 28 segments: 13, 11, 9, 8, 7, 6, 6, 6, 6, 5, 5, 5, 4, 4, 4, 3, 3, 2, 2, 2, 2 minutes, then "Less than a minute" from 25/28; it never rose. The first figure was about 1.7 times too high because the first segments are slower (cold voice). Narration took about 9.5 min; rendering and finalizing followed (the line covers narration only).
- **Share (`shareExportedVideo`, `buildVideoShareIntent`):** there already was a "Share" button; it used the MediaStore URI and threw if no share target existed. Now `ACTION_SEND` with the MP4 from `cacheDir/video_export/` through the app's FileProvider (`file_paths.xml` already covers it through its `cache-path`, so no change), a read grant on that one URI, a fallback to the MediaStore copy, and a plain "Couldn't open sharing..." line instead of an exception. The dialog buttons are Close, Open, Share. Evidence on the device: the chooser said "Sharing 1 file chess_review_<n>.mp4"; `dumpsys activity` listed `readUriPermissions content://net.palaya.chessanalyzer.fileprovider/cache/video_export/chess_review_<n>.mp4`; the cache file and the `/sdcard/Movies/ChessAnalyzer` copy have the same SHA-256 (two exports); `ffprobe`: h264 plus aac, 303.8 s; the installed package's requested permissions are unchanged (foreground service, data sync, notifications). Not driven on a device: the share-failed line (a chooser always exists on the emulator); the null-intent path is covered by the instrumented test.
- **Limits:** no TalkBack run (the semantics are asserted in the accessibility tree, not heard); the timing evidence comes from a CPU-contended host; the paste field shows its label instead of the example text while empty.
- **Found while testing, not fixed:** in he-IL narration is reported unavailable (no Hebrew narration strings); the video poster still says "45 plies" (R6b).

### R6b (recap end card in the exported MP4, "plies" -> "moves") — DONE, verified
- **Verified from the XML:** `:core` **486/0/0 skipped** (478 plus 8: `GameRecapTest`), `:app` unit **212/0/0** (193 plus 19: `RecapCardTest`), `:app` instrumented **97/0/0 skipped** (91 plus 6: `VideoRecapExportInstrumentedTest` 5, one new case in `PanelChipLabelTest`; none uses `assumeTrue`), `lintDebug` 0 errors (70 warnings, unchanged).
- **chess.com pattern used (owner's rule, pattern only, nothing copied):** Game Review's summary hierarchy: accuracy per side first and large, a move-quality count per side under it, one short verdict below. The layout, palette (the video's own), chip look and every word are ours.
- **What the card holds** (`VideoScript.recap`, built by `GameRecap` in `:core`, drawn by `BoardFrameRenderer.renderRecapFrame`, 1280x720 like the rest of the video; the "1080 wide" in the brief is the phone, the video is 1280 wide): heading "Game recap"; per side a colour dot, the name (with "(you)" for the viewer's side), the accuracy as the Summary writes it (`%.0f%%`, same 85/65 colour bands, now one shared `accuracyBand`), count chips (Brilliant, Great, Inaccuracy, Mistake, Miss, Blunder, non-zero only, in the class colours with near-black ink); the `GameSummarySentence` text for the same viewer; "Biggest moment: move N, SAN (Class by Side)" with a class-coloured dot.
- **What proves each part (no new chess claim):** names are the title card's; accuracy and counts are `PlayerReport` numbers (`GameRecapTest` recounts the counts from the annotations on all four recorded games, three side choices each); the sentence is `GameSummarySentence.text`, word for word; the moment is `GameSummarySentence.turningPoint`, the move the sentence names, and only when it lost 20 or more win-percent (the sentence's own line), else no line (a clean game says nothing; seen in the 17-move game with 25-character names). Tests: consistency with `GameSummarySentence.build(...).moveNumber`, the boundary at 19.9 / 20.0, no moves = no recap.
- **Silent, deliberately.** Narrating it would add a `ScriptSegment`, which changes the segment count behind "N of M" and the measured time-left. So it is not a segment: `totalEstimatedMs` and the §9.7 budget are untouched (stated in ANALYSIS_SPEC §9.7), and the in-app player's timeline is unchanged (playback ends on the last lesson card; only the MP4 carries the recap, which is what the brief asked for). Duration 4 to 6 s: 3 s plus 100 ms per word of the two sentences, clamped (`recapDurationMs`). `TimelineBuilder.build` is unchanged; `ScriptTimeline.withRecap` is applied by the exporter, which pads the AAC track with silence so both tracks end together and draws the identical recap frame once.
- **Text fit** (pure, `RecapCard.kt`, tested with a fake measurer): `fitLine` (a name shrinks from 40 px to 23 px, then ends in an ellipsis at its logical end, never wider than its box), `fitParagraph` (sentence and moment line step down, then truncate at 3 and 2 lines), `flowChips` (rows, nothing dropped). On the device, `renderedCardNeverReachesTheMarginsOrTheCentreChannel` renders 8 name pairs (1 character up to 40+, Hebrew, 60-letter Hebrew, mixed) and checks pixels: the outer margins and the strip between the columns are still background.
- **"plies" -> "moves":** the only user-visible occurrence was the title card of the video (`CardResultLine`, "1-0 · 34 plies", which is also the video's first frame in the player). It now takes full moves, `(plies + 1) / 2`, like the Summary and Home ("17 moves", "1 move"). Home and Summary already used the `recent_moves_count` plural with FSI/PDI and LRM for RTL; nothing else in strings.xml or the text generators said ply. `docs/NARRATION_STRINGS.md` updated. Test: `GameRecapTest` (33 plies prints "17 moves", 1 ply prints "1 move", no script text on any of four games contains "ply" or "plies"). Ply-based internals untouched.
- **PanelChipLabelTest kept consistent:** `PanelLabels` gained `recap: RecapLabels` with an English default, so hand-built labels still compile; a new case asserts the recap names every class with the same resource string as the panel chip.
- **Device evidence (emulator chess34, API 34, debug build, real narration with the bundled Kokoro voice), MP4s pulled with `adb pull`, measured on the host with ffprobe/ffmpeg:**
  - Short game, names "Al" / "Bo" (4 moves): 57.47 s, h264 1280x720 + aac; the card starts at about 52.8 s (4.7 s, 16 words); mean volume of the last 3.7 s is -91 dB (digital silence) against -25 dB for the narration. Dialog "57 s · 8.9 MB".
  - Hebrew names (4 moves): 85.98 s; last 3 s -91 dB; the card shows both Hebrew names centred, "(you)" on Black.
  - Long names, "GrandmasterMorphyFan1857X" / "DukeOfBrunswickAndCount_1" (17 moves): 295.17 s, last 3.5 s -91 dB; both names at full size on the card, no biggest-moment line (no move lost 20 points).
  - Instrumented export test (tone narrator, so silence means something): the file with the card is longer than the file without by the card's duration (within 150 ms); the last three sampled frames match the locally rendered card (mean diff 1.8) and the frame 700 ms earlier does not (14.4); audio and video end within 200 ms; RMS 7795 on the narration, 0.0 on the card; no extra sentence was sent to the voice.
  - Screenshots viewed: `r13_r6b_recap_short_en_end`, `r13_r6b_recap_short_he_end`, `r13_r6b_recap_long_end`, `r13_r6b_recap_fit_shortest`, `_fit_longest_en`, `_fit_he`, `_fit_overlong_he`, `r13_r6b_recap_clean_game`, `r13_r6b_poster_short_en` (the player's first frame: "1-0 · 4 moves"), `r13_r6b_poster_short_en_frame` / `_short_he_frame` / `_long_frame` (MP4 frame at 0.5 s), `r13_r6b_poster_long`, `r13_r6b_poster_short_he`, `r13_r6b_before_recap_short_en`.
- **Not done / found, not fixed:**
  - **Hebrew locale (he-IL) export not run:** narration is unavailable there (R6a), so there is no export to look at; the Hebrew-name case was checked under en-US with Hebrew PGN names, which is what exercises the card's bidi handling. The card's own words ("Game recap", "Biggest moment...", chip names) come from resources and have no Hebrew translation yet.
  - **The intro card overflows with very long names** (pre-existing, not part of this task): with 25-character names the title wraps to two lines, the "White: ... Black: ..." line wraps too, and the last sub-line (the opening name) runs under the caption bar. `renderCardFrame` has no fit logic; `fitLine` / `fitParagraph` could be reused for it. Screenshot `r13_r6b_poster_long_frame.png`.
  - The intro card and outro still print accuracy with one decimal ("Black 7.2%") while the Summary and the recap print whole percent ("7%"). Left as is.
  - In the Hebrew-name intro card the "White: <Hebrew> · Black: <Hebrew>" line mixes directions (pre-existing).
  - Device state: `wm size`, density, font scale, locale and rotation were never changed in this task (1080x2400, 420, 1.0, en-US, 0), so nothing to restore.


### R6c (intro and final-numbers cards: fit, each fact once, whole percent, bidi) — DONE, verified
- **Verified from the XML:** `:core` **490/0/0 skipped** (486 plus 4: `CardTextTest`), `:app` unit **229/0/0** (212 plus 17: `CardLayoutTest`), `:app` instrumented **103/0/0 skipped** (97 plus 6: `CardFrameFitInstrumentedTest`; none uses `assumeTrue`), `lintDebug` 0 errors (70 warnings, unchanged).
- **chess.com pattern used (owner's rule, pattern only, nothing copied):** Game Review's header hierarchy: the two players first and large with each rating beside its name, the facts about the game (result, opening) on one quiet line under them, the accuracies as their own row. The intro card takes that order (title = players with ratings, one subtitle line, one accuracy line) and draws it in the video's own palette and words.
- **Defects, and what fixed each:**
  1. *Overflow.* `renderCardFrame` had no fit logic. It now takes a `CardContent` (`video/CardLayout.kt`, pure) and fits every line: the title is one line that shrinks (64 px down to 42 px at 720 p), then stacks the two names on two lines at one common size (54 px down to 29 px), then ends in an ellipsis (`layoutTitle` on `fitLine`); grey body paragraphs use `fitParagraph` (a bounded number of lines) or `fitParagraphToHeight` (a lesson that fills the room, built on `fitParagraph`); green fact lines use `fitLine`. Text lives in a column 10 percent in from each side, below the chapter bar and above the caption bar's 9.5 percent plus 3 percent air, kept clear whether or not a caption is drawn (`CardGeometry`); a block still too tall steps every size down together. `flowChips` was not needed: these cards have no chips (the recap's chips are untouched).
  2. *Repeats.* The intro is now `[title with both names and ratings] [one subtitle "1-0 · 17 moves · Opening (ECO)"] [one accuracy line "White 84% · Black 79%"]`. The generator writes exactly `[subtitle, accuracy]` as the card's lines; the app's "White: .. · Black: .." / "Result: .." / opening sub-lines are deleted. The final-numbers card lost its structured sub-lines too (they repeated the names and accuracy of its own lines). The intro and the final numbers draw no caption bar (the caption was the title again, and the accuracy again); the lesson cards keep "Takeaway 1 of 3".
  3. *Accuracy.* Every accuracy on a card or caption is whole percent. The Summary and the recap now literally share one function, `recapAccuracyText` (`GameReportScreen` calls it instead of its own copy of the format); the generator (`:core` cannot see the app) rounds with `roundToInt`, and `CardTextTest` proves it equals the Summary's `%.0f` on 0..100 in 0.01 steps plus the half-way edges; `CardLayoutTest` generates real scripts and checks the card text equals `recapAccuracyText`'s number and that no decimal accuracy appears on any card or caption. The spoken sentences ("84.1 percent") are unchanged.
  4. *Direction.* Names are wrapped in FSI..PDI (title and final-numbers lines); the rating stays outside the isolate as a plain "(1500)". A first version put the rating inside a Hebrew name's isolate and the brackets and digits came out reordered (viewed on the device, then fixed); the card text is also laid out with a forced left-to-right paragraph direction so a line that starts with Hebrew is not right-aligned or reordered.
- **Tests added:** `CardTextTest` (core: lines are `[subtitle, accuracy]`, each fact once on the four recorded games, whole percent, rounding equivalence, name isolation, no isolate in spoken text), `CardLayoutTest` (app unit: each fact once incl. a real generated script with Latin and Hebrew names, the title / paragraph fit rules over 1..400-character names with a fake measurer, geometry, caption policy, accuracy consistency), `CardFrameFitInstrumentedTest` (device pixels, 1280x720, the 8 name pairs of the recap test, intro and final numbers: left and right margins, top, bottom and the caption bar's zone are all still background, and the card is not blank; also a very long opening name, a very long lesson, the chapter bar, and caption-or-not). `GameRecapTest` (R6b) was updated: it looked for the old three-line intro. `PanelChipLabelTest` needed no change (no label it asserts was touched; its 7 cases pass).
- **Device evidence (emulator chess34, API 34, debug build, real narration with the bundled Kokoro voice; MP4s pulled with `adb pull`, frames cut with ffmpeg, each viewed):** a 4-move game with three name pairs: "Al"/"Bo" (59.19 s), "GrandmasterMorphyFan1857X"/"DukeOfBrunswickAndCount_1" (69.73 s), Hebrew names (93.67 s); all h264 1280x720 plus aac. In all three the last 3.6 s have mean volume -91.0 dB (digital silence) against -24.6 dB for the narration (measured on the first), and the recap card is the last thing in the file. `docs/screenshots/r13_r6c_mp4_{short_en,long_en,he}_{intro,outro,recap}.png`; the player's first frame for the same games `r13_r6c_poster_{short_en,long,he}.png`; the renders of the 8 pairs `r13_r6c_card_*.png` (intro 2 to 7, outro 7 and lesson_long viewed). The previous defect (`r13_r6b_poster_long_frame.png`) is the same case as `r13_r6c_mp4_long_en_intro.png`: both names, the opening and the accuracy now sit well above the bottom.
- **Device state:** the app was not installed at the start (only `net.palaya.chessanalyzer.engine.test`); the final Gradle run uninstalled it again. I deleted the three MP4s and the one temp file I created (`/data/local/tmp/he.pgn`); the three older MP4s in `/sdcard/Movies/ChessAnalyzer/` are untouched. `wm size`, density, font scale, locale and rotation were never changed (1080x2400, 420, 1.0, en-US, 0). The notification prompt that appears at the first export was answered "Don't allow" both times (the export runs without it).
- **Not done / found, not fixed:**
  - The Summary screen's own title with Hebrew names reads "משה דיין vs דוד בן־גוריון" (the two names swap sides in an LTR header: `r13_r6c_summary_he_names_not_fixed.png`). Same mixed-direction defect the card had; the screen is outside this task.
  - The spoken accuracy sentences still say one decimal ("84.1 percent"); only what is printed on cards and in the outro caption changed. Other captions ("Turning point ... 35.2% swing") keep their decimal: that is a win-percent swing, not an accuracy.
  - A 60-letter Hebrew name on the intro title is cut with an ellipsis at its logical end, which also drops that side's rating (`r13_r6c_card_intro_6.png`); nothing narrower fits a 29 px line.
  - "est. 2900" / "est. 100" on a 4-move game's final-numbers card is the existing low-confidence estimate (the Summary says "Not enough moves to estimate a rating"); the card does not carry that caveat (pre-existing).
  - The cards' own words ("Final numbers", "What to work on", "vs", "moves") still come from the English narration strings; he-IL narration is unavailable anyway (R6a). Hebrew locale (he-IL) export not run, as in R6b.


### R7 (final: Summary-header bidi fix, full gate, signed release APK, airplane-mode offline proof) — DONE, verified

- **Step 1, Summary header direction.** Cause: the title was `TextDirection.Content` and started with Hebrew, so the whole line became an RTL paragraph and the two names swapped sides. Fix (small, no refactor): new pure `versusLine(versusFormat, white, black, decorateWhite, decorateBlack)` in `video/CardLayout.kt`, built on the existing `isolateBidi` (FSI..PDI); "(you)" is added outside the isolate (same rule as the rating on a card); the title line is drawn with `TextDirection.Ltr`. Used in the Summary `HeaderCard` and the Home recent-game row (`RecentGameRow`). Those are the only two places that print "A vs B": the Board and Video app-bar titles are fixed words ("Board", "Video review"), and the video's own cards were fixed in R6c. Test: `VersusLineTest` (4 cases: isolation and order with Hebrew names, Latin names, "(you)" outside the isolate, mixed scripts). Device (debug build, emulator): `r13_r7_summary_he_names.png` (White's name on the left, "vs", Black's on the right), `r13_r7_summary_he_names_you.png` ("(you)" after White's name), `r13_r7_home_he_names.png`; all three viewed. Replaces the defect picture `r13_r6c_summary_he_names_not_fixed.png`.
- **Step 2, full gate on the debug build (counts read from the result XML):** `:core` 490 tests / 0 failures / 0 errors / 0 skipped; `:app` unit 233 / 0 / 0 / 0 (229 + 4 new); `:app` instrumented `connectedDebugAndroidTest` 103 / 0 failures / 0 errors / **0 skipped** (434 s on chess34, API 34); `lintDebug` 0 errors, 70 warnings. `:engine` instrumented was not re-run (nothing in `:engine` changed; last result 20/0/0).
- **Step 3, signed release APK.** `verifyBundledModels` passed, so `fetch_models.sh` was not needed. `./gradlew :app:assembleRelease` took 1 m 57 s. `dist/PalayaChess-1.0-release.apk` (versionName 1.0, versionCode 1), **364,733,235 bytes**, SHA-256 `dc2214f3008db284c39534cc1a3677f68433d7e6491dc21cc6932088b4373573`. `apksigner verify`: Verifies; v1 false (not needed at minSdk 26), **v2 true, v3 true**, v3.1 false, v4 false; 1 signer, certificate SHA-256 `ca4f7b42ce837f97d48e0e802b48b1c9c9c253ba4e604e56a8dff6979a890947` (equals the fingerprint recorded in PUBLISHING.md). `unzip -lv`: `assets/nnue/nn-1a298aa575a0.nnue` 98,511,183 B **Stored**; `assets/tts/kokoro-int8-en-v0_19.tar` 158,269,440 B **Stored**; `libstockfish.so` present for arm64-v8a (1,588,408), armeabi-v7a (1,279,484) and x86_64 (1,626,256), all Stored. `aapt2 dump permissions`: FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC, POST_NOTIFICATIONS and the app's own signature-level DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION (added by AndroidX); **no INTERNET, no ACCESS_NETWORK_STATE**. `dist/` added to `.gitignore`; nothing in it was deleted (it did not exist).
- **Step 4, airplane-mode offline proof (emulator chess34, API 34, release APK, fresh install).** `adb uninstall` printed DELETE_FAILED_INTERNAL_ERROR because the app was not installed (Gradle had removed it), so the install was fresh. Networking off with `cmd connectivity airplane-mode enable`, `svc wifi disable`, `svc data disable`: `ping -c 2 -W 2 8.8.8.8` gave "connect: Network is unreachable"; `dumpsys connectivity` gave "Active default network: none"; the status bar shows the aeroplane icon in every screenshot; re-checked after the export (still `airplane_mode_on=1`, still unreachable). The Immortal Game (`fixtures/immortal.pgn`) was shared with `am start -a android.intent.action.SEND ... --es android.intent.extra.TEXT "$(cat /data/local/tmp/imm.pgn)"`. Observed: "Setting up the engine (one time)..." (`r13_r7_off_01_setup.png`), then "Move 3 of 23" (`_02_analysing`), then the Summary in about 3 minutes (`_03_summary`, 82% / 73%, "~1602" / "~1208"); Summary bottom (`_04`), Walkthrough "What you missed: X-ray" (`_05`), Board (`_06`), side chosen White with Show me what I missed / Try it (`_07`), Practise "1 / 2" (`_08`), Video screen (`_09`). Export: notification prompt answered "Don't allow"; "Preparing narration... (0/49)" (`_10`), "About 2 min left" at 44/49 (`_11`), "Rendering video..." (`_12`), "Video saved, 7 min 2 s, 76 MB" (`_13`). The export took about 28 minutes on the emulator (software rendering).
  - **MP4 (pulled with `adb pull`, measured on the host):** duration 422.25 s (7 min 2 s), 75,570,863 bytes, h264 1280x720 30 fps + aac 44.1 kHz mono. `volumedetect` over the whole file: mean **-25.3 dB**, max **-5.6 dB** (narration present); the last 3.5 s: mean -91.0 dB, max -91.0 dB (digital silence = the silent recap card). Frames cut with ffmpeg and viewed: intro at 2 s (`r13_r7_off_mp4_intro.png`: names, "1-0 · 23 moves · opening (C33)", "White 82% · Black 73%"), middle at 211 s (`_middle.png`: missed-tactic frame, "Nxa8", eval +5.2, the caption), and the recap at 420.25 s (`_recap.png`: both names with "(you)" on White, 82% / 73%, move-quality chips, "You won, even after a blunder on move 18.", "Biggest moment: move 18, Bd6 (Blunder by White)").
  - **Permissions and network.** `dumpsys package net.palaya.chessanalyzer`: requested = FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC, POST_NOTIFICATIONS (granted=false after "Don't allow"), DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION; no INTERNET. Logcat (`logcat -c`, then 11,019 lines up to 16:39, 3,122 of them from the app's pid 14098), grep `github|stockfishchess|okhttp|UnknownHost|http://|https://`: **one hit**, `ZipLPPopulator(14603): Updated file group for group 'langpack-domain_en-US_3008_zipfile' from location: https://dl.google.com/android/voice/soda/en-US/v3008/soda-en-US-v3008.zip`. Pid 14603 is `com.google.android.tts` (log tag `gle.android.tts`, the emulator image's Google text-to-speech engine, a system app with its own permissions) doing its own language-pack bookkeeping; it is not the app (pid 14098) and is the only line with a URL. No socket, DNS or connect line from the app's pid.
  - The app narrates with the bundled sherpa-onnx Kokoro voice (log: `sherpa-onnx(14098)`); it also binds the system TextToSpeech service (`Connected to TTS engine`) for the device-voice option, a local binder connection, not network.
- **Device state restored:** airplane mode off, wifi and data on (`airplane_mode_on=0`, `Active default network: 110`); `wm size` 1080x2400, density 420, font_scale 1.0, rotation 0 and locale en-US were never changed. I removed my temp files on the device (`/data/local/tmp/he.pgn`, `imm.pgn`, `/sdcard/ui.xml`) and the exported MP4; the three older MP4s in `/sdcard/Movies/ChessAnalyzer/` are untouched. The release app was uninstalled so the device is as it was found (only `net.palaya.chessanalyzer.engine.test` installed).
- **Found, not fixed:**
  - With `TextDirection.Ltr` the Summary title is left-aligned even in a he-IL UI; chosen on purpose (White's name must be first and on the left).
  - A 23-move export on the emulator takes about 28 minutes; on a real phone it should be faster, not measured.
  - Still open from earlier rounds: `SOURCE_REPO_URL` placeholder, green/board colours close to chess.com values (owner decision before a public listing), no run on a physical phone by an agent, Play needs asset packs (the APK is for sideloading / F-Droid), `:engine` instrumented not re-run in R7.


### D1 (Google Play readiness: API 36, 16 KB pages, R8, FGS type, App Bundle) — DONE, verified

Baseline commit `bc355e5`. Nothing committed. The two bundled models were left in the APK on purpose (D0 designs their removal).

- **Versions (old -> new):** AGP 8.4.2 -> **8.9.3** (the minimum line that supports API 36 is 8.9.1; 8.9.3 is its last patch); Gradle wrapper 8.6 -> **8.11.1** (AGP 8.9's minimum); compileSdk/targetSdk 34 -> **36**; NDK 26.1.10909125 -> **28.2.13676358** (r28c), now set in `:app` too; build-tools 35.0.0 auto-installed by AGP. **Kotlin 1.9.24 and Compose compiler 1.5.14 unchanged** (AGP 8.9 accepts them; no compile errors, only the existing warnings plus `announceForAccessibility` deprecated). Compose BOM, AndroidX and sherpa-onnx 1.13.8 unchanged. `:desktop` still configures and was not touched.
- **16 KB pages.** Before: only `libstockfish.so` was 4 KB-aligned (`Align 0x1000` on all three ABIs in the R7 APK); sherpa-onnx 1.13.8 (`libonnxruntime.so`, `libsherpa-onnx-jni/c-api/cxx-api.so`) and `libdatastore_shared_counter.so` were already 0x4000, so **no sherpa-onnx upgrade was needed**. After (NDK r28 plus an explicit `-Wl,-z,max-page-size=16384` in `CMakeLists.txt`): every LOAD segment of all 18 `.so` (6 libraries x 3 ABIs) is `0x4000`, read with the NDK's `llvm-readelf -lW` from the universal APK, the AAB and bundletool's arm64 split; `zipalign -c -P 16 -v 4` passes on all four release APKs and the split. The emulators run 4 KB pages, so loading on a 16 KB kernel was not run (a `google_apis_ps16k` image exists if wanted).
- **Stripping defect found and fixed.** With AGP 8.9 and no `ndkVersion` in `:app`, AGP looked for its default NDK 27.0 (not installed), printed "Unable to strip the following libraries" and packaged an unstripped 17 MB `libstockfish.so`; the release APK grew to 397 MB. `:app` now names NDK r28: `libstockfish.so` is 1.55 MB (arm64).
- **R8.** `isMinifyEnabled` + `isShrinkResources` on release. Keep rules: `engine/consumer-rules.pro` (new: `NativeBridge` and its native methods; the C++ never calls back into Java), `app/proguard-rules.pro` keeps `com.k2fsa.sherpa.onnx.**` with members (its AAR ships an empty `proguard.txt`, and `libsherpa-onnx-jni.so` reads the config classes' fields by name), and FileProvider. No kotlinx.serialization or reflection in `:app` (org.json); DataStore ships its own rules. R8 reported no missing classes. **Dex: 44,747,292 B in 3 files (11,859,386 B compressed) -> 3,076,328 B in 1 file (1,451,488 B compressed).** Universal release APK 364,733,235 B (R7) -> 353,729,432 B.
- **Foreground service.** Manifest: `foregroundServiceType="dataSync|mediaProcessing"`, new `FOREGROUND_SERVICE_MEDIA_PROCESSING`. `ExportForegroundServiceType.forSdk` picks mediaProcessing on 35+, dataSync on 29-34, none below; `onTimeout(startId, fgsType)` (Android 15's 6-hour limit) cancels the export, records it as Failed with a plain reason (`video_export_time_limit`) and calls `stopSelf()`. **Defect found on API 36 and fixed:** `ServiceCompat.startForeground` (androidx.core 1.13.1) masks the type with the Android 14 set (`0x40000FFF`, read from its bytecode), so mediaProcessing (0x2000) became 0 and the platform threw `InvalidForegroundServiceTypeException: Starting FGS with type none`; the service swallows that by design, so the export ran on without a foreground service and no test noticed. The service now calls `Service.startForeground(id, n, type)` directly. After the fix `dumpsys activity services` showed `isForeground=true foregroundId=4101 types=0x00002000`. Tests: host `ExportForegroundServiceTypeTest` (4 tests: 35/36/37 -> 0x2000, 29-34 -> 1, 26-28 -> 0, the manifest declares both types); `ManifestPermissionsTest` and the instrumented `NoNetworkPermissionTest` list the new permission; the new instrumented `VideoExportServiceInstrumentedTest.theExportRunsAsAForegroundServiceOfTheTypeChosenForThisSdk` reads the platform's own `getForegroundServiceType()` (recorded by the service) and passed on API 34 (dataSync) and API 36 (mediaProcessing).
- **Edge to edge (targetSdk 36, opt-out removed).** The app already called `enableEdgeToEdge()`. Found and fixed: (1) the Scaffold `bottomBar` buttons on Practise ("Next"), Walkthrough ("Next"/"Done") and Video ("Save video") had no inset handling and sat under the navigation bar; they are now lifted with `navigationBarsPadding()` (the bar's colour still runs underneath). (2) At targetSdk 35+ the window also extends into a display cutout; the Scaffolds handle system bars but not a side cutout in landscape, so `MainActivity` applies `WindowInsets.displayCutout.only(Horizontal)` once around the NavHost. (3) `SystemBarStyle.auto` drew dark status-bar icons and a light navigation scrim over the always-dark UI when the phone is in light mode (seen in the first API 36 screenshot); now `SystemBarStyle.dark` for both. Checked on chess36 with the 3-button bar, a tall cutout in landscape, and gesture navigation.
- **Other API 35/36 changes checked.** Predictive back: `android:enableOnBackInvokedCallback="true"` set (the default at 36 anyway); the app has no `onBackPressed` override, only Compose `BackHandler`; system back from Board, Walkthrough and Video full screen worked on API 36. `elegantTextHeight`: affects scripts such as Arabic or Thai; the app ships English and Hebrew. Android 16 ignores orientation requests on sw600dp+ screens: the only use is Video full screen asking for landscape, and the 16:9 picture fits either orientation, so nothing to change. `statusBarColor`/`navigationBarColor` in `themes.xml` are ignored from 35 (harmless, left). Strict intent matching: the only explicit intents target our own non-exported service. The app requests no audio focus.
- **App Bundle.** `:app:bundleRelease` -> `app-release.aab` 250,131,307 B; `jarsigner -verify`: jar verified, certificate SHA-256 `CA:4F:7B:...:09:47` (the release key). bundletool **1.18.3** in the gitignored `tools/` (SHA-256 `a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29`). `get-size total` per device (API 36 spec, en-US, 420 dpi): **arm64-v8a 196,058,239 B, x86_64 197,495,419 B, armeabi-v7a 195,043,077 B**; on a throwaway copy of the bundle without the two models (placeholders, never kept): **14,364,484 / 15,801,664 / 13,349,322 B**. The difference is the two models compressed (gzip -9: voice 102,579,403 B, net 78,944,849 B). So the bundle is ~196 MB per device, just under Play's 200 MB base-module limit (not "far over" as PUBLISHING.md said before; that figure was the universal APK).
- **APKs for direct installs.** ABI splits plus a universal APK, enabled only for an `assembleRelease` invocation without a bundle task (with both in one command AGP 8.9's `buildReleasePreBundle` failed: "Sequence contains more than one matching element"). Sizes: `app-arm64-v8a-release.apk` **292,485,879 B**, `app-armeabi-v7a-release.apk` 282,389,054 B, `app-x86_64-release.apk` 296,743,865 B, `app-universal-release.apk` 353,729,432 B; `apksigner verify` OK on all four (v2+v3, the release certificate). `dist/PalayaChess-1.0-release.apk` (R7) untouched.
- **API 36 AVD.** `chess36`: `system-images;android-36;google_apis;x86_64` (rev 7), Pixel 6, 4 GB RAM, 8 GB data. Booted in under 2 minutes (WHPX).
- **Release app end to end on chess36 (API 36, fresh install of the minified release APK).** Immortal Game shared in; analysis (about 10 min on this emulator); Summary, side chosen (White), Board, Practise (Show answer, Next), Walkthrough ("What you missed: X-ray"), Video; "Save video" with notifications allowed: `types=0x00002000` in dumpsys; "Video saved, 6 min 42 s, 33 MB"; the Share sheet opened with the MP4. The narration used the bundled Kokoro voice through sherpa-onnx (logcat shows the R8-built app's `OfflineTtsKokoroModelConfig` read by the JNI). **MP4 pulled and measured on the host:** 402.56 s, 32,847,301 B, h264 1280x720 30 fps + aac 44.1 kHz mono; `volumedetect` over the whole file mean **-25.3 dB**, max **-5.6 dB**; last 3.5 s -91.0 dB (the silent recap). Frames at 2 s and 200 s viewed (`d1_api36_mp4_intro.png`, `_mp4_middle.png`). Logcat: no FATAL, no UnsatisfiedLinkError/NoSuchField/NoSuchMethod/ClassNotFound, crash buffer empty; URL lines came only from the system `ConfigUpdater` (another pid).
- **Screenshots (all viewed):** `docs/screenshots/d1_api36_{home,analysing,summary,summary_bottom,board,practise,walkthrough,video,export_progress,export_done,share,settings,about,about_bottom}.png` (portrait, 3-button navigation), `d1_api36_land_{summary,board,video,video_fullscreen}.png` (landscape with the emulated tall cutout), `d1_api36_video_gesture_nav.png`, `d1_api36_mp4_{intro,middle}.png`.
- **Gate (counts read from the result XML):** `:core` **490/0/0, 0 skipped**; `:app` unit **237/0/0, 0 skipped** (233 + 4 `ExportForegroundServiceTypeTest`); `:app` instrumented **104/0/0, 0 skipped on chess36 (API 36)** and **104/0/0, 0 skipped on chess34 (API 34)** (103 + the FGS-type test); `:engine` instrumented **20/0/0, 0 skipped on chess36 and on chess34**; `lintDebug` **0 errors, 70 warnings** (70 before; the two new `InlinedApi` warnings in `ExportForegroundServiceType` are suppressed with the reason; the composition before D1 was not recorded; now: 33 GradleDependency, 10 UnusedResources, 7 UseKtx, 6 AndroidGradlePluginVersion, 5 IconLauncherShape, 3 ObsoleteSdkInt, 2 PluralsCandidate, 2 UsableSpace, 1 ModifierParameter, 1 StaticFieldLeak).
- **Device state restored.** chess36 (new): 3-button navigation and the cutout overlay switched back (gestural, no cutout), rotation back to auto (user_rotation 0), the exported MP4 and temp PGNs deleted, the release app uninstalled. chess34: Gradle uninstalled `net.palaya.chessanalyzer.engine.test`, which was installed when I started; reinstalled from `engine-debug-androidTest.apk`. Both emulators shut down at the end.
- **Found, not fixed:**
  - The bundle is ~196 MB per device, 4 MB under Play's 200 MB limit by bundletool's estimate; real headroom depends on Play's own compression. D0/D2 (models as a download) removes the question.
  - `libsherpa-onnx-c-api.so` and `libsherpa-onnx-cxx-api.so` (~4.9 MB per ABI) are packaged but nothing needs them (`libsherpa-onnx-jni.so` NEEDs only libonnxruntime and system libraries). Excluding them would save ~5 MB per device; not done (needs a narrated-export check).
  - The AAB produces ~80 language splits from AndroidX resources although the app ships English and Hebrew; `localeFilters` would trim them (small).
  - Compose BOM 2024.06.00 / Material3 1.2 is old (lint GradleDependency); not upgraded, because Kotlin stays 1.9.24.
  - The Immortal Game gave 80% / 72% here against 82% / 73% in R7 (time-limited search on a slower, busier emulator; no code change).
  - No run on a 16 KB-page kernel and none on a physical phone.
  - An untracked `games/game01.txt` (created 19:57 today) is in the tree; not mine, left alone.


### F1 (owner's bug: Deep "took forever", stuck at move 23, then could not analyse) — DONE, verified

Baseline `bc355e5` plus D1's uncommitted changes. Nothing committed. Spec: `docs/ANALYSIS_SPEC.md` §8 (rewritten: §8.1 budget, §8.2 one depth per result, §8.3 resume, §8.4 time bar).

- **Cause, part 1 (the wait).** `AnalysisService` sent `go depth D` only. Per-position work is heavy-tailed: the host calibration (Stockfish 19, Threads 4, Hash 96, MultiPV 3, hash kept, 226 positions of game01, the chess.com game, the Immortal Game and Byrne-Fischer) gave depth-18 node counts of median 6.3 M, p95 42.2 M, max 100.5 M; depth 14 median 1.2 M, p95 21.1 M, max 102 M (Standard has tails too). The game01 position after 23.Rdg1 is not stable: 24.9 M warm in the whole-game run; three cold runs completed d18 at 30.9 M, 76.5 M and 132.1 M (owner: 80-129 M).
- **Cause, part 2 (the message), most likely.** The game text lived only in `AnalysisViewModel.pendingPgnByGameId`. Android restores the nav back stack after a process death, the new ViewModel had an empty map, and `runAnalysis` reported `GAME_TEXT_LOST` ("Couldn't analyze this game" / "The game text was lost. Open the game again."). A minutes-long 4-thread search in a backgrounded app is what gets a cached process killed (or frozen). The owner's actual message is still unknown (see the end).
- **Fix 1, bounded search.** `go depth D nodes N movetime T` (`ui/model/SearchBudget.kt`): Quick 4 M / 30 s, Standard 25 M / 150 s, Deep 45 M / 270 s. Rule: at least 95% of calibration positions reach full depth (97.8% / 96.0% / 96.0%) and the game01 outlier stops near 16 at Deep (owner's profile: 16; F1 cold runs: 18, 17, 17; emulator `CappedSearchInstrumentedTest`: 16 after 45.0 M nodes / 72 s in one run, 18 after 21.0 M / 30 s in the next). Time caps: chess34 searched large positions at 510-600 k nodes/s (median 570 k); the caps are at least 3x the budget's time at 0.5 M nodes/s, so the clock only decides on a phone more than 3x slower than the emulator. A first 240 s Deep cap (from one 0.63 M sample) was raised to 270 s after the full runs. `PositionEval.requestedDepth` + `isCapped` (Contract.kt), stored in the eval cache; Summary Details ends with "To save time, N positions were searched less deeply than the rest." Cache key = SHA-256(PGN, depth, MultiPV, `n<nodes>:t<ms>`), so results from before F1 are re-analysed once.
- **Defect found on the way: the stop batch lies about its depth.** Host capture (`go depth 18 nodes 12000000` on the Rdg1 position): the stop re-printed the depth-15 scores and PVs as "depth 16". On the emulator a search stopped 1,673 nodes past its 45 M budget printed all three slots as "depth 18", the requested depth, and my first rule ("a clean batch reached the requested depth, so the search finished") accepted it. Final rule (`ConsistentLines`, spec §8.2): when the engine's own counters reached the node or time limit, the last batch is discarded; the result is the last batch with slots 1..k at one depth and no bound. `StockfishEngine.analyze` gained `nodes` and an optional `onProgress` (plain Kotlin on the output it already reads; cancellation and drain unchanged; nothing new crosses JNI, so no R8 rule). Host `ConsistentLinesTest` (10, built on the recorded host output); CLAUDE.md engine gotcha 6.
- **Fix 2, never lose the game.** `PendingAnalysisStore` (`filesDir/pending_analysis.json`, excluded from backup): written at run start, deleted on success, on a parse failure and on the user's Cancel/Back. A restored Analysing screen reads it; a fresh launch at Home opens it once (if under 24 h old). Checkpoint every ply (was every 5). **No foreground service added, and I do not think one is needed:** with resume from the checkpoint a kill costs at most the position in progress; the trade-off is that a backgrounded analysis may pause or be killed and continues when the app is opened again.
  - **Proof on chess34 (debug build).** Deep game01 started fresh, Home at about move 12, `adb shell am kill net.palaya.chessanalyzer` (pid gone), relaunched from the launcher: the Analysing screen came back at "Move 13 of 33" (`f1_resumed_after_kill.png`) and finished on the Summary (`f1_summary_after_resume.png`); `pending_analysis.json` was gone afterwards. Log of that relaunch: `previous process exit: USER_REQUESTED ... importance CACHED ... "[KILL BACKGROUND] kill background"`, `MainActivity onCreate (recreated from saved state)`, `resuming ... from the saved request (process was restarted)`, `resuming from the checkpoint: 25 of 67 positions already done`. Second path: the Immortal Game at Deep, `am force-stop`, launcher: the app started at Home, opened the Analysing screen by itself and resumed at 6 of 46; Cancel then deleted the request.
- **Fix 3, time bar.** Elapsed clock, "About N min left", and "Thinking deeper on this move… depth d of 18" after 3 s on one position (`f1_time_bar.png`, `f1_thinking_deeper.png`, viewed). The export's estimator (measured time per item) was tried first and was badly wrong for analysis: 3 min shown after 6 positions with 19 left, 6-7 min at move 19 with 11 left (openings are cheap, middlegames dear). The estimate is now positions left x calibrated typical nodes per position (Deep 10.3 M, Standard 3.45 M, Quick 0.5 M) x this run's measured ms per node; on the final build it read 18 min at move 3 (about 20 left) and 14 at move 11; replayed on the clean run's log: 14 min after 6 positions (19.3 left), 12 after 30 (12.5 left), 6 after 50 (4.6 left). Display rules reused from `ExportTimeLeft.kt` (hidden until 6 positions are searched, rises only when off by 2 min or 25%). Polite live region on the estimate and the "thinking deeper" line, not on the ticking clock; the column scrolls and nothing has maxLines. Chess.com reference (pattern only): one determinate bar, one short status line, secondary facts below in smaller muted text.
- **Fix 4, diagnostic log.** `diagnostics/DiagnosticLog.kt` (two files of 512 KiB in `filesDir/logs/`, excluded from backup, synchronous append, never throws), `DiagnosticFormat.kt` (pure), `AppDiagnostics.kt` (app and device facts, previous-process exit reasons via `getHistoricalProcessExitReasons` on API 30+, an uncaught-exception handler chaining to the previous one, activity start/stop/recreate, onTrimMemory/onLowMemory, the share intent). Logged: analysis settings, the game's tags and SAN moves, one line per position (index, SAN, requested/reached depth, nodes, ms, capped, score), failures with stack traces, each distinct engine `info string` line once per process (Stockfish repeats its set-up lines at every `go`), video export start/done/cancel/fail. Shared from Settings ("Share diagnostic log", with "The log stays on this phone unless you share it.") and from the error screen ("Share details"): `ACTION_SEND` text/plain, a snapshot in `cacheDir/diagnostics/` through the existing FileProvider, `EXTRA_TEXT` summary (version, device, last error); the chooser excludes the app itself (it takes text shares as PGN, and the first share sheet offered "Analyze in Palaya Chess"). Screens: `f1_settings_share_log.png`, `f1_error_share_details.png`, `f1_share_sheet.png` (Nearby Share, Chrome, Drive, Messages, Bluetooth; nothing was sent).
- **game01 at Deep, end to end on chess34 (API 34), clean run:** 19 min 49 s from "analysis start" to "analysis done" (67 positions, 679 M nodes), **1 capped** (29.b3, depth 13/18, 45.0 M nodes, 73.5 s); reached the Summary; the Details line is `f1_summary_capped_line.png`. 23.Rdg1 itself took 42.3 M nodes / 72.6 s and reached 18 in that run (37.8 M / 60.9 s in the resumed run, which had 0 capped).
- **Tests.** New: `ConsistentLinesTest` (:engine host, 10), `SearchBudgetTest` (7), `DiagnosticLogTest` (10), `DiagnosticFormatTest` (9), `AnalysisTimeLeftTest` (10), `BackupRulesTest` extended; instrumented `CappedSearchInstrumentedTest` (wall bound 180 s = 2.5x the measured 72 s for the whole budget; asserts one depth for all lines, capped exactly when a limit was hit, nodes within the budget, the pipe clean afterwards), `DiagnosticShareInstrumentedTest`, `ResumeAnalysisTest` +4 (pending request round trip, damaged request dropped, requestedDepth through the eval cache, the budget in the cache key).
- **Release build (R8).** `:app:assembleRelease`: arm64 292,522,515 B, x86_64 296,780,492 B, universal 353,766,064 B. The x86_64 APK installed fresh on chess34: first-run setup, game01 at Deep reached the Summary (72% / 84%, `f1_release_summary.png`) with "To save time, 4 positions were searched less deeply than the rest." (`f1_release_summary_capped.png`), in 34 min 21 s, while the chess36 test suite ran on the same host (both emulators competing for CPU). Logcat: no FATAL, no UnsatisfiedLinkError / NoSuchMethod / ClassNotFound. The release build is not debuggable, so its log was not pulled; why 4 positions were capped there (1 and 0 in the two debug runs) is not established: node counts with 4 threads vary run to run, and the time cap may have been reached under that load.
- **Gate (counts read from the XML):** `:core` **490/0/0**, 0 skipped; `:engine` unit **10/0/0** (new); `:app` unit **273/0/0** (237 + 36); `:app` instrumented **110/0/0, 0 skipped on chess34 (API 34) and on chess36 (API 36)** (104 + 6), final build; `:engine` instrumented **20/0/0 on chess34**, and on chess36 **19/1/0 first** (`EngineBenchmarkTest.benchmarkDepth18`, an unbounded `go depth 18` benchmark, passed its 900 s per-position timeout while chess34 ran a Deep analysis in parallel) then **20/0/0** rerun alone (that test 306 s); `lintDebug` **0 errors, 70 warnings** (same composition as D1).
- **Device state restored.** chess34: the app uninstalled, `net.palaya.chessanalyzer.engine.test` reinstalled (Gradle had removed it; it was there at the start), temp files deleted, font scale 1.0, size and density untouched. chess36: nothing of ours installed (as found). Both emulators shut down (none was running at the start).
- **Found, not fixed:**
  - The owner's original message is still not known. `GAME_TEXT_LOST` after a background kill is the path that fits ("could not analyse" after a long wait), and it can no longer happen; `ANALYSIS` (an engine exception) was not reproduced. The next report should carry the shared log.
  - `EngineBenchmarkTest.benchmarkDepth18` still searches without a budget and can time out on a loaded host (seen once on chess36); it measures the old behaviour and could move to the Deep budget.
  - A backgrounded analysis keeps running only as long as Android lets a cached process run; it is resumed, not continued, after a kill (by design, no foreground service).
  - Old eval caches (no budget in the key) are re-analysed once; nothing deletes the orphaned files (`eval_cache/` is excluded from backup and is small).
  - `games/game01.txt` is still untracked.


### D2a + D2b (small installer: model pins, build, stores, downloader, setup logic; no UI yet) — DONE, verified on the host

Baseline `0479daf` plus F1's uncommitted changes (kept intact: `SearchBudget`, `ConsistentLines`, `PositionEval.isCapped`/`requestedDepth`, `PendingAnalysisStore`, `diagnostics/*`, the budget in the eval-cache key). Nothing committed. Design: `docs/MODEL_DOWNLOAD_DESIGN.md` §9 rows D2a and D2b; owner's open questions settled with the design's defaults (provisional `palayax/palaya-chess` base URL, plain `.tar` voice, signing key next to the keystore, Setup first, Check for updates in Settings).

**D2a, pins and build.**
- `vendor/models/MODELS.lock` gains `net.sha256`, `net.arch_hash`, `net.version` and `release.tag=models-2026.10`. The three net values were blanked and then written by the updated `scripts/fetch_models.sh` (first-run pinning from the file: full SHA-256 and the NNUE header read with `od`); a second run verifies instead of pinning. **Measured header:** bytes `fa 8a 44 6a | 05 22 5b a8 | 54 00 00 00` = version **0x6a448afa** (the design's example `0x7af32f20` is an older Stockfish's), architecture hash **0xa85b2205**, description 84 bytes.
- `generateModelPins` replaces `verifyBundledModels` in both modules. `:engine` checks the net fields (64/8 hex, `0x`+8 hex), that the 12 hex in evaluate.h's net name prefix `net.sha256`, that `net.version` equals `Version` in `nnue_common.h` (an addition: the compiled engine's literal), and the tag pattern; writes `GeneratedNetPins` (size, SHA-256, arch hash, version). `:app` checks the voice fields, derives the tar name, checks the tag; writes `GeneratedModelPins` (`VOICE_FILE_NAME`, `VOICE_SIZE_BYTES`, `VOICE_SHA256`, `RELEASE_TAG`; `MANIFEST_PUBLIC_KEY_DER` waits for D2e's key). If the files are present under `vendor/models/` they are verified against the lock (size, SHA-256, header); the build never needs them. `assets.srcDir(...)` and both `noCompress` lines are gone; the 600 s install timeout stays (D2d's test APK will carry ~257 MB).
- `BuildConfig.MODEL_BASE_URL` (`https://github.com/palayax/palaya-chess/releases/download/`, provisional, commented as such), `MODEL_MANIFEST_URL` (+ `models/models.json`), `SHERPA_ONNX_VERSION` (from `libs.versions.toml`, 1.13.8). `-PpalayaModelBaseUrl` changes the **debug** fields only; it must be an http(s) URL ending in `/`; with a release task in the same invocation the build stops before any task ("-PpalayaModelBaseUrl is for debug builds only"). Checked: debug BuildConfig = `http://10.0.2.2:8787/`; release generate + override = BUILD FAILED; `ftp://x` = configuration error; no override = the GitHub URL.
- `scripts/publish_models.sh <tag> --min-version-code N [--repo] [--dry-run]`: verifies the files against the lock (size, SHA-256, header), refuses a tag other than the lock's, writes `dist/models/models.json` (§3.2 shape, engineTag from `STOCKFISH_VERSION.txt`, runtime from the catalog), signs it with `keystore/models-signing.pem` (verifies against `vendor/models/manifest_public_key.der` when present), `gh release create <tag>` (refuses an existing tag: binaries are immutable) and `gh release upload models --clobber`. One-time key commands documented in the header. **Only `--dry-run` was run** (manifest written and checked; wrong tag refused); signing and upload were not run (no key, no repo, no `gh`).
- `scripts/model_test_server.py --root vendor/models --port 8787 [--fault truncate:F|drop:F|hash|404|500|slow:RATE|redirect|norange] [--fault-times N]`: serves `/<tag>/<file>` (found by name under the root) and an unsigned `/models/models.json` from the lock, honours Range, prints every request; `redirect` answers 302 to a second port. Smoke-tested on the host with curl (302 + Location to the second port, 206 with `Content-Range: bytes 98511000-98511182/98511183`, the manifest, 404).
- `.gitignore`: `*.pem`, and `!vendor/models/manifest_public_key.der` ahead of D2e (`git check-ignore` confirms both).
- **Done-when, proven:** the three model files moved out of `vendor/models/` (only `MODELS.lock` left), `./gradlew assembleDebug` **BUILD SUCCESSFUL** (58 s); `app-debug.apk` **114,010,909 B**, `unzip -l` shows no `.nnue` and no `.tar` (assets: the five licence texts and `openings.tsv`). With the files still away, `:engine`/`:app` host tests and both androidTest compiles also passed. Files restored afterwards (verified by size). `generateModelPins` on four inconsistent locks (via `-PpalayaModelsLock=<copy>`, the real lock untouched): net SHA not starting with the name's prefix, `release.tag=models-latest`, `net.version=0x7af32f20` (differs from `nnue_common.h`), `kokoro.tar.sha256=xyz` -> each **BUILD FAILED** with that reason.

**D2b, stores, downloader, setup logic.**
- New: `engine/.../NetStore.kt` (`filesDir/nets/`: `activeNetOrNull` size only, `verifiedNetOrNull` size + **full** SHA-256 once per process, `installVerified(part, name)` atomic move that also refuses a header the compiled engine cannot parse, `readHeader`/`parseHeader`, `deleteParts`, `migrateLegacy`, `NetPins` injectable, `NetNotInstalledException`); `app/.../video/VoiceStore.kt` (the bundled installer's extraction tail unchanged: digest over the whole stream, top-level dir stripped, zip-slip guard, `kokoro.extracting/` swap, `.provisioned` marker last; `installFromTar(part)` deletes the part afterwards, `installFromStream` for D2d's asset seeding, `installedVersionId`, `partFile` = `tts_models/kokoro.tar.part`, `LAYOUT`); `data/models/DownloadState.kt` (`DownloadState`, `DownloadEvent`, `Transition`, pure `DownloadStateMachine`), `ModelDownloader.kt`, `ModelSetup.kt` (with `SetupProgress`, `FileProgress`, `SetupStatus`, `SetupOutcome`, `SetupState`), `NetworkStatus.kt` (Round 11 `ConnectivityNetworkCostProbe` logic verbatim as `ConnectivityNetworkStatus`, fail-closed to METERED); `app/src/sharedTest/.../FaultHttpServer.kt` (ServerSocket, HTTP/1.1 GET, compiled into both test source sets).
- `ModelDownloader` (the only connection site): HttpURLConnection, https only, the http exception only for 10.0.2.2/127.0.0.1/localhost when `allowCleartextLoopback` (= `BuildConfig.DEBUG`); manual redirects (5 hops, each re-checked, re-resolved every attempt, **host only in the log**); Range resume with the prefix re-hashed; 200-with-offset restarts from 0; 206 total and 200 Content-Length must equal the pin; 404/410 fatal; 429/503 Retry-After <= 60 s honoured; other 5xx transient; ENOSPC -> INSUFFICIENT_STORAGE; 256 KB chunks with `ensureActive()`; `fd.sync()`; cancellation = pause (part kept). `User-Agent: PalayaChess/<versionName> (Android <SDK>)`, `Accept-Encoding: identity`, timeouts 15/30 s.
- Manifest: `INTERNET`, `ACCESS_NETWORK_STATE` (comment rewritten). `app/src/debug/AndroidManifest.xml` + `app/src/debug/res/xml/network_security_config.xml`: base cleartext false, cleartext true only for `10.0.2.2`, `127.0.0.1`, `localhost` (no subdomains). Release checked with aapt2: permissions = INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC, FOREGROUND_SERVICE_MEDIA_PROCESSING, POST_NOTIFICATIONS (+ AndroidX's own signature permission); `networkSecurityConfig`/`usesCleartextTraffic` occurrences in the release manifest: **0**; no `network_security_config` resource in the release APK (it is in the debug APK).
- Backup rules (both files, all three sections): `nets/` and `models/` added; the two legacy root literals (`nn-1a298aa575a0.nnue`, `.part`) kept for one release; `tts_models/` already covers the voice part.
- `EngineController(netStore)`: `ensureReady()` = `netStore.verifiedNetOrNull() ?: throw NetNotInstalledException()` before `engine.start()`; `setEvalFile`/`analyze` guards untouched; nothing downloads or copies there. `AnalysisService`: the first-run setup step is gone; `Failure.SETUP_REQUIRED` replaces `SETUP_STORAGE` and `SETUP_DAMAGED` (both unreachable now: analysis copies nothing); raised only when the engine is needed, so a game already in the eval cache still opens without a net. The Analysing error shows "Finish setting up first." with no Try again (D2c adds "Set up"). `AnalysisPhase.FIRST_RUN_SETUP`, `progress_first_run_setup`, `analysis_failed_setup_storage/_damaged` and the first-run preview deleted.
- **Deleted:** `FirstRunSetup.kt`, `BundledNetProvider.kt`, `BundledVoiceInstaller.kt`, and their instrumented tests `FirstRunSetupInstrumentedTest`, `BundledVoiceInstallerInstrumentedTest`, `BundledNetProviderInstrumentedTest` (subject gone; D2d ports them from git `HEAD` as `ModelSetup/VoiceStore/NetStoreInstrumentedTest`), `NoNetworkPermissionTest` (now `NetworkPermissionTest`: the exact set, both normal permissions granted, a loopback connect to a closed port fails with ECONNREFUSED instead of EPERM). `grep` over all code, build files and scripts: no reference to the deleted classes (historical design docs and this log still name them).
- `ChessAnalyzerApplication` exposes `netStore`, `voiceStore`, `networkStatus`, `modelDownloader` (log into the diagnostic log, tag `models`), `modelSetup`; `onCreate` runs `modelSetup.migrateLegacy()` right after the diagnostics start. `AnalysisViewModel`, `AboutScreen`, `NeuralTtsProvider`, `NarrationVoiceSettings` docs moved to the stores. `TestApp`/`TestNet` (androidTest) rewritten to seed from the test APK's assets (`nnue/<net>`, `tts/<tar>`) through the same store tails and to fail with "not seeded (D2d)" until D2d wires those assets; both androidTest source sets compile.
- **Migration (§8), as implemented:** at every start, `NetStore.migrateLegacy()` lists the `filesDir` root: `filesDir/<pinned net name>` is renamed (atomic, same filesystem) to `nets/<name>` when `nets/` does not already have it; any other `nn-*.nnue` or `nn-*.part` there is deleted. No hashing at start; the net is hashed once when the engine first needs it, as before. The voice needs nothing: same directory, and `VoiceStore.isInstalled()` is the old check byte for byte (marker == this build's pinned tar SHA-256, required files and `espeak-ng-data/` present). Result: `needsNet()` and `needsVoice()` false, `ModelSetup.run()` returns Complete with **zero requests** (host test). Logged: `migrateLegacy: moved nn-….nnue into nets/…; voice installed: true` to the diagnostic log and logcat (tag `ModelSetup`). Not moved: `eval_cache/*.json` into `eval_cache/<net prefix>/` (that layout is D2e's).
- **Diagnostic log (tag `models`):** setup start (what is needed, free bytes, bytes needed, base host, tag), per file: download start (size, bytes already on disk, host), 25/50/75%, every transient failure with its cause and the next state, redirect hosts, resume offsets, "the server ignored the range", verified, installed, paused (reason, bytes kept), failed (reason, detail), cancelled; "setup complete". Never a URL path or query (asserted: no `http://` and no `<tag>/` in the log).

**Fault matrix (host, `ModelDownloaderTest` 29 + `ModelSetupTest` 13, real HTTP through `FaultHttpServer`):**

| Fault | Result |
|---|---|
| none | Verified, 1 request, no Range, UA + `Accept-Encoding: identity` sent |
| truncated body (clean close at 300,000 of 700,000) | backoff 2 s, `Range: bytes=300000-`, Verified (prefix re-hashed) |
| connection reset mid-file (RST at 250,000) | backoff 2 s, resume from what was on disk, Verified |
| truncated on every request | 5 failures, backoff 2/4/8/16 s, `Paused(CONNECTION_LOST)`, part kept |
| stall longer than the read timeout | transient, `Range: bytes=200000-`, Verified |
| slow (600 KB/s and 200 KB/s) | Verified, progress monotonic and frequent |
| wrong hash once | part deleted, restart without Range, Verified |
| wrong hash twice | `Failed(DAMAGED)`, part deleted |
| Content-Length / Content-Range total differs from the pin | `Failed(SIZE_MISMATCH)`, part deleted |
| pinned size > 1 GB | refused before any request |
| server ignores Range (200 with an offset) | part truncated, restart from 0, Verified |
| 416 | part deleted, backoff, restart without Range, Verified |
| 404, 410 | `Failed(NOT_FOUND)` at once, existing part kept |
| 403 | `Failed(SERVER)` |
| 500 once / 500 always | Verified after 2 s / 5 requests then `Paused(SERVER_UNAVAILABLE)` |
| 503 Retry-After 7 then 429 Retry-After 120 | waits 7 s, then the normal 4 s |
| 302 and 307 to a second server | followed, Range re-sent to both, Verified; the signed query never logged |
| redirect to http on another host | `Failed(INSECURE)` |
| redirect loop | `Failed(SERVER)` after 6 requests |
| http URL in a release-mode downloader | `Failed(INSECURE)`, 0 requests |
| a complete part with the right hash | reused, 0 requests; with the wrong hash: deleted and fetched |
| pause mid-stream (coroutine cancelled) | part kept, state `Paused(USER)`, next run resumes with `Range: bytes=<kept>-` |
| setup: Cancel after a pause | `discardPartials()` deletes the voice part, the installed net stays |
| setup: low space before the net / before the voice's peak | `Failed(NET|VOICE, INSUFFICIENT_STORAGE)` before any request for that file |
| setup: 404 on the net | Failed, the voice is not attempted |
| setup: voice tar without its model files | `Failed(VOICE, DAMAGED)`, part deleted |
| setup: damaged installed net of the right size | re-downloaded (the engine gate hashes) |

**Tests (counts read from the XML):** `:core` **490/0/0**, 0 skipped (unchanged); `:engine` unit **27/0/0** (10 + `NetHeaderTest` 6 + `NetStoreTest` 11); `:app` unit **341/0/0**, 0 skipped (273 + `DownloadStateMachineTest` 14, `ModelDownloaderTest` 29, `ModelSetupTest` 13, `VoiceStoreTest` 8, `NetworkCallSitesTest` 2, `ManifestPermissionsTest` 6 -> 8; `BackupRulesTest` 7, rewritten). The data/models tests and `VoiceStoreTest` were re-run three more times with `--rerun`: 66/0/0 each time (about 2.8 s for the downloader matrix). Renamed because the old names now lie: `theDefaultSettingsSelectTheKokoroVoice`, `switchOnWritesTheDeviceVoiceAndOffWritesTheKokoroOne`, `kokoroInstalledThroughVoiceStoreSynthesizesRealNonSilentAudio`, `theVoiceStoresScratchModelAndPartFilesAreCoveredByTheTtsRule`, `theNetAndItsPartFileLiveUnderTheExcludedNetsDirectory`; `ManifestPermissionsTest`'s no-network tests became `internetIsRequestedForTheOneTimeModelDownload`, `networkStateIsRequestedForTheMeteredCheckBeforeADownload`, `exactlyTheDownloadExportServiceAndNotificationPermissions`, plus `noNetworkSecurityConfigInTheMainManifest` and `theDebugOnlyConfigAllowsCleartextToTheLocalTestHostsAndNothingElse`. `lintDebug`: **0 errors, 70 warnings** (`:app`; same composition as F1, the two `UsableSpace` warnings now point at the free-space lambda `ChessAnalyzerApplication` gives `ModelSetup` and at `VoiceStore`), `:engine` 0/0. **Instrumented suites not run, by instruction:** they cannot pass until D2c/D2d (no models on a fresh install, seed assets not wired); both androidTest source sets compile.

**Sizes (release = R8, signed with the release key, certificate SHA-256 `ca4f7b42…0947`, zipalign -P 16 OK):**

| Output | F1 (models inside) | D2b |
|---|---|---|
| `app-universal-release.apk` | 353,766,064 B | **96,967,686 B** |
| `app-arm64-v8a-release.apk` | 292,522,515 B | **35,724,137 B** |
| `app-x86_64-release.apk` | 296,780,492 B | **39,982,124 B** |
| `app-armeabi-v7a-release.apk` | 282,389,054 B (D1) | **25,627,310 B** |
| `app-debug.apk` | about 371 MB (R4) | **114,010,909 B** |
| `app-release.aab` | — | **68,454,190 B** |
| Play download per device (bundletool 1.18.3 `get-size total`) | arm64 196,058,239 B (D1) | **arm64 14,368,523 B**, x86_64 15,805,703 B, armeabi-v7a 13,353,361 B |

R8: no new keep rules (no reflection; `org.json` and `java.net` are platform). The R8 mapping keeps `ModelDownloader`, `ModelSetup`, `NetStore`, `VoiceStore`; nothing calls `ModelSetup.run` until D2c, so R8 may still drop parts of it.

**Deviations from the design, with reasons:**
1. `verifiedNetOrNull` compares the **full** SHA-256 (design: the 12-hex prefix): the full pin now exists, and the cost is the same hash.
2. Backoff 2/4/8/16 s and the 5th failure pauses, as the §2.3 diagram says ("n==5 -> Paused"); the 30 s step in §2.2's list is unreachable under that rule and was dropped.
3. `PauseReason.SERVER_UNAVAILABLE` added next to `CONNECTION_LOST`: §1.3 words "server isn't answering" and "connection dropped" differently, the diagram had one reason.
4. 404/410 fail at once (§2.2/§2.3); §1.3's "after 3 attempts" is left to the user's Try again (a tap, never automatic).
5. A part that is already complete and hashes right is reused without a request (§2.2 step 2 deletes it): otherwise a kill during "Unpacking the voice" re-downloads 158 MB.
6. 416 counts as a transient failure (with backoff) instead of a free restart, so a misbehaving server cannot loop.
7. Too many redirects -> `FailureReason.SERVER` (no separate reason); ENOSPC mid-write -> `INSUFFICIENT_STORAGE`.
8. `installVerified` also checks the NNUE header against the compiled engine (§4.2's structural check, applied on first run too: a wrong pin can never reach Stockfish).
9. `generateModelPins` also cross-checks `net.version` with `nnue_common.h`; the inconsistent-lock proof is a shell check through a new `-PpalayaModelsLock` property (the design allowed TestKit or a shell check).
10. `migrateLegacy` does not move `eval_cache/*.json` into per-net directories (that layout belongs to D2e); it deletes stale legacy nets and `.part`s.
11. `SETUP_STORAGE`/`SETUP_DAMAGED` removed with their strings (unreachable); `SETUP_REQUIRED` is raised only on a cache miss.
12. The three old instrumented tests were deleted rather than ported (porting needs the seed assets, which are D2d); D2d ports them from git `HEAD`. `TestApp`/`TestNet` seeding is already written so D2d only wires the assets.
13. `GeneratedModelPins` has no `MANIFEST_PUBLIC_KEY_DER` yet (the key does not exist; D2e). `trialSynthesis` (D2e) not written.
14. The `vendor/models/engine-assets/nnue/` and `app-assets/tts/` layout is kept (not flattened), so D2d can point androidTest assets at the same directories with the old asset paths.

**Found, not fixed / open:**
- Until D2c the app has no way to download: a fresh install analyses nothing ("Finish setting up first."). Until D2d the instrumented suites fail. Both are by plan.
- The end-to-end download against the real GitHub URL cannot be run until the repo and the `models-2026.10` release exist (D2f records it as blocked if still so).
- `docs/PUBLISHING.md`, `docs/STORE_LISTING.md`, `README.md` still describe the bundled, no-network app (D2f per the design).
- `UsableSpace` lint hint: D2c could use `StorageManager.getAllocatableBytes` for the up-front check.


### D2c (small installer: download service, Setup screen, Home card, Set up / Video notices) — DONE, verified on chess36 (API 36) and chess34 (API 34)

Baseline `0479daf` plus the uncommitted F1, D2a and D2b work (kept intact: `SearchBudget`, `ConsistentLines`, the capped flags, `PendingAnalysisStore` resume, the diagnostic log). Nothing committed. Design: `docs/MODEL_DOWNLOAD_DESIGN.md` §1, §1.5, §1.7, §6.5 and §9 row D2c. The session was cut once (the host restarted mid-run); the work was resumed from the tree and the screenshots, and every check below was run on the final code or re-run after it changed.

**Built.**
- `data/models/ModelDownloadService.kt`: a started foreground service of type `dataSync`, cloned from `VideoExportService`. It keeps `progress: StateFlow<SetupProgress?>` and `running` in its companion. It calls the platform `Service.startForeground(id, n, FOREGROUND_SERVICE_TYPE_DATA_SYNC)`. A run starts only from `start()`, which only the Setup screen's button calls; `onStartCommand` runs nothing unless a tap queued it, and it is `START_NOT_STICKY`. Pause cancels the coroutine (parts kept). Cancel cancels the coroutine and then calls `discardPartials()`; with no run in flight it deletes the parts directly. `onTimeout(startId, type)` pauses and stops. The notification (channel `model_download_progress`, low importance, silent, ongoing) has a determinate bar, "120 MB of 257 MB", Pause, Cancel, and a tap that opens Setup (`MainActivity.EXTRA_OPEN_SETUP`). The `model_download_done` channel carries "Palaya Chess is ready" / "Setup paused" / "Setup stopped" / "Setup cancelled" (the last only when Cancel came from the notification; a Pause tapped on the screen posts nothing). `ProgressThrottle` lets status and file-state changes through at once and byte counts at 10 Hz to the UI and 2 Hz to the notification. Diagnostic lines (tag `models`) cover: started/resumed by the user (with the part sizes, the notification permission and the FGS type the platform reports), pause tapped (screen or notification), cancel tapped, paused (reason), failed (reason), done, and the time limit. `ModelSetup`/`ModelDownloader` already log each file's start, milestones, verified, installed and every retry, with hosts only and never a path or query.
- `ui/model/SetupLogic.kt` (pure; `formatStorageMegabytes` moved here): `aboutMegabytes` (decimal MB rounded UP, so a size is never understated: 98.5 → 100, 158.3 → 160, 256.8 → 260, peak 448.6 → 450), `progressMegabytes`, `setupPrecheck` (space first, then no network, then metered), `setupView` (phase, line, rows, buttons from the disk state plus the service's last snapshot), `homeSetupCard`, `ProgressThrottle`.
- `ui/screens/SetupScreen.kt` and `ui/viewmodel/SetupViewModel.kt`, plus `Destination.Setup` ("setup"). Setup is the start destination while `needsNet()`. "Not now" / "Continue in the background" / "Continue" go back where Setup was opened from, or to Home. Layout follows §1.2: the knight, a heading, the body with both sizes, a card with one row per file (size before, "21 MB of 158 MB" with a thin bar during, a check and "Done" after), the status line, a 6 dp overall bar with `stateDescription`, one filled button, Pause/Cancel in a `FlowRow`, and the text link. The metered dialog follows §1.3. Inline errors cover no internet and low storage (both checked before any request). Statuses are a polite live region; while downloading it speaks only "Downloading" (the byte count changes ten times a second), and the bar carries the full line. Errors are assertive and drawn in the error colour. The column is capped at 560 dp and scrolls. File rows stack from font scale 1.3. A damaged installed net counts as missing on this screen (verified once per visit, cached by `NetStore`).
- Space: `ChessAnalyzerApplication.modelStorageFreeBytes()` = `StorageManager.getAllocatableBytes(getUuidForPath(filesDir))` (API 26, so always available; falls back to `usableSpace` only if the service cannot answer). It is used by the pre-check, by `ModelSetup` and by `VoiceStore`. Before a run, `reserveModelStorage()` calls `allocateBytes` for the peak. Lint `UsableSpace` went from 2 to 1 (the one left is `VoiceStore`'s default constructor, used only by tests).
- Home (`ImportScreen`): the "Finish setting up" card above the start card until both files are in. It shows "About N MB left to download." or the running count, plus "Your game is kept…" when a game waits, and one Continue button.
- Analysing: `SETUP_REQUIRED` now shows a filled **Set up** button. The hint now reads "Finish setting up first. Your game is kept and will be reviewed once the chess engine's data is in."
- Video: while the neural voice is chosen but not installed, a notice under the player says "Narrated with the phone's voice until setup is finished.", with **Finish setup**. The narration provider is rebuilt when the voice arrives.
- **A game shared before setup is kept** (task requirement; the design discarded it on "Not now"). It is saved in `filesDir/setup_waiting_game.json` (`PendingAnalysisStore` with a second file name; excluded from backup in all three rule sets, and asserted by `BackupRulesTest`). This happens on a share, a file pick or a paste while the net is missing, and also when an analysis ends in `SETUP_REQUIRED` (the in-flight request is moved there). When the net is in (at launch, or the moment the service installs it), and the user is on Setup or Home, the nav host registers the game and opens Analysing with Home under it, even while the voice is still downloading.
- Strings: the `setup_*` set, `analysis_set_up`, `video_voice_not_installed` and `video_finish_setup`, all in our own words (the §1.3 table's copy, slightly reworded). Notification icon: `ic_notification_model_download` (the Material "file_download" glyph). Manifest: the service, `exported=false`, `foregroundServiceType="dataSync"`; no new permission.
- Tests: `SetupLogicTest` (25: the sizes against the design's 100/160/260/450 MB, no understating, precheck order, every view state including the paused-at-launch, voice-only, net-only, failed and stale-snapshot cases, the Home card, the throttle, and the manifest service declaration). `BackupRulesTest`'s list gained the waiting-game file.

**References used (owner's rule).** chess.com: one primary action per onboarding screen with a secondary text link (S15 in `docs/CHESSCOM_REFERENCE_ALIGNMENT.md`) gave the single filled Download and "Not now". The gated-feature card with one button (Game Review on a free account, S1) gave the Home "Finish setting up" card and the Analysing "Set up" button. Its size-and-progress-bar engine fetch is noted **[memory]** in the design. chess.com has no pause, metered-data or resume pattern, so for those: Google Play's download pattern (size stated before the tap, Wi-Fi preference, pause/cancel in the notification) and the notification actions of the platform download manager. Material 3: a determinate linear indicator with a text label (overall 6 dp, per file 4 dp). No assets or copy were taken.

**Emulator runs.** Debug build `-PpalayaModelBaseUrl=http://10.0.2.2:8787/`, models served by `scripts/model_test_server.py` (unchanged; its faults were enough), mostly with `--fault slow:3m --fault-times 0` so the states can be seen. Every screenshot below was viewed.
- **chess36, fresh install:** Setup is the first screen with both sizes (`d2c_api36_intro`). "Not now" leads to the Home card (`_home_card`). The Immortal Game was shared before setup: Setup reopened with "Your shared game is kept…" (`_shared_game_waiting`). "Not now" again showed the Home card with the same line (`_home_card_game_waiting`), and `files/setup_waiting_game.json` was on disk. Continue, then Download in airplane mode gave "No internet connection…" with Try again (`_no_internet`); nothing was requested (log: `network UNAVAILABLE … -> NO_NETWORK`). Wi-Fi off and mobile data on gave the metered dialog (`_metered_dialog`, "about 260 MB"). Wi-Fi back, Download: the notification permission was asked (`_notification_permission`, allowed here). Downloading showed 15 MB of 257 MB (`_downloading`), with `dumpsys`: `isForeground=true foregroundId=4201 types=0x00000001`. Pause (`_paused`, the part kept at 37,643,584 B, the service gone). Resume: the server saw `Range: bytes=37643584-` (`_resumed`).
- **Fault: server killed mid-download.** "Retrying… (2 of 5)" (`_retrying`). The emulator's connects to a dead host port time out (15 s each) rather than being refused. After 5 failures, about 90 s: "The connection dropped. Your progress is saved; resume when you're back online." with Resume/Cancel (`_connection_dropped`), and the "Setup paused" notification. Server restarted, Resume: `Range: bytes=67265856-`.
- **Fault: airplane mode mid-download.** At once a transient failure, then ConnectException backoffs, then "connection dropped" after about 30 s (`_airplane_mid_download`, `_airplane_paused`). Airplane off, Resume: `Range: bytes=79717696-` (`_after_airplane_resumed`).
- **Analysis as soon as the net is in:** the waiting Immortal Game opened in Analysing by itself while the voice went on downloading (`_net_in_game_analysing`; log `setup installed the engine data: analysing the waiting game …`), then reached its Summary (`_waiting_game_summary`, 80% / 72%). Video showed the voice notice with Finish setup (`_video_voice_notice`), and Finish setup opened Setup (`_voice_paused_from_video`).
- **Pause and resume from the notification:** shade (`_notification`, `_notification_expanded`: "Setting up Palaya Chess · 204 MB of 257 MB", Pause, Cancel). Pause in the notification: `pause tapped in the notification`, part kept (`_notification_after_pause`). **Defects found here and fixed:** (1) the ongoing progress notification stayed after a Pause from the notification; (2) an earlier "Setup paused" notification stayed after Resume. The fix: no progress post once Pause/Cancel is asked for or for a non-running status, `cancel(4201)` after `stopForeground`, and `cancel(4202)` when a run starts. **Re-verified on the fixed build:** after Pause only 4202 "Setup paused" was left (`_fix_notification_after_pause`); tapping it opened Setup (`_opened_from_notification`); after Resume only 4201 was listed.
- **Process killed mid-download:** voice at 144 MB, Home key, `kill -9` of the app's pid (`run-as`). The service was not restarted (`pidof` empty 8 s later; START_NOT_STICKY). On relaunch (Android restored the back stack): "Paused at 91%. Your progress is saved." with Resume/Cancel (`_after_kill_home`). Resume sent `Range: bytes=144502368-`, the part was reused, then verified, installed, "All set" (`_done`).
- **chess34, fresh install:** intro (`d2c_api34_intro`). Font 2.0 (`_intro_font2`: everything fits, rows stacked), landscape at 2.0 (`_intro_land_font2`, `_intro_land_font2_scrolled`: the column scrolls, nothing clipped), landscape at 1.0 (`_intro_land`), RTL via the per-app locale `he` (`_intro_rtl_he`: mirrored, "About 100 MB" not reordered). **Low storage:** a 4,000 MB file in `/data/local/tmp` left 337 MB on `df`; `getAllocatableBytes` reported 42 MB; Download gave "Palaya Chess needs about 450 MB of free space to set up (260 MB once it's done)…" (`_low_storage`) and nothing was requested. Notifications were **denied** twice (`_notification_permission`). The download ran anyway, as a foreground service: `isForeground=true foregroundId=4201 types=00000001`, log `notifications not allowed, fgs type 1` (`_downloading_no_notifications`).
- **Fault: wrong hash** (`--fault hash --fault-times 2`): first mismatch, part deleted, restarted by itself; second mismatch, "The download didn't match the expected file, twice in a row…" with Try again (`_damaged`). **Fault: server 500** (`--fault 500 --fault-times 0`): backoff 2/4/8/16 s, then "The download server isn't answering right now. Try again in a few minutes." with Try again (`_server_unavailable`). **Cancel** during the voice: `setup cancelled by the user; part files deleted`, `tts_models/` empty, the net kept, and the screen showed "Download the voice (about 160 MB)" with the net row done (`_after_cancel_voice_only`). Font 2.0 while downloading: `_downloading_font2`, `_downloading_font2_scrolled`. "Unpacking the voice…" was caught (`_unpacking`). The Immortal Game was analysed after setup: 81% / 73% (`_immortal_summary`).
- **Set up from Analysing (chess34):** the net and the eval cache were removed by hand. Launch opened Setup with "Download (about 100 MB)" (`_net_missing_start`). Not now, then reopening the recent game: "Couldn't analyze this game / Finish setting up first. Your game is kept…" with **Set up**, Back and Share details (`_setup_required`). Set up opened Setup with the waiting line (`_setup_from_setup_required`). Download: once the net was installed, the game was analysed by itself (`_setup_required_game_resumed`).
- **Emulator network loses bytes at speed (found; not an app defect).** Unthrottled, and once at 3 MB/s on chess36, a long response ended a few bytes short (`ProtocolException` near the end). The resumed tail then failed the SHA-256, so the app restarted the file and, the second time, reported "didn't match" (correctly). Proof that it is the emulator and not the app: from the host, `curl` of the net from the same server hashed correctly (`1a298aa5…dfc2`); on chess34, a raw `toybox nc` fetch of the same URL came back 13 bytes short and hashed wrong (`1da7f273…`), and a second fetch differed again. The runs above used `slow:3m`, where it is rare. On a phone the TLS layer would turn such a loss into a connection error, which resumes cleanly.

**Offline proof (chess36, the setup from the run above, D2c debug build).** Airplane mode on, Wi-Fi and data off: `ping 8.8.8.8` gives "Network is unreachable" and `dumpsys connectivity` gives "Active default network: none". The app was force-stopped and logcat cleared, then the app was launched (pid 2172): Home with the recent game and no card (`_offline_home`). `games/game01.txt` was shared: analysed at Standard (depth 14, 25 M nodes / 150 s) in 2 min 58 s, 67 positions, 2 capped (`_offline_analysing`, `_offline_summary`: 72% / 86%). Video showed no voice notice (the voice was installed) (`_offline_video`). Save video: "Preparing narration… (0/61)", then rendering, then "Video saved, 8 min 23 s · 41 MB" (`_offline_export_progress`, `_offline_export_done`); the export took 35 min with a second emulator busy. Diagnostic log: `export start: 61 segments, voice NeuralTtsProvider, fgs type 8192`. The logcat of the whole session (25,136 lines, 444 from pid 2172) has no line from pid 2172 matching http, socket, UnknownHost, github, 10.0.2.2, ModelDownload, ModelSetup or okhttp. The app pid shows `sherpa-onnx` (Kokoro) and the TextToSpeech binder, which is local. The only URL lines come from other pids (system SpeechPackManager, AiAi, Checkin, ConfigUpdater). The diagnostic log has **no `[models]` line after `setup done: both files installed`**, so the downloader was not invoked. `dumpsys netstats` was not read.

**MP4 (pulled with `adb pull`, measured on the host):** 502.63 s, 40,926,371 B, h264 1280x720 30 fps + aac 44.1 kHz mono. `volumedetect` whole file: mean **-25.3 dB**, max **-5.3 dB**. Last 3.5 s: -91.0 dB (the silent recap). Frames at 2 s (`d2c_api36_mp4_intro`: names, "0-1 · 33 moves · Scandinavian Defense: Marshall Variation (B01)", "White 72% · Black 86%") and at 250 s (`_mp4_middle`: move 25 missed tactic, Qf4, +2.9, the caption) were viewed. The MP4 was deleted from the device.

**Release (R8) on chess36:** `:app:assembleRelease` (its own invocation): arm64 35,799,699 B, x86_64 40,057,682 B, armeabi-v7a 25,702,874 B, universal 97,043,255 B (D2b: 35,724,137 / 39,982,124 / 25,627,310 / 96,967,686). Debug app uninstalled (different key), x86_64 release installed. Setup rendered (`d2c_api36_release_intro`); permission (`_release_permission`). Download went to the provisional GitHub URL and got **"The engine files aren't on the server. Update Palaya Chess, or try again later."** (`_release_not_found`). The service started as FGS (ActivityManager "Background started FGS: Allowed … ModelDownloadService"). No FATAL, ClassNotFound or NoSuchMethod. No new keep rule was needed. Release uninstalled.

**Gate (counts read from the XML, final code):** `:core` **490/0/0**, 0 skipped; `:engine` unit **27/0/0**; `:app` unit **366/0/0**, 0 skipped (341 + 25 `SetupLogicTest`); `lintDebug` **0 errors, 69 warnings** (70 before: `UsableSpace` 2 → 1; the two warnings D2c first added, `ObsoleteSdkInt` in the service and `ModifierParameter` on the error state, were fixed). Instrumented suites were not run (seed assets are D2d's, by instruction).

**Device state restored.** chess36: airplane off, Wi-Fi and data on, font 1.0, rotation auto (user 0), locale untouched, our app uninstalled, temp PGNs deleted (nothing of ours was installed at the start). chess34: font 1.0, rotation back to auto, the per-app locale cleared, the fill file deleted, `adb unroot`, our app uninstalled, `net.palaya.chessanalyzer.engine.test` left as found. Both emulators shut down. Host server stopped.

**Deviations from the design, with reasons:**
1. A game shared before setup is **kept** (the task asked for it); the design discarded it on "Not now". It waits on disk, not in the route: the route is plain `setup`, not `setup?next=<id>`.
2. It is analysed when the **net** is in, not at "All set" (the task: "available as soon as the net is in").
3. A running download offers "Continue in the background" instead of "Not now" (the latter would read as "stop").
4. "Retrying… (n of 5)" counts the attempt being made (2..5).
5. Download sizes are decimal MB rounded up. The Settings storage row keeps its MiB with one decimal.
6. `ModelSetup`'s free-space reading and `VoiceStore`'s (in the app) use `getAllocatableBytes`, plus an `allocateBytes` before a run.
7. The terminal notification is skipped for a Pause or Cancel tapped on the screen.
8. A net-only body ("The narration voice is installed. The chess engine's data … is still needed…") was added for a net removed after setup (seen on chess34).

**Found, not fixed / open:**
- The emulator network loss above: use `--fault slow:3m` for emulator runs.
- `ModelSetup` logs the 25/50/75% milestones from 0 on every run, so a resume past 75% logs all three at once (cosmetic, D2b code).
- A failure's detail in the log is the last transient error (e.g. `DAMAGED: ProtocolException…`), not the hash (cosmetic).
- The paused state found on disk at launch offers Cancel even with nothing to delete after a 5xx pause (harmless).
- `SetupFlowInstrumentedTest` (design §6.2) is D2d's.
- The release run against the real GitHub URL is blocked until the repo and `models-2026.10` exist (D2f).


### D2d (small installer: instrumented tests, seed assets) — DONE, verified on chess36 (API 36) and chess34 (API 34)

Baseline `0479daf` plus the uncommitted F1, D2a, D2b and D2c work (kept intact). Nothing committed. Design: `docs/MODEL_DOWNLOAD_DESIGN.md` §6.2 and §9 row D2d.

**Seed assets (how the models reach the tests).** `vendor/models/engine-assets` (net) and `vendor/models/app-assets` (voice tar) are the **androidTest** asset directories of `:app` (both) and `:engine` (net only), stored uncompressed (`noCompress` `.nnue`/`.tar`; the seed tests prove "stored" with `openFd()`). A new `checkTestSeedAssets` task in each module runs before `merge*AndroidTestAssets` and fails with "Run scripts/fetch_models.sh" when the files are missing; `generateModelPins` already verifies them against the lock when present. `TestApp.ensureSetUp()` / `TestNet.net()` (written in D2b) install them through `NetStore.installVerified` / `VoiceStore.installFromStream`; `SeedAssetBody` (androidTest) serves them straight from the APK's file descriptor through `FaultHttpServer`, which gained a `Body` abstraction (`serveBody`) so the 98.5 MB and 158 MB files are neither copied to disk nor held on the heap. **Absent from the app:** `unzip -l` of `app-debug.apk` (114,165,656 B) and of all four release APKs (universal 97,043,249 B, arm64 35,799,700 B, x86_64 40,057,678 B, armeabi-v7a 25,702,867 B) shows no `.nnue`, no `.tar`, no `assets/nnue` or `assets/tts` (assets: the five licence texts and `openings.tsv`, plus `dexopt/` in release). **Present in the test APKs:** `app-debug-androidTest.apk` 258,231,489 B with `assets/nnue/nn-1a298aa575a0.nnue` (98,511,183 B, Stored) and `assets/tts/kokoro-int8-en-v0_19.tar` (158,269,440 B, Stored); `engine-debug-androidTest.apk` 104,951,066 B with the net (Stored).

**Tests added or ported** (`:app` 90 → 153, `:engine` 9 → 20):
- `engine/.../NetStoreInstrumentedTest` (11, ported from HEAD's `BundledNetProviderInstrumentedTest`): the seed is stored with the pinned size and hash; a fresh dir has no net; a verified part moves into `nets/` and passes the engine gate (cold store too; the header matches the compiled engine); a truncated net is neither active nor verified; a corrupt net of the right length is refused by the gate after a cold start; verifying never rewrites, and a changed file is hashed again; a part with a bad architecture hash is refused by `installVerified`; a part that skipped verification is still refused by the gate; a missing part is an error; Cancel (`deleteParts`) keeps the installed net; the legacy migration moves `filesDir/<net>` into `nets/` and deletes stale copies.
- `video/VoiceStoreInstrumentedTest` (15, ported from `BundledVoiceInstallerInstrumentedTest`): the stored seed; the real tar as a `.part` unpacked by `installFromTar` (part deleted, required files, marker = pinned hash, `installedVersionId`, 134,186,977 B model, monotonic progress); a cold start without rewriting; a stale marker and a missing file repaired; a killed run's scratch discarded; cancel mid-unpack leaves nothing; `deletePart` keeps the voice; synthetic tars: top-level strip, zip-slip refused, tampered hash, a damaged part kept for the caller, short archive, missing file, low space.
- `data/models/ModelSetupInstrumentedTest` (6, the download successor of `FirstRunSetupInstrumentedTest`): the REAL net and voice with this build's pins over the in-process server. A fresh run (one request per file, byte-weighted monotonic progress, the net's share of 38% at its install, UNPACKING seen, DONE at 1); a second run asks for nothing; low space stops before the request that would not fit (the net, then the voice only); only the missing voice is fetched and counted; a dropped connection resumes with a Range; a voice corrupt on every response fails DAMAGED after one automatic restart, with the part deleted and the net kept.
- `data/models/ModelDownloaderInstrumentedTest` (30): the host fault matrix, moved into `app/src/sharedTest/.../ModelDownloaderFaultMatrix` (abstract; the host `ModelDownloaderTest` is now a one-line subclass, still 29) and run on the device with Android's HttpURLConnection and the debug network config, plus the real net dropped at 40 MB, resumed, verified and installed. `TestModelFiles` moved to sharedTest too.
- `data/models/SetupFlowInstrumentedTest` (6): the real `ModelDownloadService`, started from a foreground `MainActivity` as the Setup button does, against `FaultHttpServer` in-process (small stand-in files at 500 KB/s) and a scratch dir via the new `ChessAnalyzerApplication.modelSetupForTesting` seam. The FGS type is `FOREGROUND_SERVICE_TYPE_DATA_SYNC` from the platform (`lastForegroundServiceType`) and from `dumpsys activity services` (chess36 `isForeground=true foregroundId=4201 types=0x00000001`, chess34 `types=00000001`), and the service is gone after DONE. Pause keeps the part and Resume sends `Range: bytes=<kept>-`. The Activity is destroyed mid-download and the run still finishes. Cancel via the notification's intent deletes the voice part and keeps the net. Cancel of a paused setup deletes its part. An `ACTION_START` intent that no tap queued makes no request and writes nothing.
- `NoNetworkAfterSetupTest` (1): see below. `NetworkSecurityConfigTest` (3): debug build; cleartext allowed to 10.0.2.2, 127.0.0.1 and localhost; refused for the base config, github.com, example.com, 10.0.2.3, 192.168.1.1 and sub.localhost. `SetupGateInstrumentedTest` (2): with no net (a scratch dir via the seam) `MainActivity` opens on Setup (title and "Not now", no Home); with the seeded net it opens on Home. No other test opens the nav host expecting Home (`VideoExportServiceInstrumentedTest` only needs a foreground Activity).

**`NoNetworkAfterSetupTest`: method and result.** After `ensureSetUp()` a recording `java.net.ProxySelector` becomes the process default and is proven live first: an HttpURLConnection to an in-process `FaultHttpServer` is recorded. Android's OkHttp passes the route URI `http://127.0.0.1:<port>/`. A raw `java.net.Socket` is NOT seen by the selector on this libcore, which is why TrafficStats is the second instrument. Then the uid's `TrafficStats` tx/rx is read. The measured window covers: `MainActivity` opened at Home for 5 s; a full analysis of the Opera Game (a unique tag, so no cache answers) at Standard (depth 14, 25 M nodes / 150 s), 33 annotations; and the game's own `VideoScriptGenerator` script (first 4 segments plus the recap card) narrated by Kokoro and saved through the real `VideoExportService` (MediaStore). **Result: on chess36, a 247 s window, 0 `select()` calls, TrafficStats tx 0 B / rx 0 B; on chess34, a 319 s window, 0 calls, tx 0 / rx 0.** `UNSUPPORTED` would have counted as "not measured" (it was not seen). The update-check half (exactly two calls) is D2e's.

**Benchmark.** `EngineBenchmarkTest.benchmarkDepth18` now runs exactly as the app has since F1: `go depth 18 nodes 45000000 movetime 270000` (`SearchBudget.DEEP`, restated as constants in `:engine`, which cannot import `:app`). The per-position timeout is movetime + 60 s (it was an unbounded search with a 900 s cap). It logs total, mean and worst time and every capped position. chess36: 34 positions in 239.2 s, worst 26.6 s, 0 capped. chess34: 365.6 s, worst 64.7 s, 1 capped (ply 23 at depth 15, 45,000,079 nodes). `benchmarkDepth12` is unchanged (18.9 s / 46.1 s).

**Defects found and fixed.**
1. **App crash on Resume right after Pause** (found by `SetupFlowInstrumentedTest` on chess36). The service's run tail set `running=false`, published "Paused", then called `stopSelf()` unconditionally. A Resume in that window had already called `startForegroundService()`; the service was destroyed before its `startForeground()`, and the platform killed the app with `ForegroundServiceDidNotStartInTimeException`. Fix in `ModelDownloadService`: the flags are reset before `running` goes false, and the stop runs on the main thread (where `start()` and `onStartCommand()` run) only when no new run is queued (`activeJob == null && !startQueued`). It passed on both devices afterwards.
2. `VideoShareInstrumentedTest.theShareIntentNeedsNoPermissionOfItsOwn` still asserted "no INTERNET" (stale since D2b). It now asserts that no storage permission is requested; INTERNET is pinned by `NetworkPermissionTest`.
3. `VideoExporterInstrumentedTest`'s "duration must not track the wall clock" check failed on chess36 when the emulator happened to export at real time (18.6 s for a 19.4 s video). That is inconclusive by coincidence, not a defect. Now an inconclusive first export is followed by the short script, whose fixed start-up cost puts wall clock far from duration, and both checks must hold there. (Both final runs were conclusive, at ratio 1.13.)

**Counts (read from the XML, final code).** `:app` connected **153/0/0, 0 skipped, on chess36** (713.6 s; Gradle 12 m 15 s) and **153/0/0, 0 skipped, on chess34** (785.7 s; 13 m 26 s). `:engine` connected **20/0/0, 0 skipped, on chess36** (268.3 s) and **20/0/0, 0 skipped, on chess34** (422.9 s). Earlier chess36 attempts, for the record: `:app` 71 run with 2 failures and then a crash (defect 1, plus the ProxySelector control matching the URI path), then 153/2/0 (findings 2 and 3), then the green run. `:core` **490/0/0**, `:engine` unit **27/0/0**, `:app` unit **366/0/0** (all with `--rerun-tasks`, 0 skipped), `lintDebug` **0 errors, 69 warnings** (`:engine` 0/0). `:app:assembleRelease` OK (sizes above, no seed assets). The suites ran one emulator at a time, never concurrently.

**Device state restored.** Gradle uninstalled the app and test packages on both devices. chess34's `net.palaya.chessanalyzer.engine.test` (present at the start) was reinstalled from this build. Three MP4s from 5 Oct in chess34's `Movies/ChessAnalyzer` predate this run and were left; the tests delete their own videos. No settings were changed. Both emulators are shut down.

**Deviations from the design, with reasons.**
1. `ActivationRollbackInstrumentedTest` and the `UpdateChecker` half of `NoNetworkAfterSetupTest` are not written: the code they test is D2e's.
2. The narrated export in `NoNetworkAfterSetupTest` is the analysed game's own script cut to its first 4 segments plus the recap. It is the same pipeline as a whole review, which takes about 4x real time on an emulator.
3. `SetupFlowInstrumentedTest` uses small stand-in files so every state can be caught on the way; the real 257 MB go through the same code in `ModelSetupInstrumentedTest` and `ModelDownloaderInstrumentedTest`.
4. A test seam in main code: `ChessAnalyzerApplication.modelSetupForTesting` (`@VisibleForTesting`, null in the app, set by nothing in main code). The service and the gate read `app.modelSetup`, which honours it. `TestApp.ensureSetUp()` checks the real stores, not `app.modelSetup`.
5. The design's "fresh `filesDir`" is a scratch directory per test: the shared filesDir keeps the seeded models the other tests need.

**Found, not fixed / open.**
- No instrumented test covers edge to edge (D1 checked it by hand with screenshots), and none was added here.
- `TrafficStats` accounting of loopback traffic was not verified (the probe ran before the baseline). The zero covers off-device traffic, which is what matters.
- The release build carries the unused `modelSetupForTesting` field; R8 keeps it. Harmless.
- Unchanged from D2c: milestone logging on resume, failure detail wording, and Cancel offered after a 5xx pause. The release run against the real GitHub URL is blocked until the repo exists (D2f).


### D2e (small installer: "Check for updates", signed manifest, journaled activation, trial and rollback) — DONE, verified on chess36 (API 36) and chess34 (API 34)

Baseline `0479daf` plus the uncommitted F1, D2a-D2d work (kept intact: the F1 budget in the eval-cache key, `ConsistentLines`, the D2c setup flow and service, D2d's seeding and `NoNetworkAfterSetupTest`). Nothing committed. Design: `docs/MODEL_DOWNLOAD_DESIGN.md` §1.8, §3.2-§3.4, §4, §6, §9 row D2e.

**Built (`app/.../data/models/` unless noted).**
- `ModelManifest.kt`: `models.json` parsed with org.json, only after the signature has verified. As built: `schemaVersion` must be 1; `models[]` entries with `id` `engine-net` / `voice-kokoro-en` (any other id is skipped unread, unknown fields ignored); required `displayName`, `version`, `fileName`, `url`, `size` (whole number > 0), `sha256` (64 hex, lower-cased), `minVersionCode`, `compat`; optional `maxVersionCode` (null = none); `compat` = `{"kind":"stockfish-nnue","version":"0x6a448afa","archHash":"a85b2205","engineTag":…}` or `{"kind":"sherpa-onnx-kokoro","layout":"kokoro-v0_19"}` (another kind parses as `Other`, never compatible); `runtime` `{"name":"sherpa-onnx","min","max"}` required for a voice. A missing/malformed required field rejects the whole manifest. Exactly `publish_models.sh`'s output.
- `ManifestSignature.kt`: `SHA256withECDSA`, `KeyFactory("EC")`, X.509 DER public key, P-256 only (field size 256), signature at most 512 B; never throws. The key is compiled in.
- `ModelCompatibility.kt` (pure): version-code range (inclusive), URL https (debug: http to 10.0.2.2/127.0.0.1/localhost) AND under the app's base URL (no `/../`, query or fragment), net = `stockfish-nnue` + header version + architecture hash equal to this engine's pins + `nn-<12hex>.nnue` matching the SHA-256 and the URL + 50-400 MB, voice = `sherpa-onnx-kokoro` + `kokoro-v0_19` + sherpa-onnx range containing 1.13.8 (numeric dotted compare) + plain `.tar` + 20-600 MB; offered iff compatible and not the installed SHA-256.
- `UpdateChecker.kt`: only on the Settings tap. No network -> NoInternet, nothing requested. Otherwise exactly two `ModelDownloader.fetchSmall` requests (`models.json` <= 64 KB, `.sig` <= 512 B; one attempt, same https/redirect/timeout rules, hosts only in the log), then signature, then parse, then compatibility. Results: UpToDate / Available (at most one offer per kind) / NoInternet / ServerUnavailable / NotFound / SignatureInvalid (also a missing `.sig`) / ManifestInvalid. `ModelDownloader.fetchSmall` is new; `ModelDownloader.kt` is still the only connection site (`NetworkCallSitesTest` unchanged and green).
- `ActivationJournal.kt`: `filesDir/models/activation.json` (backup-excluded), atomic temp + fsync + rename. `ActivationMachine` (pure): net `swapped -> trial -> committed`, voice `trial -> swapped -> committed`; at start an in-flight record before `committed` = roll back, at `committed` = finish the clean-up, a `rolledBack` record = the Settings notice, an unreadable file = fail safe to the compiled net.
- `ModelActivator.kt`: `recoverOnStartup()` (first thing in `ChessAnalyzerApplication.onCreate`, renames only, before anything can touch the engine), `activateNet`, `activateVoice`, `pendingNotice`/`acknowledgeNotice`, a test-only `checkpoint` hook. Net: the header is checked in Kotlin first (version, arch hash, description < 1 KB, size = manifest, >= `MIN_PLAUSIBLE_NET_BYTES`), so a wrong-architecture file never reaches Stockfish; then inside `EngineController.exclusive`: journal `swapped`, `installVerified` + `setActiveIdentity(new)`, journal `trial`, `trialLocked()` (the engine gets `verifiedNetOrNull()`: full SHA-256 against the signed value), journal `committed`, delete the old net, purge other nets' eval caches, clear. A trial that throws is rolled back at once (old identity restored and re-loaded). Voice: unpack + verify into `kokoro.extracting/`, journal `trial`, throwaway synthesis (`NeuralVoiceTrial`: loads, >= 1 speaker, a sample rate, > 300 ms and RMS > 200 on 16-bit, the evidence tests' floor), journal `swapped`, `kokoro/` -> `kokoro.previous/`, scratch -> `kokoro/`, `.compat` + marker, journal `committed`, delete previous + part, `NarrationStore.clear()`.
- `ModelUpdateInstaller.kt`: judges the entry AGAIN with `ModelCompatibility` before any request (an incompatible entry is never downloaded, whatever the UI does), checks space, downloads with `ModelDownloader.download` (manifest size + SHA-256; resumable part: `nets/<name>.part`, `tts_models/update-<12hex>.tar.part`), hands over to the activator. Cancel deletes the part.
- `ModelUpdates.kt`: app-scope state (`UpdateUiState`), one thing at a time; "Last checked" (`models_last_update_check_ms` in the settings DataStore) only when the server was asked.
- `:engine` `NetStore`: `ActiveNet` identity = the compiled pin or `nets/active.properties` (an update's name/size/SHA-256; ignored when its file's header is foreign, e.g. after an app update that changed the engine); `activeNetOrNull`/`verifiedNetOrNull`/`netFile` follow it; `setActiveIdentity`, `hasUpdateRecord`, `deleteNet` (never the active one), `otherNets`. Setup resets the record when it installs the compiled net.
- `EngineController`: `beginAnalysis`/`endAnalysis` (used by `AnalysisService` around its engine work), `analysisInFlight`, `exclusive` (refuses while an analysis runs; a new analysis waits for it), `trialLocked`, `switchNet()`, `loadedNetName`. Still one engine; the `setEvalFile`/`analyze` guards are untouched.
- Eval cache per net: `EvalCacheLayout` (`eval_cache/<12hex>/`), `GameRepository(filesDir, netPrefix)`; the flat cache is moved into the active net's folder at start (`migrateFlatEvalCache`); `AnalysisService` saves under the net the engine actually loaded. F1's budget stays in the key.
- Narration: `NeuralTtsProvider(voiceVersionId)`, fingerprint `KOKORO@<12hex>/sid…/ls…`; `VoiceStore` gains `extractToScratch`, `swapInScratch`, `restorePrevious`, `deletePrevious`, `discardScratch`, `updatePartFile`, `installedSha256`, and accepts an update's voice through `kokoro/.compat` while layout and runtime still match (an archive cannot bring its own marker or `.compat`).
- UI: Settings gets a fifth row below About, "Check for updates" / "Engine data and voice. Last checked: never|<date>" (or "Checking…", "Installing an update…", or the reason it is disabled). The tap opens the result sheet (`UpdateSheet`, ModalBottomSheet) and starts the check. Sheet states: checking, up to date, update available (one card per offer: "Narration voice v0_19-r2 · about 160 MB" + Download and install), no internet, server unavailable, not found, signature invalid, unreadable, connecting / downloading X of Y / retrying (n of 5) / checking the file / unpacking / trying the new version (bar with `stateDescription`), installed ("Installed." / the net's "Games you reopen will be re-analysed…"), rolled back, connection dropped, damaged, low storage, incompatible, busy, failed. The metered dialog and the space/no-network pre-check reuse Setup's rules. Status = polite live region (speaks "Downloading", not bytes), errors assertive in the error colour, title a heading, Material buttons, no `maxLines`, scrolls. The row is disabled during an analysis, an export or a running setup download; installing also needs a finished setup. The startup rollback notice shows once on the row. Pure logic in `ui/model/UpdateLogic.kt`; `UpdatesViewModel`.
- Signing: P-256 pair generated 2026-10-07; private `keystore/models-signing.pem` (gitignored by `*.pem`, `git check-ignore` confirmed; never printed), public `vendor/models/manifest_public_key.der` (committed; SHA-256 `912744011368613075e4fdd3604d2e6de4be46528f5e29e5dcfa4300e449f699`), compiled into `GeneratedModelPins.MANIFEST_PUBLIC_KEY_DER_BASE64` by `generateModelPins` (fails unless it is a 91-byte P-256 SPKI). `publish_models.sh` signs (as before) and now `--verify`s before uploading, plus `--verify [file]` and `--sign <file>` modes (checked: dry-run + sign + verify OK; a changed byte -> "does NOT verify", exit 1). `model_test_server.py --manifest-dir DIR` serves a signed manifest and extra files. Key custody in `docs/PUBLISHING.md` §4b.

**Tests (host).** `:engine` NetStoreTest +5 (identity record, cold store, wrong bytes, foreign header = stale, malformed record / bad identity). `:app` +77: `ModelManifestTest` 8, `ManifestSignatureTest` 7 (valid; any changed byte incl. a trailing newline; a changed signature; another key both ways; empty/garbage/huge signature and a broken key; a P-384 key refused even with a matching signature; an **openssl-made signature verified by Java** (fixture made with a throwaway openssl key, private half deleted; `.gitattributes` keeps it byte-exact); the compiled-in key = the committed file, P-256, and a test key's signature fails against it), `ModelCompatibilityTest` 9 (every §3.3 rule and boundary, incl. arch hash off by one and the debug loopback rule), `ActivationJournalTest` 6, `ModelActivatorTest` 14 (net success; trial failure rolled back and re-loaded; wrong arch never reaches the engine; busy; simulated death at `journal-swapped`, `trial`, `committed`; voice success; silent voice refused; busy export; death in voice `trial`, `journal-swapped`, `swapped`, `committed`; unreadable journal), `UpdateCheckerTest` 13 (exactly two requests; **wrong-arch net neither offered nor requested, and refused by the installer with zero requests**; a manifest that lies about the arch is downloaded, then refused by the header check, the engine never loads it; tampered manifest; another key; the app key rejects a test signature; unsigned; schema 2; no network = zero requests; 404/500/503/server down; already installed; full net install with its phases; damaged download; voice install), `ModelUpdatesTest` 3, `EvalCacheLayoutTest` 5, `UpdateLogicTest` 7, `NarrationFingerprintTest` 1, `VoiceStoreTest` +4. Mutation check: with the arch rule disabled, 2 tests fail.

**Tests (instrumented, new).** `ActivationRollbackInstrumentedTest` (6, the app's ONE engine and real stores; the "update" net is the real net with one description byte changed: same architecture, new SHA-256 and name): a same-arch net is tried on the app's engine, becomes the net analyses use (an analysis caches under `eval_cache/<its prefix>/`), and the compiled net is then activated back the same way (the other folder purged); **a crash mid-trial** (the process "dies" after the engine loaded and searched the new net) is rolled back by a fresh `recoverOnStartup()` before the engine is touched, the notice is pending once, and the engine then loads the old net and finds the mate; a net damaged on disk between swap and trial is refused by `verifiedNetOrNull()` inside the trial, so the engine never loads it, and it is rolled back at once; a wrong-arch net (byte 5) is refused before the engine; voice deaths at `trial`, `journal-swapped`, `swapped` rolled back on device storage; the real `NeuralVoiceTrial` on the installed Kokoro passes (chess36: 11 speakers, 24 kHz, 4420 ms, RMS 1869) and fails on a broken directory. `UpdateCheckNetworkTest` (1, the sibling of `NoNetworkAfterSetupTest`): through the real UI (Home, the Settings button, the Settings screen showing the row, 5 s), **0 `ProxySelector` routes and 0 server requests before the tap**; after the tap exactly `/models/models.json` and `/models/models.json.sig`, exactly 2 routes, both to the in-process `FaultHttpServer`, "Update available" with the voice listed and the wrong-arch net not; no model file requested. The manifest is signed by a test key generated in the test (`ChessAnalyzerApplication.updateCheckerForTesting`).

**Emulator end to end (chess36, debug build with `-PpalayaModelBaseUrl=http://10.0.2.2:8787/`, `scripts/model_test_server.py --manifest-dir … --fault slow:3m --fault-times 0`).** The "upgrade" is honest about what it is: no other compatible model exists, so `kokoro-int8-en-v0_19-r2.tar` is the upstream Kokoro tar with one small text file appended (`PALAYA_REPACK.txt`): the same model files, the same size (158,269,440 B: the member fits the tar's record padding), a different SHA-256 (`920d81a7…`). The signed manifest (signed with the real key via `publish_models.sh --sign`, so the app's compiled-in key verified it) listed: the installed net (compatible, already installed), a **wrong-architecture net** (the real net with bytes 4..7 = 0xdeadbeef, `nn-6633c73c5319.nnue`, `compat.archHash` `deadbeef`, served by the server) and the voice r2.
1. Fresh install, Setup download (net then voice, no manifest request), a 17-move game analysed, a Kokoro-narrated MP4 exported: the narration cache held 118 WAVs (27.6 MB), voice marker `7190c480…`.
2. Settings row: "Last checked: never" (`d2e_api36_settings_row`). Airplane mode: "No internet connection." and **no request** reached the server (`_no_internet`). (Found here: that check still stamped "Last checked"; fixed, `ModelUpdatesTest`.)
3. Network back, Check again: exactly `GET /models/models.json` + `.sig`; the sheet listed only "Narration voice v0_19-r2 · about 160 MB" (`_update_available`); log: `not offered: … nn-1a298aa575a0.nnue (already installed); … nn-6633c73c5319.nnue (NET_ARCH: architecture 0xdeadbeef, engine 0xa85b2205)`.
4. Download and install: "Downloading… 25 MB of 158 MB" (`_downloading`); 50 s download, verified against the manifest's SHA-256, unpacked in 1.5 s, trial 10 s (11 speakers, 24 kHz, 4417 ms, RMS 1877), "Installed." (`_installed`). After: marker `920d81a7…`, `.compat` written, no `kokoro.previous`, no part, journal gone, **narration cache 118 -> 0 files**.
5. Check again: "You're up to date." (`_up_to_date`). Tampered manifest (version string changed, original signature): exactly two requests, "The update information couldn't be verified, so nothing was downloaded." (`_signature_invalid`), log `ManifestRejected(signature)`.
6. **Narration still works:** the same game exported again: 118 WAVs re-synthesized under the new voice id. Pulled and measured on the host: the largest WAV 24 kHz mono, 21.24 s, RMS 1927 (-24.6 dBFS), peak 13709; the MP4 (pulled) 5 min 15 s, h264 1280x720 + AAC 44.1 kHz mono, volumedetect mean **-25.1 dB**, max **-6.0 dB**.
7. **A real process death mid-trial:** a second upgrade (r3, same method) offered and installed; when the diagnostic log said "trying it", the screen was captured ("Trying the new version…", `_trying_then_killed`) and the app's pid was `kill -9`ed. On disk: the journal at `trial`, `kokoro.extracting/` and the update part present, the marker still r2. Relaunch: the diagnostic log shows `previous process exit: SIGNALED … status 9` and `recoverOnStartup: voice 176cda7cb66e rolled back (died in trial…)`; scratch and part deleted, the journal replaced by the notice, the voice still r2. Settings showed "The update couldn't be used, so the previous version was restored." once (`_rolled_back_notice`), not on the next visit.
8. Font 2.0: the sheet with an offer fits and wraps (`_available_font2`).
9. Over the whole run the server never saw a request for `nn-6633c73c5319.nnue` (the wrong-arch net), and every manifest request followed a tap.

**Release (R8) on chess36:** `:app:assembleRelease` (arm64 35,866,297 B, x86_64 40,124,292 B, armeabi-v7a 25,769,486 B, universal 97,109,852 B; no `.nnue`/`.tar` inside). x86_64 installed fresh, Setup "Not now", Settings > Check for updates against the provisional GitHub URL: "There's no update information on the server yet. Try again later." (`_release_check`, NotFound). No FATAL/ClassNotFound/NoSuchMethod. No new keep rule.

**Gate (counts from the XML, final code).** `:core` **490/0/0**, `:engine` unit **32/0/0** (27 + 5), `:app` unit **443/0/0** (366 + 77), all 0 skipped; `lintDebug` **0 errors, 69 warnings** (`:engine` 0/0). `:app:assembleRelease` OK (above). Connected, one device and one suite at a time: `:app` **160/0/0, 0 skipped, on chess36** (748.1 s) and **160/0/0, 0 skipped, on chess34** (854.1 s) (153 + `ActivationRollbackInstrumentedTest` 6 + `UpdateCheckNetworkTest` 1); `:engine` **20/0/0, 0 skipped, on chess36** (303.8 s) and **20/0/0, 0 skipped, on chess34** (521.4 s). For the record, the first chess36 `:app` run was 160 with 1 failure: `VideoExporterInstrumentedTest`'s wall-clock check, the coincidence D2d documented (both exports near real time; the short one 3,842 ms of video in 4,223 ms, 381 ms off against a 384 ms bar). Code D2e did not touch; the check was not weakened; the full suite was re-run on chess36 and passed (the counts above). New tests passed on every run.

**Deviations from the design, with reasons.**
1. The update download runs in-process (`ModelUpdates`, application scope), not as a `ModelDownloadService` job: the setup service's states, notifications and Cancel semantics are setup's, and the task required D2c/D2d's behaviour intact. A killed update only loses the download in progress (the part is resumed by the next tap through Range); a death during swap/trial is the journal's job.
2. The voice is tried BEFORE the swap, in the scratch directory (design §4.3 order); the net after it (§4.2). Both are journaled, so a death in either trial is rolled back at the next start.
3. A bad or missing signature has its own message ("couldn't be verified, so nothing was downloaded"), and 404 its own ("no update information on the server yet"); the design folded both into "isn't answering". Logged as `ManifestRejected(signature)` as designed.
4. The row opens a result sheet at once (the task's "row and a result sheet") instead of updating its own second line first; the row still shows Checking / Installing / Last checked.
5. Installing also needs a finished setup (an update needs something to roll back to); a check does not.
6. `AppFacts` takes the debug loopback rule (the test servers are http); release URLs must be https under `MODEL_BASE_URL`.
7. `NetStore.verifiedNetOrNull()` follows the active identity; the "update" net in the instrumented tests is a description-byte variant of the real net (no other same-architecture net exists).
8. The "simulated crash" in the instrumented test is an Error thrown at the step boundary (the process cannot kill itself inside a test); the real `kill -9` mid-trial was done on the emulator (step 7).

**Found, not fixed / open.**
- After this update, narration WAVs cached by earlier builds (key without the voice id) are orphaned until "Clear" in Settings: they are never hit again, just take space.
- An analysis started during the 1-2 s of a net trial waits for it; one already running blocks the activation ("Finish or cancel the current analysis first."). A Video screen that has the voice loaded while a voice swaps keeps its loaded model (sherpa has read it); its next provider uses the new one.
- A net upgrade through the manifest needs a same-architecture net, which in practice means none until an app update (design §3.3); only the voice path was exercised with a real download end to end.
- `uiautomator dump` stalls while a progress bar animates: the short phases (unpacking, 1.5 s) were captured from the log, "Trying…" by a plain screencap.
- The release run against the real GitHub URL stays blocked until the repo exists (D2f).

**Device state restored.** chess36: airplane mode off, font 1.0, our app and test packages uninstalled (Gradle after the last run), the two test MP4s and `/sdcard` scratch files deleted, nothing of ours left (nothing was installed at the start). chess34: font 1.0, airplane off, `net.palaya.chessanalyzer.engine.test` (present at the start) reinstalled from this build. Both emulators shut down. Host test server stopped. Host-side e2e fixtures (repacked tars, the wrong-arch net, signed test manifests) stay in the session scratchpad, outside the repo; `dist/models/models.json` + `.sig` (the real manifest from the `--dry-run`/`--sign` check, GitHub URLs) are in the gitignored `dist/`.


### D2f (small installer: docs, policy, migration, release) — DONE, verified on chess36 (API 36) and chess34 (API 34)

Baseline `0479daf` plus the uncommitted F1 and D2a-D2e work (kept intact). Nothing committed. Design:
`docs/MODEL_DOWNLOAD_DESIGN.md` §7, §8, §9 row D2f, §10.

**1. Version.** `versionCode` 1 -> **2**, `versionName` "1.0" -> **"1.1"** (`app/build.gradle.kts`, with a comment). The
bundled R7 build is versionCode 1 (aapt2: `versionCode='1' versionName='1.0'`, targetSdk 34). `publish_models.sh
--min-version-code` must be **2** for the first manifest (1.0 has no update check; 2 is the first build that reads one);
the script's header and PUBLISHING say so. The manifest bounds (`ModelCompatibility`: min <= versionCode <= max, inclusive)
need no change; the test servers' manifests keep minVersionCode 1 (they serve debug builds of the same code).

**2. `.tar.gz` for the voice (open question 2): measured, switched.**
- `gzip -9 -n` (GNU gzip 1.14) of the 158,269,440 B tar: **102,543,452 B** (SHA-256 `936044f1…69c6`), 55,725,988 B (35%)
  smaller, and 0.7 MB smaller than the upstream `.tar.bz2`. Two runs gave identical bytes (no name, no time stamp).
- Decompression on chess36, measured with a dex benchmark run by `dalvikvm` (`GZIPInputStream` over a
  `BufferedInputStream`, 256 KB reads, SHA-256 of the output, the way `VoiceStore` reads): **1,349 / 900 / 875 ms**
  (three runs; the plain tar read + SHA-256 on the same device: 399 / 365 / 433 ms). Host: 1,057 ms. In the app itself
  (`VoiceStoreInstrumentedTest`, inflate + extract + SHA-256 + required-file check of the real part): **1,414 ms on
  chess36, 1,647 ms on chess34** (D2e's plain-tar unpack: 1.5 s). Both bars (>= 30 MB saved, < ~20 s) met by far.
- Implementation: `VoiceStore.gunzipIfCompressed` wraps the stream in a streaming `GZIPInputStream` when it starts with
  1f 8b (a plain tar still works: test seeds, synthetic tars, an update's `.tar`). The digest and the size are the
  decompressed TAR's, so the pins `kokoro.tar.*`, the `.provisioned` marker (`7190c480…`), the migration from 1.0 and
  the narration cache key are unchanged. New: `CountingInputStream` throws as soon as the stream passes the expected
  size (a small `.tar.gz` that inflates to 300 MB is stopped after ~64 KB read, host test). `VoiceDownload` (the
  `.tar.gz`'s name/size/SHA-256) feeds `ModelSetup.voiceSpec`; `installFromTar`/`extractToScratch` take the tar's
  expected size. The download stays resumable (the part is the `.tar.gz`; the inflation is local, after verification);
  the part keeps its name `kokoro.tar.part`. Setup figures: download **201 MB** ("about 210 MB"), voice "about 110 MB",
  peak free space about **400 MB** (was 450), 260 MB once done; `SetupSizes.voiceInstalledBytes` +
  `downloadTotalBytes` keep "once done" (unpacked) apart from the progress total (download). `progressMegabytes` no
  longer reads "201 of 201" one byte early (201.05 MB rounds to 201).
- Pins: `MODELS.lock` gains `kokoro.targz.sha256`/`size`; `generateModelPins` writes `VOICE_FILE_NAME`
  (`kokoro-int8-en-v0_19.tar.gz`), `VOICE_DOWNLOAD_SIZE_BYTES`, `VOICE_DOWNLOAD_SHA256`, `VOICE_TAR_NAME`, and keeps
  `VOICE_SIZE_BYTES`/`VOICE_SHA256` as the tar's; when the file is present it checks the `.tar.gz` AND what it inflates
  to (a lock whose two voice pins disagree fails the build). `fetch_models.sh` keeps the tar in `.cache/`, makes and
  pins the `.tar.gz` (refuses a different gzip's output, pointing at the release copy; regeneration from the cached
  tar gave the pinned bytes), moves a pre-D2f `app-assets/tts/*.tar` out. `publish_models.sh` uploads the `.tar.gz`,
  checks that it inflates to the tar pin, and writes `tarSize`/`tarSha256` in the voice entry (dry-run + `--sign` +
  `--verify` OK; `dist/models/models.json` is the real manifest, signed). `model_test_server.py` serves the `.tar.gz`.
- Updates: `ManifestEntry.tarSha256`/`tarSizeBytes` (optional; malformed = whole manifest rejected), `isGzip`,
  `unpackedSha256`/`unpackedSizeBytes`. `ModelCompatibility`: `.tar` or `.tar.gz`; a `.tar.gz` must carry both tar
  fields, a `.tar` may only repeat its own; both sizes within limits; "already installed" compares the TAR hash with the
  marker. `ModelActivator`/`ModelUpdateInstaller` unpack against and record the tar's pins.
- **AAPT trap found:** the test APK held `assets/tts/kokoro-int8-en-v0_19.tar` (158,269,440 B, Stored) although the
  source asset was the `.tar.gz`: AAPT gunzips any asset named `*.gz` and drops the suffix. 12 tests failed with "not
  seeded". Fix: `prepareTestSeedAssets` copies it as `tts/kokoro-int8-en-v0_19.tar.gz.seed` (`noCompress` `.seed`),
  `TestApp.voiceSeedPath` follows. The androidTest APK is now 202,354,007 B (was ~258 MB). CLAUDE.md gotcha added.
- Tests: host +13 (`ModelSetupTest` gz setup e2e and "gz of another tar = DAMAGED", the real-size arithmetic;
  `VoiceStoreTest` +6: gz part installs with the tar marker, gz of another tar, the gzip bomb stopped early, truncated
  gz, update `.tar.gz` needs the tar size, magic detection; `ModelCompatibilityTest` +1; `ModelManifestTest` +1 (the
  exact entry `publish_models.sh` writes); `UpdateCheckerTest` +2 (gz voice offered, installed, then up to date; gz of
  the wrong tar refused, old voice kept); `ManifestPermissionsTest` +1, below). Instrumented: the seed test checks the
  `.tar.gz` pins; `ModelSetupInstrumentedTest`, `SetupFlowInstrumentedTest`, `VoiceStoreInstrumentedTest`,
  `UpdateCheckNetworkTest` use the download pins/the gz; `NetworkPermissionTest` +1.

**3. Found and fixed: a library fetched a font over the network on the app's behalf.** The second chess36 run had
`NoNetworkAfterSetupTest` fail: 0 ProxySelector calls but **TrafficStats uid tx 30,966 B**. `dumpsys netstats detail`
then showed the removed uid's Wi-Fi bucket at exactly tb=30,966 / rb=3,042,052, and the test's logcat showed Play
services' `FontLog: Received query Noto Color Emoji Compat` 1.7 s after `MainActivity` started, then
`Download finished for https://fonts.gstatic.com/s/a/71ec20….ttf`. Cause: androidx.emoji2 (via Compose) registers
`EmojiCompatInitializer` in androidx.startup; on the first Activity it asks the GMS font provider for the emoji font,
and GMS downloads it charged to the calling app when it has no cached copy (earlier runs had it cached, which is why
D2d/D2e passed). Run alone afterwards the test passed (cached). Fix: the main manifest removes that initializer
(`tools:node="remove"` on the `InitializationProvider` merge; `tools:ignore="MissingClass"` because androidx.startup is
only on the runtime classpath, which lint reported as an error before). Merged/installed manifest: only
`ProcessLifecycleInitializer` and `ProfileInstallerInitializer` remain (aapt2 on the release APK). Proof after the fix:
no `Received query Noto Color Emoji` from our process in any test logcat of the next three full runs (the one hit on
chess34 came from Google Messages, pid 12257, "Bugle: Initializing EmojiCompat"), and `NoNetworkAfterSetupTest`
measured 0 calls / tx 0 / rx 0 on both devices. The app needs no downloadable emoji (system font). Not found by any
earlier gate because the ProxySelector cannot see a request made by another process.

**4. Migration proof (design §8), release builds, chess36 (API 36) and chess34 (API 34).** Same key: `apksigner`
certificate SHA-256 `ca4f7b42…0947` for `dist/PalayaChess-1.0-release.apk` and every 1.1 APK. The release build is not
debuggable, so `run-as` cannot read its files; the `google_apis` images allow `adb root`, which was used to list
`/data/data/net.palaya.chessanalyzer/files` and to read the diagnostic log (and `adb unroot` afterwards).
- chess36: `adb install` 1.0 (versionCode 1). The Opera Game shared (`am start -a SEND` with the PGN text): 1.0's
  first-run copy and unpack ran, the analysis reached the Summary (94% / 82%, `d2f_api36_v10_summary.png`). On disk:
  `files/nn-1a298aa575a0.nnue` (98,511,183 B), `tts_models/kokoro/` with `.provisioned` = `7190c480…`, one eval-cache
  file. Then **airplane mode on** (`ping 8.8.8.8`: Network is unreachable) and `adb install -r` the 1.1 universal APK:
  Success, versionCode 2, `firstInstallTime` unchanged (an update, not a new install). Launch: **Home** with the recent
  game, no Setup screen, no setup card (`d2f_api36_after_update_home.png`). On disk: `nets/nn-1a298aa575a0.nnue`, no net
  in the root, the voice and its marker untouched, `eval_cache/1a298aa575a0/<key>.json`. Diagnostic log (pulled): the
  process start (app 1.1 (2, release)), `previous process exit: PACKAGE_UPDATED`, and the only `[models]` lines:
  `migrateLegacy: moved nn-1a298aa575a0.nnue into nets/; voice installed: true` and `eval cache: moved 1 file(s) into
  eval_cache/1a298aa575a0/`; no setup, download or update line. Légal's mate shared and analysed offline (97% / 48%,
  `d2f_api36_after_update_offline_summary.png`); Video showed no "phone's voice" notice (`_video.png`); **Save video
  offline**: notification permission allowed, `dumpsys` `isForeground=true foregroundId=4101 types=0x00002000`,
  "Video saved, 1 min 53 s · 7.8 MB" (`_export_progress.png`, `_export_done.png`); log `export start: 8 segments …
  fgs type 8192` / `export done: 7827458 bytes`; logcat: `NeuralTtsProvider: loaded KOKORO: speakers=11 sampleRate=24000
  sid=1` with the sherpa config pointing at `files/tts_models/kokoro/` (the voice 1.0 had unpacked). **MP4 pulled and
  measured:** 113.08 s, 7,827,458 B, h264 1280x720 + AAC 44.1 kHz mono, `volumedetect` mean **-25.5 dB**, max
  **-6.8 dB**, last 3 s -91.0 dB (the silent recap); frames at 2 s and 60 s viewed (`d2f_api36_mp4_intro.png`,
  `_mp4_middle.png`: 7.Nd5#, "! Great"). Logcat of the app pid: no http/socket/UnknownHost/github/ModelDownload line.
- Reopening the Opera Game in 1.1 re-analyses it (`d2f_api36_after_update_reopened_game.png`; cancelled with Back): 1.0's
  cache key had no F1 search budget, so its cache file is moved but never hit (known since F1, once per game).
- chess34: the same steps without the export: 1.0 set up and analysed the Opera Game (`d2f_api34_v10_summary.png`);
  airplane mode; `install -r` 1.1; Home, no Setup (`d2f_api34_after_update_home.png`); `nets/`, the eval-cache folder and
  the same two `[models]` lines; Légal's mate analysed offline (98% / 49%, `d2f_api34_after_update_offline_summary.png`).
- The migration ran on the first 1.1 build; the final build differs only by the manifest's `tools:ignore` (the binary
  manifests differ only in source line numbers, `aapt2 dump xmltree` diff), so it was not repeated.

**5. Release outputs** (each its own Gradle invocation; final build after the lint fix):

| Output | Size | SHA-256 |
|---|---|---|
| `app-arm64-v8a-release.apk` -> `dist/PalayaChess-1.1-arm64-release.apk` | 35,866,501 B | `566b42a57e984916a1ae98512c5e83bf6def5333466a161b7927fd1e5a29f1f2` |
| `app-universal-release.apk` -> `dist/PalayaChess-1.1-universal-release.apk` | 97,110,046 B | `b4a30bb75b20a0b4f22eafc10f44bc766e1f3f0143cb7b623bc4b4ef5be899e3` |
| `app-release.aab` -> `dist/PalayaChess-1.1-release.aab` | 68,714,862 B | `4f831b27258f8dcf6e735593222ff5fba937e4fe4f00510158ddb849368634f4` |
| `app-armeabi-v7a-release.apk` (not copied) | 25,769,676 B | |
| `app-x86_64-release.apk` (not copied) | 40,124,486 B | |

Play download per device (bundletool 1.18.3 `get-size total`, API 36, en-US, 420 dpi): **arm64-v8a 14,473,967 B,
x86_64 15,911,159 B, armeabi-v7a 13,458,827 B**. Checks: `apksigner verify` on all four APKs: v1 false (by design), **v2
true, v3 true**, 1 signer, `ca4f7b42…0947`; `jarsigner -verify` on the AAB: jar verified, same certificate;
**`zipalign -c -P 16 -v 4` exit 0** on all four; all 18 `.so` (6 x 3 ABIs) have every LOAD at 0x4000 (`llvm-readelf -lW`);
`aapt2 dump permissions`: exactly INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC,
FOREGROUND_SERVICE_MEDIA_PROCESSING, POST_NOTIFICATIONS (+ AndroidX's own signature permission); release manifest:
0 `networkSecurityConfig`/`usesCleartextTraffic`, no `EmojiCompatInitializer`; no `.nnue`/`.tar`/`.gz`/`.seed` in any
APK or the AAB (only the five licence texts, `openings.tsv`, `dexopt/`). The old `dist/` files are kept
(`PalayaChess-1.0-release.apk` `dc2214f3…3573`, `PalayaChess-1.0-f1-arm64.apk`).

**6. Docs and policy.** `docs/PUBLISHING.md` rewritten (§0 current facts; §3 bundle, sizes, Play App Signing, the
foreground-service declaration text for dataSync (setup download) and mediaProcessing/dataSync (export), §3b Device and
Network Abuse position "data, not code", APKs; §4b the gz format, pins table, publish flow with `--min-version-code 2`;
§4c the GitHub repo and release steps; §5 Data safety: nothing collected or shared, GitHub sees the IP, the log and
Auto Backup explained, the EmojiCompat note; §6 release checklist incl. "publish models BEFORE shipping" and 12 testers x
14 days; §7 voice). New `docs/PRIVACY_POLICY.md` (plain English, Chess@palaya.net, not published). `docs/STORE_LISTING.md`
rewritten: title "Palaya Chess: Game Review" (25), short description 79 characters, full description 2,382 characters
(measured with `len()`), "powered by Stockfish" only in the description, the one-time download paragraph.
README, CLAUDE.md (gz pins, AAPT `.gz`, EmojiCompat, version, `adb root`), HANDOFF (owner to-do at the top, D2f, stale
current-state lines fixed, history marked), RUN_PLAN, `MODEL_DOWNLOAD_DESIGN.md` (status note, §0 row, §10 Q2 decided);
superseded notes at the top of `BUNDLED_MODELS_DESIGN.md` and `NEURAL_VOICE.md`. In-app text: `about_license_neural_body`
and `NEURAL_VOICE_LICENSE.txt` said the voice is "included in the app"/"not downloaded"; now "downloaded once, when you
set up the app"; the Stockfish licence body notes the net is downloaded once; a stale "no INTERNET permission" comment in
`AppDiagnostics.kt` fixed. A repo-wide grep for "no INTERNET", "no network permission", "bundled", "365/371 MB",
"257/260/450 MB", "plain tar" left only history (RUN_LOG, older RUN_PLAN rounds, the superseded designs, HANDOFF's
marked history sections, the design doc's mock-ups under its status note).

**Gate (counts read from the XML, final code):** `:core` **490/0/0**; `:engine` unit **32/0/0**; `:app` unit **456/0/0**
(443 + 13), all 0 skipped (`--rerun`); `lintDebug` **0 errors, 69 warnings** (`:engine` 0/0). Connected, one device and
one suite at a time: `:app` **161/0/0, 0 skipped, on chess36** (799.0 s) and **161/0/0, 0 skipped, on chess34** (929.6 s)
(160 + `NetworkPermissionTest.noEmojiFontIsFetchedFromPlayServicesOnTheAppsBehalf`); `:engine` **20/0/0, 0 skipped, on
chess36** (357.3 s) and **on chess34** (578.1 s). Both devices ran the full suites because the voice format and the
setup code changed. For the record, the earlier attempts: chess36 run 1 = 72 of 160 then "System has crashed"
(12 "not seeded" failures from the AAPT trap, then system_server aborted in `GrallocUploadTh`: "Failed to create context,
error = EGL_BAD_CONFIG", an emulator graphics fault); run 2 = 159/1 (the font download above); chess34 run 1 = 21 of 161
then the same system_server EGL abort (relaunched with `-gpu swangle_indirect -no-snapshot-load`, then green).

**Device state restored.** chess36: airplane off, our app uninstalled, the export and an MP4 a crashed test run had left in
`Movies/ChessAnalyzer` deleted, pushed PGNs deleted, `adb unroot`, emulator shut down (nothing of ours was installed at the
start). chess34: airplane off, our app uninstalled, temp files deleted, `adb unroot`, `net.palaya.chessanalyzer.engine.test`
(present at the start, removed by Gradle) reinstalled from this build, emulator shut down.

**Deviations, with reasons.**
1. The voice archive format changed (task allowed it once the measurement passed); the part file keeps its old name.
2. The EmojiCompat fix and the AAPT seed rename are outside D2f's listed scope; both were needed to make the gate honest
   (the first contradicted the Data safety/privacy text, the second broke the seed).
3. The migration evidence was read with `adb root` instead of a shared diagnostic log; the log read is the same file the
   app's Share action sends. chess34 migration was done without the export (done on chess36).
4. The release APKs and AAB were rebuilt after the migration run to pick up the lint suppression; the binary manifests
   differ only in line numbers.
5. Release end to end against the real GitHub URL: **blocked** until the repo and `models-2026.10` exist (owner).

**Found, not fixed / open.**
- Emulator graphics: system_server's `GrallocUploadTh` EGL_BAD_CONFIG abort hit two of six suite runs (swiftshader_indirect).
- 1.0's eval caches are moved but never hit (no budget in their key): each game from 1.0 is re-analysed once.
- `ModelSetup` milestone logging on resume, failure-detail wording, Cancel after a 5xx pause: unchanged from D2c.
- While extracting native libraries for the alignment check, a stray `C:\c\…` directory was created by a path mistake
  (`/c/...` handed to Windows Python) and removed with `rm -rf /c/c`; had a `C:\c` directory existed before, it would have
  been deleted with it.

---

## V1 + V3 (2026-10-07): narrator voice picker, video pace

Owner requests of 2026-10-06: "Add other English voices to choose from" (V1) and "current analysis is a bit too
fast for key moves/sequences" (V3). Nothing committed. desktop/ and pc/ untouched (pc/'s Kokoro README read only:
it names no speakers, it links hexgrad/Kokoro-82M; the names are sherpa-onnx's for this archive, as in Round 6).

### V1: the voice picker

**The speakers.** `voices.bin` is 5,755,904 B = **11 x 523,264 B** (511 x 256 float32 per speaker). The brief's
"11 x 522,240 B" is 11,264 B short (522,240 = 510 rows); the count of 11 is what the device reports
(`numSpeakers()`, asserted since Round 6). Ids and labels (`KokoroVoices.SPEAKERS`, picker order by group, then
upstream's grade):

| sid | upstream | Picker label | measured wpm (`measurements.txt`) |
|---|---|---|---|
| 1 | af_bella | Bella · American, female (**default**, unchanged) | 169 |
| 2 | af_nicole | Nicole · American, female | 127 |
| 3 | af_sarah | Sarah · American, female | 164 |
| 4 | af_sky | Sky · American, female | 177 |
| 0 | af | Blend · American, female | 166 |
| 6 | am_michael | Michael · American, male | 153 |
| 5 | am_adam | Adam · American, male | 173 |
| 7 | bf_emma | Emma · British, female | 170 |
| 8 | bf_isabella | Isabella · British, female | 167 |
| 9 | bm_george | George · British, male | 149 |
| 10 | bm_lewis | Lewis · British, male | 158 |

**Settings.** A new **Video** card between Language and Advanced: "Narrator voice" (second line: the chosen voice,
"... · after setup" before the voice is installed, "The phone's built-in voice" while the Advanced switch is on)
opens a picker: one radio row per voice with "Play sample" (Preparing… / Stop), the default marked, Done. Tapping a
row chooses at once and the list stays open so several can be compared. Not installed: the picker says the voices
come with setup and Play is disabled; the device-voice switch in Advanced is unchanged and still decides the
provider. The choice is `kokoro_speaker_id` in the existing narration DataStore (unknown ids read as the default).
**Play sample** (`VoiceSamplePlayer`): loads Kokoro once on the first tap, synthesizes one fixed sentence ("Knight to
f seven, check! The king must move, and the queen on d eight is lost.") with `NeuralTtsProvider.synthesizeAs(sid)`,
caches it in `cacheDir/voice_samples/` under a name made of the voice files' id, sid, length scale and text, and
stops the previous sample when another starts; leaving Settings or closing the picker releases the engine.
Reference: chess.com's coach picker (a list of coaches, each with a name and a descriptor, a way to hear it before
choosing, the current one marked). Taken: that list shape and preview-then-pick; our own words, no art.

**Plumbing.** `NarrationVoiceSettings.speakerId` -> `NarrationProviderSelection.Neural(tier, speakerId)` ->
`NeuralTtsProvider(speakerId = ...)`, built by `AnalysisViewModel.buildNarrationProvider()` for both the in-app player
and `VideoExportService`. The narration cache fingerprint already carried `sid` (Round 6) and the voice id (D2e):
`KOKORO@<voice id>/sid<n>/ls1.20`; checked per speaker (11 distinct fingerprints and store keys, host) and on the
device (George then Bella on the same text and store: 0 segment and 0 sentence cache hits across speakers, different
bytes, both audible; George again: a cache hit). The script is estimated at the chosen speaker's measured rate
(`narrationOptionsFor`, which now reads the repositories, not the eager StateFlows). The update trial
(`NeuralVoiceTrial`) is unchanged and uses the default speaker.

**Listen (host-measured, chess36, `docs/voice_samples/v1/`, table in `docs/voice_samples/README.md`).** All eleven
speakers, the sample sentence, pulled and measured with numpy: 5.03-6.50 s, RMS 1460-2866 (-27.0 to -21.2 dBFS),
peaks -11.9 to -4.0 dBFS, **0 clipped samples**, 10-19 pause windows of ~110 (speech-shaped, none silent). They are
different voices by measurement: median pitch male 102-144 Hz (Lewis 102, Michael 124, Adam 126, George 144), female
154-203 Hz (Nicole 154, Sky 170, Emma 189, Blend 195, Sarah 197, Bella 198, Isabella 203); spectral centroid 1.6 kHz
(Sky) to 3.0 kHz (Emma). Listening is the owner's.

### V3: the pace

**Measured first** (`PaceMeasurementDumpTest`, `core/build/pace/before_*.txt`; the Immortal Game and game01 recorded
at depth 12 MultiPV 3, `game01.analysis.json` new; speech estimated at 169 wpm with the app's 900 ms floor and 250 ms
gaps). Narration and board were in step, and the detour lines were not fast (each move has its own sentence: 5.7 to
9.5 s per move, final position 10.3-13.1 s). What was rushed was the **key moves**:

| | Immortal Game (47 beats, 433.8 s) | game01 (70 beats, 562.1 s) |
|---|---|---|
| key moves with 0 ms on the position before (slide starts with the voice) | 3 of 7: 21.Nxg7+, 22.Qf6+, 23.Be7# | 10 of 15 |
| board jumps (a beat starting on a position the board never showed) | 18 | 27 |
| skipped moves inside the final sequence | 21...Kd8, 22...Nxf6: 0 ms each | 30.Qxd7, 31.Kc1, 32.Kd1, 33.Ke1: 0 ms each |

The other key moves had a still before them only because a "back to the game" or puzzle beat happened to show it.

**After** (Normal; Relaxed/Brisk in `after_*`): every key move has its pause (Immortal 7/7, game01 15/15 with at least
1.0 s; at Brisk 0.7 s by design), the skipped replies are played at the sequence rate (Immortal: 20...Na6 replayed
after the turning-point still, 21...Kd8, 22...Nxf6 at 1.5 s each; game01: 11 approach moves before 10 key moves, among them 30.Qxd7, 31.Kc1 and 33.Ke1 of the mating run; 32.Kd1 still jumps, because 32...Qc2+ is a BRIEF beat, not a key move), the result is held
+1.0 s, and the lines' final positions +1.0 s (11.3-14.1 s). Jumps into key moves: 0 (Immortal 18 -> 15, game01 27 -> 17, all
remaining ones into non-key beats: inaccuracy stills, detour pivots and "skip ahead" holds, which the narration announces).

**Design** (ANALYSIS_SPEC §9.8): `VideoPace` RELAXED / NORMAL / BRISK (pause 1.5 / 1.2 / 0.7 s, hold 1.5 / 1.0 / 0.5 s,
sequence move 2.0 / 1.5 / 1.1 s, line final +1.5 / 1.0 / 0.5 s); `ScriptSegment.leadIn` (`SegmentLeadIn`: approach of at
most 2 plies, then the pause with the move's two squares lit, the eval of the position before, chip "Key moment", no
verdict); applied by `ScriptBuilder.paced` AFTER the budget loop, so the story, words, segment count and cached audio
are identical at every pace. `TimelineBuilder` puts the lead-in before the speech (`TimedSegment.speechStartMs`), the
exporter writes it as silence, `SegmentFrameBuilder` draws it and starts the segment's board after it, the player
starts the voice at `speechStartMs`. **Default Relaxed**: the owner's complaint is exactly these moments; Normal is
the reference the owner's numbers (1.0-1.5 s pause, ~1.5 s per move) are stated at. Settings, Video, Pace
(segmented, a radio list at large font), persisted as `video_pace` in the settings DataStore, passed through
`narrationOptionsFor` into the script, so the player and the MP4 both get it.

**Budget decision.** The §9.7 budget is not scaled with the pace: it holds the story (`VideoScript.storyMs`), and the
pace time (`pacingMs`) sits on top like the recap, capped at 15 percent of the budget (`pacingCapMs`, scaled down if
passed; never binds on the recorded games). Measured pace time, Relaxed / Normal / Brisk: scholar's 5.0 / 3.7 / 2.3 s
(story 52.5 s), chesscom 29.0 / 21.2 / 12.6 s (306.9 s), Immortal 25.5 / 18.1 / 10.4 s (422.0 s), game01 64.0 / 47.1 /
28.7 s (544.6 s), Byrne-Fischer 51.5 / 37.5 / 22.2 s (537.3 s): 2.5-11.8 percent. Reasons in §9.8 (the same story at
every pace, no re-synthesis on a pace change, small and bounded). Protected beats are untouched (the pass runs after
every tier and budget decision; `PaceTimingTest` checks the brilliant/great/mate beats' words at every pace). The
recap card and the "about N min left" estimate are unchanged (no new segment, no new speech); seen on chess36: "About
4 min left" then "About 3 min left" during the Relaxed export's narration step.

Reference: chess.com Game Review stepping through a key moment (the position, then the move with its classification,
then the explanation over the held result; a best line stepped one move at a time). Taken: pause before, verdict with
the move, hold after, steady sequence rate, no skipped replies.

### On the emulator (chess36, debug build, models installed from the test APK's seeds)

- **Settings** (`docs/screenshots/v3_settings_pace_relaxed.png`, `v3_settings_pace_brisk.png`, `v1_voice_picker.png`,
  `v1_voice_picker_preparing.png`, `v1_voice_picker_playing.png`, `v1_settings_voice_george.png`; all viewed): the
  Video card, the picker with all eleven and the default marked, "Preparing…" on the first tap (Kokoro loads), "Stop"
  while a cached sample plays, the row reading "George · British, male" after choosing him. Logcat: `loaded KOKORO:
  speakers=11 sampleRate=24000 sid=9`.
- **The Immortal Game, shared as text, analysed on the device (Standard), voice George, exported from the Video screen
  twice:** Relaxed **454.34 s** (audio 454.343 s, video 454.267 s, 37,645,637 B, "7 min 34 s · 38 MB"), then Brisk
  **435.64 s** (36,040,032 B): **18.70 s shorter**, nothing else different. The Brisk export's narration step was
  41/41 at once ("Less than a minute left"): every clip came from the cache the Relaxed export filled, i.e. the same
  words. The first 60 s of the two audio tracks are bit-identical (no key move there); their median pitch is 143 Hz
  (George's sample: 144 Hz), so the chosen speaker reached the MP4. The device's script has 41 segments (the on-device
  analysis is depth 14, the host recording depth 12).
- **Frames at 4 fps across 22.Qf6+** (`docs/screenshots/v3_relaxed_qf6_4fps.png`, `v3_brisk_qf6_4fps.png`,
  `v3_relaxed_qf6_pause_frame.png`; viewed). Relaxed, from 324 s: 21.Nxg7+ held (its result and the hold, 4.75 s
  visible), then 21...Kd8 slides in and rests (8 frames, 2.0 s, caption "21... Kd8"), then the pause: f3 and f6 lit,
  chip "Key Moment", eval M2, caption "22. Qf6+" (7 frames, about 1.5 s), then the queen slides and the beat is held.
  Brisk, from 312 s: 21...Kd8 for about 1.1 s (5 frames), the pause about 0.75 s (3 frames), then the slide. The 2 s
  contact sheets from 280 s show the whole run: 20...Na6 (replayed after the turning-point still), Nxg7+, Kd8, pause,
  Qf6+, Nxf6, pause, Be7#, the final-numbers card.
- Not fixed, seen in the frames: the panel's chapter line kept "MOVE 18: BXG1" through moves 19-23 (the chapter
  marker of the last FULL moment; unchanged by V3). Fixed after the export (in the gated build): during a lead-in the
  panel's "Recent moves" did not list the approach moves; it does now (`recentPlayedSans` counts them).

### Tests

- **Host, new:** `PaceTimingTest` (12: pace values ordered and Normal at the owner's numbers, scaling floor, persisted
  names, the same story at every pace on five recorded games, the Immortal mating run's approach and pauses, every key
  move at least 1 s on its position at Normal/Relaxed, approach starts where the board was, line moves at the line rate
  and final holds, brilliant/great/mate beats unchanged at every pace, the budget holds the story and the pace time is
  under its cap, EVERY_MOVE paced), `PaceMeasurementDumpTest` (the measurement, writes `core/build/pace/`),
  `NarratorVoiceTest` (9: eleven speakers and names, accent/gender prefixes, picker order and default, measured rates
  equal `measurements.txt`, unknown ids, 11 distinct fingerprints and cache keys, the selection carries the sid,
  `narrationOptionsFor` rate and pace, the timeline's lead-in). Updated for `storyMs` / lead-ins: `PacingTiersTest`,
  `PacingTinyGameTest`, `PacingLongGameTest`, `GameRecapTest`, `VideoScriptGeneratorTest` (no assertion weakened: the
  budget assertions now hold the story, which is exactly what they held before V3).
- **Instrumented, new:** `NarratorAndPaceInstrumentedTest` (3: the speaker set in the real DataStore reaches the
  provider the app's view model builds, George's audio is made, differs from Bella's and never shares a cache entry,
  George again is a cache hit; `VoiceSamplePlayer` synthesizes, caches and plays one sample at a time; the pace set in
  the real settings reaches `VideoExporter`: the same key moment exported at Relaxed and Brisk differs by exactly the
  script's pace time, container within 200 ms of the timeline), `PanelChipLabelTest.theLeadInShowsNoVerdictThenTheMoveCarriesIt`.
  `NarratorVoiceEvidence` is a `@ManualEvidenceTool` (not in the suite).

### Counts (from the result XML, final code)

`:core` **503/0/0** (490 + 12 `PaceTimingTest` + 1 `PaceMeasurementDumpTest`), `:engine` unit **32/0/0**, `:app` unit
**465/0/0** (456 + 9 `NarratorVoiceTest`), all 0 skipped; lint **0 errors / 69 warnings**; `assembleRelease` OK (arm64
35,894,256 B, universal 97,137,802 B); `:app` connected on chess36 **165/0/0, 0 skipped** (161 + 3 + 1; 1054.7 s).
chess34 not run: no FGS or setup code was touched. `:engine` connected not re-run (no engine change). `:desktop:test`
25/0/0 after the core change (desktop/ untouched; its own timeline ignores lead-ins, see below).

### Files

- core: `narration/VideoPace.kt` (new: `VideoPace`, `PaceTimes`, `ScriptTiming`), `NarrationContract.kt`
  (`ScriptSegment.leadIn`/`leadInMs`, `SegmentLeadIn`, `VideoScript.pacingMs`/`storyMs`, `NarrationOptions.pace`),
  `VideoScriptGenerator.kt` (`paced` after the budget loop, `pacingCapMs`); tests `PaceTimingTest.kt`,
  `PaceMeasurementDumpTest.kt` (new), `RealGameFixture.kt` (game01), the five updated pacing/recap/generator tests;
  `core/src/test/resources/pacing/game01.analysis.json` (new recording).
- app: `video/KokoroVoices.kt` (speaker table), `video/VoiceSamplePlayer.kt` (new), `video/NeuralTtsProvider.kt`
  (`synthesizeAs`), `video/NarrationProviderSelection.kt` (speaker in the selection, `narrationOptionsFor`),
  `video/ScriptTimeline.kt`, `video/SegmentFrameBuilder.kt`, `video/VideoExporter.kt`, `ui/video/VideoPlayerController.kt`
  (lead-in), `ui/model/NarrationVoiceSettings.kt`, `ui/model/GameModels.kt` (`videoPace`), `data/SettingsRepository.kt`,
  `data/NarrationSettingsRepository.kt`, `ui/viewmodel/AnalysisViewModel.kt`, `ui/screens/SettingsScreen.kt` (Video
  card, picker), `ui/navigation/ChessAnalyzerNavHost.kt`, `res/values/strings.xml`; tests `NarratorVoiceTest.kt`,
  `NarratorAndPaceInstrumentedTest.kt`, `NarratorVoiceEvidence.kt` (new), `PanelChipLabelTest.kt`,
  `NoNetworkAfterSetupTest.kt` (subset total includes lead-ins).
- docs: `ANALYSIS_SPEC.md` (§9.7 budget holds `storyMs`, new §9.8), `voice_samples/README.md` + `voice_samples/v1/`
  (11 WAVs, 2.9 MB), `screenshots/v1_*.png` (4), `screenshots/v3_*.png` (5), CLAUDE.md, RUN_PLAN.md, HANDOFF.md, this log.

### Deviations

1. The brief's `voices.bin` arithmetic (11 x 522,240 B) is off by 11,264 B; the file is 11 x 523,264 B. Documented.
2. All eleven speakers are offered, including the blend `af` (labelled "Blend"), not only the ten named ones.
3. The sequence "time per move" target (1.5 s at Normal) applies to the game moves the board used to jump over (now
   played in the lead-in) and as a floor for detour line moves; the detour moves were already 5.7-9.5 s each because
   each has its own sentence, so they did not get slower.
4. V3 also changes the speech-rate estimate per speaker (V1): the script (and so the budget) is estimated at the chosen
   speaker's measured rate, which can change which beats a slow speaker (Nicole, 127 wpm) gets inside the budget.
5. The e2e exports used the on-device analysis (depth 14, 41 segments), not the host recording; the before/after
   timing tables are from the host recordings, the MP4 durations and frames from the device.
6. `desktop/` keeps its own timeline and ignores `leadIn` (read-only for this task): a PC-rendered video gets the
   pace's holds but not the lead-in pause or approach moves.
7. Notification permission was declined on the emulator's prompt during the exports (export ran without a visible
   notification, as in D2c's "notifications denied" check).

**Device state restored.** chess36 was off at the start with nothing of ours installed: both exported MP4s, the share
script and the UI dump were deleted, Gradle uninstalled the app and the test APK, airplane mode off, emulator shut down.
The two MP4s are kept on the host only (scratchpad), not in the repo.

## V2 (2026-10-07): best-line simulation

Owner request of 2026-10-06: "The alternate best tactics is not simulated and only appear with color arrow. Add
simulation for the best moves / sequence." Nothing committed. desktop/ and pc/ untouched (desktop/ only compiled
and tested: `:desktop:test` 25/0/0 after the narration-contract change). Download/setup/update code untouched.

### What was there (verified in the tree first)

- **Board** (`ReviewScreen`): the engine's best move was one green arrow on the position after the played move,
  plus "Better was X" on the card. **Summary** key moments: a sentence, and "Show me what I missed" only where a
  walkthrough exists. **Video**: error beats, inaccuracy beats and the turning point drew the best move as an
  arrow over the position before the move (`annotateDirective`); only a move with a detected missed motif got a
  narrated detour.
- **Walkthrough** (`TacticSimulationScreen`) plays `MoveAnnotation.simulation`, which `GameAnalyzer` builds only
  when the played move lost at least 5 win-% AND the detector found a motif for the best move (`tacticsMissed`),
  cut at 8 plies or the realised payoff. So a mistake whose better line wins nothing a detector names, every move
  without a motif, and any good key moment had no sequence anywhere.
- `MoveAnnotation.candidateLines` held each MultiPV line's **first move only** (uci, san, score); the full PVs were
  in the eval cache but not on the annotation. Only `bestLineSan` (the whole best PV, no depth) was kept.

### The line rules (ANALYSIS_SPEC §6.2, `core.analysis.BestLines` / `BestLine` / `BestLineCaption`)

- `CandidateLine` gains `pvUci` and `depth` (defaulted trailing fields; `GameAnalyzer` fills them, depth = the
  smaller of the line's and the position's, one depth by §8.2).
- **N = min(PV length, depth / 2, 8), stop at mate.** Measured on the five recorded games: the best line before each
  of the 41 moves that lost >= 5 win-% was 8-29 plies at depth 12-20, longer than the depth in 38 of 41 (the tail
  is extensions / quiescence). depth / 2 keeps every shown move backed by at least half the search: 6 at Quick, 7 at
  Standard, 8 at Deep; 8 = the walkthrough's cap. "Until the tactic resolves" was measured and rejected: the last
  material change in those PVs fell at ply 0-25, median 12, beyond what the depth supports.
- **Which lines:** line 1 always; lines 2/3 within 2.0 win-% (`PracticeSelector.ACCEPT_LOSS`, §2/§11's
  best-or-near-best bound), never the move played. On the five games: 246 alternatives among 678 lines shown for
  the three audited games x three sides.
- **Which moves:** INACCURACY, MISTAKE, MISS, BLUNDER, plus any Summary key moment with a line.
- **Caption:** the engine's score as the engine's ("The engine rates this line +2.3." / "The engine sees a forced
  mate in 3 for White."), then only what the shown plies prove ("The line ends in checkmate." / "In this line White
  wins a piece." from the settled gain, 40 cp naming rule, "material" between values); you / your opponent per side.

### Board, Summary, Walkthrough

- `ui/components/LinePlayer.kt` (new): `LinePlaybackState` (step, play), `LinePlaybackEffect` (Play at a step
  rate), `LineBoard` (position, last move lit, next move as an arrow, check), `LineStepper` (Back / caption with
  who is to move and "2 / 7" in a polite live region / Next / Play-pause, pinned LTR, 56 dp buttons). Pure logic in
  `ui/model/LinePlaybackLogic.kt`. **The Walkthrough now uses it** (its own stepper row and replay code are gone; it
  gains Play and the last-move highlight); the Board's line mode is the second user.
- `ReviewScreen`: "Show the best line" on the comment card (`CommentCard.onShowBestLine`) enters the mode in place:
  `LineBoard` replaces the board, the eval bar shows the line's score, chips for the alternatives (one TalkBack stop
  each: "Best: e4, rated +0.4, selected", role Tab), the stepper, and `BestLineCard` (title "Best line instead of
  11… cxb5" / "Instead of …: line 2" / "The engine's line from 8. h3" when the played move was the engine's,
  notation with the current move in green and no-break spaces, the caption, "Engine depth 14", "Back to the
  game"). System back leaves the mode. Play steps at the user's video pace (`settings.videoPace.lineMoveMinMs`).
- Summary: a key moment without a walkthrough offers "Show the best line" (`GameReport.plysWithBestLine`), which
  opens `review/{id}?ply=N&line=true` straight in line mode.
- Fixed on the way: `ReviewScreen` clamped `initialPly` to `moves.size - 1`, so the LAST move of a game could never
  be opened from a key moment (or its line); it now clamps to the last ply.

### Video (ANALYSIS_SPEC §9.8)

- `ScriptSegment.bestLine: SegmentBestLine?` (fen, uci, san, captions "Best line — 18... Nf5 19. Qd2", stepMs,
  finalHoldMs). **Decision: the line extends the segment by pace time, inside the §9.8 cap**: it is the first part
  of the segment's `holdAfterMs`, so `TimelineBuilder`, the exporter's PCM track (silence) and the player need
  nothing new; `SegmentFrameBuilder.build(..., speechMs)` draws it from the end of the timeline's speech (both the
  exporter and `VideoPlayerController` pass `TimedSegment.speechDurationMs`). Story, words, cached narration, the
  recap card, the budget and "N of M" / "about N min left" are unchanged (no new segment, no new speech).
- Where: a beat whose narration names the better move over a still board (`betterMoveBeats`: error beat at
  DWELL/FULL, brief beat on a report key moment), only on MISTAKE/MISS/BLUNDER (an inaccuracy is "never a walk"
  by §9.7 and keeps its arrow). `bestLinePlan` shares the room under the cap that the V3 pace time leaves AT
  RELAXED, most important first (tier, loss, ply), up to 4 plies each (`BestLines.VIDEO_MAX_PLIES`), fewer when
  four do not fit, so every pace plays the same moves and V3's pauses are never scaled down for a line.
- Drawn as an excursion: tinted border, chip "Engine's best line" (`PanelLabels.bestLine`, `panel_best_line`), no
  verdict chip, eval bar = the beat's (position-before) eval, each move slides 400 ms and rests with its caption.
- First attempt (all better-move beats, 4 plies, scaled with the rest) failed the V3 tests: game01 and the
  scholar's mate hit the cap, which scaled every lead-in (scholar's 4.Qxf7# pause 931 ms < 1 s), and the 17-move
  Opera Game passed 6 minutes. Replaced by the plan above; all V3 pacing tests pass unchanged.
- **Timing, one key moment (host recording, 169 wpm, Normal):** the Opera Game's 9...b5 (BLUNDER beat) was on screen
  9.73 s (speech + gap, the best move an arrow); now 16.73 s: the speech ends at 9.48 s, then Kd8 O-O-O+ Kc7 Bxf7 at
  1.5 s each and 1.0 s on the final position. Relaxed 9.73 -> 19.23 s, Brisk 9.73 -> 14.63 s. Per game (pace time
  added, Relaxed / Normal / Brisk): Opera 1 moment, +9.5 / 7.0 / 4.9 s; Immortal 2 (11...cxb5, 16...Bc5), +19.0 /
  14.0 / 9.8 s; game01 3 (24.Bh3, 7...e4 four plies, 13...Bc6 one), +22.5 / 16.5 / 11.4 s; scholar's 1 (3...Nf6, one
  ply), +3.5 / 2.5 / 1.6 s; Byrne-Fischer 0. All under the cap (game01 Relaxed 86.5 of 87.3 s).

### Audit (`scripts/audit_commentary.py lines`, python-chess)

New mode over `core/build/commentary_audit/best_lines.jsonl` (`CommentaryAuditDumpTest.dumpBestLines`: the Immortal
Game, the Opera Game and game01, no side / White / Black, every move): each line replayed (legal, SAN = python-chess's,
moves = SAN count), a prefix of the recorded PV, length = the rule (or shorter only at mate), depth = the recording's,
alternatives within 2 win-% and not the played move, every caption sentence re-derived independently (score
formatting with Java rounding, the mate side, checkmate from the board, the settled gain with python-chess's own
exchange evaluation, the 40 cp names, the subject words), and every video line (1-4 plies of the Board's best line,
legal, numbered captions, only on MISTAKE/MISS/BLUNDER). **Result: 432 move records, 678 lines (246 alternatives),
18 video lines; 2478 checks, 2478 supported, 0 WRONG.** Discrimination proved: a tampered copy (a wrong SAN, an
unproved "wins a rook", an extra PV move) is flagged on all three. The existing `after` audit is unchanged: 78 texts
0 WRONG, 15 walkthroughs 0 WRONG, 0 side-variant differences.

### Reference (chess.com, pattern only)

Game Review's "Show" plays the engine line from the mistake and "Best" reveals the better move (alignment doc
S1/S3/S7/S14); the analysis board lists the top engine lines with their scores and steps through any of them.
Taken: play the line from the position before the move on the same board, step / play, the top lines with their
evaluation as choices, notation with move numbers and the current move marked, a way back to the game. Our own
words, layout and art; nothing copied.

### On the emulator (chess36, debug build)

- Models: the first-run download from `scripts/model_test_server.py` failed its checksum twice in a row ("The
  download didn't match the expected file, twice in a row"; each full GET ended at byte 98,511,174 of 98,511,183, the
  9-byte resume then failed the SHA-256), on a retry too. The file on the host matches the pin. Not investigated
  (download code is out of scope; D2c-D2f passed the same flow on this host); the models were pushed by hand as
  CLAUDE.md describes (net into `files/nets/`, the tar unpacked into `files/tts_models/kokoro/` + `.provisioned`).
  The server was stopped. **Worth a look in a later task.**
- chess36 had to be restarted with `-gpu swangle_indirect`: with the default host GPU every `screencap` was black
  (CLAUDE.md gotcha added).
- The Immortal Game and game01 shared as text and analysed on the device (Standard, depth 14).
- **Screenshots (all viewed):** `docs/screenshots/v2_board_show_best_line.png` (11...cxb5's card: "Show me what I
  missed" and "Show the best line"), `v2_line_start.png` (Start of the line, Black to move, 0 / 7, the h7-h5 arrow,
  eval bar -0.5 = the line's score), `v2_line_middle.png` (12... Qg6, 3 / 7, g5-g6 lit, next move Ba4 as the arrow,
  Qg6 green in the notation), `v2_line_end.png` (14... d5, 7 / 7, Next disabled, Play back after the run),
  `v2_line_landscape.png` (board left, stepper and card right), `v2_line_font2.png` (font 2.0: nothing clipped, the
  notation wraps), `v2_line_rtl_he.png` (app locale he: the bar mirrors, the board, stepper and notation stay LTR),
  `v2_summary_show_best_line.png` (game01: 8.h3, a Brilliant key moment with no walkthrough, offers the line).
- **Video export** (the Immortal Game, Relaxed, Bella): "8 min 1 s · 40 MB"; pulled: format 481.221 s, audio
  481.221 s, video 481.133 s; mean -25.8 dB, max -5.9 dB; "About 10 min left" ... "Less than a minute left" during the
  51-segment narration step. Silences >= 3 s (ffmpeg silencedetect -50 dB): 57.9-67.9 s and 338.2-351.7 s are the two
  best lines (10.g4 and 20...Na6 on the device's depth-14 analysis), the rest the V3 holds.
- **Frames at 4 fps across 10.g4** (`docs/screenshots/v2_video_best_line_4fps.png`, 55-69 s, viewed): the error beat
  with its two arrows and "? Mistake" for 3 s while the voice speaks; then, in silence, the purple border and the chip
  "Engine's best line", no verdict: 10. Ba4 (8 frames = 2.0 s, caption "Best line — 10. Ba4"), 10... Na6 (8), 11. g3
  (8), 11... g6 (8), the final position held 6 more frames (1.5 s); then the next beat (an inaccuracy) starts.
- Device state restored: the MP4 and every pushed file deleted from the device, app locale back to the system,
  font 1.0, auto-rotate on; the app and test APK were uninstalled by Gradle at the end of the connected run.

### Tests

- **Host, new:** `BestLineTest` (9: PV to SAN with each step legal by the core move generator, Black-first
  numbering, illegal/garbage lines, the truncation rule at 12/14/16/18/30/0, no-PV lines, mate stop, settled gain,
  alternatives margin and played-move exclusion, every line of every move of the five recorded games = the legal PV
  prefix cut by the rule), `BestLineVideoTest` (5: lines only on better-move beats of error moves, the Board's moves;
  the line fits the hold at Relaxed/Normal/Brisk at the pace's own rate; pace time not story, same moves at every
  pace; game01/Opera have lines and a side changes no move; the measurement dump), 4 cases in `CommentaryClaimsTest`
  (engine score White-relative; mate side and checkmate; settled material and the 40 cp names; every caption of the
  Immortal, Opera and game01 for three sides re-derived), `CommentaryAuditDumpTest.dumpBestLines`,
  `LinePlaybackLogicTest` (6 in :app: positions, stepping/Play, numbering and side to move, which moves offer the
  line, notation, the timeline at three paces with real vs estimated speech and a lead-in). Updated:
  `GameAnalyzerTest` (candidate lines now carry PV and depth), `NarrationStringsTest` (the new caption is read, not
  spoken).
- **Instrumented, new:** `BestLineModeInstrumentedTest` (4: enter, step, back, Play to the end, the alternative chip
  through its semantics action, Back to the game, system back; TalkBack: buttons with words, the live-region caption,
  the heading, the chip sentence with role and state; a quiet move offers nothing; opened from the Summary in line
  mode; a Summary key moment without a walkthrough offers the line), `BestLineVideoInstrumentedTest` (export with a
  tone voice: container = timeline incl. the line, each MP4 frame during the line matches the renderer's frame for
  that instant (max cell diff 1.1 vs 96-149 to the neighbouring step), audio RMS 8032 while speaking and 0.0 during
  the line, and `VideoPlayerController` on the same cached clips lays out the same total and draws the same frame),
  `PanelChipLabelTest.theBestLineAfterTheSpeechIsAnExcursionWithNoVerdict`. Updated:
  `NarratorAndPaceInstrumentedTest` (the pace-time sum counts best lines).

### Counts (from the result XML, final code)

`:core` **522/0/0** (503 + 9 `BestLineTest` + 5 `BestLineVideoTest` + 4 `CommentaryClaimsTest` + 1 `dumpBestLines`),
`:engine` unit **32/0/0**, `:app` unit **471/0/0** (465 + 6 `LinePlaybackLogicTest`), all 0 skipped; lint **0 errors /
69 warnings** (a "Line %1$d instead of" wording that lint read as a plural was reworded); `assembleRelease` OK;
`:app` connected **171/0/0, 0 skipped** on chess36 (1093.9 s) and on chess34 (1081.7 s) (165 + 4 + 1 + 1); `:desktop:test`
25/0/0 (narration contract changed). `:engine` connected not re-run (no engine change). Audit: 2478 / 0 WRONG.

### Files

- core: `analysis/BestLine.kt` (new: `BestLine`, `BestLineStep`, `BestLines`, `BestLineCaption`), `analysis/Contract.kt`
  (`CandidateLine.pvUci`, `.depth`), `analysis/GameAnalyzer.kt`, `narration/NarrationContract.kt` (`ScriptSegment.bestLine`,
  `SegmentBestLine`), `narration/VideoScriptGenerator.kt` (`betterMoveBeats`, `bestLinePlan`, `bestLineTail`),
  `narration/NarrationStrings.kt` + `EnglishNarration.kt` + `NarrationCatalogue.kt` (`CaptionBestLine`); tests
  `BestLineTest.kt`, `BestLineVideoTest.kt` (new), `CommentaryClaimsTest.kt`, `CommentaryAuditDumpTest.kt`,
  `GameAnalyzerTest.kt`, `NarrationStringsTest.kt`.
- app: `ui/components/LinePlayer.kt`, `ui/model/LinePlaybackLogic.kt` (new), `ui/screens/ReviewScreen.kt` (line mode,
  last-ply clamp), `ui/screens/TacticSimulationScreen.kt` (on the shared player), `ui/components/CommentCard.kt`,
  `ui/screens/GameReportScreen.kt`, `ui/model/GameModels.kt` (`plysWithBestLine`), `data/mapper/DomainMapper.kt`,
  `ui/navigation/Destinations.kt` + `ChessAnalyzerNavHost.kt` (`line=` arg, pace rate), `video/SegmentFrameBuilder.kt`,
  `video/BoardFrameRenderer.kt` (`PanelLabels.bestLine`), `video/VideoExporter.kt`, `ui/video/VideoPlayerController.kt`,
  `res/values/strings.xml`; tests `LinePlaybackLogicTest.kt`, `BestLineModeInstrumentedTest.kt`,
  `BestLineVideoInstrumentedTest.kt` (new), `PanelChipLabelTest.kt`, `NarratorAndPaceInstrumentedTest.kt`.
- scripts: `audit_commentary.py` (`lines` mode, game01). docs: `ANALYSIS_SPEC.md` (§6.2 new, §9.8 "The best line in
  the video"), `COMMENTARY_AUDIT.md`, `screenshots/v2_*.png` (9), CLAUDE.md (swangle gotcha), RUN_PLAN.md, HANDOFF.md,
  this log.

### Deviations

1. **The video plays lines only on MISTAKE / MISS / BLUNDER key moments, and only as many as fit the pace cap.**
   Inaccuracies keep their arrow (§9.7 says an inaccuracy is never a walk of the missed line), and a moment the cap
   has no room for keeps its arrow too (game01's 13...Bc6 gets one ply). Playing every better-move beat for four plies
   pushed game01 and the scholar's mate over the 15 % cap (scaling V3's pauses below a second) and the 17-move Opera
   Game past 6 minutes. The Board offers every line regardless.
2. A move with a detected missed motif keeps its narrated detour in the video (it already walked the line); the new
   silent line is for the moments that had only an arrow.
3. The Summary's key moments offer "Show the best line" only where there is no walkthrough ("Show me what I missed"
   already plays that line); the Board offers both.
4. Line mode is per-screen state: a font-size or locale change (which recreates the activity) returns to the game;
   rotation keeps it (the activity handles orientation).
5. Fixed beyond the brief: the Board could not be opened at a game's last move (`initialPly` clamp).
6. The emulator models were pushed by hand after two checksum failures from `model_test_server.py` (above; not
   investigated, flagged for a later task).
7. `desktop/` keeps its own timeline: a PC-rendered video shows the hold on the still board, not the line.
8. The before/after timing table is from the host recordings (169 wpm); the device export used the on-device
   depth-14 analysis, whose two lines are on 10.g4 and 20...Na6.
