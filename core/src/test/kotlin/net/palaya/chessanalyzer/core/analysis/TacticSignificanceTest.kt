package net.palaya.chessanalyzer.core.analysis

import net.palaya.chessanalyzer.core.chess.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ANALYSIS_SPEC §9.6 — the significance gate on detected tactics.
 *
 * The boundary is tested at 49 / 50 / 51 exactly as the move threshold is, and every protection
 * the spec lists for a decisive-but-low-swing tactic has its own test, because each of them is a
 * case where a naive gate deletes the most instructive moment of the game.
 */
class TacticSignificanceTest {

    private val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    private val afterE4 = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"

    /** A real checkmate position, so the "is it mate" fallback can be exercised without a `#`. */
    private val foolsMateBefore = "rnbqkbnr/pppp1ppp/8/4p3/6P1/5P2/PPPPP2P/RNBQKBNR b KQkq g3 0 2"
    private val foolsMateAfter = "rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3"

    private fun fork(color: Color = Color.WHITE) =
        TacticInstance(TacticType.FORK, color, "a1a2", materialSwing = 500, confidence = 0.95)

    private fun pin(color: Color = Color.WHITE) =
        TacticInstance(TacticType.PIN_RELATIVE, color, "a1a2", confidence = 0.6)

    private fun annotation(
        ply: Int,
        evalBefore: Int = 0,
        evalAfter: Int = 0,
        classification: MoveClassification = MoveClassification.BEST,
        found: List<TacticInstance> = emptyList(),
        missed: List<TacticInstance> = emptyList(),
        secondBest: Int? = null,
        mateInBefore: Int? = null,
        mateInAfter: Int? = null,
        san: String = "e4",
        fenBefore: String = startFen,
        fenAfter: String = afterE4,
        simulation: TacticSimulation? = null
    ) = MoveAnnotation(
        ply = ply,
        moveNumber = (ply + 1) / 2,
        color = if (ply % 2 == 1) Color.WHITE else Color.BLACK,
        san = san,
        uci = "e2e4",
        fenBefore = fenBefore,
        fenAfter = fenAfter,
        classification = classification,
        loss = 0.0,
        winPercentBefore = 50.0,
        winPercentAfter = 50.0,
        evalBeforeCp = evalBefore,
        evalAfterCp = evalAfter,
        mateInBefore = mateInBefore,
        mateInAfter = mateInAfter,
        evalSecondBestCp = secondBest,
        tacticsFound = found,
        tacticsMissed = missed,
        simulation = simulation
    )

    private fun prunedFound(vararg a: MoveAnnotation, threshold: Int = 50): List<List<TacticInstance>> =
        TacticSignificance.prune(a.toList(), threshold).map { it.tacticsFound }

    // -----------------------------------------------------------------------
    // Missed tactics: the ply's own swing, at the 49/50/51 boundary
    // -----------------------------------------------------------------------

    @Test
    fun `a missed tactic is judged by the ply's swing at the exact boundary`() {
        for ((swing, kept) in listOf(49 to false, 50 to true, 51 to true)) {
            val a = annotation(1, evalBefore = 0, evalAfter = -swing, classification = MoveClassification.INACCURACY, missed = listOf(fork()))
            val out = TacticSignificance.prune(listOf(a), 50).single()
            assertEquals("swing $swing", kept, out.tacticsMissed.isNotEmpty())
        }
    }

    @Test
    fun `the missed swing is absolute, so the sign does not matter`() {
        val a = annotation(2, evalBefore = 0, evalAfter = 50, classification = MoveClassification.MISTAKE, missed = listOf(fork(Color.BLACK)))
        assertTrue(TacticSignificance.prune(listOf(a), 50).single().tacticsMissed.isNotEmpty())
    }

    // -----------------------------------------------------------------------
    // Found tactics: the counterfactual, not the (zero) swing
    // -----------------------------------------------------------------------

    @Test
    fun `a found tactic on a best move has no swing of its own and is judged by the MultiPV margin`() {
        // Best line +500 (the fork), second line 0: finding it was worth 500. Swing itself is 0.
        val a = annotation(1, evalBefore = 500, evalAfter = 500, secondBest = 0, found = listOf(fork()))
        assertEquals(0, TacticSignificance.swingCp(a))
        assertEquals(500, TacticSignificance.foundSwingCp(a, null))
        assertTrue(prunedFound(a).single().isNotEmpty())
    }

    @Test
    fun `a found tactic whose alternative was just as good is minor`() {
        // Bg5 "pins" a knight to the queen but Be3 scores the same: nothing turned on it.
        val a = annotation(1, evalBefore = 30, evalAfter = 30, secondBest = 25, found = listOf(pin()))
        assertTrue(prunedFound(a).single().isEmpty())
    }

