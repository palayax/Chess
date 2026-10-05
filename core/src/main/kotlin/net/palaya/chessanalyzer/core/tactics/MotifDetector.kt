package net.palaya.chessanalyzer.core.tactics

import net.palaya.chessanalyzer.core.analysis.SeeEvaluator
import net.palaya.chessanalyzer.core.analysis.TacticInstance
import net.palaya.chessanalyzer.core.analysis.TacticType
import net.palaya.chessanalyzer.core.analysis.TacticsDetector
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Move
import net.palaya.chessanalyzer.core.chess.Piece
import net.palaya.chessanalyzer.core.chess.PieceType
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.Square
import net.palaya.chessanalyzer.core.chess.moveToSan
import net.palaya.chessanalyzer.core.chess.parseUci

/**
 * Detects the ANALYSIS_SPEC 5.3 motifs created by one candidate move.
 *
 * Two ideas run through every detector here and are worth stating once:
 *
 * **Newness.** Almost every motif is filtered against the position *before* the move. A pin
 * that already existed is not something this move did, and reporting it on every subsequent
 * ply would bury the real tactics in noise. The detectors therefore diff before/after
 * (attack pairs, pin triples, battery triples) rather than reading the after-position alone.
 *
 * **Qualification.** "Attacks two pieces" is not a fork. The targets have to be worth
 * taking - the king, something at least as valuable as the forker, or something undefended -
 * and the whole operation has to show a material profit once SEE has charged us for what we
 * put en prise. That is what stops the classic false positive of a queen "forking" two
 * defended pawns.
 *
 * The PV-dependent motifs (deflection, decoy, overload, interference, clearance,
 * zwischenzug, windmill, Greek gift, removing the defender) genuinely replay `pvUci` on
 * copies of the position and check that the exploiting move is actually played. With no PV
 * they report nothing at all rather than guessing.
 */
class MotifDetector(private val see: SeeEvaluator = StaticExchangeEvaluator()) : TacticsDetector {

    /**
     * What the app reports for [move]: every motif the detectors found ([detectRaw]), reduced to
     * the few that are worth saying (ANALYSIS_SPEC 5.3 "Reporting"). The detectors are
     * deliberately generous - one move routinely trips several of them for the same underlying
     * fact - and a presentation that lists all of them narrates nonsense: a rook that delivers
     * mate was a "fork", a "double attack", a "hanging piece" and a "skewer" as well.
     *
     *  1. A **checkmating move** is described by its mating pattern ([MATING_TYPES]) and by
     *     nothing else.
     *  2. A motif that merely restates another one on the same move is dropped: a
     *     [TacticType.HANGING_PIECE] on a square another motif already names *and accounts for at
     *     least as much material* (the fork or pin is the mechanism, the hanging piece is its
     *     consequence; an x-ray worth nothing explains nothing), a [TacticType.DOUBLE_ATTACK] that a
     *     fork of the same targets already covers, and a plain [TacticType.PROMOTION_TACTIC] next
     *     to the [TacticType.UNDERPROMOTION] that is the interesting half of the same move.
     *  3. What is left is ranked - mating motifs first, then confidence, then material swing - and
     *     at most [MAX_TACTICS_PER_MOVE] survive.
     */
    override fun detect(position: Position, move: Move, pvUci: List<String>): List<TacticInstance> {
        val raw = detectRaw(position, move, pvUci)
        val mates = raw.filter { it.type in MATING_TYPES }
        val relevant = if (position.makeMove(move).isCheckmate()) mates else dropRedundant(raw)
        return relevant
            .sortedWith(
                compareByDescending<TacticInstance> { it.type in MATING_TYPES }
                    .thenByDescending { it.confidence }
                    .thenByDescending { it.materialSwing }
            )
            .take(MAX_TACTICS_PER_MOVE)
    }

    private fun dropRedundant(all: List<TacticInstance>): List<TacticInstance> {
        val forkTargets = all
            .filter { it.type == TacticType.FORK || it.type == TacticType.PAWN_FORK }
            .flatMapTo(HashSet()) { it.targetSquares }
        val withoutCoveredDoubleAttacks = all.filterNot {
            it.type == TacticType.DOUBLE_ATTACK && it.targetSquares.isNotEmpty() &&
                forkTargets.containsAll(it.targetSquares)
        }
        val withoutPlainPromotion =
            if (withoutCoveredDoubleAttacks.any { it.type == TacticType.UNDERPROMOTION }) {
                withoutCoveredDoubleAttacks.filterNot { it.type == TacticType.PROMOTION_TACTIC }
            } else {
                withoutCoveredDoubleAttacks
            }
        return withoutPlainPromotion.filterNot { hanging ->
            hanging.type == TacticType.HANGING_PIECE &&
                withoutPlainPromotion.any { other ->
                    other !== hanging && other.type != TacticType.HANGING_PIECE &&
                        other.materialSwing >= hanging.materialSwing &&
                        hanging.targetSquares.any { it in other.targetSquares }
                }
        }
    }

    /**
     * Every motif the detectors recognise on [move], unreduced: no mate suppression, no
     * redundancy filter, no cap. This is what the detector tests and the reference-corpus
     * cross-check assert against, because they are about *detection*; [detect] is the
     * presentation of it.
     */
    fun detectRaw(position: Position, move: Move, pvUci: List<String>): List<TacticInstance> {
        val c = Ctx(position, move, pvUci)
        val out = ArrayList<TacticInstance>(8)

        detectFork(c, out)
        detectDoubleAttack(c, out)
        detectLineMotifs(c, out)
        detectDiscoveries(c, out)
        detectHanging(c, out)
        detectTrapped(c, out)
        detectMating(c, out)
        detectPromotion(c, out)
        detectPassedPawn(c, out)
        detectDesperado(c, out)
        detectPerpetual(c, out)
        detectStalemateTrick(c, out)
        detectFortress(c, out)
        if (c.hasPv) detectPvMotifs(c, out)

        return out
            .filter { it.confidence >= MIN_CONFIDENCE }
            .distinctBy { Triple(it.type, it.targetSquares.toSet(), it.involvedSquares.toSet()) }
            .sortedWith(
                compareByDescending<TacticInstance> { it.confidence }
                    .thenByDescending { it.materialSwing }
            )
    }

    // -----------------------------------------------------------------------
    // Shared per-call state
    // -----------------------------------------------------------------------

    /**
     * Everything the detectors need about one (position, move, pv) triple, computed once.
     * `detect` is called roughly twice per ply for a whole game, so the expensive things -
     * the after-position, the attack-pair diffs, the replayed PV - are built here exactly
     * once and shared, and the genuinely costly searches stay behind cheap gates.
     */
    private inner class Ctx(val before: Position, val move: Move, val pvUci: List<String>) {
        val us: Color = before.pieceAt(move.from)?.color ?: move.color
        val them: Color = us.opposite()
        val after: Position = before.makeMove(move)
        val landing: Square = move.to
        val moverType: PieceType = move.promotion ?: move.piece
        val moverValue: Int = valueOf(moverType)
        val uci: String = move.toUci()
        val moveSee: Int = see.see(before, move)
        val san: String by lazy { before.moveToSan(move) }

        /**
         * Squares this move emptied. A discovered attack has to run through one of these,
         * which is also what keeps the castling rook's new lines from being mistaken for a
         * discovery, and what lets an en-passant capture open a line the way it really does.
         */
        val vacated: List<Square> = buildList {
            add(move.from)
            if (move.isEnPassant) add(Square.of(move.to.file, move.from.rank))
            if (move.isCastle) add(Square.of(if (move.isCastleKingside) 7 else 0, move.from.rank))
        }

        val pairsBefore: Set<Pair<Square, Square>> by lazy { Attacks.attackPairs(before, us) }
        val pairsAfter: Set<Pair<Square, Square>> by lazy { Attacks.attackPairs(after, us) }

        val hasPv: Boolean = pvUci.isNotEmpty()

        /** The PV replayed as real moves, always normalised so index 0 is [move]. */
        val pvMoves: List<Move>
        /** `pvPositions[i]` is the position *before* `pvMoves[i]`; the list is one longer. */
        val pvPositions: List<Position>

        init {
            val moves = ArrayList<Move>()
            val positions = ArrayList<Position>()
            if (hasPv) {
                // Engines normally hand back a PV that starts with the move being judged, but
                // an adapter may strip it; accept both spellings rather than silently
                // mis-aligning every "our move / their move" index below.
                val line = if (pvUci.firstOrNull() == uci) pvUci else listOf(uci) + pvUci
                var p = before
                for (u in line) {
                    val m = try { p.parseUci(u) } catch (e: Exception) { break }
                    positions.add(p)
                    moves.add(m)
                    p = p.makeMove(m)
                }
                positions.add(p)
            }
            pvMoves = moves
            pvPositions = positions
        }

        /** Our moves in the PV sit at even indices; the opponent's at odd ones. */
        fun ourPvMove(index: Int): Move? = pvMoves.getOrNull(index).takeIf { index % 2 == 0 }
        fun theirPvMove(index: Int): Move? = pvMoves.getOrNull(index).takeIf { index % 2 == 1 }
        fun positionBeforePly(index: Int): Position? = pvPositions.getOrNull(index)
        fun positionAfterPly(index: Int): Position? = pvPositions.getOrNull(index + 1)
    }

