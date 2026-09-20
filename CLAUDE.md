# CLAUDE.md — Chess Analyzer

Conventions and hard-won gotchas for this project. Read before changing the build or the engine.

## Layout

- `:core` — pure Kotlin/JVM. Chess rules, PGN, and the whole analysis model. **No Android imports.**
  All of it is host-testable; keep it that way, it is why the analysis logic has real tests.
- `:engine` — Android library. Stockfish compiled via NDK/CMake + JNI bridge + net download.
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
4. **The net is not in the APK.** ~79 MB compressed / 98,511,183 bytes on disk, downloaded at
   runtime and verified by SHA-256 (the filename encodes the first 12 hex digits of its own hash).
5. **Net name has one source of truth**: `vendor/Stockfish/src/evaluate.h` `EvalFileDefaultName`.
   The build generates a constant from it. Do not hardcode it a second place.

## Build gotchas

- Stockfish source is **not** committed. Run `scripts/fetch_stockfish.sh` (pinned to tag `sf_19`)
  before a first build.
- `local.properties` `sdk.dir` must use forward slashes on Windows. Backslashes are Java-properties
  escapes and silently corrupt the path, breaking **every** module's configuration.
- Any module declaring `testInstrumentationRunner` also needs an explicit
  `androidTestImplementation(libs.androidx.test.runner)` — otherwise instrumentation dies with
  `ClassNotFoundException` before a single test runs.
- Release signing reads `keystore.properties` (gitignored) and falls back to unsigned when absent,
  so fresh clones still configure.

## Emulator gotchas

- **Be patient on first boot.** Under swiftshader an AVD can sit `offline` for 10+ minutes. It is
  usually working, not hung — check `qemu-system-*` CPU time, and use `-verbose -show-kernel` to
  watch zygote start before concluding it is stuck.
- Prefer an API 34 `google_apis` x86_64 image. The API 36 `google_apis_playstore` image never came
  online here.
- **Git Bash mangles device paths.** `adb push x /data/local/tmp/` becomes
  `C:/Program Files/Git/data/local/tmp/`. Use `MSYS_NO_PATHCONV=1`, or run adb from PowerShell.
- `scripts/push_test_net.sh` puts the net on the device so engine tests do not download 98 MB.
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
- **Re-seed the NNUE net before driving the UI by hand.** The app downloads the ~98 MB Stockfish net
  at runtime into its own `filesDir`, so a fresh install starts with nothing and a manual smoke test
  sits on "Preparing engine…" re-downloading it. The staged copy at
  `/data/local/tmp/nn-1a298aa575a0.nnue` **survives** an uninstall, so seed from that instead:
  ```bash
  # LAUNCH ONCE FIRST — filesDir does not exist until the app has run, and run-as then fails with
  # "No such file or directory", which reads like a permissions problem and is not one.
  adb shell am start -n net.palaya.chessanalyzer/.MainActivity
  adb shell 'run-as net.palaya.chessanalyzer sh -c "cat /data/local/tmp/nn-1a298aa575a0.nnue > /data/data/net.palaya.chessanalyzer/files/nn-1a298aa575a0.nnue"'
  ```
  Every `connectedDebugAndroidTest` wipes the seed again along with the app. And `run-as` only works
  on a **debuggable** build — the release APK cannot be seeded this way and must download the net for
  real.

## Testing standards

- **Instrumented tests share one real DataStore.** Preferences written by one test survive into the
  next, so a test that needs a pristine value must *establish* it, not assume it (see
  `NarrationSettingsRepository.clearProviderChoiceForTesting`). A test that passed alone and fails
  in the suite is this, not a code bug.
- **A test that measures itself is not independent evidence.** The neural-TTS test logging its own
  duration/RMS was taken as proof for a whole round; the WAV it was supposed to leave behind had
  never actually been retrieved. Where an artifact can be pulled off the device and checked on the
  host, pull it.

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
Details in `docs/PUBLISHING.md`.
