package net.palaya.chessanalyzer.core.analysis

import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Move
import net.palaya.chessanalyzer.core.chess.PieceType
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.Square
import net.palaya.chessanalyzer.core.chess.moveToSan
import net.palaya.chessanalyzer.core.chess.parseUci

/**
 * Builds a [TacticSimulation] from a missed tactic and the engine PV that demonstrates it —
 * ANALYSIS_SPEC.md section 6.
 *
 * Every ply gets an explanation that names the moving piece, the square it lands on, what it
 * captures or attacks, and the motif where one applies ("Nf6+: the knight lands on f6, forking
 * the king on g8 and the queen on d5."). Material claims go through [ExchangeEvaluator], so a
 * capture that loses material is never described as winning any, and the payoff on the final
 * frame is read off the line itself (mate distance, or the material actually netted) rather
 * than taken on trust from the tactic's advertised swing.
 *
 * This text is rendered on screen; it is not the spoken narration (that lives in
 * `core.narration`), so ordinary algebraic notation is fine here.
 */
class SimulationBuilder {

    private val maxPlies = 8

    /** Anything smaller than this is noise rather than a realised material payoff. */
    private val minimumPayoffCp = 100

    /**
     * @param startPosition the position the PV starts from (the position BEFORE the missed
     *   move — never mutated; [Position] itself is immutable, so this holds automatically).
     * @param pvUci the engine PV, starting with the move the player should have played.
     * @param tactic the tactic this PV demonstrates.
     * @param maxPlies the spec §6 cap of 8 for a game excursion; a reference example passes its
     *   own line length so the whole lesson is shown.
     * @param truncateAtPayoff stop once the material payoff has landed (spec §6). Off for a
     *   reference example, whose line is the lesson in full.
     */
    fun build(
        startPosition: Position,
        pvUci: List<String>,
        tactic: TacticInstance,
        maxPlies: Int = this.maxPlies,
        truncateAtPayoff: Boolean = true,
        /**
         * What to say on the final frame when the line neither mates nor nets material — a
         * reference example knows what its one-move motif collects next (the corpus verified
         * it); a game excursion does not, and keeps the non-committal fallbacks.
         */
        payoffOverride: String? = null
    ): TacticSimulation {
        var pos = startPosition
        val sanList = ArrayList<String>()
        val explanations = ArrayList<String>()
        val truncatedUci = ArrayList<String>()
        val winner = tactic.byColor
        val payoffTarget = maxOf(tactic.materialSwing, minimumPayoffCp)

        var previous: Move? = null
        for (uci in pvUci) {
            if (truncatedUci.size >= maxPlies) break
            val move = try {
                pos.parseUci(uci)
            } catch (e: Exception) {
                break
            }
            val san = pos.moveToSan(move)
            val next = pos.makeMove(move)

            truncatedUci.add(uci)
            sanList.add(san)
            explanations.add(
                explanationFor(
                    before = pos,
                    move = move,
                    san = san,
                    after = next,
                    tactic = if (tactic.moveUci == uci) tactic else null,
                    previous = previous
                )
            )

            previous = move
            pos = next
            if (next.isCheckmate()) break
            // Spec section 6: truncate at 8 plies OR once the payoff has actually been realised.
            if (truncateAtPayoff && payoffRealised(startPosition, pos, winner, payoffTarget)) break
        }

        val payoff = payoffDescription(startPosition, pos, sanList.size, winner, tactic, payoffOverride)
        if (explanations.isNotEmpty() && payoff.isNotBlank()) {
            // Spec section 6: the final frame states the payoff - when there is one that the board
            // proves. A line that mates or nets material says so; one that does neither says nothing.
            explanations[explanations.lastIndex] = "${explanations.last()} ${colorName(winner)} $payoff."
        }
        return TacticSimulation(
            startFen = startPosition.toFen(),
            pvUci = truncatedUci,
            pvSan = sanList,
            perPlyExplanation = explanations,
            tactic = tactic,
            payoffDescription = payoff
        )
    }

