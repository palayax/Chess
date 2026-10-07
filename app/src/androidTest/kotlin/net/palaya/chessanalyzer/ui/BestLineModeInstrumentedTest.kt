package net.palaya.chessanalyzer.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.palaya.chessanalyzer.R
import net.palaya.chessanalyzer.core.analysis.CandidateLine
import net.palaya.chessanalyzer.core.analysis.MoveAnnotation
import net.palaya.chessanalyzer.core.analysis.MoveClassification
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.data.mapper.toMoveRecord
import net.palaya.chessanalyzer.ui.model.GameHeader
import net.palaya.chessanalyzer.ui.model.ImportedGame
import net.palaya.chessanalyzer.ui.model.PlaceholderData
import net.palaya.chessanalyzer.ui.screens.GameReportScreen
import net.palaya.chessanalyzer.ui.screens.ReviewScreen
import net.palaya.chessanalyzer.ui.theme.ChessAnalyzerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * V2 on the device: the Board's "Show the best line" mode (ANALYSIS_SPEC 6.2) — enter, step, back, play,
 * choose an alternative, leave with "Back to the game" and with the system back, and the TalkBack labels
 * of CLAUDE.md's accessibility conventions (buttons with words, the step caption a polite live region,
 * a line chip one sentence with its selected state, the card's title a heading). Plus the Summary's key
 * moment offering the line where it has no walkthrough.
 */
