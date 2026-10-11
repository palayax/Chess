package net.palaya.chessanalyzer.ui.model

import net.palaya.chessanalyzer.core.narration.VideoPace
import net.palaya.chessanalyzer.video.ExportTimeLeft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** RF: the one processing run (stage plan, overall bar, measured time left, stored speeds). */
class ReviewPipelineLogicTest {

    private fun options(
        wording: Boolean = false,
        video: Boolean = true,
        voice: NarrationProviderChoice = NarrationProviderChoice.NEURAL,
    ) = ReviewOptions(
        strength = AnalysisStrength.STANDARD,
        side = SideChoice.UNKNOWN,
        naturalWording = wording,
        prepareVideo = video,
        voice = voice,
        speakerId = 1,
        pace = VideoPace.NORMAL,
    )

    @Test
    fun theStagePlanFollowsTheGamesOptions() {
        val all = listOf(ReviewStage.ENGINE, ReviewStage.POLISH_COMMENTARY, ReviewStage.WRITE_NARRATION, ReviewStage.RECORD_VOICE)
        assertEquals(all, reviewStagePlan(options(wording = true), wordingUsable = true, kokoroInstalled = true))
        // Wording asked for but the model cannot run here: no polishing stage.
        assertEquals(all - ReviewStage.POLISH_COMMENTARY, reviewStagePlan(options(wording = true), wordingUsable = false, kokoroInstalled = true))
        // No video now: the engine and (with wording) the cards only.
        assertEquals(listOf(ReviewStage.ENGINE, ReviewStage.POLISH_COMMENTARY), reviewStagePlan(options(wording = true, video = false), true, true))
        assertEquals(listOf(ReviewStage.ENGINE), reviewStagePlan(options(video = false), true, true))
        // The device voice speaks live, and a natural voice that is not installed cannot be recorded: no recording.
        assertEquals(listOf(ReviewStage.ENGINE, ReviewStage.WRITE_NARRATION), reviewStagePlan(options(voice = NarrationProviderChoice.DEVICE), true, true))
        assertEquals(listOf(ReviewStage.ENGINE, ReviewStage.WRITE_NARRATION), reviewStagePlan(options(), true, kokoroInstalled = false))
        // A plain reopen (a library row) runs only the engine stage, whatever the options say.
        assertEquals(listOf(ReviewStage.ENGINE), reviewStagePlan(options(wording = true), true, true, reopen = true))
    }

    @Test
    fun onlyTheEngineCannotBeSkipped() {
        assertTrue(ReviewStage.entries.filter { !it.skippable } == listOf(ReviewStage.ENGINE))
    }

    @Test
    fun weightsAreMeasuredMillisecondsOnlyWhenEveryStageHasOne() {
        val plan = listOf(ReviewStage.ENGINE, ReviewStage.RECORD_VOICE)
        val measured = stageWeights(plan, mapOf(ReviewStage.ENGINE to 300_000L, ReviewStage.RECORD_VOICE to 100_000L))
        assertEquals(300_000.0, measured.getValue(ReviewStage.ENGINE), 0.0)
        assertEquals(100_000.0, measured.getValue(ReviewStage.RECORD_VOICE), 0.0)
        // One stage unmeasured: the priors for all (milliseconds and priors are different units).
        val prior = stageWeights(plan, mapOf(ReviewStage.ENGINE to 300_000L, ReviewStage.RECORD_VOICE to null))
        assertEquals(PRIOR_STAGE_WEIGHTS.getValue(ReviewStage.ENGINE), prior.getValue(ReviewStage.ENGINE), 0.0)
        assertEquals(PRIOR_STAGE_WEIGHTS.getValue(ReviewStage.RECORD_VOICE), prior.getValue(ReviewStage.RECORD_VOICE), 0.0)
        // A cached engine stage (0 ms) still has a weight, so the bar is defined.
        assertEquals(1.0, stageWeights(plan, mapOf(ReviewStage.ENGINE to 0L, ReviewStage.RECORD_VOICE to 5L)).getValue(ReviewStage.ENGINE), 0.0)
    }

    @Test
    fun theOverallBarWeighsEachStageAndNeverGoesBackOnASkip() {
        val weights = mapOf(ReviewStage.ENGINE to 3.0, ReviewStage.RECORD_VOICE to 1.0)
        val running = listOf(
            StageState(ReviewStage.ENGINE, StageStatus.CURRENT, done = 10, total = 20),
            StageState(ReviewStage.RECORD_VOICE, StageStatus.PENDING),
        )
        assertEquals(0.375f, overallFraction(running, weights), 1e-6f)
        val engineDone = running.finished(ReviewStage.ENGINE)
        assertEquals(StageStatus.CURRENT, engineDone[1].status)
        assertEquals(0.75f, overallFraction(engineDone, weights), 1e-6f)
        val halfVoice = engineDone.updated(ReviewStage.RECORD_VOICE) { it.copy(done = 5, total = 10) }
        assertEquals(0.875f, overallFraction(halfVoice, weights), 1e-6f)
        // Skipped counts as complete: the bar jumps forward, never back.
        val skipped = halfVoice.finished(ReviewStage.RECORD_VOICE, skipped = true)
        assertEquals(StageStatus.SKIPPED, skipped[1].status)
        assertEquals(1f, overallFraction(skipped, weights), 1e-6f)
    }

