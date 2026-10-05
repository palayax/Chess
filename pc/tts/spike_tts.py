#!/usr/bin/env python
"""Palaya Chess -- TTS listening-test spike (round 12, task 54).

Regenerates every sample in docs/voice_samples/pc/ plus measurements and RESULTS.md.

Two virtual environments live next to this file (see RESULTS.md for the exact commands):
  .venv-kokoro  (system Python 3.13 + sherpa-onnx)   -> candidate A
  .venv         (uv Python 3.11 + torch 2.6.0 cu124 + chatterbox + dicta-onnx) -> B, C, report
  .venv-voxcpm  (optional, candidate D)

Usage (from C:\\Claude\\ChessAnalyzer):
  pc\\tts\\.venv-kokoro\\Scripts\\python pc\\tts\\spike_tts.py kokoro
  pc\\tts\\.venv\\Scripts\\python        pc\\tts\\spike_tts.py turbo
  pc\\tts\\.venv\\Scripts\\python        pc\\tts\\spike_tts.py mtl
  pc\\tts\\.venv-voxcpm\\Scripts\\python pc\\tts\\spike_tts.py voxcpm
  pc\\tts\\.venv\\Scripts\\python        pc\\tts\\spike_tts.py report   (COMPARE wavs + mp3 + RESULTS.md)

Every synthesized file appends one JSON record to docs/voice_samples/pc/measurements.jsonl
(later records for the same file replace earlier ones in the report).
"""
from __future__ import annotations

import argparse
import datetime as _dt
import glob
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import wave
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent            # pc/tts
ROOT = HERE.parents[1]                            # C:\Claude\ChessAnalyzer
OUT = ROOT / "docs" / "voice_samples" / "pc"
MODELS = HERE / "models"
MEAS = OUT / "measurements.jsonl"

# Keep every model cache inside pc/tts.
os.environ.setdefault("HF_HOME", str(HERE / "hf_cache"))
os.environ.setdefault("HF_HUB_DISABLE_TELEMETRY", "1")
os.environ.pop("HF_TOKEN", None)                  # prove nothing needs a login

# --------------------------------------------------------------------------------------------
# Test script (verbatim from the task)
# --------------------------------------------------------------------------------------------
EN = {
    1: "White is completely winning, up a full rook, and decides the queen needs some fresh air. "
       "On the one square where a knight can fork it. The eval bar just fell down the stairs.",
    2: "Wait, wait. Black is giving the rook away? On purpose? Take it, and the bishop comes through "
       "on the long diagonal. Mate is coming. That is a move you frame and hang on the wall.",
    3: "Pause here. There's a move that wins on the spot. Three, two, one. Knight takes f7, forking "
       "king and queen. And what did our friend play instead? Pawn to h3.",
    4: "e4, e5, knights come out, bishops come out. Everybody's following the recipe. Nothing to see yet.",
}
# Turbo paralinguistic-tag variants of lines 1-2 (tags only inserted, words unchanged).
EN_TAGS = {
    1: "White is completely winning, up a full rook, and decides the queen needs some fresh air. "
       "[chuckle] On the one square where a knight can fork it. [sigh] The eval bar just fell down the stairs. [laugh]",
    2: "Wait, wait. [gasp] Black is giving the rook away? On purpose? Take it, and the bishop comes through "
       "on the long diagonal. Mate is coming. [laugh] That is a move you frame and hang on the wall.",
}
HE = {
    "H1": "לבן מנצח לגמרי, עם צריח שלם יותר, ומחליט שהמלכה צריכה קצת אוויר. בדיוק למשבצת שבה הפרש יכול לתקוף אותה.",
    "H2": "רגע, רגע. שחור נותן את הצריח? בכוונה? זה מסע שצריך למסגר ולתלות על הקיר.",
}

SEED = 1234
SUFFIX = ""        # --suffix: appended to output names (e.g. "_fp16") for side-by-side timing runs
EXPECTED_WPS = 2.5
GAP_S = 0.7


# --------------------------------------------------------------------------------------------
# Audio I/O + measurement (numpy + stdlib only, so it works in every venv)
# --------------------------------------------------------------------------------------------
def write_wav(path: Path, x: np.ndarray, sr: int) -> None:
    x = np.asarray(x, dtype=np.float32).reshape(-1)
    pcm = (np.clip(x, -1.0, 1.0) * 32767.0).round().astype("<i2")
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(pcm.tobytes())


def read_wav(path: Path) -> tuple[np.ndarray, int]:
    with wave.open(str(path), "rb") as w:
        sr, n, ch, sw = w.getframerate(), w.getnframes(), w.getnchannels(), w.getsampwidth()
        raw = w.readframes(n)
    assert sw == 2, f"{path}: expected 16-bit PCM"
    x = np.frombuffer(raw, dtype="<i2").astype(np.float32) / 32768.0
    if ch > 1:
        x = x.reshape(-1, ch).mean(axis=1)
    return x, sr


