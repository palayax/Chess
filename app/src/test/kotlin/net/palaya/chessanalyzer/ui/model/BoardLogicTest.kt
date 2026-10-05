package net.palaya.chessanalyzer.ui.model

import net.palaya.chessanalyzer.ui.board.BoardOrientation
import net.palaya.chessanalyzer.ui.theme.MoveClassification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure logic behind the Board (UX step U6): orientation, highlight tiers, chip label, cost line. */
class BoardLogicTest {

    private fun move(
        ply: Int,
        san: String = "e4",
        cls: MoveClassification? = null,
        evalCp: Int? = 0,
        before: Int? = 0,
        mate: Int? = null,
        mateBefore: Int? = null,
        best: String? = null,
        annotation: String? = null,
    ) = MoveRecord(
        ply = ply,
        san = san,
        classification = cls,
        evalCp = evalCp,
        evalBeforeCp = before,
        mateInMoves = mate,
        isMateScore = mate != null,
        mateInBefore = mateBefore,
        bestMoveSan = best,
        annotation = annotation,
    )

    // ---- Orientation ----

    @Test
    fun theBoardOpensFromBlacksSideOnlyWhenTheUserPlayedBlack() {
        assertEquals(BoardOrientation.BLACK_DOWN, defaultBoardOrientation(PieceColor.BLACK))
        assertEquals(BoardOrientation.WHITE_DOWN, defaultBoardOrientation(PieceColor.WHITE))
        // Unknown side and "Not me" both arrive as null: White's side, as before.
        assertEquals(BoardOrientation.WHITE_DOWN, defaultBoardOrientation(null))
    }

    @Test
    fun theManualFlipInvertsWhateverTheDefaultIs() {
        assertEquals(BoardOrientation.WHITE_DOWN, BoardOrientation.WHITE_DOWN.flippedIf(false))
        assertEquals(BoardOrientation.BLACK_DOWN, BoardOrientation.WHITE_DOWN.flippedIf(true))
        assertEquals(BoardOrientation.BLACK_DOWN, BoardOrientation.BLACK_DOWN.flippedIf(false))
        assertEquals(BoardOrientation.WHITE_DOWN, BoardOrientation.BLACK_DOWN.flippedIf(true))
    }

    // ---- Highlight tiers ----

    @Test
    fun onlyBrilliantAndTheFourMistakeClassesAreHighlighted() {
        val highlighted = MoveClassification.entries.filter { it.isHighlight }.toSet()
        assertEquals(
            setOf(
                MoveClassification.BRILLIANT,
                MoveClassification.INACCURACY, MoveClassification.MISTAKE,
                MoveClassification.MISS, MoveClassification.BLUNDER,
            ),
            highlighted,
        )
        for (quiet in listOf(
            MoveClassification.GREAT, MoveClassification.BEST, MoveClassification.EXCELLENT, MoveClassification.GOOD,
            MoveClassification.BOOK, MoveClassification.FORCED,
        )) {
            assertFalse("$quiet must be quiet", quiet.isHighlight)
        }
    }

    @Test
    fun everyMistakeClassIsHighlightedAndNoGoodMoveIsAMistake() {
        for (c in MoveClassification.entries) {
            if (c.isMistake) assertTrue("$c is a mistake so it must be highlighted", c.isHighlight)
        }
        assertEquals(
            setOf(
                MoveClassification.INACCURACY, MoveClassification.MISTAKE,
                MoveClassification.MISS, MoveClassification.BLUNDER,
            ),
            MoveClassification.entries.filter { it.isMistake }.toSet(),
        )
    }

    @Test
    fun theGlyphsStayDistinctEnoughToCarryTheMeaningWithoutColour() {
        // Colour is never the only signal: the four mistake classes and the brilliancy keep their glyphs.
        assertEquals("!!", MoveClassification.BRILLIANT.glyph)
        assertEquals("?!", MoveClassification.INACCURACY.glyph)
        assertEquals("?", MoveClassification.MISTAKE.glyph)
        assertEquals("??", MoveClassification.BLUNDER.glyph)
    }

    // ---- Chip label ----

