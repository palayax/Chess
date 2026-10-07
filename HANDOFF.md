# HANDOFF — Palaya Chess

Entry point for a fresh session. Read this first, then `RUN_PLAN.md` (task breakdown) and
`RUN_LOG.md` (what was built, what was verified, and every defect found). `CLAUDE.md` holds the
gotchas that cost real time — read it before touching the build, the engine, or the emulator.

**Do not trust this file over the tree.** Everything below is claimed state; verify it by building
and running the tests before building on it. This is not boilerplate caution — Round 5 found that
the previous handoff's central claim ("neural TTS verified") rested on evidence that had never
actually been collected. The conclusion happened to be right; the proof was not there.

---

## OWNER TO-DO BEFORE GOOGLE PLAY (D2f, 2026-10-07) — only you can do these

The app (1.1, versionCode 2) is built, signed and verified; it cannot go live until these are done. Details in
`docs/PUBLISHING.md` (§4c for the GitHub steps, §6 for the release checklist).

1. ~~Create the GitHub repo~~ **done 2026-10-07: `palayax/Chess`** (owner). The app, `publish_models.sh`, the
   privacy policy, the listing and PUBLISHING now point at it. It must be **public**: the app downloads the models
   from its Releases without signing in, and GPLv3 needs the source offered.
2. **Push the source** (GPLv3 obligation). `keystore/`, `keystore.properties`, `*.pem`, `vendor/models/*` (except
   `MODELS.lock` and `manifest_public_key.der`), `dist/` and `tools/` are gitignored; check `git status --ignored`
   first. The About screen's source link and the listing already say https://github.com/palayax/Chess.
3. **Publish the models**: `gh auth login`, `scripts/fetch_models.sh`, then
   `scripts/publish_models.sh models-2026.10 --min-version-code 2`. It creates the immutable `models-2026.10`
   release (the net, 98,511,183 B, and `kokoro-int8-en-v0_19.tar.gz`, 102,543,452 B) and the signed `models.json`
   + `models.json.sig` on the rolling `models` tag. **Do this BEFORE the app is released**: until then a fresh
   install's Download ends in "The engine files aren't on the server". Afterwards: release APK, fresh install,
   Download reaches "All set", and Check for updates says "You're up to date."
4. **Back up `keystore/` offline** (two places): `chessanalyzer-release.jks`, `keystore.properties` and
   `models-signing.pem`. Losing the first two means no app updates for sideloaded users (Play can reset an upload
   key); losing the `.pem` means no model updates until an app update ships a new key.
5. **Host the privacy policy** (`docs/PRIVACY_POLICY.md`, ready, not published anywhere) on palaya.net and put the
   URL in Play Console and the listing. Confirm the repo address in it first.
6. ~~Decide the colours~~ **decided 2026-10-07: keep the current colours** (owner).
7. **Choose the Play developer account type** (personal or organisation, e.g. Palaya Cyber Security LTD).
8. **If it is a personal account:** Play requires a closed test with **at least 12 testers opted in for 14 days**
   before you can apply for production. Recruit them early (an organisation account is exempt).

Then, in Play Console: upload `dist/PalayaChess-1.1-release.aab`, choose Play App Signing deliberately (PUBLISHING
§3: keep the existing key if sideloaded 1.0/1.1 users must update in place), fill in the foreground-service
declarations with two short videos (§3), the Data safety form (§5: no data collected or shared), content rating
(Everyone), and the listing (`docs/STORE_LISTING.md`).

---

## V2 DONE (2026-10-07): best-line simulation

RUN_LOG "V2". Nothing committed. **Next: G1** (famous games).

