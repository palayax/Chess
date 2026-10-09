# CLAUDE.md — Chess Analyzer

Conventions and hard-won gotchas for this project. Read before changing the build or the engine.

## Layout

- `:rephrase` — Android library (C2): llama.cpp built from source via NDK/CMake + JNI (`librephrase.so`), `LlamaRephraser`,
  `GgufHeader`. The weights are downloaded, never in the APK.
- `:core` — pure Kotlin/JVM. Chess rules, PGN, and the whole analysis model. **No Android imports.**
  All of it is host-testable; keep it that way, it is why the analysis logic has real tests.
- `:engine` — Android library. Stockfish compiled via NDK/CMake + JNI bridge + `NetStore` (the downloaded NNUE net in `filesDir/nets/`).
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
4. **The net is NOT in the APK any more (D2a/D2b, `docs/MODEL_DOWNLOAD_DESIGN.md`).** It is downloaded
   once on first run (only after the user's tap on the Setup screen, D2c: `ModelDownloadService`) into `filesDir/nets/<name>.part`, checked
   against the build-time pins (98,511,183 bytes, full SHA-256, NNUE header version + architecture hash,
   all from `vendor/models/MODELS.lock` via `generateModelPins`) and moved to `filesDir/nets/<name>` by
   `NetStore.installVerified`. `EngineController.ensureReady()` hands Stockfish only
   `NetStore.verifiedNetOrNull()` (size + full SHA-256, hashed once per process) and otherwise throws
   `NetNotInstalledException` (analysis -> `Failure.SETUP_REQUIRED`). The `setEvalFile` guards are
   unchanged. Never point Stockfish at a file that has not passed `verifiedNetOrNull()`, and never use
   the engine to "test-load" a net: a failed load is `exit()` (gotcha 1). An update from a bundled build
   moves `filesDir/nn-*.nnue` into `nets/` once (`NetStore.migrateLegacy`, run in `Application.onCreate`).
5. **Net name has one source of truth**: `vendor/Stockfish/src/evaluate.h` `EvalFileDefaultName`.
   The build generates a constant from it. Do not hardcode it a second place.
6. **A search stopped by `nodes`/`movetime` prints one last batch that lies about its depth** (F1).
   Stockfish 19 re-prints its lines at the stop: slots not re-searched yet keep the previous
   iteration's score and PV but carry the NEW depth label (seen on the host: depth-15 numbers printed
   as "depth 16"). `StockfishEngine.analyze` therefore never takes "the latest line per slot": it uses
   `ConsistentLines` (last complete batch of one depth; the stop batch is discarded) and reports
   `stoppedEarly`. Do not "simplify" that back. The progress callback on `analyze` is plain Kotlin
   on the output already being read, nothing crosses JNI, so it needs no R8 rule.

