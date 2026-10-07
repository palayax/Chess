# Part of the famous-games curation tools (G1, docs/FAMOUS_GAMES.md section 4).
"""Negative checks: the verifier must fail on a bad game."""
import os
import shutil
import subprocess
import sys
import tempfile

W = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
HERE = os.path.dirname(os.path.abspath(__file__))
NEG = os.path.join(tempfile.mkdtemp(prefix="famous_selftest_"), "root")
CASES = [
    ("wrong result on a mate", "23. Be7# 1-0", "23. Be7# 0-1"),
    ("illegal move", "5. Bxb5 Nf6", "5. Bxb6 Nf6"),
    ("annotation", "23. Be7# 1-0", "23. Be7#! 1-0"),
    ("comment", "2. f4 exf4", "2. f4 {King's Gambit} exf4"),
    ("control (unchanged)", "4. Kf1 b5", "4. Kf1 b5"),  # must pass
]
pgn = open(os.path.join(W, "app", "src", "main", "assets", "famous_games.pgn"), encoding="utf-8").read()
failures = 0
for name, old, new in CASES:
    shutil.rmtree(NEG, ignore_errors=True)
    os.makedirs(os.path.join(NEG, "app", "src", "main", "assets"))
    os.makedirs(os.path.join(NEG, "docs"))
    shutil.copy(os.path.join(W, "app", "src", "main", "assets", "famous_games_index.tsv"), os.path.join(NEG, "app", "src", "main", "assets"))
    shutil.copy(os.path.join(W, "docs", "famous_games_sources.tsv"), os.path.join(NEG, "docs"))
    assert old in pgn, old
    open(os.path.join(NEG, "app", "src", "main", "assets", "famous_games.pgn"), "w", encoding="utf-8").write(pgn.replace(old, new, 1))
    r = subprocess.run([sys.executable, os.path.join(W, "scripts", "verify_famous_games.py"), "--root", NEG], capture_output=True, text=True)
    want = 0 if name.startswith("control") else 1
    ok = r.returncode == want
    failures += 0 if ok else 1
    print(f"{'ok ' if ok else 'BAD'} {name}: exit {r.returncode}: {r.stdout.strip().splitlines()[-1][:150]}")
sys.exit(1 if failures else 0)