def _db(v: float) -> float:
    return float(20.0 * np.log10(max(v, 1e-12)))


def count_words(text: str) -> int:
    text = re.sub(r"\[[^\]]*\]", " ", text)                       # paralinguistic tags
    return len(re.findall(r"[\w\u0590-\u05FF]+(?:['’][\w]+)?", text))


def measure(x: np.ndarray, sr: int, text: str) -> dict:
    """Objective proxies only: level, clipping, silence structure, pace."""
    x = np.asarray(x, dtype=np.float64).reshape(-1)
    dur = len(x) / sr if sr else 0.0
    rms = float(np.sqrt(np.mean(x ** 2))) if len(x) else 0.0
    peak = float(np.max(np.abs(x))) if len(x) else 0.0
    clipped = int(np.sum(np.abs(x) >= 0.999))
    # 20 ms frame energy -> silence structure
    hop = max(1, int(0.02 * sr))
    nfr = len(x) // hop
    fr = x[: nfr * hop].reshape(nfr, hop) if nfr else np.zeros((0, hop))
    fdb = 20 * np.log10(np.sqrt(np.mean(fr ** 2, axis=1)) + 1e-12) if nfr else np.zeros(0)
    thr = -45.0                                                    # dBFS: below = silence
    active = fdb > thr
    if active.any():
        first = int(np.argmax(active))
        last = int(nfr - 1 - np.argmax(active[::-1]))
        lead_s, trail_s = first * 0.02, (nfr - 1 - last) * 0.02
        inner = ~active[first:last + 1]
        longest, run = 0, 0
        for s in inner:
            run = run + 1 if s else 0
            longest = max(longest, run)
        longest_pause_s = longest * 0.02
        speech_s = float(active.sum()) * 0.02
    else:
        lead_s = trail_s = dur
        longest_pause_s = 0.0
        speech_s = 0.0
    tail_db = float(fdb[-2:].max()) if nfr >= 2 else -120.0        # last 40 ms
    words = count_words(text)
    wps = words / dur if dur > 0 else 0.0
    expected = words / EXPECTED_WPS
    ratio = dur / expected if expected else 0.0
    flags = []
    if rms < 10 ** (-45 / 20):
        flags.append("SILENT")
    if not (0.5 <= ratio <= 2.0):
        flags.append(f"DURATION_OFF(x{ratio:.2f} of expected)")
    if clipped > 0.0005 * len(x):
        flags.append(f"CLIPPING({clipped})")
    if longest_pause_s > 1.5:
        flags.append(f"LONG_PAUSE({longest_pause_s:.1f}s)")
    if tail_db > -30:
        flags.append(f"ABRUPT_END(last40ms {tail_db:.0f}dBFS)")
    return {
        "sr": sr, "duration_s": round(dur, 3), "rms_dbfs": round(_db(rms), 2), "peak_dbfs": round(_db(peak), 2),
        "clipped_samples": clipped, "lead_silence_s": round(lead_s, 2), "trail_silence_s": round(trail_s, 2),
        "longest_inner_pause_s": round(longest_pause_s, 2), "speech_s": round(speech_s, 2),
        "words": words, "words_per_s": round(wps, 2), "expected_duration_s": round(expected, 2),
        "flags": flags or ["ok"],
    }


def record(entry: dict) -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    entry["timestamp"] = _dt.datetime.now().isoformat(timespec="seconds")
    with open(MEAS, "a", encoding="utf-8") as f:
        f.write(json.dumps(entry, ensure_ascii=False) + "\n")
    m = entry
    print(f"  -> {m['file']}: {m.get('duration_s')}s rms={m.get('rms_dbfs')} peak={m.get('peak_dbfs')} "
          f"wall={m.get('wall_s')} rtf={m.get('rtf')} dev={m.get('device')} "
          f"vram_torch={m.get('vram_torch_peak_mb')} flags={m.get('flags')}", flush=True)


# --------------------------------------------------------------------------------------------
# nvidia-smi sampler (whole-GPU memory, includes the ~1.1 GB the Windows desktop already holds)
# --------------------------------------------------------------------------------------------
def smi_used_mb() -> int | None:
    try:
        out = subprocess.run(["nvidia-smi", "--query-gpu=memory.used", "--format=csv,noheader,nounits"],
                             capture_output=True, text=True, timeout=10).stdout.strip().splitlines()
        return int(out[0])
    except Exception:
        return None


SMI_PERIOD = 0.5


