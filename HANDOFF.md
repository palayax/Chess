# HANDOFF — Palaya Chess

Entry point for a fresh session. Read this first, then `RUN_PLAN.md` (task breakdown) and
`RUN_LOG.md` (what was built, what was verified, and every defect found). `CLAUDE.md` holds the
gotchas that cost real time — read it before touching the build, the engine, or the emulator.

**Do not trust this file over the tree.** Everything below is claimed state; verify it by building
and running the tests before building on it. This is not boilerplate caution — Round 5 found that
the previous handoff's central claim ("neural TTS verified") rested on evidence that had never
actually been collected. The conclusion happened to be right; the proof was not there.

---

## D-TRACK: GOOGLE PLAY (2026-10-06) — read this first

The owner will publish on Google Play. **D1 (build Play-ready) is done** (RUN_LOG "D1", RUN_PLAN "D-track");
D0 (moving the two models to a first-run download) is a separate design, `docs/MODEL_DOWNLOAD_DESIGN.md`. The
models are still in the APK.

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
scripts/fetch_models.sh                   # the NNUE net + Kokoro voice into vendor/models/ (not committed); the build fails loudly without them
./gradlew :core:test                      # expect 490 tests, 0 skipped
./gradlew :app:testDebugUnitTest          # expect 237 tests, 0 skipped (D1)
./gradlew :app:assembleDebug              # expect exit 0; the APK is ~371 MB (debug) and both assets are Stored
# emulator (cold boot takes 10+ min, sits "offline" the whole time — this is normal)
"$ANDROID_HOME/emulator/emulator" -avd chess34 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect &
./gradlew :app:connectedDebugAndroidTest  # expect 104 tests (D1), on chess34 and on chess36; CHECK skipped="0" in the XML. Pushes a ~371 MB APK, takes 20-40 minutes. Nothing to stage: the models are in the APK
python scripts/verify_tactic_references.py fixtures/tactic_references.json   # expect exit 0, "28 verified, 0 rejected"
./gradlew :engine:connectedDebugAndroidTest   # expect 20 tests, 0 skipped
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
| `:engine` | Stockfish 19 compiled via NDK/CMake → `libstockfish.so` + JNI UCI bridge + the bundled NNUE net (an asset, copied once to `filesDir`). |
| `:app` | Compose UI, PGN intake, orchestration, narrated video (render/export/playback). |

`docs/ANALYSIS_SPEC.md` is the authoritative scoring/tactics spec. `core/.../analysis/Contract.kt`
and `core/.../narration/NarrationContract.kt` are the shared type contracts and win over prose.

---

## What is DONE and verified on-device

- Chess core: perft exact (depth 5 on start + Position 3, depth 4 on the other three standard positions).
- Stockfish running on Android: 9/9 instrumented, real searches, finds mate in 1.
- Full pipeline: PGN → analysis → report, re-verified Round 5 against the **signed release APK**
  from a clean install. (History: that build still downloaded the net. Since R4a the net is bundled and
  the same path is exercised by `EndToEndAnalysisTest` with no seeding; no signed release APK has been
  re-run since bundling, which is B5.)
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
  `NEURAL` and `ResolvedProvider` maps a missing or legacy `CLOUD` value to it. The voice is unpacked from the
  APK during the first-run setup; there is nothing to auto-download. Settings has one switch to use the
  phone's built-in voice instead.
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
3. `SOURCE_REPO_URL` is a visible placeholder in About — GPLv3 requires offering source.
4. Two originally-planned tests never written: simulation flow and share-intent parsing. (The net-download test is moot: there is no download.)
5. **The "Narration ready" copy defect is already fixed in the tree** — `video_prepare_narration_done_body`
   now reads "reuse it instead of synthesizing it again", not "instead of calling the API again".
   Earlier handoffs listed this as outstanding; it is not. Whether the last *signed release APK*
   that was verified end-to-end carries the old or the new string has NOT been checked.
6. **APK is ~365 MB by design** (371 MB debug), see below.

---

## The APK is ~365 MB by design — read this before "optimising" it

The owner chose "a big APK is fine" in Round 13, so the app works fully offline from the first launch.
Debug APK: **371,058,258 bytes** (measured, R4a). A release APK is estimated at about 365 MB (not yet built since bundling).
What is in it:

- the **NNUE net**, 98,511,183 bytes, a stored (uncompressed) asset in `:engine`;
- the **Kokoro voice** as one plain `.tar`, 158,269,440 bytes, a stored asset in `:app` (the sherpa-onnx `tar.bz2` is decompressed at fetch time, so the phone does no bzip2 work);
- ~89 MB of sherpa-onnx native libraries for three ABIs (the old 108 MB build's bulk), plus Stockfish and the app itself.

`classes.dex` stays deflated; only `.nnue` and `.tar` are stored (`androidResources.noCompress`). Do not widen that to everything.
On first launch the app copies the net and unpacks the voice into `filesDir` (about 257 MB more), so the
install needs roughly 620 MB of steady storage and more at peak. The Play Store would **need Play Asset Delivery**
for this (its base-module limit is far below this size); the deliverable here is a sideloaded APK, so none of
that is done. Per-ABI splits would remove ~35-50 MB per device but are not the lever that matters any more.

---

## Live environment state

- Emulator AVD `chess34` (API 34, x86_64). Cold boot 10+ min. May or may not be running.
- Models are fetched once into the gitignored `vendor/models/` by `scripts/fetch_models.sh` and bundled by
  the build; nothing is staged on the device any more (the old device-staging push scripts are deleted).
- Release keystore at `keystore/chessanalyzer-release.jks`, creds in gitignored `keystore.properties`.
  **Losing these means never updating the listing.**
- The repo is under git (history starts at "Import the rest of the tree: Palaya Chess through Round 11"); Round 13 work is still uncommitted. `vendor/models/*` is gitignored except `MODELS.lock`.

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
2. B5: **done in R7** (signed release APK, offline cold-install proof, narrated MP4 measured).
3. State the ~365 MB size on any download page; Play would need Play Asset Delivery.
4. Commit the repo: `vendor/models/MODELS.lock` and `scripts/fetch_models.sh` are tracked, the models are not.
5. The two missing tests; physical-device run.
6. The export foreground service is `dataSync` because compileSdk is 34. If the project moves to
   compileSdk/targetSdk 35+, switch it to the better-fitting `mediaProcessing` type and its
   permission (`VideoExportService`'s class doc records why).
