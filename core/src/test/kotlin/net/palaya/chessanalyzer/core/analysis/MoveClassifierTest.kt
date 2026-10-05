package net.palaya.chessanalyzer.core.analysis

import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Move
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.parseSan
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One test per branch of the ANALYSIS_SPEC.md section 2 ladder, plus the already-decided guard.
 * Fixtures are constructed directly (hand-built [PositionEval]s) — no engine involved. Uses a
 * fake [SeeEvaluator] since the real implementation lives in the concurrently-developed
 * `core.tactics` package, which this module must not depend on.
 */
class MoveClassifierTest {

    /** A fake SEE that reports whatever the test wires up, defaulting to "nothing special". */
    private class FakeSee(
        private val seeValues: Map<String, Int> = emptyMap(),
        private val hangingSquares: Set<String> = emptySet()
    ) : SeeEvaluator {
        override fun see(position: Position, move: Move): Int = seeValues[move.toUci()] ?: 0
        override fun isHanging(position: Position, square: net.palaya.chessanalyzer.core.chess.Square): Boolean =
            square.toString() in hangingSquares
    }

    private fun line(multiPv: Int, cp: Int? = null, mateIn: Int? = null, pv: List<String> = emptyList()) =
        EngineLineInput(multiPv, cp, mateIn, depth = 18, pvUci = pv)

    private fun evalOf(fen: String, vararg lines: EngineLineInput) =
        PositionEval(fen, lines.toList(), depth = 18)

    private fun classifierWith(see: SeeEvaluator = FakeSee()) = MoveClassifier(see)

    // 1. FORCED ---------------------------------------------------------------------------

    @Test
    fun `single legal move is FORCED`() {
        // Black king on a8, only legal move is Kb8-ish position: use a position with exactly
        // one legal move for the side to move.
        // One-legal-move fixture: Black king boxed to a single square.
        // stalemate-adjacent single-move position: black king h8 boxed except one square.
        val forcedFen = "7k/8/6K1/8/8/8/8/7Q b - - 0 1"
        val pos = Position.fromFen(forcedFen)
        val legal = pos.legalMoves()
        require(legal.size == 1) { "fixture must have exactly one legal move, had ${legal.size}: $legal" }
        val move = legal.first()
        val before = evalOf(forcedFen, line(1, cp = -900))
        val after = evalOf(pos.makeMove(move).toFen(), line(1, cp = 900))

        val result = classifierWith().classify(pos, move, before, after, isBookPosition = false, isBookMove = false, ply = 40)
        assertEquals(MoveClassification.FORCED, result)
    }

    // 2. BOOK -------------------------------------------------------------------------------

    @Test
    fun `book position and book move within ply 20 is BOOK`() {
        val pos = Position.startPosition()
        val move = pos.parseSan("e4")
        val after = pos.makeMove(move)
        val before = evalOf(pos.toFen(), line(1, cp = 30, pv = listOf(move.toUci())))
        val afterEval = evalOf(after.toFen(), line(1, cp = -25))

        val result = classifierWith().classify(pos, move, before, afterEval, isBookPosition = true, isBookMove = true, ply = 1)
        assertEquals(MoveClassification.BOOK, result)
    }

    @Test
    fun `book move beyond ply 20 is not BOOK`() {
        val pos = Position.startPosition()
        val move = pos.parseSan("e4")
        val after = pos.makeMove(move)
        val before = evalOf(pos.toFen(), line(1, cp = 30, pv = listOf(move.toUci())))
        val afterEval = evalOf(after.toFen(), line(1, cp = -25))

        val result = classifierWith().classify(pos, move, before, afterEval, isBookPosition = true, isBookMove = true, ply = 21)
        assertEquals(MoveClassification.BEST, result)
    }

    // 3. BRILLIANT ----------------------------------------------------------------------------

    @Test
    fun `genuine piece sacrifice that stays winning is BRILLIANT`() {
        // White to move, has a knight it can sac (SEE -320), stays winning, near-best, and the
        // opponent's best reply is not a clean recapture (per the fake).
        val fen = "4k3/8/8/8/3n4/5N2/8/4K3 w - - 0 1"
        val pos = Position.fromFen(fen)
        val move = pos.parseSan("Nxd4") // knight takes knight — but wire SEE to say this loses material overall
        val after = pos.makeMove(move)

        val see = FakeSee(seeValues = mapOf(move.toUci() to -320))
        val before = evalOf(fen, line(1, cp = 200, pv = listOf(move.toUci())), line(2, cp = 150))
        // Opponent's best reply doesn't recapture cleanly (no capture at all in this fake PV),
        // and White (the mover) remains clearly winning afterwards (winAfter for White ~= 66.5,
        // i.e. loss ~= 1.1, comfortably <= 2.0). afterEval's side to move is Black, so a cp that
        // is bad for Black is what keeps White winning.
        val afterEval = evalOf(after.toFen(), line(1, cp = invWinPercent(33.5), pv = listOf("e8d8")))

        val result = classifierWith(see).classify(pos, move, before, afterEval, isBookPosition = false, isBookMove = false, ply = 30)
        assertEquals(MoveClassification.BRILLIANT, result)
    }

