# Part of the famous-games curation tools (G1, docs/FAMOUS_GAMES.md section 4).
import json
import re
import sys
import chess
from wk import category, raw
from extract import sequences, to_san_line

sys.stdout.reconfigure(encoding="utf-8")


def sections(text):
    parts = re.split(r"\n(=+[^=\n].*?=+)\s*\n", "\n" + text)
    out = [("(lead)", parts[0])]
    for k in range(1, len(parts) - 1, 2):
        out.append((parts[k].strip("= ").strip(), parts[k + 1]))
    return out


def survey(lang, title, per_section=False):
    t = raw(lang, title)
    rows = []
    chunks = sections(t) if per_section else [("", t)]
    for head, body in chunks:
        best = {}
        import extract
        for mode, unwrap in (("marked", False), ("all", False), ("marked", True), ("all", True)):
            extract.UNWRAP = unwrap
            for s in sequences(body, lang, mode):
                key = tuple(m.uci() for m in s["moves"][:6])
                cur = best.get(key)
                if cur is None or len(s["moves"]) > len(cur["moves"]):
                    s["mode"] = mode
                    best[key] = s
        for s in best.values():
            b = s["board"]
            status = "MATE" if b.is_checkmate() else ("STALE" if b.is_stalemate() else "")
            rows.append({"lang": lang, "title": title, "section": head, "mode": s["mode"], "plies": len(s["moves"]),
                         "result": s["result"], "status": status, "san": to_san_line(s["moves"])})
    return rows


if __name__ == "__main__":
    targets = json.load(open(sys.argv[1], encoding="utf-8"))
    allrows = []
    for lang, title, per in targets:
        try:
            rows = survey(lang, title, per)
        except Exception as e:
            print("ERR", lang, title, e)
            continue
        for r in rows:
            print(f"{r['lang']} | {r['title']} | {r['section'][:40]} | {r['mode']} | {r['plies']} | {r['result']} | {r['status']} | {r['san'][:60]}")
        allrows += rows
    json.dump(allrows, open(sys.argv[2], "w", encoding="utf-8"), ensure_ascii=False, indent=1)
