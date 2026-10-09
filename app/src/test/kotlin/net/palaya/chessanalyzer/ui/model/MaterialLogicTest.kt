package net.palaya.chessanalyzer.ui.model

import net.palaya.chessanalyzer.core.chess.PieceType as CorePieceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The per-side shaping of `core.analysis.MaterialBalance` for the Board's strips and the Summary (A4). */
class MaterialLogicTest {

    private val start = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

    @Test
    fun theStartPositionShowsNothingForEitherSide() {
        val v = materialViewOrNull(start)!!
        assertTrue(v.isLevel)
        for (side in listOf(v.white, v.black)) {
            assertTrue(side.taken.isEmpty())
            assertEquals(0, side.ahead)
        }
    }

    @Test
    fun theSideThatTookAPieceShowsItAndThePlusAndTheOtherShowsNothing() {
        // 1.e4 d5 2.exd5: White took a pawn and is a point up.
        val v = materialViewOrNull("rnbqkbnr/ppp1pppp/8/3P4/8/8/PPPP1PPP/RNBQKBNR b KQkq - 0 2")!!
        assertEquals(listOf(CorePieceType.PAWN), v.white.taken)
        assertEquals(1, v.white.ahead)
        assertTrue(v.black.taken.isEmpty())
        assertEquals(0, v.black.ahead)
        assertFalse(v.isLevel)
    }

    @Test
    fun takenPiecesAreTheOpponentsColour() {
        val v = materialViewOrNull("rnbqkbnr/ppp1pppp/8/3P4/8/8/PPPP1PPP/RNBQKBNR b KQkq - 0 2")!!
        assertEquals(PieceColor.BLACK, v.white.takenColor)
        assertEquals(PieceColor.WHITE, v.black.takenColor)
    }

    @Test
    fun aTradeLeavesBothSidesWithAPieceAndNoPlus() {
        // Each side has lost a knight.
        val v = materialViewOrNull("r1bqkb1r/pppppppp/8/8/8/8/PPPPPPPP/R1BQKB1R w KQkq - 0 1")!!
        assertEquals(listOf(CorePieceType.KNIGHT, CorePieceType.KNIGHT), v.white.taken)
        assertEquals(listOf(CorePieceType.KNIGHT, CorePieceType.KNIGHT), v.black.taken)
        assertTrue(v.isLevel)
        assertEquals(0, v.white.ahead)
        assertEquals(0, v.black.ahead)
    }

    @Test
    fun anUnreadableFenGivesNoViewInsteadOfACrash() {
        assertNull(materialViewOrNull(null))
        assertNull(materialViewOrNull(""))
        assertNull(materialViewOrNull("not a fen"))
        assertNull(materialViewOrNull("8/8/8/8/8/8/8/7X w - - 0 1"))
        assertNotNull(materialViewOrNull(start))
    }

    @Test
    fun takenPiecesGroupByKindMostValuableFirst() {
        val grouped = groupedTaken(
            listOf(CorePieceType.PAWN, CorePieceType.QUEEN, CorePieceType.PAWN, CorePieceType.KNIGHT, CorePieceType.PAWN),
        )
        assertEquals(
            listOf(CorePieceType.QUEEN to 1, CorePieceType.KNIGHT to 1, CorePieceType.PAWN to 3),
            grouped,
        )
        assertTrue(groupedTaken(emptyList()).isEmpty())
    }

    @Test
    fun theBottomStripBelongsToTheSideAtTheBottomOfTheBoard() {
        assertEquals(PieceColor.WHITE, bottomColor(whiteAtBottom = true))
        assertEquals(PieceColor.BLACK, bottomColor(whiteAtBottom = false))
    }
}
