# Bundling the Stockfish net and the Kokoro voice inside the APK: design (Round 13, task 68)

Produced by a Fable 5.1 / high design agent (read-only), 2026-10-03, saved by the orchestrator in condensed form.
"Measured" means read from the tree or the live emulator. "Estimate" means arithmetic or a guess. The owner's two open
questions were answered with the recommended defaults (§9).

**Goal:** the app works **fully offline from first launch**, with no downloads, and the manifest declares **no network permission**.

## 0. Decisions

| Question | Decision | Why |
|---|---|---|
| Packaging | `assets/`, stored **uncompressed** (`androidResources.noCompress`). Net in `:engine`'s assets, voice in `:app`'s assets | The only mechanism that needs no engine/TTS code-path change |
| Use the net directly from the APK? | **No.** Copy once to `filesDir` with SHA-256-prefix verification and an atomic temp+rename | `Network::load_external` is `std::ifstream(dir/path)` (`vendor/Stockfish/src/nnue/network.cpp:231-233`), and `verify()` calls `exit(EXIT_FAILURE)` (`:188`). The `setEvalFile` guards stay byte-for-byte |
| Voice | **Kokoro only**, as one plain `.tar` (bzip2 removed), extracted once | Kokoro is the verified default. Piper existed only as the metered-network fallback, which disappears. The UX removes the tier picker |
| When | One **"Setting up (one time)…"** phase inside the Analysing screen, before the first analysis, byte-weighted progress | Matches the UX design §6.2. `progress_first_run_setup` already exists in `strings.xml` |
| INTERNET | **Removed**, plus `ACCESS_NETWORK_STATE` and OkHttp | After deleting the two downloaders and the probe, nothing opens a socket |
| APK size | **~365 MB** universal, sideloaded | Owner: "a big APK is fine" |

## 1. Packaging

- **(a) `assets/`: chosen.** AGP merges library assets into the APK. They are deflated by default, so set `android.androidResources.noCompress += listOf(".nnue", ".tar")` **in `:app`**, because compression is decided when the APK is packaged, even for the net that lives in `:engine`. **Never use `""`**, which would also store the 32 MB `classes.dex`. Uncompressed means `openFd()` works, there is no inflate CPU on first run, and debug builds skip deflating 257 MB.
- **(b) `res/raw`: rejected.** Names must be lowercase identifiers with no dots, so `nn-1a298aa575a0.nnue` could not keep the hash name that the loader verifies against.
- **(c) Play Asset Delivery: rejected.** It needs an AAB through Google Play, and the deliverable is a sideloaded APK.
- **(d) Embed the net in `libstockfish.so` (INCBIN): rejected for this round.** It would mmap with no copy, but the net is embedded **per ABI** (3 × 98.5 MB, about 560 MB total), and it would force a redesign of the `setEvalFile`/`analyze` guards, which are the most crash-sensitive code in the project. It is the only zero-copy option, recorded in case per-ABI splits ever ship.
- **Do NOT try `/proc/self/fd/N` from an `AssetFileDescriptor`.** `std::ifstream` would open the whole APK from byte 0, `read_header` would fail, and `verify()` would `exit()`. `File("/proc/self/fd/N").isFile` is true, so the Kotlin guard would let it through. Keep `missingNetThrowsInsteadOfKillingTheProcess` exactly as is.

**Sizes (measured):**

| Item | Bytes |
|---|---|
| Current release APK | 108,052,265 |
| NNUE net on disk | 98,511,183 |
| Kokoro `.tar.bz2` | 103,248,205 (archive SHA-256 prefix `c9f0dd39…08bd`, pinned in `VoiceModelProvisioner.kt:304`) |
| Kokoro extracted | 157,947,103 in 360 files and 38 dirs. `model.int8.onnx` is 134,186,977, `voices.bin` 5,755,904, `espeak-ng-data/` about 17.9 MB, and 356 files have no extension |
| Kokoro as plain `.tar` | about 158.2 MB (estimate) |