    @Test
    fun `a piece the opponent cannot legally take is not a sacrifice - the only attacker is pinned`() {
        // Opera Game, 14.Rd1: the rook on d7 "attacks" d1 but is pinned to its king by the bishop on
        // b5, so Rxd1 is illegal. Spec section 2 says a legal capture; the fake SEE says "hanging" the
        // way the real one does (pseudo-legal), which is exactly what used to make this BRILLIANT.
        val fen = "4kb1r/p2rqppp/5n2/1B2p1B1/4P3/1Q6/PPP2PPP/2K4R w k - 0 14"
        val pos = Position.fromFen(fen)
        val move = pos.parseSan("Rd1")
        val after = pos.makeMove(move)
        require(after.legalMoves().none { it.isCapture && it.to.toString() == "d1" }) { "fixture: the rook must be immune" }
        val before = evalOf(fen, line(1, cp = 886, pv = listOf(move.toUci())))
        val afterEval = evalOf(after.toFen(), line(1, cp = -882))
        val result = classifierWith(FakeSee(hangingSquares = setOf("d1")))
            .classify(pos, move, before, afterEval, isBookPosition = false, isBookMove = false, ply = 27)
        assertEquals(MoveClassification.BEST, result)
    }

    @Test
    fun `the same offer is still a sacrifice when the piece that attacks it is free to take it`() {
        // Without the bishop on b5 the rook on d7 is not pinned: Rxd1+ is legal.
        val fen = "4kb1r/p2rqppp/5n2/4p1B1/4P3/1Q6/PPP2PPP/2K4R w k - 0 14"
        val pos = Position.fromFen(fen)
        val move = pos.parseSan("Rd1")
        val after = pos.makeMove(move)
        require(after.legalMoves().any { it.isCapture && it.to.toString() == "d1" }) { "fixture: the rook must be takeable" }
        val before = evalOf(fen, line(1, cp = 886, pv = listOf(move.toUci())))
        val afterEval = evalOf(after.toFen(), line(1, cp = -882))
        val result = classifierWith(FakeSee(hangingSquares = setOf("d1")))
            .classify(pos, move, before, afterEval, isBookPosition = false, isBookMove = false, ply = 27)
        assertEquals(MoveClassification.BRILLIANT, result)
    }

    @Test
    fun `sacrifice that leaves the mover losing is not BRILLIANT`() {
        val fen = "4k3/8/8/8/3n4/5N2/8/4K3 w - - 0 1"
        val pos = Position.fromFen(fen)
        val move = pos.parseSan("Nxd4")
        val after = pos.makeMove(move)

        val see = FakeSee(seeValues = mapOf(move.toUci() to -320))
        // The engine's actual top choice is a different move, so the played sacrifice isn't
        // being scored as "the best move" by construction — otherwise the BEST branch would
        // short-circuit before loss even matters.
        val before = evalOf(fen, line(1, cp = 200, pv = listOf("e1d1")))
        // After the "sac", White (the mover) is now losing badly -> winAfter(mover) < 50.
        val afterEval = evalOf(after.toFen(), line(1, cp = 600)) // +600 for Black (side to move) = bad for White

        val result = classifierWith(see).classify(pos, move, before, afterEval, isBookPosition = false, isBookMove = false, ply = 30)
        assertEquals(MoveClassification.BLUNDER, result)
    }

    // 4. GREAT ------------------------------------------------------------------------------

    @Test
    fun `only move preserving a big multiPV gap is GREAT`() {
        val pos = Position.startPosition()
        val move = pos.parseSan("e4")
        val after = pos.makeMove(move)
        val before = evalOf(
            pos.toFen(),
            line(1, cp = 300, pv = listOf(move.toUci())),
            line(2, cp = -50)
        )
        val afterEval = evalOf(after.toFen(), line(1, cp = -280))

        val result = classifierWith().classify(pos, move, before, afterEval, isBookPosition = false, isBookMove = false, ply = 25)
        assertEquals(MoveClassification.GREAT, result)
    }

