package net.palaya.chessanalyzer.video

/*
 * "About 5 min left" on the export dialog's narration step (R6a). Everything here is pure Kotlin so
 * it has a host test; the service feeds it progress as it arrives and the dialog only displays the
 * result.
 *
 * No constants stand in for how long a segment takes: the estimate is the **measured** time per
 * segment so far, times the segments still to do. The only fixed numbers are about presentation
 * (when to start showing it, how to round, how to keep it from jumping), and each is named below.
 */

/** What the dialog shows under "Preparing narration... (n/N)". */
sealed interface ExportTimeLeft {
    /** Nothing yet: fewer than [MIN_SEGMENTS_FOR_ESTIMATE] segments are done (two clean measurements). */
    data object Hidden : ExportTimeLeft

    /** About a minute or less remains ("Less than a minute left"). */
    data object LessThanMinute : ExportTimeLeft

    /** [minutes] whole minutes remain, rounded up; never below 2 (below that it is [LessThanMinute]). */
    data class Minutes(val minutes: Int) : ExportTimeLeft
}

/** Progress seen at a moment: [completed] segments done at [atMs] (a monotonic clock, e.g. elapsedRealtime). */
data class ProgressSample(val completed: Int, val atMs: Long)

/**
 * Nothing is shown until this many segments are done. Two is the least that says anything (the
 * first segment also pays for start-up, so it is not a measurement), but a single measured segment
 * is noisy: the first emulator run showed "About 34 min left" after segment 2 (that one segment
 * happened to take 46 s) and "About 12 min left" four segments later. The third segment gives a
 * second clean measurement, so the line starts there: the number is still a running average, it
 * just does not start from a sample of one.
 */
const val MIN_SEGMENTS_FOR_ESTIMATE = 3

/**
 * A single slow segment (a long sentence, a cold cache, the device busy) must not drag the whole
 * estimate: each measured segment time is capped at this multiple of the median before averaging (a long stall adds at most one median of extra time to the mean).
 * Only applied from [MIN_SEGMENTS_FOR_CAP] measurements, since with fewer there is no "normal" to cap against.
 */
const val SLOW_SEGMENT_CAP_FACTOR = 2.0
const val MIN_SEGMENTS_FOR_CAP = 3

/**
 * How much the shown number of minutes may RISE before the display follows it up: the shown value
 * only goes down while the export progresses, unless the estimate has been wrong by at least this
 * many minutes, or by [RISE_TOLERANCE_FRACTION] of what is shown, whichever is larger (a few slower
 * segments in a row then show up; one slow segment does not make "12 min" read "14 min").
 */
const val MINUTES_RISE_TOLERANCE = 2
const val RISE_TOLERANCE_FRACTION = 0.25

private const val MS_PER_MINUTE = 60_000L

/**
 * Measured milliseconds per finished segment, or null when [samples] cannot give one yet.
 *
 * [samples] are in time order, the first normally `(0, start)`. A step from `(c1, t1)` to `(c2, t2)`
 * means `c2 - c1` segments took `t2 - t1`, so each of them took the average of that (a progress
 * update the UI missed still counts correctly). The step that ends at the first finished segment
 * also contains one-off start-up work (loading the voice), so it is left out whenever another step
 * exists; if it is the only one, it is used.
 */
fun measuredSegmentTimesMs(samples: List<ProgressSample>): List<Double> {
    val steps = ArrayList<Pair<Int, Double>>() // (segments in the step, ms per segment)
    for (i in 1 until samples.size) {
        val before = samples[i - 1]
        val after = samples[i]
        val count = after.completed - before.completed
        if (count <= 0) continue
        steps += count to ((after.atMs - before.atMs).coerceAtLeast(0L).toDouble() / count)
    }
    val usable = if (steps.size > 1) steps.drop(1) else steps
    return usable.flatMap { (count, perSegment) -> List(count) { perSegment } }
}

