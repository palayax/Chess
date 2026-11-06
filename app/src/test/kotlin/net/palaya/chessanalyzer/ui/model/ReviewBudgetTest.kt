package net.palaya.chessanalyzer.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** EL2: the whole-review deadline and the upper bounds shown before anything is measured (spec §8.4, §8.6). */
class ReviewBudgetTest {

    @Test
    fun theConstantsAreTheSpecifiedOnes() {
        assertEquals(600_000L, REVIEW_TOTAL_BUDGET_MS)
        assertEquals(90_000L, WORDING_STAGE_BUDGET_MS)
        assertEquals(8_000L, MAX_LEVEL_MOVETIME_CEILING_MS)
        // 250,000 nodes per second.
        assertEquals(250_000.0, 1000.0 / ENGINE_PRIOR_MS_PER_NODE, 1e-6)
        // The engine budget, the two wording stages and the reserve leave a recording stage inside the deadline.
        assertTrue(GAME_ENGINE_BUDGET_MS + 2 * WORDING_STAGE_BUDGET_MS + RECORDING_RESERVE_MS <= REVIEW_TOTAL_BUDGET_MS)
    }

    @Test
    fun timeToTheDeadlineNeverGoesNegative() {
        assertEquals(600_000L, timeToDeadlineMs(0L))
        assertEquals(100_000L, timeToDeadlineMs(500_000L))
        assertEquals(0L, timeToDeadlineMs(600_000L))
        assertEquals(0L, timeToDeadlineMs(900_000L))
    }

    @Test
    fun aWordingStageGetsNinetySecondsOrWhatTheDeadlineLeavesMinusTheReserve() {
        val reserve = reserveAfterWordingMs(narrationWritePlanned = true, recordingPlanned = true)
        assertEquals(NARRATION_WRITE_RESERVE_MS + RECORDING_RESERVE_MS, reserve)
        // Early: the 90 s cap.
        assertEquals(90_000L, wordingStageBudgetMs(elapsedMs = 120_000L, reserveMs = reserve))
        // A late start: the time to the deadline minus the reserve.
        assertEquals(40_000L, wordingStageBudgetMs(elapsedMs = 430_000L, reserveMs = reserve))
        // No time left for it: skipped (the original texts stay).
        assertEquals(0L, wordingStageBudgetMs(elapsedMs = 500_000L, reserveMs = reserve))
        assertEquals(0L, wordingStageBudgetMs(elapsedMs = 700_000L, reserveMs = 0L))
        // Nothing after it: only the deadline limits it.
        assertEquals(30_000L, wordingStageBudgetMs(elapsedMs = 570_000L, reserveMs = reserveAfterWordingMs(false, false)))
        assertEquals(0L, reserveAfterWordingMs(false, false))
        assertEquals(RECORDING_RESERVE_MS, reserveAfterWordingMs(false, true))
    }

    @Test
    fun twoWordingStagesAfterAFullEngineStageStillLeaveTheRecordingItsReserve() {
        var elapsed = GAME_ENGINE_BUDGET_MS + 20_000L // a slow phone: the engine used its whole budget plus start-up
        val first = wordingStageBudgetMs(elapsed, reserveAfterWordingMs(true, true))
        elapsed += first
        val second = wordingStageBudgetMs(elapsed, reserveAfterWordingMs(false, true))
        elapsed += second
        assertTrue(timeToDeadlineMs(elapsed) >= RECORDING_RESERVE_MS)
    }

    @Test
    fun theEngineBoundUsesTheSlowPhonePriorBeforeAnythingIsMeasured() {
        // 41 positions x 20,000 nodes x (1/250 ms) = 3,280 ms.
        assertEquals(TimeEstimate(3_280L, true), levelEngineEstimate(EngineLevel.L2500, 41, null))
        // 81 x 600,000 x 0.004 = 194,400 ms: under the budget, still only a bound.
        assertEquals(TimeEstimate(194_400L, true), levelEngineEstimate(EngineLevel.L3100, 81, null))
        // The budget cuts a big one.
        assertEquals(TimeEstimate(GAME_ENGINE_BUDGET_MS, true), levelEngineEstimate(EngineLevel.L3100, 200, null))
        // Measured: a plain estimate with the measured speed.
        assertEquals(TimeEstimate(Math.round(81 * 600_000.0 * 0.001), false), levelEngineEstimate(EngineLevel.L3100, 81, 0.001))
        // Cached: ready at once, exactly.
        assertEquals(TimeEstimate(0L, false), levelEngineEstimate(EngineLevel.MAX, 81, null, cached = true))
    }

    @Test
    fun maxIsBoundedByItsCeilingPerPositionAndTheBudget() {
        assertEquals(TimeEstimate(20 * MAX_LEVEL_MOVETIME_CEILING_MS, true), levelEngineEstimate(EngineLevel.MAX, 20, null))
        assertEquals(TimeEstimate(GAME_ENGINE_BUDGET_MS, true), levelEngineEstimate(EngineLevel.MAX, 81, 0.001))
        assertEquals(0L, levelEngineEstimate(EngineLevel.MAX, 0, null).ms)
    }

    @Test
    fun theWholeReviewIsTheSumCappedAtTheDeadlineAndABoundWhenAnyPartIs() {
        val measured = TimeEstimate(60_000L, false)
        assertEquals(TimeEstimate(60_000L, false), wholeReviewEstimate(measured, emptyList()))
        assertEquals(TimeEstimate(100_000L, false), wholeReviewEstimate(measured, listOf(TimeEstimate(40_000L, false))))
        assertEquals(TimeEstimate(150_000L, true), wholeReviewEstimate(measured, listOf(TimeEstimate(90_000L, true))))
        assertEquals(TimeEstimate(100_000L, true), wholeReviewEstimate(TimeEstimate(60_000L, true), listOf(TimeEstimate(40_000L, false))))
        // Above the deadline: cut to it, and then it is a bound whatever the parts were.
        val cut = wholeReviewEstimate(measured, listOf(TimeEstimate(590_000L, false)))
        assertEquals(REVIEW_TOTAL_BUDGET_MS, cut.ms)
        assertTrue(cut.upperBound)
        assertFalse(wholeReviewEstimate(TimeEstimate(0L, false), emptyList()).upperBound)
    }
}
