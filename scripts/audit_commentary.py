#!/usr/bin/env python
"""Claim-by-claim audit of the generated card texts and walkthroughs (docs/COMMENTARY_AUDIT.md).

    python scripts/audit_commentary.py after core/build/commentary_audit/after.jsonl
    python scripts/audit_commentary.py before docs/audit/commentary_before_r1b.txt

For every sentence of every annotation text, and every intro / step / payoff of every walkthrough, the
claim it makes is re-derived from the recorded position and the recorded engine data with python-chess
(an engine independent of ``:core``'s own move generator and detectors) and given a verdict:

    S  supported         the position or the engine's own numbers prove it
    F  harmless flavour  true, and asserts nothing beyond the classification it follows
    W  WRONG             false, attributed to the wrong move or side, or not provable from the data

The recordings (core/src/test/resources/pacing/*.analysis.json) are the engine data. ``after`` reads the
JSON lines written by core's test ``CommentaryAuditDumpTest``; ``before`` reads the text dump of the
generator as it was before R1b, kept in docs/audit/ so the "before" table can be reproduced.
"""
import json
import math
import re
import sys
from collections import Counter, defaultdict

import chess

ROOT = __file__.replace("\\", "/").rsplit("/scripts/", 1)[0]
VALUE = {chess.PAWN: 100, chess.KNIGHT: 320, chess.BISHOP: 330, chess.ROOK: 500, chess.QUEEN: 900, chess.KING: 20000}
NAME = {chess.PAWN: "pawn", chess.KNIGHT: "knight", chess.BISHOP: "bishop", chess.ROOK: "rook", chess.QUEEN: "queen", chess.KING: "king"}
GAME_FILES = {
    "immortal": ROOT + "/core/src/test/resources/pacing/immortal.analysis.json",
    "chesscom": ROOT + "/core/src/test/resources/pacing/chesscom_style_game.analysis.json",
}
GAIN_WORDS = {"a queen": 900, "a rook": 500, "a piece": 325, "a pawn": 100}
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


# ---------------------------------------------------------------------------------------------
# Phrase verification (the AFTER texts)
# ---------------------------------------------------------------------------------------------

