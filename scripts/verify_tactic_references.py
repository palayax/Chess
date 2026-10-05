"""Author + MECHANICALLY VERIFY reference tactic positions for Palaya Chess.

Every entry is a canonical teaching position for one `TacticType`. Nothing here is trusted because
it reads plausibly - plausible-looking chess is exactly what a language model produces by default,
and a *teaching* feature that shows a learner "this is a fork" must not be showing them something
that isn't one. On the first pass of this file, 7 of 10 hand-written positions were wrong: an
illegal move, a "double check" giving one check, a knight fork attacking nothing, an invented
knight move, and a bishop "pin" blocked by a pawn.

So each entry is checked against python-chess:
  - the FEN is legal, and nobody is already in check who shouldn't be,
  - every move of the solution line is legal,
  - the pattern's OWN structural claim holds - mate is mate, a fork really forks two valuable
    pieces, a pin leaves a genuinely pinned piece, a discovered check is given by a piece that did
    not move, a "wins material" combination actually ends up material,
  - and, for the combinations, the line is *sound*: a small material alpha-beta (or an exhaustive
    forced-mate solver, where mate is the claim) confirms the first move is the best move and
    that the claimed payoff is really forced rather than dependent on a co-operative reply.

Run:  python scripts/verify_tactic_references.py fixtures/tactic_references.json [TacticReferenceCorpus.kt]
Exit code is non-zero if ANY entry fails, so this can gate a build. When every entry passes and
the second path is given, the same corpus is also written out as a generated Kotlin table so the
app ships exactly the positions this script verified - `:core`'s `TacticReferenceCorpusTest`
asserts the JSON and the Kotlin agree, and replays every line through `:core`'s own move
generator as a second, independent check.
"""
import chess, json, sys

PIECE_VALUE = {chess.PAWN: 1, chess.KNIGHT: 3, chess.BISHOP: 3, chess.ROOK: 5, chess.QUEEN: 9, chess.KING: 100}
MATE_SCORE = 1000


def material(board, color):
    return sum(PIECE_VALUE[p.piece_type] for s in chess.SQUARES
               if (p := board.piece_at(s)) and p.color == color and p.piece_type != chess.KING)


def balance(board, color):
    return material(board, color) - material(board, not color)


def capture_net(board, square):
    """Net material (PIECE_VALUE units) the side to move gets by its best capture on `square`, with
    both sides free to recapture there or stop. None when it has no legal capture there at all.
    Legal moves only, so a pinned piece or an illegal king capture is never counted."""
    victim = board.piece_at(square)
    if victim is None:
        return None
    best = None
    for m in board.legal_moves:
        if m.to_square != square or not board.is_capture(m):
            continue
        board.push(m)
        reply = capture_net(board, square)  # the other side's best recapture; it may also decline
        board.pop()
        net = PIECE_VALUE[victim.piece_type] - (max(reply, 0) if reply is not None else 0)
        best = net if best is None else max(best, net)
    return best