    @Test
    fun aChipCarriesTheSanAndTheScoreAndNumbersOnlyWhiteMoves() {
        val white = chipLabel(move(ply = 7, san = "Nf3", evalCp = 34))
        assertEquals(ChipLabel(number = "4.", san = "Nf3", score = "+0.3"), white)
        val black = chipLabel(move(ply = 8, san = "Nc6", evalCp = -12))
        assertNull(black.number)
        assertEquals("-0.1", black.score)
    }

    @Test
    fun aChipForAMatePositionReadsThroughTheSharedFormatter() {
        assertEquals("M3", chipLabel(move(ply = 31, evalCp = 10000, mate = 3)).score)
        assertEquals("#", chipLabel(move(ply = 33, evalCp = 10000, mate = 0)).score)
    }

    // ---- Cost line ----

    @Test
    fun theCostIsRoundedToHalfPawnsFromTheMoversPointOfView() {
        // White moves and the eval drops 190 cp: about 2 pawns (4 halves).
        assertEquals(4, pawnCostHalves(move(ply = 1, cls = MoveClassification.MISTAKE, evalCp = 10, before = 200)))
        // Black moves and the eval rises 60 cp: it hurt Black by about half a pawn.
        assertEquals(1, pawnCostHalves(move(ply = 2, cls = MoveClassification.INACCURACY, evalCp = 60, before = 0)))
        // 150 cp is exactly three halves: "1.5 pawns".
        assertEquals(3, pawnCostHalves(move(ply = 3, cls = MoveClassification.MISTAKE, evalCp = -50, before = 100)))
    }

    @Test
    fun noCostWhenTheMoveGainedOrLostNothingWorthSaying() {
        // Moving in the mover's favour.
        assertNull(pawnCostHalves(move(ply = 1, evalCp = 100, before = 0)))
        // Under a quarter pawn rounds to nothing.
        assertNull(pawnCostHalves(move(ply = 1, evalCp = -20, before = 0)))
        assertNull(pawnCostHalves(move(ply = 1, evalCp = null, before = 0)))
    }

    @Test
    fun noCostAcrossAMateBoundaryEvenWhenTheScoresAreHuge() {
        assertNull(pawnCostHalves(move(ply = 1, cls = MoveClassification.BLUNDER, evalCp = -10000, before = 300, mate = -2)))
        assertNull(pawnCostHalves(move(ply = 2, cls = MoveClassification.BLUNDER, evalCp = 200, before = 10000, mateBefore = 3)))
    }

    // ---- Card copy ----

    @Test
    fun betterWasOnlyForMistakeClassesWhenTheBestMoveDiffers() {
        assertEquals("Nf6", betterMoveToShow(move(ply = 2, san = "g6", cls = MoveClassification.MISTAKE, best = "Nf6")))
        assertNull(betterMoveToShow(move(ply = 2, san = "Nf6", cls = MoveClassification.MISTAKE, best = "Nf6")))
        assertNull(betterMoveToShow(move(ply = 2, san = "g6", cls = MoveClassification.GOOD, best = "Nf6")))
        assertNull(betterMoveToShow(move(ply = 2, san = "g6", cls = MoveClassification.MISTAKE, best = null)))
        assertNull(betterMoveToShow(move(ply = 2, san = "g6", cls = MoveClassification.MISTAKE, best = " ")))
    }

    @Test
    fun noBetterWasLineWhenTheExplanationAlreadySaysIt() {
        val said = move(ply = 2, san = "cxb5", cls = MoveClassification.BLUNDER, best = "h5", annotation = "This drops a pawn. Better was h5, keeping material level.")
        assertNull(betterMoveToShow(said))
        val unsaid = move(ply = 2, san = "cxb5", cls = MoveClassification.BLUNDER, best = "h5", annotation = "This drops a pawn.")
        assertEquals("h5", betterMoveToShow(unsaid))
    }

    @Test
    fun theShowMeButtonSaysMissedOnlyForMistakeClasses() {
        assertTrue(showMeIsAboutAMiss(move(ply = 1, cls = MoveClassification.BLUNDER)))
        assertTrue(showMeIsAboutAMiss(move(ply = 1, cls = MoveClassification.MISS)))
        assertFalse(showMeIsAboutAMiss(move(ply = 1, cls = MoveClassification.BRILLIANT)))
        assertFalse(showMeIsAboutAMiss(move(ply = 1, cls = null)))
    }
}
