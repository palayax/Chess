package net.palaya.chessanalyzer.ui.model

import net.palaya.chessanalyzer.core.analysis.BestLines
import net.palaya.chessanalyzer.core.analysis.CandidateLine
import net.palaya.chessanalyzer.core.analysis.MoveAnnotation
import net.palaya.chessanalyzer.core.analysis.MoveClassification as CoreClass
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.narration.BoardDirective
import net.palaya.chessanalyzer.core.narration.ScriptSegment
import net.palaya.chessanalyzer.core.narration.SegmentBestLine
import net.palaya.chessanalyzer.core.narration.SegmentKind
import net.palaya.chessanalyzer.core.narration.VideoPace
import net.palaya.chessanalyzer.core.narration.VideoScript
import net.palaya.chessanalyzer.ui.theme.MoveClassification
import net.palaya.chessanalyzer.video.NarrationSynthesizer
import net.palaya.chessanalyzer.video.SegmentFrameBuilder
import net.palaya.chessanalyzer.video.TimelineBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V2: the line player's pure logic (shared by the Walkthrough and the Board's best-line mode), which
 * moves offer "Show the best line", and where a best line sits on the video timeline at every pace.
 */
class LinePlaybackLogicTest {

    private val start = Position.STANDARD_START_FEN
    private val pv = listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1b5", "a7a6", "b5a4", "g8f6")

    // ---------------------------------------------------------------------------------------------
    // Stepping
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `positions replay the line with the core move generator and stop at an illegal move`() {
        val positions = linePositions(start, pv)
        assertEquals(pv.size + 1, positions.size)
        assertEquals(start, positions.first().toFen())
        for (k in pv.indices) {
            assertTrue(positions[k].legalMoves().any { it.toUci() == pv[k] })
        }
        assertEquals(3, linePositions(start, listOf("e2e4", "e7e5", "e4e5", "g1f3")).size)
        assertEquals("a bad FEN is the start of a game", Position.startPosition().toFen(), linePositions("junk", emptyList()).single().toFen())
    }

    @Test
    fun `back and next stay inside the line, and Play stops on the last move and restarts from the top`() {
        assertEquals(0, linePreviousStep(0))
        assertEquals(2, linePreviousStep(3))
        assertEquals(1, lineNextStep(0, 4))
        assertEquals(4, lineNextStep(4, 4))
        assertEquals(0, lineNextStep(0, 0))
        assertEquals(3, linePlayTick(2, 4))
        assertEquals(4, linePlayTick(3, 4))
        assertNull(linePlayTick(4, 4))
        assertEquals(2, linePlayStart(2, 4))
        assertEquals(0, linePlayStart(4, 4))
    }

    @Test
    fun `a step names its move with the number a player counts and says who is to move`() {
        val fen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 3 18"
        val sans = listOf("Nf6", "Nc3", "Bc5")
        assertNull(lineStepMove(fen, 0, sans))
        assertEquals(WalkthroughMove(18, false, "Nf6"), lineStepMove(fen, 1, sans))
        assertEquals(WalkthroughMove(19, true, "Nc3"), lineStepMove(fen, 2, sans))
        assertEquals(WalkthroughMove(19, false, "Bc5"), lineStepMove(fen, 3, sans))
        val positions = linePositions(fen, listOf("g8f6", "b1c3"))
        assertEquals(Color.BLACK, lineSideToMove(positions, 0))
        assertEquals(Color.WHITE, lineSideToMove(positions, 1))
        assertNull(lineSideToMove(positions, 5))
    }

    // ---------------------------------------------------------------------------------------------
    // Which moves offer "Show the best line"
    // ---------------------------------------------------------------------------------------------

    private fun annotation(lines: List<CandidateLine>) = MoveAnnotation(
        ply = 3, moveNumber = 2, color = Color.WHITE, san = "a3", uci = "a2a3", fenBefore = start, fenAfter = start,
        classification = CoreClass.MISTAKE, loss = 12.0, winPercentBefore = 55.0, winPercentAfter = 43.0,
        evalBeforeCp = 40, evalAfterCp = -60, bestMoveUci = "e2e4", candidateLines = lines,
    )

    private fun move(cls: MoveClassification?, ply: Int = 3, lines: List<CandidateLine> = listOf(CandidateLine(1, "e2e4", "e4", 40, null, pv, 14))) =
        MoveRecord(ply = ply, san = "a3", classification = cls, core = annotation(lines))

    @Test
    fun `every mistake class and every key moment offers the line, a quiet move does not, nor a move with no line`() {
        for (cls in listOf(MoveClassification.INACCURACY, MoveClassification.MISTAKE, MoveClassification.MISS, MoveClassification.BLUNDER)) {
            val m = move(cls)
            assertTrue("$cls", bestLineOffered(m, emptyList(), bestLinesFor(m)))
        }
        for (cls in listOf(MoveClassification.BEST, MoveClassification.GOOD, MoveClassification.BRILLIANT, MoveClassification.BOOK)) {
            val m = move(cls)
            assertFalse("$cls", bestLineOffered(m, emptyList(), bestLinesFor(m)))
            assertTrue("$cls as a key moment", bestLineOffered(m, listOf(3), bestLinesFor(m)))
        }
        val none = move(MoveClassification.BLUNDER, lines = emptyList())
        assertFalse(bestLineOffered(none, listOf(3), bestLinesFor(none)))
        assertTrue(bestLinesFor(null).isEmpty())
        assertTrue(bestLinesFor(MoveRecord(ply = 1, san = "e4")).isEmpty())
    }

