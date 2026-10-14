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
    fun autosSecondPassIsPartOfTheWorkLeft() {
        // 6 positions searched at 0.6 M nodes/s; 61 Standard positions and 20 deep ones left.
        val ms = estimateAnalysisRemainingMs(
            6, 67, runNodes = 6_000_000, runMs = 10 * second, runPositions = 6, typicalNodes = 3_450_000,
            deepLeft = 20, deepTypicalNodes = AutoDepthBudget.TYPICAL_DEEPENED_NODES,
        )
        assertEquals(Math.round((61 * 3_450_000.0 + 20 * AutoDepthBudget.TYPICAL_DEEPENED_NODES) * (10_000.0 / 6_000_000)), ms)
        // The positions left of the second pass: the expected share before it, the picked ones not done during it.
        assertEquals(20, deepPositionsLeft(progress(30, nodes = 1, ms = 1).copy(deepPassExpected = 20)))
        assertEquals(7, deepPositionsLeft(progress(67, nodes = 1, ms = 1).copy(deepPassDone = 5, deepPassTotal = 12, deepPassExpected = 20)))
        assertEquals(0, deepPositionsLeft(progress(30, nodes = 1, ms = 1)))
    }

    @Test
    fun anAutoRunKeepsAnEstimateAfterItsFirstPassAndHidesItOnlyWhenTheSecondPassIsDone() {
        val tracker = AnalysisTimeLeftTracker(typicalNodes = 6_000_000, deepTypicalNodes = 12_000_000)
        for (done in 0..66) tracker.onProgress(progress(done, nodes = done * 6_000_000L, ms = done * 6 * second).copy(deepPassExpected = 20))
        // First pass done, 20 deep positions expected: 20 x 12 s = 4 min, not hidden.
        val afterFirst = tracker.onProgress(progress(67, nodes = 402_000_000, ms = 402 * second).copy(deepPassExpected = 20))
        assertTrue("shown $afterFirst", afterFirst != ExportTimeLeft.Hidden)
        // The second pass counts on: done 10 of 20.
        val mid = tracker.onProgress(progress(67, positions = 77, nodes = 522_000_000, ms = 522 * second).copy(deepPassDone = 10, deepPassTotal = 20))
        assertTrue("shown $mid", mid != ExportTimeLeft.Hidden)
        assertEquals(
            ExportTimeLeft.Hidden,
            tracker.onProgress(progress(67, positions = 87, nodes = 642_000_000, ms = 642 * second).copy(deepPassDone = 20, deepPassTotal = 20)),
        )
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

    // ---- Elo levels (spec §8.4, §8.6) ----

    @Test
    fun aLevelEstimateIsBoundedByTheEngineBudgetLeft() {
        // 10 positions done of 81; 1 ms per node, level nodes 180,000: 71 x 180 s would be 12,780 s, the budget left decides.
        val bounded = estimateAnalysisRemainingMs(
            10, 81, runNodes = 1_800_000, runMs = 1_800_000, runPositions = 10, typicalNodes = 180_000, budgetLeftMs = 240_000,
        )
        assertEquals(240_000L, bounded)
        // A fast phone: 0.001 ms per node: 71 x 180,000 x 0.001 = 12,780 ms, under the budget, so the nodes decide.
        val fast = estimateAnalysisRemainingMs(
            10, 81, runNodes = 1_800_000, runMs = 1_800, runPositions = 10, typicalNodes = 180_000, budgetLeftMs = 240_000,
        )
        assertEquals(12_780L, fast)
        // Still hidden before enough positions were measured (a level that has a node count).
        assertNull(estimateAnalysisRemainingMs(3, 81, runNodes = 540_000, runMs = 540, runPositions = 3, typicalNodes = 180_000, budgetLeftMs = 240_000))
    }

    @Test
    fun maxIsTheBudgetLeftFromTheFirstPosition() {
        assertEquals(
            250_000L,
            estimateAnalysisRemainingMs(1, 81, runNodes = 0, runMs = 0, runPositions = 0, typicalNodes = 0, budgetLeftMs = 250_000),
        )
        val tracker = AnalysisTimeLeftTracker(typicalNodes = 0, levelRun = true)
        val shown = tracker.onProgress(progress(1, nodes = 1_000_000, ms = 4_000).copy(engineBudgetMs = 300_000))
        assertEquals(ExportTimeLeft.Minutes(5), shown)
    }

    @Test
    fun theSpeedIsTheLastEightPositionsNotTheWholeRun() {
        val r = RecentSpeed()
        assertNull(r.msPerNode())
        // 20 positions at 1 ms per 1000 nodes (0.001), then the phone throttles to 0.004.
        var nodes = 0L
        var ms = 0L
        for (p in 1..20) { nodes += 100_000; ms += 100; r.add(p, nodes, ms) }
        assertEquals(0.001, r.msPerNode()!!, 1e-9)
        for (p in 21..40) { nodes += 100_000; ms += 400; r.add(p, nodes, ms) }
        // The whole-run average would be 0.0025; the window of 8 sees only the slow phone.
        assertEquals(0.004, r.msPerNode()!!, 1e-9)
        assertEquals(0.0025, ms.toDouble() / nodes, 1e-9)
        // A repeated position count adds nothing.
        r.add(40, nodes + 1, ms + 1)
        assertEquals(0.004, r.msPerNode()!!, 1e-9)
    }

    @Test
    fun theEstimateUsesTheRecentSpeedWhenGiven() {
        val whole = estimateAnalysisRemainingMs(10, 81, runNodes = 1_000_000, runMs = 1_000, runPositions = 10, typicalNodes = 100_000)!!
        val recent = estimateAnalysisRemainingMs(
            10, 81, runNodes = 1_000_000, runMs = 1_000, runPositions = 10, typicalNodes = 100_000, recentMsPerNode = 0.004,
        )!!
        assertEquals(Math.round(71 * 100_000.0 * 0.001), whole)
        assertEquals(Math.round(71 * 100_000.0 * 0.004), recent)
    }

    @Test
    fun aLevelTrackerFollowsAThrottlingPhone() {
        val tracker = AnalysisTimeLeftTracker(typicalNodes = 100_000, levelRun = true)
        var nodes = 0L
        var ms = 0L
        var shown: ExportTimeLeft = ExportTimeLeft.Hidden
        // Fast first: 0.1 s per position (0.001 ms per node)...
        for (p in 1..20) { nodes += 100_000; ms += 100; shown = tracker.onProgress(progress(p, total = 100, nodes = nodes, ms = ms).copy(engineBudgetMs = 300_000)) }
        val fast = shown
        // ...then 4x slower: the shown time rises (it is only allowed to when plainly wrong, here 4x).
        for (p in 21..40) { nodes += 100_000; ms += 400; shown = tracker.onProgress(progress(p, total = 100, nodes = nodes, ms = ms).copy(engineBudgetMs = 300_000)) }
        assertTrue("$fast then $shown", fast is ExportTimeLeft.Minutes || fast == ExportTimeLeft.LessThanMinute)
        assertTrue("$shown", shown is ExportTimeLeft.Minutes || shown == ExportTimeLeft.LessThanMinute)
    }
}
