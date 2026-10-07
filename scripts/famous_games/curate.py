# Part of the famous-games curation tools (G1, docs/FAMOUS_GAMES.md section 4).
"""Build the famous-games assets from the curated list."""
import io
import os
import sys
import chess
import chess.pgn
from resolve import resolve, url
from games_list import GAMES

sys.stdout.reconfigure(encoding="utf-8")
REPO = sys.argv[1]
OPENINGS = os.path.join(REPO, "app", "src", "main", "assets", "openings.tsv")


def eco_book():
    book = {}
    with open(OPENINGS, encoding="utf-8") as f:
        next(f)
        for line in f:
            eco, name, pgn = line.rstrip("\n").split("\t")
            b = chess.Board()
            for t in pgn.split():
                if t.endswith("."):
                    continue
                b.push_san(t)
            book.setdefault(b.epd(), eco)
    return book


BOOK = eco_book()


def eco_for(ucis):
    b = chess.Board()
    eco = None
    for u in ucis:
        b.push_uci(u)
        e = BOOK.get(b.epd())
        if e:
            eco = e
    return eco


def movetext(ucis, result):
    b = chess.Board()
    parts = []
    for u in ucis:
        m = chess.Move.from_uci(u)
        if b.turn == chess.WHITE:
            parts.append(f"{b.fullmove_number}.")
        parts.append(b.san(m))
        b.push(m)
    parts.append(result)
    # wrap at 80 columns
    lines, cur = [], ""
    for p in parts:
        if len(cur) + 1 + len(p) > 80:
            lines.append(cur)
            cur = p
        else:
            cur = p if not cur else cur + " " + p
    lines.append(cur)
    return "\n".join(lines), b


def esc(s):
    return s.replace("\\", "\\\\").replace('"', '\\"')


pgn_out, index_out, src_out = [], [], []
problems = []
for g in GAMES:
    spec = g["src"]
    p, best, others = resolve(*spec)
    ucis = p["uci"]
    if g.get("plies") and len(ucis) != g["plies"]:
        problems.append(f"{g['id']}: expected {g['plies']} plies, got {len(ucis)}")
    text, board = movetext(ucis, g["result"])
    if board.is_checkmate():
        want = "0-1" if board.turn == chess.WHITE else "1-0"
        if want != g["result"]:
            problems.append(f"{g['id']}: mate on the board but result {g['result']}")
    if p["result"] and p["result"] != g["result"]:
        problems.append(f"{g['id']}: source result token {p['result']} vs {g['result']}")
    eco = g.get("eco") or eco_for(ucis)
    tags = [("Event", g["event"]), ("Site", g["site"]), ("Date", g["date"]), ("Round", g.get("round", "-")),
            ("White", g["white"]), ("Black", g["black"]), ("Result", g["result"])]
    if eco:
        tags.append(("ECO", eco))
    pgn_out.append("\n".join(f'[{k} "{esc(v)}"]' for k, v in tags) + "\n\n" + text + "\n")
    year = g["date"][:4]
    index_out.append("\t".join([g["id"], g["era"], g["title"], g["white"], g["black"], year, g["result"], g["desc"]]))
    lang, title = spec[0], spec[1]
    sec = spec[2].lstrip("=") if len(spec) > 2 else ""
    src_url = url(lang, title) + ("#" + sec.replace(" ", "_") if sec and sec not in ("Partien",) else "")
    if best:
        _, kind, r = best
        r_sec = r["section"]
        second = url(r["lang"], r["title"]) + ("#" + r_sec.replace(" ", "_") if r_sec and "<" not in r_sec and "{" not in r_sec else "")
        src_out.append("\t".join([g["id"], src_url, second, kind, str(len(r["uci"])), r["board_fen"], g.get("note", "-")]))
    else:
        src_out.append("\t".join([g["id"], src_url, "-", "none", "-", "-", g.get("note", "-")]))
    st = "MATE" if board.is_checkmate() else ""
    print(f"{g['id']:38} {len(ucis):4} {g['result']:8} {st:4} {eco or '---'} 2nd={best[1] if best else 'none'}")

for pr in problems:
    print("PROBLEM:", pr)
assets = os.path.join(REPO, "app", "src", "main", "assets")
with open(os.path.join(assets, "famous_games.pgn"), "w", encoding="utf-8", newline="\n") as f:
    f.write("\n".join(pgn_out))
with open(os.path.join(assets, "famous_games_index.tsv"), "w", encoding="utf-8", newline="\n") as f:
    f.write("# Famous games library (G1). One row per game of famous_games.pgn, same order. Descriptions are Palaya's own words.\n")
    f.write("id\tera\ttitle\twhite\tblack\tyear\tresult\tdescription\n")
    f.write("\n".join(index_out) + "\n")
with open(os.path.join(REPO, "docs", "famous_games_sources.tsv"), "w", encoding="utf-8", newline="\n") as f:
    f.write("# Where each famous game's moves come from (G1, docs/FAMOUS_GAMES.md). Moves only; no annotations were taken.\n")
    f.write("# second_check: same-moves | same-position (a transposition or a different but equal piece choice) | prefix (the second source stops earlier) | none\n")
    f.write("id\tsource_url\tsecond_source_url\tsecond_check\tsecond_plies\tsecond_final_board\tnotes\n")
    f.write("\n".join(src_out) + "\n")
print(len(GAMES), "games written;", len(problems), "problems")
