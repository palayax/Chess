package net.palaya.chessanalyzer.core.narration

import net.palaya.chessanalyzer.core.analysis.BestLines
import net.palaya.chessanalyzer.core.analysis.GameReport
import net.palaya.chessanalyzer.core.analysis.MoveClassification
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.parseUci
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ANALYSIS_SPEC 9.8 (V2, narrated since V4): a key moment whose narration names the better move is followed
 * by the engine's best line, one spoken [SegmentKind.BEST_LINE] segment per move and then "Back to the game
 * now." These tests pin how the line sits in the script at Relaxed, Normal and Brisk: right after its key
 * moment, the same moves as the Board's line, each on screen at least the pace's line rate, all of it pace
 * time (outside the story budget, inside the cap), and the same words at every pace.
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

    /** The segments a key moment's line put after it: its moves, then the return. */
    private fun lineSegments(s: VideoScript, key: ScriptSegment): List<ScriptSegment> =
        s.segments.subList(key.index + 1, key.index + 2 + key.bestLine!!.uci.size)

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
                    assertEquals(line.uci.size, line.spoken.size)
                    assertTrue(line.captions.last(), line.captions.last().startsWith("Best line — "))
                    assertTrue(line.captions.last(), line.captions.last().contains(line.san.last()))
                    // V4: the beats after it play exactly those moves, each from the position the previous one left,
                    // and say them; then the board is the game's position again and the voice says so.
                    val after = lineSegments(s, seg)
                    var pos = Position.fromFen(line.fen)
                    for ((k, ls) in after.dropLast(1).withIndex()) {
                        assertEquals(SegmentKind.BEST_LINE, ls.kind)
                        val d = ls.board as BoardDirective.PlayMove
                        assertEquals(pos, Position.fromFen(d.fen))
                        assertEquals(line.uci[k], d.uci)
                        assertEquals(line.san[k], d.san)
                        assertEquals(line.spoken[k], ls.narration)
                        assertEquals(line.captions[k], ls.caption)
                        assertEquals(a.ply, ls.ply)
                        assertEquals(seg.eval, ls.eval)
                        pos = pos.makeMove(pos.parseUci(d.uci))
                    }
                    val back = after.last()
                    assertEquals(SegmentKind.KEY_MOMENT, back.kind)
                    assertEquals("Back to the game now.", back.narration)
                    assertEquals(seg.board, back.board)
                    assertEquals(a.classification, back.classification)
                }
            }
        }
    }

    @Test
    fun `each line move is on screen at least the pace's line rate, and the final position is held`() {
        for ((name, g) in games) {
            for (pace in VideoPace.entries) {
                val (_, s) = script(g, pace)
                val cap = VideoScriptGenerator.pacingCapMs((g.pgn.moves.size + 1) / 2)
                assertTrue("$name $pace ${s.pacingMs} vs $cap", s.pacingMs <= cap)
                for (seg in s.segments) {
                    val line = seg.bestLine ?: continue
                    // The lines only use the room the V3 pace time leaves under the cap, so nothing is scaled.
                    assertEquals("$name $pace", pace.lineMoveMinMs, line.stepMs)
                    assertEquals("$name $pace", pace.lineFinalHoldMs, line.finalHoldMs)
                    val moves = lineSegments(s, seg).dropLast(1)
                    for ((k, m) in moves.withIndex()) {
                        // Laid out the way the app's timeline lays a segment out: speech (with its floor), hold, gap.
                        val onScreen = maxOf(m.estimatedSpeechMs, ScriptTiming.MIN_SEGMENT_MS) + m.holdAfterMs + ScriptTiming.INTER_SEGMENT_GAP_MS
                        val last = k == moves.lastIndex
                        assertTrue("$name $pace ${m.caption}: $onScreen ms", onScreen >= pace.lineMoveMinMs + if (last) pace.lineFinalHoldMs else 0L)
                        assertTrue(onScreen > ScriptTiming.MOVE_ANIMATION_MS)
                        assertEquals(0L, m.leadInMs)
                    }
                    // The return holds nothing extra: the next beat continues the game.
                    assertEquals(0L, lineSegments(s, seg).last().holdAfterMs)
                }
            }
        }
    }

    @Test
    fun `the line is pace time, so the story and the budget are untouched, and every pace says the same words`() {
        for ((name, g) in games) {
            val scripts = VideoPace.entries.map { script(g, it).second }
            for (s in scripts) {
                val lineMs = s.segments.withIndex().sumOf { (i, x) ->
                    val afterLine = i > 0 && s.segments[i - 1].kind == SegmentKind.BEST_LINE && x.kind != SegmentKind.BEST_LINE
                    if (x.kind == SegmentKind.BEST_LINE || afterLine) x.estimatedSpeechMs + x.holdAfterMs else 0L
                }
                assertTrue("$name: lines $lineMs within pace time ${s.pacingMs}", lineMs <= s.pacingMs)
                assertEquals(s.segments.sumOf { it.estimatedSpeechMs + it.leadInMs + it.holdAfterMs }, s.totalEstimatedMs)
                assertTrue(s.pacingMs <= VideoScriptGenerator.pacingCapMs((g.pgn.moves.size + 1) / 2))
                assertTrue("$name story ${s.storyMs}", s.storyMs <= VideoScriptGenerator.budgetMs((g.pgn.moves.size + 1) / 2))
            }
            // The same segments at every pace, with a line on the same ones and the same words: only time differs.
            for (other in scripts.drop(1)) {
                assertEquals(name, scripts[0].segments.map { it.narration }, other.segments.map { it.narration })
                assertEquals(name, scripts[0].segments.map { it.bestLine?.uci }, other.segments.map { it.bestLine?.uci })
                assertEquals(name, scripts[0].storyMs, other.storyMs)
            }
        }
    }

    /** The measurement behind RUN_LOG V4: per game and pace, the moments with a line and what they add. */
    @Test
    fun `measurement - writes core build pace v4_best_lines txt`() {
        val out = StringBuilder()
        for ((name, g) in games) {
            for (pace in VideoPace.entries) {
                val (_, s) = script(g, pace)
                val keys = s.segments.filter { it.bestLine != null }
                val moves = (g.pgn.moves.size + 1) / 2
                val lineMs = keys.sumOf { k -> lineSegments(s, k).sumOf { it.estimatedSpeechMs + it.holdAfterMs } }
                out.appendLine(
                    "$name $pace: ${keys.size} moments with a line, ${keys.sumOf { it.bestLine!!.uci.size }} plies, line time $lineMs ms, " +
                        "pace time ${s.pacingMs} ms (cap ${VideoScriptGenerator.pacingCapMs(moves)}), story ${s.storyMs} ms, " +
                        "total ${s.totalEstimatedMs} ms, ${s.segments.size} segments"
                )
                for (k in keys) {
                    val segs = lineSegments(s, k)
                    out.appendLine(
                        "  ply ${k.ply} [${k.caption}]: " + segs.joinToString(" | ") { x ->
                            "${x.narration} (${x.estimatedSpeechMs}+${x.holdAfterMs})"
                        }
                    )
                }
            }
        }
        java.io.File("build/pace/v4_best_lines.txt").apply { parentFile.mkdirs() }.writeText(out.toString())
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

    @Test
    fun `chapters still point at the segments they named before the lines were inserted`() {
        for ((name, g) in games) {
            val (_, s) = script(g, VideoPace.RELAXED)
            for (c in s.chapters) {
                val first = s.segments[c.startSegmentIndex]
                assertTrue("$name ${c.title} starts on a ${first.kind}", first.kind != SegmentKind.BEST_LINE)
            }
            assertEquals(s.chapters.map { it.startSegmentIndex }.sorted(), s.chapters.map { it.startSegmentIndex })
            assertEquals((0 until s.segments.size).toList(), s.segments.map { it.index })
        }
    }
}