    // 5. MISS -------------------------------------------------------------------------------

    @Test
    fun `throwing away a forced mate is MISS`() {
        val pos = Position.startPosition()
        val badMove = pos.parseSan("a3") // definitely not the mating move
        val after = pos.makeMove(badMove)
        val before = evalOf(pos.toFen(), line(1, mateIn = 2, pv = listOf("d1h5")))
        val afterEval = evalOf(after.toFen(), line(1, cp = 20)) // no more mate, roughly equal

        val result = classifierWith().classify(pos, badMove, before, afterEval, isBookPosition = false, isBookMove = false, ply = 15)
        assertEquals(MoveClassification.MISS, result)
    }

    @Test
    fun `throwing away a large winning advantage without mate is MISS`() {
        val pos = Position.startPosition()
        val move = pos.parseSan("a3")
        val after = pos.makeMove(move)
        val before = evalOf(pos.toFen(), line(1, cp = 700, pv = listOf("d1h5")))
        val afterEval = evalOf(after.toFen(), line(1, cp = 650)) // still +650 for opponent = winAfter(mover) very low

        val result = classifierWith().classify(pos, move, before, afterEval, isBookPosition = false, isBookMove = false, ply = 15)
        assertEquals(MoveClassification.MISS, result)
    }

    // 6. BEST --------------------------------------------------------------------------------

    @Test
    fun `move matching engine top choice is BEST`() {
        val pos = Position.startPosition()
        val move = pos.parseSan("e4")
        val after = pos.makeMove(move)
        val before = evalOf(pos.toFen(), line(1, cp = 30, pv = listOf(move.toUci())))
        val afterEval = evalOf(after.toFen(), line(1, cp = -25))

        val result = classifierWith().classify(pos, move, before, afterEval, isBookPosition = false, isBookMove = false, ply = 25)
        assertEquals(MoveClassification.BEST, result)
    }

    // 7-10: loss-banded classifications -----------------------------------------------------

    @Test
    fun `small loss under 2 is EXCELLENT`() {
        val (pos, move, before, afterEval) = lossFixture(bestUci = "b1c3", targetLoss = 1.0)
        val result = classifierWith().classify(pos, move, before, afterEval, isBookPosition = false, isBookMove = false, ply = 25)
        assertEquals(MoveClassification.EXCELLENT, result)
    }

    @Test
    fun `loss under 5 is GOOD`() {
        val (pos, move, before, afterEval) = lossFixture(bestUci = "b1c3", targetLoss = 3.5)
        val result = classifierWith().classify(pos, move, before, afterEval, isBookPosition = false, isBookMove = false, ply = 25)
        assertEquals(MoveClassification.GOOD, result)
    }

    @Test
    fun `loss under 10 is INACCURACY`() {
        val (pos, move, before, afterEval) = lossFixture(bestUci = "b1c3", targetLoss = 7.5)
        val result = classifierWith().classify(pos, move, before, afterEval, isBookPosition = false, isBookMove = false, ply = 25)
        assertEquals(MoveClassification.INACCURACY, result)
    }

    @Test
    fun `loss under 20 is MISTAKE`() {
        val (pos, move, before, afterEval) = lossFixture(bestUci = "b1c3", targetLoss = 15.0)
        val result = classifierWith().classify(pos, move, before, afterEval, isBookPosition = false, isBookMove = false, ply = 25)
        assertEquals(MoveClassification.MISTAKE, result)
    }

    // 11. BLUNDER -----------------------------------------------------------------------------

    @Test
    fun `loss of 20 or more is BLUNDER`() {
        val (pos, move, before, afterEval) = lossFixture(bestUci = "b1c3", targetLoss = 35.0)
        val result = classifierWith().classify(pos, move, before, afterEval, isBookPosition = false, isBookMove = false, ply = 25)
        assertEquals(MoveClassification.BLUNDER, result)
    }

    // Already-decided guard ---------------------------------------------------------------
    //
    // Note on how this is tested: winBefore >= 98 is, by construction of the spec's own
    // formula, only ever reachable via a mate score (winPercent's cp input is clamped to
    // +/-1000, whose max is winPercent(1000) ~= 97.54 — just short of 98). And whenever
    // winBefore comes from a mate score, the MISS rule's "mate-in-N was available and the
    // played move does not mate" clause fires unconditionally (it doesn't gate on winAfter),
    // which outranks the loss-band/guard step of the ladder entirely. So driving the guard
    // through the full classify() ladder end-to-end is not meaningful — it is exercised here
    // directly instead, which is what actually determines its behaviour.