def verify_phrase(phrase, before, move, after, rec, context):
    """Return (verdict, note) for a verb phrase said about [move] from [before] to [after]."""
    us = before.turn
    them = not us
    p = phrase.strip().rstrip(".")

    if p == "is checkmate":
        return ("S", "checkmate on the board") if after.is_checkmate() else ("W", "not checkmate")
    if p == "starts a forced mate":
        if context == "found":
            mm = mover_mate_after(rec)
            return ("S", "engine: mate for the mover after the move") if (mm is not None and mm > 0) else ("W", "no engine mate for the mover")
        if context == "allowed":
            return ("S", "engine: mate for the opponent after the move") if (mover_mate_after(rec) or 0) < 0 else ("W", "no engine mate against the mover")
        mm = mover_mate_before(rec)
        return ("S", "engine: mate for the mover before the move") if (mm is not None and mm > 0) else ("W", "no engine mate")
    if p == "is a back-rank mate":
        k = after.king(them)
        return ("S", "mate, king on the back rank") if after.is_checkmate() and chess.square_rank(k) in (0, 7) else ("W", "not a back-rank mate")
    if p == "is a smothered mate":
        if not after.is_checkmate():
            return ("W", "not mate")
        ks = after.king(them)
        checkers = list(after.checkers())
        ok = len(checkers) == 1 and after.piece_at(checkers[0]).piece_type == chess.KNIGHT and all(
            after.piece_at(n) is not None and after.piece_at(n).color == them for n in after.attacks(ks))
        return ("S", "a lone knight mates a king boxed in by its own pieces") if ok else ("W", "not a smothered mate")
    if p == "gives double check":
        return ("S", "two checkers") if len(after.checkers()) >= 2 else ("W", "not a double check")

    m = re.match(r"forks (.+)$", p)
    if m:
        targets = re.findall(r"the (\w+) on ([a-h][1-8])", m.group(1))
        ok = len(targets) >= 2
        for piece, sq in targets:
            s = chess.parse_square(sq)
            pc = after.piece_at(s)
            ok = ok and pc is not None and pc.color == them and NAME[pc.piece_type] == piece and move.to_square in after.attackers(us, s)
        return ("S", "the moved piece attacks every named piece") if ok else ("W", "fork not borne out")

    m = re.match(r"attacks (.+) at once$", p)
    if m:
        targets = re.findall(r"the (\w+) on ([a-h][1-8])", m.group(1))
        ok = len(targets) >= 2 and all(
            after.piece_at(chess.parse_square(sq)) is not None and after.piece_at(chess.parse_square(sq)).color == them
            and after.attackers(us, chess.parse_square(sq)) for _, sq in targets)
        return ("S", "each target attacked") if ok else ("W", "double attack not borne out")

    m = re.match(r"pins the (\w+) on ([a-h][1-8]) to the (\w+) on ([a-h][1-8])$", p)
    if m:
        front = chess.parse_square(m.group(2))
        rear = chess.parse_square(m.group(4))
        fp, rp = after.piece_at(front), after.piece_at(rear)
        ok = bool(fp and rp and fp.color == them and rp.color == them and NAME[fp.piece_type] == m.group(1) and NAME[rp.piece_type] == m.group(3))
        ok = ok and any(after.piece_at(s).piece_type in (chess.BISHOP, chess.ROOK, chess.QUEEN) and on_line_beyond(after, s, front, rear)
                        for s in after.attackers(us, front))
        return ("S", "slider, front and rear on one line") if ok else ("W", "pin not borne out")

    m = re.match(r"skewers the (\w+) on ([a-h][1-8]), with the (\w+) on ([a-h][1-8]) behind it$", p)
    if m:
        front = chess.parse_square(m.group(2))
        rear = chess.parse_square(m.group(4))
        ok = any(after.piece_at(s).piece_type in (chess.BISHOP, chess.ROOK, chess.QUEEN) and on_line_beyond(after, s, front, rear)
                 for s in after.attackers(us, front))
        return ("S", "line verified") if ok else ("W", "skewer not borne out")

    m = re.match(r"uncovers the (\w+) on ([a-h][1-8]), which now attacks the (\w+) on ([a-h][1-8])$", p)
    if m:
        a = chess.parse_square(m.group(2))
        t = chess.parse_square(m.group(4))
        ok = a in after.attackers(us, t) and a not in before.attackers(us, t) and a != move.to_square
        return ("S", "new attack through the vacated square") if ok else ("W", "discovery not borne out")

    m = re.match(r"gives check by uncovering the (\w+) on ([a-h][1-8])$", p)
    if m:
        a = chess.parse_square(m.group(2))
        return ("S", "discovered check") if a in after.checkers() and a != move.to_square else ("W", "not a discovered check")

    m = re.match(r"attacks the undefended (\w+) on ([a-h][1-8])$", p)
    if m:
        s = chess.parse_square(m.group(2))
        pc = after.piece_at(s)
        ok = pc is not None and pc.color == them and NAME[pc.piece_type] == m.group(1) and move.to_square in after.attackers(us, s) and not after.attackers(them, s)
        return ("S", "attacked by the moved piece, nothing defends it") if ok else ("W", "undefended attack not borne out")

    m = re.match(r"leaves the (\w+) on ([a-h][1-8]) undefended, with the (\w+) on ([a-h][1-8]) attacking it$", p)
    if m:
        s = chess.parse_square(m.group(2))
        a = chess.parse_square(m.group(4))
        pc = after.piece_at(s)
        ok = pc is not None and pc.color == them and NAME[pc.piece_type] == m.group(1) and a in after.attackers(us, s) and not after.attackers(them, s)
        return ("S", "attacked by the named piece, nothing defends it") if ok else ("W", "not borne out")

    m = re.match(r"attacks the (\w+) on ([a-h][1-8]) with a (\w+)$", p)
    if m:
        s = chess.parse_square(m.group(2))
        pc = after.piece_at(s)
        att = attackers(after, us, s)
        ok = pc is not None and pc.color == them and NAME[pc.piece_type] == m.group(1) and bool(att) \
            and NAME[after.piece_at(att[0]).piece_type] == m.group(3) and VALUE[after.piece_at(att[0]).piece_type] < VALUE[pc.piece_type]
        return ("S", "attacked by a cheaper piece") if ok else ("W", "not borne out")

    m = re.match(r"attacks the (\w+) on ([a-h][1-8]) more often than it is defended$", p)
    if m:
        s = chess.parse_square(m.group(2))
        pc = after.piece_at(s)
        ok = pc is not None and pc.color == them and NAME[pc.piece_type] == m.group(1) and len(after.attackers(us, s)) > len(after.attackers(them, s))
        return ("S", "more attackers than defenders") if ok else ("W", "not borne out")

    m = re.match(r"leaves the (\w+) on ([a-h][1-8]) attacked by a (\w+)$", p)
    if m:
        s = chess.parse_square(m.group(2))
        pc = after.piece_at(s)
        att = attackers(after, us, s)
        ok = pc is not None and pc.color == them and bool(att) and NAME[after.piece_at(att[0]).piece_type] == m.group(3)
        return ("S", "attacked by the named piece") if ok else ("W", "not borne out")

    m = re.match(r"leaves the (\w+) on ([a-h][1-8]) attacked more often than it is defended$", p)
    if m:
        s = chess.parse_square(m.group(2))
        pc = after.piece_at(s)
        ok = pc is not None and pc.color == them and len(after.attackers(us, s)) > len(after.attackers(them, s))
        return ("S", "more attackers than defenders") if ok else ("W", "not borne out")

    m = re.match(r"leaves the (\w+) on ([a-h][1-8]) with no safe square$", p)
    if m:
        s = chess.parse_square(m.group(2))
        pc = after.piece_at(s)
        if not (pc is not None and pc.color == them and not after.is_check()):
            return ("W", "no such piece, or in check")
        moves = [mv for mv in after.legal_moves if mv.from_square == s]
        ok = bool(moves) and all(see(after, mv) < 0 for mv in moves)
        return ("S", "every legal move of the piece loses material by exchange") if ok else ("W", "the piece has a safe move")

    m = re.match(r"wins (a queen|a rook|a piece|a pawn)$", p)
    if m:
        cp = see(before, move)
        ok = before.is_capture(move) and abs(cp - GAIN_WORDS[m.group(1)]) <= 40
        return ("S", "capture nets %d by exchange" % cp) if ok else ("W", "capture nets %d" % cp)

    m = re.match(r"promotes the pawn to (a|an) (\w+)$", p)
    if m:
        return ("S", "promotion") if move.promotion and NAME[move.promotion] == m.group(2) else ("W", "no such promotion")
    m = re.match(r"underpromotes to (a|an) (\w+)$", p)
    if m:
        ok = move.promotion and move.promotion != chess.QUEEN and NAME[move.promotion] == m.group(2)
        return ("S", "underpromotion") if ok else ("W", "no such underpromotion")

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
    for piece, sq in re.findall(r"the (\w+) on ([a-h][1-8])", rest):
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
    return ("S", "engine line replays legally; text consistent with it")


