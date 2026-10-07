package net.palaya.chessanalyzer.core.narration

import net.palaya.chessanalyzer.core.analysis.BestLines
import net.palaya.chessanalyzer.core.analysis.GameReport
import net.palaya.chessanalyzer.core.analysis.MoveClassification
import net.palaya.chessanalyzer.core.chess.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ANALYSIS_SPEC 9.8 (V2): a key moment whose narration names the better move plays the engine's best line
 * after its speech, instead of leaving the better move as an arrow. These tests pin how the line fits the
 * video timeline at Relaxed, Normal and Brisk: inside the segment's hold (pace time, outside the story
 * budget), starting when the speech ends, never longer than the Board's line for the same move.
 */
class BestLineVideoTest {

    private val games = listOf(
        "scholars" to RealGameFixture.scholars,
        "chesscom" to RealGameFixture.chesscom,
        "immortal" to RealGameFixture.immortal,
        "game01" to RealGameFixture.game01,
        "byrne_fischer" to RealGameFixture.byrneFischer,
    )

    private fun script(game: RealGameFixture.Game, pace: VideoPace, side: Color? = null): Pair<GameReport, VideoScript> {
        val report = game.report(side)
        return report to VideoScriptGenerator(side).generate(report, game.pgn, NarrationOptions(speechWpm = 169, pace = pace))
    }

    @Test
    fun `the best line is played on the key moments that name the better move, at every pace`() {
        for ((name, g) in games) {
            for (pace in VideoPace.entries) {
                val (report, s) = script(g, pace)
                for (seg in s.segments) {
                    val line = seg.bestLine ?: continue
                    val a = report.annotations[seg.ply!! - 1]
                    // Only on a still picture of the position before the move, on an error or key-moment beat,
                    // of a MISTAKE, MISS or BLUNDER (an inaccuracy is never a walk of the line, spec 9.7).
                    val board = seg.board as BoardDirective.Annotate
                    assertEquals(name, a.fenBefore, board.fen)
                    assertTrue(name, seg.kind == SegmentKind.BLUNDER || seg.kind == SegmentKind.KEY_MOMENT)
                    assertTrue("$name ${a.san}", a.classification in setOf(MoveClassification.MISTAKE, MoveClassification.MISS, MoveClassification.BLUNDER))
                    assertTrue("$name ${a.san}: the better move differs", a.bestMoveUci != a.uci)
                    assertTrue("$name ${a.san}: the narration names the better move", seg.narration.contains(" was the move") ||
                        seg.narration.contains("Instead, ") || seg.narration.contains("The move was ") || seg.narration.contains("mate"))
                    // The same moves the Board shows for this move, cut to at most the video's four.
                    val boardLine = BestLines.bestFor(a)!!
                    assertTrue(line.uci.size in 1..BestLines.VIDEO_MAX_PLIES)
                    assertEquals(name, boardLine.startFen, line.fen)
                    assertEquals(name, boardLine.ucis.take(line.uci.size), line.uci)
                    assertEquals(name, boardLine.sans.take(line.uci.size), line.san)
                    assertEquals(line.uci.size, line.captions.size)
                    assertTrue(line.captions.last(), line.captions.last().startsWith("Best line — "))
                    assertTrue(line.captions.last(), line.captions.last().contains(line.san.last()))
                }
            }
        }
    }

    @Test
    fun `the line fits the segment's hold at Relaxed, Normal and Brisk, at the pace's line rate`() {
        for ((name, g) in games) {
            for (pace in VideoPace.entries) {
                val (_, s) = script(g, pace)
                val cap = VideoScriptGenerator.pacingCapMs((g.pgn.moves.size + 1) / 2)
                for (seg in s.segments) {
                    val line = seg.bestLine ?: continue
                    // The lines only use the room the V3 pace time leaves under the cap, so nothing is scaled:
                    // every line moves at the pace's own rate and holds its own final time.
                    assertEquals("$name $pace", pace.lineMoveMinMs, line.stepMs)
                    assertEquals("$name $pace", pace.lineFinalHoldMs, line.finalHoldMs)
                    assertTrue("$name $pace ${s.pacingMs} vs $cap", s.pacingMs <= cap)
                    assertTrue(line.stepMs >= PaceTimes.MIN_LINE_STEP_MS)
                    assertEquals(line.uci.size * line.stepMs + line.finalHoldMs, line.durationMs)
                    // Laid out the way the app's timeline lays a segment out: lead-in, speech (with its floor),
                    // then the hold, which begins with the line; then the gap. The line ends inside the segment.
                    val speech = maxOf(seg.estimatedSpeechMs, ScriptTiming.MIN_SEGMENT_MS)
                    val segmentMs = seg.leadInMs + speech + seg.holdAfterMs + ScriptTiming.INTER_SEGMENT_GAP_MS
                    val lineStart = seg.leadInMs + speech
                    assertTrue("$name $pace", seg.holdAfterMs >= line.durationMs)
                    assertTrue("$name $pace", lineStart + line.durationMs <= segmentMs - ScriptTiming.INTER_SEGMENT_GAP_MS)
                    // Every move is on screen at least the line rate: the slide plus a rest.
                    assertTrue(line.stepMs > ScriptTiming.MOVE_ANIMATION_MS)
                }
            }
        }
    }