    @Test
    fun `the MultiPV margin is tested at the boundary`() {
        for ((margin, kept) in listOf(49 to false, 50 to true, 51 to true)) {
            val a = annotation(1, evalBefore = 100, evalAfter = 100, secondBest = 100 - margin, found = listOf(fork()))
            assertEquals("margin $margin", kept, prunedFound(a).single().isNotEmpty())
        }
    }

    @Test
    fun `without a second line the fork that punished a blunder is credited via the two-ply swing`() {
        // Ply 1: Black to move? No — ply 1 is White's blunder (0 -> -500), ply 2 Black's fork
        // keeps -500. Black's fork has zero swing and no MultiPV margin; the pair swing is 500.
        val blunder = annotation(1, evalBefore = 0, evalAfter = -500, classification = MoveClassification.BLUNDER)
        val punish = annotation(2, evalBefore = -500, evalAfter = -500, found = listOf(fork(Color.BLACK)))
        val out = TacticSignificance.prune(listOf(blunder, punish), 50)
        assertTrue(out[1].tacticsFound.isNotEmpty())
        assertEquals(500, TacticSignificance.foundSwingCp(punish, blunder))
    }

    // -----------------------------------------------------------------------
    // THE TRAP: decisive tactics with no swing must survive any threshold
    // -----------------------------------------------------------------------

    @Test
    fun `the checkmating move survives an absurd threshold even with zero swing`() {
        // A queen up, the eval was already saturated; the mate barely moves the number.
        val a = annotation(
            1, evalBefore = 9950, evalAfter = 10000, san = "Qh4#",
            found = listOf(TacticInstance(TacticType.BACK_RANK_MATE, Color.WHITE, "a1a2", materialSwing = 10000, confidence = 0.95))
        )
        assertTrue(prunedFound(a, threshold = 1_000_000).single().isNotEmpty())
    }

    @Test
    fun `a checkmating move is recognised from the position when the PGN omitted the hash`() {
        val a = annotation(
            2, evalBefore = -300, evalAfter = -10000, san = "Qh4", fenBefore = foolsMateBefore, fenAfter = foolsMateAfter,
            found = listOf(TacticInstance(TacticType.MATE_NET, Color.BLACK, "d8h4", confidence = 0.95))
        )
        assertTrue(TacticSignificance.isDecisiveFound(a))
        assertTrue(prunedFound(a, threshold = 1_000_000).single().isNotEmpty())
    }

    @Test
    fun `a quiet move that keeps a forced mate on the board survives`() {
        // M5 -> M4 for White: swing is one MATE_STEP (50cp), which a 60cp threshold would cut.
        val a = annotation(
            1, evalBefore = 9750, evalAfter = 9800, mateInBefore = 5, mateInAfter = 4,
            found = listOf(TacticInstance(TacticType.MATE_NET, Color.WHITE, "a1a2", confidence = 0.95))
        )
        assertEquals(50, TacticSignificance.swingCp(a))
        assertTrue(prunedFound(a, threshold = 300).single().isNotEmpty())
    }

    @Test
    fun `a forced mate for the OTHER side does not protect the mover's tactic`() {
        // Black is getting mated; White-relative mateInAfter = +3 is not a mate for Black.
        val a = annotation(2, evalBefore = 9850, evalAfter = 9850, mateInAfter = 3, found = listOf(pin(Color.BLACK)))
        assertFalse(TacticSignificance.isDecisiveFound(a))
        assertTrue(prunedFound(a).single().isEmpty())
    }

    @Test
    fun `BRILLIANT and GREAT moves are significant by the classifier's own verdict`() {
        for (c in listOf(MoveClassification.BRILLIANT, MoveClassification.GREAT)) {
            val a = annotation(1, evalBefore = 200, evalAfter = 200, classification = c, found = listOf(fork()))
            assertTrue("$c", prunedFound(a, threshold = 1_000_000).single().isNotEmpty())
        }
        val best = annotation(1, evalBefore = 200, evalAfter = 200, classification = MoveClassification.BEST, found = listOf(fork()))
        assertTrue("BEST alone is not a protection", prunedFound(best, threshold = 1_000_000).single().isEmpty())
    }

    @Test
    fun `a missed forced mate survives any threshold, as does a MISS`() {
        val missedMate = annotation(
            1, evalBefore = 9800, evalAfter = 9750, mateInBefore = 4, mateInAfter = 5,
            classification = MoveClassification.GOOD, missed = listOf(fork())
        )
        assertTrue(TacticSignificance.prune(listOf(missedMate), 1_000_000).single().tacticsMissed.isNotEmpty())

        val miss = annotation(1, evalBefore = 0, evalAfter = 0, classification = MoveClassification.MISS, missed = listOf(fork()))
        assertTrue(TacticSignificance.prune(listOf(miss), 1_000_000).single().tacticsMissed.isNotEmpty())
    }

    // -----------------------------------------------------------------------
    // Sequences and the off switch
    // -----------------------------------------------------------------------

