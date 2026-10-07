#!/usr/bin/env python3
"""Verify the built-in famous-games library (G1, docs/FAMOUS_GAMES.md) with python-chess.

Independent of the app's own move generator (FamousGamesAssetTest replays the same games with :core): a game
that fails here must not ship. Exit code 0 only when every check passes; otherwise every failure is printed
and the exit code is 1.

Checks, per game:
  * the PGN parses with python-chess without errors, from the standard start, every move legal;
  * bare moves only: no comment, NAG, variation or annotation glyph; the SAN written is python-chess's own
    SAN for the move (so checks are marked with + and mates with #, and nothing else);
  * only factual tags (the Seven Tag Roster plus ECO), Date in PGN form, ECO in [A-E]nn form;
  * the Result tag equals the movetext's termination marker, and fits the final position: a checkmate is won
    by the side that gave it, a stalemate is a draw;
  * the index row (famous_games_index.tsv) agrees with the PGN tags (White, Black, Result, the Date's year),
    in the same order; ids, titles and move sequences are unique;
  * the index description is ours and makes no chess claim: one sentence, at most 240 characters, and no
    move notation in it;
  * the sources file (docs/famous_games_sources.tsv) cites a Wikipedia URL for every game, and, where a
    second source was compared at curation time, its recorded ply count and final position match this game.

Usage:  python scripts/verify_famous_games.py [--root <repo>] [-v]
"""
import argparse
import csv
import io
import os
import re
import sys

try:
    import chess
    import chess.pgn
except ImportError:  # pragma: no cover
    sys.exit("python-chess is required: pip install chess")

ASSETS = os.path.join("app", "src", "main", "assets")
PGN_FILE = os.path.join(ASSETS, "famous_games.pgn")
INDEX_FILE = os.path.join(ASSETS, "famous_games_index.tsv")
SOURCES_FILE = os.path.join("docs", "famous_games_sources.tsv")

ALLOWED_TAGS = {"Event", "Site", "Date", "Round", "White", "Black", "Result", "ECO"}
ROSTER = ["Event", "Site", "Date", "Round", "White", "Black", "Result"]
RESULTS = {"1-0", "0-1", "1/2-1/2"}
ERAS = ["romantic", "classical", "interwar", "postwar", "kasparov", "modern", "computers"]
INDEX_COLUMNS = ["id", "era", "title", "white", "black", "year", "result", "description"]
SOURCE_COLUMNS = ["id", "source_url", "second_source_url", "second_check", "second_plies", "second_final_board", "notes"]
SECOND_CHECKS = {"same-moves", "same-position", "prefix", "none"}
# Move-like tokens a description must not contain (we state facts about the game, not about its moves).
NOTATION = re.compile(r"(?<![A-Za-z])(?:[KQRBN][a-h]?[1-8]?x?[a-h][1-8]|[a-h]x[a-h][1-8]|O-O(?:-O)?|\d+\.\s*[a-hKQRBNO])")


class Report:
    def __init__(self):
        self.errors = []

    def fail(self, where, msg):
        self.errors.append(f"{where}: {msg}")


def read_tsv(path, columns, report):
    rows = []
    with open(path, encoding="utf-8", newline="") as f:
        text = f.read().lstrip("﻿")
    lines = [ln for ln in text.splitlines() if ln.strip() and not ln.startswith("#")]
    if not lines or lines[0].split("\t") != columns:
        report.fail(path, f"header must be {columns}")
        return rows
    for n, ln in enumerate(lines[1:], start=2):
        f = ln.split("\t")
        if len(f) != len(columns):
            report.fail(path, f"row {n}: {len(f)} columns, expected {len(columns)}")
            continue
        rows.append(dict(zip(columns, f)))
    return rows


def split_games(text):
    """The asset's games, as text, split where a tag section follows movetext (as the app does)."""
    games, cur, saw_moves = [], [], False
    for line in text.lstrip("﻿").splitlines():
        t = line.strip()
        if t.startswith("[") and saw_moves:
            games.append("\n".join(cur).strip())
            cur, saw_moves = [], False
        if not t and not cur:
            continue
        if t and not t.startswith("["):
            saw_moves = True
        cur.append(line)
    if "".join(cur).strip():
        games.append("\n".join(cur).strip())
    return games


def movetext_of(game_text):
    return " ".join(ln.strip() for ln in game_text.splitlines() if ln.strip() and not ln.strip().startswith("["))


