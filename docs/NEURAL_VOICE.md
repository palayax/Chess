# On-device neural narration (sherpa-onnx)

The second [`NarrationVoiceProvider`](../app/src/main/kotlin/net/palaya/chessanalyzer/video/NarrationVoiceProvider.kt),
alongside `DeviceTtsProvider` (Android's built-in TTS):
`NeuralTtsProvider`, backed by [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (k2-fsa,
Apache 2.0) running entirely on-device. Free, fully offline once the model is downloaded, no
per-user API key, no cloud redistribution question — strictly better than the device voice at no
ongoing cost, which is why it becomes the default the first time narration is wanted (see
`AnalysisViewModel.ensureDefaultNeuralVoice`).

**The default tier is Kokoro** (`af_bella`, `length_scale` 1.20), not Piper — see *Choosing the
voice* and *Pacing* below. Piper remains the metered-network fallback.

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

- **`VoiceModelProvisioner`** (`app/.../video/VoiceModelProvisioner.kt`) — downloads a model
  archive, verifies its SHA-256 against a hash pinned in code (never trusts a checksum fetched
  from the network), extracts it (`.tar.bz2`, via `commons-compress` — Android has no bzip2
  binary), and atomically moves it into `filesDir/tts_models/<tier>/`. Mirrors
  `engine/.../NetworkProvider.kt`'s pattern for the Stockfish NNUE net. Never bundles a model in
  the APK; lives under `filesDir`, not `cacheDir`, for the same eviction reason `NarrationStore`
  does.
- **`NeuralTtsProvider`** (`app/.../video/NeuralTtsProvider.kt`) — loads a provisioned model
  directory via sherpa-onnx's `OfflineTts` (Kotlin API, `newFromFile` — absolute paths, no Android
  assets involved) and turns text into a WAV. Fails cleanly (`SynthesisResult.Failure`, never
  throws) when the model is missing or inference errors, so `NarrationCoordinator`'s existing
  mandatory fallback to `DeviceTtsProvider` still covers it — no silent gaps.
- **Settings UI** (`ui/screens/SettingsScreen.kt`) — a third narration-provider radio ("Natural
  voice (on-device)") plus a per-tier picker showing size, install state, download progress/cancel,
  and delete.
- **Licensing** (`ui/screens/AboutScreen.kt`, `app/src/main/assets/NEURAL_VOICE_LICENSE.txt`,
  `app/src/main/assets/APACHE_2.0_LICENSE.txt`) — sherpa-onnx's and each model's licence, with the
  reasoning for which Piper voice was (and wasn't) chosen. See `docs/PUBLISHING.md` §7 for the
  publication-facing summary table.

## Model tiers

Pinned URL/SHA-256/size live in one place: `VoiceModelProvisioner.PIPER_SPEC` /
`VoiceModelProvisioner.KOKORO_SPEC`.

| Tier | Archive | Download | On disk | Rate | Speakers |
|---|---|---|---|---|---|
| Piper (metered fallback) | `vits-piper-en_US-ljspeech-medium-int8.tar.bz2` | 21,090,429 B (20.1 MB) | 37,347,875 B (35.6 MB) | 22050 Hz | 1 |
| **Kokoro (default)** | `kokoro-int8-en-v0_19.tar.bz2` | 103,248,205 B (98.5 MB) | 157,947,103 B (150.6 MB) | 24000 Hz | 11 |

Both are sherpa-onnx's own published "tts-models" GitHub release assets — not re-hosted, not
re-packaged. Every figure above is measured (archive length; `du -sb` of the extracted tree;
`OfflineTts.sampleRate()` / `numSpeakers()` on the loaded model), not quoted from a release page.
`ModelSpec.installedSizeBytes` carries the on-disk figure so Settings can state both — the two
differ by more than 50 MB for Kokoro, and quoting only one of them misleads whichever you pick.

The two tiers are genuinely different sherpa-onnx shapes, which is why nothing proven about Piper
carried over to Kokoro:

| | Piper | Kokoro |
|---|---|---|
| Config class | `OfflineTtsVitsModelConfig` | `OfflineTtsKokoroModelConfig` |
| Model file | `en_US-ljspeech-medium.onnx` | `model.int8.onnx` |
| Speaker bank | none (single-speaker VITS) | `voices.bin`, 11 speakers |
| Phonemizer | `espeak-ng-data/` | `espeak-ng-data/` |
| Lexicon | not used | not used — `kokoro-int8-en-v0_19` ships none |

`lexicon` / `dictDir` / `lang` are deliberately left empty for both. Listing the pinned Kokoro
archive shows only `model.int8.onnx`, `voices.bin`, `tokens.txt`, `espeak-ng-data/`, `LICENSE`
and `README.md` — English is phonemized entirely from `espeak-ng-data`, matching upstream's own
documented invocation for this model. Those fields exist for the multilingual
`kokoro-multi-lang-v1_*` models.

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
the Piper baseline**, with measured duration/RMS for each. Changing the default is a one-constant
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
`NeuralVoiceTier.measuredWpm` — 169 for Kokoro, 165 for Piper — for the selected tier.

## Making Kokoro the default without a 98 MB surprise

`AnalysisViewModel.ensureDefaultNeuralVoice()` runs the first time narration is wanted. It reads
the **persisted** settings (`narrationSettingsRepository.current()`, never the eagerly-seeded
StateFlow), does nothing at all if the user has ever chosen a provider themselves, and otherwise
asks `decideAutoVoice(cost, installed)`:

| Connection | Nothing installed | Piper installed | Kokoro installed |
|---|---|---|---|
| Unmetered | download Kokoro | promote Piper **and** download Kokoro | promote Kokoro |
| Metered | download Piper (~20 MB) | promote Piper, download nothing | promote Kokoro |
| No network | nothing | promote Piper | promote Kokoro |

The rule: **Kokoro's ~98.5 MB is only ever fetched automatically on an unmetered connection.** The
app already pulls a ~98 MB Stockfish net unprompted, and stacking a second one onto a cellular
plan is not a cost the user agreed to. On metered the automatic path tops out at Piper's ~20 MB —
the same amount already shipped as an unconditional auto-download, so this is a tightening, never
a new cost — and the device voice is the floor below that.

`ConnectivityNetworkCostProbe` reads `NET_CAPABILITY_NOT_METERED` and **fails closed to metered**:
no capabilities, a `SecurityException`, or an uncharacterisable transport all return `METERED`.
Mis-reading metered as unmetered bills the user; the opposite mistake costs a smaller model.

Two invariants this must not break, both hard-won in Round 5:

- An automatic switch uses `setProviderAutomatically()`, which deliberately does **not** latch
  `providerExplicitlyChosen`. Only the user's own Settings tap (`setProvider()`) does.
- Narration never stalls on the device voice while a model downloads: `buildNarrationProvider()`
  prefers the selected tier but falls back to **any** installed tier before dropping to device TTS.

A user tapping Download in Settings is not gated at all — that is an explicit action with the size
printed beside it.

## Testing without re-downloading every run

`app/src/androidTest/kotlin/net/palaya/chessanalyzer/video/NeuralTtsProviderInstrumentedTest.kt`
expects **both** archives pre-staged on the device, the same pattern `scripts/push_test_net.sh`
already uses for the Stockfish net. `scripts/push_voice_models.sh` downloads, SHA-256-verifies and
pushes both:

```bash
scripts/push_voice_models.sh
./gradlew :app:connectedDebugAndroidTest
```

For reference, the manual equivalent for one archive:

```bash
curl -fSL -o vits-piper-en_US-ljspeech-medium-int8.tar.bz2 \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-ljspeech-medium-int8.tar.bz2
MSYS_NO_PATHCONV=1 adb push vits-piper-en_US-ljspeech-medium-int8.tar.bz2 /data/local/tmp/
MSYS_NO_PATHCONV=1 adb shell chmod 644 /data/local/tmp/vits-piper-en_US-ljspeech-medium-int8.tar.bz2
./gradlew :app:connectedDebugAndroidTest
```

The test does not use `assumeTrue` to skip when the archive is absent — per this project's testing
standard, a missing archive is a loud failure, not a silent skip (`skipped` must stay 0).

`VoiceModelProvisioner.provisionFromLocalArchiveForTesting(tier, archiveFile)` is the test-only
seam: it runs the exact same SHA-256-verify + extract + atomic-install logic `ensureModel` uses,
just skipping the HTTP GET. Production code never calls it.

## Regenerating `docs/voice_samples/`

`VoiceSampleSweep` renders the comparison set. It is annotated `@ManualEvidenceTool` and excluded
from `connectedDebugAndroidTest` by `notAnnotation` in `app/build.gradle.kts` — not because it is
allowed to be flaky, but because **Gradle uninstalls the app when the connected run finishes**,
which deletes `/sdcard/Android/data/<pkg>/` and every artifact it wrote. Run it directly:

```bash
scripts/push_voice_models.sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class 'net.palaya.chessanalyzer.video.VoiceSampleSweep' \
    net.palaya.chessanalyzer.test/androidx.test.runner.AndroidJUnitRunner
adb pull /sdcard/Android/data/net.palaya.chessanalyzer/files/voice_samples docs/
```

The `notAnnotation` filter is a runner *filter*, not a skip: excluded methods never appear in the
result XML, so `skipped="0"` stays a meaningful check.
