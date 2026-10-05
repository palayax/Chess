package net.palaya.chessanalyzer.data.mapper

import net.palaya.chessanalyzer.core.analysis.GameReport as CoreGameReport
import net.palaya.chessanalyzer.core.analysis.MoveAnnotation
import net.palaya.chessanalyzer.core.analysis.MoveClassification as CoreClassification
import net.palaya.chessanalyzer.core.analysis.PlayerReport as CorePlayerReport
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.ui.model.GameHeader
import net.palaya.chessanalyzer.ui.model.PieceColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one-sentence game summary (ANALYSIS_SPEC §12) as the report screen receives it: filled by
 * `toUiReport` from `:core`, and re-written when the side chooser re-maps the same core report.
 */
class SummarySentenceMappingTest {

    private val header = GameHeader(white = "w", black = "b")

    private fun annotation(ply: Int, loss: Double = 0.0, classification: CoreClassification = CoreClassification.GOOD) =
        MoveAnnotation(
            ply = ply, moveNumber = (ply + 1) / 2, color = if (ply % 2 == 1) Color.WHITE else Color.BLACK,
            san = "m$ply", uci = "a2a3", fenBefore = "", fenAfter = "", classification = classification,
            loss = loss, winPercentBefore = 55.0, winPercentAfter = 55.0 - loss, evalBeforeCp = 0, evalAfterCp = 0,
        )

    /** 40 plies, 1-0; Black blunders on move 11 (ply 22) from a fine position. */
    private fun report(plies: Int = 40, result: String = "1-0"): CoreGameReport {
        fun player(color: Color) = CorePlayerReport(color, null, 80.0, 1500, false, emptyMap(), emptyList(), emptyList())
        val moves = (1..plies).map {
            if (it == 22) annotation(it, loss = 30.0, classification = CoreClassification.BLUNDER) else annotation(it)
        }
        return CoreGameReport(
            white = player(Color.WHITE), black = player(Color.BLACK), annotations = moves,
            openingName = null, openingEco = null, result = result,
            evalGraph = List(plies + 1) { 50.0 }, keyMoments = emptyList(), analysisDepth = 12,
        )
    }

    @Test
    fun theExistingCallStillWorksAndGivesTheColourWording() {
        // Exactly the shape of the older call sites: header only, then header + colour + threshold.
        assertEquals(
            "Black was fine until move 11, then a blunder decided it.",
            report().toUiReport(header).summarySentence,
        )
        assertEquals(
            "Your opponent was fine until move 11, then a blunder decided it.",
            report().toUiReport(header, PieceColor.WHITE, tacticThresholdCp = 50).summarySentence,
        )
    }

    @Test
    fun theSideChooserReWritesTheSentenceFromTheSameReport() {
        val core = report()
        val white = core.toUiReport(header, PieceColor.WHITE)
        val black = core.toUiReport(header, PieceColor.BLACK)
        val notMe = core.toUiReport(header, PieceColor.BLACK, notMe = true)
        assertEquals("Your opponent was fine until move 11, then a blunder decided it.", white.summarySentence)
        assertEquals("You were fine until move 11, then a blunder decided it.", black.summarySentence)
        assertEquals("Black was fine until move 11, then a blunder decided it.", notMe.summarySentence)
        assertNotEquals(white.summarySentence, black.summarySentence)
        // Nothing else about the analysis moved.
        assertEquals(white.evalHistory, black.evalHistory)
    }

    @Test
    fun aReportWithNoMovesHasNoSummary() {
        assertNull(report(plies = 0).toUiReport(header).summarySentence)
    }

    @Test
    fun aGameWithNoResultSaysSoAndNothingElse() {
        assertEquals(
            "The game stops after 20 moves without a result.",
            report(result = "*").toUiReport(header, PieceColor.WHITE).summarySentence,
        )
    }
}
