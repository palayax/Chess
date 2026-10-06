# CLAUDE.md — Chess Analyzer

Conventions and hard-won gotchas for this project. Read before changing the build or the engine.

## Layout

- `:core` — pure Kotlin/JVM. Chess rules, PGN, and the whole analysis model. **No Android imports.**
  All of it is host-testable; keep it that way, it is why the analysis logic has real tests.
- `:engine` — Android library. Stockfish compiled via NDK/CMake + JNI bridge + the bundled NNUE net (an asset, copied once to `filesDir`).
- `:app` — Compose UI, navigation, PGN intake, orchestration.

`:core` must never depend on `:engine` or Android. Engine results are adapted into
`core.analysis.EngineLineInput` / `PositionEval` at the app layer.

## The authoritative spec

`docs/ANALYSIS_SPEC.md` defines every threshold, formula and tactic rule. Do not invent
thresholds in code. If the spec is wrong, fix the spec and the code together.
`core/.../analysis/Contract.kt` holds the shared types and wins over the prose spec on any
conflict.

## Engine gotchas — these bite

1. **Stockfish calls `exit()` on a bad net.** We build with `NNUE_EMBEDDING_OFF`, so there is no
   embedded network. `nnue/network.cpp` prints "The engine will be terminated now." and calls
   `exit(EXIT_FAILURE)` if it cannot load the `EvalFile`. The engine runs **in-process on a JNI
   thread**, so that takes the entire app down with no catchable exception.
   `StockfishEngine.setEvalFile()` validates the file before handing it over and `analyze()`
   refuses to run without a loaded net. **Never weaken those guards.**
2. **One engine per process.** The JNI bridge `dup2`s the process-global STDIN/STDOUT onto pipes.
   Two concurrent `StockfishEngine` instances corrupt each other. Own exactly one.
3. **Do not vendor the official Android binary.** It is ~100 MB (embedded net) and `ET_EXEC`
   non-PIE. We compile from source instead: ~1.6 MB per ABI. See README.
4. **The net is in the APK** (uncompressed asset in `:engine`), copied once to `filesDir` and
   SHA-256-verified (98,511,183 bytes; the filename encodes the first 12 hex digits of its own hash).
   Stockfish still cannot read it from the APK (it opens a plain file path), and the `setEvalFile`
   guards are unchanged. Never point it at `/proc/self/fd/N` or any other APK-backed path.
5. **Net name has one source of truth**: `vendor/Stockfish/src/evaluate.h` `EvalFileDefaultName`.
   The build generates a constant from it. Do not hardcode it a second place.

## Build gotchas

- Stockfish source is **not** committed. Run `scripts/fetch_stockfish.sh` (pinned to tag `sf_19`)
  before a first build.
- The two bundled models (the NNUE net and the Kokoro voice, ~257 MB) are **not** committed either.
  Run `scripts/fetch_models.sh` before a first build (after `fetch_stockfish.sh`, which names the net):
  it downloads them into the gitignored `vendor/models/`, verifies them against
  `vendor/models/MODELS.lock`, and the build bundles them. The build **fails loudly** if a model is
  missing or has the wrong size or SHA-256 (`verifyBundledModels`).
- `local.properties` `sdk.dir` must use forward slashes on Windows. Backslashes are Java-properties
  escapes and silently corrupt the path, breaking **every** module's configuration.
- Any module declaring `testInstrumentationRunner` also needs an explicit
  `androidTestImplementation(libs.androidx.test.runner)` — otherwise instrumentation dies with
  `ClassNotFoundException` before a single test runs.
- Release signing reads `keystore.properties` (gitignored) and falls back to unsigned when absent,
  so fresh clones still configure.
- **Toolchain (D1, Google Play target API 36):** AGP **8.9.3** (the minimum line for compileSdk 36),
  Gradle **8.11.1**, Kotlin **1.9.24** with Compose compiler 1.5.14 (unchanged; AGP 8.9 accepts it),
  `compileSdk`/`targetSdk` **36**, minSdk 26, NDK **28.2.13676358** (r28c) in **both** `:engine` and `:app`.
  `:app` compiles no C++ but needs `ndkVersion` anyway: AGP strips every packaged `.so` with that NDK's
  `llvm-strip`, and with it unset AGP 8.9 looks for its own default NDK (27.0, not installed), prints
  "Unable to strip the following libraries" and packages `libstockfish.so` at 17 MB instead of 1.6 MB.
  AGP 8.9 also auto-installs build-tools 35.0.0 on first use.
- **16 KB pages.** Every `.so` must have LOAD segments aligned to 16 KB (Play, targetSdk 35+). NDK r28
  does it by default and `engine/src/main/cpp/CMakeLists.txt` also passes `-Wl,-z,max-page-size=16384`.
  The sherpa-onnx 1.13.8 prebuilts (`libonnxruntime.so`, `libsherpa-onnx-*.so`) and DataStore's
  `libdatastore_shared_counter.so` are already 0x4000. Check after any NDK or dependency change:
  `llvm-readelf -lW <lib>.so` (Align 0x4000 on every LOAD) for each `.so` in the APK, and
  `zipalign -c -P 16 -v 4 <apk>`.
