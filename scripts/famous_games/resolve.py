# Part of the famous-games curation tools (G1, docs/FAMOUS_GAMES.md section 4).
"""Resolve a curated game: its primary extraction and the best independent second source in the pool."""
import json
import sys
import chess

sys.stdout.reconfigure(encoding="utf-8")
POOL = json.load(open("pool.json", encoding="utf-8"))


def replay(san_line):
    b = chess.Board()
    ucis = []
    for t in san_line.split():
        if t.endswith("."):
            continue
        m = b.parse_san(t)
        ucis.append(m.uci())
        b.push(m)
    return ucis, b


for r in POOL:
    r["uci"], r["_b"] = replay(r["san"])
    r["board_fen"] = r["_b"].board_fen()


def find_primary(lang, title, section="", first=None, plies=None):
    sec_ok = (lambda s: s == section[1:]) if section.startswith("=") else (lambda s: section in s)
    c = [r for r in POOL if r["lang"] == lang and r["title"] == title and sec_ok(r["section"])
         and (first is None or r["san"].startswith(first)) and (plies is None or len(r["uci"]) == plies)]
    if not c:
        raise KeyError(f"no primary for {lang}|{title}|{section}|{first}")
    return max(c, key=lambda r: len(r["uci"]))


def compare(p, r):
    a, b = p["uci"], r["uci"]
    if a == b:
        return "same-moves"
    if len(a) == len(b) and p["board_fen"] == r["board_fen"] and a[:6] == b[:6]:
        return "same-position"
    if len(b) < len(a) and a[:len(b)] == b and len(b) >= min(20, len(a) - 1):
        return "prefix"
    # shared opening but diverging later: a conflict worth a look
    n = 0
    while n < min(len(a), len(b)) and a[n] == b[n]:
        n += 1
    if n >= 16 and n < min(len(a), len(b)):
        return f"conflict@{n}"
    if len(b) > len(a) and b[:len(a)] == a and len(a) >= 16:
        return "longer"
    return None


RANK = {"same-moves": 3, "same-position": 2, "prefix": 1}


def resolve(lang, title, section="", first=None, plies=None):
    p = find_primary(lang, title, section, first, plies)
    others = []
    for r in POOL:
        if r["lang"] == p["lang"] and r["title"] == p["title"]:
            continue
        k = compare(p, r)
        if k:
            others.append((k, r))
    best = None
    for k, r in others:
        if k in RANK:
            key = (RANK[k], len(r["uci"]))
            if best is None or key > best[0]:
                best = (key, k, r)
    return p, best, others


def url(lang, title):
    return f"https://{lang}.wikipedia.org/wiki/" + title.replace(" ", "_")


if __name__ == "__main__":
    specs = sys.argv[1:]
    if specs and specs[0].startswith("@"):
        specs = [l.strip() for l in open(specs[0][1:], encoding="utf-8") if l.strip()]
    for spec in specs:
        parts = spec.split("|") + ["", "", ""]
        lang, title, section, first = parts[0], parts[1], parts[2], parts[3] or None
        try:
            p, best, others = resolve(lang, title, section, first)
        except KeyError as e:
            print("== MISSING", spec)
            continue
        b = p["_b"]
        st = "MATE" if b.is_checkmate() else ("STALE" if b.is_stalemate() else "")
        print(f"== {spec}: {len(p['uci'])} plies res={p['result']} {st}")
        print("   last:", " ".join(p["san"].split()[-7:]))
        if best:
            print(f"   2nd: {best[1]} {best[2]['lang']}:{best[2]['title'][:50]}/{best[2]['section'][:30]} ({len(best[2]['uci'])})")
        for k, r in others:
            if not k in RANK or (best and r is not best[2]):
                if k.startswith("conflict") or k == "longer":
                    print(f"   !! {k} {r['lang']}:{r['title'][:50]}/{r['section'][:30]} ({len(r['uci'])})")
