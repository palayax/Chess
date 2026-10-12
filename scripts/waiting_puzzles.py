#!/usr/bin/env python3
"""Mine and verify the "While you wait" puzzles (docs/WAITING_CONTENT.md).

Every puzzle is a decisive moment that was PLAYED in one of the app's own famous games
(app/src/main/assets/famous_games.pgn): the position before the move, the move, and (for a
multi-move solution) the forced reply and the next move. Nothing is downloaded from anywhere;
the only inputs are the games and a local Stockfish binary.

    python scripts/waiting_puzzles.py mine   [--engine <stockfish.exe>] [--work <dir>] [--workers 8]
    python scripts/waiting_puzzles.py verify [--engine <stockfish.exe>]

``mine`` writes app/src/main/assets/waiting/puzzles.json. ``verify`` re-checks the committed file from
scratch and exits 1 on any failure. The engine is found with --engine, $STOCKFISH, or by walking up from
this script's directory looking for pc/bin/stockfish/*.exe.

THE RULES A PUZZLE MUST PASS (all thresholds are stated here and echoed in the output file's "rules" key)
---------------------------------------------------------------------------------------------------
Scores are in centipawns from the point of view of the side to move ("the solver"); a mate counts as
infinite for the comparison and is reported as "mate in N".

1. The solution's first move is the move that was played in the game AND the engine's first choice.
2. MATE puzzle: the engine sees a forced mate in N (2 <= N <= 4) and NO other first move gives a forced
   mate in N or fewer (the second-best MultiPV line is not a mate in <= N). For N <= 2 this is also proved
   without the engine, by exhaustive search with python-chess (``verify`` and ``mine`` both run it).
3. OTHER puzzle: the best move scores at least WIN_CP (+150) for the solver, and the second-best move scores
   at least MARGIN_CP (150) less than the best (a mate counts as higher than any score). So the best move is
   "clearly" better than every other move, by at least a pawn and a half.
4. Not obvious: a depth-1 search by the same engine does NOT already choose the answer (this removes
   recaptures, free captures and mate-in-one).
5. Both checks use depth 18 and again depth 20 (``mine``; ``verify`` repeats depth 20 on 4 threads) on a
   fresh engine state (ucinewgame before every position, so the numbers do not depend on search order);
   a puzzle must pass the rules at BOTH depths with the same best move.
6. Multi-move solutions: after the solver's move, the opponent's reply is auto-played. It is accepted only
   when it is forced or near-forced: the only legal move; or all other replies are at least MARGIN_CP worse for
   the opponent than the engine's reply; or, in a mate puzzle, every reply loses to mate (the engine's
   longest defence is played). The solver's next move must again pass rule 2 or 3 in the position reached.
   A solution has at most MAX_SOLVER_MOVES (2) solver moves, or ends in checkmate.
"""
import argparse
import concurrent.futures
import glob
import json
import os
import sys
import time

try:
    import chess
    import chess.engine
    import chess.pgn
except ImportError:  # pragma: no cover
    sys.exit("python-chess is required: pip install chess")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
PGN_FILE = os.path.join(ASSETS, "famous_games.pgn")
INDEX_FILE = os.path.join(ASSETS, "famous_games_index.tsv")
OUT_FILE = os.path.join(ASSETS, "waiting", "puzzles.json")

WIN_CP = 150
MARGIN_CP = 150
MAX_MATE = 4
MAX_SOLVER_MOVES = 2
CONT_DEPTH = 16        # depth of the later steps of a multi-move solution (the first move gets the deep checks)
SCREEN_DEPTH = 14      # cheap pass that finds candidates
QUICK_DEPTH = 16       # cheap filter before the expensive checks (not a rule)
CONFIRM_DEPTH = 18     # rule 5, first check
FINAL_DEPTH = 20       # rule 5, second check; `verify` repeats it on 4 threads
OBVIOUS_DEPTH = 1      # rule 4
BIG = 1_000_000        # "a mate" in a comparison