- **Board:** every INACCURACY / MISTAKE / MISS / BLUNDER and every Summary key moment offers **"Show the best line"**:
  the engine's line from the position before the move, played on the same board with Back / Next / Play-pause,
  "12... Qg6" + who is to move + "3 / 7", the next move as an arrow, the eval bar on the line's score, the
  notation, a verified caption ("The engine rates this line +2.3." / "...forced mate in 3 for White." / "In
  this line White wins a piece." only when settled), "Engine depth 14", chips for lines 2-3 within 2 win-%,
  "Back to the game" (system back too). A Summary key moment without a walkthrough offers the same button.
- **Rule (ANALYSIS_SPEC §6.2):** `min(PV length, depth / 2, 8)` plies, stop at mate; `CandidateLine` now carries
  `pvUci` + `depth`. The Walkthrough and the line mode share `ui/components/LinePlayer.kt`.
- **Video (§9.8):** on a MISTAKE/MISS/BLUNDER key moment whose narration names the better move over a still
  board, the line (up to 4 plies) plays silently after the speech, inside the segment's hold, as pace time
  within the 15 % cap (chosen at Relaxed so every pace plays the same moves; inaccuracies keep the arrow).
- **Audit:** `python scripts/audit_commentary.py lines core/build/commentary_audit/best_lines.jsonl` (new mode).
- Counts / evidence: RUN_LOG "V2".

---

## V1 + V3 DONE (2026-10-07): narrator voice picker, video pace

RUN_LOG "V1 + V3". Nothing committed. V2 is done (section above).

- **V1, Settings, Video, "Narrator voice":** all 11 Kokoro speakers (`KokoroVoices.SPEAKERS`; `voices.bin` = 11 x
  523,264 B), labelled in our words ("Bella · American, female"), each with an on-device "Play sample"
  (`VoiceSamplePlayer`, cached, one at a time). Default unchanged (Bella, sid 1). Persisted as `kokoro_speaker_id`;
  used by the player and the MP4; part of the narration cache key; the update trial keeps the default. Host-measured
  samples of all eleven in `docs/voice_samples/v1/` (no clipping; male 102-144 Hz, female 154-203 Hz): **the owner
  should listen** and confirm or change the default.
- **V3, Settings, Video, "Pace": Relaxed (default) / Normal / Brisk** (ANALYSIS_SPEC §9.8). Measured problem: key
  moves slid in at the first frame of their beat with no pause, and the replies between them were never shown
  (the Immortal Game's 21...Kd8 and 22...Nxf6: 0 ms). Now every key move gets a silent lead-in (the skipped moves at
  the sequence rate, then a 1.5 / 1.2 / 0.7 s pause with the move's squares lit) and a hold after. Same story and
  audio at every pace; the §9.7 budget holds the story, the pace time (2.5-12 percent measured) sits on top, capped
  at 15 percent of the budget.
- **Counts / evidence:** see RUN_LOG "V1 + V3".

---

## D2f DONE (2026-10-07): 1.1 release, voice as .tar.gz, update from 1.0 proven, docs and policy

RUN_LOG "D2f". Nothing committed. The small-installer track (D2a-D2f) is complete; the one check left is the
release build against the real GitHub release, which waits for the owner (above).

- **Version:** versionName **1.1**, versionCode **2** (1.0 / 1 = the bundled R7 build). `publish_models.sh
  --min-version-code 2`.
- **Voice is now downloaded as `kokoro-int8-en-v0_19.tar.gz`** (open question 2): `gzip -9 -n` makes it 102,543,452 B,
  55.7 MB smaller than the 158,269,440 B tar; `GZIPInputStream` inflates it in 0.9-1.3 s on chess36 (bench), and the
  app's whole unpack (inflate + extract + SHA-256) took 1.4 s there. Download: 201 MB instead of 257; peak free space
  400 MB instead of 450. The tar's pins stay the identity (marker, migration, narration cache key); update manifests
  carry `tarSha256`/`tarSize`; a stream that inflates past the tar's size is refused at once.
- **Migration proven on chess36 (release builds, same key `ca4f7b42…0947`):** 1.0 installed, Opera Game analysed (net
  copied to `files/`, voice unpacked, marker `7190c480…`); airplane mode on; `adb install -r` 1.1: opens on **Home**,
  no Setup, the diagnostic log's only `[models]` lines are `migrateLegacy: moved nn-1a298aa575a0.nnue into nets/; voice
  installed: true` and the eval-cache move; a new game analysed offline and a Kokoro-narrated MP4 exported offline
  (113.1 s, mean -25.5 dB, max -6.8 dB, pulled). Screenshots `docs/screenshots/d2f_*`.
- **Found and fixed: a library downloaded a font on the app's behalf.** androidx.emoji2's startup initializer made Play
  services fetch "Noto Color Emoji Compat" (~3 MB, charged to the app's uid) on the first screen; caught by
  `NoNetworkAfterSetupTest` (tx 30,966 B). Initializer removed in the manifest; pinned by two tests.
- **AAPT trap found:** an asset named `*.gz` is gunzipped into the APK; the test seed is `tts/<name>.tar.gz.seed`.
- **Release outputs (dist/):** `PalayaChess-1.1-arm64-release.apk` 35,866,501 B, `PalayaChess-1.1-universal-release.apk`
  97,110,046 B, `PalayaChess-1.1-release.aab` 68,714,862 B; Play download per device (bundletool) arm64 14,473,967 B,
  x86_64 15,911,159 B, armeabi-v7a 13,458,827 B. v2+v3 signed, 16 KB-aligned, six permissions, no cleartext config.
  SHA-256s in RUN_LOG D2f.
- **Docs:** `docs/PUBLISHING.md` (rewritten: facts, FGS declarations, Device and Network Abuse, Data safety, checklist,
  GitHub steps), `docs/PRIVACY_POLICY.md` (new), `docs/STORE_LISTING.md` (rewritten), README, CLAUDE.md, this file;
  superseded notes on `BUNDLED_MODELS_DESIGN.md` and `NEURAL_VOICE.md`; About and the voice licence text now say
  "downloaded once".
- **Counts (XML):** `:core` 490/0/0, `:engine` unit 32/0/0, `:app` unit 456/0/0; lint 0 errors / 69 warnings; `:app`
  connected 161/0/0 on chess36 and on chess34, `:engine` connected 20/0/0 on both, all 0 skipped.

---

## D2e DONE (2026-10-07): "Check for updates", signed manifest, journaled activation, trial and rollback

RUN_LOG "D2e". Nothing committed. Settings has a fifth row, **Check for updates**, which checks ONLY when tapped
(exactly two requests: `models.json` and `models.json.sig`) and opens a result sheet: up to date / update available
with its size / downloading / checking the file / unpacking / trying the new version / installed / rolled back /
no internet / server unavailable / not found / signature invalid.

- **Trust:** ECDSA P-256 signature over the exact manifest bytes, verified with the public key compiled in from
  `vendor/models/manifest_public_key.der`; the private key is `keystore/models-signing.pem` (gitignored, never
  printed; custody and the publish flow in `docs/PUBLISHING.md` §4b; `publish_models.sh --sign/--verify`).
- **Compatibility:** a net only with this engine's NNUE version 0x6a448afa and arch hash 0xa85b2205, a voice only
  with layout `kokoro-v0_19` and a sherpa-onnx range containing 1.13.8, version codes respected; re-checked
  before any download, so a wrong-arch net is never offered and never downloaded (host test, instrumented test,
  emulator server log).
- **Activation:** `ModelActivator` + `filesDir/models/activation.json`; net = Kotlin header check, journaled swap,
  depth-1 trial on THE engine (`EngineController.switchNet`/`trialLocked`, only `verifiedNetOrNull()`),
  commit; voice = unpack to scratch, throwaway Kokoro synthesis with an RMS floor, journaled swap, commit,
  `NarrationStore.clear()`. A failed trial rolls back at once; a process death mid-trial is rolled back by
  `recoverOnStartup()` at the next start (proven on chess36 with a real `kill -9`), and Settings says so once.
- **Caches:** `eval_cache/<net 12-hex>/` (flat cache migrated, other nets purged on activation, F1 budget kept in
  the key); the narration cache key carries the voice id.
- **Counts (XML):** `:core` 490/0/0, `:engine` unit 32/0/0, `:app` unit 443/0/0; lint 0 errors / 69 warnings;
  `:app` connected 160/0/0 on chess36 and on chess34, `:engine` connected 20/0/0 on both, all 0 skipped;
  `assembleRelease` OK.
- **Emulator e2e (chess36):** voice "upgrade" = the same Kokoro tar with one note file appended (no other
  compatible model exists): checked, installed (RMS 1877 trial), narration cache 118 -> 0, re-exported MP4 mean
  -25.1 dB / max -6.0 dB; wrong-arch net never requested; tampered manifest refused; release build shows "no update
  information on the server yet" against the provisional URL. Screenshots `docs/screenshots/d2e_*`.
- **Next:** D2f is done (section above).

---

## D2d DONE (2026-10-07): instrumented tests green again, seed assets wired

RUN_LOG "D2d". Nothing committed. **The instrumented suites pass again** on chess36 and chess34, with the models only
in the TEST APKs: `vendor/models/engine-assets` + `app-assets` are the androidTest asset dirs of `:app` and `:engine`
(stored uncompressed; `checkTestSeedAssets` fails the test build until `scripts/fetch_models.sh` has run). `unzip -l`
shows no `.nnue`/`.tar` in `app-debug.apk` or in any release APK.

- **Counts (XML, final code):** `:app` connected **153/0/0** on chess36 and on chess34, `:engine` connected **20/0/0** on
  both, all 0 skipped; `:core` 490/0/0, `:engine` unit 27/0/0, `:app` unit 366/0/0; lint 0 errors / 69 warnings;
  `assembleRelease` OK. A full `:app` run takes about 12-14 min per device; run the devices one after the other.
- **New / ported tests:** `NetStoreInstrumentedTest` (:engine), `VoiceStoreInstrumentedTest`, `ModelSetupInstrumentedTest`
  (the real 257 MB over an in-process `FaultHttpServer`), `ModelDownloaderInstrumentedTest` (the host fault matrix, now
  shared from `app/src/sharedTest`), `SetupFlowInstrumentedTest` (the real `ModelDownloadService`: dataSync type from the
  platform and dumpsys, pause/resume, cancel, Activity death, a stray intent), `NoNetworkAfterSetupTest`,
  `NetworkSecurityConfigTest`, `SetupGateInstrumentedTest`.
- **No network after setup, measured:** recording `ProxySelector` (proven live first) + uid `TrafficStats` over Home,
  a full Standard analysis and a Kokoro-narrated export through `VideoExportService`: 0 calls, 0 B tx/rx on both devices.
- **Fixed:** a crash (`ForegroundServiceDidNotStartInTimeException`) when Resume was tapped right after Pause
  (`ModelDownloadService` stopped itself unconditionally); a stale "no INTERNET" assertion in `VideoShareInstrumentedTest`;
  a coincidence-prone wall-clock check in `VideoExporterInstrumentedTest`. `benchmarkDepth18` now runs under the Deep budget.
- **Test seam:** `ChessAnalyzerApplication.modelSetupForTesting` (tests only) points the service and the Setup gate at a
  scratch dir.
- **Next:** D2e is done (section above), then D2f.

---

## D2c DONE (2026-10-07): download service, Setup screen, Home card, "Set up" and Video notices

RUN_LOG "D2c". Nothing committed. A fresh install now opens on **Setup**: both sizes are stated, Download is one tap, and
the download runs in `ModelDownloadService` (FGS `dataSync`, started only by that tap, `START_NOT_STICKY`). It shows
progress, Pause and Cancel in the notification, and the metered and low-space checks come before any request. "Not now"
leads to the Home "Finish setting up" card. A game shared before setup is kept (`filesDir/setup_waiting_game.json`)
and analysed as soon as the net is in, while the voice still downloads. Analysing `SETUP_REQUIRED` has **Set up**.
Video says "Narrated with the phone's voice until setup is finished." while the voice is missing.

- **Verified on chess36 and chess34** against `scripts/model_test_server.py`: every §1.3 state, plus the faults (server
  killed, airplane mode, notification pause/resume, process killed then resumed from the part, cancel, wrong hash twice,
  HTTP 500, low storage, notifications denied). Font 2.0, landscape and RTL (he) were checked. Offline proof with
  game01: Standard analysis and a narrated Kokoro MP4 (mean -25.3 dB, max -5.3 dB, pulled), no network line from the
  app's pid, no `[models]` log line after setup. Release (R8) on chess36 shows NOT_FOUND against the provisional
  GitHub URL. Screenshots: `docs/screenshots/d2c_*` (60).
- **Emulator trap:** unthrottled transfers through the emulator NAT can lose bytes (proven with `toybox nc`). The app
  correctly reports "didn't match". Serve with `--fault slow:3m --fault-times 0` on emulators.
- **Counts (XML):** `:core` 490/0/0, `:engine` unit 27/0/0, `:app` unit 366/0/0, all 0 skipped. Lint 0 errors / 69 warnings.
- The instrumented suites were IN FLUX until D2d (done, section above).

---

## D2a + D2b DONE (2026-10-07): models out of the APK, downloader and setup logic (no UI yet)

The small installer is half built (RUN_LOG "D2a + D2b", design `docs/MODEL_DOWNLOAD_DESIGN.md` §9). Nothing committed.
**The app is in flux until D2c and D2d** (read this before running anything on a device):

- **No models in any APK.** Release universal 96,967,686 B (was 353.8 MB), arm64 35,724,137 B, debug 114,010,909 B;
  AAB 68,454,190 B, Play download arm64 14,368,523 B (bundletool). `assembleDebug` works from a tree without
  `vendor/models/` files; the build needs only `vendor/models/MODELS.lock` (`generateModelPins`).
- **No Setup screen yet (D2c).** A fresh install cannot download anything: an analysis ends with "Finish setting up
  first." (`AnalysisService.Failure.SETUP_REQUIRED`). An update from a bundled build keeps working: `migrateLegacy`
  moves the old net into `filesDir/nets/` at start and the voice marker is accepted as before (nothing downloaded).
- **Instrumented suites fail until D2d** (seed assets not wired into the test APKs). Not run in D2b, by instruction.
  Do not "fix" them by re-bundling the models.
- **Network:** INTERNET + ACCESS_NETWORK_STATE in the manifest; `data/models/ModelDownloader.kt` is the only class that
  opens a connection (`NetworkCallSitesTest`); https only, http only to 10.0.2.2/127.0.0.1/localhost in debug builds.
- **Counts (XML):** `:core` 490/0/0, `:engine` unit 27/0/0, `:app` unit 341/0/0, all 0 skipped; lint 0 errors / 70 warnings.
- **For D2c:** `app.modelSetup` (`needsNet/needsVoice/isComplete/state/bytesLeft/storageNeeded`, `suspend run(onProgress)`:
  cancel the job = Pause, then `discardPartials()` = Cancel), `SetupProgress`/`SetupStatus`/`PauseReason`/`FailureReason`
  for the §1.3 copy, `app.networkStatus.current()` for the metered/no-network checks, `Failure.SETUP_REQUIRED` for the
  Analysing "Set up" button. Details in the RUN_LOG entry.
- **Tools:** `scripts/model_test_server.py` (local release stand-in with faults) + `-PpalayaModelBaseUrl=http://10.0.2.2:8787/`
  (debug only); `scripts/publish_models.sh` (only `--dry-run` was run: no repo, no key yet).
- **Next:** D2c is done (section above); then D2d.

---

## F1 DONE (2026-10-06): Deep no longer hangs, a killed analysis resumes, diagnostic log

The owner's bug ("Deep" on `games/game01.txt` took forever at move 23, then "could not analyse") is fixed
(RUN_LOG "F1", ANALYSIS_SPEC §8). Nothing committed.

- **Per-position budget** `go depth D nodes N movetime T` (`ui/model/SearchBudget.kt`): Quick 4 M / 30 s,
  Standard 25 M / 150 s, Deep 45 M / 270 s, calibrated on 226 host positions (>= 95% still reach full depth).
  Capped positions are flagged (`PositionEval.requestedDepth`/`isCapped`) and counted in one quiet Summary line.
  The eval-cache key now includes the budget (old results are re-analysed once).
- **Engine:** `StockfishEngine.analyze(..., nodes, onProgress)`; results use `ConsistentLines` (one depth for all
  lines; the batch Stockfish prints at a node/time stop is discarded: it can carry the requested depth's label
  with old numbers). CLAUDE.md engine gotcha 6.
- **Resume:** `PendingAnalysisStore` (`filesDir/pending_analysis.json`) + a checkpoint every ply; proven with
  `am kill` on chess34. No foreground service (none needed, none fits).
- **Analysing screen:** elapsed time, node-based "About N min left", "Thinking deeper on this move… depth d of 18".
- **Diagnostic log:** `filesDir/logs/` (~1 MiB, two files, excluded from backup), previous-process exit reasons,
  crash handler, lifecycle; shared from Settings and from the error screen's "Share details".
- **Counts (XML):** `:core` 490/0/0, `:engine` unit 10/0/0 (new), `:app` unit 273/0/0, `:app` instrumented
  110/0/0 on chess34 and 110/0/0 on chess36, `:engine` instrumented 20/0/0 on chess34 and 20/0/0 (one benchmark timeout under parallel load first, see RUN_LOG) on chess36,
  all 0 skipped; lint 0 errors / 70 warnings. Release (R8) build analysed game01 at Deep on chess34: reached the Summary, 4 positions capped, no crash.
- **Unknown:** which message the owner actually saw. The next report from a phone should come with the
  shared diagnostic log: its "previous process exit" lines say whether Android killed the app.
- **Next:** D2a–D2f (RUN_PLAN Round 14 queue). D2a + D2b are done (section above).

---

## D-TRACK: GOOGLE PLAY (2026-10-06) — read this first

The owner will publish on Google Play. **D1 (build Play-ready) is done** (RUN_LOG "D1", RUN_PLAN "D-track");
D0 (moving the two models to a first-run download) is a separate design, `docs/MODEL_DOWNLOAD_DESIGN.md`. Since D2a
the models are NOT in the APK any more (see the D2a + D2b section at the top); the sizes and the "no network" facts
below are D1's.

- **Toolchain now:** AGP 8.9.3, Gradle 8.11.1, Kotlin 1.9.24 (unchanged), compileSdk/targetSdk **36**, NDK
  **28.2.13676358** in `:engine` and `:app`. Details and traps in `CLAUDE.md` (build gotchas).
- **Release build is R8-minified** (dex 44.7 MB -> 3.1 MB) with JNI keep rules; every `.so` is 16 KB-aligned;
  the export FGS is `mediaProcessing` on Android 15+ (`dataSync` below) with `onTimeout`.
- **Outputs:** `./gradlew :app:bundleRelease` -> signed `app-release.aab` (Play: ~196 MB download per arm64
  device, ~14 MB without the models; limit 200 MB). `./gradlew :app:assembleRelease` (a separate invocation)
  -> `app-arm64-v8a-release.apk` (292,485,879 B) and the other ABIs plus `app-universal-release.apk`.
  bundletool: `java -jar tools/bundletool-all-1.18.3.jar` (gitignored). Play App Signing and the FGS
  declaration text: `docs/PUBLISHING.md` section 3.
- **AVDs:** `chess34` (API 34) and `chess36` (API 36 `google_apis`, new). Run the instrumented suites on both.
- **Counts (XML):** `:core` 490/0/0, `:app` unit 237/0/0, `:app` instrumented 104/0/0 on API 36 and on API 34,
  `:engine` instrumented 20/0/0 on both, all 0 skipped; `lintDebug` 0 errors, 70 warnings.
- **Verified on API 36 with the release APK:** fresh install, analysis, Summary, Board, Practise, Walkthrough,
  Video, a narrated MP4 export with the Kokoro voice (pulled: mean -25.3 dB, max -5.6 dB), Share, no crashes.
  Screenshots `docs/screenshots/d1_api36_*`.
- **Next:** D2+ implements D0 once the design is accepted. Open items from D1 are at the end of its RUN_LOG entry.

---

## FINAL STATE (R7, 2026-10-05)

> **History (1.0).** This was the bundled, no-network 1.0 build. Since 1.1 (D2a-D2f) the models are downloaded on
> first run and the app holds INTERNET; current state is the D2f section and the owner to-do list at the top.

**The deliverable is done: a signed release APK that works completely offline, proven on a device.**

- **APK:** `C:\Claude\ChessAnalyzer\dist\PalayaChess-1.0-release.apk` (gitignored), versionName 1.0 / versionCode 1,
  **364,733,235 bytes** (about 348 MiB), SHA-256 `dc2214f3008db284c39534cc1a3677f68433d7e6491dc21cc6932088b4373573`.
- **Signature (`apksigner verify`):** v2 true, v3 true (v1 false by design, minSdk 26), 1 signer, certificate SHA-256
  `ca4f7b42ce837f97d48e0e802b48b1c9c9c253ba4e604e56a8dff6979a890947` (the fingerprint in `docs/PUBLISHING.md`). The keystore and
  passwords stay in `keystore/` and `keystore.properties` (gitignored; back them up offline).
- **Contents:** `nn-1a298aa575a0.nnue` (98,511,183 B) and the Kokoro `.tar` (158,269,440 B) are Stored (uncompressed);
  `libstockfish.so` for arm64-v8a, armeabi-v7a, x86_64. Permissions: FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC,
  POST_NOTIFICATIONS only; **no INTERNET, no ACCESS_NETWORK_STATE.**
- **Final test counts (read from the result XML, debug build):** `:core` 490/0/0 skipped; `:app` unit 233/0/0; `:app` instrumented
  103/0/0 skipped; `lintDebug` 0 errors (70 warnings). `:engine` instrumented 20/0/0 (not re-run in R7, nothing changed).
- **Offline proof (emulator chess34, API 34, release APK, fresh install, airplane mode on, wifi and data off, ping to 8.8.8.8
  unreachable, no default network):** the Immortal Game shared in, "Setting up the engine (one time)...", analysis, Summary,
  Board, Practise and Walkthrough walked, and a narrated MP4 exported: 7 min 2 s, 1280x720 h264 + aac, mean volume -25.3 dB, max
  -5.6 dB, silent recap at the end (-91 dB), frames viewed. Logcat had no network traffic from the app; its one URL line came from
  the system Google TTS engine (a different pid). Details and screenshots (`docs/screenshots/r13_r7_*`) are in `RUN_LOG.md` (R7).
- **R7 also fixed** the Summary header (and the Home row) swapping Hebrew names: `versusLine` in `video/CardLayout.kt`, `VersusLineTest`.

**What "done" means for the owner:** you can copy the APK to a phone and install it (allow "install unknown apps"). It needs about
620 MB of free space after the first analysis, works with no internet, no account and no card, and the first analysis shows
"Setting up the engine (one time)..." for 10-30 s. Not done, by choice or scope: publishing (needs a public source repo for the
GPLv3 source offer, `SOURCE_REPO_URL` is a placeholder, a Play bundle needs asset packs because of the size), the owner decision on
board colours, and a run on a physical phone by an agent (the owner tested one earlier). Release steps: `docs/PUBLISHING.md`.

---

## ROUND 13 (read this before the older sections below)

> **History.** The "bundled in the APK" and "no INTERNET" rules below were Round 13's; the owner replaced them on
> 2026-10-06 with the small installer (D-track above).

The owner tested the app on a real phone and found it **too complex**, so Round 13 is a simplification
plus local-only hardening. **The sections further down describe the app as it stood after Round 11 and
are partly out of date** (the neural-voice and download bullets are marked as history where they are
kept). Truth order: the tree, then `RUN_LOG.md` (latest entries), then `RUN_PLAN.md` ("Round 13"
onwards), then this file.

**Owner rules for Round 13:**
- Everything works **locally, with no external APIs, no account, no credit card.** Google Cloud voice is **removed.**
- A big APK is fine. The Stockfish net and the Kokoro voice are **bundled in the APK** (design: `docs/BUNDLED_MODELS_DESIGN.md`).
- **Chess.com is the UI/UX reference** for any design decision not derived from the owner's goals; reference only, no assets or copy (`CLAUDE.md`, "Design reference"; audit: `docs/CHESSCOM_REFERENCE_ALIGNMENT.md`).
- Complex design work goes to a Fable 5.1 / high agent; **one `./gradlew` at a time**.

**Designs (all approved):** `docs/MOBILE_UX_DESIGN.md` (U1-U10), `docs/PRACTICE_DESIGN.md` (P1-P5),
`docs/BUNDLED_MODELS_DESIGN.md` (B0-B5), `docs/CHESSCOM_REFERENCE_ALIGNMENT.md` (R-queue in RUN_PLAN).

**Done and verified (details in RUN_LOG):**
- Cloud voice removed and backup rules fixed; shared-core fixes (mate sign, lenient PGN); video pacing tiers and tactic-noise cleanup.
- UX U1-U8: copy, navigation landing on the Summary, Home, Analysing, Summary hub with the side chooser, Board, Video, Walkthrough. Practice core and the Practise screens (P1-P4).
- **R1b** (commentary claims): every annotation text and walkthrough of both recorded games is re-derived with python-chess (`docs/COMMENTARY_AUDIT.md`); 119 wrong claims found and fixed (49 of 78 annotation texts and 15 of 16 walkthroughs were wrong before, 0 after). Video budget for tiny games fixed (4 moves: 236 s to 54 s). Card text is a pure function of annotation plus the chosen side.
- **R2** (board badge, "Next key moment", single-word class names, Video U7, Walkthrough U8). **R3** (Practise screens P3+P4; puzzle counts depend on live engine depth: Immortal 3, chesscom as Black 2, chesscom as White 0).
- **R4a, bundled models (B1-B3):** the net and the Kokoro voice are stored assets in the APK, set up once on the first analysis ("Setting up the engine (one time)..."). `BundledNetProvider` (`:engine`), `BundledVoiceInstaller` and `FirstRunSetup` (`:app`). No download code, no OkHttp, no tier picker, no Piper, and the manifest has **no INTERNET and no ACCESS_NETWORK_STATE** (host `ManifestPermissionsTest` plus instrumented `NoNetworkPermissionTest`; on API 34 a socket without INTERNET fails with EPERM). `NarrationVoiceSettings.provider` defaults to `NEURAL`.
- **R4b / U9, Settings:** four visible rows (Your name, Language, Advanced, About) plus Advanced (Analysis strength Quick/Standard/Deep, what the review talks about, a built-in-voice switch, saved narration audio). Depth and the voice switch were checked end to end on the emulator.
- **R6a (U10 and three small features):** accessibility, large-font (2.0), landscape and RTL pass over every screen; a measured "About N min left" on the export's narration step; a Share action (FileProvider, no permission) on the export-complete dialog; the doubled Stockfish line in About fixed. Details in RUN_LOG (R6a). Conventions for future UI work are in CLAUDE.md ("Accessibility conventions").
- **R6b (recap end card, "moves" wording):** an exported MP4 now ends with a silent 4-6 s recap card (both names, accuracy as the Summary writes it, the `GameSummarySentence`, "Biggest moment: move N ..." only when that move lost 20+ win-percent, move-quality chips). It is `VideoScript.recap` (`GameRecap` in `:core`), not a segment, so the pacing budget, segment count and time-left are unchanged (ANALYSIS_SPEC 9.7); the in-app player does not show it. The video title card says "17 moves", not "34 plies". Pure text-fit and layout logic in `video/RecapCard.kt`. Evidence and open items (intro card overflows with 25-character names, he-IL export not run) in RUN_LOG (R6b).
- **R6c (title-card polish):** the video's intro and final-numbers cards now fit any realistic name (`video/CardLayout.kt`, pure: `layoutTitle`, `fitParagraphToHeight`, `CardGeometry`, `CardContents`; reuses `fitLine` / `fitParagraph` from `RecapCard.kt`). Intro = "Name (rating) vs Name (rating)" (stacks to two lines for long names), one subtitle line "1-0 · 17 moves · Opening (ECO)", one accuracy line; each fact once; intro and final numbers carry no caption bar; accuracy on cards is whole percent like the Summary and the recap (`recapAccuracyText` is the one function the Summary and the recap share); names are bidi-isolated. Evidence and open items (the Summary screen's own Hebrew title still swaps the names) in RUN_LOG (R6c).
- **R4c / B4 (earlier step):** docs, licence texts and About strings brought in line with the above; the espeak-ng data is now credited (see "Licences" below); the two device-staging push scripts are deleted.

**Test baselines at the last verification (counts read from the result XML):**
`:core` 490/0/0 skipped, `:app` unit 229/0/0 (R6c), `:app` instrumented 103/0/0 skipped (R6c), `:engine` instrumented 20/0/0 skipped,
`lintDebug` 0 errors (70 warnings). The one `:core` failure seen during R4a was a 2 s wall-clock assertion
(`OpeningBookTest`); it is now a 15 s catastrophic-regression guard. No instrumented test asserts on About or licence text.

**Licences (informational for this POC, not legal conclusions):** sherpa-onnx and Kokoro-82M are Apache 2.0. The
`espeak-ng-data` folder inside the Kokoro archive has no licence file of its own; the espeak-ng project's README
says the project is "GPL version 3 or later", and that is what About says, attributed to the project. The exact
terms of every file in that folder were not checked file by file (a sherpa-onnx issue asking for its provenance,
k2-fsa/sherpa-onnx#4002, was open and unanswered when checked). The NNUE net's provenance (Leela Chess Zero
data, ODbL, per the Stockfish README) is credited in About. Whether any ODbL notice wording is required was not assessed.

**Known open items:** green/board colours are close to chess.com's brand values (owner decision before any public
listing); `SOURCE_REPO_URL` is still a placeholder; nothing has been run on a physical phone by an agent (the owner
did test one); B5 and R7 are **done** (see FINAL STATE above); a narrated export on the
bundled copy was run end to end offline in R7; engine-line motifs remain heuristics (R1b notes);
unmuting a device-TTS segment mid-sentence stays silent until the next segment.