New universal APK is about **364.8 MB** (estimate), and the debug build about 371 MB.

**Storage:** the first run adds 98.5 + 158.0 = **256.5 MB** under `filesDir`, so the steady footprint is about 620 MB and the install peak about 985 MB. The `chess34` emulator has 3.7 GB free (measured), so that is fine. Both artefacts stay excluded from Auto Backup, and are now regenerable from the APK.

## 2. First-launch flow

**Components**
- `:engine` **`BundledNetProvider(filesDir, assets)`** replaces `NetworkProvider`, with the same shape, `ensureNet(onProgress): File`, and the same `NET_FILENAME` from `GeneratedNetworkConstants`. Keep that generator untouched (single source of truth, CLAUDE.md gotcha 5). Logic:
  1. If the file exists and its prefix hash matches, return.
  2. Else delete any `*.part` and check free space.
  3. Stream `assets.open("nnue/<name>", ACCESS_STREAMING)` through a `DigestInputStream` into `<name>.part`.
  4. Compare length and digest prefix, then rename atomically.
  OkHttp and `org.json` go.
- `:app` **`BundledVoiceInstaller(filesDir, assets)`** replaces `VoiceModelProvisioner`. It keeps `rootDir = filesDir/tts_models`, the `.provisioned` marker, `extractTar` with the zip-slip guard and `stripTopLevelDir`, `hasRequiredFiles`, and the `.extracting` scratch directory with an atomic rename and the marker written last. It drops the download path, OkHttp, bzip2, `cancel()`, `provisionFromLocalArchiveForTesting` and `delete()`.
  - The tar is read from `assets.open("tts/kokoro-int8-en-v0_19.tar")` through a `DigestInputStream`, with the pinned hash checked **after** the pass and before the rename.
  - The marker now holds **this build's pinned tar hash**, so a model bump or an upgrade from an older build re-extracts automatically.
- `:app/data` **`FirstRunSetup(engineController, voiceInstaller, freeBytes)`**: `ensure(onProgress): SetupResult {Done | InsufficientStorage(needed) | Damaged(detail)}`, net then voice, progress weighted by bytes (98.5 : 158.2). `freeBytes` is injectable for tests.

**Where it runs:** in `AnalysisService.analyze()` after the PGN parse, so a bad paste fails fast, and **before** the eval-cache lookup, not only in the `evals == null` branch. Otherwise a game reopened from cache after an upgrade would skip the voice install. It reports `AnalysisPhase.FIRST_RUN_SETUP` in the 0.02–0.30 band that `DOWNLOADING_NET` uses today. When everything is installed it returns in milliseconds. `EngineController.ensureReady()` keeps its own idempotent `ensureNet()` call as the structural gate.

**Resumability, corruption, low disk**
- **Killed mid-copy:** restart-on-failure. Each attempt deletes `.part` and `kokoro.extracting/` first, with no byte-level resume. The final rename and marker write run under `withContext(NonCancellable)`.
- **Corrupt installed file:** the net is re-verified by prefix hash on every cold engine start, and re-copied on mismatch. The voice is checked by marker plus required files, and a failed `prepare()` falls back to the device voice, as today.
- **Bad APK asset:** `SetupResult.Damaged` → `Failure.SETUP_DAMAGED` → "The app's built-in engine files are damaged. Reinstall Palaya Chess."
- **Low disk:** `needed = (net missing ? 98.5 MB : 0) + (voice missing ? 158.0 MB : 0) + 32 MB`. If `freeBytes() < needed`, → `Failure.SETUP_STORAGE` → "Palaya Chess needs about 260 MB of free space to finish setting up. Free up some space and try again." An ENOSPC `IOException` maps to the same reason. Both use the existing inline error state.

**What the user sees:** the Analysing screen reads "Setting up the engine (one time)…" with a determinate bar of about 10–30 s on the emulator and 2–6 s on a phone (estimates), and no megabyte counter. The `bytesDownloaded/totalBytes` fields and `AnalysisProgressScreenDownloadingPreview` are deleted.

