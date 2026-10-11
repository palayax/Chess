@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package net.palaya.chessanalyzer.ui.screens

import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.palaya.chessanalyzer.R
import net.palaya.chessanalyzer.data.AnalysisService
import net.palaya.chessanalyzer.ui.a11y.AppBarTitle
import net.palaya.chessanalyzer.ui.a11y.asHeading
import net.palaya.chessanalyzer.ui.model.AnalysisPhase
import net.palaya.chessanalyzer.ui.model.AnalysisProgress
import net.palaya.chessanalyzer.ui.model.ReviewRunProgress
import net.palaya.chessanalyzer.ui.model.ReviewStage
import net.palaya.chessanalyzer.ui.model.StageState
import net.palaya.chessanalyzer.ui.model.StageStatus
import net.palaya.chessanalyzer.ui.model.formatElapsed
import net.palaya.chessanalyzer.ui.model.thinkingDeeper
import net.palaya.chessanalyzer.ui.theme.ChessAnalyzerTheme
import net.palaya.chessanalyzer.video.ExportTimeLeft

/**
 * The one processing screen of a review (RF, owner's phone test 2026-10-11, items 5 to 7): every stage the game's
 * options asked for runs here, once, and the screen ends on the Summary.
 *
 * Top to bottom: ONE overall bar (stages weighted by their estimated time), the measured "About N min left" (hidden
 * until it can be honest; the current stage's own line meanwhile) with the elapsed clock, a checklist of the stages
 * (done / current / waiting / skipped, each with its own "N of M", Skip on the optional ones), then [whileYouWait],
 * which takes the rest of the screen (the game-insights panel), and Cancel pinned at the bottom.
 *
 * When [error] is non-null the same screen turns into an error state instead of the caller stacking a dialog on top:
 * the user can [onRetry] (the game text is still registered, and cached evals make a second attempt resume rather than
 * restart), go [onBack], or [onShareDetails] (the diagnostic log, F1).
 *
 * Design reference (chess.com's game review progress and its multi-step loaders, pattern only): one determinate bar with
 * a short status line, secondary facts below it in smaller muted text, the steps as a short list with a check per done
 * step, and something to look at while it works rather than an empty wait.
 */
@Composable
fun AnalysisProgressScreen(
    run: ReviewRunProgress,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null,
    error: AnalysisService.Failure? = null,
    onRetry: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    onShareDetails: (() -> Unit)? = null,
    /** D2c: "Set up" on the SETUP_REQUIRED error, to the Setup screen (the game waits for it). */
    onSetUp: (() -> Unit)? = null,
    /** Skip on an optional stage (2 to 4): what is done stays, the rest of that stage is not done. */
    onSkipStage: ((ReviewStage) -> Unit)? = null,
    /**
     * The "while you wait" slot (RF): placed under the progress area, it takes all the remaining space. The game-insights
     * panel goes here (its data: `AnalysisViewModel.processingGame` and `evalsSoFar`). Empty by default.
     */
    whileYouWait: @Composable () -> Unit = {},
) {
    if (error != null) {
        AnalysisErrorState(error = error, onRetry = onRetry, onBack = onBack, onShareDetails = onShareDetails, onSetUp = onSetUp, modifier = modifier)
        return
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { AppBarTitle(stringResource(R.string.progress_title)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (onCancel != null) {
                // Edge to edge: the bottom bar owns its insets.
                Surface(color = MaterialTheme.colorScheme.background) {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier
                            .navigationBarsPadding()
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .heightIn(min = 48.dp),
                    ) {
                        Text(text = stringResource(R.string.progress_cancel))
                    }
                }
            }
        },
    ) { innerPadding ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            val progressMax = maxHeight * 0.62f
            Column(modifier = Modifier.fillMaxSize()) {
                // The progress area scrolls inside its own share of the height (landscape, a 2.0 font), so the slot
                // below always keeps the rest of the screen.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = progressMax)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    OverallProgress(run)
                    Spacer(modifier = Modifier.height(16.dp))
                    StageChecklist(run = run, onSkipStage = onSkipStage)
                }
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) { whileYouWait() }
            }
        }
    }
}

