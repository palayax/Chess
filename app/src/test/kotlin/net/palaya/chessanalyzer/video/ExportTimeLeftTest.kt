package net.palaya.chessanalyzer.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "about N min left" line on the export's narration step (R6a): hidden before two segments are
 * done, whole minutes rounded up, "less than a minute" at the end, measured from the segments so
 * far, and no wrong-looking jump when one segment is much slower than the rest.
 */
class ExportTimeLeftTest {

    private val second = 1_000L

    /** Samples for [segmentMs] per segment, starting at t=0 with `(0, 0)`. */
    private fun steady(segmentMs: Long, done: Int, startUpMs: Long = 0L): List<ProgressSample> =
        listOf(ProgressSample(0, 0L)) + (1..done).map { ProgressSample(it, startUpMs + it * segmentMs) }

    // ---- hidden before two segments ----

    @Test
    fun nothingIsShownBeforeTwoSegmentsAreDoneAndTheLineStartsWithTwoCleanMeasurements() {
        assertNull(estimateRemainingMs(emptyList(), 21))
        assertNull(estimateRemainingMs(steady(25 * second, 0), 21))
        // The spec's floor: fewer than two segments done is never enough.
        assertNull(estimateRemainingMs(steady(25 * second, 1), 21))
        // Two done is only one clean measurement (the first segment pays for start-up): still hidden.
        assertNull(estimateRemainingMs(steady(25 * second, 2), 21))
        assertNotNull(estimateRemainingMs(steady(25 * second, 3), 21))
        assertEquals(2, MIN_SEGMENTS_FOR_ESTIMATE - 1)
    }

    @Test
    fun theTrackerStaysHiddenUntilTwoSegmentsHaveBeenMeasured() {
        val tracker = ExportTimeLeftTracker()
        assertEquals(ExportTimeLeft.Hidden, tracker.onProgress(0, 21, 0L))
        assertEquals(ExportTimeLeft.Hidden, tracker.onProgress(1, 21, 30 * second))
        assertEquals(ExportTimeLeft.Hidden, tracker.onProgress(2, 21, 55 * second))
        assertTrue(tracker.onProgress(3, 21, 80 * second) != ExportTimeLeft.Hidden)
    }

    // ---- the measurement itself ----

    @Test
    fun theEstimateIsTheMeasuredTimePerSegmentTimesTheSegmentsLeft() {
        // 25 s per segment after the start-up: 21 segments, 5 done, 16 to go = 400 s.
        assertEquals(16 * 25 * second, estimateRemainingMs(steady(25 * second, 5), 21))
    }

    @Test
    fun theFirstSegmentsStartUpWorkIsNotCountedAsTypicalSegmentTime() {
        // Loading the voice made segment 1 take 40 s longer; every other segment takes 10 s.
        val samples = steady(10 * second, 4, startUpMs = 40 * second)
        assertEquals(17 * 10 * second, estimateRemainingMs(samples, 21))
    }

    @Test
    fun aMissedProgressUpdateStillCountsEverySegment() {
        // Updates for segments 1 and 2 were conflated into one step: 2 segments in 50 s (25 s each).
        val samples = listOf(ProgressSample(0, 0L), ProgressSample(2, 50 * second), ProgressSample(3, 75 * second))
        // The step ending at the first observed progress (2 segments, start-up included) is dropped
        // because another step exists, leaving the 25 s step.
        assertEquals(18 * 25 * second, estimateRemainingMs(samples, 21))
    }

    @Test
    fun whenOnlyTheStartUpStepExistsItIsUsedRatherThanShowingNothing() {
        val samples = listOf(ProgressSample(0, 0L), ProgressSample(3, 90 * second))
        assertEquals(18 * 30 * second, estimateRemainingMs(samples, 21))
    }

    @Test
    fun nothingIsLeftWhenEverySegmentIsDone() {
        assertEquals(0L, estimateRemainingMs(steady(25 * second, 21), 21))
    }

