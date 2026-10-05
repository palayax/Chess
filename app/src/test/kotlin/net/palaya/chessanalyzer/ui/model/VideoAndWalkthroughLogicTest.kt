package net.palaya.chessanalyzer.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Video speed cycle (U7) and walkthrough step labelling (U8). */
class VideoAndWalkthroughLogicTest {

    // ---- U7: the speed cycle ----

    @Test
    fun theSpeedCyclesOneAndAQuarterAndAHalfThenBackToOne() {
        assertEquals(listOf(1f, 1.25f, 1.5f), PLAYBACK_SPEEDS)
        assertEquals(1.25f, nextPlaybackSpeed(1f))
        assertEquals(1.5f, nextPlaybackSpeed(1.25f))
        assertEquals(1f, nextPlaybackSpeed(1.5f))
    }

    @Test
    fun aSpeedOutsideTheCycleRestartsAtOne() {
        assertEquals(1f, nextPlaybackSpeed(0.75f))
        assertEquals(1f, nextPlaybackSpeed(2f))
    }

    @Test
    fun theSpeedNumberHasNoTrailingZerosAndAlwaysADot() {
        assertEquals("1", playbackSpeedNumber(1f))
        assertEquals("1.25", playbackSpeedNumber(1.25f))
        assertEquals("1.5", playbackSpeedNumber(1.5f))
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("1.25", playbackSpeedNumber(1.25f))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    // ---- U8: the step label ----

    private val whiteToMove = "r1bqkb1r/pppp1ppp/2n2n2/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4"
    private val blackToMove = "r1bqkb1r/pppp1ppp/2n2n2/4p3/2B1P3/3P1N2/PPP2PPP/RNBQK2R b KQkq - 0 7"

    @Test
    fun aWhiteStartNumbersTheFirstMoveFromTheFenAndAlternatesColours() {
        assertEquals(WalkthroughMove(4, true, "O-O"), walkthroughMove(whiteToMove, 1, "O-O"))
        assertEquals(WalkthroughMove(4, false, "Nxe4"), walkthroughMove(whiteToMove, 2, "Nxe4"))
        assertEquals(WalkthroughMove(5, true, "Bxf7+"), walkthroughMove(whiteToMove, 3, "Bxf7+"))
    }

    @Test
    fun aBlackStartIsBlacksMoveOnTheSameNumberThenWhiteTakesTheNext() {
        assertEquals(WalkthroughMove(7, false, "Bxa6"), walkthroughMove(blackToMove, 1, "Bxa6"))
        assertEquals(WalkthroughMove(8, true, "Nf3"), walkthroughMove(blackToMove, 2, "Nf3"))
        assertEquals(WalkthroughMove(8, false, "d6"), walkthroughMove(blackToMove, 3, "d6"))
    }

    @Test
    fun aMalformedFenIsTreatedAsTheStartOfAGame() {
        assertEquals(WalkthroughMove(1, true, "e4"), walkthroughMove("", 1, "e4"))
        assertEquals(WalkthroughMove(1, false, "e5"), walkthroughMove("garbage", 2, "e5"))
    }

    @Test
    fun theButtonAdvancesUntilTheLastStepThenReadsDone() {
        val total = 5
        for (step in 0 until total) assertFalse("step $step", walkthroughButtonIsDone(step, total))
        assertTrue(walkthroughButtonIsDone(total, total))
        assertEquals(1, walkthroughNextStep(0, total))
        assertEquals(total, walkthroughNextStep(total - 1, total))
        // Never past the end.
        assertEquals(total, walkthroughNextStep(total, total))
    }

    @Test
    fun aWalkthroughWithNoMovesIsDoneImmediately() {
        assertTrue(walkthroughButtonIsDone(0, 0))
    }
}