class SmiSampler:
    """Whole-GPU memory.used / SM clock / temperature, sampled in-process through NVML (the library
    nvidia-smi itself reads; spawning nvidia-smi.exe every tick was measurably intrusive on Windows).
    Falls back to nvidia-smi.exe when nvidia-ml-py is not installed. period<=0 disables sampling."""

    def __init__(self, period=None):
        self.period = SMI_PERIOD if period is None else period
        self.peak, self.clocks, self.temps = None, [], []
        self._stop = threading.Event()
        self._h = None
        try:
            import pynvml
            pynvml.nvmlInit()
            self._nv, self._h = pynvml, pynvml.nvmlDeviceGetHandleByIndex(0)
        except Exception:
            pass

    def _sample(self):
        if self._h is not None:
            nv = self._nv
            used = nv.nvmlDeviceGetMemoryInfo(self._h).used // 2 ** 20
            self.clocks.append(nv.nvmlDeviceGetClockInfo(self._h, nv.NVML_CLOCK_SM))
            self.temps.append(nv.nvmlDeviceGetTemperature(self._h, nv.NVML_TEMPERATURE_GPU))
            return int(used)
        return smi_used_mb()

    def __enter__(self):
        if self.period <= 0:
            return self
        self.peak = self._sample()
        self._t = threading.Thread(target=self._run, daemon=True)
        self._t.start()
        return self

    def _run(self):
        while not self._stop.is_set():
            v = self._sample()
            if v is not None:
                self.peak = v if self.peak is None else max(self.peak, v)
            self._stop.wait(self.period)

    def __exit__(self, *a):
        if self.period > 0:
            self._stop.set()
            self._t.join()

    def summary(self) -> dict:
        if not self.clocks:
            return {}
        return {"sm_clock_mhz_min_med_max": [int(min(self.clocks)), int(np.median(self.clocks)), int(max(self.clocks))],
                "gpu_temp_c_max": int(max(self.temps))}


# --------------------------------------------------------------------------------------------
# A. Kokoro baseline (sherpa-onnx, CPU) -- mirrors the Android app's pipeline
# --------------------------------------------------------------------------------------------
SENTENCE_SPLIT = re.compile(r"[^.!?]+[.!?]+[\"')\]]*|[^.!?]+$")   # NarrationSynthesizer.SENTENCE_SPLIT_REGEX


def cmd_kokoro(args) -> None:
    import sherpa_onnx
    d = MODELS / "kokoro-int8-en-v0_19"
    if not d.exists():
        sys.exit(f"missing {d}; download "
                 "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-en-v0_19.tar.bz2 "
                 f"and extract into {MODELS}")
    cfg = sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(
            kokoro=sherpa_onnx.OfflineTtsKokoroModelConfig(
                model=str(d / "model.int8.onnx"), voices=str(d / "voices.bin"), tokens=str(d / "tokens.txt"),
                data_dir=str(d / "espeak-ng-data"), length_scale=1.2),
            provider="cpu", num_threads=args.threads),
        max_num_sentences=1)
    t0 = time.perf_counter()
    tts = sherpa_onnx.OfflineTts(cfg)
    load_s = time.perf_counter() - t0
    print(f"kokoro loaded in {load_s:.1f}s, speakers={tts.num_speakers}")
    tts.generate("Warm up.", sid=1, speed=1.0)
    for i, text in EN.items():
        # App behaviour: one utterance per sentence, 220 ms gap (320 ms after '?'), speed=1.0.
        parts = [p.strip() for p in SENTENCE_SPLIT.findall(text) if p.strip()]
        chunks, sr = [], 24000
        t0 = time.perf_counter()
        for j, p in enumerate(parts):
            a = tts.generate(p, sid=1, speed=1.0)
            sr = a.sample_rate
            chunks.append(np.asarray(a.samples, dtype=np.float32))
            if j < len(parts) - 1:
                gap = 0.32 if p.rstrip("\"')]").endswith("?") else 0.22
                chunks.append(np.zeros(int(gap * sr), dtype=np.float32))
        wall = time.perf_counter() - t0
        x = np.concatenate(chunks)
        name = f"A_kokoro_line{i}.wav"
        write_wav(OUT / name, x, sr)
        m = measure(x, sr, text)
        record({"file": name, "candidate": "A", "model": "kokoro-int8-en-v0_19 sid1 af_bella ls1.2 (sherpa-onnx)",
                "device": f"cpu({args.threads}thr)", "text": text, "wall_s": round(wall, 2),
                "rtf": round(wall / m["duration_s"], 3), "load_s": round(load_s, 1),
                "vram_torch_peak_mb": None, "vram_smi_peak_mb": None,
                "settings": "sentence-split, 220/320 ms gaps, speed 1.0", **m})