    // -----------------------------------------------------------------------
    // Small shared judgements
    // -----------------------------------------------------------------------

    /** ANALYSIS_SPEC 5.2's hanging test asked on behalf of a side that may not be to move. */
    private fun winnableBy(position: Position, square: Square, byColor: Color): Boolean {
        val victim = position.pieceAt(square) ?: return false
        if (victim.color == byColor) return false
        val attackers = Attacks.attackersOf(position, square, byColor)
        if (attackers.isEmpty()) return false
        val cheapest = attackers.minByOrNull { valueOf(position.pieceAt(it)!!.type) }!!
        return see.see(position, Attacks.captureMove(position, cheapest, square)) >= 0
    }

    private fun bestCaptureSee(position: Position, square: Square, byColor: Color): Int {
        val victim = position.pieceAt(square) ?: return 0
        if (victim.color == byColor) return 0
        val attackers = Attacks.attackersOf(position, square, byColor)
        if (attackers.isEmpty()) return 0
        return attackers.maxOf { see.see(position, Attacks.captureMove(position, it, square)) }
    }

    /**
     * Hands the move back to the other side without playing anything, so we can ask "what
     * would I do if it were my turn again?" - the cheapest honest way to test a mate threat.
     * Returns null while in check, where passing is not a meaningful question.
     */
    private fun passTurn(position: Position): Position? {
        if (position.isInCheck()) return null
        val f = position.toFen().split(" ").toMutableList()
        f[1] = if (position.sideToMove == Color.WHITE) "b" else "w"
        f[3] = "-" // an en-passant right cannot survive a skipped move
        return try { Position.fromFen(f.joinToString(" ")) } catch (e: Exception) { null }
    }

    /** True when the PV plays one of our moves onto one of [squares] within the next 4 plies. */
    private fun pvConfirms(c: Ctx, squares: Collection<Square>): Boolean {
        if (!c.hasPv || squares.isEmpty()) return false
        for (i in intArrayOf(2, 4)) {
            val m = c.ourPvMove(i) ?: continue
            if (m.to in squares) return true
        }
        return false
    }

    private fun confidence(confirmed: Boolean): Double = if (confirmed) PV_CONFIDENCE else STATIC_CONFIDENCE

    /**
     * What we can expect to bank when several enemy units are attacked at once: the opponent
     * saves the most valuable one, so we collect the next best. A check is different - it
     * must be answered, so the other target is simply taken.
     */
    private fun swingAcrossTargets(winnables: List<Int>, includesKing: Boolean): Int {
        val sorted = winnables.sortedDescending()
        return when {
            includesKing -> sorted.firstOrNull() ?: 0
            sorted.size >= 2 -> sorted[1]
            else -> 0
        }
    }

    /** Centipawns we could take off [square] if the opponent were forced to leave it there. */
    private fun winnableValue(position: Position, square: Square, byColor: Color): Int {
        val piece = position.pieceAt(square) ?: return 0
        if (piece.type == PieceType.KING) return 0
        val value = valueOf(piece.type)
        val defenders = Attacks.attackersOf(position, square, piece.color)
        if (defenders.isEmpty()) return value
        val attackers = Attacks.attackersOf(position, square, byColor)
        if (attackers.isEmpty()) return 0
        val cheapest = attackers.minOf { valueOf(position.pieceAt(it)!!.type) }
        return maxOf(0, value - cheapest)
    }

    // -----------------------------------------------------------------------
    // FORK / PAWN_FORK
    // -----------------------------------------------------------------------

    /**
     * True when the opponent can take the piece that just moved without losing material - an even
     * trade counts, because the threats it made die with it and nothing was won by them.
     */
    private fun forkerCapturedForFree(c: Ctx): Boolean =
        Attacks.attackersOf(c.after, c.landing, c.them).isNotEmpty() &&
            bestCaptureSee(c.after, c.landing, c.them) >= 0

    private fun detectFork(c: Ctx, out: MutableList<TacticInstance>) {
        if (c.move.isCastle) return
        val targets = Attacks.attackedEnemiesFrom(c.after, c.landing)
        if (targets.size < 2) return

        val qualifying = ArrayList<Square>(targets.size)
        val winnables = ArrayList<Int>(targets.size)
        var includesKing = false
        for (sq in targets) {
            val piece = c.after.pieceAt(sq)!!
            val value = valueOf(piece.type)
            val defended = Attacks.attackersOf(c.after, sq, c.them).isNotEmpty()
            val isKing = piece.type == PieceType.KING
            // ANALYSIS_SPEC 5.3: a target only counts if taking it is actually attractive.
            if (!(isKing || value >= c.moverValue || !defended)) continue
            qualifying.add(sq)
            if (isKing) {
                includesKing = true
            } else {
                winnables.add(if (defended) maxOf(0, value - c.moverValue) else value)
            }
        }
        if (qualifying.size < 2) return

        // The fork only works if the forker survives to cash it in. When the opponent can simply
        // take the forking piece without losing material (an even trade counts: the fork is gone
        // and nothing was won by it), what happened is a capture or an exchange, not a fork. This
        // is the classic Bxd7+ Nxd7 "fork" of king and queen, where the bishop is simply taken.
        if (forkerCapturedForFree(c)) return

        val swing = swingAcrossTargets(winnables, includesKing)
        // The forker may itself be en prise; a fork that does not survive the recapture is
        // not a fork worth reporting.
        val net = swing + minOf(0, c.moveSee)
        if (net <= 0) return

        val names = qualifying.map { named(c.after.pieceAt(it)!!, it) }
        val type = if (c.moverType == PieceType.PAWN) TacticType.PAWN_FORK else TacticType.FORK
        val confirmed = pvConfirms(c, qualifying)
        out.add(
            TacticInstance(
                type = type,
                byColor = c.us,
                moveUci = c.uci,
                targetSquares = qualifying,
                involvedSquares = listOf(c.landing),
                materialSwing = net,
                description = "${capitalise(nounFor(c.moverType))} on ${c.landing} forks " +
                    "${joinNatural(names)}.",
                confidence = confidence(confirmed)
            )
        )
    }

    // -----------------------------------------------------------------------
    // DOUBLE_ATTACK
    // -----------------------------------------------------------------------

    private fun detectDoubleAttack(c: Ctx, out: MutableList<TacticInstance>) {
        val hitBefore = c.pairsBefore.mapTo(HashSet()) { it.second }
        val hitAfter = c.pairsAfter.mapTo(HashSet()) { it.second }
        val fresh = (hitAfter - hitBefore).filter { sq ->
            val piece = c.after.pieceAt(sq)!!
            piece.type == PieceType.KING ||
                valueOf(piece.type) >= 300 ||
                Attacks.attackersOf(c.after, sq, c.them).isEmpty()
        }
        if (fresh.size < 2) return

        val includesKing = fresh.any { c.after.pieceAt(it)!!.type == PieceType.KING }
        val winnables = fresh.filter { c.after.pieceAt(it)!!.type != PieceType.KING }
            .map { winnableValue(c.after, it, c.us) }
        val swing = swingAcrossTargets(winnables, includesKing) + minOf(0, c.moveSee)
        if (swing <= 0) return

        val attackers = c.pairsAfter.filter { it.second in fresh }.map { it.first }.distinct()
        // Same survival test as a fork: when the moved piece is the only attacker and can simply
        // be taken, the "double attack" ends with the next reply.
        if (attackers == listOf(c.landing) && forkerCapturedForFree(c)) return
        out.add(
            TacticInstance(
                type = TacticType.DOUBLE_ATTACK,
                byColor = c.us,
                moveUci = c.uci,
                targetSquares = fresh,
                involvedSquares = attackers,
                materialSwing = swing,
                description = "${c.san} attacks " +
                    "${joinNatural(fresh.map { named(c.after.pieceAt(it)!!, it) })} at the same time.",
                confidence = confidence(pvConfirms(c, fresh))
            )
        )
    }

    // -----------------------------------------------------------------------
    // PIN_ABSOLUTE / PIN_RELATIVE / SKEWER / X_RAY / BATTERY
    // -----------------------------------------------------------------------

    /** A slider, the first piece on its ray, and whatever stands directly behind that piece. */
    private data class Triple3(val slider: Square, val front: Square, val rear: Square)

    /**
     * Walks every ray of every [byColor] slider and yields (slider, first piece, next piece).
     * Pins, skewers, x-rays and batteries are all the same geometric shape and differ only in
     * who owns the front and rear pieces and how they compare in value, so they share one scan.
     */
    private fun sliderTriples(position: Position, byColor: Color): List<Triple3> {
        val out = ArrayList<Triple3>(8)
        for (sq in Attacks.piecesOf(position, byColor)) {
            val piece = position.pieceAt(sq)!!
            if (!isSlider(piece.type)) continue
            for (dir in slidingDirections(piece.type)) {
                val front = firstOccupied(position, sq, dir) ?: continue
                val rear = firstOccupied(position, front, dir) ?: continue
                out.add(Triple3(sq, front, rear))
            }
        }
        return out
    }