def find_engine(explicit=None):
    candidates = [explicit, os.environ.get("STOCKFISH")]
    d = HERE
    for _ in range(6):
        candidates += glob.glob(os.path.join(d, "pc", "bin", "stockfish", "*.exe"))
        candidates += glob.glob(os.path.join(d, "pc", "bin", "stockfish", "stockfish*"))
        d = os.path.dirname(d)
    for c in candidates:
        if c and os.path.isfile(c) and not c.endswith((".md", ".txt", ".cff")):
            return c
    sys.exit("Stockfish not found: pass --engine or set STOCKFISH")


# ---------------------------------------------------------------------------------------------
# Engine helpers
# ---------------------------------------------------------------------------------------------
class Eng:
    def __init__(self, path, hash_mb=128, threads=1):
        self.e = chess.engine.SimpleEngine.popen_uci(path)
        self.e.configure({"Threads": threads, "Hash": hash_mb})
        self.name = self.e.id.get("name", "unknown")
        self._n = 0

    def lines(self, board, depth, multipv):
        """MultiPV lines, best first: [(move, cp_or_BIG_for_solver, mate_or_None, depth)]. Fresh state each call."""
        self._n += 1
        info = self.e.analyse(board, chess.engine.Limit(depth=depth), multipv=multipv, game=object())
        out = []
        for i in info:
            pv = i.get("pv")
            if not pv:
                continue
            sc = i["score"].pov(board.turn)
            if sc.is_mate():
                m = sc.mate()
                cp = BIG - abs(m) if m > 0 else -BIG + abs(m)
                out.append((pv[0], cp, m, i.get("depth", depth), pv))
            else:
                out.append((pv[0], sc.score(), None, i.get("depth", depth), pv))
        return out

    def quit(self):
        try:
            self.e.quit()
        except Exception:
            pass


def mate_n(line):
    return line[2]


# ---------------------------------------------------------------------------------------------
# Independent mate proof (no engine): is there a forced mate in <= n for the side to move?
# ---------------------------------------------------------------------------------------------
def mates_within(board, n):
    """True when the side to move can force checkmate in at most n of its own moves."""
    if n <= 0:
        return False
    for mv in list(board.legal_moves):
        board.push(mv)
        ok = False
        if board.is_checkmate():
            ok = True
        elif n > 1 and not board.is_game_over():
            ok = True
            for reply in list(board.legal_moves):
                board.push(reply)
                good = mates_within(board, n - 1)
                board.pop()
                if not good:
                    ok = False
                    break
        board.pop()
        if ok:
            return True
    return False


def first_moves_that_mate_within(board, n):
    """All first moves after which the opponent cannot avoid mate within n moves of ours."""
    found = []
    for mv in list(board.legal_moves):
        board.push(mv)
        ok = False
        if board.is_checkmate():
            ok = True
        elif n > 1 and not board.is_game_over():
            ok = True
            for reply in list(board.legal_moves):
                board.push(reply)
                good = mates_within(board, n - 1)
                board.pop()
                if not good:
                    ok = False
                    break
        board.pop()
        if ok:
            found.append(mv)
    return found


# ---------------------------------------------------------------------------------------------
# Rules 2 and 3 on one set of engine lines
# ---------------------------------------------------------------------------------------------
def judge_step(lines):
    """Returns (kind, detail) where kind is 'mate' | 'cp' | None for the solver's position.

    detail: {"move": Move, "mate": N|None, "bestCp": int|None, "secondCp": int|None}."""
    if len(lines) < 2:
        return None, "fewer than two lines"
    best, second = lines[0], lines[1]
    if best[2] is not None and best[2] > 0:
        n = best[2]
        if n < 2 and False:
            return None, "mate in one"
        if n > MAX_MATE:
            return None, f"mate in {n} is too long"
        if second[2] is not None and second[2] > 0 and second[2] <= n:
            return None, f"second line also mates in {second[2]}"
        sec_cp = None if (second[2] is not None) else second[1]
        return "mate", {"move": best[0], "mate": n, "bestCp": None, "secondCp": sec_cp,
                        "secondMate": second[2]}
    if best[1] < WIN_CP:
        return None, f"best only {best[1]} cp"
    if best[1] - second[1] < MARGIN_CP:
        return None, f"margin {best[1] - second[1]} cp"
    return "cp", {"move": best[0], "mate": None, "bestCp": best[1], "secondCp": second[1],
                  "secondMate": second[2] if second[2] is not None and second[2] < 0 else None}


