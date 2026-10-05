"""Kokoro (sherpa-onnx, CPU): kokoro-int8-en-v0_19, speaker sid 1 (af_bella), length_scale 1.2.

The baseline English voice. CPU only: the GPU belongs to the Chatterbox spike while it runs.
Model: pc/models/kokoro/kokoro-int8-en-v0_19 (sherpa-onnx `tts-models` GitHub release).
"""
import os

import numpy as np
import sherpa_onnx

from backends.wavio import write_pcm16


class KokoroBackend:
    def __init__(self, args):
        d = os.path.abspath(args.model_dir)
        for name in ("model.int8.onnx", "voices.bin", "tokens.txt", "espeak-ng-data"):
            if not os.path.exists(os.path.join(d, name)):
                raise FileNotFoundError("kokoro model incomplete, missing %s in %s" % (name, d))
        self.model_dir = d
        self.sid = args.sid
        self.length_scale = args.length_scale
        self.threads = args.threads
        self.device = "cpu"
        cfg = sherpa_onnx.OfflineTtsConfig(
            model=sherpa_onnx.OfflineTtsModelConfig(
                kokoro=sherpa_onnx.OfflineTtsKokoroModelConfig(
                    model=os.path.join(d, "model.int8.onnx"),
                    voices=os.path.join(d, "voices.bin"),
                    tokens=os.path.join(d, "tokens.txt"),
                    data_dir=os.path.join(d, "espeak-ng-data"),
                    length_scale=self.length_scale,
                ),
                provider="cpu",
                num_threads=self.threads,
                debug=False,
            ),
            max_num_sentences=1,
        )
        if not cfg.validate():
            raise RuntimeError("sherpa-onnx rejected the kokoro config for " + d)
        self.tts = sherpa_onnx.OfflineTts(cfg)
        self.sample_rate = self.tts.sample_rate

    def info(self):
        return {"backend": "kokoro", "version": "sherpa-onnx " + getattr(sherpa_onnx, "__version__", "?"),
                "model": "kokoro-int8-en-v0_19", "device": self.device, "sampleRate": self.sample_rate,
                "voice": "af_bella", "params": {"sid": self.sid, "lengthScale": self.length_scale, "threads": self.threads},
                "supportsTags": [], "supportsExaggeration": False, "langs": ["en"]}

    def set_device(self, value):
        return value == "cpu"

    def synth(self, text, lang, emotion, out):
        # speed stays 1.0 so the config's length_scale (1.2) alone sets the pace.
        audio = self.tts.generate(text, sid=self.sid, speed=1.0)
        samples = np.asarray(audio.samples, dtype=np.float32)
        if samples.size == 0:
            raise RuntimeError("kokoro produced no samples")
        pcm16 = (np.clip(samples, -1.0, 1.0) * 32767.0).round().astype("<i2")
        write_pcm16(out, pcm16.tobytes(), audio.sample_rate)
        return pcm16.size * 1000.0 / audio.sample_rate, audio.sample_rate