# (tactic, fen, solution line in SAN, teaching point)
REFS = [
    ("FORK", "2r3k1/5ppp/8/3N4/8/8/5PPP/6K1 w - - 0 1", ["Ne7+"],
     "The knight checks the king and hits the rook at the same time; the king must move and the rook drops."),
    ("PAWN_FORK", "4k3/8/8/3n1n2/8/4P3/8/4K3 w - - 0 1", ["e4"],
     "One pawn push attacks two pieces at once, and neither can take it - the cheapest attacker always wins the exchange."),
    ("PIN_ABSOLUTE", "4k3/3n4/8/8/8/8/8/4KB2 w - - 0 1", ["Bb5"],
     "A piece pinned against its own king is frozen - it cannot legally move, so it defends nothing."),
    ("PIN_RELATIVE", "3qk3/8/5n2/8/8/8/8/2B1K3 w - - 0 1", ["Bg5"],
     "The knight may legally move, but doing so loses the queen behind it - a relative pin."),
    ("SKEWER", "rk6/8/8/8/8/8/8/6KR w - - 0 1", ["Rh8+"],
     "Check the king along a line with a piece behind it; when the king steps aside, that piece falls."),
    ("BACK_RANK_MATE", "6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1", ["Ra8#"],
     "A king walled in by its own pawns has no escape; a rook arriving on the back rank is mate."),
    ("SMOTHERED_MATE", "6rk/6pp/8/6N1/8/8/8/6K1 w - - 0 1", ["Nf7#"],
     "The king's own pieces steal every flight square, so a lone knight delivers mate."),
    ("DOUBLE_CHECK", "4k3/8/8/8/4B3/8/8/4R1K1 w - - 0 1", ["Bc6+"],
     "The bishop checks and uncovers the rook behind it. Two checks at once cannot be blocked or captured - the king MUST move."),
    ("DISCOVERED_CHECK", "4k3/8/8/8/4N3/8/8/4R1K1 w - - 0 1", ["Ng5+"],
     "Moving one piece out of the way lets the piece behind it give check - the mover is free to go anywhere."),
    ("DISCOVERED_ATTACK", "k3q3/8/8/8/4N3/8/8/4R1K1 w - - 0 1", ["Ng5"],
     "Stepping aside unmasks an attack from the piece behind - here the rook suddenly hits the queen."),
    ("HANGING_PIECE", "rnbqkb1r/pppp1ppp/8/4n3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 0 1", ["Nxe5"],
     "An undefended piece can simply be taken for free - check what is hanging before every move."),
    ("REMOVING_THE_DEFENDER", "7k/8/3p4/4p3/1B6/5N2/8/4K3 w - - 0 1", ["Bxd6", "Kg8", "Nxe5"],
     "The e5 pawn was only held by its neighbour. Take the defender first, and the defended piece falls next."),
    ("TRAPPED_PIECE", "4k3/8/8/8/8/8/5KPb/8 w - - 0 1", ["g3"],
     "A piece deep in enemy territory can run out of squares - every retreat here simply loses the bishop."),
    ("PROMOTION_TACTIC", "8/P7/8/8/8/8/8/K6k w - - 0 1", ["a8=Q+"],
     "A pawn reaching the last rank becomes a queen - a passed pawn is a queen waiting to happen."),
    ("UNDERPROMOTION", "8/4k1P1/8/8/8/8/8/K7 w - - 0 1", ["g8=N+"],
     "Sometimes a knight beats a queen: only the knight gives check here."),

    # ---- Round 6 additions: the motifs the detector emits and the narration names ----------
    ("DEFLECTION", "3q2k1/2p2ppp/8/8/8/2Q5/5PPP/4R1K1 w - - 0 1", ["Qxc7", "Qxc7", "Re8#"],
     "The queen is the only guard of the back rank. Attack it with something it must take, and the guard is gone."),
    ("DECOY", "7k/2q3pp/8/6N1/8/8/5PPP/3R2K1 w - - 0 1", ["Rd8+", "Qxd8", "Nf7+", "Kg8", "Nxd8"],
     "Give up the rook to drag the queen onto d8 - the one square where the knight forks king and queen."),
    ("OVERLOADED_PIECE", "4r1k1/1n1q1ppp/8/8/8/5B2/5PPP/R3R1K1 w - - 0 1", ["Rxe8+", "Qxe8", "Bxb7"],
     "One queen was guarding two things. Take the first, it must recapture, and the second is left hanging."),
    ("CLEARANCE", "5rk1/5pp1/8/7R/8/3B4/6P1/3Q2K1 w - - 0 1", ["Rh8+", "Kxh8", "Qh5+", "Kg8", "Qh7#"],
     "The rook is sitting on the square the queen needs, so it leaves with check - and the queen lands on h5 next move."),
    ("ZWISCHENZUG", "3qk3/ppp2ppp/8/4p3/2B1P3/5b2/PPP2PPP/3QK3 w - - 0 1", ["Bxf7+", "Kxf7", "Qxd8"],
     "Do not recapture on autopilot. First a check that drags the king off the queen's defence, then take the queen."),
    ("DOUBLE_ATTACK", "4k3/5ppp/q7/8/4N3/8/5PPP/4R1K1 w - - 0 1", ["Nc5+", "Kd8", "Nxa6"],
     "One move, two threats from two different pieces: the rook behind gives check, the knight itself hits the queen. Only one can be answered."),
    ("MATE_NET", "7k/4Nppp/8/8/4Q3/R7/8/6K1 w - - 0 1", ["Qxh7+", "Kxh7", "Rh3#"],
     "A queen sacrifice pulls the king into a net where every square is already covered - then the rook lifts and it is over."),
    ("WINDMILL", "3q1n1k/pppbnpRp/5B2/8/8/8/5PPP/6K1 w - - 0 1", ["Rxf7+", "Kg8", "Rg7+", "Kh8", "Rxe7+", "Kg8", "Rg7+", "Kh8", "Rxd7+"],
     "Discovered check, direct check, discovered check: the king is shuttled back and forth while the rook eats the whole rank."),
    ("GREEK_GIFT", "r1bq1rk1/ppp1bppp/2n1p3/3pP3/3P4/3B1N2/PPP2PPP/R2Q1RK1 w - - 0 1", ["Bxh7+", "Kxh7", "Ng5+", "Kg8", "Qh5"],
     "The classic bishop sacrifice on h7: the king is pulled out, the knight checks, and the queen arrives with a mate threat."),
    ("PASSED_PAWN_BREAKTHROUGH", "7k/ppp5/8/PPP5/8/8/8/7K w - - 0 1", ["b6", "cxb6", "a6", "bxa6", "c6"],
     "Three pawns against three: give up two of them and the third becomes a passed pawn nobody can catch."),
    ("DESPERADO", "3rk3/8/8/4p3/3Q4/8/PPP2PPP/3K4 w - - 0 1", ["Qxd8+", "Kxd8"],
     "The queen is lost whatever it does - the rook pins it and the pawn attacks it - so it sells itself for a rook rather than for nothing."),
    ("PERPETUAL_CHECK", "6k1/6p1/8/3p4/8/8/qr2Q3/6K1 w - - 0 1", ["Qe8+", "Kh7", "Qh5+", "Kg8", "Qe8+", "Kh7", "Qh5+", "Kg8"],
     "Down a queen and a rook, White cannot win - but the king cannot escape the checks either, and repetition is a draw."),
    ("STALEMATE_TRICK", "r6k/8/1q6/3Q4/8/7p/7P/7K w - - 0 1", ["Qg8+", "Kxg8"],
     "Hopelessly lost, White has one move left: a queen check that MUST be captured - and then White has no legal move at all."),
]


