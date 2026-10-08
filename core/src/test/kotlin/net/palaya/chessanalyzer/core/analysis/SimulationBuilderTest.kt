package net.palaya.chessanalyzer.core.analysis

import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.Square
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ANALYSIS_SPEC.md section 6 — the guided walkthrough of a missed tactic. Each ply has to name
 * concrete pieces and squares, material claims have to be true, and the line is truncated at 8
 * plies *or* once the payoff is realised.
 */
class SimulationBuilderTest {

    private val builder = SimulationBuilder()

    private fun sq(name: String) = Square.fromAlgebraic(name)

    // -----------------------------------------------------------------------
    // Concrete pieces and squares
    // -----------------------------------------------------------------------

    @Test
    fun `a knight fork explanation names the knight, its square and both forked pieces`() {
        // Black king g8, black queen d5, white knight e4: Nf6+ forks king and queen.
        val start = Position.fromFen("6k1/8/8/3q4/4N3/8/8/4K3 w - - 0 1")
        val tactic = TacticInstance(
            type = TacticType.FORK,
            byColor = Color.WHITE,
            moveUci = "e4f6",
            targetSquares = listOf(sq("g8"), sq("d5")),
            materialSwing = 900,
            confidence = 0.95
        )

        val sim = builder.build(start, listOf("e4f6", "g8g7", "f6d5", "g7g6"), tactic)
        val first = sim.perPlyExplanation.first()

        assertEquals("Nf6+", sim.pvSan.first())
        assertTrue("should name the moving piece: $first", first.contains("knight"))
        assertTrue("should name the landing square: $first", first.contains("f6"))
        assertTrue("should name the forked king: $first", first.contains("the king on g8"))
        assertTrue("should name the forked queen: $first", first.contains("the queen on d5"))
        assertTrue("should name the motif: $first", first.contains("forking"))
        assertTrue("should mark the check: $first", first.contains("check"))
    }

    @Test
    fun `a capture explanation names the captured piece and its square`() {
        val start = Position.fromFen("6k1/8/8/3q4/4N3/8/8/4K3 w - - 0 1")
        val tactic = TacticInstance(
            type = TacticType.FORK, byColor = Color.WHITE, moveUci = "e4f6",
            targetSquares = listOf(sq("g8"), sq("d5")), materialSwing = 900, confidence = 0.95
        )

        val sim = builder.build(start, listOf("e4f6", "g8g7", "f6d5", "g7g6"), tactic)
        val capture = sim.perPlyExplanation[2]

        assertEquals("Nxd5", sim.pvSan[2])
        assertTrue("should name the capturing piece: $capture", capture.contains("knight"))
        assertTrue("should name the captured piece: $capture", capture.contains("queen"))
        assertTrue("should name the square: $capture", capture.contains("d5"))
        assertTrue("should state the material won: $capture", capture.contains("winning a queen"))
    }

    // -----------------------------------------------------------------------
    // Material honesty
    // -----------------------------------------------------------------------

    @Test
    fun `a losing capture is never described as winning material`() {
        // Rd1xd5 grabs a pawn that is defended by the c6 pawn: -400cp by SEE.
        val start = Position.fromFen("4k3/8/2p5/3p4/8/8/8/3RK3 w - - 0 1")
        val tactic = TacticInstance(
            type = TacticType.HANGING_PIECE, byColor = Color.WHITE, moveUci = "d1d5",
            targetSquares = listOf(sq("d5")), materialSwing = 100, confidence = 0.6
        )

        val sim = builder.build(start, listOf("d1d5", "c6d5"), tactic)
        val losing = sim.perPlyExplanation.first()

        assertEquals("Rxd5", sim.pvSan.first())
        assertTrue("should name the rook: $losing", losing.contains("rook"))
        assertTrue("should name the square: $losing", losing.contains("d5"))
        assertFalse("must not claim material: $losing", losing.contains("winning"))
        assertFalse("must not claim material: $losing", losing.contains("wins a"))
        assertTrue(
            "should read as a sacrifice: $losing",
            losing.contains("the rook gives itself up for the pawn on d5")
        )

        assertFalse(
            "the payoff must not claim material that the line does not win: ${sim.payoffDescription}",
            sim.payoffDescription.startsWith("wins")
        )
    }

