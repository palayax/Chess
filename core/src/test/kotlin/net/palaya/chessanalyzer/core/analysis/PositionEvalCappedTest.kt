package net.palaya.chessanalyzer.core.analysis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `isCapped` for fixed-depth searches (spec §8.1) and Elo-level searches stopped by the clock (spec §8.6). */
class PositionEvalCappedTest {
    private fun eval(depth: Int, requested: Int?, timeCapped: Boolean = false) =
        PositionEval(fen = "x", lines = emptyList(), depth = depth, requestedDepth = requested, timeCapped = timeCapped)

    @Test
    fun aFixedDepthSearchIsCappedBelowItsRequestedDepth() {
        assertTrue(eval(14, 18).isCapped)
        assertFalse(eval(18, 18).isCapped)
        assertFalse(eval(20, 18).isCapped)
    }

    @Test
    fun aLevelSearchHasNoRequestedDepthAndIsCappedOnlyWhenTheClockStoppedIt() {
        assertFalse(eval(17, null).isCapped)
        assertTrue(eval(17, null, timeCapped = true).isCapped)
        assertFalse(eval(60, null).isCapped)
    }

    @Test
    fun anOldEvalWithoutTheFlagIsNotTimeCapped() {
        assertFalse(PositionEval(fen = "x", lines = emptyList(), depth = 5).timeCapped)
    }
}