**Deleted outright:** `AnalysisViewModel.ensureDefaultNeuralVoice()` and its `LaunchedEffect`, all of `video/AutoVoicePolicy.kt` (`decideAutoVoice`, `NetworkCostProbe` and the rest), the download, cancel, delete, refresh and tier methods, `NeuralModelUiState`, and `setProviderAutomatically`. The neural voice becomes the default **by construction**: `NarrationVoiceSettings.provider` defaults to `NEURAL`, and `ResolvedProvider.from()` maps a missing or legacy `CLOUD` value to `NEURAL`. `providerExplicitlyChosen` and `setProvider` stay for the UX's "Use the phone's built-in voice instead" switch.

## 3. What to remove, and the INTERNET permission

**Delete:**
- `NetworkProvider.kt` whole, including `checkForEngineUpdate` and `EngineUpdateInfo`.
- `EngineController.checkForEngineUpdate`, `AnalysisViewModel.checkForUpdates`, `AnalysisPhase.DOWNLOADING_NET` and its screen branch, and `APPROX_NET_BYTES`.
- The Settings tier picker with its Download/Cancel/Delete/Wi-Fi-only UI and the "Check for updates" button, plus their parameters and nav-host wiring.
- The related strings, the `EngineSettings.netVersion = "not downloaded"` default, `libs.okhttp` in both modules and `libs.versions.toml`, and `scripts/push_test_net.sh` and `push_voice_models.sh`.
- Coordinate with UX step U9, which touches the same files: **run one after the other, never concurrently.**

**INTERNET can go entirely.**
- Every network user in the tree is a deleted file: OkHttp in the two downloaders, `ConnectivityManager` in `AutoVoicePolicy.kt`, and the GitHub call in `NetworkProvider`. A grep for `HttpURLConnection|java.net.URL|okhttp|ConnectivityManager` found nothing else.
- `AboutScreen` opens `https://stockfishchess.org` with an `ACTION_VIEW` intent, which needs no permission.
- The merged debug manifest attributes both permissions solely to `app/src/main/AndroidManifest.xml:6-7`, and the sherpa-onnx AAR declares none.
- So delete those lines and the `usesCleartextTraffic` attribute.

**Proof, three ways:**
1. Host `ManifestPermissionsTest`: no `INTERNET` or `ACCESS_NETWORK_STATE` in the manifest.
2. Instrumented `NoNetworkPermissionTest`:
   - The installed package's requested permissions contain neither.
   - A behavioural proof: `Socket().connect(127.0.0.1:9)` throws `SocketException` with `EACCES`. This is from memory of Android's behaviour, so verify it on the emulator.
3. On the release APK: `dumpsys package` lists only `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` and `POST_NOTIFICATIONS`.

## 4. Voice: Kokoro only, as a plain `.tar`

- Kokoro is the verified default, with `af_bella` at sid 1 and `length_scale` 1.2. Piper only existed for the metered-network fallback.
- Layout comparison:
  - `.tar.bz2` (103 MB): pure-Java bz2 decode of 158 MB is tens of seconds on a phone and minutes on the emulator. This is the worst first run.
  - 360 loose files: the copy is a byte stream, but `noCompress` is suffix-based and 356 files have no extension, and it needs a 360-row manifest and new code.
  - **Plain `.tar` (158.2 MB): chosen.** One asset, one pinned hash, no decompression, and it reuses the tested extraction tail. `bunzip2 -k` of the pinned archive yields a byte-identical inner tar, so its hash can be pinned.
- Code: `NeuralVoiceTier` keeps one `KOKORO` entry. A persisted `neural_voice_tier = PIPER` from an older build already resolves to KOKORO.

## 5. Build and repo logistics

