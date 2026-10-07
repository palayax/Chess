#!/usr/bin/env python3
"""Local stand-in for the GitHub release that serves the two model files (docs/MODEL_DOWNLOAD_DESIGN.md §6.4).

A debug build pointed at it downloads the models from this machine instead of GitHub:

    adb reverse tcp:8787 tcp:8787                                                  (device 127.0.0.1 -> host, over adb)
    ./gradlew :app:assembleDebug -PpalayaModelBaseUrl=http://127.0.0.1:8787/
    python scripts/model_test_server.py --root vendor/models --port 8787 [--fault ...]

Use adb reverse, NOT http://10.0.2.2:8787/ (R8). The emulator's user-mode network (10.0.2.2 -> host loopback)
drops single bytes of a long response: one byte at a time on 1440-byte segment boundaries, all within the last
~128 KB of the stream, 3-16 bytes per 98.5 MB file in 12 of 12 unthrottled fetches on chess36 (emulator
36.3.10). The host had handed every byte to the emulator process (sendall done in 0.04 s), the same server is
byte-exact to host curl and through adb reverse (3 of 3 raw, and the app's whole setup), and a plain
raw-socket server shows the same loss whether it closes at once, 10 s later or only after the client's EOF.
The app then sees a body N bytes short, resumes the last N bytes and fails the SHA-256 (correctly, twice ->
"didn't match"). --fault slow:3m hides it (0 of 2) but is slow; adb reverse needs neither.

URLs, mirroring GitHub Releases (base + tag + "/" + file):
    /<release.tag>/<net file>         the NNUE net, found anywhere under --root by name
    /<release.tag>/<voice tar>        the Kokoro tar, likewise
    /models/models.json               an UNSIGNED manifest generated from MODELS.lock (upgrades, D2e)

With --manifest-dir DIR (D2e, "Check for updates" on an emulator) the manifest comes from DIR instead:
    /models/models.json, /models/models.json.sig   DIR/models.json and DIR/models.json.sig as they are
                                                  (sign a hand-made manifest with scripts/publish_models.sh --sign)
    /<path>                                       every other file under DIR at its relative path, e.g. an
                                                  "upgrade" at DIR/models-2026.10.1/<file> (faults apply to these too)
A missing models.json.sig is served as 404 (the app must refuse an unsigned manifest).

Range requests are honoured (206 + Content-Range) unless a fault says otherwise. Every request is printed.

Faults (applied to model-file requests only; --fault-times N limits them to the first N such requests,
default 1, 0 = every request):
    truncate:F   send the full Content-Length but close cleanly after fraction F of the body
    drop:F       reset the connection (RST) after fraction F of the body
    hash         flip one byte in the middle of the body (the download fails its SHA-256 check)
    404 | 500    answer with that status
    slow:RATE    throttle to RATE bytes/s (suffix k or m, e.g. 200k)
    redirect     302 to the same file on a second port (--port + 1), like GitHub's CDN redirect
    norange      ignore Range and answer 200 with the whole file

Debug builds only: the app accepts cleartext only for 10.0.2.2 / 127.0.0.1 / localhost, and only in a
debug build (network_security_config in app/src/debug, and ModelDownloader's own rule).
"""
import argparse
import json
import os
import re
import socket
import struct
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def read_lock(path):
    props = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            props[k.strip()] = v.strip()
    return props


def net_name():
    with open(os.path.join(ROOT_DIR, "vendor", "Stockfish", "src", "evaluate.h"), encoding="utf-8") as f:
        m = re.search(r'#define\s+EvalFileDefaultName\s+"([^"]+)"', f.read())
    if not m:
        sys.exit("cannot read EvalFileDefaultName from vendor/Stockfish/src/evaluate.h")
    return m.group(1)


def find_file(root, name):
    for dirpath, _dirs, files in os.walk(root):
        if name in files:
            return os.path.join(dirpath, name)
    return None