@RunWith(AndroidJUnit4::class)
class BestLineModeInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val start = Position.STANDARD_START_FEN
    private val ruyLopez = listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1b5", "a7a6", "b5a4", "g8f6", "e1g1")

    private fun str(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

    /** 1. a3 (a MISTAKE; the engine's 1. e4 line at depth 14, and 1. d4 within the margin), then 1... e5. */
    private fun game(): ImportedGame {
        val afterA3 = Position.fromFen(start).let { it.makeMove(it.legalMoves().first { m -> m.toUci() == "a2a3" }) }
        val afterE5 = afterA3.makeMove(afterA3.legalMoves().first { it.toUci() == "e7e5" })
        val a3 = MoveAnnotation(
            ply = 1, moveNumber = 1, color = Color.WHITE, san = "a3", uci = "a2a3", fenBefore = start, fenAfter = afterA3.toFen(),
            classification = MoveClassification.MISTAKE, loss = 11.0, winPercentBefore = 54.0, winPercentAfter = 43.0,
            evalBeforeCp = 40, evalAfterCp = -60, bestMoveUci = "e2e4", bestMoveSan = "e4",
            text = "This gives back ground. Better was e4.",
            candidateLines = listOf(
                CandidateLine(1, "e2e4", "e4", 40, null, ruyLopez, 14),
                CandidateLine(2, "d2d4", "d4", 35, null, listOf("d2d4", "d7d5", "c2c4"), 14),
                CandidateLine(3, "c2c4", "c4", -100, null, listOf("c2c4"), 14),
            ),
        )
        val e5 = MoveAnnotation(
            ply = 2, moveNumber = 1, color = Color.BLACK, san = "e5", uci = "e7e5", fenBefore = afterA3.toFen(), fenAfter = afterE5.toFen(),
            classification = MoveClassification.BEST, loss = 0.0, winPercentBefore = 57.0, winPercentAfter = 57.0,
            evalBeforeCp = -60, evalAfterCp = -60, bestMoveUci = "e7e5", bestMoveSan = "e5",
            candidateLines = listOf(CandidateLine(1, "e7e5", "e5", 60, null, listOf("e7e5", "g1f3"), 14)),
        )
        return ImportedGame("g", GameHeader("W", "B"), listOf(a3.toMoveRecord(), e5.toMoveRecord()))
    }

    private val liveRegion = SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)

    @Test
    fun theLineModeStepsPlaysChoosesAndReturnsToTheGame() {
        compose.setContent { ChessAnalyzerTheme { ReviewScreen(game = game(), initialPly = 1, playStepMs = 60L, onBack = {}) } }

        // A mistake offers the line; entering it replaces the game's controls with the line's.
        compose.onNodeWithText(str(R.string.review_show_best_line)).performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.line_title_best, str(R.string.simulation_move_white, 1, "a3"))).assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
        )
        // Depth 14: seven plies of the nine-ply PV.
        compose.onNode(hasText(str(R.string.simulation_step_counter, 0, 7), substring = true) and liveRegion, useUnmergedTree = false).assertExists()
        compose.onNode(hasText(str(R.string.line_start), substring = true)).assertExists()
        compose.onNodeWithText(str(R.string.panel_to_move, str(R.string.side_white)), substring = true).assertExists()
        compose.onNodeWithContentDescription(str(R.string.line_previous)).assertIsNotEnabled()
        compose.onNodeWithText(str(R.string.review_show_best_line)).assertDoesNotExist()

        // Next: 1. e4, Black to move, 1 / 7.
        compose.onNodeWithContentDescription(str(R.string.line_next)).performClick()
        compose.onNode(hasText(str(R.string.simulation_move_white, 1, "e4"), substring = true) and liveRegion).assertExists()
        compose.onNodeWithText(str(R.string.panel_to_move, str(R.string.side_black)), substring = true).assertExists()
        compose.onNodeWithText(str(R.string.simulation_step_counter, 1, 7), substring = true).assertExists()
        compose.onNodeWithContentDescription(str(R.string.line_next)).performClick()
        compose.onNodeWithText(str(R.string.simulation_move_black, 1, "e5"), substring = true).assertExists()
        // Back.
        compose.onNodeWithContentDescription(str(R.string.line_previous)).assertIsEnabled().performClick()
        compose.onNodeWithText(str(R.string.simulation_step_counter, 1, 7), substring = true).assertExists()

        // Play runs to the end of the line and stops there; the button reads Play again.
        compose.onNodeWithContentDescription(str(R.string.line_play)).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(str(R.string.simulation_step_counter, 7, 7), substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(str(R.string.simulation_move_white, 4, "Ba4"), substring = true).assertExists()
        compose.waitUntil(2_000) { compose.onAllNodes(hasContentDescription(str(R.string.line_play))).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(str(R.string.line_next)).assertIsNotEnabled()
        // The card states the engine's number as the engine's (White-relative) and the depth.
        compose.onNodeWithText("The engine rates this line +0.4.", substring = true).assertExists()
        compose.onNodeWithText(str(R.string.line_depth, 14)).assertExists()

        // Two lines are within the margin (line 3 is not): one chip each, a sentence with its state.
        val best = str(R.string.cd_line_choice, str(R.string.line_choice_best), "e4", "+0.4") + ", " + str(R.string.cd_line_selected)
        compose.onNodeWithContentDescription(best).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
        val second = str(R.string.cd_line_choice, str(R.string.line_choice_number, 2), "d4", "+0.4")
        // Chosen through the chip's own semantics action: what TalkBack's double tap does.
        compose.onNodeWithContentDescription(second).performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText(str(R.string.line_title_alternative, 2, str(R.string.simulation_move_white, 1, "a3"))).assertExists()
        compose.onNodeWithText(str(R.string.simulation_step_counter, 0, 3), substring = true).assertExists()
        compose.onNodeWithContentDescription(str(R.string.cd_line_choice, str(R.string.line_choice_number, 3), "c4", "-1.0")).assertDoesNotExist()

        // "Back to the game": the comment card and the game's transport are back.
        compose.onNodeWithText(str(R.string.line_back_to_game)).performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.review_show_best_line)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(str(R.string.review_next_move)).assertExists()

        // The system back leaves the line mode first, not the screen.
        compose.onNodeWithText(str(R.string.review_show_best_line)).performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.line_back_to_game)).assertExists()
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.line_back_to_game)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.review_show_best_line)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aQuietMoveThatIsNotAKeyMomentOffersNoLine() {
        // 1... e5 is BEST and not a key moment.
        compose.setContent { ChessAnalyzerTheme { ReviewScreen(game = game(), initialPly = 2, onBack = {}) } }
        compose.onNodeWithContentDescription(str(R.string.review_next_move)).assertExists()
        compose.onNodeWithText(str(R.string.review_show_best_line)).assertDoesNotExist()
    }

    @Test
    fun theBoardOpenedFromTheSummaryStartsInTheLineMode() {
        compose.setContent { ChessAnalyzerTheme { ReviewScreen(game = game(), initialPly = 1, openBestLine = true, onBack = {}) } }
        compose.onNodeWithText(str(R.string.line_back_to_game)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(str(R.string.simulation_step_counter, 0, 7), substring = true).assertExists()
    }

    @Test
    fun aSummaryKeyMomentWithoutAWalkthroughOffersTheLine() {
        var opened: Int? = null
        // 13 Bb3 has a walkthrough in the sample ("Show me"); 15 c3 does not, and has a line.
        val report = PlaceholderData.sampleReport.copy(plysWithSimulation = setOf(13), plysWithBestLine = setOf(13, 15))
        compose.setContent { ChessAnalyzerTheme { GameReportScreen(report = report, onShowMeClick = {}, onShowBestLine = { opened = it }) } }
        compose.onNodeWithText(str(R.string.review_show_best_line)).performScrollTo().performClick()
        assertEquals(15, opened)
    }
}
