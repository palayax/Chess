package net.palaya.chessanalyzer.ui.model

import net.palaya.chessanalyzer.video.ExportTimeLeft
import net.palaya.chessanalyzer.video.ProgressSample
import net.palaya.chessanalyzer.video.estimateRemainingMs
import net.palaya.chessanalyzer.video.nextDisplayedTimeLeft
import net.palaya.chessanalyzer.video.timeLeftFor

/*
 * The one processing run of a review (owner's phone test, 2026-10-11, items 5 to 7): every stage the game's options ask
 * for runs once, on one screen, with one overall bar, a checklist of the stages and a measured "About N min left". Pure,
 * host-tested (ReviewPipelineLogicTest); the ViewModel runs the stages and the screen only draws them.
 *
 * The time left is measured, never assumed:
 *  - the engine stage keeps F1's estimate (positions left x typical nodes x this run's time per node, from 6 positions);
 *  - every other stage, once it runs, uses its own measured time per item (ExportTimeLeft's rule: from 3 items, the
 *    first step left out, slow outliers capped);
 *  - a stage that has not started yet uses the time the same stage took per game move on this phone in earlier reviews
 *    ([ReviewSpeeds]). When any stage still to come has no measurement, the overall line is hidden and only the current
 *    stage's own line can show. The first review on a phone therefore shows less; it never shows a guess.
 */

/** The stages of a review, in the order they run. */
enum class ReviewStage {
    /** Analysing moves: the engine, every position. */
    ENGINE,

    /** Polishing the commentary: the key-moment cards reworded by the on-device model (wording on). */
    POLISH_COMMENTARY,

    /** Writing the video narration: the paced script, its beats reworded when wording is on (prepare-video on). */
    WRITE_NARRATION,

    /** Recording the narration voice: every segment synthesised in the chosen Kokoro voice (prepare-video on). */
    RECORD_VOICE;

    /** Only the engine is required; the others can be skipped from the screen. */
    val skippable: Boolean get() = this != ENGINE
}

/**
 * The most time [this] stage can take in a review (EL2, spec §8.4 and §8.6): what the Review options screen adds for a stage
 * this phone has never measured, so the figure shown is an upper bound and not a guess. [wordingInEffect] = the game's
 * Natural wording runs (the narration stage then also rewords its beats).
 */
fun ReviewStage.boundMs(wordingInEffect: Boolean): Long = when (this) {
    ReviewStage.ENGINE -> GAME_ENGINE_BUDGET_MS
    ReviewStage.POLISH_COMMENTARY -> WORDING_STAGE_BUDGET_MS
    ReviewStage.WRITE_NARRATION -> if (wordingInEffect) WORDING_STAGE_BUDGET_MS else NARRATION_WRITE_RESERVE_MS
    // Runs until the deadline; what is not recorded by then is recorded in the background.
    ReviewStage.RECORD_VOICE -> REVIEW_TOTAL_BUDGET_MS
}

enum class StageStatus { PENDING, CURRENT, DONE, SKIPPED }

/** One row of the checklist. [done] of [total] items (positions, texts, beats, segments); [total] 0 = not known yet. */
data class StageState(
    val stage: ReviewStage,
    val status: StageStatus = StageStatus.PENDING,
    val done: Int = 0,
    val total: Int = 0,
) {
    /** How far this stage is, 0..1. Done and skipped stages count as complete, so the overall bar never goes back. */
    val fraction: Float
        get() = when (status) {
            StageStatus.DONE, StageStatus.SKIPPED -> 1f
            StageStatus.PENDING -> 0f
            StageStatus.CURRENT -> if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
        }
}

/**
 * The stages a run does for [options]. A reopened game (a library row, after a restart) runs only the engine stage,
 * which reads the eval cache: its other work was done when it was reviewed, and what is missing (audio in a changed
 * voice, say) is made where it is needed (the Video screen), not by surprise when the game is merely opened.
 *
 * @param wordingUsable the wording model is installed and supported on this phone
 * @param kokoroInstalled the natural voice is installed (a device voice speaks live and needs no recording)
 */
fun reviewStagePlan(options: ReviewOptions, wordingUsable: Boolean, kokoroInstalled: Boolean, reopen: Boolean = false): List<ReviewStage> {
    if (reopen) return listOf(ReviewStage.ENGINE)
    return buildList {
        add(ReviewStage.ENGINE)
        if (wordingInEffect(options, wordingUsable)) add(ReviewStage.POLISH_COMMENTARY)
        if (options.prepareVideo) {
            add(ReviewStage.WRITE_NARRATION)
            if (options.voice == NarrationProviderChoice.NEURAL && kokoroInstalled) add(ReviewStage.RECORD_VOICE)
        }
    }
}

