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
}
