package net.palaya.chessanalyzer.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.palaya.chessanalyzer.ui.a11y.AppBarTitle
import net.palaya.chessanalyzer.ui.board.BoardBadge
import net.palaya.chessanalyzer.ui.board.ChessBoard
import net.palaya.chessanalyzer.ui.components.EvalBar
import net.palaya.chessanalyzer.ui.components.MoveList
import net.palaya.chessanalyzer.ui.model.BoardState
import net.palaya.chessanalyzer.ui.model.MoveRecord
import net.palaya.chessanalyzer.ui.model.algebraicToSquare
import net.palaya.chessanalyzer.ui.theme.ChessAnalyzerTheme
import net.palaya.chessanalyzer.ui.theme.ClassificationBadge
import net.palaya.chessanalyzer.ui.theme.MoveClassification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The accessibility tree of the shared building blocks (U10), read through Compose's semantics
 * test API (no `assumeTrue`, so none of this can pass vacuously): `uiautomator dump` cannot show
 * headings, states or actions, this can.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilitySemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    @Test
    fun anAppBarTitleIsAHeading() {
        compose.setContent { ChessAnalyzerTheme { AppBarTitle("Board") } }
        compose.onNodeWithText("Board").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    @Test
    fun theReadOnlyBoardIsOneStopThatListsTheWholePosition() {
        compose.setContent { ChessAnalyzerTheme { ChessBoard(board = BoardState.startingPosition()) } }
        val node = compose.onNodeWithContentDescription("Chess board. White: king e1, queen d1", substring = true)
        node.assertExists()
        val description = node.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
        assertTrue(description, "Black: king e8, queen d8" in description)
        // 16 pieces a side, listed once each: 15 separators per side.
        assertEquals(15, description.substringAfter("White: ").substringBefore(". Black").count { it == ',' })
    }

    @Test
    fun thePlayableBoardHasSixtyFourButtonSquaresThatSayWhatIsOnThem() {
        val tapped = ArrayList<Int>()
        compose.setContent {
            ChessAnalyzerTheme {
                ChessBoard(board = BoardState.startingPosition(), onSquareClick = { tapped += it })
            }
        }
        compose.onAllNodes(hasRole(Role.Button)).assertCountEquals(64)
        compose.onNodeWithContentDescription("White pawn on e2").assertExists()
        compose.onNodeWithContentDescription("Black king on e8").assertExists()
        compose.onNodeWithContentDescription("Square e4").assertExists()
        // TalkBack's double tap is the OnClick semantic action; it reaches the callback with that square.
        compose.onNodeWithContentDescription("White pawn on e2").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(algebraicToSquare("e2")), tapped)
    }

    @Test
    fun aSelectedSquareAndItsTargetsAreStatedInWords() {
        compose.setContent {
            ChessAnalyzerTheme {
                ChessBoard(
                    board = BoardState.startingPosition(),
                    selectedSquare = algebraicToSquare("e2"),
                    legalMoveTargets = setOf(algebraicToSquare("e3")!!, algebraicToSquare("e4")!!),
                    onSquareClick = {},
                )
            }
        }
        fun state(description: String) = compose.onNodeWithContentDescription(description)
            .fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
        assertEquals("selected", state("White pawn on e2"))
        assertEquals("possible move", state("Square e3"))
        assertEquals("possible move", state("Square e4"))
        assertEquals(null, state("White pawn on d2"))
    }

    @Test
    fun theBadgeOnTheBoardKeepsItsOwnDescription() {
        compose.setContent {
            ChessAnalyzerTheme {
                ChessBoard(
                    board = BoardState.startingPosition(),
                    badge = BoardBadge(algebraicToSquare("f3")!!, MoveClassification.BLUNDER, "Blunder on f3"),
                )
            }
        }
        compose.onNodeWithContentDescription("Blunder on f3").assertExists()
    }

    @Test
    fun aClassificationBadgeIsSilentUnlessItStandsAlone() {
        compose.setContent {
            ChessAnalyzerTheme {
                ClassificationBadge(MoveClassification.BLUNDER)
                ClassificationBadge(MoveClassification.BRILLIANT, contentDescription = "Brilliant move")
            }
        }
        // The glyph "??" is not read out (it would be "question mark question mark").
        compose.onNodeWithText("??").assertDoesNotExist()
        compose.onNodeWithContentDescription("Brilliant move").assertExists()
    }

    @Test
    fun theEvalBarSpeaksASentenceNotABareNumber() {
        compose.setContent { ChessAnalyzerTheme { EvalBar(evalCentipawns = 90) } }
        compose.onNodeWithContentDescription("Evaluation +0.9").assertExists()
    }

    @Test
    fun aMoveChipIsOneButtonWithASentenceAndASelectedState() {
        val moves = listOf(
            MoveRecord(ply = 1, san = "e4", classification = MoveClassification.BEST, evalCp = 20),
            MoveRecord(ply = 2, san = "e5", classification = MoveClassification.BLUNDER, evalCp = 90),
        )
        var picked = -1
        compose.setContent {
            ChessAnalyzerTheme { MoveList(moves = moves, selectedPly = 2, onMoveSelected = { picked = it }) }
        }
        val selected = compose.onNodeWithContentDescription("Move 1, e5, blunder", substring = true)
        selected.assertIsSelected()
        selected.assertHasClickAction()
        selected.assertHeightIsAtLeast(48.dp)
        selected.assert(hasRole(Role.Button))
        // The chip's inner texts (SAN, score, badge glyph) are not separate stops.
        compose.onNodeWithText("??").assertDoesNotExist()
        val other = compose.onNodeWithContentDescription("Move 1, e4, best", substring = true)
        other.performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, picked)
    }

    @Test
    fun onlyTheBoardsOwnNodesAreDescribedAsSquares() {
        compose.setContent { ChessAnalyzerTheme { ChessBoard(board = BoardState.startingPosition()) } }
        // A read-only board has no per-square stops.
        compose.onAllNodes(hasContentDescription("Square e4")).assertCountEquals(0)
    }
}