    @Test
    fun finishingAStageStartsTheNextPendingOneAndMarksItsCountComplete() {
        val stages = initialStageStates(listOf(ReviewStage.ENGINE, ReviewStage.POLISH_COMMENTARY, ReviewStage.WRITE_NARRATION))
        assertEquals(listOf(StageStatus.CURRENT, StageStatus.PENDING, StageStatus.PENDING), stages.map { it.status })
        val next = stages.updated(ReviewStage.ENGINE) { it.copy(done = 30, total = 41) }.finished(ReviewStage.ENGINE)
        assertEquals(41, next[0].done)
        assertEquals(listOf(StageStatus.DONE, StageStatus.CURRENT, StageStatus.PENDING), next.map { it.status })
        val last = next.finished(ReviewStage.POLISH_COMMENTARY).finished(ReviewStage.WRITE_NARRATION)
        assertTrue(last.all { it.status == StageStatus.DONE })
    }

    @Test
    fun theOverallTimeLeftIsHiddenUntilEveryRemainingStageHasAnEstimate() {
        val stages = listOf(
            StageState(ReviewStage.ENGINE, StageStatus.DONE, 41, 41),
            StageState(ReviewStage.WRITE_NARRATION, StageStatus.CURRENT, 3, 10),
            StageState(ReviewStage.RECORD_VOICE, StageStatus.PENDING),
        )
        assertEquals(70_000L, overallRemainingMs(stages, 10_000L, mapOf(ReviewStage.RECORD_VOICE to 60_000L)))
        assertNull(overallRemainingMs(stages, null, mapOf(ReviewStage.RECORD_VOICE to 60_000L)))
        assertNull(overallRemainingMs(stages, 10_000L, mapOf(ReviewStage.RECORD_VOICE to null)))
        // The last stage running: only its own time.
        assertEquals(5_000L, overallRemainingMs(stages.map { if (it.status == StageStatus.PENDING) it.copy(status = StageStatus.SKIPPED) else it }, 5_000L, emptyMap()))
    }

    @Test
    fun storedSpeedsEstimateAPendingStagePerGamePlyAndBlendNewMeasurements() {
        var speeds = ReviewSpeeds()
        assertNull(speeds.estimateMs(ReviewStage.RECORD_VOICE, 60))
        speeds = speeds.withStage(ReviewStage.RECORD_VOICE, measuredMs = 120_000L, plies = 60)
        assertEquals(2_000.0, speeds.msPerPly.getValue(ReviewStage.RECORD_VOICE), 1e-9)
        assertEquals(80_000L, speeds.estimateMs(ReviewStage.RECORD_VOICE, 40))
        // The next measurement is blended with the old (the mean), not a replacement.
        speeds = speeds.withStage(ReviewStage.RECORD_VOICE, measuredMs = 240_000L, plies = 60)
        assertEquals(3_000.0, speeds.msPerPly.getValue(ReviewStage.RECORD_VOICE), 1e-9)
        // A stage that did nothing (everything cached) measures nothing.
        assertEquals(speeds, speeds.withStage(ReviewStage.WRITE_NARRATION, measuredMs = 200L, plies = 60))
        // The engine is measured per node, not per ply, and only from enough positions.
        assertEquals(speeds, speeds.withStage(ReviewStage.ENGINE, 100_000L, 60))
        assertNull(speeds.withEngine(runMs = 10_000L, runNodes = 1_000_000L, runPositions = 2).engineMsPerNode)
        assertEquals(0.01, speeds.withEngine(runMs = 10_000L, runNodes = 1_000_000L, runPositions = 10).engineMsPerNode!!, 1e-12)
    }

    @Test
    fun theTrackerShowsTheStagesOwnLineFirstAndTheOverallOnceEverythingIsMeasured() {
        val speeds = ReviewSpeeds(msPerPly = mapOf(ReviewStage.RECORD_VOICE to 1_000.0))
        val tracker = ReviewTimeLeftTracker(speeds, plies = 60)
        var stages = listOf(
            StageState(ReviewStage.ENGINE, StageStatus.CURRENT, 3, 61),
            StageState(ReviewStage.RECORD_VOICE, StageStatus.PENDING),
        )
        // The engine has no honest estimate yet (F1: from 6 positions): both lines hidden.
        assertEquals(ExportTimeLeft.Hidden to ExportTimeLeft.Hidden, tracker.update(stages, engineRemainingMs = null))
        // The engine's estimate arrives: 4 min of engine + 60 s of recording (from the stored speed) = 5 min.
        val (overall, step) = tracker.update(stages, engineRemainingMs = 240_000L)
        assertEquals(ExportTimeLeft.Minutes(5), overall)
        assertEquals(ExportTimeLeft.Minutes(4), step)
        // The recording stage starts: its own line starts from nothing and is measured from its own items.
        stages = stages.finished(ReviewStage.ENGINE).updated(ReviewStage.RECORD_VOICE) { it.copy(done = 0, total = 40) }
        tracker.onStageProgress(ReviewStage.RECORD_VOICE, 0, 0L)
        val first = tracker.update(stages, null)
        assertEquals(ExportTimeLeft.Hidden, first.second)
        for (i in 1..4) tracker.onStageProgress(ReviewStage.RECORD_VOICE, i, i * 3_000L)
        stages = stages.updated(ReviewStage.RECORD_VOICE) { it.copy(done = 4) }
        // 3 s per segment x 36 left = 108 s: 2 min, rounded up.
        assertEquals(ExportTimeLeft.Minutes(2), tracker.update(stages, null).second)
        assertEquals(108_000L, tracker.stageRemainingMs(ReviewStage.RECORD_VOICE, 40))
    }

    @Test
    fun theCurrentStageIsTheOneRunning() {
        val run = ReviewRunProgress(
            stages = listOf(
                StageState(ReviewStage.ENGINE, StageStatus.DONE),
                StageState(ReviewStage.RECORD_VOICE, StageStatus.CURRENT, 2, 9),
            ),
        )
        assertEquals(ReviewStage.RECORD_VOICE, run.current?.stage)
    }
}