# --------------------------------------------------------------------------------------------
# Chatterbox helpers
# --------------------------------------------------------------------------------------------
def _torch_setup():
    import torch
    torch.backends.cuda.matmul.allow_tf32 = False
    return torch


def _gen_timed(torch, device, fn):
    """Run one generation; return (wav np.float32, wall_s, torch_peak_mb, smi_peak_mb, gpu_stats)."""
    torch.manual_seed(SEED)
    if device == "cuda":
        torch.cuda.synchronize()
        torch.cuda.reset_peak_memory_stats()
    with SmiSampler(period=SMI_PERIOD if device == "cuda" else 0) as s:
        t0 = time.perf_counter()
        wav = fn()
        if device == "cuda":
            torch.cuda.synchronize()
        wall = time.perf_counter() - t0
    tpk = round(torch.cuda.max_memory_allocated() / 2 ** 20) if device == "cuda" else None
    tres = round(torch.cuda.max_memory_reserved() / 2 ** 20) if device == "cuda" else None
    x = wav.detach().cpu().float().numpy().reshape(-1) if hasattr(wav, "detach") else np.asarray(wav).reshape(-1)
    stats = s.summary()
    if tres:
        stats["vram_torch_reserved_mb"] = tres
    return x, wall, tpk, s.peak, stats


def _load_with_fallback(torch, loader, want):
    """loader(device) -> model. Tries CUDA, falls back to CPU on OOM; returns (model, device, load_s, note)."""
    note = ""
    dev = want
    if dev == "cuda" and not torch.cuda.is_available():
        dev, note = "cpu", "CUDA not available"
    t0 = time.perf_counter()
    try:
        model = loader(dev)
    except torch.cuda.OutOfMemoryError as e:
        note = f"CUDA OOM at load ({str(e).splitlines()[0][:120]}); fell back to CPU"
        print(note)
        torch.cuda.empty_cache()
        dev = "cpu"
        t0 = time.perf_counter()
        model = loader(dev)
    return model, dev, time.perf_counter() - t0, note


def _run_items(torch, model, dev, items, candidate, model_label, load_s, note, loader, base_smi):
    """items: list of (filename, spoken_text, text_for_wordcount, gen_kwargs, extra_fields)."""
    for name, text, count_text, kw, extra in items:
        name = name.replace(".wav", SUFFIX + ".wav")
        print(f"[{candidate}] {name}")
        try:
            x, wall, tpk, spk, gstats = _gen_timed(torch, dev, lambda: model.generate(text, **kw))
        except torch.cuda.OutOfMemoryError as e:
            note = f"CUDA OOM during generate ({str(e).splitlines()[0][:120]}); reloaded on CPU"
            print(note)
            del model
            torch.cuda.empty_cache()
            dev = "cpu"
            model = loader(dev)
            x, wall, tpk, spk, gstats = _gen_timed(torch, dev, lambda: model.generate(text, **kw))
        sr = model.sr
        write_wav(OUT / name, x, sr)
        m = measure(x, sr, count_text)
        record({"file": name, "candidate": candidate, "model": model_label, "device": dev, "text": text,
                "wall_s": round(wall, 2), "rtf": round(wall / max(m["duration_s"], 1e-6), 3),
                "load_s": round(load_s, 1), "vram_torch_peak_mb": tpk, "vram_smi_peak_mb": spk,
                "vram_smi_baseline_mb": base_smi, "note": note, **gstats,
                "settings": ", ".join(f"{k}={v}" for k, v in kw.items() if k != "language_id") or "defaults",
                **extra, **m})
    return model, dev


# --------------------------------------------------------------------------------------------
# B. Chatterbox-Turbo (English, built-in default voice)
# --------------------------------------------------------------------------------------------
def cmd_turbo(args) -> None:
    torch = _torch_setup()
    from chatterbox.tts_turbo import ChatterboxTurboTTS
    base = smi_used_mb()
    loader = lambda d: ChatterboxTurboTTS.from_pretrained(device=d)
    model, dev, load_s, note = _load_with_fallback(torch, loader, args.device)
    load_peak = round(torch.cuda.max_memory_allocated() / 2 ** 20) if dev == "cuda" else None
    print(f"turbo on {dev}, load {load_s:.1f}s, torch peak after load {load_peak} MB, smi baseline {base} MB")
    _gen_timed(torch, dev, lambda: model.generate("This is a warm up sentence."))   # CUDA warm-up, not saved
    label = "Chatterbox-Turbo 350M (ResembleAI/chatterbox-turbo), default voice, fp32"
    items = [(f"B_turbo_line{i}.wav", t, t, {}, {}) for i, t in EN.items()]
    items += [(f"B_turbo_tags_line{i}.wav", t, t, {}, {}) for i, t in EN_TAGS.items()]
    _run_items(torch, model, dev, items, "B", label, load_s, note, loader, base)


