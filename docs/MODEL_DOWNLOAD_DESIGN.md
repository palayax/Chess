# Downloading the engine net and the voice on first run: design (D0)

Produced by a Fable 5.1 / high design agent (read-only), 2026-10-06. Supersedes the packaging half of
`docs/BUNDLED_MODELS_DESIGN.md` (B-series); the extraction, verification and marker logic from that round is
kept. "Measured" = read from the tree or git history; "estimate" = arithmetic or a guess, marked as such.
chess.com statements marked **[memory]** were not read on a page this session.

> **Status (D2f, 2026-10-07): implemented as D2a-D2f (RUN_LOG).** The sizes in the UX mock-ups below are the
> design's (plain tar: "about 260 MB", "450 MB"). Since D2f the voice is downloaded as `.tar.gz` (open question 2,
> §10): the Setup screen reads "about 210 MB" to download, about 400 MB free space at the peak, 260 MB once done.
> Current facts: `docs/PUBLISHING.md` §0. Deviations from this design are listed in each RUN_LOG entry.

**Owner decision (2026-10-06):** a small installer for Google Play and direct installs. The two models
(`nn-1a298aa575a0.nnue`, 98,511,183 B; `kokoro-int8-en-v0_19.tar`, 158,269,440 B; pins in
`vendor/models/MODELS.lock`) leave the APK and are downloaded once, on one setup screen. Afterwards the app is
100% offline and makes no network call on its own. Upgrades only through a manual "Check for updates".
No program code is ever downloaded.

## 0. Decisions

| Question | Decision | Why |
|---|---|---|
| When does the download start | Only after the user taps **Download** on the Setup screen. Never automatically, never at launch | The owner's "no network calls on its own" plus the metered confirmation both need a tap first |
| Setup = both files? | Yes, one screen, net first then voice. **Analysis is unblocked as soon as the net is in**; the voice is not required to use the app (device TTS narrates until it arrives), but setup only counts as *complete* with both | The net is the hard dependency (no engine without it); the voice has a fallback by construction (`selectNarrationProvider`) |
| Background mechanism | Our own foreground service `ModelDownloadService`, FGS type `dataSync`, cloned from `VideoExportService` | Same proven pattern (companion `StateFlow`, notification with actions, survives Activity death), no new 1.5 MB dependency, immediate start, fine-grained progress, natural pause/resume. WorkManager rejected: see §1.6 |
| HTTP | `HttpURLConnection`, HTTPS only, manual redirect loop, `Range` resume, `.part` files, streaming SHA-256 | GET + Range is all we need; Android's `HttpURLConnection` is OkHttp-backed internally and does TLS 1.3 |
| Hosting | GitHub Releases of the public repo. `BuildConfig.MODEL_BASE_URL` = `https://github.com/palayax/palaya-chess/releases/download/` (**provisional**, repo does not exist yet). Binaries on immutable tags `models-YYYY.MM[.n]`; manifest on a rolling tag `models` | One constant, immutable binaries, a manifest that can be re-pointed without touching the app |
| First-run trust | Pinned at build time from `MODELS.lock` (size, full SHA-256, net arch hash, release tag). The remote manifest is never consulted on first run | A wrong or hostile manifest cannot affect a fresh install |
| Upgrade trust | HTTPS + per-file SHA-256 from `models.json` + structural validation + **ECDSA P-256 signature on the manifest** (`SHA256withECDSA`, available on API 26) | A crafted model file is parsed by native code (Stockfish, onnxruntime); that is a code-execution class risk, and the signature costs ~40 lines. Ed25519 is not needed |
| Net compatibility | A net is offered/installed only if its NNUE header `version` and `archHash` equal the ones compiled into this app (read from the pinned net at pin time) and its name/hash scheme holds | A net the compiled engine cannot parse makes Stockfish `exit()`; the engine cannot be used as a validator. **In practice net upgrades ride with app updates**; this rule keeps the rare same-architecture net safe |
| Activation | Download + verify + validate fully, then journaled swap, trial, and rollback on the next launch if the trial crashed the process | The only way to survive an `exit()` during the first load of a new net |
| Eval cache | Keyed by net: `eval_cache/<net 12-hex>/<key>.json`; other nets' directories purged on activation | A report must come from one net |
| Narration cache | `NeuralTtsProvider.cacheFingerprint` gains the installed voice id; `NarrationStore.clear()` on voice activation | WAVs from the old voice can never be hit again |
| Voice archive format | ~~Keep the plain `.tar` (158.3 MB) for v1~~ **D2f: `.tar.gz` (102.5 MB)**, inflated as a stream while unpacking (0.9-1.3 s on chess36); the TAR's hash stays the marker | Measured in D2f: saves 55.7 MB per user, under the 30 MB / 20 s bar; still resumable (the download), the extraction is local |
| Permissions | Add `INTERNET` and `ACCESS_NETWORK_STATE`; nothing else | The metered/available check needs `ConnectivityManager` |

## 1. UX

### 1.1 Where the Setup screen sits

- New route `Destination.Setup` (`setup?next={gameId}`). `ChessAnalyzerNavHost` picks the start destination:
  `Setup` when `modelSetup.needsNet()` (no verified net), else `Import`. A share/open intent while the net is
  missing registers the game as today (`registerPendingImport`) and navigates to `setup?next=<gameId>`; on
  completion the host navigates to `AnalysisProgress(gameId)` with `popUpTo(Setup) { inclusive = true }`.
- **Not now** (text button) pops to Home. Home then shows a persistent card above the start card (§1.4) until
  setup is complete. A pending game dropped this way is discarded with the snackbar "Finish setting up to review
  games." (one line; the user shares again). Keeping the text alive across a skipped setup is not worth a second
  state machine.
- Setup never auto-resumes. A `.part` left by a kill or a pause shows as "Paused at 43%" with **Resume**.

### 1.2 Layout (our visual language: dark chrome, `Scaffold`, `AppBarTitle`, 16 dp gutters, 48 dp targets)

```
┌──────────────────────────────┐
│ Set up Palaya Chess          │  AppBarTitle (heading)
├──────────────────────────────┤
│          [knight icon]       │  KnightIcon from ImportScreen, 56 dp
│  Review games on your phone  │  titleLarge, heading
│  Palaya Chess needs two      │  bodyMedium, onSurfaceVariant, Content direction
│  files before the first      │
│  review: the chess engine's  │
│  data (about 100 MB) and the │
│  narration voice (about      │
│  160 MB). One-time download, │
│  about 260 MB. Wi-Fi         │
│  recommended.                │
│                              │
│ [  Download (about 260 MB) ] │  filled Button, fillMaxWidth
│          Not now             │  TextButton
└──────────────────────────────┘
```

While downloading the body is replaced by:

```
│ Downloading… 120 MB of 260 MB│  bodyMedium, polite live region
│ ▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬░░░░░░░░░░░   │  overall LinearProgressIndicator 6 dp, stateDescription = the line above
│                              │
│ Chess engine data        ✓   │  per-file row: label + "done" check (or "42 MB of 99 MB")
│ Narration voice  21 MB of 158│
│ ▬▬▬░░░░░░░░░░░░░░░░░░░░░░░   │  per-file bar, 4 dp
│                              │
│ [ Pause ]       [ Cancel ]   │  OutlinedButtons in a FlowRow (wrap at font scale 1.3+)
```