**PC video producer (`desktop/`, `pc/`, `docs/PC_PRODUCER_DESIGN.md`): paused.** P0 (analysis) and P1 (first
end-to-end video) are done; P2 and the LLM spike were stopped. Nothing in the app depends on it.

---

## What this is

Android app: import a chess PGN (file or Android share) → analyse every ply with on-device
Stockfish 19 → chess.com-style move grades, tactics found/missed for **both** players, a
missed-tactic walkthrough, a game report with accuracy and estimated rating → and a **narrated
video review** (GothamChess-style) that plays in-app and exports to MP4.

Owner: **Dor Amit**, contact `Chess@palaya.net`, brand **Palaya**. Deliverable is a working signed
APK. **It is a proof of concept, not a commercial product** — licence constraints on models are
informational only; the model can be swapped later if that changes.

Location: `C:\Claude\ChessAnalyzer`.

---

## Verify state in this order

```bash
cd /c/Claude/ChessAnalyzer
scripts/fetch_stockfish.sh                # Stockfish source (not committed), first build only
./gradlew :core:test                      # expect 490 tests, 0 skipped
./gradlew :engine:testDebugUnitTest       # expect 32 tests, 0 skipped
./gradlew :app:testDebugUnitTest          # expect 455 tests, 0 skipped (D2f)
./gradlew :app:lintDebug                  # expect 0 errors, 69 warnings
./gradlew :app:assembleDebug              # expect exit 0 WITHOUT the model files; app-debug.apk ~114 MB, no .nnue/.tar/.tar.gz inside
scripts/fetch_models.sh                   # the net + the voice .tar.gz into vendor/models/ (not committed): needed for the
                                          # instrumented tests (seed assets of the TEST APK), the local test server and publishing
# emulators (chess36 cold-boots in ~2 min with WHPX; chess34 can sit "offline" 10+ min under swiftshader — normal)
"$ANDROID_HOME/emulator/emulator" -avd chess36 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect &
./gradlew :app:connectedDebugAndroidTest  # expect 160 tests, 0 skipped, on chess36 and on chess34, one device at a time; ~13-15 min each
./gradlew :engine:connectedDebugAndroidTest   # expect 20 tests, 0 skipped
python scripts/verify_tactic_references.py fixtures/tactic_references.json   # expect exit 0, "28 verified, 0 rejected"
```