- **`scripts/fetch_models.sh`** (replaces both push scripts):
  - Read the net name from `vendor/Stockfish/src/evaluate.h` `EvalFileDefaultName`.
  - Download it from `https://tests.stockfishchess.org/api/nn/<name>` into `vendor/models/engine-assets/nnue/<name>` and verify the prefix hash.
  - Download `kokoro-int8-en-v0_19.tar.bz2` from the sherpa-onnx `tts-models` release, verify the pinned archive SHA-256, decompress to `vendor/models/app-assets/tts/kokoro-int8-en-v0_19.tar`, and verify the tar against its pinned hash.
  - **The inner-tar hash is not known yet.** Compute it once from the verified archive and pin it.
- **`vendor/models/MODELS.lock`** (committed) holds name, URL, SHA-256 and size. The script and Gradle both read it. **Add `vendor/models/` to `.gitignore` BEFORE fetching**: the repo now has commits, and an accidental `git add` of 257 MB would be real damage.
- **Wiring:** `engine/build.gradle.kts` `assets.srcDir(vendor/models/engine-assets)`. `app/build.gradle.kts` the same for `app-assets`, plus `androidResources { noCompress += listOf(".nnue", ".tar") }` and `installation { timeOutInMs = 600_000 }`.
- **`verifyBundledModels` task** in each module: it fails the build loudly if a model is missing or has the wrong size or hash, with "run scripts/fetch_models.sh". `preBuild` depends on it. In `:app` it also generates `GeneratedBundledVoiceConstants.kt`.
- **Build effects (estimates):** a clean `assembleDebug` takes 10–30 s longer, and incremental builds 1–3 s longer. `assembleRelease` signing over 365 MB takes about 2–4 s. `app/build` grows by about 1 GB. Every `connectedDebugAndroidTest` pushes the 371 MB APK.
- **Risk:** the `:engine` library's own androidTest APK may not carry its assets. If it cannot open the net, keep `push_test_net.sh` temporarily for the engine tests only.

## 6. Licences

- **Kokoro-82M:** Apache-2.0. The model's own `LICENSE` and `README.md` land in `tts_models/kokoro/` via the tar.
- **A pre-existing gap:** `espeak-ng-data/` (18 MB, 355 files) is espeak-ng's phoneme data, **GPL-3.0-or-later** (from memory, **verify**). The About text and `NEURAL_VOICE_LICENSE.txt` do not mention it. It is compatible with the app's GPLv3, so add an attribution line.
- **NNUE net:** trained on data from the Leela Chess Zero project under the ODbL (`vendor/Stockfish/README.md:125-126`). Add that to `about_license_stockfish_body`. Whether ODbL "produced work" notice wording is required is unverified, and the POC treats licence questions as informational.
- **Piper:** no longer shipped. Keep the "voices not used and why" history and drop the Piper tier text from About.
- **Edits:** `about_license_neural_body` → "…Kokoro-82M voice model (Apache 2.0) and espeak-ng pronunciation data (GPLv3) are included in the app and nothing is ever sent anywhere." Update `NEURAL_VOICE_LICENSE.txt` ("Included in the app"), `PUBLISHING.md` (attribution, size 365 MB with a Play AAB caveat, data safety "Network use: none", checklist, table) and `STORE_LISTING.md` (delete the download note and the INTERNET justification).

## 7. Testing and verification

**Instrumented tests to change or add** (no `assumeTrue`, and pull artefacts to the host):
- `:engine`: new `BundledNetProviderInstrumentedTest` (fresh dir gives the right length and hash; a truncated file is replaced; a leftover `.part` is discarded; a second call does not rewrite). The two engine tests stop reading `/data/local/tmp` and use the provider.
- `:app`: delete the Piper test and `AutoVoicePolicyInstrumentedTest` (12 tests). Rewrite the Kokoro test to install from the APK, keeping the duration and RMS assertions and the pullable evidence WAV. Rewrite `VideoExportNeuralVoiceEvidenceTest`, `VoiceSampleSweep` and `EndToEndAnalysisTest`, **removing the seeding and the `assumeTrue`** so it can no longer pass vacuously. Update `NarrationSettingsRepositoryTest` (NEURAL default). Add `BundledVoiceInstallerInstrumentedTest`, `FirstRunSetupInstrumentedTest` and `NoNetworkPermissionTest`.
- Host: `BackupRulesTest` (new constants and `.part` suffix), `ResolvedProviderTest` (NEURAL default), new `ManifestPermissionsTest`.

