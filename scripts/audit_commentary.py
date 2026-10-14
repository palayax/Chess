#!/usr/bin/env python
"""Claim-by-claim audit of the generated card texts and walkthroughs (docs/COMMENTARY_AUDIT.md).

    python scripts/audit_commentary.py after  core/build/commentary_audit/after.jsonl
    python scripts/audit_commentary.py before docs/audit/commentary_before_r1b.txt
    python scripts/audit_commentary.py lines  core/build/commentary_audit/best_lines.jsonl
    python scripts/audit_commentary.py story  core/build/commentary_audit/story.jsonl            (NS)
    python scripts/audit_commentary.py mutate-story core/build/commentary_audit/story.jsonl      (NS)
    python scripts/audit_commentary.py mutate core/build/commentary_audit/after.jsonl
    python scripts/audit_commentary.py rephrase core/build/rephrase/mutations.jsonl          (C2)
    python scripts/audit_commentary.py rephrase core/build/rephrase/measure_<model>.jsonl    (C2)
    python scripts/audit_commentary.py mutate-rephrase core/build/rephrase/corpus.jsonl      (C2)

For every sentence of every annotation text, and every intro / step / payoff of every walkthrough, the
claim it makes is re-derived from the recorded position and the recorded engine data with python-chess
(an engine independent of ``:core``'s own move generator and detectors) and given a verdict:

    S  supported         the position or the engine's own numbers prove it
    F  harmless flavour  true, and asserts nothing beyond the classification it follows
    W  WRONG             false, attributed to the wrong move or side, or not provable from the data

``after`` reads the JSON lines written by core's test ``CommentaryAuditDumpTest`` (the Immortal Game, the
Opera Game and game01, for no side, White and Black). Every template and every term of
docs/COMMENTARY_STYLE.md (C1) has a verifier here: a "fork" must show the moved piece attacking every
named target, a "pin" its slider, front and rear piece on one line (an "absolute pin" with the king at the
rear), "wins the exchange" a minor piece taking a rook for a net of rook-minus-minor, a "zwischenzug" a
forcing move whose postponed capture the engine's line still makes, an "overloaded" defender the sole
guard of two attacked pieces, a "desperado" a piece that was lost where it stood, a back-rank "threat" a
heavy-piece mate if the opponent could pass, "forced mate in N" the engine's own distance, an "only move"
the MultiPV gap, and the evaluation words the win-percent bands.

``mutate`` is the negative control: it breaks one template or term at a time in the recorded texts (a
wrong fork square, "a rook" for the exchange, an absolute pin that is relative, "en prise" for a
defended piece, mate in N+1, a shifted evaluation band, a zwischenzug on a quiet move, ...) and shows
that every mutation is flagged WRONG. It exits non-zero when one is not.

``lines`` (V2, ANALYSIS_SPEC 6.2) audits every engine line the Board's "Show the best line" can display and
every line the narrated video plays, for the two recorded games plus game01 (written by core's test
``CommentaryAuditDumpTest.dumpBestLines``): each move is replayed and must be legal, its SAN must be
python-chess's, the line must be a prefix of the recorded engine PV cut by the rule (depth / 2, at most 8,
and nothing after a checkmate), alternatives must be within 2 win-percent and not the move played, and each
sentence of the caption is re-derived (the engine's score as recorded, checkmate from the board, a material
gain settled with python-chess's own exchange evaluation and named by the 40 cp rule, "the exchange" by
the count of pieces). Since V4 the video's line is spoken: each move's sentence ("Knight takes the pawn on f
seven, with check.") is re-derived from python-chess's board and must be exactly that, over the position the
line has reached, and "Back to the game now." must follow a simulated line (a best line, or a detour's payoff,
from ``video_returns.jsonl`` next to the dump) and stand on the game's own position. ``mutate`` also breaks
the spoken line (a wrong square or piece, a capture said as a quiet move, a check left out, the return over the
wrong board) and every break must be flagged.

``story`` (NS, ANALYSIS_SPEC 9.9) audits the sentences the narrated video says about the STORY of the game, for the Immortal
Game, the Opera Game, game01 and Byrne-Fischer, each for no side, White and Black (written by core's test
``CommentaryAuditDumpTest.dumpStory``): the cold open's stakes ("Black won, but for 16 moves this was White's game"), how it
ended, the hook, where the opening left the game, every phase boundary and its cause, the skipped stretches, the remarks on
a quiet move, the error beats (what the move let in, the better move and what it did), the mating sequence (announced
once, then bare), the consequence in bands, the turning point and what followed it, the outro and the one takeaway. Every
sentence must match the template of its kind (an unrecognised sentence is WRONG) and every claim in it is re-derived from
the recorded engine numbers and python-chess: the phases from the White win-percent series (a second implementation of
core's GamePhases and StoryArc), the standing words from the win-percent bands, a mate from the engine's own distance, the
better move's result from the first plies of the engine's line, what a move lets in from the engine's best reply and static
exchange, the skipped stretch from the board. ``mutate-story`` (also part of ``mutate``) breaks one claim of one sentence at
a time and every break must be flagged.

The recordings (core/src/test/resources/pacing/*.analysis.json) are the engine data. ``before`` reads the
text dump of the generator as it was before R1b, kept in docs/audit/ so the "before" table can be
reproduced.
"""
import json
import os
import math
import re
import sys
from collections import Counter, defaultdict

import chess

ROOT = __file__.replace("\\", "/").rsplit("/scripts/", 1)[0]
VALUE = {chess.PAWN: 100, chess.KNIGHT: 320, chess.BISHOP: 330, chess.ROOK: 500, chess.QUEEN: 900, chess.KING: 20000}
NAME = {chess.PAWN: "pawn", chess.KNIGHT: "knight", chess.BISHOP: "bishop", chess.ROOK: "rook", chess.QUEEN: "queen", chess.KING: "king"}
TYPE = {v: k for k, v in NAME.items()}
GAME_FILES = {
    "immortal": ROOT + "/core/src/test/resources/pacing/immortal.analysis.json",
    "chesscom": ROOT + "/core/src/test/resources/pacing/chesscom_style_game.analysis.json",
    "game01": ROOT + "/core/src/test/resources/pacing/game01.analysis.json",
}
GAIN_WORDS = {"a queen": 900, "a rook": 500, "a piece": 325, "a pawn": 100}
EXCHANGE_MIN, EXCHANGE_MAX = 500 - 330 - 40, 500 - 320 + 40
_analysis = {}


def analysis(game):
    if game not in _analysis:
        _analysis[game] = json.load(open(GAME_FILES[game], encoding="utf-8"))["evals"]
    return _analysis[game]


# ---------------------------------------------------------------------------------------------
# Chess helpers (python-chess; independent of :core)
# ---------------------------------------------------------------------------------------------

def see(board, move):
    """Net centipawns for the side making [move], by swapping off on the destination square."""
    if board.is_en_passant(move):
        captured = VALUE[chess.PAWN]
    else:
        captured = VALUE[board.piece_at(move.to_square).piece_type] if board.piece_at(move.to_square) else 0
    promo = (VALUE[move.promotion] - VALUE[chess.PAWN]) if move.promotion else 0
    b = board.copy()
    b.push(move)
    return captured + promo - _swap(b, move.to_square, 0)


def _swap(board, square, depth):
    if depth > 30:
        return 0
    occ = board.piece_at(square)
    if occ is None or occ.color == board.turn:
        return 0
    caps = [m for m in board.legal_moves if m.to_square == square and board.is_capture(m) and not board.is_en_passant(m)]
    if not caps:
        return 0
    m = min(caps, key=lambda x: VALUE[board.piece_at(x.from_square).piece_type])
    gain = VALUE[occ.piece_type] + ((VALUE[m.promotion] - 100) if m.promotion else 0)
    b = board.copy()
    b.push(m)
    return max(0, gain - _swap(b, square, depth + 1))


def attackers(board, color, square):
    return sorted(board.attackers(color, square), key=lambda s: VALUE[board.piece_at(s).piece_type])


def win_percent(cp):
    cp = max(-1000, min(1000, cp))
    return 50 + 50 * (2 / (1 + math.exp(-0.00368208 * cp)) - 1)


def line_wp(line):
    if line.get("mateIn") is not None:
        return 100.0 if line["mateIn"] > 0 else 0.0
    return win_percent(line["scoreCp"])


def great_gap(game, ply):
    lines = analysis(game)[ply - 1]["lines"]
    if len(lines) < 2:
        return None
    return line_wp(lines[0]) - line_wp(lines[1])


def mover_mate_after(rec):
    """Mate distance after the move, mover-relative (negative = the opponent mates)."""
    m = rec["mateA"]
    if m is None:
        return None
    return m if rec["color"] == "WHITE" else -m


def mover_mate_before(rec):
    m = rec["mateB"]
    if m is None:
        return None
    return m if rec["color"] == "WHITE" else -m


def pv_after(game, ply):
    """The engine's line after the played move (the opponent to move first)."""
    ev = analysis(game)[ply]
    return ev["lines"][0]["pvUci"] if ev["lines"] else []


def pv_starting_with(game, ply, uci):
    for line in analysis(game)[ply - 1]["lines"]:
        if line["pvUci"] and line["pvUci"][0] == uci:
            return line["pvUci"]
    return []


def is_pin(board, us, front):
    """A slider of [us] attacks the piece on [front] and an enemy piece worth more (or the king) stands behind it."""
    fp = board.piece_at(front)
    if fp is None or fp.color == us:
        return False
    for sl in board.attackers(us, front):
        pc = board.piece_at(sl)
        if pc.piece_type not in (chess.BISHOP, chess.ROOK, chess.QUEEN):
            continue
        df = (chess.square_file(front) > chess.square_file(sl)) - (chess.square_file(front) < chess.square_file(sl))
        dr = (chess.square_rank(front) > chess.square_rank(sl)) - (chess.square_rank(front) < chess.square_rank(sl))
        f, r = chess.square_file(front) + df, chess.square_rank(front) + dr
        while 0 <= f < 8 and 0 <= r < 8:
            rp = board.piece_at(chess.square(f, r))
            if rp is not None:
                if rp.color != us and (rp.piece_type == chess.KING or VALUE[rp.piece_type] > VALUE[fp.piece_type]):
                    return True
                break
            f += df
            r += dr
    return False


def on_line_beyond(board, slider, front, rear):
    """[rear] lies beyond [front] on the ray from [slider] through [front], with nothing between front and rear."""
    df = (chess.square_file(front) > chess.square_file(slider)) - (chess.square_file(front) < chess.square_file(slider))
    dr = (chess.square_rank(front) > chess.square_rank(slider)) - (chess.square_rank(front) < chess.square_rank(slider))
    f, r = chess.square_file(front) + df, chess.square_rank(front) + dr
    while 0 <= f < 8 and 0 <= r < 8:
        sq = chess.square(f, r)
        if sq == rear:
            return True
        if board.piece_at(sq) is not None:
            return False
        f += df
        r += dr
    return False


def material(board, color):
    return sum(VALUE[p.piece_type] for p in board.piece_map().values() if p.color == color and p.piece_type != chess.KING)


def pass_turn(board):
    """The same position with the other side to move (a null move), or None while in check."""
    if board.is_check():
        return None
    b = board.copy()
    b.push(chess.Move.null())
    return b


def best_capture_on(board, square):
    """The most the side to move nets by capturing on [square], or None without a legal capture there."""
    nets = [see(board, m) for m in board.legal_moves if board.is_capture(m) and m.to_square == square]
    return max(nets) if nets else None


def count(board, color, piece_type):
    return len(board.pieces(piece_type, color))


def wins_the_exchange(start, end, winner):
    """Winner gave exactly one minor and took exactly one rook; queens, pawns, the rest unchanged."""
    loser = not winner

    def minors(b, c):
        return count(b, c, chess.KNIGHT) + count(b, c, chess.BISHOP)
    return (minors(end, winner) - minors(start, winner) == -1 and minors(end, loser) - minors(start, loser) == 0
            and count(end, loser, chess.ROOK) - count(start, loser, chess.ROOK) == -1
            and count(end, winner, chess.ROOK) == count(start, winner, chess.ROOK)
            and count(end, winner, chess.QUEEN) == count(start, winner, chess.QUEEN)
            and count(end, loser, chess.QUEEN) == count(start, loser, chess.QUEEN)
            and count(end, winner, chess.PAWN) == count(start, winner, chess.PAWN)
            and count(end, loser, chess.PAWN) == count(start, loser, chess.PAWN))


def is_exchange_capture(board, move):
    mover = board.piece_at(move.from_square)
    victim = board.piece_at(move.to_square)
    return (not board.is_en_passant(move) and move.promotion is None and mover is not None and victim is not None
            and victim.piece_type == chess.ROOK and mover.piece_type in (chess.KNIGHT, chess.BISHOP))


def standing_words(wp):
    """The evaluation bands of ANALYSIS_SPEC 9 (NarrationVocabulary.standing), in the card's words."""
    if wp >= 95:
        return "decisively winning"
    if wp >= 82:
        return "winning"
    if wp >= 68:
        return "clearly better"
    if wp >= 57:
        return "slightly better"
    if wp >= 43:
        return "about level"
    if wp >= 32:
        return "slightly worse"
    if wp >= 18:
        return "clearly worse"
    if wp >= 5:
        return "losing"
    return "decisively lost"


# ---------------------------------------------------------------------------------------------
# Phrase verification (the AFTER texts)
# ---------------------------------------------------------------------------------------------

PIECE_ON = r"the (\w+) on ([a-h][1-8])"


def named_piece(board, piece, sq, color):
    """True when a [color] [piece] stands on [sq]."""
    s = chess.parse_square(sq)
    pc = board.piece_at(s)
    return pc is not None and pc.color == color and NAME[pc.piece_type] == piece


def verify_phrase(phrase, before, move, after, rec, context):
    """Return (verdict, note) for a verb phrase said about [move] from [before] to [after]."""
    us = before.turn
    them = not us
    p = phrase.strip().rstrip(".")

    if p in ("is checkmate", "is mate", "delivers checkmate"):
        return ("S", "checkmate on the board") if after.is_checkmate() else ("W", "not checkmate")

    m = re.match(r"(?:starts|begins) a forced mate(?: in (\d+))?$|sets a forced mate in (\d+) in motion$", p)
    if m:
        n = int(m.group(1) or m.group(2)) if (m.group(1) or m.group(2)) else None
        if context == "found":
            mm = mover_mate_after(rec)
            ok = mm is not None and mm > 0 and (n is None or mover_mate_before(rec) == n)
            return ("S", "engine: mate for the mover (in %s before the move)" % mover_mate_before(rec)) if ok else ("W", "no engine mate for the mover, or the wrong distance")
        if context == "allowed":
            mm = mover_mate_after(rec) or 0
            ok = mm < 0 and (n is None or -mm == n)
            return ("S", "engine: mate in %d for the opponent after the move" % -mm) if ok else ("W", "no engine mate against the mover, or the wrong distance")
        mm = mover_mate_before(rec)
        ok = mm is not None and mm > 0 and (n is None or mm == n)
        return ("S", "engine: mate in %s for the mover before the move" % mm) if ok else ("W", "no engine mate, or the wrong distance")

    if p in ("is a back-rank mate", "is mate on the back rank"):
        k = after.king(them)
        return ("S", "mate, king on the back rank") if after.is_checkmate() and chess.square_rank(k) in (0, 7) else ("W", "not a back-rank mate")
    if p in ("is a smothered mate", "is a smothered mate: the king is boxed in by its own pieces"):
        if not after.is_checkmate():
            return ("W", "not mate")
        ks = after.king(them)
        checkers = list(after.checkers())
        ok = len(checkers) == 1 and after.piece_at(checkers[0]).piece_type == chess.KNIGHT and all(
            after.piece_at(n) is not None and after.piece_at(n).color == them for n in after.attacks(ks))
        return ("S", "a lone knight mates a king boxed in by its own pieces") if ok else ("W", "not a smothered mate")
    if p in ("gives double check", "is a double check: only a king move can answer it"):
        return ("S", "two checkers") if len(after.checkers()) >= 2 else ("W", "not a double check")

    m = re.match(r"(?:forks (.+?)(?: with a pawn)?|is a (?:pawn )?fork, hitting (.+?)(?: at once)?|lands a fork on (.+))$", p)
    if m:
        pawn = "with a pawn" in p or "pawn fork" in p
        targets = re.findall(PIECE_ON, m.group(1) or m.group(2) or m.group(3))
        ok = len(targets) >= 2
        for piece, sq in targets:
            s = chess.parse_square(sq)
            ok = ok and named_piece(after, piece, sq, them) and move.to_square in after.attackers(us, s)
        if pawn:
            ok = ok and after.piece_at(move.to_square).piece_type == chess.PAWN
        return ("S", "the moved piece attacks every named piece%s" % (", and is a pawn" if pawn else "")) if ok else ("W", "fork not borne out")

    m = re.match(r"(?:attacks (.+) at once|creates a double attack on (.+))$", p)
    if m:
        targets = re.findall(PIECE_ON, m.group(1) or m.group(2))
        ok = len(targets) >= 2 and all(named_piece(after, piece, sq, them) and after.attackers(us, chess.parse_square(sq)) for piece, sq in targets)
        return ("S", "each target attacked") if ok else ("W", "double attack not borne out")

    m = re.match(r"(?:pins the (\w+) on ([a-h][1-8]) to the (\w+) on ([a-h][1-8])"
                 r"|puts the (\w+) on ([a-h][1-8]) in an absolute pin against the (king) on ([a-h][1-8])"
                 r"|ties the (\w+) on ([a-h][1-8]) to the (\w+) on ([a-h][1-8]) with a relative pin)$", p)
    if m:
        g = [x for x in m.groups() if x is not None]
        fpiece, fsq, rpiece, rsq = g
        front = chess.parse_square(fsq)
        rear = chess.parse_square(rsq)
        ok = named_piece(after, fpiece, fsq, them) and named_piece(after, rpiece, rsq, them)
        ok = ok and any(after.piece_at(s).piece_type in (chess.BISHOP, chess.ROOK, chess.QUEEN) and on_line_beyond(after, s, front, rear)
                        for s in after.attackers(us, front))
        if "absolute pin" in p:
            ok = ok and rpiece == "king"
        if "relative pin" in p:
            ok = ok and rpiece != "king" and VALUE[TYPE[rpiece]] > VALUE[TYPE[fpiece]]
        return ("S", "slider, front and rear on one line") if ok else ("W", "pin not borne out")

    m = re.match(r"(?:skewers the (\w+) on ([a-h][1-8]), with the (\w+) on ([a-h][1-8]) behind it"
                 r"|is a skewer: it attacks the (\w+) on ([a-h][1-8]), and the (\w+) on ([a-h][1-8]) stands behind it on the same line)$", p)
    if m:
        fpiece, fsq, rpiece, rsq = [x for x in m.groups() if x is not None]
        front = chess.parse_square(fsq)
        rear = chess.parse_square(rsq)
        ok = named_piece(after, fpiece, fsq, them) and named_piece(after, rpiece, rsq, them)
        ok = ok and any(after.piece_at(s).piece_type in (chess.BISHOP, chess.ROOK, chess.QUEEN) and on_line_beyond(after, s, front, rear)
                        for s in after.attackers(us, front))
        return ("S", "line verified") if ok else ("W", "skewer not borne out")

    m = re.match(r"(?:uncovers the (\w+) on ([a-h][1-8]), which now attacks the (\w+) on ([a-h][1-8])"
                 r"|is a discovered attack: the (\w+) on ([a-h][1-8]) is unmasked against the (\w+) on ([a-h][1-8]))$", p)
    if m:
        apiece, asq, tpiece, tsq = [x for x in m.groups() if x is not None]
        a = chess.parse_square(asq)
        t = chess.parse_square(tsq)
        ok = named_piece(after, apiece, asq, us) and named_piece(after, tpiece, tsq, them)
        ok = ok and a in after.attackers(us, t) and a not in before.attackers(us, t) and a != move.to_square
        return ("S", "new attack through the vacated square") if ok else ("W", "discovery not borne out")

    m = re.match(r"(?:gives check by uncovering the (\w+) on ([a-h][1-8])|is a discovered check from the (\w+) on ([a-h][1-8]))$", p)
    if m:
        apiece, asq = [x for x in m.groups() if x is not None]
        a = chess.parse_square(asq)
        ok = named_piece(after, apiece, asq, us) and a in after.checkers() and a != move.to_square
        return ("S", "discovered check") if ok else ("W", "not a discovered check")

    m = re.match(r"(?:attacks the undefended (\w+) on ([a-h][1-8])|hits the loose (\w+) on ([a-h][1-8])|attacks the (\w+) on ([a-h][1-8]), which is en prise)$", p)
    if m:
        piece, sq = [x for x in m.groups() if x is not None]
        s = chess.parse_square(sq)
        ok = named_piece(after, piece, sq, them) and move.to_square in after.attackers(us, s) and not after.attackers(them, s)
        return ("S", "attacked by the moved piece, nothing defends it") if ok else ("W", "undefended attack not borne out")

    m = re.match(r"(?:leaves the (\w+) on ([a-h][1-8]) undefended, with the (\w+) on ([a-h][1-8]) attacking it"
                 r"|leaves the (\w+) on ([a-h][1-8]) en prise to the (\w+) on ([a-h][1-8]))$", p)
    if m:
        piece, sq, apiece, asq = [x for x in m.groups() if x is not None]
        s = chess.parse_square(sq)
        a = chess.parse_square(asq)
        ok = named_piece(after, piece, sq, them) and named_piece(after, apiece, asq, us) and a in after.attackers(us, s) and not after.attackers(them, s)
        return ("S", "attacked by the named piece, nothing defends it") if ok else ("W", "not borne out")

    m = re.match(r"(?:attacks|hits) the (\w+) on ([a-h][1-8]) with an? (\w+)$", p)
    if m:
        s = chess.parse_square(m.group(2))
        pc = after.piece_at(s)
        att = attackers(after, us, s)
        ok = named_piece(after, m.group(1), m.group(2), them) and bool(att) \
            and NAME[after.piece_at(att[0]).piece_type] == m.group(3) and VALUE[after.piece_at(att[0]).piece_type] < VALUE[pc.piece_type]
        return ("S", "attacked by a cheaper piece") if ok else ("W", "not borne out")

    m = re.match(r"(?:attacks the (\w+) on ([a-h][1-8]) more often than it is defended|piles up on the (\w+) on ([a-h][1-8]): more attackers than defenders)$", p)
    if m:
        piece, sq = [x for x in m.groups() if x is not None]
        s = chess.parse_square(sq)
        ok = named_piece(after, piece, sq, them) and len(after.attackers(us, s)) > len(after.attackers(them, s))
        return ("S", "more attackers than defenders") if ok else ("W", "not borne out")

    m = re.match(r"leaves the (\w+) on ([a-h][1-8]) attacked by an? (\w+)$", p)
    if m:
        s = chess.parse_square(m.group(2))
        att = attackers(after, us, s)
        ok = named_piece(after, m.group(1), m.group(2), them) and bool(att) and NAME[after.piece_at(att[0]).piece_type] == m.group(3)
        return ("S", "attacked by the named piece") if ok else ("W", "not borne out")

    m = re.match(r"leaves the (\w+) on ([a-h][1-8]) attacked more often than it is defended$", p)
    if m:
        s = chess.parse_square(m.group(2))
        ok = named_piece(after, m.group(1), m.group(2), them) and len(after.attackers(us, s)) > len(after.attackers(them, s))
        return ("S", "more attackers than defenders") if ok else ("W", "not borne out")

    m = re.match(r"(?:leaves the (\w+) on ([a-h][1-8]) with no safe square|traps the (\w+) on ([a-h][1-8]): every square it can reach loses material)$", p)
    if m:
        piece, sq = [x for x in m.groups() if x is not None]
        s = chess.parse_square(sq)
        if not (named_piece(after, piece, sq, them) and not after.is_check()):
            return ("W", "no such piece, or in check")
        moves = [mv for mv in after.legal_moves if mv.from_square == s]
        ok = bool(moves) and all(see(after, mv) < 0 for mv in moves)
        return ("S", "every legal move of the piece loses material by exchange") if ok else ("W", "the piece has a safe move")

    m = re.match(r"(?:wins|picks up) (a queen|a rook|a piece|a pawn|the exchange)$", p)
    if m:
        cp = see(before, move)
        if m.group(1) == "the exchange":
            ok = is_exchange_capture(before, move) and EXCHANGE_MIN <= cp <= EXCHANGE_MAX
            return ("S", "a minor piece takes a rook, net %d by exchange" % cp) if ok else ("W", "not a minor piece taking a rook for rook-minus-minor (nets %d)" % cp)
        ok = before.is_capture(move) and abs(cp - GAIN_WORDS[m.group(1)]) <= 40
        return ("S", "capture nets %d by exchange" % cp) if ok else ("W", "capture nets %d" % cp)

    m = re.match(r"promotes (?:the pawn )?to (a|an) (\w+)$", p)
    if m:
        return ("S", "promotion") if move.promotion and NAME[move.promotion] == m.group(2) else ("W", "no such promotion")
    m = re.match(r"(?:underpromotes to|is an underpromotion, to) (a|an) (\w+)$", p)
    if m:
        ok = move.promotion and move.promotion != chess.QUEEN and NAME[move.promotion] == m.group(2)
        return ("S", "underpromotion") if ok else ("W", "no such underpromotion")

    m = re.match(r"is a desperado: the (\w+) was lost anyway, so it takes the (\w+) on ([a-h][1-8]) on the way out$", p)
    if m:
        mover = before.piece_at(move.from_square)
        victim = before.piece_at(move.to_square)
        ok = (mover is not None and NAME[mover.piece_type] == m.group(1) and VALUE[mover.piece_type] >= 300
              and victim is not None and NAME[victim.piece_type] == m.group(2) and chess.parse_square(m.group(3)) == move.to_square
              and before.is_capture(move) and not before.is_en_passant(move) and see(before, move) < 0)
        passed = pass_turn(before)
        ok = ok and passed is not None and (best_capture_on(passed, move.from_square) or -1) >= 0
        ok = ok and (best_capture_on(after, move.to_square) or -1) >= 0
        return ("S", "lost where it stood, loses material by exchange, still lost where it landed") if ok else ("W", "not a desperado")

    m = re.match(r"(?:threatens (\S+), mate on the back rank|sets up a back-rank mate: (\S+) is the threat)$", p)
    if m:
        san = m.group(1) or m.group(2)
        if after.is_check():
            return ("W", "the opponent is in check: no threat can be tested")
        k = after.king(them)
        back = 0 if them == chess.WHITE else 7
        fwd = 1 if them == chess.WHITE else -1
        escapes = [chess.square(chess.square_file(k) + df, chess.square_rank(k) + fwd)
                   for df in (-1, 0, 1) if 0 <= chess.square_file(k) + df < 8 and 0 <= chess.square_rank(k) + fwd < 8]
        boxed = (chess.square_rank(k) == back and escapes
                 and all(after.piece_at(e) is not None and after.piece_at(e).color == them for e in escapes)
                 and any(after.piece_at(e).piece_type == chess.PAWN for e in escapes))
        passed = pass_turn(after)
        try:
            threat = passed.parse_san(san) if passed is not None else None
        except Exception:
            threat = None
        ok = boxed and threat is not None and chess.square_rank(threat.to_square) == back \
            and passed.piece_at(threat.from_square).piece_type in (chess.ROOK, chess.QUEEN)
        if ok:
            b = passed.copy()
            b.push(threat)
            ok = b.is_checkmate()
        return ("S", "king boxed in by its own pawns; the named heavy-piece move mates after a pass") if ok else ("W", "back-rank threat not borne out")

    return ("W", "unrecognised claim [%s]" % p)


