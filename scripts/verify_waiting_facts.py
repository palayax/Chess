#!/usr/bin/env python3
"""Computed checks of facts in assets/waiting/facts.json that the Kotlin tests cannot reach (docs/WAITING_CONTENT.md).

    python scripts/verify_waiting_facts.py

Fact "kqk": with perfect play a king and queen mate a lone king in at most 10 moves, king and rook in at most 16.
Proved here by retrograde analysis over EVERY legal position of the two endings (python-chess generates the moves,
no tablebase file is used): the longest forced mate, over all positions with the stronger side to move, is the
number of moves of the stronger side. Exit code 1 if either number differs from the fact's text.
"""
import itertools
import json
import os
import sys

import chess

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def longest_mate(piece_type):
    """Max over all legal positions (White = K + piece, to move) of the White moves needed to force mate."""
    keys = {}
    boards = []
    sq = range(64)
    for wk, wp, bk in itertools.permutations(sq, 3):
        for turn in (chess.WHITE, chess.BLACK):
            b = chess.Board(None)
            b.set_piece_at(wk, chess.Piece(chess.KING, chess.WHITE))
            b.set_piece_at(wp, chess.Piece(piece_type, chess.WHITE))
            b.set_piece_at(bk, chess.Piece(chess.KING, chess.BLACK))
            b.turn = turn
            if not b.is_valid():
                continue
            keys[(wk, wp, bk, turn)] = len(boards)
            boards.append(b)
    # successors
    succ = []
    for b in boards:
        out = []
        for m in b.legal_moves:
            b.push(m)
            wk = b.king(chess.WHITE)
            bk = b.king(chess.BLACK)
            pieces = [s for s in b.pieces(piece_type, chess.WHITE)]
            if pieces:
                out.append(keys[(wk, pieces[0], bk, b.turn)])
            else:
                out.append(-1)  # the piece was captured: a draw, never a win
            b.pop()
        succ.append(out)
    n = len(boards)
    INF = 10**9
    white_to_move = [b.turn == chess.WHITE for b in boards]
    dist = [INF] * n  # for black-to-move: plies; use "white moves" count
    for i, b in enumerate(boards):
        if not white_to_move[i] and b.is_checkmate():
            dist[i] = 0
    # value iteration by white-move count d = 1, 2, ...
    d = 0
    best = 0
    while True:
        d += 1
        newly = []
        for i in range(n):
            if not white_to_move[i] or dist[i] != INF:
                continue
            for j in succ[i]:
                if j >= 0 and dist[j] != INF and dist[j] == d - 1:
                    newly.append(i)
                    break
        if not newly:
            break
        for i in newly:
            dist[i] = d
        # black-to-move positions all of whose replies lead to a white win in <= d
        black_new = []
        for i in range(n):
            if white_to_move[i] or dist[i] != INF:
                continue
            ss = succ[i]
            if ss and all(j >= 0 and dist[j] != INF and dist[j] <= d for j in ss):
                black_new.append(i)
        for i in black_new:
            dist[i] = d  # mated after White's d-th move
        best = d
    return best


def main():
    facts = {f["id"]: f["text"] for f in json.load(open(os.path.join(ROOT, "app", "src", "main", "assets", "waiting", "facts.json"), encoding="utf-8"))["facts"]}
    text = facts["kqk"]
    ok = True
    for name, pt, claim in (("queen", chess.QUEEN, "at most 10 moves"), ("rook", chess.ROOK, "at most 16")):
        got = longest_mate(pt)
        print(f"king and {name} against king: longest forced mate = {got} moves")
        want = int(claim.split()[-2] if claim.endswith("moves") else claim.split()[-1])
        if got != want or claim not in text:
            print(f"  MISMATCH: fact says '{claim}'")
            ok = False
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