    // ---- rounding ----

    @Test
    fun minutesRoundUp() {
        assertEquals(ExportTimeLeft.Minutes(2), timeLeftFor(61 * second))
        assertEquals(ExportTimeLeft.Minutes(2), timeLeftFor(120 * second))
        assertEquals(ExportTimeLeft.Minutes(3), timeLeftFor(121 * second))
        assertEquals(ExportTimeLeft.Minutes(8), timeLeftFor(7 * 60 * second + 1))
    }

    @Test
    fun aMinuteOrLessIsLessThanAMinute() {
        assertEquals(ExportTimeLeft.LessThanMinute, timeLeftFor(0L))
        assertEquals(ExportTimeLeft.LessThanMinute, timeLeftFor(1L))
        assertEquals(ExportTimeLeft.LessThanMinute, timeLeftFor(60 * second))
        assertEquals(ExportTimeLeft.LessThanMinute, timeLeftFor(-5L))
    }

    @Test
    fun theLastSegmentsEndInLessThanAMinute() {
        val tracker = ExportTimeLeftTracker()
        var last: ExportTimeLeft = ExportTimeLeft.Hidden
        for (done in 0..21) last = tracker.onProgress(done, 21, done * 25 * second)
        assertEquals(ExportTimeLeft.LessThanMinute, last)
    }

    // ---- monotonic behaviour ----

    @Test
    fun withSteadySegmentsTheShownTimeNeverGoesUp() {
        val tracker = ExportTimeLeftTracker()
        var previous = Int.MAX_VALUE
        for (done in 0..21) {
            val shown = tracker.onProgress(done, 21, done * 25 * second)
            val minutes = when (shown) {
                ExportTimeLeft.Hidden -> continue
                ExportTimeLeft.LessThanMinute -> 1
                is ExportTimeLeft.Minutes -> shown.minutes
            }
            assertTrue("went up from $previous to $minutes at segment $done", minutes <= previous)
            previous = minutes
        }
        assertEquals(1, previous)
    }

    @Test
    fun theShownTimeFallsByAboutAMinutePerMinuteThatPasses() {
        // 24 s per segment, 25 segments: about 10 minutes in total. After 5 segments, 20 remain = 8 minutes.
        val tracker = ExportTimeLeftTracker()
        var shown: ExportTimeLeft = ExportTimeLeft.Hidden
        for (done in 0..5) shown = tracker.onProgress(done, 25, done * 24 * second)
        assertEquals(ExportTimeLeft.Minutes(8), shown)
        for (done in 6..15) shown = tracker.onProgress(done, 25, done * 24 * second)
        // 10 remain = 240 s = 4 minutes.
        assertEquals(ExportTimeLeft.Minutes(4), shown)
    }

    // ---- one slow segment ----

    @Test
    fun oneVerySlowSegmentDoesNotDragTheEstimateAway() {
        // Nine segments of 20 s and one of 200 s (the device was busy), 30 segments in all.
        val times = List(9) { 20_000.0 } + listOf(200_000.0)
        // Uncapped the mean would be 38 s; capped at twice the median (40 s) it is 22 s.
        assertEquals(22_000.0, robustMeanMs(times), 0.001)
        val plain = 20 * second
        val withSlow = listOf(ProgressSample(0, 0L)) + (1..10).runningFold(0L) { t, i -> t + if (i == 6) 200 * second else plain }
            .drop(1).mapIndexed { index, t -> ProgressSample(index + 1, t) }
        val remaining = estimateRemainingMs(withSlow, 30)!!
        // 20 segments left at the capped mean (about 22 s).
        assertTrue("estimate $remaining ms should stay near 20 x 22 s, not 20 x 38 s", remaining < 20 * 30 * second)
        assertTrue(remaining > 20 * 20 * second)
    }