7. **Model updates switch the net on THE engine, journaled (D2e, `docs/MODEL_DOWNLOAD_DESIGN.md` §4).** The
   active net is `NetStore.activeIdentity()`: the compiled pin, or an update's record in `nets/active.properties`
   (name, size, SHA-256 from the signed manifest; ignored if the file's NNUE header is foreign). Only
   `ModelActivator` and setup write it. A switch is `EngineController.exclusive { trialLocked() }`: it refuses
   while an analysis runs (`AnalysisService` wraps its engine work in `beginAnalysis()`/`endAnalysis()`; a new
   analysis waits for the switch) and hands the engine nothing but `verifiedNetOrNull()`. The header is checked
   in Kotlin before (`activateNet`), so a wrong-architecture net never reaches Stockfish. A trial that kills the
   process (Stockfish `exit()`) is rolled back by `ModelActivator.recoverOnStartup()`, which runs FIRST in
   `ChessAnalyzerApplication.onCreate`: nothing may touch the engine or the voice before it. Never add a second
   `EngineController`/`StockfishEngine` to "try" a net.
8. **Eval caches are per net** (`eval_cache/<net 12-hex>/<key>.json`, `EvalCacheLayout`). The flat layout of
   earlier builds is moved into the active net's folder at start; a committed net update deletes the other
   folders. `AnalysisService` saves into the folder of the net the engine actually loaded
   (`EngineController.loadedNetName`). The F1 budget stays in the key.

## Build gotchas

- Stockfish source is **not** committed. Run `scripts/fetch_stockfish.sh` (pinned to tag `sf_19`)
  before a first build.
- **The two models (NNUE net 98.5 MB, Kokoro voice 102.5 MB as `.tar.gz`) are not in the APK (D2a) and not committed.** The
  build needs only `vendor/models/MODELS.lock`: `generateModelPins` (in `:engine` for the net, in `:app`
  for the voice and `release.tag`) checks the lock (net name prefix of `net.sha256`, `net.version` equal
  to `Version` in `nnue_common.h`, hash/size/tag formats) and writes `GeneratedNetPins` /
  `GeneratedModelPins`. A clean clone builds WITHOUT `fetch_models.sh` (proven in D2a with the files moved
  away). If the files ARE under `vendor/models/` they are verified against the lock too, and a mismatch
  fails the build. `scripts/fetch_models.sh` is now the developer/test/publish fetcher (it also pins
  `net.sha256`, `net.arch_hash`, `net.version` on its first run); you need it for
  `scripts/model_test_server.py`, `scripts/publish_models.sh` and the instrumented tests (seed assets, D2d).
  `-PpalayaModelsLock=<file>` points the task at another lock (only to prove a bad lock fails).
- **The voice is downloaded as `kokoro-int8-en-v0_19.tar.gz` (D2f), but its identity is still the TAR.**
  `MODELS.lock` pins both: `kokoro.targz.*` (what `ModelDownloader` verifies; `GeneratedModelPins.VOICE_FILE_NAME`,
  `VOICE_DOWNLOAD_SIZE_BYTES`, `VOICE_DOWNLOAD_SHA256`) and `kokoro.tar.*` (`VOICE_SIZE_BYTES`, `VOICE_SHA256`: what
  `VoiceStore` checks the inflated stream against, and what the `.provisioned` marker holds). Keep it that way: the
  marker is how an update from the bundled 1.0 keeps its voice and how the narration cache key stays stable.
  `VoiceStore.gunzipIfCompressed` switches on the gzip magic, so a plain tar still works; the unpack stops as
  soon as the stream passes the tar's size (gzip bomb). Update manifests carry `tarSha256`/`tarSize` for a
  `.tar.gz` voice and "already installed" compares tars (`ManifestEntry.unpackedSha256`). The `.tar.gz` is GNU
  `gzip -9 -n` output (reproducible); `fetch_models.sh` makes it from the cached tar and refuses a different result.
- **AAPT gunzips assets named `*.gz` and drops the suffix** (found in D2f: the test APK held the 158 MB tar, not
  the 102.5 MB download). The voice seed therefore goes into the androidTest APK as `tts/<name>.tar.gz.seed`
  (`prepareTestSeedAssets`, `TestApp.voiceSeedPath`). Never add a `.gz` asset expecting the bytes to survive.
- **Version.** versionCode 1 = the bundled 1.0 (R7, in `dist/`); 2 = 1.1, the first downloading build (D2f). Raise
  it for every upload. Publish model manifests with `--min-version-code 2`.
- **Model download URLs.** `BuildConfig.MODEL_BASE_URL` = `https://github.com/palayax/Chess/releases/download/`
  (the owner's public repo, created 2026-10-07); first-run URL = base + `release.tag` + `/` + file name.
  `-PpalayaModelBaseUrl=http://127.0.0.1:8787/` (with `adb reverse tcp:8787 tcp:8787`) overrides it **for debug builds only** (a release task in the
  same invocation fails before anything runs). Cleartext is allowed only by the debug-only
  `app/src/debug/res/xml/network_security_config.xml` (10.0.2.2, 127.0.0.1, localhost) and by
  `ModelDownloader`'s own rule (`allowCleartextLoopback = BuildConfig.DEBUG`). Publish the release
  (`scripts/publish_models.sh <tag> --min-version-code N`) BEFORE shipping an app that points at it.
- **The update manifest is signed (D2e).** `models.json.sig` = DER ECDSA P-256 over the exact bytes; the
  public key is `vendor/models/manifest_public_key.der` (committed), compiled into
  `GeneratedModelPins.MANIFEST_PUBLIC_KEY_DER_BASE64` by `generateModelPins` (the build fails unless it is a
  P-256 SPKI). The private key is `keystore/models-signing.pem` (gitignored by `*.pem`; never print, log or
  read it into a tool output; custody in `docs/PUBLISHING.md` §4b). Tests sign with TEST keys generated at run
  time (`TestManifests`) or the throwaway-openssl fixture in `app/src/test/resources/manifest/` (its private
  half was deleted); `.gitattributes` marks those fixtures `-text` because CRLF conversion would break the
  signature. `scripts/publish_models.sh --sign <file>` / `--verify [file]` sign and check by hand. On Windows
  the `python3` on the PATH may be the Store alias that only prints a hint: scripts must pick a python that
  actually runs.
- **"Check for updates" runs only on the tap** (`UpdateChecker.check()` from the Settings sheet; exactly two
  requests, `models.json` and `.sig`, through `ModelDownloader.fetchSmall`), and every entry is judged again by
  `ModelCompatibility` right before a download (`ModelUpdateInstaller`), so an incompatible file can never be
  fetched. `UpdateCheckNetworkTest` drives the real Settings UI against `FaultHttpServer`
  (`ChessAnalyzerApplication.updateCheckerForTesting`, tests only).
- **The narration cache key carries the voice id** (`NeuralTtsProvider(voiceVersionId = VoiceStore.installedVersionId())`)
  **and the speaker** (V1: `sid` from Settings, Narrator voice; the update trial keeps the default speaker), and a
  voice update clears `NarrationStore`.
- **The video pace (V3, ANALYSIS_SPEC §9.8) is laid over the finished story** (`ScriptBuilder.paced`, after the
  budget loop). Keep it there: then every pace has the same segments and words (cached narration is reused) and the
  §9.7 budget holds `storyMs`, not the pace time. A key move's silent lead-in (`ScriptSegment.leadIn`) is honoured by
  `TimelineBuilder`, `SegmentFrameBuilder`, the exporter's PCM track and the player; a new timeline consumer must
  honour it too. A voice from an update is accepted by `VoiceStore.isInstalled()`
  through its `kokoro/.compat` record (layout + sherpa-onnx range) while this build still matches it.
- **Only `data/models/ModelDownloader.kt` may open a network connection** (`NetworkCallSitesTest` scans
  `:app` and `:engine` main sources). The manifest has INTERNET + ACCESS_NETWORK_STATE since D2b; the app
  must still make no network call on its own: downloads start only from a user tap.
- **A library can use the network on the app's behalf without opening a socket in our process (D2f).**
  androidx.emoji2 (via Compose) registered `EmojiCompatInitializer`, which asks Play services' font provider for
  "Noto Color Emoji Compat" on the first Activity; GMS downloads it (~3 MB) and charges the app's uid. The
  ProxySelector saw nothing; only `NoNetworkAfterSetupTest`'s TrafficStats check caught it, and only when GMS had
  no cached copy. The initializer is removed in the main manifest (`tools:node="remove"`), pinned by
  `ManifestPermissionsTest` and `NetworkPermissionTest`. After any dependency change, read the merged manifest's
  `androidx.startup.InitializationProvider` meta-data.
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
  `ModelDownloadService` (D2c, the first-run download) is `dataSync` only, on every API level, and also
  calls the platform `startForeground` (seen on chess36/34: `types=0x00000001`); its `onTimeout` pauses the
  download. It is started ONLY from the Setup screen's button (`START_NOT_STICKY`; a stray intent runs
  nothing), so "no network call on its own" holds: a killed download is not restarted, it shows as
  "Paused at N%" with Resume on the next launch.
  **Never stopSelf() a started service unconditionally at the end of a run** (D2d): a Resume tapped right after
  a Pause has already called `startForegroundService()`, and destroying the service before its
  `startForeground()` makes the platform kill the app (`ForegroundServiceDidNotStartInTimeException`). The
  download's tail stops on the main thread and only when no new run was queued.
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

## On-device wording model gotchas (C2, docs/LLM_REPHRASE_DESIGN.md)

- **The model only rewords; the claim checker decides.** `core.text.ClaimChecker` is a comparator over the facts of
  the verified C1 text (moves with their check marks, squares, piece-on-square pairs, pieces, the order of sides,
  numbers, term and outcome-verb COUNTS, band order, names, negations, alternative-move markers, and the order of
  moves/outcomes/check), plus count rules for hedges, praise and judgement words. Any doubt is a rejection and the
  original is shown. Every backend's output goes through `RephraseVerdicts.judge`; nothing may bypass it.
  `scripts/rephrase_check.py` (`audit_commentary.py rephrase` / `mutate-rephrase`) is the independent Python twin;
  the two must agree on every line of `core/build/rephrase/mutations.jsonl` and of every `measure_*.jsonl`. A rule
  added to one is added to the other in the same change, with a mutation in both tables that proves it bites.
- **Narration is heard**: a candidate that writes a square as notation ("h5") is refused (SHAPE_FORMAT);
  `RephrasePrompt.cleanOutput(raw, NARRATION)` first turns bare squares back into the spoken form ("h five").
- **llama.cpp is built from source** (`scripts/fetch_llama_cpp.sh`, tag in `vendor/LLAMA_CPP_VERSION.txt` =
  `rephrase.runtime.tag` in MODELS.lock; `:rephrase`'s `generateRephraseRuntime` fails the build if they differ or
  the checkout is missing). One static `librephrase.so` per ABI (arm64-v8a with `GGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16`,
  x86_64 with AVX2/FMA/F16C), `BUILD_SHARED_LIBS=OFF`, no `GGML_BACKEND_DL`/`ALL_VARIANTS` (their dlopen'ed .so files
  escape the 16 KB link flag), no OpenMP/llamafile, **no KleidiAI** (its CMake downloads sources at configure time).
  `-Wl,--exclude-libs,ALL` keeps only the six JNI symbols exported. **The debug variant is compiled -O3 too**
  (`CMAKE_C/CXX_FLAGS_DEBUG` forced in the CMakeLists): an -O0 llama.cpp is unusably slow even on an emulator.
  armeabi-v7a gets no library (`RephraseSupport` says UNSUPPORTED_ABI); an x86_64 CPU without AVX2/FMA/F16C says
  UNSUPPORTED_CPU instead of dying on an illegal instruction.
- **R8**: `rephrase/consumer-rules.pro` keeps `NativeRephrase` and its natives (symbols
  `Java_net_palaya_chessanalyzer_rephrase_NativeRephrase_native*`). The bridge returns the generated text as a
  `byte[]` (UTF-8; a token can split a multi-byte character, and `NewStringUTF` would choke on it).
- **One model per process** (`RephraseBackend`): the 1.1 GB GGUF is mmap'ed (file-backed for the low-memory killer),
  the fixed prompt prefix (~700 tokens) stays in the KV cache (`llama_memory_seq_rm` from the prefix boundary),
  n_ctx 2048 (about 57 MB of f16 KV on the 1.5B). It is freed on `onTrimMemory(RUNNING_LOW+)` and a minute after a
  job. A load is journaled (`rephrase/loading.json`): found at start, the file is re-hashed before its next use; two
  deaths in a row turn the feature off (the low-memory killer is a likelier cause than a bad file, so one crash does
  not delete 1.1 GB).
- **The GGUF is checked in Kotlin before llama.cpp sees it** (`GgufHeader`: magic, version 3, counts, known tensor
  types, every tensor inside the file, `general.architecture`) on install, on update activation and once per
  process with the full SHA-256. llama.cpp b11190 refused a truncated and a corrupted file cleanly on the emulator
  (load returns 0), but the app never relies on that.
- **The wording model is setup's optional third file** (`ModelFile.REPHRASE`, only while `rephrase/wanted` exists;
  it never makes setup incomplete) on its OWN release tag `rephrase.release.tag` (the net and the voice stay on
  `release.tag`). Downloading it at setup switches the feature on (owner decision §12.3). The cache lives in
  `filesDir/rephrase/cache/<model>/` (NOT `rephrase/<model>/`: `rephrase/models/` holds the GGUF and a model switch
  purges every other cache folder). `rephrase/` is excluded from backup in all three rule sets.
