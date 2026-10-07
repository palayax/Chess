package net.palaya.chessanalyzer.ui.model

import net.palaya.chessanalyzer.video.ExportTimeLeft
import net.palaya.chessanalyzer.video.nextDisplayedTimeLeft
import net.palaya.chessanalyzer.video.timeLeftFor

/*
 * The time bar on the Analysing screen (F1): elapsed time, "About N min left", and "Thinking deeper
 * on this move… depth 15 of 18" when one position takes long. Pure, host-tested
 * (AnalysisTimeLeftTest); the screen only displays it.
 *
 * What is reused from the export's "About N min left" (video/ExportTimeLeft.kt): nothing is shown
 * until enough has been measured, minutes are rounded up with "Less than a minute" at the end, and the
 * shown number only rises when it was plainly wrong (nextDisplayedTimeLeft).
 *
 * What is NOT reused: the export's estimate (measured time per item times the items left). Positions
 * are not alike the way narration segments are: the opening is cheap and the middlegame dear. On the
 * emulator's game01 Deep run that estimate said "About 3 min left" after 6 positions when 19 minutes
 * were left, and 7 min at move 10 when 17 were left (spec §8.4). So the estimate is in nodes, which
 * do not depend on the device: positions left x nodes a position typically takes at this strength
 * (calibrated on the host, SearchBudget.typicalNodes) x this device's measured time per node in this
 * run. The game's own node counts are deliberately not used: its opening is cheaper than what is left,
 * so they pull the estimate down exactly when it matters (replayed on the run below, blending them in
 * gave 12-13 min after 6 positions instead of 14). A game much heavier than the calibration games is
 * underestimated; the display then rises once it is off by 2 minutes or 25%. Replayed on that run's log it shows 14 min after 6 positions
 * (19.3 left), 14 after 20 (16.6 left), 12 after 30 (12.5 left) and 6 after 50 (4.6 left).
 */

/** Positions the engine has searched in this run before an estimate is shown (its speed is then measured). */
const val MIN_POSITIONS_FOR_ESTIMATE = 6

/** A position searched for longer than this gets the "Thinking deeper on this move" line. */
const val THINKING_DEEPER_AFTER_MS = 3_000L

/**
 * Milliseconds still to go, or null while fewer than [MIN_POSITIONS_FOR_ESTIMATE] positions have
 * been searched in this run.
 *
 * @param done positions done (including any restored from a checkpoint), of [total].
 * @param runNodes, runMs, runPositions nodes, wall time and positions searched by the engine in
 *   this run (a resumed run counts only its own).
 * @param typicalNodes nodes a position typically takes at this strength ([SearchBudget.typicalNodes]).
 */
fun estimateAnalysisRemainingMs(
    done: Int,
    total: Int,
    runNodes: Long,
    runMs: Long,
    runPositions: Int,
    typicalNodes: Long,
): Long? {
    if (runPositions < MIN_POSITIONS_FOR_ESTIMATE || runNodes <= 0L || runMs <= 0L || total <= 0) return null
    val msPerNode = runMs.toDouble() / runNodes
    val left = (total - done).coerceAtLeast(0)
    return Math.round(left * typicalNodes.toDouble() * msPerNode)
}

/** The running estimate for one analysis run: feed it every progress update, read the line to show. */
class AnalysisTimeLeftTracker(private val typicalNodes: Long) {
    private var shown: ExportTimeLeft = ExportTimeLeft.Hidden
    private var lastDone = -1

    /** Returns what to show for [progress]; recomputed only when another position is done. */
    @Synchronized
    fun onProgress(progress: AnalysisProgress): ExportTimeLeft {
        if (progress.phase != AnalysisPhase.ANALYZING_MOVES || progress.totalMoves <= 0) return shown
        if (progress.currentMoveIndex <= lastDone) return shown
        lastDone = progress.currentMoveIndex
        if (progress.currentMoveIndex >= progress.totalMoves) {
            shown = ExportTimeLeft.Hidden
            return shown
        }
        val remaining = estimateAnalysisRemainingMs(
            done = progress.currentMoveIndex,
            total = progress.totalMoves,
            runNodes = progress.runNodes,
            runMs = progress.runSearchMs,
            runPositions = progress.runPositionsSearched,
            typicalNodes = typicalNodes,
        ) ?: return shown
        shown = nextDisplayedTimeLeft(shown, timeLeftFor(remaining))
        return shown
    }
}

/** "0:42", "12:05", "1:02:03": elapsed wall time as a clock reads it (digits only, no words). */
fun formatElapsed(elapsedMs: Long): String {
    val total = (elapsedMs.coerceAtLeast(0L) / 1000)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(java.util.Locale.ROOT, "%d:%02d", m, s)
}

/** (depth reached, depth asked for) when the "Thinking deeper" line should show at [nowMs], else null. */
fun thinkingDeeper(progress: AnalysisProgress, nowMs: Long): Pair<Int, Int>? {
    if (progress.phase != AnalysisPhase.ANALYZING_MOVES) return null
    if (progress.positionStartedAtMs <= 0L || progress.targetDepth <= 0 || progress.searchDepth <= 0) return null
    if (nowMs - progress.positionStartedAtMs < THINKING_DEEPER_AFTER_MS) return null
    return progress.searchDepth.coerceAtMost(progress.targetDepth) to progress.targetDepth
}