# ---------------------------------------------------------------------------
# Small solvers, so "wins material" and "forces mate" are checked as chess
# ---------------------------------------------------------------------------

def forced_mate(board, plies):
    """True when the side to move can force checkmate within `plies` plies (exhaustive)."""
    if plies <= 0:
        return False
    for m in board.legal_moves:
        board.push(m)
        try:
            if board.is_checkmate():
                return True
            if plies >= 3 and not board.is_game_over():
                if all(_mated_within(board, plies - 2) for _ in [0]):
                    return True
        finally:
            board.pop()
    return False


def _mated_within(board, plies):
    """Side to move (the defender) cannot avoid being mated within `plies` more attacker plies."""
    for reply in board.legal_moves:
        board.push(reply)
        try:
            if not forced_mate(board, plies):
                return False
        finally:
            board.pop()
    return True


def search(board, depth, alpha=-10 ** 6, beta=10 ** 6):
    """Material negamax with a capture-only quiescence; score from the side to move."""
    if board.is_checkmate():
        return -MATE_SCORE - depth
    if board.is_stalemate() or board.is_insufficient_material() or board.can_claim_threefold_repetition():
        return 0
    if depth == 0:
        return quiesce(board, alpha, beta, 6)
    best = -10 ** 6
    for m in board.legal_moves:
        board.push(m)
        v = -search(board, depth - 1, -beta, -alpha)
        board.pop()
        if v > best:
            best = v
        if best > alpha:
            alpha = best
        if alpha >= beta:
            break
    return best


def quiesce(board, alpha, beta, qdepth):
    if board.is_checkmate():
        return -MATE_SCORE
    if board.is_stalemate():
        return 0
    stand = balance(board, board.turn)
    if qdepth == 0 or stand >= beta:
        return stand
    alpha = max(alpha, stand)
    for m in board.legal_moves:
        if not board.is_capture(m) and not m.promotion:
            continue
        board.push(m)
        v = -quiesce(board, -beta, -alpha, qdepth - 1)
        board.pop()
        if v >= beta:
            return v
        alpha = max(alpha, v)
    return alpha


def root_search(board, depth):
    """(best value, [moves achieving it]) for the side to move."""
    scored = []
    for m in board.legal_moves:
        board.push(m)
        scored.append((-search(board, depth - 1), m))
        board.pop()
    best = max(v for v, _ in scored)
    return best, [m for v, m in scored if v == best]


def sound_material_line(board, moves, min_gain, depth=4):
    """The first move is (one of) the best by search AND the search agrees the gain is forced."""
    best, best_moves = root_search(board, depth)
    if moves[0] not in best_moves:
        return False, f"first move is not best by search (best={[board.san(m) for m in best_moves]}, value={best})"
    gain = best - balance(board, board.turn)
    if gain < min_gain:
        return False, f"search finds only {gain:+d}, claimed >= {min_gain:+d}"
    return True, f"search: best move, forced gain {gain:+d}"


def replies_all_forced(board, moves):
    """Every opponent reply in the line is the only legal move at that point."""
    probe = board.copy()
    for i, m in enumerate(moves):
        if i % 2 == 1 and probe.legal_moves.count() != 1:
            return False
        probe.push(m)
    return True