    // -----------------------------------------------------------------------
    // Truncation
    // -----------------------------------------------------------------------

    /**
     * True once [winner] is up at least [target] centipawns on the deal AND the opponent has had
     * the last word (so we are not counting a half-finished exchange that is about to be
     * recaptured).
     */
    private fun payoffRealised(start: Position, current: Position, winner: Color, target: Int): Boolean {
        if (current.sideToMove != winner) return false
        return ExchangeEvaluator.netGain(start, current, winner) >= target
    }

    // -----------------------------------------------------------------------
    // Per-ply explanation
    // -----------------------------------------------------------------------

    private fun explanationFor(
        before: Position,
        move: Move,
        san: String,
        after: Position,
        tactic: TacticInstance?,
        previous: Move?
    ): String {
        val exchange = if (move.isCapture || move.promotion != null) ExchangeEvaluator.see(before, move) else 0
        val clauses = ArrayList<String>()
        clauses += movementClause(before, move, exchange)

        // Taking back on the square the opponent just captured on is the second half of a trade.
        // Exchange evaluation sees only the recapture ("Qxd6 ... winning a pawn" straight after
        // "exd6"), so a recapture is judged by the two captures together: what this side took minus
        // what it had just lost. An even pair says nothing; a pair that wins material says so.
        val takesBack = previous != null && previous.isCapture && move.isCapture && previous.to == move.to
        if (takesBack) {
            tradeClause(move, previous!!, before)?.let { clauses += it }
        } else {
            materialClause(move, exchange)?.let { clauses += it }
        }
        val motif = tactic
            ?.takeUnless { restatesTheCapture(it, move) }
            ?.let { motifClause(it, before, after, move) }
        if (motif != null) {
            clauses += motif
        } else {
            // Only describe raw attacks when no motif already named the targets, so the same
            // pieces are never listed twice in one sentence.
            attackClause(move, after)?.let { clauses += it }
        }

        val head = "$san: " + joinClauses(clauses)
        val suffix = when {
            after.isCheckmate() -> " — checkmate."
            after.isInCheck() -> ", with check."
            else -> "."
        }
        return head + suffix
    }

    /** "the knight lands on f6" / "the rook captures the bishop on d7" / "the king castles short". */
    private fun movementClause(before: Position, move: Move, exchange: Int): String {
        val moverName = PieceValues.name(move.piece)
        if (move.isCastleKingside) return "the king castles short to ${move.to}"
        if (move.isCastleQueenside) return "the king castles long to ${move.to}"
        val promotion = move.promotion
        if (promotion != null) {
            val promoted = PieceValues.name(promotion)
            val verb = if (move.isCapture) "captures on ${move.to} and promotes" else "promotes on ${move.to}"
            return "the pawn $verb to a $promoted"
        }
        if (move.isCapture) {
            val capturedName = capturedPieceName(before, move)
            // A capture that loses the exchange is a sacrifice, and is described as one.
            if (exchange <= -minimumPayoffCp) {
                return "the $moverName gives itself up for the $capturedName on ${move.to}"
            }
            return "the $moverName captures the $capturedName on ${move.to}"
        }
        return "the $moverName lands on ${move.to}"
    }

    /**
     * True when the motif says nothing the movement clause has not already said — a
     * HANGING_PIECE/deflection motif whose only target is the square just captured on.
     */
    private fun restatesTheCapture(tactic: TacticInstance, move: Move): Boolean {
        if (!move.isCapture) return false
        val restating = tactic.type == TacticType.HANGING_PIECE ||
            tactic.type == TacticType.REMOVING_THE_DEFENDER
        if (!restating) return false
        val targets = tactic.targetSquares.distinctBy { it.index }
        return targets.isEmpty() || (targets.size == 1 && targets.single().index == move.to.index)
    }

    private fun capturedPieceName(before: Position, move: Move): String {
        val type = when {
            move.isEnPassant -> PieceType.PAWN
            else -> before.pieceAt(move.to)?.type ?: move.capturedPiece
        }
        return type?.let { PieceValues.name(it) } ?: "piece"
    }