def reply_ok(lines, board_after_solver, mate_mode):
    """Rule 6: is the opponent's reply (lines[0]) forced or near-forced? Lines are from the OPPONENT's view."""
    legal = list(board_after_solver.legal_moves)
    if len(legal) == 1:
        return True
    if len(lines) < 2:
        return False
    best, second = lines[0], lines[1]
    if mate_mode:
        # every reply loses to mate: the opponent's best line is a mate against it, and so is the second
        if best[2] is not None and best[2] < 0 and second[2] is not None and second[2] < 0:
            return True
    # near-forced: every other reply is at least MARGIN_CP worse for the opponent
    return best[1] - second[1] >= MARGIN_CP


# ---------------------------------------------------------------------------------------------
# Phase 1: screen every position of every game
# ---------------------------------------------------------------------------------------------
def load_games():
    games = []
    with open(PGN_FILE, encoding="utf-8") as f:
        while True:
            g = chess.pgn.read_game(f)
            if g is None:
                break
            games.append(g)
    ids = []
    with open(INDEX_FILE, encoding="utf-8") as f:
        for line in f:
            if line.startswith("#") or line.startswith("id\t") or not line.strip():
                continue
            ids.append(line.split("\t")[0])
    if len(ids) != len(games):
        sys.exit(f"index has {len(ids)} games, PGN has {len(games)}")
    return list(zip(ids, games))


def positions_of(game):
    """[(ply_index_1_based, board_before, move, prev_uci)]"""
    out = []
    board = game.board()
    prev = None
    ply = 0
    for mv in game.mainline_moves():
        ply += 1
        out.append((ply, board.copy(), mv, prev))
        board.push(mv)
        prev = mv.uci()
    return out


_worker = {}


def stop_pool(ex):
    """Do not wait for the workers' orderly exit: a worker holding a live Stockfish child can hang the interpreter's
    shutdown for minutes. All results are on disk already; end the workers (their engines die with their pipes)."""
    procs = list((getattr(ex, "_processes", None) or {}).values())
    ex.shutdown(wait=False, cancel_futures=True)
    for proc in procs:
        try:
            proc.terminate()
        except Exception:
            pass


def _engine(path):
    """One engine per worker process, quit when the worker exits (a live Stockfish child would hang the pool's shutdown)."""
    if "eng" not in _worker:
        import atexit
        _worker["eng"] = Eng(path)
        atexit.register(_worker["eng"].quit)
    return _worker["eng"]


def _screen_game(args):
    path, gid, pgn_text = args
    eng = _engine(path)
    import io
    game = chess.pgn.read_game(io.StringIO(pgn_text))
    cands = []
    for ply, board, mv, prev in positions_of(game):
        if ply < 8:
            continue
        if board.legal_moves.count() < 4:
            continue
        lines = eng.lines(board, SCREEN_DEPTH, 2)
        if len(lines) < 2 or lines[0][0] != mv:
            continue
        kind, detail = judge_step(lines)
        # the screen is deliberately a little looser than the confirm pass, so a depth-14 wobble does not lose a
        # puzzle; the confirm pass applies the real rules
        loose = False
        if kind is None:
            b = lines[0]
            s = lines[1]
            if b[2] is not None and b[2] > 0 and b[2] <= MAX_MATE and not (s[2] is not None and 0 < s[2] <= b[2]):
                loose = True
            elif b[2] is None and b[1] >= WIN_CP - 50 and b[1] - s[1] >= MARGIN_CP - 50:
                loose = True
        if kind is not None or loose:
            cands.append({"ply": ply})
    return gid, cands