- **The narration post-pass is not wired yet** (V4 owned the video files when C2 landed): see the "V4 INTEGRATION
  HOOK" comment in `AnalysisViewModel` and the TODO in `ChessAnalyzerNavHost`'s Video route.

## Emulator gotchas

- **Be patient on first boot.** Under swiftshader an AVD can sit `offline` for 10+ minutes. It is
  usually working, not hung — check `qemu-system-*` CPU time, and use `-verbose -show-kernel` to
  watch zygote start before concluding it is stuck.
- Two AVDs: `chess34` (API 34 `google_apis` x86_64) and `chess36` (API 36 `google_apis` x86_64, Pixel 6,
  4 GB RAM, 8 GB data; created in D1, cold boot under 2 min with WHPX). The API 36(.1)
  `google_apis_playstore` image never came online here; use `google_apis`. Run the instrumented suites on
  both: API 35+ behaviour (edge to edge, the mediaProcessing FGS type) only shows on `chess36`.
- **Start chess36 with `-gpu swangle_indirect`** for any screenshot or UI driving (V2): with the default host GPU
  (`gpu mode host`, the NVIDIA card) the instrumented tests pass but `screencap` returns an all-black frame and the
  first boot after it shows "System UI isn't responding" (tap Wait).
- Edge-to-edge checks on a device: `cmd overlay enable-exclusive --category
  com.android.internal.systemui.navbar.threebutton` (3-button bar, the strictest case) and `cmd overlay
  enable com.android.internal.display.cutout.emulation.tall` (a cutout, on the side in landscape). Restore
  with `...navbar.gestural` and `cmd overlay disable ...cutout.emulation.tall`.