    /**
     * Only ever claims won material when [ExchangeEvaluator] agrees; a capture that loses the
     * exchange is described as the sacrifice it is.
     */
    private fun materialClause(move: Move, exchange: Int): String? {
        if (!move.isCapture && move.promotion == null) return null
        return when {
            exchange >= minimumPayoffCp ->
                ExchangeEvaluator.describeGain(exchange)?.let { "winning $it" } ?: "winning material"
            // The sacrifice itself is already spelled out by the movement clause.
            exchange <= -minimumPayoffCp -> "betting on the attack instead of the material"
            move.isCapture -> "an even trade"
            else -> null
        }
    }

    /** "winning a pawn" / "winning material" for a recapture that nets that over the pair of captures, else null. */
    private fun tradeClause(move: Move, previous: Move, before: Position): String? {
        val taken = capturedValue(move, before)
        val lost = previous.capturedPiece?.let { PieceValues.of(it) } ?: PieceValues.of(PieceType.PAWN)
        val net = taken - lost
        if (net < minimumPayoffCp) return null
        return ExchangeEvaluator.describeGain(net)?.let { "winning $it" } ?: "winning material"
    }

    private fun capturedValue(move: Move, before: Position): Int = when {
        move.isEnPassant -> PieceValues.of(PieceType.PAWN)
        else -> before.pieceAt(move.to)?.type?.let { PieceValues.of(it) } ?: 0
    }

    /**
     * Names the motif and the concrete pieces and squares it hits. Squares are read off the
     * position BEFORE the move (the victims are still standing there), and the mover's own
     * pieces are never listed as targets.
     */
    private fun motifClause(tactic: TacticInstance, before: Position, after: Position, move: Move): String? {
        val targets = describeSquares(tactic.targetSquares, before, excludeColor = tactic.byColor)
        val involved = describeSquares(tactic.involvedSquares, before, excludeColor = tactic.byColor)
        return when (tactic.type) {
            TacticType.FORK, TacticType.PAWN_FORK, TacticType.DOUBLE_ATTACK -> {
                val verb = if (tactic.type == TacticType.DOUBLE_ATTACK) "hitting" else "forking"
                if (targets != null) "$verb $targets" else "$verb two targets at once"
            }
            TacticType.PIN_ABSOLUTE, TacticType.PIN_RELATIVE ->
                if (targets != null) "pinning $targets" else "pinning a defender to a bigger piece"
            TacticType.SKEWER ->
                if (targets != null) "skewering $targets" else "skewering the pieces on the line"
            TacticType.DISCOVERED_ATTACK -> "uncovering an attack from behind it"
            TacticType.DISCOVERED_CHECK -> "uncovering a discovered check"
            TacticType.DOUBLE_CHECK -> "giving double check"
            // The piece is attacked, not collected: the opponent moves next. Said only when the board
            // shows the target attacked by the mover and with nothing defending it.
            TacticType.HANGING_PIECE -> undefendedTargets(tactic, after, move.color)?.let { "attacking $it, which nothing defends" }
            TacticType.TRAPPED_PIECE ->
                if (targets != null) "trapping $targets" else "trapping the piece"
            // The target squares of these motifs are what the defender was holding, not the defender:
            // the defender is the first involved square (read off the position before the move).
            TacticType.DEFLECTION ->
                if (involved != null) "dragging ${involved.substringBefore(" and ").substringBefore(", ")} away from the defence"
                else "dragging a defender away"
            TacticType.REMOVING_THE_DEFENDER -> "removing the defender"
            TacticType.DECOY ->
                if (involved != null) "luring $involved onto a fatal square" else "luring the defender onto a fatal square"
            TacticType.OVERLOADED_PIECE -> {
                val overloaded = describeSquares(tactic.involvedSquares.take(1), before, excludeColor = tactic.byColor)
                if (overloaded != null) "overloading $overloaded" else "overloading the last defender"
            }
            TacticType.INTERFERENCE -> "cutting the defending line in two"
            TacticType.CLEARANCE -> "clearing the line for the pieces behind it"
            TacticType.ZWISCHENZUG -> "slipping in an in-between move before the recapture"
            TacticType.BACK_RANK_MATE -> "hitting the back rank where the king is boxed in"
            TacticType.SMOTHERED_MATE -> "smothering the king with its own pieces"
            TacticType.GREEK_GIFT -> "opening the king's shelter"
            TacticType.WINDMILL -> "starting a windmill of discovered checks"
            TacticType.MATE_NET -> "closing the mating net"
            TacticType.PROMOTION_TACTIC, TacticType.UNDERPROMOTION, TacticType.PASSED_PAWN_BREAKTHROUGH ->
                "clearing the way for the passed pawn"
            TacticType.PERPETUAL_CHECK -> "setting up perpetual check"
            TacticType.STALEMATE_TRICK -> "angling for stalemate"
            TacticType.DESPERADO -> "cashing in a piece that was lost anyway"
            TacticType.X_RAY, TacticType.BATTERY ->
                if (targets != null) "lining up on $targets" else "lining the heavy pieces up"
            TacticType.FORTRESS -> "sealing the position shut"
        }
    }