def replay(position, pv, limit=8):
    """The principal variation as SAN, or None when it is not legal."""
    b = position.copy()
    sans = []
    for u in pv[:limit]:
        mv = chess.Move.from_uci(u)
        if mv not in b.legal_moves:
            return None
        sans.append(b.san(mv))
        b.push(mv)
    return sans


def verify_engine_line(rest, position, move, rec, context, game, ply):
    """Engine-line motifs: the claim is made about the engine's own principal variation."""
    uci = move.uci()
    if context == "allowed":
        pv = pv_after(game, ply)  # the opponent's reply heads the line after the played move
    else:
        pv = pv_starting_with(game, ply, uci)
    if not pv or pv[0] != uci:
        return ("W", "the move is not the start of any recorded engine line")
    sans = replay(position, pv)
    if sans is None:
        return ("W", "the recorded line is not legal")
    for f in re.findall(r"([KQRBN]?[a-h]?[1-8]?x?[a-h][1-8](?:=[QRBN])?[+#]?|O-O(?:-O)?)(?= (?:can|follows))", rest):
        if f not in sans[1:5]:
            return ("W", "the follow-up %s is not in the engine's line %s" % (f, sans[:5]))
    for piece, sq in re.findall(PIECE_ON, rest):
        b2 = position.copy()
        found = False
        for u in [None] + pv[:2]:
            if u is not None:
                b2.push(chess.Move.from_uci(u))
            pc = b2.piece_at(chess.parse_square(sq))
            if pc is not None and NAME[pc.piece_type] == piece:
                found = True
        if not found:
            return ("W", "no %s on %s" % (piece, sq))
    m = re.match(r"deflects the (\w+) on ([a-h][1-8]) away from guarding ([a-h][1-8])$", rest)
    if m and len(pv) >= 3:
        defender = chess.parse_square(m.group(2))
        target = chess.parse_square(m.group(3))
        b3 = position.copy()
        b3.push(chess.Move.from_uci(pv[0]))
        reply = chess.Move.from_uci(pv[1])
        if reply.from_square != defender:
            return ("W", "the engine's reply is not made by that piece")
        if reply.to_square == target:
            return ("W", "the defender lands on the square it was supposed to be deflected from")
        guarded = target in b3.attacks(defender)
        b3.push(reply)
        still = target in b3.attacks(reply.to_square)
        return ("S", "engine line: the defender leaves and no longer guards %s" % m.group(3)) if guarded and not still else ("W", "the defender still guards it")
    m = re.match(r"clears ([a-h][1-8]) so that (\S+) can come through$", rest)
    if m and len(pv) >= 3:
        vacated = chess.parse_square(m.group(1))
        b4 = position.copy()
        b4.push(chess.Move.from_uci(pv[0]))
        b4.push(chess.Move.from_uci(pv[1]))
        follow_mv = chess.Move.from_uci(pv[2])
        if follow_mv.from_square == move.to_square:
            return ("W", "the same piece comes back, nothing is cleared for anyone")
        if b4.san(follow_mv) != m.group(2):
            return ("W", "the line's follow-up is %s, not %s" % (b4.san(follow_mv), m.group(2)))
        uses = vacated in chess.SquareSet(chess.between(follow_mv.from_square, follow_mv.to_square)) or follow_mv.to_square == vacated
        return ("S", "engine line: another piece uses the vacated line") if uses else ("W", "the follow-up does not use the vacated square")
    m = re.match(r"is a zwischenzug: it comes first, and (\S+) follows$", rest)
    if m:
        # C1: the move is forcing (check, or takes something worth more than the mover), a capture that
        # does not lose material was available on some enemy piece worth a minor or more, and the
        # engine's line makes that capture on its 2nd or 3rd own move (plies 2 and 4).
        b = position.copy()
        mover = b.piece_at(move.from_square)
        victim = b.piece_at(move.to_square)
        b.push(move)
        forcing = b.is_check() or (victim is not None and VALUE[victim.piece_type] > VALUE[mover.piece_type])
        if not forcing:
            return ("W", "the move is neither check nor a capture of something bigger: not a zwischenzug")
        later = None
        b5 = position.copy()
        for i, u in enumerate(pv[:5]):
            mv = chess.Move.from_uci(u)
            if i in (2, 4) and b5.is_capture(mv) and b5.san(mv) == m.group(1):
                later = (b5.copy(), mv)
                break
            b5.push(mv)
        if later is None:
            return ("W", "the engine's line does not make the postponed capture %s" % m.group(1))
        pending = later[1].to_square
        pc = position.piece_at(pending)
        caps = [c for c in position.legal_moves if c.to_square == pending and position.is_capture(c)]
        cheapest = min(caps, key=lambda c: VALUE[position.piece_at(c.from_square).piece_type]) if caps else None
        ok = pc is not None and pc.color != position.turn and pc.piece_type != chess.KING and VALUE[pc.piece_type] >= 300 \
            and cheapest is not None and see(position, cheapest) >= 0
        return ("S", "forcing move first; the capture on %s was available and the line makes it" % chess.square_name(pending)) if ok \
            else ("W", "no profitable capture was waiting on %s" % chess.square_name(pending))
    m = re.match(r"exploits the overloaded (\w+) on ([a-h][1-8]), which cannot guard ([a-h][1-8]) and ([a-h][1-8]) at once$", rest)
    if m:
        # C1: on the position BEFORE the move, the named piece is the opponent's and the sole defender of
        # both squares, each holding an opponent's piece the mover attacks; the engine's line (our 2nd
        # move, ply 2) lands on one of them.
        us = position.turn
        them = not us
        ov = chess.parse_square(m.group(2))
        if not named_piece(position, m.group(1), m.group(2), them):
            return ("W", "no such defender")
        for sq in (m.group(3), m.group(4)):
            s = chess.parse_square(sq)
            pc = position.piece_at(s)
            if pc is None or pc.color != them or pc.piece_type == chess.KING:
                return ("W", "no opponent's piece on %s" % sq)
            if not position.attackers(us, s):
                return ("W", "%s is not attacked" % sq)
            if list(position.attackers(them, s)) != [ov]:
                return ("W", "%s has other defenders" % sq)
        if len(pv) < 3 or chess.Move.from_uci(pv[2]).to_square not in (chess.parse_square(m.group(3)), chess.parse_square(m.group(4))):
            return ("W", "the engine's follow-up does not land on either square")
        return ("S", "sole defender of two attacked pieces; the line cashes one")
    return ("S", "engine line replays legally; text consistent with it")


SENT_SPLIT = re.compile(r"(?<=\.)\s+")

WHO = r"(you|your opponent|White|Black)"
SAN = r"(\S+)"

LEADS = [
    # (regex, kind): the first sentence of a class, with the proof each needs.
    (r"^(\S+) follows known opening theory\.$|^(\S+) is still opening theory\.$|^(\S+) stays in book\.$", "book"),
    (r"^(\S+) was the only legal move\.$|^(\S+) was forced: the only legal move\.$|^No choice here: (\S+) was the only legal move\.$", "forced"),
    (r"^(\S+) matches the engine's top choice\.$|^(\S+) is the engine's first choice\.$|^(\S+) is the top engine move here\.$", "best"),
    (r"^(\S+) is very close to the best move\.$|^(\S+) is nearly the engine's top choice\.$|^(\S+) comes within a whisker of the best move\.$", "excellent"),
    (r"^(\S+) is a sound move\.$|^(\S+) is a reasonable move\.$|^(\S+) is a solid choice\.$", "good"),
    (r"^(\S+) was the only move that kept things on track\.$|^(\S+) is the only move here: the next-best option gives up real ground\.$"
     r"|^(\S+) is an only move, and nothing else keeps the position on track\.$", "great"),
    (r"^(\S+) gives back ground\.$|^(\S+) is not the most precise\.$|^(\S+) concedes a little ground\.$", "wrong-inaccuracy"),
    (r"^(\S+) gives up real ground\.$|^(\S+) goes wrong\.$|^(\S+) lets the position slip\.$", "wrong-mistake"),
    (r"^(\S+) gives up a big chunk of the position\.$|^(\S+) is a serious slip\.$|^(\S+) throws a big chunk of the position away\.$", "wrong-blunder"),
    (r"^(\S+) is among the engine's best moves here\.$", "among-best"),
]


def audit_text_after(rec):
    """Verdicts for every sentence of [rec]'s text. Returns a list of (sentence, verdict, note)."""
    board = chess.Board(rec["fenBefore"])
    move = chess.Move.from_uci(rec["uci"])
    after = board.copy()
    after.push(move)
    cls, loss, san, game, ply = rec["cls"], rec["loss"], rec["san"], rec["game"], rec["ply"]
    bare_san = board.san(move)
    out = []
    for sent in SENT_SPLIT.split(rec["text"]):
        s = sent.strip()
        v = None
        for regex, kind in LEADS:
            m = re.match(regex, s)
            if not m:
                continue
            named = next(x for x in m.groups() if x is not None)
            if named.rstrip("+#") != san.rstrip("+#"):
                v = ("W", "names a move that was not played")
            elif kind == "book":
                v = ("S", "BOOK classification") if cls == "BOOK" else ("W", "not a book move")
            elif kind == "forced":
                v = ("S", "one legal move") if board.legal_moves.count() == 1 else ("W", "several legal moves")
            elif kind == "best":
                v = ("S", "played move equals the engine's top move") if rec["best"] == san else ("W", "not the engine's top move")
            elif kind == "excellent":
                v = ("S", "loss %.1f < 2" % loss) if loss < 2 else ("W", "loss %.1f" % loss)
            elif kind == "good":
                v = ("F", "loss %.1f < 5" % loss) if loss < 5 else ("W", "loss %.1f" % loss)
            elif kind == "great":
                gap = great_gap(game, ply)
                v = ("S", "MultiPV gap %.1f >= 10 win%% (every other move gives up real ground)" % gap) if gap is not None and gap >= 10 and rec["best"] == san else ("W", "gap %s" % gap)
            elif kind == "wrong-inaccuracy":
                v = ("F", "loss %.1f >= 5" % loss) if loss >= 5 else ("W", "loss %.1f is not giving back ground" % loss)
            elif kind == "wrong-mistake":
                v = ("F", "loss %.1f >= 10 (real ground)" % loss) if loss >= 10 else ("W", "loss %.1f is not real ground" % loss)
            elif kind == "wrong-blunder":
                v = ("F", "loss %.1f >= 20 (a big chunk)" % loss) if loss >= 20 else ("W", "loss %.1f is not a big chunk" % loss)
            elif kind == "among-best":
                v = ("S", "loss %.1f <= 2" % loss) if loss <= 2 else ("W", "loss %.1f" % loss)
            break
        if v is not None:
            out.append((s, v[0], v[1]))
            continue

        m = re.match(r"^This allows a forced mate(?: in (\d+))?\.$|^This walks into a forced mate in (\d+)\.$|^After this, %s has a forced mate in (\d+)\.$" % WHO, s)
        if m:
            n = m.group(1) or m.group(2) or m.group(4)
            mm = mover_mate_after(rec) or 0
            ok = mm < 0 and cls in ("INACCURACY", "MISTAKE", "BLUNDER") and (n is None or -mm == int(n))
            if ok and m.group(3) is not None:
                ok = m.group(3) == who_word(rec, rec["color"] != "WHITE")
            v = ("S", "engine: the opponent mates in %d after the move" % -mm) if ok else ("W", "no engine mate against the mover, or the wrong distance")
        m = re.match(r"^(\S+) is a sacrifice: it offers the (\w+) on ([a-h][1-8])\.$|^(\S+) sacrifices the (\w+) on ([a-h][1-8])\.$"
                     r"|^(\S+) offers the (\w+) on ([a-h][1-8]): a sacrifice the engine rates among the best moves here\.$", s)
        if m and v is None:
            msan, piece, sq = [x for x in m.groups() if x is not None]
            target = chess.parse_square(sq)
            nets = [see(after, c) for c in after.legal_moves if after.is_capture(c) and c.to_square == target and not after.is_en_passant(c)]
            best = max(nets) if nets else None
            ok = msan.rstrip("+#") == san.rstrip("+#") and named_piece(after, piece, sq, board.turn) and best is not None and best >= 200
            if "rates among the best" in s:
                ok = ok and loss <= 2
            v = ("S", "the opponent can take it for a net %s" % best) if ok else ("W", "no real sacrifice (best capture nets %s)" % best)
        m = re.match(r"^(\S+) leaves the (\w+) on ([a-h][1-8]) open to capture, and the engine still rates it among the best moves\.$", s)
        if m and v is None:
            target = chess.parse_square(m.group(3))
            legal = any(after.is_capture(c) and c.to_square == target for c in after.legal_moves)
            v = ("S", "a legal capture exists; loss %.1f" % loss) if legal and loss <= 2 else ("W", "no legal capture, or not near-best")
        m = re.match(r"^(?:This lets %s play %s|Now %s can play %s|This hands %s %s)(?:, which (.+)|; in the engine's line it (.+))\.$" % (WHO, SAN, WHO, SAN, WHO, SAN), s)
        if m and v is None:
            who = m.group(1) or m.group(3) or m.group(5)
            reply_san = m.group(2) or m.group(4) or m.group(6)
            engine_allowed = m.group(8) is not None
            phrase = m.group(7) or m.group(8)
            try:
                reply = after.parse_san(reply_san)
            except Exception:
                reply = None
            if cls not in ("INACCURACY", "MISTAKE", "BLUNDER"):
                v = ("W", "a charge against a move rated %s" % cls)
            elif reply is None:
                v = ("W", "the reply %s is not a legal move" % reply_san)
            elif who != who_word(rec, rec["color"] != "WHITE"):
                v = ("W", "the beneficiary is not the opponent")
            else:
                after_reply = after.copy()
                after_reply.push(reply)
                if engine_allowed:
                    v = verify_engine_line(phrase, after, reply, rec, "allowed", game, ply)
                else:
                    v = verify_phrase(phrase, after, reply, after_reply, rec, "allowed")
        m = re.match(r"^That takes %s from (.+) to (.+)\.$|^The position swings from (.+) to (.+) for %s\.$|^From (.+) to (.+) in one move: that is what this cost %s\.$" % (WHO, WHO, WHO), s)
        if m and v is None:
            g = m.groups()
            who = g[0] or g[5] or g[8]
            before_w = g[1] or g[3] or g[6]
            after_w = g[2] or g[4] or g[7]
            ok = (cls in ("INACCURACY", "MISTAKE", "BLUNDER", "MISS") and who == who_word(rec, rec["color"] == "WHITE")
                  and before_w == standing_words(rec["wpBefore"]) and after_w == standing_words(rec["wpAfter"]) and before_w != after_w)
            v = ("S", "bands of the mover's win-percent %.1f -> %.1f" % (rec["wpBefore"], rec["wpAfter"])) if ok else ("W", "evaluation words not borne out by the win-percent bands")
        m = re.match(r"^Better was (\S+), forcing mate in (\d+)\.$|^Better was (\S+), with a forced mate in (\d+)\.$|^Better was (\S+): mate in (\d+) was on the board\.$", s)
        if m and v is None:
            bsan, n = [x for x in m.groups() if x is not None]
            ok = bsan == rec["best"] and cls == "MISS" and (mover_mate_before(rec) or 0) == int(n)
            v = ("S", "engine: mate in %s for the mover" % n) if ok else ("W", "mate claim not in the data")
        m = re.match(r"^Better was (\S+), keeping (a decisive advantage|a winning position)\.$|^Better was (\S+), which holds on to (a decisive advantage|a winning position)\.$"
                     r"|^Better was (\S+): it keeps (a decisive advantage|a winning position)\.$", s)
        if m and v is None:
            bsan, kept = [x for x in m.groups() if x is not None]
            wp = rec["wpBefore"]
            band_ok = (wp >= 95) if kept == "a decisive advantage" else (82 <= wp < 95)
            ok = bsan == rec["best"] and cls == "MISS" and band_ok
            v = ("S", "MISS; the mover's win-percent before, %.1f, is in the band" % wp) if ok else ("W", "not provable, or the wrong band for %.1f" % wp)
        m = re.match(r"^A forced mate in (\d+) was on the board\.$", s)
        if m and v is None:
            v = ("S", "engine mate for the mover") if cls == "MISS" and (mover_mate_before(rec) or 0) == int(m.group(1)) else ("W", "mate claim not in the data")
        if s == "A decisive advantage was on the board." and v is None:
            v = ("S", "MISS: win percent before >= 90, or a mate") if cls == "MISS" else ("W", "not a MISS")
        m = re.match(r"^Better was (\S+)(?:, which (.+)|: it (.+)|; in the engine's line it (.+)|: in the engine's line it (.+))?\.$", s)
        if m and v is None:
            bsan = m.group(1)
            plain = m.group(2) or m.group(3)
            engine = m.group(4) or m.group(5)
            if bsan != rec["best"]:
                v = ("W", "not the engine's top move")
            elif cls not in ("INACCURACY", "MISTAKE", "BLUNDER", "MISS"):
                v = ("W", "a better move offered on a move rated %s" % cls)
            elif not plain and not engine:
                v = ("S", "the engine's top move")
            else:
                bm = board.parse_san(bsan)
                ab = board.copy()
                ab.push(bm)
                if engine:
                    v = verify_engine_line(engine, board, bm, rec, "better", game, ply)
                else:
                    v = verify_phrase(plain, board, bm, ab, rec, "better")
        m = re.match(r"^In the engine's line, (\S+) (.+)\.$|^The engine's line shows it: (\S+) (.+)\.$", s)
        if m and v is None:
            msan = m.group(1) or m.group(3)
            rest = m.group(2) or m.group(4)
            if msan.rstrip("+#") != bare_san.rstrip("+#"):
                v = ("W", "the engine-line claim names another move")
            else:
                v = verify_engine_line(rest, board, move, rec, "found", game, ply)
                if v[0] == "S" and (mover_mate_after(rec) or 0) < 0:
                    v = ("W", "the opponent has a forced mate after this move")
        m = re.match(r"^(This|It|%s) (.+)\.$" % re.escape(bare_san), s)
        if m and v is None:
            v = verify_phrase(m.group(2), board, move, after, rec, "found")
            if v[0] == "S" and (mover_mate_after(rec) or 0) < 0:
                v = ("W", "the opponent has a forced mate after this move")
        if v is None:
            v = ("W", "unrecognised sentence")
        out.append((s, v[0], v[1]))
    return out