- **Git Bash mangles device paths.** `adb push x /data/local/tmp/` becomes
  `C:/Program Files/Git/data/local/tmp/`. Use `MSYS_NO_PATHCONV=1`, or run adb from PowerShell.
- `connectedDebugAndroidTest`: the debug app APK is ~114 MB since D2a (no models). **The models reach a
  test only as seed assets of the TEST APK (D2d):** `vendor/models/engine-assets` and (via `prepareTestSeedAssets`,
  the voice `.tar.gz` renamed `.seed`) `build/generated/testSeedAssets` are the `androidTest` asset dirs of `:app`
  (both files) and `:engine` (the net), stored uncompressed (`noCompress`), and `checkTestSeedAssets` fails the
  test build if `scripts/fetch_models.sh` was never run.
  `TestApp.ensureSetUp()` / `TestNet.net()` install them through the stores' own tails; the setup tests serve
  them over the in-process `FaultHttpServer` (`SeedAssetBody`). They are NOT in `app-debug.apk` or any release
  APK (checked with `unzip -l` in D2d); never "fix" a test by putting them back into the app. The androidTest
  APK is ~202 MB since D2f (the `:engine` one ~105 MB). The full `:app` run took **12-14 minutes** per device in D2d
  (153 tests; budget 20-40 under host load), `:engine` 5-8 minutes; run the devices one after the other (never
  two connected suites at once on this host). A test that opens `MainActivity` and expects Home
  must call `ensureSetUp()` first (a fresh process opens on Setup while the net is missing);
  `SetupGateInstrumentedTest` pins the gate itself. `ChessAnalyzerApplication.modelSetupForTesting` points the
  service and the gate at a scratch directory (tests only).
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
- **The Video screen's preview never synthesizes**: it only plays WAVs already in `files/narration/` (put there
  by an export or "Prepare narration") and otherwise speaks with the device voice. To get Kokoro WAVs onto a
  device for a check, export ("Save video").
