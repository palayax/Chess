package net.palaya.chessanalyzer.ui.model

import net.palaya.chessanalyzer.ui.board.BOARD_BADGE_FRACTION
import net.palaya.chessanalyzer.ui.board.BoardOrientation
import net.palaya.chessanalyzer.ui.board.squareToDisplayCell
import net.palaya.chessanalyzer.ui.theme.MoveClassification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Board polish R2: the on-board badge (B1) and "Next key moment" (B2). */
class BoardBadgeAndKeyMomentTest {

    private val f3 = algebraicToSquare("f3")!!

    // ---- B1: when a badge is drawn ----

    @Test
    fun everyHighlightTierClassGetsABadgeOnTheDestinationSquare() {
        val highlighted = MoveClassification.entries.filter { it.isHighlight }
        assertTrue(highlighted.isNotEmpty())
        for (cls in highlighted) {
            val spec = boardBadgeFor(cls, f3)
            assertNotNull("no badge for $cls", spec)
            assertEquals(f3, spec!!.square)
            assertEquals(cls, spec.classification)
        }
    }

    @Test
    fun everyQuietTierClassGetsNoBadge() {
        for (cls in MoveClassification.entries.filter { !it.isHighlight }) {
            assertNull("badge drawn for quiet class $cls", boardBadgeFor(cls, f3))
        }
    }

    @Test
    fun noClassificationOrNoDestinationMeansNoBadge() {
        // The start position has neither.
        assertNull(boardBadgeFor(null, f3))
        assertNull(boardBadgeFor(MoveClassification.BLUNDER, null))
        assertNull(boardBadgeFor(null, null))
    }

    @Test
    fun theBadgeSquareIsAnAbsoluteSquareSoFlippingMovesItWithTheSquare() {
        // f3 is file 5, rank 3 (row 5 from the top). White at the bottom: column 5, row 5.
        // Black at the bottom the whole board is rotated 180 degrees: column 2, row 2.
        val spec = boardBadgeFor(MoveClassification.MISTAKE, f3)!!
        assertEquals(5 to 5, squareToDisplayCell(spec.square, BoardOrientation.WHITE_DOWN))
        assertEquals(2 to 2, squareToDisplayCell(spec.square, BoardOrientation.BLACK_DOWN))
        // a1 and h8 swap corners.
        assertEquals(0 to 7, squareToDisplayCell(algebraicToSquare("a1")!!, BoardOrientation.WHITE_DOWN))
        assertEquals(7 to 0, squareToDisplayCell(algebraicToSquare("a1")!!, BoardOrientation.BLACK_DOWN))
    }

    @Test
    fun theBadgeIsAboutAQuarterOfASquare() {
        assertTrue(BOARD_BADGE_FRACTION in 0.25f..0.30f)
    }

    // ---- B2: the next key moment ----

    @Test
    fun nextKeyMomentIsTheFirstLaterPlyAndNeverGoesBackwards() {
        val plies = listOf(15, 4, 10) // any order
        assertEquals(4, nextKeyMomentPly(plies, 0))
        assertEquals(10, nextKeyMomentPly(plies, 4))
        assertEquals(15, nextKeyMomentPly(plies, 10))
        // Between two moments it goes to the later one, not the earlier.
        assertEquals(15, nextKeyMomentPly(plies, 12))
    }

    @Test
    fun atTheLastKeyMomentThereIsNoNextOne() {
        assertNull(nextKeyMomentPly(listOf(4, 10, 15), 15))
        assertNull(nextKeyMomentPly(listOf(4, 10, 15), 30))
        assertNull(nextKeyMomentPly(emptyList(), 0))
    }

    private fun moment(ply: Int, cls: MoveClassification) =
        KeyMoment(ply = ply, moveNumber = (ply + 1) / 2, san = "x$ply", classification = cls, description = "d$ply")

    @Test
    fun theKeyMomentPliesAreTheOnesTheSummaryListsInGameOrder() {
        val report = PlaceholderData.sampleReport.copy(
            userColor = PieceColor.WHITE,
            keyMoments = listOf(
                moment(15, MoveClassification.BLUNDER), // White
                moment(10, MoveClassification.BRILLIANT), // Black
                moment(4, MoveClassification.MISTAKE), // Black
                moment(7, MoveClassification.INACCURACY), // not a Summary class
            ),
        )
        // Yours and the opponent's, merged, sorted; the inaccuracy never appears on the Summary, so
        // "Next key moment" must not stop on it either.
        assertEquals(listOf(4, 10, 15), report.keyMomentPlies)
    }
}