# --------------------------------------------------------------------------------------------
# C. Chatterbox Multilingual V3 (English exaggeration sweep + Hebrew with/without dicta)
# --------------------------------------------------------------------------------------------
def _install_dicta():
    """Fix for chatterbox #467: add_hebrew_diacritics() calls Dicta() with no model path and
    silently falls back to raw text. Point it at the downloaded dicta-1.0.int8.onnx instead."""
    from dicta_onnx import Dicta
    import chatterbox.models.tokenizers.tokenizer as tok
    path = MODELS / "dicta-1.0.int8.onnx"
    if not path.exists():
        sys.exit(f"missing {path}; download https://github.com/thewh1teagle/dicta-onnx/releases/download/"
                 "model-files-v1.0/dicta-1.0.int8.onnx")
    tok._dicta = Dicta(str(path))
    return tok


def cmd_mtl(args) -> None:
    torch = _torch_setup()
    from chatterbox.mtl_tts import ChatterboxMultilingualTTS
    tok = _install_dicta()
    orig_add = tok.add_hebrew_diacritics
    base = smi_used_mb()

    def loader(d):
        m = ChatterboxMultilingualTTS.from_pretrained(device=d, t3_model="v3")
        if args.fp16_t3 and d == "cuda":
            # Half weights alone crash (fp32 speaker embedding vs fp16 Linear); autocast fixes the mix.
            # Measured here: saves ~1 GB VRAM but is NOT faster (fp16 GEMM is ~5x slower than fp32 on this card).
            m.t3.half()
            torch.cuda.empty_cache()
            _gen = m.generate

            def _gen_autocast(*a, **k):
                with torch.autocast("cuda", dtype=torch.float16):
                    return _gen(*a, **k)
            m.generate = _gen_autocast
        return m

    model, dev, load_s, note = _load_with_fallback(torch, loader, args.device)
    load_peak = round(torch.cuda.max_memory_allocated() / 2 ** 20) if dev == "cuda" else None
    print(f"mtl v3 on {dev}, load {load_s:.1f}s, torch peak after load {load_peak} MB, smi baseline {base} MB")
    _gen_timed(torch, dev, lambda: model.generate("This is a warm up sentence.", language_id="en"))
    label = "Chatterbox Multilingual V3 500M (ResembleAI/chatterbox t3_mtl23ls_v3), default voice, " + \
            ("t3 fp16" if args.fp16_t3 else "fp32")

    if not args.hebrew_only:
        items = []
        for ex, cfg in ((0.5, 0.5), (0.8, 0.3)):
            for i, t in EN.items():
                items.append((f"C_mtl_ex{ex}_line{i}.wav", t, t,
                              {"language_id": "en", "exaggeration": ex, "cfg_weight": cfg}, {}))
        model, dev = _run_items(torch, model, dev, items, "C", label, load_s, note, loader, base)

    # Hebrew: prove diacritics are added.
    proof = []
    he_items = []
    for k, t in HE.items():
        voc = orig_add(t)
        added = sum(1 for c in voc if "\u0591" <= c <= "\u05C7")
        print(f"{k} raw      : {t}\n{k} vocalized: {voc}\n{k} niqqud marks added: {added}")
        if added == 0:
            sys.exit(f"dicta added no diacritics to {k}; aborting Hebrew (fix not working)")
        proof.append(f"{k} raw:       {t}\n{k} vocalized: {voc}\n{k} niqqud/cantillation marks added: {added}\n")
        he_items.append((f"C_mtl_he_{k}.wav", t, t,
                         {"language_id": "he", "exaggeration": 0.5, "cfg_weight": 0.5},
                         {"text_vocalized": voc, "diacritics_added": added}))
    (OUT / "hebrew_vocalized.txt").write_text("\n".join(proof), encoding="utf-8")
    model, dev = _run_items(torch, model, dev, he_items, "C", label + ", dicta fix ON", load_s, note, loader, base)

    # Same line without the fix (the upstream default behaviour: raw unvocalized text).
    tok.add_hebrew_diacritics = lambda s: s
    try:
        no_items = [("C_mtl_he_H1_noDiacritics.wav", HE["H1"], HE["H1"],
                     {"language_id": "he", "exaggeration": 0.5, "cfg_weight": 0.5}, {"diacritics_added": 0})]
        _run_items(torch, model, dev, no_items, "C", label + ", dicta fix OFF (raw text)", load_s, note, loader, base)
    finally:
        tok.add_hebrew_diacritics = orig_add


