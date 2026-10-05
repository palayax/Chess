# On-device neural narration (sherpa-onnx + Kokoro)

The second [`NarrationVoiceProvider`](../app/src/main/kotlin/net/palaya/chessanalyzer/video/NarrationVoiceProvider.kt),
alongside `DeviceTtsProvider` (Android's built-in TTS):
`NeuralTtsProvider`, backed by [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (k2-fsa,
Apache 2.0) running entirely on-device. Free, fully offline, no per-user API key, no cloud
redistribution question, and strictly better than the device voice at no ongoing cost, which is why it
is the default **by construction**: `NarrationVoiceSettings.provider` defaults to `NEURAL`, and
`ResolvedProvider.from()` maps a missing or legacy `CLOUD` value to it. Settings > Advanced has one
switch, "use the phone's built-in voice instead", for anyone who prefers the device voice.

**The voice model is inside the APK** (Round 13): the Kokoro model ships as one stored `.tar` asset, is
unpacked once on the first analysis ("Setting up the engine (one time)..."), and the app has **no network
permission at all**. There is no download, no Wi-Fi-only logic, no tier picker, no "Check for updates", no
API key, and no Google Cloud voice. The APK is about 365 MB because of it.

> **Removed in Round 13:** the opt-in Google Cloud Text-to-Speech provider (it needs a billing account even
> for the free tier, against the owner's "free, no credit card, local only" rule), the Piper tier (it only
> existed as the metered-network fallback), and every download path. Narration has exactly two providers,
> Device and Neural, both on-device.

**There is one neural voice: Kokoro** (`af_bella`, `length_scale` 1.20) - see *Choosing the voice* and
*Pacing* below.

## Why this fits the existing pipeline unchanged

Narration in this app is **pre-generated**, not spoken in real time: `NarrationCoordinator` +
`NarrationStore` synthesize a whole `VideoScript` ahead of playback/export, and everything
downstream (sentence-level caching, AAC encoding for video export, `MediaPlayer` playback) only
ever reads finished WAV files. That means:

- The usual "TTS models are too slow for realtime" objection doesn't apply — a slower, better
  model (Kokoro) costs a few extra seconds once per sentence, not a stall on every screen.
- `NeuralTtsProvider` only had to implement the existing `prepare()` / `synthesize()` / `release()`
  contract and write a plain 16-bit PCM mono WAV via the existing `WavUtil` — no changes were
  needed to `VideoExporter`, `NarrationCoordinator`, `NarrationStore`, or playback.

## Components

- **`BundledVoiceInstaller`** (`app/.../video/BundledVoiceInstaller.kt`) - unpacks the bundled voice
  archive (`assets/tts/kokoro-int8-en-v0_19.tar`, a plain tar of 158,269,440 bytes, stored uncompressed
  in the APK) into `filesDir/tts_models/kokoro/`. It reads the asset as a stream through a digest, extracts
  into a scratch directory with a zip-slip guard, checks the pinned SHA-256 **after** the pass and before
  anything moves into place, atomically renames the directory, and writes the `.provisioned` marker last.
  The marker holds this build's pinned tar hash, so a model bump re-extracts automatically. It replaced
  the old `VoiceModelProvisioner` (download, bzip2, tiers). Lives under `filesDir`, not `cacheDir`, so it
  is never evicted, and is excluded from Auto Backup because the APK can regenerate it.
- **`FirstRunSetup`** (`app/.../data/`) - runs the net copy and the voice unpacking once, before the first
  analysis, with byte-weighted progress. Failures are inline and specific: "needs about 260 MB of free
  space" or "the app's built-in engine files are damaged. Reinstall".
- **`NeuralTtsProvider`** (`app/.../video/NeuralTtsProvider.kt`) - loads the unpacked model directory via
  sherpa-onnx's `OfflineTts` (Kotlin API, `newFromFile`, absolute paths) and turns text into a WAV. Fails
  cleanly (`SynthesisResult.Failure`, never throws) when the model is missing or inference errors, so
  `NarrationCoordinator`'s mandatory fallback to `DeviceTtsProvider` still covers it, with no silent gaps.
- **Settings UI** (`ui/screens/SettingsScreen.kt`) - Advanced holds the built-in-voice switch and the
  saved-narration-audio clear button.
- **Licensing** (`ui/screens/AboutScreen.kt`, `app/src/main/assets/NEURAL_VOICE_LICENSE.txt`,
  `app/src/main/assets/APACHE_2.0_LICENSE.txt`) - credits for sherpa-onnx, Kokoro-82M and the espeak-ng
  pronunciation data, plus the history of which Piper voice was and was not considered. See
  `docs/PUBLISHING.md` section 7 for the publication-facing summary table.

## The model

Pinned in `vendor/models/MODELS.lock` and fetched by `scripts/fetch_models.sh` (the build's
`verifyBundledModels` task fails if the file is missing or wrong).

| | |
|---|---|
| Source | `kokoro-int8-en-v0_19.tar.bz2`, sherpa-onnx's own published "tts-models" GitHub release asset, not re-hosted |
| Archive | 103,248,205 B, SHA-256 `c9f0dd39...08bd` |
| Shipped in the APK | the decompressed plain tar, 158,269,440 B, SHA-256 `7190c480...5dea` (so the phone does no bzip2 work) |
| Unpacked on disk | about 157,947,103 B (150.6 MB) |
| Sample rate / speakers | 24000 Hz / 11 |

Kokoro uses `OfflineTtsKokoroModelConfig` with `model.int8.onnx` and the speaker bank `voices.bin`.
`lexicon` / `dictDir` / `lang` are deliberately left empty: the archive ships only `model.int8.onnx`,
`voices.bin`, `tokens.txt`, `espeak-ng-data/`, `LICENSE` and `README.md`, and English is phonemized
entirely from `espeak-ng-data`, matching upstream's documented invocation for this model. Those fields
exist for the multilingual `kokoro-multi-lang-v1_*` models.

### Licences of what is inside

| Part | Licence | Source of that statement |
|---|---|---|
| sherpa-onnx | Apache 2.0 | its repository |
| Kokoro-82M | Apache 2.0 | the `LICENSE` file inside the archive |
| `espeak-ng-data/` (392 files) | the espeak-ng project is "GPL version 3 or later" | the espeak-ng README; the archive ships **no** licence file for this folder and sherpa-onnx's docs state none, so per-file terms are **unverified** |

About credits the espeak-ng data factually ("from the espeak-ng project, GPL version 3 or later"). This
is informational for the proof of concept, not a legal conclusion.

## Choosing the voice

Kokoro v0.19 exposes **11 English speakers**, ids 0-10 (`KokoroVoices.NAMES`). Speaker **0 is
`af`, an averaged blend** rather than a speaker in its own right, so defaulting to it would be an
accident, not a choice.

The default is **sid 1, `af_bella`**. Upstream's own published voice table grades it **A-**, the
highest of the eleven; every other voice is B- or below, and the two American male voices grade
C+ (`am_michael`) and F+ (`am_adam`) —
https://huggingface.co/hexgrad/Kokoro-82M/blob/main/VOICES.md

That grade is upstream's judgement, and voice quality is ultimately a listening decision, so
**`docs/voice_samples/` holds the same chess paragraph rendered through all eleven speakers plus
a baseline from the old Piper tier**, with measured duration/RMS for each (kept as history). Changing the default is a one-constant
edit: `KOKORO_DEFAULT_SPEAKER_ID` in `ui/model/NarrationVoiceSettings.kt`.

The speaker id is part of `NeuralTtsProvider.cacheFingerprint`, so retuning it cannot serve stale
clips out of `NarrationStore`.

## Pacing (`length_scale`) and `speechWpm`

`length_scale` is higher-is-slower and lives in the model config, not in the per-call `speed`
argument, precisely so it is covered by the cache fingerprint. Kokoro's own pace is faster than
Piper's, so the default is **1.20**, not 1.0. Measured on the same four-sentence paragraph through
the real per-sentence pipeline (see `docs/voice_samples/measurements.txt`):

| `length_scale` | duration | effective wpm |
|---|---|---|
| 0.90 | 11.22 s | 210 |
| 1.00 | 12.01 s | 195 |
| 1.10 | 12.59 s | 185 |
| **1.20** | **13.74 s** | **169** |
| 1.30 | 15.82 s | 145 |

1.20 lands within 3% of the **165 wpm the Piper baseline measures at** — a pace this narration
already ships with. 1.10 is rushed for coordinate- and percentage-dense text; 1.30 drags.

`NarrationOptions.speechWpm` (default 165) describes the *device* voice; it is what the timeline
builder uses to lay out board animation **before** any audio exists, so it has to describe the
voice that will actually speak. `AnalysisViewModel.narrationOptionsForCurrentVoice()` substitutes
`NeuralVoiceTier.measuredWpm` (169 for Kokoro) when the neural voice is selected.

## Setup, selection and fallback

The voice is unpacked during `FirstRunSetup`, before the first analysis; when everything is already in
place it returns in milliseconds. `buildNarrationProvider()` asks `selectNarrationProvider(settings)`
(pure, unit-tested) whether the neural voice is selected and installed; if not, narration uses the device
voice, and `NarrationCoordinator` falls back to it per sentence if synthesis fails.

Invariants worth keeping:

- The choice is stored in `NarrationSettingsRepository`; `providerExplicitlyChosen` latches only on the
  user's own Settings tap (the built-in-voice switch).
- A stored `CLOUD` or other legacy value reads as `NEURAL`; a persisted older tier name already resolves to KOKORO.
- Do not read `narrationVoiceSettings.value` for a decision (it serves a default until DataStore's first
  emission); read `narrationSettingsRepository.current()`.

## Testing

The instrumented tests need **no staging**: the model is in the APK under test, so
`BundledVoiceInstallerInstrumentedTest`, `FirstRunSetupInstrumentedTest` and
`NeuralTtsProviderInstrumentedTest` install it from the assets. The Kokoro test keeps its duration and RMS
assertions and leaves a pullable evidence WAV; `NoNetworkPermissionTest` and the host
`ManifestPermissionsTest` prove the app has no network permission. There is no `assumeTrue`: a missing
asset is a loud failure (`skipped` must stay 0). A full `connectedDebugAndroidTest` pushes a ~371 MB APK
and takes 20-40 minutes.

## Regenerating `docs/voice_samples/`

`VoiceSampleSweep` renders the comparison set. It is annotated `@ManualEvidenceTool` and excluded
from `connectedDebugAndroidTest` by `notAnnotation` in `app/build.gradle.kts` — not because it is
allowed to be flaky, but because **Gradle uninstalls the app when the connected run finishes**,
which deletes `/sdcard/Android/data/<pkg>/` and every artifact it wrote. Run it directly:

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class 'net.palaya.chessanalyzer.video.VoiceSampleSweep' \
    net.palaya.chessanalyzer.test/androidx.test.runner.AndroidJUnitRunner
adb pull /sdcard/Android/data/net.palaya.chessanalyzer/files/voice_samples docs/
```

The `notAnnotation` filter is a runner *filter*, not a skip: excluded methods never appear in the
result XML, so `skipped="0"` stays a meaningful check.