    private fun detectLineMotifs(c: Ctx, out: MutableList<TacticInstance>) {
        val beforeTriples = sliderTriples(c.before, c.us).toSet()
        val afterTriples = sliderTriples(c.after, c.us)
        val reportedRear = HashSet<Pair<Square, Square>>()

        for (t in afterTriples) {
            if (t in beforeTriples) continue // not created by this move
            val front = c.after.pieceAt(t.front)!!
            val rear = c.after.pieceAt(t.rear)!!
            val sliderPiece = c.after.pieceAt(t.slider)!!

            if (front.color == c.them && rear.color == c.them) {
                val frontValue = valueOf(front.type)
                val rearValue = valueOf(rear.type)
                if (front.type == PieceType.KING) {
                    // The king in front is a check, not a pin; only the skewer reading applies.
                    reportedRear.add(t.slider to t.rear)
                    out.add(skewer(c, t, sliderPiece, front, rear))
                    continue
                }
                if (rear.type == PieceType.KING) {
                    reportedRear.add(t.slider to t.rear)
                    out.add(pin(c, t, sliderPiece, front, rear, absolute = true))
                    continue
                }
                if (rearValue > frontValue) {
                    reportedRear.add(t.slider to t.rear)
                    out.add(pin(c, t, sliderPiece, front, rear, absolute = false))
                    continue
                }
                // Front at least as valuable as the rear piece and worth enough to have to
                // move: that is a skewer, the mirror image of the pin above.
                if (frontValue >= rearValue && frontValue >= valueOf(PieceType.ROOK)) {
                    reportedRear.add(t.slider to t.rear)
                    out.add(skewer(c, t, sliderPiece, front, rear))
                    continue
                }
            }

            // A battery is the same shape as an x-ray but with our own slider in front, and
            // it is the more useful thing to say, so it is tested first and suppresses the
            // x-ray report for the same pair.
            var isBattery = false
            if (front.color == c.us && rear.color == c.them &&
                isSlider(front.type) && isSlider(sliderPiece.type)
            ) {
                val dir = directionBetween(t.slider, t.front)
                if (dir != null && slidesAlong(front.type, dir) && slidesAlong(sliderPiece.type, dir)) {
                    isBattery = true
                    out.add(
                        TacticInstance(
                            type = TacticType.BATTERY,
                            byColor = c.us,
                            moveUci = c.uci,
                            targetSquares = listOf(t.rear),
                            involvedSquares = listOf(t.slider, t.front),
                            materialSwing = 0,
                            description = "${capitalise(named(sliderPiece, t.slider))} and " +
                                "${named(front, t.front)} form a battery on " +
                                "${lineName(t.slider, t.front)} aimed at ${named(rear, t.rear)}.",
                            confidence = confidence(pvConfirms(c, listOf(t.rear)))
                        )
                    )
                }
            }

            if (!isBattery && rear.color == c.them && (t.slider to t.rear) !in reportedRear) {
                // Anything else that sees an enemy piece through exactly one blocker is an
                // x-ray: typically our own piece in front, which is a latent threat rather
                // than an immediate one, hence the lower priority in the spec.
                out.add(
                    TacticInstance(
                        type = TacticType.X_RAY,
                        byColor = c.us,
                        moveUci = c.uci,
                        targetSquares = listOf(t.rear),
                        involvedSquares = listOf(t.slider, t.front),
                        materialSwing = 0,
                        description = "${capitalise(named(sliderPiece, t.slider))} x-rays " +
                            "${named(rear, t.rear)} through ${named(front, t.front)}.",
                        confidence = confidence(pvConfirms(c, listOf(t.rear)))
                    )
                )
            }

        }
    }

    private fun pin(
        c: Ctx, t: Triple3, slider: Piece, front: Piece, rear: Piece, absolute: Boolean
    ): TacticInstance {
        // A pinned piece cannot run, so it is effectively worth its full value to us once we
        // attack it more often than it is defended.
        val attackers = Attacks.attackersOf(c.after, t.front, c.us).size
        val defenders = Attacks.attackersOf(c.after, t.front, c.them).size
        val swing = if (attackers > defenders) valueOf(front.type) else 0
        return TacticInstance(
            type = if (absolute) TacticType.PIN_ABSOLUTE else TacticType.PIN_RELATIVE,
            byColor = c.us,
            moveUci = c.uci,
            targetSquares = listOf(t.front),
            involvedSquares = listOf(t.slider, t.rear),
            materialSwing = swing,
            description = "${capitalise(named(slider, t.slider))} pins ${named(front, t.front)} " +
                "against ${named(rear, t.rear)}.",
            confidence = confidence(pvConfirms(c, listOf(t.front)))
        )
    }

    private fun skewer(c: Ctx, t: Triple3, slider: Piece, front: Piece, rear: Piece): TacticInstance {
        val defended = Attacks.attackersOf(c.after, t.rear, c.them).isNotEmpty()
        val swing =
            if (defended) maxOf(0, valueOf(rear.type) - valueOf(slider.type)) else valueOf(rear.type)
        return TacticInstance(
            type = TacticType.SKEWER,
            byColor = c.us,
            moveUci = c.uci,
            targetSquares = listOf(t.front, t.rear),
            involvedSquares = listOf(t.slider),
            materialSwing = swing,
            description = "${capitalise(named(slider, t.slider))} skewers ${named(front, t.front)}; " +
                "when it moves, ${named(rear, t.rear)} behind it is attacked.",
            confidence = confidence(pvConfirms(c, listOf(t.rear)))
        )
    }

    // -----------------------------------------------------------------------
    // DISCOVERED_ATTACK / DISCOVERED_CHECK / DOUBLE_CHECK
    // -----------------------------------------------------------------------

    private fun detectDiscoveries(c: Ctx, out: MutableList<TacticInstance>) {
        val theirKing = c.after.kingSquare(c.them)

        for ((attacker, target) in c.pairsAfter - c.pairsBefore) {
            if (attacker == c.landing) continue // the moved piece itself is not a discovery
            val piece = c.after.pieceAt(attacker) ?: continue
            if (!isSlider(piece.type)) continue
            // The new line has to run through a square this move emptied - otherwise the
            // attack appeared for some other reason (a capture, a castling rook) and calling
            // it "discovered" would be wrong.
            if (c.vacated.none { isBetween(attacker, it, target) }) continue

            val victim = c.after.pieceAt(target)!!
            if (target == theirKing) {
                out.add(
                    TacticInstance(
                        type = TacticType.DISCOVERED_CHECK,
                        byColor = c.us,
                        moveUci = c.uci,
                        targetSquares = listOf(target),
                        involvedSquares = listOf(attacker, c.landing),
                        materialSwing = 0,
                        description = "Moving off ${c.move.from} uncovers a check from " +
                            "${named(piece, attacker)}.",
                        confidence = PV_CONFIDENCE // a check is not a guess
                    )
                )
            } else {
                val swing = winnableValue(c.after, target, c.us)
                out.add(
                    TacticInstance(
                        type = TacticType.DISCOVERED_ATTACK,
                        byColor = c.us,
                        moveUci = c.uci,
                        targetSquares = listOf(target),
                        involvedSquares = listOf(attacker, c.landing),
                        materialSwing = swing,
                        description = "Moving off ${c.move.from} uncovers ${named(piece, attacker)}, " +
                            "which now hits ${named(victim, target)}.",
                        confidence = confidence(pvConfirms(c, listOf(target)))
                    )
                )
            }
        }

        val checkers = Attacks.attackersOf(c.after, theirKing, c.us)
        if (checkers.size >= 2) {
            out.add(
                TacticInstance(
                    type = TacticType.DOUBLE_CHECK,
                    byColor = c.us,
                    moveUci = c.uci,
                    targetSquares = listOf(theirKing),
                    involvedSquares = checkers,
                    materialSwing = 0,
                    description = "${capitalise(named(c.after, theirKing))} is in double check from " +
                        joinNatural(checkers.map { named(c.after.pieceAt(it)!!, it) }) +
                        " - only a king move can answer it.",
                    confidence = PV_CONFIDENCE
                )
            )
        }
    }

    // -----------------------------------------------------------------------
    // HANGING_PIECE
    // -----------------------------------------------------------------------

