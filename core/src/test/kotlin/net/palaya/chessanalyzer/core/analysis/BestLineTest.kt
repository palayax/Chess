package net.palaya.chessanalyzer.core.analysis

import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.moveToSan
import net.palaya.chessanalyzer.core.chess.parseUci
import net.palaya.chessanalyzer.core.narration.RealGameFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ANALYSIS_SPEC §6.2 (V2): which engine lines the Board's "Show the best line" plays, how much of each,
 * and that every step of every one is a legal move whose SAN the core move generator agrees with.
 */
class BestLineTest {

    private val start = Position.STANDARD_START_FEN
    private val ruyLopez = listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1b5", "a7a6", "b5a4", "g8f6", "e1g1", "f8e7")

    private fun line(pv: List<String>, depth: Int, multiPv: Int = 1, cp: Int? = 30, mate: Int? = null) =
        CandidateLine(multiPv, pv.first(), null, cp, mate, pv, depth)

    // ---------------------------------------------------------------------------------------------
    // PV to SAN, legal on every step
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a PV becomes SAN with move numbers, each step legal in the position before it`() {
        val l = BestLines.build(start, line(ruyLopez, depth = 18))!!
        assertEquals(listOf("e4", "e5", "Nf3", "Nc6", "Bb5", "a6", "Ba4", "Nf6"), l.sans)
        assertEquals(listOf(1, 1, 2, 2, 3, 3, 4, 4), l.steps.map { it.moveNumber })
        assertEquals(listOf(Color.WHITE, Color.BLACK), l.steps.take(2).map { it.color })
        var fen = start
        for (s in l.steps) {
            assertEquals("each step starts where the previous one ended", fen, s.fenBefore)
            val pos = Position.fromFen(s.fenBefore)
            val move = pos.parseUci(s.uci)
            assertTrue("${s.uci} is legal", pos.legalMoves().any { it.toUci() == s.uci })
            assertEquals(pos.moveToSan(move), s.san)
            assertEquals(pos.makeMove(move).toFen(), s.fenAfter)
            fen = s.fenAfter
        }
        assertEquals(fen, l.endFen)
        assertEquals(Color.WHITE, l.mover)
        assertEquals(ruyLopez.size, l.pvLength)
    }

    @Test
    fun `a line that starts with Black is numbered from the position's own move counter`() {
        val fen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 3 18"
        val l = BestLines.build(fen, line(listOf("g8f6", "b1c3", "f8c5"), depth = 12))!!
        assertEquals(listOf(18, 19, 19), l.steps.map { it.moveNumber })
        assertEquals(listOf(Color.BLACK, Color.WHITE, Color.BLACK), l.steps.map { it.color })
        assertEquals(Color.BLACK, l.mover)
    }

    @Test
    fun `the line stops at the first move that is not legal, and a line whose first move is illegal is no line`() {
        val broken = BestLines.build(start, line(listOf("e2e4", "e7e5", "e4e5", "g8f6"), depth = 18))!!
        assertEquals(listOf("e4", "e5"), broken.sans)
        assertNull(BestLines.build(start, line(listOf("e2e5", "e7e5"), depth = 18)))
        assertNull(BestLines.build("not a fen", line(ruyLopez, depth = 18)))
    }

    // ---------------------------------------------------------------------------------------------
    // Truncation
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `at most depth over two plies, at most eight, never more than the PV`() {
        assertEquals(6, BestLines.maxPliesFor(12))
        assertEquals(7, BestLines.maxPliesFor(14))
        assertEquals(8, BestLines.maxPliesFor(16))
        assertEquals(8, BestLines.maxPliesFor(18))
        assertEquals(8, BestLines.maxPliesFor(30))
        assertEquals(1, BestLines.maxPliesFor(1))
        assertEquals(1, BestLines.maxPliesFor(3))
        assertEquals("an unknown depth shows the first move only", 1, BestLines.maxPliesFor(0))

        assertEquals(6, BestLines.build(start, line(ruyLopez, depth = 12))!!.steps.size)
        assertEquals(7, BestLines.build(start, line(ruyLopez, depth = 14))!!.steps.size)
        assertEquals(8, BestLines.build(start, line(ruyLopez, depth = 18))!!.steps.size)
        assertEquals(8, BestLines.build(start, line(ruyLopez, depth = 20))!!.steps.size)
        assertEquals(3, BestLines.build(start, line(ruyLopez.take(3), depth = 20))!!.steps.size)
        assertEquals(1, BestLines.build(start, line(ruyLopez, depth = 0))!!.steps.size)
        // A caller may ask for fewer (the video's four), never for more than the depth supports.
        assertEquals(4, BestLines.build(start, line(ruyLopez, depth = 20), maxPlies = 4)!!.steps.size)
        assertEquals(6, BestLines.build(start, line(ruyLopez, depth = 12), maxPlies = 40)!!.steps.size)
    }

    @Test
    fun `a line without its PV is its first move alone`() {
        val l = BestLines.build(start, CandidateLine(1, "d2d4", "d4", 20, null))!!
        assertEquals(listOf("d4"), l.sans)
    }

    @Test
    fun `the line ends at checkmate, and the board says so`() {
        val fen = "6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1"
        // A PV never continues past mate, but if it did the extra moves would not be shown.
        val l = BestLines.build(fen, CandidateLine(1, "a1a8", null, null, 1, listOf("a1a8", "g8h8"), 12))!!
        assertEquals(listOf("Ra8#"), l.sans)
        assertTrue(l.endsInCheckmate)
    }

    @Test
    fun `the material the line nets is settled, so a capture that is taken straight back nets nothing`() {
        // Qxd4 and the king steps away: White is a knight up and it is White's move.
        val free = BestLines.build("4k3/8/8/8/3n4/8/8/3QK3 w - - 0 1", line(listOf("d1d4", "e8f7"), depth = 12))!!
        assertEquals(320, free.settledGainCp)
        // Qxd4 with the knight defended by e5, and the line stops there: exd4 is charged.
        val takenBack = BestLines.build("4k3/8/8/4p3/3n4/8/8/3QK3 w - - 0 1", line(listOf("d1d4"), depth = 12))!!
        assertTrue("${takenBack.settledGainCp}", takenBack.settledGainCp < 0)
    }

    // ---------------------------------------------------------------------------------------------
    // Which lines
    // ---------------------------------------------------------------------------------------------

    private fun annotation(played: String, lines: List<CandidateLine>) = MoveAnnotation(
        ply = 1, moveNumber = 1, color = Color.WHITE, san = "x", uci = played, fenBefore = start, fenAfter = start,
        classification = MoveClassification.MISTAKE, loss = 15.0, winPercentBefore = 60.0, winPercentAfter = 45.0,
        evalBeforeCp = 100, evalAfterCp = -50, bestMoveUci = lines.firstOrNull()?.uci, candidateLines = lines,
    )

    @Test
    fun `lines 2 and 3 are offered only within two win-percent of the best, and never the move played`() {
        val best = line(listOf("e2e4", "e7e5"), depth = 14, multiPv = 1, cp = 100)
        val close = line(listOf("d2d4", "d7d5"), depth = 14, multiPv = 2, cp = 90) // 0.9 win-% below
        val far = line(listOf("c2c4", "e7e5"), depth = 14, multiPv = 3, cp = 0) // 9 win-% below
        assertEquals(listOf(1, 2), BestLines.linesFor(annotation("a2a3", listOf(best, close, far))).map { it.multiPv })
        // Line 2 is the move that was played: the game, not an alternative.
        assertEquals(listOf(1), BestLines.linesFor(annotation("d2d4", listOf(best, close, far))).map { it.multiPv })
        // Unsorted input is sorted; a mate for the mover is 100 percent and a cp line is not within 2 of it.
        val mate = line(listOf("e2e4"), depth = 14, multiPv = 1, cp = null, mate = 3)
        assertEquals(listOf(1), BestLines.linesFor(annotation("a2a3", listOf(close, mate))).map { it.multiPv })
        val mate2 = line(listOf("d2d4"), depth = 14, multiPv = 2, cp = null, mate = 5)
        assertEquals(listOf(1, 2), BestLines.linesFor(annotation("a2a3", listOf(mate2, mate))).map { it.multiPv })
        assertTrue(BestLines.linesFor(annotation("a2a3", emptyList())).isEmpty())
        assertEquals(2.0, BestLines.ALTERNATIVE_MARGIN, 0.0)
    }

    // ---------------------------------------------------------------------------------------------
    // Every line of the recorded games
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `every line of every move of the five recorded games is the legal prefix of the engine's PV, cut by the rule`() {
        val games = listOf(
            RealGameFixture.scholars, RealGameFixture.chesscom, RealGameFixture.immortal,
            RealGameFixture.game01, RealGameFixture.byrneFischer,
        )
        var lines = 0
        var alternatives = 0
        for (g in games) {
            val report = g.report(null)
            for (a in report.annotations) {
                val eval = g.evals[a.ply - 1]
                val shown = BestLines.linesFor(a)
                assertTrue("ply ${a.ply} has a line", shown.isNotEmpty())
                assertEquals(1, shown.first().multiPv)
                for (l in shown) {
                    lines++
                    if (l.multiPv > 1) alternatives++
                    val recorded = eval.lines.single { it.multiPv == l.multiPv }
                    assertEquals(eval.depth, l.depth)
                    assertEquals(recorded.pvUci.take(l.steps.size), l.ucis)
                    val cap = minOf(recorded.pvUci.size, eval.depth / 2, BestLines.MAX_PLIES)
                    assertTrue("ply ${a.ply} line ${l.multiPv}: ${l.steps.size} > $cap", l.steps.size <= cap)
                    assertTrue(l.steps.size == cap || l.endsInCheckmate)
                    var pos = Position.fromFen(a.fenBefore)
                    for (s in l.steps) {
                        val move = pos.legalMoves().singleOrNull { it.toUci() == s.uci }
                        assertNotNull("ply ${a.ply}: ${s.uci} legal", move)
                        assertEquals(pos.moveToSan(move!!), s.san)
                        pos = pos.makeMove(move)
                    }
                    assertEquals(recorded.scoreCp, l.scoreCp)
                    assertEquals(recorded.mateIn, l.mateIn)
                    if (l.multiPv > 1) {
                        assertTrue(shown.first().moverWinPercent - l.moverWinPercent <= BestLines.ALTERNATIVE_MARGIN)
                        assertFalse(l.ucis.first() == a.uci)
                    }
                }
            }
        }
        assertTrue("$lines lines, $alternatives alternatives", lines > 200 && alternatives > 0)
    }
}
