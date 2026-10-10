package net.palaya.chessanalyzer.video

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.palaya.chessanalyzer.core.analysis.MoveClassification
import net.palaya.chessanalyzer.core.narration.BoardDirective
import net.palaya.chessanalyzer.core.narration.ScriptSegment
import net.palaya.chessanalyzer.core.narration.SegmentEval
import net.palaya.chessanalyzer.core.narration.SegmentKind
import net.palaya.chessanalyzer.core.narration.VideoScript
import net.palaya.chessanalyzer.ui.board.BoardOrientation
import net.palaya.chessanalyzer.ui.model.BoardState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The seam between `ScriptSegment.classification` and the word the video's side panel puts on a
 * move (ANALYSIS_SPEC §4).
 *
 * Worth an on-device test rather than a host one for the same reason as
 * [net.palaya.chessanalyzer.data.mapper.MoveSwingMappingTest]: `:core` publishing the right
 * verdict is already covered in `:core`, but a [SegmentFrameBuilder] that dropped the field, or a
 * [BoardFrameRenderer] that went back to labelling the move by its [SegmentKind], would leave
 * every `:core` test green while the panel and the review move list called the same ply two
 * different things again.
 */
@RunWith(AndroidJUnit4::class)
class PanelChipLabelTest {

    private val fenBefore = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2"

    private fun segment(
        kind: SegmentKind,
        board: BoardDirective,
        classification: MoveClassification?,
    ) = ScriptSegment(
        index = 0,
        kind = kind,
        ply = 8,
        narration = "Careful. Black plays pawn to g6.",
        caption = "4... g6 ?",
        board = board,
        estimatedSpeechMs = 2_000L,
        eval = SegmentEval(winPercentWhite = 62.0, evalCp = 120),
        moveNumber = 4,
        evalSwingCp = -250,
        classification = classification,
    )

    private fun script(segment: ScriptSegment) = VideoScript(
        title = "Légal's Trap",
        subtitle = "test",
        segments = listOf(segment),
        chapters = emptyList(),
        totalEstimatedMs = segment.estimatedSpeechMs,
        userColor = null,
    )

    private fun specFor(segment: ScriptSegment): BoardFrameRenderer.BoardFrameSpec {
        val instruction = SegmentFrameBuilder.build(
            script(segment),
            segment,
            elapsedMs = SegmentFrameBuilder.MOVE_ANIMATION_MS,
            orientation = BoardOrientation.WHITE_DOWN,
        )
        assertTrue("expected a board frame, got $instruction", instruction is RenderInstruction.Board)
        return (instruction as RenderInstruction.Board).spec
    }

    // -----------------------------------------------------------------------
    // The verdict wins the chip
    // -----------------------------------------------------------------------

    @Test
    fun aMistakeOnABlunderKindBeatIsLabelledMistake() {
        // The exact shape of the defect: an error beat annotates the position and is emitted as
        // SegmentKind.BLUNDER, but the move is a MISTAKE. The panel must say what the move list
        // says.
        val segment = segment(
            kind = SegmentKind.BLUNDER,
            board = BoardDirective.Annotate(fenBefore),
            classification = MoveClassification.MISTAKE,
        )
        val spec = specFor(segment)
        assertEquals(MoveClassification.MISTAKE, spec.classification)

        val chip = BoardFrameRenderer.panelChip(spec)
        assertNotNull(chip)
        assertEquals("? Mistake", chip!!.text)
    }

    @Test
    fun theChipMatchesTheMoveListsWordForEveryClassification() {
        for (cls in MoveClassification.values()) {
            val spec = specFor(
                segment(SegmentKind.BLUNDER, BoardDirective.Annotate(fenBefore), cls)
            )
            val chip = BoardFrameRenderer.panelChip(spec)
            assertNotNull("no chip for $cls", chip)
            assertEquals("${cls.glyph} ${cls.displayName}", chip!!.text)
        }
    }

    @Test
    fun aPlayMoveBeatIsLabelledByItsVerdictToo() {
        val spec = specFor(
            segment(
                kind = SegmentKind.BLUNDER,
                board = BoardDirective.PlayMove(fenBefore, "g7g6", "g6", MoveClassification.MISTAKE),
                classification = MoveClassification.MISTAKE,
            )
        )
        assertEquals("? Mistake", BoardFrameRenderer.panelChip(spec)!!.text)
    }