def screen(engine_path, workers, work):
    """Phase 1, cached per game in <work>/screen/<id>.json so an interrupted run resumes."""
    games = load_games()
    cache_dir = os.path.join(work, "screen")
    os.makedirs(cache_dir, exist_ok=True)
    out = {}
    todo = []
    for gid, g in games:
        f = os.path.join(cache_dir, gid + ".json")
        if os.path.isfile(f):
            out[gid] = json.load(open(f))
        else:
            exporter = chess.pgn.StringExporter(headers=True, variations=False, comments=False)
            todo.append((engine_path, gid, g.accept(exporter)))
    t0 = time.time()
    if todo:
        with concurrent.futures.ProcessPoolExecutor(max_workers=workers) as ex:
            futures = [ex.submit(_screen_game, t) for t in todo]
            for i, fut in enumerate(concurrent.futures.as_completed(futures), 1):
                gid, cands = fut.result()
                json.dump(cands, open(os.path.join(cache_dir, gid + ".json"), "w"))
                out[gid] = cands
                print(f"  screened {i}/{len(todo)} {gid}: {len(cands)} candidates ({time.time() - t0:.0f}s)", flush=True)
            stop_pool(ex)
    return {gid: out[gid] for gid, _ in games}


# ---------------------------------------------------------------------------------------------
# Phase 2: confirm one candidate at depth D and grow the solution line
# ---------------------------------------------------------------------------------------------
def grow(eng, board, played, depth):
    """Apply the rules at `depth` from `board` (solver to move, `played` the game move).

    Returns the puzzle skeleton or (None, reason)."""
    # rule 4 first, because it is nearly free and removes most candidates
    shallow = eng.lines(board, OBVIOUS_DEPTH, 1)
    if shallow and shallow[0][0] == played:
        return None, "obvious at depth 1"
    lines = eng.lines(board, depth, 2)
    kind, detail = judge_step(lines)
    if kind is None:
        return None, detail
    if detail["move"] != played:
        return None, "engine prefers another move than the one played"

    first = dict(detail, kind=kind, depth=min(l[3] for l in lines[:2]))
    sol = [played]
    steps = [dict(kind=kind, mate=detail["mate"], bestCp=detail["bestCp"], secondCp=detail["secondCp"])]
    b = board.copy()
    b.push(played)
    mate_mode = kind == "mate"
    solver_moves = 1
    while not b.is_game_over():
        if solver_moves >= MAX_SOLVER_MOVES and not mate_mode:
            break
        rl = eng.lines(b, min(depth, CONT_DEPTH), 2)
        if not rl:
            break
        if not reply_ok(rl, b, mate_mode):
            if mate_mode:
                return None, "mate puzzle with a loose defence"
            break
        reply = rl[0][0]
        b2 = b.copy()
        b2.push(reply)
        if b2.is_game_over():
            sol.append(reply)
            b = b2
            break
        nxt = eng.lines(b2, min(depth, CONT_DEPTH), 2)
        k2, d2 = judge_step(nxt)
        if k2 is None or (mate_mode and k2 != "mate"):
            if mate_mode:
                return None, f"mate line not unique later: {d2}"
            break
        sol.append(reply)
        sol.append(d2["move"])
        steps.append(dict(kind=k2, mate=d2["mate"], bestCp=d2["bestCp"], secondCp=d2["secondCp"]))
        b = b2
        b.push(d2["move"])
        solver_moves += 1
    return {"solution": [m.uci() for m in sol], "first": first, "steps": steps, "endsInMate": b.is_checkmate()}, None


def san_line(board, ucis):
    b = board.copy()
    out = []
    for u in ucis:
        mv = chess.Move.from_uci(u)
        out.append(b.san(mv))
        b.push(mv)
    return out


def interest_key(p):
    """Higher is better: prefers puzzles with a longer, harder-to-see win and a bigger margin."""
    first = p["steps"][0]
    margin = 0
    if first["kind"] == "cp":
        margin = first["bestCp"] - first["secondCp"]
    else:
        margin = 800
    return (len(p["solution"]), min(margin, 1500))