    @Test
    fun `a member of a motif run whose combined swing clears the bar survives`() {
        // Three missed forks, 20cp each on their own, 60cp across the run (a TACTIC sequence).
        val plies = listOf(
            annotation(1, 0, -20, MoveClassification.INACCURACY, missed = listOf(fork())),
            annotation(2, -20, -40, MoveClassification.INACCURACY, missed = listOf(fork())),
            annotation(3, -40, -60, MoveClassification.INACCURACY, missed = listOf(fork()))
        )
        // The run is keyed on (type, colour); make all three the same colour so it forms.
        val sameColour = plies.map { it.copy(color = Color.WHITE, tacticsMissed = listOf(fork(Color.WHITE))) }
        val out = TacticSignificance.prune(sameColour, 50)
        assertTrue("middle of a 60cp run must survive", out[1].tacticsMissed.isNotEmpty())

        // Control: no run, each 20cp move is judged alone.
        val alone = annotation(1, 0, -20, MoveClassification.INACCURACY, missed = listOf(fork()))
        assertTrue(TacticSignificance.prune(listOf(alone), 50).single().tacticsMissed.isEmpty())
    }

    @Test
    fun `a threshold of zero disables the gate`() {
        val a = annotation(1, evalBefore = 0, evalAfter = 0, found = listOf(pin()), missed = listOf(pin()))
        val out = TacticSignificance.prune(listOf(a), 0).single()
        assertEquals(1, out.tacticsFound.size)
        assertEquals(1, out.tacticsMissed.size)
    }

    // -----------------------------------------------------------------------
    // What pruning touches, and what it leaves alone
    // -----------------------------------------------------------------------

    @Test
    fun `the simulation goes with its missed tactic, and the commentary text stays`() {
        val sim = TacticSimulation(startFen, listOf("e2e4"), listOf("e4"), listOf("e4."), fork(), "wins a rook")
        val a = annotation(1, evalBefore = 0, evalAfter = -10, classification = MoveClassification.INACCURACY, missed = listOf(fork()), simulation = sim)
            .copy(text = "Inaccuracy. This misses a fork.")
        val out = TacticSignificance.prune(listOf(a), 50).single()
        assertTrue(out.tacticsMissed.isEmpty())
        assertNull("no listed tactic, no Show me", out.simulation)
        assertEquals("Inaccuracy. This misses a fork.", out.text)

        val kept = TacticSignificance.prune(listOf(a.copy(evalAfterCp = -300)), 50).single()
        assertNotNull(kept.simulation)
    }

    @Test
    fun `the player reports are rebuilt from the pruned annotations`() {
        val a = annotation(1, evalBefore = 30, evalAfter = 30, secondBest = 25, found = listOf(pin()))
        val report = GameReport(
            white = PlayerReport(Color.WHITE, null, 90.0, 1500, false, emptyMap(), tacticsFound = listOf(pin()), tacticsMissed = emptyList()),
            black = PlayerReport(Color.BLACK, null, 90.0, 1500, false, emptyMap(), emptyList(), emptyList()),
            annotations = listOf(a),
            openingName = null, openingEco = null, result = "*", evalGraph = listOf(50.0, 50.0), keyMoments = emptyList(), analysisDepth = 14
        )
        val pruned = TacticSignificance.prune(report, 50)
        assertTrue(pruned.white.tacticsFound.isEmpty())
        assertEquals("nothing else changes", report.white.accuracy, pruned.white.accuracy, 0.0)
        assertEquals(report.keyMoments, pruned.keyMoments)
    }

    @Test
    fun `minor lists exactly what the gate removed`() {
        val a = annotation(1, evalBefore = 30, evalAfter = 30, secondBest = 25, found = listOf(pin(), fork()))
        val minor = TacticSignificance.minor(listOf(a), 50).single()
        assertEquals(listOf(pin(), fork()), minor.tacticsFound)
        // BRILLIANT protects the fork (engine-confirmed) but not the static pin riding on it.
        val decisive = a.copy(classification = MoveClassification.BRILLIANT)
        assertEquals(listOf(pin()), TacticSignificance.minor(listOf(decisive), 50).single().tacticsFound)
    }

    @Test
    fun `a found motif the engine never cashes in is minor even on a move that mattered`() {
        // A forced recapture: huge MultiPV margin (the alternative loses a piece), and a static
        // relative pin (0.6) happens to appear. The move mattered; the pin did not.
        val a = annotation(1, evalBefore = 100, evalAfter = 100, secondBest = -400, found = listOf(pin(), fork()))
        val kept = prunedFound(a).single()
        assertEquals(listOf(fork()), kept)
        assertTrue(TacticSignificance.CONFIRMED_CONFIDENCE > pin().confidence)
        assertTrue(fork().confidence >= TacticSignificance.CONFIRMED_CONFIDENCE)
    }
}