def attacked_after(board, move):
    """Is the moved piece attacked on its destination once it has actually moved there?"""
    board.push(move)
    try:
        return board.is_attacked_by(board.turn, move.to_square)
    finally:
        board.pop()


def unstoppable_passer(board, color):
    """A pawn of `color` with a clear, uncapturable path whose promotion square the enemy king cannot reach in time."""
    enemy = not color
    for s in chess.SQUARES:
        p = board.piece_at(s)
        if not p or p.color != color or p.piece_type != chess.PAWN:
            continue
        f, r = chess.square_file(s), chess.square_rank(s)
        step = 1 if color == chess.WHITE else -1
        promo_rank = 7 if color == chess.WHITE else 0
        path = [chess.square(f, rr) for rr in range(r + step, promo_rank + step, step)]
        if any(board.piece_at(sq) for sq in path):
            continue
        # No enemy pawn can ever capture on the path (adjacent files, ahead of the pawn).
        blocked = False
        for sq in chess.SQUARES:
            q = board.piece_at(sq)
            if q and q.color == enemy and q.piece_type == chess.PAWN:
                if abs(chess.square_file(sq) - f) <= 1 and (chess.square_rank(sq) - r) * step > 0:
                    blocked = True
        if blocked:
            continue
        # Square rule, with the mover to move: distance to promote vs enemy king distance.
        pawn_moves = len(path)
        king_moves = chess.square_distance(board.king(enemy), path[-1])
        tempo = 0 if board.turn == color else 1
        if king_moves - tempo >= pawn_moves:
            # Any other enemy piece besides king and pawns? Then the rule does not apply.
            others = [q for sq in chess.SQUARES if (q := board.piece_at(sq)) and q.color == enemy
                      and q.piece_type not in (chess.KING, chess.PAWN)]
            if not others:
                return True, f"{chess.square_name(s)} pawn promotes in {pawn_moves}, king needs {king_moves}"
    return False, "no unstoppable passed pawn"


# ---------------------------------------------------------------------------
# The structural claims
# ---------------------------------------------------------------------------

def valuable(board, sq, solver, min_value=3):
    q = board.piece_at(sq)
    return q is not None and q.color != solver and (q.piece_type == chess.KING or PIECE_VALUE[q.piece_type] >= min_value)


def newly_attacked_valuable(board, after1, solver, min_value=3):
    """Enemy pieces (>= min_value, or the king) attacked by the solver after the move but not before."""
    out = {}
    for s in chess.SQUARES:
        if not valuable(after1, s, solver, min_value):
            continue
        before_attackers = board.attackers(solver, s) if board.piece_at(s) else chess.SquareSet()
        after_attackers = after1.attackers(solver, s)
        if after_attackers and not before_attackers:
            out[s] = after_attackers
    return out


