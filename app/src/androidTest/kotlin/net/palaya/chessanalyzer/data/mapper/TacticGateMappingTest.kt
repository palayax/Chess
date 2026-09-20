package net.palaya.chessanalyzer.data.mapper

import androidx.test.ext.junit.runners.AndroidJUnit4
import net.palaya.chessanalyzer.core.analysis.GameReport as CoreGameReport
import net.palaya.chessanalyzer.core.analysis.MoveAnnotation
import net.palaya.chessanalyzer.core.analysis.MoveClassification as CoreClassification
import net.palaya.chessanalyzer.core.analysis.PlayerReport as CorePlayerReport
import net.palaya.chessanalyzer.core.analysis.TacticInstance
import net.palaya.chessanalyzer.core.analysis.TacticReferenceLibrary
import net.palaya.chessanalyzer.core.analysis.TacticType
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.parseUci
import net.palaya.chessanalyzer.ui.model.GameHeader
import net.palaya.chessanalyzer.ui.model.PieceColor
import net.palaya.chessanalyzer.ui.navigation.Destination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The seam between `:core`'s tactic significance gate (ANALYSIS_SPEC §9.6) and what the game
 * report actually draws, plus the reference library (§10) as it is reached from the app.
 *
 * On-device rather than host-side for the same reason as [MoveSwingMappingTest]: `:core` proves
 * the gate, but a mapper that forgot to pass the threshold through would silently show every
 * detector hit again and every `:core` test would still pass.
 */
@RunWith(AndroidJUnit4::class)
class TacticGateMappingTest {

    private val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    private val afterE4 = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"

    private fun annotation(
        ply: Int,
        evalBefore: Int,
        evalAfter: Int,
        secondBest: Int? = null,
        classification: CoreClassification = CoreClassification.BEST,
        found: List<TacticInstance> = emptyList(),
        missed: List<TacticInstance> = emptyList(),
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
        evalSecondBestCp = secondBest,
        tacticsFound = found,
        tacticsMissed = missed,
    )

    private fun report(annotations: List<MoveAnnotation>): CoreGameReport {
        fun player(color: Color) = CorePlayerReport(
            color, null, 80.0, 1500, false, emptyMap(),
            tacticsFound = annotations.filter { it.color == color }.flatMap { it.tacticsFound },
            tacticsMissed = annotations.filter { it.color == color }.flatMap { it.tacticsMissed },
        )
        return CoreGameReport(
            white = player(Color.WHITE), black = player(Color.BLACK), annotations = annotations,
            openingName = null, openingEco = null, result = "*",
            evalGraph = List(annotations.size + 1) { 50.0 }, keyMoments = emptyList(), analysisDepth = 12,
        )
    }

    private val header = GameHeader(white = "w", black = "b")

    @Test
    fun theReportGatesTacticsAtTheThresholdAndKeepsTheRestAsMinor() {
        val pin = TacticInstance(TacticType.PIN_RELATIVE, Color.WHITE, "a1a2", confidence = 0.6)
        val fork = TacticInstance(TacticType.FORK, Color.WHITE, "a1a2", materialSwing = 500, confidence = 0.95)
        val core = report(
            listOf(
                // A relative pin on a best move with an equal alternative: no bearing on the game.
                annotation(1, 30, 30, secondBest = 28, found = listOf(pin)),
                annotation(2, 30, 30),
                // A fork worth 500 over the second-best line: this one mattered.
                annotation(3, 500, 500, secondBest = 0, found = listOf(fork)),
            )
        )

        val gated = core.toUiReport(header, PieceColor.WHITE, tacticThresholdCp = 50)
        assertEquals(listOf(TacticType.FORK), gated.white.tacticsFound.map { it.type })
        assertEquals(listOf(TacticType.PIN_RELATIVE), gated.white.minorTacticsFound.map { it.type })
        assertEquals(50, gated.tacticThresholdCp)

        val ungated = core.toUiReport(header, PieceColor.WHITE, tacticThresholdCp = 0)
        assertEquals(2, ungated.white.tacticsFound.size)
        assertTrue(ungated.white.minorTacticsFound.isEmpty())
    }

    @Test
    fun theGroupsKnowWhetherATextbookExampleExists() {
        val fork = TacticInstance(TacticType.FORK, Color.WHITE, "a1a2", materialSwing = 500, confidence = 0.95)
        val xray = TacticInstance(TacticType.X_RAY, Color.WHITE, "a1a2", materialSwing = 500, confidence = 0.95)
        val core = report(listOf(annotation(1, 500, 500, secondBest = 0, found = listOf(fork, xray))))
        val ui = core.toUiReport(header, PieceColor.WHITE, tacticThresholdCp = 50)
        val byType = ui.white.tacticsFound.associateBy { it.type }
        assertTrue(byType.getValue(TacticType.FORK).hasReference)
        assertTrue(!byType.getValue(TacticType.X_RAY).hasReference)
    }

    @Test
    fun everyReferenceInTheShippedCorpusReplaysOnDevice() {
        // The corpus that is actually inside the APK, replayed through the on-device :core.
        val all = TacticReferenceLibrary.all
        assertTrue("expected the full verified corpus, got ${all.size}", all.size >= 28)
        for (ref in all) {
            val sim = TacticReferenceLibrary.simulation(ref.type)
            assertNotNull("${ref.type}: no simulation", sim)
            var pos = Position.fromFen(sim!!.startFen)
            for (uci in sim.pvUci) pos = pos.makeMove(pos.parseUci(uci))
            assertEquals("${ref.type}: whole line is played", ref.solutionSan.size, sim.pvUci.size)
            assertEquals(ref.solutionSan, sim.pvSan)
            // The route a report or review card navigates to must survive the trip.
            val route = Destination.Reference.createRoute(ref.type)
            assertEquals(ref.type, TacticType.valueOf(route.substringAfterLast('/')))
        }
    }
}
