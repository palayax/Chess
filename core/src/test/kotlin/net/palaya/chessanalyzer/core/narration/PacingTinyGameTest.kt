package net.palaya.chessanalyzer.core.narration

import net.palaya.chessanalyzer.core.analysis.GameReport
import net.palaya.chessanalyzer.core.analysis.MoveClassification
import net.palaya.chessanalyzer.core.chess.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ANALYSIS_SPEC §9.7, R1b: the length budget for a tiny game.
 *
 * The Round 13 budget was `min(720 s, 120 s + 14 s x moves)`. Its 120 s base was never meant for a
 * four-move game, which came out at 3 min 43 s on the emulator (a scholar's mate: 21 beats, a puzzle
 * and an eight-ply walk of 3...g6, then three lessons). The budget is now
 * `min(720 s, 120 s + 14 s x moves, 23 s x moves - 32 s)`: the same for every normal game (17 moves
 * and up), and 60 s for four moves. A budget is a ceiling - nothing is padded to reach it.
 *
 * Every game here is a real recorded Stockfish analysis ([RealGameFixture]); the 8- and 40-move games
 * are the first 16 and 80 plies of Byrne-Fischer 1956, the 4-move game is the scholar's mate.
 */
class PacingTinyGameTest {

    private val games: List<Triple<Int, String, RealGameFixture.Game>> by lazy {
        listOf(
            Triple(4, "scholar's mate", RealGameFixture.scholars),
            Triple(8, "Byrne-Fischer, first 8 moves", RealGameFixture.byrneFischer.firstPlies(16)),
            Triple(17, "Opera Game", RealGameFixture.chesscom),
            Triple(23, "Immortal Game", RealGameFixture.immortal),
            Triple(40, "Byrne-Fischer, first 40 moves", RealGameFixture.byrneFischer.firstPlies(80))
        )
    }

    private fun script(g: RealGameFixture.Game, color: Color? = null, options: NarrationOptions = NarrationOptions()): Pair<GameReport, VideoScript> {
        val report = g.report(color)
        return report to VideoScriptGenerator(color).generate(report, g.pgn, options)
    }

    // -----------------------------------------------------------------------
    // The formula
    // -----------------------------------------------------------------------

    @Test
    fun `the budget is 60 seconds for four moves and the old one from seventeen moves up`() {
        assertEquals(60_000L, VideoScriptGenerator.budgetMs(4))
        assertEquals(152_000L, VideoScriptGenerator.budgetMs(8))
        assertEquals(358_000L, VideoScriptGenerator.budgetMs(17))
        assertEquals(442_000L, VideoScriptGenerator.budgetMs(23))
        assertEquals(680_000L, VideoScriptGenerator.budgetMs(40))
        assertEquals(720_000L, VideoScriptGenerator.budgetMs(43))
        assertEquals(720_000L, VideoScriptGenerator.budgetMs(120))
        // Nothing changed for a normal game: from 17 moves the new budget is exactly the Round 13 one.
        for (moves in 17..60) {
            assertEquals("$moves moves", minOf(720_000L, 120_000L + 14_000L * moves), VideoScriptGenerator.budgetMs(moves))
        }
        // ...and it only ever got smaller for a shorter one, growing with the game.
        var previous = 0L
        for (moves in 1..17) {
            val b = VideoScriptGenerator.budgetMs(moves)
            assertTrue("$moves moves: $b", b <= 120_000L + 14_000L * moves && b >= previous && b >= 20_000L)
            previous = b
        }
    }

    // -----------------------------------------------------------------------
    // The five lengths
    // -----------------------------------------------------------------------

    @Test
    fun `a four-move game lands around a minute, not three and three quarter`() {
        val (report, s) = script(RealGameFixture.scholars)
        assertEquals(7, report.annotations.size) // 1.e4 e5 2.Bc4 Nc6 3.Qh5 Nf6 4.Qxf7#
        assertTrue("ran ${s.totalEstimatedMs / 1000.0}s, ${s.segments.size} beats", s.totalEstimatedMs in 45_000L..65_000L)
        // The old result, for the record: 21 beats, 235 s.
        assertTrue("${s.segments.size} beats", s.segments.size <= 8)
    }

    @Test
    fun `each length stays inside its budget, and the normal games stay where they were`() {
        val minutes = HashMap<Int, Double>()
        for ((moves, name, g) in games) {
            val (report, s) = script(g)
            val budget = VideoScriptGenerator.budgetMs((report.annotations.size + 1) / 2)
            assertEquals(name, moves, (report.annotations.size + 1) / 2)
            assertTrue("$name ($moves moves) ran ${s.totalEstimatedMs / 1000.0}s against ${budget / 1000.0}s", s.totalEstimatedMs <= budget)
            minutes[moves] = s.totalEstimatedMs / 60_000.0
        }
        // 17 moves about five minutes, the Immortal Game seven to eight, forty moves at most about twelve.
        assertTrue("17 moves: ${minutes[17]} min", minutes.getValue(17) in 4.0..6.0)
        assertTrue("23 moves: ${minutes[23]} min", minutes.getValue(23) in 7.0..8.0)
        assertTrue("40 moves: ${minutes[40]} min", minutes.getValue(40) <= 12.0)
        assertTrue("8 moves: ${minutes[8]} min", minutes.getValue(8) < 152 / 60.0)
        assertTrue("4 moves: ${minutes[4]} min", minutes.getValue(4) in 0.7..1.1)
    }