def who_word(rec, is_white):
    """"you" / "your opponent" / the colour, for the side that is White when [is_white], as [rec]'s viewer sees it."""
    side = rec["side"]
    colour = "White" if is_white else "Black"
    if side is None:
        return colour
    return "you" if side == ("WHITE" if is_white else "BLACK") else "your opponent"


# ---------------------------------------------------------------------------------------------
# Walkthroughs (AFTER)
# ---------------------------------------------------------------------------------------------

def audit_sim_after(rec):
    sim = rec["sim"]
    board = chess.Board(sim["startFen"])
    winner = chess.WHITE if sim["tactic"]["by"] == "WHITE" else chess.BLACK
    pv = sim["pvUci"]
    sans = sim["pvSan"]
    rows = []
    # intro
    parts = SENT_SPLIT.split(sim["intro"])
    m = re.match(r"^Watch what happens: (\S+) starts the line\.$", parts[0])
    if m:
        rows.append(("intro", parts[0], "S" if m.group(1) == sans[0] else "W", "names the first move of the line"))
        point = " ".join(parts[1:])
        if point.startswith(sans[0].rstrip("+#") + " "):
            rows.append(("intro", point, "W", "repeats the first move"))
    else:
        point = " ".join(parts[1:]) if parts[0].startswith("Watch what happens.") else sim["intro"]
        rows.append(("intro", parts[0], "S", "lead-in; the description names the first move itself"))
    pt = re.match(r"^(?:The|the) (\w+) on ([a-h][1-8]) is attacked and nothing defends it\.$", point)
    pw = re.match(r"^(?:The|the) (\w+) on ([a-h][1-8]) is attacked, and taking it would win material\.$", point)
    if pt:
        b = board.copy()
        b.push(chess.Move.from_uci(pv[0]))
        s = chess.parse_square(pt.group(2))
        pc = b.piece_at(s)
        ok = pc is not None and NAME[pc.piece_type] == pt.group(1) and pc.color != winner and b.attackers(winner, s) and not b.attackers(not winner, s)
        rows.append(("intro", point, "S" if ok else "W", "attacked, nothing defends it, after the first move" if ok else "not attacked or not undefended"))
    elif pw:
        b = board.copy()
        b.push(chess.Move.from_uci(pv[0]))
        s = chess.parse_square(pw.group(2))
        b.turn = winner
        best = max([see(b, c) for c in b.legal_moves if b.is_capture(c) and c.to_square == s], default=None)
        rows.append(("intro", point, "S" if best is not None and best > 0 else "W", "capturing nets %s" % best))
    elif re.search(r"can be taken for nothing|cannot be held", point):
        rows.append(("intro", point, "W", "claims the piece is lost; it is only attacked and the opponent moves next"))
    elif point.strip():
        rows.append(("intro", point, "S", "motif description: geometry or the engine's line (detector tests)"))
    # steps
    b = board.copy()
    prev_move, prev_capture, prev_taken = None, False, 0
    for i, (u, san, text) in enumerate(zip(pv, sans, sim["steps"])):
        mv = chess.Move.from_uci(u)
        before = b.copy()
        b.push(mv)
        verdict, notes = "S", []
        pay = re.search(r" (White|Black) (wins [a-z ]+|mates in \d+|leaves the king in a mating net|forces stalemate.*|forces a draw by repetition|.+)\.$", text) if i == len(pv) - 1 else None
        head = (text[: pay.start()] + ".") if pay else text
        if i == len(pv) - 1 and re.search(r"(has invested material in the attack|gains a decisive advantage|keeps the king under fire|leaves the king in a mating net)\.$", text):
            verdict = "W"
            notes.append("unprovable payoff sentence on the last step")
        if not head.startswith(san + ":"):
            verdict = "W"
            notes.append("does not open with the move's SAN")
        piece = NAME[before.piece_at(mv.from_square).piece_type]
        if not mv.promotion and not before.is_castling(mv) and ("the %s " % piece) not in head:
            verdict = "W"
            notes.append("wrong moving piece")
        cm = re.search(r"captures the (\w+) on ([a-h][1-8])", head)
        if cm:
            cap = before.piece_at(mv.to_square)
            if not (cap or before.is_en_passant(mv)) or (cap and NAME[cap.piece_type] != cm.group(1)):
                verdict = "W"
                notes.append("wrong captured piece")
        recapture = prev_move is not None and prev_move.to_square == mv.to_square and before.is_capture(mv) and prev_capture
        mm = re.search(r"winning (a queen|a rook|a piece|a pawn|the exchange|material)", head)
        if mm:
            want = GAIN_WORDS.get(mm.group(1))
            if recapture:
                net = VALUE[before.piece_at(mv.to_square).piece_type] - prev_taken
                label = "pair"
            else:
                net = see(before, mv)
                label = "capture"
            if mm.group(1) == "the exchange":
                if not (not recapture and is_exchange_capture(before, mv) and EXCHANGE_MIN <= net <= EXCHANGE_MAX):
                    verdict = "W"
                    notes.append("claims the exchange but the capture is not a minor piece taking a rook for rook-minus-minor (nets %d)" % net)
            elif not (net >= 100 and (want is None or abs(net - want) <= 40)):
                verdict = "W"
                notes.append("claims %s but the %s nets %d" % (mm.group(1), label, net))
        if "an even trade" in head and not recapture:
            cp = see(before, mv)
            if not (-100 < cp < 100):
                verdict = "W"
                notes.append("not an even trade (nets %d)" % cp)
        if "gives itself up for" in head and see(before, mv) > -100:
            verdict = "W"
            notes.append("not a sacrifice")
        am = re.search(r"attacking (.+?)(?:, with check| — checkmate|, which nothing defends|\.$)", head)
        if am:
            mover_color = b.piece_at(mv.to_square).color
            for pcs, sq in re.findall(PIECE_ON, am.group(1)):
                s = chess.parse_square(sq)
                pc = b.piece_at(s)
                if not (pc is not None and NAME[pc.piece_type] == pcs and mv.to_square in b.attackers(mover_color, s)):
                    verdict = "W"
                    notes.append("%s on %s is not attacked by the moved piece" % (pcs, sq))
        if "collecting" in head:
            verdict = "W"
            notes.append("'collecting' claims a win the move does not make")
        if "which nothing defends" in head:
            mm2 = re.search(r"attacking the (\w+) on ([a-h][1-8]), which nothing defends", head)
            s = chess.parse_square(mm2.group(2))
            mover_color = b.piece_at(mv.to_square).color
            if not (b.attackers(mover_color, s) and not b.attackers(not mover_color, s)):
                verdict = "W"
                notes.append("not undefended")
        if "dragging" in head:
            mm2 = re.search(r"dragging the (\w+) on ([a-h][1-8]) away", head)
            pc = before.piece_at(chess.parse_square(mm2.group(2))) if mm2 else None
            if not (pc is not None and NAME[pc.piece_type] == mm2.group(1)):
                verdict = "W"
                notes.append("the dragged piece is not where the text says")
        if head.endswith("— checkmate.") and not b.is_checkmate():
            verdict = "W"
            notes.append("not checkmate")
        if ", with check." in head and not b.is_check():
            verdict = "W"
            notes.append("not check")
        rows.append(("step %d" % (i + 1), text, verdict, "; ".join(notes) or "movement and attacks verified"))
        prev_move = mv
        prev_capture = before.is_capture(mv)
        prev_taken = (VALUE[before.piece_at(mv.to_square).piece_type] if before.piece_at(mv.to_square) else VALUE[chess.PAWN]) if prev_capture else 0
    # payoff
    pay = sim["payoff"]
    if not pay:
        rows.append(("payoff", "(none)", "S", "nothing the board proves, so nothing is said"))
    else:
        if pay.startswith("mates in"):
            ok = b.is_checkmate() and int(pay.split()[-1]) == (len(pv) + 1) // 2
            rows.append(("payoff", pay, "S" if ok else "W", "checkmate at the end of the line" if ok else "not mate in that many"))
        elif pay.startswith("wins"):
            net = material(b, winner) - material(b, not winner) - (material(board, winner) - material(board, not winner))
            if b.turn != winner:  # settled: charge the loser's best take-back
                net -= max([0] + [see(b, c) for c in b.legal_moves if b.is_capture(c)])
            if pay == "wins the exchange":
                ok = wins_the_exchange(board, b, winner) and EXCHANGE_MIN <= net <= EXCHANGE_MAX
                rows.append(("payoff", pay, "S" if ok else "W", ("a minor piece for a rook, settled net %d" % net) if ok else ("not the exchange: settled net %d, or the wrong pieces changed" % net)))
            else:
                want = {"wins " + k[2:]: v for k, v in GAIN_WORDS.items()}.get(pay)
                ok = net >= 100 and (want is None or abs(net - want) <= 40)
                rows.append(("payoff", pay, "S" if ok else "W", ("settled net material %d" % net) if ok else ("settled net material is %d" % net)))
        elif re.search(r"invested material|decisive advantage|king under fire|mating net", pay):
            rows.append(("payoff", pay, "W", "a payoff the data cannot back"))
        else:
            rows.append(("payoff", pay, "S", "tactic-specific outcome"))
    return rows


# ---------------------------------------------------------------------------------------------
# BEFORE (the pre-R1b texts, from the text dump)
# ---------------------------------------------------------------------------------------------

def parse_before(path):
    recs = []
    game = side = None
    cur = None
    for line in open(path, encoding="utf-8"):
        line = line.rstrip("\n")
        m = re.match(r"=== (\w+) side=(\w+)", line)
        if m:
            game, side = m.group(1), (None if m.group(2) == "null" else m.group(2))
            continue
        m = re.match(r"ply (\d+) (\d+)(\.+) (\S+) \[(\w+)\] loss=(\S+) wp=(\S+)->(\S+) cp=(\S+)->(\S+) mateB=(\S+) mateA=(\S+) best=(\S+)", line)
        if m:
            cur = dict(game=game, side=side, ply=int(m.group(1)), moveNumber=int(m.group(2)), color="WHITE" if m.group(3) == "." else "BLACK",
                       san=m.group(4), cls=m.group(5), loss=float(m.group(6)),
                       mateB=None if m.group(11) == "null" else int(m.group(11)), mateA=None if m.group(12) == "null" else int(m.group(12)),
                       best=m.group(13), played=[], missed=[], threats=[], sim=None, text="", fenBefore=None)
            recs.append(cur)
            continue
        if cur is None:
            continue
        if line.startswith("   TEXT: "):
            cur["text"] = line[len("   TEXT: "):]
        elif line.startswith("   fenBefore="):
            cur["fenBefore"] = line[len("   fenBefore="):]
        elif line.startswith("   SIM "):
            m = re.match(r"   SIM (\w+) startFen=(.*) pv=\[(.*)\]$", line)
            cur["sim"] = dict(type=m.group(1), startFen=m.group(2), pvSan=[x.strip() for x in m.group(3).split(",")], steps=[], intro="", payoff="")
        elif line.startswith("     INTRO: "):
            cur["sim"]["intro"] = line[len("     INTRO: "):]
        elif re.match(r"     step\d+: ", line):
            cur["sim"]["steps"].append(re.sub(r"^     step\d+: ", "", line))
        elif line.startswith("     payoff: "):
            cur["sim"]["payoff"] = line[len("     payoff: "):]
        else:
            m = re.match(r"   (PLAYED|FOUND|MISSED|THREAT) (\w+) by=(\w+) (\w+) tgt=\[(.*?)\] inv=\[(.*?)\] sw=(\d+) c=([\d.]+) :: (.*)$", line)
            if m:
                t = dict(type=m.group(2), by=m.group(3), uci=m.group(4), targets=[x.strip() for x in m.group(5).split(",") if x.strip()],
                         involved=[x.strip() for x in m.group(6).split(",") if x.strip()], swing=int(m.group(7)), conf=float(m.group(8)), description=m.group(9))
                {"PLAYED": cur["played"], "FOUND": cur["played"], "MISSED": cur["missed"], "THREAT": cur["threats"]}[m.group(1)].append(t)
    return recs


BEFORE_SPLIT = re.compile(r"(?<=\.)\s+(?=[A-Z])")


def audit_text_before(rec):
    board = chess.Board(rec["fenBefore"])
    move = board.parse_san(rec["san"])
    after = board.copy()
    after.push(move)
    cls, loss, game, ply, san = rec["cls"], rec["loss"], rec["game"], rec["ply"], rec["san"]
    out = []
    for sent in BEFORE_SPLIT.split(rec["text"]):
        s = sent.strip()
        v = None
        if re.match(r"^\S+ follows known opening theory\.$", s):
            v = ("S", "BOOK classification")
        elif re.match(r"^\S+ was the only legal move\.$", s):
            v = ("S", "one legal move") if board.legal_moves.count() == 1 else ("W", "several legal moves")
        elif re.match(r"^\S+ matches the engine's top choice\.$", s):
            v = ("S", "played = engine top move") if rec["best"] == san else ("W", "not the top move")
        elif re.match(r"^\S+ is very close to the best move\.$", s):
            v = ("S", "loss %.1f < 2" % loss) if loss < 2 else ("W", "loss")
        elif re.match(r"^\S+ is a sound move\.$", s):
            v = ("F", "loss %.1f < 5" % loss)
        elif re.match(r"^\S+ was the only move that kept things on track\.$", s):
            gap = great_gap(game, ply)
            v = ("S", "MultiPV gap %.1f >= 10" % gap) if gap is not None and gap >= 10 else ("W", "gap %s" % gap)
        elif re.match(r"^\S+ is a stunning sacrifice\.$", s):
            nets = []
            for cap in after.legal_moves:
                if after.is_capture(cap) and not after.is_en_passant(cap):
                    victim = after.piece_at(cap.to_square)
                    if victim.color == board.turn and VALUE[victim.piece_type] >= 300:
                        nets.append(see(after, cap))
            best = max(nets) if nets else None
            v = ("S", "the opponent can take a piece for a net %s" % best) if best is not None and best >= 200 else ("W", "no sacrifice: the best legal capture of a piece nets %s" % best)
        elif s == "The point becomes clear a few moves later.":
            v = ("F", "filler, claims nothing checkable")
        elif re.match(r"^\S+ gives back ground\.$", s):
            v = ("F", "loss %.1f" % loss)
        elif re.match(r"^A forced mate in \d+ was on the board\.$|^Better was \S+, forcing mate in \d+\.$|^Better was \S+, keeping a decisive advantage\.$|^A decisive advantage was on the board\.$", s):
            v = ("S", "MISS wording from engine data")
        else:
            m = re.match(r"^Better was (\S+)(, keeping material level)?\.$", s)
            if m:
                if m.group(1) != rec["best"]:
                    v = ("W", "not the top move")
                elif m.group(2):
                    v = ("W", "'keeping material level' is not provable (nothing says the move holds material)")
                else:
                    v = ("S", "engine top move")
            m = re.match(r"^This wins the (\w+) on ([a-h][1-8])\.$", s)
            if m:
                tsq = chess.parse_square(m.group(2))
                captured = board.is_capture(move) and move.to_square == tsq and see(board, move) > 0
                b2 = after.copy()
                took = False
                for i, u in enumerate(pv_after(game, ply)[:4]):
                    mv = chess.Move.from_uci(u)
                    if i % 2 == 1 and b2.is_capture(mv) and mv.to_square == tsq:
                        took = True
                    b2.push(mv)
                if captured:
                    v = ("S", "the move itself captures it for a net gain")
                elif took:
                    v = ("S", "the engine's line takes it within four plies")
                else:
                    v = ("W", "the move does not capture it and the engine's line does not take it: it at most attacks it")
            m = re.match(r"^This drops the (\w+) on ([a-h][1-8])\.$", s)
            if m:
                tsq = chess.parse_square(m.group(2))
                pc = after.piece_at(tsq)
                if pc is None or NAME[pc.piece_type] != m.group(1):
                    v = ("W", "no such piece there")
                elif pc.color != board.turn:
                    v = ("W", "the %s belongs to the OPPONENT: the mover does not drop it" % m.group(1))
                else:
                    cp = [see(after, c) for c in after.legal_moves if c.to_square == tsq and after.is_capture(c)]
                    v = ("S", "the opponent can take the piece for a net %s" % max(cp)) if cp and max(cp) > 0 else ("W", "not actually droppable")
            m = re.match(r"^This forks pieces on ([a-h][1-8])( and [a-h][1-8])?\.$", s)
            if m:
                sqs = [chess.parse_square(x) for x in re.findall(r"[a-h][1-8]", s)]
                ok = all(move.to_square in after.attackers(board.turn, x) for x in sqs)
                v = ("S", "the played piece attacks both") if ok else ("W", "the played move does not fork these squares: the fork belongs to another move")
            m = re.match(r"^This pins the piece on ([a-h][1-8])\.$", s)
            if m:
                pinned = is_pin(after, board.turn, chess.parse_square(m.group(1)))
                v = ("S", "the played move pins it") if pinned else ("W", "the played move does not pin that piece")
            if re.match(r"^This skewers the piece on ([a-h][1-8])\.$", s):
                v = ("W", "skewer attributed to the played move; not borne out")
            if s == "This opens a discovered attack.":
                ok = any(t["type"] in ("DISCOVERED_ATTACK", "DISCOVERED_CHECK") and t["uci"] == move.uci() for t in rec["played"])
                v = ("S", "the move uncovers an attack (the detector's own rule)") if ok else ("W", "no discovery by this move")
            if s == "This delivers a double check.":
                v = ("S", "double check") if len(after.checkers()) >= 2 else ("W", "not a double check")
            if s == "This forces mate.":
                mm = mover_mate_after(rec)
                ok = after.is_checkmate() or (mm is not None and mm > 0)
                v = ("S", "the mover mates (engine / board)") if ok else ("W", "the engine's mate is the OPPONENT's, or there is none")
            m = re.match(r"^This traps the piece on ([a-h][1-8])\.$", s)
            if m:
                tsq = chess.parse_square(m.group(1))
                pc = after.piece_at(tsq)
                if not pc or pc.color == board.turn:
                    v = ("W", "the trapped piece is the MOVER's own: it is the opponent's threat, not the mover's trap")
                else:
                    mvs = [c for c in after.legal_moves if c.from_square == tsq]
                    v = ("S", "no safe square") if mvs and all(see(after, c) < 0 for c in mvs) else ("W", "the piece has a safe move")
            m = re.match(r"^This sets up (an?) ([\w -]+) on ([a-h][1-8])\.$", s)
            if m:
                tname = m.group(2).upper().replace(" ", "_").replace("-", "_")
                tname = {"RELATIVE_PIN": "PIN_RELATIVE", "ABSOLUTE_PIN": "PIN_ABSOLUTE", "MATING_NET": "MATE_NET", "IN_BETWEEN_MOVE": "ZWISCHENZUG"}.get(tname, tname)
                same = [t for t in rec["played"] if t["type"] == tname and t["uci"] == move.uci() and m.group(3) in t["targets"] and t["by"] == rec["color"]]
                other = [t for t in rec["missed"] + rec["threats"] if t["type"] == tname and m.group(3) in t["targets"]]
                if not same:
                    v = ("W", "the motif belongs to a different move (%s)" % ("the better move or the opponent's reply" if other else "none"))
                elif tname == "CLEARANCE":
                    pv = pv_starting_with(game, ply, move.uci())
                    ok = False
                    if len(pv) >= 3:
                        b3 = board.copy()
                        b3.push(chess.Move.from_uci(pv[0]))
                        b3.push(chess.Move.from_uci(pv[1]))
                        f = chess.Move.from_uci(pv[2])
                        ok = f.from_square != move.to_square and b3.piece_type_at(f.from_square) != chess.PAWN
                    v = ("S", "another piece uses the line") if ok else ("W", "the same piece comes back, or a pawn steps in: nothing is cleared for anyone")
                elif tname == "DEFLECTION":
                    pv = pv_starting_with(game, ply, move.uci())
                    mm = re.match(r"(\S+) deflects the (\w+) on ([a-h][1-8]) away from guarding ([a-h][1-8])", same[0]["description"])
                    ok = bool(mm and len(pv) >= 3 and chess.Move.from_uci(pv[1]).to_square != chess.parse_square(mm.group(4)))
                    if (mover_mate_after(rec) or 0) < 0:
                        v = ("W", "credited to a move after which the OPPONENT has a forced mate: the line is the opponent's combination")
                    else:
                        v = ("S", "the defender leaves its post") if ok else ("W", "the 'deflected' piece lands on the very square it was supposed to be deflected from (an exchange, not a deflection)")
                elif tname in ("X_RAY", "PIN_RELATIVE", "PIN_ABSOLUTE", "SKEWER"):
                    v = ("S", "geometry holds (static pattern)")
                else:
                    v = ("S", "motif recorded by the detector for this move")
            m = re.match(r"^(You|White|Black) allowed (an?) ([\w -]+?)( on [a-h][1-8])?\.$", s)
            if m:
                swings = [t["swing"] for t in rec["threats"]]
                if cls in ("INACCURACY", "MISTAKE", "BLUNDER", "MISS"):
                    v = ("S", "an error, and the opponent's best reply creates it") if swings and max(swings) >= 100 else ("F", "an error, but the motif wins nothing (swing 0)")
                elif cls in ("EXCELLENT", "GOOD"):
                    v = ("F", "a charge against a move rated %s, with a material consequence" % cls) if swings and max(swings) >= 100 \
                        else ("W", "charges a move rated %s with 'allowing' a motif that wins nothing" % cls)
                else:
                    v = ("W", "charges a move rated %s (the engine's own choice or better) with 'allowing' something" % cls)
            if re.match(r"^It drags the piece on ([a-h][1-8]) away from defending ([a-h][1-8]), and mate follows\.$", s):
                v = ("W", "unverifiable decoy claim")
        if v is None:
            v = ("W", "unrecognised sentence")
        out.append((s, v[0], v[1]))
    return out


