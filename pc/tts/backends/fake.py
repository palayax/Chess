"""A deterministic stand-in voice: a 440 Hz tone whose length is proportional to the text.

It exists so the whole AUDIO -> TIMELINE -> MIX chain (and the JSONL protocol, and a real Python
process) is exercised without a model. Layout of every WAV:
    100 ms silence | tone (ms_per_char * len(text), min 300 ms, 10 ms fades) | 100 ms silence
Amplitude 0.3 of full scale: peak about -10.5 dBFS, tone RMS about -13.5 dBFS.
"""
import math

from backends.wavio import pack_int16, write_pcm16

SAMPLE_RATE = 24000
FREQ = 440.0
AMPLITUDE = 0.3
PAD_MS = 100
FADE_MS = 10


class FakeBackend:
    def __init__(self, args):
        self.ms_per_char = args.fake_ms_per_char
        self.device = "cpu"

    def info(self):
        return {"backend": "fake", "version": "1", "device": self.device, "sampleRate": SAMPLE_RATE,
                "voice": "tone440", "params": {"msPerChar": self.ms_per_char, "padMs": PAD_MS, "freq": FREQ},
                "supportsTags": [], "supportsExaggeration": False, "langs": ["en", "he"]}

    def set_device(self, value):
        return True

    def synth(self, text, lang, emotion, out):
        tone_ms = max(300, int(round(self.ms_per_char * len(text.strip()))))
        pad = SAMPLE_RATE * PAD_MS // 1000
        n = SAMPLE_RATE * tone_ms // 1000
        fade = SAMPLE_RATE * FADE_MS // 1000
        values = [0] * pad
        w = 2 * math.pi * FREQ / SAMPLE_RATE
        for i in range(n):
            env = min(1.0, i / fade, (n - 1 - i) / fade)
            values.append(int(round(32767 * AMPLITUDE * env * math.sin(w * i))))
        values.extend([0] * pad)
        write_pcm16(out, pack_int16(values), SAMPLE_RATE)
        return len(values) * 1000.0 / SAMPLE_RATE, SAMPLE_RATE