def mine(args):
    engine_path = find_engine(args.engine)
    work = args.work
    os.makedirs(work, exist_ok=True)
    print("engine:", engine_path)
    scr = screen(engine_path, args.workers, work)
    games = dict(load_games())
    n_cands = sum(len(v) for v in scr.values())
    print(f"phase 1 done: {n_cands} candidates in {sum(1 for v in scr.values() if v)} games")

    # round-robin over the games, so an interrupted run has covered many games
    jobs = []
    k = 0
    while True:
        added = False
        for gid, cands in scr.items():
            if k < len(cands):
                jobs.append((engine_path, gid, cands[k]["ply"]))
                added = True
        if not added:
            break
        k += 1

    results = []
    t0 = time.time()
    cdir = os.path.join(work, "confirm")
    os.makedirs(cdir, exist_ok=True)
    todo = []
    for job in jobs:
        f = os.path.join(cdir, f"{job[1]}-{job[2]}.json")
        if os.path.isfile(f):
            r = json.load(open(f))
            if r is not None and "fail" not in r:
                results.append(r)
        else:
            todo.append(job)
    if args.only_cached:
        todo = []  # write the file from the candidates confirmed so far (an interrupted run can be used)
    with concurrent.futures.ProcessPoolExecutor(max_workers=args.workers) as ex:
        futures = {ex.submit(_confirm_job, job): job for job in todo}
        for i, fut in enumerate(concurrent.futures.as_completed(futures), 1):
            job = futures[fut]
            try:
                r = fut.result()
            except Exception as e:  # noqa: BLE001  a crash in one candidate must not stop the run
                print("  ERROR", job[1], job[2], repr(e), flush=True)
                r = {"fail": f"exception {e!r}"}
            json.dump(r, open(os.path.join(cdir, f"{job[1]}-{job[2]}.json"), "w"))
            if r is not None and "fail" not in r:
                results.append(r)
            if i % 10 == 0 or i == len(todo):
                print(f"  confirmed {i}/{len(todo)}: {len(results)} pass ({time.time() - t0:.0f}s)", flush=True)
        stop_pool(ex)

    # one or two per game, the most interesting first, at least 12 plies apart
    chosen = []
    by_game = {}
    for r in results:
        by_game.setdefault(r["game"], []).append(r)
    for gid, rs in by_game.items():
        rs.sort(key=interest_key, reverse=True)
        picked = []
        for r in rs:
            if all(abs(r["ply"] - q["ply"]) >= 12 for q in picked):
                picked.append(r)
            if len(picked) >= args.per_game:
                break
        chosen += picked
    order = {gid: i for i, gid in enumerate(games)}
    chosen.sort(key=lambda r: (order[r["game"]], r["ply"]))
    print(f"phase 2 done: {len(results)} puzzles pass depth {CONFIRM_DEPTH}+{FINAL_DEPTH}; keeping {len(chosen)}")

    write_output(chosen, engine_path, args)


def _confirm_job(job):
    path, gid, ply = job
    eng = _engine(path)
    game = dict(load_games_cached())[gid]
    for p, board, mv, prev in positions_of(game):
        if p == ply:
            break
    else:
        return {"fail": "ply not found"}
    # cheap filter, then the two documented depths (rule 5) with the same solution
    a, why = grow(eng, board, mv, CONFIRM_DEPTH)
    if a is None:
        return {"fail": f"depth {CONFIRM_DEPTH}: {why}"}
    b, why2 = grow(eng, board, mv, FINAL_DEPTH)
    if b is None:
        return {"fail": f"depth {FINAL_DEPTH}: {why2}"}
    if b["solution"] != a["solution"]:
        return {"fail": "solution differs between depths"}
    # the tap-to-move board auto-queens, so a solution may only promote to a queen
    if any(len(u) == 5 and u[4] != "q" for u in b["solution"]):
        return {"fail": "non-queen promotion"}
    # independent proof for short mates
    first = b["steps"][0]
    if first["kind"] == "mate" and first["mate"] <= 2:
        got = first_moves_that_mate_within(board.copy(), first["mate"])
        if got != [mv]:
            return {"fail": "exhaustive mate search disagrees"}
    sans = san_line(board, b["solution"])
    return {
        "game": gid, "ply": ply, "fen": board.fen(), "prev": prev, "solution": b["solution"], "san": sans,
        "steps": b["steps"], "endsInMate": b["endsInMate"], "depth": min(a["first"]["depth"], b["first"]["depth"]),
        "engine": eng.name,
    }


_games_cache = {}


def load_games_cached():
    if "g" not in _games_cache:
        _games_cache["g"] = load_games()
    return _games_cache["g"]