- **Compose status lines are `clearAndSetSemantics` live regions**: their words are a content description, so
  a test finds them with `onAllNodesWithContentDescription`, not `...WithText` (cost a run in D2e).
- An exported video lands in `/sdcard/Movies/ChessAnalyzer/`, not `/sdcard/Movies/` — a
  non-recursive `ls` of the parent will tell you it failed when it did not.
- **A fresh install has no models (D2b) and opens on the Setup screen (D2c).** Nothing is copied out of
  the APK and there is no "Setting up the engine (one time)" phase. To get the models onto an emulator:
  `python scripts/model_test_server.py` on the host (it serves `vendor/models/`, faults with `--fault`,
  `--fault slow:3m --fault-times 0` makes the states visible), `adb reverse tcp:8787 tcp:8787`, a debug build made
  with `-PpalayaModelBaseUrl=http://127.0.0.1:8787/`, then tap Download.
  **Do not use 10.0.2.2 for the model files (R8).** The emulator's user-mode network drops single bytes of a long
  response (on 1440-byte segment boundaries, all in the last ~128 KB; 3-16 bytes per 98.5 MB, every unthrottled
  fetch): the app sees the body end short, resumes the tail and fails the SHA-256 twice ("didn't match"). Proven
  outside the app (raw `nc` with a diff against the file; a plain socket server does the same; adb reverse is
  byte-exact) and inside it (its `.part` = exactly the bytes received, same gaps). It is not the downloader and
  not the server; the fault matrix pins the behaviour (`Fault.LoseBytesAt`). A real phone has no such NAT, and
  over HTTPS a lost byte would be a TLS error, which resumes cleanly. Killing the server made connects via
  10.0.2.2 TIME OUT (D2c: 15 s each, about 100 s until "The connection dropped"); airplane mode fails at once
  (about 30 s of backoff). Alternatively push them by hand: launch the app once (so `filesDir` exists), then
  `adb push` the net and `run-as net.palaya.chessanalyzer` copy it to `files/nets/nn-1a298aa575a0.nnue`
  (the voice needs `files/tts_models/kokoro/` unpacked plus its `.provisioned` marker holding the tar
  SHA-256). A release build points at `palayax/Chess`, whose `models-2026.10` release and signed `models` manifest are
  live since 2026-10-07: a fresh release install downloads both files from GitHub (302 to
  `release-assets.githubusercontent.com`) and verifies them (R8: about 100 s on the emulators, no retry).
