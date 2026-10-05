"""Palaya TTS worker: JSON lines over stdin/stdout (docs/PC_PRODUCER_DESIGN.md section 11).

    -> {"cmd":"hello"}
    <- {"ok":true,"backend":"kokoro","version":"...","device":"cpu","sampleRate":24000,...}
    -> {"cmd":"synth","id":"b017_l0","text":"...","lang":"en","emotion":"excited","out":"C:/.../b017_l0.wav"}
    <- {"ok":true,"id":"b017_l0","durationMs":2310,"sampleRate":24000,"rtf":0.61}
    <- {"ok":false,"id":"...","error":"..."}
    -> {"cmd":"device","value":"cpu"}
    -> {"cmd":"quit"}

stdout carries protocol lines and nothing else. Native libraries (onnxruntime, torch) sometimes
print to fd 1, so fd 1 is re-pointed at stderr at startup and the protocol writes to a private
duplicate of the original stdout.

The Python interpreter is chosen by the caller (the producer's --tts-python flag), so each backend
can live in its own venv: kokoro/fake in pc/tts/.venv-kokoro, Chatterbox (later) in pc/tts/.venv.
"""
import argparse
import io
import json
import os
import sys
import time
import traceback

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)


def _protocol_stream():
    proto_fd = os.dup(1)
    os.dup2(2, 1)
    sys.stdout = sys.stderr
    return io.open(proto_fd, "w", encoding="utf-8", newline="\n", buffering=1)


def log(msg):
    sys.stderr.write("[tts_worker] " + msg + "\n")
    sys.stderr.flush()


def load_backend(args):
    if args.backend == "fake":
        from backends.fake import FakeBackend
        return FakeBackend(args)
    if args.backend == "kokoro":
        from backends.kokoro import KokoroBackend
        return KokoroBackend(args)
    raise SystemExit("unknown backend: " + args.backend)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--backend", required=True, choices=["fake", "kokoro"])
    ap.add_argument("--model-dir", default=os.path.join(HERE, "..", "models", "kokoro", "kokoro-int8-en-v0_19"))
    ap.add_argument("--sid", type=int, default=1, help="kokoro speaker id (1 = af_bella)")
    ap.add_argument("--length-scale", type=float, default=1.2)
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--fake-ms-per-char", type=float, default=60.0)
    args = ap.parse_args()

    out = _protocol_stream()
    stdin = io.TextIOWrapper(sys.stdin.buffer, encoding="utf-8")

    def reply(obj):
        out.write(json.dumps(obj, ensure_ascii=False) + "\n")
        out.flush()

    t0 = time.time()
    try:
        backend = load_backend(args)
    except Exception as e:  # report the load failure through the protocol, then exit
        traceback.print_exc()
        reply({"ok": False, "error": "backend load failed: %s: %s" % (type(e).__name__, e)})
        return 3
    log("backend %s loaded in %.2f s" % (args.backend, time.time() - t0))

    for raw in stdin:
        raw = raw.strip()
        if not raw:
            continue
        try:
            msg = json.loads(raw)
        except ValueError as e:
            reply({"ok": False, "error": "bad json: %s" % e})
            continue
        cmd = msg.get("cmd")
        try:
            if cmd == "hello":
                info = {"ok": True, "loadSeconds": round(time.time() - t0, 3)}
                info.update(backend.info())
                reply(info)
            elif cmd == "synth":
                sid = msg.get("id")
                t = time.time()
                duration_ms, rate = backend.synth(msg["text"], msg.get("lang", "en"), msg.get("emotion", "neutral"), msg["out"])
                elapsed = time.time() - t
                rtf = elapsed / max(duration_ms / 1000.0, 1e-6)
                reply({"ok": True, "id": sid, "durationMs": int(round(duration_ms)), "sampleRate": rate,
                       "rtf": round(rtf, 4), "synthSeconds": round(elapsed, 4)})
            elif cmd == "device":
                ok = backend.set_device(msg.get("value", "cpu"))
                reply({"ok": ok, "device": backend.device})
            elif cmd == "quit":
                reply({"ok": True, "bye": True})
                break
            else:
                reply({"ok": False, "error": "unknown cmd: %r" % cmd})
        except Exception as e:
            traceback.print_exc()
            reply({"ok": False, "id": msg.get("id"), "error": "%s: %s" % (type(e).__name__, e)})
    return 0


if __name__ == "__main__":
    sys.exit(main())
