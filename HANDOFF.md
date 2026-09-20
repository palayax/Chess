# HANDOFF — Palaya Chess

Entry point for a fresh session. Read this first, then `RUN_PLAN.md` (task breakdown) and
`RUN_LOG.md` (what was built, what was verified, and every defect found). `CLAUDE.md` holds the
gotchas that cost real time — read it before touching the build, the engine, or the emulator.

**Do not trust this file over the tree.** Everything below is claimed state; verify it by building
and running the tests before building on it. This is not boilerplate caution — Round 5 found that
the previous handoff's central claim ("neural TTS verified") rested on evidence that had never
actually been collected. The conclusion happened to be right; the proof was not there.

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
./gradlew :core:test                      # expect 279 tests, 0 skipped
./gradlew :app:assembleDebug              # expect exit 0
# emulator (cold boot takes 10+ min, sits "offline" the whole time — this is normal)
"$ANDROID_HOME/emulator/emulator" -avd chess34 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect &
scripts/push_test_net.sh                  # stage the NNUE net so engine tests don't download 98 MB
scripts/push_voice_models.sh              # stage BOTH neural-TTS model archives (Piper + Kokoro)
./gradlew :app:connectedDebugAndroidTest  # expect 59 tests; CHECK skipped="0" in the XML
python scripts/verify_tactic_references.py fixtures/tactic_references.json   # expect exit 0, "28 verified, 0 rejected"
./gradlew :engine:connectedDebugAndroidTest
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
| `:engine` | Stockfish 19 compiled via NDK/CMake → `libstockfish.so` + JNI UCI bridge + NNUE net download. |
| `:app` | Compose UI, PGN intake, orchestration, narrated video (render/export/playback). |

`docs/ANALYSIS_SPEC.md` is the authoritative scoring/tactics spec. `core/.../analysis/Contract.kt`
and `core/.../narration/NarrationContract.kt` are the shared type contracts and win over prose.

---

## What is DONE and verified on-device

- Chess core: perft exact (depth 5 on start + Position 3, depth 4 on the other three standard positions).
- Stockfish running on Android: 9/9 instrumented, real searches, finds mate in 1.
- Full pipeline: PGN → analysis → report, re-verified Round 5 against the **signed release APK**
  from a clean install (so the real 98 MB net download ran).