def audit_sim_before(rec):
    sim = rec["sim"]
    board = chess.Board(sim["startFen"])
    pv_uci = []
    b = board.copy()
    for san in sim["pvSan"]:
        mv = b.parse_san(san)
        pv_uci.append(mv.uci())
        b.push(mv)
    shaped = dict(sim, pvUci=pv_uci, tactic=dict(by="WHITE" if board.turn == chess.WHITE else "BLACK"))
    return audit_sim_after(dict(rec, sim=shaped))


# ---------------------------------------------------------------------------------------------
# V2: the displayed engine lines (ANALYSIS_SPEC 6.2)
# ---------------------------------------------------------------------------------------------

def score_text(cp):
    """EvalFormat.score for centipawns: one decimal, explicit sign, Java's half-up rounding."""
    rounded = math.floor(cp / 100.0 * 10 + 0.5) / 10.0
    if rounded == 0:
        return "0.0"
    return ("+" if rounded > 0 else "") + "%.1f" % rounded


def balance(board, color):
    return sum((VALUE[p.piece_type] if p.piece_type != chess.KING else 0) * (1 if p.color == color else -1)
               for p in board.piece_map().values())


def settled_gain(start, end, mover):
    """The material [mover] netted, after the opponent's best take-back when it is their move."""
    net = balance(end, mover) - balance(start, mover)
    if end.turn == mover:
        return net
    takes = [see(end, m) for m in end.legal_moves if end.is_capture(m)]
    return net - max(0, max(takes) if takes else 0)


def gain_words(cp):
    for word, value in (("a queen", 900), ("a rook", 500), ("a piece", 325), ("a pawn", 100)):
        if abs(cp - value) <= 40:
            return word
    return None


def who(color, side):
    if side is None:
        return "White" if color == chess.WHITE else "Black"
    return "you" if (color == chess.WHITE) == (side == "WHITE") else "your opponent"


def numbered(board, sans):
    """'18... Nf5 19. Qd2 Nd4' from the start position's own move counter (the video caption's numbering)."""
    out, number, white = [], board.fullmove_number, board.turn == chess.WHITE
    for i, san in enumerate(sans):
        if white:
            out.append("%d. %s" % (number, san))
        elif i == 0:
            out.append("%d... %s" % (number, san))
        else:
            out.append(san)
        if not white:
            number += 1
        white = not white
    return " ".join(out)


RANK_WORDS = ["one", "two", "three", "four", "five", "six", "seven", "eight"]
PIECE_WORDS = {chess.PAWN: "pawn", chess.KNIGHT: "knight", chess.BISHOP: "bishop", chess.ROOK: "rook",
               chess.QUEEN: "queen", chess.KING: "king"}
BACK_TO_THE_GAME = "Back to the game now."


def square_words(sq):
    """'f seven': the file letter and the spelled rank, as the narration says a square."""
    return "%s %s" % (chess.square_name(sq)[0], RANK_WORDS[chess.square_rank(sq)])


def expected_line_move(board, move):
    """V4: what a spoken best-line move must say, from python-chess's board alone (ANALYSIS_SPEC 9.8):
    the piece on the from-square, its square when another piece of its kind could also reach the target,
    the target, the piece standing there for a capture, en passant, the promotion, and check or mate."""
    mover = board.piece_at(move.from_square)
    if board.is_castling(move):
        base = "castles kingside" if board.is_kingside_castling(move) else "castles queenside"
    else:
        name = PIECE_WORDS[mover.piece_type]
        twin = any(m.to_square == move.to_square and m.from_square != move.from_square
                   and board.piece_at(m.from_square).piece_type == mover.piece_type for m in board.legal_moves)
        who = "the %s on %s" % (name, square_words(move.from_square)) if twin else name
        victim = board.piece_at(move.to_square)
        if board.is_en_passant(move):
            base = "%s takes on %s, en passant" % (who, square_words(move.to_square))
        elif victim is not None:
            base = "%s takes the %s on %s" % (who, PIECE_WORDS[victim.piece_type], square_words(move.to_square))
        elif twin:
            base = "the %s from %s to %s" % (name, square_words(move.from_square), square_words(move.to_square))
        else:
            base = "%s to %s" % (who, square_words(move.to_square))
        if move.promotion:
            base += ", promoting to a %s" % PIECE_WORDS[move.promotion]
    after = board.copy()
    after.push(move)
    outcome = ", and that is checkmate" if after.is_checkmate() else (", with check" if after.is_check() else "")
    return base[0].upper() + base[1:] + outcome + "."