- **R8 is on for release** (`isMinifyEnabled` + `isShrinkResources`). Keep rules that matter:
  `engine/consumer-rules.pro` pins `NativeBridge` and its `native` methods (the JNI symbols are
  `Java_net_palaya_chessanalyzer_engine_NativeBridge_*`; rename either and every engine call dies with
  UnsatisfiedLinkError); `app/proguard-rules.pro` keeps `com.k2fsa.sherpa.onnx.**` with all members (the
  AAR ships an EMPTY `proguard.txt`, and its JNI reads config fields by name). `:app` uses no
  kotlinx.serialization or reflection (org.json only). A new JNI library, or anything read by name from
  native code or reflection, needs its own rule, and the release build must be run on a device, not
  just compiled: a missing rule only fails at runtime.
- **Foreground-service type.** `VideoExportService` declares `dataSync|mediaProcessing` and picks one at
  runtime (`ExportForegroundServiceType`: mediaProcessing on API 35+). Call the platform
  `Service.startForeground(id, n, type)`, **not** `ServiceCompat.startForeground`: androidx.core
  1.13.1 masks the type to the Android 14 set and turns mediaProcessing into 0, which the platform
  refuses ("FGS with type none"); the export then runs on silently without a foreground service.
- **Edge to edge** is enforced at targetSdk 35+. A Scaffold `bottomBar` gets no insets of its own:
  wrap its content in `navigationBarsPadding()` (Practise, Walkthrough, Video do). `MainActivity` keeps
  the whole UI out of a side display cutout and forces dark system-bar styles (the app is always dark).
- **bundletool** is not on the PATH: `java -jar tools/bundletool-all-1.18.3.jar` (gitignored `tools/`,
  from github.com/google/bundletool releases, SHA-256 `a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29`).
  Sizes per device: `build-apks --bundle=app/build/outputs/bundle/release/app-release.aab --output=x.apks`
  then `get-size total --apks=x.apks --device-spec=<spec>.json` (see `docs/PUBLISHING.md`).
- `assembleRelease` writes per-ABI APKs (`app-arm64-v8a-release.apk`, ...) plus
  `app-universal-release.apk`; the ABI split is only enabled when an `assembleRelease` task is requested,
  so debug builds still produce the single `app-debug.apk`.

## Emulator gotchas

- **Be patient on first boot.** Under swiftshader an AVD can sit `offline` for 10+ minutes. It is
  usually working, not hung — check `qemu-system-*` CPU time, and use `-verbose -show-kernel` to
  watch zygote start before concluding it is stuck.
- Two AVDs: `chess34` (API 34 `google_apis` x86_64) and `chess36` (API 36 `google_apis` x86_64, Pixel 6,
  4 GB RAM, 8 GB data; created in D1, cold boot under 2 min with WHPX). The API 36(.1)
  `google_apis_playstore` image never came online here; use `google_apis`. Run the instrumented suites on
  both: API 35+ behaviour (edge to edge, the mediaProcessing FGS type) only shows on `chess36`.
- Edge-to-edge checks on a device: `cmd overlay enable-exclusive --category
  com.android.internal.systemui.navbar.threebutton` (3-button bar, the strictest case) and `cmd overlay
  enable com.android.internal.display.cutout.emulation.tall` (a cutout, on the side in landscape). Restore
  with `...navbar.gestural` and `cmd overlay disable ...cutout.emulation.tall`.
- **Git Bash mangles device paths.** `adb push x /data/local/tmp/` becomes
  `C:/Program Files/Git/data/local/tmp/`. Use `MSYS_NO_PATHCONV=1`, or run adb from PowerShell.
- `connectedDebugAndroidTest` pushes a ~371 MB debug APK (the models are inside it) and the full `:app`
  run takes **20-40 minutes**. A hand `adb install` of that APK takes about a minute.
- **Gradle uninstalls the app once `connectedDebugAndroidTest` finishes.** That deletes
  `/sdcard/Android/data/<pkg>/`, so any evidence file a test wrote there (a synthesized WAV, an
  exported MP4) is gone before you can `adb pull` it — and a later `run-as` reports
  `unknown package`, which reads like a permissions bug and is not one. To keep an artifact,
  install both APKs yourself and run the test directly, bypassing Gradle:
  ```bash
  adb install -r -t app/build/outputs/apk/debug/app-debug.apk
  adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
  adb shell am instrument -w -r -e class '<fqcn>#<method>'     net.palaya.chessanalyzer.test/androidx.test.runner.AndroidJUnitRunner
  ```
- An exported video lands in `/sdcard/Movies/ChessAnalyzer/`, not `/sdcard/Movies/` — a
  non-recursive `ls` of the parent will tell you it failed when it did not.