    @Test
    fun `guard clamps a raw BLUNDER to GOOD when the mover is still winning afterwards`() {
        val classifier = MoveClassifier(FakeSee())
        val clamped = classifier.applyDecidedGuard(MoveClassification.BLUNDER, winBefore = 99.0, winAfter = 60.0)
        assertEquals(MoveClassification.GOOD, clamped)
    }

    @Test
    fun `guard does not clamp a BLUNDER that flips who is winning`() {
        val classifier = MoveClassifier(FakeSee())
        val notClamped = classifier.applyDecidedGuard(MoveClassification.BLUNDER, winBefore = 99.0, winAfter = 10.0)
        assertEquals(MoveClassification.BLUNDER, notClamped)
    }

    @Test
    fun `guard also covers the decisively-losing side, clamping MISTAKE to GOOD`() {
        val classifier = MoveClassifier(FakeSee())
        val clamped = classifier.applyDecidedGuard(MoveClassification.MISTAKE, winBefore = 1.0, winAfter = 20.0)
        assertEquals(MoveClassification.GOOD, clamped)
    }

    @Test
    fun `guard leaves non-mistake classifications untouched`() {
        val classifier = MoveClassifier(FakeSee())
        val untouched = classifier.applyDecidedGuard(MoveClassification.EXCELLENT, winBefore = 99.0, winAfter = 99.0)
        assertEquals(MoveClassification.EXCELLENT, untouched)
    }

    @Test
    fun `end-to-end, a near-max non-mate advantage that collapses is still a real BLUNDER`() {
        // Sanity check that the guard doesn't over-fire below its threshold: winBefore here is
        // the practical ceiling for a non-mate eval (~97.5, since cp is clamped to 1000), which
        // is still under the guard's 98 threshold, so this must NOT be clamped.
        val pos = Position.startPosition()
        val move = pos.parseSan("a3")
        val after = pos.makeMove(move)
        val before = evalOf(pos.toFen(), line(1, cp = 1000, pv = listOf("b1c3")))
        // winAfter(white) = 76 (>= 75, so this is NOT also a MISS) but loss ~= 21.5 (>= 20, a
        // real BLUNDER by the loss bands) and winBefore ~= 97.5 stays under the guard's 98
        // threshold, so the guard must not clamp it.
        val afterEval = evalOf(after.toFen(), line(1, cp = invWinPercent(24.0)))

        val result = classifierWith().classify(pos, move, before, afterEval, isBookPosition = false, isBookMove = false, ply = 25)
        assertEquals(MoveClassification.BLUNDER, result)
    }

    // --- helpers ---------------------------------------------------------------------------

    private data class LossFixture(val pos: Position, val move: Move, val before: PositionEval, val after: PositionEval)

    /**
     * Inverse of [WinProbability.winPercent]: the cp value that yields win percent [wp].
     * winPercent(cp) = 50 + 50*tanh(K*cp/2), so cp = 2*atanh((wp-50)/50) / K.
     */
    private fun invWinPercent(wp: Double): Int {
        val t = ((wp - 50.0) / 50.0).coerceIn(-0.999999, 0.999999)
        val atanh = 0.5 * Math.log((1 + t) / (1 - t))
        return Math.round(2.0 * atanh / 0.00368208).toInt()
    }

    /**
     * Builds a White-to-move fixture where the played move (a3) produces an exact win-percent
     * [targetLoss] for White (the mover) relative to the best move, by fixing winBefore at
     * exactly 50% (cp = 0) and solving for the after-position cp that yields
     * `winAfter = 50 - targetLoss`. This makes the loss band exact rather than an
     * approximation, so tests can sit safely inside each band.
     */
    private fun lossFixture(bestUci: String, targetLoss: Double): LossFixture {
        val pos = Position.startPosition()
        val move = pos.parseSan("a3")
        require(move.toUci() != bestUci)
        val after = pos.makeMove(move)
        val before = evalOf(pos.toFen(), line(1, cp = 0, pv = listOf(bestUci)))
        // afterEval's side to move is Black; we need winPercentForColor(after, WHITE) = 50 - targetLoss,
        // i.e. 100 - winPercentOfLine(afterLine) = 50 - targetLoss, i.e. afterLine's own win% = 50 + targetLoss.
        val afterEval = evalOf(after.toFen(), line(1, cp = invWinPercent(50.0 + targetLoss)))
        return LossFixture(pos, move, before, afterEval)
    }
}