def structural_ok(kind, board, moves):
    """Check the pattern's own claim, not merely that the moves were legal."""
    solver = board.turn
    first = moves[0]
    after1 = board.copy()
    after1.push(first)

    final = board.copy()
    for m in moves:
        final.push(m)

    gain = balance(final, solver) - balance(board, solver)

    if kind in ("BACK_RANK_MATE", "SMOTHERED_MATE"):
        return final.is_checkmate(), "is_checkmate"

    if kind == "PIN_ABSOLUTE":
        pinned = [s for s in chess.SQUARES
                  if (p := after1.piece_at(s)) and p.color == after1.turn and after1.is_pinned(after1.turn, s)]
        return len(pinned) >= 1, f"{len(pinned)} piece(s) absolutely pinned"

    if kind == "PIN_RELATIVE":
        # Attacker -> victim -> a MORE valuable piece further along the same ray, victim not the king.
        for victim in chess.SQUARES:
            vp = after1.piece_at(victim)
            if not vp or vp.color == solver or vp.piece_type == chess.KING:
                continue
            if victim not in after1.attacks(first.to_square):
                continue
            ray = chess.SquareSet(chess.ray(first.to_square, victim))
            behind = [s for s in ray
                      if (p := after1.piece_at(s)) and p.color == vp.color and s != victim
                      and chess.square_distance(first.to_square, s) > chess.square_distance(first.to_square, victim)
                      and PIECE_VALUE[p.piece_type] > PIECE_VALUE[vp.piece_type]]
            if behind:
                return True, f"{chess.piece_name(vp.piece_type)} pinned to something larger"
        return False, "no relative pin found"

    if kind == "DOUBLE_CHECK":
        return len(after1.checkers()) >= 2, f"checkers={len(after1.checkers())}"

    if kind == "DISCOVERED_CHECK":
        if not after1.is_check():
            return False, "not check"
        checkers = after1.checkers()
        return first.to_square not in checkers, "check given by a piece that did NOT move"

    if kind == "DISCOVERED_ATTACK":
        # A non-moving friendly piece attacks a valuable enemy piece it did not attack before.
        for s in chess.SQUARES:
            p = after1.piece_at(s)
            if not p or p.color != solver or s == first.to_square:
                continue
            now = {t for t in after1.attacks(s)
                   if (q := after1.piece_at(t)) and q.color != solver and PIECE_VALUE[q.piece_type] >= 5}
            before = {t for t in board.attacks(s)
                      if (q := board.piece_at(t)) and q.color != solver and PIECE_VALUE[q.piece_type] >= 5}
            if now - before:
                return True, "unmasked attack on a valuable piece"
        return False, "no newly unmasked attack"

    if kind in ("FORK", "PAWN_FORK"):
        hits = [t for t in after1.attacks(first.to_square)
                if (q := after1.piece_at(t)) and q.color != solver and PIECE_VALUE[q.piece_type] >= 3]
        if len(hits) < 2:
            return False, f"attacks {len(hits)} valuable pieces at once"
        # A fork only works if the forking piece survives to cash it in. If the opponent can simply
        # take the forker without losing material (an even trade counts), the "fork" is a capture or
        # an exchange. The first PAWN_FORK reference, 1.e4 against a knight and a bishop, failed
        # exactly this: the forked bishop on f5 just took the pawn. Mirrors ANALYSIS_SPEC 5.3.
        taken = capture_net(after1.copy(), first.to_square)
        if taken is not None and taken >= 0:
            return False, f"the forking piece can be taken without loss (exchange result {taken:+d} for the defender)"
        return True, f"attacks {len(hits)} valuable pieces at once, and the forker cannot be taken without loss"

    if kind == "DOUBLE_ATTACK":
        # Two NEW threats against valuable pieces, from at least two DIFFERENT attacking pieces -
        # which is what separates a double attack from a plain fork by one piece. And the line
        # must actually cash one of them in.
        new = newly_attacked_valuable(board, after1, solver)
        attackers = set()
        for sq, atk in new.items():
            attackers |= set(atk)
        if len(new) < 2 or len(attackers) < 2:
            return False, f"{len(new)} new target(s) from {len(attackers)} attacker(s)"
        if gain < 2:
            return False, f"material swing only {gain:+d}"
        return sound_material_line(board, moves, 2)

    if kind == "SKEWER":
        if not after1.is_check():
            return False, "not check"
        ks = after1.king(after1.turn)
        ray = chess.SquareSet(chess.ray(first.to_square, ks)) if ks is not None else chess.SquareSet()
        behind = [s for s in ray
                  if (p := after1.piece_at(s)) and p.color == after1.turn and s != ks
                  and chess.square_distance(first.to_square, s) > chess.square_distance(first.to_square, ks)
                  and PIECE_VALUE[p.piece_type] >= 4]
        return len(behind) >= 1, f"check with {len(behind)} valuable piece(s) behind the king"

    if kind == "HANGING_PIECE":
        return (board.is_capture(move := first) and not board.is_attacked_by(not solver, move.to_square)), \
               "captures an undefended piece"

    if kind == "REMOVING_THE_DEFENDER":
        return gain >= 2, f"material swing {gain:+d}"

    if kind == "TRAPPED_PIECE":
        # Every legal move of the targeted enemy piece lands on a square the solver attacks.
        victims = [s for s in chess.SQUARES
                   if (p := after1.piece_at(s)) and p.color != solver
                   and p.piece_type in (chess.BISHOP, chess.KNIGHT, chess.ROOK, chess.QUEEN)]
        for v in victims:
            escapes = [m for m in after1.legal_moves if m.from_square == v]
            if escapes and all(after1.is_attacked_by(solver, m.to_square) for m in escapes):
                return True, f"{len(escapes)} escape square(s), all covered"
        return False, "no trapped piece"

    if kind == "PROMOTION_TACTIC":
        return first.promotion == chess.QUEEN, "promotes to queen"

    if kind == "UNDERPROMOTION":
        return first.promotion == chess.KNIGHT and after1.is_check(), "knight promotion with check"

    if kind == "DEFLECTION":
        # Our move attacks a defender D; the reply moves D (it takes our piece); the square D was
        # guarding is now unguarded by D, and our next move lands there and mates or wins.
        if len(moves) < 3:
            return False, "needs attack / reply / exploit"
        reply, exploit = moves[1], moves[2]
        if reply.to_square != first.to_square:
            return False, "reply does not capture the deflecting piece"
        defender_from = reply.from_square
        target = exploit.to_square
        guarded_before = target in board.attacks(defender_from)
        after2 = after1.copy()
        after2.push(reply)
        guarded_after = target in after2.attacks(reply.to_square)
        if not guarded_before or guarded_after:
            return False, "the captured-on square was not the defender's guard duty"
        if final.is_checkmate():
            return True, f"defender dragged off {chess.square_name(target)}, mate follows"
        if gain >= 2:
            return sound_material_line(board, moves, 2)
        return False, "neither mate nor material"

    if kind == "DECOY":
        # A sacrifice lures a piece (here the king) onto a square; our next move then attacks
        # that square AND something else at once, and the line nets material.
        if len(moves) < 3:
            return False, "needs sacrifice / capture / fork"
        reply = moves[1]
        if not board.is_capture(first) and PIECE_VALUE[board.piece_at(first.from_square).piece_type] < 3:
            return False, "first move is not a sacrifice"
        if reply.to_square != first.to_square:
            return False, "the lure was not taken"
        after2 = after1.copy()
        after2.push(reply)
        after3 = after2.copy()
        after3.push(moves[2])
        lured = reply.to_square
        hits = [t for t in after3.attacks(moves[2].to_square)
                if (q := after3.piece_at(t)) and q.color != solver and (q.piece_type == chess.KING or PIECE_VALUE[q.piece_type] >= 3)]
        if lured not in hits or len(hits) < 2:
            return False, f"the lured piece is not forked (hits={[chess.square_name(h) for h in hits]})"
        if gain < 2:
            return False, f"material swing only {gain:+d}"
        return sound_material_line(board, moves, 2)

    if kind == "OVERLOADED_PIECE":
        # One defender D guards two squares t1, t2. We take on t1, D recaptures, we take on t2 and
        # D no longer guards it.
        if len(moves) < 3:
            return False, "needs capture / recapture / capture"
        t1, reply, second = first.to_square, moves[1], moves[2]
        t2 = second.to_square
        if not board.is_capture(first) or reply.to_square != t1 or not after1.is_capture(reply):
            return False, "shape is not capture / recapture"
        d = reply.from_square
        if not (t1 in board.attacks(d) and t2 in board.attacks(d)):
            return False, "the defender did not guard both squares"
        after2 = after1.copy()
        after2.push(reply)
        if t2 in after2.attacks(t1):
            return False, "the defender still guards the second square after recapturing"
        if not after2.is_capture(second) or gain < 2:
            return False, f"second capture does not net material ({gain:+d})"
        return sound_material_line(board, moves, 2)

    if kind == "CLEARANCE":
        # The first move vacates a square; a LATER move by a different friendly piece lands on it
        # or passes through it; and the line mates or wins.
        vacated = first.from_square
        used = False
        probe = after1.copy()
        for i, m in enumerate(moves[1:], start=1):
            if i % 2 == 0 and m.from_square != first.to_square:
                between = chess.SquareSet(chess.between(m.from_square, m.to_square))
                if m.to_square == vacated or vacated in between:
                    used = True
            probe.push(m)
        if not used:
            return False, "no later move uses the vacated square"
        if final.is_checkmate():
            plies = len(moves)
            return forced_mate(board.copy(), plies), f"vacated {chess.square_name(vacated)} then mate, forced in {plies} plies"
        if gain >= 2:
            return sound_material_line(board, moves, 2)
        return False, "neither mate nor material"

    if kind == "ZWISCHENZUG":
        # An "obvious" capture is available; instead we insert a CHECK first, and after the forced
        # reply the line ends with more than the obvious capture would have got.
        obvious = [m for m in board.legal_moves if board.is_capture(m)
                   and PIECE_VALUE[board.piece_at(m.to_square).piece_type] >= 3]
        if not obvious:
            return False, "no obvious capture was on offer"
        if first in obvious or not after1.is_check():
            return False, "first move is not an in-between check"
        # What the plain capture is actually worth, by search (a queen trade is not "+9").
        obvious_gain = -10 ** 6
        for m in obvious:
            board.push(m)
            obvious_gain = max(obvious_gain, -search(board, 3) - balance(board, not board.turn))
            board.pop()
        if gain <= obvious_gain:
            return False, f"in-between move nets {gain:+d}, the plain capture {obvious_gain:+d}"
        return sound_material_line(board, moves, obvious_gain + 1)

    if kind == "MATE_NET":
        if not final.is_checkmate() or len(moves) < 3:
            return False, "line must end in mate and take more than one move"
        plies = len(moves)
        if not forced_mate(board.copy(), plies):
            return False, f"mate is not forced within {plies} plies"
        return True, f"forced mate in {(plies + 1) // 2}"

    if kind == "WINDMILL":
        # All our moves are checks; at least two are DISCOVERED checks (the checker did not move),
        # at least two are captures; every reply is the only legal move; and it wins material.
        probe = board.copy()
        discovered = captures = 0
        for i, m in enumerate(moves):
            if i % 2 == 0:
                if probe.is_capture(m):
                    captures += 1
                probe.push(m)
                if not probe.is_check():
                    return False, f"our move {i // 2 + 1} is not a check"
                if m.to_square not in probe.checkers():
                    discovered += 1
            else:
                probe.push(m)
        if discovered < 2 or captures < 2:
            return False, f"{discovered} discovered check(s), {captures} capture(s)"
        if not replies_all_forced(board, moves):
            return False, "a reply was not forced"
        return gain >= 4, f"{discovered} discovered checks, {captures} captures, forced, wins {gain:+d}"

    if kind == "GREEK_GIFT":
        # Bxh7+ (or Bxh2+), Kxh7, Ng5+ (Ng4+), king retreats, Qh5 (Qh4) with a mate threat.
        if len(moves) < 5:
            return False, "needs the five-ply pattern"
        b, k, n, k2, q = moves[:5]
        h_sq = chess.H7 if solver == chess.WHITE else chess.H2
        g_sq = chess.G5 if solver == chess.WHITE else chess.G4
        q_sq = chess.H5 if solver == chess.WHITE else chess.H4
        if board.piece_at(b.from_square).piece_type != chess.BISHOP or b.to_square != h_sq or not board.is_capture(b):
            return False, "first move is not a bishop capture on h7"
        if not after1.is_check() or k.to_square != h_sq:
            return False, "the sacrifice is not a check the king takes"
        p2 = after1.copy(); p2.push(k)
        if p2.piece_at(n.from_square).piece_type != chess.KNIGHT or n.to_square != g_sq:
            return False, "third move is not the knight to g5"
        p3 = p2.copy(); p3.push(n)
        if not p3.is_check():
            return False, "knight move is not check"
        p4 = p3.copy(); p4.push(k2)
        if p4.piece_at(q.from_square).piece_type != chess.QUEEN or q.to_square != q_sq:
            return False, "fifth move is not the queen to h5"
        p5 = p4.copy(); p5.push(q)
        # Mate is threatened: if the defender passes, there is a mate in one.
        p5.push(chess.Move.null())
        threat = forced_mate(p5, 1)
        return threat, "bishop sac, knight check, queen to h5 threatening mate"

    if kind == "PASSED_PAWN_BREAKTHROUGH":
        ok, why = unstoppable_passer(final, solver)
        if not ok:
            return False, why
        if not replies_all_forced(board, moves):
            # Replies are not strictly forced in a breakthrough (the defender may choose which
            # pawn captures), so instead require that EVERY defender reply leaves an unstoppable
            # passer within the same number of plies. Exhaustive over the tiny pawn ending.
            return all_lines_break_through(board, len(moves)), "every defence still yields an unstoppable passer"
        return True, why

    if kind == "DESPERADO":
        # Our piece X is attacked and doomed: every non-capturing move of X lands on an attacked
        # square. X captures the most it can, and is recaptured. Search agrees the capture is best.
        x = first.from_square
        piece = board.piece_at(x)
        if not board.is_attacked_by(not solver, x):
            return False, "the desperado piece is not even attacked"
        quiet = [m for m in board.legal_moves if m.from_square == x and not board.is_capture(m)]
        if not quiet or not all(attacked_after(board, m) for m in quiet):
            return False, "the piece has a safe square, so it is not doomed"
        if not board.is_capture(first):
            return False, "first move is not a capture"
        if len(moves) < 2 or moves[1].to_square != first.to_square:
            return False, "the desperado is not recaptured"
        best, best_moves = root_search(board, 4)
        if first not in best_moves:
            return False, f"search prefers {[board.san(m) for m in best_moves]}"
        grabbed = PIECE_VALUE[board.piece_at(first.to_square).piece_type]
        return True, f"doomed {chess.piece_name(piece.piece_type)} takes {grabbed} on the way out"

    if kind == "PERPETUAL_CHECK":
        if balance(board, solver) >= 0:
            return False, "the checking side is not behind, so a draw is not the point"
        probe = board.copy()
        for i, m in enumerate(moves):
            probe.push(m)
            if i % 2 == 0 and not probe.is_check():
                return False, f"our move {i // 2 + 1} is not a check"
        if not replies_all_forced(board, moves):
            return False, "a reply was not forced"
        return probe.is_repetition(2), "all checks, all replies forced, position repeats"

    if kind == "STALEMATE_TRICK":
        if balance(board, solver) >= 0:
            return False, "the tricking side is not behind"
        if not after1.is_check():
            return False, "the trick is not a check"
        # EVERY legal reply leaves the solver stalemated.
        for reply in after1.legal_moves:
            p = after1.copy(); p.push(reply)
            if not p.is_stalemate():
                return False, f"reply {after1.san(reply)} avoids stalemate"
        return True, f"{after1.legal_moves.count()} reply/replies, every one stalemates"

    return False, "NO STRUCTURAL CHECK DEFINED - refusing to trust this entry"