Statuses on the first line, in order: "Connecting…", "Downloading… X of Y", "Retrying… (2 of 5)",
"Checking the file…", "Unpacking the voice…", "Paused. Your progress is saved.", "All set". On "All set" the
buttons become one filled **Continue** (to Home or to the pending game). Sizes are rounded MB (`formatStorageMegabytes`
already exists in `SettingsScreen.kt`; move it to `ui/model/SetupLogic.kt`).

Conventions from `CLAUDE.md` apply: every title is a heading, bars carry `stateDescription`, the status line is a
polite live region, errors are assertive, no `maxLines`, landscape = the same scrolling column (no picture/controls
split; there is no picture). Edge-to-edge: `Scaffold` inner padding like `AnalysisProgressScreen`, so the API 35
enforcement D1 brings changes nothing.

### 1.3 States and copy (our words; every string in `strings.xml`, prefix `setup_`)

| State | What the user sees | Action |
|---|---|---|
| Idle (first open) | the screen above | Download / Not now |
| Metered network at tap | Dialog "You're on mobile data" / "This download is about 260 MB. Download now, or wait until you're on Wi-Fi." | **Download anyway** / **Not now** |
| No network at tap | Inline error (assertive live region, error colour): "No internet connection. Connect to Wi-Fi and try again." | **Try again** |
| Low storage at tap or ENOSPC mid-way | "Palaya Chess needs about 450 MB of free space to set up (260 MB once done). Free up some space and try again." | Try again |
| Downloading | progress block | Pause / Cancel |
| Paused (user, kill, or retries exhausted) | "Paused. Your progress is saved." or "The connection dropped. Your progress is saved — resume when you're back online." | **Resume** / Cancel |
| Server 5xx after retries | "The download server isn't answering right now. Try again in a few minutes." | Try again |
| 404/410 after 3 attempts | "The engine files aren't on the server. Update Palaya Chess, or try again later." | Try again |
| Hash mismatch, twice | "The download didn't match the expected file, twice in a row. Check your connection and try again." | Try again |
| Done | "All set. Palaya Chess works offline from now on." | **Continue** |

Cancel deletes every `.part` and returns to Idle (or to "net installed, voice missing", in which case the screen
reads "Chess engine data ✓ · Narration voice: about 160 MB left" and the button is "Download the voice (about 160 MB)").

Why 450 MB: net 98.5 MB + voice tar `.part` 158.3 MB + extracted voice 158 MB coexist at the peak, plus the 32 MB
margin `FirstRunSetup.SAFETY_MARGIN_BYTES` already uses; the tar is deleted after extraction, so 260 MB remain.

### 1.4 Elsewhere in the app while setup is incomplete

- **Home**: a card above the start card. Title "Finish setting up", body "About 160 MB left to download." (or
  "About 260 MB"), filled button **Continue**. Hidden once complete.
- **Analysing**: `AnalysisService` returns a new `Failure.SETUP_REQUIRED` when the net is missing; the existing
  inline error state shows "Finish setting up first." with button **Set up** (navigates to `setup?next=<gameId>`)
  instead of Try again. `AnalysisPhase.FIRST_RUN_SETUP` and `progress_first_run_setup` are deleted; nothing is copied
  during an analysis any more.
- **Video**: when the voice is not installed and the provider resolves to Device, a one-line notice under the
  player: "Narrated with the phone's voice until setup is finished." with a text button **Finish setup**. The
  existing fallback already makes this work; the notice only explains it.
- Settings, About, Board, Practise, Walkthrough: unchanged.

### 1.5 Leaving the screen or the app

`ModelDownloadService` keeps downloading. Notification (channel `model_download_progress`, `IMPORTANCE_LOW`,
silent, ongoing, `FOREGROUND_SERVICE_IMMEDIATE`): title "Setting up Palaya Chess", text "120 MB of 260 MB",
determinate progress, actions **Pause** and **Cancel**, tap opens `MainActivity` with extra `open_setup=true`
(nav host navigates to `Setup`). On completion a `model_download_done` notification "Palaya Chess is ready"
(or "Setup paused" / "Setup cancelled"). POST_NOTIFICATIONS is requested once when the first download starts and
is never a gate, exactly as for the export. On targetSdk 35 `Service.onTimeout(startId, type)` (dataSync 6 h/day
cap) pauses the download; 260 MB never gets near it.

### 1.6 Why not WorkManager

WorkManager would give process-death survival and constraint-based scheduling, which we deliberately do not want
(no automatic resume, no "when Wi-Fi comes back" network use without a tap). Its `setForeground` needs the
`SystemForegroundService` type merged into the manifest, its progress is coarse, pause = cancel + re-enqueue, and
it adds Room and SQLite to a 108 MB APK that D1 is about to minify. The export service already proved the own-FGS
pattern on-device (RUN_LOG R6/R7). Reuse it.

### 1.7 References used (owner rule)

- One primary action per onboarding screen with a secondary text link (chess.com welcome/onboarding, S15 in
  `docs/CHESSCOM_REFERENCE_ALIGNMENT.md`); the gated-feature card that says what you get and has one button
  (Game Review on a free account, S1) → our Setup screen and the Home "Finish setting up" card.
- chess.com's app shows a size and a progress bar when it fetches an engine/bot on demand **[memory]**; it has no
  pause/resume, metered or update-check pattern, so for those: Android's own Settings › System › System update
  ("Check for update" button, "Last checked" line) for §1.8; Google Play's download pattern (size stated before the
  tap, Wi-Fi preference, pause/cancel in the notification) and `DownloadManager`'s notification actions for §1.5;
  Material 3 progress indicators (determinate linear with a text label) for the bars.

### 1.8 Settings: Check for updates

A fifth visible row in `SettingsScreen` below About (the owner asked for Settings; Android's own manual update check
lives in Settings too): title "Check for updates", supporting "Engine data and voice. Last checked: never". Tapping
runs the check inline; the supporting text becomes "Checking…", then one of: "You're up to date." /
"No internet connection." / "The update server isn't answering right now." / "Update available". When something is
available a bottom sheet lists it: "Narration voice v1.0 · about 170 MB" with **Download and install** (metered
dialog applies) and the same progress block as Setup; on success "Installed." For an engine net: "Installed. Games
you reopen will be re-analysed with the new engine data." If the trial failed and rolled back on relaunch:
"The update couldn't be used, so the previous version was restored." (shown once, from a flag in the journal).
The button is disabled, with "Finish or cancel the current analysis first" / "…video export first", while
`AnalysisViewModel.analysisRunning` or `VideoExportService.running` is true. `last checked` is one `long` in the
general settings DataStore (`models_last_update_check_ms`).

### 1.8b Upstream versions in the update sheet (A4)

