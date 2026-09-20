package net.palaya.chessanalyzer.data.mapper

import androidx.test.ext.junit.runners.AndroidJUnit4
import net.palaya.chessanalyzer.core.analysis.EvalFormat
import net.palaya.chessanalyzer.core.analysis.MoveAnnotation
import net.palaya.chessanalyzer.core.analysis.MoveClassification as CoreClassification
import net.palaya.chessanalyzer.core.analysis.WinProbability
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.ui.theme.MoveClassification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The seam between `:core`'s per-ply evaluation and what the review move list actually renders
 * (ANALYSIS_SPEC §9.1/§9.4).
 *
 * Worth an on-device test rather than a host one because it is the composition that matters:
 * `:core` computing the right number is already covered in `:core`, but a mapper that drops
 * `evalBeforeCp` would silently make every chip's swing disappear and every `:core` test would
 * still pass.
 */
@RunWith(AndroidJUnit4::class)
class MoveSwingMappingTest {

    private val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    private val afterE4 = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"

    private fun annotation(
        ply: Int,
        evalBefore: Int,
        evalAfter: Int,
        classification: CoreClassification = CoreClassification.GOOD,
        mateInAfter: Int? = null,
        mateInBefore: Int? = null,
    ) = MoveAnnotation(
        ply = ply,
        moveNumber = (ply + 1) / 2,
        color = if (ply % 2 == 1) Color.WHITE else Color.BLACK,
        san = "e4",
        uci = "e2e4",
        fenBefore = startFen,
        fenAfter = afterE4,
        classification = classification,
        loss = 0.0,
        winPercentBefore = 50.0,
        winPercentAfter = 50.0,
        evalBeforeCp = evalBefore,
        evalAfterCp = evalAfter,
        mateInBefore = mateInBefore,
        mateInAfter = mateInAfter,
    )

    @Test
    fun theMoveRecordCarriesBothEndsOfTheEvaluation() {
        val record = annotation(ply = 5, evalBefore = 30, evalAfter = -120).toMoveRecord()
        assertEquals(30, record.evalBeforeCp)
        assertEquals(-120, record.evalCp)
        assertEquals(-150, record.evalSwingCp)
    }

    @Test
    fun swingIsNullWhenEitherEndIsUnknown() {
        // Placeholder/preview records carry no evaluation at all; the chip must then show no
        // swing rather than a fabricated 0.0, which would read as "this move changed nothing".
        val record = net.palaya.chessanalyzer.ui.model.MoveRecord(ply = 1, san = "e4")
        assertNull(record.evalSwingCp)
    }

    @Test
    fun mateScoresSurviveTheMappingAsMateAndRenderAsMate() {
        val record = annotation(
            ply = 33,
            evalBefore = 400,
            evalAfter = WinProbability.cpFromMate(1),
            mateInAfter = 1,
        ).toMoveRecord()

        assertTrue(record.isMateScore)
        assertEquals(1, record.mateInMoves)
        assertEquals("M1", EvalFormat.score(record.evalCp, record.mateInMoves))
        // No "+95.5 pawns" swing across the mate boundary — ANALYSIS_SPEC §9.1.
        assertNull(record.evalSwingCp)
    }

    @Test
    fun aDeliveredCheckmateReadsAsAHashNotAsM0() {
        val record = annotation(
            ply = 33,
            evalBefore = 900,
            evalAfter = WinProbability.cpFromMate(0),
            mateInAfter = 0,
        ).toMoveRecord()

        assertEquals("#", EvalFormat.score(record.evalCp, record.mateInMoves))
    }

    @Test
    fun sequencesMapAcrossWithTheUiPalette() {
        val annotations = listOf(
            annotation(1, 0, 0),
            annotation(2, 0, 0),
            annotation(3, 0, -300, CoreClassification.MISTAKE),
            annotation(4, -300, -300),
            annotation(5, -300, -900, CoreClassification.BLUNDER),
        )
        val sequences = annotations.toSequenceViews()

        assertEquals(1, sequences.size)
        val run = sequences.single()
        assertEquals(3, run.startPly)
        assertEquals(5, run.endPly)
        assertEquals("Collapse", run.label)
        assertEquals(900, run.totalSwingCp)
        // The ui.theme enum, so the move list and the eval graph use the same palette the badge
        // already uses — not a second one.
        assertEquals(MoveClassification.BLUNDER, run.classification)
        assertTrue(4 in run)
    }
}
