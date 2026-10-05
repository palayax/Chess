## Where to start listening

1. `COMPARE_line1_all_models.mp3`: the blunder line from every model in this order: Kokoro, Turbo, Turbo+tags, MTL ex0.5, MTL ex0.8, VoxCPM2.
2. `COMPARE_B_turbo_tags.mp3`: lines 1 and 2, each plain and then with tags.
3. `COMPARE_C_mtl_hebrew.mp3`: H1 and H2 with the dicta fix, then H1 **without** it. Also `COMPARE_D_voxcpm.mp3` for the same Hebrew lines on VoxCPM2.

## Measured findings (objective proxies only; nobody has listened yet)

- **Nothing came out silent, truncated or garbage.** Every file is between -28 and -13 dBFS RMS and lasts
  0.5–2x the duration expected at 2.5 words/s. Leading and trailing silence is ≤0.4 s, and no pause
  inside speech is longer than 1.1 s. Whisper transcribed every English file at WER ≤0.10.
- **Turbo is the only expressive candidate that runs at about real time here.** RTF was 0.84–1.28 on
  plain lines and ~1.55 with tags, measured after a warm-up. It uses 2.9–3.1 GB of `torch` VRAM; the whole
  GPU (desktop included) peaked at 3996 of 4096 MiB. A first run of the same files, before sampling
  moved from spawning `nvidia-smi` to in-process NVML, gave RTF 0.9–2.3 with identical audio. Speed on
  this laptop varies a lot from run to run.
- **Turbo tags do something measurable.** With tags, line 1 grew from 10.20 s to 15.28 s and line 2 from
  10.44 s to 13.20 s, and Whisper still heard every word. The extra ~3–5 s is non-speech audio inserted
  where the tags were, but whether it *sounds* like a laugh or a gasp can only be judged by ear. All four
  tags used (`[chuckle] [sigh] [laugh] [gasp]`) exist as tokens in the Turbo tokenizer (19 tags in total,
  including `[angry] [surprised] [dramatic] [sarcastic] [happy] [whispering]`).
- **Multilingual V3 is far too slow on this GPU:** RTF 8–17 on CUDA fp32. Profiling showed the T3
  decoder producing **~2.4 speech tokens/s** where real time needs 25. Measured causes:
  - The GPU reports `SW Thermal Slowdown: Active`, with the SM clock pinned at 1035 of 2100 MHz.
  - Kernel launch latency is ~25 µs under WDDM, and a 30-layer Llama step at CFG batch 2 is thousands of
    small kernels.
  - fp32 weights nearly fill the card: 3.3 GB allocated plus the desktop's ~1.1 GB.

  Two things did not help:
  - **fp16 T3** (`--fp16-t3` casts it; it also needs `torch.autocast`, otherwise there is a dtype-mismatch
    crash) cut VRAM to 2.35 GB, but RTF stayed at ~11–14 in a two-line ad-hoc test. On this card an fp16
    4096² matmul measured **0.23 TFLOPS against 1.23 TFLOPS for fp32**, so fp16 is slower here, not
    faster.
  - **CPU:** an ad-hoc run of line 4 gave MTL RTF 12.9 on CPU (Turbo 5.6 on CPU).
- **MTL speaks faster and louder.** English ran at 3.3–4.7 words/s, against 2.2–3.4 for Turbo, so its
  files are 25–35% shorter for the same text. Lines 1–2 at ex0.8 were the fastest (4.4–4.7 w/s), which
  matches upstream's note that higher exaggeration speeds speech up. RMS is ~10 dB above Turbo and Kokoro,
  peaks sit at -0.6 to +0.2 dBFS, and 1–3 samples per file clipped (C ex0.5 line 2, C ex0.8 line 3,
  C he H1). Normalize before mixing.
- **MTL mangles chess notation, according to ASR.** In line 3 Whisper heard "f7" as "ES7" or "even", and
  "h3" as "8, 3" or "EE3". Kokoro, Turbo and VoxCPM2 all came back as "f7 / h3". Listen to
  `C_mtl_*_line3.wav` before trusting this, because it is ASR evidence, not a listening result.
- **The Hebrew dicta fix works, and the difference is large.** The fix added 69 niqqud marks to H1 and 44
  to H2 (the full text is in `hebrew_vocalized.txt`).
  - Whisper (ivrit-ai large-v3-turbo) WER on H1: **0.05 with the fix against 0.42 without**. Without it,
    for example, "עם צריח" came back as "אמצאייך" and "שהמלכה צריכה" as "שמל קצף". H2 with the fix had
    WER 0.
  - **Caveat:** dicta vocalized **הפרש as הֶפְרֵשׁ ("difference") rather than הַפָּרָשׁ ("the knight")**,
    and added a stray dagesh in לְמִּשְׁבֶּצֶת. The model is therefore probably saying "hefresh" for
    "the knight". ASR cannot catch this, because both readings are spelled the same. Chess vocabulary
    will need a post-dicta override list.