# --------------------------------------------------------------------------------------------
# D. VoxCPM2 (stretch)
# --------------------------------------------------------------------------------------------
def cmd_voxcpm(args) -> None:
    """VoxCPM2 is 2B params. Its checkpoint is bf16 (4.6 GB): Turing has no bf16 and fp16 weights alone
    (~4.6 GB) exceed this 4 GB card, so it runs on CPU in fp32 (bf16 on a CPU without AVX512-BF16 is
    emulated). torch.compile is off (needs triton, not available on Windows)."""
    torch = _torch_setup()
    import voxcpm.model.voxcpm2 as vx2
    from voxcpm import VoxCPM
    dev = args.device if args.device == "cpu" else "cpu"
    if args.device != "cpu":
        print("VoxCPM2 forced to CPU: fp16 weights (~4.6 GB) cannot fit a 4 GB GPU and bf16 is unsupported on Turing")
    vx2.pick_runtime_dtype = lambda device, configured: "float32"
    torch.set_num_threads(args.threads)

    def loader(d):
        return VoxCPM.from_pretrained(args.voxcpm_repo, load_denoiser=False, optimize=False, device=d)

    t0 = time.perf_counter()
    model = loader(dev)
    load_s = time.perf_counter() - t0
    sr = int(model.tts_model.sample_rate)
    print(f"voxcpm2 on {dev} fp32, sr={sr}, load {load_s:.1f}s")

    class _W:  # adapt to _run_items' model.generate(text, **kw) + .sr
        def __init__(s, m):
            s.m, s.sr = m, sr

        def generate(s, text, **kw):
            return torch.from_numpy(np.asarray(s.m.generate(text=text, **kw), dtype=np.float32))

    w = _W(model)
    kw = {"cfg_value": 2.0, "inference_timesteps": 10, "normalize": False}
    items = [(f"D_voxcpm_he_{k}.wav", t, t, dict(kw), {}) for k, t in HE.items()]
    items += [("D_voxcpm_line1.wav", EN[1], EN[1], dict(kw), {})]
    _run_items(torch, w, dev, items, "D", f"VoxCPM2 2B ({args.voxcpm_repo}), default voice, fp32", load_s,
               "CPU fp32; GPU not feasible (4 GB)", lambda d: _W(loader(d)), None)


# --------------------------------------------------------------------------------------------
# ASR intelligibility proxy (CPU, faster-whisper; runs in .venv-kokoro)
# --------------------------------------------------------------------------------------------
ASR = OUT / "asr.jsonl"
_NUM = {"0": "zero", "1": "one", "2": "two", "3": "three", "4": "four", "5": "five", "6": "six", "7": "seven",
        "8": "eight", "9": "nine", "10": "ten"}


def _norm_en(s: str) -> str:
    s = s.lower().replace("’", "'").replace("-", " ")
    s = re.sub(r"\[[^\]]*\]", " ", s)
    s = re.sub(r"\b([a-h])\s+(\d)\b", r"\1\2", s)                 # "f 7" -> "f7"
    s = re.sub(r"[^\w\s']", " ", s)
    toks = [_NUM.get(t, t) for t in s.split()]
    return " ".join(toks)


def _norm_he(s: str) -> str:
    s = re.sub(r"[֑-ׇ]", "", s)                          # strip niqqud / cantillation
    s = re.sub(r"[^\w\s]", " ", s)
    return " ".join(s.split())


def cmd_asr(args) -> None:
    import jiwer
    from faster_whisper import WhisperModel
    recs = load_records()
    models = {}

    def get(lang):
        if lang not in models:
            repo = "Systran/faster-whisper-small.en" if lang == "en" else "ivrit-ai/whisper-large-v3-turbo-ct2"
            models[lang] = (repo, WhisperModel(repo, device="cpu", compute_type="int8", cpu_threads=args.threads))
        return models[lang]

    out = []
    for name, r in sorted(recs.items()):
        if not (OUT / name).exists():
            continue
        lang = "he" if "_he_" in name else "en"
        repo, m = get(lang)
        segs, _ = m.transcribe(str(OUT / name), language=lang, beam_size=5, vad_filter=False,
                               condition_on_previous_text=False)
        hyp = " ".join(s.text.strip() for s in segs)
        norm = _norm_he if lang == "he" else _norm_en
        ref_n, hyp_n = norm(r["text"]), norm(hyp)
        wer = jiwer.wer(ref_n, hyp_n) if hyp_n else 1.0
        cer = jiwer.cer(ref_n, hyp_n) if hyp_n else 1.0
        e = {"file": name, "asr_model": repo, "asr_text": hyp, "asr_wer": round(wer, 3), "asr_cer": round(cer, 3)}
        out.append(e)
        print(f"{name}: WER={wer:.2f} CER={cer:.2f} | {hyp}", flush=True)
    ASR.write_text("\n".join(json.dumps(e, ensure_ascii=False) for e in out) + "\n", encoding="utf-8")