    private fun detectHanging(c: Ctx, out: MutableList<TacticInstance>) {
        for (sq in Attacks.piecesOf(c.after, c.them)) {
            val piece = c.after.pieceAt(sq)!!
            if (piece.type == PieceType.KING) continue
            val value = valueOf(piece.type)
            val best = bestCaptureSee(c.after, sq, c.us)
            val hanging = winnableBy(c.after, sq, c.us)
            if (!((hanging && value >= 300) || best > 0)) continue

            // Only report what this move created: a piece that was already loose is not news.
            val wasLoose = c.before.pieceAt(sq)?.let {
                it.color == c.them && it.type == piece.type && winnableBy(c.before, sq, c.us)
            } ?: false
            if (wasLoose) continue

            val undefended = Attacks.attackersOf(c.after, sq, c.them).isEmpty()
            // What the board shows, no more: the piece is attacked and (nothing defends it | taking
            // it would win material). The opponent is to move and may save it, so "can be taken for
            // nothing" / "cannot be held" were claims about a future the detector never checked.
            val text = if (undefended) {
                "${capitalise(named(piece, sq))} is attacked and nothing defends it."
            } else {
                "${capitalise(named(piece, sq))} is attacked, and taking it would win material."
            }
            out.add(
                TacticInstance(
                    type = TacticType.HANGING_PIECE,
                    byColor = c.us,
                    moveUci = c.uci,
                    targetSquares = listOf(sq),
                    involvedSquares = Attacks.attackersOf(c.after, sq, c.us),
                    materialSwing = maxOf(best, 0),
                    description = text,
                    confidence = confidence(pvConfirms(c, listOf(sq)))
                )
            )
        }
    }

    // -----------------------------------------------------------------------
    // TRAPPED_PIECE
    // -----------------------------------------------------------------------

    private fun detectTrapped(c: Ctx, out: MutableList<TacticInstance>) {
        // "No safe square" is a statement about a piece's own mobility, so it is only meaningful
        // when the opponent is free to move it. While their king is in check the legal-move list
        // is whatever answers the check, and every other piece looks "trapped" simply because it
        // is not allowed to move at all. A checking move therefore traps nothing.
        if (c.after.isInCheck()) return
        val theirMoves = c.after.legalMoves()
        val movedControls = Attacks.attacksFrom(c.after, c.landing).toHashSet()

        for (sq in Attacks.piecesOf(c.after, c.them)) {
            val piece = c.after.pieceAt(sq)!!
            val value = valueOf(piece.type)
            if (piece.type == PieceType.KING || value < 300) continue

            val escapes = theirMoves.filter { it.from == sq }
            if (escapes.isEmpty()) continue // immobile is not the same as trapped
            if (escapes.any { see.see(c.after, it) >= 0 }) continue

            // Nowhere safe to go only matters if we can actually collect the piece - either
            // right now, or by bringing an attacker up next move while it still cannot run.
            val attackedNow = Attacks.isAttackedBy(c.after, sq, c.us)
            val best = bestCaptureSee(c.after, sq, c.us)
            val collectable = if (attackedNow) best > 0 else canAttackNextMove(c, sq)
            if (!collectable) continue

            // Insist that this move is what did the trapping: it attacks the piece, takes a
            // flight square away, or steps onto a square the piece was covering. Without
            // this a piece that was already in the net is re-reported on every later ply.
            val didIt = movedControls.contains(sq) ||
                escapes.any { movedControls.contains(it.to) } ||
                c.landing in Attacks.attacksFrom(c.before, sq)
            if (!didIt) continue

            out.add(
                TacticInstance(
                    type = TacticType.TRAPPED_PIECE,
                    byColor = c.us,
                    moveUci = c.uci,
                    targetSquares = listOf(sq),
                    involvedSquares = Attacks.attackersOf(c.after, sq, c.us) + c.landing,
                    materialSwing = if (best > 0) best else value,
                    description = "${capitalise(named(piece, sq))} is trapped - every square it " +
                        "can reach loses material.",
                    confidence = confidence(pvConfirms(c, listOf(sq)))
                )
            )
        }
    }

    /**
     * The other half of "trapped": the classic ...Bxa2 b3! leaves the bishop with nowhere to
     * go while nothing is attacking it yet. Asks whether we have a safe move that brings an
     * attacker to bear, which is what makes the net close.
     */
    private fun canAttackNextMove(c: Ctx, square: Square): Boolean {
        val passed = passTurn(c.after) ?: return false
        return passed.legalMoves().any { m ->
            m.to != square &&
                see.see(passed, m) >= 0 &&
                square in Attacks.attacksFrom(passed.makeMove(m), m.to) &&
                winnableBy(passed.makeMove(m), square, c.us)
        }
    }

    // -----------------------------------------------------------------------
    // BACK_RANK_MATE / SMOTHERED_MATE / MATE_NET
    // -----------------------------------------------------------------------

    private fun detectMating(c: Ctx, out: MutableList<TacticInstance>) {
        val kingSq = c.after.kingSquare(c.them)
        val isMate = c.after.isCheckmate()

        if (isMate) {
            out.add(
                TacticInstance(
                    type = TacticType.MATE_NET,
                    byColor = c.us,
                    moveUci = c.uci,
                    targetSquares = listOf(kingSq),
                    involvedSquares = Attacks.attackersOf(c.after, kingSq, c.us),
                    materialSwing = MATE_SWING,
                    description = "${c.san} is checkmate.",
                    confidence = PV_CONFIDENCE
                )
            )
        } else if (c.hasPv && pvEndsInMateByUs(c)) {
            out.add(
                TacticInstance(
                    type = TacticType.MATE_NET,
                    byColor = c.us,
                    moveUci = c.uci,
                    targetSquares = listOf(kingSq),
                    involvedSquares = listOf(c.landing),
                    materialSwing = MATE_SWING,
                    description = "${c.san} begins a forced mate.",
                    confidence = PV_CONFIDENCE
                )
            )
        }

        detectSmothered(c, out, kingSq, isMate)
        detectBackRank(c, out, kingSq, isMate)
    }

    private fun pvEndsInMateByUs(c: Ctx): Boolean {
        val last = c.pvPositions.lastOrNull() ?: return false
        return c.pvMoves.size <= MATE_PV_PLIES && last.isCheckmate() && last.sideToMove == c.them
    }

    private fun detectSmothered(c: Ctx, out: MutableList<TacticInstance>, kingSq: Square, isMate: Boolean) {
        if (!isMate) return
        val checkers = Attacks.attackersOf(c.after, kingSq, c.us)
        if (checkers.size != 1) return
        if (c.after.pieceAt(checkers[0])!!.type != PieceType.KNIGHT) return
        val ringOccupiedByOwn = neighbours(kingSq).all { c.after.pieceAt(it)?.color == c.them }
        if (!ringOccupiedByOwn) return
        out.add(
            TacticInstance(
                type = TacticType.SMOTHERED_MATE,
                byColor = c.us,
                moveUci = c.uci,
                targetSquares = listOf(kingSq),
                involvedSquares = checkers,
                materialSwing = MATE_SWING,
                description = "${c.san} is smothered mate - ${named(c.after, kingSq)} is hemmed " +
                    "in by its own pieces.",
                confidence = PV_CONFIDENCE
            )
        )
    }

    private fun detectBackRank(c: Ctx, out: MutableList<TacticInstance>, kingSq: Square, isMate: Boolean) {
        val backRank = if (c.them == Color.WHITE) 0 else 7
        if (kingSq.rank != backRank) return

        // ANALYSIS_SPEC 5.3 wants the escape squares shut by the king's own pawns, which is
        // exactly what makes luft the refutation of the whole pattern.
        val forward = if (c.them == Color.WHITE) 1 else -1
        val escapes = (-1..1).mapNotNull { df ->
            val f = kingSq.file + df
            val r = kingSq.rank + forward
            if (f in 0..7 && r in 0..7) Square.of(f, r) else null
        }
        if (escapes.isEmpty()) return
        val shut = escapes.all { c.after.pieceAt(it)?.color == c.them }
        val byPawn = escapes.any { c.after.pieceAt(it)?.type == PieceType.PAWN }
        if (!shut || !byPawn) return

        if (isMate) {
            val checkers = Attacks.attackersOf(c.after, kingSq, c.us)
            val heavy = checkers.filter {
                val t = c.after.pieceAt(it)!!.type
                (t == PieceType.ROOK || t == PieceType.QUEEN) && it.rank == backRank
            }
            if (heavy.isEmpty()) return
            out.add(
                TacticInstance(
                    type = TacticType.BACK_RANK_MATE,
                    byColor = c.us,
                    moveUci = c.uci,
                    targetSquares = listOf(kingSq),
                    involvedSquares = heavy,
                    materialSwing = MATE_SWING,
                    description = "${c.san} is mate on the back rank - ${named(c.after, kingSq)} is " +
                        "walled in by its own pawns.",
                    confidence = PV_CONFIDENCE
                )
            )
            return
        }

        // Not mate yet: is it mate next move if the opponent were to do nothing? That is the
        // spec's "mate threat within 2", and it is cheap because only heavy-piece moves onto
        // the back rank can possibly be the mate.
        val passed = passTurn(c.after) ?: return
        val threat = passed.legalMoves().firstOrNull { m ->
            m.to.rank == backRank &&
                (m.piece == PieceType.ROOK || m.piece == PieceType.QUEEN) &&
                passed.makeMove(m).isCheckmate()
        } ?: return
        out.add(
            TacticInstance(
                type = TacticType.BACK_RANK_MATE,
                byColor = c.us,
                moveUci = c.uci,
                targetSquares = listOf(kingSq),
                involvedSquares = listOf(threat.from, threat.to),
                materialSwing = MATE_SWING,
                description = "${capitalise(named(c.after, kingSq))} is boxed in on the back rank " +
                    "by its own pawns; ${passed.moveToSan(threat)} mates.",
                confidence = STATIC_CONFIDENCE
            )
        )
    }