Test-count claims are worthless without `skipped="0"` — `assumeTrue` has silently voided a whole
suite in this project before. Tally it mechanically rather than reading the log:

```bash
python - <<'PY'
import glob, xml.etree.ElementTree as ET
for p in glob.glob("app/build/outputs/androidTest-results/connected/debug/*.xml"):
    r = ET.parse(p).getroot()
    print(r.get("tests"), r.get("failures"), r.get("errors"), r.get("skipped"))
PY
```

---

## Architecture in one screen

| Module | What |
|---|---|
| `:core` | Pure Kotlin/JVM. Chess rules (perft-verified), PGN, analysis model, tactics, narration script. **No Android imports** — keep it that way. |
| `:engine` | Stockfish 19 compiled via NDK/CMake → `libstockfish.so` + JNI UCI bridge + `NetStore` (the NNUE net downloaded on first run into `filesDir/nets/`, verified before the engine may load it). |
| `:app` | Compose UI, PGN intake, orchestration, narrated video (render/export/playback), first-run Setup download and Check for updates (`data/models/`). |

`docs/ANALYSIS_SPEC.md` is the authoritative scoring/tactics spec. `core/.../analysis/Contract.kt`
and `core/.../narration/NarrationContract.kt` are the shared type contracts and win over prose.