# --------------------------------------------------------------------------------------------
# Report: COMPARE files, MP3s, RESULTS.md
# --------------------------------------------------------------------------------------------
COMPARES = {
    "COMPARE_A_kokoro": [f"A_kokoro_line{i}.wav" for i in EN],
    "COMPARE_B_turbo": [f"B_turbo_line{i}.wav" for i in EN],
    "COMPARE_B_turbo_tags": ["B_turbo_line1.wav", "B_turbo_tags_line1.wav", "B_turbo_line2.wav", "B_turbo_tags_line2.wav"],
    "COMPARE_C_mtl_ex0.5": [f"C_mtl_ex0.5_line{i}.wav" for i in EN],
    "COMPARE_C_mtl_ex0.8": [f"C_mtl_ex0.8_line{i}.wav" for i in EN],
    "COMPARE_C_mtl_hebrew": ["C_mtl_he_H1.wav", "C_mtl_he_H2.wav", "C_mtl_he_H1_noDiacritics.wav"],
    "COMPARE_D_voxcpm": ["D_voxcpm_line1.wav", "D_voxcpm_he_H1.wav", "D_voxcpm_he_H2.wav"],
    "COMPARE_line1_all_models": ["A_kokoro_line1.wav", "B_turbo_line1.wav", "B_turbo_tags_line1.wav",
                                 "C_mtl_ex0.5_line1.wav", "C_mtl_ex0.8_line1.wav", "D_voxcpm_line1.wav"],
}


def find_ffmpeg() -> str | None:
    p = shutil.which("ffmpeg")
    if p:
        return p
    pat = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Microsoft", "WinGet", "Packages", "*FFmpeg*", "**", "ffmpeg.exe")
    hits = glob.glob(pat, recursive=True)
    return hits[0] if hits else None