    @Test
    fun `an even recapture is not described as winning material`() {
        // Nxd5 grabs a pawn but is recaptured by the c6 pawn: SEE is -220, so no material claim.
        val start = Position.fromFen("4k3/8/2p5/3p4/5N2/8/8/4K3 w - - 0 1")
        val tactic = TacticInstance(
            type = TacticType.HANGING_PIECE, byColor = Color.WHITE, moveUci = "f4d5",
            targetSquares = listOf(sq("d5")), materialSwing = 100, confidence = 0.6
        )

        val sim = builder.build(start, listOf("f4d5", "c6d5"), tactic)
        val whitePly = sim.perPlyExplanation.first()
        assertEquals("Nxd5", sim.pvSan.first())
        assertFalse("must not claim material: $whitePly", whitePly.contains("winning"))
        assertTrue(
            "should name the knight and the square: $whitePly",
            whitePly.contains("knight") && whitePly.contains("d5")
        )
        // Black's recapture takes a knight for the pawn it had just lost: material is won over the
        // pair of captures, so it may say so - as material, not as "a piece", because the pawn went.
        assertTrue(sim.perPlyExplanation[1], sim.perPlyExplanation[1].contains("winning material"))
    }

    // -----------------------------------------------------------------------
    // Truncation and payoff
    // -----------------------------------------------------------------------

    @Test
    fun `truncation stops once the payoff is realised, not at 8 plies`() {
        val start = Position.fromFen("6k1/8/8/3q4/4N3/8/8/4K3 w - - 0 1")
        val tactic = TacticInstance(
            type = TacticType.FORK, byColor = Color.WHITE, moveUci = "e4f6",
            targetSquares = listOf(sq("g8"), sq("d5")), materialSwing = 900, confidence = 0.95
        )
        // A deliberately long, fully legal PV: the queen is won on ply 3 and the deal settles
        // after Black's reply on ply 4, so the walkthrough must stop there.
        val longPv = listOf("e4f6", "g8g7", "f6d5", "g7g6", "d5f4", "g6g5", "f4e2", "g5g4")

        val sim = builder.build(start, longPv, tactic)

        assertEquals(4, sim.pvUci.size)
        assertEquals(4, sim.pvSan.size)
        assertEquals(4, sim.perPlyExplanation.size)
        assertEquals("wins a queen", sim.payoffDescription)
    }

    @Test
    fun `truncation still caps a quiet line at 8 plies`() {
        val start = Position.startPosition()
        val tactic = TacticInstance(
            type = TacticType.CLEARANCE, byColor = Color.WHITE, moveUci = "e2e4",
            materialSwing = 0, confidence = 0.6
        )
        val quietPv = listOf(
            "e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "g8f6", "d2d3", "f8c5", "e1g1", "e8g8"
        )

        val sim = builder.build(start, quietPv, tactic)
        assertEquals(8, sim.pvUci.size)
    }

    @Test
    fun `the final frame states a real mate payoff`() {
        // Back-rank mate: Rd8#.
        val start = Position.fromFen("6k1/5ppp/8/8/8/8/8/3R2K1 w - - 0 1")
        val tactic = TacticInstance(
            type = TacticType.BACK_RANK_MATE, byColor = Color.WHITE, moveUci = "d1d8",
            targetSquares = listOf(sq("g8")), materialSwing = 0, confidence = 0.95
        )

        val sim = builder.build(start, listOf("d1d8", "g8f8"), tactic)

        assertEquals(1, sim.pvUci.size)
        assertEquals("Rd8#", sim.pvSan.single())
        assertEquals("mates in 1", sim.payoffDescription)
        val frame = sim.perPlyExplanation.single()
        assertTrue("should name the rook: $frame", frame.contains("rook"))
        assertTrue("should name the square: $frame", frame.contains("d8"))
        assertTrue("should state mate: $frame", frame.contains("checkmate"))
        assertTrue("final frame should state the payoff: $frame", frame.contains("mates in 1"))
    }