    // -----------------------------------------------------------------------
    // The kind still labels what it is actually about
    // -----------------------------------------------------------------------

    @Test
    fun aSegmentAboutNoClassifiedMoveKeepsItsKind() {
        // A puzzle prompt is about a position, not about a verdict on one move, so the kind is
        // the only honest label and must survive.
        val spec = specFor(
            segment(SegmentKind.PUZZLE_PROMPT, BoardDirective.Annotate(fenBefore), classification = null)
        )
        assertEquals("Puzzle Prompt", BoardFrameRenderer.panelChip(spec)!!.text)
    }

    @Test
    fun noChipAtAllWhenTheSpecCarriesNeither() {
        val bare = BoardFrameRenderer.BoardFrameSpec(boardState = BoardState.empty())
        assertEquals(null, BoardFrameRenderer.panelChip(bare))
    }

    // -----------------------------------------------------------------------
    // One verdict, not two
    // -----------------------------------------------------------------------

    @Test
    fun theVerdictIsDrawnOnceAndOnlyOnce() {
        // The panel used to draw the kind chip at the top *and* a classification badge beside the
        // SAN, so one move could be labelled twice in one panel. Counted off the real pixels of a
        // rendered 1280x720 frame rather than off the draw calls, because "how many chips ended
        // up on screen" is the actual claim.
        val spec = specFor(
            segment(
                kind = SegmentKind.BLUNDER,
                board = BoardDirective.PlayMove(fenBefore, "g7g6", "g6", MoveClassification.MISTAKE),
                classification = MoveClassification.MISTAKE,
            )
        )
        val bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        BoardFrameRenderer.renderBoardFrame(Canvas(bitmap), 1280, 720, spec)

        // The chip is a solid fill of the MISTAKE orange from ui/theme/Color.kt. Count the
        // separated horizontal bands containing it: one chip is one band.
        val mistakeOrange = 0xFFE58F2A.toInt()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        bitmap.recycle()
        var bands = 0
        var inBand = false
        for (y in 0 until 720) {
            val row = y * 1280
            var hit = false
            for (x in 0 until 1280) {
                if (pixels[row + x] == mistakeOrange) { hit = true; break }
            }
            if (hit && !inBand) bands++
            inBand = hit
        }
        assertEquals("the panel drew the verdict $bands times", 1, bands)
    }

    // -----------------------------------------------------------------------
    // V3: the lead-in before a key move shows no verdict yet
    // -----------------------------------------------------------------------

    @Test
    fun theLeadInShowsNoVerdictThenTheMoveCarriesIt() {
        // 2. Nf3 Nc6 skipped by the story, then the key move 3. Bb5 (labelled MISTAKE here only to see the chip).
        val start = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2"
        val before = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3"
        val lead = net.palaya.chessanalyzer.core.narration.SegmentLeadIn(
            fen = start,
            approachUci = listOf("g1f3", "b8c6"),
            approachSan = listOf("Nf3", "Nc6"),
            approachCaptions = listOf("2. Nf3", "2... Nc6"),
            stepMs = 1_500L,
            pauseMs = 1_200L,
            highlightSquares = listOf("f1", "b5"),
            eval = SegmentEval(winPercentWhite = 55.0, evalCp = 30),
        )
        val seg = segment(
            kind = SegmentKind.BLUNDER,
            board = BoardDirective.PlayMove(before, "f1b5", "Bb5", MoveClassification.MISTAKE),
            classification = MoveClassification.MISTAKE,
        ).copy(caption = "3. Bb5 ?", leadIn = lead)
        fun at(ms: Long) = (SegmentFrameBuilder.build(script(seg), seg, ms, BoardOrientation.WHITE_DOWN) as RenderInstruction.Board).spec

        // The first skipped move, landed: its own caption, its squares marked, no chip at all.
        val approach = at(SegmentFrameBuilder.MOVE_ANIMATION_MS + 100)
        assertEquals("2. Nf3", approach.caption)
        assertEquals(null, approach.classification)
        assertEquals(null, BoardFrameRenderer.panelChip(approach))
        assertEquals(55.0, approach.evalWinPercentWhite!!, 0.0)
        // The pause: the key move's position, its two squares lit, "Key Moment", still no verdict.
        val pause = at(3_000L + 600L)
        assertEquals("3. Bb5 ?", pause.caption)
        assertEquals(2, pause.highlightSquares.size)
        assertEquals(null, pause.classification)
        assertEquals(null, pause.animating)
        assertEquals("Key Moment", BoardFrameRenderer.panelChip(pause)!!.text)
        // After the lead-in the move slides in and carries its verdict.
        val sliding = at(lead.durationMs + 100)
        assertNotNull(sliding.animating)
        val landed = at(lead.durationMs + SegmentFrameBuilder.MOVE_ANIMATION_MS)
        assertEquals("? Mistake", BoardFrameRenderer.panelChip(landed)!!.text)
        assertEquals(62.0, landed.evalWinPercentWhite!!, 0.0)
    }