- **VoxCPM2 (stretch) ran, but only on CPU:** RTF 36–41 under CPU contention (see below).
  - Its checkpoint is bf16, 4.96 GB. fp16 weights (~4.6 GB) cannot fit this card and Turing has no
    bf16, so it ran in fp32 on the CPU.
  - Hebrew WER was 0.16 on H1 and 0.07 on H2. On H1 the errors were "עם"→"אם", which is a homophone and
    therefore an ASR artefact, and "ומחליט"→"הוא מחליט". On H2 it was "הצריח"→"הצרח".
  - English line 1 had WER 0 at 2.56 w/s.
  - Output is 48 kHz, ~3 dB hotter than MTL, with peaks near 0 dBFS.
  - It needed no diacritization step.

## Hardware, contention and honesty notes

- **GPU runs had the GPU to themselves.** `nvidia-smi` listed only desktop graphics clients.
- **CPU numbers are upper bounds.** During the Kokoro rerun, ASR and VoxCPM2, other processes were
  loading the CPU: three `tts_worker.py` Kokoro processes from another session, then `ffmpeg` and `java`.
  - Kokoro RTF was 2.0–2.7 in the first run (8 threads, during the torch install) and 4.2–7.7 in the
    final run (4 threads, contended), even though this is an 82M int8 model. Treat both as polluted.
    The Android app's on-device figures in `../measurements.txt` are the relevant ones for Kokoro.
- Chatterbox outputs carry Resemble's inaudible PerTh watermark.
- Seeds are fixed (`torch.manual_seed(1234)` per file), so reruns reproduce the audio. That was verified
  for Turbo: two runs gave identical durations and levels.

## Install log (what went wrong and the workaround)

- **`uv venv -p 3.11`** downloaded CPython 3.11.15 and then failed with "Missing expected target
  directory for Python minor version link". Workaround: pass the interpreter path explicitly
  (`uv venv -p %APPDATA%\uv\python\cpython-3.11.15-windows-x86_64-none\python.exe`).
- **The torch 2.6.0+cu124 install took 37.8 min.** download.pytorch.org served at ~1.5 MB/s. Hugging Face
  downloads ran at ~10 MB/s.
- **Chatterbox** was installed from a git clone at commit `5de7a54` (2026-07-21), package 0.1.7. It pins
  torch 2.6.0 and transformers 5.2.0. `dicta-onnx` is **not** among its dependencies and has to be added
  by hand.
- **Chatterbox #467 is confirmed as the mechanism.** `add_hebrew_diacritics()` calls `Dicta()` with no
  model path, which raises `TypeError: missing 'model_path'`. A bare `except` swallows that, and the raw
  text goes through. The spike sets `tokenizer._dicta = Dicta("models/dicta-1.0.int8.onnx")` instead.
- **`dicta-onnx` fetches its tokenizer** (`dicta-il/dictabert-large-char-menaked/tokenizer.json`) from
  Hugging Face on first use. This is anonymous, but it breaks under `HF_HUB_OFFLINE=1` until it is cached.
- **No Hugging Face login was needed anywhere.** `HF_TOKEN` is unset by the script, and every repo was
  ungated.
- **Windows cannot create symlinks without Developer Mode**, so the HF cache stores copies. That is
  harmless; only a warning is printed.
- **VoxCPM2:** `torch.compile` is disabled because triton is not available on Windows. The ZipEnhancer
  denoiser was not loaded, since it is ModelScope-hosted and not needed.

## Downloads

| Item | Size |
|---|---|
| torch 2.6.0+cu124 + torchaudio wheels (per venv, cached once) | ~2.5 GB download |
| Kokoro `kokoro-int8-en-v0_19.tar.bz2` | 103 MB (152 MB extracted) |
| `dicta-1.0.int8.onnx` | 307 MB |
| `ResembleAI/chatterbox-turbo` (the loader's allow-list) | 4.04 GB (includes a 1.06 GB `s3gen.safetensors` that Turbo does not load) |
| `ResembleAI/chatterbox` MTL V3 subset | 3.21 GB |
| `openbmb/VoxCPM2` | 4.96 GB |
| ASR only: `Systran/faster-whisper-small.en` / `ivrit-ai/whisper-large-v3-turbo-ct2` | 0.49 GB / 1.62 GB |

## Regenerate

```
cd C:\Claude\ChessAnalyzer
pc\tts\.venv-kokoro\Scripts\python pc\tts\spike_tts.py kokoro
pc\tts\.venv\Scripts\python        pc\tts\spike_tts.py turbo
pc\tts\.venv\Scripts\python        pc\tts\spike_tts.py mtl            # add --fp16-t3 --suffix _fp16 to try fp16
pc\tts\.venv-voxcpm\Scripts\python pc\tts\spike_tts.py voxcpm --device cpu
pc\tts\.venv-kokoro\Scripts\python pc\tts\spike_tts.py asr
pc\tts\.venv\Scripts\python        pc\tts\spike_tts.py report
```