    /**
     * "the pawn on e5" for each target of a HANGING_PIECE motif that the mover really does attack in
     * [after] and that nothing defends; null when no target passes, so nothing is claimed.
     */
    private fun undefendedTargets(tactic: TacticInstance, after: Position, mover: Color): String? {
        val parts = tactic.targetSquares.distinctBy { it.index }.mapNotNull { square ->
            val piece = after.pieceAt(square) ?: return@mapNotNull null
            if (piece.color == mover || piece.type == PieceType.KING) return@mapNotNull null
            if (BoardFacts.attackers(after, square, mover).isEmpty()) return@mapNotNull null
            if (BoardFacts.defenders(after, square).isNotEmpty()) return@mapNotNull null
            "the ${PieceValues.name(piece.type)} on $square"
        }
        return when (parts.size) {
            0 -> null
            1 -> parts[0]
            else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
        }
    }

    /** What the piece that just moved now hits: the king, or an enemy unit worth a piece or more. */
    private fun attackClause(move: Move, after: Position): String? {
        val landingSquare = move.to
        val mover = move.color
        val hits = attackedByPieceOn(after, landingSquare, mover)
            .filter { square ->
                val piece = after.pieceAt(square) ?: return@filter false
                piece.type == PieceType.KING || PieceValues.of(piece.type) >= PieceValues.of(move.piece)
            }
            .sortedByDescending { after.pieceAt(it)?.type?.let { t -> PieceValues.of(t) } ?: 0 }
            .take(2)
        if (hits.isEmpty()) return null
        val described = describeSquares(hits, after) ?: return null
        return "attacking $described"
    }

    /** Enemy squares attacked by the (friendly) piece standing on [from]. */
    private fun attackedByPieceOn(position: Position, from: Square, mover: Color): List<Square> {
        val piece = position.pieceAt(from) ?: return emptyList()
        if (piece.color != mover) return emptyList()
        val result = ArrayList<Square>()
        for (index in 0..63) {
            val target = Square(index)
            if (index == from.index) continue
            val occupant = position.pieceAt(target) ?: continue
            if (occupant.color == mover) continue
            if (attacks(position, from, target, piece.type, mover)) result.add(target)
        }
        return result
    }

    private fun attacks(position: Position, from: Square, to: Square, type: PieceType, color: Color): Boolean {
        val df = to.file - from.file
        val dr = to.rank - from.rank
        return when (type) {
            PieceType.PAWN -> {
                val forward = if (color == Color.WHITE) 1 else -1
                dr == forward && (df == 1 || df == -1)
            }
            PieceType.KNIGHT -> {
                val a = kotlin.math.abs(df)
                val b = kotlin.math.abs(dr)
                (a == 1 && b == 2) || (a == 2 && b == 1)
            }
            PieceType.KING -> kotlin.math.abs(df) <= 1 && kotlin.math.abs(dr) <= 1
            PieceType.BISHOP -> kotlin.math.abs(df) == kotlin.math.abs(dr) && clearPath(position, from, to)
            PieceType.ROOK -> (df == 0 || dr == 0) && clearPath(position, from, to)
            PieceType.QUEEN ->
                (df == 0 || dr == 0 || kotlin.math.abs(df) == kotlin.math.abs(dr)) && clearPath(position, from, to)
        }
    }

