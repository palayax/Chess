#!/usr/bin/env python
"""Record a real Stockfish analysis of a fixture PGN in the format core/src/test/resources/pacing/*.analysis.json.

    python scripts/record_analysis.py fixtures/scholars_mate.pgn core/src/test/resources/pacing/scholars_mate.analysis.json

Stockfish 19 (pc/bin/stockfish), depth 12, MultiPV 3, one entry per ply plus the final position, scores
relative to the side to move (exactly as UCI reports them, and as `EngineLineInput` stores them). Used for the
recordings the pacing and commentary tests replay; nothing at runtime depends on it.
"""
import json, sys, chess, chess.engine, chess.pgn

SF = r"C:\Claude\ChessAnalyzer\pc\bin\stockfish\stockfish-windows-x86-64-universal.exe"
DEPTH, MULTIPV = 12, 3

def main(pgn_path, out_path):
    with open(pgn_path) as fh:
        game = chess.pgn.read_game(fh)
    engine = chess.engine.SimpleEngine.popen_uci(SF)
    engine.configure({"Threads": 4, "Hash": 256})
    board = game.board()
    positions = [board.copy()]
    for mv in game.mainline_moves():
        board.push(mv)
        positions.append(board.copy())
    evals = []
    for ply, pos in enumerate(positions):
        if pos.is_checkmate():
            lines = [{"multiPv": 1, "mateIn": 0, "depth": DEPTH, "pvUci": []}]
        else:
            infos = engine.analyse(pos, chess.engine.Limit(depth=DEPTH), multipv=MULTIPV)
            lines = []
            for i, info in enumerate(infos, start=1):
                sc = info["score"].relative
                line = {"multiPv": i}
                if sc.is_mate():
                    line["mateIn"] = sc.mate()
                else:
                    line["scoreCp"] = sc.score()
                line["depth"] = info.get("depth", DEPTH)
                line["pvUci"] = [m.uci() for m in info.get("pv", [])][:16]
                lines.append(line)
        evals.append({"ply": ply, "fen": pos.fen(), "depth": DEPTH, "capped": False, "lines": lines})
    engine.quit()
    out = {
        "schema": "palaya.analysis/1",
        "tags": dict(game.headers),
        "engine": {"name": "Stockfish 19", "threads": 4, "multiPv": MULTIPV, "depth": DEPTH},
        "evals": evals,
        "checkpoint": {"completedPlies": len(evals), "complete": True},
    }
    with open(out_path, "w") as fh:
        json.dump(out, fh, indent=1)

if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