    // -----------------------------------------------------------------------
    // V2 + V4: a move of the best line is an excursion with no verdict, and the way back has the verdict again
    // -----------------------------------------------------------------------

    @Test
    fun theBestLineAfterTheSpeechIsAnExcursionWithNoVerdict() {
        val beat = segment(
            kind = SegmentKind.BLUNDER,
            board = BoardDirective.Annotate(fenBefore),
            classification = MoveClassification.MISTAKE,
        )
        // The line's first move, as the generator lays it out after the beat: its own spoken segment.
        val move = beat.copy(
            index = beat.index + 1, kind = SegmentKind.BEST_LINE, narration = "Knight to f three.",
            caption = "Best line — 2. Nf3", board = BoardDirective.PlayMove(fenBefore, "g1f3", "Nf3"), classification = null,
        )
        val back = beat.copy(index = beat.index + 2, kind = SegmentKind.KEY_MOMENT, narration = "Back to the game now.", caption = "Back to the game — 4... g6")
        val script = script(beat).let { it.copy(segments = listOf(beat, move, back)) }
        fun at(seg: net.palaya.chessanalyzer.core.narration.ScriptSegment, ms: Long) =
            (SegmentFrameBuilder.build(script, seg, ms, BoardOrientation.WHITE_DOWN) as RenderInstruction.Board).spec

        // While the beat speaks: its verdict, its caption, no excursion.
        val speaking = at(beat, 1_000L)
        assertEquals("? Mistake", BoardFrameRenderer.panelChip(speaking)!!.text)
        assertEquals("4... g6 ?", speaking.caption)
        assertEquals(false, speaking.excursionActive)
        // The line's move: it slides as its words start, in the excursion's colours, with no chip at all.
        val sliding = at(move, 100L)
        assertNotNull(sliding.animating)
        assertEquals(true, sliding.excursionActive)
        assertEquals("Engine's best line", sliding.excursionLabel)
        assertEquals(null, BoardFrameRenderer.panelChip(sliding))
        val landed = at(move, SegmentFrameBuilder.MOVE_ANIMATION_MS + 50)
        assertEquals(null, landed.animating)
        assertEquals("Best line — 2. Nf3", landed.caption)
        assertEquals("Nf3", landed.san)
        // The eval bar keeps the beat's eval: the line is the engine's best play from that position.
        assertEquals(62.0, landed.evalWinPercentWhite!!, 0.0)
        // Back to the game: the beat's own picture, its verdict back, no excursion.
        val returned = at(back, 500L)
        assertEquals(false, returned.excursionActive)
        assertEquals("? Mistake", BoardFrameRenderer.panelChip(returned)!!.text)
        assertEquals(speaking.boardState, returned.boardState)
    }

    // -----------------------------------------------------------------------
    // The recap card (R6b) says a class the way the panel chip does
    // -----------------------------------------------------------------------

    @Test
    fun theRecapCardNamesEveryClassWithTheWordThePanelChipUses() {
        val labels = BoardFrameRenderer.PanelLabels.from(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        )
        for (cls in MoveClassification.values()) {
            // The chip is "glyph + name" and the recap chip is "name ×n": the same name, from the same
            // string resource, so the two cannot drift into calling a Mistake two things.
            assertEquals("${cls.glyph} ${labels.recap.className(cls)}", labels.verdict(cls))
        }
        // The English defaults agree with the resources, so a hand-built spec reads the same.
        for (cls in MoveClassification.values()) {
            assertEquals(cls.displayName, RecapLabels.ENGLISH.className(cls))
        }
    }
}