def audit_line_record(rec):
    """Every check on one move's displayed lines and video line; returns [(what, verdict, note)]."""
    out = []
    ev = analysis(rec["game"])[rec["ply"] - 1]
    recorded = {l["multiPv"]: l for l in ev["lines"]}
    depth = ev["depth"]
    start = chess.Board(rec["fenBefore"])
    mover = start.turn
    lines = rec["lines"]
    if not lines:
        return [("lines", "W", "no line for a move the engine analysed")]
    if lines[0]["multiPv"] != 1:
        out.append(("best first", "W", "the first line is not MultiPV 1"))
    best_wp = None
    for l in lines:
        tag = "line %d" % l["multiPv"]
        r = recorded.get(l["multiPv"])
        if r is None:
            out.append((tag, "W", "not a recorded line"))
            continue
        if chess.Board(l["startFen"]).board_fen() != start.board_fen() or chess.Board(l["startFen"]).turn != mover:
            out.append((tag, "W", "starts from another position"))
        if l["depth"] != depth:
            out.append((tag, "W", "depth %d, recorded %d" % (l["depth"], depth)))
        pv = r["pvUci"]
        if l["uci"] != pv[:len(l["uci"])]:
            out.append((tag, "W", "not a prefix of the recorded PV"))
        b = start.copy()
        legal = True
        if len(l["uci"]) != len(l["san"]):
            out.append((tag, "W", "%d moves but %d SANs" % (len(l["uci"]), len(l["san"]))))
        for u, san in zip(l["uci"], l["san"]):
            m = chess.Move.from_uci(u)
            if m not in b.legal_moves:
                out.append((tag, "W", "%s is illegal" % u))
                legal = False
                break
            if b.san(m) != san:
                out.append((tag, "W", "SAN %s, python-chess says %s" % (san, b.san(m))))
            b.push(m)
        if not legal:
            continue
        out.append((tag + " legal", "S", "%d plies" % len(l["uci"])))
        cap = min(len(pv), max(1, depth // 2) if depth > 0 else 1, 8)
        if len(l["uci"]) > cap or (len(l["uci"]) < cap and not b.is_checkmate()):
            out.append((tag + " length", "W", "%d plies, the rule gives %d" % (len(l["uci"]), cap)))
        else:
            out.append((tag + " length", "S", "min(%d, %d/2, 8)" % (len(pv), depth)))
        wp = line_wp(r)
        if l["multiPv"] == 1:
            best_wp = wp
        else:
            if best_wp is None or best_wp - wp > 2.0:
                out.append((tag + " margin", "W", "%.2f win-%% below the best" % ((best_wp or 0) - wp)))
            elif l["uci"][0] == rec["uci"]:
                out.append((tag + " margin", "W", "the alternative is the move played"))
            else:
                out.append((tag + " margin", "S", "%.2f below" % (best_wp - wp)))
        # The caption, sentence by sentence.
        caption = l["caption"]
        sentences = [x.strip() for x in re.split(r"(?<=\.)\s+", caption) if x.strip()]
        expected = []
        if r.get("mateIn") is not None and r["mateIn"] != 0:
            winner = mover if r["mateIn"] > 0 else (not mover)
            expected.append("The engine sees a forced mate in %d for %s." % (abs(r["mateIn"]), who(winner, rec["side"])))
        else:
            cp = r["scoreCp"] if mover == chess.WHITE else -r["scoreCp"]
            expected.append("The engine rates this line %s." % score_text(cp))
        if b.is_checkmate():
            expected.append("The line ends in checkmate.")
        else:
            gain = settled_gain(start, b, mover)
            if gain >= 100:
                subject = who(mover, rec["side"])
                if wins_the_exchange(start, b, mover) and EXCHANGE_MIN <= gain <= EXCHANGE_MAX:
                    what = "the exchange"
                else:
                    what = gain_words(gain) or "material"
                expected.append("In this line %s %s %s." % (subject, "win" if subject == "you" else "wins", what))
        if sentences == expected:
            for x in sentences:
                out.append((tag + " caption", "S", x))
        else:
            out.append((tag + " caption", "W", "%r, expected %r" % (caption, " ".join(expected))))
    v = rec.get("video")
    if v is not None:
        best = lines[0]
        if chess.Board(v["fen"]).board_fen() != start.board_fen():
            out.append(("video", "W", "starts from another position"))
        elif not (1 <= len(v["uci"]) <= 4) or v["uci"] != best["uci"][:len(v["uci"])]:
            out.append(("video", "W", "not the first 1-4 plies of the Board's best line"))
        else:
            b = start.copy()
            ok = True
            for k, u in enumerate(v["uci"]):
                m = chess.Move.from_uci(u)
                if m not in b.legal_moves or b.san(m) != v["san"][k]:
                    ok = False
                    break
                said = v.get("says", [None] * len(v["uci"]))[k]
                if said is not None:
                    # V4: each move is spoken over its own board, and says exactly that move.
                    if chess.Board(v["sayFens"][k]).board_fen() != b.board_fen() or v["sayUci"][k] != u:
                        out.append(("video spoken move", "W", "segment %d shows another position or move" % k))
                    want_said = expected_line_move(b, m)
                    out.append(("video spoken move", "S" if said == want_said else "W",
                                said if said == want_said else "%r, expected %r" % (said, want_said)))
                b.push(m)
                want = "Best line — " + numbered(start, v["san"][:k + 1])
                if v["captions"][k] != want:
                    out.append(("video caption", "W", "%r, expected %r" % (v["captions"][k], want)))
            out.append(("video", "S" if ok else "W", "%d plies" % len(v["uci"])))
            if "says" in v:
                if len(v["says"]) != len(v["uci"]) or v["sayKinds"] != ["BEST_LINE"] * len(v["uci"]) + ["KEY_MOMENT"]:
                    out.append(("video segments", "W", "%d spoken moves, kinds %s" % (len(v["says"]), v["sayKinds"])))
                # The return: said once, and the board really is the game's position before the move again.
                back_ok = v["back"] == BACK_TO_THE_GAME and chess.Board(v["backFen"]).board_fen() == start.board_fen()
                out.append(("video back to the game", "S" if back_ok else "W",
                            v["back"] if back_ok else "%r on %s" % (v["back"], v["backFen"])))
        if rec["cls"] not in ("MISTAKE", "MISS", "BLUNDER"):
            out.append(("video", "W", "a line played on a %s" % rec["cls"]))
    return out


def audit_return(rec):
    """V4: one place the narration says the board is back in the game. It must open with the sentence, say
    it once, follow a simulated line (a best line's last move or a detour's payoff) and show the game's
    position before the move it is about. Returns [(what, verdict, note)]."""
    out = []
    n = rec["narration"]
    if not n.startswith(BACK_TO_THE_GAME) or n.count(BACK_TO_THE_GAME) != 1:
        out.append(("return sentence", "W", "%r does not open with it exactly once" % n))
    elif rec["prevKind"] == "BEST_LINE" and n != BACK_TO_THE_GAME:
        out.append(("return sentence", "W", "%r adds to it after a best line" % n))
    else:
        out.append(("return sentence", "S", n))
    if rec["prevKind"] not in ("BEST_LINE", "MISSED_TACTIC"):
        out.append(("return place", "W", "follows a %s, not a simulated line" % rec["prevKind"]))
    else:
        out.append(("return place", "S", "after a %s" % rec["prevKind"]))
    if rec["boardFen"] is None or chess.Board(rec["boardFen"]).board_fen() != chess.Board(rec["fenBefore"]).board_fen():
        out.append(("return board", "W", "the board shows %s, the game was at %s" % (rec["boardFen"], rec["fenBefore"])))
    else:
        out.append(("return board", "S", "the game's position before ply %d" % rec["ply"]))
    return out


def main_lines(path):
    recs = [json.loads(l) for l in open(path, encoding="utf-8") if l.strip()]
    counts = Counter()
    wrong = []
    per_game = defaultdict(Counter)
    for r in recs:
        for what, verdict, note in audit_line_record(r):
            counts[verdict] += 1
            per_game[r["game"]][verdict] += 1
            if verdict == "W":
                wrong.append((r["game"], r["side"], r["ply"], r["san"], what, note))
    returns_path = os.path.join(os.path.dirname(path), "video_returns.jsonl")
    returns = [json.loads(l) for l in open(returns_path, encoding="utf-8") if l.strip()] if os.path.exists(returns_path) else []
    for r in returns:
        for what, verdict, note in audit_return(r):
            counts[verdict] += 1
            per_game[r["game"]][verdict] += 1
            if verdict == "W":
                wrong.append((r["game"], r["side"], r["ply"], "", what, note))
    lines = sum(len(r["lines"]) for r in recs)
    alts = sum(1 for r in recs for l in r["lines"] if l["multiPv"] > 1)
    video = sum(1 for r in recs if r.get("video"))
    exchange = sum(1 for r in recs for l in r["lines"] if "the exchange" in l["caption"])
    spoken = sum(len(r["video"].get("says", [])) for r in recs if r.get("video"))
    after_detour = sum(1 for r in returns if r["prevKind"] == "MISSED_TACTIC")
    print("### Displayed engine lines (ANALYSIS_SPEC 6.2)\n")
    print("%d moves x sides, %d lines (%d alternatives), %d video lines, %d captions say 'the exchange'. %d checks: %d supported, %d WRONG.\n" % (
        len(recs), lines, alts, video, exchange, sum(counts.values()), counts["S"], counts["W"]))
    print("V4: %d spoken line moves; %d returns to the game (%d after a best line, %d after a detour).\n" % (
        spoken, len(returns), len(returns) - after_detour, after_detour))
    print("| Game | Checks | Supported | WRONG |")
    print("|---|---|---|---|")
    for g, c in sorted(per_game.items()):
        print("| %s | %d | %d | %d |" % (g, sum(c.values()), c["S"], c["W"]))
    print("\n### Every WRONG check\n")
    for g, side, ply, san, what, note in wrong:
        print("- %s (%s) ply %d %s [%s]: %s" % (g, side, ply, san, what, note))
    if not wrong:
        print("(none)")
    return 1 if wrong else 0


# ---------------------------------------------------------------------------------------------
# Negative controls: break one template or term and show that the verifier flags it
# ---------------------------------------------------------------------------------------------

def _sub(pattern, repl):
    def f(text):
        return re.sub(pattern, repl, text, count=1) if re.search(pattern, text) else None
    return f


def _append(sentence):
    def f(text):
        return text + " " + sentence
    return f


def _swap_bands(text):
    m = re.search(r"from (.+?) to (.+?)\.", text)
    if not m or " from " not in text:
        return None
    return text[:m.start()] + "from %s to %s." % (m.group(2), m.group(1)) + text[m.end():]


MUTATIONS = [
    # (name, which record it applies to (a predicate on the record), how the text is broken)
    ("a fork with a wrong target square", lambda r: re.search(r"forks? .*the \w+ on [a-h][1-8] and the \w+ on [a-h][1-8]", r["text"]),
     lambda t: re.sub(r"(and the \w+ on )([a-h])([1-8])", lambda m: m.group(1) + ("a" if m.group(2) != "a" else "h") + m.group(3), t, count=1)),
    ("a fork claimed on pieces the moved piece does not attack", lambda r: r["cls"] == "BEST" and r["text"].count(".") == 1,
     lambda t: t + " This forks the king on e8 and the queen on d8."),
    ("a pawn fork claimed for a piece that is not a pawn", lambda r: r["cls"] == "BEST" and r["san"][0] in "NBRQK" and r["text"].count(".") == 1,
     lambda t: t + " This is a pawn fork, hitting the king on e8 and the queen on d8."),
    ("a skewer whose rear piece is not on the line", lambda r: re.search(r"skewers the \w+ on [a-h][1-8], with the \w+ on [a-h][1-8] behind it", r["text"]),
     _sub(r"(with the \w+ on )([a-h])([1-8]) behind it", lambda m: m.group(1) + ("a" if m.group(2) != "a" else "h") + m.group(3) + " behind it")),
    ("a discovered check claimed for a direct check", lambda r: r["cls"] == "BEST" and r["san"].endswith("+") and r["text"].count(".") == 1,
     lambda t: t + " This is a discovered check from the rook on a1."),
    ("a trapped piece that has a safe square", lambda r: r["cls"] == "BEST" and r["text"].count(".") == 1,
     lambda t: t + " This traps the queen on d8: every square it can reach loses material."),
    ("'wins the exchange' said as 'wins a rook'", lambda r: "the exchange" in r["text"], _sub(r"(wins|picks up) the exchange", r"\1 a rook")),
    ("'wins a rook' said as 'wins the exchange'", lambda r: re.search(r"(wins|picks up) a rook", r["text"]), _sub(r"(wins|picks up) a rook", r"\1 the exchange")),
    ("a relative pin called an absolute pin", lambda r: re.search(r"pins the \w+ on [a-h][1-8] to the (?!king)\w+ on [a-h][1-8]", r["text"]),
     _sub(r"pins (the \w+ on [a-h][1-8]) to (the \w+ on [a-h][1-8])", r"puts \1 in an absolute pin against \2")),
    ("'en prise' for a piece that is defended", lambda r: re.search(r"attacks the \w+ on [a-h][1-8] with an? \w+", r["text"]),
     _sub(r"attacks (the \w+ on [a-h][1-8]) with an? \w+", r"attacks \1, which is en prise")),
    ("'loose' for a piece attacked more often than defended (it has defenders)", lambda r: "more often than it is defended" in r["text"],
     _sub(r"attacks the (\w+) on ([a-h][1-8]) more often than it is defended", r"hits the loose \1 on \2")),
    ("forced mate in N said as N+1", lambda r: re.search(r"forced mate in (\d+)", r["text"]),
     lambda t: re.sub(r"forced mate in (\d+)", lambda m: "forced mate in %d" % (int(m.group(1)) + 1), t, count=1)),
    ("the evaluation bands swapped", lambda r: re.search(r"^That takes|swings from|in one move: that is what", r["text"], re.M) and " from " in r["text"], _swap_bands),
    ("a zwischenzug claimed on a quiet move", lambda r: r["cls"] == "BEST" and not r["san"].endswith("+") and "x" not in r["san"] and r["text"].count(".") == 1,
     lambda t: t + " In the engine's line, %s is a zwischenzug: it comes first, and Qxd8 follows." % t.split()[0]),
    ("an overloaded defender that is not the sole guard", lambda r: r["cls"] == "BEST" and r["text"].count(".") == 1,
     lambda t: t + " In the engine's line, %s exploits the overloaded king on e8, which cannot guard d7 and f7 at once." % t.split()[0]),
    ("an 'only move' lead on a move that is not an only move", lambda r: r["cls"] == "BEST" and "matches the engine's top choice" in r["text"],
     _sub(r"matches the engine's top choice", "was the only move that kept things on track")),
    ("a desperado claimed for an ordinary capture", lambda r: r["cls"] == "BEST" and "x" in r["san"] and r["text"].count(".") == 1,
     lambda t: t + " This is a desperado: the bishop was lost anyway, so it takes the pawn on e5 on the way out."),
    ("a back-rank mate threat that is not there", lambda r: r["cls"] == "BEST" and r["text"].count(".") == 1,
     lambda t: t + " This threatens Rd8, mate on the back rank."),
    ("'Better was' naming the wrong move", lambda r: "Better was " in r["text"], _sub(r"Better was (\S+)", r"Better was Zz9")),
    ("a charge against a move the engine approved of", lambda r: r["cls"] == "BEST" and r["text"].count(".") == 1,
     lambda t: t + " This lets White play Qxd8, which wins a queen."),
    ("'a decisive advantage' kept on a MISS below the band", lambda r: r["cls"] == "MISS" and "a winning position" in r["text"],
     _sub(r"a winning position", "a decisive advantage")),
    ("a sacrifice on a piece nobody can take", lambda r: re.search(r"is a sacrifice: it offers the \w+ on [a-h][1-8]", r["text"]),
     _sub(r"it offers the \w+ on ([a-h][1-8])", r"it offers the king on \1")),
    ("a smothered mate that is a plain mate", lambda r: re.search(r"(This|It|\S+) (is checkmate|is mate|delivers checkmate)\.", r["text"]),
     _sub(r"(is checkmate|is mate|delivers checkmate)\.", "is a smothered mate.")),
    ("'is mate' on a move that is not mate", lambda r: r["cls"] == "BEST" and r["text"].count(".") == 1 and not r["san"].endswith("#"),
     lambda t: t + " This delivers checkmate."),
]


def _mutate_said(fn):
    """A mutation of the first spoken line move of a record (None when it does not apply)."""
    def f(rec):
        v = rec.get("video")
        if not v or not v.get("says"):
            return None
        for k, said in enumerate(v["says"]):
            changed = fn(said)
            if changed is not None and changed != said:
                says = list(v["says"])
                says[k] = changed
                return dict(rec, video=dict(v, says=says))
        return None
    return f


def _mutate_video(fn):
    def f(rec):
        v = rec.get("video")
        if not v or not v.get("says"):
            return None
        return dict(rec, video=fn(dict(v)))
    return f


def _last_position(v):
    b = chess.Board(v["fen"])
    for u in v["uci"]:
        b.push(chess.Move.from_uci(u))
    return b.fen()


LINE_MUTATIONS = [
    ("a spoken line move on the wrong square", _mutate_said(lambda t: re.sub(r"([a-h]) (one|two|three|four|five|six|seven|eight)\.$",
        lambda m: "%s %s." % ("a" if m.group(1) != "a" else "h", m.group(2)), t, count=1) if re.search(r"[a-h] \w+\.$", t) else None)),
    ("a capture said as a quiet move", _mutate_said(lambda t: re.sub(r"takes the \w+ on", "to", t, count=1) if "takes the" in t else None)),
    ("the wrong piece named", _mutate_said(lambda t: ("Bishop" + t[len("Knight"):]) if t.startswith("Knight") else (("Knight" + t[len("Pawn"):]) if t.startswith("Pawn") else None))),
    ("a check left unsaid or added", _mutate_said(lambda t: t.replace(", with check.", ".") if ", with check." in t else t[:-1] + ", with check.")),
    ("a notation word instead of the spoken move", _mutate_said(lambda t: "Nf3.")),
    ("'Back to the game now.' over the line's last position", _mutate_video(lambda v: dict(v, backFen=_last_position(v)))),
    ("the return sentence missing", _mutate_video(lambda v: dict(v, back="In the real game, this went on the board.")))
]


def main_mutate_lines(path):
    recs = [json.loads(l) for l in open(path, encoding="utf-8") if l.strip() and json.loads(l)["side"] is None]
    print("\n### Negative controls: the spoken best line (V4)\n")
    print("| Mutation | Record | Flagged |")
    print("|---|---|---|")
    missed = 0
    for name, mutate in LINE_MUTATIONS:
        rec, mutated = None, None
        for r in recs:
            mutated = mutate(r)
            if mutated is not None:
                rec = r
                break
        if rec is None:
            print("| %s | (no record to apply it to) | n/a |" % name)
            continue
        before = [v for _, v, _ in audit_line_record(rec)]
        after = audit_line_record(mutated)
        flagged = "W" not in before and any(v == "W" for _, v, _ in after)
        note = next((n for _, v, n in after if v == "W"), "")
        print("| %s | %s %d %s | %s |" % (name, rec["game"], rec["ply"], rec["san"], ("WRONG: " + note).replace("|", "\\|") if flagged else "**NOT FLAGGED**"))
        if not flagged:
            missed += 1
    print("\n%d line mutations not flagged." % missed)
    return missed


def main_mutate(path):
    recs = [json.loads(l) for l in open(path, encoding="utf-8") if l.strip() and json.loads(l)["side"] is None]
    print("### Negative controls: one template or term broken at a time\n")
    print("| Mutation | Record | Mutated sentence | Flagged |")
    print("|---|---|---|---|")
    missed = 0
    for name, applies, mutate in MUTATIONS:
        rec = next((r for r in recs if applies(r)), None)
        if rec is None:
            print("| %s | (no record to apply it to) | | n/a |" % name)
            continue
        mutated = mutate(rec["text"])
        if mutated is None or mutated == rec["text"]:
            print("| %s | %s %d %s | (mutation did not apply) | n/a |" % (name, rec["game"], rec["ply"], rec["san"]))
            continue
        rows = audit_text_after(dict(rec, text=mutated))
        changed = [(s, v, n) for (s, v, n) in rows if s not in SENT_SPLIT.split(rec["text"])]
        flagged = any(v == "W" for _, v, _ in changed) or any(v == "W" for _, v, _ in rows)
        sentence = (changed[0][0] if changed else mutated).replace("|", "\\|")
        note = next((n for _, v, n in rows if v == "W"), "")
        print("| %s | %s %d %s | %s | %s |" % (name, rec["game"], rec["ply"], rec["san"], sentence, ("WRONG: " + note) if flagged else "**NOT FLAGGED**"))
        if not flagged:
            missed += 1
    print("\n%d mutations not flagged." % missed)
    lines_path = os.path.join(os.path.dirname(path), "best_lines.jsonl")
    if os.path.exists(lines_path):
        missed += main_mutate_lines(lines_path)
    story_path = os.path.join(os.path.dirname(path), "story.jsonl")
    if os.path.exists(story_path):
        missed += main_mutate_story(story_path)
    return 1 if missed else 0


# ---------------------------------------------------------------------------------------------
# NS: the narrated story (ANALYSIS_SPEC 9.9). `story` re-derives every claim of the story sentences
# ---------------------------------------------------------------------------------------------
#
# Core's test ``CommentaryAuditDumpTest.dumpStory`` writes ``core/build/commentary_audit/story.jsonl``: per game and
# side a `game` record (each ply's move, class and missed motif - what the generator took from its annotations) and a
# `part` record for every sentence of the told story that makes a claim of its own (its kind, its words, the plies it is
# about). Each sentence must match the template of its kind (an unrecognised sentence is WRONG) and every claim in it is
# re-derived here from the recorded engine numbers and python-chess: the phases of the game from the White win-percent
# series (a second implementation of core's GamePhases and StoryArc), the standing words from the win-percent bands, a mate
# from the engine's distance, the better move's result from the first plies of the engine's line, what a move lets in from
# the engine's best reply and static exchange, the skipped stretch from the board.

STORY_GAMES = dict(GAME_FILES, byrne_fischer=ROOT + "/core/src/test/resources/pacing/byrne_fischer.analysis.json")
NUMBER_WORDS = ["no", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten"]
NUM = r"(\d+|no|one|two|three|four|five|six|seven|eight|nine|ten)"
BAND = r"(decisively winning|winning|clearly better|slightly better|about level|slightly worse|clearly worse|losing|decisively lost)"
WB = r"(White|Black)"
SEVERITY = {50: "the whole game", 30: "most of the advantage", 18: "a big chunk of the position", 10: "real ground"}
TACTIC_NAMES = {
    "FORK": "fork", "PAWN_FORK": "pawn fork", "DOUBLE_ATTACK": "double attack", "PIN_ABSOLUTE": "absolute pin",
    "PIN_RELATIVE": "relative pin", "SKEWER": "skewer", "DISCOVERED_ATTACK": "discovered attack",
    "DISCOVERED_CHECK": "discovered check", "DOUBLE_CHECK": "double check", "HANGING_PIECE": "hanging piece",
    "TRAPPED_PIECE": "trapped piece", "DEFLECTION": "deflection", "DECOY": "decoy", "OVERLOADED_PIECE": "overloaded piece",
    "INTERFERENCE": "interference", "CLEARANCE": "clearance", "ZWISCHENZUG": "in-between move", "BACK_RANK_MATE": "back-rank mate",
    "SMOTHERED_MATE": "smothered mate", "GREEK_GIFT": "greek gift sacrifice", "WINDMILL": "windmill", "X_RAY": "x-ray",
    "PROMOTION_TACTIC": "promotion", "UNDERPROMOTION": "underpromotion", "PASSED_PAWN_BREAKTHROUGH": "passed pawn breakthrough",
    "REMOVING_THE_DEFENDER": "removing the defender", "MATE_NET": "mating net", "PERPETUAL_CHECK": "perpetual check",
    "STALEMATE_TRICK": "stalemate trick", "DESPERADO": "desperado", "BATTERY": "battery", "FORTRESS": "fortress",
}
ERROR_NOUN = {"BLUNDER": "blunder", "MISTAKE": "mistake", "MISS": "missed win"}
PHASE_ERRORS = ("BLUNDER", "MISTAKE", "MISS")
MISTAKE_CLASSES = ("INACCURACY", "MISTAKE", "MISS", "BLUNDER")


def st_num(token):
    return NUMBER_WORDS.index(token) if token in NUMBER_WORDS else int(token)


def st_number_word(n):
    return NUMBER_WORDS[n] if 0 <= n <= 10 else str(n)


# --- the series, the phases (a second implementation of core's GamePhases), the arc -----------------------------------

def st_white_wp(ev):
    line = min(ev["lines"], key=lambda l: l["multiPv"]) if ev["lines"] else None
    if line is None:
        return 50.0
    stm_white = ev["fen"].split()[1] == "w"
    w = line_wp(line)
    return w if stm_white else 100.0 - w


def st_signed(w):
    ahead = w if w >= 50.0 else 100.0 - w
    level = 3 if ahead >= 82.0 else 2 if ahead >= 68.0 else 1 if ahead >= 57.0 else 0
    return level if w >= 50.0 else -level


ST_LOW = {-3: 0.0, -2: 18.0, -1: 32.0, 0: 43.0, 1: 57.0, 2: 68.0, 3: 82.0}
ST_HIGH = {-3: 18.0, -2: 32.0, -1: 43.0, 0: 57.0, 1: 68.0, 2: 82.0, 3: 100.0 + 1e-9}
ST_MARGIN, ST_MIN_LEN, ST_MIN_LAST = 3.0, 4, 2


def st_phases(series):
    """[(signed standing, start, end)]: hysteresis, runs, then the shortest short phase joined, one at a time."""
    if not series:
        return []
    cur = st_signed(series[0])
    states = [cur]
    for x in series[1:]:
        fresh = st_signed(x)
        if fresh != cur and (x < ST_LOW[cur] - ST_MARGIN or x >= ST_HIGH[cur] + ST_MARGIN):
            cur = fresh
        states.append(cur)
    phases, start = [], 0
    for i in range(1, len(states) + 1):
        if i == len(states) or states[i] != states[start]:
            phases.append([states[start], start, i - 1])
            start = i
    while len(phases) >= 2:
        best = None
        for i, p in enumerate(phases):
            minimum = ST_MIN_LAST if i == len(phases) - 1 else ST_MIN_LEN
            if p[2] - p[1] + 1 >= minimum:
                continue
            if best is None or p[2] - p[1] < phases[best][2] - phases[best][1]:
                best = i
        if best is None:
            break
        i, p = best, phases[best]
        prev = phases[i - 1] if i > 0 else None
        nxt = phases[i + 1] if i + 1 < len(phases) else None
        if nxt is None and prev is not None:
            prev[2] = p[2]
        elif prev is None and nxt is not None:
            nxt[1] = p[1]
        elif prev is not None and nxt is not None and prev[0] == nxt[0]:
            prev[2] = nxt[2]
            del phases[i + 1]
        elif nxt is not None:
            nxt[1] = p[1]
        del phases[i]
        merged = []
        for q in phases:
            if merged and merged[-1][0] == q[0]:
                merged[-1][2] = q[2]
            else:
                merged.append(q)
        phases = merged
    return [tuple(p) for p in phases]


def st_leader(signed):
    return None if signed == 0 else (chess.WHITE if signed > 0 else chess.BLACK)


def st_longest_clear(phases, color):
    best = run = 0
    for s, a, b in phases:
        if st_leader(s) == color and abs(s) >= 2:
            run += b - a + 1
            best = max(best, run)
        else:
            run = 0
    return best


def st_arc(phases, result, plies):
    """core's StoryArc.of: ('comeback', winner, moves) | ('tookover', winner, move, early) | ('tightdraw',) | ('wasted', leader, moves) | None."""
    if plies < 16 or not phases:
        return None
    if result in ("1-0", "0-1"):
        winner = chess.WHITE if result == "1-0" else chess.BLACK
        loser = not winner
        run = st_longest_clear(phases, loser)
        if run // 2 >= 6:
            return ("comeback", winner, run // 2)
        if any(st_leader(s) == loser for s, _, _ in phases):
            return None
        first = next((i for i, (s, _, _) in enumerate(phases) if st_leader(s) == winner), None)
        if first is None or any(st_leader(s) != winner for s, _, _ in phases[first:]):
            return None
        if sum(b - a + 1 for _, a, b in phases[first:]) < 8:
            return None
        move = max(1, (phases[first][1] + 1) // 2)
        return ("tookover", winner, move, move <= 6)
    if result == "1/2-1/2":
        for color in (chess.WHITE, chess.BLACK):
            run = st_longest_clear(phases, color)
            if run // 2 >= 6:
                return ("wasted", color, run // 2)
        return ("tightdraw",) if all(abs(s) <= 1 for s, _, _ in phases) else None
    return None


def st_aftermath(phases, ply, mover):
    opp = not mover
    idx = next((i for i, (_, a, b) in enumerate(phases) if a <= ply <= b), None)
    if idx is None:
        return None
    after = phases[idx:]
    if st_leader(after[0][0]) != opp or abs(after[0][0]) < 1:
        return None
    if all(st_leader(s) == opp for s, _, _ in after):
        return ("neverback", opp)
    back = next((p for p in after if st_leader(p[0]) != opp), None)
    return ("foughtback", mover, (back[1] + 1) // 2, back[0])


def st_band(wp):
    return standing_words(wp)


def st_phase_words(signed):
    return {1: "slightly better", 2: "clearly better", 3: "winning"}.get(abs(signed), "about level")


def st_side_name(color):
    return "White" if color == chess.WHITE else "Black"


def st_color_of(name):
    return chess.WHITE if name == "White" else chess.BLACK


# --- a game and a side ----------------------------------------------------------------------------------------------

class StoryGame:
    def __init__(self, rec):
        self.name = rec["game"]
        self.side = rec["side"]
        self.result = rec["result"]
        self.plies = rec["plies"]
        self.n = len(self.plies)
        # a game cut short has the first positions of the recording
        self.evals = analysis_story(rec.get("base", self.name))[: self.n + 1]
        self.series = [st_white_wp(ev) for ev in self.evals]
        self.phases = st_phases(self.series)
        self.user = None if self.side is None else (chess.WHITE if self.side == "WHITE" else chess.BLACK)
        self._boards = {}

    def color(self, ply):
        return chess.WHITE if self.plies[ply - 1]["color"] == "WHITE" else chess.BLACK

    def before(self, ply):
        return chess.Board(self.evals[ply - 1]["fen"])

    def after(self, ply):
        return chess.Board(self.evals[ply]["fen"])

    def move(self, ply):
        return chess.Move.from_uci(self.plies[ply - 1]["uci"])

    def wp(self, ply, which, color):
        """Win-percent for [color] in the position before ([which] 0) or after ([which] 1) ply [ply]."""
        w = self.series[ply - 1 + which]
        return w if color == chess.WHITE else 100.0 - w

    def loss(self, ply):
        c = self.color(ply)
        return max(0.0, self.wp(ply, 0, c) - self.wp(ply, 1, c))

    def cls(self, ply):
        return self.plies[ply - 1]["cls"]

    def book_end(self):
        return max((p["ply"] for p in self.plies if p["cls"] == "BOOK"), default=0)

    def turning(self):
        """core's turningPoint: the largest loss above 0.5 that is neither BOOK nor FORCED, the earlier on a tie."""
        best = None
        for p in self.plies:
            loss = self.loss(p["ply"])
            if loss > 0.5 and p["cls"] not in ("BOOK", "FORCED") and (best is None or loss > best[0] + 1e-12):
                best = (loss, p["ply"])
        return None if best is None else best[1]

    def who(self, color):
        """The narration's subject for [color]: 'you' for the viewer, else the colour."""
        return "you" if self.user is not None and color == self.user else st_side_name(color)

    def mover_mate_before(self, ply):
        line = min(self.evals[ply - 1]["lines"], key=lambda l: l["multiPv"]) if self.evals[ply - 1]["lines"] else None
        if line is None or line.get("mateIn") is None or line["mateIn"] <= 0:
            return None
        return line["mateIn"] if line["mateIn"] <= 8 else None

    def opponent_mate_after(self, ply):
        if ply >= len(self.evals):
            return None
        line = min(self.evals[ply]["lines"], key=lambda l: l["multiPv"]) if self.evals[ply]["lines"] else None
        if line is None or line.get("mateIn") is None or line["mateIn"] <= 0:
            return None
        return line["mateIn"] if line["mateIn"] <= 8 else None

    def best_pv(self, ply):
        lines = self.evals[ply - 1]["lines"]
        return min(lines, key=lambda l: l["multiPv"])["pvUci"] if lines else []


_story_analysis = {}


def analysis_story(game):
    if game not in _story_analysis:
        _story_analysis[game] = json.load(open(STORY_GAMES[game], encoding="utf-8"))["evals"]
    return _story_analysis[game]


# --- spoken forms -----------------------------------------------------------------------------------------------------

def st_phrase(board, move):
    """A move as a noun phrase after a verb ('knight takes the pawn on f seven, with check'), from the board alone."""
    e = expected_line_move(board, move)
    return e[0].lower() + e[1:-1]


def st_cap(text):
    return text[0].upper() + text[1:] if text else text


def st_clause(board, move, you, color, recapture=False):
    """core's Played / moveClause for a PLAY verb: 'White plays knight to f three', 'you take the pawn on e five with the knight'."""
    who = "you" if you else st_side_name(color)
    third = not you
    after = board.copy()
    after.push(move)
    suffix = ", and that is checkmate" if after.is_checkmate() else (", with check" if after.is_check() else "")
    if board.is_castling(move):
        return "%s %s %s%s" % (who, "castles" if third else "castle", "kingside" if board.is_kingside_castling(move) else "queenside", suffix)
    if board.is_capture(move):
        mover = board.piece_at(move.from_square)
        name = PIECE_WORDS[mover.piece_type]
        twin = any(m.to_square == move.to_square and m.from_square != move.from_square
                   and board.piece_at(m.from_square).piece_type == mover.piece_type for m in board.legal_moves)
        with_part = "with the %s on %s" % (name, square_words(move.from_square)) if twin else "with the %s" % name
        verb = ("takes" if third else "take") + (" back" if recapture else "")
        to = square_words(move.to_square)
        if recapture:
            obj = "on %s" % to
        elif board.is_en_passant(move):
            obj = "on %s, en passant," % to
        else:
            obj = "the %s on %s" % (PIECE_WORDS[board.piece_at(move.to_square).piece_type], to)
        promo = ", promoting to a %s" % PIECE_WORDS[move.promotion] if move.promotion else ""
        return "%s %s %s %s%s%s" % (who, verb, obj, with_part, promo, suffix)
    return "%s %s %s" % (who, "plays" if third else "play", st_phrase(board, move))


def st_recapture(g, ply):
    if ply < 2:
        return False
    move, previous = g.move(ply), g.move(ply - 1)
    return g.before(ply).is_capture(move) and g.before(ply - 1).is_capture(previous) and previous.to_square == move.to_square


def st_exchange_band(cp):
    """core's materialGain 40 cp rule on a gain: 'a queen' ... or None."""
    return gain_words(cp)


def st_severity(loss):
    for limit, words in SEVERITY.items():
        if loss >= limit:
            return words
    return "a little something"


# --- the verdict of one part -----------------------------------------------------------------------------------------

def st_verify(g, part, parts_before):
    """[(verdict, note)] for one story sentence: S supported, W wrong (a template that does not match is wrong too)."""
    pid, text, plies = part["id"], part["text"], part["plies"]
    fn = ST_VERIFIERS.get(pid)
    if fn is None:
        return [("W", "no verifier for the sentence kind %s" % pid)]
    if part["segment"] < 0:
        return [("W", "the sentence is not in the script")]
    try:
        return [fn(g, text, plies, parts_before)]
    except AssertionError as e:
        return [("W", str(e))]


def st_expect(cond, note):
    if not cond:
        raise AssertionError(note)


def st_match(patterns, text):
    for p in patterns:
        m = re.match(p, text)
        if m:
            return m
    raise AssertionError("not a template of its kind: %r" % text)


def v_stakes(g, text, plies, before):
    arc = st_arc(g.phases, g.result, g.n)
    st_expect(arc is not None, "the graph proves no story for this game")
    if text.startswith(("White won", "Black won", "For ")):
        m = st_match([r"^%s won, but for %s moves this was %s's game\.$" % (WB, NUM, WB),
                      r"^%s won it, though for %s moves %s was clearly ahead\.$" % (WB, NUM, WB),
                      r"^For %s moves %s was clearly better, and %s still won\.$" % (NUM, WB, WB)], text)
        sides = [x for x in m.groups() if x in ("White", "Black")]
        n = st_num(next(x for x in m.groups() if x not in ("White", "Black")))
        if text.startswith("For "):
            loser, winner = st_color_of(sides[0]), st_color_of(sides[1])
        else:
            winner, loser = st_color_of(sides[0]), st_color_of(sides[1])
        st_expect(winner != loser, "the winner and the other side are the same")
        st_expect(arc[0] == "comeback" and arc[1] == winner, "the arc is %r" % (arc,))
        st_expect(arc[2] == n, "the other side was clearly better for %d moves, not %d" % (arc[2], n))
        return ("S", "the other side held a clear standing for %d moves in a row, and lost" % n)
    if text.startswith(("It was level until move", "Nothing separated them")) or "on top from the opening" in text or "got ahead early" in text:
        m = st_match([r"^%s was on top from the opening and never let go\.$" % WB, r"^%s got ahead early and stayed there\.$" % WB,
                      r"^It was level until move (\d+), and then %s took over for good\.$" % WB,
                      r"^Nothing separated them until move (\d+), when %s took over for good\.$" % WB], text)
        winner = st_color_of([x for x in m.groups() if x in ("White", "Black")][0])
        early = text.startswith(("White was on", "Black was on", "White got", "Black got"))
        st_expect(arc[0] == "tookover" and arc[1] == winner and arc[3] == early, "the arc is %r" % (arc,))
        if not early:
            st_expect(int(m.group(1)) == arc[2], "the lead began at move %d, not %s" % (arc[2], m.group(1)))
        return ("S", "the other side never led; the winner led from move %d" % arc[2])
    if text.startswith(("Neither side", "It was close all the way")):
        st_match([r"^Neither side ever got more than a slight edge\.$", r"^It was close all the way: neither side was ever more than slightly better\.$"], text)
        st_expect(arc[0] == "tightdraw", "the arc is %r" % (arc,))
        return ("S", "a draw and no phase above an edge")
    m = st_match([r"^%s was clearly better for %s moves, but it ended in a draw\.$" % (WB, NUM),
                  r"^%s held the upper hand for %s moves and still could not win\.$" % (WB, NUM)], text)
    leader, n = st_color_of(m.group(1)), st_num(m.group(2))
    st_expect(arc[0] == "wasted" and arc[1] == leader and arc[2] == n, "the arc is %r" % (arc,))
    return ("S", "a draw in which %s was clearly better for %d moves" % (m.group(1), n))


def v_result_mate(g, text, plies, before):
    m = st_match([r"^It ended in mate on move (\d+)\.$", r"^The finish was a checkmate on move (\d+)\.$"], text)
    st_expect(g.result in ("1-0", "0-1") and g.after(g.n).is_checkmate(), "the game did not end in a checkmate")
    st_expect(int(m.group(1)) == (g.n + 1) // 2, "the game has %d moves" % ((g.n + 1) // 2))
    return ("S", "checkmate on the board after move %d" % int(m.group(1)))


def v_result_lasted(g, text, plies, before):
    m = st_match([r"^It lasted %s moves?\.$" % NUM, r"^All over after %s moves?\.$" % NUM], text)
    st_expect(not g.after(g.n).is_checkmate() or g.result not in ("1-0", "0-1"), "the game ended in a checkmate")
    st_expect(st_num(m.group(1)) == (g.n + 1) // 2, "the game has %d moves" % ((g.n + 1) // 2))
    return ("S", "the game's length, no mate")


def st_hook_check(g, plies, lo, hi):
    tp = g.turning()
    st_expect(tp is not None and plies and plies[0] == tp, "the largest swing is at ply %s" % tp)
    loss = g.loss(tp)
    st_expect(lo <= loss < hi, "the largest swing lost %.1f win-percent" % loss)
    return tp, loss


def v_hook_big(g, text, plies, before):
    m = st_match([r"^One move, around move (\d+), swings the whole game, and it's easy to walk past\.$",
                  r"^Everything here turns on a single move, around move (\d+)\.$",
                  r"^There's one move in here, around move (\d+), that changes everything\. Let's find it\.$"], text)
    tp, loss = st_hook_check(g, plies, 30.0, 1e9)
    st_expect(int(m.group(1)) == g.plies[tp - 1]["moveNumber"], "the swing is at move %d" % g.plies[tp - 1]["moveNumber"])
    return ("S", "the largest swing lost %.1f win-percent at move %s" % (loss, m.group(1)))


def v_hook_close(g, text, plies, before):
    m = st_match([r"^One moment, around move (\d+), matters more than any other\.$", r"^The key moment comes around move (\d+)\.$"], text)
    tp, loss = st_hook_check(g, plies, 12.0, 30.0)
    st_expect(int(m.group(1)) == g.plies[tp - 1]["moveNumber"], "the swing is at move %d" % g.plies[tp - 1]["moveNumber"])
    return ("S", "the largest swing lost %.1f win-percent at move %s" % (loss, m.group(1)))


def v_hook_clean(g, text, plies, before):
    st_match([r"^This one is clean from both sides\. The biggest slip is small, but it is worth a look\.$"], text)
    tp, loss = st_hook_check(g, plies, 0.5, 12.0)
    return ("S", "the largest swing lost only %.1f win-percent" % loss)


def st_clause_standing(text_clause):
    if text_clause == "the position is about level":
        return 0
    m = re.match(r"^(White|Black) is (slightly better|clearly better|winning)$", text_clause)
    st_expect(m is not None, "not a standing clause: %r" % text_clause)
    level = {"slightly better": 1, "clearly better": 2, "winning": 3}[m.group(2)]
    return level if m.group(1) == "White" else -level


def v_theory_ends(g, text, plies, before):
    m = st_match([r"^Theory runs out around move (\d+), and (.+)\.$", r"^By move (\d+) the book is spent, and (.+)\.$"], text)
    book = g.book_end()
    st_expect(book >= 4 and int(m.group(1)) == (book + 1) // 2, "the last book move is ply %d" % book)
    st_expect(plies == [book], "the standing is read after ply %d" % book)
    st_expect(st_clause_standing(m.group(2)) == st_signed(g.series[book]), "the board after ply %d stands at %d" % (book, st_signed(g.series[book])))
    return ("S", "the win-percent after the last book move puts the game at %s" % m.group(2))


def v_opening_verdict(g, text, plies, before):
    m = st_match([r"^Out of the opening, (.+)\.$", r"^Coming out of the opening, (.+)\.$"], text)
    st_expect(plies and st_clause_standing(m.group(1)) == st_signed(g.series[plies[0]]), "the board after ply %s stands at %d" % (plies, st_signed(g.series[plies[0]])))
    st_expect(not (g.book_end() >= 4), "the opening has a last book move to name")
    return ("S", "the win-percent after ply %d puts the game at %s" % (plies[0], m.group(1)))


CAUSE_ERR = re.compile(r", after (White|Black)'s (blunder|mistake|missed win|slip) on move (\d+)")
CAUSE_MAT = re.compile(r", after winning (a queen|a rook|the exchange|a piece|a pawn|material)")
CAUSE_MATE = re.compile(r", with a forced mate in %s" % NUM)
PHASE_WORDS = r"(slightly better|clearly better|winning)"
PHASE_FORMS = [
    ("level_error", r"^%s gave up the advantage with a (blunder|mistake|missed win|slip) on move (\d+), and it was level again\.$" % WB),
    ("level_neutral", r"^It was level again\.$"),
    ("level_fought", r"^%s fought back to level\.$" % WB),
    ("cross_a", r"^The game turned: %s was %s now\.$" % (WB, PHASE_WORDS)),
    ("cross_b", r"^%s took over, and was %s\.$" % (WB, PHASE_WORDS)),
    ("grow1", r"^%s edged ahead\.$" % WB), ("grow1", r"^%s got a small edge\.$" % WB),
    ("grow2", r"^%s built a clear edge\.$" % WB), ("grow2", r"^%s took control\.$" % WB),
    ("grow3", r"^%s was winning now\.$" % WB), ("grow3", r"^%s's edge became a winning one\.$" % WB),
    ("shrink", r"^%s's grip loosened: only %s now\.$" % (WB, PHASE_WORDS)),
    ("shrink", r"^%s gave some ground, and was only %s now\.$" % (WB, PHASE_WORDS)),
]


def st_phase_cause(g, shift_ply, gainer):
    """The reason core states for a boundary: the loser's error in the two moves up to it, the gainer's mate, the gainer's material."""
    loser = not gainer
    window = [p for p in (shift_ply, shift_ply - 1) if 1 <= p <= g.n]
    errs = [p for p in window if g.color(p) == loser and g.cls(p) in PHASE_ERRORS]
    if errs:
        best = max(errs, key=lambda p: (g.loss(p), -p))
        return ("error", loser, ERROR_NOUN[g.cls(best)], g.plies[best - 1]["moveNumber"])
    for p in window:
        if g.color(p) == gainer and g.mover_mate_before(p) is not None:
            return ("mate", g.mover_mate_before(p))
    first = max(0, shift_ply - 2)
    start = g.before(1) if first == 0 else g.after(first)
    end = g.after(shift_ply)
    settled = settled_gain(start, end, gainer)
    if settled >= 100:
        word = gain_words(settled)
        # "the exchange" is counted on the settled board: the opponent's best take-back played first
        board = end.copy()
        if board.turn != gainer:
            takes = [m for m in board.legal_moves if board.is_capture(m)]
            if takes:
                top = max(takes, key=lambda m: see(board, m))
                if see(board, top) > 0:
                    board.push(top)
        if wins_the_exchange(start, board, gainer) and EXCHANGE_MIN <= settled <= EXCHANGE_MAX:
            word = "the exchange"
        return ("material", word or "material")
    return None


def v_phase_turn(g, text, plies, before):
    st_expect(len(plies) == 1, "a phase boundary names its ply")
    ply = plies[0]
    shifts = {b[1]: (a[0], b[0]) for a, b in zip(g.phases, g.phases[1:])}
    st_expect(ply in shifts, "the phases of the game have no boundary at ply %d" % ply)
    frm, to = shifts[ply]
    gainer = chess.WHITE if to > frm else chess.BLACK
    cause = st_phase_cause(g, ply, gainer)
    # the cause clause is read out of the sentence first, then the rest must be one of the forms
    cause_in_text, body = None, text
    for rx, build in ((CAUSE_ERR, lambda m: ("error", st_color_of(m.group(1)), m.group(2), int(m.group(3)))),
                      (CAUSE_MAT, lambda m: ("material", m.group(1))), (CAUSE_MATE, lambda m: ("mate", st_num(m.group(1))))):
        m = rx.search(body)
        if m:
            cause_in_text = build(m)
            body = body.replace(m.group(0), "", 1)
            break
    found = None
    for key, pat in PHASE_FORMS:
        m = re.match(pat, body)
        if m:
            found = (key, pat, m)
            break
    st_expect(found is not None, "not a template of its kind: %r" % text)
    key, pat, m = found
    if key == "level_error":
        # this form carries its cause in its own words: "White gave up the advantage with a blunder on move 11, ..."
        cause_in_text = ("error", st_color_of(m.group(1)), m.group(2), int(m.group(3)))
    st_expect(cause_in_text == cause, "the boards give the cause %r, the sentence says %r" % (cause, cause_in_text))
    lead, old = st_leader(to), st_leader(frm)
    groups = m.groups()
    if key.startswith("level"):
        st_expect(to == 0 and old is not None, "the boundary is not a return to level")
        by_old_error = cause is not None and cause[0] == "error" and cause[1] == old
        if key == "level_error":
            st_expect(st_color_of(groups[0]) == old and by_old_error, "the side that was ahead did not err")
        elif key == "level_fought":
            st_expect(st_color_of(groups[0]) == (not old) and not by_old_error, "the side that was behind did not fight back")
    elif key.startswith("cross"):
        who = st_color_of(groups[0])
        st_expect(old is not None and lead is not None and lead != old and who == lead, "the lead did not change hands to that side")
        st_expect(groups[1] == st_phase_words(to), "the new standing is %s" % st_phase_words(to))
    elif key.startswith("grow"):
        who = st_color_of(groups[0])
        st_expect(lead == who and abs(to) == int(key[4]) and (old is None or (old == who and abs(frm) < abs(to))), "not a growing lead for %s to level %s" % (groups[0], key[4]))
    else:
        who = st_color_of(groups[0])
        st_expect(lead == who and old == who and 0 < abs(to) < abs(frm), "not a shrinking lead for %s" % groups[0])
        st_expect(groups[1] == st_phase_words(to), "the new standing is %s" % st_phase_words(to))
    return ("S", "the win-percent series changes phase %+d -> %+d at ply %d; cause %r" % (frm, to, ply, cause))


def v_skip(g, text, plies, before):
    st_expect(len(plies) == 2, "a skip names both ends")
    a, b = plies
    skipped = list(range(a + 1, b))
    cw = cb = checks = 0
    swing = 0.0
    for k in skipped:
        board = g.before(k)
        mv = g.move(k)
        if board.is_capture(mv):
            if g.color(k) == chess.WHITE:
                cw += 1
            else:
                cb += 1
        after = g.after(k)
        if after.is_check():
            checks += 1
        swing = max(swing, abs(g.series[k] - g.series[k - 1]))
    if cw == 0 and cb == 0 and checks == 0 and swing <= 5.0:
        kind = "QUIET"
    elif cw >= 1 and cb >= 1:
        kind = "TRADES"
    else:
        kind = "PLAIN"
    target = g.plies[b - 1]["moveNumber"]
    forms = {
        "QUIET": [r"^A few quiet moves follow, no captures and no checks, and we pick it up at move (\d+)\.$",
                  r"^Nothing is taken and nobody gives check for a while, so on to move (\d+)\.$",
                  r"^Quiet moves, no captures, no checks\. We rejoin at move (\d+)\.$"],
        "TRADES": [r"^There are trades along the way, and we pick it up at move (\d+)\.$", r"^Some pieces come off, and then we're at move (\d+)\.$",
                   r"^A few trades later, it's move (\d+)\.$"],
        "PLAIN": [r"^Let's jump to move (\d+)\.$", r"^Skipping ahead to move (\d+)\.$", r"^On to move (\d+)\.$"],
    }
    m = st_match(forms[kind], text)
    st_expect(int(m.group(1)) == target, "the next told move is move %d" % target)
    return ("S", "plies %d..%d hold %d+%d captures, %d checks, a swing of %.1f: %s" % (a + 1, b - 1, cw, cb, checks, swing, kind))


def st_poss(g, color):
    return "your" if g.user is not None and color == g.user else "%s's" % st_side_name(color)


def v_small_swing(g, text, plies, before):
    st_expect(len(plies) == 1, "one ply")
    ply = plies[0]
    c = g.color(ply)
    delta = g.wp(ply, 1, c) - g.wp(ply, 0, c)
    who, poss = g.who(c), st_poss(g, c)
    favours = [r"^That helps %s\.$" % who, r"^It nudges the game %s way\.$" % poss.replace("'s", "'s"), r"^%s position improves\.$" % st_cap(poss)]
    against = [r"^That gives a little back\.$", r"^It costs %s a little\.$" % who, r"^%s position slips a little\.$" % st_cap(poss)]
    st_expect(abs(delta) >= 4.0, "the move changed the mover's win-percent by only %.1f" % delta)
    m = st_match(favours if delta >= 0 else against, text)
    return ("S", "the mover's win-percent went %s by %.1f" % ("up" if delta >= 0 else "down", abs(delta)))


def v_played_slip(g, text, plies, before):
    ply = plies[0]
    c = g.color(ply)
    clause = st_clause(g.before(ply), g.move(ply), g.user == c, c, st_recapture(g, ply))
    st_match([r"^%s, a small slip\.$" % re.escape(clause), r"^%s, which is slightly off\.$" % re.escape(clause),
              r"^%s, but not the most precise\.$" % re.escape(clause)], st_cap(text) if False else text)
    st_expect(g.cls(ply) == "INACCURACY", "the move is a %s, not an inaccuracy" % g.cls(ply))
    return ("S", "an inaccuracy, said as played")


def st_best_replay(g, ply, plies_limit=4):
    """The mover's best net material along the first plies of the engine's best line, settled, and whether it mates."""
    board = g.before(ply)
    start = board.copy()
    mover = board.turn
    best = 0
    for u in g.best_pv(ply)[:plies_limit]:
        mv = chess.Move.from_uci(u)
        if mv not in board.legal_moves:
            break
        board.push(mv)
        best = max(best, settled_gain(start, board, mover))
        if board.is_checkmate():
            break
    return best


def v_better(g, text, plies, before):
    ply = plies[0]
    board = g.before(ply)
    pv = g.best_pv(ply)
    st_expect(pv and pv[0] != g.plies[ply - 1]["uci"], "the engine's first move is the move played")
    move = chess.Move.from_uci(pv[0])
    phrase = st_phrase(board, move)
    cap = st_cap(phrase)
    mate = g.mover_mate_before(ply)
    gain = st_best_replay(g, ply)
    kind = "mates" if mate is not None else ("wins" if gain >= 100 else "plain")
    forms = {
        "plain": [r"^%s was the move\.$" % re.escape(cap), r"^The move was %s\.$" % re.escape(phrase), r"^Instead, %s\.$" % re.escape(phrase)],
        "mates": [r"^%s was the move: mate in %s\.$" % (re.escape(cap), NUM), r"^The move was %s, forcing mate in %s\.$" % (re.escape(phrase), NUM),
                  r"^Instead, %s, and it is mate in %s\.$" % (re.escape(phrase), NUM)],
        "wins": [r"^%s was the move, winning (.+)\.$" % re.escape(cap), r"^The move was %s, which wins (.+)\.$" % re.escape(phrase),
                 r"^Instead, %s wins (.+)\.$" % re.escape(phrase)],
    }
    m = st_match(forms[kind], text)
    if kind == "mates":
        st_expect(st_num(m.group(1)) == mate, "the engine's mate is in %d" % mate)
    if kind == "wins":
        st_expect(m.group(1) == (gain_words(gain) or "material"), "the line nets %d cp: %s" % (gain, gain_words(gain) or "material"))
    return ("S", "the engine's first move, result %s (mate %s, line nets %d cp)" % (kind, mate, gain))


def v_allows_mate(g, text, plies, before):
    ply = plies[0]
    m = st_match([r"^That allows a forced mate in %s\.$" % NUM, r"^It walks into a forced mate in %s\.$" % NUM,
                  r"^After that, there is a forced mate in %s\.$" % NUM], text)
    mate = g.opponent_mate_after(ply)
    st_expect(mate is not None and st_num(m.group(1)) == mate, "the engine's mate for the opponent after the move is %s" % mate)
    return ("S", "engine: mate in %d for the opponent after the move" % mate)


def st_allowed_reply(g, ply):
    """core's allowsOf for a reply: the engine's best answer to the move, a capture that nets at least a pawn by static exchange."""
    if ply >= len(g.evals):
        return None
    lines = g.evals[ply]["lines"]
    pv = min(lines, key=lambda l: l["multiPv"])["pvUci"] if lines else []
    if not pv:
        return None
    after = g.after(ply)
    mv = chess.Move.from_uci(pv[0])
    if mv not in after.legal_moves or not after.is_capture(mv):
        return None
    played = g.move(ply)
    if g.before(ply).is_capture(played) and played.to_square == mv.to_square:
        return None
    gain = see(after, mv)
    if gain < 100:
        return None
    return mv, gain


def v_played_lets_in(g, text, plies, before):
    ply = plies[0]
    c = g.color(ply)
    clause = st_clause(g.before(ply), g.move(ply), g.user == c, c, st_recapture(g, ply))
    r = st_allowed_reply(g, ply)
    st_expect(r is not None, "the engine's best answer is not a capture that nets a pawn or more")
    mv, gain = r
    after = g.after(ply)
    phrase = st_phrase(after, mv)
    wins = (", winning %s" % gain_words(gain)) if gain_words(gain) else ""
    opp = st_side_name(not c)
    expected = ["%s, and now %s has %s%s." % (clause, opp, phrase, wins), "%s, but %s has the reply %s%s." % (clause, opp, phrase, wins),
                "%s, and %s can answer with %s%s." % (clause, opp, phrase, wins)]
    st_expect(st_cap(text) in [st_cap(e) for e in expected] or text in expected, "the sentence is not what the boards say: %r" % expected[0])
    return ("S", "the engine's best answer %s nets %d cp by static exchange" % (phrase, gain))


def v_mate_announce(g, text, plies, before):
    ply = plies[0]
    m = st_match([r"^This is a forced mate in %s\.$" % NUM, r"^From here it is mate in %s, by force\.$" % NUM, r"^Mate in %s, and nothing can stop it\.$" % NUM], text)
    mate = g.mover_mate_before(ply)
    st_expect(mate is not None and st_num(m.group(1)) == mate, "the engine's mate for the mover is %s" % mate)
    return ("S", "engine: mate in %d for the mover, counting this move" % mate)


def v_mate_step(g, text, plies, before):
    ply = plies[0]
    phrase = st_phrase(g.before(ply), g.move(ply))
    st_match([r"^%s\.$" % re.escape(st_cap(phrase)), r"^Then %s\.$" % re.escape(phrase)], text)
    st_expect(g.mover_mate_before(ply) is not None, "the mover has no forced mate here")
    c = g.color(ply)
    # a sequence is under way when the aim was announced, or a step said, by this side, or the move before it let this side's mate in
    prior = [b for b in before if b["plies"] and 0 < ply - b["plies"][0] <= 2 and
             ((b["id"] in ("MateAnnounce", "MateStep") and g.color(b["plies"][0]) == c) or (b["id"] == "AllowsMate" and g.color(b["plies"][0]) != c))]
    st_expect(prior, "no mating sequence of this side was under way")
    return ("S", "the played move, in a mating sequence")


def v_mate_finish(g, text, plies, before):
    ply = plies[0]
    c = g.color(ply)
    st_expect(g.after(ply).is_checkmate(), "the move is not checkmate")
    if g.user is not None and c == g.user:
        st_match([r"^You win it!$", r"^Game over, and you win!$"], text)
    else:
        m = st_match([r"^Game over\. %s wins!$" % WB, r"^And that's the game: %s wins!$" % WB, r"^%s wins it!$" % WB], text)
        st_expect(st_color_of(m.group(1)) == c, "the mate was delivered by %s" % st_side_name(c))
    st_expect((g.result == "1-0") == (c == chess.WHITE) and g.result in ("1-0", "0-1"), "the result is %s" % g.result)
    return ("S", "checkmate on the board; the result agrees")


def st_band_before(g, ply, color):
    return st_band(g.wp(ply, 0, color))


def st_band_after(g, ply, color):
    return st_band(g.wp(ply, 1, color))


def v_consequence(g, text, plies, before):
    ply = plies[0]
    c = g.color(ply)
    who, cwho = g.who(c), st_cap(g.who(c))
    be, was, have = ("are", "were", "have") if who == "you" else ("is", "was", "has")
    b0, b1 = st_band_before(g, ply, c), st_band_after(g, ply, c)
    if b0 == b1:
        m = st_match([r"^%s %s still %s, but that gave away (.+)\.$" % (cwho, be, BAND)], text)
        st_expect(m.group(1) == b1, "the mover is %s after the move" % b1)
        st_expect(m.group(2) == st_severity(g.loss(ply)), "the move lost %.1f win-percent: %s" % (g.loss(ply), st_severity(g.loss(ply))))
        return ("S", "the band stayed %s; the loss was %.1f" % (b1, g.loss(ply)))
    m = st_match([r"^%s %s %s\. Now %s %s %s\.$" % (cwho, was, BAND, who, be, BAND), r"^That takes %s from %s to %s\.$" % (re.escape(who), BAND, BAND),
                  r"^%s %s slipped from %s to %s\.$" % (cwho, have, BAND, BAND)], text)
    words = [x for x in m.groups() if x in dict.fromkeys(["decisively winning", "winning", "clearly better", "slightly better", "about level",
                                                         "slightly worse", "clearly worse", "losing", "decisively lost"])]
    st_expect(words == [b0, b1], "the mover went from %s to %s, not %s" % (b0, b1, words))
    return ("S", "the mover's win-percent bands: %s -> %s" % (b0, b1))


def v_turning_lead(g, text, plies, before):
    ply = plies[0]
    tp = g.turning()
    st_expect(tp == ply, "the largest swing is at ply %s" % tp)
    if g.loss(ply) >= 12.0:
        st_match([r"^That was the turning point\.$", r"^That's where the game turned\.$", r"^And that was the turning point\.$"], text)
    else:
        st_match([r"^That was the biggest slip of the game\.$", r"^If there is a turning point, that is it\.$"], text)
    return ("S", "the largest swing lost %.1f win-percent" % g.loss(ply))


def v_turning_swing(g, text, plies, before):
    ply = plies[0]
    st_expect(g.turning() == ply, "the largest swing is at ply %s" % g.turning())
    m = st_match([r"^No other move in this game cost as much\.$", r"^Nothing else in this game swings the evaluation like move (\d+)\.$"], text)
    if m.re.groups and m.group(1):
        st_expect(int(m.group(1)) == g.plies[ply - 1]["moveNumber"], "the swing is at move %d" % g.plies[ply - 1]["moveNumber"])
    return ("S", "no other move lost as much")


def v_turning_after(g, text, plies, before):
    ply = plies[0]
    after = st_aftermath(g.phases, ply, g.color(ply))
    st_expect(after is not None, "the phases prove nothing about what followed")
    if after[0] == "neverback":
        m = st_match([r"^From there, %s never gave the lead back\.$" % WB, r"^From then on, %s stayed ahead\.$" % WB], text)
        st_expect(st_color_of(m.group(1)) == after[1], "the side ahead is %s" % st_side_name(after[1]))
        return ("S", "every phase from the move on is led by %s" % st_side_name(after[1]))
    m = st_match([r"^It wasn't over: %s was (ahead again|back to level) by move (\d+)\.$" % WB], text)
    st_expect(st_color_of(m.group(1)) == after[1] and int(m.group(3)) == after[2], "the phases say %r" % (after,))
    st_expect((m.group(2) == "ahead again") == (st_leader(after[3]) == after[1]), "the standing then was %d" % after[3])
    return ("S", "the phases return to %d by move %d" % (after[3], after[2]))


def v_payoff_standing(g, text, plies, before):
    ply = plies[0]
    c = g.color(ply)
    who = g.who(c)
    be = "are" if who == "you" else "is"
    m = st_match([r"^The line ends there, with %s %s\.$" % (re.escape(who), BAND), r"^That is as far as it goes, and %s %s %s\.$" % (re.escape(who), be, BAND)], text)
    st_expect(m.groups()[-1] == st_band_before(g, ply, c), "the engine's evaluation of best play is %s" % st_band_before(g, ply, c))
    return ("S", "the engine's evaluation of best play from the position before the move")


def v_pivot_question(g, text, plies, before):
    ply = plies[0]
    m = st_match([r"^Hold on, back up\. Can you see what %s missed\?$" % WB, r"^Let's rewind\. Do you see what %s missed here\?$" % WB,
                  r"^Freeze it there\. Can you see what %s missed\?$" % WB], text)
    st_expect(st_color_of(m.group(1)) == g.color(ply), "the move was played by %s" % st_side_name(g.color(ply)))
    st_expect(g.plies[ply - 1]["missed"] is not None, "no missed motif is recorded for this move")
    pv = g.best_pv(ply)
    st_expect(pv and pv[0] != g.plies[ply - 1]["uci"], "the engine's first move is the move played")
    return ("S", "the engine preferred another move that carried a %s" % g.plies[ply - 1]["missed"])


def st_ending_words(g, winner):
    return None


def v_outro_ending(g, text, plies, before):
    full = (g.n + 1) // 2
    mated = g.after(g.n).is_checkmate()
    count = r"(\d+ moves|one move)"
    m = st_match([r"^%s won by checkmate on move (\d+)\.$" % WB, r"^It ended in checkmate: %s wins on move (\d+)\.$" % WB,
                  r"^%s won after %s\.$" % (WB, count), r"^%s took the win after %s\.$" % (WB, count),
                  r"^It ended in a draw after %s\.$" % count, r"^The game was drawn after %s\.$" % count, r"^The game stops after %s\.$" % count], text)
    if "checkmate" in text:
        st_expect(mated and g.result in ("1-0", "0-1"), "the game did not end in checkmate")
        st_expect(int(m.group(2)) == full, "the game has %d moves" % full)
        st_expect(st_color_of(m.group(1)) == (chess.WHITE if g.result == "1-0" else chess.BLACK), "the winner is not that side")
    elif "won after" in text or "took the win" in text:
        st_expect(g.result in ("1-0", "0-1") and not mated, "the result is %s" % g.result)
        st_expect(st_color_of(m.group(1)) == (chess.WHITE if g.result == "1-0" else chess.BLACK), "the winner is not that side")
        st_expect(m.group(2) in ("%d moves" % full, "one move"), "the game has %d moves" % full)
    elif "draw" in text:
        st_expect(g.result == "1/2-1/2", "the result is %s" % g.result)
        st_expect(m.group(1) in ("%d moves" % full, "one move"), "the game has %d moves" % full)
    else:
        st_expect(g.result not in ("1-0", "0-1", "1/2-1/2"), "the game has a result: %s" % g.result)
        st_expect(m.group(1) in ("%d moves" % full, "one move"), "the game has %d moves" % full)
    return ("S", "the result tag %s and the last move" % g.result)


def v_outro_decider(g, text, plies, before):
    tp = g.turning()
    st_expect(tp is not None and g.loss(tp) >= 12.0, "no move lost 12 win-percent")
    c = g.color(tp)
    b0, b1 = st_band_before(g, tp, c), st_band_after(g, tp, c)
    st_expect(b0 != b1, "the bands before and after are the same")
    noun = {"BLUNDER": "blunder", "MISTAKE": "mistake", "MISS": "missed win"}.get(g.cls(tp), "slip")
    poss, who = st_poss(g, c), g.who(c)
    m = st_match([r"^The decisive moment was %s %s on move (\d+), which took %s from %s to %s\.$" % (re.escape(poss), noun, re.escape(who), BAND, BAND),
                  r"^It all came down to %s %s on move (\d+): %s went from %s to %s\.$" % (re.escape(poss), noun, re.escape(st_cap(who)), BAND, BAND)], text)
    st_expect(int(m.group(1)) == g.plies[tp - 1]["moveNumber"], "the largest swing is at move %d" % g.plies[tp - 1]["moveNumber"])
    st_expect([m.group(2), m.group(3)] == [b0, b1], "the mover went from %s to %s" % (b0, b1))
    return ("S", "the largest swing: %s -> %s, lost %.1f" % (b0, b1, g.loss(tp)))


def v_outro_no_decider(g, text, plies, before):
    st_match([r"^There was no single decisive mistake\.$", r"^Neither side made one decisive mistake\.$"], text)
    tp = g.turning()
    if tp is not None:
        c = g.color(tp)
        st_expect(g.loss(tp) < 12.0 or st_band_before(g, tp, c) == st_band_after(g, tp, c), "the largest swing lost %.1f and changed the band" % g.loss(tp))
    return ("S", "no move lost 12 win-percent and changed a band")


def st_lesson_focus(g):
    if g.user is not None:
        return g.user
    tp = g.turning()
    return g.color(tp) if tp is not None else chess.WHITE


def st_worst(g, focus):
    cands = [p for p in g.plies if (chess.WHITE if p["color"] == "WHITE" else chess.BLACK) == focus and p["cls"] in MISTAKE_CLASSES
             and (g.loss(p["ply"]) >= 10.0 or p["missed"] is not None)]
    if not cands:
        return None
    return max(cands, key=lambda p: (g.loss(p["ply"]), -p["ply"]))["ply"]


def v_takeaway_missed(g, text, plies, before):
    worst = st_worst(g, st_lesson_focus(g))
    st_expect(worst is not None and plies == [worst], "the focus player's worst error is at ply %s" % worst)
    p = g.plies[worst - 1]
    st_expect(p["missed"] is not None, "no missed motif is recorded for this move")
    who = g.who(g.color(worst))
    name = TACTIC_NAMES[p["missed"]]
    article = "an" if name[0] in "aeiou" or name.startswith("x-") else "a"
    best = st_phrase(g.before(worst), chess.Move.from_uci(p["best"])) if p["best"] else None
    pv = g.best_pv(worst)
    st_expect(pv and p["best"] == pv[0] and pv[0] != p["uci"], "the engine's first move is the move played")
    tail = (" %s was the move." % st_cap(best)) if best else ""
    first = "%s missed %s %s" % (who, article, name)
    expected = ["At move %d, %s.%s" % (p["moveNumber"], first, tail), "Look at move %d. %s.%s" % (p["moveNumber"], st_cap(first), tail)]
    st_expect(text in expected, "expected one of %r" % expected)
    return ("S", "the worst error of the focus side, with its missed %s" % name)


def v_takeaway_allowed(g, text, plies, before):
    worst = st_worst(g, st_lesson_focus(g))
    st_expect(worst is not None and plies == [worst], "the focus player's worst error is at ply %s" % worst)
    p = g.plies[worst - 1]
    st_expect(p["missed"] is None, "the move missed a motif, which the lesson would name first")
    c = g.color(worst)
    opp = st_side_name(not c)
    mate = g.opponent_mate_after(worst)
    reply = None if mate is not None else st_allowed_reply(g, worst)
    st_expect(mate is not None or reply is not None, "the move let nothing in")
    tail = ""
    if mate is not None:
        tail = " It allowed a forced mate in %s." % st_number_word(mate)
    else:
        tail = " %s could answer with %s." % (opp, st_phrase(g.after(worst), reply[0]))
    expected = ["Move %d let %s in.%s" % (p["moveNumber"], opp, tail), "At move %d, the door opened for %s.%s" % (p["moveNumber"], opp, tail)]
    st_expect(text in expected, "expected one of %r" % expected)
    return ("S", "the worst error of the focus side, and what it let in")


def v_takeaway_costly(g, text, plies, before):
    worst = st_worst(g, st_lesson_focus(g))
    st_expect(worst is not None and plies == [worst], "the focus player's worst error is at ply %s" % worst)
    p = g.plies[worst - 1]
    c = g.color(worst)
    st_expect(p["missed"] is None and g.opponent_mate_after(worst) is None and st_allowed_reply(g, worst) is None, "the move missed a motif or let something in, which the lesson would name first")
    b0, b1 = st_band_before(g, worst, c), st_band_after(g, worst, c)
    bands = ", from %s to %s" % (b0, b1) if b0 != b1 else ""
    sev = st_severity(g.loss(worst))
    expected = ["Move %d gave up %s%s." % (p["moveNumber"], sev, bands), "At move %d, %s gave up %s%s." % (p["moveNumber"], g.who(c), sev, bands)]
    st_expect(text in expected, "expected one of %r" % expected)
    return ("S", "the worst error of the focus side: %s%s" % (sev, bands))


def v_takeaway_clean(g, text, plies, before):
    st_match([r"^Neither side made a decisive mistake, so the habit to keep is simple: check what every move allows\.$"], text)
    st_expect(st_worst(g, st_lesson_focus(g)) is None, "the focus player made a mistake worth a lesson")
    return ("S", "no error of 10 win-percent or with a missed motif")


def v_habit_allowed(g, text, plies, before):
    st_match([r"^Before you commit, ask one question: what does this let them do next\?$"], text)
    worst = st_worst(g, st_lesson_focus(g))
    st_expect(worst is not None and plies == [worst], "the focus player's worst error is at ply %s" % worst)
    st_expect(g.opponent_mate_after(worst) is not None or st_allowed_reply(g, worst) is not None, "the move let nothing in")
    return ("S", "the lesson's first sentence was about what the move let in")


def v_habit_standing(g, text, plies, before):
    worst = st_worst(g, st_lesson_focus(g))
    st_expect(worst is not None and plies == [worst], "the focus player's worst error is at ply %s" % worst)
    c = g.color(worst)
    b0 = st_band_before(g, worst, c)
    if b0 in ("decisively winning", "winning", "clearly better"):
        want = "When you're ahead, slow down. Check every capture and every check before you move."
    elif b0 in ("slightly better", "about level"):
        want = "In a level position, every move counts. Check every capture and every check before you move."
    else:
        want = "Even when you're worse, don't rush. Check every capture and every check before you move."
    st_expect(text == want, "the focus player stood %s before the move: %r" % (b0, want))
    return ("S", "the habit fits the standing before the move: %s" % b0)


def v_lesson_mated(g, text, plies, before):
    worst = st_worst(g, st_lesson_focus(g))
    st_expect(worst is not None and plies == [worst], "the focus player's worst error is at ply %s" % worst)
    c = g.color(worst)
    st_expect(g.after(g.n).is_checkmate() and g.color(g.n) != c, "the focus side was not mated at the end")
    st_expect(worst == g.turning(), "the worst error is not the largest swing")
    move = g.plies[worst - 1]["moveNumber"]
    who = st_cap(g.who(c))
    want = "%s got mated at the end, but the mate wasn't the mistake. Move %d was. Fix it and the mate never happens." % (who, move)
    st_expect(text == want, "expected %r" % want)
    return ("S", "mated at the end after the game's largest swing")


ST_VERIFIERS = {
    "StakesComeback": v_stakes, "StakesTookOver": v_stakes, "StakesTightDraw": v_stakes, "StakesWastedLead": v_stakes,
    "ResultEndsInMate": v_result_mate, "ResultLasted": v_result_lasted,
    "HookBigTurn": v_hook_big, "HookClose": v_hook_close, "HookClean": v_hook_clean,
    "TheoryEnds": v_theory_ends, "OpeningVerdict": v_opening_verdict, "PhaseTurn": v_phase_turn, "SkipAhead": v_skip,
    "SmallSwing": v_small_swing, "PlayedSlip": v_played_slip, "BetterHere": v_better, "AllowsMate": v_allows_mate,
    "PlayedThenReply": v_played_lets_in, "MateAnnounce": v_mate_announce, "MateStep": v_mate_step, "MateFinish": v_mate_finish,
    "ConsequenceChanged": v_consequence, "ConsequenceUnchanged": v_consequence,
    "TurningPointLead": v_turning_lead, "TurningPointSwing": v_turning_swing, "TurningAftermath": v_turning_after,
    "PayoffStanding": v_payoff_standing, "PivotQuestion": v_pivot_question,
    "OutroEnding": v_outro_ending, "OutroDecider": v_outro_decider, "OutroNoDecider": v_outro_no_decider,
    "TakeawayMissed": v_takeaway_missed, "TakeawayAllowed": v_takeaway_allowed, "TakeawayCostly": v_takeaway_costly,
    "TakeawayClean": v_takeaway_clean, "TakeawayHabitAllowed": v_habit_allowed, "TakeawayHabitStanding": v_habit_standing,
    "LessonMatedButNotTheMistake": v_lesson_mated,
}


def story_load(path):
    games, parts = {}, defaultdict(list)
    for line in open(path, encoding="utf-8"):
        if not line.strip():
            continue
        rec = json.loads(line)
        key = (rec["game"], rec["side"])
        if rec["rec"] == "game":
            games[key] = StoryGame(rec)
        else:
            parts[key].append(rec)
    return games, parts


def story_audit(games, parts):
    """[(game, side, part, verdict, note)] for every part of every game and side."""
    rows = []
    for key, plist in parts.items():
        g = games[key]
        for i, part in enumerate(plist):
            verdict, note = st_verify(g, part, plist[:i])[0]
            rows.append((key, part, verdict, note))
    return rows


def main_story(path):
    games, parts = story_load(path)
    rows = story_audit(games, parts)
    counts = Counter(v for _, _, v, _ in rows)
    per_id = defaultdict(Counter)
    for _, part, v, _ in rows:
        per_id[part["id"]][v] += 1
    print("### The narrated story (ANALYSIS_SPEC 9.9)\n")
    print("%d story sentences in %d scripts (the four recorded games and shortened or re-resulted variants of them, each for no side, White and Black): %d supported, %d WRONG.\n" % (len(rows), len(games), counts["S"], counts["W"]))
    print("| Sentence kind | Sentences | Supported | WRONG |")
    print("|---|---|---|---|")
    for pid, c in sorted(per_id.items()):
        print("| %s | %d | %d | %d |" % (pid, sum(c.values()), c["S"], c["W"]))
    print("\n### Every WRONG claim\n")
    wrong = [r for r in rows if r[2] == "W"]
    for (game, side), part, _, note in wrong:
        print("- %s (%s) %s plies %s: \"%s\" - %s" % (game, side, part["id"], part["plies"], part["text"], note))
    if not wrong:
        print("(none)")
    missing = sorted(set(ST_VERIFIERS) - set(per_id))
    print("\nSentence kinds with a verifier that no recorded script used: %s" % (", ".join(missing) or "(none)"))
    return 1 if wrong else 0


# --- negative controls: break one claim of one sentence at a time -----------------------------------------------------

def _p(part, **changes):
    out = dict(part)
    out.update(changes)
    return out


def _swap_sides(text):
    if not re.search(r"White|Black", text):
        return None
    return re.sub(r"White|Black", lambda m: "Black" if m.group(0) == "White" else "White", text)


def _num_up(text, by=1):
    m = re.search(r"\b(\d+)\b", text)
    return text[:m.start()] + str(int(m.group(1)) + by) + text[m.end():] if m else None


def _word_up(token):
    if token in NUMBER_WORDS:
        return NUMBER_WORDS[min(NUMBER_WORDS.index(token) + 1, 10)] if token != "ten" else "11"
    return str(int(token) + 1)


def _mate_up(text):
    m = re.search(r"(mate in )(\w+)", text, flags=re.I)
    return text[:m.start(2)] + _word_up(m.group(2).lower()) + text[m.end(2):] if m else None


def _swap_two_bands(text):
    spans = [m.span() for m in re.finditer(BAND, text)]
    if len(spans) < 2:
        return None
    (a0, a1), (b0, b1) = spans[0], spans[1]
    first, second = text[a0:a1], text[b0:b1]
    if first == second:
        return None
    return text[:a0] + second + text[a1:b0] + first + text[b1:]


def _first_band_to(text, band):
    m = re.search(BAND, text)
    return text[:m.start()] + band + text[m.end():] if m and m.group(0) != band else None


def _ply_of_class(g, classes, missed=None):
    for q in g.plies:
        if q["cls"] in classes and q["ply"] > 1 and (missed is None or (q["missed"] is None) == missed):
            return q["ply"]
    return None


def _when(cond, part, **changes):
    return _p(part, **changes) if cond else None


STORY_MUTATIONS = [
    # (name, the sentence kinds it applies to, how the part is broken: part, game -> part or None)
    ("the stakes: the other side was clearly better for more moves than it was", ("StakesComeback",),
     lambda p, g: _p(p, text=_num_up(p["text"], 4))),
    ("the stakes: the winner and the other side swapped", ("StakesComeback", "StakesTookOver"),
     lambda p, g: _when(_swap_sides(p["text"]), p, text=_swap_sides(p["text"]))),
    ("the stakes: 'level until move N' at the wrong move", ("StakesTookOver",),
     lambda p, g: _when("until move" in p["text"], p, text=_num_up(p["text"], 2))),
    ("the stakes: a draw story told of a decisive game", ("StakesComeback", "StakesTookOver"),
     lambda p, g: _p(p, id="StakesTightDraw", text="Neither side ever got more than a slight edge.")),
    ("the result: 'ended in mate' said of a game that did not", ("ResultLasted",),
     lambda p, g: _p(p, id="ResultEndsInMate", text="It ended in mate on move %d." % ((g.n + 1) // 2))),
    ("the result: a mate on the wrong move", ("ResultEndsInMate",), lambda p, g: _p(p, text=_num_up(p["text"], 1))),
    ("the hook: the largest swing at the wrong move", ("HookBigTurn", "HookClose"), lambda p, g: _p(p, text=_num_up(p["text"], 2))),
    ("the hook: 'swings the whole game' said of a swing that lost under 30", ("HookClose",),
     lambda p, g: _p(p, id="HookBigTurn", text="Everything here turns on a single move, around move %s." % re.search(r"(\d+)", p["text"]).group(1))),
    ("the opening: the book left the game winning for Black", ("TheoryEnds", "OpeningVerdict"),
     lambda p, g: _when("Black is winning" not in p["text"], p, text=re.sub(r"the position is about level|(White|Black) is (slightly better|clearly better|winning)", "Black is winning", p["text"], count=1))),
    ("the opening: theory ending at the wrong move", ("TheoryEnds",), lambda p, g: _p(p, text=_num_up(p["text"], 1))),
    ("a phase turn told with the wrong standing", ("PhaseTurn",),
     lambda p, g: _when(re.search(r"built a clear edge|took control|edged ahead|got a small edge", p["text"]), p,
                        text=re.sub(r"built a clear edge|took control|edged ahead|got a small edge", "was winning now", p["text"], count=1))),
    ("a phase turn credited to the other side", ("PhaseTurn",), lambda p, g: _when(_swap_sides(p["text"]), p, text=_swap_sides(p["text"]))),
    ("a phase turn with an error blamed on the other side", ("PhaseTurn",),
     lambda p, g: _when(re.search(r"after (White|Black)'s", p["text"]), p,
                        text=re.sub(r"after (White|Black)'s", lambda m: "after %s's" % ("Black" if m.group(1) == "White" else "White"), p["text"], count=1))),
    ("a phase turn with an error on the wrong move", ("PhaseTurn",),
     lambda p, g: _when("on move" in p["text"], p, text=re.sub(r"on move (\d+)", lambda m: "on move %d" % (int(m.group(1)) + 1), p["text"], count=1))),
    ("a phase turn told at a move where the game did not change phase", ("PhaseTurn",), lambda p, g: _p(p, plies=[p["plies"][0] + 1])),
    ("a phase turn that invents a forced mate as its cause", ("PhaseTurn",),
     lambda p, g: _when(" after " not in p["text"] and " with a forced" not in p["text"], p, text=p["text"].replace(".", ", with a forced mate in three.", 1))),
    ("a skipped stretch called quiet that holds captures", ("SkipAhead",),
     lambda p, g: _when("quiet" not in p["text"].lower() and "no captures" not in p["text"], p,
                        text="A few quiet moves follow, no captures and no checks, and we pick it up at move %s." % re.search(r"(\d+)", p["text"]).group(1))),
    ("a skipped stretch called trades that holds none", ("SkipAhead",),
     lambda p, g: _when("trades" not in p["text"] and "pieces come off" not in p["text"], p,
                        text="There are trades along the way, and we pick it up at move %s." % re.search(r"(\d+)", p["text"]).group(1))),
    ("a skip ahead to the wrong move", ("SkipAhead",), lambda p, g: _p(p, text=_num_up(p["text"], 1))),
    ("a small swing in the wrong direction", ("SmallSwing",),
     lambda p, g: _p(p, text="That gives a little back." if re.search(r"helps|nudges|improves", p["text"]) else "That helps %s." % g.who(g.color(p["plies"][0])))),
    ("an inaccuracy verdict on a move that was not one", ("PlayedSlip",), lambda p, g: _p(p, plies=[_ply_of_class(g, ("BEST", "GOOD", "EXCELLENT"))])),
    ("a better move that is the wrong move", ("BetterHere",),
     lambda p, g: _when(re.search(r" to ([a-h]) (one|two|three|four|five|six|seven|eight)", p["text"]), p,
                        text=re.sub(r" to ([a-h]) (one|two|three|four|five|six|seven|eight)", lambda m: " to %s %s" % ("a" if m.group(1) != "a" else "h", m.group(2)), p["text"], count=1))),
    ("a better move said to win material it does not win", ("BetterHere",),
     lambda p, g: _when(p["text"].endswith(" was the move."), p, text=p["text"].replace(" was the move.", " was the move, winning a rook."))),
    ("a better move said to mate one move too late", ("BetterHere",), lambda p, g: _when("mate in" in p["text"], p, text=_mate_up(p["text"]))),
    ("a mate said to be allowed that the engine does not see", ("AllowsMate",), lambda p, g: _p(p, plies=[1])),
    ("a mate allowed one move too late", ("AllowsMate",), lambda p, g: _p(p, text=_mate_up(p["text"]))),
    ("a reply that is not the engine's best answer", ("PlayedThenReply",),
     lambda p, g: _when(re.search(r"on ([a-h]) (one|two|three|four|five|six|seven|eight)", p["text"]), p,
                        text=re.sub(r"on ([a-h]) (one|two|three|four|five|six|seven|eight)", lambda m: "on %s %s" % ("h" if m.group(1) != "h" else "a", m.group(2)), p["text"], count=1))),
    ("a reply said to win a queen when it wins less", ("PlayedThenReply",),
     lambda p, g: _when("winning" in p["text"] and "winning a queen" not in p["text"], p,
                        text=re.sub(r"winning (a \w+|the exchange|material)", "winning a queen", p["text"], count=1))),
    ("a forced mate announced one move too long", ("MateAnnounce",), lambda p, g: _p(p, text=_mate_up(p["text"]))),
    ("a mating step that is not part of a sequence", ("MateStep",), lambda p, g: _p(p, plies=[2])),
    ("the finish said of a move that is not mate", ("MateFinish",), lambda p, g: _p(p, plies=[p["plies"][0] - 2])),
    ("the finish credited to the other side", ("MateFinish",), lambda p, g: _when(_swap_sides(p["text"]), p, text=_swap_sides(p["text"]))),
    ("a band change told backwards", ("ConsequenceChanged",), lambda p, g: _when(_swap_two_bands(p["text"]), p, text=_swap_two_bands(p["text"]))),
    ("'still' said of a side that changed band", ("ConsequenceUnchanged",), lambda p, g: _p(p, plies=[p["plies"][0] + 1])),
    ("a payoff standing in the wrong band", ("PayoffStanding",), lambda p, g: _p(p, text=_first_band_to(p["text"], "decisively lost"))),
    ("the turning point on the wrong move", ("TurningPointLead", "TurningPointSwing", "TurningAftermath"), lambda p, g: _p(p, plies=[p["plies"][0] + 1])),
    ("the turning point called significant when it was a small slip", ("TurningPointLead",),
     lambda p, g: _when("slip" in p["text"] or "If there is" in p["text"], p, text="That was the turning point.")),
    ("what followed the turning point given to the wrong side", ("TurningAftermath",), lambda p, g: _when(_swap_sides(p["text"]), p, text=_swap_sides(p["text"]))),
    ("a pivot question about a move that missed nothing", ("PivotQuestion",), lambda p, g: _p(p, plies=[_ply_of_class(g, ("BEST", "GOOD", "EXCELLENT"), missed=True)])),
    ("the ending with the wrong move count", ("OutroEnding",), lambda p, g: _p(p, text=_num_up(p["text"], 1))),
    ("the ending crediting the wrong side", ("OutroEnding",), lambda p, g: _when(_swap_sides(p["text"]), p, text=_swap_sides(p["text"]))),
    ("the decisive moment at the wrong move", ("OutroDecider",),
     lambda p, g: _p(p, text=re.sub(r"on move (\d+)", lambda m: "on move %d" % (int(m.group(1)) + 1), p["text"], count=1))),
    ("the decisive moment with the wrong bands", ("OutroDecider",), lambda p, g: _when(_swap_two_bands(p["text"]), p, text=_swap_two_bands(p["text"]))),
    ("'no decisive mistake' said of a game with one", ("OutroDecider",), lambda p, g: _p(p, id="OutroNoDecider", text="There was no single decisive mistake.")),
    ("the takeaway about the wrong move", ("TakeawayMissed", "TakeawayAllowed", "TakeawayCostly"),
     lambda p, g: _p(p, text=re.sub(r"[Mm]ove (\d+)", lambda m: "%s %d" % (m.group(0).split()[0], int(m.group(1)) + 1), p["text"], count=1))),
    ("the takeaway naming a motif that was not missed", ("TakeawayMissed",),
     lambda p, g: _when("a fork" not in p["text"], p, text=re.sub(r"missed an? [\w-]+( [\w-]+)?", "missed a fork", p["text"], count=1))),
    ("the habit for the wrong standing", ("TakeawayHabitStanding",),
     lambda p, g: _p(p, text="When you're ahead, slow down. Check every capture and every check before you move." if "ahead" not in p["text"]
                     else "Even when you're worse, don't rush. Check every capture and every check before you move.")),
    ("a sentence no template knows", ("PhaseTurn", "BetterHere", "OutroEnding", "TheoryEnds", "SkipAhead"),
     lambda p, g: _p(p, text="The position is extremely sharp and anything can happen.")),
]


def main_mutate_story(path):
    games, parts = story_load(path)
    print("\n### Negative controls: the story sentences (NS)\n")
    print("| Mutation | Sentence | Flagged |")
    print("|---|---|---|")
    missed = 0
    for name, kinds, mutate in STORY_MUTATIONS:
        done = False
        for key in sorted(parts, key=lambda k: (k[1] is not None, str(k))):
            g, plist = games[key], parts[key]
            for i, part in enumerate(plist):
                if part["id"] not in kinds or st_verify(g, part, plist[:i])[0][0] != "S":
                    continue
                try:
                    broken = mutate(part, g)
                except (StopIteration, AttributeError, IndexError, TypeError):
                    broken = None
                if broken is None or broken.get("text") is None or broken["plies"] == [None]:
                    continue
                if broken["text"] == part["text"] and broken["plies"] == part["plies"] and broken["id"] == part["id"]:
                    continue
                verdict, note = st_verify(g, broken, plist[:i])[0]
                flagged = verdict == "W"
                shown = broken["text"].replace("|", "\\|")
                print("| %s | %s %s ply %s: %s | %s |" % (name, key[0], key[1], broken["plies"], shown,
                                                        ("WRONG: " + note.replace("|", "\\|")) if flagged else "**NOT FLAGGED**"))
                missed += 0 if flagged else 1
                done = True
                break
            if done:
                break
        if not done:
            print("| %s | (no sentence to apply it to) | n/a |" % name)
    print("\n%d story mutations not flagged." % missed)
    return missed


def _rephrase_check():
    """scripts/rephrase_check.py, loaded by path (``python -I`` does not put the script's folder on sys.path)."""
    import importlib.util
    import os
    spec = importlib.util.spec_from_file_location("rephrase_check", os.path.join(os.path.dirname(os.path.abspath(__file__)), "rephrase_check.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main():
    mode, path = sys.argv[1], sys.argv[2]
    if mode in ("rephrase", "mutate-rephrase"):
        # C2 (docs/LLM_REPHRASE_DESIGN.md §5.4): the independent Python twin of core's ClaimChecker.
        #   rephrase <jsonl>         re-judges original/candidate pairs and must agree with the Kotlin verdicts
        #   mutate-rephrase <jsonl>  breaks one fact of every recorded text at a time; every break must be rejected
        return _rephrase_check().main([mode, path])
    if mode == "lines":
        return main_lines(path)
    if mode == "story":
        # NS (ANALYSIS_SPEC 9.9): the sentences of the narrated story, claim by claim.
        return main_story(path)
    if mode == "mutate-story":
        return 1 if main_mutate_story(path) else 0
    if mode == "mutate":
        return main_mutate(path)
    if mode == "after":
        recs = [json.loads(l) for l in open(path, encoding="utf-8") if l.strip()]
        text_audit, sim_audit = audit_text_after, audit_sim_after
    else:
        recs = parse_before(path)
        text_audit, sim_audit = audit_text_before, audit_sim_before
    null = [r for r in recs if r["side"] is None]
    sentence_counts, text_counts, sim_counts = Counter(), Counter(), Counter()
    wrong, rows_text, rows_sim = [], [], []
    for r in null:
        rows = text_audit(r)
        for s, v, n in rows:
            sentence_counts[v] += 1
            if v == "W":
                wrong.append((r["game"], r["ply"], r["san"], s, n))
        if any(v == "W" for _, v, _ in rows):
            verdict = "WRONG"
        elif all(v == "F" for _, v, _ in rows):
            verdict = "flavour"
        else:
            verdict = "supported"
        text_counts[verdict] += 1
        rows_text.append((r, verdict, "".join(v for _, v, _ in rows)))
    for r in null:
        if not r.get("sim"):
            continue
        parts = sim_audit(r)
        steps = [v for k, _, v, _ in parts if k.startswith("step")]
        intro = "".join(v for k, _, v, _ in parts if k == "intro")
        pay = [t for k, t, _, _ in parts if k == "payoff"]
        for kind, text, v, n in parts:
            sim_counts[v] += 1
            if v == "W":
                wrong.append((r["game"], r["ply"], r["san"], "[%s] %s" % (kind, text), n))
        bad = any(v == "W" for _, _, v, _ in parts)
        rows_sim.append((r, "WRONG" if bad else "supported", intro, "%dS %dW" % (steps.count("S"), steps.count("W")), pay[0] if pay else ""))

    print("### Annotation texts (no side chosen)\n")
    print("%d texts: %d supported, %d harmless flavour, %d WRONG. %d sentences: %d supported, %d flavour, %d WRONG.\n" % (
        len(rows_text), text_counts["supported"], text_counts["flavour"], text_counts["WRONG"],
        sum(sentence_counts.values()), sentence_counts["S"], sentence_counts["F"], sentence_counts["W"]))
    print("| Game | Ply | Move | Class | Text | Sentences | Verdict |")
    print("|---|---|---|---|---|---|---|")
    for r, verdict, letters in rows_text:
        text = r["text"].replace("|", "\\|")
        print("| %s | %d | %s | %s | %s | %s | %s |" % (r["game"], r["ply"], r["san"], r["cls"], text, letters, verdict))
    print("\n### Walkthroughs (no side chosen)\n")
    print("%d walkthroughs: %d supported, %d WRONG. %d parts (intro sentences, steps, payoff): %d supported, %d WRONG.\n" % (
        len(rows_sim), sum(1 for x in rows_sim if x[1] == "supported"), sum(1 for x in rows_sim if x[1] == "WRONG"),
        sum(sim_counts.values()), sim_counts["S"], sim_counts["W"]))
    print("| Game | Ply | Move | Intro | Steps | Payoff | Verdict |")
    print("|---|---|---|---|---|---|---|")
    for r, verdict, intro, steps, pay in rows_sim:
        print("| %s | %d | %s | %s | %s | %s | %s |" % (r["game"], r["ply"], r["san"], intro, steps, pay or "(none)", verdict))

    # the side variants must be the same texts with only the subject words changed
    by = defaultdict(dict)
    for r in recs:
        by[(r["game"], r["ply"])][r["side"]] = r

    def norm(t):
        return re.sub(r"\b(you|your opponent|White|Black|You)\b", "X", t)

    bad_sides = 0
    for key, sides in sorted(by.items()):
        base = norm(sides[None]["text"])
        for sd in ("WHITE", "BLACK"):
            if norm(sides[sd]["text"]) != base:
                bad_sides += 1
    print("\n### Side variants\n")
    print("%d of %d White/Black texts differ from the no-side text beyond the subject words (you / your opponent / colour)." % (bad_sides, 2 * len(by)))
    print("\n### Every WRONG claim\n")
    for g, p, san, s, n in wrong:
        print("- %s ply %d %s: \"%s\" - %s" % (g, p, san, s, n))
    return 1 if wrong or bad_sides else 0


if __name__ == "__main__":
    sys.exit(main())