    private fun neighbours(square: Square): List<Square> =
        KING_DELTAS.mapNotNull { (df, dr) ->
            val f = square.file + df
            val r = square.rank + dr
            if (f in 0..7 && r in 0..7) Square.of(f, r) else null
        }

    // -----------------------------------------------------------------------
    // PROMOTION_TACTIC / UNDERPROMOTION
    // -----------------------------------------------------------------------

    private fun detectPromotion(c: Ctx, out: MutableList<TacticInstance>) {
        val promo = c.move.promotion
        if (promo != null) {
            val swing = valueOf(promo) - valueOf(PieceType.PAWN) + minOf(0, c.moveSee)
            out.add(
                TacticInstance(
                    type = TacticType.PROMOTION_TACTIC,
                    byColor = c.us,
                    moveUci = c.uci,
                    targetSquares = listOf(c.landing),
                    involvedSquares = listOf(c.move.from, c.landing),
                    materialSwing = maxOf(swing, 0),
                    description = "The pawn promotes to a ${nounFor(promo)} on ${c.landing}.",
                    confidence = confidence(c.hasPv && pvConfirms(c, listOf(c.landing)))
                )
            )
            if (promo != PieceType.QUEEN) detectUnderpromotion(c, out, promo)
            return
        }

        // A pawn that has reached the seventh with nothing able to take it is a promotion
        // threat in its own right, which is what the spec's "promotion tactic" covers.
        if (c.moverType != PieceType.PAWN) return
        val seventh = if (c.us == Color.WHITE) 6 else 1
        if (c.landing.rank != seventh) return
        if (!isPassedPawn(c.after, c.landing, c.us)) return
        if (winnableBy(c.after, c.landing, c.them)) return
        out.add(
            TacticInstance(
                type = TacticType.PROMOTION_TACTIC,
                byColor = c.us,
                moveUci = c.uci,
                targetSquares = listOf(Square.of(c.landing.file, if (c.us == Color.WHITE) 7 else 0)),
                involvedSquares = listOf(c.landing),
                materialSwing = valueOf(PieceType.QUEEN) - valueOf(PieceType.PAWN),
                description = "The pawn reaches ${c.landing} and cannot be stopped from queening.",
                confidence = STATIC_CONFIDENCE
            )
        )
    }

    private fun detectUnderpromotion(c: Ctx, out: MutableList<TacticInstance>, promo: PieceType) {
        // Promoting to less than a queen is only a tactic when it does something a queen
        // could not: give check, fork, or dodge a stalemate.
        val givesCheck = c.after.isInCheck(c.them)
        val hits = Attacks.attackedEnemiesFrom(c.after, c.landing).size
        val queenWouldStalemate = run {
            val queening = Move(
                c.move.from, c.move.to, PieceType.PAWN, c.us,
                promotion = PieceType.QUEEN,
                isCapture = c.move.isCapture, capturedPiece = c.move.capturedPiece
            )
            try { c.before.makeMove(queening).isStalemate() } catch (e: Exception) { false }
        }
        if (!givesCheck && hits < 2 && !queenWouldStalemate) return

        val reason = when {
            queenWouldStalemate -> "a queen would only be stalemate"
            givesCheck -> "it comes with check"
            else -> "it hits two pieces at once"
        }
        out.add(
            TacticInstance(
                type = TacticType.UNDERPROMOTION,
                byColor = c.us,
                moveUci = c.uci,
                targetSquares = Attacks.attackedEnemiesFrom(c.after, c.landing),
                involvedSquares = listOf(c.landing),
                materialSwing = if (queenWouldStalemate) MATE_SWING / 2 else valueOf(promo) - valueOf(PieceType.PAWN),
                description = "Underpromotion to a ${nounFor(promo)} on ${c.landing}: $reason.",
                confidence = confidence(c.hasPv && pvConfirms(c, listOf(c.landing)))
            )
        )
    }

    // -----------------------------------------------------------------------
    // PASSED_PAWN_BREAKTHROUGH
    // -----------------------------------------------------------------------

    private fun detectPassedPawn(c: Ctx, out: MutableList<TacticInstance>) {
        if (c.move.piece != PieceType.PAWN) return

        // Compared by file, not by square: simply pushing a pawn that was already passed
        // creates nothing, and reporting it would fire on half the pawn moves in an endgame.
        val passedFilesBefore = ourPassedPawns(c.before, c.us).mapTo(HashSet()) { it.file }
        val passedAfter = ourPassedPawns(c.after, c.us)
        val advanced = if (c.us == Color.WHITE) 4 else 3
        val fresh = passedAfter.filter { sq ->
            sq.file !in passedFilesBefore &&
                (if (c.us == Color.WHITE) sq.rank >= advanced else sq.rank <= advanced)
        }

        if (fresh.isNotEmpty()) {
            val pawn = fresh.first()
            val unstoppable = isOutsideSquareOfPawn(c.after, pawn, c.us)
            out.add(
                TacticInstance(
                    type = TacticType.PASSED_PAWN_BREAKTHROUGH,
                    byColor = c.us,
                    moveUci = c.uci,
                    targetSquares = listOf(pawn),
                    involvedSquares = listOf(c.landing),
                    materialSwing = if (unstoppable) valueOf(PieceType.QUEEN) - valueOf(PieceType.PAWN) else 0,
                    description = if (unstoppable) {
                        "${c.san} creates a passed pawn on $pawn that the king cannot catch."
                    } else {
                        "${c.san} creates a passed pawn on $pawn."
                    },
                    confidence = STATIC_CONFIDENCE
                )
            )
            return
        }

        // The textbook breakthrough (b6! axb6 c6! bxc6 a6) does not make a passer until two
        // moves later, so it needs a small search. It is only attempted in a pure pawn
        // ending, which is both where the motif lives and what keeps the branching tiny.
        if (!isPawnEnding(c.after)) return
        if (!Attacks.isAttackedBy(c.after, c.landing, c.them)) return
        if (breakthroughAfterCapture(c.after, c.us, BREAKTHROUGH_PLIES)) {
            out.add(
                TacticInstance(
                    type = TacticType.PASSED_PAWN_BREAKTHROUGH,
                    byColor = c.us,
                    moveUci = c.uci,
                    targetSquares = listOf(c.landing),
                    involvedSquares = listOf(c.landing),
                    materialSwing = valueOf(PieceType.QUEEN) - valueOf(PieceType.PAWN),
                    description = "${c.san} breaks through - taking the pawn lets another one " +
                        "run to the queening square.",
                    confidence = STATIC_CONFIDENCE
                )
            )
        }
    }

    private fun ourPassedPawns(position: Position, color: Color): Set<Square> =
        Attacks.piecesOf(position, color)
            .filter { position.pieceAt(it)!!.type == PieceType.PAWN && isPassedPawn(position, it, color) }
            .toSet()

    private fun isPassedPawn(position: Position, square: Square, color: Color): Boolean {
        val ahead = if (color == Color.WHITE) 1 else -1
        for (df in -1..1) {
            val f = square.file + df
            if (f !in 0..7) continue
            var r = square.rank + ahead
            while (r in 0..7) {
                val p = position.pieceAt(Square.of(f, r))
                if (p != null && p.type == PieceType.PAWN && p.color != color) return false
                r += ahead
            }
        }
        return true
    }

    /** Rule of the square: can the defending king step into the pawn's path in time? */
    private fun isOutsideSquareOfPawn(position: Position, pawn: Square, color: Color): Boolean {
        val queeningRank = if (color == Color.WHITE) 7 else 0
        val distance = kotlin.math.abs(queeningRank - pawn.rank)
        val enemyKing = position.kingSquare(color.opposite())
        val kingDistance = maxOf(
            kotlin.math.abs(enemyKing.file - pawn.file),
            kotlin.math.abs(enemyKing.rank - queeningRank)
        )
        // Only meaningful when nothing but kings and pawns is left to help.
        if (!isPawnEnding(position)) return false
        val tempo = if (position.sideToMove == color.opposite()) 0 else 1
        return kingDistance > distance + tempo
    }

    private fun isPawnEnding(position: Position): Boolean = (0..63).all {
        val p = position.pieceAt(Square(it))
        p == null || p.type == PieceType.PAWN || p.type == PieceType.KING
    }

    /**
     * Defender to move, having just been offered a pawn: does taking it always let the
     * attacker run another pawn through?
     *
     * Honest limits: only pawn captures are considered for the defender and only pawn moves
     * for the attacker, and a defender who simply declines is not evaluated at all. It is
     * therefore a proof that the *sacrifice, if accepted, works* - not a proof that the move
     * wins - which is why it is reported at static confidence.
     */
    private fun breakthroughAfterCapture(position: Position, us: Color, depth: Int): Boolean {
        if (depth <= 0) return false
        val captures = position.legalMoves().filter { it.piece == PieceType.PAWN && it.isCapture }
        if (captures.isEmpty()) return false
        return captures.all { pushesPawnThrough(position.makeMove(it), us, depth - 1) }
    }

