package net.palaya.chessanalyzer.data.mapper

import net.palaya.chessanalyzer.core.analysis.GameReport as CoreGameReport
import net.palaya.chessanalyzer.core.analysis.KeyMoment as CoreKeyMoment
import net.palaya.chessanalyzer.core.analysis.MoveAnnotation
import net.palaya.chessanalyzer.core.analysis.MoveClassification as CoreClassification
import net.palaya.chessanalyzer.core.analysis.PlayerReport as CorePlayerReport
import net.palaya.chessanalyzer.core.analysis.TacticInstance
import net.palaya.chessanalyzer.core.analysis.TacticSimulation
import net.palaya.chessanalyzer.core.analysis.TacticType
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.ui.model.GameHeader
import net.palaya.chessanalyzer.ui.model.PieceColor
import net.palaya.chessanalyzer.ui.model.SideChoice
import net.palaya.chessanalyzer.ui.model.selectSummaryMoments
import net.palaya.chessanalyzer.ui.model.sideChoice
import net.palaya.chessanalyzer.ui.theme.MoveClassification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What `toUiReport` passes to the Summary (U5): opening, side state, simulations, brilliancies. */
class SummaryMappingTest {

    private val fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    private val header = GameHeader(white = "w", black = "b")

    private fun annotation(
        ply: Int,
        classification: CoreClassification = CoreClassification.BEST,
        simulation: TacticSimulation? = null,
    ) = MoveAnnotation(
        ply = ply, moveNumber = (ply + 1) / 2, color = if (ply % 2 == 1) Color.WHITE else Color.BLACK,
        san = "m$ply", uci = "a2a3", fenBefore = fen, fenAfter = fen, classification = classification,
        loss = 0.0, winPercentBefore = 50.0, winPercentAfter = 50.0, evalBeforeCp = 0, evalAfterCp = 0,
        text = "t$ply", simulation = simulation,
    )

    private fun report(
        annotations: List<MoveAnnotation>,
        keyMoments: List<CoreKeyMoment> = emptyList(),
        openingName: String? = null,
    ): CoreGameReport {
        fun player(color: Color) = CorePlayerReport(color, null, 80.0, 1500, false, emptyMap(), emptyList(), emptyList())
        return CoreGameReport(
            white = player(Color.WHITE), black = player(Color.BLACK), annotations = annotations,
            openingName = openingName, openingEco = null, result = "*",
            evalGraph = List(annotations.size + 1) { 50.0 }, keyMoments = keyMoments, analysisDepth = 12,
        )
    }

    private fun simulation() = TacticSimulation(
        startFen = fen, pvUci = emptyList(), pvSan = emptyList(), perPlyExplanation = emptyList(),
        tactic = TacticInstance(TacticType.FORK, Color.WHITE, "a1a2"), payoffDescription = "",
    )

    @Test
    fun theOpeningNameReachesTheUiReport() {
        val ui = report(listOf(annotation(1)), openingName = "Philidor Defense").toUiReport(header)
        assertEquals("Philidor Defense", ui.openingName)
        assertNull(report(listOf(annotation(1))).toUiReport(header).openingName)
    }

    @Test
    fun onlyPliesWithAWalkthroughAreOffered() {
        val ui = report(listOf(annotation(1), annotation(2, simulation = simulation()), annotation(3))).toUiReport(header)
        assertEquals(setOf(2), ui.plysWithSimulation)
    }

    @Test
    fun theColourIsSetAndNotMeIsFalseByDefaultSoOldCallersKeepWorking() {
        val core = report(listOf(annotation(1)))
        // The exact call TacticGateMappingTest makes.
        val ui = core.toUiReport(header, PieceColor.WHITE, tacticThresholdCp = 50)
        assertEquals(PieceColor.WHITE, ui.userColor)
        assertEquals(SideChoice.WHITE, ui.sideChoice)
        assertEquals(SideChoice.UNKNOWN, core.toUiReport(header).sideChoice)
    }

    @Test
    fun remappingWithAnotherColourChangesTheFramingWithoutTouchingTheAnalysis() {
        val core = report(listOf(annotation(1), annotation(2)))
        val white = core.toUiReport(header, PieceColor.WHITE)
        val black = core.toUiReport(header, PieceColor.BLACK)
        assertEquals(PieceColor.BLACK, black.userColor)
        assertEquals(white.evalHistory, black.evalHistory)
        assertEquals(white.white.accuracyPercent, black.white.accuracyPercent, 0.0)
    }

    @Test
    fun notMeDropsTheColourButIsAnExplicitState() {
        val ui = report(listOf(annotation(1))).toUiReport(header, PieceColor.WHITE, notMe = true)
        assertNull(ui.userColor)
        assertTrue(ui.notMe)
        assertEquals(SideChoice.NOT_ME, ui.sideChoice)
    }

    @Test
    fun brilliantMovesJoinTheMistakesAsKeyMomentsInPlyOrderWithAtMostTwo() {
        val annotations = listOf(
            annotation(1, CoreClassification.BRILLIANT),
            annotation(2, CoreClassification.BLUNDER),
            annotation(3, CoreClassification.BRILLIANT),
            annotation(4, CoreClassification.BRILLIANT),
        )
        val core = report(annotations, keyMoments = listOf(CoreKeyMoment(2, "m2", CoreClassification.BLUNDER, 40.0, "oops")))
        val moments = core.toUiReport(header).keyMoments
        assertEquals(listOf(1, 2, 3), moments.map { it.ply })
        assertEquals(MoveClassification.BRILLIANT, moments[0].classification)
        assertEquals(40.0, moments[1].loss, 0.0)
        assertEquals("oops", moments[1].description)
    }

    @Test
    fun theSummaryKeepsTheMistakesAndBrilliancyAndDropsInaccuracies() {
        val annotations = listOf(annotation(1, CoreClassification.BRILLIANT), annotation(2), annotation(3), annotation(4))
        val core = report(
            annotations,
            keyMoments = listOf(
                CoreKeyMoment(2, "m2", CoreClassification.INACCURACY, 6.0, "meh"),
                CoreKeyMoment(3, "m3", CoreClassification.MISTAKE, 12.0, "hmm"),
            ),
        )
        val ui = core.toUiReport(header, PieceColor.WHITE)
        assertEquals(listOf(1, 3), selectSummaryMoments(ui).primary.map { it.ply })
    }
}