/**
 * Relative weights for the overall bar when not every stage has a measured estimate yet. They only shape the bar (never a
 * time shown to the user): the engine is most of a review, a recording pass about a third of it, a wording pass less.
 * As soon as every stage of the plan has an estimate in milliseconds, those replace these.
 */
internal val PRIOR_STAGE_WEIGHTS: Map<ReviewStage, Double> = mapOf(
    ReviewStage.ENGINE to 6.0,
    ReviewStage.POLISH_COMMENTARY to 1.0,
    ReviewStage.WRITE_NARRATION to 1.0,
    ReviewStage.RECORD_VOICE to 2.0,
)

/**
 * The weight of each stage of [plan] in the overall bar: the estimated milliseconds when EVERY stage has one (they are
 * then comparable), else the priors for all (mixing milliseconds with priors would compare different units).
 */
fun stageWeights(plan: List<ReviewStage>, estimatesMs: Map<ReviewStage, Long?>): Map<ReviewStage, Double> {
    val all = plan.map { estimatesMs[it] }
    return if (plan.isNotEmpty() && all.all { it != null }) {
        plan.associateWith { (estimatesMs[it] ?: 0L).coerceAtLeast(1L).toDouble() }
    } else {
        plan.associateWith { PRIOR_STAGE_WEIGHTS.getValue(it) }
    }
}

/** The one overall bar, 0..1: each stage's own fraction weighted by [weights]. */
fun overallFraction(stages: List<StageState>, weights: Map<ReviewStage, Double>): Float {
    val total = stages.sumOf { weights[it.stage] ?: 0.0 }
    if (total <= 0.0) return 0f
    return (stages.sumOf { (weights[it.stage] ?: 0.0) * it.fraction } / total).toFloat().coerceIn(0f, 1f)
}

/**
 * Milliseconds left for the whole run: the current stage's own remaining time plus an estimate for every stage still
 * pending. Null (hide the line) when the current stage has no measurement yet or any pending stage has no estimate.
 */
fun overallRemainingMs(stages: List<StageState>, currentRemainingMs: Long?, pendingEstimatesMs: Map<ReviewStage, Long?>): Long? {
    var sum = 0L
    for (s in stages) {
        when (s.status) {
            StageStatus.CURRENT -> sum += currentRemainingMs ?: return null
            StageStatus.PENDING -> sum += pendingEstimatesMs[s.stage] ?: return null
            StageStatus.DONE, StageStatus.SKIPPED -> Unit
        }
    }
    return sum
}

/**
 * What this phone measured in earlier reviews, kept on disk (`ReviewSpeedStore`): the engine's milliseconds per node and,
 * for each other stage, its milliseconds per game ply (the stage's wall time over the game's plies: the number of texts,
 * beats and segments grows with the game, and the ply count is known before anything runs).
 */
data class ReviewSpeeds(
    val engineMsPerNode: Double? = null,
    val msPerPly: Map<ReviewStage, Double> = emptyMap(),
) {
    /** The estimate for [stage] on a game of [plies], or null when this phone has never measured it. */
    fun estimateMs(stage: ReviewStage, plies: Int): Long? {
        if (plies <= 0) return null
        val rate = msPerPly[stage] ?: return null
        return Math.round(rate * plies)
    }

    /** [measuredMs] for a whole [stage] over a game of [plies], blended with what was known (the mean of the two). */
    fun withStage(stage: ReviewStage, measuredMs: Long, plies: Int): ReviewSpeeds {
        if (stage == ReviewStage.ENGINE || plies <= 0 || measuredMs < MIN_STAGE_MS_TO_RECORD) return this
        val rate = measuredMs.toDouble() / plies
        val old = msPerPly[stage]
        return copy(msPerPly = msPerPly + (stage to if (old == null) rate else (old + rate) / 2.0))
    }

    /**
     * The engine's measured time per node from one run, blended towards the latest run (0.7 new / 0.3 old, spec §8.4):
     * the phone's speed changes with heat and charge, and the newest run says most about the next one.
     */
    fun withEngine(runMs: Long, runNodes: Long, runPositions: Int): ReviewSpeeds {
        if (runPositions < MIN_POSITIONS_FOR_ESTIMATE || runNodes <= 0L || runMs <= 0L) return this
        val rate = runMs.toDouble() / runNodes
        val old = engineMsPerNode
        return copy(engineMsPerNode = if (old == null) rate else NEW_RUN_WEIGHT * rate + (1.0 - NEW_RUN_WEIGHT) * old)
    }

    companion object {
        /** A stage that took less than this did no real work (everything was cached): it measures nothing. */
        const val MIN_STAGE_MS_TO_RECORD = 1_000L

        /** The weight of the latest run in the engine's blended milliseconds per node. */
        const val NEW_RUN_WEIGHT = 0.7
    }
}

/**
 * The running "About N min left" for one review run. Feed it every stage update; read [overall] (the whole run) and
 * [current] (the stage running now). The display only rises when the estimate was plainly wrong ([nextDisplayedTimeLeft]).
 */