    private fun clearPath(position: Position, from: Square, to: Square): Boolean {
        val stepFile = Integer.signum(to.file - from.file)
        val stepRank = Integer.signum(to.rank - from.rank)
        var file = from.file + stepFile
        var rank = from.rank + stepRank
        while (file != to.file || rank != to.rank) {
            if (file !in 0..7 || rank !in 0..7) return false
            if (position.pieceAt(Square.of(file, rank)) != null) return false
            file += stepFile
            rank += stepRank
        }
        return true
    }

    /** "the king on g8 and the queen on d5" for squares that are actually occupied. */
    private fun describeSquares(
        squares: List<Square>,
        position: Position,
        excludeColor: Color? = null
    ): String? {
        val parts = squares.distinctBy { it.index }.mapNotNull { square ->
            val piece = position.pieceAt(square) ?: return@mapNotNull null
            if (piece.color == excludeColor) return@mapNotNull null
            "the ${PieceValues.name(piece.type)} on $square"
        }
        return when (parts.size) {
            0 -> null
            1 -> parts[0]
            else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
        }
    }

    private fun colorName(color: Color): String = if (color == Color.WHITE) "White" else "Black"

    private fun joinClauses(clauses: List<String>): String = when (clauses.size) {
        0 -> ""
        1 -> clauses[0]
        else -> clauses[0] + ", " + clauses.drop(1).joinToString(", ")
    }

    // -----------------------------------------------------------------------
    // Payoff
    // -----------------------------------------------------------------------

    /**
     * The real payoff of the line, or "" when the board proves none: mate distance when the line
     * actually mates, otherwise the material [winner] has netted between the start position and the
     * end of the truncated line - *settled*, so that a line that stops right after a capture is not
     * credited with a piece the opponent can take straight back. A line that neither mates nor nets
     * material says nothing rather than a vague "gains a decisive advantage" the data cannot back
     * (ANALYSIS_SPEC section 6.1).
     */
    private fun payoffDescription(
        start: Position,
        finalPosition: Position,
        plyCount: Int,
        winner: Color,
        tactic: TacticInstance,
        payoffOverride: String? = null
    ): String {
        if (finalPosition.isCheckmate()) {
            val fullMoves = (plyCount + 1) / 2
            return "mates in $fullMoves"
        }
        // Draws are a payoff too: the stalemate trick and the perpetual are played from a lost
        // position, and the desperado from a doomed one - "wins material" is the wrong scale.
        if (finalPosition.isStalemate()) return "forces stalemate: a draw from a lost position"
        when (tactic.type) {
            TacticType.PERPETUAL_CHECK -> return "forces a draw by repetition"
            TacticType.STALEMATE_TRICK -> return "sets up a stalemate the opponent cannot avoid"
            TacticType.DESPERADO -> return "gets the most out of a piece that was lost anyway"
            TacticType.PASSED_PAWN_BREAKTHROUGH -> return "creates a passed pawn nobody can catch"
            else -> Unit
        }
        val gain = ExchangeEvaluator.settledGain(start, finalPosition, winner)
        ExchangeEvaluator.describeGain(gain)?.let { return "wins $it" }
        if (gain >= minimumPayoffCp) return "wins material"
        // Nothing cashed in: a reference knows what comes next (the corpus verified it), a game
        // excursion does not and says nothing.
        payoffOverride?.let { return it }
        if (tactic.type == TacticType.MATE_NET && tactic.confidence >= 0.95) return "leaves the king in a mating net"
        return ""
    }
}