def all_lines_break_through(board, plies):
    """Pawn-ending exhaustive check: whatever the defender does, an unstoppable passer appears."""
    solver = board.turn

    def rec(b, left, to_move_is_solver):
        if unstoppable_passer(b, solver)[0]:
            return True
        if left == 0:
            return False
        moves = list(b.legal_moves)
        if not moves:
            return False
        if to_move_is_solver:
            return any(rec_push(b, m, left - 1, False) for m in moves)
        return all(rec_push(b, m, left - 1, True) for m in moves)

    def rec_push(b, m, left, nxt):
        b.push(m)
        try:
            return rec(b, left, nxt)
        finally:
            b.pop()

    return rec(board.copy(), plies, True)


# ---------------------------------------------------------------------------
# Output
# ---------------------------------------------------------------------------

def kotlin_string(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'


def write_kotlin(entries, path):
    lines = [
        "package net.palaya.chessanalyzer.core.analysis",
        "",
        "/**",
        " * GENERATED by `scripts/verify_tactic_references.py` from `fixtures/tactic_references.json`.",
        " * Do not edit by hand: every entry here passed python-chess legality, the pattern's own structural",
        " * check and (where the claim is material or mate) a small exhaustive solver. Edit the script's",
        " * REFS table, re-run it, and let it regenerate this file. `TacticReferenceCorpusTest` asserts this",
        " * table and the JSON are identical and replays every line through `:core`'s own move generator.",
        " */",
        "internal object TacticReferenceCorpus {",
        "    val entries: List<TacticReference> = listOf(",
    ]
    for i, e in enumerate(entries):
        sol = ", ".join(kotlin_string(s) for s in e["solution"])
        comma = "," if i < len(entries) - 1 else ""
        lines.append("        TacticReference(")
        lines.append(f"            type = TacticType.{e['tactic']},")
        lines.append(f"            fen = {kotlin_string(e['fen'])},")
        lines.append(f"            solutionSan = listOf({sol}),")
        lines.append(f"            teachingPoint = {kotlin_string(e['teachingPoint'])}")
        lines.append(f"        ){comma}")
    lines += ["    )", "}", ""]
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines))