def goal_of(r):
    first = r["steps"][0]
    if first["kind"] == "mate":
        return {"type": "mate", "moves": first["mate"]}
    return {"type": "best"}


def write_output(chosen, engine_path, args):
    items = []
    for i, r in enumerate(chosen, 1):
        board = chess.Board(r["fen"])
        first = r["steps"][0]
        item = {
            "id": f"p{i:03d}",
            "game": r["game"],
            "ply": r["ply"],
            "fen": r["fen"],
            "prev": r["prev"],
            "goal": goal_of(r),
            "solution": r["solution"],
            "san": r["san"],
        }
        if first["kind"] == "cp":
            item["bestCp"] = first["bestCp"]
            item["secondCp"] = first["secondCp"]
        else:
            item["secondCp"] = first["secondCp"]
        item["depth"] = r["depth"]
        items.append(item)
    doc = {
        "version": 1,
        "engine": chosen[0]["engine"] if chosen else "unknown",
        "rules": {
            "winCp": WIN_CP, "marginCp": MARGIN_CP, "maxMate": MAX_MATE, "maxSolverMoves": MAX_SOLVER_MOVES,
            "confirmDepth": CONFIRM_DEPTH, "finalDepth": FINAL_DEPTH, "obviousDepth": OBVIOUS_DEPTH,
        },
        "puzzles": items,
    }
    os.makedirs(os.path.dirname(OUT_FILE), exist_ok=True)
    with open(OUT_FILE, "w", encoding="utf-8", newline="\n") as f:
        json.dump(doc, f, ensure_ascii=False, indent=1)
        f.write("\n")
    print("wrote", OUT_FILE, len(items), "puzzles")