/**
 * Mean of [times], each capped at [SLOW_SEGMENT_CAP_FACTOR] times the median once there are
 * [MIN_SEGMENTS_FOR_CAP] of them.
 */
fun robustMeanMs(times: List<Double>): Double {
    if (times.isEmpty()) return 0.0
    if (times.size < MIN_SEGMENTS_FOR_CAP) return times.average()
    val sorted = times.sorted()
    val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
    val cap = median * SLOW_SEGMENT_CAP_FACTOR
    return times.map { minOf(it, cap) }.average()
}

/**
 * Milliseconds still to go for the segments not yet done, or null while fewer than
 * [MIN_SEGMENTS_FOR_ESTIMATE] are done (or [total] is unknown).
 */
fun estimateRemainingMs(samples: List<ProgressSample>, total: Int): Long? {
    val completed = samples.maxOfOrNull { it.completed } ?: return null
    if (completed < MIN_SEGMENTS_FOR_ESTIMATE || total <= 0) return null
    val times = measuredSegmentTimesMs(samples)
    if (times.isEmpty()) return null
    val remainingSegments = (total - completed).coerceAtLeast(0)
    return Math.round(robustMeanMs(times) * remainingSegments)
}

/** [remainingMs] as what is shown: whole minutes rounded UP, and "less than a minute" at one minute or less. */
fun timeLeftFor(remainingMs: Long): ExportTimeLeft {
    val minutes = ((remainingMs.coerceAtLeast(0L) + MS_PER_MINUTE - 1) / MS_PER_MINUTE).toInt()
    return if (minutes <= 1) ExportTimeLeft.LessThanMinute else ExportTimeLeft.Minutes(minutes)
}

private fun ExportTimeLeft.asMinutes(): Int? = when (this) {
    ExportTimeLeft.Hidden -> null
    ExportTimeLeft.LessThanMinute -> 1
    is ExportTimeLeft.Minutes -> minutes
}

/**
 * Decides what to show next, given what is shown now. The display goes down as the export
 * progresses and does not wobble up and down with every slightly slower segment: it rises only when
 * the new estimate is at least [MINUTES_RISE_TOLERANCE] minutes above what is shown (the earlier
 * figure was then plainly wrong). Pure, so the "never a wrong-looking jump" rule is tested directly.
 */
fun nextDisplayedTimeLeft(shown: ExportTimeLeft, candidate: ExportTimeLeft): ExportTimeLeft {
    val shownMinutes = shown.asMinutes() ?: return candidate
    val candidateMinutes = candidate.asMinutes() ?: return shown
    val tolerance = maxOf(MINUTES_RISE_TOLERANCE, Math.ceil(shownMinutes * RISE_TOLERANCE_FRACTION).toInt())
    return if (candidateMinutes <= shownMinutes || candidateMinutes - shownMinutes >= tolerance) {
        candidate
    } else {
        shown
    }
}

/**
 * The running record for one export: feed it every progress update, read the line to show.
 * [reset] before a new export. Thread-safe: the service updates it from a worker thread and the
 * export starts on the main thread.
 */
class ExportTimeLeftTracker {
    private val samples = ArrayList<ProgressSample>()
    private var shown: ExportTimeLeft = ExportTimeLeft.Hidden

    @Synchronized
    fun reset() {
        samples.clear()
        shown = ExportTimeLeft.Hidden
    }

    /** Records "[completed] of [total] done at [nowMs]" and returns what to show. Repeats and regressions are ignored. */
    @Synchronized
    fun onProgress(completed: Int, total: Int, nowMs: Long): ExportTimeLeft {
        val last = samples.lastOrNull()
        if (last != null && completed <= last.completed) return shown
        samples += ProgressSample(completed, nowMs)
        val remaining = estimateRemainingMs(samples, total) ?: return shown
        shown = nextDisplayedTimeLeft(shown, timeLeftFor(remaining))
        return shown
    }
}