    @Test
    fun `the final frame states the material payoff`() {
        val start = Position.fromFen("6k1/8/8/3q4/4N3/8/8/4K3 w - - 0 1")
        val tactic = TacticInstance(
            type = TacticType.FORK, byColor = Color.WHITE, moveUci = "e4f6",
            targetSquares = listOf(sq("g8"), sq("d5")), materialSwing = 900, confidence = 0.95
        )

        val sim = builder.build(start, listOf("e4f6", "g8g7", "f6d5", "g7g6"), tactic)
        assertTrue(
            "final frame should state the payoff: ${sim.perPlyExplanation.last()}",
            sim.perPlyExplanation.last().contains("wins a queen")
        )
    }

    @Test
    fun `explanations are never the old canned strings`() {
        val start = Position.fromFen("6k1/8/8/3q4/4N3/8/8/4K3 w - - 0 1")
        val tactic = TacticInstance(
            type = TacticType.FORK, byColor = Color.WHITE, moveUci = "e4f6",
            targetSquares = listOf(sq("g8"), sq("d5")), materialSwing = 900, confidence = 0.95
        )
        val sim = builder.build(start, listOf("e4f6", "g8g7", "f6d5", "g7g6"), tactic)

        val canned = setOf("continues the attack.", "wins material.", "gives check.")
        sim.perPlyExplanation.forEach { text ->
            assertTrue("blank explanation", text.isNotBlank())
            assertTrue("explanation should end in a full stop: $text", text.trim().endsWith("."))
            assertFalse("generic canned text leaked through: $text", canned.any { text.endsWith(it) })
        }
    }

    @Test
    fun `the simulation never mutates the start position`() {
        val startFen = "6k1/8/8/3q4/4N3/8/8/4K3 w - - 0 1"
        val start = Position.fromFen(startFen)
        val tactic = TacticInstance(
            type = TacticType.FORK, byColor = Color.WHITE, moveUci = "e4f6",
            targetSquares = listOf(sq("g8"), sq("d5")), materialSwing = 900, confidence = 0.95
        )

        val sim = builder.build(start, listOf("e4f6", "g8g7", "f6d5", "g7g6"), tactic)
        assertEquals(startFen, sim.startFen)
        assertEquals(startFen, start.toFen())
    }

    // -----------------------------------------------------------------------
    // R1b: what the walkthrough may claim (ANALYSIS_SPEC 6.1)
    // -----------------------------------------------------------------------

    @Test
    fun `a motif that only attacks a loose piece never claims to collect it`() {
        // Nf3 attacks the e5 pawn, which nothing defends. The opponent moves next: it is attacked, not won.
        val start = Position.fromFen("4k3/8/8/4p3/8/8/8/4K1N1 w - - 0 1")
        val tactic = TacticInstance(
            type = TacticType.HANGING_PIECE, byColor = Color.WHITE, moveUci = "g1f3",
            targetSquares = listOf(sq("e5")), materialSwing = 100, confidence = 0.6
        )
        val sim = builder.build(start, listOf("g1f3", "e8e7"), tactic)
        val first = sim.perPlyExplanation.first()
        assertEquals("Nf3: the knight lands on f3, attacking the pawn on e5, which nothing defends.", first)
        assertFalse(first, first.contains("collecting"))
    }

    @Test
    fun `a motif whose target is defended is not described as attacking an undefended piece`() {
        val start = Position.fromFen("4k3/8/3p4/4p3/8/8/8/4K1N1 w - - 0 1")
        val tactic = TacticInstance(
            type = TacticType.HANGING_PIECE, byColor = Color.WHITE, moveUci = "g1f3",
            targetSquares = listOf(sq("e5")), materialSwing = 100, confidence = 0.6
        )
        val first = builder.build(start, listOf("g1f3", "e8e7"), tactic).perPlyExplanation.first()
        assertFalse(first, first.contains("nothing defends"))
        assertFalse(first, first.contains("collecting"))
    }