# ---------------------------------------------------------------------------------------------
# verify: re-check the committed file from scratch
# ---------------------------------------------------------------------------------------------
def verify(args):
    engine_path = find_engine(args.engine)
    doc = json.load(open(OUT_FILE, encoding="utf-8"))
    games = dict(load_games())
    eng = Eng(engine_path, hash_mb=256, threads=4)
    fails = []
    seen = set()
    margins = []
    for p in doc["puzzles"]:
        pid = p["id"]
        where = f"{pid} ({p['game']} ply {p['ply']})"
        try:
            if pid in seen:
                fails.append(f"{where}: duplicate id")
            seen.add(pid)
            game = games[p["game"]]
            # the position is the game's, the first move is the game's move
            mainline = list(game.mainline_moves())
            board = game.board()
            for mv in mainline[:p["ply"] - 1]:
                board.push(mv)
            if board.fen() != p["fen"]:
                fails.append(f"{where}: FEN is not the game's position before ply {p['ply']}")
                continue
            if p["prev"] != (mainline[p["ply"] - 2].uci() if p["ply"] >= 2 else None):
                fails.append(f"{where}: prev move is not the game's previous move")
            if p["solution"][0] != mainline[p["ply"] - 1].uci():
                fails.append(f"{where}: first solution move is not the move played in the game")
            # legality + SAN
            b = board.copy()
            for u, s in zip(p["solution"], p["san"]):
                mv = chess.Move.from_uci(u)
                if mv not in b.legal_moves:
                    fails.append(f"{where}: illegal solution move {u}")
                    break
                if b.san(mv) != s:
                    fails.append(f"{where}: SAN {s} != {b.san(mv)}")
                b.push(mv)
            if len(p["solution"]) % 2 == 0:
                fails.append(f"{where}: a solution must end on a solver move")
            if len(p["solution"]) != len(p["san"]):
                fails.append(f"{where}: san length")
            goal = p["goal"]
            if goal["type"] == "mate":
                if not b.is_checkmate():
                    fails.append(f"{where}: mate puzzle does not end in checkmate")
                if (len(p["solution"]) + 1) // 2 != goal["moves"]:
                    fails.append(f"{where}: mate in {goal['moves']} but {(len(p['solution']) + 1) // 2} solver moves")
                if goal["moves"] <= 2:
                    got = first_moves_that_mate_within(board.copy(), goal["moves"])
                    if got != [chess.Move.from_uci(p["solution"][0])]:
                        fails.append(f"{where}: exhaustive search finds mate-in-{goal['moves']} first moves {[m.uci() for m in got]}")
                    if goal["moves"] > 1 and first_moves_that_mate_within(board.copy(), goal["moves"] - 1):
                        fails.append(f"{where}: a shorter mate exists")
            # engine re-check at the final depth, 4 threads (a different configuration than mine)
            lines = eng.lines(board, FINAL_DEPTH, 2)
            kind, detail = judge_step(lines)
            if kind is None:
                fails.append(f"{where}: engine rules fail at depth {FINAL_DEPTH}: {detail}")
            elif detail["move"].uci() != p["solution"][0]:
                fails.append(f"{where}: engine prefers {detail['move']}")
            else:
                if goal["type"] == "best":
                    margins.append(detail["bestCp"] - detail["secondCp"])
                    if kind != "cp":
                        fails.append(f"{where}: goal best but engine says mate")
                else:
                    if kind != "mate" or detail["mate"] != goal["moves"]:
                        fails.append(f"{where}: engine mate {detail.get('mate')} != goal {goal['moves']}")
            shallow = eng.lines(board, OBVIOUS_DEPTH, 1)
            if shallow and shallow[0][0].uci() == p["solution"][0]:
                fails.append(f"{where}: obvious at depth 1")
            # every auto-played reply is forced or near-forced; every later solver move passes the rules
            b = board.copy()
            b.push(chess.Move.from_uci(p["solution"][0]))
            for k in range(1, len(p["solution"]), 2):
                if b.is_game_over():
                    break
                rl = eng.lines(b, CONFIRM_DEPTH, 2)
                if rl[0][0].uci() != p["solution"][k] or not reply_ok(rl, b, goal["type"] == "mate"):
                    fails.append(f"{where}: reply {p['solution'][k]} is not the forced/near-forced best reply ({rl[0][0]})")
                    break
                b.push(chess.Move.from_uci(p["solution"][k]))
                if k + 1 < len(p["solution"]):
                    nl = eng.lines(b, CONFIRM_DEPTH, 2)
                    k2, d2 = judge_step(nl)
                    if k2 is None or d2["move"].uci() != p["solution"][k + 1]:
                        fails.append(f"{where}: later solver move {p['solution'][k + 1]} fails the rules: {d2}")
                        break
                    b.push(chess.Move.from_uci(p["solution"][k + 1]))
        except Exception as e:  # noqa: BLE001
            fails.append(f"{where}: exception {e!r}")
    eng.quit()
    n = len(doc["puzzles"])
    print(f"verified {n} puzzles with {eng.name}; smallest best-vs-second margin of the non-mate puzzles: "
          f"{min(margins) if margins else 'n/a'} cp")
    if fails:
        print("FAILURES:")
        for f in fails:
            print("  ", f)
        if args.prune:
            bad = {f.split(" ", 1)[0] for f in fails}
            doc["puzzles"] = [p for p in doc["puzzles"] if p["id"] not in bad]
            with open(OUT_FILE, "w", encoding="utf-8", newline="\n") as f:
                json.dump(doc, f, ensure_ascii=False, indent=1)
                f.write("\n")
            print(f"pruned {len(bad)} puzzles that did not hold up in the independent check; {len(doc['puzzles'])} remain "
                  f"(ids are kept, so the numbering has gaps). Run verify again.")
            sys.exit(0)
        sys.exit(1)
    print("all puzzles pass")


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    m = sub.add_parser("mine")
    m.add_argument("--engine")
    m.add_argument("--work", default=os.path.join(ROOT, "build", "waiting"))
    m.add_argument("--workers", type=int, default=8)
    m.add_argument("--per-game", type=int, default=2)
    m.add_argument("--only-cached", action="store_true", help="select from the confirmations already on disk; start no new analysis")
    v = sub.add_parser("verify")
    v.add_argument("--engine")
    v.add_argument("--prune", action="store_true", help="remove the puzzles that fail the independent check from the file")
    args = ap.parse_args()
    if args.cmd == "mine":
        mine(args)
    else:
        verify(args)


if __name__ == "__main__":
    main()