/** The overall bar, the time left (whole run, or this step's while the whole run's cannot be measured) and the clock. */
@Composable
private fun OverallProgress(run: ReviewRunProgress) {
    val percent = (run.overallFraction * 100).toInt().coerceIn(0, 100)
    val overallCd = stringResource(R.string.cd_progress_overall, percent)
    LinearProgressIndicator(
        progress = { run.overallFraction.coerceIn(0f, 1f) },
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .semantics { stateDescription = overallCd },
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    )
    Spacer(modifier = Modifier.height(8.dp))
    val left = when (val t = run.overallTimeLeft) {
        ExportTimeLeft.Hidden -> when (val s = run.stageTimeLeft) {
            ExportTimeLeft.Hidden -> null
            ExportTimeLeft.LessThanMinute -> stringResource(R.string.progress_step_less_than_minute)
            is ExportTimeLeft.Minutes -> pluralStringResource(R.plurals.progress_step_minutes_left, s.minutes, s.minutes)
        }
        ExportTimeLeft.LessThanMinute -> stringResource(R.string.progress_less_than_minute)
        is ExportTimeLeft.Minutes -> pluralStringResource(R.plurals.progress_minutes_left, t.minutes, t.minutes)
    }
    if (left != null) {
        // Changes rarely: a polite live region. The elapsed clock below is not one (it ticks every second).
        Text(
            text = left,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    if (run.runStartedAtMs > 0L) {
        var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
        LaunchedEffect(Unit) {
            while (true) {
                now = SystemClock.elapsedRealtime()
                delay(1_000L)
            }
        }
        Text(
            text = stringResource(R.string.progress_elapsed, formatElapsed(now - run.runStartedAtMs)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val deeper = thinkingDeeper(run.engine, now)
        if (deeper != null && run.current?.stage == ReviewStage.ENGINE) {
            Text(
                text = stringResource(R.string.progress_thinking_deeper, deeper.first, deeper.second),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun StageChecklist(run: ReviewRunProgress, onSkipStage: ((ReviewStage) -> Unit)?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (stage in run.stages) {
            StageRow(stage = stage, engine = run.engine, onSkip = onSkipStage?.takeIf { stage.stage.skippable && stage.status == StageStatus.CURRENT })
        }
    }
}

@StringRes
internal fun stageLabelRes(stage: ReviewStage): Int = when (stage) {
    ReviewStage.ENGINE -> R.string.stage_engine
    ReviewStage.POLISH_COMMENTARY -> R.string.stage_polish
    ReviewStage.WRITE_NARRATION -> R.string.stage_write_narration
    ReviewStage.RECORD_VOICE -> R.string.stage_record_voice
}

/**
 * The count a stage shows: positions as whole moves for the engine (people count moves, not plies; Home says
 * "17 moves"), items for the others. Null while the total is not known.
 */
internal fun stageCount(stage: StageState): Pair<Int, Int>? {
    if (stage.total <= 0) return null
    return if (stage.stage == ReviewStage.ENGINE) wholeMoveCounter(stage.done, stage.total)
    else stage.done.coerceAtMost(stage.total) to stage.total
}

/** One checklist row: icon, the stage, its count; Skip on the running optional stage. One TalkBack stop plus Skip. */
@Composable
private fun StageRow(stage: StageState, engine: AnalysisProgress, onSkip: ((ReviewStage) -> Unit)?) {
    val label = stringResource(stageLabelRes(stage.stage))
    val status = stringResource(
        when (stage.status) {
            StageStatus.DONE -> R.string.stage_status_done
            StageStatus.SKIPPED -> R.string.stage_status_skipped
            StageStatus.CURRENT -> R.string.stage_status_current
            StageStatus.PENDING -> R.string.stage_status_pending
        },
    )
    val count = stageCount(stage)?.let { (d, t) -> stringResource(R.string.stage_count, d, t) }
        // Before the engine counts positions it is starting up ("Starting…").
        ?: if (stage.stage == ReviewStage.ENGINE && stage.status == StageStatus.CURRENT && engine.phase == AnalysisPhase.PREPARING_ENGINE) {
            stringResource(R.string.progress_downloading_engine)
        } else null
    val rowDescription = if (count != null && stage.status == StageStatus.CURRENT) {
        stringResource(R.string.cd_stage_row_count, label, status, count)
    } else {
        stringResource(R.string.cd_stage_row, label, status)
    }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clearAndSetSemantics { contentDescription = rowDescription },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                when (stage.status) {
                    StageStatus.DONE -> Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    StageStatus.SKIPPED -> Icon(Icons.Filled.RemoveCircleOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    StageStatus.PENDING -> Icon(Icons.Filled.RadioButtonUnchecked, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                    StageStatus.CURRENT -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = if (stage.status == StageStatus.CURRENT) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                    color = if (stage.status == StageStatus.PENDING) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                if (stage.status == StageStatus.CURRENT && count != null) {
                    Text(text = count, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (stage.status == StageStatus.SKIPPED) {
                    Text(text = status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (onSkip != null) {
            val skipCd = stringResource(R.string.cd_stage_skip, label)
            TextButton(
                onClick = { onSkip(stage.stage) },
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = skipCd },
            ) {
                Text(stringResource(R.string.stage_skip))
            }
        }
    }
}

@Composable
private fun AnalysisErrorState(
    error: AnalysisService.Failure,
    onRetry: (() -> Unit)?,
    onBack: (() -> Unit)?,
    onShareDetails: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onSetUp: (() -> Unit)? = null,
) {
    Scaffold(modifier = modifier) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(64.dp),
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.dialog_analysis_failed_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                // Announced when the error replaces the progress, so a TalkBack user is not left on a
                // screen that silently changed.
                modifier = Modifier.asHeading().semantics { liveRegion = LiveRegionMode.Assertive },
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(failureHintRes(error)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(24.dp))
            // A missing engine net is fixed by Setup, not by a retry (D2c): the one filled button is
            // "Set up", and the game waits on disk until the net is in.
            if (error == AnalysisService.Failure.SETUP_REQUIRED && onSetUp != null) {
                Button(onClick = onSetUp, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.analysis_set_up))
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
            // Retrying a lost game text cannot work (nothing is registered to re-run), and a retry
            // cannot install the engine data, so for those two the only other action is Back.
            if (onRetry != null && error != AnalysisService.Failure.GAME_TEXT_LOST && error != AnalysisService.Failure.SETUP_REQUIRED) {
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.analysis_try_again))
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
            if (onBack != null) {
                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.common_back))
                }
            }
            if (onShareDetails != null) {
                Spacer(modifier = Modifier.height(12.dp))
                // Sends the diagnostic log (F1): what failed and why, for the developer.
                TextButton(onClick = onShareDetails, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.analysis_share_details))
                }
            }
        }
        }
    }
}

/** Which hint a failure gets. Raw exception text never reaches the screen. */
@StringRes
internal fun failureHintRes(failure: AnalysisService.Failure): Int = when (failure) {
    AnalysisService.Failure.NO_GAMES, AnalysisService.Failure.PARSE -> R.string.analysis_failed_hint
    AnalysisService.Failure.ENGINE_PREPARE, AnalysisService.Failure.ENGINE_START -> R.string.analysis_failed_engine
    AnalysisService.Failure.SETUP_REQUIRED -> R.string.analysis_failed_setup_required
    AnalysisService.Failure.ANALYSIS -> R.string.analysis_failed_generic
    AnalysisService.Failure.GAME_TEXT_LOST -> R.string.analysis_failed_lost
}

/**
 * The engine searches every *position* (the start position plus one per ply), but people count
 * whole moves: Home says "17 moves" for a 33-ply game, so the progress screen must count to 17
 * too, not to 34. [positionsDone]/[positionsTotal] are the engine's counts; the result is
 * (whole moves done, whole moves in the game), with done never past the total.
 */
internal fun wholeMoveCounter(positionsDone: Int, positionsTotal: Int): Pair<Int, Int> {
    val total = (positionsTotal / 2).coerceAtLeast(1)
    val done = ((positionsDone.coerceAtLeast(0) + 1) / 2).coerceAtMost(total)
    return done to total
}

@Preview(showBackground = true, backgroundColor = 0xFF302E2B)
@Composable
private fun AnalysisProgressScreenPreview() {
    ChessAnalyzerTheme {
        AnalysisProgressScreen(
            run = ReviewRunProgress(
                stages = listOf(
                    StageState(ReviewStage.ENGINE, StageStatus.DONE, 33, 33),
                    StageState(ReviewStage.POLISH_COMMENTARY, StageStatus.SKIPPED, 2, 5),
                    StageState(ReviewStage.WRITE_NARRATION, StageStatus.CURRENT, 12, 40),
                    StageState(ReviewStage.RECORD_VOICE, StageStatus.PENDING),
                ),
                overallFraction = 0.64f,
                overallTimeLeft = ExportTimeLeft.Minutes(3),
            ),
            onCancel = {},
            onSkipStage = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF302E2B)
@Composable
private fun AnalysisProgressScreenErrorPreview() {
    ChessAnalyzerTheme {
        AnalysisProgressScreen(
            run = ReviewRunProgress(),
            error = AnalysisService.Failure.PARSE,
            onRetry = {},
            onBack = {},
        )
    }
}