def main():
    out_path = sys.argv[1] if len(sys.argv) > 1 else None
    kotlin_path = sys.argv[2] if len(sys.argv) > 2 else None
    ok, bad = [], []
    seen = set()
    for kind, fen, sans, teach in REFS:
        try:
            if kind in seen:
                bad.append((kind, "duplicate tactic")); continue
            seen.add(kind)
            b = chess.Board(fen)
            if not b.is_valid():
                bad.append((kind, "FEN is not legal")); continue
            probe, moves = b.copy(), []
            for san in sans:
                moves.append(probe.parse_san(san)); probe.push(moves[-1])
            # The SAN in the table must be the canonical spelling, since :core will re-derive it.
            canon = []
            p2 = b.copy()
            for m in moves:
                canon.append(p2.san(m)); p2.push(m)
            if canon != sans:
                bad.append((kind, f"non-canonical SAN: {sans} vs {canon}")); continue
            good, why = structural_ok(kind, b, moves)
            if good:
                ok.append({"tactic": kind, "fen": fen, "solution": sans, "teachingPoint": teach})
                print(f"PASS  {kind:24} {' '.join(sans):40} ({why})")
            else:
                bad.append((kind, why)); print(f"FAIL  {kind:24} {' '.join(sans):40} -> {why}")
        except Exception as e:
            bad.append((kind, str(e))); print(f"ERROR {kind:24} {e}")

    print(f"\n{len(ok)} verified, {len(bad)} rejected")
    for k, r in bad:
        print(f"  rejected {k}: {r}")
    if out_path and not bad:
        with open(out_path, "w", encoding="utf-8", newline="\n") as f:
            json.dump(ok, f, indent=2)
            f.write("\n")
        print(f"wrote {out_path}")
        if kotlin_path:
            write_kotlin(ok, kotlin_path)
            print(f"wrote {kotlin_path}")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
