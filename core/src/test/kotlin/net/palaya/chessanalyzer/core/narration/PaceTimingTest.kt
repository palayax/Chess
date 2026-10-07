package net.palaya.chessanalyzer.core.narration

import net.palaya.chessanalyzer.core.analysis.GameReport
import net.palaya.chessanalyzer.core.analysis.MoveClassification
import net.palaya.chessanalyzer.core.chess.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ANALYSIS_SPEC 9.8 (V3): the video pace. The owner found the review "a bit too fast for key
 * moves/sequences"; the measurement (RUN_LOG V3) showed why on the Immortal Game: 21.Nxg7+, 22.Qf6+
 * and 23.Be7# each started sliding at the first frame of their beat, from a position the board had
 * never shown (Black's replies 21...Kd8 and 22...Nxf6 were jumped over, 0 ms on screen).
 *
 * These tests pin the contract on the recorded games: the same story at every pace, a pause on the
 * position before every key move, the skipped replies played at the line rate, a hold after, the
 * protected tiers untouched, and the length budget held by the story alone.
 */
class PaceTimingTest {

    private fun script(game: RealGameFixture.Game, pace: VideoPace, userColor: Color? = null): Pair<GameReport, VideoScript> {
        val report = game.report(userColor)
        return report to VideoScriptGenerator(userColor).generate(report, game.pgn, NarrationOptions(speechWpm = 169, pace = pace))
    }

    private val games = listOf(
        "scholars" to RealGameFixture.scholars,
        "chesscom" to RealGameFixture.chesscom,
        "immortal" to RealGameFixture.immortal,
        "game01" to RealGameFixture.game01,
        "byrne_fischer" to RealGameFixture.byrneFischer,
    )

    private fun playMove(s: VideoScript, san: String): ScriptSegment =
        s.segments.single { (it.board as? BoardDirective.PlayMove)?.san == san && it.classification != null }

    // ---------------------------------------------------------------------------------------------
    // The pace values
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `the paces are ordered and Normal is the owner's reference`() {
        val (r, n, b) = Triple(VideoPace.RELAXED, VideoPace.NORMAL, VideoPace.BRISK)
        assertTrue(r.keyLeadInMs > n.keyLeadInMs && n.keyLeadInMs > b.keyLeadInMs)
        assertTrue(r.keyHoldAfterMs > n.keyHoldAfterMs && n.keyHoldAfterMs > b.keyHoldAfterMs)
        assertTrue(r.lineMoveMinMs > n.lineMoveMinMs && n.lineMoveMinMs > b.lineMoveMinMs)
        assertTrue(r.lineFinalHoldMs > n.lineFinalHoldMs && n.lineFinalHoldMs > b.lineFinalHoldMs)
        // "A pause of about 1.0-1.5 s", "about 1.5 s per move at Normal".
        assertTrue(n.keyLeadInMs in 1_000L..1_500L)
        assertTrue(r.keyLeadInMs in 1_000L..1_500L)
        assertEquals(1_500L, n.lineMoveMinMs)
        // Even Brisk leaves time to see a move land: the slide plus a moment.
        assertTrue(b.lineMoveMinMs >= PaceTimes.MIN_LINE_STEP_MS)
        assertEquals(VideoPace.RELAXED, VideoPace.DEFAULT)
    }

    @Test
    fun `pace times scale down, never a line move below the slide plus a moment`() {
        val t = PaceTimes.of(VideoPace.RELAXED)
        assertEquals(t, t.scaled(1.0))
        assertEquals(t, t.scaled(7.0))
        val half = t.scaled(0.5)
        assertEquals(750L, half.keyLeadInMs)
        assertEquals(750L, half.keyHoldAfterMs)
        assertEquals(1_000L, half.lineMoveMinMs)
        assertEquals(PaceTimes.MIN_LINE_STEP_MS, t.scaled(0.1).lineMoveMinMs)
        assertEquals(0L, t.scaled(0.0).keyLeadInMs)
    }

    @Test
    fun `a persisted pace name round-trips and anything unknown is null`() {
        for (p in VideoPace.entries) assertEquals(p, VideoPace.fromPersistedOrNull(p.name))
        assertNull(VideoPace.fromPersistedOrNull(null))
        assertNull(VideoPace.fromPersistedOrNull("SLOW"))
    }

    // ---------------------------------------------------------------------------------------------
    // Same story at every pace
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `every pace tells the same story in the same words, only silent time differs`() {
        for ((name, g) in games) {
            val (_, relaxed) = script(g, VideoPace.RELAXED)
            val (_, normal) = script(g, VideoPace.NORMAL)
            val (_, brisk) = script(g, VideoPace.BRISK)
            for (other in listOf(normal, brisk)) {
                assertEquals(name, relaxed.segments.map { it.narration }, other.segments.map { it.narration })
                assertEquals(name, relaxed.segments.map { it.kind }, other.segments.map { it.kind })
                assertEquals(name, relaxed.segments.map { it.board }, other.segments.map { it.board })
                assertEquals(name, relaxed.segments.map { it.estimatedSpeechMs }, other.segments.map { it.estimatedSpeechMs })
                assertEquals(name, relaxed.chapters, other.chapters)
                assertEquals(name, relaxed.storyMs, other.storyMs)
                assertEquals(name, relaxed.recap, other.recap)
            }
            assertTrue("$name: ${relaxed.pacingMs} > ${normal.pacingMs} > ${brisk.pacingMs}", relaxed.pacingMs > normal.pacingMs && normal.pacingMs > brisk.pacingMs)
            assertTrue(name, relaxed.totalEstimatedMs > normal.totalEstimatedMs && normal.totalEstimatedMs > brisk.totalEstimatedMs)
            for (s in listOf(relaxed, normal, brisk)) {
                assertEquals(name, s.segments.sumOf { it.estimatedSpeechMs + it.leadInMs + it.holdAfterMs }, s.totalEstimatedMs)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Key moments
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `the Immortal Game's mating sequence is played move by move with a pause before each key move`() {
        for (pace in VideoPace.entries) {
            val (_, s) = script(RealGameFixture.immortal, pace)
            // 21.Nxg7+ (the turning point's beat was a still of the position before 20...Na6, so Na6 is played again first),
            // 22.Qf6+ after 21...Kd8, 23.Be7# after 22...Nxf6: each skipped reply is played, then the pause.
            for ((san, approach) in listOf("Nxg7+" to listOf("Na6"), "Qf6+" to listOf("Kd8"), "Be7#" to listOf("Nxf6"))) {
                val seg = playMove(s, san)
                val lead = assertNotNull("$pace $san has a lead-in", seg.leadIn).let { seg.leadIn!! }
                assertEquals("$pace $san approach", approach, lead.approachSan)
                assertEquals(pace.lineMoveMinMs, lead.stepMs)
                assertEquals(pace.keyLeadInMs, lead.pauseMs)
                val d = seg.board as BoardDirective.PlayMove
                assertEquals(listOf(d.uci.substring(0, 2), d.uci.substring(2, 4)), lead.highlightSquares)
                assertNotNull(lead.eval)
            }
            // Be7# is followed by the outro card: the mate is held.
            assertTrue(playMove(s, "Be7#").holdAfterMs >= pace.keyHoldAfterMs)
        }
    }

    @Test
    fun `before the change none of those three moves had a moment on the position before it`() {
        // The measurement this fixes, kept as a statement about the story itself: the segment before
        // each of them ends on a different position (the reply in between was never on the board).
        val (_, s) = script(RealGameFixture.immortal, VideoPace.NORMAL)
        for (san in listOf("Qf6+", "Be7#")) {
            val i = s.segments.indexOf(playMove(s, san))
            val prev = s.segments[i - 1].board as BoardDirective.PlayMove
            assertTrue("$san follows another move's beat", prev.fen != (s.segments[i].board as BoardDirective.PlayMove).fen)
        }
    }

    @Test
    fun `every key move gets at least a second on its position before it moves, at Normal and Relaxed`() {
        for (pace in listOf(VideoPace.NORMAL, VideoPace.RELAXED)) {
            for ((name, g) in games) {
                val (report, s) = script(g, pace)
                for ((i, seg) in s.segments.withIndex()) {
                    val d = seg.board as? BoardDirective.PlayMove ?: continue
                    if (seg.classification == null) continue
                    val a = report.annotations[seg.ply!! - 1]
                    val key = a.san.endsWith("#") || a.classification == MoveClassification.BRILLIANT ||
                        a.classification == MoveClassification.GREAT || seg.kind == SegmentKind.FOUND_TACTIC
                    if (!key) continue
                    val prev = s.segments[i - 1]
                    val stillBefore = (prev.board is BoardDirective.Annotate || prev.board is BoardDirective.Hold) &&
                        (prev.board as? BoardDirective.Annotate)?.fen?.startsWith(d.fen.substringBefore(' ')) != false &&
                        (prev.board as? BoardDirective.Hold)?.fen?.startsWith(d.fen.substringBefore(' ')) != false
                    val pause = seg.leadIn?.pauseMs ?: 0L
                    assertTrue("$name $pace ${a.moveNumber}.${a.san}: pause $pause ms", pause >= 1_000L || stillBefore)
                }
            }
        }
    }

    @Test
    fun `the board no longer jumps into a key move`() {
        for ((name, g) in games) {
            val (report, s) = script(g, VideoPace.NORMAL)
            for ((i, seg) in s.segments.withIndex()) {
                val lead = seg.leadIn ?: continue
                val d = seg.board as BoardDirective.PlayMove
                // The approach starts where the board was and ends exactly on the key move's position.
                val a = report.annotations[seg.ply!! - 1]
                if (lead.approachUci.isNotEmpty()) {
                    val first = report.annotations[a.ply - lead.approachUci.size - 1]
                    assertEquals(name, first.fenBefore, lead.fen)
                    assertEquals(name, (a.ply - lead.approachUci.size until a.ply).map { report.annotations[it - 1].uci }, lead.approachUci)
                    assertTrue(lead.approachUci.size <= SegmentLeadIn.MAX_APPROACH_PLIES)
                } else {
                    assertEquals(name, d.fen, lead.fen)
                }
                assertTrue(name, i > 0)
            }
        }
    }

    @Test
    fun `a played-out line moves at the line rate or slower and its final position is held longer`() {
        for (pace in VideoPace.entries) {
            val (_, s) = script(RealGameFixture.game01, pace)
            val lineMoves = s.segments.filter { it.kind == SegmentKind.MISSED_TACTIC && it.board is BoardDirective.PlayMove }
            assertTrue(lineMoves.isNotEmpty())
            for (m in lineMoves) {
                val onScreen = maxOf(m.estimatedSpeechMs, ScriptTiming.MIN_SEGMENT_MS) + m.holdAfterMs + ScriptTiming.INTER_SEGMENT_GAP_MS
                assertTrue("$pace line move ${(m.board as BoardDirective.PlayMove).san}: $onScreen ms", onScreen >= pace.lineMoveMinMs)
            }
            val finals = s.segments.withIndex().filter { (i, seg) ->
                seg.kind == SegmentKind.MISSED_TACTIC && seg.board is BoardDirective.Annotate &&
                    s.segments.getOrNull(i - 1)?.let { it.kind == SegmentKind.MISSED_TACTIC && it.board is BoardDirective.PlayMove } == true
            }
            assertTrue(finals.isNotEmpty())
            for ((_, f) in finals) assertTrue("$pace final hold ${f.holdAfterMs}", f.holdAfterMs >= pace.lineFinalHoldMs)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The contract: protected tiers and the budget
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `brilliant, great and mating moves keep their beats at every pace`() {
        for ((name, g) in games) {
            val base = script(g, VideoPace.NORMAL).second
            for (pace in VideoPace.entries) {
                val (report, s) = script(g, pace)
                for (a in report.annotations) {
                    val protectedMove = a.classification == MoveClassification.BRILLIANT ||
                        a.classification == MoveClassification.GREAT || a.san.endsWith("#")
                    if (!protectedMove) continue
                    val mine = s.segments.filter { it.ply == a.ply }
                    val ref = base.segments.filter { it.ply == a.ply }
                    assertEquals("$name $pace ${a.san}", ref.map { it.narration }, mine.map { it.narration })
                }
            }
        }
    }

    @Test
    fun `the budget holds the story at every pace and the pace time stays under its cap`() {
        for ((name, g) in games) {
            for (pace in VideoPace.entries) {
                val (report, s) = script(g, pace)
                val moves = (report.annotations.size + 1) / 2
                val budget = VideoScriptGenerator.budgetMs(moves)
                assertTrue("$name $pace story ${s.storyMs} vs budget $budget", s.storyMs <= budget)
                assertTrue("$name $pace pacing ${s.pacingMs}", s.pacingMs > 0)
                assertTrue("$name $pace pacing ${s.pacingMs} vs cap ${VideoScriptGenerator.pacingCapMs(moves)}", s.pacingMs <= VideoScriptGenerator.pacingCapMs(moves))
                assertEquals(s.totalEstimatedMs - s.pacingMs, s.storyMs)
            }
        }
        assertEquals((VideoScriptGenerator.budgetMs(23) * 0.15).toLong(), VideoScriptGenerator.pacingCapMs(23))
    }

    @Test
    fun `every-move is paced too, and its story is unchanged by the pace`() {
        val opts = { p: VideoPace -> NarrationOptions(speechWpm = 169, depth = NarrationDepth.EVERY_MOVE, pace = p) }
        val g = RealGameFixture.scholars
        val r = g.report(null)
        val a = VideoScriptGenerator(null).generate(r, g.pgn, opts(VideoPace.RELAXED))
        val b = VideoScriptGenerator(null).generate(r, g.pgn, opts(VideoPace.BRISK))
        assertEquals(a.segments.map { it.narration }, b.segments.map { it.narration })
        assertTrue(a.pacingMs > b.pacingMs)
        // The mate 4.Qxf7# is a key move: it is announced by a pause.
        assertTrue(playMove(a, "Qxf7#").leadInMs > 0)
    }
}