- **Screenshots on chess36/chess34 (R8):** emulator 36.3.10 runs `-gpu swangle_indirect` as `swiftshader_indirect`
  ("change of renderer detected"), and `adb exec-out screencap` then returns an all-WHITE frame. Use the emulator
  console instead: `adb emu screenrecord screenshot <host dir>` writes a correct `Screenshot_<n>.png` there.
- **A release build is not debuggable, so `run-as` fails; the `google_apis` images allow `adb root`** (D2f): with
  root, `ls /data/data/net.palaya.chessanalyzer/files/...` reads the release app's storage directly (the
  migration proof used it). `adb unroot` afterwards. The `_playstore` images do not allow root.
- A game shared while the net is missing waits in `filesDir/setup_waiting_game.json` (D2c) and is analysed
  as soon as the net is in (from Setup or Home), even while the voice still downloads.

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
  §7.2), and `scripts/audit_commentary.py` re-checks every text of the three recorded games with
  python-chess (`docs/COMMENTARY_AUDIT.md`; `mutate` is its negative control). A new sentence template
  needs a verified claim behind it, a verifier in the script (an unrecognised sentence is WRONG), a row
  in the vocabulary table of `docs/COMMENTARY_STYLE.md` and a case in `CommentaryClaimsTest` (its
  template catalogue must list every phrasing); an unverifiable sentence is dropped, not hedged. A
  professional term is used only where its definition is proved (C1). Phrasings rotate by
  `Variety(ply)`, never by randomness, so the text is a pure function of the annotation and the
  viewer's side, is rewritten when the user picks a side, and keeps the narration cache reproducible.
- Move generation is verified by **perft** against the five standard positions: **depth 5** on the
  start position and Position 3, **depth 4** on Kiwipete, Position 4 and Position 5. If you
  touch move generation, those numbers must stay exact — they are the correctness oracle.
- **"No network after setup" is measured, not assumed** (`NoNetworkAfterSetupTest`, D2d): a recording
  `ProxySelector` (Android's HttpURLConnection/OkHttp asks it per route, URI without a path; a raw
  `java.net.Socket` is NOT seen by it on this libcore) plus the uid's `TrafficStats` delta, which covers raw
  sockets. The selector is proven live with an in-process request before the zero is trusted.
- Instrumented tests that use `assumeTrue` can pass **vacuously**. Always check `skipped="0"` in
  `*/build/outputs/androidTest-results/**/*.xml`, not just "BUILD SUCCESSFUL".
- Engine behaviour is verified on-device (`:engine:connectedDebugAndroidTest`), including a real
  forced-mate search. A compile is not verification.

## Licensing — affects publication

GPLv3, because Stockfish is linked. The app cannot be closed-source, and corresponding source must
be offered. Opening book is CC0. **Piece art is Cburnett, CC BY-SA 3.0** (attribution in
`app/src/main/assets/PIECES_LICENSE.txt`); classification badges are original. Chess.com is a *design
reference only* — no assets, logos or trademarks, and the listing must not imply affiliation.
The narration voice (downloaded on first run since D2b) adds Kokoro-82M (Apache 2.0) and espeak-ng pronunciation data (GPL v3 or later per the
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
- **RTL is switched off for this version** (owner, 2026-10-08: Hebrew deferred). `supportsRtl="false"` and a root
  `LocalLayoutDirection provides Ltr` in `MainActivity`; the per-component Ltr pins (board, move list, eval bar) stay for
  when it comes back. Do not spend device time on he-IL / RTL until the owner reopens it (RUN_PLAN "H0").