    @Test
    fun `a recapture is judged by the pair of captures, not by the second one alone`() {
        // The Opera Game's missed 4...Nc6 line: exd6 Qxd6 Qxd6 Bxd6. Each side takes a pawn, then a queen
        // is exchanged for a queen: nothing is won by either recapture on its own merits.
        val start = Position.fromFen("rn1qkbnr/ppp2ppp/3p4/4P3/4P1b1/5N2/PPP2PPP/RNBQKB1R b KQkq - 0 4")
        val tactic = TacticInstance(
            type = TacticType.CLEARANCE, byColor = Color.BLACK, moveUci = "b8c6",
            materialSwing = 0, confidence = 0.6
        )
        val sim = builder.build(start, listOf("b8c6", "e5d6", "d8d6", "d1d6", "f8d6"), tactic)
        assertEquals(listOf("Nc6", "exd6", "Qxd6", "Qxd6", "Bxd6"), sim.pvSan)
        assertFalse("Qxd6 takes back a pawn: ${sim.perPlyExplanation[2]}", sim.perPlyExplanation[2].contains("winning"))
        assertFalse("Bxd6 takes back a queen: ${sim.perPlyExplanation[4]}", sim.perPlyExplanation[4].contains("winning"))
        // White's Qxd6 takes a queen for the pawn it had lost: that does win material.
        assertTrue(sim.perPlyExplanation[3], sim.perPlyExplanation[3].contains("winning material"))
    }

    @Test
    fun `an exchange of a rook for a bishop is the exchange, never a pawn`() {
        // Bxg7 takes a rook and the king takes the bishop back: +170, which is neither a pawn nor a piece,
        // and is exactly what a player calls winning the exchange (C1).
        val start = Position.fromFen("4k3/6R1/7K/8/3b4/8/8/8 b - - 0 1")
        val tactic = TacticInstance(
            type = TacticType.HANGING_PIECE, byColor = Color.BLACK, moveUci = "d4g7",
            targetSquares = listOf(sq("g7")), materialSwing = 170, confidence = 0.6
        )
        val sim = builder.build(start, listOf("d4g7"), tactic)
        val first = sim.perPlyExplanation.first()
        assertTrue(first, first.contains("winning the exchange"))
        assertFalse(first, first.contains("winning a pawn"))
        // The line stops after Bxg7 with the king to take back: settled, Black is the exchange up.
        assertEquals("wins the exchange", sim.payoffDescription)
    }

    @Test
    fun `a line that stops right after a capture the opponent takes straight back claims nothing`() {
        // Nxe5 wins a pawn on the board at the end of the line, but dxe5 takes the knight next move.
        val start = Position.fromFen("6k1/8/3p4/4p3/8/5N2/8/4K3 w - - 0 1")
        val tactic = TacticInstance(
            type = TacticType.HANGING_PIECE, byColor = Color.WHITE, moveUci = "f3e5",
            targetSquares = listOf(sq("e5")), materialSwing = 100, confidence = 0.6
        )
        val sim = builder.build(start, listOf("f3e5"), tactic)
        assertEquals("", sim.payoffDescription)
        assertFalse(sim.perPlyExplanation.last(), sim.perPlyExplanation.last().contains("wins"))
    }

    @Test
    fun `a line that neither mates nor nets anything says nothing about its payoff`() {
        val start = Position.startPosition()
        val tactic = TacticInstance(
            type = TacticType.CLEARANCE, byColor = Color.WHITE, moveUci = "e2e4",
            materialSwing = 0, confidence = 0.6
        )
        val quietPv = listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "g8f6", "d2d3", "f8c5")
        val sim = builder.build(start, quietPv, tactic)
        assertEquals("", sim.payoffDescription)
        for (vague in listOf("invested material", "decisive advantage", "king under fire", "mating net")) {
            assertFalse(vague, sim.perPlyExplanation.any { vague in it })
        }
        // ...and no sentence is bolted onto the last step.
        assertFalse(sim.perPlyExplanation.last(), sim.perPlyExplanation.last().contains("White "))
    }

    @Test
    fun `a deflection drags the defender, not the square it was holding`() {
        // The knight on c3 guards d5; ...Rd5? is not the point, the deflection of the knight is.
        val start = Position.fromFen("3r2k1/8/8/3p4/8/2n5/8/R3K3 w - - 0 1")
        val tactic = TacticInstance(
            type = TacticType.DEFLECTION, byColor = Color.WHITE, moveUci = "a1a3",
            targetSquares = listOf(sq("d5")), involvedSquares = listOf(sq("c3"), sq("a3")),
            materialSwing = 100, confidence = 0.95
        )
        val first = builder.build(start, listOf("a1a3", "g8g7"), tactic).perPlyExplanation.first()
        assertTrue(first, first.contains("dragging the knight on c3 away from the defence"))
        assertFalse("the target pawn is not the dragged piece: $first", first.contains("dragging the pawn"))
    }
}