---

## What is DONE and verified on-device

- Chess core: perft exact (depth 5 on start + Position 3, depth 4 on the other three standard positions).
- Stockfish running on Android: 9/9 instrumented, real searches, finds mate in 1.
- Full pipeline: PGN → analysis → report, re-verified Round 5 against the **signed release APK**
  from a clean install, again in R7 (bundled 1.0), D1, and in D2f on the 1.1 release APK after an update from
  1.0 (RUN_LOG D2f). `EndToEndAnalysisTest` covers it with the net seeded from the test APK.
- UI: import (file/paste/**share intent**), review with classified moves + commentary, game report
  with the **four tactic buckets**, missed-tactic simulation, settings, About.
- Narrated video: script generation, in-app playback, **MP4 export** (H.264+AAC, verified playable).
- **Export runs in a foreground service** (`VideoExportService`, FGS type `dataSync`), so leaving the
  video screen or backgrounding the app no longer cancels it. Verified on-device, not just by test:
  `dumpsys activity services` showed `isForeground=true foregroundId=4101 types=00000001` (= DATA_SYNC)
  with the ongoing `video_export_progress` notification and its Cancel action, and the export
  completed after the Activity was destroyed mid-render. It also completes with POST_NOTIFICATIONS
  **denied** (confirmed `granted=false` on a fresh install) — notifications are cosmetic, never a gate.
- **On-device neural TTS (sherpa-onnx) — genuinely verified in Round 5 (history: it was Piper then; Piper is gone).**
  The synthesized WAV was pulled off the device and analysed on the host: 5.097 s, 22050 Hz mono, RMS 1705.7,
  peak −4.5 dBFS, 26% near-silent 50 ms windows and a speech-shaped envelope. A sample is kept at
  `docs/screenshots/r5_neural_tts_sample.wav` — listen to it rather than taking this on faith.
- **The neural voice is the default by construction (R4a).** `NarrationVoiceSettings.provider` defaults to
  `NEURAL` and `ResolvedProvider` maps a missing or legacy `CLOUD` value to it. Since 1.1 the voice is
  downloaded once on the Setup screen (a `.tar.gz` since D2f); until it is in, the phone's voice narrates and
  Video says so. Settings has one switch to use the phone's built-in voice instead.
- **Kokoro verified (Round 6) and now the only voice tier.** The tier had never once produced audio;
  `sid` was hard-coded to 0 and `length_scale` was never set. Fixed, and proven the same way Piper
  was: WAV pulled off the device and measured on the host — **24000 Hz mono, 4.825 s, RMS 1758.4
  (−25.4 dBFS), peak −8.1 dBFS, 22% near-silent windows**. Default speaker is `af_bella` (sid 1) at
  `length_scale` 1.20 (169 wpm, within 1.2% of the Piper baseline's measured 166 wpm).
- **`docs/voice_samples/`** — the same chess paragraph through all 11 Kokoro speakers + Piper, plus
  a `length_scale` sweep, so the voice can be chosen **by ear**. Changing it is one constant
  (`KOKORO_DEFAULT_SPEAKER_ID`). Start here; see its README.
- **Exported MP4s really carry the selected neural voice (Round 11).** For several rounds
  `VideoScreen.startExport()` passed `null` as the provider, so every exported video was narrated
  by the **device** voice while in-app playback (a different path) used the neural one — the app
  sounded right and only the artifact anyone would judge was wrong. Fixed in Round 8/9
  (`VideoScreen.kt:293`); *proved* in Round 11, on the host, from pulled artifacts: a Kokoro
  reference sentence cross-correlates **0.982** against the provider-backed export and **0.176**
  against a `null`-provider export of the same script. Device TTS on this emulator produces real
  speech (rms 3534.6), so that 0.176 discriminates Kokoro from a genuine alternative voice, not
  from silence. Two coverage gaps closed with it: the `VideoExportService.start()` → `export()`
  forwarding (the seam the `null` actually sat above, previously untested) and the
  real-Kokoro-through-export composition, which was assumed for three rounds while false.
- Branding: Palaya logo/icon, About with owner + licences.
- **i18n groundwork (Round 10).** Every narration sentence in `:core` is a typed `Sentence` rendered
  by a `NarrationStrings` locale (`EnglishNarration` is the only one); the generator holds no prose.
  `docs/NARRATION_STRINGS.md` is the 123-row table a translator receives. Settings has a Language
  option (English only; per-app language is API 33+ only — no AppCompat, deliberately). RTL was
  rendered for real via a per-app `he-IL` locale: the board does not mirror, the move list is pinned
  LTR after a bidi defect (".3", "0.4+") was found and fixed. Screenshots `docs/screenshots/r10_*`.
- Signed release APK builds and runs (v2+v3 signatures), cert `ca4f7b42…` unchanged.

- **Google Cloud voice REMOVED (Round 13, tasks 60-62).** The opt-in Cloud TTS provider, its setup
  wizard, `CLOUD` provider choice and all API-key storage (EncryptedSharedPreferences and the
  plaintext `narration_key_fallback_unencrypted` fallback) are deleted. Reason: Google requires
  billing (a payment method) even for the free tier, which violates the owner's "free, no credit
  card, local only" rule. Narration has two providers, Device and Neural. A persisted `CLOUD`
  provider reads as Device (`ResolvedProvider`), and `LegacyKeyStoragePurge` deletes stale key
  files at startup. Backup rules now exclude `tts_models/`, `narration/`, `eval_cache/`, the NNUE
  net and the narration prefs. Older notes in RUN_LOG.md about the Cloud voice are history.

## What is NOT done / NOT verified

0. **Hebrew device TTS is unverified and probably unavailable on this engine build.** Google TTS on
   the emulator answers `LANG_NOT_SUPPORTED` for `he-IL`, enumerates zero Hebrew voices, and its own
   download manifest lists no Hebrew pack — so the "device TTS is the Hebrew default" decision
   rests on nothing yet. Needs a real Hebrew-locale device with current Google TTS (RUN_LOG Round 10).
1. **Nobody has listened to the narration.** The audio is proven non-silent and speech-shaped by
   measurement, but no human has confirmed it says the right words or sounds good. This is now the
   single most valuable thing anyone can do: `docs/voice_samples/` has 12 voices of the same
   paragraph waiting to be compared. Round 11 added the first playable audio *of an actual export*
   — `docs/voice_samples/r11_exported_mp4_narration_kokoro.wav` and `..._device.wav`, decoded from
   the two exported MP4s — but that is the synthetic test script, not a real review, so this item
   stays open.
2. **Never run on physical hardware.** All timings are from a software-rendered emulator.
3. `SOURCE_REPO_URL` is a visible placeholder in About — GPLv3 requires offering source (owner to-do at the top).
4. Two originally-planned tests never written: simulation flow and share-intent parsing. (The download has its own
   suites since D2b-D2e.)
5. **The "Narration ready" copy defect is already fixed in the tree** — `video_prepare_narration_done_body`
   now reads "reuse it instead of synthesizing it again", not "instead of calling the API again".
   Earlier handoffs listed this as outstanding; it is not. Whether the last *signed release APK*
   that was verified end-to-end carries the old or the new string has NOT been checked.
6. **The release build has never downloaded from the real GitHub URL**: the repo and the `models-2026.10` release
   do not exist yet (owner to-do at the top). Everything else about the download was run against local servers.

---

## History: the 365 MB APK (1.0, R7)

Version 1.0 (R7, `dist/PalayaChess-1.0-release.apk`, 364,733,235 B) carried the net and the Kokoro voice as stored
assets so it worked offline from the first launch with no network permission (owner decision, Round 13). D2
(owner decision 2026-10-06, `docs/MODEL_DOWNLOAD_DESIGN.md`) moved both out for Google Play: 1.1 is ~36 MB for
arm64 and downloads 201 MB once, on a tap. Current sizes and facts: `docs/PUBLISHING.md` §0 and §3. Installing
1.1 over 1.0 keeps the models 1.0 already set up (no download; proven in D2f).

---

## Live environment state

- Emulator AVDs `chess34` (API 34) and `chess36` (API 36), x86_64. May or may not be running.
- Models are fetched into the gitignored `vendor/models/` by `scripts/fetch_models.sh`: `engine-assets/nnue/<net>`,
  `app-assets/tts/kokoro-int8-en-v0_19.tar.gz` and, in `.cache/`, the upstream `.tar.bz2` and the plain tar it
  was made from. The app build does not need them; the test APKs carry them as seed assets.
- Release keystore at `keystore/chessanalyzer-release.jks`, creds in gitignored `keystore.properties`, and the
  model-manifest signing key `keystore/models-signing.pem`. **Losing these means never updating the listing
  (or the models).** Never read or print them.
- The repo is under git (last commit `0479daf`, D1/D0); F1 and D2a-D2f are uncommitted. `vendor/models/*` is gitignored except `MODELS.lock` and `manifest_public_key.der`. There is no remote yet.

---

## Hard-won traps (full list in CLAUDE.md)

- **Never run two `./gradlew` invocations against `app/build` at once** — it corrupted the D8 dex
  cache and produced a `NoClassDefFoundError` that looked exactly like a source bug.
- **Gradle uninstalls the app after `connectedDebugAndroidTest`.** That wipes
  `/sdcard/Android/data/<pkg>/`, so any evidence file a test wrote there is gone before you can pull
  it — and `run-as` then reports "unknown package", which looks like a permissions problem and is
  not. To keep an artifact: `adb install` both APKs by hand and run `adb shell am instrument`
  directly, bypassing Gradle.
- Stockfish calls `exit()` on a bad net and takes the whole app down — the guards in
  `StockfishEngine.setEvalFile()`/`analyze()` are load-bearing.
- One `StockfishEngine` per process (the JNI bridge dup2s process-global stdin/stdout).
- Git Bash mangles `adb push /data/local/tmp/...` — use PowerShell or `MSYS_NO_PATHCONV=1`.
- `TextToSpeech.setSpeechRate()` only affects the **next** `speak()` — not the utterance in flight.
- **Instrumented tests share one real DataStore.** A test that needs a pristine preference must
  establish it (see `clearProviderChoiceForTesting`), not assume it — another test's cleanup will
  have written to it.
- **Don't trust `narrationVoiceSettings.value` for a decision.** It is `stateIn(..., Eagerly,
  NarrationVoiceSettings())`, so it serves a *default* until DataStore's first emission lands. Read
  `narrationSettingsRepository.current()` when correctness depends on the stored value.

---

## Suggested next steps

1. **Listen to `docs/voice_samples/`** and pick the narration voice. Everything else about the
   voice is measured; this is the one thing that cannot be. The recommendation is `af_bella`;
   `am_michael` is the male alternative. Changing it is one constant.
2. The owner to-do list at the top of this file (repo, publish the models, policy, Play account).
3. Then the release APK against the real GitHub release (fresh install + update check), the one D-track check
   that is still blocked.
4. Commit the work (F1, D2a-D2f are uncommitted).
5. The two missing tests; a physical-device run (none yet by an agent).
6. Round 14 queue after D2: V1, V3, V2, G1, C1, C2 (`RUN_PLAN.md`).
