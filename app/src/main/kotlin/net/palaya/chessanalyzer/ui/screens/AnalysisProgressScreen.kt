package net.palaya.chessanalyzer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
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
import net.palaya.chessanalyzer.ui.model.AnalysisPhase
import net.palaya.chessanalyzer.ui.model.AnalysisProgress
import net.palaya.chessanalyzer.ui.theme.ChessAnalyzerTheme

/**
 * Shown while the engine/net are being prepared and the game is being analyzed move by
 * move. [progress] is expected to be a stream of updates from the (future) `:engine`
 * analysis pipeline; for now the caller drives it however it likes (a ViewModel StateFlow
 * once one exists).
 */
@Composable
fun AnalysisProgressScreen(
    progress: AnalysisProgress,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null,
) {
    Scaffold(modifier = modifier) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(
                progress = { progress.fractionComplete.coerceIn(0f, 1f) },
                modifier = Modifier.height(64.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 5.dp,
            )
            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = stringResource(R.string.progress_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = statusText(progress),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(20.dp))

            LinearProgressIndicator(
                progress = { progress.fractionComplete.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )

            if (onCancel != null) {
                Spacer(modifier = Modifier.height(24.dp))
                OutlinedButton(onClick = onCancel) {
                    Text(text = stringResource(R.string.progress_cancel))
                }
            }
        }
    }
}

@Composable
private fun statusText(progress: AnalysisProgress): String = when (progress.phase) {
    AnalysisPhase.PREPARING_ENGINE -> stringResource(R.string.progress_downloading_engine)
    AnalysisPhase.DOWNLOADING_NET -> stringResource(
        R.string.progress_downloading_net,
        formatBytes(progress.bytesDownloaded),
        formatBytes(progress.totalBytes),
    )
    AnalysisPhase.ANALYZING_MOVES -> stringResource(
        R.string.progress_analyzing_moves,
        progress.currentMoveIndex,
        progress.totalMoves,
    )
    AnalysisPhase.DONE -> stringResource(R.string.progress_title)
}

private fun formatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return "%.0f MB".format(mb)
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
private fun AnalysisProgressScreenDownloadingPreview() {
    ChessAnalyzerTheme {
        AnalysisProgressScreen(
            progress = AnalysisProgress(
                phase = AnalysisPhase.DOWNLOADING_NET,
                bytesDownloaded = 40_000_000,
                totalBytes = 79_000_000,
                fractionComplete = 0.5f,
            ),
        )
    }
}
