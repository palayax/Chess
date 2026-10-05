package net.palaya.chessanalyzer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import net.palaya.chessanalyzer.ui.a11y.asHeading
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.palaya.chessanalyzer.R
import net.palaya.chessanalyzer.data.AnalysisService
import net.palaya.chessanalyzer.ui.model.AnalysisPhase
import net.palaya.chessanalyzer.ui.model.AnalysisProgress
import net.palaya.chessanalyzer.ui.theme.ChessAnalyzerTheme

/**
 * Shown while the engine/net are being prepared and the game is being analyzed move by
 * move. [progress] is expected to be a stream of updates from the (future) `:engine`
 * analysis pipeline; for now the caller drives it however it likes (a ViewModel StateFlow
 * once one exists).
 *
 * When [error] is non-null the same screen turns into an error state instead of the caller
 * stacking a dialog on top: a modal whose only button leaves the screen threw the user back to Home
 * with nothing to act on. Here they can [onRetry] (the game text is still registered, and cached
 * evals make a second attempt resume rather than restart) or go [onBack].
 */
@Composable
fun AnalysisProgressScreen(
    progress: AnalysisProgress,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null,
    error: AnalysisService.Failure? = null,
    onRetry: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
    if (error != null) {
        AnalysisErrorState(error = error, onRetry = onRetry, onBack = onBack, modifier = modifier)
        return
    }
    Scaffold(modifier = modifier) { innerPadding ->
        // A centred, scrollable column: centred when it fits, scrolling (never clipped) in landscape
        // or at a 2.0 font where the ring, the texts, the bar and Cancel are taller than the window.
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
            CircularProgressIndicator(
                progress = { progress.fractionComplete.coerceIn(0f, 1f) },
                // Decoration: the linear bar below carries the progress for TalkBack (two bars were two stops).
                modifier = Modifier.size(64.dp).clearAndSetSemantics { },
                color = MaterialTheme.colorScheme.primary,
                // A determinate ring draws only the done part; the track shows where it is going.
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                strokeWidth = 5.dp,
            )
            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = stringResource(R.string.progress_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.asHeading(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            val status = statusText(progress)
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(20.dp))

            LinearProgressIndicator(
                progress = { progress.fractionComplete.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    // "Move 5 of 23" instead of a bare percentage, and TalkBack announces it as it moves.
                    .semantics { stateDescription = status },
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )

            if (onCancel != null) {
                Spacer(modifier = Modifier.height(24.dp))
                OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(text = stringResource(R.string.progress_cancel))
                }
            }
        }
        }
    }
}

@Composable
private fun AnalysisErrorState(
    error: AnalysisService.Failure,
    onRetry: (() -> Unit)?,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
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
            // Retrying a lost game text cannot work (nothing is registered to re-run), so the
            // only honest action there is Back.
            if (onRetry != null && error != AnalysisService.Failure.GAME_TEXT_LOST && error != AnalysisService.Failure.SETUP_DAMAGED) {
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
        }
        }
    }
}

/** Which hint a failure gets. Raw exception text never reaches the screen. */
@StringRes
internal fun failureHintRes(failure: AnalysisService.Failure): Int = when (failure) {
    AnalysisService.Failure.NO_GAMES, AnalysisService.Failure.PARSE -> R.string.analysis_failed_hint
    AnalysisService.Failure.ENGINE_PREPARE, AnalysisService.Failure.ENGINE_START -> R.string.analysis_failed_engine
    AnalysisService.Failure.SETUP_STORAGE -> R.string.analysis_failed_setup_storage
    AnalysisService.Failure.SETUP_DAMAGED -> R.string.analysis_failed_setup_damaged
    AnalysisService.Failure.ANALYSIS -> R.string.analysis_failed_generic
    AnalysisService.Failure.GAME_TEXT_LOST -> R.string.analysis_failed_lost
}

@Composable
private fun statusText(progress: AnalysisProgress): String = when (progress.phase) {
    AnalysisPhase.PREPARING_ENGINE -> stringResource(R.string.progress_downloading_engine)
    AnalysisPhase.FIRST_RUN_SETUP -> stringResource(R.string.progress_first_run_setup)
    AnalysisPhase.ANALYZING_MOVES -> {
        val (done, total) = wholeMoveCounter(progress.currentMoveIndex, progress.totalMoves)
        stringResource(R.string.progress_analyzing_moves, done, total)
    }
    AnalysisPhase.DONE -> stringResource(R.string.progress_title)
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
            progress = AnalysisProgress(
                phase = AnalysisPhase.ANALYZING_MOVES,
                currentMoveIndex = 14,
                totalMoves = 32,
                fractionComplete = 0.44f,
            ),
            onCancel = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF302E2B)
@Composable
private fun AnalysisProgressScreenErrorPreview() {
    ChessAnalyzerTheme {
        AnalysisProgressScreen(
            progress = AnalysisProgress(phase = AnalysisPhase.PREPARING_ENGINE),
            error = AnalysisService.Failure.PARSE,
            onRetry = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF302E2B)
@Composable
private fun AnalysisProgressScreenFirstRunPreview() {
    ChessAnalyzerTheme {
        AnalysisProgressScreen(
            progress = AnalysisProgress(phase = AnalysisPhase.FIRST_RUN_SETUP, fractionComplete = 0.1f),
            onCancel = {},
        )
    }
}