    @Test
    fun aSlowSegmentDoesNotMakeTheDisplayJumpUp() {
        val tracker = ExportTimeLeftTracker()
        var t = 0L
        var shown: ExportTimeLeft = tracker.onProgress(0, 30, t)
        val shownMinutes = ArrayList<Int>()
        for (done in 1..12) {
            t += if (done == 8) 150 * second else 20 * second
            shown = tracker.onProgress(done, 30, t)
            shownMinutes += when (shown) {
                ExportTimeLeft.Hidden -> continue
                ExportTimeLeft.LessThanMinute -> 1
                is ExportTimeLeft.Minutes -> shown.minutes
            }
        }
        // Whatever the slow segment did to the raw estimate, the number on screen only ever went down or stayed.
        for (i in 1 until shownMinutes.size) {
            assertTrue("rose at step $i: $shownMinutes", shownMinutes[i] <= shownMinutes[i - 1])
        }
    }

    @Test
    fun theDisplayRisesOnlyWhenTheEarlierFigureWasPlainlyWrong() {
        // Shown "3 min": a slightly higher estimate (4 min) is held back, a much higher one (5 min) wins.
        // From a larger figure the allowance grows with it: "12 min" does not become "14 min", "16 min" does.
        assertEquals(ExportTimeLeft.Minutes(3), nextDisplayedTimeLeft(ExportTimeLeft.Minutes(3), ExportTimeLeft.Minutes(4)))
        assertEquals(ExportTimeLeft.Minutes(5), nextDisplayedTimeLeft(ExportTimeLeft.Minutes(3), ExportTimeLeft.Minutes(5)))
        assertEquals(ExportTimeLeft.Minutes(12), nextDisplayedTimeLeft(ExportTimeLeft.Minutes(12), ExportTimeLeft.Minutes(14)))
        assertEquals(ExportTimeLeft.Minutes(16), nextDisplayedTimeLeft(ExportTimeLeft.Minutes(12), ExportTimeLeft.Minutes(16)))
        assertEquals(ExportTimeLeft.Minutes(2), nextDisplayedTimeLeft(ExportTimeLeft.Minutes(3), ExportTimeLeft.Minutes(2)))
        assertEquals(ExportTimeLeft.LessThanMinute, nextDisplayedTimeLeft(ExportTimeLeft.Minutes(2), ExportTimeLeft.LessThanMinute))
        // "Less than a minute" does not turn back into "2 min" because one more segment was slow.
        assertEquals(ExportTimeLeft.LessThanMinute, nextDisplayedTimeLeft(ExportTimeLeft.LessThanMinute, ExportTimeLeft.Minutes(2)))
        assertEquals(ExportTimeLeft.Minutes(4), nextDisplayedTimeLeft(ExportTimeLeft.Hidden, ExportTimeLeft.Minutes(4)))
    }

    @Test
    fun aRepeatedOrOlderProgressUpdateChangesNothing() {
        val tracker = ExportTimeLeftTracker()
        tracker.onProgress(0, 21, 0L)
        tracker.onProgress(1, 21, 25 * second)
        tracker.onProgress(2, 21, 50 * second)
        val shown = tracker.onProgress(3, 21, 75 * second)
        assertTrue("something is shown by now", shown != ExportTimeLeft.Hidden)
        assertEquals(shown, tracker.onProgress(3, 21, 190 * second))
        assertEquals(shown, tracker.onProgress(1, 21, 195 * second))
    }

    @Test
    fun resetForgetsTheLastExport() {
        val tracker = ExportTimeLeftTracker()
        for (done in 0..5) tracker.onProgress(done, 21, done * 25 * second)
        tracker.reset()
        assertEquals(ExportTimeLeft.Hidden, tracker.onProgress(0, 21, 0L))
        assertEquals(ExportTimeLeft.Hidden, tracker.onProgress(1, 21, 10 * second))
        assertEquals(ExportTimeLeft.Hidden, tracker.onProgress(2, 21, 20 * second))
    }
}