    @Test
    fun `the line is pace time, so the story, its words and the budget are untouched`() {
        for ((name, g) in games) {
            val scripts = VideoPace.entries.map { script(g, it).second }
            for (s in scripts) {
                val lines = s.segments.sumOf { it.bestLine?.durationMs ?: 0L }
                assertTrue("$name: lines $lines within pace time ${s.pacingMs}", lines <= s.pacingMs)
                assertEquals(s.segments.sumOf { it.estimatedSpeechMs + it.leadInMs + it.holdAfterMs }, s.totalEstimatedMs)
                assertTrue(s.pacingMs <= VideoScriptGenerator.pacingCapMs((g.pgn.moves.size + 1) / 2))
            }
            // The same segments at every pace, with a line on the same ones: only its timing differs.
            val withLine = scripts.map { s -> s.segments.filter { it.bestLine != null }.map { it.index } }
            assertEquals(name, withLine[0], withLine[1])
            assertEquals(name, withLine[0], withLine[2])
            assertEquals(name, scripts[0].storyMs, scripts[2].storyMs)
            assertEquals(name, scripts[0].segments.map { it.bestLine?.uci }, scripts[1].segments.map { it.bestLine?.uci })
            assertEquals(name, scripts[0].segments.map { it.bestLine?.uci }, scripts[2].segments.map { it.bestLine?.uci })
        }
    }

    /** The measurement behind RUN_LOG V2: per game and pace, the moments with a line and their time before and after. */
    @Test
    fun `measurement - writes core build pace v2_best_lines txt`() {
        val out = StringBuilder()
        for ((name, g) in games) {
            for (pace in VideoPace.entries) {
                val (_, s) = script(g, pace)
                val withLine = s.segments.filter { it.bestLine != null }
                val lineMs = withLine.sumOf { it.bestLine!!.durationMs }
                val moves = (g.pgn.moves.size + 1) / 2
                out.appendLine(
                    "$name $pace: ${withLine.size} moments with a line, line time ${lineMs} ms, pace time ${s.pacingMs} ms " +
                        "(cap ${VideoScriptGenerator.pacingCapMs(moves)}), story ${s.storyMs} ms, total ${s.totalEstimatedMs} ms"
                )
                for (seg in withLine) {
                    val line = seg.bestLine!!
                    val speech = maxOf(seg.estimatedSpeechMs, ScriptTiming.MIN_SEGMENT_MS)
                    val after = seg.leadInMs + speech + seg.holdAfterMs + ScriptTiming.INTER_SEGMENT_GAP_MS
                    val before = after - line.durationMs
                    out.appendLine(
                        "  ply ${seg.ply} [${seg.caption}] ${seg.kind}: on screen ${before} ms -> ${after} ms; line " +
                            "${line.san.joinToString(" ")} (${line.uci.size} x ${line.stepMs} + ${line.finalHoldMs}) from ${seg.leadInMs + speech} ms"
                    )
                }
            }
        }
        java.io.File("build/pace/v2_best_lines.txt").apply { parentFile.mkdirs() }.writeText(out.toString())
        assertTrue(out.isNotEmpty())
    }

    @Test
    fun `game01 and the Opera Game show lines, and a viewer's side changes no move`() {
        for ((name, g) in listOf("game01" to RealGameFixture.game01, "chesscom" to RealGameFixture.chesscom)) {
            val (_, s) = script(g, VideoPace.NORMAL)
            assertTrue("$name has at least one played-out best line", s.segments.any { it.bestLine != null })
            val (_, white) = script(g, VideoPace.NORMAL, Color.WHITE)
            val (_, black) = script(g, VideoPace.NORMAL, Color.BLACK)
            for (other in listOf(white, black)) {
                val mine = s.segments.mapNotNull { seg -> seg.bestLine?.let { seg.ply to it.uci } }.toMap()
                val theirs = other.segments.mapNotNull { seg -> seg.bestLine?.let { seg.ply to it.uci } }.toMap()
                for ((ply, uci) in theirs) mine[ply]?.let { assertEquals("$name ply $ply", it, uci) }
            }
        }
    }
}
