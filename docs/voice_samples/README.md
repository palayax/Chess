# Voice samples — pick the narration voice by ear

Every file here is the **same paragraph**, synthesized on-device by the real narration
pipeline (one utterance per sentence, stitched with the app's own 220 ms / 320 ms gaps), and
pulled off the device rather than re-rendered anywhere else.

> This is where the game turned. Black had a knight fork on f2 that wins the queen, but
> played h6 instead. Can you see why that was the losing move? White finished with 91.4
> percent accuracy.

Chosen for coordinates, a two-figure accuracy and a question — the things a weak TTS voice
mangles. 36 words, 4 sentences.

## Recommendation

**`kokoro_01_af_bella.wav` is the default** (`KOKORO_DEFAULT_SPEAKER_ID = 1`). It is
upstream's own highest-graded voice of the eleven (A-; every other is B- or below), and at the
configured `length_scale` of 1.20 it speaks at ~169 wpm — within a few percent of the Piper
baseline's measured pace, which this narration already shipped with.

**If you want a male voice**, `kokoro_06_am_michael.wav` is the one to listen to; it is the
better-graded of the two American males (C+ vs F+ for `am_adam`) and closer to the
chess-YouTube register the script is written in. It is a clear step down in upstream's grading,
so it is a trade, not a free swap.

**Avoid `kokoro_00_af.wav` as a default** even though it sounds fine: id 0 is an averaged
blend of two other voices, so choosing it is choosing not to choose.

To change the default, edit one constant —
`KOKORO_DEFAULT_SPEAKER_ID` in `app/src/main/kotlin/net/palaya/chessanalyzer/ui/model/NarrationVoiceSettings.kt`.
The speaker id is part of the narration cache key, so old clips are not reused by mistake.

## The eleven Kokoro speakers (all at `length_scale` 1.20)

| sid | File | Voice | Upstream grade | Duration | Eff. wpm | RMS | Peak |
|---|---|---|---|---|---|---|---|
| 0 | `kokoro_00_af.wav` | averaged blend, not a distinct speaker | - | 13.94 s | 166 | 1819 | -7.1 dBFS |
| 1 | `kokoro_01_af_bella.wav` **(default)** | American female | A- | 13.74 s | 169 | 1839 | -7.5 dBFS |
| 2 | `kokoro_02_af_nicole.wav` | American female, breathy/ASMR | B- | 17.91 s | 127 | 1804 | -4.4 dBFS |
| 3 | `kokoro_03_af_sarah.wav` | American female | C+ | 14.11 s | 164 | 1880 | -7.8 dBFS |
| 4 | `kokoro_04_af_sky.wav` | American female (minutes of training data) | C- | 13.13 s | 177 | 1427 | -11.1 dBFS |
| 5 | `kokoro_05_am_adam.wav` | American male | F+ | 13.38 s | 174 | 2339 | -5.7 dBFS |
| 6 | `kokoro_06_am_michael.wav` | American male | C+ | 15.04 s | 153 | 1496 | -3.5 dBFS |
| 7 | `kokoro_07_bf_emma.wav` | British female | B- | 13.66 s | 170 | 2039 | -6.4 dBFS |
| 8 | `kokoro_08_bf_isabella.wav` | British female | C | 13.85 s | 168 | 2725 | -4.6 dBFS |
| 9 | `kokoro_09_bm_george.wav` | British male | C | 15.45 s | 149 | 1991 | -9.1 dBFS |
| 10 | `kokoro_10_bm_lewis.wav` | British male | D+ | 14.58 s | 159 | 1371 | -6.6 dBFS |

| — | `piper_baseline_ljspeech.wav` | Piper `en_US-ljspeech-medium`, the previous default | — | 13.90 s | 167 | 1579 | -7.6 dBFS |

Grades are upstream's own published voice table
(https://huggingface.co/hexgrad/Kokoro-82M/blob/main/VOICES.md), not a judgement made here.
The speaker **names** come from sherpa-onnx's model page for this archive; nothing inside the
archive labels the vectors, so the names cannot be verified on-device. The speaker **count**
(11) is verified — `OfflineTts.numSpeakers()` is asserted in the instrumented test.

Duration/RMS/peak above were re-derived on the host from these exact files, not copied from
what the device logged. `measurements.txt` holds the device's own figures for comparison.

## Pacing sweep (`af_bella`, `length_scale`)

| `length_scale` | File | Duration | Eff. wpm |
|---|---|---|---|
| 0.90 | `pace_kokoro_01_ls0_90.wav` | 11.22 s | 210 |
| 1.00 | `pace_kokoro_01_ls1_00.wav` | 12.01 s | 195 |
| 1.10 | `pace_kokoro_01_ls1_10.wav` | 12.59 s | 186 |
| 1.20 **(shipped)** | `pace_kokoro_01_ls1_20.wav` | 13.74 s | 169 |
| 1.30 | `pace_kokoro_01_ls1_30.wav` | 15.83 s | 145 |

Higher `length_scale` is slower. 1.20 ships because it lands nearest the Piper baseline's
measured pace — a rate this narration is already known to be readable at. `NeuralVoiceTier`
carries both the scale and the measured wpm; the latter feeds `NarrationOptions.speechWpm`, so
the board animation is laid out against the pace the voice actually speaks at.

## Caveat

Nobody has listened to these yet. Everything above is measured; *which voice sounds best* is
the one question measurement cannot answer, which is the entire reason this directory exists.

Regenerate with `VoiceSampleSweep` — see `docs/NEURAL_VOICE.md`.