- **Nothing to seed.** A fresh install sets itself up on the first analysis ("Setting up the engine
  (one time)...", about 10-30 s on the emulator): the net is copied and the voice unpacked from the
  APK into `filesDir`. The app has no network permission, so there is nothing to download either.

## Testing standards

- **Instrumented tests share one real DataStore.** Preferences written by one test survive into the
  next, so a test that needs a pristine value must *establish* it, not assume it (see
  `NarrationSettingsRepository.clearProviderChoiceForTesting`). A test that passed alone and fails
  in the suite is this, not a code bug.
- **A test that measures itself is not independent evidence.** The neural-TTS test logging its own
  duration/RMS was taken as proof for a whole round; the WAV it was supposed to leave behind had
  never actually been retrieved. Where an artifact can be pulled off the device and checked on the
  host, pull it.

- **Generated text is a claim, and is verified.** Card texts and walkthroughs are shown as fact:
  `CommentaryGenerator` says a thing only when the position or the engine's numbers prove it (spec
  §7.2), and `scripts/audit_commentary.py` re-checks every text of the two recorded games with
  python-chess (`docs/COMMENTARY_AUDIT.md`). A new sentence template needs a verified claim behind it
  and a case in `CommentaryClaimsTest`; an unverifiable sentence is dropped, not hedged. The text is a
  pure function of the annotation and the viewer's side, so it is rewritten when the user picks a side.
- Move generation is verified by **perft** against the five standard positions: **depth 5** on the
  start position and Position 3, **depth 4** on Kiwipete, Position 4 and Position 5. If you
  touch move generation, those numbers must stay exact — they are the correctness oracle.
- Instrumented tests that use `assumeTrue` can pass **vacuously**. Always check `skipped="0"` in
  `*/build/outputs/androidTest-results/**/*.xml`, not just "BUILD SUCCESSFUL".
- Engine behaviour is verified on-device (`:engine:connectedDebugAndroidTest`), including a real
  forced-mate search. A compile is not verification.

## Licensing — affects publication

GPLv3, because Stockfish is linked. The app cannot be closed-source, and corresponding source must
be offered. Opening book is CC0. **Piece art is Cburnett, CC BY-SA 3.0** (attribution in
`app/src/main/assets/PIECES_LICENSE.txt`); classification badges are original. Chess.com is a *design
reference only* — no assets, logos or trademarks, and the listing must not imply affiliation.
The bundled voice adds Kokoro-82M (Apache 2.0) and espeak-ng pronunciation data (GPL v3 or later per the
espeak-ng README; the archive ships no licence file for it), and the NNUE net was trained on Leela Chess
Zero data (ODbL, per the Stockfish README). All are credited in About. Details in `docs/PUBLISHING.md`.

## Design reference — owner's standing rule (2026-10-04)

**Chess.com is the UI/UX reference for every design decision that is not clearly reflected in, or
derived from, the owner's own design goals** (simple, friendly, fully local, no external APIs).
Before choosing a layout, flow, wording pattern, interaction or default on your own, look at how
chess.com's Game Review, analysis board and puzzles handle it, and follow that unless it conflicts
with an owner goal. Record the reference you used (pattern + what you took from it) in the design doc
or the RUN_LOG entry; "I thought it looked better" is not a reference.

The licensing line above still binds: chess.com is a **design reference only**. Take *patterns*
(information hierarchy, interaction model, terminology conventions such as move-quality names and
colours); never copy assets, icons, logos, screenshots, marketing text or their exact copy, and
never imply affiliation. Draw our own art and write our own words.

## Accessibility conventions (U10, R6a)

- **Every screen title is an `AppBarTitle`** (`ui/a11y/Accessibility.kt`): a heading for TalkBack, one line, its size capped at
  1.3x so a fixed 64 dp bar never clips at a 2.0 system font. Section titles use `Modifier.asHeading()`.
- **A picture is described or hidden, never read as glyphs.** `ClassificationBadge` hides itself from TalkBack unless given a
  `contentDescription` (its "??" would be read as "question mark question mark"); the board describes itself
  (`ChessBoard`: one stop listing the position when read-only, 64 square nodes with a click action when it takes taps);
  `EvalBar` and `EvalGraph` say what they show. A row of cells that only makes sense together (move chip, summary table row)
  is one `clearAndSetSemantics` sentence.
- **Change announcements.** Dynamic text that appears after an action sits in a polite live region (Practise feedback and hints,
  the Walkthrough and Board cards, the share-failed line); the mute toggle calls `announce(...)`.
- **Touch targets 48 dp** (`heightIn(min = 48.dp)` on every clickable that is not a Material button). The one exception is the
  chess board's squares: the board cannot be wider than the screen, so a square is `width / 8` (51 dp on a 411 dp phone with the
  edge-to-edge Practise board, 45 dp at 360 dp, about 41 dp in landscape).
- **Text is never clipped at 2.0**: no `maxLines` on content, rows that hold a name beside a button stack from font scale 1.3.
- **Colours.** `ThemeContrastTest` asserts WCAG AA for the semantic roles in `Color.kt`; the move-quality palette (`Class*`) is
  owner-approved and untouched (text in a class colour goes through `legibleTextColor`, the badge glyph is near-black).
- **Landscape** is `isLandscape()` (the activity handles `orientation|screenSize`, so state survives rotation): Board, Practise,
  Walkthrough and Video put the picture on the left and controls on the right; the rest scroll.