- UI: import (file/paste/**share intent**), review with classified moves + commentary, game report
  with the **four tactic buckets**, missed-tactic simulation, settings, About.
- Narrated video: script generation, in-app playback, **MP4 export** (H.264+AAC, verified playable).
- **Export runs in a foreground service** (`VideoExportService`, FGS type `dataSync`), so leaving the
  video screen or backgrounding the app no longer cancels it. Verified on-device, not just by test:
  `dumpsys activity services` showed `isForeground=true foregroundId=4101 types=00000001` (= DATA_SYNC)
  with the ongoing `video_export_progress` notification and its Cancel action, and the export
  completed after the Activity was destroyed mid-render. It also completes with POST_NOTIFICATIONS
  **denied** (confirmed `granted=false` on a fresh install) — notifications are cosmetic, never a gate.
- **On-device neural TTS (sherpa-onnx + Piper) — genuinely verified in Round 5.** The synthesized
  WAV was pulled off the device and analysed on the host: 5.097 s, 22050 Hz mono, RMS 1705.7,
  peak −4.5 dBFS, 26% near-silent 50 ms windows and a speech-shaped envelope. A sample is kept at
  `docs/screenshots/r5_neural_tts_sample.wav` — listen to it rather than taking this on faith.
- **The neural voice is now the actual default**, not just nominally: it auto-provisions the first
  time narration is wanted. Confirmed in the release build — Settings showed "Natural voice
  (on-device)" selected and Piper installed at 35.6 MB without the user ever opening Settings, and
  logcat showed sherpa-onnx loading `.../tts_models/piper/en_US-ljspeech-medium.onnx`.
- **Kokoro verified and made the default tier (Round 6).** The tier had never once produced audio;
  `sid` was hard-coded to 0 and `length_scale` was never set. Fixed, and proven the same way Piper
  was: WAV pulled off the device and measured on the host — **24000 Hz mono, 4.825 s, RMS 1758.4
  (−25.4 dBFS), peak −8.1 dBFS, 22% near-silent windows**. Default speaker is `af_bella` (sid 1) at
  `length_scale` 1.20 (169 wpm, within 1.2% of the Piper baseline's measured 166 wpm).
- **`docs/voice_samples/`** — the same chess paragraph through all 11 Kokoro speakers + Piper, plus
  a `length_scale` sweep, so the voice can be chosen **by ear**. Changing it is one constant
  (`KOKORO_DEFAULT_SPEAKER_ID`). Start here; see its README.
- **The 98.5 MB Kokoro download is gated to unmetered networks** (`decideAutoVoice` +
  `ConnectivityNetworkCostProbe`, which fails closed to metered). On metered the automatic path
  tops out at Piper's 20 MB; device TTS is the floor. The probe is injectable so both sides are
  tested without a real cellular connection.
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
1b. **The full Kokoro auto-download has never run end to end.** The gate's decision is tested at
   every combination and Kokoro synthesis is proven from a pushed archive, but no run has let
   `ensureDefaultNeuralVoice()` actually pull 98.5 MB from GitHub and narrate a whole game with it.
   The HTTP path is unchanged from Round 5 and the verify/extract/install tail is exercised — but
   the composition is inferred, not observed.
2. **Never run on physical hardware.** All timings are from a software-rendered emulator.
3. `SOURCE_REPO_URL` is a visible placeholder in About — GPLv3 requires offering source.
4. Three originally-planned tests never written: simulation flow, share-intent parsing, net download.
5. **The "Narration ready" copy defect is already fixed in the tree** — `video_prepare_narration_done_body`
   now reads "reuse it instead of synthesizing it again", not "instead of calling the API again".
   Earlier handoffs listed this as outstanding; it is not. Whether the last *signed release APK*
   that was verified end-to-end carries the old or the new string has NOT been checked.
6. **APK is 108 MB** (was 17 MB). Not a defect, but a consequence worth a decision — see below.

---

## The APK is now 108 MB — read this before "optimising" it

`app-release.apk` = **108,627,896 bytes**. The NNUE net is **not** in it (that rule still holds).
~89 MB is sherpa-onnx's native libraries — `libonnxruntime.so` alone is 24.4/21.2/14.7 MB across
x86_64/arm64-v8a/armeabi-v7a, stored uncompressed.

**Per-ABI splits are the fix** and would put an arm64 device at roughly 51 MB. Not done here because
the deliverable is a single signed APK and this is a POC. Do this first if it ever ships.

---

## Live environment state

- Emulator AVD `chess34` (API 34, x86_64). Cold boot 10+ min. May or may not be running.
- NNUE net cached at `%TEMP%\claude\...\scratchpad\nn-1a298aa575a0.nnue` — `scripts/push_test_net.sh` stages it.
- **Both** voice-model archives staged on device in `/data/local/tmp/`:
  `vits-piper-en_US-ljspeech-medium-int8.tar.bz2` (21 MB) and `kokoro-int8-en-v0_19.tar.bz2`
  (103 MB). The neural-TTS instrumented tests require them and **fail loudly** if absent, by
  design. `scripts/push_voice_models.sh` stages both.
- Release keystore at `keystore/chessanalyzer-release.jks`, creds in gitignored `keystore.properties`.
  **Losing these means never updating the listing.**
- Repo has **zero commits** — nothing is under version control yet. Consider `git add`/commit early.

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
2. Rebuild + re-verify the signed APK — the last one predates several source changes.
3. Per-ABI splits if the 108 MB size matters.
4. Commit the repo — it still has zero commits.
5. The three missing tests; physical-device run.
6. The export foreground service is `dataSync` because compileSdk is 34. If the project moves to
   compileSdk/targetSdk 35+, switch it to the better-fitting `mediaProcessing` type and its
   permission (`VideoExportService`'s class doc records why).