    private fun pushesPawnThrough(position: Position, us: Color, depth: Int): Boolean {
        if (hasRunawayPasser(position, us)) return true
        if (depth <= 0) return false
        val pawnMoves = position.legalMoves().filter { it.piece == PieceType.PAWN }
        return pawnMoves.any { m ->
            val next = position.makeMove(m)
            hasRunawayPasser(next, us) || breakthroughAfterCapture(next, us, depth - 1)
        }
    }

    private fun hasRunawayPasser(position: Position, us: Color): Boolean =
        ourPassedPawns(position, us).any {
            if (us == Color.WHITE) it.rank >= 5 else it.rank <= 2
        }

    // -----------------------------------------------------------------------
    // DESPERADO
    // -----------------------------------------------------------------------

    private fun detectDesperado(c: Ctx, out: MutableList<TacticInstance>) {
        if (!c.move.isCapture) return
        if (c.moverValue < 300) return
        // A desperado gives the piece away on purpose. If the exchange breaks even this is
        // just a normal trade, however loose the piece looked beforehand.
        if (c.moveSee >= 0) return
        // The piece was already going to be lost where it stood ...
        if (!winnableBy(c.before, c.move.from, c.them)) return
        // ... and it is still going to be lost where it has landed, so this is selling it
        // dearly rather than saving it.
        if (!winnableBy(c.after, c.landing, c.them)) return
        val grabbed = c.move.capturedPiece ?: return

        out.add(
            TacticInstance(
                type = TacticType.DESPERADO,
                byColor = c.us,
                moveUci = c.uci,
                targetSquares = listOf(c.landing),
                involvedSquares = listOf(c.move.from, c.landing),
                materialSwing = valueOf(grabbed),
                description = "${capitalise(nounFor(c.moverType))} on ${c.move.from} was lost " +
                    "anyway, so it takes the ${nounFor(grabbed)} on ${c.landing} on the way down.",
                confidence = STATIC_CONFIDENCE
            )
        )
    }

    // -----------------------------------------------------------------------
    // PERPETUAL_CHECK
    // -----------------------------------------------------------------------

    private fun detectPerpetual(c: Ctx, out: MutableList<TacticInstance>) {
        if (!c.after.isInCheck(c.them)) return
        if (c.after.isCheckmate()) return
        val budget = intArrayOf(PERPETUAL_NODES)
        val seen = HashSet<Long>()
        seen.add(c.before.zobristKey)
        if (!checksNeverRunOut(c.after, c.us, seen, PERPETUAL_PLIES, budget)) return
        out.add(
            TacticInstance(
                type = TacticType.PERPETUAL_CHECK,
                byColor = c.us,
                moveUci = c.uci,
                targetSquares = listOf(c.after.kingSquare(c.them)),
                involvedSquares = listOf(c.landing),
                materialSwing = 0,
                description = "${c.san} starts a run of checks the king cannot escape - " +
                    "enough for a draw by repetition.",
                confidence = STATIC_CONFIDENCE
            )
        )
    }

    /**
     * Defender to move and in check: can the checking side keep checking whatever the
     * defender does, for [depth] more plies?
     *
     * This is a bounded approximation of a perpetual, not a proof of one: a horizon of a few
     * plies cannot distinguish "checks forever" from "checks for a while". Hitting a position
     * we have already seen with the checking side to move *is* a real repetition, so that
     * short-circuits to success; otherwise surviving the full depth is the evidence, and the
     * motif is reported at static confidence accordingly.
     */
    private fun checksNeverRunOut(
        position: Position, us: Color, seen: MutableSet<Long>, depth: Int, budget: IntArray
    ): Boolean {
        if (budget[0]-- <= 0) return false
        val replies = position.legalMoves()
        if (replies.isEmpty()) return false // mate or stalemate, not a perpetual
        return replies.all { reply ->
            val ours = position.makeMove(reply)
            if (!seen.add(ours.zobristKey)) return@all true // position repeated
            val result = if (depth <= 1) {
                false
            } else {
                ours.legalMoves().any { check ->
                    val next = ours.makeMove(check)
                    next.isInCheck(us.opposite()) &&
                        checksNeverRunOut(next, us, seen, depth - 2, budget)
                }
            }
            seen.remove(ours.zobristKey)
            result
        }
    }

    // -----------------------------------------------------------------------
    // STALEMATE_TRICK
    // -----------------------------------------------------------------------

    private fun detectStalemateTrick(c: Ctx, out: MutableList<TacticInstance>) {
        if (c.after.isStalemate()) {
            out.add(
                TacticInstance(
                    type = TacticType.STALEMATE_TRICK,
                    byColor = c.us,
                    moveUci = c.uci,
                    targetSquares = listOf(c.after.kingSquare(c.them)),
                    involvedSquares = listOf(c.landing),
                    materialSwing = 0,
                    description = "${c.san} leaves the opponent with no legal move - stalemate.",
                    confidence = PV_CONFIDENCE
                )
            )
            return
        }
        // The other shape of the trick: offering a piece whose capture is stalemate.
        val trap = c.after.legalMoves().firstOrNull {
            it.to == c.landing && it.isCapture && c.after.makeMove(it).isStalemate()
        } ?: return
        out.add(
            TacticInstance(
                type = TacticType.STALEMATE_TRICK,
                byColor = c.us,
                moveUci = c.uci,
                targetSquares = listOf(c.landing),
                involvedSquares = listOf(c.landing),
                materialSwing = 0,
                description = "Taking ${named(c.after, c.landing)} with " +
                    "${named(c.after, trap.from)} would be stalemate.",
                confidence = STATIC_CONFIDENCE
            )
        )
    }

    // -----------------------------------------------------------------------
    // FORTRESS (deliberately narrow - see the KDoc)
    // -----------------------------------------------------------------------

    /**
     * A genuinely hard motif: a fortress is a long-term evaluation claim, not a pattern, and
     * nothing short of a real search can confirm one. What is implemented is the narrowest
     * honest slice of it - the side that is materially down has every pawn on the board
     * blocked head-to-head, and the opponent has no passed pawn to break with. That catches
     * the blocked-structure fortress and nothing else, which is why it never claims more
     * than static confidence.
     */
    private fun detectFortress(c: Ctx, out: MutableList<TacticInstance>) {
        val ourMaterial = materialOf(c.after, c.us)
        val theirMaterial = materialOf(c.after, c.them)
        if (theirMaterial - ourMaterial < FORTRESS_DEFICIT) return

        val pawns = Attacks.piecesOf(c.after, c.us).filter { c.after.pieceAt(it)!!.type == PieceType.PAWN } +
            Attacks.piecesOf(c.after, c.them).filter { c.after.pieceAt(it)!!.type == PieceType.PAWN }
        if (pawns.isEmpty()) return
        val allBlocked = pawns.all { sq ->
            val colour = c.after.pieceAt(sq)!!.color
            val ahead = sq.rank + if (colour == Color.WHITE) 1 else -1
            ahead in 0..7 && c.after.pieceAt(Square.of(sq.file, ahead)) != null
        }
        if (!allBlocked) return
        if (ourPassedPawns(c.after, c.them).isNotEmpty()) return

        out.add(
            TacticInstance(
                type = TacticType.FORTRESS,
                byColor = c.us,
                moveUci = c.uci,
                targetSquares = emptyList(),
                involvedSquares = listOf(c.landing),
                materialSwing = 0,
                description = "${c.san} keeps the pawn chains locked - material down, but there " +
                    "is no way through.",
                confidence = STATIC_CONFIDENCE
            )
        )
    }

    private fun materialOf(position: Position, color: Color): Int =
        Attacks.piecesOf(position, color)
            .map { position.pieceAt(it)!!.type }
            .filter { it != PieceType.KING }
            .sumOf { valueOf(it) }

    // -----------------------------------------------------------------------
    // PV-confirmed motifs
    // -----------------------------------------------------------------------

    private fun detectPvMotifs(c: Ctx, out: MutableList<TacticInstance>) {
        detectRemovingTheDefender(c, out)
        detectDeflection(c, out)
        detectDecoy(c, out)
        detectOverload(c, out)
        detectInterference(c, out)
        detectClearance(c, out)
        detectZwischenzug(c, out)
        detectGreekGift(c, out)
        detectWindmill(c, out)
    }