    @Test
    fun `the line is written as a scoresheet writes it`() {
        val line = BestLines.build(start, CandidateLine(1, "e2e4", "e4", 40, null, pv, 14))!!
        assertEquals("1. e4 e5 2. Nf3 Nc6 3. Bb5 a6 4. Ba4", numberedLine(line))
        val black = BestLines.build(
            "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 3 18",
            CandidateLine(1, "g8f6", null, 10, null, listOf("g8f6", "b1c3", "f8c5"), 12),
        )!!
        assertEquals("18… Nf6 19. Nc3 Bc5", numberedLine(black))
    }

    // ---------------------------------------------------------------------------------------------
    // The video timeline (V2 + V4): the line's moves are segments of their own, spoken, at every pace
    // ---------------------------------------------------------------------------------------------

    /** A key moment, its spoken line ([plies] moves) and the return, as the generator lays them out (V4). */
    private fun scriptWithLine(pace: VideoPace, plies: Int): VideoScript {
        val sans = listOf("e4", "e5", "Nf3", "Nc6").take(plies)
        val spoken = listOf("Pawn to e four.", "Pawn to e five.", "Knight to f three.", "Knight to c six.").take(plies)
        val line = SegmentBestLine(
            fen = start, uci = pv.take(plies), san = sans,
            captions = (1..plies).map { "Best line — $it" }, stepMs = pace.lineMoveMinMs, finalHoldMs = pace.lineFinalHoldMs,
            spoken = spoken, backToGame = "Back to the game now.",
        )
        val annotate = BoardDirective.Annotate(start)
        val intro = ScriptSegment(0, SegmentKind.INTRO, null, "Hello.", "", BoardDirective.Hold(start), 1_000L)
        val key = ScriptSegment(
            index = 1, kind = SegmentKind.BLUNDER, ply = 1, narration = "White plays a3. Pawn to e four was the move.",
            caption = "1. a3 ?", board = annotate, estimatedSpeechMs = 4_000L, classification = CoreClass.MISTAKE, bestLine = line,
        )
        var pos = Position.fromFen(start)
        val moves = (0 until plies).map { k ->
            val fen = pos.toFen()
            pos = pos.makeMove(pos.legalMoves().first { it.toUci() == pv[k] })
            ScriptSegment(
                index = 2 + k, kind = SegmentKind.BEST_LINE, ply = 1, narration = spoken[k], caption = line.captions[k],
                board = BoardDirective.PlayMove(fen, pv[k], sans[k]), estimatedSpeechMs = 1_660L,
                holdAfterMs = if (k == plies - 1) pace.lineFinalHoldMs else 0L,
            )
        }
        val back = ScriptSegment(
            index = 2 + plies, kind = SegmentKind.KEY_MOMENT, ply = 1, narration = "Back to the game now.",
            caption = "Back to the game — 1. a3", board = annotate, estimatedSpeechMs = 2_015L, classification = CoreClass.MISTAKE,
        )
        val segs = listOf(intro, key) + moves + back
        return VideoScript("t", "s", segs, emptyList(), segs.sumOf { it.estimatedSpeechMs + it.holdAfterMs }, null)
    }

    @Test
    fun `the timeline gives each spoken line move its own real speech, and the frames play the line then return`() {
        for (pace in VideoPace.entries) {
            for (plies in 1..4) {
                val script = scriptWithLine(pace, plies)
                // The real voice took 1.4 s per move, not the 1.66 s estimate: each move's segment follows its own audio.
                val real = script.segments.filter { it.kind == SegmentKind.BEST_LINE }.map { NarrationSynthesizer.Result.Synthesized(it.index, File("x.wav"), 1_400) }
                val tl = TimelineBuilder.build(script, real)
                for (k in 0 until plies) {
                    val timed = tl.segments[2 + k]
                    assertEquals(1_400L, timed.speechDurationMs)
                    // At its first frame the move slides, in the excursion's colours, with no verdict.
                    val first = (SegmentFrameBuilder.build(script, timed.segment, 0L, net.palaya.chessanalyzer.ui.board.BoardOrientation.WHITE_DOWN)
                        as net.palaya.chessanalyzer.video.RenderInstruction.Board).spec
                    assertTrue(first.animating != null)
                    assertTrue(first.excursionActive)
                    assertEquals("Engine's best line", first.excursionLabel)
                    assertNull(first.classification)
                    assertNull(first.segmentKind)
                    assertEquals("Best line — ${k + 1}", first.caption)
                    // The material of the shown position rides along: nothing has been captured in this line.
                    assertEquals(0, first.material!!.advantage)
                }
                // Back to the game: the key moment's own picture again, no excursion, the verdict back.
                val backTimed = tl.segments.last()
                val back = (SegmentFrameBuilder.build(script, backTimed.segment, 0L, net.palaya.chessanalyzer.ui.board.BoardOrientation.WHITE_DOWN)
                    as net.palaya.chessanalyzer.video.RenderInstruction.Board).spec
                assertFalse(back.excursionActive)
                assertEquals(CoreClass.MISTAKE, back.classification)
                // The line's moves are not game moves: "recent moves" never lists them.
                assertTrue(back.recentMoves.isEmpty())
                // Contiguous: every segment starts where the previous one ended.
                for (i in 1 until tl.segments.size) assertEquals(tl.segments[i - 1].endMs, tl.segments[i].startMs)
            }
        }
    }
}
