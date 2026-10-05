"""16-bit mono PCM WAV writing, stdlib only (the fake backend must run in any Python)."""
import os
import struct
import wave


def write_pcm16(path, pcm_bytes, sample_rate):
    """Writes little-endian int16 mono bytes atomically (tmp + replace)."""
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    tmp = path + ".tmp"
    with wave.open(tmp, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sample_rate)
        w.writeframes(pcm_bytes)
    os.replace(tmp, path)


def pack_int16(values):
    return struct.pack("<%dh" % len(values), *values)