def _resample(x, sr, target):
    if sr == target:
        return x
    from scipy.signal import resample_poly
    from math import gcd
    g = gcd(sr, target)
    return resample_poly(x, target // g, sr // g).astype(np.float32)


def load_records() -> dict:
    recs = {}
    if MEAS.exists():
        for line in MEAS.read_text(encoding="utf-8").splitlines():
            if line.strip():
                r = json.loads(line)
                recs[r["file"]] = r
    return recs


def cmd_report(args) -> None:
    ff = find_ffmpeg()
    print("ffmpeg:", ff)
    made = []
    for cname, files in COMPARES.items():
        present = [f for f in files if (OUT / f).exists()]
        if len(present) < 2:
            continue
        clips = [read_wav(OUT / f) for f in present]
        target = max(sr for _, sr in clips)
        parts = []
        for k, (x, sr) in enumerate(clips):
            parts.append(_resample(x, sr, target))
            if k < len(clips) - 1:
                parts.append(np.zeros(int(GAP_S * target), dtype=np.float32))
        y = np.concatenate(parts)
        wpath = OUT / f"{cname}.wav"
        write_wav(wpath, y, target)
        mp3 = OUT / f"{cname}.mp3"
        if ff:
            subprocess.run([ff, "-y", "-loglevel", "error", "-i", str(wpath), "-codec:a", "libmp3lame", "-q:a", "2",
                            str(mp3)], check=True)
        made.append((cname, present, len(y) / target, mp3.exists()))
        print(f"{cname}: {present} -> {len(y) / target:.1f}s mp3={mp3.exists()}")

    recs = load_records()
    asr = {}
    if ASR.exists():
        for line in ASR.read_text(encoding="utf-8").splitlines():
            if line.strip():
                e = json.loads(line)
                asr[e["file"]] = e
    order = sorted(recs.values(), key=lambda r: (r["candidate"], r["file"]))
    L = ["# PC TTS listening-test spike: results", "",
         f"Generated by `pc/tts/spike_tts.py` on {_dt.date.today().isoformat()}. "
         "Machine: i9-10885H, 32 GB RAM, GTX 1650 Ti Max-Q 4 GB (driver 573.57). "
         "All numbers below are measured from the sample files and timers in the script; nothing here is a "
         "judgement of how good a voice *sounds*.", "",
         "- **RMS / peak** are dBFS over the whole file. **RTF** = synthesis wall time / audio duration "
         "(<1 is faster than real time), measured after one un-saved warm-up generation, model load excluded.",
         "- **VRAM torch** = `torch.cuda.max_memory_allocated()` during that one generation (weights included). "
         "**VRAM smi** = peak whole-GPU `memory.used` (NVML, the nvidia-smi source, polled every 500 ms), which includes the "
         "Windows desktop's own usage (baseline column, read before the model loaded). Peak dBFS > 0 means the float "
         "output exceeded full scale before 16-bit conversion (those samples are clipped in the WAV).",
         f"- **w/s** = words per second (expected ~{EXPECTED_WPS}). Flags: SILENT (RMS < -45 dBFS), "
         "DURATION_OFF (outside 0.5-2x of words/2.5), CLIPPING, LONG_PAUSE (>1.5 s inside speech), "
         "ABRUPT_END (last 40 ms louder than -30 dBFS).",
         "- **ASR WER** = word error rate of a Whisper transcript against the script (English: "
         "`Systran/faster-whisper-small.en`; Hebrew: `ivrit-ai/whisper-large-v3-turbo-ct2`, niqqud stripped). "
         "It is an intelligibility proxy only: 0 means the recognizer heard the right words, not that it sounds good.", "",
         "| File | Model | Device | Dur (s) | RMS dBFS | Peak dBFS | w/s | Wall (s) | RTF | VRAM torch (MB) | VRAM smi peak / baseline (MB) | ASR WER | Flags |",
         "|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for r in order:
        smi = f"{r.get('vram_smi_peak_mb')} / {r.get('vram_smi_baseline_mb')}" if r.get("vram_smi_peak_mb") else "-"
        L.append(f"| `{r['file']}` | {r['model']} | {r['device']} | {r['duration_s']} | {r['rms_dbfs']} | "
                 f"{r['peak_dbfs']} | {r['words_per_s']} | {r['wall_s']} | {r['rtf']} | "
                 f"{r.get('vram_torch_peak_mb') or '-'} | {smi} | "
                 f"{asr[r['file']]['asr_wer'] if r['file'] in asr else '-'} | {'; '.join(r['flags'])} |")
    L += ["", "## Settings and silence structure", "",
          "| File | Settings | Lead / trail silence (s) | Longest inner pause (s) | Clipped samples | SM clock min/med/max (MHz) | Load (s) | Note |",
          "|---|---|---|---|---|---|---|---|"]
    for r in order:
        clk = "/".join(map(str, r["sm_clock_mhz_min_med_max"])) if r.get("sm_clock_mhz_min_med_max") else "-"
        L.append(f"| `{r['file']}` | {r.get('settings', '')} | {r['lead_silence_s']} / {r['trail_silence_s']} | "
                 f"{r['longest_inner_pause_s']} | {r['clipped_samples']} | {clk} | {r.get('load_s')} | {r.get('note') or ''} |")
    if asr:
        L += ["", "## ASR transcripts", "", "| File | WER | CER | Whisper heard |", "|---|---|---|---|"]
        for r in order:
            e = asr.get(r["file"])
            if e:
                L.append(f"| `{r['file']}` | {e['asr_wer']} | {e['asr_cer']} | {e['asr_text']} |")
    voc = [r for r in order if r.get("text_vocalized")]
    if voc:
        L += ["", "## Hebrew diacritization proof (dicta-1.0.int8.onnx)", ""]
        for r in voc:
            L += [f"- `{r['file']}`: {r['diacritics_added']} niqqud marks added", "",
                  f"  - raw: {r['text']}", f"  - vocalized: {r['text_vocalized']}", ""]
    L += ["", "## Comparison files", "", "| File | Contents (0.7 s gaps) | Length (s) | MP3 |", "|---|---|---|---|"]
    for cname, present, dur, has_mp3 in made:
        L.append(f"| `{cname}.wav` | {', '.join(present)} | {dur:.1f} | {'yes' if has_mp3 else 'no'} |")
    extra = OUT / "RESULTS_notes.md"
    if extra.exists():
        L += ["", extra.read_text(encoding="utf-8")]
    (OUT / "RESULTS.md").write_text("\n".join(L) + "\n", encoding="utf-8")
    print("wrote", OUT / "RESULTS.md")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("cmd", choices=["kokoro", "turbo", "mtl", "voxcpm", "asr", "report"])
    ap.add_argument("--device", default="cuda", choices=["cuda", "cpu"])
    ap.add_argument("--threads", type=int, default=8, help="kokoro CPU threads")
    ap.add_argument("--fp16-t3", action="store_true", help="mtl: cast the T3 backbone to fp16 (Turing: no bf16)")
    ap.add_argument("--hebrew-only", action="store_true", help="mtl: skip the English sweep")
    ap.add_argument("--voxcpm-repo", default="openbmb/VoxCPM2")
    ap.add_argument("--suffix", default="", help="append to output file names, e.g. _fp16")
    args = ap.parse_args()
    global SUFFIX
    SUFFIX = args.suffix
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    {"kokoro": cmd_kokoro, "turbo": cmd_turbo, "mtl": cmd_mtl, "voxcpm": cmd_voxcpm, "asr": cmd_asr,
     "report": cmd_report}[args.cmd](args)


if __name__ == "__main__":
    main()
