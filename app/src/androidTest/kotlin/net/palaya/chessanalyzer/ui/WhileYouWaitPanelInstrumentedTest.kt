package net.palaya.chessanalyzer.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.palaya.chessanalyzer.R
import net.palaya.chessanalyzer.ui.components.WaitingPreviewContent
import net.palaya.chessanalyzer.ui.components.WaitingTags
import net.palaya.chessanalyzer.ui.components.WhileYouWaitPanel
import net.palaya.chessanalyzer.ui.theme.ChessAnalyzerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The "While you wait" panel on a device (docs/WAITING_CONTENT.md). Compiled with `:app:assembleDebugAndroidTest`;
 * run by the orchestrator after the merge. Auto-rotation is off (the test drives the arrows), the seed is fixed, so the
 * second card is always a fact and the third a puzzle (the deck's pattern is fact, puzzle, game, fact, player, puzzle).
 */
@RunWith(AndroidJUnit4::class)
class WhileYouWaitPanelInstrumentedTest {

    @get:Rule
    val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun show(fontScale: Float? = null) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides if (fontScale == null) density else Density(density.density, fontScale)) {
                ChessAnalyzerTheme {
                    WhileYouWaitPanel(
                        insights = WaitingPreviewContent.insights,
                        content = WaitingPreviewContent.content,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        seed = 1L,
                        autoRotate = false,
                    )
                }
            }
        }
    }

    private fun square(color: Int, piece: Int, name: String) =
        compose.onNodeWithContentDescription(str(R.string.cd_piece, str(color), str(piece), name))

    @Test
    fun theInsightsComeFirstAndThePreviousArrowIsOff() {
        show()
        compose.onNodeWithText(str(R.string.wait_kind_insights)).assertIsDisplayed()
        compose.onNodeWithText("Opening: Sicilian Defense: Najdorf Variation (B90)").assertIsDisplayed()
        compose.onNodeWithTag(WaitingTags.PREVIOUS).assertIsNotEnabled()
        compose.onNodeWithTag(WaitingTags.NEXT).assertIsEnabled()
    }

    @Test
    fun theArrowsAndThePauseButtonAreAtLeast48dp() {
        show()
        for (tag in listOf(WaitingTags.PREVIOUS, WaitingTags.NEXT, WaitingTags.PAUSE)) {
            compose.onNodeWithTag(tag).assertHeightIsAtLeast(48.dp)
        }
    }

    @Test
    fun nextShowsAFactAndBackReturnsToTheInsights() {
        show()
        compose.onNodeWithTag(WaitingTags.NEXT).performClick()
        compose.onNodeWithText(str(R.string.wait_kind_fact)).assertIsDisplayed()
        compose.onNodeWithTag(WaitingTags.PREVIOUS).assertIsEnabled().performClick()
        compose.onNodeWithText(str(R.string.wait_kind_insights)).assertIsDisplayed()
    }

    @Test
    fun theMateInOneIsSolvedByTappingAndNextFollows() {
        show()
        repeat(2) { compose.onNodeWithTag(WaitingTags.NEXT).performClick() }
        compose.onNodeWithText(str(R.string.wait_kind_puzzle)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.wait_puzzle_goal_mate, 1)).assertIsDisplayed()
        square(R.string.side_white, R.string.piece_queen, "h5").performClick()
        square(R.string.side_black, R.string.piece_pawn, "f7").performClick()
        compose.onNodeWithText(str(R.string.wait_puzzle_solved_mate, 1)).assertIsDisplayed()
    }

    @Test
    fun aWrongMoveIsRefusedWithAMessage() {
        show()
        repeat(2) { compose.onNodeWithTag(WaitingTags.NEXT).performClick() }
        square(R.string.side_white, R.string.piece_queen, "h5").performClick()
        square(R.string.side_black, R.string.piece_pawn, "e5").performClick()
        compose.onNodeWithText(str(R.string.wait_puzzle_wrong)).assertIsDisplayed()
    }

    @Test
    fun showSolutionWritesTheLine() {
        show()
        repeat(2) { compose.onNodeWithTag(WaitingTags.NEXT).performClick() }
        compose.onNodeWithTag(WaitingTags.SHOW_SOLUTION).performClick()
        compose.onNodeWithText(str(R.string.wait_puzzle_revealed, "4.Qxf7#")).assertIsDisplayed()
    }

    @Test
    fun pauseBecomesResume() {
        show()
        compose.onNodeWithContentDescription(str(R.string.wait_pause)).performClick()
        compose.onNodeWithContentDescription(str(R.string.wait_resume)).assertIsDisplayed()
    }

    @Test
    fun aLargeSystemFontKeepsTheControlsAndTheTextOnScreen() {
        show(fontScale = 2f)
        compose.onNodeWithText(str(R.string.wait_title)).assertIsDisplayed()
        compose.onNodeWithText("Opening: Sicilian Defense: Najdorf Variation (B90)").assertIsDisplayed()
        compose.onNodeWithTag(WaitingTags.NEXT).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }
}
