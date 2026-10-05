# Research: local expressive TTS for the narrator

Round 12, task 50. Produced by a research subagent; lightly edited. **No speed figure below was measured on a
GTX 1650 Ti. Everything marked est. is an estimate, which the spike (task 54) replaces with measurements.**

## Bottom line

- **English:** the Chatterbox family (MIT) fits best.
  - **Turbo** (350M) supports `[laugh]` / `[chuckle]` / `[gasp]` / `[sigh]` tags.
  - **Multilingual V3** (500M) adds Hebrew and an `exaggeration` dial.
- **Hebrew:** **VoxCPM2** (Apache-2.0, 2B, Apr 2026) has the best published intelligibility: CER 2.98% vs Fish S2-Pro at 15.27% ([README](https://github.com/OpenBMB/VoxCPM)). That number is the authors' own. It needs about 8 GB in PyTorch, so on this machine it has to run as GGUF.

## Comparison

| Model | Licence | Params | Fits 4 GB? | Expressiveness | Hebrew |
|---|---|---|---|---|---|
| **Chatterbox-Turbo** | MIT | 350M | Likely (fp16; official ONNX) | 9 sound tags work; emotion tags unverified in PyTorch ([#557](https://github.com/resemble-ai/chatterbox/issues/557)) | No |
| **Chatterbox Multilingual V3** | MIT | 500M | Borderline (3.6 GB measured vs 5–7 GB claimed) | `exaggeration`, `cfg_weight`, cloning | Yes, but raw text hits a vowel-marking bug (below) |
| Chatterbox-Nano | MIT | 110M | Yes, 3× real time on 8 CPU cores | Turbo's tags | No |
| **VoxCPM2** | Apache-2.0 | 2B | Only as GGUF | Natural-language style prefix, voice design, cloning | **Best** |
| OmniVoice | Apache-2.0 | 0.8B | Likely | `[laughter]`, `[sigh]` | Weak (13.4 h of training data) |
| IndexTTS2/2.5 | bilibili | 0.8B | No | Best emotion sliders | No |
| VibeVoice 1.5B | MIT (research) | 1.5B | No | Long-form | No |
| Orpheus 3B | Llama-derived | 3B | Tight at Q4 | `<laugh>` etc. | Community fine-tunes only |
| Dia2 | Apache | 1–2B | Unclear (bf16 default) | Dialogue | No |
| Higgs v3 / Fish S2 / F5 / XTTS-v2 | **Non-commercial** | — | — | — | — |
| Zonos-Hebrew | **CC-BY-NC** | 1.6B | No | Emotion vectors | Yes |
| Sesame CSM-1B | Apache | 1B | Yes | Conversational | No, and it needs a **gated HF account** |
| BlueTTS 2.5 | MIT | small ONNX | CPU | Low | Yes (raw text, built-in vowel marking) |

## Gotchas

- **Chatterbox Hebrew:** the automatic vowel marking via `dicta_onnx` silently fails on master ([#467](https://github.com/resemble-ai/chatterbox/issues/467)), so the text is read without vowels and comes out as gibberish. To fix it, download `dicta-1.0.int8.onnx` (307 MB) and pass its path into `add_hebrew_diacritics`.
- **Long-form:** Chatterbox caps each call at about 40 s, and Turbo garbles input over about 350 characters. Feed it 1–3 sentences per call.
- **Watermark:** Chatterbox embeds an inaudible PerTh watermark in every output.
- **Multilingual V3** has to be selected explicitly with `t3_model="v3"`, because the default is still v2.

## Shortlist for listening on this PC

1. **Chatterbox-Turbo (EN).** Install with `uv venv -p 3.11`, then `uv pip install git+https://github.com/resemble-ai/chatterbox`. Weights (about 4.0 GB) come from `ResembleAI/chatterbox-turbo`. An ONNX fp16 set of about 1.66 GB also exists. Speed est. 1–2× real time here.
2. **Chatterbox Multilingual V3 (HE, plus EN with exaggeration around 0.7).** Same stack. About 3.2 GB of weights, plus the 307 MB dicta model.
3. **VoxCPM2 (the Hebrew quality bet).** There are two routes:
   - **GGUF:** runs via llama.cpp-omni and needs a build from source (unverified).
   - **PyTorch:** `pip install voxcpm` with Python 3.10–3.12. CPU RTF est. 4–8.
4. **Optional:** OmniVoice, or Pocket TTS (already supported by sherpa-onnx).
