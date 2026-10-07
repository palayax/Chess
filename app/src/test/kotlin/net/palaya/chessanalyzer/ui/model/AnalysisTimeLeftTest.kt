package net.palaya.chessanalyzer.ui.model

import net.palaya.chessanalyzer.video.ExportTimeLeft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Analysing screen's time bar (F1, spec §8.4): elapsed clock, measured time left, and "Thinking deeper". */
class AnalysisTimeLeftTest {

    private val second = 1_000L
    private val deepTypical = SearchBudget.DEEP.typicalNodes

    @Test
    fun theEstimateIsHiddenUntilSixPositionsWereSearchedInThisRun() {
        assertNull(estimateAnalysisRemainingMs(5, 67, runNodes = 5_000_000, runMs = 8 * second, runPositions = 5, typicalNodes = deepTypical))
        assertNotNull(estimateAnalysisRemainingMs(6, 67, runNodes = 6_000_000, runMs = 10 * second, runPositions = 6, typicalNodes = deepTypical))
        assertNull("nothing measured", estimateAnalysisRemainingMs(6, 67, 0, 0, 6, deepTypical))
    }

    @Test
    fun theEstimateIsPositionsLeftTimesTypicalNodesTimesThisDevicesTimePerNode() {
        // 6 cheap opening positions: 6 M nodes in 10 s (0.6 M nodes/s). 61 left at a typical 10.3 M
        // nodes each = 628.3 M nodes = 1047 s, although the six so far took under 2 s each.
        val ms = estimateAnalysisRemainingMs(6, 67, runNodes = 6_000_000, runMs = 10 * second, runPositions = 6, typicalNodes = 10_300_000)
        assertEquals(Math.round(61 * 10_300_000.0 * (10_000.0 / 6_000_000)), ms)
    }

    @Test
    fun theGamesCheapOpeningDoesNotPullTheEstimateDown() {
        // Ten opening positions of 0.3 M nodes each: the speed is measured from them, the cost of the
        // positions still to come is the calibrated typical one.
        val cheap = estimateAnalysisRemainingMs(10, 67, runNodes = 3_000_000, runMs = 5 * second, runPositions = 10, typicalNodes = 10_300_000)
        assertEquals(Math.round(57 * 10_300_000.0 * (5_000.0 / 3_000_000)), cheap)
    }

    @Test
    fun aResumedRunMeasuresOnlyItsOwnSearches() {
        // 40 positions came from the checkpoint; 6 were searched now and give the speed; 21 are left.
        val ms = estimateAnalysisRemainingMs(46, 67, runNodes = 60_000_000, runMs = 100 * second, runPositions = 6, typicalNodes = 10_300_000)
        assertEquals(Math.round(21 * 10_300_000.0 * (100_000.0 / 60_000_000)), ms)
    }

    private fun progress(done: Int, total: Int = 67, positions: Int = done, nodes: Long, ms: Long) = AnalysisProgress(
        phase = AnalysisPhase.ANALYZING_MOVES,
        currentMoveIndex = done,
        totalMoves = total,
        runNodes = nodes,
        runSearchMs = ms,
        runPositionsSearched = positions,
    )

    @Test
    fun theTrackerShowsMinutesCountsDownAndHidesAtTheEnd() {
        val tracker = AnalysisTimeLeftTracker(typicalNodes = 6_000_000)
        // 1 M nodes per second, 6 M per position: 6 s per position.
        for (done in 0..5) assertEquals(ExportTimeLeft.Hidden, tracker.onProgress(progress(done, nodes = done * 6_000_000L, ms = done * 6 * second)))
        // 61 left x 6 s = 366 s -> 7 min (rounded up).
        assertEquals(ExportTimeLeft.Minutes(7), tracker.onProgress(progress(6, nodes = 36_000_000, ms = 36 * second)))
        var shown: ExportTimeLeft = ExportTimeLeft.Hidden
        for (done in 7..60) shown = tracker.onProgress(progress(done, nodes = done * 6_000_000L, ms = done * 6 * second))
        assertEquals(ExportTimeLeft.LessThanMinute, shown)
        assertEquals(ExportTimeLeft.Hidden, tracker.onProgress(progress(67, nodes = 402_000_000, ms = 402 * second)))
    }

    @Test
    fun oneSlowPositionDoesNotMakeTheNumberJumpUp() {
        val tracker = AnalysisTimeLeftTracker(typicalNodes = 6_000_000)
        var shown: ExportTimeLeft = ExportTimeLeft.Hidden
        for (done in 1..20) shown = tracker.onProgress(progress(done, nodes = done * 6_000_000L, ms = done * 6 * second))
        val before = (shown as ExportTimeLeft.Minutes).minutes
        // The game01 kind of position: the whole 45 M budget (45 s at this device's speed).
        shown = tracker.onProgress(progress(21, nodes = 120_000_000 + 45_000_000, ms = 120 * second + 45 * second))
        assertTrue("was $before, now $shown", shown is ExportTimeLeft.Minutes && shown.minutes <= before)
    }

    @Test
    fun updatesWithinOnePositionAreIgnored() {
        val tracker = AnalysisTimeLeftTracker(typicalNodes = 6_000_000)
        val first = tracker.onProgress(progress(6, nodes = 36_000_000, ms = 36 * second))
        // "Thinking deeper" updates repeat the same position count with other numbers: no recompute.
        assertEquals(first, tracker.onProgress(progress(6, nodes = 36_000_000, ms = 999 * second)))
        assertEquals(first, tracker.onProgress(progress(6, nodes = 36_000_000, ms = 36 * second).copy(phase = AnalysisPhase.PREPARING_ENGINE)))
    }

    @Test
    fun elapsedTimeReadsLikeAClock() {
        assertEquals("0:00", formatElapsed(0))
        assertEquals("0:42", formatElapsed(42_900))
        assertEquals("12:05", formatElapsed((12 * 60 + 5) * second))
        assertEquals("1:02:03", formatElapsed((3600 + 2 * 60 + 3) * second))
        assertEquals("0:00", formatElapsed(-5))
    }

    private fun searching(startedAt: Long, depth: Int, target: Int = 18) = AnalysisProgress(
        phase = AnalysisPhase.ANALYZING_MOVES,
        currentMoveIndex = 45,
        totalMoves = 67,
        searchDepth = depth,
        targetDepth = target,
        positionStartedAtMs = startedAt,
    )

    @Test
    fun thinkingDeeperShowsOnlyAfterThreeSecondsOnOnePosition() {
        val p = searching(startedAt = 10_000L, depth = 15)
        assertNull(thinkingDeeper(p, 12_999L))
        assertEquals(15 to 18, thinkingDeeper(p, 13_000L))
    }

    @Test
    fun thinkingDeeperNeedsARealSearch() {
        assertNull("no depth yet", thinkingDeeper(searching(10_000L, depth = 0), 60_000L))
        assertNull("between positions", thinkingDeeper(searching(0L, depth = 15), 60_000L))
        assertNull("not analysing", thinkingDeeper(searching(10_000L, 15).copy(phase = AnalysisPhase.PREPARING_ENGINE), 60_000L))
        assertEquals("never past the target", 18 to 18, thinkingDeeper(searching(10_000L, depth = 19), 60_000L))
    }
}