SENT_SPLIT = re.compile(r"(?<=\.)\s+(?=[A-Z])")


def audit_text_after(rec):
    """Verdicts for every sentence of [rec]'s text. Returns a list of (sentence, verdict, note)."""
    board = chess.Board(rec["fenBefore"])
    move = chess.Move.from_uci(rec["uci"])
    after = board.copy()
    after.push(move)
    cls, loss, san, game, ply = rec["cls"], rec["loss"], rec["san"], rec["game"], rec["ply"]
    out = []
    for sent in SENT_SPLIT.split(rec["text"]):
        s = sent.strip()
        v = None
        if re.match(r"^(\S+) follows known opening theory\.$", s):
            v = ("S", "BOOK classification") if cls == "BOOK" else ("W", "not a book move")
        elif re.match(r"^(\S+) was the only legal move\.$", s):
            v = ("S", "one legal move") if board.legal_moves.count() == 1 else ("W", "several legal moves")
        elif re.match(r"^(\S+) matches the engine's top choice\.$", s):
            v = ("S", "played move equals the engine's top move") if rec["best"] == san else ("W", "not the engine's top move")
        elif re.match(r"^(\S+) is very close to the best move\.$", s):
            v = ("S", "loss %.1f < 2" % loss) if loss < 2 else ("W", "loss %.1f" % loss)
        elif re.match(r"^(\S+) is a sound move\.$", s):
            v = ("F", "loss %.1f < 5" % loss) if loss < 5 else ("W", "loss %.1f" % loss)
        elif re.match(r"^(\S+) was the only move that kept things on track\.$", s):
            gap = great_gap(game, ply)
            v = ("S", "MultiPV gap %.1f >= 10 win%%" % gap) if gap is not None and gap >= 10 and rec["best"] == san else ("W", "gap %s" % gap)
        elif re.match(r"^(\S+) gives back ground\.$", s):
            v = ("F", "loss %.1f" % loss) if loss >= 5 else ("W", "loss %.1f is not giving back ground" % loss)
        elif re.match(r"^(\S+) is among the engine's best moves here\.$", s):
            v = ("S", "loss %.1f <= 2" % loss) if loss <= 2 else ("W", "loss %.1f" % loss)
        elif s == "This allows a forced mate.":
            ok = (mover_mate_after(rec) or 0) < 0 and cls in ("INACCURACY", "MISTAKE", "BLUNDER")
            v = ("S", "engine: the opponent mates after the move") if ok else ("W", "no engine mate against the mover")
        else:
            m = re.match(r"^(\S+) is a sacrifice: it offers the (\w+) on ([a-h][1-8])\.$", s)
            if m:
                target = chess.parse_square(m.group(3))
                nets = [see(after, c) for c in after.legal_moves if after.is_capture(c) and c.to_square == target and not after.is_en_passant(c)]
                best = max(nets) if nets else None
                pc = after.piece_at(target)
                ok = pc is not None and pc.color == board.turn and NAME[pc.piece_type] == m.group(2) and best is not None and best >= 200
                v = ("S", "the opponent can take it for a net %s" % best) if ok else ("W", "no real sacrifice (best capture nets %s)" % best)
            m = re.match(r"^(\S+) leaves the (\w+) on ([a-h][1-8]) open to capture, and the engine still rates it among the best moves\.$", s)
            if m:
                target = chess.parse_square(m.group(3))
                legal = any(after.is_capture(c) and c.to_square == target for c in after.legal_moves)
                v = ("S", "a legal capture exists; loss %.1f" % loss) if legal and loss <= 2 else ("W", "no legal capture, or not near-best")
            m = re.match(r"^This lets (you|your opponent|White|Black) play (\S+), which (.+)\.$", s)
            engine_allowed = False
            if not m:
                m = re.match(r"^This lets (you|your opponent|White|Black) play (\S+); in the engine's line it (.+)\.$", s)
                engine_allowed = m is not None
            if m:
                try:
                    reply = after.parse_san(m.group(2))
                except Exception:
                    reply = None
                if cls not in ("INACCURACY", "MISTAKE", "BLUNDER"):
                    v = ("W", "a charge against a move rated %s" % cls)
                elif reply is None:
                    v = ("W", "the reply %s is not a legal move" % m.group(2))
                else:
                    after_reply = after.copy()
                    after_reply.push(reply)
                    if engine_allowed:
                        v = verify_engine_line(m.group(3), after, reply, rec, "allowed", game, ply)
                    else:
                        v = verify_phrase(m.group(3), after, reply, after_reply, rec, "allowed")
            m = re.match(r"^Better was (\S+), forcing mate in (\d+)\.$", s)
            if m:
                ok = m.group(1) == rec["best"] and (mover_mate_before(rec) or 0) == int(m.group(2))
                v = ("S", "engine: mate in %s for the mover" % m.group(2)) if ok else ("W", "mate claim not in the data")
            m = re.match(r"^Better was (\S+), keeping a decisive advantage\.$", s)
            if m:
                ok = m.group(1) == rec["best"] and cls == "MISS"
                v = ("S", "MISS: win percent before >= 90; the engine's top move") if ok else ("W", "not provable")
            m = re.match(r"^Better was (\S+)(?:, which (.+)|; in the engine's line it (.+))?\.$", s)
            if m and v is None:
                bsan = m.group(1)
                if bsan != rec["best"]:
                    v = ("W", "not the engine's top move")
                elif not m.group(2) and not m.group(3):
                    v = ("S", "the engine's top move")
                else:
                    bm = board.parse_san(bsan)
                    ab = board.copy()
                    ab.push(bm)
                    if m.group(3):
                        v = verify_engine_line(m.group(3), board, bm, rec, "better", game, ply)
                    else:
                        v = verify_phrase(m.group(2), board, bm, ab, rec, "better")
            m = re.match(r"^In the engine's line, (\S+) (.+)\.$", s)
            if m and v is None:
                v = verify_engine_line(m.group(2), board, move, rec, "found", game, ply)
                if v[0] == "S" and (mover_mate_after(rec) or 0) < 0:
                    v = ("W", "the opponent has a forced mate after this move")
            m = re.match(r"^This (.+)\.$", s)
            if m and v is None:
                v = verify_phrase(m.group(1), board, move, after, rec, "found")
        if v is None:
            v = ("W", "unrecognised sentence")
        out.append((s, v[0], v[1]))
    return out


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
        mm = re.search(r"winning (a queen|a rook|a piece|a pawn|material)", head)
        if mm:
            want = GAIN_WORDS.get(mm.group(1))
            if recapture:
                net = VALUE[before.piece_at(mv.to_square).piece_type] - prev_taken
                label = "pair"
            else:
                net = see(before, mv)
                label = "capture"
            if not (net >= 100 and (want is None or abs(net - want) <= 40)):
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
            for pcs, sq in re.findall(r"the (\w+) on ([a-h][1-8])", am.group(1)):
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


def audit_text_before(rec):
    board = chess.Board(rec["fenBefore"])
    move = board.parse_san(rec["san"])
    after = board.copy()
    after.push(move)
    cls, loss, game, ply, san = rec["cls"], rec["loss"], rec["game"], rec["ply"], rec["san"]
    out = []
    for sent in SENT_SPLIT.split(rec["text"]):
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
# Driver
# ---------------------------------------------------------------------------------------------

def main():
    mode, path = sys.argv[1], sys.argv[2]
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


if __name__ == "__main__":
    main()