The same tap also asks the original projects what they have released, and the sheet shows one row per component under
the signed result: a check mark and "Up to date", an arrow and "Newer upstream version X available — comes with an app
update", an "Upstream has re-published this file" note, or "Couldn't check: …" (no internet / GitHub is limiting
requests / GitHub isn't answering / its answer couldn't be read). Information only: nothing upstream is downloaded and
engine code never can be (Play, §3b of `docs/PUBLISHING.md`), so a newer upstream version always "comes with an app
update". The rows are a list of sources (`UpstreamSources.defaults`), so an LLM row is one more source and one name string.

| Row | Request (GitHub REST, unauthenticated) | Ours | Newer when |
|---|---|---|---|
| Stockfish | `GET repos/official-stockfish/Stockfish/releases/latest` (`tag_name`, e.g. `sf_19`) | `BuildConfig.STOCKFISH_TAG`, read by the build from `vendor/STOCKFISH_VERSION.txt` | the tag's number is higher |
| sherpa-onnx | `GET repos/k2-fsa/sherpa-onnx/releases/latest` (`tag_name`, e.g. `v1.13.8`) | `BuildConfig.SHERPA_ONNX_VERSION` (the version catalog) | higher semver |
| Kokoro voice | `GET repos/k2-fsa/sherpa-onnx/releases/tags/tts-models` (the asset list `scripts/fetch_models.sh` takes the voice from) | the tar's name (`kokoro-int8-en-v0_19` = v0.19) and the pinned archive (`VOICE_UPSTREAM_ARCHIVE_*`, from `MODELS.lock`) | a `kokoro-int8-*-vX_Y` asset has a higher X.Y; or "re-published" when our archive is gone or its size / SHA-256 differ |

Rules: only on the tap, next to the signed check and never in its way (its own coroutine and state flow in `ModelUpdates`,
so a rate limit, an outage or garbage upstream only changes that row); every request through `ModelDownloader.fetchSmall`
(https only, redirects checked, size-capped, one attempt), by a second `ModelDownloader` with shorter timeouts (8 s / 12 s)
and the same User-Agent; no key, cookie or identifier. The release lists are 0.5 to 1 MB of JSON, so these three ask for
gzip (`fetchSmall(..., acceptGzip = true)`: the cap applies to the INFLATED size, a gzip bomb is refused): about 36 KB +
73 KB + 5 KB on the wire. GitHub's anonymous limit is 60 requests an hour per address; a check costs 3 of them (403 / 429
become `SmallFetchFailure.RATE_LIMITED`, shown as a calm per-row line). Hugging Face is not asked: the pinned source is the
k2-fsa release. Tests: `UpstreamVersionsTest`, `UpstreamCheckerTest`, `UpdateUpstreamIsolationTest` (host, `FaultHttpServer`),
`UpdateCheckUpstreamTest` (device); the base URL is injectable (`ChessAnalyzerApplication.upstreamCheckerForTesting`).

## 2. Download engine

### 2.1 Pieces

| Class | Module / file | Role |
|---|---|---|
| `NetStore` | `:engine`, `engine/.../NetStore.kt` (from `BundledNetProvider.kt`, asset copy removed) | `filesDir/nets/`: `activeNetOrNull()`, `verifiedNetOrNull()` (prefix hash check with the `verifiedStamp` cache), `installVerified(part, name)` (atomic move), `readHeader(file)`, `migrateLegacy(filesDir)` (renames `filesDir/nn-…nnue` into `nets/` once), `PART_SUFFIX = ".part"` |
| `VoiceStore` | `:app`, `video/VoiceStore.kt` (from `BundledVoiceInstaller.kt`) | `tts_models/kokoro/`, `installFromTar(tar, expectedSha, onProgress)` = existing `extractTar` + zip-slip guard + `stripTopLevelDir` + `hasRequiredFiles` + `kokoro.extracting/` swap + `.provisioned` marker (still holds the tar SHA-256); `installedVersionId()` = marker hash, 12 hex; `trialSynthesis(dir)` (§4.2) |
| `ModelDownloader` | `:app`, `data/models/ModelDownloader.kt` | one file: open, redirect, range, stream, hash, size check |
| `DownloadStateMachine` | `data/models/DownloadState.kt`, pure | states and transitions (§2.3), host-tested |
| `ModelSetup` | `data/models/ModelSetup.kt` (replaces `FirstRunSetup`) | `needsNet()`, `needsVoice()`, `state(): SetupState`, `bytesLeft()`, `storageNeeded()`, runs the two downloads in order and hands them to the stores |
| `ModelDownloadService` | `data/models/ModelDownloadService.kt` | FGS; companion `state: StateFlow<SetupProgress>`, `start(context, job)`, `pause()`, `cancel()`; jobs: `Setup`, `Update(entry)` |
| `NetworkStatus` | `data/models/NetworkStatus.kt` | `current(): UNAVAILABLE / METERED / UNMETERED` — the Round 11 `ConnectivityNetworkCostProbe` logic (`git show bbeb931:app/src/main/kotlin/net/palaya/chessanalyzer/video/AutoVoicePolicy.kt`) brought back verbatim, fail-closed to METERED |
| `GeneratedModelPins` | `:app` and `:engine`, generated (§3.1) | sizes, SHA-256s, arch hash, release tag |

`EngineController.ensureReady()` calls `netStore.verifiedNetOrNull() ?: throw NetNotInstalledException()`; the
`setEvalFile`/`analyze` guards in `StockfishEngine` are untouched. `ChessAnalyzerApplication` exposes `netStore`,
`voiceStore`, `modelSetup`; `TestApp` follows.

### 2.2 One file, step by step (`ModelDownloader.download(spec, partFile, onProgress)`)

1. `require(spec.url.startsWith("https://"))` unless `BuildConfig.DEBUG && spec.url.startsWith("http://10.0.2.2")`
   or `http://127.0.0.1` (test servers, §6.3). Sizes above 1 GB are refused before any request.
2. If `partFile` exists and `length < spec.size`: hash the existing prefix into the `MessageDigest` (≈0.5 s per
   100 MB, estimate) and set `offset = length`; if `length >= spec.size`: delete it, `offset = 0`.
3. Request loop, `instanceFollowRedirects = false`, at most 5 hops, each hop must be `https://` (or the debug
   exception); headers `User-Agent: PalayaChess/<versionName> (Android <SDK_INT>)`, `Accept-Encoding: identity`,
   `Range: bytes=<offset>-` when `offset > 0`. `connectTimeout 15 s`, `readTimeout 30 s`. GitHub answers 302 to
   `objects.githubusercontent.com` (or whatever CDN host it uses: no host allowlist, the https rule is the guard)
   with a short-lived signed URL: **resolve the redirect afresh on every attempt and never log the full redirect
   URL** (host only).
4. Response rules: `200` with `offset > 0` → the server ignored the range: truncate the part to 0, reset the digest,
   continue from 0. `206` → `Content-Range` total must equal `spec.size`. `200` → `Content-Length`, if present, must
   equal `spec.size`. `416` → delete the part, restart from 0. `404/410` → `Fatal(NotFound)`. `429/503` with
   `Retry-After` ≤ 60 s → wait that, else transient. Other `5xx` → transient. Other `4xx` → `Fatal(Server)`.
   Size mismatch → `Fatal(SizeMismatch)` (treated like a hash failure: the pin is wrong or the server lies).