    /**
     * REMOVING_THE_DEFENDER: the move itself takes the piece that was holding something
     * together, and the PV comes back for what it was holding. The defended point is read
     * off the *before* position, because by the time the move has been played the defender
     * is no longer on the board to ask.
     */
    private fun detectRemovingTheDefender(c: Ctx, out: MutableList<TacticInstance>) {
        if (!c.move.isCapture || c.move.isEnPassant) return
        val defender = c.before.pieceAt(c.landing) ?: return
        val ourFollowUp = c.ourPvMove(2) ?: return
        val posBeforeFollowUp = c.positionBeforePly(2) ?: return

        val guarded = Attacks.attacksFrom(c.before, c.landing)
        val exploited = ourFollowUp.to
        if (exploited !in guarded) return
        if (!ourFollowUp.isCapture && !posBeforeFollowUp.makeMove(ourFollowUp).isInCheck(c.them)) return

        val mate = posBeforeFollowUp.makeMove(ourFollowUp).isCheckmate()
        out.add(
            TacticInstance(
                type = TacticType.REMOVING_THE_DEFENDER,
                byColor = c.us,
                moveUci = c.uci,
                targetSquares = listOf(exploited),
                involvedSquares = listOf(c.landing),
                materialSwing = if (mate) MATE_SWING
                else maxOf(0, bestCaptureSee(posBeforeFollowUp, exploited, c.us)),
                description = "${c.san} takes away ${named(defender, c.landing)}, which was what " +
                    "held $exploited; ${posBeforeFollowUp.moveToSan(ourFollowUp)} follows.",
                confidence = PV_CONFIDENCE
            )
        )
    }

    /**
     * DEFLECTION: an enemy piece is doing a job, the PV shows it being dragged off that job,
     * and our next move cashes it in. The job is either *guarding* a square or *blocking*
     * one of our lines - Morphy's 16.Qb8+ in the Opera Game is the second kind, where the
     * knight on d7 was not defending d8 at all, it was standing in the rook's way.
     */
    private fun detectDeflection(c: Ctx, out: MutableList<TacticInstance>) {
        val theirReply = c.theirPvMove(1) ?: return
        val ourFollowUp = c.ourPvMove(2) ?: return
        val posBeforeReply = c.positionBeforePly(1) ?: return
        val posBeforeFollowUp = c.positionBeforePly(2) ?: return
        if (!(ourFollowUp.isCapture || posBeforeFollowUp.makeMove(ourFollowUp).isInCheck(c.them))) return

        val defenderSquare = theirReply.from
        val defender = posBeforeReply.pieceAt(defenderSquare) ?: return

        val guarded = Attacks.attacksFrom(posBeforeReply, defenderSquare).toSet()
        val exploited = ourFollowUp.to
        val guardedTarget = exploited in guarded
        val blockedLine = defenderSquare in lineBetween(ourFollowUp.from, exploited)
        if (!guardedTarget && !blockedLine) return
        // "Dragged away from guarding X" needs the defender to end up somewhere that no longer
        // guards X. A defender that simply captures on X (exd6 ... Qxd6) is exchanged there, not
        // deflected from it, and one that still sees X from its new square was not deflected at all.
        val afterReply = posBeforeFollowUp
        if (guardedTarget && !blockedLine) {
            if (theirReply.to == exploited) return
            if (exploited in Attacks.attacksFrom(afterReply, theirReply.to)) return
        }

        val mate = posBeforeFollowUp.makeMove(ourFollowUp).isCheckmate()
        val swing = if (mate) MATE_SWING else maxOf(0, bestCaptureSee(posBeforeFollowUp, exploited, c.us))
        val text = if (blockedLine) {
            "${c.san} drags ${named(defender, defenderSquare)} off " +
                "${lineName(ourFollowUp.from, exploited)}, and " +
                "${posBeforeFollowUp.moveToSan(ourFollowUp)} follows."
        } else {
            "${c.san} deflects ${named(defender, defenderSquare)} away from guarding $exploited."
        }
        out.add(
            TacticInstance(
                type = TacticType.DEFLECTION,
                byColor = c.us,
                moveUci = c.uci,
                targetSquares = listOf(exploited),
                involvedSquares = listOf(defenderSquare, c.landing),
                materialSwing = swing,
                description = text,
                confidence = PV_CONFIDENCE
            )
        )
    }

    /**
     * DECOY: the PV shows an enemy piece arriving on a square, and our very next move forking
     * or mating it there. The lure is the point, so the square the enemy lands on has to be
     * one of the targets of the follow-up.
     */
    private fun detectDecoy(c: Ctx, out: MutableList<TacticInstance>) {
        val theirReply = c.theirPvMove(1) ?: return
        val ourFollowUp = c.ourPvMove(2) ?: return
        val posBeforeFollowUp = c.positionBeforePly(2) ?: return
        val lured = theirReply.to
        val afterFollowUp = posBeforeFollowUp.makeMove(ourFollowUp)

        val mate = afterFollowUp.isCheckmate()
        val hits = Attacks.attackedEnemiesFrom(afterFollowUp, ourFollowUp.to)
        val forksLured = lured in hits && hits.size >= 2
        if (!mate && !forksLured) return
        // A decoy has to cost something - otherwise it is just a normal attacking move.
        if (!c.move.isCapture && c.moveSee >= 0 && !mate) return

        val decoyed = c.positionBeforePly(1)?.pieceAt(theirReply.from)
        val who = decoyed?.let { nounFor(it.type) } ?: "piece"
        val text = if (mate) {
            "${c.san} lures the $who to $lured, and ${posBeforeFollowUp.moveToSan(ourFollowUp)} mates."
        } else {
            "${c.san} drags the $who to $lured, where " +
                "${posBeforeFollowUp.moveToSan(ourFollowUp)} forks it."
        }
        out.add(
            TacticInstance(
                type = TacticType.DECOY,
                byColor = c.us,
                moveUci = c.uci,
                targetSquares = listOf(lured),
                involvedSquares = listOf(c.landing, ourFollowUp.to),
                materialSwing = if (mate) MATE_SWING else winnableValue(afterFollowUp, lured, c.us),
                description = text,
                confidence = PV_CONFIDENCE
            )
        )
    }

    /** OVERLOADED_PIECE: one enemy piece is the only guard of two things we are already hitting. */
    private fun detectOverload(c: Ctx, out: MutableList<TacticInstance>) {
        val ourFollowUp = c.ourPvMove(2) ?: return
        val posBeforeFollowUp = c.positionBeforePly(2) ?: return

        val guardedTargets = HashMap<Square, MutableList<Square>>()
        for (sq in Attacks.piecesOf(c.before, c.them)) {
            if (c.before.pieceAt(sq)!!.type == PieceType.KING) continue
            if (!Attacks.isAttackedBy(c.before, sq, c.us)) continue
            val defenders = Attacks.attackersOf(c.before, sq, c.them)
            if (defenders.size != 1) continue
            guardedTargets.getOrPut(defenders[0]) { ArrayList() }.add(sq)
        }
        val (overloaded, targets) = guardedTargets.entries
            .firstOrNull { it.value.size >= 2 } ?: return
        if (ourFollowUp.to !in targets) return

        out.add(
            TacticInstance(
                type = TacticType.OVERLOADED_PIECE,
                byColor = c.us,
                moveUci = c.uci,
                targetSquares = targets,
                involvedSquares = listOf(overloaded),
                materialSwing = maxOf(0, bestCaptureSee(posBeforeFollowUp, ourFollowUp.to, c.us)),
                description = "${capitalise(named(c.before, overloaded))} is overloaded - it cannot " +
                    "guard ${joinNatural(targets.map { it.toString() })} at once.",
                confidence = PV_CONFIDENCE
            )
        )
    }

    /** INTERFERENCE: the move parks a piece across an enemy line, and the PV uses what is now loose. */
    private fun detectInterference(c: Ctx, out: MutableList<TacticInstance>) {
        val ourFollowUp = c.ourPvMove(2) ?: return
        val posBeforeFollowUp = c.positionBeforePly(2) ?: return

        for (sliderSq in Attacks.piecesOf(c.after, c.them)) {
            val slider = c.after.pieceAt(sliderSq)!!
            if (!isSlider(slider.type)) continue
            if (!isBetween(sliderSq, c.landing, ourFollowUp.to)) continue
            val lostControl = Attacks.attacksFrom(c.before, sliderSq).toSet() -
                Attacks.attacksFrom(c.after, sliderSq).toSet()
            if (ourFollowUp.to !in lostControl) continue

            val mate = posBeforeFollowUp.makeMove(ourFollowUp).isCheckmate()
            out.add(
                TacticInstance(
                    type = TacticType.INTERFERENCE,
                    byColor = c.us,
                    moveUci = c.uci,
                    targetSquares = listOf(ourFollowUp.to),
                    involvedSquares = listOf(c.landing, sliderSq),
                    materialSwing = if (mate) MATE_SWING
                    else maxOf(0, bestCaptureSee(posBeforeFollowUp, ourFollowUp.to, c.us)),
                    description = "${c.san} cuts ${named(slider, sliderSq)} off from " +
                        "${ourFollowUp.to}, and ${posBeforeFollowUp.moveToSan(ourFollowUp)} follows.",
                    confidence = PV_CONFIDENCE
                )
            )
            return
        }
    }