class ReviewTimeLeftTracker(
    private val speeds: ReviewSpeeds,
    private val plies: Int,
) {
    private val samples = HashMap<ReviewStage, MutableList<ProgressSample>>()
    private var shownOverall: ExportTimeLeft = ExportTimeLeft.Hidden
    private var shownCurrent: ExportTimeLeft = ExportTimeLeft.Hidden
    private var currentStage: ReviewStage? = null

    /** Milliseconds left in a non-engine [stage] from its own samples, or null until it has measured enough. */
    @Synchronized
    fun stageRemainingMs(stage: ReviewStage, total: Int): Long? = estimateRemainingMs(samples[stage].orEmpty(), total)

    /** Records "[done] of [total] at [nowMs]" for a non-engine [stage]. Repeats and regressions are ignored. */
    @Synchronized
    fun onStageProgress(stage: ReviewStage, done: Int, nowMs: Long) {
        val list = samples.getOrPut(stage) { mutableListOf() }
        val last = list.lastOrNull()
        if (last != null && done <= last.completed) return
        list += ProgressSample(done, nowMs)
    }

    /**
     * Recomputes both lines for [stages]. [engineRemainingMs] is F1's estimate while the engine stage runs (null until
     * it can be honest); for other stages the tracker's own samples are used.
     */
    @Synchronized
    fun update(stages: List<StageState>, engineRemainingMs: Long?): Pair<ExportTimeLeft, ExportTimeLeft> {
        val current = stages.firstOrNull { it.status == StageStatus.CURRENT }
        if (current?.stage != currentStage) {
            // A new stage starts its own line from nothing (its unit and rate differ from the last stage's).
            currentStage = current?.stage
            shownCurrent = ExportTimeLeft.Hidden
        }
        val currentMs = when {
            current == null -> null
            current.stage == ReviewStage.ENGINE -> engineRemainingMs
            else -> estimateRemainingMs(samples[current.stage].orEmpty(), current.total)
        }
        if (current == null) {
            shownOverall = ExportTimeLeft.Hidden
            shownCurrent = ExportTimeLeft.Hidden
            return shownOverall to shownCurrent
        }
        shownCurrent = currentMs?.let { nextDisplayedTimeLeft(shownCurrent, timeLeftFor(it)) } ?: shownCurrent
        val pending = stages.filter { it.status == StageStatus.PENDING }.associate { it.stage to speeds.estimateMs(it.stage, plies) }
        val overallMs = overallRemainingMs(stages, currentMs, pending)
        shownOverall = overallMs?.let { nextDisplayedTimeLeft(shownOverall, timeLeftFor(it)) } ?: shownOverall
        return shownOverall to shownCurrent
    }
}

/** The ViewModel's snapshot of a run, which the processing screen draws. */
data class ReviewRunProgress(
    val stages: List<StageState> = listOf(StageState(ReviewStage.ENGINE, StageStatus.CURRENT)),
    val overallFraction: Float = 0f,
    /** The whole run's "About N min left". */
    val overallTimeLeft: ExportTimeLeft = ExportTimeLeft.Hidden,
    /** The current stage's own line, shown when the overall one cannot be honest yet. */
    val stageTimeLeft: ExportTimeLeft = ExportTimeLeft.Hidden,
    /** The engine's own details (elapsed clock, "Thinking deeper…"), as before. */
    val engine: AnalysisProgress = AnalysisProgress(phase = AnalysisPhase.PREPARING_ENGINE),
    /** Monotonic time the run started; 0 when unknown. */
    val runStartedAtMs: Long = 0L,
) {
    val current: StageState? get() = stages.firstOrNull { it.status == StageStatus.CURRENT }
}

/** The stages of [plan] as the checklist starts them: the first one running, the rest pending. */
fun initialStageStates(plan: List<ReviewStage>): List<StageState> =
    plan.mapIndexed { i, s -> StageState(s, if (i == 0) StageStatus.CURRENT else StageStatus.PENDING) }

/** [stages] with [stage] updated by [change] (other rows untouched). */
fun List<StageState>.updated(stage: ReviewStage, change: (StageState) -> StageState): List<StageState> =
    map { if (it.stage == stage) change(it) else it }

/** [stages] after [stage] finished ([skipped] = the user skipped it, or it had nothing to do): the next pending one runs. */
fun List<StageState>.finished(stage: ReviewStage, skipped: Boolean = false): List<StageState> {
    val marked = updated(stage) { it.copy(status = if (skipped) StageStatus.SKIPPED else StageStatus.DONE, done = if (skipped) it.done else it.total) }
    val next = marked.indexOfFirst { it.status == StageStatus.PENDING }
    return if (next < 0) marked else marked.mapIndexed { i, s -> if (i == next) s.copy(status = StageStatus.CURRENT) else s }
}