class State:
    def __init__(self, args):
        self.args = args
        self.lock = read_lock(args.lock)
        self.tag = self.lock["release.tag"]
        self.net = net_name()
        # D2f: the app downloads the voice as <tar>.gz (vendor/models/app-assets/tts/, made by fetch_models.sh).
        self.tar = self.lock["kokoro.archive.url"].rsplit("/", 1)[-1][: -len(".bz2")] + ".gz"
        self.files = {}
        for name in (self.net, self.tar):
            path = find_file(args.root, name)
            if path is None:
                sys.exit(f"{name} not found under {args.root} (run scripts/fetch_models.sh)")
            self.files[f"/{self.tag}/{name}"] = path
        self.manifest_dir = os.path.abspath(args.manifest_dir) if args.manifest_dir else None
        if self.manifest_dir:
            if not os.path.isfile(os.path.join(self.manifest_dir, "models.json")):
                sys.exit(f"{self.manifest_dir}/models.json not found")
            for dirpath, _dirs, names in os.walk(self.manifest_dir):
                for n in names:
                    full = os.path.join(dirpath, n)
                    rel = os.path.relpath(full, self.manifest_dir).replace(os.sep, "/")
                    if rel in ("models.json", "models.json.sig"):
                        continue
                    self.files["/" + rel] = full
        self.fault_kind, self.fault_arg = None, None
        if args.fault:
            kind, _, arg = args.fault.partition(":")
            self.fault_kind, self.fault_arg = kind, arg
        self.fault_left = args.fault_times if args.fault_times > 0 else None  # None = always
        self.mutex = threading.Lock()

    def take_fault(self):
        """The fault to apply to this model-file request, or None."""
        with self.mutex:
            if self.fault_kind is None:
                return None
            if self.fault_left is None:
                return self.fault_kind
            if self.fault_left <= 0:
                return None
            self.fault_left -= 1
            return self.fault_kind

    def manifest(self, base):
        lk = self.lock
        def entry(mid, display, version, name, size, sha, compat, runtime=None, tar=None):
            e = {"id": mid, "displayName": display, "version": version, "fileName": name,
                 "url": f"{base}{self.tag}/{name}", "size": int(size), "sha256": sha,
                 "minVersionCode": 1, "maxVersionCode": None, "compat": compat}
            if runtime:
                e["runtime"] = runtime
            if tar:
                e["tarSize"], e["tarSha256"] = int(tar[0]), tar[1]
            return e
        return {
            "schemaVersion": 1,
            "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "models": [
                entry("engine-net", "Chess engine data", self.net[:-5], self.net, lk["net.size"], lk["net.sha256"],
                      {"kind": "stockfish-nnue", "version": lk["net.version"], "archHash": lk["net.arch_hash"]}),
                entry("voice-kokoro-en", "Narration voice", "v0_19", self.tar, lk["kokoro.targz.size"], lk["kokoro.targz.sha256"],
                      {"kind": "sherpa-onnx-kokoro", "layout": "kokoro-v0_19"},
                      tar=(lk["kokoro.tar.size"], lk["kokoro.tar.sha256"])),
            ],
        }


def parse_rate(text):
    m = re.fullmatch(r"(\d+)([km]?)", text.lower())
    if not m:
        sys.exit(f"bad rate {text}")
    return int(m.group(1)) * {"": 1, "k": 1024, "m": 1024 * 1024}[m.group(2)]


