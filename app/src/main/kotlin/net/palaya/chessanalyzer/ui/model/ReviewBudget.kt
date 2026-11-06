package net.palaya.chessanalyzer.ui.model

/*
 * The whole-review deadline (EL2, docs/ANALYSIS_SPEC.md §8.6 and §8.4): a review (engine + wording + narration + voice)
 * must be over in about ten minutes on a phone like a Pixel 8, and every time shown to the user must be honest. Pure
 * arithmetic only (no clock, no Android, no ReviewStage), host-tested (ReviewBudgetTest); the ViewModel reads the clock
 * and applies these numbers.
 */

/** The whole review (every stage) is over by this long after the run started; the voice recording stops here (spec §8.6). */
const val REVIEW_TOTAL_BUDGET_MS: Long = 600_000L

/** Each wording stage (polishing the key moments; rewording the narration beats) gets at most this much time (spec §8.6). */
const val WORDING_STAGE_BUDGET_MS: Long = 90_000L

/** Time kept for writing the narration script (no model involved) when the wording stages are budgeted. */
const val NARRATION_WRITE_RESERVE_MS: Long = 10_000L

/** Time kept for the voice recording when the wording stages are budgeted; the recording itself runs to the deadline. */
const val RECORDING_RESERVE_MS: Long = 120_000L

/**
 * The engine speed assumed before this phone has measured one: 250,000 nodes per second, which is deliberately slow
 * (a Pixel 8 does several times that), so the upper bound shown on the first review is a bound and not a hope.
 * Milliseconds per node, as [net.palaya.chessanalyzer.ui.model.ReviewSpeeds.engineMsPerNode] (1 / 250 ms = 0.004).
 */
const val ENGINE_PRIOR_MS_PER_NODE: Double = 1.0 / 250.0

/** A time for the user: [ms], and whether it is only an upper bound ("up to about") rather than a measured figure. */
data class TimeEstimate(val ms: Long, val upperBound: Boolean)

/** Milliseconds from [elapsedMs] (since the run started) to the review deadline; never negative. */
fun timeToDeadlineMs(elapsedMs: Long, totalMs: Long = REVIEW_TOTAL_BUDGET_MS): Long = (totalMs - elapsedMs).coerceAtLeast(0L)

/** The time kept for the stages that come after a wording stage. */
fun reserveAfterWordingMs(narrationWritePlanned: Boolean, recordingPlanned: Boolean): Long =
    (if (narrationWritePlanned) NARRATION_WRITE_RESERVE_MS else 0L) + (if (recordingPlanned) RECORDING_RESERVE_MS else 0L)

/**
 * The total budget of one wording stage that starts [elapsedMs] into the run: [WORDING_STAGE_BUDGET_MS], or the time left
 * to the deadline minus the [reserveMs] of the stages still to come when that is less; 0 = skip the stage (the original
 * texts stay).
 */
fun wordingStageBudgetMs(
    elapsedMs: Long,
    reserveMs: Long,
    totalMs: Long = REVIEW_TOTAL_BUDGET_MS,
    capMs: Long = WORDING_STAGE_BUDGET_MS,
): Long = minOf(capMs, timeToDeadlineMs(elapsedMs, totalMs) - reserveMs).coerceAtLeast(0L)

/**
 * The upper bound of the engine stage for [level] on a game of [positions] positions (spec §8.4): the cached case is 0;
 * Max is min(positions x [MAX_LEVEL_MOVETIME_CEILING_MS], the engine budget); another level is
 * min(positions x its nodes x [msPerNode], the engine budget). With a measured [msPerNode] a node level is a measured
 * estimate; without one the slow-phone prior [ENGINE_PRIOR_MS_PER_NODE] gives an upper bound. Max is always a bound
 * (a short game finishes before its budget, a hard one may not).
 */
fun levelEngineEstimate(
    level: EngineLevel,
    positions: Int,
    msPerNode: Double?,
    cached: Boolean = false,
    budgetMs: Long = GAME_ENGINE_BUDGET_MS,
): TimeEstimate {
    if (cached) return TimeEstimate(0L, upperBound = false)
    val n = positions.coerceAtLeast(0)
    val nodes = level.nodes
    if (nodes == null) return TimeEstimate(minOf(n * MAX_LEVEL_MOVETIME_CEILING_MS, budgetMs), upperBound = true)
    val measured = msPerNode != null && msPerNode > 0.0
    val rate = if (measured) msPerNode!! else ENGINE_PRIOR_MS_PER_NODE
    val byNodes = Math.round(n * nodes.toDouble() * rate)
    return TimeEstimate(minOf(byNodes, budgetMs), upperBound = !measured)
}

/**
 * The whole review: the [engine] stage plus the [others] (one estimate per other stage of the plan: the measured time,
 * or the stage's bound when it was never measured), never above [REVIEW_TOTAL_BUDGET_MS]. It is an upper bound as soon as
 * any part is one, and also when the sum had to be cut to the deadline.
 */
fun wholeReviewEstimate(engine: TimeEstimate, others: List<TimeEstimate>, totalMs: Long = REVIEW_TOTAL_BUDGET_MS): TimeEstimate {
    val sum = engine.ms + others.sumOf { it.ms }
    val bound = engine.upperBound || others.any { it.upperBound }
    return if (sum > totalMs) TimeEstimate(totalMs, upperBound = true) else TimeEstimate(sum, bound)
}