**Offline cold-install proof on the release APK:**
1. `assembleRelease` gives about 365 MB, and `unzip -lv` shows both assets `Stored`.
2. Uninstall the app and its test APK.
3. Disable wifi, mobile data and enable airplane mode, and confirm `ping` **fails**.
4. `adb install` the release APK (time it), and check `dumpsys package` for the requested permissions.
5. Launch, share the Opera-game PGN as text, and screenshot the "Setting up (one time)…" phase.
6. Summary → Video → Save, then `ls` **`/sdcard/Movies/ChessAnalyzer/`** (not the parent) and `adb pull` the MP4.
7. On the host: `ffprobe` for duration, extract the audio, and measure **RMS, peak and near-silent windows** with Python.
8. `logcat`: expect the installed-net and loaded-KOKORO lines, and 0 matches for `stockfishchess|github.com|okhttp`.
9. Restore the network settings.

Pass criteria: install works offline, the setup phase appears once, the audio is speech-level with duration within about 10% of the script estimate, and a second analysis shows no setup phase. `run-as` does not work on release builds, so logcat is the on-device evidence.

**CLAUDE.md changes:**
- Gotcha 4: "The net is in the APK (uncompressed asset in `:engine`), copied once to `filesDir` and SHA-256-verified; Stockfish still cannot read it from the APK, and the `setEvalFile` guards are unchanged."
- Delete the whole "Re-seed the NNUE net" gotcha and the `push_test_net.sh` line. Replace with: "A fresh install sets itself up on the first analysis (~10–30 s on the emulator). Nothing to seed."
- Note that `connectedDebugAndroidTest` pushes a ~371 MB APK.
- Update HANDOFF, README and NEURAL_VOICE likewise.

## 8. Ordered implementation plan (one Gradle build at a time)

| # | Step | Done when | Risk |
|---|---|---|---|
| B0 | Repo prep, no Gradle: `.gitignore` += `vendor/models/`; write `MODELS.lock` and `fetch_models.sh`; run it; pin the inner-tar SHA-256 | both files hash to the lock; `git status` shows no model file | forgetting `.gitignore` first |
| B1 | `:engine`: assets srcDir, `verifyBundledModels`, `BundledNetProvider`, drop okhttp, rewrite the engine tests and add the new one | `:engine:assembleDebug` and `:engine:connectedDebugAndroidTest` pass, skipped=0 | the library androidTest APK may not carry assets. `:app` will not compile until B2, so do **B1 and B2 back-to-back** |
| B2 | `:app` main code per §2–3 (build file, installer, `FirstRunSetup`, `AnalysisService`, ViewModel, screens, NEURAL default, manifest, strings, backup rules) | `:app:assembleDebug` and unit tests green; `unzip -lv` shows both assets `Stored` | same files as U9: **sequence, never interleave** |
| B3 | `:app` instrumented tests per §7 | `connectedDebugAndroidTest`: failures=0, skipped=0; the evidence WAV pulled and measured | slower first run (371 MB install plus a real 257 MB copy) |
| B4 | Docs, licence texts, About strings, delete the push scripts | grep for the old names returns only RUN_LOG history | none |
| B5 | Release build and the offline evidence run | offline cold install → analysis → narrated MP4 measured; permissions clean | install time unmeasured; re-check `df /data` first |

## 9. Owner questions, answered with the recommended defaults

1. **Drop Piper entirely?** Yes. It costs 37 MB and a second code path for no user-visible benefit.
2. **Setup before the first analysis, or install the voice lazily?** Before the first analysis: one "Setting up (one time)…" moment, paid once.