def make_handler(state, port_offset):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *a):
            sys.stdout.write("%s :%d %s\n" % (time.strftime("%H:%M:%S"), self.server.server_address[1], fmt % a))
            sys.stdout.flush()

        def plain(self, code, text):
            body = text.encode()
            self.send_response(code)
            self.send_header("Content-Type", "text/plain")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            print(f"    Range: {self.headers.get('Range')}  User-Agent: {self.headers.get('User-Agent')}")
            path = self.path.split("?", 1)[0]
            host = self.headers.get("Host", f"127.0.0.1:{self.server.server_address[1]}")
            if path in ("/models/models.json", "/models/models.json.sig"):
                if state.manifest_dir:
                    f = os.path.join(state.manifest_dir, path.rsplit("/", 1)[-1])
                    if not os.path.isfile(f):
                        return self.plain(404, "not found")
                    with open(f, "rb") as fh:
                        body = fh.read()
                elif path.endswith(".sig"):
                    return self.plain(404, "not found")
                else:
                    body = json.dumps(state.manifest(f"http://{host}/"), indent=2).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/octet-stream" if path.endswith(".sig") else "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Connection", "close")
                self.end_headers()
                self.wfile.write(body)
                return
            file = state.files.get(path)
            if file is None:
                return self.plain(404, "not found")
            # The redirect target (second port) serves the file without applying faults again.
            fault = state.take_fault() if port_offset == 0 else None
            if fault:
                print(f"    FAULT {fault}{':' + state.fault_arg if state.fault_arg else ''}")
            if fault in ("404", "500"):
                return self.plain(int(fault), fault)
            if fault == "redirect":
                hostname = host.rsplit(":", 1)[0]
                self.send_response(302)
                self.send_header("Location", f"http://{hostname}:{state.args.port + 1}{path}?signed=demo")
                self.send_header("Content-Length", "0")
                self.send_header("Connection", "close")
                self.end_headers()
                return
            size = os.path.getsize(file)
            start = 0
            rng = self.headers.get("Range")
            partial = False
            if rng and fault != "norange":
                m = re.fullmatch(r"bytes=(\d+)-", rng.strip())
                if not m:
                    return self.plain(416, "bad range")
                start = int(m.group(1))
                if start >= size:
                    self.send_response(416)
                    self.send_header("Content-Range", f"bytes */{size}")
                    self.send_header("Content-Length", "0")
                    self.send_header("Connection", "close")
                    self.end_headers()
                    return
                partial = True
            length = size - start
            self.send_response(206 if partial else 200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(length))
            if partial:
                self.send_header("Content-Range", f"bytes {start}-{size - 1}/{size}")
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Connection", "close")
            self.end_headers()

            stop_after = None
            if fault in ("truncate", "drop"):
                stop_after = int(length * float(state.fault_arg or "0.5"))
            flip_at = size // 2 if fault == "hash" else None
            rate = parse_rate(state.fault_arg) if fault == "slow" else None
            sent = 0
            began = time.monotonic()
            with open(file, "rb") as f:
                f.seek(start)
                while sent < length:
                    chunk = f.read(min(64 * 1024, length - sent))
                    if not chunk:
                        break
                    if flip_at is not None and start + sent <= flip_at < start + sent + len(chunk):
                        i = flip_at - (start + sent)
                        chunk = chunk[:i] + bytes([chunk[i] ^ 0xFF]) + chunk[i + 1:]
                    if stop_after is not None and sent + len(chunk) > stop_after:
                        chunk = chunk[: stop_after - sent]
                    try:
                        self.wfile.write(chunk)
                    except (BrokenPipeError, ConnectionResetError):
                        print("    client went away")
                        return
                    sent += len(chunk)
                    if stop_after is not None and sent >= stop_after:
                        if fault == "drop":
                            self.connection.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
                        print(f"    {fault}: stopped after {sent} of {length} bytes")
                        self.close_connection = True
                        return
                    if rate:
                        ahead = sent / rate - (time.monotonic() - began)
                        if ahead > 0:
                            time.sleep(ahead)
    return Handler


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", default=os.path.join(ROOT_DIR, "vendor", "models"))
    ap.add_argument("--lock", default=os.path.join(ROOT_DIR, "vendor", "models", "MODELS.lock"))
    ap.add_argument("--host", default="127.0.0.1", help="the emulator reaches the host's loopback as 10.0.2.2")
    ap.add_argument("--port", type=int, default=8787)
    ap.add_argument("--fault", help="truncate:F | drop:F | hash | 404 | 500 | slow:RATE | redirect | norange")
    ap.add_argument("--fault-times", type=int, default=1, help="apply the fault to the first N file requests (0 = always)")
    ap.add_argument("--manifest-dir", help="serve DIR/models.json(.sig) and every other file under DIR (D2e update tests)")
    args = ap.parse_args()
    if args.fault and args.fault.partition(":")[0] not in ("truncate", "drop", "hash", "404", "500", "slow", "redirect", "norange"):
        ap.error(f"unknown fault {args.fault}")
    state = State(args)
    servers = [ThreadingHTTPServer((args.host, args.port), make_handler(state, 0))]
    if state.fault_kind == "redirect":
        servers.append(ThreadingHTTPServer((args.host, args.port + 1), make_handler(state, 1)))
    for path, file in state.files.items():
        print(f"serving {path}  ({os.path.getsize(file)} bytes, {file})")
    if state.manifest_dir:
        print(f"manifest from {state.manifest_dir} (signature {'present' if os.path.isfile(os.path.join(state.manifest_dir, 'models.json.sig')) else 'MISSING: 404'})")
    print(f"listening on http://{args.host}:{args.port}/ (emulator: `adb reverse tcp:{args.port} tcp:{args.port}`"
          f" and http://127.0.0.1:{args.port}/; 10.0.2.2 loses bytes of long downloads, see the header)"
          + (f", fault {args.fault} x{args.fault_times or 'always'}" if args.fault else ""))
    for s in servers[1:]:
        threading.Thread(target=s.serve_forever, daemon=True).start()
    try:
        servers[0].serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