    @Test
    fun `a quiet short game comes out under its budget, nothing is added to fill it`() {
        val (report, s) = script(RealGameFixture.byrneFischer.firstPlies(16))
        val budget = VideoScriptGenerator.budgetMs((report.annotations.size + 1) / 2)
        assertTrue("${s.totalEstimatedMs} vs $budget", s.totalEstimatedMs < budget - 20_000L)
        // The structure is whole: nothing was trimmed because nothing had to be.
        assertTrue(s.segments.any { it.kind == SegmentKind.OPENING_SUMMARY })
        assertTrue(s.segments.count { it.kind == SegmentKind.OUTRO_LESSONS } >= 2)
    }

    // -----------------------------------------------------------------------
    // What a tiny game keeps, and what gives way first
    // -----------------------------------------------------------------------

    @Test
    fun `the checkmate and the turning point are still told in the one-minute game`() {
        val (report, s) = script(RealGameFixture.scholars)
        val mate = report.annotations.last()
        assertTrue(mate.san.endsWith("#"))
        assertTrue("the mate is told", s.segments.any { it.ply == mate.ply })
        val turning = report.annotations
            .filter { it.loss > 0.5 && it.classification != MoveClassification.BOOK && it.classification != MoveClassification.FORCED }
            .maxByOrNull { it.loss }!!
        assertEquals("3...Nf6 is the blunder", "Nf6", turning.san)
        assertTrue("the turning point is told", s.segments.any { it.ply == turning.ply })
        // The better move is named, in a sentence rather than an eight-ply walk.
        assertTrue(s.segments.filter { it.ply == turning.ply }.joinToString(" ") { it.narration }.contains("was the move"))
        assertTrue("no eight-ply walk in a minute", s.segments.none { it.kind == SegmentKind.MISSED_TACTIC })
        assertTrue("no puzzle pause in a minute", s.segments.none { it.kind == SegmentKind.PUZZLE_PROMPT })
    }

    @Test
    fun `the lesson lead counts its lessons truthfully`() {
        for ((_, name, g) in games) {
            val (_, s) = script(g)
            val lessons = s.segments.filter { it.kind == SegmentKind.OUTRO_LESSONS }
            if (lessons.isEmpty()) continue
            val lead = lessons.first().narration
            val counted = mapOf("Two things" to 2, "Three things" to 3, "Four things" to 4, "one thing to actually" to 1)
            for ((words, n) in counted) {
                if (words in lead) assertEquals("$name: [$lead]", n, lessons.size)
            }
        }
    }

    @Test
    fun `the structure gives way in order, and only after the story has`() {
        // A one-minute game keeps one lesson and drops the ratings and the opening summary...
        val (_, tiny) = script(RealGameFixture.scholars)
        assertTrue(tiny.segments.count { it.kind == SegmentKind.OUTRO_LESSONS } <= 1)
        assertTrue(tiny.segments.none { it.kind == SegmentKind.OPENING_SUMMARY })
        val outro = tiny.segments.single { it.kind == SegmentKind.OUTRO_SUMMARY }.narration
        assertTrue(outro, "percent accuracy" in outro)
        // no estimate is given, so no caveat about estimates is left dangling either
        assertTrue(outro, "roughly" !in outro && "rating" !in outro)
        // ...while the Opera Game, which fits, loses none of it.
        val (_, normal) = script(RealGameFixture.chesscom)
        assertTrue(normal.segments.any { it.kind == SegmentKind.OPENING_SUMMARY })
        assertTrue(normal.segments.count { it.kind == SegmentKind.OUTRO_LESSONS } >= 2)
        assertTrue(normal.segments.single { it.kind == SegmentKind.OUTRO_SUMMARY }.narration.contains("roughly"))
    }

    @Test
    fun `the scripts are deterministic and EVERY_MOVE is still exempt from the budget`() {
        val (_, a) = script(RealGameFixture.scholars)
        val (_, b) = script(RealGameFixture.scholars)
        assertEquals(a, b)
        val (_, all) = script(RealGameFixture.scholars, options = NarrationOptions(depth = NarrationDepth.EVERY_MOVE))
        assertTrue("every move is the whole game: ${all.totalEstimatedMs}", all.totalEstimatedMs > a.totalEstimatedMs)
    }
}