def check_game(gid, text, report, verbose):
    where = f"game {gid}"
    game = chess.pgn.read_game(io.StringIO(text))
    if game is None:
        report.fail(where, "does not parse")
        return None
    if game.errors:
        report.fail(where, f"python-chess errors: {game.errors}")
    tags = dict(game.headers)
    # python-chess fills in the roster with "?" when absent; read the real tags from the text.
    written = dict(re.findall(r'^\[(\w+)\s+"((?:[^"\\]|\\.)*)"\]\s*$', text, flags=re.M))
    extra = set(written) - ALLOWED_TAGS
    if extra:
        report.fail(where, f"tags beyond the factual set: {sorted(extra)}")
    for t in ROSTER:
        if not written.get(t, "").strip():
            report.fail(where, f"missing tag {t}")
    if "SetUp" in written or "FEN" in written or game.board().fen() != chess.STARTING_FEN:
        report.fail(where, "must start from the standard position")
    date = written.get("Date", "")
    if not re.fullmatch(r"\d{4}\.(\d{2}|\?\?)\.(\d{2}|\?\?)", date):
        report.fail(where, f"Date '{date}' is not a PGN date")
    eco = written.get("ECO")
    if eco is not None and not re.fullmatch(r"[A-E]\d{2}", eco):
        report.fail(where, f"ECO '{eco}'")

    movetext = movetext_of(text)
    if re.search(r"[{};()$!?]", movetext):
        report.fail(where, "movetext has a comment, variation, NAG or annotation glyph")
    tokens = movetext.split()
    if not tokens or tokens[-1] not in RESULTS:
        report.fail(where, f"movetext must end with a result, ends with '{tokens[-1] if tokens else ''}'")
    elif tokens[-1] != written.get("Result"):
        report.fail(where, f"Result tag {written.get('Result')} vs movetext {tokens[-1]}")

    # Replay, and require python-chess's own SAN for every move.
    board = chess.Board()
    sans = []
    # "1. e4 e5 2. Nf3 ...": a number before every White move, and nothing else between the moves.
    expected = 1
    for t in tokens[:-1]:
        m = re.fullmatch(r"(\d+)\.", t)
        if m:
            if int(m.group(1)) != expected or len(sans) % 2 != 0:
                report.fail(where, f"move number '{t}' out of place")
                return None
            expected += 1
        else:
            if len(sans) % 2 == 0 and (expected - 1) * 2 != len(sans) + 2:
                report.fail(where, f"White's move '{t}' has no move number before it")
                return None
            sans.append(t)
    uci = []
    for i, san in enumerate(sans):
        try:
            move = board.parse_san(san)
        except ValueError as e:
            report.fail(where, f"ply {i + 1} '{san}' is not legal: {e}")
            return None
        canonical = board.san(move)
        if canonical != san:
            report.fail(where, f"ply {i + 1} written '{san}', python-chess writes '{canonical}'")
        uci.append(move.uci())
        board.push(move)
    mainline = list(game.mainline_moves())
    if [m.uci() for m in mainline] != uci:
        report.fail(where, "python-chess's own reading of the game differs from the replay")
    for node in game.mainline():
        if node.comment or node.nags or len(node.variations) > 1:
            report.fail(where, "comment, NAG or variation found by python-chess")
            break

    result = written.get("Result")
    if board.is_checkmate():
        winner = "0-1" if board.turn == chess.WHITE else "1-0"
        if result != winner:
            report.fail(where, f"checkmate on the board, but Result is {result} (expected {winner})")
    elif board.is_stalemate():
        if result != "1/2-1/2":
            report.fail(where, f"stalemate on the board, but Result is {result}")
    if verbose:
        end = "mate" if board.is_checkmate() else ("stalemate" if board.is_stalemate() else "")
        print(f"  {gid}: {len(uci)} plies, {result} {end}")
    return {"tags": written, "uci": tuple(uci), "plies": len(uci), "board": board.board_fen(), "mate": board.is_checkmate()}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", default=os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")))
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()
    root = args.root
    report = Report()

    index = read_tsv(os.path.join(root, INDEX_FILE), INDEX_COLUMNS, report)
    sources = read_tsv(os.path.join(root, SOURCES_FILE), SOURCE_COLUMNS, report)
    with open(os.path.join(root, PGN_FILE), encoding="utf-8") as f:
        texts = split_games(f.read())

    if len(index) != len(texts):
        report.fail("library", f"index has {len(index)} games, the PGN {len(texts)}")
    if not 80 <= len(index) <= 120:
        report.fail("library", f"{len(index)} games; the library should hold 80-120")

    seen_ids, seen_titles, seen_moves = {}, {}, {}
    games = {}
    for row, text in zip(index, texts):
        gid = row["id"]
        where = f"index {gid}"
        if not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", gid):
            report.fail(where, "bad id")
        if gid in seen_ids:
            report.fail(where, "duplicate id")
        seen_ids[gid] = True
        if row["era"] not in ERAS:
            report.fail(where, f"unknown era {row['era']}")
        if row["result"] not in RESULTS:
            report.fail(where, f"bad result {row['result']}")
        if not re.fullmatch(r"\d{4}", row["year"]):
            report.fail(where, f"bad year {row['year']}")
        t = row["title"].strip().lower()
        if t in seen_titles:
            report.fail(where, f"same title as {seen_titles[t]}")
        seen_titles[t] = gid
        d = row["description"]
        if len(d) > 240 or not d.endswith("."):
            report.fail(where, "description must be one sentence of at most 240 characters")
        if NOTATION.search(d):
            report.fail(where, f"description contains move notation: '{NOTATION.search(d).group(0)}'")

        g = check_game(gid, text, report, args.verbose)
        if g is None:
            continue
        games[gid] = g
        tags = g["tags"]
        for col, tag in (("white", "White"), ("black", "Black"), ("result", "Result")):
            if row[col] != tags.get(tag):
                report.fail(where, f"{col} '{row[col]}' vs PGN {tag} '{tags.get(tag)}'")
        if row["year"] != tags.get("Date", "")[:4]:
            report.fail(where, f"year {row['year']} vs PGN Date {tags.get('Date')}")
        if g["uci"] in seen_moves:
            report.fail(where, f"same moves as {seen_moves[g['uci']]}")
        seen_moves[g["uci"]] = gid

    # Sources: one row per game, a Wikipedia URL, and the recorded second-source facts.
    by_id = {}
    for s in sources:
        if s["id"] in by_id:
            report.fail(f"sources {s['id']}", "listed twice")
        by_id[s["id"]] = s
    for row in index:
        gid = row["id"]
        s = by_id.get(gid)
        where = f"sources {gid}"
        if s is None:
            report.fail(where, "no source recorded")
            continue
        if not re.match(r"https://[a-z]{2,3}\.wikipedia\.org/wiki/\S+$", s["source_url"]):
            report.fail(where, f"source must be a Wikipedia article URL: {s['source_url']}")
        check = s["second_check"]
        if check not in SECOND_CHECKS:
            report.fail(where, f"second_check '{check}' not in {sorted(SECOND_CHECKS)}")
            continue
        g = games.get(gid)
        if check == "none":
            if s["second_source_url"] != "-":
                report.fail(where, "second_check none but a second URL is given")
            continue
        if not re.match(r"https://[a-z]{2,3}\.wikipedia\.org/wiki/\S+$", s["second_source_url"]):
            report.fail(where, f"second source must be a Wikipedia article URL: {s['second_source_url']}")
        if g is None:
            continue
        try:
            plies = int(s["second_plies"])
        except ValueError:
            report.fail(where, f"second_plies '{s['second_plies']}'")
            continue
        if check in ("same-moves", "same-position"):
            if plies != g["plies"]:
                report.fail(where, f"second source has {plies} plies, the library {g['plies']}")
            if s["second_final_board"] != g["board"]:
                report.fail(where, "second source's final position differs from the library's")
        elif check == "prefix":
            if not 0 < plies < g["plies"]:
                report.fail(where, f"a prefix check needs 0 < {plies} < {g['plies']}")
                continue
            # The second source stops earlier: the library's game must pass through its last position.
            b = chess.Board()
            for u in g["uci"][:plies]:
                b.push_uci(u)
            if s["second_final_board"] != b.board_fen():
                report.fail(where, f"the library's position after {plies} plies differs from the second source's last one")
    for gid in by_id:
        if gid not in seen_ids:
            report.fail(f"sources {gid}", "not in the index")

    if report.errors:
        print(f"FAILED: {len(report.errors)} problem(s) in the famous-games library")
        for e in report.errors:
            print("  " + e)
        return 1
    checks = {}
    for s in sources:
        checks[s["second_check"]] = checks.get(s["second_check"], 0) + 1
    mates = sum(1 for g in games.values() if g["mate"])
    print(f"OK: {len(games)} games verified with python-chess {chess.__version__}; "
          f"second sources: {', '.join(f'{k} {v}' for k, v in sorted(checks.items()))}; {mates} end in checkmate")
    return 0


if __name__ == "__main__":
    sys.exit(main())
