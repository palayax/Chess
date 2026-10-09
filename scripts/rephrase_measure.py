#!/usr/bin/env python3
"""C2 P2a: run the recorded commentary through a candidate wording model on the host (docs/LLM_REPHRASE_DESIGN.md §5.5).

Input: the corpus written by `:core:test --tests *RephraseCorpusDumpTest*` (core/build/rephrase/corpus.jsonl and the
fixed prompt prefixes, byte for byte what the app's backend sends). For each text it asks a local llama.cpp server
(`llama-server`, CPU only, greedy, the same stop rules and token cap as the app: 2 x the original's tokens + 16) for a
rewording and records the raw output and the server's own timings. It judges nothing: the Kotlin ClaimChecker
(`RephraseMeasurementDumpTest`) and the Python twin (`audit_commentary.py rephrase`) do that on the output file.

    python -I scripts/rephrase_measure.py --model vendor/models/rephrase-assets/qwen2.5-1.5b-instruct-q4_k_m.gguf \
        --family qwen2 --out core/build/rephrase/raw_qwen2.5-1.5b-q4_k_m.jsonl [--threads 8] [--limit N]

The server binary defaults to pc/bin/llama/llama-server.exe (llama.cpp b11190, the tag :rephrase pins). Network:
loopback only (127.0.0.1); the model file is read by the server, never parsed here.
"""
import argparse
import json
import os
import subprocess
import sys
import time
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def post(port, path, body, timeout=600):
    req = urllib.request.Request(
        f"http://127.0.0.1:{port}{path}", data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def wait_ready(port, proc, timeout=300):
    t0 = time.time()
    while time.time() - t0 < timeout:
        if proc.poll() is not None:
            raise SystemExit(f"llama-server exited with {proc.returncode}")
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=5) as r:
                if r.status == 200:
                    return
        except Exception:
            pass
        time.sleep(1)
    raise SystemExit("llama-server did not become ready")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--family", choices=["qwen2", "qwen3"], required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--corpus", default=os.path.join(ROOT, "core/build/rephrase/corpus.jsonl"))
    ap.add_argument("--server", default=os.path.join(ROOT, "pc/bin/llama/llama-server.exe"))
    ap.add_argument("--threads", type=int, default=8)
    ap.add_argument("--ctx", type=int, default=2048)
    ap.add_argument("--port", type=int, default=8791)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--only", choices=["CARD", "NARRATION"], default=None)
    ap.add_argument("--narration-every", type=int, default=1,
                    help="keep every Nth narration beat (all cards are kept): the comparison runs of P2a use 4")
    args = ap.parse_args()

    prefix = open(os.path.join(os.path.dirname(args.corpus), f"prefix_{args.family}.txt"), encoding="utf-8").read()
    items = [json.loads(l) for l in open(args.corpus, encoding="utf-8") if l.strip()]
    if args.only:
        items = [i for i in items if i["surface"] == args.only]
    if args.narration_every > 1:
        kept, n = [], 0
        for i in items:
            if i["surface"] == "NARRATION":
                n += 1
                if (n - 1) % args.narration_every:
                    continue
            kept.append(i)
        items = kept
    if args.limit:
        items = items[: args.limit]

    log_dir = os.path.join(ROOT, "core/build/rephrase/serverlogs")
    os.makedirs(log_dir, exist_ok=True)
    log = open(os.path.join(log_dir, os.path.basename(args.out) + ".server.log"), "w", encoding="utf-8")
    proc = subprocess.Popen(
        [args.server, "-m", args.model, "-c", str(args.ctx), "-t", str(args.threads), "-tb", str(args.threads),
         "-ngl", "0", "--host", "127.0.0.1", "--port", str(args.port), "-np", "1", "--no-webui", "-fa", "off"],
        stdout=log, stderr=subprocess.STDOUT)
    try:
        wait_ready(args.port, proc)
        prefix_tokens = len(post(args.port, "/tokenize", {"content": prefix, "parse_special": True})["tokens"])
        # Warm the prefix into the KV cache once, as the app does on load.
        post(args.port, "/completion", {"prompt": prefix, "n_predict": 0, "cache_prompt": True, "parse_special": True})
        with open(args.out, "w", encoding="utf-8") as out:
            for n, it in enumerate(items):
                suffix = it["suffix_" + args.family]
                orig_tokens = len(post(args.port, "/tokenize", {"content": it["text"]})["tokens"])
                n_predict = 2 * orig_tokens + 16
                t0 = time.time()
                r = post(args.port, "/completion", {
                    "prompt": prefix + suffix, "n_predict": n_predict, "temperature": 0.0, "top_k": 1, "seed": 7,
                    "cache_prompt": True, "stop": ["<|im_end|>", "<|endoftext|>", "\n\n"], "parse_special": True,
                })
                wall = (time.time() - t0) * 1000
                tm = r.get("timings", {})
                rec = {
                    "id": it["id"], "surface": it["surface"], "kind": it["kind"], "game": it["game"], "side": it["side"],
                    "text": it["text"], "raw": r.get("content", ""), "orig_tokens": orig_tokens, "n_predict": n_predict,
                    "prefix_tokens": prefix_tokens, "prompt_n": tm.get("prompt_n"), "prompt_ms": tm.get("prompt_ms"),
                    "predicted_n": tm.get("predicted_n"), "predicted_ms": tm.get("predicted_ms"), "wall_ms": round(wall, 1),
                    "cache_n": tm.get("cache_n"), "stop_type": r.get("stop_type"), "truncated": r.get("truncated"),
                }
                out.write(json.dumps(rec, ensure_ascii=False) + "\n")
                out.flush()
                if n % 25 == 0:
                    print(f"{n}/{len(items)} {rec['wall_ms']:.0f} ms", flush=True)
    finally:
        proc.terminate()
        try:
            proc.wait(10)
        except Exception:
            proc.kill()
    print("done", args.out)


if __name__ == "__main__":
    main()