5. Stream in 256 KB chunks through the digest, `coroutineContext.ensureActive()` per chunk, `onProgress(bytes)`
   per chunk (the UI throttles to 10 Hz, the notification to 500 ms as the export does). `fd.sync()` on close.
6. `IOException` mid-stream → transient: keep the part, back off 2, 4, 8, 16, 30 s (5 attempts per session, the
   counter resets on user Resume), re-resolve the redirect, resume with the new offset. After 5 → `Paused(ConnectionLost)`.
7. At EOF: bytes must equal `spec.size` and the digest must equal `spec.sha256` (full 64 hex; for the net also
   the 12-hex filename prefix). Mismatch → delete the part, `HashFailed`; `ModelSetup` restarts that file once
   automatically, the second mismatch is shown (§1.3).
8. Hand the verified part to the store: `NetStore.installVerified()` = `Files.move(ATOMIC_MOVE)` into
   `nets/<name>`; `VoiceStore.installFromTar()` extracts from the part (progress "Unpacking the voice…", weighted
   as 10% of the voice step), verifies `hasRequiredFiles`, swaps the directory, writes the marker, deletes the part.
   Both tails run under `withContext(NonCancellable)` as today.

Order: net first, then voice. Storage is checked before each file (`usableSpace` ≥ that file's peak + 32 MB) and
once up front on the Setup screen for the sum. `ENOSPC` anywhere maps to `InsufficientStorage` via the existing
`FirstRunSetup.isOutOfSpace`.

Backup: `backup_rules.xml` / `data_extraction_rules.xml` gain `nets/` and `models/` (the journal, §4.1) and keep
the two legacy literals for one release; `tts_models/` already covers the voice part file (`tts_models/kokoro.tar.part`).
`BackupRulesTest.mustExclude` is updated accordingly.

### 2.3 State machine (`DownloadStateMachine`, pure, one per file)

```
Idle ──start──> Connecting ──206/200──> Streaming ──eof, hash ok──> Verified ──install ok──> Installed
  ^                │  │                   │   │                          │
  │                │  └─transient────> Backoff(n) ──n<5, delay──> Connecting
  │                │                      └─n==5──> Paused(ConnectionLost)
  │                └─fatal (404/4xx/size)──> Failed(NotFound | Server | SizeMismatch)
  │                                         │
  │              Streaming ──user pause──> Paused(User) ──resume──> Connecting
  │              Streaming ──eof, hash bad──> HashFailed ──auto once──> Connecting ; twice ──> Failed(Damaged)
  └──cancel from any non-terminal state (deletes .part)
```

`SetupProgress(overallFraction, bytesDone, bytesTotal, perFile: List<FileProgress>, status, error)` is what the
service publishes and what the screen, the Home card and the notification render.

## 3. Pins and manifest

### 3.1 Build-time pins (first run)

`vendor/models/MODELS.lock` gains:

```
net.sha256=<64 hex>          # full hash; the name still encodes its first 12 hex
net.arch_hash=<8 hex>        # bytes 4..7 of the file, little-endian uint32: Stockfish's architecture hash
net.version=0x7af32f20       # bytes 0..3: NNUE file version
release.tag=models-2026.10   # GitHub release that holds both files
```

`scripts/fetch_models.sh` writes `net.sha256`, `net.arch_hash` and `net.version` on its first successful run
(like it pins the tar hash today). A new Gradle task **`generateModelPins`** in `:engine` (net) and `:app` (voice, tag)
replaces `verifyBundledModels`: it reads MODELS.lock and `evaluate.h`, checks consistency (the net name's 12 hex is
a prefix of `net.sha256`; all sizes and hashes well-formed; `release.tag` matches `models-\d{4}\.\d{2}(\.\d+)?`),
and writes `GeneratedNetPins.kt` / `GeneratedModelPins.kt`. **It does not need the model files**, so a clean clone
builds without `fetch_models.sh`. If `vendor/models/` files are present it also verifies them against the lock
(developer safety, not a build input). `assets.srcDir(...)` lines go; `noCompress` lines go.

`BuildConfig` fields in `:app`: `MODEL_BASE_URL` (default above, marked provisional in a comment and in About's
source line), `SHERPA_ONNX_VERSION` (from `libs.versions.toml`, read by Gradle), `MODEL_MANIFEST_URL =
MODEL_BASE_URL + "models/models.json"`. First-run URL = `MODEL_BASE_URL + release.tag + "/" + fileName`. A Gradle
property `-PpalayaModelBaseUrl=http://127.0.0.1:8787/` (emulator: with `adb reverse`, R8) overrides the base URL **for debug builds only**; the release
build fails configuration if it is set.

### 3.2 `models.json` (upgrades only)

```json
{
  "schemaVersion": 1,
  "generatedAt": "2026-10-06T12:00:00Z",
  "models": [
    {
      "id": "engine-net",
      "displayName": "Chess engine data",
      "version": "nn-1a298aa575a0",
      "fileName": "nn-1a298aa575a0.nnue",
      "url": "https://github.com/palayax/palaya-chess/releases/download/models-2026.10/nn-1a298aa575a0.nnue",
      "size": 98511183,
      "sha256": "…64 hex…",
      "minVersionCode": 2,
      "maxVersionCode": null,
      "compat": { "kind": "stockfish-nnue", "version": "0x7af32f20", "archHash": "…8 hex…", "engineTag": "sf_19" }
    },
    {
      "id": "voice-kokoro-en",
      "displayName": "Narration voice",
      "version": "v0_19",
      "fileName": "kokoro-int8-en-v0_19.tar",
      "url": "https://github.com/palayax/palaya-chess/releases/download/models-2026.10/kokoro-int8-en-v0_19.tar",
      "size": 158269440,
      "sha256": "7190c4801645bf31d10996477a04082019d9cf492ad3d5aeef7b1f7cf10a5dea",
      "minVersionCode": 2,
      "maxVersionCode": null,
      "compat": { "kind": "sherpa-onnx-kokoro", "layout": "kokoro-v0_19" },
      "runtime": { "name": "sherpa-onnx", "min": "1.13.8", "max": "1.13.8" }
    }
  ]
}
```

URL: `https://github.com/palayax/palaya-chess/releases/download/models/models.json` plus `models.json.sig`
(rolling release tagged `models`, assets replaced with `gh release upload --clobber`). Not `latest/download/`:
"latest" would flip to every app release and force every app release to carry a manifest. Both files are fetched
with `ModelDownloader` (64 KB cap, no `.part`), only on the tap.

Parsing: `org.json` (already used by `GameRepository`), in `data/models/ModelManifest.kt`. Host tests need
`testImplementation("org.json:json:20240303")` because the Android stub throws on the host. Unknown `id`s and
unknown fields are ignored; a missing required field rejects the whole manifest.

### 3.3 Compatibility rules (`ModelCompatibility.evaluate(entry, AppFacts)`, pure)

`AppFacts(versionCode, netArchHash, netVersion, sherpaOnnxVersion, voiceLayout, baseUrl, installedNetPrefix, installedVoiceSha)`.

- `minVersionCode <= versionCode <= (maxVersionCode ?: Int.MAX)`.
- `url` starts with `baseUrl` and with `https://` (defence in depth under the signature).
- `engine-net`: `compat.kind == "stockfish-nnue"`, `compat.version == netVersion`, `compat.archHash == netArchHash`,
  `fileName` matches `nn-([0-9a-f]{12})\.nnue` and `sha256.startsWith(that group)`, `50 MB <= size <= 400 MB`.
- `voice-kokoro-en`: `compat.kind == "sherpa-onnx-kokoro"`, `compat.layout == VoiceStore.LAYOUT` (`"kokoro-v0_19"`),
  `runtime.min <= sherpaOnnxVersion <= runtime.max` (numeric dotted compare), `20 MB <= size <= 600 MB`.
- Offered iff compatible **and** not already installed (`sha256` prefix ≠ `installedNetPrefix`; `sha256` ≠ marker).
  "Newer" is the publisher's call; the app does not order versions.

Honest consequence: Stockfish changes the architecture hash most releases, so an engine-net entry will usually fail
`archHash` on the current app and pass only after the app update that carries the new engine. Net upgrades are
therefore app updates in practice; the manifest path exists for the same-architecture retrain and for re-pointing
a broken asset.

### 3.4 Trust model

- **HTTPS** protects the wire. **SHA-256 from the manifest** protects the bytes against the CDN. **Structural
  validation** (§4.2) protects the native parsers against accidents. **The signature** protects against a
  compromised GitHub account or a MITM with a bad CA, which could otherwise serve a crafted net or ONNX model whose
  hash matches its own manifest: both are parsed by native code, so that is potential code execution.
- Signature: `models.json.sig` = DER ECDSA signature over the exact bytes of `models.json`, verified with
  `Signature.getInstance("SHA256withECDSA")` and a P-256 public key compiled in (`vendor/models/manifest_public_key.der`,
  committed, generated into `GeneratedModelPins.MANIFEST_PUBLIC_KEY_DER`). Private key `keystore/models-signing.pem`
  (gitignored with the release keystore; add `*.pem` to `.gitignore`). Both `java.security` calls exist on API 26;
  no Ed25519, no BouncyCastle. A missing or bad signature = "The update server isn't answering right now" plus a
  logged `ManifestRejected(signature)`; nothing is downloaded.
- Without the signature a compromised manifest could: point at any https URL (a size cap and the base-URL prefix
  limit that), make the app download a crafted model and, if structural checks pass, feed it to Stockfish or
  onnxruntime; nag with a perpetual "update available". It could never: install code, change the engine, affect
  a fresh install, or make the app download anything without the user's tap. With the signature it can do nothing
  the key holder did not sign. Verdict: sign it; it is cheaper than the privacy-policy paragraph.

## 4. Activation and rollback

### 4.1 Journal

`filesDir/models/activation.json`: `{"model":"engine-net","phase":"swapped|trial","new":"nn-….nnue","old":"nn-….nnue","at":<ms>}`.
`ModelActivator.recoverOnStartup()` runs first thing in `ChessAnalyzerApplication.onCreate` (synchronously, it is a
file read): a journal in phase `swapped` or `trial` means the process died between swap and a successful trial
(most likely Stockfish's `exit()`), so the new file is deleted, the old one is reinstated, the journal is replaced by
`{"rolledBack":"engine-net"}` for the Settings message, and only then is the engine ever touched.

### 4.2 Engine net

1. Download to `nets/<new>.part`, verify size + SHA-256 (§2.2).
2. Structural validation in Kotlin before anything else: `NetStore.readHeader()` checks bytes 0..3 == pinned
   `net.version`, bytes 4..7 == pinned `net.arch_hash`, description length (bytes 8..11) < 1 KB, file length ≥
   `StockfishEngine.MIN_PLAUSIBLE_NET_BYTES`. The engine is never used to "test-load" a net: a failed load is `exit()`.
3. Preconditions: no analysis running (`EngineController.analysisInFlight`), one engine per process holds. Journal
   `swapped`; `installVerified()` renames the part to `nets/<new>`; the old file stays.
4. Trial: journal `trial`; `EngineController.switchNet(newFile)` under `prepMutex`: `engine.setEvalFile(path)`
   (same guards), `newGame()`, `setPosition(startpos)`, `analyze(depth = 1)`; `evalFilePath` now points at the new
   file. If the engine was not started yet, the trial is `ensureReady()` + the depth-1 search.
5. Success: delete `nets/<old>`, clear the journal, `GameRepository.purgeEvalCachesExcept(newPrefix)`, publish
   "Installed." A crash anywhere in 3–4 is caught by §4.1 on the next launch.

`GameRepository.cacheKey(pgnText, depth, multiPv)` is unchanged, but the file lives at
`eval_cache/<netPrefix>/<key>.json` (and `…/<key>.partial.json`), with `netPrefix` from `NetStore.activeNetOrNull()`
passed into the repository. Existing cache files move into `eval_cache/1a298aa575a0/` by the same `migrateLegacy` pass.

### 4.3 Voice

1. Download to `tts_models/kokoro.tar.part`, verify.
2. Extract into `tts_models/kokoro.extracting/` (existing logic), `hasRequiredFiles` and `espeak-ng-data/` present.
3. Trial in the scratch directory: `VoiceStore.trialSynthesis(dir)` = a throwaway `NeuralTtsProvider` on that
   directory, `prepare()` must return true with `speakerCount ≥ 1` and `modelSampleRate > 0`, then synthesize one
   fixed sentence to a temp WAV: duration > 300 ms and RMS above the silence floor the Round 6 evidence tests
   already use. sherpa-onnx and onnxruntime throw on load errors rather than exiting, so this is a real validator
   (the project's "valid-but-silent WAV" history is why the RMS check is not optional). Precondition: no export
   running (`VideoExportService.running`).
4. Journal `swapped`; rename `kokoro/` → `kokoro.previous/`, `kokoro.extracting/` → `kokoro/`, write the marker
   (new tar hash), clear the journal, delete `kokoro.previous/`, delete the part, `NarrationStore.clear()`.
   Recovery: journal present with `kokoro.previous/` → move it back. Per-screen `NeuralTtsProvider` instances are
   short-lived (`buildNarrationProvider()` makes a new one per Video screen), so nothing global reloads.

`NeuralTtsProvider.cacheFingerprint` becomes `"${tier.name}@${voiceVersionId}/sid$speakerId/ls…"` with
`voiceVersionId = VoiceStore.installedVersionId()` injected at construction, so the key can never collide across voices.

## 5. Build changes

| Change | File(s) |
|---|---|
| Remove `assets.srcDir(engineAssetsDir)` / `appAssetsDir`, `noCompress`, `installation.timeOutInMs` can drop to the default | `engine/build.gradle.kts`, `app/build.gradle.kts` |
| `verifyBundledModels` → `generateModelPins` (§3.1), outputs `GeneratedNetPins.kt` (`:engine`: `NET_SIZE_BYTES`, `NET_SHA256`, `NET_ARCH_HASH`, `NET_VERSION`) and `GeneratedModelPins.kt` (`:app`: voice size/hash/name, `RELEASE_TAG`, `MANIFEST_PUBLIC_KEY_DER`) | same |
| `buildConfigField` `MODEL_BASE_URL`, `MODEL_MANIFEST_URL`, `SHERPA_ONNX_VERSION`; debug-only `-PpalayaModelBaseUrl` override | `app/build.gradle.kts` |
| `INTERNET`, `ACCESS_NETWORK_STATE`; `ModelDownloadService` with `foregroundServiceType="dataSync"` | `app/src/main/AndroidManifest.xml` |
| Debug-only `android:networkSecurityConfig` (§6.3) | `app/src/debug/AndroidManifest.xml`, `app/src/debug/res/xml/network_security_config.xml` |
| androidTest-only assets for seeding (§6.2) | `android.sourceSets["androidTest"].assets.srcDir(...)` in both modules |
| `app/src/sharedTest/kotlin` added to both `test` and `androidTest` source sets | `app/build.gradle.kts` |
| `scripts/fetch_models.sh`: also pins `net.sha256`, `net.arch_hash`, `net.version`; stays the developer/test fetcher | `scripts/fetch_models.sh` |
| **`scripts/publish_models.sh <tag>`**: verifies `vendor/models/` against the lock, writes `dist/models/models.json` from the lock plus `--min-version-code N`, signs it (`openssl dgst -sha256 -sign keystore/models-signing.pem -out models.json.sig models.json`), then `gh release create <tag> --title "Model files <tag>" --notes "…" <net> <tar>` and `gh release upload models --clobber models.json models.json.sig` (creates the `models` release on first use). Documents the one-time `openssl ecparam -genkey -name prime256v1 -noout -out keystore/models-signing.pem` and `openssl ec -pubout -outform DER` steps | new |
| `scripts/model_test_server.py` (§6.2) | new |
| `.gitignore`: `*.pem` | `.gitignore` |
| `proguard-rules.pro`: nothing new (platform HTTP, `org.json`, `java.security`; no reflection). D1's keep rules for sherpa-onnx JNI are unaffected | — |

**Size expectation (estimate from measured parts):** universal release APK ≈ 364.7 − 98.5 − 158.3 ≈ **108 MB**
(≈ 89 MB sherpa-onnx native libs for three ABIs + Stockfish + dex + resources), less once D1's R8 lands. A Play AAB
delivers one ABI: arm64 download ≈ **45–55 MB** (Round 5 measured ≈ 51 MB for the arm64 slice of the 108 MB build),
comfortably under Play's 200 MB limit with no asset packs. First run then downloads 257 MB. Debug APK ≈ 115 MB;
`connectedDebugAndroidTest` pushes about the same total as today because the test APK now carries the seed assets.

## 6. Test plan

### 6.1 Host unit tests (`app/src/test`, `engine/src/test`)

- `DownloadStateMachineTest`: every edge of §2.3, including backoff counts, the auto-restart after one hash failure,
  cancel from each state, and that `Paused` never deletes the part.
- `ModelDownloaderTest` against **`FaultHttpServer`** (`app/src/sharedTest/kotlin/.../FaultHttpServer.kt`, ~150 lines
  on `java.net.ServerSocket`, HTTP/1.1 GET only, runs on the JVM and on the device): faults `TruncateAfter(n)`,
  `CorruptByteAt(n)`, `Status(404|410|429|500|503, retryAfter)`, `Slow(bytesPerSecond)`, `DropAfter(n)` (closes the
  socket), `RedirectTo(otherServer, 302|307)`, `RedirectToHttp` (must be refused), `IgnoreRange`, `WrongTotalSize`,
  `TooManyRedirects`. Cases: clean download; resume after drop with correct hash (prefix re-hash); range ignored →
  restart from 0; 416 → restart; size mismatch → fatal; wrong hash → part deleted; redirect to a second host with
  the Range header re-sent; 5 transient failures → `Paused(ConnectionLost)`; cancel mid-stream leaves `.part` deleted.
- `ModelManifestTest` (parse, missing fields, unknown ids ignored), `ModelCompatibilityTest` (every rule in §3.3,
  boundary version codes, arch hash mismatch, runtime range), `ManifestSignatureTest` (a test key pair generated in
  the test; good / tampered byte / wrong key / missing sig).
- `NetHeaderTest` (`:engine`): synthetic 16-byte headers for version, arch hash and description length.
- `SetupLogicTest`: MB formatting, bytes-left, storage-needed arithmetic (450 / 260 / 160 MB cases).
- `NetworkCallSitesTest`: scans `app/src/main/kotlin` and `engine/src/main/kotlin` for `openConnection|HttpURLConnection|
  java.net.Socket|OkHttp` and asserts the only hits are in `data/models/ModelDownloader.kt`.
- `ManifestPermissionsTest`: expected set becomes `{INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE,
  FOREGROUND_SERVICE_DATA_SYNC, POST_NOTIFICATIONS}` (plus D1's `FOREGROUND_SERVICE_MEDIA_PROCESSING` once it lands);
  still no `usesCleartextTraffic`, and a new assertion: no `networkSecurityConfig` in the **main** manifest.
- `BackupRulesTest`: `nets/`, `models/`, legacy literals.

### 6.2 Instrumented tests (`:app`, `:engine`)

Seeding: the androidTest APKs carry the two model files as test-only assets (same `vendor/models/` source, so
`fetch_models.sh` stays required for tests). `TestApp.ensureSetUp()` installs them through `NetStore.installVerified`
and `VoiceStore.installFromTar` from the asset streams, which is the same tail the real download uses. `TestNet.kt`
in `:engine` does the same. No test depends on a host server, so the suite also runs on a physical device, and no
`assumeTrue` appears anywhere (CLAUDE.md).

- `ModelDownloaderInstrumentedTest`: `FaultHttpServer` **in-process on 127.0.0.1**, the same fault matrix as the host,
  plus the real 98.5 MB net served from the test asset end to end into `NetStore`.
- `SetupFlowInstrumentedTest`: fresh `filesDir`, `ModelDownloadService.start(Setup)` against the in-process server,
  Activity destroyed mid-download (`ActivityScenario`, as `VideoExportServiceInstrumentedTest` does), `dumpsys
  activity services` shows `isForeground=true types=00000001`, pause/resume, cancel, completion with both stores
  installed and no `.part` left.
- `ActivationRollbackInstrumentedTest`: write a `trial` journal with a fake "new" net, call `recoverOnStartup()`,
  assert the old net is active and the flag is set. Voice variant with `kokoro.previous/`.
- `VoiceStoreInstrumentedTest` (from `BundledVoiceInstallerInstrumentedTest`), `ModelSetupInstrumentedTest`
  (from `FirstRunSetupInstrumentedTest`), `NetStoreInstrumentedTest` (from `BundledNetProviderInstrumentedTest`).
- **`NoNetworkAfterSetupTest`**: after `ensureSetUp()`, install a recording `java.net.ProxySelector` (every
  `HttpURLConnection` consults it; OkHttp too) and run a full analysis, narration synthesis and an export; assert
  zero `select()` calls. Belt and braces: `TrafficStats.getUidTxBytes(uid)` delta is 0 when the API reports a value
  (it returns `UNSUPPORTED` on some devices; treat that as "not measured", never as pass). Then tap-equivalent
  `UpdateChecker.check()` and assert exactly two `select()` calls (manifest and signature).
- `NetworkSecurityConfigTest` (debug): `NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("10.0.2.2")`
  true, `…("example.com")` false.
- Replaced: `NoNetworkPermissionTest` becomes `NetworkPermissionTest`: the installed package requests exactly the set
  in §6.1, and a loopback socket now **succeeds** with `ECONNREFUSED` (the permission is there).

### 6.3 Cleartext, debug only

`app/src/debug/res/xml/network_security_config.xml`: `<base-config cleartextTrafficPermitted="false">` and one
`<domain-config cleartextTrafficPermitted="true">` listing `10.0.2.2`, `127.0.0.1`, `localhost`. Referenced only from
`app/src/debug/AndroidManifest.xml`; the main manifest has no such attribute (asserted), release builds get the
platform default (cleartext blocked) and `ModelDownloader`'s own https rule. Release checklist: `apkanalyzer manifest
print app-release.apk | grep -c networkSecurityConfig` prints 0.

### 6.4 Host fault server for the emulator run

`scripts/model_test_server.py --root vendor/models --port 8787 [--fault truncate:0.5|hash|404|500|slow:200k|drop:0.3|redirect|norange]`:
serves `<tag>/<file>` and `models/models.json` from the lock, supports `Range`, prints every request. The emulator
reaches it at `http://127.0.0.1:8787/` through `adb reverse tcp:8787 tcp:8787` via `-PpalayaModelBaseUrl` on a debug
build. (Not `http://10.0.2.2:8787/`: the emulator's user-mode network drops single bytes near the end of a long
response, so the file fails its SHA-256; found in D2c/V2, diagnosed in R8, see CLAUDE.md.) Used for the end-to-end run below
and for watching the real UI under faults; the automated suite does not depend on it.

### 6.5 End to end on the emulator (chess34, debug build pointed at the host server)

1. `adb uninstall`, install, launch: Setup screen is the first screen; sizes stated; "Not now" shows the Home card.
2. Share the Opera-game PGN: Setup opens with the game pending; Download; airplane mode toggled on mid-way → "The
   connection dropped…"; off → Resume; completes; the analysis starts by itself; screenshot each state (`docs/screenshots/d2_*`).
3. Airplane mode on, Wi-Fi and data off, `ping 8.8.8.8` fails. Reopen a recent game, Board, Practise, Walkthrough,
   export a narrated MP4: pull it, `ffprobe`, RMS as in R7. `adb logcat` filtered to the app's pid: no `ModelDownload`
   lines after "Setup complete"; `dumpsys netstats` uid delta 0.
4. Settings › Check for updates in airplane mode: "No internet connection." Network back, server returns a manifest
   with a new voice: sheet, install, "Installed."; with a tampered `.sig`: nothing downloaded.
5. Release APK (real `MODEL_BASE_URL`): the same run **once the repo and the `models-2026.10` release exist**; until
   then it cannot be done and must be listed as such in RUN_LOG, not approximated.

## 7. Policy and privacy

- **Google Play Device and Network Abuse:** apps may not download executable code (dex, JAR, `.so`) from outside
  Play; downloading data files is fine. The net and the voice are data parsed by code that ships in the installer;
  `libstockfish.so`, sherpa-onnx/onnxruntime and dex never move. Engine upgrades are app updates. Say this in the
  Play Console FGS declaration: foreground service type `dataSync`, use case "user-initiated download of files the
  app needs", with a short video of the Setup screen (Play asks for one). Android 15's dataSync timeout is handled (§1.5).
- **Data safety form:** collects user data: No. Shares: No. The app makes exactly these network requests, only on a
  user tap: the first-run downloads from GitHub, and "Check for updates" (manifest, signature, chosen files). GitHub
  sees the device's IP address as part of serving the request; the app stores or transmits nothing else, has no
  analytics, accounts or ads. Owner to confirm against the form's current wording that standard connection metadata
  the app does not collect needs no entry (this is the common reading; it is not a legal conclusion).
- **Privacy policy text (needed as a Play URL; put it on palaya.net and in About):** "Palaya Chess runs entirely on
  your phone. It connects to the internet only when you ask it to: once, to download the chess engine's data and the
  narration voice (about 260 MB) from GitHub, and whenever you tap Check for updates in Settings. GitHub receives your
  device's IP address to serve those files, as any download does, and handles it under GitHub's privacy statement.
  The app sends nothing else, collects no personal data, has no account, no analytics and no advertising. Your games,
  analyses and settings stay on your phone."
- **`docs/PUBLISHING.md` facts to change later:** §3 sizes (108 MB APK, ≈ 50 MB Play download, 257 MB first-run
  download, no asset packs needed), the AAB paragraph (now straightforward), §5 Data safety ("Network use: none" is
  no longer true; replace with the paragraph above), §6 checklist (publish `models-2026.10` and the `models`
  release **before** the app that points at them; back up `models-signing.pem` with the keystore; privacy policy URL),
  §7 "model ships inside the APK" wording, the pre-launch item "state the 365 MB size". `docs/STORE_LISTING.md`
  lines 49–86 (no-network and 365 MB claims) and `README.md:12,73-92`, `CLAUDE.md` gotchas 4 and "Nothing to seed",
  `HANDOFF.md` FINAL STATE.

## 8. Migration from the bundled builds

The R7 release (versionCode 1) has `filesDir/nn-1a298aa575a0.nnue` and `tts_models/kokoro/.provisioned` holding
`7190c480…`. On the first launch of the download build:

- `NetStore.migrateLegacy()` renames `filesDir/nn-*.nnue` into `nets/` (same filesystem, atomic) and moves
  `eval_cache/*.json` into `eval_cache/1a298aa575a0/`; `verifiedNetOrNull()` re-hashes it once (as today) and
  accepts it; the marker check in `VoiceStore.isInstalled()` is byte-for-byte the same as `BundledVoiceInstaller`'s, so
  the voice is accepted. `needsNet()` and `needsVoice()` are false: no Setup screen, no download, nothing on the wire.
- A phone killed mid-voice on the old build (net present, no marker) lands on "net installed, voice missing": the
  Home card says "About 160 MB left" and only the voice is fetched.
- Verified by `MigrationInstrumentedTest`-equivalent manual step in D2f: install `dist/PalayaChess-1.0-release.apk`,
  run one analysis, `adb install -r` the new release (same certificate), launch under the `NoNetworkAfterSetupTest`
  conditions: Home opens directly, logcat shows the `migrateLegacy` line and no `ModelDownload` line.

## 9. Work breakdown (one Gradle invocation at a time; D1 lands first)

| Task | Scope | Files | Done when |
|---|---|---|---|
| **D2a** Pins and build (no device) | MODELS.lock fields; `fetch_models.sh` pins them; `generateModelPins` in both modules replacing `verifyBundledModels`; assets and `noCompress` removed; BuildConfig fields and the debug-only override; `publish_models.sh`, `model_test_server.py`; `.gitignore` | `vendor/models/MODELS.lock`, `scripts/*`, `engine/build.gradle.kts`, `app/build.gradle.kts`, `.gitignore` | `assembleDebug` exits 0 from a tree **without** `vendor/models/` files; `unzip -l app-debug.apk` shows no `.nnue`/`.tar`; APK < 120 MB; `generateModelPins` fails on an inconsistent lock (unit-tested through a Gradle `TestKit` case or a shell check) |
| **D2b** Stores, downloader, setup (back-to-back with D2a, same agent: the app does not run between them) | `NetStore`, `VoiceStore`, `ModelDownloader`, `DownloadStateMachine`, `ModelSetup`, `NetworkStatus`, `FaultHttpServer`, manifest permissions, debug network config, backup rules, `EngineController` changes, `AnalysisService.Failure.SETUP_REQUIRED`, delete `FirstRunSetup`, `BundledNetProvider`, `BundledVoiceInstaller`, `AnalysisPhase.FIRST_RUN_SETUP` | `engine/.../NetStore.kt`, `app/.../data/models/*`, `video/VoiceStore.kt`, `data/EngineController.kt`, `data/AnalysisService.kt`, `AndroidManifest.xml`, `src/debug/*`, `res/xml/*rules.xml`, `app/src/sharedTest/*`, host tests of §6.1 | `:app:testDebugUnitTest` and `:engine` host tests green with the new tests; `NetworkCallSitesTest` passes; no reference to the deleted classes outside RUN_LOG |
| **D2c** Service and UI | `ModelDownloadService`, `SetupScreen`, `SetupLogic`, `Destination.Setup` and nav gating, Home card, Analysing `SETUP_REQUIRED` state, Video notice, strings, notification channels, POST_NOTIFICATIONS request, accessibility per CLAUDE.md, landscape/2.0 font/RTL checked | `ui/screens/SetupScreen.kt`, `ui/model/SetupLogic.kt`, `ui/navigation/*`, `ui/screens/ImportScreen.kt`, `AnalysisProgressScreen.kt`, `VideoScreen.kt`, `strings.xml`, `ChessAnalyzerApplication.kt`, `AnalysisViewModel.kt` | Emulator run of §6.5 steps 1–3 against the host server with screenshots `d2c_*`; `lintDebug` 0 errors |
| **D2d** Instrumented tests | Seed assets in the test APKs, `TestApp` rewrite, all §6.2 tests, `:engine` tests on the test asset | `app/src/androidTest/*`, `engine/src/androidTest/*`, both build files | `connectedDebugAndroidTest` for `:app` and `:engine`: failures 0, **skipped 0 read from the XML**; `NoNetworkAfterSetupTest` asserts zero calls |
| **D2e** Updates | `ModelManifest`, `ModelCompatibility`, `ManifestSignature`, `UpdateChecker`, `ModelActivator` + journal + `recoverOnStartup`, `EngineController.switchNet`, eval-cache directories, narration fingerprint, Settings row and sheet, strings, host and instrumented tests of §6 | `data/models/*`, `data/GameRepository.kt`, `video/NeuralTtsProvider.kt`, `video/NarrationStore.kt`, `ui/screens/SettingsScreen.kt`, `MainActivity`/`Application` for recovery | §6.5 step 4 passes with a locally signed manifest; `ActivationRollbackInstrumentedTest` green; a deliberately wrong-arch net in the manifest is **not offered** |
| **D2f** Docs, policy, migration, release | PUBLISHING/STORE_LISTING/README/CLAUDE/HANDOFF per §7; privacy policy text; migration run (§8); `assembleRelease` + `bundleRelease`, sizes recorded; offline proof; RUN_LOG | `docs/*`, `README.md`, `CLAUDE.md`, `HANDOFF.md`, `RUN_LOG.md` | Sizes measured and written; migration proof in RUN_LOG; release-APK e2e against the real host recorded as **blocked until the repo exists** if it is |

Order: D2a+D2b → D2c → D2d → D2e → D2f. D2e can be deferred without harm: setup works without any manifest code.

**Interactions with D1 (toolchain):**
- FGS: D1 moves the export to `mediaProcessing`; the download stays `dataSync`, so `FOREGROUND_SERVICE_DATA_SYNC`
  is kept and the Play FGS declaration lists both types. targetSdk 35: `onTimeout` on the download service (§1.5),
  and `startForegroundService` from the background is not needed (always from a tap).
- `ManifestPermissionsTest` and `NetworkPermissionTest` expected sets change in both D1 and D2b; whichever lands
  second updates them.
- R8: no new keep rules; `GeneratedModelPins` are `const val`s. If D1 enables resource shrinking, the debug-only
  `network_security_config.xml` is referenced from the debug manifest and survives.
- compileSdk/targetSdk 36 edge-to-edge: `SetupScreen` uses `Scaffold` insets like every other screen; the Home card
  is inside the existing `LazyColumn`.
- App Bundle: the sizes in §5 assume per-ABI config splits (AGP default for AAB). 16 KB pages / NDK r28 are
  unrelated to this work.
- D1's AGP bump may change `android.sourceSets` DSL names for the `sharedTest` trick; use `testFixtures` instead
  if the DSL moved.

## 10. Open questions for the owner

1. **Repository name and base URL.** `https://github.com/palayax/palaya-chess/releases/download/` is a guess; the
   constant is one line to change, but `publish_models.sh` and the privacy text name the repo too.
2. **Voice archive size.** The plain tar is 158.3 MB; the upstream `.tar.bz2` is 103.2 MB. Keeping the tar costs
   users 55 MB more on first run; switching saves it at the price of a pure-Java bzip2 decode on the phone
   (tens of seconds, minutes on the emulator, per the B design) and a non-resumable extraction. Recommendation: tar
   now, measure a `.tar.gz` (likely ≈ 110–120 MB, estimate) in D2f and decide then.
   **Decided in D2f: switched to `.tar.gz`.** Measured: `gzip -9 -n` gives 102,543,452 B (55.7 MB smaller than
   the tar, 0.7 MB smaller than the upstream bzip2), and `GZIPInputStream` inflates it in 0.9-1.3 s on chess36
   (the plain tar's read + SHA-256 takes 0.4 s), so both bars of the task (>= 30 MB saved, < ~20 s) are met by a
   wide margin. The download stays resumable; the tar's pins stay the identity (marker, migration, cache key).
3. **Manifest signing key custody.** The design puts `models-signing.pem` next to the release keystore. Losing it
   means no model updates until an app update rotates the public key (first run is unaffected). Accept?
4. **Setup first, or Home first?** The design makes Setup the first screen on a fresh install with a "Not now"
   escape. The alternative (Home first, Setup only when a game is shared) is one line in the nav host; chess.com
   onboards before showing the lobby, which is why the design picks Setup first.
5. **Check for updates placement.** A fifth visible Settings row, per the owner's instruction. It could instead sit
   on the About screen (where the net name already shows) to keep Settings at four rows.
