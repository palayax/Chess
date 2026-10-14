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
 *
 * Elo levels (§8.6): remaining = min(positions left x the level's nodes x ms per node, the engine budget left). The speed
 * is that of the last 8 searched positions ([RecentSpeed]), not the whole run, so thermal throttling is tracked. Max has
 * no node budget: the budget left is the estimate and is shown from the first position.
 *
 * Legacy Auto-depth (ANALYSIS_SPEC §8.4, §8.5, no longer offered) adds its second pass to the work left: the positions it will search again
 * x the nodes a deepened position typically takes. Before the second pass that count is the expected share of the
 * game ([AnalysisProgress.deepPassExpected]); during it, the positions picked and not yet searched. So the estimate
 * never reaches "less than a minute" at the end of the first pass while a second one is still to come.
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
    deepLeft: Int = 0,
    deepTypicalNodes: Long = 0L,
    /**
     * The speed of the LAST positions only ([RecentSpeed], ms per node), so a phone that slows down when it heats is
     * tracked; null = the whole run's average.
     */
    recentMsPerNode: Double? = null,
    /**
     * An Elo level's remaining engine budget (spec §8.6): the estimate is min(positions left x [typicalNodes] x speed,
     * this). With [typicalNodes] 0 (Max, no node budget) the budget itself is the estimate, shown from the start.
     * Null for a legacy fixed-depth run.
     */
    budgetLeftMs: Long? = null,
): Long? {
    if (budgetLeftMs != null && typicalNodes <= 0L) return budgetLeftMs.coerceAtLeast(0L)
    if (runPositions < MIN_POSITIONS_FOR_ESTIMATE || runNodes <= 0L || runMs <= 0L || total <= 0) return null
    val msPerNode = recentMsPerNode?.takeIf { it > 0.0 } ?: (runMs.toDouble() / runNodes)
    val left = (total - done).coerceAtLeast(0)
    val nodesLeft = left * typicalNodes.toDouble() + deepLeft.coerceAtLeast(0) * deepTypicalNodes.toDouble()
    val byNodes = Math.round(nodesLeft * msPerNode)
    return if (budgetLeftMs != null) minOf(byNodes, budgetLeftMs.coerceAtLeast(0L)) else byNodes
}

/**
 * The engine's speed over the last [window] searched positions (ms per node), from cumulative run totals fed once per
 * searched position: the average of the whole run hides a phone that throttles after a minute (spec §8.4).
 */
class RecentSpeed(private val window: Int = WINDOW) {
    private class Sample(val positions: Int, val nodes: Long, val ms: Long)

    private val samples = ArrayDeque<Sample>()

    /** Feed the run's cumulative totals; a repeat of the same position count is ignored. */
    fun add(runPositions: Int, runNodes: Long, runMs: Long) {
        if (runPositions <= 0) return
        if (samples.lastOrNull()?.let { runPositions <= it.positions } == true) return
        samples.addLast(Sample(runPositions, runNodes, runMs))
        while (samples.size > window + 1) samples.removeFirst()
    }

    /** ms per node over the window, or null with fewer than two samples or no nodes in the window. */
    fun msPerNode(): Double? {
        if (samples.size < 2) return null
        val dn = samples.last().nodes - samples.first().nodes
        val dt = samples.last().ms - samples.first().ms
        return if (dn > 0L && dt > 0L) dt.toDouble() / dn else null
    }

    companion object {
        const val WINDOW = 8
    }
}

/**
 * Auto-depth's second-pass positions still to search for [progress]: the picked ones not yet searched during the
 * second pass, the expected count before it (spec §8.5). 0 for any other strength.
 */
fun deepPositionsLeft(progress: AnalysisProgress): Int =
    if (progress.deepPassTotal > 0) (progress.deepPassTotal - progress.deepPassDone).coerceAtLeast(0)
    else progress.deepPassExpected.coerceAtLeast(0)

/**
 * The running estimate for one analysis run: feed it every progress update, read the line to show.
 * [deepTypicalNodes] is legacy Auto-depth's per-position cost of its second pass (`AutoDepthBudget.TYPICAL_DEEPENED_NODES`),
 * 0 for the other strengths. [levelRun]: an Elo-level run (spec §8.6), whose estimate is bounded by the run's engine
 * budget ([AnalysisProgress.engineBudgetMs]); [typicalNodes] is the level's node budget (0 for Max).
 */
class AnalysisTimeLeftTracker(private val typicalNodes: Long, private val deepTypicalNodes: Long = 0L, private val levelRun: Boolean = false) {
    private var shown: ExportTimeLeft = ExportTimeLeft.Hidden
    private var lastDone = -1
    private val recent = RecentSpeed()

    /** The speed over the last positions (ms per node), for the engine stage's own estimate; null until measured. */
    @Synchronized
    fun recentMsPerNode(): Double? = recent.msPerNode()

    /** Returns what to show for [progress]; recomputed only when another position is done. */
    @Synchronized
    fun onProgress(progress: AnalysisProgress): ExportTimeLeft {
        if (progress.phase != AnalysisPhase.ANALYZING_MOVES || progress.totalMoves <= 0) return shown
        // Positions done across both of Auto's passes (the second pass counts on after the first).
        val done = progress.currentMoveIndex + progress.deepPassDone
        recent.add(progress.runPositionsSearched, progress.runNodes, progress.runSearchMs)
        if (done <= lastDone) return shown
        lastDone = done
        val deepLeft = deepPositionsLeft(progress)
        if (progress.currentMoveIndex >= progress.totalMoves && deepLeft == 0) {
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
            deepLeft = deepLeft,
            deepTypicalNodes = deepTypicalNodes,
            recentMsPerNode = recent.msPerNode(),
            budgetLeftMs = if (levelRun) (progress.engineBudgetMs - progress.runSearchMs).coerceAtLeast(0L) else null,
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