    /** CLEARANCE: the move steps out of the way and the PV sends another piece down that line. */
    private fun detectClearance(c: Ctx, out: MutableList<TacticInstance>) {
        val ourFollowUp = c.ourPvMove(2) ?: return
        val posBeforeFollowUp = c.positionBeforePly(2) ?: return
        val vacatedSquare = c.move.from
        // A clearance gets *another* piece through: the piece that just moved coming back along the
        // line it left (Bxb5 ... Be2) clears nothing for anyone, and a pawn stepping onto the square
        // the moved piece vacated is not using a line. (Both were reported: "Bxb5 clears c4 so that
        // Be2 can come through", "Nf5 clears h4 so that h4 can come through".)
        if (ourFollowUp.from == c.landing) return
        val travels = vacatedSquare in lineBetween(ourFollowUp.from, ourFollowUp.to) ||
            ourFollowUp.to == vacatedSquare
        if (!travels) return
        val mover = posBeforeFollowUp.pieceAt(ourFollowUp.from) ?: return
        if (mover.color != c.us || mover.type == PieceType.PAWN) return

        out.add(
            TacticInstance(
                type = TacticType.CLEARANCE,
                byColor = c.us,
                moveUci = c.uci,
                targetSquares = listOf(ourFollowUp.to),
                involvedSquares = listOf(vacatedSquare, ourFollowUp.from),
                materialSwing = maxOf(0, bestCaptureSee(posBeforeFollowUp, ourFollowUp.to, c.us)),
                description = "${c.san} clears $vacatedSquare so that " +
                    "${posBeforeFollowUp.moveToSan(ourFollowUp)} can come through.",
                confidence = PV_CONFIDENCE
            )
        )
    }

    /**
     * ZWISCHENZUG: a capture was sitting there waiting to be made, and instead the move
     * inserts something more forcing first, with the PV coming back for the capture after.
     *
     * A single position cannot tell us that the opponent *just* captured, which is how a
     * human recognises the motif. What it can tell us is that a profitable capture is
     * available and is being deliberately postponed, which is the same shape.
     */
    private fun detectZwischenzug(c: Ctx, out: MutableList<TacticInstance>) {
        val forcing = c.after.isInCheck(c.them) ||
            (c.move.isCapture && valueOf(c.move.capturedPiece ?: PieceType.PAWN) > c.moverValue)
        if (!forcing) return

        val pending = Attacks.piecesOf(c.before, c.them).firstOrNull { sq ->
            sq != c.landing &&
                c.before.pieceAt(sq)!!.type != PieceType.KING &&
                valueOf(c.before.pieceAt(sq)!!.type) >= 300 &&
                winnableBy(c.before, sq, c.us)
        } ?: return

        for (i in intArrayOf(2, 4)) {
            val later = c.ourPvMove(i) ?: continue
            if (later.to != pending || !later.isCapture) continue
            val posBefore = c.positionBeforePly(i)!!
            out.add(
                TacticInstance(
                    type = TacticType.ZWISCHENZUG,
                    byColor = c.us,
                    moveUci = c.uci,
                    targetSquares = listOf(pending),
                    involvedSquares = listOf(c.landing),
                    materialSwing = maxOf(0, bestCaptureSee(c.before, pending, c.us)),
                    description = "${c.san} comes first; ${posBefore.moveToSan(later)} on $pending " +
                        "is still there afterwards.",
                    confidence = PV_CONFIDENCE
                )
            )
            return
        }
    }

    /** GREEK_GIFT: Bxh7+/Bxh2+ with the knight and queen following it in, exactly as the spec says. */
    private fun detectGreekGift(c: Ctx, out: MutableList<TacticInstance>) {
        if (c.moverType != PieceType.BISHOP || !c.move.isCapture) return
        val gift = if (c.us == Color.WHITE) Square.fromAlgebraic("h7") else Square.fromAlgebraic("h2")
        if (c.landing != gift) return
        if (!c.after.isInCheck(c.them)) return

        val knightTo = if (c.us == Color.WHITE) Square.fromAlgebraic("g5") else Square.fromAlgebraic("g4")
        val queenTo = if (c.us == Color.WHITE) Square.fromAlgebraic("h5") else Square.fromAlgebraic("h4")
        val ourMoves = c.pvMoves.filterIndexed { i, _ -> i % 2 == 0 }
        val knightComes = ourMoves.any { it.piece == PieceType.KNIGHT && it.to == knightTo }
        val queenComes = ourMoves.any { it.piece == PieceType.QUEEN && it.to == queenTo }
        if (!knightComes || !queenComes) return

        out.add(
            TacticInstance(
                type = TacticType.GREEK_GIFT,
                byColor = c.us,
                moveUci = c.uci,
                targetSquares = listOf(c.after.kingSquare(c.them)),
                involvedSquares = listOf(c.landing, knightTo, queenTo),
                materialSwing = MATE_SWING / 2,
                description = "${c.san} is the Greek gift: the knight comes to $knightTo and the " +
                    "queen to $queenTo behind it.",
                confidence = PV_CONFIDENCE
            )
        )
    }

    /**
     * WINDMILL (ANALYSIS_SPEC 5.3): a repeating discovered-check-plus-capture cycle. Torre's rook
     * shuttles to g7 against Lasker: the rook gives a direct check, steps off the bishop's
     * diagonal to take something with a *discovered* check, goes back, and so on while the king
     * only ever has king moves.
     *
     * The first version only asked for "a slider lands on the same square twice, two captures and
     * three checks somewhere in the PV", which any long forcing line satisfies: 15.Bxd7+ Qxd7
     * Qb8+ ... Rxd7+ was a "windmill" because a bishop and, later, a rook both landed on d7. The
     * cycle has to be the whole line, so this requires, inside one unbroken run of the mover's
     * checks:
     *  - at least [WINDMILL_MIN_CHECKS] consecutive checking moves, every reply a king move (a
     *    windmill leaves the defender no choice);
     *  - at least two *discovered* checks (the checking piece is not the one that moved) and at
     *    least two captures;
     *  - the *same* piece returning to a square it already landed on - the "blade" coming round
     *    again, not two different pieces visiting one square.
     */
    private fun detectWindmill(c: Ctx, out: MutableList<TacticInstance>) {
        val ourIndices = c.pvMoves.indices.filter { it % 2 == 0 }
        if (ourIndices.size < WINDMILL_MIN_CHECKS) return

        // Longest run of consecutive own moves that all give check, replies all king moves.
        var best: List<Int> = emptyList()
        var run = ArrayList<Int>()
        for (i in ourIndices) {
            val posAfter = c.positionAfterPly(i) ?: break
            if (!posAfter.isInCheck(c.them)) {
                run = ArrayList()
                continue
            }
            val previousReplyForced = i == 0 || c.pvMoves[i - 1].piece == PieceType.KING
            if (run.isNotEmpty() && !previousReplyForced) run = ArrayList()
            run.add(i)
            if (run.size > best.size) best = ArrayList(run)
        }
        if (best.size < WINDMILL_MIN_CHECKS) return

        var captures = 0
        var discovered = 0
        var nextId = 0
        val idAt = HashMap<Square, Int>()
        val landings = HashMap<Pair<Int, Square>, Int>()
        for (i in best) {
            val m = c.pvMoves[i]
            val posAfter = c.positionAfterPly(i) ?: continue
            if (m.isCapture) captures++
            val kingSq = posAfter.kingSquare(c.them)
            if (Attacks.attackersOf(posAfter, kingSq, c.us).any { it != m.to }) discovered++
            val id = idAt.remove(m.from) ?: nextId++
            idAt[m.to] = id
            if (isSlider(m.piece)) {
                val key = id to m.to
                landings[key] = (landings[key] ?: 0) + 1
            }
        }
        val hub = landings.entries.firstOrNull { it.value >= 2 } ?: return
        if (captures < 2 || discovered < 2) return

        out.add(
            TacticInstance(
                type = TacticType.WINDMILL,
                byColor = c.us,
                moveUci = c.uci,
                targetSquares = listOf(c.after.kingSquare(c.them)),
                involvedSquares = listOf(c.landing, hub.key.second),
                materialSwing = captures * valueOf(PieceType.PAWN),
                description = "${c.san} sets up a windmill: the rook keeps coming back to " +
                    "${hub.key.second} with check, taking material each time round.",
                confidence = PV_CONFIDENCE
            )
        )
    }

    companion object {
        private const val STATIC_CONFIDENCE = 0.6
        private const val PV_CONFIDENCE = 0.95
        private const val MIN_CONFIDENCE = 0.6

        /** Mate is not material, but it has to sort above every material motif. */
        private const val MATE_SWING = 10_000

        private const val MATE_PV_PLIES = 11
        private const val BREAKTHROUGH_PLIES = 5
        private const val PERPETUAL_PLIES = 6
        private const val PERPETUAL_NODES = 400
        private const val FORTRESS_DEFICIT = 500

        /** A windmill is at least this many checks in a row (ANALYSIS_SPEC 5.3). */
        private const val WINDMILL_MIN_CHECKS = 4

        /** ANALYSIS_SPEC 5.3 "Reporting": at most this many motifs survive per move. */
        const val MAX_TACTICS_PER_MOVE = 2

        /** The motifs that describe a mate. They rank above every other motif. */
        val MATING_TYPES = setOf(TacticType.MATE_NET, TacticType.BACK_RANK_MATE, TacticType.SMOTHERED_MATE)
    }
}
